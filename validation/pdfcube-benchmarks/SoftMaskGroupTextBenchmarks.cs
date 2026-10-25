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

// A US Letter page at 300 DPI (2550x3300) holding a transparency group, the
// full page (GroupHeight=792) or its bottom strip (80), whose 3 pt text near
// the bottom of the page is shown under an ExtGState luminosity soft mask
// with a small mask group. Every glyph then draws through a non-solid
// (SoftMask) paint onto the group bitmap. The Glyphs=2000 minus Glyphs=200
// time is the per-glyph cost; it should not depend on the group's area.
[MemoryDiagnoser]
[SimpleJob(RunStrategy.Monitoring, launchCount: 1, warmupCount: 1, iterationCount: 5)]
public class SoftMaskGroupTextBenchmarks
{
    private PDDocument document = null!;
    private PDFRenderer renderer = null!;

    [Params(200, 2000)]
    public int Glyphs { get; set; }

    [Params(80, 792)]
    public int GroupHeight { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        document = Loader.LoadPDF((sbyte[])(Array)Fixture(Glyphs, GroupHeight));
        renderer = new PDFRenderer(document);
        var image = renderer.RenderImage(0, 300f / 72f, ImageType.Rgb);
        // Setup-only output comparison across revisions; never timed.
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var bytes = image.GetPixelSpan();
        for (var row = 0; row < image.Height; row++)
            digest.AppendData(bytes.Slice(row * image.RowBytes, image.Width * image.BytesPerPixel));
        Console.WriteLine($"Soft-masked text in a group: glyphs={Glyphs}; group height={GroupHeight}; pixels={image.Width}x{image.Height}; " +
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

    // 100 glyphs per 3.5 pt line from y=72 down: 2000 glyphs stay on the page
    // and inside the mask's [36 0 200 76] box.
    internal static byte[] Fixture(int glyphs, int groupHeight)
    {
        const int perLine = 100;
        var text = new StringBuilder("/SM gs BT /F1 3 Tf 3.5 TL 40 75.5 Td");
        for (var shown = 0; shown < glyphs; shown += perLine)
            text.Append(" (").Append('x', Math.Min(perLine, glyphs - shown)).Append(") '");
        text.Append(" ET");
        static string Stream(string entries, string data) =>
            $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";
        var objects = new SortedDictionary<int, string>
        {
            [1] = "<< /Type /Catalog /Pages 2 0 R >>",
            [2] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            [3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                  "/Resources << /XObject << /G 5 0 R >> >> /Contents 4 0 R >>",
            [4] = Stream("", "q /G Do Q"),
            [5] = Stream($"/Type /XObject /Subtype /Form /BBox [0 0 612 {groupHeight}] " +
                         "/Group << /S /Transparency /CS /DeviceRGB >> /Resources << " +
                         "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> " +
                         "/ExtGState << /SM << /Type /ExtGState /SMask << /Type /Mask /S /Luminosity " +
                         "/G 6 0 R >> >> >> >>", text.ToString()),
            [6] = Stream("/Type /XObject /Subtype /Form /BBox [36 0 200 76] " +
                         "/Group << /S /Transparency /CS /DeviceGray >>", "0.8 g 36 0 164 76 re f"),
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
