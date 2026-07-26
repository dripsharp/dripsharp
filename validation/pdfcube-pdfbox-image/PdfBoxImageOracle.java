import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public final class PdfBoxImageOracle
{
    private static final String[] FIXTURES = {
        "JPXTestCMYK.pdf",
        "JPXTestGrey.pdf",
        "JPXTestRGB.pdf",
        "JBIG2Image.pdf"
    };

    private static final List<String> OBSERVATIONS = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            throw new IllegalArgumentException(
                    "Expected output trace and image resource directory.");
        }
        Path output = Paths.get(args[0]);
        Path resources = Paths.get(args[1]);
        registerProvider(
                "com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi");
        registerProvider("org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi");
        for (String fixture : FIXTURES)
        {
            observeFixture(resources.resolve(fixture), fixture);
        }
        observeMalformedJpxFailure();
        Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void registerProvider(String className) throws Exception
    {
        Object provider = Class.forName(className).getConstructor().newInstance();
        IIORegistry.getDefaultInstance().registerServiceProvider(provider);
    }

    private static void observeFixture(Path path, String fixture) throws Exception
    {
        if (fixture.startsWith("JPX") &&
                !ImageIO.getImageReadersByFormatName("JPEG2000").hasNext())
        {
            throw new IllegalStateException(
                    "Pinned Java JPEG2000 ImageIO provider is unavailable.");
        }
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            PDResources resources = document.getPage(0).getResources();
            List<COSName> names = new ArrayList<>();
            resources.getXObjectNames().forEach(names::add);
            names.sort(Comparator.comparing(COSName::getName));
            int imageIndex = 0;
            for (COSName name : names)
            {
                PDXObject candidate = resources.getXObject(name);
                if (!(candidate instanceof PDImageXObject))
                {
                    continue;
                }
                PDImageXObject image = (PDImageXObject) candidate;
                String id = fixture + ":" + imageIndex++;
                observe(
                        "codec-metadata",
                        id,
                        image.getWidth(),
                        image.getHeight(),
                        image.getBitsPerComponent(),
                        image.getColorSpace().getName(),
                        image.getColorSpace().getNumberOfComponents(),
                        image.getSuffix());

                WritableRaster raw = image.getRawRaster();
                observe(
                        "full-pixels",
                        id,
                        raw.getWidth(),
                        raw.getHeight(),
                        raw.getNumBands(),
                        sampleSummary(raw));

                Rectangle region = region(image.getWidth(), image.getHeight());
                BufferedImage sampled = image.getImage(region, 2);
                observe(
                        "region-subsampling",
                        id,
                        region.x,
                        region.y,
                        region.width,
                        region.height,
                        2,
                        sampled.getWidth(),
                        sampled.getHeight());
            }
            if (imageIndex == 0)
            {
                throw new IllegalStateException("Fixture contains no page image: " + fixture);
            }
        }
    }

    private static Rectangle region(int width, int height)
    {
        int x = width / 4;
        int y = height / 4;
        return new Rectangle(
                x,
                y,
                Math.max(1, width / 2),
                Math.max(1, height / 2));
    }

    private static String sampleSummary(WritableRaster raster)
    {
        int bands = raster.getNumBands();
        int[] minimum = new int[bands];
        int[] maximum = new int[bands];
        long[] sum = new long[bands];
        java.util.Arrays.fill(minimum, Integer.MAX_VALUE);
        java.util.Arrays.fill(maximum, Integer.MIN_VALUE);
        int[] samples = raster.getPixels(
                0, 0, raster.getWidth(), raster.getHeight(), (int[]) null);
        for (int index = 0; index < samples.length; index++)
        {
            int band = index % bands;
            int sample = samples[index];
            minimum[band] = Math.min(minimum[band], sample);
            maximum[band] = Math.max(maximum[band], sample);
            sum[band] += sample;
        }
        int pixels = raster.getWidth() * raster.getHeight();
        List<String> statistics = new ArrayList<>();
        for (int band = 0; band < bands; band++)
        {
            statistics.add(String.format(
                    Locale.ROOT,
                    "%d,%d,%.3f",
                    minimum[band],
                    maximum[band],
                    sum[band] / (double) pixels));
        }
        return String.join(";", statistics);
    }

    private static void observeMalformedJpxFailure() throws Exception
    {
        boolean failed = false;
        try (PDDocument document = new PDDocument())
        {
            PDImageXObject image = new PDImageXObject(
                    document,
                    new ByteArrayInputStream(new byte[] { 0, 1, 2, 3 }),
                    COSName.JPX_DECODE,
                    2,
                    2,
                    8,
                    PDDeviceRGB.INSTANCE);
            image.getImage();
        }
        catch (Exception expected)
        {
            failed = true;
        }
        observe("failure", "malformed-jpx", failed);
    }

    private static void observe(String family, String id, Object... values)
    {
        List<String> normalized = new ArrayList<>();
        for (Object value : values)
        {
            normalized.add(String.valueOf(value));
        }
        OBSERVATIONS.add(
                family + "\t" + id + "\t" + String.join("|", normalized));
    }

}
