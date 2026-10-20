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
/// than by graphics work. ProcessRealPage parses the same number of operators
/// written with real operands ("q 0.5 0 0 0.75 12.25 -3.125 cm Q"), so every
/// digit goes through Character.isDigit and every real through COSFloat and
/// Float.parseFloat. ParseRealToken isolates COSFloat(String) for one token.
/// StringKeyLookup isolates one JavaLinkedHashMap hit
/// with a non-interned string key, the lookup PDFStreamEngine.processOperator
/// performs for every operator. Building the document is setup and is not timed.
/// </summary>
[MemoryDiagnoser]
public class ContentStreamBenchmarks
{
    private PDDocument document = null!;
    private PDPage page = null!;
    private PDPage realPage = null!;
    private CountingEngine engine = null!;
    private readonly DripSharp.Runtime.JavaLinkedHashMap<string, int> operators = new();
    private readonly string probe = new(new[] { 'c', 'm' });
    private readonly string[] realTokens = { "12.345", "-0.5", "612", ".75", "1000.0625", "-3.125" };

    [Params(3000, 30000)]
    public int Operators { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        document = new PDDocument();
        page = AddPage("q 1 0 0 1 0 0 cm Q\n", out var length);
        realPage = AddPage("q 0.5 0 0 0.75 12.25 -3.125 cm Q\n", out var realLength);
        engine = new CountingEngine();
        if (ProcessPage() != Operators || ProcessRealPage() != Operators)
            throw new InvalidOperationException("Content stream lost operators.");
        foreach (var name in new[] { "q", "Q", "cm", "BT", "ET", "Tj", "Tf", "re", "f", "S" })
            operators[name] = operators.Count;
        Console.WriteLine($"Content stream: operators={Operators} bytes={length} real-bytes={realLength}");
    }

    private PDPage AddPage(string operatorsLine, out int length)
    {
        var added = new PDPage();
        document.AddPage(added);
        var content = new System.Text.StringBuilder();
        for (var index = 0; index < Operators / 3; index++)
            content.Append(operatorsLine);
        var stream = document.GetDocument().CreateCOSStream();
        using (var output = stream.CreateOutputStream())
        {
            var bytes = System.Text.Encoding.ASCII.GetBytes(content.ToString());
            output.Write(bytes, 0, bytes.Length);
        }
        added.GetCOSObject().SetItem(COSName.Contents, stream);
        length = content.Length;
        return added;
    }

    [Benchmark]
    public int ProcessPage()
    {
        engine.Processed = 0;
        engine.ProcessPage(page);
        return engine.Processed;
    }

    [Benchmark]
    public int ProcessRealPage()
    {
        engine.Processed = 0;
        engine.ProcessPage(realPage);
        return engine.Processed;
    }

    [Benchmark(OperationsPerInvoke = 1200)]
    public float ParseRealToken()
    {
        var total = 0f;
        for (var index = 0; index < 1200; index++)
            total += new COSFloat(realTokens[index % realTokens.Length]).FloatValue();
        return total;
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
