using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

/// <summary>
/// PDImageXObject.GetImage for a 1600x1200 DCT image, RGB or gray, encoded
/// by Skia at quality 90. SampledImageReader always hands DCTFilter a source
/// region; FullImage asks for the whole image, which an explicit region
/// keeps out of the image cache, and Subsampled2 for every second pixel.
/// Building the image is setup and is not timed.
/// </summary>
[MemoryDiagnoser]
[GcServer(true)]
public class ImageReadBenchmarks
{
    private const int Width = 1600;
    private const int Height = 1200;
    private PDDocument? document;
    private PDImageXObject? image;

    [Params("rgb", "gray")]
    public string Color { get; set; } = "rgb";

    [GlobalSetup]
    public void Setup()
    {
        document = new PDDocument();
        image = JPEGFactory.CreateFromByteArray(document, Signed(Jpeg(Color == "gray")));
    }

    [GlobalCleanup]
    public void Cleanup() => document?.Dispose();

    [Benchmark]
    public int FullImage()
    {
        using var bitmap = image!.GetImage(new SKRectI(0, 0, Width, Height), 1);
        return bitmap.Width;
    }

    [Benchmark]
    public int Subsampled2()
    {
        using var bitmap = image!.GetImage(new SKRectI(0, 0, Width, Height), 2);
        return bitmap.Width;
    }

    private static byte[] Jpeg(bool gray)
    {
        using var bitmap = new SKBitmap(new SKImageInfo(
            Width, Height, gray ? SKColorType.Gray8 : SKColorType.Rgba8888, SKAlphaType.Opaque));
        var pixels = bitmap.GetPixelSpan();
        for (var index = 0; index < pixels.Length; index++)
            pixels[index] = (byte)(index * 7 / 3 + index / 4096);
        bitmap.NotifyPixelsChanged();
        using var data = bitmap.Encode(SKEncodedImageFormat.Jpeg, 90);
        return data.ToArray();
    }

    private static sbyte[] Signed(byte[] bytes)
    {
        var result = new sbyte[bytes.Length];
        Buffer.BlockCopy(bytes, 0, result, 0, bytes.Length);
        return result;
    }
}
