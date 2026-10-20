// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Security.Cryptography;
using System.Text;
using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Engines;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Rendering;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

// Drawing part of a large bitmap: DrawSubrect draws a 100x100 subrect of a
// 2480x3508 INT_ARGB page into a 100x100 image through PdfCartonGraphics2D;
// RenderGroups renders a US Letter page at 300 DPI with 40 small non-isolated
// /Multiply transparency groups, each of which draws its backdrop from the page.
[MemoryDiagnoser]
[SimpleJob(RunStrategy.Monitoring, launchCount: 1, warmupCount: 1, iterationCount: 5)]
public class BitmapDrawBenchmarks
{
    private SKBitmap page = null!;
    private SKBitmap target = null!;
    private PdfCartonGraphics2D graphics = null!;
    private PDDocument document = null!;
    private PDFRenderer renderer = null!;

    [GlobalSetup]
    public void Setup()
    {
        page = PdfCartonFontCompat.CreateBitmap(2480, 3508, PdfCartonFontCompat.TYPE_INT_ARGB);
        page.Erase(SKColors.Coral);
        target = PdfCartonFontCompat.CreateBitmap(100, 100, PdfCartonFontCompat.TYPE_INT_ARGB);
        graphics = new PdfCartonGraphics2D(target);
        document = Loader.LoadPDF((sbyte[])(Array)Fixture());
        renderer = new PDFRenderer(document);
        using var image = renderer.RenderImage(0, 300f / 72f, ImageType.Rgb);
        // Setup-only output comparison across revisions; never timed.
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var bytes = image.GetPixelSpan();
        for (var row = 0; row < image.Height; row++)
            digest.AppendData(bytes.Slice(row * image.RowBytes, image.Width * image.BytesPerPixel));
        Console.WriteLine($"Bitmap draw groups: pixels={image.Width}x{image.Height}; " +
                          $"SHA256={Convert.ToHexString(digest.GetHashAndReset())}");
    }

    [Benchmark(OperationsPerInvoke = 100)]
    public int DrawSubrect()
    {
        for (var i = 0; i < 100; i++)
            graphics.DrawImage(page, 0, 0, 100, 100, 1000 + i, 1000, 1100 + i, 1100, null);
        return target.Width;
    }

    [Benchmark]
    public int RenderGroups()
    {
        using var image = renderer.RenderImage(0, 300f / 72f, ImageType.Rgb);
        return image.Width * image.Height;
    }

    [GlobalCleanup]
    public void Cleanup()
    {
        graphics?.Dispose();
        target?.Dispose();
        page?.Dispose();
        document?.Dispose();
    }

    internal static byte[] Fixture()
    {
        static string Stream(string entries, string data) =>
            $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";
        var content = new StringBuilder("0.9 0.8 0.2 rg 0 0 612 792 re f\n");
        for (var group = 0; group < 40; group++)
            content.Append($"q 1 0 0 1 {50 + group % 8 * 64} {80 + group / 8 * 128} cm /G Do Q\n");
        var objects = new SortedDictionary<int, string>
        {
            [1] = "<< /Type /Catalog /Pages 2 0 R >>",
            [2] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            [3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                  "/Resources << /XObject << /G 5 0 R >> >> /Contents 4 0 R >>",
            [4] = Stream("", content.ToString()),
            [5] = Stream("/Type /XObject /Subtype /Form /BBox [0 0 30 30] /Group << /S /Transparency >> " +
                         "/Resources << /ExtGState << /M << /BM /Multiply >> >> >>",
                "/M gs 0.2 0.4 1 rg 0 0 30 30 re f"),
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
