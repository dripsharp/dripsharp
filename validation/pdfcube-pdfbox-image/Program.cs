using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.Runtime;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Graphics.Color;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using SkiaSharp;

internal static class Program
{
    private static readonly string[] Fixtures =
    {
        "JPXTestCMYK.pdf",
        "JPXTestGrey.pdf",
        "JPXTestRGB.pdf",
        "JBIG2Image.pdf"
    };

    private static readonly List<string> Observations = new();

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2)
                throw new ArgumentException(
                    "Expected output trace and image resource directory.");

            foreach (var fixture in Fixtures)
                ObserveFixture(Path.Combine(args[1], fixture), fixture);
            ObserveMalformedJpxFailure();
            File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveFixture(string path, string fixture)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        var resources = document.GetPage(0).GetResources();
        var names = resources.GetXObjectNames()
            .OrderBy(name => name.GetName(), StringComparer.Ordinal)
            .ToArray();
        var imageIndex = 0;
        foreach (var name in names)
        {
            if (resources.GetXObject(name) is not PDImageXObject image)
                continue;

            var id = $"{fixture}:{imageIndex++}";
            Observe(
                "codec-metadata",
                id,
                image.GetWidth(),
                image.GetHeight(),
                image.GetBitsPerComponent(),
                image.GetColorSpace().GetName(),
                image.GetColorSpace().GetNumberOfComponents(),
                image.GetSuffix());

            var raw = image.GetRawRaster();
            Observe(
                "full-pixels",
                id,
                raw.Width,
                raw.Height,
                raw.NumberOfBands,
                SampleSummary(raw));

            var region = Region(image.GetWidth(), image.GetHeight());
            using var sampled = image.GetImage(region, 2);
            Observe(
                "region-subsampling",
                id,
                region.Left,
                region.Top,
                region.Width,
                region.Height,
                2,
                sampled.Width,
                sampled.Height);
        }

        if (imageIndex == 0)
            throw new InvalidOperationException(
                $"Fixture contains no page image: {fixture}");
    }

    private static SKRectI Region(int width, int height)
    {
        var x = width / 4;
        var y = height / 4;
        return new SKRectI(
            x,
            y,
            x + Math.Max(1, width / 2),
            y + Math.Max(1, height / 2));
    }

    private static string SampleSummary(JavaRaster raster)
    {
        var minimum = Enumerable.Repeat(int.MaxValue, raster.NumberOfBands).ToArray();
        var maximum = Enumerable.Repeat(int.MinValue, raster.NumberOfBands).ToArray();
        var sum = new long[raster.NumberOfBands];
        var samples = raster.GetPixels(
            0, 0, raster.Width, raster.Height, null);
        for (var index = 0; index < samples.Length; index++)
        {
            var band = index % raster.NumberOfBands;
            var sample = samples[index];
            minimum[band] = Math.Min(minimum[band], sample);
            maximum[band] = Math.Max(maximum[band], sample);
            sum[band] += sample;
        }
        var pixels = raster.Width * raster.Height;
        var statistics = new string[raster.NumberOfBands];
        for (var band = 0; band < statistics.Length; band++)
        {
            statistics[band] = FormattableString.Invariant(
                $"{minimum[band]},{maximum[band]},{sum[band] / (double)pixels:F3}");
        }
        return string.Join(";", statistics);
    }

    private static void ObserveMalformedJpxFailure()
    {
        var failed = false;
        try
        {
            using var document = new PDDocument();
            using var encoded = new MemoryStream(new byte[] { 0, 1, 2, 3 });
            var image = new PDImageXObject(
                document,
                encoded,
                COSName.JpxDecode,
                2,
                2,
                8,
                PDDeviceRGB.Instance);
            using var decoded = image.GetImage();
        }
        catch (Exception)
        {
            failed = true;
        }
        Observe("failure", "malformed-jpx", failed);
    }

    private static void Observe(string family, string id, params object?[] values)
    {
        Observations.Add(
            $"{family}\t{id}\t{string.Join("|", values.Select(Normalize))}");
    }

    private static string Normalize(object? value) => value switch
    {
        null => "null",
        bool boolean => boolean ? "true" : "false",
        _ => Convert.ToString(value, System.Globalization.CultureInfo.InvariantCulture)
             ?? "null"
    };
}
