using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

// Unlike the large SmallBlendBenchmarks batch, this workload remains practical
// against the historical full-page composite implementation. The reset is timed
// on both revisions so BDN can choose an appropriate invocation count for each.
[MemoryDiagnoser]
public class BlendComparisonBenchmarks
{
    private SKBitmap page = null!;
    private PdfCartonGraphics2D graphics = null!;

    [GlobalSetup]
    public void Setup()
    {
        // The old TYPE_INT_RGB packed writer treated its unused alpha bits as
        // transparent, producing incorrect black output. Use opaque contents in
        // an ARGB destination so both revisions perform equivalent blending.
        page = PdfCartonFontCompat.CreateBitmap(1024, 1024, PdfCartonFontCompat.TYPE_INT_ARGB);
        graphics = new PdfCartonGraphics2D(page);
        graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING,
            PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
        graphics.SetColor(new SKColor(128, 192, 224));
        ResetAndBlend();
        var color = page.GetPixel(402, 402);
        if (Math.Abs(color.Red - 40) > 1 || Math.Abs(color.Green - 90) > 1 ||
            Math.Abs(color.Blue - 141) > 1 || page.GetPixel(0, 0) != new SKColor(80, 120, 160))
            throw new InvalidOperationException($"Historical blend comparison failed its pixel check: " +
                $"inside={color} (expected #ff285a8d +/-1/channel), " +
                $"outside={page.GetPixel(0, 0)} (expected #ff5078a0).");
    }

    [Benchmark]
    public void ResetAndBlend()
    {
        page.Erase(new SKColor(80, 120, 160));
        graphics.FillRect(400, 400, 8, 8);
    }

    [GlobalCleanup]
    public void Cleanup()
    {
        graphics?.Dispose();
        page?.Dispose();
    }
}
