import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.text.TextPositionComparator;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

public final class PdfBoxFontTextOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();

    private static Path pdfboxRoot;
    private static Path testResources;

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "Expected output trace, PDFBox checkout, and exchange directory.");
        }
        Path output = Paths.get(args[0]);
        pdfboxRoot = Paths.get(args[1]);
        testResources = pdfboxRoot.resolve("pdfbox/src/test/resources");
        Path exchange = Paths.get(args[2]);
        Files.createDirectories(exchange);

        Path ownPdf = exchange.resolve("java-font-text.pdf");
        writeRepresentative(ownPdf);

        observeDirectModels();
        observeExtraction(ownPdf, "synthetic");
        observeFixture(
                testResources.resolve("input/sample_fonts_solidconvertor.pdf"),
                "sample-fonts");
        observeFixture(
                testResources.resolve(
                        "input/PDFBOX-3127-RAU4G6QMOVRYBISJU7R6MOVZCRFUO7P4-VFont.pdf"),
                "vertical-font");
        observeFixture(
                testResources.resolve("org/apache/pdfbox/pdmodel/font/F001u_3_7j.pdf"),
                "f001");
        observeFixture(
                testResources.resolve("input/FC60_Times.pdf"),
                "times");
        observeFixture(
                testResources.resolve("input/PDFBOX-3044-010197-p5-ligatures.pdf"),
                "type1c");
        observeFixture(
                testResources.resolve("input/PDFBOX-3053-reduced.pdf"),
                "type3");
        observeFixture(
                testResources.resolve("input/PDFBOX-3062-005717-p1.pdf"),
                "cid-type0");
        observeFixture(
                testResources.resolve("input/PDFBOX-4322-Empty-ToUnicode-reduced.pdf"),
                "empty-to-unicode");
        observeArticles(
                testResources.resolve("input/PDFBOX-3110-poems-beads.pdf"));
        observeCrossRuntime(exchange.resolve("dotnet-font-text.pdf"));

        Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void writeRepresentative(Path path) throws Exception
    {
        Path ttf = pdfboxRoot.resolve(
                "pdfbox/src/main/resources/org/apache/pdfbox/resources/ttf/"
                        + "LiberationSans-Regular.ttf");
        Path pfb = pdfboxRoot.resolve(
                "fontbox/target/fonts/DejaVuSerifCondensed.pfb");
        if (!Files.isRegularFile(ttf) || !Files.isRegularFile(pfb))
        {
            throw new IOException("Representative upstream font fixture is missing");
        }

        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(new PDRectangle(420, 520));
            document.addPage(page);

            PDType1Font standard =
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType0Font type0 = PDType0Font.load(document, ttf.toFile());
            PDTrueTypeFont trueType = PDTrueTypeFont.load(
                    document, ttf.toFile(), WinAnsiEncoding.INSTANCE);
            PDType1Font embeddedType1;
            try (InputStream input = Files.newInputStream(pfb))
            {
                embeddedType1 =
                        new PDType1Font(document, input, WinAnsiEncoding.INSTANCE);
            }

            try (PDPageContentStream content =
                    new PDPageContentStream(document, page))
            {
                show(content, standard, 12, 40, 60, "bottom");
                show(content, type0, 14, 40, 340, "Type0 café");
                show(content, trueType, 13, 40, 290, "TrueType");
                show(content, embeddedType1, 12, 40, 240, "Type1");
                show(content, standard, 12, 40, 440, "top");

                content.beginText();
                content.setFont(standard, 12);
                content.setTextMatrix(Matrix.getTranslateInstance(40, 190));
                content.showTextWithPositioning(
                        new Object[] { "word", -2200f, "gap" });
                content.endText();

                show(content, standard, 12, 40, 140, "duplicate");
                show(content, standard, 12, 40, 140, "duplicate");
            }
            document.save(path.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }

    private static void show(
            PDPageContentStream content,
            PDFont font,
            float size,
            float x,
            float y,
            String text)
            throws Exception
    {
        content.beginText();
        content.setFont(font, size);
        content.setTextMatrix(Matrix.getTranslateInstance(x, y));
        content.showText(text);
        content.endText();
    }

    private static void observeDirectModels() throws Exception
    {
        Path ttf = pdfboxRoot.resolve(
                "pdfbox/src/main/resources/org/apache/pdfbox/resources/ttf/"
                        + "LiberationSans-Regular.ttf");
        Path pfb = pdfboxRoot.resolve(
                "fontbox/target/fonts/DejaVuSerifCondensed.pfb");
        try (PDDocument document = new PDDocument())
        {
            PDFont standard =
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType0Font type0;
            try (InputStream input = Files.newInputStream(ttf))
            {
                type0 = PDType0Font.load(document, input, false);
            }
            PDTrueTypeFont trueType = PDTrueTypeFont.load(
                    document, ttf.toFile(), WinAnsiEncoding.INSTANCE);
            PDType1Font embeddedType1;
            try (InputStream input = Files.newInputStream(pfb))
            {
                embeddedType1 =
                        new PDType1Font(document, input, WinAnsiEncoding.INSTANCE);
            }

            observe(
                    "type-1",
                    "standard",
                    standard.getClass().getSimpleName(),
                    standard.getSubType(),
                    standard.getName());
            observe(
                    "standard-font",
                    "helvetica",
                    standard.isStandard14(),
                    standard.isEmbedded(),
                    standard.getFontDescriptor() != null);
            observe(
                    "encoding",
                    "win-ansi-and-standard",
                    ((PDSimpleFont) standard).getEncoding().getEncodingName(),
                    trueType.getEncoding().getEncodingName(),
                    embeddedType1.getEncoding().getEncodingName());

            observe(
                    "type-0",
                    "embedded-truetype",
                    type0.getClass().getSimpleName(),
                    type0.getSubType(),
                    type0.isEmbedded(),
                    type0.isDamaged());
            PDCIDFont descendant = type0.getDescendantFont();
            observe(
                    "cid",
                    "type0-descendant",
                    descendant.getClass().getSimpleName(),
                    descendant.getBaseFont(),
                    descendant.isEmbedded(),
                    descendant.isDamaged());
            observe(
                    "true-type",
                    "simple-embedded",
                    trueType.getClass().getSimpleName(),
                    trueType.isEmbedded(),
                    trueType.isDamaged(),
                    trueType.getTrueTypeFont().getName());
            observe(
                    "embedded",
                    "three-paths",
                    type0.isEmbedded(),
                    trueType.isEmbedded(),
                    embeddedType1.isEmbedded());

            int type0Code = readCode(type0, "A");
            int trueTypeCode = readCode(trueType, "A");
            int standardCode = readCode(standard, "A");
            observe(
                    "to-unicode",
                    "font-cmaps",
                    type0Code,
                    type0.toUnicode(type0Code),
                    trueTypeCode,
                    trueType.toUnicode(trueTypeCode),
                    standardCode,
                    standard.toUnicode(standardCode));
            observe(
                    "glyph-mapping",
                    "codes-to-glyphs",
                    type0.getDescendantFont().codeToCID(type0Code),
                    type0.getDescendantFont().codeToGID(type0Code),
                    trueType.codeToGID(trueTypeCode),
                    trueType.hasGlyph(trueTypeCode));
            observe(
                    "width-advance",
                    "font-widths",
                    number(standard.getWidth(standardCode)),
                    number(standard.getStringWidth("ABC")),
                    number(type0.getWidth(type0Code)),
                    number(type0.getStringWidth("ABC")),
                    number(trueType.getWidth(trueTypeCode)));
            Vector displacement = type0.getDisplacement(type0Code);
            observe(
                    "displacement",
                    "horizontal",
                    number(displacement.getX()),
                    number(displacement.getY()),
                    type0.isVertical());
            observe(
                    "missing-glyph",
                    "unassigned-code-point",
                    fails(() -> type0.encode("\u0378")),
                    fails(() -> trueType.encode("\u0378")),
                    fails(() -> standard.encode("\u0378")));

            PDFont damaged = damagedTrueType(document);
            PDSimpleFont damagedSimple = (PDSimpleFont) damaged;
            observe(
                    "damaged-font",
                    "invalid-embedded-truetype",
                    damaged.isDamaged(),
                    damaged.isEmbedded(),
                    damagedSimple.getFontBoxFont() != null);
            observe(
                    "substituted",
                    "damaged-font-substitution",
                    !damaged.isEmbedded(),
                    damagedSimple.getFontBoxFont() != null,
                    damaged.getName());
            observe(
                    "fallback",
                    "damaged-font-readable",
                    damagedSimple.getFontBoxFont() != null,
                    damaged.getWidth(65) > 0,
                    damaged.getBoundingBox() != null);

            observeTextPositions(standard);
        }
    }

    private static PDFont damagedTrueType(PDDocument document) throws Exception
    {
        COSDictionary dictionary = new COSDictionary();
        dictionary.setItem(COSName.TYPE, COSName.FONT);
        dictionary.setItem(COSName.SUBTYPE, COSName.TRUE_TYPE);
        dictionary.setName(COSName.BASE_FONT, "LiberationSans");
        COSDictionary descriptor = new COSDictionary();
        descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
        descriptor.setName(COSName.FONT_NAME, "LiberationSans");
        PDStream invalid = new PDStream(
                document,
                new ByteArrayInputStream(new byte[] { 0, 1, 2, 3, 4 }));
        descriptor.setItem(COSName.FONT_FILE2, invalid);
        dictionary.setItem(COSName.FONT_DESC, descriptor);
        return new PDTrueTypeFont(dictionary);
    }

    private static void observeTextPositions(PDFont font)
    {
        TextPosition first = new TextPosition(
                0,
                420,
                520,
                new Matrix(2, 0, 0, 3, 40, 440),
                52,
                440,
                9,
                12,
                4,
                "A",
                new int[] { 65 },
                font,
                12,
                12);
        TextPosition second = new TextPosition(
                0,
                420,
                520,
                new Matrix(2, 0, 0, 3, 60, 440),
                72,
                440,
                9,
                12,
                4,
                "B",
                new int[] { 66 },
                font,
                12,
                12);
        TextPosition diacritic = new TextPosition(
                0,
                420,
                520,
                new Matrix(1, 0, 0, 1, 44, 440),
                48,
                440,
                4,
                4,
                2,
                "`",
                new int[] { 96 },
                font,
                12,
                12);
        observe(
                "text-position",
                "public-construction",
                first.getUnicode(),
                first.getCharacterCodes()[0],
                number(first.getX()),
                number(first.getY()),
                number(first.getXDirAdj()),
                number(first.getYDirAdj()),
                number(first.getWidthDirAdj()),
                number(first.getHeightDir()),
                number(first.getXScale()),
                number(first.getYScale()),
                first.contains(diacritic),
                first.completelyContains(diacritic));
        observe(
                "text-matrix",
                "position-matrix",
                matrix(first.getTextMatrix()),
                number(first.getDir()),
                number(first.getFontSize()),
                number(first.getFontSizeInPt()));
        observe(
                "text-position-comparator",
                "same-line",
                new TextPositionComparator().compare(first, second),
                new TextPositionComparator().compare(second, first));
        observe(
                "positioned-text",
                "diacritic",
                diacritic.isDiacritic(),
                first.isDiacritic(),
                merge(first, diacritic));
    }

    private static String merge(TextPosition base, TextPosition diacritic)
    {
        base.mergeDiacritic(diacritic);
        return base.getUnicode();
    }

    private static void observeExtraction(Path pdf, String id) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdf.toFile()))
        {
            String unsorted = extract(document, false, true, true);
            String sorted = extract(document, true, true, true);
            String duplicates = extract(document, true, false, true);
            observe("extraction-api", id + "-get-text", unsorted);
            observe("sorting", id, unsorted, sorted, !unsorted.equals(sorted));
            observe(
                    "duplicate-suppression",
                    id,
                    count(duplicates, "duplicate"),
                    count(sorted, "duplicate"),
                    duplicates.length(),
                    sorted.length());
            observe(
                    "word-separation",
                    id,
                    sorted.contains("word<W>gap"),
                    sorted);
            observe(
                    "line-separation",
                    id,
                    count(sorted, "<L>"),
                    sorted.contains("top<L>"),
                    sorted.contains("bottom"));

            PositionCollector collector = new PositionCollector();
            collector.setSortByPosition(true);
            collector.getText(document);
            observe(
                    "positioned-text",
                    id + "-positions",
                    collector.positions.size(),
                    collector.summary());

            PDFTextStripperByArea byArea = new PDFTextStripperByArea();
            byArea.setLineSeparator("<L>");
            byArea.addRegion("top", new Rectangle2D.Double(0, 0, 420, 260));
            byArea.extractRegions(document.getPage(0));
            observe(
                    "extraction-api",
                    id + "-by-area",
                    byArea.getRegions().size(),
                    byArea.getTextForRegion("top"));
        }
    }

    private static String extract(
            PDDocument document,
            boolean sort,
            boolean suppressDuplicates,
            boolean separateByBeads)
            throws Exception
    {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(sort);
        stripper.setSuppressDuplicateOverlappingText(suppressDuplicates);
        stripper.setShouldSeparateByBeads(separateByBeads);
        stripper.setLineSeparator("<L>");
        stripper.setWordSeparator("<W>");
        stripper.setPageStart("<P>");
        stripper.setPageEnd("</P>");
        stripper.setParagraphStart("<G>");
        stripper.setParagraphEnd("</G>");
        stripper.setArticleStart("<A>");
        stripper.setArticleEnd("</A>");
        return stripper.getText(document);
    }

    private static void observeFixture(Path pdf, String id) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdf.toFile()))
        {
            List<String> inventory = new ArrayList<>();
            boolean vertical = false;
            int pageIndex = 0;
            for (PDPage page : document.getPages())
            {
                PDResources resources = page.getResources();
                if (resources == null)
                {
                    pageIndex++;
                    continue;
                }
                List<COSName> names = new ArrayList<>();
                resources.getFontNames().forEach(names::add);
                names.sort(Comparator.comparing(COSName::getName));
                for (COSName resourceName : names)
                {
                    PDFont font = resources.getFont(resourceName);
                    String fontId = id + "-p" + pageIndex + "-" + resourceName.getName();
                    String value = font.getClass().getSimpleName()
                            + "|" + font.getName()
                            + "|" + font.getSubType()
                            + "|" + font.isEmbedded()
                            + "|" + font.isDamaged()
                            + "|" + font.isStandard14()
                            + "|" + font.isVertical();
                    inventory.add(fontId + "=" + value);
                    observe("font-dictionary", fontId, value);
                    observeFontFamily(font, fontId, value);
                    vertical |= font.isVertical();
                }
                pageIndex++;
            }
            String text = extract(document, true, true, true);
            observe(
                    "representative-pdf",
                    id,
                    document.getNumberOfPages(),
                    inventory.size(),
                    sha256(text),
                    text.length());
            if (id.equals("vertical-font"))
            {
                observe(
                        "vertical-writing",
                        id,
                        vertical,
                        sha256(text),
                        text.length(),
                        count(text, "\n") + 1);
            }
        }
    }

    private static void observeFontFamily(PDFont font, String id, String value)
    {
        if (font instanceof PDType0Font)
        {
            PDType0Font type0 = (PDType0Font) font;
            observe("type-0", id, value);
            observe(
                    "cid",
                    id,
                    type0.getDescendantFont().getClass().getSimpleName(),
                    type0.getDescendantFont().isEmbedded(),
                    type0.getDescendantFont().isDamaged());
            if (type0.getDescendantFont().getClass().getSimpleName().contains("Type0"))
            {
                observe("cff", id, value);
            }
        }
        else if (font instanceof PDType1CFont)
        {
            observe("cff", id, value);
            observe("type-1", id, value);
        }
        else if (font instanceof PDType1Font)
        {
            observe("type-1", id, value);
        }
        else if (font instanceof PDTrueTypeFont)
        {
            observe("true-type", id, value);
        }
        else if (font instanceof PDType3Font)
        {
            observe("type-3", id, value);
        }
    }

    private static void observeArticles(Path pdf) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdf.toFile()))
        {
            String separated = extract(document, true, true, true);
            String together = extract(document, true, true, false);
            observe(
                    "article-handling",
                    "thread-beads",
                    sha256(separated),
                    separated.length(),
                    count(separated, "<A>"),
                    sha256(together),
                    together.length(),
                    count(together, "<A>"),
                    !separated.equals(together));
        }
    }

    private static void observeCrossRuntime(Path pdf) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(pdf.toFile()))
        {
            String text = extract(document, true, true, true);
            List<String> classes = new ArrayList<>();
            for (COSName name : document.getPage(0).getResources().getFontNames())
            {
                classes.add(
                        document.getPage(0).getResources().getFont(name)
                                .getClass().getSimpleName());
            }
            classes.sort(String::compareTo);
            observe(
                    "cross-reopen",
                    "other-runtime-pdf",
                    sha256(text),
                    text.length(),
                    String.join(",", classes));
        }
    }

    private static int readCode(PDFont font, String text) throws Exception
    {
        byte[] encoded = font.encode(text);
        return font.readCode(new ByteArrayInputStream(encoded));
    }

    private static boolean fails(CheckedAction action)
    {
        try
        {
            action.run();
            return false;
        }
        catch (Exception expected)
        {
            return true;
        }
    }

    private static int count(String value, String needle)
    {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0)
        {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private static String sha256(String value) throws Exception
    {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest)
        {
            result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private static String matrix(Matrix matrix)
    {
        return number(matrix.getScaleX())
                + "," + number(matrix.getShearY())
                + "," + number(matrix.getShearX())
                + "," + number(matrix.getScaleY())
                + "," + number(matrix.getTranslateX())
                + "," + number(matrix.getTranslateY());
    }

    private static String number(double value)
    {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static void observe(String family, String id, Object... values)
    {
        String value = java.util.Arrays.stream(values)
                .map(PdfBoxFontTextOracle::text)
                .collect(Collectors.joining("|"));
        OBSERVATIONS.add(family + "\t" + id + "\t" + value);
    }

    private static String text(Object value)
    {
        return String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @FunctionalInterface
    private interface CheckedAction
    {
        void run() throws Exception;
    }

    private static final class PositionCollector extends PDFTextStripper
    {
        private final List<TextPosition> positions = new ArrayList<>();

        @Override
        protected void processTextPosition(TextPosition text)
        {
            positions.add(text);
            super.processTextPosition(text);
        }

        private String summary()
        {
            return positions.stream()
                    .limit(12)
                    .map(position -> position.getUnicode()
                            + "@" + number(position.getXDirAdj())
                            + "," + number(position.getYDirAdj())
                            + "," + number(position.getWidthDirAdj()))
                    .collect(Collectors.joining(";"));
        }
    }
}
