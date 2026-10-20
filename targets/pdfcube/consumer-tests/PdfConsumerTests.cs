using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Filter;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Graphics.Color;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using DripSharp.PdfCarton.Rendering;
using SkiaSharp;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class PdfConsumerTests
{
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
