import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.print.Book;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.Orientation;
import org.apache.pdfbox.printing.PDFPageable;
import org.apache.pdfbox.printing.PDFPrintable;
import org.apache.pdfbox.printing.Scaling;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

public final class PdfBoxPrintingOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            throw new IllegalArgumentException("Expected the output trace path.");
        }

        observePaperAndPageFormat();
        observeBook();
        try (PDDocument document = createDocument())
        {
            observePageable(document);
            observeScaling(document);
            observeRasterization(document);
            observeRenderedOutput(document);
            observeFailuresAndLifecycle(document);
            observeCallerProvidedSurfaces(document);
        }

        Files.write(
                Paths.get(args[0]),
                OBSERVATIONS,
                StandardCharsets.UTF_8);
    }

    private static void observePaperAndPageFormat()
    {
        Paper paper = new Paper();
        observe(
                "paper",
                "defaults",
                paper.getWidth(),
                paper.getHeight(),
                paper.getImageableX(),
                paper.getImageableY(),
                paper.getImageableWidth(),
                paper.getImageableHeight());

        paper.setSize(-10, Double.NaN);
        paper.setImageableArea(
                Double.NEGATIVE_INFINITY,
                3,
                -4,
                Double.POSITIVE_INFINITY);
        observe(
                "paper",
                "arbitrary-double-contract",
                paper.getWidth(),
                paper.getHeight(),
                paper.getImageableX(),
                paper.getImageableY(),
                paper.getImageableWidth(),
                paper.getImageableHeight());

        Paper sourcePaper = new Paper();
        sourcePaper.setSize(200, 300);
        sourcePaper.setImageableArea(10, 20, 150, 250);
        Paper clonedPaper = (Paper) sourcePaper.clone();
        clonedPaper.setSize(400, 500);
        clonedPaper.setImageableArea(1, 2, 3, 4);
        observe(
                "paper",
                "clone-isolation",
                sourcePaper.getWidth(),
                sourcePaper.getImageableX(),
                clonedPaper.getWidth(),
                clonedPaper.getImageableX());

        for (int orientation :
                new int[] {
                    PageFormat.PORTRAIT,
                    PageFormat.LANDSCAPE,
                    PageFormat.REVERSE_LANDSCAPE
                })
        {
            PageFormat format = format(sourcePaper, orientation);
            observe(
                    "page-format",
                    orientationName(orientation),
                    format.getWidth(),
                    format.getHeight(),
                    format.getImageableX(),
                    format.getImageableY(),
                    format.getImageableWidth(),
                    format.getImageableHeight(),
                    format.getOrientation(),
                    numbers(format.getMatrix()));
        }

        PageFormat defensive = format(sourcePaper, PageFormat.PORTRAIT);
        Paper returned = defensive.getPaper();
        returned.setSize(1, 2);
        PageFormat clonedFormat = (PageFormat) defensive.clone();
        clonedFormat.setOrientation(PageFormat.LANDSCAPE);
        Paper changedClonePaper = clonedFormat.getPaper();
        changedClonePaper.setSize(8, 9);
        clonedFormat.setPaper(changedClonePaper);
        observe(
                "page-format",
                "defensive-paper-and-clone",
                defensive.getWidth(),
                returned.getWidth(),
                clonedFormat.getWidth(),
                defensive.getOrientation(),
                clonedFormat.getOrientation());
        observe(
                "failure",
                "invalid-orientation",
                failure(() -> defensive.setOrientation(-1)),
                failure(() -> defensive.setOrientation(3)));
    }

    private static void observeBook()
    {
        Book book = new Book();
        NamedPrintable first = new NamedPrintable("first");
        NamedPrintable second = new NamedPrintable("second");
        PageFormat firstFormat = new PageFormat();
        PageFormat secondFormat = new PageFormat();

        book.append(first, firstFormat, 3);
        observe(
                "book",
                "append-range",
                book.getNumberOfPages(),
                book.getPrintable(0) == first,
                book.getPrintable(2) == first,
                book.getPageFormat(1) == firstFormat);

        firstFormat.setOrientation(PageFormat.LANDSCAPE);
        book.setPage(1, second, secondFormat);
        book.append(first, firstFormat, 0);
        observe(
                "book",
                "identity-and-set-page",
                book.getNumberOfPages(),
                book.getPageFormat(0).getOrientation(),
                book.getPrintable(1) == second,
                book.getPageFormat(1) == secondFormat);

        book.append(first, firstFormat, -1);
        observe(
                "book",
                "negative-count-shrinks",
                book.getNumberOfPages(),
                book.getPrintable(0) == first,
                book.getPrintable(1) == second);
        observe(
                "failure",
                "book-indexes",
                failure(() -> book.getPageFormat(-1)),
                failure(() -> book.getPrintable(2)),
                failure(() -> new Book().append(first, firstFormat, -1)));
        observe(
                "failure",
                "book-null-order",
                failure(() -> book.setPage(99, null, firstFormat)),
                failure(() -> book.setPage(99, first, null)),
                failure(() -> book.append(null, firstFormat)));
    }

    private static PDDocument createDocument() throws IOException
    {
        PDDocument document = new PDDocument();

        PDPage landscape = new PDPage(new PDRectangle(200, 100));
        landscape.setCropBox(new PDRectangle(10, 20, 160, 60));
        document.addPage(landscape);
        try (PDPageContentStream content =
                new PDPageContentStream(document, landscape))
        {
            content.setNonStrokingColor(1f, 0f, 0f);
            content.addRect(30, 35, 40, 20);
            content.fill();
        }

        PDPage rotated = new PDPage(new PDRectangle(100, 200));
        rotated.setCropBox(new PDRectangle(5, 15, 80, 160));
        rotated.setRotation(90);
        document.addPage(rotated);

        PDPage portrait = new PDPage(new PDRectangle(120, 180));
        portrait.setCropBox(new PDRectangle(6, 8, 100, 150));
        document.addPage(portrait);
        return document;
    }

    private static void observePageable(PDDocument document)
    {
        PDFPageable auto = new PDFPageable(document);
        observe(
                "pageable",
                "page-count-and-auto-layout",
                auto.getNumberOfPages(),
                pageFormat(auto.getPageFormat(0)),
                pageFormat(auto.getPageFormat(1)),
                pageFormat(auto.getPageFormat(2)));

        for (Orientation orientation : Orientation.values())
        {
            PDFPageable pageable =
                    new PDFPageable(document, orientation, true, 144, false);
            pageable.setSubsamplingAllowed(true);
            observe(
                    "orientation",
                    orientation.name().toLowerCase(Locale.ROOT),
                    pageFormat(pageable.getPageFormat(0)),
                    pageable.isSubsamplingAllowed(),
                    pageable.getPrintable(0) instanceof PDFPrintable);
        }

        Printable negative = auto.getPrintable(-1);
        observe(
                "pageable",
                "printable-index-contract",
                negative instanceof PDFPrintable,
                failure(() -> auto.getPrintable(auto.getNumberOfPages())),
                failure(() -> auto.getPageFormat(-1)),
                failure(() -> auto.getPageFormat(auto.getNumberOfPages())));
    }

    private static void observeScaling(PDDocument document)
            throws Exception
    {
        Paper paper = new Paper();
        paper.setSize(120, 120);
        paper.setImageableArea(5, 7, 100, 100);
        PageFormat pageFormat = new PageFormat();
        pageFormat.setPaper(paper);

        for (Scaling scaling : Scaling.values())
        {
            CaptureRenderer renderer = new CaptureRenderer(document, false);
            PDFPrintable printable =
                    new PDFPrintable(
                            document,
                            scaling,
                            false,
                            PDFPrintable.RASTERIZE_OFF,
                            true,
                            renderer);
            BufferedImage output =
                    new BufferedImage(140, 140, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = output.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            int result = printable.print(graphics, pageFormat, 0);
            graphics.dispose();
            observe(
                    "scaling",
                    scaling.name().toLowerCase(Locale.ROOT),
                    result,
                    renderer.scaleX,
                    renderer.scaleY,
                    matrix(renderer.transform),
                    bounds(output));
        }
    }

    private static void observeRasterization(PDDocument document)
            throws Exception
    {
        PageFormat pageFormat = fullPageFormat(100, 100);

        CaptureRenderer fixedRenderer = new CaptureRenderer(document, false);
        PDFPrintable fixed =
                new PDFPrintable(
                        document,
                        Scaling.SHRINK_TO_FIT,
                        false,
                        144,
                        true,
                        fixedRenderer);
        BufferedImage fixedOutput =
                new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D fixedGraphics = fixedOutput.createGraphics();
        fixedGraphics.setColor(Color.WHITE);
        fixedGraphics.fillRect(
                0, 0, fixedOutput.getWidth(), fixedOutput.getHeight());
        int fixedResult = fixed.print(fixedGraphics, pageFormat, 0);
        fixedGraphics.dispose();
        observe(
                "rasterization",
                "fixed-dpi",
                fixedResult,
                fixedRenderer.scaleX,
                fixedRenderer.scaleY,
                bounds(fixedOutput),
                color(fixedOutput, 0, 0));

        CaptureRenderer borderRenderer = new CaptureRenderer(document, false);
        PDFPrintable border =
                new PDFPrintable(
                        document,
                        Scaling.SHRINK_TO_FIT,
                        true,
                        144,
                        true,
                        borderRenderer);
        BufferedImage borderOutput =
                new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D borderGraphics = borderOutput.createGraphics();
        borderGraphics.setColor(Color.WHITE);
        borderGraphics.fillRect(
                0, 0, borderOutput.getWidth(), borderOutput.getHeight());
        int borderResult = border.print(borderGraphics, pageFormat, 0);
        borderGraphics.dispose();
        observe(
                "rasterization",
                "page-border",
                borderResult,
                hasGrayPixel(borderOutput));

        CaptureRenderer autoRenderer = new CaptureRenderer(document, false);
        PDFPrintable automatic =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        false,
                        PDFPrintable.RASTERIZE_DPI_AUTO,
                        false,
                        autoRenderer);
        BufferedImage autoOutput =
                new BufferedImage(240, 240, BufferedImage.TYPE_INT_ARGB);
        Graphics2D autoGraphics = autoOutput.createGraphics();
        autoGraphics.setColor(Color.WHITE);
        autoGraphics.fillRect(
                0, 0, autoOutput.getWidth(), autoOutput.getHeight());
        autoGraphics.scale(2, 2);
        int autoResult = automatic.print(autoGraphics, pageFormat, 0);
        autoGraphics.dispose();
        observe(
                "rasterization",
                "automatic-dpi",
                autoResult,
                autoRenderer.scaleX,
                autoRenderer.scaleY,
                bounds(autoOutput));
    }

    private static void observeRenderedOutput(PDDocument document)
            throws Exception
    {
        BufferedImage output =
                new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
        PDFPrintable printable =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        false,
                        PDFPrintable.RASTERIZE_OFF,
                        false);
        int result = printable.print(graphics, fullPageFormat(200, 100), 0);
        graphics.dispose();
        observe(
                "rendering",
                "caller-surface-content",
                result,
                color(output, 40, 35),
                color(output, 25, 45),
                color(output, 100, 50),
                bounds(output));
    }

    private static void observeFailuresAndLifecycle(PDDocument document)
            throws Exception
    {
        PageFormat pageFormat = fullPageFormat(100, 100);
        CaptureRenderer invalidRenderer =
                new CaptureRenderer(document, false);
        PDFPrintable invalid =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        false,
                        0,
                        false,
                        invalidRenderer);
        BufferedImage invalidImage =
                new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D invalidGraphics = invalidImage.createGraphics();
        observe(
                "failure",
                "printable-indexes",
                invalid.print(invalidGraphics, pageFormat, -1),
                invalid.print(
                        invalidGraphics,
                        pageFormat,
                        document.getNumberOfPages()),
                invalidRenderer.calls);
        invalidGraphics.dispose();

        CaptureRenderer throwingRenderer =
                new CaptureRenderer(document, true);
        PDFPrintable throwing =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        false,
                        0,
                        false,
                        throwingRenderer);
        BufferedImage throwingImage =
                new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D throwingGraphics = throwingImage.createGraphics();
        String throwingFailure;
        try
        {
            throwing.print(throwingGraphics, pageFormat, 0);
            throwingFailure = "none";
        }
        catch (PrinterIOException error)
        {
            throwingFailure =
                    "printer-io:" +
                    (error.getIOException() instanceof IOException);
        }
        throwingGraphics.dispose();
        observe("failure", "renderer-io", throwingFailure);

        CaptureRenderer renderer = new CaptureRenderer(document, false);
        PDFPrintable printable =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        true,
                        144,
                        false,
                        renderer);
        BufferedImage output =
                new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        graphics.translate(7, 11);
        graphics.scale(1.3, 1.3);
        graphics.setColor(Color.RED);
        graphics.setBackground(Color.BLUE);
        graphics.setStroke(new BasicStroke(3.7f));
        graphics.setClip(2, 3, 50, 60);
        AffineTransform originalTransform = graphics.getTransform();
        Color originalColor = graphics.getColor();
        Color originalBackground = graphics.getBackground();
        BasicStroke originalStroke = (BasicStroke) graphics.getStroke();
        Rectangle originalClip = graphics.getClipBounds();

        int result = printable.print(graphics, pageFormat, 0);
        observe(
                "lifecycle",
                "caller-state-isolation",
                result,
                originalTransform.equals(graphics.getTransform()),
                originalColor.equals(graphics.getColor()),
                originalBackground.equals(graphics.getBackground()),
                originalStroke.equals(graphics.getStroke()),
                originalClip.equals(graphics.getClipBounds()));
        graphics.setClip(null);
        graphics.setTransform(new AffineTransform());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(120, 120, 5, 5);
        graphics.dispose();
        observe(
                "lifecycle",
                "caller-remains-usable",
                color(output, 122, 122));
    }

    private static void observeCallerProvidedSurfaces(PDDocument document)
            throws Exception
    {
        PDFPrintable printable =
                new PDFPrintable(
                        document,
                        Scaling.ACTUAL_SIZE,
                        false,
                        0,
                        false);

        BufferedImage first =
                new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D firstGraphics = first.createGraphics();
        firstGraphics.setColor(Color.WHITE);
        firstGraphics.fillRect(0, 0, first.getWidth(), first.getHeight());
        int firstResult =
                printable.print(
                        firstGraphics,
                        fullPageFormat(200, 100),
                        0);
        firstGraphics.dispose();
        first.setRGB(199, 99, 0xFF000000);

        BufferedImage second =
                new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D secondGraphics = second.createGraphics();
        secondGraphics.setColor(Color.WHITE);
        secondGraphics.fillRect(
                0, 0, second.getWidth(), second.getHeight());
        int secondResult =
                printable.print(
                        secondGraphics,
                        fullPageFormat(200, 100),
                        0);
        secondGraphics.dispose();
        second.setRGB(198, 99, 0xFF000000);

        observe(
                "host-surface",
                "canvas-and-surface-ownership",
                firstResult,
                secondResult,
                color(first, 199, 99),
                color(second, 198, 99),
                color(first, 40, 35),
                color(second, 40, 35));
    }

    private static PageFormat format(Paper paper, int orientation)
    {
        PageFormat format = new PageFormat();
        format.setPaper(paper);
        format.setOrientation(orientation);
        return format;
    }

    private static PageFormat fullPageFormat(double width, double height)
    {
        Paper paper = new Paper();
        paper.setSize(width, height);
        paper.setImageableArea(0, 0, width, height);
        PageFormat format = new PageFormat();
        format.setPaper(paper);
        return format;
    }

    private static String pageFormat(PageFormat format)
    {
        Paper paper = format.getPaper();
        return value(
                format.getOrientation(),
                format.getWidth(),
                format.getHeight(),
                format.getImageableX(),
                format.getImageableY(),
                format.getImageableWidth(),
                format.getImageableHeight(),
                paper.getWidth(),
                paper.getHeight(),
                paper.getImageableX(),
                paper.getImageableY(),
                paper.getImageableWidth(),
                paper.getImageableHeight(),
                numbers(format.getMatrix()));
    }

    private static String orientationName(int orientation)
    {
        switch (orientation)
        {
            case PageFormat.LANDSCAPE:
                return "landscape";
            case PageFormat.REVERSE_LANDSCAPE:
                return "reverse-landscape";
            default:
                return "portrait";
        }
    }

    private static String bounds(BufferedImage image)
    {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                if (isSolidInk(image.getRGB(x, y)))
                {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                    count++;
                }
            }
        }
        return value(left, top, right, bottom, count);
    }

    private static String color(BufferedImage image, int x, int y)
    {
        return String.format(
                Locale.ROOT, "%08X", image.getRGB(x, y));
    }

    private static boolean isSolidInk(int argb)
    {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return Math.min(red, Math.min(green, blue)) < 64;
    }

    private static boolean hasGrayPixel(BufferedImage image)
    {
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                int argb = image.getRGB(x, y);
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (red == green && green == blue &&
                        red > 50 && red < 200)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static String matrix(AffineTransform transform)
    {
        double[] values = new double[6];
        transform.getMatrix(values);
        return numbers(values);
    }

    private static String numbers(double[] values)
    {
        Object[] boxed = new Object[values.length];
        for (int index = 0; index < values.length; index++)
        {
            boxed[index] = values[index];
        }
        return value(boxed);
    }

    private static String failure(ThrowingAction action)
    {
        try
        {
            action.run();
            return "none";
        }
        catch (NullPointerException error)
        {
            return "null";
        }
        catch (IndexOutOfBoundsException error)
        {
            return "range";
        }
        catch (IllegalArgumentException error)
        {
            return "argument";
        }
        catch (Exception error)
        {
            return error.getClass().getSimpleName();
        }
    }

    private static void observe(String family, String id, Object... parts)
    {
        OBSERVATIONS.add(family + "\t" + id + "\t" + value(parts));
    }

    private static String value(Object... parts)
    {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++)
        {
            if (index > 0)
            {
                builder.append('|');
            }
            builder.append(formatValue(parts[index]));
        }
        return builder.toString();
    }

    private static String formatValue(Object value)
    {
        if (value instanceof Double || value instanceof Float)
        {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number))
            {
                return "NaN";
            }
            if (number == Double.POSITIVE_INFINITY)
            {
                return "Infinity";
            }
            if (number == Double.NEGATIVE_INFINITY)
            {
                return "-Infinity";
            }
            return String.format(Locale.ROOT, "%.4f", number);
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private interface ThrowingAction
    {
        void run() throws Exception;
    }

    private static final class NamedPrintable implements Printable
    {
        private final String name;

        private NamedPrintable(String name)
        {
            this.name = name;
        }

        @Override
        public int print(
                java.awt.Graphics graphics,
                PageFormat pageFormat,
                int pageIndex)
        {
            return PAGE_EXISTS;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }

    private static final class CaptureRenderer extends PDFRenderer
    {
        private final boolean fail;
        private int calls;
        private float scaleX;
        private float scaleY;
        private AffineTransform transform = new AffineTransform();

        private CaptureRenderer(PDDocument document, boolean fail)
        {
            super(document);
            this.fail = fail;
        }

        @Override
        public void renderPageToGraphics(
                int pageIndex,
                Graphics2D graphics,
                float scaleX,
                float scaleY,
                RenderDestination destination)
                throws IOException
        {
            calls++;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            transform = graphics.getTransform();
            if (fail)
            {
                throw new IOException("deliberate printing probe failure");
            }
            graphics.scale(scaleX, scaleY);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, 10, 10);
        }
    }
}
