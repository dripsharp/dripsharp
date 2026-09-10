using System.Numerics;
using System.Runtime.CompilerServices;
using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Configs;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

// Benchmark-only kernel comparison. The Vector<T> kernel APIs are available on
// netstandard2.0; this executable harness and the Vector128 oracle use .NET 10.
[MemoryDiagnoser]
[GroupBenchmarksBy(BenchmarkLogicalGroupRule.ByCategory)]
[CategoriesColumn]
public class PortableRasterBenchmarks
{
    [Params(512)] public int Size { get; set; }
    private SKBitmap bitmap = null!;
    private JavaRaster raster = null!;
    private int[] samples = null!;
    private int[] destination = null!;

    [GlobalSetup]
    public void Setup()
    {
        Validate();
        bitmap = new SKBitmap(new SKImageInfo(Size, Size, SKColorType.Bgra8888, SKAlphaType.Unpremul));
        raster = PdfCartonFontCompat.GetRaster(bitmap);
        samples = Enumerable.Range(0, Size * Size * 4).Select(i => unchecked(i * 1299721)).ToArray();
        destination = new int[samples.Length];
        raster.SetPixels(0, 0, Size, Size, samples);
    }
    [Benchmark(Baseline = true), BenchmarkCategory("Read")]
    public int[] CurrentRead() => raster.GetPixels(0, 0, Size, Size, destination);
    [Benchmark, BenchmarkCategory("Read")]
    public int[] PortableRead() { Read(bitmap, 0, 0, Size, Size, destination, true); return destination; }
    [Benchmark, BenchmarkCategory("Read")]
    public int[] ScalarRead() { Read(bitmap, 0, 0, Size, Size, destination, false); return destination; }
    [Benchmark(Baseline = true), BenchmarkCategory("Write")]
    public void CurrentWrite() => raster.SetPixels(0, 0, Size, Size, samples);
    [Benchmark, BenchmarkCategory("Write")]
    public void PortableWrite() => Write(bitmap, 0, 0, Size, Size, samples, true);
    [Benchmark, BenchmarkCategory("Write")]
    public void ScalarWrite() => Write(bitmap, 0, 0, Size, Size, samples, false);
    [GlobalCleanup] public void Cleanup() => bitmap.Dispose();

    private static void Check(SKBitmap image, int x, int y, int width, int height, int[] values)
    {
        System.ArgumentNullException.ThrowIfNull(values);
        if (x < 0 || y < 0 || width < 0 || height < 0 || x > image.Width - width || y > image.Height - height)
            throw new IndexOutOfRangeException();
        if (values.Length < checked(width * height * 4)) throw new IndexOutOfRangeException();
    }
    public static void Read(SKBitmap image, int x, int y, int width, int height, int[] values, bool vector)
    {
        Check(image, x, y, width, height, values);
        using var access = new PortablePixels(image);
        if (!access.TryGetPixels(x, y, width, height, values, vector)) throw new NotSupportedException();
    }
    public static void Write(SKBitmap image, int x, int y, int width, int height, int[] values, bool vector)
    {
        Check(image, x, y, width, height, values);
        using var access = new PortablePixels(image);
        if (!access.TrySetPixels(x, y, width, height, values, vector)) throw new NotSupportedException();
    }
    public static void Validate()
    {
        // Cross every R/B pair; sweep G/A through all 256 values including zero.
        using (var image = new SKBitmap(new SKImageInfo(257, 256, SKColorType.Bgra8888, SKAlphaType.Unpremul), 257 * 4 + 8))
        {
            var input = new int[257 * 256 * 4];
            for (var p = 0; p < input.Length / 4; p++)
            {
                input[p * 4] = p >> 8;
                input[p * 4 + 1] = (p >> 8) ^ p;
                input[p * 4 + 2] = p;
                input[p * 4 + 3] = 255 - p;
            }
            var edge = new[] { int.MinValue, int.MaxValue, -1, -256, -257, 0, 1, 254, 255, 256, 65535, 65536 };
            edge.CopyTo(input, input.Length - edge.Length);
            var actual = new int[input.Length + 7];
            foreach (var vector in new[] { false, true })
            {
                Write(image, 0, 0, image.Width, image.Height, input, vector);
                Array.Fill(actual, -765);
                Read(image, 0, 0, image.Width, image.Height, actual, vector);
                for (var i = 0; i < input.Length; i++)
                    if (actual[i] != (input[i] & 255)) throw new Exception($"Portable roundtrip {vector}: {i}");
                if (actual.Skip(input.Length).Any(v => v != -765)) throw new Exception("Output tail overwritten");
                var scalar = new int[input.Length];
                RasterStrategyBenchmarks.Read(image, 0, 0, image.Width, image.Height, scalar, false);
                if (!scalar.SequenceEqual(actual.Take(input.Length))) throw new Exception("Scalar reference mismatch");
            }
        }
        foreach (var width in new[] { 0, 1, 3, 4, 5, 7, 8, 9, 17 })
        foreach (var vector in new[] { false, true })
        {
            using var image = new SKBitmap(new SKImageInfo(25, 5, SKColorType.Bgra8888, SKAlphaType.Unpremul), 25 * 4 + 8);
            image.GetPixelSpan().Fill(71);
            var input = Enumerable.Range(0, width * 3 * 4).Select(i => unchecked(i * 1299721)).ToArray();
            Write(image, 3, 1, width, 3, input, vector);
            var actual = new int[input.Length];
            Read(image, 3, 1, width, 3, actual, vector);
            if (!actual.SequenceEqual(input.Select(v => v & 255))) throw new Exception($"Subrect {width}/{vector}");
            var bytes = image.GetPixelSpan();
            for (var y = 0; y < image.Height; y++)
            for (var x = 0; x < image.RowBytes && y * image.RowBytes + x < bytes.Length; x++)
                if (!(y >= 1 && y < 4 && x >= 12 && x < (3 + width) * 4) && bytes[y * image.RowBytes + x] != 71)
                    throw new Exception("Outside region modified");
        }
    }
}

internal ref struct PortablePixels
{
    private readonly SKBitmap bitmap;
    private readonly Span<byte> bytes;
    private readonly SKImageInfo info;
    private readonly int rowBytes;
    private bool changed;
    internal PortablePixels(SKBitmap bitmap)
    {
        this.bitmap = bitmap;
        info = bitmap.Info;
        rowBytes = bitmap.RowBytes;
        bytes = bitmap.GetPixelSpan();
        changed = false;
    }
    public void Dispose()
    {
        if (changed) bitmap.NotifyPixelsChanged();
        GC.KeepAlive(bitmap);
    }
    // The caller validates the region and sample array before entering these bulk
    // paths. Other formats and alpha modes retain the per-pixel conversion above.
    internal bool TryGetPixels(int x, int y, int width, int height, int[] pixels, bool vector)
    {
        if (info.ColorType != SKColorType.Bgra8888 ||
            info.AlphaType != SKAlphaType.Unpremul || !BitConverter.IsLittleEndian)
            return false;
        if (width == 0 || height == 0) return true;
        // A retained JavaRaster can outlive a bitmap reallocation. Let the
        // original per-pixel path preserve its bounds and partial-write behavior.
        if (x > info.Width - width || y > info.Height - height) return false;

        // Portable arithmetic swaps bytes without a scalar stack buffer.
        var sampleCount = checked(width * 4);
        for (var row = 0; row < height; row++)
        {
            var input = bytes.Slice((y + row) * rowBytes + x * 4, sampleCount);
            var output = pixels.AsSpan(row * sampleCount, sampleCount);
            var offset = 0;
            if (vector && Vector.IsHardwareAccelerated)
            {
                for (; offset <= sampleCount - Vector<byte>.Count; offset += Vector<byte>.Count)
                {
                    var rgba = SwapRedBlue(Unsafe.ReadUnaligned<Vector<uint>>(ref input[offset]));
                    Vector.Widen(Vector.AsVectorByte(rgba),
                        out Vector<ushort> low, out Vector<ushort> high);
                    Vector.Widen(low, out Vector<uint> first, out Vector<uint> second);
                    Vector.Widen(high, out Vector<uint> third, out Vector<uint> fourth);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset]), first);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count]), second);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count * 2]), third);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count * 3]), fourth);
                }
            }
            for (; offset < sampleCount; offset += 4)
            {
                var pixel = Unsafe.ReadUnaligned<uint>(ref input[offset]);
                output[offset] = (int)((pixel >> 16) & 255);
                output[offset + 1] = (int)((pixel >> 8) & 255);
                output[offset + 2] = (int)(pixel & 255);
                output[offset + 3] = (int)(pixel >> 24);
            }
        }
        return true;
    }

    internal bool TrySetPixels(int x, int y, int width, int height, int[] pixels, bool vector)
    {
        if (info.ColorType != SKColorType.Bgra8888 ||
            info.AlphaType != SKAlphaType.Unpremul || !BitConverter.IsLittleEndian)
            return false;
        if (width == 0 || height == 0) return true;
        if (x > info.Width - width || y > info.Height - height) return false;

        var sampleCount = checked(width * 4);
        var byteMask = new Vector<uint>(255);
        for (var row = 0; row < height; row++)
        {
            var input = pixels.AsSpan(row * sampleCount, sampleCount);
            var output = bytes.Slice((y + row) * rowBytes + x * 4, sampleCount);
            // Mark before writing so Dispose also invalidates Skia after any
            // partially completed write, just as the per-pixel path does.
            changed = true;
            var offset = 0;
            if (vector && Vector.IsHardwareAccelerated)
            {
                for (; offset <= sampleCount - Vector<byte>.Count; offset += Vector<byte>.Count)
                {
                    // Mask before narrowing to preserve unchecked byte conversion
                    // for negative samples and values greater than 255.
                    var first = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset])) & byteMask;
                    var second = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count])) & byteMask;
                    var third = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count * 2])) & byteMask;
                    var fourth = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count * 3])) & byteMask;
                    var packed = Vector.Narrow(Vector.Narrow(first, second), Vector.Narrow(third, fourth));
                    Unsafe.WriteUnaligned(ref output[offset], SwapRedBlue(Vector.AsVectorUInt32(packed)));
                }
            }
            for (; offset < sampleCount; offset += 4)
            {
                var pixel = (uint)unchecked((byte)input[offset + 2]) |
                    ((uint)unchecked((byte)input[offset + 1]) << 8) |
                    ((uint)unchecked((byte)input[offset]) << 16) |
                    ((uint)unchecked((byte)input[offset + 3]) << 24);
                Unsafe.WriteUnaligned(ref output[offset], pixel);
            }
        }
        return true;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static Vector<uint> SwapRedBlue(Vector<uint> pixel)
    {
        // Every masked red value is below 2^24 and is exactly representable.
        // Scaling by 2^-16 produces an exact integer, so conversion cannot round.
        var red = Vector.AsVectorInt32(pixel & new Vector<uint>(0x00ff0000));
        var redToBlue = Vector.AsVectorUInt32(Vector.ConvertToInt32(
            Vector.ConvertToSingle(red) * new Vector<float>(1f / 65536f)));
        var blueToRed = (pixel & new Vector<uint>(255)) * new Vector<uint>(65536);
        return (pixel & new Vector<uint>(0xff00ff00)) | blueToRed | redToBlue;
    }
}
