using System;
using System.Diagnostics;
using DripSharp.Runtime;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using SkiaSharp;

internal static class Program
{
    private static void Check(bool value, string message)
    {
        if (!value) throw new Exception(message);
    }

    private static void Main(string[] args)
    {
        var regressionsOnly = Array.IndexOf(args, "--regressions-only") >= 0;
        if (args.Length == 0 || regressionsOnly)
        {
            PixelFormats();
            PremultipliedReads();
            ShapeAndClipBounds();
            SquareCapBounds();
            ManagedRaster();
            CachedDrawing();
            BoundedPaintAndBlend();
            BinaryBounds();
            Console.WriteLine("Skia pixel and rendering regressions passed.");
        }
        if (!regressionsOnly) Benchmark();
    }

    private static void PixelFormats()
    {
        foreach (var format in new[] { SKColorType.Bgra8888, SKColorType.Rgba8888, SKColorType.Gray8, SKColorType.Rgb565 })
        foreach (var alpha in new[] { SKAlphaType.Opaque, SKAlphaType.Unpremul, SKAlphaType.Premul })
        {
            if (format is SKColorType.Gray8 or SKColorType.Rgb565 && alpha != SKAlphaType.Opaque) continue;
            using var bitmap = new SKBitmap(new SKImageInfo(5, 3, format, alpha), 64);
            bitmap.GetPixelSpan().Fill(0x5a);
            var raster = PdfCartonFontCompat.GetRaster(bitmap);
            var bands = raster.NumberOfBands;
            var row = new int[3 * bands];
            for (var x = 0; x < 3; x++)
            {
                row[x * bands] = 96;
                if (bands > 1) { row[x * bands + 1] = 144; row[x * bands + 2] = 192; }
                if (bands == 4) row[x * bands + 3] = 128;
            }
            raster.SetPixels(1, 1, 3, 1, row);
            var result = raster.GetPixels(1, 1, 3, 1, null);
            for (var i = 0; i < row.Length; i++)
                Check(Math.Abs(result[i] - row[i]) <= (format == SKColorType.Rgb565 ? 7 : alpha == SKAlphaType.Premul ? 1 : 0),
                    $"{format}/{alpha}: channel {i}: {result[i]} != {row[i]}");
            Check(bitmap.GetPixelSpan()[64 + 5 * bitmap.BytesPerPixel] == 0x5a, "Row padding overwritten");
            Check(bitmap.GetPixelSpan()[0] == 0x5a, "Unrelated pixel overwritten");
            // A complete pixel must retain RGB when transitioning from transparent to translucent.
            if (bands == 4)
            {
                raster.SetPixel(0, 0, new[] { 96, 144, 192, 0 });
                raster.SetPixel(0, 0, new[] { 96, 144, 192, 128 });
                var c = bitmap.GetPixel(0, 0);
                Check(Math.Abs(c.Red - 96) <= 1 && c.Alpha == 128, "Complete pixel write lost RGB/alpha");
                raster.SetPixel(0, 0, new float[] { 64, 128, 192, 128 });
                Check(Math.Abs(bitmap.GetPixel(0, 0).Red - 64) <= 1, "Float pixel write lost RGB");
            }
            var threw = false;
            try { raster.SetPixel(5, 0, new int[bands]); } catch (IndexOutOfRangeException) { threw = true; }
            Check(threw, "Raster bounds must still be checked");
        }
    }

    private static void PremultipliedReads()
    {
        using var bitmap = new SKBitmap(new SKImageInfo(256, 256, SKColorType.Bgra8888, SKAlphaType.Premul));
        var bytes = bitmap.GetPixelSpan();
        for (var alpha = 0; alpha < 256; alpha++)
            for (var x = 0; x < 256; x++)
            {
                var offset = alpha * bitmap.RowBytes + x * 4;
                var component = (byte)(x * (alpha + 1) / 256);
                bytes[offset] = bytes[offset + 1] = bytes[offset + 2] = component;
                bytes[offset + 3] = (byte)alpha;
            }
        var samples = PdfCartonFontCompat.GetRaster(bitmap).GetPixels(0, 0, 256, 256, null);
        for (var y = 0; y < 256; y++)
            for (var x = 0; x < 256; x++)
                Check(samples[(y * 256 + x) * 4] == bitmap.GetPixel(x, y).Red,
                    $"Premultiplied read differs from Skia at {x}/{y}");
    }

    private static void ShapeAndClipBounds()
    {
        using var direct = PdfCartonFontCompat.CreateBitmap(128, 128, PdfCartonFontCompat.TYPE_INT_RGB);
        using var painted = PdfCartonFontCompat.CreateBitmap(128, 128, PdfCartonFontCompat.TYPE_INT_RGB);
        void Draw(SKBitmap bitmap, bool usePaint)
        {
            bitmap.Erase(SKColors.White);
            using var graphics = new PdfCartonGraphics2D(bitmap);
            graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING, PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
            using var clipBuilder = new SKPathBuilder();
            clipBuilder.AddOval(new SKRect(5, 5, 120, 115));
            using var clip = clipBuilder.Detach();
            graphics.SetClip(clip);
            graphics.Translate(60, 55);
            graphics.Rotate(0.4);
            graphics.Scale(1.2, 0.8);
            if (usePaint) graphics.SetPaint(new RecordingPaint());
            else graphics.SetColor(new SKColor(80, 120, 160));
            graphics.SetStroke(new JavaBasicStroke(9));
            graphics.Draw(new SKRect(-35, -25, 35, 25));
            graphics.FillOval(-20, -10, 40, 20);
            using var child = graphics.Create();
            child.ClipRect(-10, -10, 20, 20);
            child.Translate(4, 2);
            child.FillRect(-30, -30, 60, 60);
            using var inverseBuilder = new SKPathBuilder { FillType = SKPathFillType.InverseEvenOdd };
            inverseBuilder.AddRect(new SKRect(-6, -6, 6, 6));
            using var inverse = inverseBuilder.Detach();
            child.Fill(inverse);
        }
        Draw(direct, false);
        Draw(painted, true);
        Check(direct.GetPixelSpan().SequenceEqual(painted.GetPixelSpan()),
            "Bounded paint differs from native solid drawing with rotated strokes and curved clips");
    }

    private static void SquareCapBounds()
    {
        foreach (var join in new[] { JavaBasicStroke.JOIN_BEVEL, JavaBasicStroke.JOIN_ROUND, JavaBasicStroke.JOIN_MITER })
        {
            using var direct = PdfCartonFontCompat.CreateBitmap(160, 160, PdfCartonFontCompat.TYPE_INT_RGB);
            using var painted = PdfCartonFontCompat.CreateBitmap(160, 160, PdfCartonFontCompat.TYPE_INT_RGB);
            using var blended = PdfCartonFontCompat.CreateBitmap(160, 160, PdfCartonFontCompat.TYPE_INT_RGB);
            void Draw(SKBitmap bitmap, bool usePaint, bool useBlend)
            {
                bitmap.Erase(SKColors.White);
                using var graphics = new PdfCartonGraphics2D(bitmap);
                graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING, PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
                if (usePaint) graphics.SetPaint(new RecordingPaint());
                else graphics.SetColor(new SKColor(80, 120, 160));
                if (useBlend) graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
                graphics.SetStroke(new JavaBasicStroke(40, JavaBasicStroke.CAP_SQUARE, join, 1, null, 0));
                using var builder = new SKPathBuilder();
                builder.MoveTo(50, 50);
                builder.LineTo(100, 100);
                using var path = builder.Detach();
                graphics.Draw(path);
            }
            Draw(direct, false, false);
            Draw(painted, true, false);
            Draw(blended, false, true);
            Check(direct.GetPixel(24, 50) == new SKColor(80, 120, 160), "Square-cap reference pixel is missing");
            Check(direct.GetPixelSpan().SequenceEqual(painted.GetPixelSpan()),
                $"Bounded paint clips diagonal square caps with join {join}");
            Check(direct.GetPixelSpan().SequenceEqual(blended.GetPixelSpan()),
                $"Bounded blend clips diagonal square caps with join {join}");
        }
    }

    private static void ManagedRaster()
    {
        var raster = new JavaRaster(PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, 3, 2, 4);
        raster.SetPixel(1, 1, new[] { 20, 40, 60, 80 });
        var copy = raster.DeepCopy();
        raster.SetPixel(1, 1, new[] { 1, 2, 3, 4 });
        Check(copy.GetPixel(1, 1, (int[]?)null)[2] == 60, "DeepCopy aliases its source");
    }

    private static void CachedDrawing()
    {
        using var source = PdfCartonFontCompat.CreateBitmap(4, 4, PdfCartonFontCompat.TYPE_INT_ARGB);
        using var target = new SKBitmap(8, 4);
        using var canvas = new SKCanvas(target);
        var raster = PdfCartonFontCompat.GetRaster(source);
        raster.SetPixel(0, 0, new[] { 255, 0, 0, 255 });
        canvas.DrawBitmap(source, 0, 0, SKSamplingOptions.Default);
        raster.SetPixel(0, 0, new[] { 0, 255, 0, 255 });
        canvas.DrawBitmap(source, 4, 0, SKSamplingOptions.Default);
        Check(target.GetPixel(0, 0).Red == 255 && target.GetPixel(4, 0).Green == 255,
            "Skia drew cached pixels after raster mutation");
    }

    private static void BoundedPaintAndBlend()
    {
        using var bitmap = PdfCartonFontCompat.CreateBitmap(1024, 1024, PdfCartonFontCompat.TYPE_INT_RGB);
        bitmap.Erase(SKColors.White);
        using var graphics = new PdfCartonGraphics2D(bitmap);
        graphics.SetRenderingHint(PdfCartonRenderingHints.KEY_ANTIALIASING, PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.SetClip(200, 200, 100, 100);
        graphics.Translate(220, 230);
        var paint = new RecordingPaint();
        graphics.SetPaint(paint);
        graphics.FillRect(0, 0, 10, 10);
        Check(paint.Bounds.Width <= 14 && paint.Bounds.Height <= 14, $"Paint requested a page-sized raster: {paint.Bounds}");
        Check(bitmap.GetPixel(222, 232).Red == 80, "Translated paint is misplaced");
        Check(bitmap.GetPixel(100, 100) == SKColors.White, "Paint touched outside its clip");
        graphics.SetPaint((JavaColor)new SKColor(128, 255, 255));
        graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
        graphics.FillRect(0, 0, 10, 10);
        Check(Math.Abs(bitmap.GetPixel(222, 232).Red - 40) <= 1, "Bounded multiply blend is misplaced or incorrect");
        Check(bitmap.GetPixel(100, 100) == SKColors.White, "Composite touched unrelated pixels");
    }

    private static void BinaryBounds()
    {
        using var bitmap = PdfCartonFontCompat.CreateBitmap(512, 512, PdfCartonFontCompat.TYPE_BYTE_BINARY);
        using var graphics = new PdfCartonGraphics2D(bitmap);
        graphics.SetColor(SKColors.White);
        graphics.FillRect(100, 110, 3, 4);
        Check(bitmap.GetPixel(101, 111).Red == 255 && bitmap.GetPixel(0, 0).Red == 0, "Binary drawing bounds changed output");
    }

    private sealed class RecordingPaint : JavaPaint, JavaPaintContext
    {
        public SKRectI Bounds;
        private SKBitmap? image;
        public JavaPaintContext CreateContext(JavaColorModel colorModel, SKRectI deviceBounds, SKRect userBounds,
            SKMatrix transform, PdfCartonRenderingHints hints) => this;
        public int GetTransparency() => PdfCartonTransparency.OPAQUE;
        public JavaColorModel GetColorModel() => new(PdfCartonFontCompat.TYPE_INT_RGB);
        public JavaRaster GetRaster(int x, int y, int width, int height)
        {
            Bounds = new SKRectI(x, y, x + width, y + height);
            image?.Dispose();
            image = PdfCartonFontCompat.CreateBitmap(width, height, PdfCartonFontCompat.TYPE_INT_RGB);
            image.Erase(new SKColor(80, 120, 160));
            return PdfCartonFontCompat.GetRaster(image);
        }
        public void Dispose() => image?.Dispose();
    }

    private static void Benchmark()
    {
        using var bitmap = PdfCartonFontCompat.CreateBitmap(512, 512, PdfCartonFontCompat.TYPE_INT_ARGB);
        var raster = PdfCartonFontCompat.GetRaster(bitmap);
        var samples = new int[512 * 512 * 4];
        for (var i = 0; i < samples.Length; i += 4)
        { samples[i] = 80; samples[i + 1] = 120; samples[i + 2] = 160; samples[i + 3] = 255; }
        raster.SetPixels(0, 0, 512, 512, samples);
        var allocated = GC.GetAllocatedBytesForCurrentThread();
        var timer = Stopwatch.StartNew();
        for (var i = 0; i < 3; i++) raster.SetPixels(0, 0, 512, 512, samples);
        Console.WriteLine($"Raster writes: {timer.Elapsed.TotalMilliseconds:F1} ms, {GC.GetAllocatedBytesForCurrentThread() - allocated:N0} allocated bytes (3 x 512²)");
        using var page = PdfCartonFontCompat.CreateBitmap(1024, 1024, PdfCartonFontCompat.TYPE_INT_RGB);
        using var graphics = new PdfCartonGraphics2D(page);
        graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
        graphics.SetColor(SKColors.Red);
        allocated = GC.GetAllocatedBytesForCurrentThread();
        timer.Restart();
        for (var i = 0; i < 3; i++) graphics.FillRect(400, 400, 8, 8);
        Console.WriteLine($"Small blends: {timer.Elapsed.TotalMilliseconds:F1} ms, {GC.GetAllocatedBytesForCurrentThread() - allocated:N0} allocated bytes (3 x 8² on 1024²)");
    }
}
