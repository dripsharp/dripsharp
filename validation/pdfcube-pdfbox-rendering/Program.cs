#nullable disable
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using DripSharp.Runtime;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Rendering;
using SkiaSharp;

internal static class Program
{
    private sealed record Fixture(
        string Id,
        string RelativePath,
        int PageIndex,
        float Scale);

    private static readonly Fixture[] Fixtures =
    {
        new("survey-1", "input/rendering/survey.pdf", 0, 0.25f),
        new("survey-5", "input/rendering/survey.pdf", 4, 0.25f),
        new("form-xobject", "input/rendering/tiger-as-form-xobject.pdf", 0, 0.25f),
        new(
            "annotations",
            "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf",
            0,
            0.25f),
        new("type3", "input/PDFBOX-3053-reduced.pdf", 0, 0.25f),
        new("soft-mask", "input/merge/PDFBOX-5811-362972.pdf", 0, 0.20f),
        new("transparency-group", "input/PDFBOX-3195.pdf", 0, 0.20f),
        new("image", "input/merge/jpegrgb.pdf", 0, 0.25f)
    };

    private static readonly List<string> Manifest = new();

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2)
                throw new ArgumentException(
                    "Expected output manifest and PDFBox resource root.");
            var manifestPath = Path.GetFullPath(args[0]);
            var outputRoot = Path.Combine(
                Path.GetDirectoryName(manifestPath)!,
                "dotnet-raw");
            Directory.CreateDirectory(outputRoot);

            RenderGraphics2D(outputRoot);
            foreach (var fixture in Fixtures)
                RenderFixture(args[1], outputRoot, fixture);

            File.WriteAllLines(
                manifestPath,
                Manifest,
                new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void RenderGraphics2D(string outputRoot)
    {
        using var bitmap = new SKBitmap(
            new SKImageInfo(
                64,
                64,
                SKColorType.Bgra8888,
                SKAlphaType.Unpremul));
        bitmap.Erase(SKColors.Transparent);
        using (var graphics = new PdfCartonGraphics2D(bitmap))
        {
            graphics.SetRenderingHint(
                PdfCartonRenderingHints.KEY_ANTIALIASING,
                PdfCartonRenderingHints.VALUE_ANTIALIAS_ON);
            graphics.SetColor(SKColors.White);
            graphics.FillRect(0, 0, 64, 64);
            graphics.SetColor(new SKColor(220, 30, 20, 255));
            graphics.FillRect(3, 4, 24, 19);

            using (var child = graphics.Create())
            {
                child.SetClip(8, 8, 38, 34);
                child.Translate(5, 3);
                child.SetComposite(
                    JavaAlphaComposite.GetInstance(
                        JavaAlphaComposite.SRC_OVER,
                        0.5f));
                child.SetColor(new SKColor(20, 190, 70, 230));
                child.FillOval(4, 5, 31, 27);
            }

            using var tile = new SKBitmap(
                new SKImageInfo(
                    2,
                    2,
                    SKColorType.Bgra8888,
                    SKAlphaType.Unpremul));
            tile.SetPixel(0, 0, new SKColor(20, 40, 220, 255));
            tile.SetPixel(1, 0, new SKColor(240, 210, 20, 255));
            tile.SetPixel(0, 1, new SKColor(240, 210, 20, 255));
            tile.SetPixel(1, 1, new SKColor(20, 40, 220, 255));
            graphics.SetComposite(
                JavaAlphaComposite.GetInstance(
                    JavaAlphaComposite.SRC_OVER,
                    1f));
            graphics.SetPaint(new JavaTexturePaint(tile, new SKRect(0, 0, 8, 8)));
            using (var triangleBuilder = new SKPathBuilder())
            {
                triangleBuilder.MoveTo(4, 56);
                triangleBuilder.LineTo(31, 30);
                triangleBuilder.LineTo(42, 59);
                triangleBuilder.Close();
                using var triangle = triangleBuilder.Detach();
                graphics.Fill(triangle);
            }

            graphics.SetColor(new SKColor(25, 25, 25, 255));
            graphics.SetStroke(
                new JavaBasicStroke(
                    2.5f,
                    JavaBasicStroke.CAP_ROUND,
                    JavaBasicStroke.JOIN_BEVEL,
                    10f,
                    new[] { 5f, 3f },
                    1f));
            graphics.DrawLine(2, 27, 58, 27);

            using var stamp = new SKBitmap(
                new SKImageInfo(
                    4,
                    4,
                    SKColorType.Bgra8888,
                    SKAlphaType.Unpremul));
            stamp.Erase(new SKColor(170, 30, 210, 190));
            var imageTransform = new SKMatrix
            {
                ScaleX = 3,
                ScaleY = 3,
                TransX = 47,
                TransY = 5,
                Persp2 = 1
            };
            graphics.DrawImage(stamp, imageTransform, null);
        }

        WriteImage(outputRoot, "graphics2d", bitmap);
        Manifest.Add("structure\tgraphics2d\t64\t64\tchild-dispose\tcpu");
    }

    private static void RenderFixture(
        string resourceRoot,
        string outputRoot,
        Fixture fixture)
    {
        var path = Path.Combine(
            resourceRoot,
            fixture.RelativePath.Replace('/', Path.DirectorySeparatorChar));
        using var document = Loader.LoadPDF(new FileInfo(path));
        if (fixture.PageIndex >= document.GetNumberOfPages())
        {
            throw new InvalidOperationException(
                $"Fixture {fixture.Id} does not contain page {fixture.PageIndex}.");
        }
        var page = document.GetPage(fixture.PageIndex);
        var cropBox = page.GetCropBox();
        var annotations = page.GetAnnotations().Count;
        var renderer = new PDFRenderer(document);
        using var image = renderer.RenderImage(
            fixture.PageIndex,
            fixture.Scale,
            ImageType.Argb);
        WriteImage(outputRoot, fixture.Id, image);
        Manifest.Add(
            string.Join(
                "\t",
                "structure",
                fixture.Id,
                document.GetNumberOfPages().ToString(CultureInfo.InvariantCulture),
                fixture.PageIndex.ToString(CultureInfo.InvariantCulture),
                page.GetRotation().ToString(CultureInfo.InvariantCulture),
                annotations.ToString(CultureInfo.InvariantCulture),
                cropBox.GetWidth().ToString("F3", CultureInfo.InvariantCulture),
                cropBox.GetHeight().ToString("F3", CultureInfo.InvariantCulture)));
    }

    private static void WriteImage(
        string outputRoot,
        string id,
        SKBitmap image)
    {
        var bytes = new byte[checked(image.Width * image.Height * 4)];
        var offset = 0;
        for (var y = 0; y < image.Height; y++)
        {
            for (var x = 0; x < image.Width; x++)
            {
                var color = image.GetPixel(x, y);
                bytes[offset++] = color.Red;
                bytes[offset++] = color.Green;
                bytes[offset++] = color.Blue;
                bytes[offset++] = color.Alpha;
            }
        }
        File.WriteAllBytes(Path.Combine(outputRoot, id + ".rgba"), bytes);
        Manifest.Add(
            string.Join(
                "\t",
                "image",
                id,
                image.Width.ToString(CultureInfo.InvariantCulture),
                image.Height.ToString(CultureInfo.InvariantCulture),
                (image.Width * image.Height).ToString(
                    CultureInfo.InvariantCulture)));
    }
}
