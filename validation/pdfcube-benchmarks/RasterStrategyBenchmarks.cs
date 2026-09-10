using System.Runtime.InteropServices;
using System.Runtime.Intrinsics;
using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Configs;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCarton.Benchmarks;

// Experimental kernels only: BGRA8888, unpremultiplied, four RGBA int samples.
// Conversion and format/region validation are included in every invocation.
[MemoryDiagnoser]
[GroupBenchmarksBy(BenchmarkLogicalGroupRule.ByCategory)]
[CategoriesColumn]
public class RasterStrategyBenchmarks
{
    [Params(512)] public int Size { get; set; }
    private SKBitmap bitmap = null!;
    private SKBitmap staging = null!;
    private JavaRaster raster = null!;
    private int[] samples = null!;
    private int[] destination = null!;

    [GlobalSetup]
    public void Setup()
    {
        ValidateAll();
        bitmap = Create(Size, Size, 0);
        staging = Create(Size, Size, 0);
        raster = PdfCartonFontCompat.GetRaster(bitmap);
        samples = Samples(Size * Size);
        destination = new int[samples.Length];
        raster.SetPixels(0, 0, Size, Size, samples);
    }

    [Benchmark(Baseline = true), BenchmarkCategory("Write")]
    public void CurrentWrite() => raster.SetPixels(0, 0, Size, Size, samples);
    [Benchmark, BenchmarkCategory("Write")]
    public void ScalarWrite() => Write(bitmap, 0, 0, Size, Size, samples, false);
    [Benchmark, BenchmarkCategory("Write")]
    public void Vector128Write() => Write(bitmap, 0, 0, Size, Size, samples, true);
    [Benchmark, BenchmarkCategory("Write")]
    public void StagedNativeWrite() => NativeWrite(bitmap, staging, samples);
    [Benchmark(Baseline = true), BenchmarkCategory("Read")]
    public int[] CurrentRead() => raster.GetPixels(0, 0, Size, Size, destination);
    [Benchmark, BenchmarkCategory("Read")]
    public int[] ScalarRead() { Read(bitmap, 0, 0, Size, Size, destination, false); return destination; }
    [Benchmark, BenchmarkCategory("Read")]
    public int[] Vector128Read() { Read(bitmap, 0, 0, Size, Size, destination, true); return destination; }
    [Benchmark, BenchmarkCategory("Read")]
    public int[] StagedNativeRead() { NativeRead(bitmap, staging, destination); return destination; }
    [GlobalCleanup] public void Cleanup() { bitmap?.Dispose(); staging?.Dispose(); }

    // A persistent native BGRA staging bitmap costs another width*height*4 bytes.
    // The scalar RGBA conversion, pixmap acquisition, and native transfer are timed.
    private static void NativeWrite(SKBitmap image, SKBitmap scratch, int[] values)
    {
        Write(scratch, 0, 0, image.Width, image.Height, values, false);
        using var pixels = scratch.PeekPixels();
        if (!pixels.ReadPixels(image.Info, image.GetPixels(), image.RowBytes))
            throw new InvalidOperationException("Native write transfer failed.");
        image.NotifyPixelsChanged();
        GC.KeepAlive(scratch);
        GC.KeepAlive(image);
    }

    private static void NativeRead(SKBitmap image, SKBitmap scratch, int[] values)
    {
        using var pixels = image.PeekPixels();
        if (!pixels.ReadPixels(scratch.Info, scratch.GetPixels(), scratch.RowBytes))
            throw new InvalidOperationException("Native read transfer failed.");
        Read(scratch, 0, 0, image.Width, image.Height, values, false);
        GC.KeepAlive(scratch);
        GC.KeepAlive(image);
    }

    private static readonly Vector128<byte> SwapRedBlue = Vector128.Create(
        (byte)2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15);

    private static void Check(SKBitmap image, int x, int y, int width, int height, int[] values)
    {
        System.ArgumentNullException.ThrowIfNull(values);
        if (image.ColorType != SKColorType.Bgra8888 || image.AlphaType != SKAlphaType.Unpremul || !BitConverter.IsLittleEndian)
            throw new NotSupportedException("Experimental kernels require little-endian BGRA8888 Unpremul.");
        if (x < 0 || y < 0 || width < 0 || height < 0 || x > image.Width - width || y > image.Height - height)
            throw new IndexOutOfRangeException();
        if (values.Length < checked(width * height * 4)) throw new IndexOutOfRangeException();
    }

    public static void Write(SKBitmap image, int x, int y, int width, int height, int[] source, bool vector)
    {
        Check(image, x, y, width, height, source);
        var bytes = image.GetPixelSpan();
        var rowBytes = image.RowBytes;
        for (var row = 0; row < height; row++)
        {
            var output = bytes.Slice((y + row) * rowBytes + x * 4, width * 4);
            var input = source.AsSpan(row * width * 4, width * 4);
            var offset = 0;
            if (vector && Vector128.IsHardwareAccelerated)
                for (; offset <= input.Length - 16; offset += 16)
                {
                    // Narrowing deliberately truncates, matching JavaRaster's unchecked byte casts.
                    var a = Vector128.Narrow(Vector128.Create((ReadOnlySpan<int>)input.Slice(offset, 4)).AsUInt32(),
                        Vector128.Create((ReadOnlySpan<int>)input.Slice(offset + 4, 4)).AsUInt32());
                    var b = Vector128.Narrow(Vector128.Create((ReadOnlySpan<int>)input.Slice(offset + 8, 4)).AsUInt32(),
                        Vector128.Create((ReadOnlySpan<int>)input.Slice(offset + 12, 4)).AsUInt32());
                    Vector128.Shuffle(Vector128.Narrow(a, b), SwapRedBlue).CopyTo(output.Slice(offset, 16));
                }
            var packed = MemoryMarshal.Cast<byte, uint>(output);
            for (; offset < input.Length; offset += 4)
                packed[offset / 4] = (uint)(byte)input[offset + 2] | ((uint)(byte)input[offset + 1] << 8) |
                    ((uint)(byte)input[offset] << 16) | ((uint)(byte)input[offset + 3] << 24);
        }
        image.NotifyPixelsChanged();
    }

    public static void Read(SKBitmap image, int x, int y, int width, int height, int[] destination, bool vector)
    {
        Check(image, x, y, width, height, destination);
        var bytes = image.GetPixelSpan();
        var rowBytes = image.RowBytes;
        for (var row = 0; row < height; row++)
        {
            var input = bytes.Slice((y + row) * rowBytes + x * 4, width * 4);
            var output = destination.AsSpan(row * width * 4, width * 4);
            var offset = 0;
            if (vector && Vector128.IsHardwareAccelerated)
                for (; offset <= input.Length - 16; offset += 16)
                {
                    var rgba = Vector128.Shuffle(Vector128.Create((ReadOnlySpan<byte>)input.Slice(offset, 16)), SwapRedBlue);
                    var low = Vector128.WidenLower(rgba);
                    var high = Vector128.WidenUpper(rgba);
                    Vector128.WidenLower(low).AsInt32().CopyTo(output.Slice(offset, 4));
                    Vector128.WidenUpper(low).AsInt32().CopyTo(output.Slice(offset + 4, 4));
                    Vector128.WidenLower(high).AsInt32().CopyTo(output.Slice(offset + 8, 4));
                    Vector128.WidenUpper(high).AsInt32().CopyTo(output.Slice(offset + 12, 4));
                }
            var packed = MemoryMarshal.Cast<byte, uint>(input);
            for (; offset < input.Length; offset += 4)
            {
                var pixel = packed[offset / 4];
                output[offset] = (int)((pixel >> 16) & 255);
                output[offset + 1] = (int)((pixel >> 8) & 255);
                output[offset + 2] = (int)(pixel & 255);
                output[offset + 3] = (int)(pixel >> 24);
            }
        }
        GC.KeepAlive(image);
    }

    private static SKBitmap Create(int width, int height, int padding) =>
        new(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Unpremul), width * 4 + padding);

    private static int[] Samples(int count)
    {
        var values = new int[checked(count * 4)];
        for (var pixel = 0; pixel < count; pixel++)
        {
            values[pixel * 4] = (pixel * 37 + 19) & 255;
            values[pixel * 4 + 1] = (pixel * 53 + 71) & 255;
            values[pixel * 4 + 2] = (pixel * 97 + 101) & 255;
            values[pixel * 4 + 3] = (pixel % 7) switch { 0 => 0, 1 => 1, 2 => 127, 3 => 128, 4 => 254, 5 => 255, _ => (pixel * 61) & 255 };
        }
        return values;
    }

    public static void ValidateAll()
    {
        foreach (var width in new[] { 1, 3, 4, 5, 17, 512 })
        foreach (var vector in new[] { false, true })
        {
            const int height = 3;
            using var expected = Create(width + 2, height + 2, 12);
            using var actual = Create(width + 2, height + 2, 12);
            expected.GetPixelSpan().Fill(0xA5);
            actual.GetPixelSpan().Fill(0xA5);
            var values = Samples(width * height);
            // Exercise unchecked conversion in addition to all byte/alpha patterns.
            values[0] = -1;
            values[1] = 256;
            values[2] = 511;
            var reference = PdfCartonFontCompat.GetRaster(expected);
            reference.SetPixels(1, 1, width, height, values);
            Write(actual, 1, 1, width, height, values, vector);
            if (!expected.GetPixelSpan().SequenceEqual(actual.GetPixelSpan()))
                throw new InvalidOperationException($"Strategy write differs: width={width}, vector={vector}.");
            var read = Enumerable.Repeat(-12345, values.Length + 8).ToArray();
            Read(actual, 1, 1, width, height, read, vector);
            var referenceRead = reference.GetPixels(1, 1, width, height, null);
            if (!read.AsSpan(0, values.Length).SequenceEqual(referenceRead) || read.AsSpan(values.Length).ContainsAnyExcept(-12345))
                throw new InvalidOperationException($"Strategy read differs: width={width}, vector={vector}.");
        }
        foreach (var width in new[] { 1, 3, 4, 5, 17, 512 })
        {
            using var expected = Create(width, 3, 12);
            using var actual = Create(width, 3, 12);
            using var scratch = Create(width, 3, 0);
            expected.GetPixelSpan().Fill(0xA5);
            actual.GetPixelSpan().Fill(0xA5);
            var values = Samples(width * 3);
            var reference = PdfCartonFontCompat.GetRaster(expected);
            reference.SetPixels(0, 0, width, 3, values);
            NativeWrite(actual, scratch, values);
            if (!expected.GetPixelSpan().SequenceEqual(actual.GetPixelSpan()))
                throw new InvalidOperationException($"Native strategy write differs: width={width}.");
            var read = Enumerable.Repeat(-12345, values.Length + 8).ToArray();
            NativeRead(actual, scratch, read);
            if (!read.AsSpan(0, values.Length).SequenceEqual(reference.GetPixels(0, 0, width, 3, null)) ||
                read.AsSpan(values.Length).ContainsAnyExcept(-12345))
                throw new InvalidOperationException($"Native strategy read differs: width={width}.");
        }
    }
}
