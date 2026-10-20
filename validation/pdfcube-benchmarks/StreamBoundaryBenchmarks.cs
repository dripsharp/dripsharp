// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;

namespace PdfCarton.Benchmarks;

/// <summary>
/// Bytes crossing the Java/.NET stream boundary. The Copy benchmarks run
/// IOUtils.copy (4 KiB Java reads and writes) over PayloadBytes synthetic
/// bytes: FileStream to a MemoryStream with preallocated capacity, MemoryStream
/// to a RandomAccessOutputStream over a counting RandomAccessWrite, and
/// RandomAccessInputStream to a FileStream. CopyToFromRandomAccess is .NET's
/// Stream.CopyTo (81,920-byte requests) from a RandomAccessInputStream.
/// LoadAndSave loads a saved PDF holding one unfiltered PayloadBytes stream
/// through RandomAccessReadBufferedFile (FileChannel.read per 4 KiB page) and
/// saves it to Stream.Null (COSWriter copies each stream with IOUtils.copy).
/// Inputs are written to a temporary directory during setup, which is not timed.
/// </summary>
[MemoryDiagnoser]
public class StreamBoundaryBenchmarks
{
    private static readonly COSName PayloadKey = COSName.GetPDFName("Payload");
    private string directory = "";
    private string payloadFile = "";
    private string pdfFile = "";
    private string outputFile = "";
    private sbyte[] payload = [];
    private MemoryStream sink = new();

    [Params(8388608)]
    public int PayloadBytes { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        directory = Directory.CreateTempSubdirectory("pdfcarton-stream-boundary").FullName;
        payloadFile = Path.Combine(directory, "payload.bin");
        pdfFile = Path.Combine(directory, "payload.pdf");
        outputFile = Path.Combine(directory, "output.bin");
        var unsigned = FlateBenchmarks.SyntheticPayload(PayloadBytes);
        payload = (sbyte[])(Array)unsigned;
        File.WriteAllBytes(payloadFile, unsigned);
        sink = new MemoryStream(PayloadBytes);
        using (var document = new PDDocument())
        {
            document.SetDocumentId(20261020L);
            document.AddPage(new PDPage());
            var stream = document.GetDocument().CreateCOSStream();
            using (var output = stream.CreateOutputStream())
                output.Write(unsigned);
            document.GetDocumentCatalog().GetCOSObject().SetItem(PayloadKey, stream);
            using var file = File.Create(pdfFile);
            document.Save(file);
        }
        if (CopyFileToMemory() != PayloadBytes || !sink.GetBuffer().AsSpan(0, PayloadBytes).SequenceEqual(unsigned))
            throw new InvalidOperationException("IOUtils.copy changed the payload.");
        if (LoadAndSave() <= PayloadBytes)
            throw new InvalidOperationException("The saved PDF lost its payload.");
        Console.WriteLine($"Stream boundary input: payload={PayloadBytes} pdf={new FileInfo(pdfFile).Length}");
    }

    [GlobalCleanup]
    public void Cleanup() => Directory.Delete(directory, recursive: true);

    [Benchmark]
    public long CopyFileToMemory()
    {
        sink.SetLength(0);
        using var input = new FileStream(payloadFile, FileMode.Open, FileAccess.Read);
        return IOUtils.Copy(input, sink);
    }

    [Benchmark]
    public long CopyMemoryToRandomAccess()
    {
        var writer = new CountingWrite();
        using var input = new MemoryStream((byte[])(Array)payload, writable: false);
        using var output = new RandomAccessOutputStream(writer);
        IOUtils.Copy(input, output);
        return writer.Count;
    }

    [Benchmark]
    public long CopyRandomAccessToFile()
    {
        using var input = new RandomAccessInputStream(new RandomAccessReadBuffer(payload));
        using var output = new FileStream(outputFile, FileMode.Create, FileAccess.Write);
        return IOUtils.Copy(input, output);
    }

    [Benchmark]
    public long CopyToFromRandomAccess()
    {
        sink.SetLength(0);
        using var input = new RandomAccessInputStream(new RandomAccessReadBuffer(payload));
        input.CopyTo(sink);
        return sink.Length;
    }

    [Benchmark]
    public long LoadAndSave()
    {
        using var document = Loader.LoadPDF(new RandomAccessReadBufferedFile(pdfFile));
        var counter = new CountingStream();
        document.Save(counter);
        return counter.Count;
    }

    private sealed class CountingWrite : RandomAccessWrite
    {
        internal long Count;
        public void Write(int value) => Count++;
        public void Write(sbyte[] values) => Count += values.Length;
        public void Write(sbyte[] values, int offset, int length) => Count += length;
        public void Clear() => Count = 0;
        public void Dispose() { }
    }

    private sealed class CountingStream : Stream
    {
        internal long Count;
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => Count;
        public override long Position { get => Count; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => Count += count;
        public override void WriteByte(byte value) => Count++;
    }
}
