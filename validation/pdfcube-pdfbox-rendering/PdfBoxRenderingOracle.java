import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class PdfBoxRenderingOracle
{
    private static final class Fixture
    {
        private final String id;
        private final String relativePath;
        private final int pageIndex;
        private final float scale;

        private Fixture(
                String id,
                String relativePath,
                int pageIndex,
                float scale)
        {
            this.id = id;
            this.relativePath = relativePath;
            this.pageIndex = pageIndex;
            this.scale = scale;
        }

        private String id()
        {
            return id;
        }

        private String relativePath()
        {
            return relativePath;
        }

        private int pageIndex()
        {
            return pageIndex;
        }

        private float scale()
        {
            return scale;
        }
    }

    private static final Fixture[] FIXTURES =
    {
        new Fixture("survey-1", "input/rendering/survey.pdf", 0, 0.25f),
        new Fixture("survey-5", "input/rendering/survey.pdf", 4, 0.25f),
        new Fixture(
                "form-xobject",
                "input/rendering/tiger-as-form-xobject.pdf",
                0,
                0.25f),
        new Fixture(
                "annotations",
                "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf",
                0,
                0.25f),
        new Fixture(
                "type3",
                "input/PDFBOX-3053-reduced.pdf",
                0,
                0.25f),
        new Fixture(
                "soft-mask",
                "input/merge/PDFBOX-5811-362972.pdf",
                0,
                0.20f),
        new Fixture(
                "transparency-group",
                "input/PDFBOX-3195.pdf",
                0,
                0.20f),
        new Fixture("image", "input/merge/jpegrgb.pdf", 0, 0.25f)
    };

    private static final List<String> MANIFEST = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            throw new IllegalArgumentException(
                    "Expected output manifest and PDFBox resource root.");
        }
        Path manifest = Paths.get(args[0]).toAbsolutePath();
        Path outputRoot = manifest.getParent().resolve("java-raw");
        Files.createDirectories(outputRoot);

        renderGraphics2D(outputRoot);
        Path resourceRoot = Paths.get(args[1]);
        for (Fixture fixture : FIXTURES)
        {
            renderFixture(resourceRoot, outputRoot, fixture);
        }
        Files.write(manifest, MANIFEST);
    }

    private static void renderGraphics2D(Path outputRoot) throws IOException
    {
        BufferedImage image =
                new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 64, 64);
            graphics.setColor(new Color(220, 30, 20, 255));
            graphics.fillRect(3, 4, 24, 19);

            Graphics2D child = (Graphics2D) graphics.create();
            try
            {
                child.setClip(8, 8, 38, 34);
                child.translate(5, 3);
                child.setComposite(
                        AlphaComposite.getInstance(
                                AlphaComposite.SRC_OVER,
                                0.5f));
                child.setColor(new Color(20, 190, 70, 230));
                child.fillOval(4, 5, 31, 27);
            }
            finally
            {
                child.dispose();
            }

            BufferedImage tile =
                    new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            tile.setRGB(0, 0, new Color(20, 40, 220, 255).getRGB());
            tile.setRGB(1, 0, new Color(240, 210, 20, 255).getRGB());
            tile.setRGB(0, 1, new Color(240, 210, 20, 255).getRGB());
            tile.setRGB(1, 1, new Color(20, 40, 220, 255).getRGB());
            graphics.setComposite(
                    AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
            graphics.setPaint(
                    new TexturePaint(
                            tile,
                            new java.awt.Rectangle(0, 0, 8, 8)));
            Path2D triangle = new Path2D.Float();
            triangle.moveTo(4, 56);
            triangle.lineTo(31, 30);
            triangle.lineTo(42, 59);
            triangle.closePath();
            graphics.fill(triangle);

            graphics.setColor(new Color(25, 25, 25, 255));
            graphics.setStroke(
                    new BasicStroke(
                            2.5f,
                            BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_BEVEL,
                            10f,
                            new float[] { 5f, 3f },
                            1f));
            graphics.drawLine(2, 27, 58, 27);

            BufferedImage stamp =
                    new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            Graphics2D stampGraphics = stamp.createGraphics();
            try
            {
                stampGraphics.setComposite(AlphaComposite.Src);
                stampGraphics.setColor(new Color(170, 30, 210, 190));
                stampGraphics.fillRect(0, 0, 4, 4);
            }
            finally
            {
                stampGraphics.dispose();
            }
            graphics.drawImage(
                    stamp,
                    new AffineTransform(3, 0, 0, 3, 47, 5),
                    null);
        }
        finally
        {
            graphics.dispose();
        }

        writeImage(outputRoot, "graphics2d", image);
        MANIFEST.add("structure\tgraphics2d\t64\t64\tchild-dispose\tcpu");
    }

    private static void renderFixture(
            Path resourceRoot,
            Path outputRoot,
            Fixture fixture) throws Exception
    {
        Path path = resourceRoot.resolve(fixture.relativePath());
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            if (fixture.pageIndex() >= document.getNumberOfPages())
            {
                throw new IllegalStateException(
                        "Fixture " + fixture.id() + " does not contain page "
                                + fixture.pageIndex());
            }
            PDPage page = document.getPage(fixture.pageIndex());
            PDRectangle cropBox = page.getCropBox();
            int annotations = page.getAnnotations().size();
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImage(
                    fixture.pageIndex(),
                    fixture.scale(),
                    ImageType.ARGB);
            writeImage(outputRoot, fixture.id(), image);
            MANIFEST.add(String.join(
                    "\t",
                    "structure",
                    fixture.id(),
                    Integer.toString(document.getNumberOfPages()),
                    Integer.toString(fixture.pageIndex()),
                    Integer.toString(page.getRotation()),
                    Integer.toString(annotations),
                    format(cropBox.getWidth()),
                    format(cropBox.getHeight())));
        }
    }

    private static void writeImage(
            Path outputRoot,
            String id,
            BufferedImage image) throws IOException
    {
        byte[] bytes = new byte[
                Math.multiplyExact(
                        Math.multiplyExact(image.getWidth(), image.getHeight()),
                        4)];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                int argb = image.getRGB(x, y);
                bytes[offset++] = (byte) (argb >> 16);
                bytes[offset++] = (byte) (argb >> 8);
                bytes[offset++] = (byte) argb;
                bytes[offset++] = (byte) (argb >> 24);
            }
        }
        Files.write(outputRoot.resolve(id + ".rgba"), bytes);
        MANIFEST.add(String.join(
                "\t",
                "image",
                id,
                Integer.toString(image.getWidth()),
                Integer.toString(image.getHeight()),
                Integer.toString(image.getWidth() * image.getHeight())));
    }

    private static String format(float value)
    {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
