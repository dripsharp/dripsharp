using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Filter;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdfparser;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using DripSharp.PdfCarton.Pdmodel.Graphics.Color;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using DripSharp.PdfCarton.Rendering;
using SkiaSharp;
using Xunit;
using Compat = DripSharp.Runtime;

namespace DripSharp.PdfCarton.Tests;

public sealed class PdfConsumerTests
{
    // Loading reads 4 KiB file-channel pages and saving copies each stream
    // with IOUtils.copy; both used to allocate temporary arrays per chunk,
    // several times the size of the file in all. What remains is mostly
    // RandomAccessReadBufferedFile's 1,000-page (4 MiB) cache, as upstream.
    [Fact]
    public void LoadingAndSavingALargeFileAllocatesFarLessThanItsSize()
    {
        const int size = 16 << 20;
        var payload = new byte[size];
        for (var index = 0; index < size; index++)
            payload[index] = unchecked((byte)(index * 7 + (index >> 10)));
        var path = Path.GetTempFileName();
        try
        {
            using (var document = new PDDocument())
            {
                document.AddPage(new PDPage());
                var stream = document.GetDocument().CreateCOSStream();
                using (var output = stream.CreateOutputStream())
                    output.Write(payload);
                document.GetDocumentCatalog().GetCOSObject()
                    .SetItem(COSName.GetPDFName("Payload"), stream);
                using var file = File.Create(path);
                document.Save(file);
            }

            long Save(Stream output)
            {
                using var document = Loader.LoadPDF(new RandomAccessReadBufferedFile(path));
                document.Save(output);
                return output.Length;
            }

            var saved = new MemoryStream(2 * size);
            Save(saved);
            saved.SetLength(0);
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            var length = Save(saved);
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;

            Assert.True(length > size);
            Assert.True(allocated < 6 << 20, $"Load and save allocated {allocated} bytes for {length}.");
            using var reloaded = Loader.LoadPDF(new RandomAccessReadBuffer(
                (sbyte[])(Array)saved.ToArray()));
            using var input = reloaded.GetDocumentCatalog().GetCOSObject()
                .GetCOSStream(COSName.GetPDFName("Payload")).CreateInputStream();
            var roundTrip = new MemoryStream();
            input.CopyTo(roundTrip);
            Assert.Equal(payload, roundTrip.ToArray());
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void DocumentCanBeConstructedSavedAndConsumedAgain()
    {
        string file = Path.Combine(
            Path.GetTempPath(),
            "pdfcarton-consumer-" + Guid.NewGuid().ToString("N") + ".pdf");
        try
        {
            using (var document = new PDDocument())
            {
                document.GetDocumentInformation().SetTitle("Product checkout");
                document.AddPage(new PDPage(PDRectangle.A4));
                document.Save(file);
            }

            using var reopened = Loader.LoadPDF(new FileInfo(file));
            Assert.Equal(1, reopened.GetNumberOfPages());
            Assert.Equal(
                "Product checkout",
                reopened.GetDocumentInformation().GetTitle());
            Assert.True(new FileInfo(file).Length > 0);
        }
        finally
        {
            File.Delete(file);
        }
    }

    // OWL-959: an RGB JPEG tagged /ICCBased with the matrix/TRC profile Skia
    // embeds used to allocate kilobytes per pixel while converting colors.
    [Fact]
    public void IccBasedRgbJpegRendersWithoutPerPixelAllocations()
    {
        const int width = 600;
        const int height = 400;
        var jpeg = IccRenderFixtures.SkiaJpeg(width, height);
        var pdf = IccRenderFixtures.ImagePdf(width, height, document =>
        {
            var image = JPEGFactory.CreateFromByteArray(document, IccRenderFixtures.Signed(jpeg));
            image.SetColorSpace(IccRenderFixtures.IccSpace(
                document, IccRenderFixtures.JpegIccProfile(jpeg), 3));
            return image;
        });

        var first = IccRenderFixtures.Render(pdf);
        var allocated = GC.GetAllocatedBytesForCurrentThread();
        var second = IccRenderFixtures.Render(pdf);
        allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;

        // Decoding needs O(pixels) buffers (a few bytes per sample); per-pixel
        // color arrays, curves and closures cost thousands of bytes per pixel.
        Assert.True(
            allocated < 128L * width * height,
            $"Rendering allocated {allocated} bytes for {width * height} pixels.");
        Assert.Equal(first, second);
    }

    // Expected colors were rendered by Apache PDFBox 3.0.8 (Java, LCMS) from
    // these fixtures; the tolerance absorbs CMM interpolation differences.
    [Fact]
    public void IccBasedColorsMatchPdfBoxAcrossProfilesAndConcurrentRenders()
    {
        var rgbProfile = IccRenderFixtures.JpegIccProfile(IccRenderFixtures.SkiaJpeg(16, 16));
        var cases = new (string Name, byte[] Pdf, int[][] Expected, int Tolerance)[]
        {
            ("matrix/TRC RGB", IccRenderFixtures.RgbPatches(rgbProfile),
                [[255, 0, 0], [0, 255, 0], [0, 0, 255], [128, 128, 128], [200, 150, 50]], 2),
            ("gray kTRC", IccRenderFixtures.GrayPatches(IccRenderFixtures.GrayProfile()),
                [[0, 0, 0], [62, 62, 62], [129, 129, 129], [255, 255, 255]], 2),
            ("CMYK LUT", IccRenderFixtures.CmykPatches(),
                [[0, 174, 239], [236, 0, 140], [255, 242, 0], [35, 31, 32], [182, 198, 215]], 4),
            ("invalid profile falls back to DeviceRGB",
                IccRenderFixtures.RgbPatches(IccRenderFixtures.Corrupt(rgbProfile)),
                [[255, 0, 0], [0, 255, 0], [0, 0, 255], [128, 128, 128], [200, 150, 50]], 0),
        };

        var sequential = cases.Select(test => IccRenderFixtures.Render(test.Pdf)).ToArray();
        for (var index = 0; index < cases.Length; index++)
        {
            var (name, _, expected, tolerance) = cases[index];
            var patches = IccRenderFixtures.PatchColors(sequential[index], expected.Length);
            for (var patch = 0; patch < expected.Length; patch++)
            {
                for (var channel = 0; channel < 3; channel++)
                {
                    Assert.True(
                        Math.Abs(patches[patch][channel] - expected[patch][channel]) <= tolerance,
                        $"{name} patch {patch}: got [{string.Join(", ", patches[patch])}], " +
                        $"expected [{string.Join(", ", expected[patch])}] +/- {tolerance}.");
                }
            }
        }

        // Concurrent renders share cached transforms (DeviceCMYK's profile is
        // process-wide) and interleave distinct profiles; every result must
        // equal the sequential one.
        var concurrent = new byte[cases.Length * 6][];
        Parallel.For(0, concurrent.Length, new ParallelOptions { MaxDegreeOfParallelism = 8 },
            index => concurrent[index] = IccRenderFixtures.Render(cases[index % cases.Length].Pdf));
        for (var index = 0; index < concurrent.Length; index++)
            Assert.Equal(sequential[index % cases.Length], concurrent[index]);
    }

    // Two RGB profiles with different TRCs (Skia's sRGB and a gamma 1.8
    // matrix/TRC profile) must keep separate transforms, including when one
    // loaded color space is shared by concurrent conversions.
    [Fact]
    public void IccTransformsStaySeparateAcrossSharedProfilesAndThreads()
    {
        var skia = IccRenderFixtures.JpegIccProfile(IccRenderFixtures.SkiaJpeg(16, 16));
        var gamma = IccRenderFixtures.RgbGammaProfile();
        var skiaPdf = IccRenderFixtures.RgbPatches(skia);
        var gammaPdf = IccRenderFixtures.RgbPatches(gamma);

        // Apache PDFBox 3.0.8 renders the gamma 1.8 patches as these colors.
        int[][] gammaExpected =
            [[255, 0, 0], [0, 255, 0], [0, 0, 255], [146, 146, 146], [210, 167, 65]];
        var interleaved = new[] { skiaPdf, gammaPdf, skiaPdf, gammaPdf }
            .Select(IccRenderFixtures.Render).ToArray();
        Assert.Equal(interleaved[0], interleaved[2]);
        Assert.Equal(interleaved[1], interleaved[3]);
        var patches = IccRenderFixtures.PatchColors(interleaved[1], gammaExpected.Length);
        for (var patch = 0; patch < gammaExpected.Length; patch++)
            for (var channel = 0; channel < 3; channel++)
                Assert.True(
                    Math.Abs(patches[patch][channel] - gammaExpected[patch][channel]) <= 2,
                    $"gamma 1.8 patch {patch}: got [{string.Join(", ", patches[patch])}].");

        using var skiaDocument = Loader.LoadPDF(IccRenderFixtures.Signed(skiaPdf));
        using var gammaDocument = Loader.LoadPDF(IccRenderFixtures.Signed(gammaPdf));
        var spaces = new[]
        {
            IccRenderFixtures.ImageColorSpace(skiaDocument),
            IccRenderFixtures.ImageColorSpace(gammaDocument)
        };
        var inputs = Enumerable.Range(0, 64)
            .Select(index => new[] { index / 63f, (63 - index) / 63f, (index * 5 % 64) / 63f })
            .ToArray();
        var sequential = spaces
            .Select(space => inputs.Select(input => space.ToRGB(input)).ToArray())
            .ToArray();
        Assert.NotEqual(sequential[0][20], sequential[1][20]);
        Parallel.For(0, 64, new ParallelOptions { MaxDegreeOfParallelism = 8 }, task =>
        {
            var space = task % 2;
            for (var index = 0; index < inputs.Length; index++)
                Assert.Equal(sequential[space][index], spaces[space].ToRGB(inputs[index]));
        });
    }

    // A color space subclass can only override the public array conversions;
    // per-pixel conversion and rendering must still honor those overrides.
    [Fact]
    public void ColorConversionHonorsPublicColorSpaceOverrides()
    {
        using var source = IccRenderFixtures.Bitmap(SKAlphaType.Opaque,
            new SKColor(10, 20, 30), new SKColor(200, 100, 50));

        using var fromRgb = new DripSharp.Runtime.JavaColorConvertOp(new ConstantFromRgbSpace())
            .Filter(source, null);
        Assert.All(IccRenderFixtures.Colors(fromRgb),
            color => Assert.Equal(new SKColor(64, 128, 191), color));

        using var toRgb = new DripSharp.Runtime.JavaColorConvertOp(new RedToRgbSpace())
            .Filter(source, null);
        Assert.All(IccRenderFixtures.Colors(toRgb),
            color => Assert.Equal(new SKColor(255, 0, 0), color));
    }

    // Converting into a caller-supplied unpremultiplied ARGB bitmap writes
    // its native pixels directly; alpha and the colors under it must survive.
    [Fact]
    public void ColorConversionPreservesAlphaInDirectDestinations()
    {
        SKColor[] colors =
            [new(10, 20, 30, 0), new(40, 50, 60, 128), new(70, 80, 90, 255)];
        using var source = IccRenderFixtures.Bitmap(SKAlphaType.Unpremul, colors);
        using var destination = IccRenderFixtures.Bitmap(SKAlphaType.Unpremul,
            new SKColor(0, 0, 0, 0), new SKColor(0, 0, 0, 0), new SKColor(0, 0, 0, 0));

        var result = new DripSharp.Runtime.JavaColorConvertOp().Filter(source, destination);

        Assert.Same(destination, result);
        Assert.Equal(colors, IccRenderFixtures.Colors(destination));
    }

    // Expected values are Java 25's ColorSpace.getInstance(CS_CIEXYZ) (D50
    // XYZ, the ICC PCS) and Apache PDFBox 3.0.8 run on it. Java converts
    // through a 16-bit LittleCMS transform (quantized samples, interpolated
    // curves); the exact matrix-and-curve conversion here differs from it by
    // under 0.0004 on these values, and CieTolerance (a quarter of an 8-bit
    // level) leaves room for platform differences in Pow.
    private const float CieTolerance = 0.001f;

    private static void AssertCie(float[] expected, float[] actual, string label)
    {
        Assert.Equal(expected.Length, actual.Length);
        for (var index = 0; index < expected.Length; index++)
            Assert.True(Math.Abs(expected[index] - actual[index]) <= CieTolerance,
                $"{label}: expected [{string.Join(", ", expected)}], " +
                $"got [{string.Join(", ", actual)}].");
    }

    [Fact]
    public void CieXyzColorSpaceConvertsD50XyzToAndFromSrgb()
    {
        var xyz = Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_CIEXYZ);
        (float[] Xyz, float[] Rgb)[] toRgb =
        [
            ([0.9642f, 1f, 0.8249f], [1f, 0.99998474f, 1f]),
            ([0f, 0f, 0f], [0f, 0f, 0f]),
            ([0.4360747f, 0.2225045f, 0.0139322f], [1f, 3.9673457E-4f, 3.9673457E-4f]),
            ([0.3850649f, 0.7168786f, 0.0971045f], [0f, 0.99998474f, 0f]),
            ([0.1430804f, 0.0606169f, 0.7141733f], [0f, 0f, 1f]),
            ([0.1801f, 0.1868f, 0.154f], [0.46947432f, 0.46938276f, 0.46924543f]),
            ([0.5f, 0.5f, 0.5f], [0.7439536f, 0.72565806f, 0.81200886f]),
            ([1f, 1f, 1f], [1f, 0.98706037f, 1f]),
            ([0.2f, 0.3f, 0.1f], [0.3363241f, 0.6517891f, 0.32504767f]),
            ([0.05f, 0.02f, 0.6f], [0f, 0.096192874f, 0.92732126f]),
            ([0.9505f, 1f, 1.089f], [0.9199206f, 1f, 1f]),
            ([0.01f, 0.02f, 0.03f], [0f, 0.18809797f, 0.21602197f]),
            // Java stores each component in a 16-bit sample with a narrowing
            // cast, so 2.5 wraps around to 0.5.
            ([2.5f, 2.5f, 2.5f], [0.7439536f, 0.72565806f, 0.81200886f])
        ];
        foreach (var (input, expected) in toRgb)
            AssertCie(expected, xyz.ToRgb(input), $"toRGB [{string.Join(", ", input)}]");

        (float[] Rgb, float[] Xyz)[] fromRgb =
        [
            ([1f, 1f, 1f], [0.9642029f, 1f, 0.82489014f]),
            ([0f, 0f, 0f], [0f, 0f, 0f]),
            ([1f, 0f, 0f], [0.43585205f, 0.22238159f, 0.013916016f]),
            ([0f, 1f, 0f], [0.38531494f, 0.717041f, 0.09713745f]),
            ([0f, 0f, 1f], [0.14300537f, 0.060577393f, 0.71383667f]),
            ([0.5f, 0.5f, 0.5f], [0.20639038f, 0.2140503f, 0.1765747f]),
            ([0.2f, 0.4f, 0.6f], [0.111206055f, 0.12194824f, 0.24075317f])
        ];
        foreach (var (input, expected) in fromRgb)
            AssertCie(expected, xyz.FromRgb(input), $"fromRGB [{string.Join(", ", input)}]");
    }

    private static COSArray CieFloats(params float[] values)
    {
        var array = new COSArray();
        foreach (var value in values)
            array.Add(new COSFloat(value));
        return array;
    }

    private static PDLab CieLab(float x, float y, float z)
    {
        var lab = new PDLab();
        lab.SetWhitePoint(new PDTristimulus(new[] { x, y, z }));
        return lab;
    }

    // White point (1, 1, 1) makes CalRGB and CalGray convert through CIE XYZ
    // (PDFBOX-2971); the matrix holds sRGB's D50 primaries.
    private static PDCalRGB CieCalRgb()
    {
        var calRgb = new PDCalRGB();
        calRgb.SetWhitePoint(new PDTristimulus(new[] { 1f, 1f, 1f }));
        calRgb.SetGamma(new PDGamma(CieFloats(2.2f, 2.2f, 2.2f)));
        ((COSDictionary)((COSArray)calRgb.GetCOSObject()).GetObject(1)).SetItem(
            COSName.Matrix,
            CieFloats(0.4360747f, 0.2225045f, 0.0139322f, 0.3850649f, 0.7168786f,
                0.0971045f, 0.1430804f, 0.0606169f, 0.7141733f));
        return calRgb;
    }

    private static PDCalGray CieCalGray()
    {
        var calGray = new PDCalGray();
        calGray.SetWhitePoint(new PDTristimulus(new[] { 1f, 1f, 1f }));
        calGray.SetGamma(2.2f);
        return calGray;
    }

    [Fact]
    public void CalibratedColorSpacesConvertThroughCieXyzAsJavaPdfBox()
    {
        var labD50 = CieLab(0.9642f, 1f, 0.8249f);
        var labD65 = CieLab(0.9505f, 1f, 1.089f);
        (float[] Lab, float[] D50, float[] D65)[] labs =
        [
            ([100f, 0f, 0f], [1f, 0.99998474f, 1f], [0.9199206f, 1f, 1f]),
            ([50f, 0f, 0f], [0.46631572f, 0.46630046f, 0.46634623f],
                [0.42681012f, 0.47109178f, 0.53946745f]),
            ([0f, 0f, 0f], [0f, 0f, 0f], [0f, 0f, 0f]),
            ([53.24f, 80.09f, 67.2f], [0.9824827f, 0f, 0.025803005f],
                [0.9729915f, 0.05600061f, 0.08740368f]),
            ([87.73f, -86.18f, 83.18f], [0f, 1f, 0f], [0f, 1f, 0.1878233f]),
            ([32.3f, 79.19f, -107.86f], [0.35559624f, 0f, 1f], [0f, 0f, 1f]),
            ([75f, 20f, -30f], [0.7874266f, 0.67899597f, 0.9427481f],
                [0.70337987f, 0.68966204f, 1f])
        ];
        foreach (var (input, d50, d65) in labs)
        {
            AssertCie(d50, labD50.ToRGB(input), $"Lab D50 [{string.Join(", ", input)}]");
            AssertCie(d65, labD65.ToRGB(input), $"Lab D65 [{string.Join(", ", input)}]");
        }

        var calRgb = CieCalRgb();
        var identity = new PDCalRGB();
        identity.SetWhitePoint(new PDTristimulus(new[] { 1f, 1f, 1f }));
        (float[] Input, float[] Matrix, float[] Identity)[] rgbs =
        [
            ([1f, 1f, 1f], [0.99998474f, 0.99998474f, 1f], [1f, 0.98706037f, 1f]),
            ([0f, 0f, 0f], [0f, 0f, 0f], [0f, 0f, 0f]),
            ([1f, 0f, 0f], [1f, 3.9673457E-4f, 3.9673457E-4f], [1f, 0f, 0.29756618f]),
            ([0f, 1f, 0f], [0f, 0.99998474f, 0f], [0f, 1f, 0f]),
            ([0f, 0f, 1f], [0f, 0f, 1f], [0f, 0.20106813f, 1f]),
            ([0.5f, 0.5f, 0.5f], [0.50379187f, 0.5038987f, 0.5039597f],
                [0.7439536f, 0.72565806f, 0.81200886f]),
            ([0.2f, 0.4f, 0.6f], [0.18609904f, 0.40047303f, 0.6056611f],
                [0f, 0.79220265f, 0.88925004f])
        ];
        foreach (var (input, matrix, unit) in rgbs)
        {
            AssertCie(matrix, calRgb.ToRGB(input), $"CalRGB [{string.Join(", ", input)}]");
            AssertCie(unit, identity.ToRGB(input), $"CalRGB identity [{string.Join(", ", input)}]");
        }

        var calGray = CieCalGray();
        (float Gray, float[] Rgb)[] grays =
        [
            (0f, [0f, 0f, 0f]),
            (0.25f, [0.24425116f, 0.23739986f, 0.26976424f]),
            (0.5f, [0.50995654f, 0.49701685f, 0.5580835f]),
            (0.75f, [0.76424813f, 0.7454948f, 0.8340429f]),
            (1f, [1f, 0.98706037f, 1f])
        ];
        foreach (var (gray, expected) in grays)
            AssertCie(expected, calGray.ToRGB(new[] { gray }), $"CalGray {gray}");
    }

    // Fills and 8-bit images in Lab, CalRGB and CalGray render as Apache
    // PDFBox 3.0.8 renders them (Java 25, page rendered at 72 DPI). They
    // match exactly; one 8-bit rounding step is allowed.
    [Fact]
    public void CalibratedFillsAndImagesRenderAsJavaPdfBox()
    {
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(70, 10));
        document.AddPage(page);
        var lab = CieLab(0.9642f, 1f, 0.8249f);
        var calRgb = CieCalRgb();
        var calGray = CieCalGray();
        var labImage = new PDImageXObject(document, new MemoryStream([191, 155, 102, 128, 128, 128]),
            null!, 2, 1, 8, lab);
        var calImage = new PDImageXObject(document, new MemoryStream([51, 102, 153, 128, 128, 128]),
            null!, 2, 1, 8, calRgb);
        var grayImage = new PDImageXObject(document, new MemoryStream([64, 191]),
            null!, 2, 1, 8, calGray);
        using (var content = new PDPageContentStream(document, page))
        {
            content.SetNonStrokingColor(new PDColor(new[] { 75f, 20f, -30f }, lab));
            content.AddRect(0, 0, 10, 10);
            content.Fill();
            content.SetNonStrokingColor(new PDColor(new[] { 0.2f, 0.4f, 0.6f }, calRgb));
            content.AddRect(10, 0, 10, 10);
            content.Fill();
            content.SetNonStrokingColor(new PDColor(new[] { 0.5f }, calGray));
            content.AddRect(20, 0, 10, 10);
            content.Fill();
            content.DrawImage(labImage, 30, 0, 20, 10);
            content.DrawImage(calImage, 50, 0, 10, 10);
            content.DrawImage(grayImage, 60, 0, 10, 10);
        }

        static void AssertPixel(uint expected, int actual, string label)
        {
            for (var shift = 0; shift < 32; shift += 8)
                Assert.True(
                    Math.Abs((int)((expected >> shift) & 0xff) - ((actual >> shift) & 0xff)) <= 1,
                    $"{label}: expected 0x{expected:x8}, got 0x{actual:x8}.");
        }

        using var rendered = new PDFRenderer(document).RenderImage(0);
        (int X, uint Argb)[] pixels =
        [
            (5, 0xffc9adf0), (15, 0xff2f669a), (25, 0xff827f8e), (35, 0xffd2abdd),
            (45, 0xff787776), (52, 0xff2f669a), (57, 0xff818081), (62, 0xff3e3c45),
            (67, 0xffc2bdd4)
        ];
        foreach (var (x, argb) in pixels)
            AssertPixel(argb, Compat.PdfCartonFontCompat.GetRgb(rendered, x, 5), $"page x={x}");

        using var labPixels = labImage.GetImage();
        AssertPixel(0xffd2abdd, Compat.PdfCartonFontCompat.GetRgb(labPixels, 0, 0), "Lab image 0");
        AssertPixel(0xff787776, Compat.PdfCartonFontCompat.GetRgb(labPixels, 1, 0), "Lab image 1");
        using var calPixels = calImage.GetImage();
        AssertPixel(0xff2f669a, Compat.PdfCartonFontCompat.GetRgb(calPixels, 0, 0), "CalRGB image 0");
        AssertPixel(0xff818081, Compat.PdfCartonFontCompat.GetRgb(calPixels, 1, 0), "CalRGB image 1");
        using var grayPixels = grayImage.GetImage();
        AssertPixel(0xff3e3c45, Compat.PdfCartonFontCompat.GetRgb(grayPixels, 0, 0), "CalGray image 0");
        AssertPixel(0xffc2bdd4, Compat.PdfCartonFontCompat.GetRgb(grayPixels, 1, 0), "CalGray image 1");
    }

    private sealed class ConstantFromRgbSpace : DripSharp.Runtime.JavaColorSpace
    {
        public ConstantFromRgbSpace() : base(0, TYPE_RGB, 3)
        {
        }

        public override float[] FromRgb(float[] rgb) => [0.25f, 0.5f, 0.75f];
    }

    private sealed class RedToRgbSpace : DripSharp.Runtime.JavaColorSpace
    {
        public RedToRgbSpace() : base(0, TYPE_RGB, 3)
        {
        }

        public override float[] ToRgb(float[] components) => [1f, 0f, 0f];
    }
}

internal static class IccRenderFixtures
{
    internal const int PatchSize = 8;

    // Renders page 0 at 72 DPI, so one image sample maps to one device pixel.
    internal static byte[] Render(byte[] pdf)
    {
        using var document = Loader.LoadPDF(Signed(pdf));
        using var image = new PDFRenderer(document).RenderImage(0);
        using var rgba = new SKBitmap(new SKImageInfo(
            image.Width, image.Height, SKColorType.Rgba8888, SKAlphaType.Unpremul));
        Assert.True(image.CopyTo(rgba, SKColorType.Rgba8888));
        return rgba.GetPixelSpan().ToArray();
    }

    internal static int[][] PatchColors(byte[] rgba, int patches)
    {
        var width = patches * PatchSize;
        var result = new int[patches][];
        for (var patch = 0; patch < patches; patch++)
        {
            var offset = ((PatchSize / 2) * width + patch * PatchSize + PatchSize / 2) * 4;
            result[patch] = [rgba[offset], rgba[offset + 1], rgba[offset + 2]];
        }
        return result;
    }

    internal static byte[] RgbPatches(byte[] profile) =>
        Patches(3, [[255, 0, 0], [0, 255, 0], [0, 0, 255], [128, 128, 128], [200, 150, 50]],
            document => IccSpace(document, profile, 3));

    internal static byte[] GrayPatches(byte[] profile) =>
        Patches(1, [[0], [64], [128], [255]], document => IccSpace(document, profile, 1));

    internal static byte[] CmykPatches() =>
        Patches(4, [[255, 0, 0, 0], [0, 255, 0, 0], [0, 0, 255, 0], [0, 0, 0, 255], [64, 32, 16, 8]],
            _ => PDDeviceCMYK.Instance);

    private static byte[] Patches(
        int components,
        byte[][] colors,
        Func<PDDocument, PDColorSpace> colorSpace)
    {
        var width = colors.Length * PatchSize;
        var samples = new byte[width * PatchSize * components];
        for (var y = 0; y < PatchSize; y++)
            for (var x = 0; x < width; x++)
                colors[x / PatchSize].CopyTo(samples, (y * width + x) * components);
        return ImagePdf(width, PatchSize, document =>
            new PDImageXObject(document, new MemoryStream(samples), null!,
                width, PatchSize, 8, colorSpace(document)));
    }

    internal static byte[] ImagePdf(int width, int height, Func<PDDocument, PDImageXObject> image)
    {
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(width, height));
        document.AddPage(page);
        var xobject = image(document);
        using (var content = new PDPageContentStream(document, page))
            content.DrawImage(xobject, 0, 0, width, height);
        using var output = new MemoryStream();
        document.Save(output);
        return output.ToArray();
    }

    internal static PDICCBased IccSpace(PDDocument document, byte[] profile, int components)
    {
        var stream = document.GetDocument().CreateCOSStream();
        stream.SetInt(COSName.N, components);
        using (var output = stream.CreateOutputStream())
            output.Write(profile);
        var array = new COSArray();
        array.Add(COSName.Iccbased);
        array.Add(stream);
        return PDICCBased.Create(array, null!);
    }

    // A deterministic RGB image encoded by Skia, which embeds its sRGB
    // matrix/TRC ICC profile (with no "sRGB" device model in the header).
    internal static byte[] SkiaJpeg(int width, int height)
    {
        var info = new SKImageInfo(
            width, height, SKColorType.Rgba8888, SKAlphaType.Opaque, SKColorSpace.CreateSrgb());
        using var bitmap = new SKBitmap(info);
        var pixels = bitmap.GetPixelSpan();
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var offset = (y * width + x) * 4;
                pixels[offset] = (byte)(x * 255 / width);
                pixels[offset + 1] = (byte)(y * 255 / height);
                pixels[offset + 2] = (byte)((x * 7 + y * 13) & 255);
                pixels[offset + 3] = 255;
            }
        }
        bitmap.NotifyPixelsChanged();
        using var image = SKImage.FromBitmap(bitmap);
        using var data = image.Encode(SKEncodedImageFormat.Jpeg, 90);
        return data.ToArray();
    }

    internal static byte[] JpegIccProfile(byte[] jpeg)
    {
        using var profile = new MemoryStream();
        var offset = 2;
        while (offset + 4 < jpeg.Length && jpeg[offset] == 0xFF && jpeg[offset + 1] != 0xDA)
        {
            var length = jpeg[offset + 2] << 8 | jpeg[offset + 3];
            if (jpeg[offset + 1] == 0xE2 &&
                System.Text.Encoding.ASCII.GetString(jpeg, offset + 4, 11) == "ICC_PROFILE")
            {
                profile.Write(jpeg, offset + 18, length - 16);
            }
            offset += 2 + length;
        }
        Assert.True(profile.Length > 128, "Skia did not embed an ICC profile.");
        return profile.ToArray();
    }

    internal static byte[] Corrupt(byte[] profile)
    {
        var broken = (byte[])profile.Clone();
        broken[36] = (byte)'x'; // the 'acsp' profile signature
        return broken;
    }

    // A minimal v2 display profile: GRAY data, XYZ PCS, D50 wtpt and a
    // gamma 2.2 kTRC.
    internal static byte[] GrayProfile()
    {
        var data = new byte[192];
        void U32(int at, uint value)
        {
            data[at] = (byte)(value >> 24);
            data[at + 1] = (byte)(value >> 16);
            data[at + 2] = (byte)(value >> 8);
            data[at + 3] = (byte)value;
        }
        void Ascii(int at, string text)
        {
            for (var index = 0; index < text.Length; index++)
                data[at + index] = (byte)text[index];
        }
        U32(0, (uint)data.Length);
        U32(8, 0x02100000);
        Ascii(12, "mntr");
        Ascii(16, "GRAY");
        Ascii(20, "XYZ ");
        Ascii(36, "acsp");
        U32(68, 0x0000F6D6);
        U32(72, 0x00010000);
        U32(76, 0x0000D32D);
        U32(128, 2);
        U32(132, 0x77747074); // wtpt
        U32(136, 156);
        U32(140, 20);
        U32(144, 0x6B545243); // kTRC
        U32(148, 176);
        U32(152, 14);
        Ascii(156, "XYZ ");
        U32(164, 0x0000F6D6);
        U32(168, 0x00010000);
        U32(172, 0x0000D32D);
        Ascii(176, "curv");
        U32(184, 1);
        data[188] = 2;
        data[189] = 0x33;
        return data;
    }

    // A v2 display profile with sRGB's D50 primaries and a gamma 1.8 TRC
    // shared by all three channels.
    internal static byte[] RgbGammaProfile()
    {
        var data = new byte[312];
        void U32(int at, uint value)
        {
            data[at] = (byte)(value >> 24);
            data[at + 1] = (byte)(value >> 16);
            data[at + 2] = (byte)(value >> 8);
            data[at + 3] = (byte)value;
        }
        void Ascii(int at, string text)
        {
            for (var index = 0; index < text.Length; index++)
                data[at + index] = (byte)text[index];
        }
        void Xyz(int at, double x, double y, double z)
        {
            Ascii(at, "XYZ ");
            U32(at + 8, (uint)Math.Round(x * 65536));
            U32(at + 12, (uint)Math.Round(y * 65536));
            U32(at + 16, (uint)Math.Round(z * 65536));
        }
        U32(0, (uint)data.Length);
        U32(8, 0x02100000);
        Ascii(12, "mntr");
        Ascii(16, "RGB ");
        Ascii(20, "XYZ ");
        Ascii(36, "acsp");
        U32(68, 0x0000F6D6);
        U32(72, 0x00010000);
        U32(76, 0x0000D32D);
        uint[] signatures =
            [0x7258595a, 0x6758595a, 0x6258595a, 0x77747074, 0x72545243, 0x67545243, 0x62545243];
        U32(128, (uint)signatures.Length);
        for (var tag = 0; tag < signatures.Length; tag++)
        {
            var entry = 132 + tag * 12;
            U32(entry, signatures[tag]);
            U32(entry + 4, tag < 4 ? (uint)(216 + tag * 20) : 296u);
            U32(entry + 8, tag < 4 ? 20u : 14u);
        }
        Xyz(216, 0.4361, 0.2225, 0.0139);
        Xyz(236, 0.3851, 0.7169, 0.0971);
        Xyz(256, 0.1431, 0.0606, 0.7141);
        Xyz(276, 0.9642, 1.0, 0.8249);
        Ascii(296, "curv");
        U32(304, 1);
        data[308] = 1;
        data[309] = 0xCD; // gamma 1.8
        return data;
    }

    internal static PDColorSpace ImageColorSpace(PDDocument document)
    {
        var resources = document.GetPage(0).GetResources();
        var image = (PDImageXObject)resources.GetXObject(resources.GetXObjectNames().Single());
        return image.GetColorSpace();
    }

    internal static SKBitmap Bitmap(SKAlphaType alphaType, params SKColor[] colors)
    {
        var bitmap = new SKBitmap(new SKImageInfo(
            colors.Length, 1, SKColorType.Bgra8888, alphaType));
        var pixels = bitmap.GetPixelSpan();
        for (var index = 0; index < colors.Length; index++)
        {
            pixels[index * 4] = colors[index].Blue;
            pixels[index * 4 + 1] = colors[index].Green;
            pixels[index * 4 + 2] = colors[index].Red;
            pixels[index * 4 + 3] = colors[index].Alpha;
        }
        bitmap.NotifyPixelsChanged();
        return bitmap;
    }

    // Reads stored BGRA bytes directly (one row), so colors under zero alpha
    // are observed as stored.
    internal static SKColor[] Colors(SKBitmap bitmap)
    {
        Assert.Equal(SKColorType.Bgra8888, bitmap.ColorType);
        Assert.Equal(1, bitmap.Height);
        var pixels = bitmap.GetPixelSpan();
        var colors = new SKColor[bitmap.Width];
        for (var index = 0; index < colors.Length; index++)
        {
            colors[index] = new SKColor(
                pixels[index * 4 + 2], pixels[index * 4 + 1], pixels[index * 4], pixels[index * 4 + 3]);
        }
        return colors;
    }

    internal static sbyte[] Signed(byte[] bytes) => (sbyte[])(Array)bytes;
}

// Raster rendering and color-model pixel access: rendered rasters must store
// what the color model's getters report for every pixel, without per-pixel
// allocation.
public sealed class RasterConversionConsumerTests
{
    private const int Width = 37;
    private const int Height = 23;

    // Every bulk row layout, plus layouts that keep per-pixel conversion,
    // renders exactly the getters' colors (Java's getRGB) for random samples.
    [Fact]
    public void RenderedRastersStoreTheColorModelsColors()
    {
        var srgb = new Compat.JavaIccColorSpace(
            Compat.JavaColorSpace.CS_sRGB,
            Compat.PdfCartonFontCompat.GetIccProfile(Compat.JavaColorSpace.CS_sRGB));
        var iccGray = new Compat.JavaIccColorSpace(
            new Compat.JavaIccProfile(IccRenderFixtures.Signed(IccRenderFixtures.GrayProfile())));
        Compat.JavaColorModel Indexed(int bits, int size) => new(
            bits,
            size,
            Enumerable.Range(0, size).Select(i => (sbyte)(i * 37)).ToArray(),
            Enumerable.Range(0, size).Select(i => (sbyte)(i * 91 + 5)).ToArray(),
            Enumerable.Range(0, size).Select(i => (sbyte)(250 - i * 13)).ToArray());
        Compat.JavaColorModel Component(Compat.JavaColorSpace space, bool alpha, int type) =>
            new(space, alpha, type);
        var cases = new (string Name, Compat.JavaColorModel Model, bool Premultiplied, int Size, int Max)[]
        {
            ("INT_ARGB packed", new(Compat.PdfCartonFontCompat.TYPE_INT_ARGB), false, Width, 255),
            ("INT_ARGB packed, premultiplied", new(Compat.PdfCartonFontCompat.TYPE_INT_ARGB), true, Width, 255),
            ("INT_RGB packed", new(Compat.PdfCartonFontCompat.TYPE_INT_RGB), false, Width, 255),
            ("INT_BGR packed", new(Compat.PdfCartonFontCompat.TYPE_INT_BGR), false, Width, 255),
            ("BYTE_BINARY 1-bit", new(Compat.PdfCartonFontCompat.TYPE_BYTE_BINARY), false, Width, 1),
            ("indexed 1-bit", Indexed(1, 2), false, Width, 1),
            ("indexed 2-bit", Indexed(2, 3), false, Width, 2),
            ("indexed 4-bit", Indexed(4, 16), false, Width, 15),
            ("indexed 8-bit", Indexed(8, 200), false, Width, 199),
            ("sRGB bytes with alpha", Component(srgb, true, Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), false, Width, 255),
            ("gray bytes with alpha", Component(new Compat.JavaColorSpace(Compat.JavaColorSpace.CS_GRAY), true,
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), false, Width, 255),
            ("sRGB 16-bit", Component(srgb, false, Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT), false, 256, 65535),
            ("sRGB 4-bit components in bytes", new(srgb, false, Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, [4, 4, 4]),
                false, Width, 255),
            ("ICC gray bytes", Component(iccGray, false, Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), false, Width, 255),
            ("CMYK bytes", Component(new Compat.JavaColorSpace(Compat.JavaColorSpace.TYPE_CMYK), false,
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), false, Width, 255),
        };
        foreach (var (name, model, premultiplied, size, max) in cases)
        {
            var raster = Fill(
                model.CreateCompatibleWritableRaster(size, size == Width ? Height : size), max, name.Length);
            using var image = Compat.PdfCartonFontCompat.CreateImage(model, raster, premultiplied, null);
            AssertStored(name, image, model, raster);
        }
    }

    // Image-type bitmaps take rasters through SetImageData, including a gray
    // bitmap's own materialized samples (SampledImageReader.from1Bit), a BGR
    // bitmap's band-offset samples and DeviceRGB's ToRGBImage byte raster.
    [Fact]
    public void SetImageDataStoresTheColorModelsColors()
    {
        foreach (var type in new[]
                 {
                     Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY,
                     Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR,
                     Compat.PdfCartonFontCompat.TYPE_INT_RGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_ARGB
                 })
        {
            using var own = Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, type);
            var raster = Compat.PdfCartonFontCompat.GetRaster(own);
            if (raster.TransferType == Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE)
                _ = ((Compat.JavaDataBufferByte)raster.GetDataBuffer()).GetData();
            Fill(raster, 255, type);
            Compat.PdfCartonFontCompat.SetImageData(own, raster);
            AssertStored($"type {type} own raster", own, Compat.PdfCartonFontCompat.GetColorModel(own), raster);

            using var target = Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, type);
            var bands = type == Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY ? 1 : 3;
            var source = Fill(Compat.PdfCartonFontCompat.CreateInterleavedRaster(
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, Width, Height, bands,
                new Compat.JavaPoint(0, 0)), 255, type + 1);
            if (type == Compat.PdfCartonFontCompat.TYPE_INT_ARGB) continue;
            Compat.PdfCartonFontCompat.SetImageData(target, source);
            AssertStored($"type {type} byte raster", target, new Compat.JavaColorModel(type), source);
        }
    }

    // Java's setData copies band samples, so a packed raster in another INT
    // layout keeps its red, green and blue (DeviceRGB's ToRGBImage included).
    [Fact]
    public void PackedRastersCopyAcrossIntLayouts()
    {
        foreach (var (sourceType, targetType) in new[]
                 {
                     (Compat.PdfCartonFontCompat.TYPE_INT_BGR, Compat.PdfCartonFontCompat.TYPE_INT_RGB),
                     (Compat.PdfCartonFontCompat.TYPE_INT_RGB, Compat.PdfCartonFontCompat.TYPE_INT_BGR),
                     (Compat.PdfCartonFontCompat.TYPE_INT_ARGB, Compat.PdfCartonFontCompat.TYPE_INT_RGB),
                     (Compat.PdfCartonFontCompat.TYPE_INT_ARGB, Compat.PdfCartonFontCompat.TYPE_INT_BGR),
                 })
        {
            var source = Fill(new Compat.JavaColorModel(sourceType)
                .CreateCompatibleWritableRaster(Width, Height), 255, sourceType);
            // DeviceRGB's ToRGBImage is SetImageData into a new INT_RGB image.
            using var target = targetType == Compat.PdfCartonFontCompat.TYPE_INT_RGB
                ? PDDeviceRGB.Instance.ToRGBImage(source)
                : Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, targetType);
            if (targetType != Compat.PdfCartonFontCompat.TYPE_INT_RGB)
                Compat.PdfCartonFontCompat.SetImageData(target, source);
            var pixels = target.GetPixelSpan();
            var sample = new int[source.NumberOfBands];
            for (var y = 0; y < Height; y++)
            {
                for (var x = 0; x < Width; x++)
                {
                    source.GetPixel(x, y, sample);
                    var offset = y * target.RowBytes + x * 4;
                    Assert.True(
                        pixels[offset + 2] == sample[0] && pixels[offset + 1] == sample[1] &&
                        pixels[offset] == sample[2],
                        $"{sourceType} -> {targetType} ({x},{y}): stored " +
                        $"[{pixels[offset + 2]}, {pixels[offset + 1]}, {pixels[offset]}], " +
                        $"samples [{string.Join(", ", sample)}].");
                }
            }
        }
    }

    [Fact]
    public void IndexesPastThePaletteStillFailToRender()
    {
        var model = new Compat.JavaColorModel(8, 3, [1, 2, 3], [4, 5, 6], [7, 8, 9]);
        var raster = model.CreateCompatibleWritableRaster(4, 2);
        raster.SetPixel(3, 1, [200]);
        var error = Assert.ThrowsAny<ArgumentException>(
            () => Compat.PdfCartonFontCompat.CreateImage(model, raster, false, null).Dispose());
        Assert.Contains("Palette index 200", error.Message);
    }

    [Fact]
    public void ColorModelGettersDoNotAllocate()
    {
        var srgb = new Compat.JavaIccColorSpace(
            Compat.JavaColorSpace.CS_sRGB,
            Compat.PdfCartonFontCompat.GetIccProfile(Compat.JavaColorSpace.CS_sRGB));
        var cases = new (Compat.JavaColorModel Model, object Pixel, int[] Argb)[]
        {
            (new(Compat.PdfCartonFontCompat.TYPE_INT_ARGB), new[] { unchecked((int)0x80402010) }, [128, 64, 32, 16]),
            (new(Compat.PdfCartonFontCompat.TYPE_INT_RGB), new[] { 0x402010 }, [255, 64, 32, 16]),
            (new(Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY), new sbyte[] { 77 }, [255, 77, 77, 77]),
            (new(srgb, true, Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), new sbyte[] { 10, 20, 30, 40 },
                [40, 10, 20, 30]),
            (new(8, 3, [1, 2, 3], [4, 5, 6], [7, 8, 9]), new sbyte[] { 2 }, [255, 3, 6, 9]),
            (new(new Compat.JavaColorSpace(Compat.JavaColorSpace.TYPE_CMYK), false,
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE), new sbyte[] { 0, 0, 0, 0 }, [255, 255, 255, 255]),
        };
        foreach (var (model, pixel, argb) in cases)
        {
            Assert.Equal(argb, new[] { model.GetAlpha(pixel), model.GetRed(pixel), model.GetGreen(pixel), model.GetBlue(pixel) });
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            var sum = 0;
            for (var i = 0; i < 1000; i++)
                sum += model.GetAlpha(pixel) + model.GetRed(pixel) + model.GetGreen(pixel) + model.GetBlue(pixel);
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
            Assert.Equal(1000 * argb.Sum(), sum);
            Assert.Equal(0, allocated);
        }
    }

    // Java's DirectColorModel compatible raster packs one int per pixel.
    [Fact]
    public void IntCompatibleRastersPackOneIntPerPixel()
    {
        foreach (var (type, pixel, packed) in new[]
                 {
                     (Compat.PdfCartonFontCompat.TYPE_INT_RGB, new[] { 10, 20, 30 }, 0x000A141E),
                     (Compat.PdfCartonFontCompat.TYPE_INT_ARGB, new[] { 10, 20, 30, 40 }, 0x280A141E),
                     (Compat.PdfCartonFontCompat.TYPE_INT_BGR, new[] { 10, 20, 30 }, 0x001E140A),
                 })
        {
            var model = new Compat.JavaColorModel(type);
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            var raster = model.CreateCompatibleWritableRaster(500, 400);
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
            Assert.True(allocated <= 4L * 500 * 400 + 1024, $"type {type}: {allocated} bytes.");
            Assert.Equal(pixel.Length, raster.NumberOfBands);
            Assert.Equal(500 * 400, raster.GetDataBuffer().Size);

            raster.SetPixel(1, 2, pixel);
            var element = Assert.IsType<int[]>(raster.GetDataElements(1, 2, null));
            Assert.Equal(new[] { packed }, element);
            Assert.Equal(pixel, raster.GetPixel(1, 2, (int[]?)null));
            Assert.Equal(packed, raster.GetDataBuffer().GetElement(2 * 500 + 1));
            Assert.Equal(pixel[..3], new[] { model.GetRed(element), model.GetGreen(element), model.GetBlue(element) });
            // A reused larger buffer holds the packed pixel in element zero.
            var reused = raster.GetDataElements(1, 2, new int[4]);
            Assert.Equal(pixel[..3], new[] { model.GetRed(reused), model.GetGreen(reused), model.GetBlue(reused) });
            Assert.Equal(pixel.Length == 4 ? pixel[3] : 255, model.GetAlpha(reused));
            Assert.Equal(pixel.Select(value => value / 255f),
                model.GetNormalizedComponents(reused, null, 0));

            var copy = raster.CreateCompatibleWritableRaster();
            copy.SetDataElements(3, 4, element);
            Assert.Equal(pixel, copy.GetPixel(3, 4, (int[]?)null));
        }
    }

    // DeviceRGB, 8-bit gray and 1-bit gray images decode to exactly their
    // samples, allocating buffers of the raster's size rather than per pixel.
    [Fact]
    public void ImageDecodingAllocatesOnlyRasterSizedBuffers()
    {
        const int width = 400;
        const int height = 300;
        var rgb = new byte[width * height * 3];
        for (var i = 0; i < rgb.Length; i++) rgb[i] = (byte)(i * 13 + i / 1201);
        var gray = new byte[width * height];
        for (var i = 0; i < gray.Length; i++) gray[i] = (byte)(i * 5 + i / 401);
        var bits = new byte[width / 8 * height];
        for (var i = 0; i < bits.Length; i++) bits[i] = (byte)(i * 37 + i / 83);
        foreach (var (name, samples, bitsPerComponent, space) in new (string, byte[], int, PDColorSpace)[]
                 {
                     ("DeviceRGB", rgb, 8, PDDeviceRGB.Instance),
                     ("DeviceGray", gray, 8, PDDeviceGray.Instance),
                     ("1-bit DeviceGray", bits, 1, PDDeviceGray.Instance),
                 })
        {
            // A fresh image each time: PDImageXObject caches its decoded image.
            using var document = new PDDocument();
            PDImageXObject Image() => new(document, new MemoryStream(samples), null!,
                width, height, bitsPerComponent, space);
            Image().GetImage().Dispose();
            var image = Image();
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            using var bitmap = image.GetImage();
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
            Assert.True(allocated < 8L * width * height,
                $"{name}: decoding allocated {allocated} bytes for {width * height} pixels.");
            for (var y = 0; y < height; y += 7)
            {
                for (var x = 0; x < width; x += 3)
                {
                    var color = bitmap.GetPixel(x, y);
                    var index = y * width + x;
                    var expected = name switch
                    {
                        "DeviceRGB" => new SKColor(rgb[index * 3], rgb[index * 3 + 1], rgb[index * 3 + 2]),
                        "DeviceGray" => new SKColor(gray[index], gray[index], gray[index]),
                        _ => (bits[index / 8] >> (7 - x % 8) & 1) == 0 ? SKColors.Black : SKColors.White,
                    };
                    Assert.True(expected == color, $"{name} ({x},{y}): {color} != {expected}.");
                }
            }
        }
    }

    // A shading under an ExtGState /SMask goes through SoftMask's per-pixel
    // getters and compatible INT_ARGB rasters, and the alpha mask through
    // SetImageData. Expected colors were rendered by Apache PDFBox 3.0.8.
    [Fact]
    public void SoftMaskedShadingsRenderLikePdfBoxWithoutPerPixelAllocations()
    {
        (int X, int Y)[] points = [(50, 150), (150, 50), (100, 100), (110, 110), (195, 195), (5, 5)];
        foreach (var (kind, expected) in new (string, int[][])[]
                 {
                     ("/Alpha", [[192, 0, 63], [159, 127, 223], [127, 0, 127], [114, 0, 140], [255, 255, 255], [255, 255, 255]]),
                     ("/Luminosity", [[205, 51, 101], [159, 127, 223], [140, 25, 140], [128, 25, 151], [255, 255, 255], [255, 255, 255]]),
                 })
        {
            var pdf = SoftMaskedShadingPdf(kind);
            var first = IccRenderFixtures.Render(pdf);
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            var second = IccRenderFixtures.Render(pdf);
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
            Assert.Equal(first, second);
            // Per-pixel getter arrays cost about 300 bytes per page pixel.
            Assert.True(allocated < 150L * 200 * 200, $"{kind}: rendering allocated {allocated} bytes.");
            for (var point = 0; point < points.Length; point++)
            {
                var offset = (points[point].Y * 200 + points[point].X) * 4;
                for (var channel = 0; channel < 3; channel++)
                    Assert.True(Math.Abs(second[offset + channel] - expected[point][channel]) <= 2,
                        $"{kind} {points[point]}: got [{second[offset]}, {second[offset + 1]}, {second[offset + 2]}].");
            }
        }
    }

    // An opaque colour under an ExtGState /SMask reaches SoftMask through the
    // colour's paint context. Java fills that context's raster with the ARGB
    // value of its default ARGB model, so the mask scales an alpha of 255.
    // Fills, strokes and glyphs then show through the mask. Expected colours
    // were rendered by Apache PDFBox 3.0.8.
    [Fact]
    public void SoftMaskedSolidPaintsRenderLikePdfBox()
    {
        const string fill = "1 0 0 rg 10 10 180 180 re f";
        const string stroke = "0 0 1 RG 20 w 30 100 m 170 100 l S";
        foreach (var (kind, content, expected) in new (string, string, (int X, int Y, int[] Rgb)[])[]
                 {
                     ("/Luminosity", fill, [(50, 150, [255, 51, 51]), (150, 50, [255, 127, 127]),
                         (100, 100, [255, 25, 25]), (195, 195, [255, 255, 255]), (15, 15, [255, 255, 255])]),
                     ("/Alpha", fill, [(50, 150, [255, 0, 0]), (150, 50, [255, 127, 127]),
                         (100, 100, [255, 0, 0]), (195, 195, [255, 255, 255]), (15, 15, [255, 255, 255])]),
                     ("/Luminosity", stroke, [(60, 100, [51, 51, 255]), (100, 100, [25, 25, 255]),
                         (150, 100, [127, 127, 255]), (190, 100, [255, 255, 255]), (100, 50, [255, 255, 255])]),
                     ("/Alpha", stroke, [(60, 100, [0, 0, 255]), (100, 100, [0, 0, 255]),
                         (150, 100, [127, 127, 255]), (190, 100, [255, 255, 255]), (100, 50, [255, 255, 255])]),
                 })
        {
            var rgba = IccRenderFixtures.Render(SoftMaskedPdf(kind, content));
            foreach (var (x, y, rgb) in expected)
            {
                var offset = (y * 200 + x) * 4;
                for (var channel = 0; channel < 3; channel++)
                    Assert.True(Math.Abs(rgba[offset + channel] - rgb[channel]) <= 2,
                        $"{kind} {content} ({x},{y}): got [{rgba[offset]}, {rgba[offset + 1]}, {rgba[offset + 2]}].");
            }
        }

        // PDFBox draws the 120 pt Helvetica "I" over 1040 pixels; the darkest
        // one, fully covered inside the 0.8 gray mask area, is [255, 51, 51]
        // under the luminosity mask and pure red under the alpha mask. The
        // substituted font may differ, so the glyph area is only bounded.
        foreach (var (kind, darkest) in new[] { ("/Luminosity", 51), ("/Alpha", 0) })
        {
            var rgba = IccRenderFixtures.Render(
                SoftMaskedPdf(kind, "1 0 0 rg BT /F1 120 Tf 20 40 Td (I) Tj ET"));
            var marked = 0;
            var minimum = 255;
            for (var offset = 0; offset < rgba.Length; offset += 4)
            {
                if (rgba[offset] + rgba[offset + 1] + rgba[offset + 2] == 765) continue;
                marked++;
                Assert.True(rgba[offset] >= 253 && Math.Abs(rgba[offset + 1] - rgba[offset + 2]) <= 2,
                    $"{kind} glyph pixel is not a tint of red.");
                minimum = Math.Min(minimum, rgba[offset + 1]);
            }
            Assert.InRange(marked, 600, 1600);
            Assert.InRange(minimum, darkest - 2, darkest + 2);
        }
    }

    // An image under an ExtGState /SMask is a TexturePaint filled through
    // SoftMask. The 4x4 image covers 160x160 pixels: with /Interpolate true
    // PDFBox keeps its bicubic hint and Java's texture paint filters
    // bilinearly; without it the upscaled image is nearest neighbour.
    // Expected colours were rendered by Apache PDFBox 3.0.8.
    [Fact]
    public void SoftMaskedImagesKeepTheirInterpolationLikePdfBox()
    {
        (int X, int Y)[] points = [(50, 150), (62, 150), (70, 110), (100, 100), (110, 60), (150, 62), (150, 100), (90, 170)];
        foreach (var (kind, interpolate, expected) in new (string, bool, int[][])[]
                 {
                     ("/Luminosity", true, [[121, 109, 128], [126, 101, 129], [73, 112, 93], [25, 25, 140],
                         [223, 223, 223], [130, 160, 158], [191, 175, 127], [67, 99, 181]]),
                     ("/Luminosity", false, [[51, 153, 153], [153, 51, 153], [51, 153, 51], [255, 25, 255],
                         [127, 127, 255], [127, 127, 127], [127, 127, 127], [153, 51, 153]]),
                     ("/Alpha", true, [[88, 72, 96], [94, 63, 97], [28, 76, 52], [0, 0, 128],
                         [223, 223, 223], [130, 160, 158], [191, 175, 127], [20, 60, 163]]),
                 })
        {
            var rgba = IccRenderFixtures.Render(SoftMaskedImagePdf(kind, interpolate));
            for (var point = 0; point < points.Length; point++)
            {
                var offset = (points[point].Y * 200 + points[point].X) * 4;
                for (var channel = 0; channel < 3; channel++)
                    Assert.True(Math.Abs(rgba[offset + channel] - expected[point][channel]) <= 2,
                        $"{kind} interpolate={interpolate} {points[point]}: " +
                        $"got [{rgba[offset]}, {rgba[offset + 1]}, {rgba[offset + 2]}].");
            }
        }
    }

    // A tiling pattern fills through TilingPaint's TexturePaint, which
    // samples its device-resolution tile bilinearly at the fractional
    // offsets of the pattern matrix. Expected colours were rendered by
    // Apache PDFBox 3.0.8.
    [Fact]
    public void TilingPatternFillsRenderLikePdfBox()
    {
        // The second fill is clipped to a box with a nonzero origin and
        // composed with /BM /Multiply and /ca 0.6 over a green backdrop on
        // the left, so the shader also goes through the custom composite.
        foreach (var (before, resources, expected) in new (string, string, (int X, int Y, int[] Rgb)[])[]
                 {
                     ("", "", [(41, 50, [77, 77, 201]), (42, 50, [0, 0, 255]), (48, 50, [178, 178, 201]),
                         (50, 50, [255, 255, 255]), (11, 11, [201, 178, 178]), (40, 40, [194, 102, 102]),
                         (15, 20, [0, 0, 255]), (150, 150, [255, 255, 255]), (5, 5, [255, 255, 255])]),
                     ("0.2 0.8 0.6 rg 0 0 120 200 re f /GS1 gs 37 23 140 150 re W n ",
                      " /ExtGState << /GS1 << /BM /Multiply /ca 0.6 >> >>",
                         [(41, 50, [30, 119, 134]), (42, 50, [20, 82, 153]), (48, 50, [42, 167, 134]),
                          (50, 50, [51, 204, 153]), (37, 28, [51, 82, 61]), (41, 28, [45, 167, 125]),
                          (131, 50, [148, 148, 223]), (132, 50, [102, 102, 255]), (140, 40, [218, 163, 163]),
                          (36, 50, [51, 204, 153]), (40, 40, [44, 131, 98]), (42, 26, [51, 204, 153]),
                          (150, 180, [255, 255, 255])]),
                 })
        {
            var rgba = IccRenderFixtures.Render(TilingPatternPdf(before, resources));
            foreach (var (x, y, rgb) in expected)
            {
                var offset = (y * 200 + x) * 4;
                for (var channel = 0; channel < 3; channel++)
                    Assert.True(Math.Abs(rgba[offset + channel] - rgb[channel]) <= 2,
                        $"{resources} ({x},{y}): got [{rgba[offset]}, {rgba[offset + 1]}, {rgba[offset + 2]}].");
            }
        }
    }

    // JavaTexturePaint fills through a Skia image shader, so it also draws
    // on a canvas without a bitmap. As in java.awt.TexturePaintContext, a
    // device pixel maps its corner to texture position u and takes texel
    // floor(u) for nearest neighbour, or blends it with the next, repeating,
    // texel by frac(u) otherwise. Here u = (x - 0.5) / 4 texels; expected
    // values were rendered by Java 2D's TexturePaint.
    [Fact]
    public void TexturePaintsSampleLikeJavaOnAnyCanvas()
    {
        using var tile = new SKBitmap(new SKImageInfo(2, 1, SKColorType.Bgra8888, SKAlphaType.Opaque));
        tile.SetPixel(0, 0, SKColors.Red);
        tile.SetPixel(1, 0, SKColors.Blue);
        int[] nearest = [0, 255, 255, 255, 255, 0, 0, 0, 0];
        int[] bilinear = [223, 223, 159, 96, 32, 32, 96, 159, 223];
        var interpolation = Compat.PdfCartonRenderingHints.KEY_INTERPOLATION;
        var rendering = Compat.PdfCartonRenderingHints.KEY_RENDERING;
        var quality = Compat.PdfCartonRenderingHints.VALUE_RENDER_QUALITY;
        // Without an interpolation hint, Java filters only for quality rendering.
        foreach (var (name, hints, reds) in new (string, object[], int[])[]
                 {
                     ("nearest", [interpolation, Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR], nearest),
                     ("bilinear", [interpolation, Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_BILINEAR], bilinear),
                     ("bicubic", [interpolation, Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_BICUBIC], bilinear),
                     ("no hints", [], nearest),
                     ("quality", [rendering, quality], bilinear),
                     ("default rendering", [rendering, Compat.PdfCartonRenderingHints.VALUE_RENDER_DEFAULT], nearest),
                     ("quality and nearest", [rendering, quality, interpolation,
                         Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR], nearest),
                 })
        {
            using var surface = SKSurface.Create(new SKImageInfo(9, 4, SKColorType.Bgra8888, SKAlphaType.Premul));
            surface.Canvas.Clear(SKColors.White);
            using (var graphics = new Compat.PdfCartonGraphics2D(surface))
            {
                for (var index = 0; index < hints.Length; index += 2)
                    graphics.SetRenderingHint(hints[index], hints[index + 1]);
                graphics.SetTransform(new SKMatrix(4, 0, 0.5f, 0, 4, 0, 0, 0, 1));
                graphics.SetPaint(new Compat.JavaTexturePaint(tile, new SKRect(0, 0, 2, 1)));
                graphics.Fill(new SKRect(-0.125f, 0, 2.25f, 1));
            }
            using var pixels = new SKBitmap(9, 4);
            Assert.True(surface.ReadPixels(pixels.Info, pixels.GetPixels(), pixels.RowBytes, 0, 0));
            for (var x = 0; x < 9; x++)
            {
                var color = pixels.GetPixel(x, 2);
                Assert.True(Math.Abs(color.Red - reds[x]) <= 2 && Math.Abs(color.Blue - (255 - reds[x])) <= 2 &&
                            color.Green == 0, $"{name} x={x}: got {color}.");
            }
        }
    }

    private static readonly int[][] InterpolationTexels =
    [
        [255, 0, 0], [0, 255, 0], [0, 0, 255], [255, 255, 0],
        [0, 255, 255], [255, 0, 255], [255, 255, 255], [0, 0, 0],
        [128, 0, 0], [0, 128, 0], [0, 0, 128], [128, 128, 0],
        [0, 128, 128], [128, 0, 128], [64, 64, 64], [192, 192, 192],
    ];

    private static byte[] SoftMaskedImagePdf(string kind, bool interpolate) =>
        SoftMaskedPdf(kind, "160 0 0 160 20 20 cm /Im Do", "/XObject << /Im 8 0 R >> ",
            PdfStream("/Type /XObject /Subtype /Image /Width 4 /Height 4 /ColorSpace /DeviceRGB " +
                $"/BitsPerComponent 8 /Interpolate {(interpolate ? "true" : "false")} /Filter /ASCIIHexDecode",
                string.Concat(InterpolationTexels.Select(texel => $"{texel[0]:X2}{texel[1]:X2}{texel[2]:X2}")) + ">"));

    private static byte[] TilingPatternPdf(string before, string resources) => SequentialPdf(
    [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Pattern << /P1 5 0 R >>" +
        resources + " >> /Contents 4 0 R >>",
        PdfStream("", before + "/Pattern cs /P1 scn 10 10 180 180 re f"),
        PdfStream("/PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 10 10] /XStep 10 /YStep 10 " +
            "/Matrix [1.5 0 0 1.5 3.3 2.6] /Resources << >>", "1 0 0 rg 0 0 5 5 re f 0 0 1 rg 5 5 5 5 re f"),
    ]);

    private static Compat.JavaRaster Fill(Compat.JavaRaster raster, int max, int seed)
    {
        var random = new Random(seed);
        var pixel = new int[raster.NumberOfBands];
        for (var y = 0; y < raster.Height; y++)
        {
            for (var x = 0; x < raster.Width; x++)
            {
                for (var band = 0; band < pixel.Length; band++) pixel[band] = random.Next(max + 1);
                raster.SetPixel(x, y, pixel);
            }
        }
        return raster;
    }

    // Compares stored native pixels with the getters' color of each pixel.
    private static void AssertStored(
        string name,
        SKBitmap image,
        Compat.JavaColorModel model,
        Compat.JavaRaster raster)
    {
        var pixels = image.GetPixelSpan();
        object? pixel = null;
        for (var y = 0; y < raster.Height; y++)
        {
            for (var x = 0; x < raster.Width; x++)
            {
                pixel = raster.GetDataElements(x, y, pixel);
                var red = model.GetRed(pixel);
                var green = model.GetGreen(pixel);
                var blue = model.GetBlue(pixel);
                var alpha = image.AlphaType == SKAlphaType.Opaque ? 255 : model.GetAlpha(pixel);
                if (image.AlphaType == SKAlphaType.Premul)
                {
                    red = (red * alpha + 127) / 255;
                    green = (green * alpha + 127) / 255;
                    blue = (blue * alpha + 127) / 255;
                }
                int[] expected, actual;
                if (image.ColorType == SKColorType.Gray8)
                {
                    Assert.True(red == green && green == blue, $"{name}: gray model rendered color.");
                    expected = [red];
                    actual = [pixels[y * image.RowBytes + x]];
                }
                else
                {
                    var offset = y * image.RowBytes + x * 4;
                    expected = [red, green, blue, alpha];
                    actual = [pixels[offset + 2], pixels[offset + 1], pixels[offset], pixels[offset + 3]];
                }
                Assert.True(expected.SequenceEqual(actual),
                    $"{name} ({x},{y}): stored [{string.Join(", ", actual)}], " +
                    $"expected [{string.Join(", ", expected)}].");
            }
        }
    }

    private static byte[] SoftMaskedShadingPdf(string kind) =>
        SoftMaskedPdf(kind, "10 10 180 180 re W n /Sh1 sh");

    private static string PdfStream(string entries, string data) =>
        $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";

    // Objects in extra are numbered from 8; resources go into the page's.
    private static byte[] SoftMaskedPdf(string kind, string content, string resources = "", params string[] extra) =>
        SequentialPdf(
        [
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << " + resources +
            "/ExtGState << /GS1 5 0 R >> " +
            "/Shading << /Sh1 << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 200 0] /Function << " +
            "/FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> /Extend [true true] >> >> " +
            "/Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> /Contents 4 0 R >>",
            PdfStream("", $"q /GS1 gs {content} Q"),
            $"<< /Type /ExtGState /SMask << /Type /Mask /S {kind} /G 6 0 R >> >>",
            PdfStream("/Type /XObject /Subtype /Form /BBox [0 0 200 200] /Group << /S /Transparency " +
                "/CS /DeviceRGB >> /Resources << /ExtGState << /A 7 0 R >> >>",
                "0.8 0.8 0.8 rg 20 20 100 100 re f /A gs 1 1 1 rg 80 80 100 100 re f"),
            "<< /Type /ExtGState /ca 0.5 >>",
            .. extra,
        ]);

    // A PDF whose objects are numbered from 1 in order.
    private static byte[] SequentialPdf(string[] objects)
    {
        var text = new System.Text.StringBuilder("%PDF-1.7\n");
        var offsets = new List<int>();
        for (var index = 0; index < objects.Length; index++)
        {
            offsets.Add(text.Length);
            text.Append($"{index + 1} 0 obj\n{objects[index]}\nendobj\n");
        }
        var xref = text.Length;
        text.Append($"xref\n0 {objects.Length + 1}\n0000000000 65535 f \n");
        foreach (var offset in offsets) text.Append($"{offset:D10} 00000 n \n");
        text.Append($"trailer\n<< /Size {objects.Length + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return System.Text.Encoding.ASCII.GetBytes(text.ToString());
    }

    // Raster.getData copies and materialized data buffers of a bitmap-backed
    // raster hold exactly the samples its per-pixel reads return, at the
    // band offsets of the raster layout, and outlive the bitmap.
    [Fact]
    public void BitmapRasterSnapshotsPreserveComponents()
    {
        var cases = new (string Name, SKColorType Color, SKAlphaType Alpha, int Padding, int Type, int[] Offsets)[]
        {
            ("BGR", SKColorType.Bgra8888, SKAlphaType.Opaque, 0, Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR, [2, 1, 0]),
            ("RGBA source as BGR, padded rows", SKColorType.Rgba8888, SKAlphaType.Opaque, 12,
                Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR, [2, 1, 0]),
            ("BGRA unpremultiplied", SKColorType.Bgra8888, SKAlphaType.Unpremul, 0,
                Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR, [0, 1, 2, 3]),
            ("RGBA unpremultiplied, padded rows", SKColorType.Rgba8888, SKAlphaType.Unpremul, 8,
                Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR, [0, 1, 2, 3]),
            ("BGRA premultiplied", SKColorType.Bgra8888, SKAlphaType.Premul, 0,
                Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR, [0, 1, 2, 3]),
            ("gray, padded rows", SKColorType.Gray8, SKAlphaType.Opaque, 5,
                Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY, [0]),
        };
        foreach (var (name, color, alpha, padding, type, offsets) in cases)
        {
            var info = new SKImageInfo(Width, Height, color, alpha);
            var bitmap = new SKBitmap(info, info.RowBytes + padding);
            var pixels = bitmap.GetPixelSpan();
            var random = new Random(name.Length);
            random.NextBytes(pixels);
            if (alpha == SKAlphaType.Premul)
            {
                // Premultiplied components never exceed alpha.
                for (var index = 0; index < pixels.Length; index += 4)
                    for (var component = 0; component < 3; component++)
                        pixels[index + component] = (byte)(pixels[index + component] % (pixels[index + 3] + 1));
            }
            bitmap.NotifyPixelsChanged();
            Compat.PdfCartonFontCompat.RegisterImageType(bitmap, type);

            var view = Compat.PdfCartonFontCompat.GetRaster(bitmap);
            var bands = view.NumberOfBands;
            Assert.Equal(offsets.Length, bands);
            var expected = new int[Height][];
            for (var y = 0; y < Height; y++)
                expected[y] = view.GetPixels(0, y, Width, 1, (int[]?)null);

            var snapshot = Compat.PdfCartonFontCompat.GetImageData(bitmap);
            var materialized = new Compat.JavaRaster(bitmap);
            var materializedData = ((Compat.JavaDataBufferByte)materialized.GetDataBuffer()).GetData();
            var snapshotData = ((Compat.JavaDataBufferByte)snapshot.GetDataBuffer()).GetData();
            bitmap.Dispose();

            for (var y = 0; y < Height; y++)
            {
                Assert.Equal(expected[y], snapshot.GetPixels(0, y, Width, 1, (int[]?)null));
                Assert.Equal(expected[y], materialized.GetPixels(0, y, Width, 1, (int[]?)null));
                for (var x = 0; x < Width; x++)
                {
                    for (var band = 0; band < bands; band++)
                    {
                        var index = (y * Width + x) * bands + offsets[band];
                        var sample = expected[y][x * bands + band];
                        Assert.True(
                            sample == (snapshotData[index] & 0xff) && sample == (materializedData[index] & 0xff),
                            $"{name} ({x}, {y}) band {band}: expected {sample}, snapshot " +
                            $"{snapshotData[index] & 0xff}, materialized {materializedData[index] & 0xff}.");
                    }
                }
            }
        }
    }

    // Per-pixel accessors of a bitmap-backed raster read and write exactly what
    // its row accessors do, for every bitmap layout GetRaster views.
    [Fact]
    public void BitmapRasterPixelAccessorsMatchRowAccessors()
    {
        var cases = new (string Name, SKColorType Color, SKAlphaType Alpha, int Padding, int Type)[]
        {
            ("INT_ARGB", SKColorType.Bgra8888, SKAlphaType.Unpremul, 0, Compat.PdfCartonFontCompat.TYPE_INT_ARGB),
            ("INT_RGB, padded rows", SKColorType.Bgra8888, SKAlphaType.Opaque, 12,
                Compat.PdfCartonFontCompat.TYPE_INT_RGB),
            ("INT_BGR", SKColorType.Bgra8888, SKAlphaType.Opaque, 0, Compat.PdfCartonFontCompat.TYPE_INT_BGR),
            ("premultiplied BGRA", SKColorType.Bgra8888, SKAlphaType.Premul, 0,
                Compat.PdfCartonFontCompat.TYPE_INT_ARGB),
            ("RGBA as 4BYTE_ABGR, padded rows", SKColorType.Rgba8888, SKAlphaType.Unpremul, 8,
                Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR),
            ("3BYTE_BGR", SKColorType.Bgra8888, SKAlphaType.Opaque, 0, Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR),
            ("gray, padded rows", SKColorType.Gray8, SKAlphaType.Opaque, 5,
                Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY),
            ("custom BGRA", SKColorType.Bgra8888, SKAlphaType.Unpremul, 0, Compat.PdfCartonFontCompat.TYPE_CUSTOM),
        };
        foreach (var (name, color, alpha, padding, type) in cases)
        {
            var info = new SKImageInfo(Width, Height, color, alpha);
            using var bitmap = new SKBitmap(info, info.RowBytes + padding);
            using var expected = new SKBitmap(info, info.RowBytes + padding);
            bitmap.Erase(SKColors.Transparent);
            expected.Erase(SKColors.Transparent);
            Compat.PdfCartonFontCompat.RegisterImageType(bitmap, type);
            Compat.PdfCartonFontCompat.RegisterImageType(expected, type);
            var raster = Compat.PdfCartonFontCompat.GetRaster(bitmap);
            var rows = Compat.PdfCartonFontCompat.GetRaster(expected);
            var bands = raster.NumberOfBands;
            var buffer = raster.GetDataBuffer();
            var random = new Random(name.Length);
            var pixel = new int[bands];
            var floats = new float[bands];
            for (var y = 0; y < Height; y++)
            {
                var row = new int[Width * bands];
                for (var index = 0; index < row.Length; index++)
                    row[index] = random.Next(256);
                rows.SetPixels(0, y, Width, 1, row);
                for (var x = 0; x < Width; x++)
                {
                    Array.Copy(row, x * bands, pixel, 0, bands);
                    switch (x % 4)
                    {
                        case 0:
                            raster.SetPixel(x, y, pixel);
                            break;
                        case 1:
                            for (var band = 0; band < bands; band++) floats[band] = pixel[band];
                            raster.SetPixel(x, y, floats);
                            break;
                        case 2:
                            raster.SetDataElements(x, y, pixel.Select(value => unchecked((sbyte)value)).ToArray());
                            break;
                        default:
                            // A DataBuffer element is the gray sample or a packed ARGB pixel.
                            buffer.SetElement(y * Width + x, bands == 1
                                ? pixel[0]
                                : pixel[0] << 16 | pixel[1] << 8 | pixel[2] | (bands == 4 ? pixel[3] : 255) << 24);
                            break;
                    }
                }
            }
            Assert.True(expected.GetPixelSpan().SequenceEqual(bitmap.GetPixelSpan()), name);
            for (var y = 0; y < Height; y++)
            {
                var row = rows.GetPixels(0, y, Width, 1, (int[]?)null);
                Assert.Equal(row, raster.GetPixels(0, y, Width, 1, (int[]?)null));
                for (var x = 0; x < Width; x++)
                {
                    var samples = row.AsSpan(x * bands, bands).ToArray();
                    Assert.Equal(samples, raster.GetPixel(x, y, (int[]?)null));
                    Assert.Equal(samples.Select(value => (float)value), raster.GetPixel(x, y, (float[]?)null));
                    Assert.Equal(
                        bands == 1
                            ? samples[0]
                            : samples[0] << 16 | samples[1] << 8 | samples[2] | (bands == 4 ? samples[3] : 255) << 24,
                        buffer.GetElement(y * Width + x));
                    var elements = raster.GetDataElements(x, y, null);
                    Assert.Equal(raster.GetDataElements(x, y, 1, 1, null), elements);
                    if (elements is sbyte[] bytes)
                        Assert.Equal(samples, bytes.Select(value => value & 0xff));
                    else if (type != Compat.PdfCartonFontCompat.TYPE_INT_BGR)
                        Assert.Equal(
                            samples[0] << 16 | samples[1] << 8 | samples[2] | (bands == 4 ? samples[3] << 24 : 0),
                            Assert.IsType<int[]>(elements)[0]);
                    // Writing a pixel's own data elements back leaves it unchanged.
                    raster.SetDataElements(x, y, elements);
                }
            }
            Assert.True(expected.GetPixelSpan().SequenceEqual(bitmap.GetPixelSpan()), $"{name} round trip");
        }
    }

    // Raster writes to a bitmap reach Skia: drawing it after per-pixel writes
    // shows them, even when the bitmap was drawn before the writes. Compat
    // draws and direct Skia draws (unscaled and scaled, which Skia samples
    // from a cached image of the bitmap if it is stale) are separate cases.
    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void BitmapRasterWritesAreVisibleToLaterDraws(bool directSkia, bool scaled)
    {
        using var source = Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        source.Erase(SKColors.Red);
        using var target = Compat.PdfCartonFontCompat.CreateBitmap(
            Width * 2, Height * 2, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        using var graphics = new Compat.PdfCartonGraphics2D(target);
        using var canvasTarget = new SKBitmap(
            new SKImageInfo(Width * 2, Height * 2, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(canvasTarget);
        var scale = scaled ? 2 : 1;
        SKColor Drawn(int x, int y)
        {
            if (!directSkia)
            {
                graphics.DrawImage(source, 0, 0, null);
                return target.GetPixel(x, y);
            }
            canvas.Clear(SKColors.Transparent);
            if (scaled)
                canvas.DrawBitmap(source, new SKRect(0, 0, Width * 2, Height * 2),
                    new SKSamplingOptions(SKFilterMode.Nearest, SKMipmapMode.Nearest), null);
            else
                canvas.DrawBitmap(source, 0, 0, SKSamplingOptions.Default, null);
            canvas.Flush();
            return canvasTarget.GetPixel(x, y);
        }
        Assert.Equal(SKColors.Red, Drawn(0, 0));
        var raster = Compat.PdfCartonFontCompat.GetRaster(source);
        for (var step = 0; step < 3; step++)
        {
            raster.SetPixel(step * 3, step, [0, 0, 255, 255]);
            for (var written = 0; written <= step; written++)
                Assert.Equal(SKColors.Blue, Drawn(written * 3 * scale, written * scale));
            Assert.Equal(SKColors.Red, Drawn(Width * scale - 1, Height * scale - 1));
        }
    }

    // GetRaster and RegisterImageType replace a bitmap's metadata atomically.
    [Fact]
    public void ConcurrentRasterAndTypeRegistrationOnOneBitmapDoNotThrow()
    {
        using var bitmap = Compat.PdfCartonFontCompat.CreateBitmap(8, 8, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var failures = new System.Collections.Concurrent.ConcurrentQueue<Exception>();
        Parallel.For(0, 8, new ParallelOptions { MaxDegreeOfParallelism = 8 }, worker =>
        {
            var pixel = new[] { worker, 2, 3, 255 };
            for (var iteration = 0; iteration < 20_000 && failures.IsEmpty; iteration++)
            {
                try
                {
                    if ((worker + iteration) % 2 == 0)
                        Compat.PdfCartonFontCompat.RegisterImageType(bitmap, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
                    else
                        Compat.PdfCartonFontCompat.GetRaster(bitmap).SetPixel(iteration % 8, worker, pixel);
                }
                catch (Exception failure)
                {
                    failures.Enqueue(failure);
                }
            }
        });
        Assert.Empty(failures);
        Assert.Equal(Compat.PdfCartonFontCompat.TYPE_INT_ARGB, Compat.PdfCartonFontCompat.GetImageType(bitmap));
        Assert.Equal(new[] { 7, 2, 3, 255 }, Compat.PdfCartonFontCompat.GetRaster(bitmap).GetPixel(0, 7, (int[]?)null));
    }

    // A retained raster views the bitmap's pixels until they are reallocated;
    // GetRaster then views the new pixels, and a disposed bitmap's view throws.
    [Fact]
    public void RetainedBitmapRastersFollowReallocationAndDisposal()
    {
        var bitmap = new SKBitmap(new SKImageInfo(4, 4, SKColorType.Bgra8888, SKAlphaType.Opaque));
        bitmap.Erase(SKColors.Red);
        var raster = Compat.PdfCartonFontCompat.GetRaster(bitmap);
        Assert.Same(raster, Compat.PdfCartonFontCompat.GetRaster(bitmap));
        Assert.Equal(new[] { 255, 0, 0 }, raster.GetPixel(3, 3, (int[]?)null));

        Assert.True(bitmap.TryAllocPixels(new SKImageInfo(64, 32, SKColorType.Bgra8888, SKAlphaType.Opaque)));
        bitmap.Erase(SKColors.Lime);
        var reallocated = Compat.PdfCartonFontCompat.GetRaster(bitmap);
        Assert.NotSame(raster, reallocated);
        Assert.Equal(64, reallocated.Width);
        Assert.Equal(new[] { 0, 255, 0 }, reallocated.GetPixel(63, 31, (int[]?)null));
        // The replaced view refuses access rather than touching released pixels.
        Assert.Throws<InvalidOperationException>(() => raster.SetPixel(0, 0, new[] { 1, 2, 3 }));
        Assert.Throws<InvalidOperationException>(() => raster.GetDataBuffer().GetElement(0));

        bitmap.Dispose();
        Assert.ThrowsAny<ObjectDisposedException>(() => reallocated.GetPixel(0, 0, (int[]?)null));
        Assert.ThrowsAny<ObjectDisposedException>(() => reallocated.SetPixel(0, 0, new[] { 1, 2, 3 }));
    }

    // BlendComposite reads the destination through a copy of the page raster.
    // An INT page's copy holds its packed pixels, which the page's colour model
    // decodes, so a Multiply fill blends with the backdrop instead of
    // replacing it.
    [Fact]
    public void MultiplyFillBlendsWithIntPages()
    {
        foreach (var type in new[]
                 {
                     Compat.PdfCartonFontCompat.TYPE_INT_ARGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_RGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_BGR,
                     Compat.PdfCartonFontCompat.TYPE_CUSTOM
                 })
        {
            using var page = Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, type);
            page.Erase(new SKColor(80, 120, 160));
            using (var graphics = new Compat.PdfCartonGraphics2D(page))
            {
                graphics.SetRenderingHint(Compat.PdfCartonRenderingHints.KEY_ANTIALIASING,
                    Compat.PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
                graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
                graphics.SetColor(new SKColor(128, 192, 224));
                graphics.FillRect(4, 4, 16, 8);
            }
            var inside = page.GetPixel(10, 6);
            Assert.True(
                Math.Abs(inside.Red - 40) <= 1 && Math.Abs(inside.Green - 90) <= 1 &&
                Math.Abs(inside.Blue - 141) <= 1 && inside.Alpha == 255,
                $"type {type}: blended {inside}, expected about #ff285a8d.");
            Assert.Equal(new SKColor(80, 120, 160), page.GetPixel(30, 18));
        }
    }

    // As Java's ColorSpace.getInstance, each standard space is one shared
    // instance, and the default colour models use it.
    [Fact]
    public void StandardColorSpacesAreSharedInstances()
    {
        foreach (var kind in new[]
                 {
                     Compat.JavaColorSpace.CS_sRGB,
                     Compat.JavaColorSpace.CS_GRAY,
                     Compat.JavaColorSpace.CS_CIEXYZ
                 })
        {
            Assert.Same(
                Compat.PdfCartonFontCompat.GetColorSpace(kind),
                Compat.PdfCartonFontCompat.GetColorSpace(kind));
        }
        var srgb = Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_sRGB);
        foreach (var type in new[]
                 {
                     Compat.PdfCartonFontCompat.TYPE_INT_ARGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_RGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_BGR,
                     Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR,
                     Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR,
                     Compat.PdfCartonFontCompat.TYPE_BYTE_BINARY
                 })
        {
            using var image = Compat.PdfCartonFontCompat.CreateBitmap(4, 4, type);
            Assert.Same(srgb, Compat.PdfCartonFontCompat.GetColorModel(image).ColorSpace);
        }
        using var gray = Compat.PdfCartonFontCompat.CreateBitmap(
            4, 4, Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY);
        Assert.Same(
            Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_GRAY),
            Compat.PdfCartonFontCompat.GetColorModel(gray).ColorSpace);
        var indexed = new Compat.JavaColorModel(
            1, 2, new sbyte[] { 0, -1 }, new sbyte[] { 0, -1 }, new sbyte[] { 0, -1 });
        Assert.Same(srgb, indexed.ColorSpace);
    }

    // BlendComposite converts the source through CIE XYZ only when the
    // colour spaces differ. sRGB layers drawn on sRGB pages share the space,
    // so, as in Java PDFBox, a Multiply blend uses the source components
    // unchanged: over white it reproduces every source colour exactly, and
    // over the same colour it gives the plain float product (Java PDFBox
    // 3.0.8 produces the same 512 pixels).
    [Fact]
    public void MultiplyBlendOfSrgbOnSrgbSkipsColourConversion()
    {
        static SKColor ColorAt(int index) =>
            new((byte)index, (byte)(index * 67 + 29), (byte)(index * 151 + 101));
        static byte Product(byte source, byte destination)
        {
            float s = source / 255f, d = destination / 255f;
            var value = s * d;
            value = s + 1f * (value - s);
            value = d + 1f * (value - d);
            return (byte)(int)(value * 255f + 0.5f);
        }

        using var page = Compat.PdfCartonFontCompat.CreateBitmap(
            256, 2, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        for (var index = 0; index < 256; index++)
        {
            page.SetPixel(index, 0, SKColors.White);
            page.SetPixel(index, 1, ColorAt(index));
        }
        using (var graphics = new Compat.PdfCartonGraphics2D(page))
        {
            graphics.SetRenderingHint(Compat.PdfCartonRenderingHints.KEY_ANTIALIASING,
                Compat.PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
            for (var index = 0; index < 256; index++)
            {
                graphics.SetColor(ColorAt(index));
                graphics.FillRect(index, 0, 1, 2);
            }
        }
        for (var index = 0; index < 256; index++)
        {
            var color = ColorAt(index);
            Assert.Equal(color, page.GetPixel(index, 0));
            Assert.Equal(
                new SKColor(
                    Product(color.Red, color.Red),
                    Product(color.Green, color.Green),
                    Product(color.Blue, color.Blue)),
                page.GetPixel(index, 1));
        }

        // The conversion allocated four arrays per pixel (about 160 bytes in
        // all); without it the blend allocates about four bytes per pixel,
        // for the layer and raster copies.
        using var large = Compat.PdfCartonFontCompat.CreateBitmap(
            256, 256, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        large.Erase(new SKColor(80, 120, 160));
        using (var graphics = new Compat.PdfCartonGraphics2D(large))
        {
            graphics.SetRenderingHint(Compat.PdfCartonRenderingHints.KEY_ANTIALIASING,
                Compat.PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.SetComposite(BlendComposite.GetInstance(BlendMode.Multiply, 1));
            graphics.SetColor(new SKColor(128, 192, 224));
            graphics.FillRect(0, 0, 8, 8);
            var before = GC.GetAllocatedBytesForCurrentThread();
            graphics.FillRect(0, 0, 256, 256);
            var allocated = GC.GetAllocatedBytesForCurrentThread() - before;
            Assert.True(allocated < 256 * 256 * 16,
                $"A 256x256 Multiply blend allocated {allocated} bytes.");
        }
        Assert.Equal(new SKColor(40, 90, 141), large.GetPixel(128, 128));
    }

    // Every blend mode at 70% alpha over an RGB and a gray backdrop. The
    // sRGB layer and the RGB page share their colour space, so separable
    // modes blend without a CIE XYZ conversion, as in Java. Expected pixels
    // are Java PDFBox 3.0.8's rendering of the same page.
    [Fact]
    public void BlendModeFillsRenderAsJavaPdfBox()
    {
        (BlendMode Mode, uint RgbBackdrop, uint GrayBackdrop)[] expected =
        [
            (BlendMode.Multiply, 0xff425965, 0xff6e4a38),
            (BlendMode.Screen, 0xffb1b6ea, 0xffc7a492),
            (BlendMode.Overlay, 0xff6d8bdc, 0xffb56e4b),
            (BlendMode.Darken, 0xff4d7569, 0xff806e4a),
            (BlendMode.Lighten, 0xffa699e6, 0xffb58080),
            (BlendMode.ColorDodge, 0xffcae0f8, 0xffd9bc96),
            (BlendMode.ColorBurn, 0xff2e2ea0, 0xff6a2626),
            (BlendMode.HardLight, 0xff988485, 0xffb56e4a),
            (BlendMode.SoftLight, 0xff6890dd, 0xff967765),
            (BlendMode.Difference, 0xff7052c2, 0xff5c395c),
            (BlendMode.Exclusion, 0xff868bc9, 0xff7f8080),
            (BlendMode.Hue, 0xffae7d70, 0xff808080),
            (BlendMode.Saturation, 0xff4d99e6, 0xff808080),
            (BlendMode.Color, 0xffae7d70, 0xffb66f4b),
            (BlendMode.Luminosity, 0xff4591de, 0xff7f7f7f)
        ];
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(expected.Length * 10, 20));
        document.AddPage(page);
        using (var content = new PDPageContentStream(document, page))
        {
            content.SetNonStrokingColor(0.3f, 0.6f, 0.9f);
            content.AddRect(0, 10, expected.Length * 10, 10);
            content.Fill();
            content.SetNonStrokingColor(0.5f);
            content.AddRect(0, 0, expected.Length * 10, 10);
            content.Fill();
            for (var index = 0; index < expected.Length; index++)
            {
                var state = new Pdmodel.Graphics.State.PDExtendedGraphicsState();
                state.SetBlendMode(expected[index].Mode);
                state.SetNonStrokingAlphaConstant(0.7f);
                content.SaveGraphicsState();
                content.SetGraphicsStateParameters(state);
                content.SetNonStrokingColor(0.8f, 0.4f, 0.2f);
                content.AddRect(index * 10, 0, 10, 20);
                content.Fill();
                content.RestoreGraphicsState();
            }
        }

        var mismatches = new List<string>();
        void CheckPixel(uint argb, int actual, string label)
        {
            for (var shift = 0; shift < 32; shift += 8)
            {
                if (Math.Abs((int)((argb >> shift) & 0xff) - ((actual >> shift) & 0xff)) > 1)
                {
                    mismatches.Add($"{label}: expected 0x{argb:x8}, got 0x{actual:x8}");
                    return;
                }
            }
        }

        using var rgb = new PDFRenderer(document).RenderImage(0);
        for (var index = 0; index < expected.Length; index++)
        {
            var (mode, rgbBackdrop, grayBackdrop) = expected[index];
            var x = index * 10 + 5;
            CheckPixel(rgbBackdrop, Compat.PdfCartonFontCompat.GetRgb(rgb, x, 5), $"{mode} on RGB");
            CheckPixel(grayBackdrop, Compat.PdfCartonFontCompat.GetRgb(rgb, x, 15), $"{mode} on gray");
        }
        Assert.True(mismatches.Count == 0, string.Join("\n", mismatches));
    }

    // DataBufferInt.getData() returns an INT image's storage, as in Java:
    // the same array on every call, which Skia drawing, raster reads and
    // GetRgb share with it.
    [Fact]
    public void IntDataBufferIsTheLiveImageStorage()
    {
        foreach (var type in new[]
                 {
                     Compat.PdfCartonFontCompat.TYPE_INT_ARGB,
                     Compat.PdfCartonFontCompat.TYPE_INT_RGB
                 })
        {
            using var image = Compat.PdfCartonFontCompat.CreateBitmap(Width, Height, type);
            var raster = Compat.PdfCartonFontCompat.GetRaster(image);
            var data = ((Compat.JavaDataBufferInt)raster.GetDataBuffer()).GetData();
            Assert.Same(data,
                ((Compat.JavaDataBufferInt)Compat.PdfCartonFontCompat.GetRaster(image).GetDataBuffer()).GetData());
            Assert.Equal(Width * Height, data.Length);

            // Skia drawing into the image is visible in the array.
            using (var canvas = new SKCanvas(image))
                canvas.Clear(new SKColor(10, 20, 30));
            Assert.Equal(unchecked((int)0xff0a141e), data[5 * Width + 7]);

            // Writes into the array are visible to raster reads, GetRgb and Skia.
            var written = type == Compat.PdfCartonFontCompat.TYPE_INT_ARGB
                ? unchecked((int)0x80c86432)
                : unchecked((int)0xffc86432);
            data[5 * Width + 7] = written;
            Assert.Equal(written, Compat.PdfCartonFontCompat.GetRgb(image, 7, 5));
            Assert.Equal(
                type == Compat.PdfCartonFontCompat.TYPE_INT_ARGB ? new[] { 200, 100, 50, 128 } : new[] { 200, 100, 50 },
                raster.GetPixel(7, 5, (int[]?)null));
            using var target = Compat.PdfCartonFontCompat.CreateBitmap(
                Width, Height, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
            using (var graphics = new Compat.PdfCartonGraphics2D(target))
                graphics.DrawImage(image, 0, 0, null);
            // Premultiplied drawing may round a translucent colour by one.
            var drawn = target.GetPixel(7, 5);
            Assert.True(
                Math.Abs(drawn.Red - 200) <= 1 && Math.Abs(drawn.Green - 100) <= 1 &&
                Math.Abs(drawn.Blue - 50) <= 1 && drawn.Alpha == (byte)(written >>> 24),
                $"type {type}: drew {drawn}.");

            // A second write after that draw reaches Skia's next draw as well.
            data[5 * Width + 7] = unchecked((int)0xff112233);
            using (var graphics = new Compat.PdfCartonGraphics2D(target))
                graphics.DrawImage(image, 0, 0, null);
            Assert.Equal(new SKColor(0x11, 0x22, 0x33), target.GetPixel(7, 5));
        }
    }

    // Handing out the storage copies nothing: the cost does not grow with
    // the image (it used to read every pixel through the native view).
    [Fact]
    public void IntDataBufferGetDataDoesNotCopyThePixels()
    {
        using var image = Compat.PdfCartonFontCompat.CreateBitmap(
            2000, 2000, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var buffer = (Compat.JavaDataBufferInt)Compat.PdfCartonFontCompat.GetRaster(image).GetDataBuffer();
        var first = buffer.GetData();
        var allocated = GC.GetAllocatedBytesForCurrentThread();
        for (var call = 0; call < 100; call++)
            Assert.Same(first, buffer.GetData());
        allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
        Assert.True(allocated < 1024, $"100 GetData calls allocated {allocated} bytes.");
    }

    // A new BufferedImage's transparency is its type's, as in Java:
    // TYPE_INT_ARGB and TYPE_4BYTE_ABGR are TRANSLUCENT whatever the pixels
    // hold (a fresh one is all transparent), the other types OPAQUE. The
    // colour model is created with the image, so no call reads pixels.
    [Fact]
    public void NewImageTransparencyComesFromItsType()
    {
        foreach (var (type, transparency) in new[]
                 {
                     (Compat.PdfCartonFontCompat.TYPE_INT_ARGB, Compat.PdfCartonTransparency.TRANSLUCENT),
                     (Compat.PdfCartonFontCompat.TYPE_4BYTE_ABGR, Compat.PdfCartonTransparency.TRANSLUCENT),
                     (Compat.PdfCartonFontCompat.TYPE_INT_RGB, Compat.PdfCartonTransparency.OPAQUE),
                     (Compat.PdfCartonFontCompat.TYPE_INT_BGR, Compat.PdfCartonTransparency.OPAQUE),
                     (Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR, Compat.PdfCartonTransparency.OPAQUE),
                     (Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY, Compat.PdfCartonTransparency.OPAQUE),
                     (Compat.PdfCartonFontCompat.TYPE_BYTE_BINARY, Compat.PdfCartonTransparency.OPAQUE),
                 })
        {
            foreach (var (width, height) in new[] { (1, 1), (2550, 3300) })
            {
                using var image = Compat.PdfCartonFontCompat.CreateBitmap(width, height, type);
                var colorModel = Compat.PdfCartonFontCompat.GetColorModel(image);
                Assert.Equal(transparency, colorModel.Transparency);
                Assert.Equal(transparency, Compat.PdfCartonFontCompat.GetTransparency(image));
                using (var canvas = new SKCanvas(image))
                    canvas.Clear(SKColors.Red);
                Assert.Equal(transparency, Compat.PdfCartonFontCompat.GetTransparency(image));
                var mismatches = 0;
                var allocated = GC.GetAllocatedBytesForCurrentThread();
                var started = System.Diagnostics.Stopwatch.GetTimestamp();
                for (var call = 0; call < 1000; call++)
                {
                    if (!ReferenceEquals(colorModel, Compat.PdfCartonFontCompat.GetColorModel(image)) ||
                        Compat.PdfCartonFontCompat.GetTransparency(image) != transparency)
                        mismatches++;
                }
                var elapsed = System.Diagnostics.Stopwatch.GetElapsedTime(started);
                allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
                Assert.Equal(0, mismatches);
                Assert.True(allocated < 1024,
                    $"type {type} {width}x{height}: 1000 calls allocated {allocated} bytes.");
                // One scan of 2550x3300 pixels takes milliseconds.
                Assert.True(elapsed < TimeSpan.FromSeconds(1),
                    $"type {type} {width}x{height}: 1000 calls took {elapsed}.");
            }
        }
    }

    // Decoded images take their decoder's transparency, as in Java's ImageIO:
    // a PNG with an alpha channel is TRANSLUCENT even when every alpha is 0
    // or 255, a transparent GIF (an IndexColorModel) is BITMASK, classified
    // from its pixels once, and a foreign bitmap is its inferred
    // TYPE_INT_ARGB's TRANSLUCENT. Later pixel writes change none of them.
    [Fact]
    public void DecodedImageTransparencyFollowsItsDecoder()
    {
        using var source = new SKBitmap(new SKImageInfo(8, 6, SKColorType.Bgra8888, SKAlphaType.Unpremul));
        source.Erase(SKColors.Transparent);
        source.SetPixel(7, 5, SKColors.Blue);
        byte[] png;
        using (var sourceImage = SKImage.FromBitmap(source))
        using (var data = sourceImage.Encode(SKEncodedImageFormat.Png, 100))
            png = data.ToArray();
        Assert.Equal(6, png[25]); // IHDR colour type: RGB with alpha

        using var decodedPng = Compat.PdfCartonFontCompat.DecodeImage(png);
        using var gif = Compat.PdfCartonFontCompat.DecodeImage(TransparentGif());
        using var foreign = source.Copy();
        foreach (var (image, expected) in new[]
                 {
                     (decodedPng, Compat.PdfCartonTransparency.TRANSLUCENT),
                     (gif, Compat.PdfCartonTransparency.BITMASK),
                     (foreign, Compat.PdfCartonTransparency.TRANSLUCENT),
                 })
        {
            Assert.Equal(expected, Compat.PdfCartonFontCompat.GetTransparency(image));
            Assert.Equal(expected, Compat.PdfCartonFontCompat.GetColorModel(image).Transparency);
            using (var canvas = new SKCanvas(image))
                canvas.Clear(SKColors.Red.WithAlpha(100));
            Assert.Equal(expected, Compat.PdfCartonFontCompat.GetTransparency(image));
            Assert.Equal(expected, Compat.PdfCartonFontCompat.GetColorModel(image).Transparency);
        }
    }

    // A 2x1 GIF89a: a four-colour palette, index 0 transparent, pixels 0 and 1.
    private static byte[] TransparentGif() =>
    [
        (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a',
        2, 0, 1, 0, 0x81, 0, 0,
        0, 0, 0, 0, 0, 255, 255, 255, 255, 255, 0, 0,
        0x21, 0xF9, 4, 1, 0, 0, 0, 0,
        0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0,
        2, 2, 0x44, 0x0A, 0,
        0x3B,
    ];

    // A TYPE_INT_ARGB image is TRANSLUCENT even when every alpha is 0 or
    // 255, so LosslessFactory writes an 8-bit soft mask rather than its 1-bit
    // BITMASK one, and JPEGFactory accepts the image instead of refusing
    // BITMASK transparency. Java PDFBox 3.0.8: Lossless soft mask 8-bit,
    // ff000000 at (0,0) and ffffffff at (1,0); JPEG soft mask 8-bit DCT.
    [Fact]
    public void ImageFactoriesTreatAnArgbImageAsTranslucent()
    {
        using var image = Compat.PdfCartonFontCompat.CreateBitmap(20, 10, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        for (var y = 0; y < 10; y++)
            for (var x = 0; x < 20; x++)
                Compat.PdfCartonFontCompat.SetRgb(image, x, y, (x + y) % 3 == 0 ? 0x00000000 : unchecked((int)0xff3366cc));
        using var document = new PDDocument();
        var xobject = LosslessFactory.CreateFromImage(document, image);
        var mask = xobject.GetSoftMask();
        Assert.NotNull(mask);
        Assert.Equal(8, mask.GetBitsPerComponent());
        using var alpha = mask.GetImage();
        Assert.Equal(unchecked((int)0xff000000), Compat.PdfCartonFontCompat.GetRgb(alpha, 0, 0));
        Assert.Equal(unchecked((int)0xffffffff), Compat.PdfCartonFontCompat.GetRgb(alpha, 1, 0));

        var jpeg = JPEGFactory.CreateFromImage(document, image);
        var jpegMask = jpeg.GetSoftMask();
        Assert.NotNull(jpegMask);
        Assert.Equal(8, jpegMask.GetBitsPerComponent());
        Assert.Equal(COSName.DctDecode, jpegMask.GetCOSObject().GetFilters());
    }

    // A non-isolated group with blend modes, painted with Multiply over a
    // coloured backdrop. Its group image starts as a copy of the backdrop;
    // removeBackdrop must take that copy out again (Java PDFBox 3.0.8 output
    // below), or the backdrop is multiplied with itself. The oracle colours
    // are exact; the tolerance covers Skia's 8-bit blend rounding.
    [Fact]
    public void NonIsolatedBlendGroupRemovesItsBackdrop()
    {
        var expected = new (int X, int Y, uint Argb)[]
        {
            (5, 5, 0xffcc9966), // backdrop outside the group's content
            (52, 52, 0xffcc9966),
            (95, 5, 0xffcc9966),
            (30, 70, 0xff522e29), // opaque Multiply content
            (70, 30, 0xff667a47), // half-transparent Multiply content
            (56, 44, 0xff667a47),
        };
        using var document = Loader.LoadPDF((sbyte[])(Array)BlendGroupFixture());
        foreach (var type in new[] { ImageType.Rgb, ImageType.Argb })
        {
            using var image = new PDFRenderer(document).RenderImage(0, 1, type);
            foreach (var (x, y, argb) in expected)
            {
                var actual = (uint)Compat.PdfCartonFontCompat.GetRgb(image, x, y);
                for (var shift = 0; shift < 32; shift += 8)
                    Assert.True(Math.Abs((int)((actual >> shift) & 255) - (int)((argb >> shift) & 255)) <= 2,
                        $"pixel ({x},{y}) is {actual:x8}, Java PDFBox renders {argb:x8}.");
            }
        }
    }

    // BufferedImage.setRGB is O(1) in Java. On an image from CreateImage (the
    // gray + alpha transparency group of create2ByteGrayAlphaImage) each call
    // used to re-render every pixel, so the per-pixel loop of
    // GroupGraphics.removeBackdrop was O((width * height)^2).
    [Fact]
    public void SetRgbWritesOnePixelOfAnyImage()
    {
        const int Size = 1000;
        using var gray = GrayAlphaImage(Size, Size);
        using var grayPage = Compat.PdfCartonFontCompat.CreateBitmap(Size, Size, Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY);
        using var bgrPage = Compat.PdfCartonFontCompat.CreateBitmap(Size, Size, Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR);
        using var intBgr = Compat.PdfCartonFontCompat.CreateBitmap(Size, Size, Compat.PdfCartonFontCompat.TYPE_INT_BGR);
        using var materialized = Compat.PdfCartonFontCompat.CreateBitmap(Size, Size, Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY);
        _ = Compat.PdfCartonFontCompat.GetRaster(grayPage);
        _ = Compat.PdfCartonFontCompat.GetRaster(bgrPage);
        _ = Compat.PdfCartonFontCompat.GetRaster(intBgr);
        // DataBufferByte.getData() moves the samples into a managed array.
        _ = ((Compat.JavaDataBufferByte)Compat.PdfCartonFontCompat.GetRaster(materialized).GetDataBuffer()).GetData();
        foreach (var (name, image) in new[]
                 {
                     ("gray + alpha", gray), ("BYTE_GRAY", grayPage), ("3BYTE_BGR", bgrPage),
                     ("INT_BGR", intBgr), ("BYTE_GRAY with getData", materialized),
                 })
        {
            static int Argb(int x, int y) => unchecked((int)((uint)(x * 7 + y) % 256 << 24 |
                (uint)((x + y * 3) % 256) * 0x010101u));
            var started = System.Diagnostics.Stopwatch.GetTimestamp();
            for (var y = 0; y < Size; y++)
                for (var x = 0; x < Size; x++)
                    Compat.PdfCartonFontCompat.SetRgb(image, x, y, Argb(x, y));
            var elapsed = System.Diagnostics.Stopwatch.GetElapsedTime(started);
            // 10^6 one-pixel writes take well under a second; re-rendering the
            // image on each one took hours.
            Assert.True(elapsed < TimeSpan.FromSeconds(20), $"{name}: 10^6 SetRgb calls took {elapsed}.");

            // The raster (getRGB) holds each written pixel, and the native
            // pixels hold what the raster renders to.
            var mismatches = 0;
            var hasAlpha = Compat.PdfCartonFontCompat.GetColorModel(image).HasAlpha;
            for (var y = 0; y < Size; y += 7)
                for (var x = 0; x < Size; x += 3)
                {
                    var written = Argb(x, y);
                    var argb = Compat.PdfCartonFontCompat.GetRgb(image, x, y);
                    var native = Compat.PdfCartonPixels.Read(image, x, y);
                    if ((uint)argb >> 24 != (hasAlpha ? (uint)written >> 24 : 255u) ||
                        (argb & 0xffffff) != (written & 0xffffff) || (uint)native != (uint)argb)
                        mismatches++;
                }
            Assert.Equal(0, mismatches);

            for (var call = 0; call < 1000; call++)
                _ = Compat.PdfCartonFontCompat.GetRgb(image, call % Size, call / Size);
            var allocated = GC.GetAllocatedBytesForCurrentThread();
            var sum = 0L;
            for (var call = 0; call < 100_000; call++)
                sum += Compat.PdfCartonFontCompat.GetRgb(image, call % Size, call / Size);
            allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
            Assert.True(allocated < 1024, $"{name}: 10^5 GetRgb calls allocated {allocated} bytes ({sum}).");
        }
    }

    // In Java an image's raster is its only storage, so Graphics2D drawing
    // shows in getRGB and survives setRGB. A CreateImage image keeps a
    // managed raster beside its native pixels: it used to miss Skia's drawing,
    // and setRGB re-rendered the stale raster over the drawn pixels.
    [Fact]
    public void CreatedImageRasterSeesNativeDrawing()
    {
        using var image = GrayAlphaImage(40, 20);
        using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(image))
        {
            graphics.SetColor(new SKColor(0x99, 0x99, 0x99));
            graphics.FillRect(0, 0, 10, 10);
        }
        Assert.Equal(unchecked((int)0xff999999), Compat.PdfCartonFontCompat.GetRgb(image, 5, 5));
        Assert.Equal(0, Compat.PdfCartonFontCompat.GetRgb(image, 15, 5) >>> 24);

        Compat.PdfCartonFontCompat.SetRgb(image, 15, 5, unchecked((int)0xff404040));
        Assert.Equal(unchecked((int)0xff404040), Compat.PdfCartonFontCompat.GetRgb(image, 15, 5));
        // The drawn pixels are still drawn, in the raster and natively.
        Assert.Equal(unchecked((int)0xff999999), Compat.PdfCartonFontCompat.GetRgb(image, 5, 5));
        Assert.Equal(new SKColor(0x99, 0x99, 0x99), Compat.PdfCartonPixels.Read(image, 5, 5));
        Assert.Equal(new SKColor(0x40, 0x40, 0x40), Compat.PdfCartonPixels.Read(image, 15, 5));

        // Drawing after a raster read is seen too; undrawn pixels keep their
        // samples, and a copy of the image data holds the drawing.
        using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(image))
        {
            graphics.SetColor(SKColors.White);
            graphics.FillRect(20, 0, 10, 20);
        }
        var argb = new int[40 * 20];
        Compat.PdfCartonFontCompat.GetRgb(image, 0, 0, 40, 20, argb, 0, 40);
        Assert.Equal(unchecked((int)0xffffffff), argb[10 * 40 + 25]);
        Assert.Equal(unchecked((int)0xff404040), argb[5 * 40 + 15]);
        Assert.Equal(unchecked((int)0xff999999), argb[5 * 40 + 5]);
        Assert.Equal(0, argb[15 * 40 + 35] >>> 24);
        var samples = Compat.PdfCartonFontCompat.GetImageData(image).GetPixel(25, 10, (int[]?)null);
        Assert.Equal(new[] { 255, 255 }, samples);
    }

    // A draw changes only the raster samples of the pixels it paints, so
    // samples written into the retained raster survive drawing elsewhere,
    // and SetRgb still writes the image's pixels after they are reallocated.
    [Fact]
    public void CreatedImageRasterWritesSurviveNativeDrawing()
    {
        using var image = GrayAlphaImage(4, 4);
        var raster = Compat.PdfCartonFontCompat.GetRaster(image);
        raster.SetPixel(3, 3, [64, 255]);
        using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(image))
        {
            graphics.ClearRect(0, 0, 1, 1);
            graphics.SetColor(SKColors.White);
            graphics.FillRect(0, 0, 2, 2);
        }
        raster.SetPixel(3, 2, [32, 255]);
        Assert.Equal(unchecked((int)0xff404040), Compat.PdfCartonFontCompat.GetRgb(image, 3, 3));
        Assert.Equal(unchecked((int)0xff202020), Compat.PdfCartonFontCompat.GetRgb(image, 3, 2));
        Assert.Equal(unchecked((int)0xffffffff), Compat.PdfCartonFontCompat.GetRgb(image, 1, 1));
        Assert.Equal(0, Compat.PdfCartonFontCompat.GetRgb(image, 2, 0) >>> 24);

        Compat.PdfCartonFontCompat.SetRgb(image, 0, 3, unchecked((int)0xff808080));
        Assert.True(image.TryAllocPixels(image.Info));
        image.Erase(SKColors.Transparent);
        Compat.PdfCartonFontCompat.SetRgb(image, 1, 3, unchecked((int)0xff909090));
        Assert.Equal(new SKColor(0x90, 0x90, 0x90), Compat.PdfCartonPixels.Read(image, 1, 3));
        Assert.Equal(unchecked((int)0xff808080), Compat.PdfCartonFontCompat.GetRgb(image, 0, 3));
    }

    // setRGB converts each channel on its own: red, green, blue and alpha land
    // in their own samples and native channels, and the neighbours are kept.
    [Fact]
    public void SetRgbPreservesDistinctColorChannels()
    {
        const int Argb = unchecked((int)0x80c04020);
        using var bgr = Compat.PdfCartonFontCompat.CreateBitmap(3, 3, Compat.PdfCartonFontCompat.TYPE_3BYTE_BGR);
        using var intArgb = Compat.PdfCartonFontCompat.CreateBitmap(3, 3, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        using var intBgr = Compat.PdfCartonFontCompat.CreateBitmap(3, 3, Compat.PdfCartonFontCompat.TYPE_INT_BGR);
        using var srgb = Compat.PdfCartonFontCompat.CreateImage(
            Compat.PdfCartonFontCompat.ComponentColorModel(
                Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_sRGB),
                true, false, Compat.PdfCartonTransparency.TRANSLUCENT,
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE),
            Compat.PdfCartonFontCompat.CreateInterleavedRaster(
                Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, 3, 3, 4, new Compat.JavaPoint(0, 0)),
            false,
            null);
        foreach (var (name, image, hasAlpha, samples) in new[]
                 {
                     ("3BYTE_BGR", bgr, false, new[] { 0xc0, 0x40, 0x20 }),
                     ("INT_ARGB", intArgb, true, new[] { 0xc0, 0x40, 0x20, 0x80 }),
                     ("INT_BGR", intBgr, false, new[] { 0xc0, 0x40, 0x20 }),
                     ("sRGB + alpha CreateImage", srgb, true, new[] { 0xc0, 0x40, 0x20, 0x80 }),
                 })
        {
            var raster = Compat.PdfCartonFontCompat.GetRaster(image);
            var neighbour = Compat.PdfCartonFontCompat.GetRgb(image, 0, 1);
            var neighbourNative = Compat.PdfCartonPixels.Read(image, 2, 1);
            Compat.PdfCartonFontCompat.SetRgb(image, 1, 1, Argb);
            var expected = hasAlpha ? Argb : Argb | unchecked((int)0xff000000);
            Assert.True(expected == Compat.PdfCartonFontCompat.GetRgb(image, 1, 1), name);
            Assert.True((uint)expected == (uint)Compat.PdfCartonPixels.Read(image, 1, 1), name);
            Assert.True(samples.SequenceEqual(raster.GetPixel(1, 1, (int[]?)null)), name);
            Assert.True(neighbour == Compat.PdfCartonFontCompat.GetRgb(image, 0, 1), name);
            Assert.True(neighbourNative == Compat.PdfCartonPixels.Read(image, 2, 1), name);
        }
    }

    // A non-isolated group inside a form XObject whose resources hold blend
    // modes, with none in the page's own resources, leaves a GRAY or BGR page
    // image as it is (PDFRenderer.hasBlendMode), so GroupGraphics.removeBackdrop
    // reads the backdrop and writes the group pixel by pixel through
    // getRGB/setRGB. A DeviceGray group is create2ByteGrayAlphaImage's
    // CreateImage image: Multiply into it used to throw, and with the blend
    // mode unused its backdrop removal re-rendered the image per pixel over a
    // raster that missed the drawn content, leaving the content black.
    // Every render matches the RGB one, whose backdrop removal for a DeviceRGB
    // group is the DataBufferInt path (NonIsolatedBlendGroupRemovesItsBackdrop
    // checks it against Java PDFBox), and the gray values computed by hand,
    // within the one rounding step an 8-bit conversion may take.
    [Fact]
    public void FormBlendGroupsRenderOnGrayAndBgrPages()
    {
        foreach (var blend in new[] { true, false })
            foreach (var groupSpace in new[] { "DeviceGray", "DeviceRGB" })
            {
                var expected = new (int X, int Y, int Gray)[]
                {
                    (5, 5, 204), // backdrop outside the group's content: 0.8
                    (52, 52, 204),
                    (95, 5, 204),
                    // Multiply: 0.8 * 0.5; Normal: 0.5.
                    (30, 70, blend ? 102 : 128),
                    // Multiply at alpha 0.5: 0.8 * (0.5 + 0.5 * 0.25); Normal: 0.25.
                    (70, 30, blend ? 128 : 64),
                    (56, 44, blend ? 128 : 64),
                };
                var name = $"{groupSpace} group{(blend ? "" : ", blend mode unused")}";
                using var document = Loader.LoadPDF((sbyte[])(Array)FormBlendGroupFixture(groupSpace, blend));
                using var reference = new PDFRenderer(document).RenderImage(0, 10, ImageType.Rgb);
                foreach (var (type, typeName) in new[] { (ImageType.Rgb, "RGB"), (ImageType.Gray, "GRAY"), (ImageType.Bgr, "BGR") })
                {
                    var started = System.Diagnostics.Stopwatch.GetTimestamp();
                    using var image = new PDFRenderer(document).RenderImage(0, 10, type);
                    var elapsed = System.Diagnostics.Stopwatch.GetElapsedTime(started);
                    // Well under a second; the per-pixel re-render took hours.
                    Assert.True(elapsed < TimeSpan.FromSeconds(30),
                        $"{name}, {typeName}: rendering 1000x1000 pixels took {elapsed}.");
                    foreach (var (x, y, gray) in expected)
                    {
                        // Pixel centres of the 1000x1000 render.
                        var actual = Compat.PdfCartonFontCompat.GetRgb(image, x * 10 + 5, y * 10 + 5);
                        var rgb = Compat.PdfCartonFontCompat.GetRgb(reference, x * 10 + 5, y * 10 + 5);
                        for (var shift = 0; shift < 24; shift += 8)
                        {
                            var channel = actual >> shift & 255;
                            Assert.True(Math.Abs(channel - (rgb >> shift & 255)) <= 1,
                                $"{name}, {typeName}: pixel ({x},{y}) is {actual:x8}, the RGB render {rgb:x8}.");
                            Assert.True(Math.Abs(channel - gray) <= 1,
                                $"{name}, {typeName}: pixel ({x},{y}) is {actual:x8}, expected gray {gray}.");
                        }
                    }
                }
            }
    }

    // PageDrawer.create2ByteGrayAlphaImage: Java's gray + alpha image over
    // an interleaved byte raster, alpha first in memory.
    private static SKBitmap GrayAlphaImage(int width, int height)
    {
        var model = Compat.PdfCartonFontCompat.ComponentColorModel(
            Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_GRAY),
            true, false, Compat.PdfCartonTransparency.TRANSLUCENT,
            Compat.PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE);
        var raster = Compat.PdfCartonFontCompat.CreateInterleavedRaster(
            new Compat.JavaDataBufferByte(width * height * 2), width, height, width * 2, 2, [1, 0],
            new Compat.JavaPoint(0, 0));
        return Compat.PdfCartonFontCompat.CreateImage(model, raster, false, null);
    }

    private static byte[] BlendGroupFixture() =>
        PdfFixture(new SortedDictionary<int, string>
        {
            [3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /ExtGState << " +
                "/MUL 5 0 R >> /XObject << /G 6 0 R >> >> /Contents 4 0 R >>",
            [4] = FixtureStream("", "0.8 0.6 0.4 rg 0 0 100 100 re f q /MUL gs /G Do Q"),
            [5] = "<< /Type /ExtGState /BM /Multiply >>",
            [6] = FixtureStream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency " +
                "/CS /DeviceRGB >> /Resources << /ExtGState << /MUL 5 0 R /HM 7 0 R >> >>",
                "/MUL gs 0.5 0.5 1 rg 10 10 40 40 re f /HM gs 0 1 1 rg 55 55 35 35 re f"),
            [7] = "<< /Type /ExtGState /BM /Multiply /ca 0.5 >>",
        });

    // The blend modes are in the form's resources only; unless blend is set
    // the content paints with Normal.
    private static byte[] FormBlendGroupFixture(string groupSpace, bool blend) =>
        PdfFixture(new SortedDictionary<int, string>
        {
            [3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << " +
                "/XObject << /G 6 0 R >> >> /Contents 4 0 R >>",
            [4] = FixtureStream("", "0.8 g 0 0 100 100 re f /G Do"),
            [5] = "<< /Type /ExtGState /BM /Multiply >>",
            [6] = FixtureStream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Group << /S /Transparency " +
                $"/CS /{groupSpace} >> /Resources << /ExtGState << /MUL 5 0 R /HM 7 0 R >> >>",
                blend
                    ? "/MUL gs 0.5 g 10 10 40 40 re f /HM gs 0.25 g 55 55 35 35 re f"
                    : "0.5 g 10 10 40 40 re f 0.25 g 55 55 35 35 re f"),
            [7] = "<< /Type /ExtGState /BM /Multiply /ca 0.5 >>",
        });

    private static string FixtureStream(string entries, string data) =>
        $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";

    // A one-page document: catalog 1, pages 2, page 3 and the given objects.
    private static byte[] PdfFixture(SortedDictionary<int, string> objects)
    {
        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>";
        objects[2] = "<< /Type /Pages /Kids [3 0 R] /Count 1 >>";
        var text = new System.Text.StringBuilder("%PDF-1.7\n");
        var offsets = new Dictionary<int, int>();
        foreach (var (number, body) in objects)
        {
            offsets[number] = text.Length;
            text.Append($"{number} 0 obj\n{body}\nendobj\n");
        }
        var size = objects.Keys.Max() + 1;
        var xref = text.Length;
        text.Append($"xref\n0 {size}\n");
        for (var number = 0; number < size; number++)
            text.Append(offsets.TryGetValue(number, out var offset)
                ? $"{offset:D10} 00000 n \n"
                : "0000000000 65535 f \n");
        text.Append($"trailer\n<< /Size {size} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return System.Text.Encoding.ASCII.GetBytes(text.ToString());
    }
}

// /FlateDecode through the public FlateFilterDecoderStream and font loading.
public sealed class FlateConsumerTests
{
    [Fact]
    public void FlateDecodingMatchesTheInputAcrossFeedsReadSizesAndStreamSizes()
    {
        // Feed int.MaxValue gives PDFBox's own 2 KiB input / 4 KiB output pattern.
        foreach (int size in new[] { 0, 1, 4095, 4097, 70_000, 1_100_000 })
        {
            byte[] payload = FlatePayload(size);
            byte[] encoded = Zlib(payload, flushAt: -1, out _);
            foreach (int feed in new[] { 1, 7, 2048, int.MaxValue })
            foreach (int read in new[] { 1, 3, 4096, 65536 })
                Assert.Equal(payload, DecodeFlate(encoded, feed, read));
        }
    }

    [Fact]
    public void FlateDecodingAllocatesIndependentlyOfTheDecodedSize()
    {
        // The decoder once re-inflated everything fed so far on every 4 KiB
        // drain: about D * D / 2048 bytes of buffers for D decoded bytes.
        byte[] small = Zlib(FlatePayload(256 * 1024), flushAt: -1, out _);
        byte[] large = Zlib(FlatePayload(4 * 1024 * 1024), flushAt: -1, out _);
        AllocatedWhileDecoding(small);
        long smallAllocated = AllocatedWhileDecoding(small);
        long largeAllocated = AllocatedWhileDecoding(large);
        Assert.True(largeAllocated < 256 * 1024,
            $"Decoding 4 MiB allocated {largeAllocated} bytes (256 KiB: {smallAllocated}).");
    }

    [Fact]
    public void FlateDecodingRecoversWhatPdfBoxRecoversFromDamagedStreams()
    {
        // Expected lengths are Java PDFBox 3.0.8's FlateFilterDecoderStream on
        // identical bytes: stored blocks, then a header with BFINAL=1, dynamic
        // codes, HLIT=31 ("too many length or distance symbols"). PDFBox keeps
        // the non-zero prefix of its 4 KiB buffer after the failing inflate,
        // so how much it recovers depends on the feed and read sizes.
        byte[] payload = StoredPayload(9000);
        foreach (var (split, block, feed, read, expected) in new[]
                 {
                     (3000, 1000, 1, 4096, 3000), (3000, 1000, 2048, 1, 2034),
                     (3000, 65535, 7, 1, 2999), (6000, 1000, 7, 1, 5998),
                     (6000, 1000, 1, 8192, 6000), (6000, 65535, 2048, 1, 4092),
                     (6000, 65535, 2048, 4096, 4096), (6000, 65535, 2048, 8192, 6000),
                 })
        {
            byte[] damaged = Stored(payload, split, block, last: false, [0xFD, 0, 0, 0]);
            Assert.Equal(payload[..expected], DecodeFlate(damaged, feed, read));
        }

        // Truncated input yields every byte it holds; trailing bytes are ignored.
        byte[] whole = Stored(payload, payload.Length, 1000, last: true, [1, 2, 3, 4]);
        foreach (int feed in new[] { 1, 2048 })
        foreach (int read in new[] { 1, 4096 })
        {
            Assert.Equal(payload[..4573], DecodeFlate(whole[..4600], feed, read));
            Assert.Equal(payload, DecodeFlate([.. whole, .. "garbage"u8.ToArray()], feed, read));
        }

        // Huffman-coded data damaged right after a sync flush.
        byte[] coded = FlatePayload(9000);
        foreach (int split in new[] { 3000, 6000 })
        {
            byte[] damaged = Zlib(coded, split, out int boundary);
            damaged[boundary] = 0xFD;
            Assert.Equal(coded[..split], DecodeFlate(damaged, 1, 4096));
        }
        byte[] withoutChecksum = Zlib(coded, -1, out _)[..^4];
        Assert.Equal(coded, DecodeFlate(withoutChecksum, 13, 4096));
    }

    [Fact]
    public void WholeEmbeddedFontsLoadWithLinearFlateDecoding()
    {
        // Stands in for the reported page with two whole ~1 MB fonts: PdfCarton's
        // bundled LiberationSans-Regular (SIL OFL, 410 KiB) embedded whole twice.
        byte[] original;
        using (Stream resource = OpenLiberationSans())
        using (var copy = new MemoryStream())
        {
            resource.CopyTo(copy);
            original = copy.ToArray();
        }

        byte[] saved;
        using (var document = new PDDocument())
        {
            var page = new PDPage();
            document.AddPage(page);
            var resources = new PDResources();
            for (int copy = 0; copy < 2; copy++)
            {
                using Stream font = OpenLiberationSans();
                resources.Add(PDType0Font.Load(document, font, false));
            }
            page.SetResources(resources);
            using var output = new MemoryStream();
            document.Save(output);
            saved = output.ToArray();
        }

        using PDDocument reopened = Loader.LoadPDF(Signed(saved));
        PDResources loaded = reopened.GetPage(0).GetResources();
        int fonts = 0;
        foreach (COSName name in loaded.GetFontNames())
        {
            var font = Assert.IsType<PDType0Font>(loaded.GetFont(name));
            Assert.Equal("LiberationSans", font.GetDescendantFont().GetBaseFont());
            Assert.True(font.GetStringWidth("PdfCarton") > 0);
            PDStream file = font.GetDescendantFont().GetFontDescriptor().GetFontFile2();
            Assert.Equal(COSName.FlateDecode, file.GetFilters()[0]);
            long before = GC.GetAllocatedBytesForCurrentThread();
            sbyte[] decoded = file.ToByteArray();
            long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
            Assert.Equal(original, (byte[])(Array)decoded);
            Assert.True(allocated < 16L * original.Length,
                $"Decoding a {original.Length}-byte font allocated {allocated} bytes.");
            fonts++;
        }
        Assert.Equal(2, fonts);
    }

    private static sbyte[] Signed(byte[] bytes) => (sbyte[])(Array)bytes;

    private static Stream OpenLiberationSans() =>
        typeof(PDDocument).Assembly.GetManifestResourceStream(
            "org.apache.pdfbox.resources.ttf.LiberationSans-Regular.ttf")
        ?? throw new InvalidOperationException("PdfCarton font resource is missing.");

    // Deterministic compressible bytes with no zeros, so PDFBox's zero-trimming
    // recovery cannot hide a lost tail.
    private static byte[] FlatePayload(int length)
    {
        var payload = new byte[length];
        uint state = 12345;
        for (int index = 0; index < length; index++)
        {
            state = state * 1103515245 + 12345;
            payload[index] = index > 32 && ((state >> 8) & 3) == 0
                ? payload[index - 1 - (int)((state >> 20) & 31)]
                : (byte)('a' + (state >> 16) % 23);
        }
        return payload;
    }

    private static byte[] StoredPayload(int length)
    {
        var payload = new byte[length];
        for (int index = 0; index < length; index++)
            payload[index] = (byte)('a' + (index * 7 + index / 13) % 23);
        return payload;
    }

    // A zlib header, the first count bytes in stored blocks of at most block
    // bytes, then tail.
    private static byte[] Stored(byte[] payload, int count, int block, bool last, byte[] tail)
    {
        using var output = new MemoryStream();
        output.Write([0x78, 0x01]);
        for (int offset = 0; offset < count; offset += block)
        {
            int length = Math.Min(block, count - offset);
            output.WriteByte(last && offset + length == count ? (byte)1 : (byte)0);
            output.Write([(byte)length, (byte)(length >> 8), (byte)~length, (byte)(~length >> 8)]);
            output.Write(payload, offset, length);
        }
        output.Write(tail);
        return output.ToArray();
    }

    // zlib-wrapped data; with flushAt >= 0, a sync flush after that many bytes
    // leaves the next block starting byte-aligned at boundary.
    private static byte[] Zlib(byte[] payload, int flushAt, out int boundary)
    {
        using var output = new MemoryStream();
        boundary = -1;
        using (var zlib = new System.IO.Compression.ZLibStream(
                   output, System.IO.Compression.CompressionLevel.Optimal, leaveOpen: true))
        {
            if (flushAt >= 0)
            {
                zlib.Write(payload, 0, flushAt);
                zlib.Flush();
                boundary = checked((int)output.Length);
                zlib.Write(payload, flushAt, payload.Length - flushAt);
            }
            else
            {
                zlib.Write(payload);
            }
        }
        return output.ToArray();
    }

    private static byte[] DecodeFlate(byte[] encoded, int feed, int read)
    {
        using var decoder = new FlateFilterDecoderStream(new TricklingInputStream(encoded, feed));
        var buffer = new sbyte[read];
        using var decoded = new MemoryStream();
        int count;
        while ((count = decoder.Read(buffer, 0, buffer.Length)) > 0)
            decoded.Write((byte[])(Array)buffer, 0, count);
        return decoded.ToArray();
    }

    private static long AllocatedWhileDecoding(byte[] encoded)
    {
        var source = new TricklingInputStream(encoded, int.MaxValue);
        var buffer = new sbyte[4096];
        long total = 0;
        long before = GC.GetAllocatedBytesForCurrentThread();
        using (var decoder = new FlateFilterDecoderStream(source))
        {
            int count;
            while ((count = decoder.Read(buffer, 0, buffer.Length)) > 0)
                total += count;
        }
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.True(total > 0);
        return allocated;
    }

    // A PDF stream source over memory that returns at most feed bytes per read.
    private sealed class TricklingInputStream(byte[] bytes, int feed)
        : RandomAccessInputStream(new RandomAccessReadBuffer(Signed(bytes)))
    {
        public override int Read(sbyte[] b, int off, int len) =>
            base.Read(b, off, Math.Min(len, feed));
    }
}

// Java equality and hashing behind translated HashMap/LinkedHashMap keys.
// Keys that are enumerable but not IList (strings, COSArray) used to scan the
// key type's interfaces on every comparison and hash, allocating each time.
public sealed class JavaEqualityConsumerTests
{
    private const int Lookups = 1000;

    [Fact]
    public void StringKeyedMapHitWithANonInternedKeyAllocatesNothing()
    {
        var map = new DripSharp.Runtime.JavaLinkedHashMap<string, int>();
        map["BT"] = 1;
        map["Tj"] = 2;
        string probe = new string(new[] { 'T', 'j' });
        Assert.NotSame(string.IsInterned(probe), probe);

        AssertNoAllocation(() => map.TryGetValue(probe, out int value) && value == 2);
    }

    [Fact]
    public void BoxedPrimitiveKeysCompareWithoutAllocating()
    {
        var map = new DripSharp.Runtime.JavaLinkedHashMap<object, int>();
        object stored = 42;
        object probe = 42;
        object wider = 42L;
        map[stored] = 1;
        Assert.NotSame(stored, probe);

        AssertNoAllocation(() => map.TryGetValue(probe, out int value) && value == 1);
        // Java's Integer.equals(Long) is false.
        Assert.False(map.ContainsKey(wider));
    }

    [Fact]
    public void EnumerableNonListKeysHashAndCompareWithoutAllocating()
    {
        var arrays = new DripSharp.Runtime.JavaLinkedHashMap<COSBase, int>();
        var array = new COSArray();
        array.Add(COSInteger.Get(1));
        arrays[array] = 1;
        AssertNoAllocation(() => arrays.TryGetValue(array, out int value) && value == 1);

        var tokens = new DripSharp.Runtime.JavaLinkedHashMap<Token, int>();
        tokens[new Token("cm")] = 3;
        var probe = new Token("cm");
        AssertNoAllocation(() => tokens.TryGetValue(probe, out int value) && value == 3);
    }

    [Fact]
    public void ListSetMapAndUriKeysKeepJavaEquality()
    {
        var map = new DripSharp.Runtime.JavaLinkedHashMap<object, string>();
        map[new List<object> { 1, "a" }] = "list";
        map[new HashSet<object> { 1, 2, 3 }] = "set";
        map[new Dictionary<string, int> { ["k"] = 1 }] = "map";
        map[new Uri("http://Example.com/a")] = "uri";
        map[new object[] { 7 }] = "array";

        // java.util.List equality holds across list implementations.
        Assert.Equal("list", Lookup(map, new ReadOnlyItems(1, "a")));
        Assert.Null(Lookup(map, new ReadOnlyItems("a", 1)));
        Assert.Equal("set", Lookup(map, new SortedSet<object> { 3, 2, 1 }));
        Assert.Null(Lookup(map, new List<object> { 1, 2, 3 }));
        Assert.Null(Lookup(map, new HashSet<object> { 1, "a" }));
        var linked = new DripSharp.Runtime.JavaLinkedHashMap<string, int>();
        linked["k"] = 1;
        Assert.Equal("map", Lookup(map, linked));
        Assert.Equal("uri", Lookup(map, new Uri("http://example.com/a")));
        // Arrays keep identity equality.
        Assert.Null(Lookup(map, new object[] { 7 }));
        Assert.Null(Lookup(map, "a"));
    }

    // The per-type classification cache must not root collectible types.
    [Fact]
    public void CollectibleEnumerableKeyTypeIsNotRetainedAfterTheMapIsCleared()
    {
        var map = new DripSharp.Runtime.JavaLinkedHashMap<object, int>();
        WeakReference type = UseCollectibleListKey(map);
        map.Clear();
        for (int attempt = 0; attempt < 20 && type.IsAlive; attempt++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
        Assert.False(type.IsAlive);
    }

    [System.Runtime.CompilerServices.MethodImpl(
        System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static WeakReference UseCollectibleListKey(DripSharp.Runtime.JavaLinkedHashMap<object, int> map)
    {
        var assembly = System.Reflection.Emit.AssemblyBuilder.DefineDynamicAssembly(
            new System.Reflection.AssemblyName("PdfCartonCollectibleKey"),
            System.Reflection.Emit.AssemblyBuilderAccess.RunAndCollect);
        var builder = assembly.DefineDynamicModule("Keys").DefineType(
            "CollectibleList",
            System.Reflection.TypeAttributes.Public | System.Reflection.TypeAttributes.Sealed,
            typeof(List<object>));
        builder.DefineDefaultConstructor(System.Reflection.MethodAttributes.Public);
        Type type = builder.CreateType();
        var key = (List<object>)Activator.CreateInstance(type)!;
        key.Add(1);
        map[key] = 1;
        // Hashing and list equality both classify the collectible key type.
        Assert.True(map.ContainsKey(new List<object> { 1 }));
        return new WeakReference(type);
    }

    private static string? Lookup(DripSharp.Runtime.JavaLinkedHashMap<object, string> map, object key) =>
        map.TryGetValue(key, out string? value) ? value : null;

    private static void AssertNoAllocation(Func<bool> lookup)
    {
        Assert.True(lookup());
        long before = GC.GetAllocatedBytesForCurrentThread();
        bool hit = true;
        for (int index = 0; index < Lookups; index++) hit &= lookup();
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.True(hit);
        Assert.Equal(0, allocated);
    }

    private sealed class Token(string text) : IEnumerable<char>
    {
        private readonly string text = text;
        public override bool Equals(object? other) => other is Token token && token.text == text;
        public override int GetHashCode() => text.GetHashCode();
        public IEnumerator<char> GetEnumerator() => text.GetEnumerator();
        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class ReadOnlyItems(params object[] items) : IReadOnlyList<object>
    {
        public object this[int index] => items[index];
        public int Count => items.Length;
        public IEnumerator<object> GetEnumerator() => ((IEnumerable<object>)items).GetEnumerator();
        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    }
}

// java.util.Arrays.hashCode behind COSName, COSString, Matrix and TextPosition
// hashing, and the JavaLinkedHashMap behind every COSDictionary. Expected
// hashes are java.util.Arrays.hashCode results from a JVM.
public sealed class JavaArrayHashAndLinkedMapConsumerTests
{
    private const int Calls = 1000;

    [Fact]
    public void ArrayHashMatchesJavaArraysHashCodeForEveryElementType()
    {
        var bytes = new sbyte[] { 0, 1, -1, 127, -128, 64 };
        var shorts = new short[] { 0, 1, -1, short.MaxValue, short.MinValue };
        var chars = new[] { 'a', '\0', '￿', 'é' };
        var ints = new[] { 0, 1, -1, int.MaxValue, int.MinValue };
        var longs = new[] { 0L, 1L, -1L, long.MaxValue, long.MinValue, 0x100000000L };
        var floats = new[]
        {
            0f, -0f, 1.5f, float.NaN, float.PositiveInfinity, float.NegativeInfinity,
            float.Epsilon, IntBitsToFloat(0x7f800001), IntBitsToFloat(unchecked((int)0xffc00000)),
        };
        var doubles = new[]
        {
            0d, -0d, 1.5d, double.NaN, double.PositiveInfinity, double.NegativeInfinity,
            double.Epsilon, BitConverter.Int64BitsToDouble(0x7ff0000000000001L), 1.0E300,
        };
        var booleans = new[] { true, false, true };
        var objects = new object?[] { "a", null, "PdfCarton" };

        Assert.Equal(888515554, Compat.JavaCompat.ArrayHash(bytes));
        Assert.Equal(29640990, Compat.JavaCompat.ArrayHash(shorts));
        Assert.Equal(5845066, Compat.JavaCompat.ArrayHash(chars));
        Assert.Equal(28657950, Compat.JavaCompat.ArrayHash(ints));
        Assert.Equal(888427203, Compat.JavaCompat.ArrayHash(longs));
        Assert.Equal(-1001818912, Compat.JavaCompat.ArrayHash(floats));
        Assert.Equal(-863435136, Compat.JavaCompat.ArrayHash(doubles));
        Assert.Equal(1252360, Compat.JavaCompat.ArrayHash(booleans));
        Assert.Equal(1962336497, Compat.JavaCompat.ArrayHash(objects));
        Assert.Equal(1, Compat.JavaCompat.ArrayHash(new sbyte[0]));
        Assert.Equal(0, Compat.JavaCompat.ArrayHash((sbyte[]?)null));
        Assert.Equal(0, Compat.JavaCompat.ArrayHash((object?[]?)null));

        // The untyped entry point dispatches to the same Java hashes.
        Array[] arrays = { bytes, shorts, chars, ints, longs, floats, doubles, booleans, objects };
        int[] expected =
        {
            888515554, 29640990, 5845066, 28657950, 888427203,
            -1001818912, -863435136, 1252360, 1962336497,
        };
        for (int index = 0; index < arrays.Length; index++)
            Assert.Equal(expected[index], Compat.JavaCompat.ArrayHash(arrays[index]));
        Assert.Equal(0, Compat.JavaCompat.ArrayHash((Array?)null));
        Assert.Equal(1962336497, Compat.JavaCompat.ArrayHash(new[] { "a", null, "PdfCarton" }));
    }

    [Fact]
    public void PrimitiveArrayHashingAndCOSNameHashingAllocateNothing()
    {
        var bytes = new sbyte[] { 1, -2, 3, -4 };
        var floats = new[] { 1f, -0f, float.NaN };
        var doubles = new[] { 1d, -0d, double.NaN };
        AssertNoAllocation(() => Compat.JavaCompat.ArrayHash(bytes) != 0);
        AssertNoAllocation(() => Compat.JavaCompat.ArrayHash(floats) != 0);
        AssertNoAllocation(() => Compat.JavaCompat.ArrayHash(doubles) != 0);

        COSName type = COSName.Type;
        Assert.Equal(3545819, type.GetHashCode());
        Assert.Equal(7722, COSName.GetPDFName(new sbyte[] { -23, -1, 65 }).GetHashCode());
        AssertNoAllocation(() => type.GetHashCode() == 3545819);
    }

    [Fact]
    public void COSStreamDescriptionUsesTheJavaHashOfItsRawBytes()
    {
        var stream = new COSStream();
        using (var output = stream.CreateRawOutputStream())
            output.Write(new byte[] { 0x80, 0xff, 0, 1, 127 });
        Assert.EndsWith("COSStream{-89611170}", stream.ToString());
    }

    [Fact]
    public void InsertionHashesTheKeyTwiceAndAllocatesNoEldestEntry()
    {
        var map = new Compat.JavaLinkedHashMap<CountingKey, int>(Calls * 2);
        var keys = new CountingKey[Calls];
        for (int index = 0; index < keys.Length; index++) keys[index] = new CountingKey(index);

        CountingKey.Reset();
        Compat.JavaCompat.MapPut(map, keys[0], 0);
        Assert.Equal(2, CountingKey.Hashes);
        CountingKey.Reset();
        Compat.JavaCompat.MapPut(map, keys[0], 1);
        Assert.Equal(1, CountingKey.Hashes);

        // One entry object per insertion: no separate list node and no eldest
        // entry for the RemoveEldestEntry check (both were allocated before).
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int index = 1; index < keys.Length; index++) Compat.JavaCompat.MapPut(map, keys[index], index);
        long perInsertion = (GC.GetAllocatedBytesForCurrentThread() - before) / (keys.Length - 1);
        Assert.InRange(perInsertion, 1, 64);

        var dictionary = new COSDictionary();
        dictionary.SetItem(COSName.Type, COSName.Page);
        long putBefore = GC.GetAllocatedBytesForCurrentThread();
        for (int index = 0; index < Calls; index++) dictionary.SetItem(COSName.Type, COSName.Page);
        Assert.Equal(0, GC.GetAllocatedBytesForCurrentThread() - putBefore);
    }

    [Fact]
    public void RemoveEldestEntryOverrideReceivesTheLiveEldestEntry()
    {
        var cache = new BoundedMap(2);
        cache["a"] = 1;
        cache["b"] = 2;
        cache["c"] = 3;
        Assert.Equal(new[] { "b", "c" }, cache.Keys.ToArray());
        cache["b"] = 20;
        cache["d"] = 4;
        Assert.Equal(new[] { "c", "d" }, cache.Keys.ToArray());
        Assert.Equal(new[] { "a=1", "a=1", "a=1", "b=20" }, cache.Seen.ToArray());
    }

    [Fact]
    public void EntrySetIterationTakesNoSnapshotAndDoesNoLookups()
    {
        var map = new Compat.JavaLinkedHashMap<CountingKey, int>();
        for (int index = 0; index < 100; index++) map[new CountingKey(index)] = index;
        var entries = Compat.JavaCompat.MapEntrySet(map);

        CountingKey.Reset();
        int sum = 0;
        foreach (var entry in entries) sum += entry.Value + entry.Key.Id;
        Assert.Equal(2 * 4950, sum);
        Assert.Equal(0, CountingKey.Hashes);
        Assert.Equal(0, CountingKey.Comparisons);

        var dictionary = new COSDictionary();
        for (int index = 0; index < 100; index++)
            dictionary.SetInt(COSName.GetPDFName("K" + index), index);
        long before = GC.GetAllocatedBytesForCurrentThread();
        int count = 0;
        foreach (var entry in dictionary.EntrySet()) count += entry.Value is COSInteger ? 1 : 0;
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(100, count);
        // The entry-set view and its enumerator, independent of the size.
        Assert.InRange(allocated, 1, 256);
    }

    [Fact]
    public void EntrySetIterationKeepsMutationDuringIterationSemantics()
    {
        var map = new Compat.JavaLinkedHashMap<string, int>();
        foreach (var key in new[] { "a", "b", "c", "d", "e" }) map[key] = 0;
        var visited = new List<string>();
        foreach (var entry in Compat.JavaCompat.MapEntrySet(map))
        {
            visited.Add(entry.Key);
            entry.SetValue(entry.Key[0]);
            if (entry.Key == "a")
            {
                map.Remove("a");    // the current entry
                map.Remove("c");    // not reached yet: skipped
                map["f"] = 0;       // added after the iterator started: not visited
            }
        }
        Assert.Equal(new[] { "a", "b", "d", "e" }, visited);
        Assert.Equal(new[] { "b=98", "d=100", "e=101", "f=0" },
            map.Select(pair => pair.Key + "=" + pair.Value).ToArray());

        // Iterator.remove() removes the entry returned by next(), also after
        // hasNext() has prepared the following entry.
        var iterator = Compat.JavaCompat.MapEntrySet(map).GetEnumerator();
        var first = Compat.JavaCompat.IteratorNext(iterator);
        Assert.True(Compat.JavaCompat.IteratorHasNext(iterator));
        Compat.JavaCompat.IteratorRemove(iterator);
        Assert.Throws<InvalidOperationException>(() => Compat.JavaCompat.IteratorRemove(iterator));
        Assert.Equal("b", first.Key);
        Assert.Equal("d", Compat.JavaCompat.IteratorNext(iterator).Key);
        Compat.JavaCompat.IteratorRemove(iterator);
        Assert.Equal(new[] { "e", "f" }, map.Keys.ToArray());

        // A removed entry is detached: it keeps its last value and no longer
        // writes through to the map, like Java's LinkedHashMap nodes.
        Assert.Equal(98, first.Value);
        var stale = Compat.JavaCompat.MapEntrySet(map).First();
        map.Remove("e");
        stale.SetValue(7);
        Assert.False(map.ContainsKey("e"));
        Assert.Equal(7, stale.Value);

        // Clearing the map ends an iteration in progress.
        map["g"] = 1;
        int seen = 0;
        foreach (var entry in Compat.JavaCompat.MapEntrySet(map))
        {
            seen++;
            map.Clear();
        }
        Assert.Equal(1, seen);

        // Key, value and pair enumeration still fail fast on modification.
        map["x"] = 1;
        map["y"] = 2;
        Assert.Throws<InvalidOperationException>(() =>
        {
            foreach (var key in map.Keys) map.Remove("y");
        });
    }

    [Fact]
    public void AccessOrderedEntrySetIterationAndEntryUpdatesKeepAccessOrder()
    {
        var map = new Compat.JavaLinkedHashMap<string, int>(8, 0.75f, true);
        map["a"] = 1;
        map["b"] = 2;
        map["c"] = 3;
        foreach (var entry in Compat.JavaCompat.MapEntrySet(map))
            if (entry.Key == "a") entry.SetValue(10);
        Assert.Equal(new[] { "a=10", "b=2", "c=3" },
            map.Select(pair => pair.Key + "=" + pair.Value).ToArray());
        Assert.Equal(2, Compat.JavaCompat.MapGet(map, "b"));
        Assert.Equal(new[] { "a", "c", "b" },
            Compat.JavaCompat.MapEntrySet(map).Select(entry => entry.Key).ToArray());
    }

    // Entries escape through entrySet() and RemoveEldestEntry, so a detached
    // entry must not keep later entries (and their values) reachable.
    [Fact]
    public void DetachedEntriesDoNotRetainOtherEntriesOrValues()
    {
        var map = new Compat.JavaLinkedHashMap<int, object>();
        var saved = ChurnAfterSavingAnEntry(map, out List<WeakReference> evicted);
        CollectUntilDead(evicted);
        Assert.All(evicted, value => Assert.False(value.IsAlive));
        Assert.False(map.ContainsKey(saved.Key));

        var kept = FillThenClear(map, out List<WeakReference> cleared);
        CollectUntilDead(cleared);
        Assert.All(cleared, value => Assert.False(value.IsAlive));
        Assert.Empty(map);
        GC.KeepAlive(saved);
        GC.KeepAlive(kept);
    }

    [Fact]
    public void SnapshotBackedEntriesStayDetachedAfterRemoval()
    {
        var maps = new IDictionary<string, int>[]
        {
            new Dictionary<string, int>(),
            new Compat.JavaLinkedHashMap<string, int>(8, 0.75f, true),
        };
        foreach (var map in maps)
        {
            map["k"] = 1;
            map["other"] = 2;
            var entry = Compat.JavaCompat.MapEntrySet(map).First(item => item.Key == "k");
            var other = Compat.JavaCompat.MapEntrySet(map).First(item => item.Key == "other");
            map.Remove("k");
            Assert.Equal(1, entry.SetValue(5));
            Assert.Equal(5, entry.Value);
            Assert.False(map.ContainsKey("k"));
            // An entry whose key is still present writes through.
            Assert.Equal(2, other.SetValue(3));
            Assert.Equal(3, map["other"]);
        }
    }

    [System.Runtime.CompilerServices.MethodImpl(
        System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static Compat.JavaMapEntry<int, object> ChurnAfterSavingAnEntry(
        Compat.JavaLinkedHashMap<int, object> map, out List<WeakReference> evicted)
    {
        evicted = new List<WeakReference>();
        map[0] = new object();
        var saved = Compat.JavaCompat.MapEntrySet(map).First();
        map.Remove(0);
        for (int key = 1; key <= 50; key++)
        {
            var value = new object();
            map[key] = value;
            if (key > 1) map.Remove(key - 1);
            if (key < 50) evicted.Add(new WeakReference(value));
        }
        map.Remove(50);
        return saved;
    }

    [System.Runtime.CompilerServices.MethodImpl(
        System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static Compat.JavaMapEntry<int, object> FillThenClear(
        Compat.JavaLinkedHashMap<int, object> map, out List<WeakReference> cleared)
    {
        cleared = new List<WeakReference>();
        for (int key = 0; key < 50; key++)
        {
            var value = new object();
            map[key] = value;
            if (key > 0) cleared.Add(new WeakReference(value));
        }
        var kept = Compat.JavaCompat.MapEntrySet(map).First();
        map.Clear();
        return kept;
    }

    private static void CollectUntilDead(List<WeakReference> references)
    {
        for (int attempt = 0; attempt < 20 && references.Any(reference => reference.IsAlive); attempt++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
    }

    private static float IntBitsToFloat(int bits) =>
        BitConverter.ToSingle(BitConverter.GetBytes(bits), 0);

    private static void AssertNoAllocation(Func<bool> call)
    {
        Assert.True(call());
        long before = GC.GetAllocatedBytesForCurrentThread();
        bool result = true;
        for (int index = 0; index < Calls; index++) result &= call();
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.True(result);
        Assert.Equal(0, allocated);
    }

    private sealed class CountingKey(int id)
    {
        [ThreadStatic] private static int hashes;
        [ThreadStatic] private static int comparisons;
        internal static int Hashes => hashes;
        internal static int Comparisons => comparisons;
        internal int Id { get; } = id;
        internal static void Reset()
        {
            hashes = 0;
            comparisons = 0;
        }
        public override int GetHashCode()
        {
            hashes++;
            return Id;
        }
        public override bool Equals(object? other)
        {
            comparisons++;
            return other is CountingKey key && key.Id == Id;
        }
    }

    private sealed class BoundedMap(int limit) : Compat.JavaLinkedHashMap<string, int>
    {
        internal List<string> Seen { get; } = new();
        protected internal override bool RemoveEldestEntry(Compat.JavaMapEntry<string, int> eldest)
        {
            Seen.Add(eldest.ToString());
            return Count > limit;
        }
    }
}

// java.util.WeakHashMap behind LegacyPDFStreamEngine.fontHeightMap (one lookup
// per glyph of PDFTextStripper output) and TilingPaintFactory. It was a list
// that every operation swept for collected keys and then scanned with equals.
public sealed class JavaWeakHashMapConsumerTests
{
    [Fact]
    public void OperationsCompareOnlyMatchingKeysWithTenThousandLiveKeys()
    {
        const int live = 10000;
        var keys = new CountingKey[live];
        var map = new Compat.JavaWeakHashMap<CountingKey, int>();
        for (int index = 0; index < live; index++)
        {
            keys[index] = new CountingKey(index, index);
            map[keys[index]] = index;
        }

        CountingKey.Reset();
        for (int index = 0; index < live; index++)
        {
            // An equal key that is a different instance, as Java equality allows.
            var probe = new CountingKey(index, index);
            Assert.True(map.TryGetValue(probe, out int value));
            Assert.Equal(index, value);
            Assert.True(map.ContainsKey(probe));
            Assert.Equal(index, Compat.JavaCompat.MapPut(map, probe, index + 1));
        }
        Assert.False(map.ContainsKey(new CountingKey(live, live)));
        // get + containsKey + (get, put) per key, each comparing one candidate.
        Assert.Equal(4 * live, CountingKey.Comparisons);

        // put on a present key keeps the original (strongly held) key, as Java
        // does, so no entry depends on a probe that is now garbage.
        CollectFully();
        Assert.Equal(live, map.Count);
        Assert.Equal(1, map[keys[0]]);
        Assert.Same(keys[live - 1], map.Keys.Last());
        GC.KeepAlive(keys);
    }

    [Fact]
    public void KeysKeepTheirEqualitySemanticsAndInsertionOrder()
    {
        // COSDictionary keeps Object identity, as the font height cache needs.
        var fonts = new Compat.JavaWeakHashMap<COSDictionary, float>();
        var first = new COSDictionary();
        var second = new COSDictionary();
        fonts[first] = 1;
        fonts[second] = 2;
        Assert.Equal(2, fonts.Count);
        Assert.Equal(1f, Compat.JavaCompat.MapGetNullable(fonts, first));
        Assert.Equal(2f, Compat.JavaCompat.MapGetNullable(fonts, second));
        Assert.Null(Compat.JavaCompat.MapGetNullable(fonts, new COSDictionary()));

        var names = new Compat.JavaWeakHashMap<string, int>();
        names["Tj"] = 1;
        Assert.True(names.ContainsKey(new string(new[] { 'T', 'j' })));

        // Unequal keys with one hash code share a chain.
        var a = new CountingKey(1, 7);
        var b = new CountingKey(2, 7);
        var c = new CountingKey(3, 7);
        var map = new Compat.JavaWeakHashMap<CountingKey, string>();
        map.Add(a, "a");
        map.Add(b, "b");
        map[c] = "c";
        Assert.ThrowsAny<System.ArgumentException>(() => map.Add(new CountingKey(2, 7), "again"));
        Assert.True(map.Remove(new CountingKey(2, 7)));
        Assert.False(map.Remove(b));
        Assert.Equal("a", map[a]);
        Assert.Equal("c", map[c]);
        Assert.Throws<KeyNotFoundException>(() => map[b]);
        map[b] = "b2";
        Assert.Equal(new[] { 1, 3, 2 }, map.Keys.Select(key => key.Id));
        Assert.Equal(new[] { "a", "c", "b2" }, map.Values);
        Assert.Equal(new[] { 1, 3, 2 }, map.Select(pair => pair.Key.Id));
        var copy = new KeyValuePair<CountingKey, string>[4];
        map.CopyTo(copy, 1);
        Assert.Same(c, copy[2].Key);
        Assert.False(map.Remove(new KeyValuePair<CountingKey, string>(a, "other")));
        Assert.True(map.Remove(new KeyValuePair<CountingKey, string>(a, "a")));
        Assert.Equal(new[] { "c", "b2" }, map.Values);
        Assert.ThrowsAny<System.ArgumentNullException>(() => map[null!] = "null");
        map.Clear();
        Assert.Empty(map);
        Assert.True(map.Count == 0);
        map[a] = "after clear";
        Assert.Equal(new[] { "after clear" }, map.Values);
    }

    [Fact]
    public void CountAndIterationDropCollectedKeysAndReleaseTheirValues()
    {
        var map = new Compat.JavaWeakHashMap<object, object>();
        var live = new object();
        map[live] = "live";
        var (keys, values) = AddUnreferencedEntries(map, 1000);
        AwaitCollection(keys);

        Assert.True(map.Count == 1, "Count must drop collected keys.");
        Assert.Same(live, Assert.Single(map).Key);
        Assert.Same(live, Assert.Single(map.Keys));
        AwaitCollection(values);
        Assert.All(values, value => Assert.False(value.IsAlive));
    }

    [Fact]
    public void LookupsAloneEventuallyPurgeCollectedKeys()
    {
        var map = new Compat.JavaWeakHashMap<object, object>();
        var live = new object();
        map[live] = "live";
        var (keys, values) = AddUnreferencedEntries(map, 1000);
        AwaitCollection(keys);

        // No Count, iteration or put: the amortised sweep runs within one
        // map-size worth of lookups after a collection.
        for (int index = 0; index < 2 * 1001; index++)
            Assert.True(map.ContainsKey(live));
        AwaitCollection(values);
        Assert.All(values, value => Assert.False(value.IsAlive));
        Assert.True(map.Count == 1, "Count must drop collected keys.");
    }

    [Fact]
    public void InsertionsAloneEventuallyPurgeCollectedKeys()
    {
        var map = new Compat.JavaWeakHashMap<object, object>();
        var (keys, values) = AddUnreferencedEntries(map, 1000);
        AwaitCollection(keys);

        // Only new distinct keys: no chain walk reaches the collected entries,
        // so the periodic sweep has to run while the map keeps growing.
        var added = new object[2 * 1000 + 16];
        for (int index = 0; index < added.Length; index++)
        {
            added[index] = new object();
            map[added[index]] = index;
        }
        AwaitCollection(values);
        Assert.All(values, value => Assert.False(value.IsAlive));
        Assert.Equal(added.Length, map.Count);
        GC.KeepAlive(added);
    }

    [Fact]
    public void LookupsAllocateNothing()
    {
        var map = new Compat.JavaWeakHashMap<COSDictionary, float>();
        var fonts = new COSDictionary[100];
        for (int index = 0; index < fonts.Length; index++)
        {
            fonts[index] = new COSDictionary();
            map[fonts[index]] = index;
        }
        long before = GC.GetAllocatedBytesForCurrentThread();
        float total = 0;
        for (int index = 0; index < 1000; index++)
            total += Compat.JavaCompat.MapGetNullable(map, fonts[index % fonts.Length]) ?? -1;
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(10 * 4950f, total);
        Assert.Equal(0, allocated);
    }

    [System.Runtime.CompilerServices.MethodImpl(
        System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static (WeakReference[] Keys, WeakReference[] Values) AddUnreferencedEntries(
        Compat.JavaWeakHashMap<object, object> map, int count)
    {
        var keys = new WeakReference[count];
        var values = new WeakReference[count];
        for (int index = 0; index < count; index++)
        {
            var key = new object();
            var value = new object();
            map[key] = value;
            keys[index] = new WeakReference(key);
            values[index] = new WeakReference(value);
        }
        return (keys, values);
    }

    private static void AwaitCollection(WeakReference[] references)
    {
        for (int attempt = 0; attempt < 20 && references.Any(reference => reference.IsAlive); attempt++)
            CollectFully();
        Assert.All(references, reference => Assert.False(reference.IsAlive));
    }

    private static void CollectFully()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
    }

    private sealed class CountingKey(int id, int hash)
    {
        [ThreadStatic] private static int comparisons;
        internal static int Comparisons => comparisons;
        internal int Id { get; } = id;
        internal static void Reset() => comparisons = 0;
        public override int GetHashCode() => hash;
        public override bool Equals(object? other)
        {
            comparisons++;
            return other is CountingKey key && key.Id == Id;
        }
    }
}

// java.util.BitSet behind ScratchFile's free-page map. It was a HashSet<int>
// whose nextSetBit scanned every set bit: an unrestricted ScratchFile starts
// with 100,000 free pages and takes each new page with nextSetBit(0).
public sealed class BitSetConsumerTests
{
    [Fact]
    public void BitSetFollowsJavaSemanticsAcrossWordsRangesAndGrowth()
    {
        var bits = new DripSharp.Runtime.JavaBitSet();
        Assert.Equal(-1, bits.nextSetBit(0));
        Assert.False(bits.get(1_000_000));
        bits.clear(5);
        bits.set(63);
        bits.set(64);
        bits.set(127, 130);
        bits.set(200, 200);
        bits.set(320, 384);
        bits.set(70_000);
        Assert.Equal(new[] { 63, 64, 127, 128, 129 }, SetBits(bits, 0, 130));
        Assert.Equal(63, bits.nextSetBit(0));
        Assert.Equal(64, bits.nextSetBit(64));
        Assert.Equal(127, bits.nextSetBit(65));
        Assert.Equal(320, bits.nextSetBit(130));
        Assert.Equal(70_000, bits.nextSetBit(384));
        Assert.Equal(-1, bits.nextSetBit(70_001));
        Assert.Equal(-1, bits.nextSetBit(int.MaxValue));
        Assert.True(bits.get(383));
        Assert.False(bits.get(384));
        Assert.False(bits.get(200));

        bits.clear(70_000);
        bits.clear(64);
        bits.clear(int.MaxValue);
        Assert.Equal(-1, bits.nextSetBit(384));
        Assert.Equal(127, bits.nextSetBit(64));
        bits.set(1, 191);
        Assert.Equal(Enumerable.Range(1, 190).Concat(Enumerable.Range(320, 64)), SetBits(bits, 0, 1000));
        bits.clear();
        Assert.Equal(-1, bits.nextSetBit(0));
        Assert.False(bits.get(2));
        bits.set(0, 64);
        bits.set(64, 128);
        Assert.Equal(Enumerable.Range(0, 128), SetBits(bits, 0, 1000));
    }

    [Fact]
    public void BitSetRejectsNegativeIndexesAndReversedRangesLikeJava()
    {
        var bits = new DripSharp.Runtime.JavaBitSet();
        bits.set(3);
        Assert.Equal("bitIndex < 0: -1",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.get(-1)).Message);
        Assert.Equal("bitIndex < 0: -2",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.set(-2)).Message);
        Assert.Equal("bitIndex < 0: -3",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.clear(-3)).Message);
        Assert.Equal("fromIndex < 0: -4",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.nextSetBit(-4)).Message);
        Assert.Equal("fromIndex < 0: -5",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.set(-5, 2)).Message);
        Assert.Equal("toIndex < 0: -6",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.set(2, -6)).Message);
        Assert.Equal("fromIndex: 9 > toIndex: 8",
            Assert.ThrowsAny<ArgumentOutOfRangeException>(() => bits.set(9, 8)).Message);
        Assert.Equal(new[] { 3 }, SetBits(bits, 0, 100));
    }

    [Fact]
    public void NextSetBitDoesNotScanTheSetBitsAfterTheResult()
    {
        // ScratchFile.initPages sets 100,000 free pages; getNewPage takes one
        // with nextSetBit(0) and clear. The HashSet scan made 25,600 pages
        // cost about 2.5e9 steps.
        var bits = new DripSharp.Runtime.JavaBitSet();
        var watch = System.Diagnostics.Stopwatch.StartNew();
        bits.set(0, 100_000);
        long taken = 0;
        for (var index = 0; index < 25_600; index++)
        {
            int page = bits.nextSetBit(0);
            bits.clear(page);
            taken += page;
        }
        watch.Stop();
        Assert.Equal(25_600L * 25_599 / 2, taken);
        Assert.Equal(25_600, bits.nextSetBit(0));
        Assert.True(watch.ElapsedMilliseconds < 1000,
            $"set(0, 100000) and 25,600 nextSetBit/clear cycles took {watch.ElapsedMilliseconds} ms.");
    }

    [Fact]
    public void ScratchFilePagesAreTakenWithoutRescanningTheFreePages()
    {
        using var scratch = new ScratchFile(MemoryUsageSetting.SetupMainMemoryOnly());
        var held = new List<DripSharp.PdfCarton.IO.RandomAccess>();
        var page = new sbyte[4096];
        var watch = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            for (var index = 0; index < 25_600; index++)
            {
                var buffer = scratch.CreateBuffer();
                page[0] = unchecked((sbyte)index);
                buffer.Write(page);
                if (index % 16 == 0) held.Add(buffer);
                else buffer.Dispose();
            }
            watch.Stop();
            for (var index = 0; index < held.Count; index++)
            {
                held[index].Seek(0);
                Assert.Equal(4096L, held[index].Length());
                Assert.Equal((index * 16) & 0xff, held[index].Read());
            }
        }
        finally
        {
            foreach (var buffer in held) buffer.Dispose();
        }
        Assert.True(watch.ElapsedMilliseconds < 5000,
            $"25,600 ScratchFile buffers took {watch.ElapsedMilliseconds} ms.");
    }

    [Fact]
    public void ScratchFileBackedRoundTripOfManyStreamsMatchesMemoryOnlyOutput()
    {
        // 1 MiB of main memory spills to the scratch file (enlarge sets
        // 16 free pages at a time); main-memory-only starts with 100,000.
        byte[] expected = SaveManyStreams(IOUtils.CreateMemoryOnlyStreamCache());
        Assert.Equal(expected, SaveManyStreams(MemoryUsageSetting.SetupMixed(1024 * 1024).StreamCache));
        Assert.Equal(expected, SaveManyStreams(MemoryUsageSetting.SetupMainMemoryOnly().StreamCache));

        byte[] resaved = Resave(expected, IOUtils.CreateMemoryOnlyStreamCache());
        Assert.Equal(resaved, Resave(expected, MemoryUsageSetting.SetupMixed(1024 * 1024).StreamCache));
        Assert.Equal(resaved, Resave(expected, MemoryUsageSetting.SetupMainMemoryOnly().StreamCache));

        using var reloaded = Loader.LoadPDF(
            (sbyte[])(Array)resaved, "", null!, null!,
            MemoryUsageSetting.SetupMixed(1024 * 1024).StreamCache);
        var payloads = (COSArray)reloaded.GetDocumentCatalog().GetCOSObject()
            .GetDictionaryObject(COSName.GetPDFName("Payloads"));
        Assert.Equal(StreamCount, payloads.Size());
        foreach (int index in new[] { 0, 1, StreamCount / 2, StreamCount - 1 })
        {
            var stream = (COSStream)payloads.GetObject(index);
            using var input = stream.CreateInputStream();
            using var copy = new MemoryStream();
            input.CopyTo(copy);
            Assert.Equal(Payload(index), copy.ToArray());
        }
    }

    private const int StreamCount = 600;

    private static byte[] SaveManyStreams(
        RandomAccessStreamCache.StreamCacheCreateFunction streamCache)
    {
        using var document = new PDDocument(streamCache);
        document.SetDocumentId(20261019L);
        document.AddPage(new PDPage());
        var payloads = new COSArray();
        for (var index = 0; index < StreamCount; index++)
        {
            var stream = document.GetDocument().CreateCOSStream();
            using (var output = stream.CreateOutputStream())
                output.Write(Payload(index));
            payloads.Add(stream);
        }
        document.GetDocumentCatalog().GetCOSObject()
            .SetItem(COSName.GetPDFName("Payloads"), payloads);
        using var saved = new MemoryStream();
        document.Save(saved);
        return saved.ToArray();
    }

    private static byte[] Resave(
        byte[] pdf, RandomAccessStreamCache.StreamCacheCreateFunction streamCache)
    {
        using var document = Loader.LoadPDF((sbyte[])(Array)pdf, "", null!, null!, streamCache);
        using var saved = new MemoryStream();
        document.Save(saved);
        return saved.ToArray();
    }

    // Roughly one to three pages of distinct bytes per stream.
    private static byte[] Payload(int index)
    {
        var payload = new byte[4096 * (1 + index % 3) - 1 + index % 7];
        for (var offset = 0; offset < payload.Length; offset++)
            payload[offset] = unchecked((byte)(offset * 31 + index * 7));
        return payload;
    }

    private static List<int> SetBits(DripSharp.Runtime.JavaBitSet bits, int from, int to)
    {
        var result = new List<int>();
        for (var index = bits.nextSetBit(from); index >= 0 && index < to; index = bits.nextSetBit(index + 1))
            result.Add(index);
        return result;
    }
}

// Every real token reaches Float.parseFloat through COSFloat(String), and
// PDFStreamParser calls Character.isDigit for every character of every number.
public sealed class NumberParsingConsumerTests
{
    private const int Tokens = 1000;

    // Expected bits are Float.floatToIntBits(Float.parseFloat(text)) from Java 17,
    // which folds every NaN to 0x7fc00000.
    [Theory]
    [InlineData("12.345", 0x4145851f)]
    [InlineData("612", 0x44190000)]
    [InlineData("-3.125", unchecked((int)0xc0480000))]
    [InlineData("1.F", 0x3f800000)]
    [InlineData(".5e-3f", 0x3a03126f)]
    [InlineData("1.5D", 0x3fc00000)]
    [InlineData("+.5", 0x3f000000)]
    [InlineData("00012.50000", 0x41480000)]
    [InlineData("1E+10", 0x501502f9)]
    [InlineData("\n\t12.345\0 ", 0x4145851f)]
    [InlineData("0X1.8P1", 0x40400000)]
    [InlineData("-0x.8p+1d", unchecked((int)0xbf800000))]
    [InlineData("-0x1.8P-1F", unchecked((int)0xbf400000))]
    [InlineData("0x1.fffffeP127f", 0x7f7fffff)]
    [InlineData("+NaN", 0x7fc00000)]
    public void RealTokensFollowTheJavaFloatingLiteralGrammar(string text, int bits)
    {
        float value = new COSFloat(text).FloatValue();
        Assert.Equal(bits, float.IsNaN(value) ? 0x7fc00000 : BitConverter.SingleToInt32Bits(value));
    }

    // Java's Float.parseFloat rejects each of these with NumberFormatException,
    // which COSFloat reports as an IOException.
    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData(".")]
    [InlineData("+")]
    [InlineData("-.")]
    [InlineData("e5")]
    [InlineData("1e+")]
    [InlineData("1e5.0")]
    [InlineData("1..5")]
    [InlineData("1_000")]
    [InlineData("1.5 f")]
    [InlineData("1.5ff")]
    [InlineData("+-1")]
    [InlineData(" 1.5")]
    [InlineData("1.5 ")]
    [InlineData("١")]
    [InlineData("0x")]
    [InlineData("0x.p1")]
    [InlineData("0x1.8")]
    [InlineData("0x1p+")]
    [InlineData("0x1p1.0")]
    [InlineData("Infinityf")]
    [InlineData("infinity")]
    [InlineData("NAN")]
    public void MalformedRealTokensAreRejected(string text) =>
        Assert.Throws<IOException>(() => new COSFloat(text));

    [Fact]
    public void ParsingARealTokenAllocatesOnlyTheCosFloat()
    {
        string[] texts = { "12.345", "-0.5", ".75", "1000.0625" };
        Assert.Equal(12.345f, new COSFloat(texts[0]).FloatValue());
        long before = GC.GetAllocatedBytesForCurrentThread();
        float total = 0;
        for (int index = 0; index < Tokens; index++)
            total += new COSFloat(texts[index % texts.Length]).FloatValue();
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(Tokens / 4 * (12.345f - 0.5f + 0.75f + 1000.0625f), total, 1f);
        // A COSFloat instance is a few dozen bytes; nothing else is needed.
        Assert.True(allocated <= 64L * Tokens, $"{Tokens} real tokens allocated {allocated} bytes.");
    }

    [Fact]
    public void ContentStreamNumbersDoNotAllocatePerDigit()
    {
        // Same tokens and COS types; the long line has 36 more digits per line.
        const string shortLine = "0.5 -0.5 1.5 2.5 3.5 4.5 cm\n";
        const string longLine = "1000000.5 -2000000.5 3000000.5 4000000.5 5000000.5 6000000.5 cm\n";
        long shortBytes = AllocatedWhileParsing(shortLine, -0.5f);
        long longBytes = AllocatedWhileParsing(longLine, -2000000.5f);
        long extraDigits = (long)(longLine.Length - shortLine.Length) * Tokens;
        // Longer token text costs a few bytes per digit; a string per digit
        // (as the netstandard Rune polyfill allocated) costs over twenty.
        Assert.True(
            longBytes - shortBytes <= 8 * extraDigits,
            $"{extraDigits} extra digits allocated {longBytes - shortBytes} extra bytes.");
    }

    // The production runtime is netstandard2.0, so these calls go through its
    // Rune polyfill; char.ConvertFromUtf32 there used to allocate per query.
    [Fact]
    public void CharacterQueriesDoNotAllocateForAsciiAndBmp()
    {
        Assert.True(DripSharp.Runtime.JavaCompat.IsDigit('7'));
        Assert.True(DripSharp.Runtime.JavaCompat.IsDigit(0x0661));
        Assert.False(DripSharp.Runtime.JavaCompat.IsDigit(0x4e2d));
        Assert.True(DripSharp.Runtime.JavaCompat.IsLetterOrDigit(0x00e9));
        Assert.Equal(9, DripSharp.Runtime.JavaCompat.CharacterType('7'));
        Assert.Equal(2, DripSharp.Runtime.JavaCompat.CharacterType('a'));
        Assert.Equal(5, DripSharp.Runtime.JavaCompat.CharacterType(0x4e2d));
        Assert.Equal(0, DripSharp.Runtime.JavaCompat.CharacterType(0xd800));
        Assert.Equal('A', DripSharp.Runtime.JavaCompat.ToUpperCase('a'));
        Assert.Equal(0x00c9, DripSharp.Runtime.JavaCompat.ToUpperCase(0x00e9));
        Assert.Equal(0x03a9, DripSharp.Runtime.JavaCompat.ToUpperCase(0x03c9));
        var omega = new DripSharp.Runtime.Rune(0x03c9);
        Assert.Equal(System.Globalization.UnicodeCategory.LowercaseLetter, DripSharp.Runtime.Rune.GetUnicodeCategory(omega));
        Assert.Equal(0x03a9, DripSharp.Runtime.Rune.ToUpperInvariant(omega).Value);

        int[] codePoints = { '7', 'a', 'Z', ' ', '.', 0x00e9, 0x0661, 0x03c9, 0x4e2d, 0xff21 };
        long checksum = Query(codePoints);
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int index = 0; index < Tokens; index++) checksum -= Query(codePoints);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.NotEqual(0, checksum);
        Assert.Equal(0, allocated);
    }

    private static long Query(int[] codePoints)
    {
        long total = 0;
        foreach (int codePoint in codePoints)
        {
            var rune = new DripSharp.Runtime.Rune(codePoint);
            total += (DripSharp.Runtime.JavaCompat.IsDigit(codePoint) ? 1 : 0) +
                (DripSharp.Runtime.JavaCompat.IsLetterOrDigit(codePoint) ? 1 : 0) +
                DripSharp.Runtime.JavaCompat.CharacterType(codePoint) +
                DripSharp.Runtime.JavaCompat.ToUpperCase(codePoint) +
                (int)DripSharp.Runtime.Rune.GetUnicodeCategory(rune) +
                DripSharp.Runtime.Rune.ToUpperInvariant(rune).Value;
        }
        return total;
    }

    private static long AllocatedWhileParsing(string line, float second)
    {
        byte[] content = System.Text.Encoding.ASCII.GetBytes(string.Concat(Enumerable.Repeat(line, Tokens)));
        Assert.Equal(7 * Tokens, new PDFStreamParser((sbyte[])(Array)content).Parse().Count);
        long before = GC.GetAllocatedBytesForCurrentThread();
        IList<object> tokens = new PDFStreamParser((sbyte[])(Array)content).Parse();
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(7 * Tokens, tokens.Count);
        Assert.Equal(second, Assert.IsType<COSFloat>(tokens[1]).FloatValue());
        return allocated;
    }
}

// GlyphList, FileSystemFontProvider and TrueTypeFont call String.split and
// String.matches while loading font metadata; Pattern.matches and
// String.replaceAll/replaceFirst share the same runtime path.
public sealed class StringRegexConsumerTests
{
    private const int Calls = 1000;

    // Expected arrays are java.lang.String.split(regex, limit) results from Java 25.
    public static TheoryData<string, string, int, string[]> SplitCases => new()
    {
        { "a;b;c", ";", 0, new string[] { "a", "b", "c" } },
        { "a;b;;", ";", 0, new string[] { "a", "b" } },
        { "a;b;;", ";", -1, new string[] { "a", "b", "", "" } },
        { "a;b;c", ";", 2, new string[] { "a", "b;c" } },
        { ";a;b", ";", 0, new string[] { "", "a", "b" } },
        { ";;", ";", 0, new string[] { } },
        { ";;", ";", -1, new string[] { "", "", "" } },
        { "", ";", 0, new string[] { "" } },
        { "", ";", -1, new string[] { "" } },
        { "abc", ";", 0, new string[] { "abc" } },
        { "a;b;c", ";", 1, new string[] { "a;b;c" } },
        { ";", ";", 1, new string[] { ";" } },
        { "a;b;c;", ";", 3, new string[] { "a", "b", "c;" } },
        { "a;b;c;", ";", 4, new string[] { "a", "b", "c", "" } },
        { "a;b;c;", ";", 5, new string[] { "a", "b", "c", "" } },
        { "a|b||", "\\|", 0, new string[] { "a", "b" } },
        { "a.b", "\\.", 0, new string[] { "a", "b" } },
        { "a\\b", "\\\\", 0, new string[] { "a", "b" } },
        { "a b  c ", " ", 0, new string[] { "a", "b", "", "c" } },
        { "boo:and:foo", "o", 0, new string[] { "b", "", ":and:f" } },
        { "boo:and:foo", "o", -1, new string[] { "b", "", ":and:f", "", "" } },
        { "boo:and:foo", "o", 2, new string[] { "b", "o:and:foo" } },
        { "a]b}c", "]", 0, new string[] { "a", "b}c" } },
        { "a-b-c", "-", 0, new string[] { "a", "b", "c" } },
        { "xéy", "é", 0, new string[] { "x", "y" } },
        { "abc", "", 0, new string[] { "a", "b", "c" } },
        { "abc", "", -1, new string[] { "a", "b", "c", "" } },
        { "abc", "", 2, new string[] { "a", "bc" } },
        { "a1b22c", "\\d+", 0, new string[] { "a", "b", "c" } },
        { "a1b22c3", "\\d", -1, new string[] { "a", "b", "", "c", "" } },
        { "abab", "(?=b)", 0, new string[] { "a", "ba", "b" } },
        { "bab", "(?=b)", 0, new string[] { "ba", "b" } },
        { "bab", "(?=b)", -1, new string[] { "ba", "b" } },
        { "a,b;c", "[,;]", 0, new string[] { "a", "b", "c" } },
        { "a.b", ".", 0, new string[] { } },
        { "a.b", ".", -1, new string[] { "", "", "", "" } },
        { "a$b", "$", 0, new string[] { "a$b" } },
        { "aXbxc", "(?i)x", 0, new string[] { "a", "b", "c" } },
        { "a  b\tc", "\\s+", 0, new string[] { "a", "b", "c" } },
        { "a b", "\\s", 2, new string[] { "a", "b" } },
    };

    [Theory]
    [MemberData(nameof(SplitCases))]
    public void SplitFollowsJavaSemantics(string value, string pattern, int limit, string[] expected)
    {
        Assert.Equal(expected, DripSharp.Runtime.JavaCompat.StringSplit(value, pattern, limit));
        // The regex engine gives the same answer for patterns the fast path takes.
        Assert.Equal(expected, DripSharp.Runtime.JavaCompat.RegexSplit(
            DripSharp.Runtime.JavaCompat.CompileRegex(pattern), value, limit));
    }

    [Fact]
    public void SplitWithoutAMatchReturnsTheInputInstance()
    {
        const string value = "no delimiter here";
        Assert.Same(value, Assert.Single(DripSharp.Runtime.JavaCompat.StringSplit(value, ";", 0)));
        Assert.Same(value, Assert.Single(DripSharp.Runtime.JavaCompat.StringSplit(value, "\\d", 0)));
    }

    [Fact]
    public void SingleCharacterSplitDoesNotCompileARegex()
    {
        long fast = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringSplit("Euro;20AC;x", ";", 0).Length);
        long escaped = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringSplit("Euro|20AC|x", "\\|", 0).Length);
        // The same split through a one-character class, which uses the regex engine.
        long regex = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringSplit("Euro;20AC;x", "[;]", 0).Length);
        // Three substrings, a list and the result array; no Regex, matcher or Match.
        Assert.True(fast <= 320, $"split(\";\") allocated {fast} bytes per call (regex engine: {regex}).");
        Assert.True(escaped <= 320, $"split(\"\\\\|\") allocated {escaped} bytes per call (regex engine: {regex}).");
        Assert.True(fast * 2 < regex, $"split(\";\") allocated {fast} bytes per call, regex engine {regex}.");
    }

    [Fact]
    public void RepeatedRegexCallsCompileOnce()
    {
        Assert.True(DripSharp.Runtime.JavaCompat.StringMatches("g1234", "g\\d+"));
        Assert.False(DripSharp.Runtime.JavaCompat.StringMatches("g12a", "g\\d+"));
        Assert.Equal("a-b-", DripSharp.Runtime.JavaCompat.StringReplaceAll("a1b2", "\\d", "-"));
        Assert.Equal("a-b2", DripSharp.Runtime.JavaCompat.StringReplaceFirst("a1b2", "\\d", "-"));
        Assert.Equal(new[] { "a", "b", "c" }, DripSharp.Runtime.JavaCompat.StringSplit("a1b22c", "\\d+", 0));
        long matches = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringMatches("g1234", "g\\d+") ? 1 : 0);
        long replaceAll = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringReplaceAll("a1b2", "\\d", "-").Length);
        long replaceFirst = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringReplaceFirst("a1b2", "\\d", "-").Length);
        long split = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.StringSplit("a1b22c", "\\d+", 0).Length);
        long compile = AllocatedPerCall(() => DripSharp.Runtime.JavaCompat.CompileRegex("g\\d+").GetHashCode());
        // Compiling allocates the translator output, parse tree and interpreter
        // code; a cached call allocates only the matcher, its Matches and the result.
        Assert.True(matches * 2 < compile, $"matches allocated {matches} bytes per call; one compile is {compile}.");
        Assert.True(replaceAll * 2 < compile, $"replaceAll allocated {replaceAll} bytes per call; one compile is {compile}.");
        Assert.True(replaceFirst * 2 < compile, $"replaceFirst allocated {replaceFirst} bytes per call; one compile is {compile}.");
        Assert.True(split * 2 < compile, $"split(\"\\\\d+\") allocated {split} bytes per call; one compile is {compile}.");
    }

    [Fact]
    public void ConcurrentColdRegexCallsShareOneInstance()
    {
        string pattern = "cold" + Guid.NewGuid().ToString("N") + "\\d+";
        var compiled = new System.Text.RegularExpressions.Regex[16];
        using var start = new Barrier(compiled.Length);
        var threads = Enumerable.Range(0, compiled.Length).Select(worker => new Thread(() =>
        {
            start.SignalAndWait();
            compiled[worker] = DripSharp.Runtime.JavaCompat.CachedRegex(pattern, 0);
        })).ToArray();
        foreach (var thread in threads) thread.Start();
        foreach (var thread in threads) thread.Join();
        Assert.All(compiled, regex => Assert.Same(compiled[0], regex));
    }

    [Fact]
    public void ConcurrentRegexCacheMissesRespectCapacity()
    {
        string prefix = "cap" + Guid.NewGuid().ToString("N");
        int capacity = DripSharp.Runtime.JavaCompat.StringRegexCacheCapacity;
        int overCapacity = 0;
        Parallel.For(0, 16, worker =>
        {
            for (int index = 0; index < capacity; index++)
            {
                DripSharp.Runtime.JavaCompat.CachedRegex(prefix + worker + "x" + index, 0);
                if (DripSharp.Runtime.JavaCompat.CachedRegexCount > capacity)
                    Interlocked.Increment(ref overCapacity);
            }
        });
        Assert.Equal(0, overCapacity);
        Assert.InRange(DripSharp.Runtime.JavaCompat.CachedRegexCount, 1, capacity);
    }

    [Fact]
    public void RegexCacheStaysCorrectPastItsCapacity()
    {
        for (int round = 0; round < 2; round++)
        {
            for (int index = 0; index < 600; index++)
            {
                string pattern = "x" + index + "\\d";
                Assert.True(DripSharp.Runtime.JavaCompat.StringMatches("x" + index + "7", pattern));
                Assert.False(DripSharp.Runtime.JavaCompat.StringMatches("x" + (index + 1) + "7", pattern));
                Assert.Equal("[" + index + "]",
                    DripSharp.Runtime.JavaCompat.StringReplaceFirst("x" + index + "7", pattern, "[" + index + "]"));
            }
        }
        Assert.Equal(new[] { "a", "b" }, DripSharp.Runtime.JavaCompat.StringSplit("a1b", "\\d", 0));
    }

    [Fact]
    public void ConcurrentCallsShareCompiledPatterns()
    {
        int failures = 0;
        Parallel.For(0, 64, worker =>
        {
            for (int index = 0; index < 200; index++)
            {
                string pattern = "w" + (index % 40) + "[a-c]+";
                if (!DripSharp.Runtime.JavaCompat.StringMatches("w" + (index % 40) + "abc", pattern) ||
                    DripSharp.Runtime.JavaCompat.StringSplit("p" + worker + ";q", ";", 0).Length != 2)
                    Interlocked.Increment(ref failures);
            }
        });
        Assert.Equal(0, failures);
    }

    private static long AllocatedPerCall(Func<int> call)
    {
        int checksum = call();
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int index = 0; index < Calls; index++) checksum += call();
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.NotEqual(int.MinValue, checksum);
        return allocated / Calls;
    }
}

// Compat bitmaps are counted process-wide, so these tests run alone.
[CollectionDefinition(nameof(NativeBitmapLifetimeCollection), DisableParallelization = true)]
public sealed class NativeBitmapLifetimeCollection
{
}

// DCT, JPX and JBIG2 decoding allocate full-size native bitmaps that Java
// leaves to its collector. The filters dispose them on every exit, and every
// compat bitmap reports its pixel bytes as GC memory pressure until it is
// disposed or finalized.
[Collection(nameof(NativeBitmapLifetimeCollection))]
public sealed class DecodeFilterBitmapLifetimeTests
{
    // A 64x16 all-white MMR generic region as an embedded JBIG2 stream: page
    // information, immediate generic region and end-of-page segments.
    private const string Jbig2Page =
        "AAAAADAAAQAAABMAAABAAAAAEAAAAAAAAAAAAAAAAAAAASYAAQAAABQAAABAAAAAEAAAAAAAAAAAAAH//wAAAAIxAAEAAAAA";

    private static (string Name, COSName Filter, COSStream Stream)[] Cases()
    {
        var jbig2 = Convert.FromBase64String(Jbig2Page);
        return
        [
            ("DCT RGB", COSName.DctDecode, Encoded(IccRenderFixtures.SkiaJpeg(96, 64), COSName.DctDecode)),
            ("DCT gray", COSName.DctDecode, Encoded(GrayJpeg(48, 32), COSName.DctDecode)),
            ("JPX RGB", COSName.JpxDecode, Encoded(Jpx(40, 24, alpha: false), COSName.JpxDecode)),
            ("JPX SMaskInData", COSName.JpxDecode, Encoded(Jpx(40, 24, alpha: true), COSName.JpxDecode,
                stream => stream.SetInt(COSName.SmaskInData, 1))),
            ("JBIG2", COSName.Jbig2Decode, Encoded(jbig2, COSName.Jbig2Decode)),
            ("JBIG2 repacked to 1 bit", COSName.Jbig2Decode, Encoded(jbig2, COSName.Jbig2Decode,
                stream => stream.SetInt(COSName.BitsPerComponent, 8))),
        ];
    }

    [Fact]
    public void DecodeFiltersDisposeTheirIntermediateBitmaps()
    {
        var cases = Cases();
        // The soft mask escapes through the decode result, so that case is
        // decoded through the filter and its caller disposes the mask.
        byte[] DecodeCase(int index) =>
            cases[index].Name == "JPX SMaskInData" ? DecodeJpxOwningMask(cases[index].Stream) : Decode(cases[index].Stream);
        var expected = cases.Select((_, index) => DecodeCase(index)).ToArray();
        Assert.Equal(96 * 64 * 3, expected[0].Length);
        Assert.Equal(48 * 32, expected[1].Length);
        Assert.Equal(40 * 24 * 3, expected[2].Length);
        Assert.Equal(40 * 24 * 3, expected[3].Length);
        Assert.Equal(64 / 8 * 16, expected[4].Length);
        Assert.Equal(expected[4], expected[5]);
        Assert.Equal(expected[3], Decode(cases[3].Stream));

        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        for (var round = 0; round < 10; round++)
        {
            foreach (var (test, index) in cases.Select((test, index) => (test, index)))
            {
                Assert.Equal(expected[index], DecodeCase(index));
                // No collection ran in between, so the decode disposed its bitmaps.
                AssertUnchanged(live, bytes, test.Name);
            }
        }

        // Malformed data fails without leaving a bitmap behind.
        foreach (var filter in new[] { COSName.DctDecode, COSName.JpxDecode, COSName.Jbig2Decode })
        {
            Assert.ThrowsAny<IOException>(() => Decode(Encoded([0xff, 0xd8, 1, 2, 3], filter)));
            AssertUnchanged(live, bytes, $"malformed {filter.GetName()}");
        }
    }

    // A failure after the bitmaps exist, here writing the decoded bytes,
    // disposes them too; the JPX soft mask never reaches a caller then.
    [Fact]
    public void DecodeFiltersDisposeTheirBitmapsWhenWritingTheOutputFails()
    {
        var cases = Cases();
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        foreach (var (name, filterName, stream) in cases)
        {
            var filter = FilterFactory.Instance.GetFilter(filterName);
            using var encoded = stream.CreateRawInputStream();
            Assert.ThrowsAny<IOException>(() => filter.Decode(encoded, new FailingOutput(), stream, 0));
            AssertUnchanged(live, bytes, name);
        }
    }

    private static void AssertUnchanged(long live, long bytes, string name) =>
        Assert.True(
            live == Compat.PdfCartonNativeBitmap.LiveCount &&
            bytes == Compat.PdfCartonNativeBitmap.OutstandingBytes,
            $"{name} left {Compat.PdfCartonNativeBitmap.LiveCount - live} bitmaps " +
            $"({Compat.PdfCartonNativeBitmap.OutstandingBytes - bytes} bytes) undisposed.");

    private sealed class FailingOutput : MemoryStream
    {
        public override void Write(byte[] buffer, int offset, int count) =>
            throw new IOException("output failed");

        public override void Write(ReadOnlySpan<byte> buffer) =>
            throw new IOException("output failed");

        public override void WriteByte(byte value) =>
            throw new IOException("output failed");
    }

    [Fact]
    public void CompatBitmapsReleaseTheirMemoryPressureOnDisposeOrFinalization()
    {
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;

        var bitmap = Compat.PdfCartonFontCompat.CreateBitmap(
            300, 200, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        Assert.Equal(live + 1, Compat.PdfCartonNativeBitmap.LiveCount);
        Assert.Equal(bytes + 300L * 4 * 200, Compat.PdfCartonNativeBitmap.OutstandingBytes);
        bitmap.Dispose();
        bitmap.Dispose();
        Assert.Equal(live, Compat.PdfCartonNativeBitmap.LiveCount);
        Assert.Equal(bytes, Compat.PdfCartonNativeBitmap.OutstandingBytes);

        AbandonBitmaps(10);
        Assert.Equal(live + 10, Compat.PdfCartonNativeBitmap.LiveCount);
        Assert.Equal(bytes + 10L * 64 * 64, Compat.PdfCartonNativeBitmap.OutstandingBytes);
        Settle();
        Assert.Equal(live, Compat.PdfCartonNativeBitmap.LiveCount);
        Assert.Equal(bytes, Compat.PdfCartonNativeBitmap.OutstandingBytes);
    }

    [System.Runtime.CompilerServices.MethodImpl(
        System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static void AbandonBitmaps(int count)
    {
        for (var index = 0; index < count; index++)
            _ = Compat.PdfCartonFontCompat.CreateBitmap(
                64, 64, Compat.PdfCartonFontCompat.TYPE_BYTE_GRAY);
    }

    private static void Settle()
    {
        for (var pass = 0; pass < 3; pass++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
    }

    private static COSStream Encoded(
        byte[] data, COSName filter, Action<COSStream>? configure = null)
    {
        var stream = new COSStream();
        using (var output = stream.CreateRawOutputStream())
            output.Write(data);
        stream.SetItem(COSName.Filter, filter);
        configure?.Invoke(stream);
        return stream;
    }

    private static byte[] Decode(COSStream stream)
    {
        using var input = stream.CreateInputStream();
        using var output = new MemoryStream();
        input.CopyTo(output);
        return output.ToArray();
    }

    private static byte[] DecodeJpxOwningMask(COSStream stream)
    {
        using var encoded = stream.CreateRawInputStream();
        using var decoded = new MemoryStream();
        var result = new JPXFilter().Decode(encoded, decoded, stream, 0);
        using var smask = result.GetJPXSMask();
        Assert.Equal((40, 24), (smask.Width, smask.Height));
        return decoded.ToArray();
    }

    internal static byte[] GrayJpeg(int width, int height)
    {
        using var bitmap = new SKBitmap(
            new SKImageInfo(width, height, SKColorType.Gray8, SKAlphaType.Opaque));
        var pixels = bitmap.GetPixelSpan();
        for (var index = 0; index < pixels.Length; index++)
            pixels[index] = (byte)(index * 5);
        bitmap.NotifyPixelsChanged();
        using var data = bitmap.Encode(SKEncodedImageFormat.Jpeg, 90);
        return data.ToArray();
    }

    // binaryAlpha: lossless, with alpha 0 where index % 3 == 0, else 255.
    // codestream: a bare J2K codestream instead of a JP2 file.
    internal static byte[] Jpx(
        int width, int height, bool alpha, bool binaryAlpha = false, bool codestream = false)
    {
        var count = alpha ? 4 : 3;
        var components = new int[count][];
        for (var component = 0; component < count; component++)
        {
            components[component] = new int[width * height];
            for (var index = 0; index < width * height; index++)
                components[component][index] = component == 3 && binaryAlpha
                    ? (index % 3 == 0 ? 0 : 255) - 128
                    : (index * (component + 3) & 255) - 128;
        }
        var source = new CoreJ2K.Util.InterleavedImageSource(
            width, height, count, 8, new bool[count], components);
        CoreJ2K.j2k.fileformat.metadata.J2KMetadata? metadata = null;
        if (alpha)
        {
            var channels = new CoreJ2K.j2k.fileformat.metadata.ChannelDefinitionData();
            for (var color = 0; color < 3; color++) channels.AddColorChannel(color, color + 1);
            // CoreJ2K maps component c to the channel associated with c + 1,
            // so the usual whole-image association (0) fails to decode
            // (dripsharp-62).
            channels.AddChannel(3, CoreJ2K.j2k.fileformat.metadata.ChannelType.Opacity, 4);
            metadata = new CoreJ2K.j2k.fileformat.metadata.J2KMetadata
            {
                ChannelDefinitions = channels
            };
        }
        // Binary alpha is encoded losslessly so that it stays 0 or 255.
        CoreJ2K.j2k.util.ParameterList? parameters = null;
        if (binaryAlpha || codestream)
        {
            parameters = new CoreJ2K.j2k.util.ParameterList(CoreJ2K.J2kImage.GetDefaultEncoderParameterList());
            if (binaryAlpha) parameters["lossless"] = "on";
            if (codestream) parameters["file_format"] = "off";
        }
        return CoreJ2K.J2kImage.ToBytes(source, metadata, parameters);
    }
}

// The JBIG2 adapter packs the decoder's page bits straight into a 1-bit
// raster, without an RGB or int-per-pixel copy, and both codec adapters
// render native pixels only when the image is drawn.
// TYPE_INT_RGB/ARGB images keep their pixels in a pinned managed array that
// Skia's release callback unpins once no native owner shares the pixels.
[Collection(nameof(NativeBitmapLifetimeCollection))]
public sealed class IntBitmapPixelLifetimeTests
{
    [Fact]
    public void AbandonedIntImagePixelsBecomeCollectible()
    {
        foreach (var type in new[] { Compat.PdfCartonFontCompat.TYPE_INT_RGB, Compat.PdfCartonFontCompat.TYPE_INT_ARGB })
        {
            var pixels = Abandon(type);
            Collect();
            Assert.False(pixels.IsAlive, $"type {type}: an abandoned image's pixels stayed pinned.");
        }
    }

    [Fact]
    public void DisposedIntImageWrapperDoesNotRetainItsPixels()
    {
        var image = Compat.PdfCartonFontCompat.CreateBitmap(64, 64, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var pixels = PixelsOf(image);
        image.Dispose();
        Collect();
        Assert.False(pixels.IsAlive);
        GC.KeepAlive(image);
    }

    // A canvas shares the pixels: they stay pinned and drawable after the
    // image is disposed and collected, until the canvas is gone too.
    [Fact]
    public void IntImagePixelsOutliveTheImageWhileACanvasSharesThem()
    {
        var (canvas, pixels) = CanvasOverDisposedImage();
        Collect();
        Assert.True(pixels.IsAlive);
        Assert.Equal(unchecked((int)0xff010203), ClearAndRead(canvas, pixels));
        canvas.Dispose();
        Collect();
        Assert.False(pixels.IsAlive);
    }

    // After SKBitmap replaces an int-backed image's pixels, GetData reads the
    // current pixels instead of returning the detached array.
    [Fact]
    public void ReallocatedIntImageNoLongerHandsOutItsOldArray()
    {
        using var image = Compat.PdfCartonFontCompat.CreateBitmap(4, 4, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var original = ((Compat.JavaDataBufferInt)Compat.PdfCartonFontCompat.GetRaster(image).GetDataBuffer()).GetData();
        Assert.True(image.TryAllocPixels(new SKImageInfo(64, 32, SKColorType.Bgra8888, SKAlphaType.Unpremul)));
        image.Erase(SKColors.Lime);
        var current = ((Compat.JavaDataBufferInt)Compat.PdfCartonFontCompat.GetRaster(image).GetDataBuffer()).GetData();
        Assert.NotSame(original, current);
        Assert.Equal(64 * 32, current.Length);
        Assert.All(current, value => Assert.Equal(unchecked((int)0xff00ff00), value));
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static WeakReference Abandon(int type)
    {
        var image = Compat.PdfCartonFontCompat.CreateBitmap(64, 64, type);
        using (var canvas = new SKCanvas(image))
            canvas.Clear(SKColors.Red);
        return PixelsOf(image);
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static (SKCanvas, WeakReference) CanvasOverDisposedImage()
    {
        var image = Compat.PdfCartonFontCompat.CreateBitmap(8, 8, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var canvas = new SKCanvas(image);
        var pixels = PixelsOf(image);
        image.Dispose();
        return (canvas, pixels);
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static int ClearAndRead(SKCanvas canvas, WeakReference pixels)
    {
        canvas.Clear(new SKColor(1, 2, 3));
        return ((int[])pixels.Target!)[0];
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static WeakReference PixelsOf(SKBitmap image) =>
        new(((Compat.JavaDataBufferInt)Compat.PdfCartonFontCompat.GetRaster(image).GetDataBuffer()).GetData());

    private static void Collect()
    {
        for (var pass = 0; pass < 3; pass++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
    }
}

public sealed class ImageCodecAdapterTests
{
    // A 64x16 all-white MMR generic region embedded as a JBIG2 stream.
    private const string Jbig2Page =
        "AAAAADAAAQAAABMAAABAAAAAEAAAAAAAAAAAAAAAAAAAASYAAQAAABQAAABAAAAAEAAAAAAAAAAAAAH//wAAAAIxAAEAAAAA";

    // A 67x20 black page (default pixel 1) ANDed with the white region at
    // columns 3-66 of rows 0-15. The width leaves 5 padding bits per row.
    internal static byte[] MixedPage()
    {
        var data = Convert.FromBase64String(Jbig2Page);
        WriteInt(data, 11, 67);
        WriteInt(data, 15, 20);
        data[27] = 0x04;
        WriteInt(data, 49, 3);
        data[57] = 1;
        return data;
    }

    [Fact]
    public void Jbig2DecodeWritesPaletteBitsWithZeroRowPadding()
    {
        var expected = new byte[9 * 20];
        for (var row = 0; row < 16; row++)
        {
            expected[row * 9] = 0x1f;
            for (var column = 1; column < 8; column++) expected[row * 9 + column] = 0xff;
            expected[row * 9 + 8] = 0xe0;
        }

        Assert.Equal(expected, Decode(MixedPage()));
    }

    [Fact]
    public void Jbig2ImagesRenderTheirPixelsWhenDrawn()
    {
        using var image = Compat.PdfCartonImageCodecs.Decode("JBIG2", MixedPage());
        using var canvas = Compat.PdfCartonFontCompat.CreateBitmap(
            67, 20, Compat.PdfCartonFontCompat.TYPE_INT_RGB);
        using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(canvas))
            graphics.DrawImage(image, 0, 0, (object)null!);

        foreach (var (x, y, white) in new[]
                 {
                     (0, 0, false), (2, 15, false), (3, 0, true), (66, 15, true),
                     (66, 16, false), (40, 19, false)
                 })
        {
            Assert.Equal(white ? -1 : unchecked((int)0xff000000),
                Compat.PdfCartonFontCompat.GetRgb(canvas, x, y));
        }
    }

    // A 300 DPI A4 page: 310 bytes x 3508 rows packed. The decoder's own bit
    // set is the same size, so the decode allocates about twice the packed
    // page, not the 26 MB RGB and 35 MB int[] copies it used to.
    [Fact]
    public void Jbig2DecodeAllocatesAboutTwiceThePackedPage()
    {
        var data = Convert.FromBase64String(Jbig2Page);
        WriteInt(data, 11, 2480);
        WriteInt(data, 15, 3508);
        const long packed = 310L * 3508;
        using (Compat.PdfCartonImageCodecs.Decode("JBIG2", data))
        {
        }

        var before = GC.GetAllocatedBytesForCurrentThread();
        using var image = Compat.PdfCartonImageCodecs.Decode("JBIG2", data);
        var allocated = GC.GetAllocatedBytesForCurrentThread() - before;

        Assert.Equal((2480, 3508), (image.Width, image.Height));
        Assert.True(allocated < packed * 5 / 2, $"JBIG2 decode allocated {allocated} bytes.");
    }

    // Each JPX component is copied through one reusable W*H buffer: the
    // adapter adds the raster and that buffer to the decoder's allocation,
    // not one int[] per component.
    [Fact]
    public void JpxDecodeReusesOneComponentBuffer()
    {
        var data = DecodeFilterBitmapLifetimeTests.Jpx(256, 256, alpha: true);
        const long buffer = 256L * 256 * sizeof(int);
        const long raster = 256L * 256 * 4;
        var decoder = Allocated(() =>
        {
            using var stream = new MemoryStream(data, writable: false);
            using var image = CoreJ2K.J2kImage.FromStream(stream, out _);
        });
        var adapter = Allocated(() =>
        {
            using var image = Compat.PdfCartonImageCodecs.Decode("JPX", data);
        });

        Assert.True(adapter - decoder < raster + buffer * 3 / 2,
            $"JPX adapter allocated {adapter - decoder} bytes beyond the decoder.");
    }

    // Gray and RGB JPX images use the shared standard colour spaces, as the
    // Java JPEG 2000 reader's ColorSpace.getInstance does.
    [Fact]
    public void JpxDecodeUsesSharedStandardColorSpaces()
    {
        var samples = new int[16 * 8];
        for (var index = 0; index < samples.Length; index++)
            samples[index] = (index * 5 & 255) - 128;
        var grayData = CoreJ2K.J2kImage.ToBytes(
            new CoreJ2K.Util.InterleavedImageSource(16, 8, 1, 8, new bool[1], [samples]));
        using (var gray = Compat.PdfCartonImageCodecs.Decode("JPX", grayData))
        {
            Assert.Same(
                Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_GRAY),
                Compat.PdfCartonFontCompat.GetColorModel(gray).ColorSpace);
        }
        using var rgb = Compat.PdfCartonImageCodecs.Decode(
            "JPX", DecodeFilterBitmapLifetimeTests.Jpx(16, 8, alpha: false));
        Assert.Same(
            Compat.PdfCartonFontCompat.GetColorSpace(Compat.JavaColorSpace.CS_sRGB),
            Compat.PdfCartonFontCompat.GetColorModel(rgb).ColorSpace);
    }

    // JBIG2Filter and JPXFilter read without rendering native pixels; only a
    // draw renders them. Transparency is the colour model's, before and
    // after.
    [Fact]
    public void FilterReadsRenderNativePixelsOnlyWhenDrawn()
    {
        foreach (var (format, data, transparency) in new[]
                 {
                     ("JBIG2", MixedPage(), Compat.PdfCartonTransparency.OPAQUE),
                     ("JPEG2000", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: false),
                         Compat.PdfCartonTransparency.OPAQUE),
                     ("JPEG2000", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: true),
                         Compat.PdfCartonTransparency.TRANSLUCENT),
                 })
        {
            foreach (var subsampling in new[] { 1, 2 })
            {
                using var image = Reader(format, data).ReadDeferred(0, Subsampled(subsampling));
                Assert.True(Compat.PdfCartonFontCompat.IsNativePending(image));
                Assert.Equal(transparency, Compat.PdfCartonFontCompat.GetTransparency(image));
                Assert.True(Compat.PdfCartonFontCompat.IsNativePending(image));

                using var canvas = Compat.PdfCartonFontCompat.CreateBitmap(
                    image.Width, image.Height, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
                using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(canvas))
                    graphics.DrawImage(image, 0, 0, (object)null!);
                Assert.False(Compat.PdfCartonFontCompat.IsNativePending(image));
                Assert.Equal(transparency, Compat.PdfCartonFontCompat.GetTransparency(image));
                if (transparency == Compat.PdfCartonTransparency.OPAQUE)
                    Assert.Equal(image.GetPixel(image.Width - 1, 1),
                        canvas.GetPixel(image.Width - 1, 1));
            }
        }
    }

    // An alpha-capable JPX is TRANSLUCENT whatever its alpha samples, as its
    // jai-imageio colour model is in Java, so JPXFilter extracts the
    // SMaskInData soft mask and returns three colour components even when
    // every alpha is 0 or 255 (Java PDFBox 3.0.8 with jai-imageio-jpeg2000
    // 1.4.0 decodes this fixture the same way).
    [Fact]
    public void JpxWithBinaryAlphaExtractsItsSoftMask()
    {
        var data = DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: true, binaryAlpha: true);
        using (var image = Reader("JPEG2000", data).ReadDeferred(0, Subsampled(1)))
            Assert.Equal(Compat.PdfCartonTransparency.TRANSLUCENT, Compat.PdfCartonFontCompat.GetTransparency(image));

        var stream = new COSStream();
        using (var output = stream.CreateRawOutputStream())
            output.Write(data);
        stream.SetItem(COSName.Filter, COSName.JpxDecode);
        stream.SetInt(COSName.SmaskInData, 1);
        using var encoded = stream.CreateRawInputStream();
        using var decoded = new MemoryStream();
        var result = new JPXFilter().Decode(encoded, decoded, stream, 0);
        using var smask = result.GetJPXSMask();
        Assert.NotNull(smask);
        Assert.Equal(40 * 24 * 3, decoded.Length);
        for (var index = 0; index < 40 * 24; index++)
            Assert.Equal(index % 3 == 0 ? 0 : 255,
                Compat.PdfCartonFontCompat.GetRgb(smask, index % 40, index / 40) & 0xff);
    }

    // The public reader returns images whose native pixels are rendered, so
    // Skia reads and raw canvas draws see the decoded pixels.
    [Fact]
    public void PublicCodecReadersReturnRenderedPixels()
    {
        foreach (var (format, data) in new[]
                 {
                     ("JBIG2", MixedPage()),
                     ("JPEG2000", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: false)),
                 })
        {
            foreach (var subsampling in new[] { 1, 2 })
            {
                using var expected = Reader(format, data).ReadDeferred(0, Subsampled(subsampling));
                using var image = Reader(format, data).Read(0, Subsampled(subsampling));
                Assert.False(Compat.PdfCartonFontCompat.IsNativePending(image));
                using var target = new SKBitmap(image.Width, image.Height);
                using (var canvas = new SKCanvas(target))
                using (var skImage = SKImage.FromBitmap(image))
                    canvas.DrawImage(skImage, 0, 0, SKSamplingOptions.Default, null);
                foreach (var (x, y) in new[] { (0, 0), (image.Width - 1, 1), (image.Width / 2, image.Height - 1) })
                {
                    var color = unchecked((uint)Compat.PdfCartonFontCompat.GetRgb(expected, x, y));
                    Assert.Equal(color, (uint)image.GetPixel(x, y));
                    Assert.Equal(color, (uint)target.GetPixel(x, y));
                }
            }
        }
    }

    private static Compat.JavaImageReader Reader(string format, byte[] data)
    {
        var reader = DripSharp.PdfCarton.Filter.Filter.FindImageReader(format, "codec missing");
        reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(data)));
        return reader;
    }

    private static Compat.JavaImageReadParam Subsampled(int subsampling)
    {
        var parameters = new Compat.JavaImageReadParam();
        parameters.SetSourceSubsampling(subsampling, subsampling, 0, 0);
        return parameters;
    }

    private static long Allocated(Action action)
    {
        action();
        var before = GC.GetAllocatedBytesForCurrentThread();
        action();
        return GC.GetAllocatedBytesForCurrentThread() - before;
    }

    private static void WriteInt(byte[] data, int offset, int value)
    {
        data[offset] = (byte)(value >> 24);
        data[offset + 1] = (byte)(value >> 16);
        data[offset + 2] = (byte)(value >> 8);
        data[offset + 3] = (byte)value;
    }

    private static byte[] Decode(byte[] jbig2)
    {
        var stream = new COSStream();
        using (var output = stream.CreateRawOutputStream())
            output.Write(jbig2);
        stream.SetItem(COSName.Filter, COSName.Jbig2Decode);
        using var input = stream.CreateInputStream();
        using var decoded = new MemoryStream();
        input.CopyTo(decoded);
        return decoded.ToArray();
    }
}

// SampledImageReader always passes the decode filters a source region, the
// whole image when nothing is clipped. As with ImageIO, a full-bounds region
// with 1x1 subsampling reads the decoded image itself; real regions and
// subsampling copy just the selected pixels, keeping the source's colour
// type, so a grayscale DCT image stays one band.
[Collection(nameof(NativeBitmapLifetimeCollection))]
public sealed class ImageReadParameterTests
{
    private static (string Name, string Format, byte[] Data)[] Sources() =>
    [
        ("DCT RGB", "JPEG", IccRenderFixtures.SkiaJpeg(40, 24)),
        ("DCT gray", "JPEG", DecodeFilterBitmapLifetimeTests.GrayJpeg(40, 24)),
        ("JBIG2", "JBIG2", ImageCodecAdapterTests.MixedPage()),
        ("JPX", "JPEG2000", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: true)),
    ];

    [Fact]
    public void FullBoundsRegionReturnsTheDecodedImage()
    {
        foreach (var (name, format, data) in Sources())
        {
            var source = Reader(format, data).ReadDeferred(0, null);
            var (width, height) = (source.Width, source.Height);
            using var image = Compat.JavaImageReader.ApplyReadParameters(
                source, Parameters(new SKRectI(0, 0, width, height), 1, 1, 0, 0));
            Assert.True(ReferenceEquals(source, image), name);
            // A region past the image bounds is clipped to them.
            Assert.True(ReferenceEquals(image, Compat.JavaImageReader.ApplyReadParameters(
                image, Parameters(new SKRectI(-3, -2, width + 5, height + 1), 1, 1, 0, 0))), name);
            Assert.NotEqual(IntPtr.Zero, image.Handle);
        }
    }

    [Fact]
    public void RegionsAndSubsamplingCopyTheSelectedPixels()
    {
        foreach (var (name, format, data) in Sources())
        {
            using var full = Reader(format, data).ReadDeferred(0, null);
            var (width, height) = (full.Width, full.Height);
            var colorType = full.ColorType;
            var bands = Compat.PdfCartonFontCompat.GetRaster(full).NumberOfBands;
            var managed = Compat.PdfCartonFontCompat.IsNativePending(full);
            foreach (var (region, periodX, periodY, offsetX, offsetY) in new[]
                     {
                         (new SKRectI(3, 2, width - 5, height - 3), 1, 1, 0, 0),
                         (new SKRectI(0, 0, width, height), 2, 3, 1, 2),
                         (new SKRectI(1, 1, width - 1, height - 2), 3, 1, 2, 0),
                         (new SKRectI(0, 0, width, height), 1, 2, 0, 1),
                     })
            {
                var label = $"{name} {region} {periodX}x{periodY}+{offsetX},{offsetY}";
                using var image = Reader(format, data).ReadDeferred(
                    0, Parameters(region, periodX, periodY, offsetX, offsetY));
                Assert.Equal(
                    ((region.Width - offsetX + periodX - 1) / periodX,
                     (region.Height - offsetY + periodY - 1) / periodY),
                    (image.Width, image.Height));
                Assert.Equal(colorType, image.ColorType);
                Assert.Equal(managed, Compat.PdfCartonFontCompat.IsNativePending(image));
                Assert.Equal(
                    Compat.PdfCartonFontCompat.GetImageType(full),
                    Compat.PdfCartonFontCompat.GetImageType(image));
                Assert.Equal(bands, Compat.PdfCartonFontCompat.GetRaster(image).NumberOfBands);
                for (var y = 0; y < image.Height; y++)
                    for (var x = 0; x < image.Width; x++)
                        Assert.True(
                            Compat.PdfCartonFontCompat.GetRgb(
                                full,
                                region.Left + offsetX + x * periodX,
                                region.Top + offsetY + y * periodY) ==
                            Compat.PdfCartonFontCompat.GetRgb(image, x, y),
                            $"{label} at ({x}, {y})");
            }
        }
    }

    // The managed copy reads and writes whole rows through one reused buffer
    // instead of allocating an int[] per pixel.
    [Fact]
    public void ManagedRegionCopyAllocatesNoPerPixelArrays()
    {
        var data = DecodeFilterBitmapLifetimeTests.Jpx(256, 256, alpha: false);
        var parameters = Parameters(new SKRectI(1, 1, 256, 256), 1, 1, 0, 0);
        const long raster = 255L * 255 * 3 * sizeof(int);
        var decode = Allocated(() =>
        {
            using var image = Reader("JPEG2000", data).ReadDeferred(0, null);
        });
        var region = Allocated(() =>
        {
            using var image = Reader("JPEG2000", data).ReadDeferred(0, parameters);
        });

        Assert.True(region - decode < raster,
            $"The region copy allocated {region - decode} bytes beyond the decode.");
    }

    // SampledImageReader reads a DeviceGray DCT image through a full-bounds
    // region. DCTFilter must decode one gray sample per pixel (W*H bytes),
    // which getImage expands to equal RGB components, as Java PDFBox's
    // PDDeviceGray.toRGBImage does.
    [Fact]
    public void GrayDctImageDecodesOneBand()
    {
        const int width = 48;
        const int height = 32;
        var jpeg = DecodeFilterBitmapLifetimeTests.GrayJpeg(width, height);
        using var expected = SKBitmap.Decode(jpeg);
        Assert.Equal(SKColorType.Gray8, expected.ColorType);
        using var document = new PDDocument();
        var image = JPEGFactory.CreateFromByteArray(document, IccRenderFixtures.Signed(jpeg));
        Assert.IsType<PDDeviceGray>(image.GetColorSpace());
        using (var stream = image.CreateInputStream(new DecodeOptions(0, 0, width, height)))
        using (var decoded = new MemoryStream())
        {
            stream.CopyTo(decoded);
            Assert.Equal(width * height, decoded.Length);
        }

        var raw = image.GetRawRaster();
        Assert.Equal(1, raw.NumberOfBands);
        using var rgb = image.GetImage();
        Assert.Equal((width, height), (rgb.Width, rgb.Height));
        var samples = expected.GetPixelSpan();
        var sample = new int[1];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var gray = samples[y * expected.RowBytes + x];
                Assert.Equal(gray, raw.GetPixel(x, y, sample)[0]);
                Assert.Equal(
                    0xff000000u | (uint)gray << 16 | (uint)gray << 8 | gray,
                    unchecked((uint)Compat.PdfCartonFontCompat.GetRgb(rgb, x, y)));
            }
        }
    }

    private static Compat.JavaImageReader Reader(string format, byte[] data)
    {
        var reader = DripSharp.PdfCarton.Filter.Filter.FindImageReader(format, "codec missing");
        reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(data)));
        return reader;
    }

    private static Compat.JavaImageReadParam Parameters(
        SKRectI region, int periodX, int periodY, int offsetX, int offsetY)
    {
        var parameters = new Compat.JavaImageReadParam();
        parameters.SetSourceSubsampling(periodX, periodY, offsetX, offsetY);
        parameters.SetSourceRegion(region);
        return parameters;
    }

    private static long Allocated(Action action)
    {
        action();
        var before = GC.GetAllocatedBytesForCurrentThread();
        action();
        return GC.GetAllocatedBytesForCurrentThread() - before;
    }
}

// ImageIO reads an image's size from its header. JPEG sizes come from the
// frame header through Skia's codec and JPX sizes from the codestream's main
// header, without a pixel decode, so JPEGFactory.createFromByteArray decodes
// nothing and JPXFilter, which reads the image and then its width and
// height, decodes once.
public sealed class ImageReaderSizeTests
{
    // 13x7 RGB, cjpeg -progressive (libjpeg-turbo 3.2.0).
    private const string ProgressiveJpeg =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/" +
        "2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wgARCAAHAA0DASIAAhEBAxEB/8QAFQAB" +
        "AQAAAAAAAAAAAAAAAAAAAAb/xAAVAQEBAAAAAAAAAAAAAAAAAAADBv/aAAwDAQACEAMQAAABixZt/8QAFRABAQAAAAAAAAAAAAAAAAAAABP/2gAIAQEAAQUCss//" +
        "xAAXEQADAQAAAAAAAAAAAAAAAAAABBVS/9oACAEDAQE/Aa7Wj//EABcRAAMBAAAAAAAAAAAAAAAAAAAEFVL/2gAIAQIBAT8BiqZP/8QAFBABAAAAAAAAAAAAAAAA" +
        "AAAAEP/aAAgBAQAGPwI//8QAFRABAQAAAAAAAAAAAAAAAAAAABH/2gAIAQEAAT8htb//2gAMAwEAAgADAAAAEA//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAED" +
        "AQE/EA//xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oACAECAQE/ED//xAAWEAADAAAAAAAAAAAAAAAAAAAAAWH/2gAIAQEAAT8QsyzP/9k=";

    // 9x6 Adobe CMYK, ImageMagick 7 -colorspace CMYK.
    private const string CmykJpeg =
        "/9j/7gAOQWRvYmUAZAAAAAAC/9sAQwAGBAUGBQQGBgUGBwcGCAoQCgoJCQoUDg8MEBcUGBgXFBYWGh0lHxobIxwWFiAsICMmJykqKRkfLTAtKDAlKCko/9sAQwEH" +
        "BwcKCAoTCgoTKBoWGigoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgo/8AAFAgABgAJBAEiAAIRAQMRAQQiAP/EABYAAQEB" +
        "AAAAAAAAAAAAAAAAAAAGCP/EABkQAQACAwAAAAAAAAAAAAAAAAADBRdRkf/EABUBAQEAAAAAAAAAAAAAAAAAAAYH/8QAGBEAAgMAAAAAAAAAAAAAAAAAAAEEFVP/" +
        "2gAOBAEAAhEDEQQAAD8AhsrWGpOmVrDUnQXqsiZoE08LJGqQH//Z";

    // 11x5 RGB, cjpeg -precision 12 (SOF1, 12-bit samples).
    private const string TwelveBitJpeg =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/" +
        "2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wQARDAAFAAsDASIAAhEBAxEB/8QAFQAB" +
        "AQAAAAAAAAAAAAAAAAAAAAv/xAAaEAABBQEAAAAAAAAAAAAAAAAAAggZVKTS/8QAFQEBAQAAAAAAAAAAAAAAAAAACQv/xAAhEQAAAQ0AAAAAAAAAAAAAAAAAAQMG" +
        "BwkXGlVYkpTT4f/aAAwDAQACEQMRAD8Ah7zKOKtbV8iZRxVravkAueQvTJCnpHcQuwBI4JXElMW9H//Z";

    private static (string Name, byte[] Data, int Width, int Height, Type ColorSpace)[] Jpegs() =>
    [
        ("baseline RGB", IccRenderFixtures.SkiaJpeg(40, 24), 40, 24, typeof(PDDeviceRGB)),
        ("baseline gray", DecodeFilterBitmapLifetimeTests.GrayJpeg(48, 32), 48, 32, typeof(PDDeviceGray)),
        ("progressive", Convert.FromBase64String(ProgressiveJpeg), 13, 7, typeof(PDDeviceRGB)),
        ("CMYK", Convert.FromBase64String(CmykJpeg), 9, 6, typeof(PDDeviceCMYK)),
        // Orientation 6 (rotate 90): ImageIO ignores it, so the size is the
        // frame's, not the displayed 10x20.
        ("EXIF rotated", WithExifOrientation(IccRenderFixtures.SkiaJpeg(20, 10), 6), 20, 10, typeof(PDDeviceRGB)),
    ];

    [Fact]
    public void JpegSizeComesFromTheHeaderWithoutAPixelDecode()
    {
        foreach (var (name, data, width, height, colorSpace) in Jpegs())
        {
            var decodes = Compat.JavaImageReader.DecodesOnCurrentThread;
            var reader = Reader("JPEG", data);
            Assert.Equal((width, height), (reader.GetWidth(0), reader.GetHeight(0)));
            using (var document = new PDDocument())
            {
                var image = JPEGFactory.CreateFromByteArray(document, IccRenderFixtures.Signed(data));
                Assert.Equal((width, height), (image.GetWidth(), image.GetHeight()));
                // The upstream PDDeviceCMYKTest replaces PDDeviceCMYK.INSTANCE with a subclass.
                Assert.IsAssignableFrom(colorSpace, image.GetColorSpace());
            }
            Assert.True(decodes == Compat.JavaImageReader.DecodesOnCurrentThread, $"{name} decoded pixels.");

            // The decoded image has the header's size.
            using var decoded = reader.ReadDeferred(0, null);
            Assert.Equal((width, height), (decoded.Width, decoded.Height));
            Assert.Equal(decodes + 1, Compat.JavaImageReader.DecodesOnCurrentThread);
        }

        using var codec = SKCodec.Create(new MemoryStream(Jpegs()[4].Data));
        Assert.Equal(SKEncodedOrigin.RightTop, codec.EncodedOrigin);
    }

    // The size of a 12-bit JPEG is its frame header's, read without a decode.
    [Fact]
    public void TwelveBitJpegSizeComesFromTheHeader()
    {
        var data = Convert.FromBase64String(TwelveBitJpeg);
        var decodes = Compat.JavaImageReader.DecodesOnCurrentThread;
        var reader = Reader("JPEG", data);
        Assert.Equal((11, 5), (reader.GetWidth(0), reader.GetHeight(0)));
        Assert.Equal(decodes, Compat.JavaImageReader.DecodesOnCurrentThread);
    }

    [Fact]
    public void JpxFilterDecodesEachImageOnce()
    {
        foreach (var (name, data, bands) in new[]
                 {
                     ("J2K codestream", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: false, codestream: true), 3),
                     ("JP2 file", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: false), 3),
                     ("JPX with alpha", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: true), 4),
                 })
        {
            var stream = new COSStream();
            using (var output = stream.CreateRawOutputStream())
                output.Write(data);
            stream.SetItem(COSName.Filter, COSName.JpxDecode);
            using var encoded = stream.CreateRawInputStream();
            using var decoded = new MemoryStream();
            var decodes = Compat.JavaImageReader.DecodesOnCurrentThread;
            var result = new JPXFilter().Decode(encoded, decoded, stream, 0);
            using var smask = result.GetJPXSMask();
            Assert.True(decodes + 1 == Compat.JavaImageReader.DecodesOnCurrentThread,
                $"{name}: {Compat.JavaImageReader.DecodesOnCurrentThread - decodes} decodes.");
            Assert.Equal(40, result.GetParameters().GetInt(COSName.Width));
            Assert.Equal(24, result.GetParameters().GetInt(COSName.Height));
            Assert.Equal(40 * 24 * bands, decoded.Length);
        }
        Assert.Equal("ftyp", System.Text.Encoding.ASCII.GetString(
            DecodeFilterBitmapLifetimeTests.Jpx(4, 4, alpha: false), 16, 4));
        Assert.Equal(new byte[] { 0xff, 0x4f, 0xff, 0x51 },
            DecodeFilterBitmapLifetimeTests.Jpx(4, 4, alpha: false, codestream: true)[..4]);
    }

    // JP2 files and bare J2K codestreams report their size from the main
    // header, the full image size whatever region a read selected.
    [Fact]
    public void JpxSizeComesFromTheHeaderWithoutAPixelDecode()
    {
        foreach (var codestream in new[] { false, true })
        {
            var reader = Reader("JPEG2000", DecodeFilterBitmapLifetimeTests.Jpx(40, 24, alpha: false, codestream: codestream));
            var decodes = Compat.JavaImageReader.DecodesOnCurrentThread;
            Assert.Equal((40, 24), (reader.GetWidth(0), reader.GetHeight(0)));
            Assert.Equal(decodes, Compat.JavaImageReader.DecodesOnCurrentThread);
            var parameters = new Compat.JavaImageReadParam();
            parameters.SetSourceSubsampling(2, 2, 0, 0);
            using (var image = reader.ReadDeferred(0, parameters))
                Assert.Equal((20, 12), (image.Width, image.Height));
            Assert.Equal((40, 24), (reader.GetWidth(0), reader.GetHeight(0)));
            Assert.Equal(decodes + 1, Compat.JavaImageReader.DecodesOnCurrentThread);

            reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(
                DecodeFilterBitmapLifetimeTests.Jpx(17, 9, alpha: true, codestream: codestream))));
            Assert.Equal((17, 9), (reader.GetWidth(0), reader.GetHeight(0)));
            using (var image = reader.ReadDeferred(0, null))
                Assert.Equal((17, 9), (image.Width, image.Height));
            Assert.Equal(decodes + 2, Compat.JavaImageReader.DecodesOnCurrentThread);
        }

        Assert.Throws<IOException>(() => Reader("JPEG2000", [0xff, 0x4f, 0xff, 0x51, 0, 2]).GetWidth(0));
    }

    // Without a header-only read (JBIG2), the size is the last decode's until
    // SetInput.
    [Fact]
    public void ReaderRemembersTheDecodedSizeUntilItsInputChanges()
    {
        var reader = Reader("JBIG2", ImageCodecAdapterTests.MixedPage());
        var decodes = Compat.JavaImageReader.DecodesOnCurrentThread;
        Assert.Equal((67, 20), (reader.GetWidth(0), reader.GetHeight(0)));
        Assert.Equal(decodes + 1, Compat.JavaImageReader.DecodesOnCurrentThread);
        using (reader.ReadDeferred(0, null))
            Assert.Equal((67, 20), (reader.GetWidth(0), reader.GetHeight(0)));
        Assert.Equal(decodes + 2, Compat.JavaImageReader.DecodesOnCurrentThread);

        reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(ImageCodecAdapterTests.MixedPage())));
        Assert.Equal((67, 20), (reader.GetWidth(0), reader.GetHeight(0)));
        Assert.Equal(decodes + 3, Compat.JavaImageReader.DecodesOnCurrentThread);

        reader.SetInput(null);
        Assert.Throws<InvalidOperationException>(() => reader.GetWidth(0));
        Assert.Throws<IndexOutOfRangeException>(() => Reader("JPEG", Convert.FromBase64String(ProgressiveJpeg)).GetWidth(1));
    }

    // Inserts an EXIF APP1 segment with one Orientation tag after SOI.
    private static byte[] WithExifOrientation(byte[] jpeg, int orientation)
    {
        byte[] app1 =
        [
            0xff, 0xe1, 0x00, 0x22, (byte)'E', (byte)'x', (byte)'i', (byte)'f', 0, 0,
            (byte)'M', (byte)'M', 0x00, 0x2a, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0x00, (byte)orientation, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ];
        return [.. jpeg.AsSpan(0, 2), .. app1, .. jpeg.AsSpan(2)];
    }

    private static Compat.JavaImageReader Reader(string format, byte[] data)
    {
        var reader = DripSharp.PdfCarton.Filter.Filter.FindImageReader(format, "codec missing");
        reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(data)));
        return reader;
    }
}

// Rendering allocates full-size intermediates that Java leaves to its
// collector: soft-mask groups and their gray masks, transparency groups and
// their backdrop alpha, masked, colour-keyed and DeviceN images, transfer,
// stencil and printer copies, and page images forced to ARGB for blending.
// They are disposed on success and failure; only the returned image and the
// images a PDImageXObject caches stay live.
[Collection(nameof(NativeBitmapLifetimeCollection))]
// SKCanvas.DrawBitmap copies every pixel of a mutable bitmap per draw, so a
// 100x100 subrect of a page cost a page copy. Raster draws now wrap the
// source's live pixels; canvases that may record keep Skia's copy.
public sealed class BitmapDrawCopyTests
{
    private static SKBitmap Page(int width, int height)
    {
        var page = Compat.PdfCartonFontCompat.CreateBitmap(width, height, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var random = new Random(56);
        for (var y = 0; y < height; y++)
            for (var x = 0; x < width; x++)
                Compat.PdfCartonFontCompat.SetRgb(page, x, y, random.Next() | (x % 7 == 0 ? 0x40 << 24 : unchecked((int)0xff000000)));
        return page;
    }

    private static IntPtr Pixels(SKImage image)
    {
        using var pixmap = image.PeekPixels();
        return pixmap.GetPixels();
    }

    [Fact]
    public void RasterDrawsWrapTheSourcePixelsInsteadOfCopyingThem()
    {
        using var page = Page(2480, 3508);
        using var target = new SKBitmap(100, 100);
        using (var view = Compat.PdfCartonFontCompat.DrawableImage(page, target))
            Assert.Equal(page.GetPixels(), Pixels(view!));
        // A canvas that may record, or a source sharing the target's pixels, gets a copy.
        using (var copy = Compat.PdfCartonFontCompat.DrawableImage(page, null))
            Assert.NotEqual(page.GetPixels(), Pixels(copy!));
        using (var copy = Compat.PdfCartonFontCompat.DrawableImage(page, page))
            Assert.NotEqual(page.GetPixels(), Pixels(copy!));
        using var subset = new SKBitmap();
        Assert.True(page.ExtractSubset(subset, new SKRectI(10, 10, 60, 60)));
        using (var copy = Compat.PdfCartonFontCompat.DrawableImage(subset, page))
            Assert.NotEqual(subset.GetPixels(), Pixels(copy!));

        // A subrect draw reads the live pixels: later writes show on the next draw.
        using var graphics = new Compat.PdfCartonGraphics2D(target);
        graphics.DrawImage(page, 0, 0, 100, 100, 1000, 1000, 1100, 1100, null);
        Assert.Equal((uint)Compat.PdfCartonFontCompat.GetRgb(page, 1051, 1050), (uint)target.GetPixel(51, 50));
        Compat.PdfCartonFontCompat.SetRgb(page, 1050, 1050, unchecked((int)0xff123456));
        graphics.DrawImage(page, 0, 0, 100, 100, 1000, 1000, 1100, 1100, null);
        Assert.Equal(0xff123456u, (uint)target.GetPixel(50, 50));
    }

    [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
    private static (SKImage Image, WeakReference Source) LiveImage(SKBitmap target)
    {
        var source = Compat.PdfCartonFontCompat.CreateBitmap(64, 64, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        source.Erase(SKColors.Red);
        return (Compat.PdfCartonFontCompat.DrawableImage(source, target)!, new WeakReference(source));
    }

    private static void Collect()
    {
        for (var pass = 0; pass < 3; pass++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
    }

    // A live image, and a shader made from it, keep their source bitmap
    // reachable until Skia releases them, and no longer.
    [Fact]
    public void LiveImagesRetainTheirSourceUntilReleased()
    {
        using var target = new SKBitmap(new SKImageInfo(64, 64, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(target);
        var (image, source) = LiveImage(target);
        Collect();
        Assert.True(source.IsAlive);
        canvas.DrawImage(image, 0, 0, SKSamplingOptions.Default, null);
        Assert.Equal(SKColors.Red, target.GetPixel(10, 10));
        image.Dispose();
        Collect();
        Assert.False(source.IsAlive);

        (image, source) = LiveImage(target);
        var shader = image.ToShader(SKShaderTileMode.Repeat, SKShaderTileMode.Repeat,
            SKSamplingOptions.Default, SKMatrix.Identity);
        image.Dispose();
        Collect();
        Assert.True(source.IsAlive);
        canvas.Clear(SKColors.Transparent);
        using (var paint = new SKPaint { Shader = shader })
            canvas.DrawPaint(paint);
        Assert.Equal(SKColors.Red, target.GetPixel(10, 10));
        shader.Dispose();
        Collect();
        Assert.False(source.IsAlive);
    }

    public static TheoryData<int, int> DrawCases()
    {
        var data = new TheoryData<int, int>();
        for (var hint = 0; hint < 4; hint++)
            for (var transform = 0; transform < 4; transform++)
                data.Add(hint, transform);
        return data;
    }

    private static void Configure(Compat.PdfCartonGraphics2D graphics, int hint, int transform)
    {
        graphics.SetRenderingHint(Compat.PdfCartonRenderingHints.KEY_ANTIALIASING,
            Compat.PdfCartonRenderingHints.VALUE_ANTIALIAS_ON);
        if (hint > 0)
            graphics.SetRenderingHint(Compat.PdfCartonRenderingHints.KEY_INTERPOLATION, hint switch
            {
                1 => Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                2 => Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_BILINEAR,
                _ => Compat.PdfCartonRenderingHints.VALUE_INTERPOLATION_BICUBIC,
            });
        graphics.SetTransform(transform switch
        {
            0 => SKMatrix.Identity,
            1 => SKMatrix.CreateScaleTranslation(1.7f, 1.3f, 0.4f, 0.3f),
            2 => SKMatrix.Concat(SKMatrix.CreateTranslation(20, -10), SKMatrix.CreateRotation(0.3f)),
            _ => SKMatrix.CreateScale(0.35f, 0.5f),
        });
    }

    // The same draws through a bitmap-backed graphics (live pixels) and a
    // canvas-backed one (Skia's copy) give identical pixels.
    [Theory]
    [MemberData(nameof(DrawCases))]
    public void LivePixelDrawsMatchCopiedDraws(int hint, int transform)
    {
        using var page = Page(300, 240);
        var info = new SKImageInfo(160, 140, SKColorType.Bgra8888, SKAlphaType.Premul);
        using var live = new SKBitmap(info);
        using var copied = new SKBitmap(info);
        using var copiedCanvas = new SKCanvas(copied);
        using (var liveGraphics = new Compat.PdfCartonGraphics2D(live))
        using (var copiedGraphics = new Compat.PdfCartonGraphics2D(copiedCanvas))
        {
            foreach (var graphics in new[] { liveGraphics, copiedGraphics })
            {
                Configure(graphics, hint, transform);
                graphics.DrawImage(page, 3, 5, 103, 95, 150, 100, 250, 190, null);
                graphics.SetComposite(Compat.JavaAlphaComposite.GetInstance(Compat.JavaAlphaComposite.SRC_OVER, 0.6f));
                graphics.DrawImage(page, SKMatrix.CreateScaleTranslation(0.4f, 0.45f, 30.5f, 20.25f), null);
            }
        }
        Assert.Equal(copied.Bytes, live.Bytes);
    }

    // CopyArea snapshots only the source area under an integer translation;
    // the pixels match the former whole-bitmap snapshot drawn by Skia.
    [Theory]
    [MemberData(nameof(DrawCases))]
    public void CopyAreaMatchesAWholeBitmapSnapshot(int hint, int transform)
    {
        var translation = transform switch
        {
            0 => SKMatrix.Identity,
            1 => SKMatrix.CreateTranslation(0.4f, 0.3f),
            2 => SKMatrix.CreateTranslation(-7f, 12f),
            _ => SKMatrix.CreateScale(1.5f, 0.75f),
        };
        foreach (var (x, y, width, height, dx, dy) in new[]
                 {
                     (40, 30, 100, 80, 15, 10), (0, 0, 60, 50, 100, 90), (200, 150, 100, 90, -150, -120),
                     (-20, -10, 50, 40, 30, 30), (500, 500, 10, 10, -400, -400), (10, 10, 0, 5, 3, 3),
                     (0, 0, int.MaxValue, 100, -128, 0), (0, 0, 100, int.MaxValue, 0, -100),
                 })
        {
            using var actual = Page(300, 240);
            using var expected = Page(300, 240);
            using (var graphics = new Compat.PdfCartonGraphics2D(actual))
            {
                Configure(graphics, hint, 0);
                graphics.SetTransform(translation);
                graphics.CopyArea(x, y, width, height, dx, dy);
            }
            using (var snapshot = expected.Copy())
            using (var canvas = new SKCanvas(expected))
            using (var paint = new SKPaint())
            {
                canvas.SetMatrix(translation);
                var options = hint switch
                {
                    0 or 1 => SKSamplingOptions.Default,
                    2 => new SKSamplingOptions(SKFilterMode.Linear),
                    _ => new SKSamplingOptions(SKCubicResampler.Mitchell),
                };
                canvas.DrawBitmap(snapshot, new SKRect(x, y, x + width, y + height),
                    new SKRect(x + dx, y + dy, x + dx + width, y + dy + height), options, paint);
            }
            Assert.True(expected.Bytes.AsSpan().SequenceEqual(actual.Bytes),
                $"hint {hint} transform {transform} area ({x},{y},{width},{height}) by ({dx},{dy})");
        }
    }
}

public sealed class RenderingBitmapLifetimeTests
{
    private const int OneMaskedFill = 0;
    private const int ManyMaskedFills = 1;
    private const int GroupsAndBlending = 2;
    private const int Images = 3;
    private const int RotatedMask = 4;
    private const int TwoBlendedGroups = 5;
    private const int MaskedFills = 200;

    private static readonly int[] Pages =
        [OneMaskedFill, ManyMaskedFills, GroupsAndBlending, Images, RotatedMask, TwoBlendedGroups];

    private static readonly string[] ImageNames = ["ImS", "ImS2", "ImM", "ImK", "ImN", "ImT", "ImD", "ImSt"];

    [Fact]
    public void RenderingDisposesItsIntermediateBitmaps()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        var renderer = new PDFRenderer(document);
        // ImageType.ToString overflows the stack (dripsharp-64), so types are named here.
        var types = new[] { ("RGB", ImageType.Rgb), ("ARGB", ImageType.Argb), ("gray", ImageType.Gray) };
        var expected = new Dictionary<(int, string), string>();
        foreach (var page in Pages)
            foreach (var (name, type) in types)
            {
                using var image = renderer.RenderImage(page, 2, type);
                expected[(page, name)] = Hash(image);
            }
        Settle();
        // Keep the image XObjects and the images they cache alive, so later
        // renders reuse them instead of decoding (and caching) new ones.
        var pinned = PinImages(document.GetPage(Images));
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        for (var round = 0; round < 3; round++)
        {
            foreach (var page in Pages)
                foreach (var (name, type) in types)
                {
                    using (var image = renderer.RenderImage(page, 2, type))
                        Assert.Equal(expected[(page, name)], Hash(image));
                    // No collection ran in between, so the render disposed its bitmaps.
                    AssertUnchanged(live, bytes, $"page {page} as {name}");
                }
        }

        // Printing rasterizes each page into an image of its own first.
        var printable = new DripSharp.PdfCarton.Printing.PDFPrintable(
            document, DripSharp.PdfCarton.Printing.Scaling.ActualSize, false, 144);
        using var paper = Compat.PdfCartonFontCompat.CreateBitmap(
            612, 792, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        live = Compat.PdfCartonNativeBitmap.LiveCount;
        bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        foreach (var page in Pages)
        {
            using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(paper))
            {
                Assert.Equal(Compat.JavaPrintConstants.PAGE_EXISTS,
                    printable.Print(graphics, new Compat.JavaPageFormat(), page));
            }
            AssertUnchanged(live, bytes, $"printing page {page}");
        }
        GC.KeepAlive(pinned);
    }

    // Decoding a masked, colour-keyed or DeviceN image keeps only the result.
    [Fact]
    public void ImageDecodingDisposesItsIntermediateBitmaps()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        var resources = document.GetPage(Images).GetResources();
        var streams = ImageNames.Concat(["ImBad"]).ToDictionary(
            name => name,
            name => (COSStream)resources.GetXObject(COSName.GetPDFName(name)).GetCOSObject());
        PDImageXObject Fresh(string name) => new(new PDStream(streams[name]), resources);
        var expected = ImageNames.ToDictionary(name => name, name =>
        {
            using var image = Fresh(name).GetImage();
            return Hash(image);
        });
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        for (var round = 0; round < 5; round++)
        {
            foreach (var name in ImageNames)
            {
                using (var image = Fresh(name).GetImage())
                    Assert.Equal(expected[name], Hash(image));
                AssertUnchanged(live, bytes, name);
            }
            // The soft mask fails to decode after the image itself decoded.
            Assert.ThrowsAny<Exception>(() => Fresh("ImBad").GetImage());
            AssertUnchanged(live, bytes, "image with a corrupt soft mask");
        }
    }

    // A fill failing anywhere in the page, including inside a soft-mask or
    // transparency group render, leaves no bitmap behind.
    [Fact]
    public void FailedRendersDisposeTheirIntermediateBitmaps()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        var pinned = PinImages(document.GetPage(Images));
        foreach (var page in Pages)
            using (new PDFRenderer(document).RenderImage(page, 2, ImageType.Rgb)) { }
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        foreach (var page in Pages)
        {
            var counting = new SamplingRenderer(document, int.MaxValue, null);
            using (counting.RenderImage(page, 2, ImageType.Rgb)) { }
            AssertUnchanged(live, bytes, $"page {page}");
            Assert.True(counting.Fills > 1, $"page {page} has {counting.Fills} fills.");
            foreach (var failAt in Enumerable.Range(1, 12).Where(fill => fill <= counting.Fills))
            {
                var failing = new SamplingRenderer(document, failAt, () => new InjectedFailure());
                Assert.Throws<InjectedFailure>(() => failing.RenderImage(page, 2, ImageType.Rgb));
                AssertUnchanged(live, bytes, $"page {page} failing at fill {failAt}");
            }
        }
        GC.KeepAlive(pinned);
    }

    // A group whose content fails in its Do operator is skipped and the page
    // goes on: the next group does not take the failed group's (disposed)
    // image as its backdrop.
    [Fact]
    public void RecoverableGroupFailuresKeepRendering()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        using (new PDFRenderer(document).RenderImage(TwoBlendedGroups, 2, ImageType.Rgb)) { }
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        var renderer = new SamplingRenderer(document, 2, () => new IOException("group content failed"));
        using (var image = renderer.RenderImage(TwoBlendedGroups, 2, ImageType.Rgb))
            Assert.Equal((288, 288), (image.Width, image.Height));
        Assert.True(renderer.Fills > 3, $"rendered {renderer.Fills} fills.");
        AssertUnchanged(live, bytes, "page with a failed group");
    }

    // renderPageToGraphics draws into the caller's graphics; a soft-masked
    // last paint is replaced by the caller's paint before its mask is
    // disposed, so the graphics stays usable.
    [Fact]
    public void RenderPageToGraphicsLeavesCallerGraphicsUsable()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        using var canvas = Compat.PdfCartonFontCompat.CreateBitmap(
            144, 144, Compat.PdfCartonFontCompat.TYPE_INT_ARGB);
        var pinned = PinImages(document.GetPage(Images));
        Settle();
        var live = Compat.PdfCartonNativeBitmap.LiveCount;
        var bytes = Compat.PdfCartonNativeBitmap.OutstandingBytes;
        foreach (var page in new[] { OneMaskedFill, GroupsAndBlending, Images })
        {
            using (var graphics = Compat.PdfCartonFontCompat.CreateGraphics(canvas))
            {
                new PDFRenderer(document).RenderPageToGraphics(page, graphics);
                Assert.IsNotType<SoftMask>(graphics.GetPaint());
                graphics.FillRect(0, 0, 10, 10);
            }
            AssertUnchanged(live, bytes, $"page {page} drawn into caller graphics");
        }
        GC.KeepAlive(pinned);
    }

    // Each fill under an ExtGState soft mask renders the mask anew; the
    // previous mask is disposed, so 200 masked fills peak like one.
    [Fact]
    public void SoftMaskedFillsKeepOneMaskLive()
    {
        using var document = Loader.LoadPDF(Signed(Fixture()));
        long Peak(int page)
        {
            Settle();
            var renderer = new SamplingRenderer(document, int.MaxValue, null)
            {
                Baseline = Compat.PdfCartonNativeBitmap.OutstandingBytes
            };
            using (renderer.RenderImage(page, 2, ImageType.Rgb)) { }
            return renderer.Peak;
        }
        var single = Peak(OneMaskedFill);
        var many = Peak(ManyMaskedFills);
        Assert.True(single > 0);
        Assert.True(many <= 2 * single,
            $"{MaskedFills} masked fills peaked at {many} native bytes, one at {single}.");
    }

    private static object[] PinImages(PDPage page)
    {
        var resources = page.GetResources();
        return ImageNames.SelectMany(name =>
        {
            var image = (PDImageXObject)resources.GetXObject(COSName.GetPDFName(name));
            return new object[] { image, image.GetImage() };
        }).ToArray();
    }

    private sealed class InjectedFailure : Exception
    {
    }

    // Counts fills, fails the given one, and samples the compat bitmap bytes
    // outstanding around every fill.
    private sealed class SamplingRenderer(PDDocument document, int failAt, Func<Exception>? failure)
        : PDFRenderer(document)
    {
        internal int Fills { get; private set; }
        internal long Baseline { get; init; }
        internal long Peak { get; private set; }

        protected internal override PageDrawer CreatePageDrawer(PageDrawerParameters parameters) =>
            new Drawer(parameters, this);

        private void Fill(Action fill)
        {
            if (++Fills == failAt) throw failure!();
            Sample();
            fill();
            Sample();
        }

        private void Sample() =>
            Peak = Math.Max(Peak, Compat.PdfCartonNativeBitmap.OutstandingBytes - Baseline);

        private sealed class Drawer(PageDrawerParameters parameters, SamplingRenderer owner)
            : PageDrawer(parameters)
        {
            public override void FillPath(int windingRule) =>
                owner.Fill(() => base.FillPath(windingRule));
        }
    }

    private static string Hash(SKBitmap image)
    {
        using var rgba = new SKBitmap(new SKImageInfo(
            image.Width, image.Height, SKColorType.Rgba8888, SKAlphaType.Unpremul));
        Assert.True(image.CopyTo(rgba, SKColorType.Rgba8888));
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(rgba.GetPixelSpan()));
    }

    private static void AssertUnchanged(long live, long bytes, string name) =>
        Assert.True(
            live == Compat.PdfCartonNativeBitmap.LiveCount &&
            bytes == Compat.PdfCartonNativeBitmap.OutstandingBytes,
            $"{name} left {Compat.PdfCartonNativeBitmap.LiveCount - live} bitmaps " +
            $"({Compat.PdfCartonNativeBitmap.OutstandingBytes - bytes} bytes) undisposed.");

    private static void Settle()
    {
        for (var pass = 0; pass < 3; pass++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
    }

    private static sbyte[] Signed(byte[] bytes) => (sbyte[])(Array)bytes;

    // Six pages: one fill under a luminosity soft mask; 200 such fills; an
    // alpha soft mask, a non-isolated group with a blend mode (drawn plain
    // and under the soft mask) and a page-level blend mode; images with soft
    // masks (scaled either way, with /Matte), a stencil /Mask, a colour key,
    // DeviceN attributes, a transfer function, a soft-masked image, a
    // downscaled image and stencil masks painted with a colour and a shading
    // pattern, and transfer functions on a soft-masked image and one that
    // fails; the soft-masked fill on a rotated page; and a blended group
    // drawn twice over a fill. ImBad, which no
    // page draws, has a soft mask that fails to decode.
    private static byte[] Fixture()
    {
        static string Stream(string entries, string data) =>
            $"<< {entries} /Length {data.Length} >>\nstream\n{data}\nendstream";
        static string Hex(int count, Func<int, int> sample) =>
            string.Concat(Enumerable.Range(0, count).Select(index => (sample(index) & 255).ToString("X2"))) + ">";
        static string Image(int width, int height, string colorSpace, int components, string extra,
            Func<int, int> sample) =>
            Stream($"/Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace {colorSpace} " +
                $"/BitsPerComponent 8 /Filter /ASCIIHexDecode {extra}",
                Hex(width * height * components, sample));
        static string Stencil(int size) =>
            Stream($"/Type /XObject /Subtype /Image /Width {size} /Height {size} /ImageMask true " +
                "/BitsPerComponent 1 /Filter /ASCIIHexDecode",
                Hex(size * size / 8, index => index % 2 == 0 ? 0xF0 : 0x3C));
        const string Resources = "/ExtGState << /SM 10 0 R /SA 11 0 R /MUL 12 0 R /TR 14 0 R /TRB 18 0 R >> " +
            "/XObject << /G 13 0 R /ImS 20 0 R /ImS2 22 0 R /ImM 24 0 R /ImK 26 0 R /ImN 27 0 R " +
            "/ImT 28 0 R /ImD 29 0 R /ImSt 30 0 R /ImBad 32 0 R >> /Pattern << /P0 15 0 R >>";
        static string Page(int contents, string extra = "") =>
            $"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 144 144] {extra} /Resources << {Resources} >> " +
            $"/Contents {contents} 0 R >>";
        var manyFills = string.Concat(Enumerable.Range(0, MaskedFills).Select(index =>
            $"1 0 0 rg {index % 20 * 7} {index / 20 * 7} 6 6 re f\n"));
        var objects = new SortedDictionary<int, string>
        {
            [1] = "<< /Type /Catalog /Pages 2 0 R >>",
            [2] = "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R 6 0 R 7 0 R 8 0 R] /Count 6 >>",
            [3] = Page(40),
            [4] = Page(41),
            [5] = Page(42),
            [6] = Page(43),
            [7] = Page(44, "/Rotate 90"),
            [8] = Page(45),
            [10] = "<< /Type /ExtGState /SMask << /Type /Mask /S /Luminosity /G 16 0 R /BC [0] >> >>",
            [11] = "<< /Type /ExtGState /SMask << /Type /Mask /S /Alpha /G 17 0 R >> >>",
            [12] = "<< /Type /ExtGState /BM /Multiply >>",
            [13] = Stream("/Type /XObject /Subtype /Form /BBox [0 0 144 144] /Group << /S /Transparency " +
                "/CS /DeviceRGB >> /Resources << /ExtGState << /MUL 12 0 R >> >>",
                "0 0 1 rg 10 10 60 60 re f /MUL gs 1 1 0 rg 40 40 60 60 re f"),
            [14] = "<< /Type /ExtGState /TR << /FunctionType 2 /Domain [0 1] /C0 [1] /C1 [0] /N 1 >> >>",
            [15] = "<< /PatternType 2 /Shading << /ShadingType 2 /ColorSpace /DeviceRGB /Coords [0 0 144 0] " +
                "/Function << /FunctionType 2 /Domain [0 1] /C0 [1 0 0] /C1 [0 0 1] /N 1 >> >> >>",
            // A transfer function that fails to parse once its output image exists.
            [18] = "<< /Type /ExtGState /TR << /FunctionType 7 /Domain [0 1] >> >>",
            [16] = Stream("/Type /XObject /Subtype /Form /BBox [0 0 144 144] /Group << /S /Transparency " +
                "/CS /DeviceGray >>", "0.8 g 20 20 100 100 re f 0.3 g 60 0 40 144 re f"),
            [17] = Stream("/Type /XObject /Subtype /Form /BBox [0 0 144 144] /Group << /S /Transparency " +
                "/CS /DeviceRGB >> /Resources << /ExtGState << /H << /ca 0.5 >> >> >>",
                "/H gs 0 0 0 rg 20 20 100 100 re f"),
            [20] = Image(8, 8, "/DeviceRGB", 3, "/SMask 21 0 R", index => index * 7),
            [21] = Image(4, 4, "/DeviceGray", 1, "", index => index * 16),
            [22] = Image(4, 4, "/DeviceRGB", 3, "/SMask 23 0 R", index => index * 21),
            [23] = Image(8, 8, "/DeviceGray", 1, "/Matte [0.5 0.5 0.5]", index => index * 4),
            [24] = Image(8, 8, "/DeviceRGB", 3, "/Mask 25 0 R", index => index * 5),
            [25] = Stencil(8),
            [26] = Image(8, 8, "/DeviceRGB", 3, "/Mask [0 100 0 100 0 100]", index => index * 11),
            [27] = Image(8, 8, "[/DeviceN [/Cyan /Spot] /DeviceCMYK 31 0 R << /Subtype /NChannel " +
                "/Process << /ColorSpace /DeviceCMYK /Components [/Cyan /Magenta /Yellow /Black] >> " +
                "/Colorants << /Spot [/Separation /Spot /DeviceRGB << /FunctionType 2 /Domain [0 1] " +
                "/C0 [1 1 1] /C1 [1 0 0] /N 1 >>] >> >>]", 2, "", index => index * 9),
            [28] = Image(8, 8, "/DeviceRGB", 3, "", index => index * 13),
            [29] = Image(64, 64, "/DeviceRGB", 3, "", index => index * 3),
            [30] = Stencil(8),
            [31] = Stream("/FunctionType 4 /Domain [0 1 0 1] /Range [0 1 0 1 0 1 0 1]", "{ pop 0 0 0 }"),
            [32] = Image(8, 8, "/DeviceRGB", 3, "/SMask 33 0 R", index => index),
            [33] = Stream("/Type /XObject /Subtype /Image /Width 8 /Height 8 /ColorSpace /DeviceGray " +
                "/BitsPerComponent 8 /Filter /DCTDecode", "not a jpeg"),
            [40] = Stream("", "q /SM gs 1 0 0 rg 10 10 6 6 re f Q"),
            [41] = Stream("", "q /SM gs\n" + manyFills + "Q"),
            [42] = Stream("", "q /SA gs 0 1 0 rg 0 0 30 30 re f Q q /SM gs /G Do Q /G Do " +
                "q /MUL gs 0 1 0 rg 50 50 40 40 re f Q"),
            [43] = Stream("", string.Join(" ",
                "q /TR gs 30 0 0 30 4 4 cm /ImT Do Q",
                "q /SM gs 30 0 0 30 38 4 cm /ImT Do Q",
                "q 30 0 0 30 72 4 cm /ImS Do Q",
                "q 30 0 0 30 106 4 cm /ImS2 Do Q",
                "q 30 0 0 30 4 38 cm /ImM Do Q",
                "q 30 0 0 30 38 38 cm /ImK Do Q",
                "q 30 0 0 30 72 38 cm /ImN Do Q",
                "q 8 0 0 8 106 38 cm /ImD Do Q",
                "q 1 0 0 rg 30 0 0 30 4 72 cm /ImSt Do Q",
                "q /Pattern cs /P0 scn 30 0 0 30 38 72 cm /ImSt Do Q",
                "q /TR gs 30 0 0 30 72 72 cm /ImS Do Q",
                "q /TRB gs 30 0 0 30 106 72 cm /ImT Do Q",
                "0 0 1 rg 100 100 20 20 re f 110 110 20 20 re f")),
            [44] = Stream("", "q /SM gs 1 0 0 rg 10 10 30 20 re f 50 50 30 20 re f Q"),
            [45] = Stream("", "1 0 0 rg 0 0 144 144 re f /G Do /G Do"),
        };
        var text = new System.Text.StringBuilder("%PDF-1.7\n");
        var offsets = new Dictionary<int, int>();
        foreach (var (number, body) in objects)
        {
            offsets[number] = text.Length;
            text.Append($"{number} 0 obj\n{body}\nendobj\n");
        }
        var size = objects.Keys.Max() + 1;
        var xref = text.Length;
        text.Append($"xref\n0 {size}\n");
        for (var number = 0; number < size; number++)
            text.Append(offsets.TryGetValue(number, out var offset)
                ? $"{offset:D10} 00000 n \n"
                : "0000000000 65535 f \n");
        text.Append($"trailer\n<< /Size {size} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return System.Text.Encoding.ASCII.GetBytes(text.ToString());
    }
}

// DCTFilter reads a four-component JPEG through ImageReader.readRaster, which
// ImageIO answers with the stored samples, without colour conversion: CMYK as
// stored, or YCC and K that DCTFilter converts to CMYK. The fixtures are
// 61x45 crops of PDFBox's jpegcmyk.jpg: ycck.jpg (Adobe transform 2, jpegtran
// -crop), ycck-progressive.jpg (jpegtran -progressive -restart 1 of it), and,
// written from ycck.jpg's samples by Java ImageIO with components 1 and 4
// sampled at 2x2 or 2x1 (Adobe transform 0) or 1x2 (transform 2): cmyk.jpg,
// cmyk-h2v1.jpg and ycck-h1v2.jpg, one per upsampling method. Expected values
// are Apache PDFBox 3.0.8 output on OpenJDK 25: the SHA-256 of the decoded
// stream and the getImage sRGB of every eighth pixel in each direction.
public sealed class FourComponentJpegTests
{
    private const string YcckDecoded =
        "fd02b548166274dc8fa03e81720d7455a765b5a3082367c39047d427e1f89740";
    private const string YcckColours =
        "231f20231f20fffcfdfff399fef5a0ffffffffffff262323252122211f20ffffff231f20" +
        "ffffffffffff201e1d221d1b272425ee2524f9fbfd242021fefefefefefe211f21004271" +
        "231f1fea1d24e42324231e1ffefefe262323211d1f003f71fdfdfded1e24ed1c24e91f25" +
        "252122231f20232021004174201e20ec1e24fffef5eb2126231f20231f20221f20003e6f";

    public static TheoryData<string, string, string> Cases() => new()
    {
        { "ycck.jpg", YcckDecoded, YcckColours },
        { "ycck-progressive.jpg", YcckDecoded, YcckColours },
        {
            "cmyk.jpg",
            "1c21ce1da2d9e377dc7629533f6c96e961bc8c896628a94dacaaed923066b2a0",
            "000000000203007481007e8200758100718000717c00020300000000000000718100" +
            "040600708000707f000000000203000000bb9f3d00727f000000007080006f7e0000" +
            "0060254e000000c3a538b9a03300000000707e000304000000602550006b79bea133" +
            "c59951bda0330002030000000000005f264f000406baa032007183bea13400000000" +
            "00000003045e244d"
        },
        {
            "cmyk-h2v1.jpg",
            "facd0d7549a3eddea741d37cd046802efb830993afba44371194e94d4d6af5cd",
            "00000000020300717d008f89007b8300707f006f7e00020300000000000000707f00" +
            "040600708000707f000000000203000000bba137006e8200000000707f006f7e0000" +
            "00602550000000c4a430b99a30000000006f8000030400000060244e006c78bea138" +
            "c59747bda1330002030000000000005f264f000406baa23200717fbea53500000000" +
            "00000003045e244e"
        },
        {
            "ycck-h1v2.jpg",
            "35b791ba8c664db3befc61211ad1ded01208bd4b7b96798aa80d1d7260a72193",
            "221f1f232122fffef5f3edbff7f7e7fffffffcf6f9252021231f20231c1dfdfeff27" +
            "2425fffffffefefe231d1d241f21231d1fe03527f5fbfe231d1ffffffffcfcfc221f" +
            "1d0040711f1e20e61e27e61c23211c1dfefefe262323221f20003f71ebeef0e32b26" +
            "bc3634e62a26252122231f20231f20004072272123e91c24f2fafeed1c24231f2023" +
            "1f20262223003e6e"
        },
    };

    internal static byte[] Fixture(string name) =>
        File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", name));

    [Theory]
    [MemberData(nameof(Cases))]
    public void DecodedSamplesMatchJavaPdfBox(string fixture, string decoded, string colours)
    {
        _ = colours;
        Assert.Equal(decoded, DecodedHash(Fixture(fixture), 61, 45));
    }

    // Narrow components, replicated rather than triangle-filtered (3x45 with
    // components 1 and 4 at 2x2, 2x45 at 2x1), and 3:1 and 1:4 sampling,
    // replicated: written by Java ImageIO like cmyk.jpg.
    public static TheoryData<string, int, string> Layouts() => new()
    {
        { "cmyk-narrow3.jpg", 3, "fbd904ef728593dfd0fdf025577dd62f26a69f90cbc069f8bcad8aaa2d35de1a" },
        { "ycck-narrow2.jpg", 2, "fb54bd34df2ff2059cb8cdb1d1068f6205280d019ebe4b1059f9d26436422bce" },
        { "cmyk-h3v1.jpg", 61, "1852d67f8e11c7d014f91aa30775698117b14b98e6b2cc4b15638d016666259b" },
        { "ycck-h1v4.jpg", 61, "e36878a3ed1140040db15db48ac6c77a8385ed30dd01a9d28bf9f4423023128b" },
    };

    [Theory]
    [MemberData(nameof(Layouts))]
    public void SamplingLayoutsMatchJavaPdfBox(string fixture, int width, string decoded) =>
        Assert.Equal(decoded, DecodedHash(Fixture(fixture), width, 45));

    // ycck-progressive.jpg cut short and ended with EOI. Cut at its first RST0,
    // the first (DC) scan's data runs out in its first restart interval, and
    // the remaining intervals stay zero; cut after that scan, the image has
    // DC values only. Both are block-smoothed, as by the libjpeg-turbo that
    // the reference OpenJDK's ImageIO links.
    [Fact]
    public void IncompleteProgressiveDataMatchesJavaPdfBox()
    {
        var progressive = Fixture("ycck-progressive.jpg");
        Assert.Equal(
            "276a2909f924284c5f2879e8ca2c044b58cc09318ea79825d2033130e904e60d",
            DecodedHash(EndAt(progressive, IndexOfMarker(progressive, 0xd0, 0)), 61, 45));
        var afterFirstScan = IndexOfMarker(progressive, -1, IndexOfMarker(progressive, 0xda, 0) + 2);
        Assert.Equal(
            "58a28ecaa38683c23fa27cb23170933ecafa02ede26db42f2102140d1bacf9ed",
            DecodedHash(EndAt(progressive, afterFirstScan), 61, 45));
    }

    // A frame header that ImageIO rejects (a zero sampling factor) fails
    // the read with an IOException, which PDFStreamEngine's image error
    // handling expects.
    [Fact]
    public void InvalidFrameHeaderThrowsIOException()
    {
        var data = Fixture("cmyk.jpg");
        data[IndexOfMarker(data, 0xc0, 0) + 11] = 0x01;
        Assert.Throws<IOException>(() => Reader(data).ReadRaster(0, null));
    }

    private static string DecodedHash(byte[] jpeg, int width, int height)
    {
        using var document = new PDDocument();
        var image = JPEGFactory.CreateFromByteArray(document, Signed(jpeg));
        // The upstream PDDeviceCMYKTest replaces PDDeviceCMYK.INSTANCE with a subclass.
        Assert.IsAssignableFrom<PDDeviceCMYK>(image.GetColorSpace());
        using var stream = image.CreateInputStream();
        using var samples = new MemoryStream();
        stream.CopyTo(samples);
        Assert.Equal(width * height * 4, samples.Length);
        Assert.Equal(4, image.GetRawRaster().NumberOfBands);
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(samples.ToArray()))
            .ToLowerInvariant();
    }

    // The offset of the first marker at or after start with the given code
    // (-1: any marker other than RSTn).
    private static int IndexOfMarker(byte[] data, int code, int start)
    {
        for (var index = start; index + 1 < data.Length; index++)
        {
            if (data[index] != 0xff) continue;
            var next = data[index + 1];
            if (code >= 0 ? next == code : next is not (0 or 0xff) and not (>= 0xd0 and <= 0xd7))
                return index;
        }
        throw new InvalidOperationException("Marker not found.");
    }

    private static byte[] EndAt(byte[] data, int length) =>
        [.. data.AsSpan(0, length), 0xff, 0xd9];

    // PDDeviceCMYK converts through PDFBox's CMYK output profile: Java uses
    // LCMS, PdfCarton its own ICC transform, so colours may differ by CMM
    // interpolation, as IccBasedColorsMatchPdfBoxAcrossProfilesAndConcurrentRenders
    // allows for its CMYK LUT profile. Without K a dark pixel turns light.
    [Theory]
    [MemberData(nameof(Cases))]
    public void RenderedColoursMatchJavaPdfBox(string fixture, string decoded, string colours)
    {
        _ = decoded;
        const int tolerance = 4;
        using var document = new PDDocument();
        var image = JPEGFactory.CreateFromByteArray(document, Signed(Fixture(fixture)));
        using var rgb = image.GetImage();
        Assert.Equal((61, 45), (rgb.Width, rgb.Height));
        var expected = Convert.FromHexString(colours);
        var index = 0;
        for (var y = 0; y < 45; y += 8)
        {
            for (var x = 0; x < 61; x += 8, index += 3)
            {
                var actual = Compat.PdfCartonFontCompat.GetRgb(rgb, x, y);
                for (var channel = 0; channel < 3; channel++)
                {
                    var value = (actual >> (16 - channel * 8)) & 0xff;
                    Assert.True(
                        Math.Abs(value - expected[index + channel]) <= tolerance,
                        $"{fixture} ({x}, {y}) channel {channel}: {value}, " +
                        $"expected {expected[index + channel]} +/- {tolerance}.");
                }
            }
        }
    }

    // ImageIO applies a read's source region and subsampling to the samples
    // of the whole image.
    [Fact]
    public void RasterRegionsAndSubsamplingSelectTheDecodedSamples()
    {
        foreach (var fixture in new[] { "ycck.jpg", "cmyk.jpg" })
        {
            var full = Reader(Fixture(fixture)).ReadRaster(0, null);
            Assert.Equal((61, 45, 4), (full.Width, full.Height, full.NumberOfBands));
            foreach (var (region, periodX, periodY, offsetX, offsetY) in new[]
                     {
                         (new SKRectI(3, 2, 56, 42), 1, 1, 0, 0),
                         (new SKRectI(0, 0, 61, 45), 2, 3, 1, 2),
                         (new SKRectI(5, 4, 61, 45), 3, 2, 2, 1),
                     })
            {
                var parameters = new Compat.JavaImageReadParam();
                parameters.SetSourceSubsampling(periodX, periodY, offsetX, offsetY);
                parameters.SetSourceRegion(region);
                var selected = Reader(Fixture(fixture)).ReadRaster(0, parameters);
                var firstX = region.Left + offsetX;
                var firstY = region.Top + offsetY;
                Assert.Equal(
                    ((region.Right - firstX + periodX - 1) / periodX,
                     (region.Bottom - firstY + periodY - 1) / periodY),
                    (selected.Width, selected.Height));
                for (var y = 0; y < selected.Height; y++)
                {
                    for (var x = 0; x < selected.Width; x++)
                    {
                        Assert.Equal(
                            full.GetPixel(firstX + x * periodX, firstY + y * periodY, (int[]?)null),
                            selected.GetPixel(x, y, (int[]?)null));
                    }
                }
            }
        }
    }

    private static Compat.JavaImageReader Reader(byte[] data)
    {
        var reader = DripSharp.PdfCarton.Filter.Filter.FindImageReader("JPEG", "codec missing");
        reader.SetInput(new Compat.JavaImageInputStream(new MemoryStream(data)));
        return reader;
    }

    private static sbyte[] Signed(byte[] bytes) => (sbyte[])(Array)bytes;
}

#pragma warning disable CS0618 // SKPath mutation is SkiaSharp m150's Java GeneralPath bridge.
// java.awt.geom path iterators read their path in place, one segment at a
// time, as upstream's do; shapes that are already paths are drawn in place.
public sealed class PathIteratorTests
{
    [Fact]
    public void PathIteratorsReadTheSameSegmentsAsATransformedCopy()
    {
        using var path = new SKPath { FillType = SKPathFillType.EvenOdd };
        path.MoveTo(1, 2);
        path.LineTo(10.5f, 2.25f);
        path.QuadTo(12, 4, 9, 9);
        path.CubicTo(7, 11, 3, 12, 1.125f, 8);
        path.Close();
        path.MoveTo(20, 20);
        path.LineTo(30, 25);
        var rotation = SKMatrix.CreateRotationDegrees(33, 4, 5);
        foreach (var transform in new object?[]
                 {
                     null, SKMatrix.Identity, SKMatrix.CreateScaleTranslation(1.5f, -2, 7, 300),
                     SKMatrix.Concat(rotation, SKMatrix.CreateSkew(0.25f, -0.5f)),
                 })
        {
            var expected = CopiedSegments(path, transform);
            Assert.Equal(expected, Segments(Compat.PdfCartonFontCompat.PathIterator(path, transform)));
            using var area = new Compat.JavaArea(path);
            Assert.Equal(expected, Segments(Compat.PdfCartonFontCompat.ShapePathIterator(area, transform)));
            Assert.Equal(expected, Segments(area.GetPathIterator(transform)));
            var iterator = Compat.PdfCartonFontCompat.PathIterator(path, transform);
            Assert.Equal(Compat.JavaPathIterator.WIND_EVEN_ODD, iterator.GetWindingRule());
            var floats = new float[6];
            Assert.Equal(Compat.JavaPathIterator.SEG_MOVETO, iterator.CurrentSegment(floats));
            Assert.Equal(expected[0], Segment(0, floats.Take(2).Select(value => (double)value)));
        }

        var rectangle = new SKRect(2, 3, 12, 8);
        using var rectanglePath = new SKPath();
        rectanglePath.AddRect(rectangle);
        Assert.Equal(CopiedSegments(rectanglePath, rotation),
            Segments(Compat.PdfCartonFontCompat.ShapePathIterator(rectangle, rotation)));
        var ellipse = new Compat.JavaEllipse(0, 0, 40, 20).GetPathIterator(rotation, 0.5);
        Assert.Equal("4:", Segments(ellipse)[^1]);
        Assert.True(ellipse.IsDone());
        ellipse.Dispose();
    }

    [Fact]
    public void IsDoneAndTheFirstSegmentsDoNotCopyALargePath()
    {
        using var large = Polyline(100_000);
        using var small = Polyline(10);
        long Allocated(SKPath path)
        {
            Compat.PdfCartonFontCompat.PathIterator(path, null).IsDone();
            var before = GC.GetAllocatedBytesForCurrentThread();
            var iterator = Compat.PdfCartonFontCompat.PathIterator(path, null);
            Assert.False(iterator.IsDone());
            var coordinates = new double[6];
            for (var segment = 0; segment < 5; segment++, iterator.Next())
                iterator.CurrentSegment(coordinates);
            iterator.Dispose();
            return GC.GetAllocatedBytesForCurrentThread() - before;
        }

        // The whole-path copy and per-segment arrays took ~11 MB here.
        Assert.Equal(Allocated(small), Allocated(large));
        Assert.True(Allocated(large) < 1024, $"{Allocated(large)} bytes");
        Assert.Equal(100_001, Segments(Compat.PdfCartonFontCompat.PathIterator(large, null)).Count);
    }

    [Fact]
    public void DrawingAndAppendingLendPathsInsteadOfCopyingThem()
    {
        using var path = Polyline(3);
        using var area = new Compat.JavaArea(path);
        using (var lease = Compat.PdfCartonFontCompat.BorrowPath(path))
            Assert.Same(path, lease.Path);
        using (var lease = Compat.PdfCartonFontCompat.BorrowPath(area))
            Assert.Same(area.Path, lease.Path);
        var rectangle = Compat.PdfCartonFontCompat.BorrowPath(new SKRect(0, 0, 1, 1));
        Assert.True(rectangle.Owned);
        rectangle.Dispose();

        // Skia ignores a path appended to itself, so self-appends still copy.
        Compat.PdfCartonFontCompat.AppendPath(path, path, false);
        Assert.Equal(8, path.VerbCount);
        Compat.PdfCartonFontCompat.AppendPath(
            path, Compat.PdfCartonFontCompat.PathIterator(path, null), false);
        Assert.Equal(16, path.VerbCount);

        // Appending an iterator appends the segments it has not yet returned,
        // continuing the destination's current subpath as Path2D.append does.
        using var target = new SKPath();
        target.MoveTo(0, 0);
        using var source = Polyline(3);
        source.Close();
        var iterator = Compat.PdfCartonFontCompat.PathIterator(source, SKMatrix.CreateTranslation(5, 0));
        iterator.Next();
        iterator.Next();
        Compat.PdfCartonFontCompat.AppendPath(target, iterator, false);
        Assert.True(iterator.IsDone());
        Assert.Equal(new[] { new SKPoint(0, 0), new SKPoint(7, 0), new SKPoint(8, 1) }, target.Points);
        Assert.Equal(new[] { "0:0,0", "1:7,0", "1:8,1", "4:" },
            Segments(Compat.PdfCartonFontCompat.PathIterator(target, null)));
        // A partially read iterator appended to its own path reads it first
        // (Skia records the implicit move back to the start after a close).
        var own = Compat.PdfCartonFontCompat.PathIterator(source, null);
        own.Next();
        Compat.PdfCartonFontCompat.AppendPath(source, own, true);
        Assert.Equal(new[] { "0:0,0", "1:1,1", "1:2,0", "1:3,1", "4:", "0:0,0", "1:1,1", "1:2,0", "1:3,1", "4:" },
            Segments(Compat.PdfCartonFontCompat.PathIterator(source, null)));
    }

    private static SKPath Polyline(int segments)
    {
        var path = new SKPath();
        path.MoveTo(0, 0);
        for (var index = 1; index <= segments; index++)
            path.LineTo(index, index % 2);
        return path;
    }

    private static string Segment(int type, IEnumerable<double> coordinates) =>
        $"{type}:{string.Join(",", coordinates.Select(value => value.ToString("R")))}";

    private static List<string> Segments(Compat.JavaPathIterator iterator)
    {
        var result = new List<string>();
        for (; !iterator.IsDone(); iterator.Next())
        {
            var coordinates = new double[6];
            var type = iterator.CurrentSegment(coordinates);
            var length = type switch { 0 or 1 => 2, 2 => 4, 3 => 6, _ => 0 };
            result.Add(Segment(type, coordinates.Take(length)));
        }
        return result;
    }

    // What the iterators returned when they copied and transformed the path.
    private static List<string> CopiedSegments(SKPath path, object? transform)
    {
        using var copy = new SKPath(path);
        if (transform is SKMatrix matrix) copy.Transform(matrix);
        var result = new List<string>();
        using var iterator = copy.CreateRawIterator();
        var points = new SKPoint[4];
        for (var verb = iterator.Next(points); verb != SKPathVerb.Done; verb = iterator.Next(points))
        {
            var (type, first, count) = verb switch
            {
                SKPathVerb.Move => (0, 0, 1),
                SKPathVerb.Line => (1, 1, 1),
                SKPathVerb.Quad => (2, 1, 2),
                SKPathVerb.Cubic => (3, 1, 3),
                _ => (4, 0, 0),
            };
            result.Add(Segment(type, points.Skip(first).Take(count)
                .SelectMany(point => new double[] { point.X, point.Y })));
        }
        return result;
    }
}
#pragma warning restore CS0618

// Undisposed SKPaths are counted as finalizable garbage process-wide, so
// these tests run alone.
[CollectionDefinition(nameof(NativePathLifetimeCollection), DisableParallelization = true)]
public sealed class NativePathLifetimeCollection
{
}

[Collection(nameof(NativePathLifetimeCollection))]
public sealed class GlyphPathLifetimeTests
{
    // Every glyph used to leave its transformed SKPath to the finalizer.
    // Text clips are dropped at ET, also when the mode stops clipping first.
    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void RenderingTextDisposesEveryGlyphPath(bool clip, bool fillBeforeEnd)
    {
        using var document = TextDocument(3000, clip, fillBeforeEnd);
        var renderer = new PDFRenderer(document);
        using (var warm = renderer.RenderImage(0, 1, ImageType.Rgb))
        {
            // Black text on a red page, or a red page fill clipped to the text.
            Assert.Equal(clip && !fillBeforeEnd ? SKColors.White : SKColors.Red,
                warm.GetPixel(300, 600));
            Assert.Contains(Enumerable.Range(0, 600).Select(x => warm.GetPixel(x, 7)),
                color => clip ? color.Red > 160 && color.Green < 160 : color.Red < 160);
        }

        for (var pass = 0; pass < 3; pass++)
        {
            GC.Collect();
            GC.WaitForPendingFinalizers();
        }
        var noCollection = GC.TryStartNoGCRegion(128L << 20);
        try
        {
            using (renderer.RenderImage(0, 1, ImageType.Rgb)) { }
        }
        finally
        {
            if (noCollection && System.Runtime.GCSettings.LatencyMode ==
                System.Runtime.GCLatencyMode.NoGCRegion)
                GC.EndNoGCRegion();
        }
        GC.Collect(2, GCCollectionMode.Forced, blocking: true);
        var abandoned = GC.GetGCMemoryInfo(GCKind.FullBlocking).FinalizationPendingCount;
        Assert.True(abandoned < 1000, $"{abandoned} finalizable objects after 3000 glyphs.");
    }

    private static PDDocument TextDocument(int glyphs, bool clip, bool fillBeforeEnd)
    {
        var document = new PDDocument();
        var page = new PDPage(new PDRectangle(600, 800));
        document.AddPage(page);
        using var fontStream = typeof(PDDocument).Assembly.GetManifestResourceStream(
            "org.apache.pdfbox.resources.ttf.LiberationSans-Regular.ttf")!;
        var font = PDType0Font.Load(document, fontStream, false);
        using var content = new PDPageContentStream(document, page);
        void FillPage()
        {
            content.SetNonStrokingColor(1f, 0f, 0f);
            content.AddRect(0, 0, 600, 800);
            content.Fill();
        }
        if (!clip) FillPage();
        content.SetNonStrokingColor(0f);
        content.BeginText();
        content.SetFont(font, 9);
        if (clip)
            content.SetRenderingMode(DripSharp.PdfCarton.Pdmodel.Graphics.State.RenderingMode.NeitherClip);
        content.NewLineAtOffset(5, 790);
        const string line = "The quick brown fox jumps over the lazy dog 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (var shown = 0; shown < glyphs; shown += line.Length)
        {
            content.ShowText(line.Substring(0, Math.Min(line.Length, glyphs - shown)));
            content.NewLineAtOffset(0, -10);
        }
        if (fillBeforeEnd)
            content.SetRenderingMode(DripSharp.PdfCarton.Pdmodel.Graphics.State.RenderingMode.Fill);
        content.EndText();
        if (clip) FillPage();
        return document;
    }
}
