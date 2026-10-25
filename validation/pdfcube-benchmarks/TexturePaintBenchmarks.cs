// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Security.Cryptography;
using System.Text;
using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Engines;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Rendering;

namespace PdfCarton.Benchmarks;

// A US Letter page at 300 DPI (2550x3300) filled through a java.awt.TexturePaint:
// "tiling" fills the whole page with a 20 pt coloured tiling pattern
// (TilingPaint), and "smask-image" draws a 64x64 /Interpolate image over the
// whole page under an ExtGState luminosity soft mask (drawBufferedImage's
// TexturePaint wrapped in SoftMask).
[MemoryDiagnoser]
[SimpleJob(RunStrategy.Monitoring, launchCount: 1, warmupCount: 1, iterationCount: 5)]
public class TexturePaintBenchmarks
{
    private PDDocument document = null!;
    private PDFRenderer renderer = null!;

    [Params("tiling", "smask-image")]
    public string Fill { get; set; } = "tiling";

    [GlobalSetup]
    public void Setup()
    {
        document = Loader.LoadPDF((sbyte[])(Array)Fixture(Fill));
        renderer = new PDFRenderer(document);
        var image = renderer.RenderImage(0, 300f / 72f, ImageType.Rgb);
        // Setup-only output comparison across revisions; never timed.
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var bytes = image.GetPixelSpan();
        for (var row = 0; row < image.Height; row++)
            digest.AppendData(bytes.Slice(row * image.RowBytes, image.Width * image.BytesPerPixel));
        Console.WriteLine($"Texture paint fill: {Fill}; pixels={image.Width}x{image.Height}; " +
                          $"SHA256={Convert.ToHexString(digest.GetHashAndReset())}");
    }

    [Benchmark]
    public int RenderPage()
    {
        var image = renderer.RenderImage(0, 300f / 72f, ImageType.Rgb);
        return image.Width * image.Height;
    }

    [GlobalCleanup]
    public void Cleanup() => document?.Dispose();

    internal static byte[] Fixture(string fill)
    {
        static string Stream(string entries, string data) =>
            $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";
        var hex = new StringBuilder();
        for (var y = 0; y < 64; y++)
            for (var x = 0; x < 64; x++)
                hex.Append($"{x * 4:X2}{y * 4:X2}{(x + y) * 2:X2}");
        hex.Append('>');
        var objects = new SortedDictionary<int, string>
        {
            [1] = "<< /Type /Catalog /Pages 2 0 R >>",
            [2] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            [3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << " +
                  "/Pattern << /P1 5 0 R >> /XObject << /Im 6 0 R >> " +
                  "/ExtGState << /SM << /Type /ExtGState /SMask << /Type /Mask /S /Luminosity " +
                  "/G 7 0 R >> >> >> >> /Contents 4 0 R >>",
            [4] = Stream("", fill == "tiling"
                ? "/Pattern cs /P1 scn 0 0 612 792 re f"
                : "/SM gs 612 0 0 792 0 0 cm /Im Do"),
            [5] = Stream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 20 20] " +
                         "/XStep 20 /YStep 20 /Resources << >>",
                "1 0 0 rg 0 0 10 10 re f 0 0 1 rg 10 10 10 10 re f 0 0.5 0 rg 12 2 6 6 re f"),
            [6] = Stream("/Type /XObject /Subtype /Image /Width 64 /Height 64 /ColorSpace /DeviceRGB " +
                         "/BitsPerComponent 8 /Interpolate true /Filter /ASCIIHexDecode", hex.ToString()),
            [7] = Stream("/Type /XObject /Subtype /Form /BBox [0 0 612 792] " +
                         "/Group << /S /Transparency /CS /DeviceGray >>", "0.8 g 36 36 540 720 re f"),
        };
        var pdf = new StringBuilder("%PDF-1.7\n");
        var offsets = new Dictionary<int, int>();
        foreach (var (number, body) in objects)
        {
            offsets[number] = pdf.Length;
            pdf.Append($"{number} 0 obj\n{body}\nendobj\n");
        }
        var size = objects.Keys.Max() + 1;
        var xref = pdf.Length;
        pdf.Append($"xref\n0 {size}\n");
        for (var number = 0; number < size; number++)
            pdf.Append(offsets.TryGetValue(number, out var offset)
                ? $"{offset:D10} 00000 n \n"
                : "0000000000 65535 f \n");
        pdf.Append($"trailer\n<< /Size {size} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return Encoding.ASCII.GetBytes(pdf.ToString());
    }
}
