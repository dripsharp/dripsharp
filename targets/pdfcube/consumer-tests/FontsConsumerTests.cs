using DripSharp.PdfCarton.Fonts.Util;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class FontsConsumerTests
{
    [Fact]
    public void BoundingBoxPreservesTranslatedGeometryBehavior()
    {
        var bounds = new BoundingBox(1, 2, 6, 10);

        Assert.Equal(5, bounds.GetWidth());
        Assert.Equal(8, bounds.GetHeight());
        Assert.True(bounds.Contains(3, 4));
        Assert.False(bounds.Contains(7, 4));
    }
}

// Bulk raster access must agree with the unchanged single-pixel operations.
// Run this class both normally and with DOTNET_EnableHWIntrinsic=0 to exercise
// the portable SIMD kernel and its scalar fallback through the public API.
public sealed class RasterTests
{
    [Fact]
    public void BgraBulkAccessPreservesEveryByteAndSample()
    {
        foreach (var width in Enumerable.Range(1, 35).Concat(new[] { 63, 64, 65, 255, 256, 257 }))
        foreach (var padding in new[] { 0, 4, 28 })
        {
            const int height = 5;
            using var expected = Bitmap(width + 2, height + 2, padding);
            using var actual = Bitmap(width + 2, height + 2, padding);
            expected.GetPixelSpan().Fill(0xa5);
            actual.GetPixelSpan().Fill(0xa5);
            var reference = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(expected);
            var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(actual);
            var samples = Samples(width * height);
            var pixel = new int[4];
            for (var row = 0; row < height; row++)
            for (var column = 0; column < width; column++)
            {
                Array.Copy(samples, (row * width + column) * 4, pixel, 0, 4);
                reference.SetPixel(column + 1, row + 1, pixel);
            }
            raster.SetPixels(1, 1, width, height, samples);
            Assert.Equal(expected.GetPixelSpan().ToArray(), actual.GetPixelSpan().ToArray());
            var destination = Enumerable.Repeat(-12345, samples.Length + 17).ToArray();
            Assert.Same(destination, raster.GetPixels(1, 1, width, height, destination));
            for (var i = 0; i < samples.Length; i++)
                Assert.Equal(unchecked((byte)samples[i]), destination[i]);
            Assert.All(destination.Skip(samples.Length), value => Assert.Equal(-12345, value));
            Assert.Equal(destination.Take(samples.Length), raster.GetPixels(1, 1, width, height, null));

            // Read independent raw BGRA bytes, including RGB under zero alpha.
            var bytes = actual.GetPixelSpan();
            for (var i = 0; i < bytes.Length; i++) bytes[i] = unchecked((byte)(i * 73 + 19));
            var read = raster.GetPixels(0, 0, actual.Width, actual.Height, null);
            for (var row = 0; row < actual.Height; row++)
            for (var column = 0; column < actual.Width; column++)
            {
                var source = row * actual.RowBytes + column * 4;
                var target = (row * actual.Width + column) * 4;
                Assert.Equal(bytes[source + 2], read[target]);
                Assert.Equal(bytes[source + 1], read[target + 1]);
                Assert.Equal(bytes[source], read[target + 2]);
                Assert.Equal(bytes[source + 3], read[target + 3]);
            }
        }
    }

    [Fact]
    public void BgraBulkConversionPreservesEveryRedBluePair()
    {
        using var bitmap = Bitmap(257, 256, 8);
        var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(bitmap);
        var samples = new int[bitmap.Width * bitmap.Height * 4];
        for (var pixel = 0; pixel < samples.Length / 4; pixel++)
        {
            samples[pixel * 4] = pixel >> 8;
            samples[pixel * 4 + 1] = (pixel >> 8) ^ pixel;
            samples[pixel * 4 + 2] = pixel;
            samples[pixel * 4 + 3] = 255 - pixel;
        }
        bitmap.GetPixelSpan().Fill(0xa5);
        raster.SetPixels(0, 0, bitmap.Width, bitmap.Height, samples);
        var bytes = bitmap.GetPixelSpan();
        for (var row = 0; row < bitmap.Height; row++)
        for (var column = 0; column < bitmap.Width; column++)
        {
            var input = (row * bitmap.Width + column) * 4;
            var output = row * bitmap.RowBytes + column * 4;
            Assert.Equal(unchecked((byte)samples[input + 2]), bytes[output]);
            Assert.Equal(unchecked((byte)samples[input + 1]), bytes[output + 1]);
            Assert.Equal(unchecked((byte)samples[input]), bytes[output + 2]);
            Assert.Equal(unchecked((byte)samples[input + 3]), bytes[output + 3]);
        }
        Assert.Equal(samples.Select(value => (int)unchecked((byte)value)),
            raster.GetPixels(0, 0, bitmap.Width, bitmap.Height, null));
        for (var row = 0; row < bitmap.Height - 1; row++)
            Assert.All(bytes.Slice(row * bitmap.RowBytes + bitmap.Width * 4, 8).ToArray(),
                value => Assert.Equal(0xa5, value));
    }

    [Fact]
    public void OtherBitmapFormatsAndAlphaModesKeepSinglePixelSemantics()
    {
        foreach (var format in new[] { SkiaSharp.SKColorType.Bgra8888, SkiaSharp.SKColorType.Rgba8888,
                     SkiaSharp.SKColorType.Gray8, SkiaSharp.SKColorType.Rgb565, SkiaSharp.SKColorType.Alpha8 })
        foreach (var alpha in new[] { SkiaSharp.SKAlphaType.Opaque, SkiaSharp.SKAlphaType.Unpremul,
                     SkiaSharp.SKAlphaType.Premul })
        {
            if (format is SkiaSharp.SKColorType.Gray8 or SkiaSharp.SKColorType.Rgb565 &&
                alpha != SkiaSharp.SKAlphaType.Opaque) continue;
            var info = new SkiaSharp.SKImageInfo(19, 5, format, alpha);
            using var expected = new SkiaSharp.SKBitmap(info, 128);
            using var actual = new SkiaSharp.SKBitmap(info, 128);
            expected.GetPixelSpan().Fill(0x5a);
            actual.GetPixelSpan().Fill(0x5a);
            var reference = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(expected);
            var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(actual);
            var bands = raster.NumberOfBands;
            var samples = Samples(17 * 3).Take(17 * 3 * bands).ToArray();
            var pixel = new int[bands];
            for (var row = 0; row < 3; row++)
            for (var column = 0; column < 17; column++)
            {
                Array.Copy(samples, (row * 17 + column) * bands, pixel, 0, bands);
                reference.SetPixel(column + 1, row + 1, pixel);
            }
            raster.SetPixels(1, 1, 17, 3, samples);
            Assert.Equal(expected.GetPixelSpan().ToArray(), actual.GetPixelSpan().ToArray());
            var read = raster.GetPixels(1, 1, 17, 3, null);
            for (var row = 0; row < 3; row++)
            for (var column = 0; column < 17; column++)
                Assert.Equal(reference.GetPixel(column + 1, row + 1, (int[]?)null),
                    read.Skip((row * 17 + column) * bands).Take(bands));
        }
    }

    [Fact]
    public void ManagedRastersKeepTheirStorageConversionsAndLayout()
    {
        foreach (var dataType in new[] { DripSharp.Runtime.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
                     DripSharp.Runtime.PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT,
                     DripSharp.Runtime.PdfCartonFontCompat.DATA_BUFFER_TYPE_INT })
        {
            var raster = new DripSharp.Runtime.JavaRaster(dataType, 19, 5, 4);
            var reference = new DripSharp.Runtime.JavaRaster(dataType, 19, 5, 4);
            var samples = Samples(17 * 3);
            for (var row = 0; row < 3; row++)
            for (var column = 0; column < 17; column++)
                reference.SetPixel(column + 1, row + 1,
                    samples.Skip((row * 17 + column) * 4).Take(4).ToArray());
            raster.SetPixels(1, 1, 17, 3, samples);
            Assert.Equal(reference.GetPixels(0, 0, 19, 5, null), raster.GetPixels(0, 0, 19, 5, null));
        }
        var buffer = new DripSharp.Runtime.JavaDataBufferByte(256);
        var interleaved = new DripSharp.Runtime.JavaRaster(buffer, 9, 3, 64, 5, new[] { 2, 1, 0, 3 });
        var values = Samples(7);
        interleaved.SetPixels(1, 1, 7, 1, values);
        Assert.Equal(values.Select(value => (int)unchecked((byte)value)), interleaved.GetPixels(1, 1, 7, 1, null));
        Assert.Equal(0, buffer.GetElement(64 + 5 + 4));
        Assert.Equal(0, buffer.GetElement(63));
    }

    [Fact]
    public void BoundsAndEmptyRectanglesPreserveBuffers()
    {
        using var bitmap = Bitmap(19, 5, 12);
        bitmap.GetPixelSpan().Fill(0xa5);
        var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(bitmap);
        var before = bitmap.GetPixelSpan().ToArray();
        foreach (var region in new[] { (-1, 0, 1, 1), (0, -1, 1, 1), (0, 0, -1, 1), (0, 0, 1, -1),
                     (19, 0, 1, 1), (0, 5, 1, 1), (18, 0, 2, 1), (0, 4, 1, 2),
                     (int.MaxValue, 0, 1, 1), (0, 0, int.MaxValue, 1) })
        {
            Assert.Throws<IndexOutOfRangeException>(() => raster.GetPixels(region.Item1, region.Item2, region.Item3, region.Item4, null));
            Assert.Throws<IndexOutOfRangeException>(() => raster.SetPixels(region.Item1, region.Item2, region.Item3, region.Item4, new int[400]));
        }
        Assert.Throws<DripSharp.Runtime.ArgumentNullException>(() => raster.SetPixels(0, 0, 1, 1, null!));
        Assert.Throws<IndexOutOfRangeException>(() => raster.SetPixels(0, 0, 17, 1, new int[67]));
        Assert.Throws<IndexOutOfRangeException>(() => raster.GetPixels(0, 0, 17, 1, new int[67]));
        foreach (var region in new[] { (19, 0, 0, 5), (0, 5, 19, 0), (19, 5, 0, 0) })
        {
            var output = new[] { -777 };
            Assert.Same(output, raster.GetPixels(region.Item1, region.Item2, region.Item3, region.Item4, output));
            Assert.Equal(-777, output[0]);
            raster.SetPixels(region.Item1, region.Item2, region.Item3, region.Item4, Array.Empty<int>());
        }
        Assert.Equal(before, bitmap.GetPixelSpan().ToArray());
    }

    [Fact]
    public void DrawingObservesBulkMutationAndTheBitmapRemainsUsable()
    {
        using var source = Bitmap(19, 3, 12);
        using var target = new SkiaSharp.SKBitmap(38, 3);
        using var canvas = new SkiaSharp.SKCanvas(target);
        var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(source);
        int[] Solid(int red, int green) => Enumerable.Range(0, 19 * 3)
            .SelectMany(_ => new[] { red, green, 0, 255 }).ToArray();
        raster.SetPixels(0, 0, 19, 3, Solid(255, 0));
        canvas.DrawBitmap(source, 0, 0, SkiaSharp.SKSamplingOptions.Default);
        raster.SetPixels(0, 0, 19, 3, Solid(0, 255));
        canvas.DrawBitmap(source, 19, 0, SkiaSharp.SKSamplingOptions.Default);
        Assert.Equal(SkiaSharp.SKColors.Red, target.GetPixel(0, 0));
        Assert.Equal(SkiaSharp.SKColors.Lime, target.GetPixel(19, 0));
        Assert.Equal(SkiaSharp.SKColors.Lime, target.GetPixel(37, 2));
        Assert.Equal(Solid(0, 255), raster.GetPixels(0, 0, 19, 3, null));
        source.Erase(SkiaSharp.SKColors.Blue);
        Assert.Equal(new[] { 0, 0, 255, 255 }, raster.GetPixels(0, 0, 1, 1, null));
    }

    [Fact]
    public void RetainedRasterHonorsReallocatedBitmapBounds()
    {
        using var bitmap = Bitmap(19, 3, 12);
        var raster = DripSharp.Runtime.PdfCartonFontCompat.GetRaster(bitmap);
        Assert.True(bitmap.TryAllocPixels(new SkiaSharp.SKImageInfo(4, 3,
            SkiaSharp.SKColorType.Bgra8888, SkiaSharp.SKAlphaType.Unpremul), 32));
        bitmap.GetPixelSpan().Fill(0xa5);
        Assert.Throws<DripSharp.Runtime.ArgumentOutOfRangeException>(() => raster.SetPixels(4, 0, 4, 1, Samples(4)));
        Assert.Throws<DripSharp.Runtime.ArgumentOutOfRangeException>(() => raster.GetPixels(4, 0, 4, 1, null));
        Assert.All(bitmap.GetPixelSpan().ToArray(), value => Assert.Equal(0xa5, value));
        Assert.Throws<DripSharp.Runtime.ArgumentOutOfRangeException>(() => raster.SetPixels(3, 0, 2, 1,
            new[] { 255, 0, 0, 255, 0, 255, 0, 255 }));
        Assert.Equal(SkiaSharp.SKColors.Red, bitmap.GetPixel(3, 0));
        Assert.Equal(0xa5, bitmap.GetPixelSpan()[16]);
        var output = Enumerable.Repeat(-1, 8).ToArray();
        Assert.Throws<DripSharp.Runtime.ArgumentOutOfRangeException>(() => raster.GetPixels(3, 0, 2, 1, output));
        Assert.Equal(new[] { 255, 0, 0, 255, -1, -1, -1, -1 }, output);
    }

    private static SkiaSharp.SKBitmap Bitmap(int width, int height, int padding) =>
        new(new SkiaSharp.SKImageInfo(width, height, SkiaSharp.SKColorType.Bgra8888,
            SkiaSharp.SKAlphaType.Unpremul), width * 4 + padding);

    private static int[] Samples(int pixels)
    {
        var edges = new[] { int.MinValue, -65537, -257, -256, -1, 0, 1, 127, 128, 254, 255, 256, 257, 511, 65535, 65536, int.MaxValue };
        return Enumerable.Range(0, pixels * 4)
            .Select(i => i % 3 == 0 ? edges[(i / 3) % edges.Length] : unchecked(i * 137 + 29)).ToArray();
    }
}
