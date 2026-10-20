using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Engines;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

// Page-sized operations whose upstream code reads and writes a bitmap-backed
// raster one pixel at a time: an A4 /ImageMask at 300 DPI through
// SampledImageReader.getStencilImage (Raster.setPixel per masked pixel) and
// a full-page Multiply fill through BlendComposite (getDataElements and
// setDataElements per pixel). Each operation takes seconds before the
// per-raster pixel view, so the Monitoring strategy runs it a few times.
[MemoryDiagnoser]
[SimpleJob(RunStrategy.Monitoring, launchCount: 1, warmupCount: 1, iterationCount: 3)]
public class PerPixelRasterBenchmarks
{
    private const int Width = 2480;
    private const int Height = 3508;
    private PDDocument document = null!;
    private PDImageXObject stencil = null!;
    private SKBitmap page = null!;
    private PdfCartonGraphics2D graphics = null!;

    [GlobalSetup]
    public void Setup()
    {
        document = new PDDocument();
        // Alternating bits: every other pixel is painted, the rest are made
        // transparent with one setPixel each.
        var rowLength = (Width + 7) / 8;
        var data = new byte[rowLength * Height];
        Array.Fill(data, (byte)0x55);
        stencil = new PDImageXObject(document, new MemoryStream(data), null!, Width, Height, 1, null!);
        stencil.SetStencil(true);
        using (var image = stencil.GetStencilImage(JavaColor.White))
        {
            // Default /Decode [0 1]: set bits are transparent.
            if (image.GetPixel(0, 0) != SKColors.White || image.GetPixel(1, 0).Alpha != 0 ||
                image.GetPixel(Width - 1, Height - 1).Alpha != 0)
                throw new InvalidOperationException("Stencil setup failed its pixel check.");
        }

        page = PdfCartonFontCompat.CreateBitmap(Width, Height, PdfCartonFontCompat.TYPE_INT_ARGB);
        graphics = new PdfCartonGraphics2D(page);
        graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING,
            PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
        graphics.SetColor(new SKColor(128, 192, 224));
        ResetAndBlendPage();
        var color = page.GetPixel(Width / 2, Height / 2);
        if (Math.Abs(color.Red - 40) > 1 || Math.Abs(color.Green - 90) > 1 ||
            Math.Abs(color.Blue - 141) > 1 || color.Alpha != 255)
            throw new InvalidOperationException($"Page blend setup failed its pixel check: {color}.");
    }

    [Benchmark(Description = "getStencilImage 2480x3508 /ImageMask")]
    public void StencilImage()
    {
        using var image = stencil.GetStencilImage(JavaColor.White);
    }

    [Benchmark(Description = "Reset and Multiply-fill 2480x3508 page")]
    public void ResetAndBlendPage()
    {
        page.Erase(new SKColor(80, 120, 160));
        graphics.FillRect(0, 0, Width, Height);
    }

    [GlobalCleanup]
    public void Cleanup()
    {
        graphics?.Dispose();
        page?.Dispose();
        document?.Dispose();
    }
}
