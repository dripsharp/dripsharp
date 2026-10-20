using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Filter;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Font;
using System.IO.Compression;

namespace PdfCarton.Benchmarks;

/// <summary>
/// /FlateDecode cost as the decoded size grows. Decode drives the public
/// FlateFilterDecoderStream over a synthetic zlib stream (about 1.45:1, like
/// font data) and drains it with 4 KiB reads. WholeFontLoad loads a saved PDF
/// that embeds LiberationSans-Regular (410 KiB, SIL OFL; PdfCarton's bundled
/// copy) whole, max(1, DecodedBytes / 256 KiB) times, and resolves every font
/// through PDResources.GetFont.
/// Building the inputs is setup and is not timed.
/// </summary>
[MemoryDiagnoser]
[GcServer(true)]
public class FlateBenchmarks
{
    private byte[] encoded = [];
    private sbyte[] pdf = [];
    private readonly sbyte[] readBuffer = new sbyte[4096];

    [Params(65536, 262144, 1048576, 4194304)]
    public int DecodedBytes { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        encoded = Compress(SyntheticPayload(DecodedBytes));
        if (Decode() != DecodedBytes)
            throw new InvalidOperationException("Flate decode lost bytes.");
        var copies = Math.Max(1, DecodedBytes / 262144);
        pdf = EmbedWholeFonts(copies);
        Console.WriteLine($"Flate input: decoded={DecodedBytes} encoded={encoded.Length} fonts={copies} pdf={pdf.Length}");
    }

    [Benchmark]
    public long Decode()
    {
        using var decoder = new FlateFilterDecoderStream(new MemoryStream(encoded, writable: false));
        long total = 0;
        int count;
        while ((count = decoder.Read(readBuffer, 0, readBuffer.Length)) > 0)
            total += count;
        return total;
    }

    [Benchmark]
    public int WholeFontLoad()
    {
        using var document = Loader.LoadPDF(pdf);
        var resources = document.GetPage(0).GetResources();
        var fonts = 0;
        foreach (COSName name in resources.GetFontNames())
            fonts += resources.GetFont(name) is PDType0Font ? 1 : 0;
        return fonts;
    }

    internal static byte[] SyntheticPayload(int length)
    {
        var payload = new byte[length];
        uint state = 0x9e3779b9;
        for (var index = 0; index < length; index++)
        {
            state = state * 1664525 + 1013904223;
            // Runs of a small alphabet with occasional repeats of earlier data.
            payload[index] = index > 64 && (state >> 28) < 6
                ? payload[index - 1 - (int)((state >> 8) % 64)]
                : (byte)(state >> 26);
        }
        return payload;
    }

    internal static byte[] Compress(byte[] payload)
    {
        using var output = new MemoryStream();
        using (var zlib = new ZLibStream(output, CompressionLevel.Optimal, leaveOpen: true))
            zlib.Write(payload);
        return output.ToArray();
    }

    private static sbyte[] EmbedWholeFonts(int copies)
    {
        using var document = new PDDocument();
        var page = new PDPage();
        document.AddPage(page);
        var resources = new PDResources();
        for (var copy = 0; copy < copies; copy++)
        {
            using var font = typeof(PDDocument).Assembly.GetManifestResourceStream(
                "org.apache.pdfbox.resources.ttf.LiberationSans-Regular.ttf")
                ?? throw new InvalidOperationException("PdfCarton font resource is missing.");
            resources.Add(PDType0Font.Load(document, font, false));
        }
        page.SetResources(resources);
        using var output = new MemoryStream();
        document.Save(output);
        return output.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    }
}
