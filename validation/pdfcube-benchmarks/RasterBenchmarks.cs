using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

[MemoryDiagnoser]
public class RasterBenchmarks
{
    private const int Size = 512;
    private SKBitmap bitmap = null!;
    private JavaRaster raster = null!;
    private int[] samples = null!;
    private int[] destination = null!;

    [GlobalSetup]
    public void Setup()
    {
        bitmap = PdfCartonFontCompat.CreateBitmap(Size, Size, PdfCartonFontCompat.TYPE_INT_ARGB);
        // Keep the historical component-at-a-time writer on equivalent opaque
        // input; transparent transitions had different behavior in that revision.
        bitmap.Erase(SKColors.Black);
        raster = PdfCartonFontCompat.GetRaster(bitmap);
        samples = new int[Size * Size * 4];
        destination = new int[samples.Length];
        for (var i = 0; i < samples.Length; i += 4)
        {
            samples[i] = (i / 4 * 17 + 80) & 255;
            samples[i + 1] = (i / 4 * 31 + 120) & 255;
            samples[i + 2] = (i / 4 * 47 + 160) & 255;
            samples[i + 3] = 255;
        }
        raster.SetPixels(0, 0, Size, Size, samples);
        if (!raster.GetPixels(0, 0, Size, Size, destination).SequenceEqual(samples))
            throw new InvalidOperationException("Raster setup failed its round-trip check.");
    }

    [Benchmark(Description = "Write 512x512 RGBA samples")]
    public void WritePixels() => raster.SetPixels(0, 0, Size, Size, samples);

    [Benchmark(Description = "Read 512x512 RGBA into reused buffer")]
    public int[] ReadPixelsReused() => raster.GetPixels(0, 0, Size, Size, destination);

    [Benchmark(Description = "Read 512x512 RGBA into new buffer")]
    public int[] ReadPixelsAllocated() => raster.GetPixels(0, 0, Size, Size, null);

    [GlobalCleanup]
    public void Cleanup() => bitmap?.Dispose();
}

[MemoryDiagnoser]
public class SmallBlendBenchmarks
{
    private const int GridSize = 64;
    private const int BlendCount = GridSize * GridSize;
    private SKBitmap page = null!;
    private PdfCartonGraphics2D graphics = null!;

    [GlobalSetup]
    public void Setup()
    {
        page = PdfCartonFontCompat.CreateBitmap(1024, 1024, PdfCartonFontCompat.TYPE_INT_RGB);
        graphics = new PdfCartonGraphics2D(page);
        graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING,
            PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
        graphics.SetColor(new SKColor(128, 192, 224));
        ResetDestination();
        graphics.FillRect(0, 0, 8, 8);
        var blended = page.GetPixel(2, 2);
        if (Math.Abs(blended.Red - 40) > 1 || Math.Abs(blended.Green - 90) > 1 ||
            Math.Abs(blended.Blue - 141) > 1 || page.GetPixel(12, 12) != new SKColor(80, 120, 160))
            throw new InvalidOperationException("Multiply blend setup failed its pixel check.");
    }

    // IterationSetup makes BDN invoke the workload once per iteration. Batching
    // amortizes harness overhead while every rectangle sees the same fresh input.
    [IterationSetup]
    public void ResetDestination() => page.Erase(new SKColor(80, 120, 160));

    [Benchmark(OperationsPerInvoke = BlendCount, Description = "8x8 multiply blend on 1024x1024 page")]
    public void SmallMultiplyBlend()
    {
        for (var y = 0; y < GridSize; y++)
            for (var x = 0; x < GridSize; x++)
                graphics.FillRect(x * 16, y * 16, 8, 8);
    }

    [GlobalCleanup]
    public void Cleanup()
    {
        graphics?.Dispose();
        page?.Dispose();
    }
}
