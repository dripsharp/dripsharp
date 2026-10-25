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
        var bitmap = image!.GetImage(new SKRectI(0, 0, Width, Height), 1);
        return bitmap.Width;
    }

    [Benchmark]
    public int Subsampled2()
    {
        var bitmap = image!.GetImage(new SKRectI(0, 0, Width, Height), 2);
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

/// <summary>
/// Image sizes read through JavaImageReader: JPEGFactory.CreateFromByteArray
/// on a 4000x3000 (12 MP) Skia RGB JPEG at quality 90, which needs only the
/// frame header, and JPXFilter.Decode of a 512x512 RGB JP2 image,
/// which reads the image and then its width and height. Encoding is setup and
/// is not timed.
/// </summary>
[MemoryDiagnoser]
[GcServer(true)]
public class ImageDimensionBenchmarks
{
    private sbyte[] jpeg = [];
    private DripSharp.PdfCarton.Cos.COSStream? jpx;

    [GlobalSetup]
    public void Setup()
    {
        using var bitmap = new SKBitmap(new SKImageInfo(4000, 3000, SKColorType.Rgba8888, SKAlphaType.Opaque));
        var pixels = bitmap.GetPixelSpan();
        for (var index = 0; index < pixels.Length; index++)
            pixels[index] = (byte)(index * 7 / 3 + index / 4096);
        bitmap.NotifyPixelsChanged();
        using (var data = bitmap.Encode(SKEncodedImageFormat.Jpeg, 90))
        {
            var bytes = data.ToArray();
            jpeg = new sbyte[bytes.Length];
            Buffer.BlockCopy(bytes, 0, jpeg, 0, bytes.Length);
        }

        const int size = 512;
        var components = new int[3][];
        for (var component = 0; component < 3; component++)
        {
            components[component] = new int[size * size];
            for (var index = 0; index < size * size; index++)
                components[component][index] = (index * (component + 3) & 255) - 128;
        }
        var source = new CoreJ2K.Util.InterleavedImageSource(size, size, 3, 8, new bool[3], components);
        jpx = new DripSharp.PdfCarton.Cos.COSStream();
        using (var output = jpx.CreateRawOutputStream())
            output.Write(CoreJ2K.J2kImage.ToBytes(source, null, (CoreJ2K.j2k.util.ParameterList?)null));
        jpx.SetItem(DripSharp.PdfCarton.Cos.COSName.Filter, DripSharp.PdfCarton.Cos.COSName.JpxDecode);
    }

    [Benchmark]
    public int JpegCreateFromByteArray()
    {
        using var target = new PDDocument();
        return JPEGFactory.CreateFromByteArray(target, jpeg).GetWidth();
    }

    [Benchmark]
    public long JpxDecode()
    {
        using var encoded = jpx!.CreateRawInputStream();
        using var decoded = new MemoryStream();
        new DripSharp.PdfCarton.Filter.JPXFilter().Decode(encoded, decoded, jpx, 0);
        return decoded.Length;
    }
}
