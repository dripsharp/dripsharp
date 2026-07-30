#nullable disable
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.Runtime;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Printing;
using DripSharp.PdfCarton.Rendering;
using SkiaSharp;

internal static class Program
{
    private static readonly List<string> Observations = new();

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is not 1 and not 4)
                throw new ArgumentException(
                    "Expected output trace or output trace, canonical trace, OS, and architecture.");

            ObservePaperAndPageFormat();
            ObserveBook();
            using (var document = CreateDocument())
            {
                ObservePageable(document);
                ObserveScaling(document);
                ObserveRasterization(document);
                ObserveRenderedOutput(document);
                ObserveFailuresAndLifecycle(document);
                ObserveCallerProvidedSurfaces(document);
            }

            File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
            if (args.Length == 4)
            {
                VerifyCanonical(args[0], args[1]);
                Console.WriteLine(
                    $"DripSharp.PdfCarton printing host smoke passed: {args[2]}/{args[3]}");
            }
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObservePaperAndPageFormat()
    {
        var paper = new JavaPaper();
        Observe(
            "paper",
            "defaults",
            paper.GetWidth(),
            paper.GetHeight(),
            paper.GetImageableX(),
            paper.GetImageableY(),
            paper.GetImageableWidth(),
            paper.GetImageableHeight());

        paper.SetSize(-10, double.NaN);
        paper.SetImageableArea(
            double.NegativeInfinity,
            3,
            -4,
            double.PositiveInfinity);
        Observe(
            "paper",
            "arbitrary-double-contract",
            paper.GetWidth(),
            paper.GetHeight(),
            paper.GetImageableX(),
            paper.GetImageableY(),
            paper.GetImageableWidth(),
            paper.GetImageableHeight());

        var sourcePaper = new JavaPaper();
        sourcePaper.SetSize(200, 300);
        sourcePaper.SetImageableArea(10, 20, 150, 250);
        var clonedPaper = (JavaPaper)sourcePaper.Clone();
        clonedPaper.SetSize(400, 500);
        clonedPaper.SetImageableArea(1, 2, 3, 4);
        Observe(
            "paper",
            "clone-isolation",
            sourcePaper.GetWidth(),
            sourcePaper.GetImageableX(),
            clonedPaper.GetWidth(),
            clonedPaper.GetImageableX());

        foreach (var orientation in new[]
                 {
                     JavaPageFormat.PORTRAIT,
                     JavaPageFormat.LANDSCAPE,
                     JavaPageFormat.REVERSE_LANDSCAPE
                 })
        {
            var format = Format(sourcePaper, orientation);
            Observe(
                "page-format",
                OrientationName(orientation),
                format.GetWidth(),
                format.GetHeight(),
                format.GetImageableX(),
                format.GetImageableY(),
                format.GetImageableWidth(),
                format.GetImageableHeight(),
                format.GetOrientation(),
                Numbers(format.GetMatrix()));
        }

        var defensive = Format(sourcePaper, JavaPageFormat.PORTRAIT);
        var returned = defensive.GetPaper();
        returned.SetSize(1, 2);
        var clonedFormat = (JavaPageFormat)defensive.Clone();
        clonedFormat.SetOrientation(JavaPageFormat.LANDSCAPE);
        var changedClonePaper = clonedFormat.GetPaper();
        changedClonePaper.SetSize(8, 9);
        clonedFormat.SetPaper(changedClonePaper);
        Observe(
            "page-format",
            "defensive-paper-and-clone",
            defensive.GetWidth(),
            returned.GetWidth(),
            clonedFormat.GetWidth(),
            defensive.GetOrientation(),
            clonedFormat.GetOrientation());
        Observe(
            "failure",
            "invalid-orientation",
            Failure(() => defensive.SetOrientation(-1)),
            Failure(() => defensive.SetOrientation(3)));
    }

    private static void ObserveBook()
    {
        var book = new JavaBook();
        var first = new NamedPrintable("first");
        var second = new NamedPrintable("second");
        var firstFormat = new JavaPageFormat();
        var secondFormat = new JavaPageFormat();

        book.Append(first, firstFormat, 3);
        Observe(
            "book",
            "append-range",
            book.GetNumberOfPages(),
            ReferenceEquals(book.GetPrintable(0), first),
            ReferenceEquals(book.GetPrintable(2), first),
            ReferenceEquals(book.GetPageFormat(1), firstFormat));

        firstFormat.SetOrientation(JavaPageFormat.LANDSCAPE);
        book.SetPage(1, second, secondFormat);
        book.Append(first, firstFormat, 0);
        Observe(
            "book",
            "identity-and-set-page",
            book.GetNumberOfPages(),
            book.GetPageFormat(0).GetOrientation(),
            ReferenceEquals(book.GetPrintable(1), second),
            ReferenceEquals(book.GetPageFormat(1), secondFormat));

        book.Append(first, firstFormat, -1);
        Observe(
            "book",
            "negative-count-shrinks",
            book.GetNumberOfPages(),
            ReferenceEquals(book.GetPrintable(0), first),
            ReferenceEquals(book.GetPrintable(1), second));
        Observe(
            "failure",
            "book-indexes",
            Failure(() => book.GetPageFormat(-1)),
            Failure(() => book.GetPrintable(2)),
            Failure(() => new JavaBook().Append(first, firstFormat, -1)));
        Observe(
            "failure",
            "book-null-order",
            Failure(() => book.SetPage(99, null, firstFormat)),
            Failure(() => book.SetPage(99, first, null)),
            Failure(() => book.Append(null, firstFormat)));
    }

    private static PDDocument CreateDocument()
    {
        var document = new PDDocument();

        var landscape = new PDPage(new PDRectangle(200, 100));
        landscape.SetCropBox(new PDRectangle(10, 20, 160, 60));
        document.AddPage(landscape);
        using (var content = new PDPageContentStream(document, landscape))
        {
            content.SetNonStrokingColor(1f, 0f, 0f);
            content.AddRect(30, 35, 40, 20);
            content.Fill();
        }

        var rotated = new PDPage(new PDRectangle(100, 200));
        rotated.SetCropBox(new PDRectangle(5, 15, 80, 160));
        rotated.SetRotation(90);
        document.AddPage(rotated);

        var portrait = new PDPage(new PDRectangle(120, 180));
        portrait.SetCropBox(new PDRectangle(6, 8, 100, 150));
        document.AddPage(portrait);
        return document;
    }

    private static void ObservePageable(PDDocument document)
    {
        var automatic = new PDFPageable(document);
        Observe(
            "pageable",
            "page-count-and-auto-layout",
            automatic.GetNumberOfPages(),
            PageFormat(automatic.GetPageFormat(0)),
            PageFormat(automatic.GetPageFormat(1)),
            PageFormat(automatic.GetPageFormat(2)));

        foreach (var pair in new[]
                 {
                     ("auto", Orientation.Auto),
                     ("landscape", Orientation.Landscape),
                     ("portrait", Orientation.Portrait),
                     ("reverse_landscape", Orientation.ReverseLandscape)
                 })
        {
            var pageable =
                new PDFPageable(document, pair.Item2, true, 144, false);
            pageable.SetSubsamplingAllowed(true);
            Observe(
                "orientation",
                pair.Item1,
                PageFormat(pageable.GetPageFormat(0)),
                pageable.IsSubsamplingAllowed(),
                pageable.GetPrintable(0) is PDFPrintable);
        }

        var negative = automatic.GetPrintable(-1);
        Observe(
            "pageable",
            "printable-index-contract",
            negative is PDFPrintable,
            Failure(() => automatic.GetPrintable(automatic.GetNumberOfPages())),
            Failure(() => automatic.GetPageFormat(-1)),
            Failure(() =>
                automatic.GetPageFormat(automatic.GetNumberOfPages())));
    }

    private static void ObserveScaling(PDDocument document)
    {
        var paper = new JavaPaper();
        paper.SetSize(120, 120);
        paper.SetImageableArea(5, 7, 100, 100);
        var pageFormat = new JavaPageFormat();
        pageFormat.SetPaper(paper);

        foreach (var pair in new[]
                 {
                     ("actual_size", Scaling.ActualSize),
                     ("shrink_to_fit", Scaling.ShrinkToFit),
                     ("stretch_to_fit", Scaling.StretchToFit),
                     ("scale_to_fit", Scaling.ScaleToFit)
                 })
        {
            var renderer = new CaptureRenderer(document, false);
            var printable =
                new PDFPrintable(
                    document,
                    pair.Item2,
                    false,
                    PDFPrintable.RasterizeOff,
                    true,
                    renderer);
            using var output =
                new SKBitmap(new SKImageInfo(140, 140, SKColorType.Bgra8888));
            output.Erase(SKColors.White);
            using (var graphics = new PdfCartonGraphics2D(output))
            {
                var result = printable.Print(graphics, pageFormat, 0);
                Observe(
                    "scaling",
                    pair.Item1,
                    result,
                    renderer.ScaleX,
                    renderer.ScaleY,
                    Matrix(renderer.Transform),
                    Bounds(output));
            }
        }
    }

    private static void ObserveRasterization(PDDocument document)
    {
        var pageFormat = FullPageFormat(100, 100);

        var fixedRenderer = new CaptureRenderer(document, false);
        var fixedPrintable =
            new PDFPrintable(
                document,
                Scaling.ShrinkToFit,
                false,
                144,
                true,
                fixedRenderer);
        using var fixedOutput =
            new SKBitmap(new SKImageInfo(120, 120, SKColorType.Bgra8888));
        fixedOutput.Erase(SKColors.White);
        int fixedResult;
        using (var fixedGraphics = new PdfCartonGraphics2D(fixedOutput))
        {
            fixedResult =
                fixedPrintable.Print(fixedGraphics, pageFormat, 0);
        }
        Observe(
            "rasterization",
            "fixed-dpi",
            fixedResult,
            fixedRenderer.ScaleX,
            fixedRenderer.ScaleY,
            Bounds(fixedOutput),
            Color(fixedOutput, 0, 0));

        var borderRenderer = new CaptureRenderer(document, false);
        var border =
            new PDFPrintable(
                document,
                Scaling.ShrinkToFit,
                true,
                144,
                true,
                borderRenderer);
        using var borderOutput =
            new SKBitmap(new SKImageInfo(120, 120, SKColorType.Bgra8888));
        borderOutput.Erase(SKColors.White);
        int borderResult;
        using (var borderGraphics = new PdfCartonGraphics2D(borderOutput))
        {
            borderResult = border.Print(borderGraphics, pageFormat, 0);
        }
        Observe(
            "rasterization",
            "page-border",
            borderResult,
            HasGrayPixel(borderOutput));

        var autoRenderer = new CaptureRenderer(document, false);
        var automatic =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                false,
                PDFPrintable.RasterizeDpiAuto,
                false,
                autoRenderer);
        using var autoOutput =
            new SKBitmap(new SKImageInfo(240, 240, SKColorType.Bgra8888));
        autoOutput.Erase(SKColors.White);
        int autoResult;
        using (var autoGraphics = new PdfCartonGraphics2D(autoOutput))
        {
            autoGraphics.Scale(2, 2);
            autoResult = automatic.Print(autoGraphics, pageFormat, 0);
        }
        Observe(
            "rasterization",
            "automatic-dpi",
            autoResult,
            autoRenderer.ScaleX,
            autoRenderer.ScaleY,
            Bounds(autoOutput));
    }

    private static void ObserveRenderedOutput(PDDocument document)
    {
        using var output =
            new SKBitmap(new SKImageInfo(200, 100, SKColorType.Bgra8888));
        output.Erase(SKColors.White);
        var printable =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                false,
                PDFPrintable.RasterizeOff,
                false);
        int result;
        using (var graphics = new PdfCartonGraphics2D(output))
        {
            result =
                printable.Print(graphics, FullPageFormat(200, 100), 0);
        }
        Observe(
            "rendering",
            "caller-surface-content",
            result,
            Color(output, 40, 35),
            Color(output, 25, 45),
            Color(output, 100, 50),
            Bounds(output));
    }

    private static void ObserveFailuresAndLifecycle(PDDocument document)
    {
        var pageFormat = FullPageFormat(100, 100);
        var invalidRenderer = new CaptureRenderer(document, false);
        var invalid =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                false,
                0,
                false,
                invalidRenderer);
        using var invalidImage =
            new SKBitmap(new SKImageInfo(100, 100, SKColorType.Bgra8888));
        using (var invalidGraphics = new PdfCartonGraphics2D(invalidImage))
        {
            Observe(
                "failure",
                "printable-indexes",
                invalid.Print(invalidGraphics, pageFormat, -1),
                invalid.Print(
                    invalidGraphics,
                    pageFormat,
                    document.GetNumberOfPages()),
                invalidRenderer.Calls);
        }

        var throwingRenderer = new CaptureRenderer(document, true);
        var throwing =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                false,
                0,
                false,
                throwingRenderer);
        using var throwingImage =
            new SKBitmap(new SKImageInfo(100, 100, SKColorType.Bgra8888));
        var throwingFailure = "none";
        using (var throwingGraphics = new PdfCartonGraphics2D(throwingImage))
        {
            try
            {
                throwing.Print(throwingGraphics, pageFormat, 0);
            }
            catch (IOException error)
            {
                throwingFailure =
                    $"printer-io:{error.InnerException is IOException}"
                        .ToLowerInvariant();
            }
        }
        Observe("failure", "renderer-io", throwingFailure);

        var renderer = new CaptureRenderer(document, false);
        var printable =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                true,
                144,
                false,
                renderer);
        using var output =
            new SKBitmap(new SKImageInfo(160, 160, SKColorType.Bgra8888));
        output.Erase(SKColors.White);
        using (var graphics = new PdfCartonGraphics2D(output))
        {
            graphics.Translate(7, 11);
            graphics.Scale(1.3, 1.3);
            graphics.SetColor(SKColors.Red);
            graphics.SetBackground(SKColors.Blue);
            var originalStroke = new JavaBasicStroke(3.7f);
            graphics.SetStroke(originalStroke);
            graphics.SetClip(2, 3, 50, 60);
            var originalTransform = graphics.GetTransform();
            var originalColor = graphics.GetColor();
            var originalBackground = graphics.GetBackground();
            var originalClip = graphics.GetClipBounds();

            var result = printable.Print(graphics, pageFormat, 0);
            Observe(
                "lifecycle",
                "caller-state-isolation",
                result,
                originalTransform == graphics.GetTransform(),
                ReferenceEquals(originalColor, graphics.GetColor()),
                ReferenceEquals(originalBackground, graphics.GetBackground()),
                ReferenceEquals(originalStroke, graphics.GetStroke()),
                originalClip == graphics.GetClipBounds());
            graphics.SetClip(null);
            graphics.SetTransform(SKMatrix.CreateIdentity());
            graphics.SetColor(SKColors.Black);
            graphics.FillRect(120, 120, 5, 5);
        }
        Observe(
            "lifecycle",
            "caller-remains-usable",
            Color(output, 122, 122));
    }

    private static void ObserveCallerProvidedSurfaces(PDDocument document)
    {
        using var surface =
            SKSurface.Create(new SKImageInfo(200, 100, SKColorType.Bgra8888));
        surface.Canvas.Clear(SKColors.White);
        var printable =
            new PDFPrintable(
                document,
                Scaling.ActualSize,
                false,
                0,
                false);
        int canvasResult;
        using (var graphics = new PdfCartonGraphics2D(surface.Canvas))
        {
            canvasResult =
                printable.Print(
                    graphics,
                    FullPageFormat(200, 100),
                    0);
        }
        surface.Canvas.DrawPoint(199, 99, SKColors.Black);
        using var firstSnapshot = surface.Snapshot();
        using var firstPixels =
            new SKBitmap(new SKImageInfo(200, 100, SKColorType.Bgra8888));
        firstSnapshot.ReadPixels(
            firstPixels.Info,
            firstPixels.GetPixels(),
            firstPixels.RowBytes,
            0,
            0);

        surface.Canvas.Clear(SKColors.White);
        int surfaceResult;
        using (var graphics = new PdfCartonGraphics2D(surface))
        {
            surfaceResult =
                printable.Print(
                    graphics,
                    FullPageFormat(200, 100),
                    0);
        }
        surface.Canvas.DrawPoint(198, 99, SKColors.Black);
        using var secondSnapshot = surface.Snapshot();
        using var secondPixels =
            new SKBitmap(new SKImageInfo(200, 100, SKColorType.Bgra8888));
        secondSnapshot.ReadPixels(
            secondPixels.Info,
            secondPixels.GetPixels(),
            secondPixels.RowBytes,
            0,
            0);

        Observe(
            "host-surface",
            "canvas-and-surface-ownership",
            canvasResult,
            surfaceResult,
            Color(firstPixels, 199, 99),
            Color(secondPixels, 198, 99),
            Color(firstPixels, 40, 35),
            Color(secondPixels, 40, 35));
    }

    private static JavaPageFormat Format(
        JavaPaper paper,
        int orientation)
    {
        var format = new JavaPageFormat();
        format.SetPaper(paper);
        format.SetOrientation(orientation);
        return format;
    }

    private static JavaPageFormat FullPageFormat(
        double width,
        double height)
    {
        var paper = new JavaPaper();
        paper.SetSize(width, height);
        paper.SetImageableArea(0, 0, width, height);
        var format = new JavaPageFormat();
        format.SetPaper(paper);
        return format;
    }

    private static string PageFormat(JavaPageFormat format)
    {
        var paper = format.GetPaper();
        return Value(
            format.GetOrientation(),
            format.GetWidth(),
            format.GetHeight(),
            format.GetImageableX(),
            format.GetImageableY(),
            format.GetImageableWidth(),
            format.GetImageableHeight(),
            paper.GetWidth(),
            paper.GetHeight(),
            paper.GetImageableX(),
            paper.GetImageableY(),
            paper.GetImageableWidth(),
            paper.GetImageableHeight(),
            Numbers(format.GetMatrix()));
    }

    private static string OrientationName(int orientation) =>
        orientation switch
        {
            JavaPageFormat.LANDSCAPE => "landscape",
            JavaPageFormat.REVERSE_LANDSCAPE => "reverse-landscape",
            _ => "portrait"
        };

    private static string Bounds(SKBitmap image)
    {
        var left = image.Width;
        var top = image.Height;
        var right = -1;
        var bottom = -1;
        var count = 0;
        for (var y = 0; y < image.Height; y++)
        {
            for (var x = 0; x < image.Width; x++)
            {
                if (IsSolidInk(image.GetPixel(x, y)))
                {
                    left = Math.Min(left, x);
                    top = Math.Min(top, y);
                    right = Math.Max(right, x);
                    bottom = Math.Max(bottom, y);
                    count++;
                }
            }
        }
        return Value(left, top, right, bottom, count);
    }

    private static string Color(SKBitmap image, int x, int y)
    {
        var color = image.GetPixel(x, y);
        return string.Create(
            CultureInfo.InvariantCulture,
            $"{color.Alpha:X2}{color.Red:X2}{color.Green:X2}{color.Blue:X2}");
    }

    private static bool IsSolidInk(SKColor color) =>
        Math.Min(color.Red, Math.Min(color.Green, color.Blue)) < 64;

    private static bool HasGrayPixel(SKBitmap image)
    {
        for (var y = 0; y < image.Height; y++)
        {
            for (var x = 0; x < image.Width; x++)
            {
                var color = image.GetPixel(x, y);
                if (color.Red == color.Green &&
                    color.Green == color.Blue &&
                    color.Red > 50 &&
                    color.Red < 200)
                    return true;
            }
        }
        return false;
    }

    private static string Matrix(SKMatrix transform) =>
        Numbers(
            new double[]
            {
                transform.ScaleX,
                transform.SkewY,
                transform.SkewX,
                transform.ScaleY,
                transform.TransX,
                transform.TransY
            });

    private static string Numbers(double[] values) =>
        Value(values.Cast<object>().ToArray());

    private static string Failure(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (NullReferenceException)
        {
            return "null";
        }
        catch (ArgumentOutOfRangeException)
        {
            return "range";
        }
        catch (IndexOutOfRangeException)
        {
            return "range";
        }
        catch (ArgumentException)
        {
            return "argument";
        }
        catch (Exception error)
        {
            return error.GetType().Name;
        }
    }

    private static void Observe(
        string family,
        string id,
        params object[] parts) =>
        Observations.Add($"{family}\t{id}\t{Value(parts)}");

    private static string Value(params object[] parts) =>
        string.Join("|", parts.Select(FormatValue));

    private static string FormatValue(object value)
    {
        switch (value)
        {
            case double number:
                return FormatNumber(number);
            case float number:
                return FormatNumber(number);
            default:
                return Convert.ToString(value, CultureInfo.InvariantCulture)
                    .ToLowerInvariant();
        }
    }

    private static string FormatNumber(double number)
    {
        if (double.IsNaN(number))
            return "NaN";
        if (double.IsPositiveInfinity(number))
            return "Infinity";
        if (double.IsNegativeInfinity(number))
            return "-Infinity";
        return number.ToString("0.0000", CultureInfo.InvariantCulture);
    }

    private static void VerifyCanonical(string actual, string canonical)
    {
        var actualLines = File.ReadAllLines(actual);
        var canonicalLines = File.ReadAllLines(canonical);
        if (!actualLines.SequenceEqual(canonicalLines))
        {
            var mismatch = Enumerable
                .Range(0, Math.Max(actualLines.Length, canonicalLines.Length))
                .First(index =>
                    index >= actualLines.Length ||
                    index >= canonicalLines.Length ||
                    actualLines[index] != canonicalLines[index]);
            throw new InvalidOperationException(
                $"Printing host trace differs at line {mismatch + 1}.");
        }
    }

    private sealed class NamedPrintable : JavaPrintable
    {
        private readonly string name;

        internal NamedPrintable(string name) => this.name = name;

        public int Print(
            PdfCartonGraphics2D graphics,
            JavaPageFormat pageFormat,
            int pageIndex) =>
            JavaPrintable.PAGE_EXISTS;

        public override string ToString() => name;
    }

    private sealed class CaptureRenderer : PDFRenderer
    {
        private readonly bool fail;

        internal CaptureRenderer(PDDocument document, bool fail)
            : base(document) =>
            this.fail = fail;

        internal int Calls { get; private set; }
        internal float ScaleX { get; private set; }
        internal float ScaleY { get; private set; }
        internal SKMatrix Transform { get; private set; } =
            SKMatrix.CreateIdentity();

        public override void RenderPageToGraphics(
            int pageIndex,
            PdfCartonGraphics2D graphics,
            float scaleX,
            float scaleY,
            RenderDestination destination)
        {
            Calls++;
            ScaleX = scaleX;
            ScaleY = scaleY;
            Transform = graphics.GetTransform();
            if (fail)
                throw new IOException(
                    "deliberate printing probe failure");
            graphics.Scale(scaleX, scaleY);
            graphics.SetColor(SKColors.Black);
            graphics.FillRect(0, 0, 10, 10);
        }
    }
}
