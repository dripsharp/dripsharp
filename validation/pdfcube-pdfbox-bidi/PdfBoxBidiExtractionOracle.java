import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfBoxBidiExtractionOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private PdfBoxBidiExtractionOracle()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            throw new IllegalArgumentException(
                    "Expected output trace and PDFBox checkout.");
        }
        Path output = Paths.get(args[0]);
        Path resources = Paths.get(args[1])
                .resolve("pdfbox/src/test/resources");

        observeFixture(
                resources.resolve("org/apache/pdfbox/text/BidiSample.pdf"),
                "bidi-sample");
        observeFixture(
                resources.resolve("input/PDFBOX-4531-bidi-ligature-1.pdf"),
                "bidi-ligature-1");
        observeFixture(
                resources.resolve("input/PDFBOX-4531-bidi-ligature-2.pdf"),
                "bidi-ligature-2");

        Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void observeFixture(Path pdf, String id) throws Exception
    {
        for (boolean sorted : new boolean[] { false, true })
        {
            String suffix = sorted ? "-sorted.txt" : ".txt";
            String expected = normalize(new String(
                    Files.readAllBytes(Paths.get(pdf + suffix)),
                    StandardCharsets.UTF_8));
            String actual;
            try (PDDocument document = Loader.loadPDF(pdf.toFile()))
            {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(sorted);
                stripper.setLineSeparator("\n");
                actual = normalize(stripper.getText(document));
            }
            observe(
                    "extraction",
                    id + (sorted ? "-sorted" : "-logical"),
                    actual.equals(expected),
                    actual);
        }
    }

    private static String normalize(String value)
    {
        String[] lines = value.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n");
        return Arrays.stream(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static void observe(
            String family, String id, boolean expected, String value)
    {
        OBSERVATIONS.add(
                family + "\t" + id + "\t"
                        + expected + "|" + escape(value));
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
