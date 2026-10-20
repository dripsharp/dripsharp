using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Contentstream;
using DripSharp.PdfCarton.Contentstream.@Operator.State;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;

namespace PdfCarton.Benchmarks;

/// <summary>
/// Per-operator cost of parsing and dispatching a content stream. ProcessPage
/// drives the public PDFStreamEngine over an uncompressed page content stream of
/// Operators cheap operators ("q 1 0 0 1 0 0 cm Q"), so the timing is dominated by
/// tokenizing, Operator.getOperator and the engine's operator-map lookup rather
/// than by graphics work. StringKeyLookup isolates one JavaLinkedHashMap hit
/// with a non-interned string key, the lookup PDFStreamEngine.processOperator
/// performs for every operator. Building the document is setup and is not timed.
/// </summary>
[MemoryDiagnoser]
public class ContentStreamBenchmarks
{
    private PDDocument document = null!;
    private PDPage page = null!;
    private CountingEngine engine = null!;
    private readonly DripSharp.Runtime.JavaLinkedHashMap<string, int> operators = new();
    private readonly string probe = new(new[] { 'c', 'm' });

    [Params(3000, 30000)]
    public int Operators { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        document = new PDDocument();
        page = new PDPage();
        document.AddPage(page);
        var content = new System.Text.StringBuilder();
        for (var index = 0; index < Operators / 3; index++)
            content.Append("q 1 0 0 1 0 0 cm Q\n");
        var stream = document.GetDocument().CreateCOSStream();
        using (var output = stream.CreateOutputStream())
        {
            var bytes = System.Text.Encoding.ASCII.GetBytes(content.ToString());
            output.Write(bytes, 0, bytes.Length);
        }
        page.GetCOSObject().SetItem(COSName.Contents, stream);
        engine = new CountingEngine();
        if (ProcessPage() != Operators)
            throw new InvalidOperationException("Content stream lost operators.");
        foreach (var name in new[] { "q", "Q", "cm", "BT", "ET", "Tj", "Tf", "re", "f", "S" })
            operators[name] = operators.Count;
        Console.WriteLine($"Content stream: operators={Operators} bytes={content.Length}");
    }

    [Benchmark]
    public int ProcessPage()
    {
        engine.Processed = 0;
        engine.ProcessPage(page);
        return engine.Processed;
    }

    [Benchmark(OperationsPerInvoke = 1000)]
    public int StringKeyLookup()
    {
        var total = 0;
        for (var index = 0; index < 1000; index++)
            total += operators.TryGetValue(probe, out var value) ? value : -1;
        return total;
    }

    [GlobalCleanup]
    public void Cleanup() => document?.Dispose();

    private sealed class CountingEngine : PDFStreamEngine
    {
        internal int Processed;

        public CountingEngine()
        {
            AddOperator(new Save(this));
            AddOperator(new Restore(this));
            AddOperator(new Concatenate(this));
        }

        protected internal override void ProcessOperator(DripSharp.PdfCarton.Contentstream.@Operator.Operator op, IList<COSBase> operands)
        {
            Processed++;
            base.ProcessOperator(op, operands);
        }
    }
}
