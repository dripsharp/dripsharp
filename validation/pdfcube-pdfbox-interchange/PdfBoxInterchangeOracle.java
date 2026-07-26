import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDStructureElementNameTreeNode;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDParentTreeValue;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.Revisions;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDLayoutAttributeObject;
import org.apache.pdfbox.pdmodel.fdf.FDFAnnotation;
import org.apache.pdfbox.pdmodel.fdf.FDFAnnotationFreeText;
import org.apache.pdfbox.pdmodel.fdf.FDFAnnotationText;
import org.apache.pdfbox.pdmodel.fdf.FDFDictionary;
import org.apache.pdfbox.pdmodel.fdf.FDFDocument;
import org.apache.pdfbox.pdmodel.fdf.FDFField;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentMembershipDictionary;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public final class PdfBoxInterchangeOracle
{
    private static final String TAGGED_NAME = "upstream-tagged.pdf";
    private static final String LAYERED_NAME = "upstream-layered.pdf";
    private static final String FDF_NAME = "upstream-programmatic.fdf";
    private static final String XFDF_NAME = "upstream-programmatic.xfdf";
    private static final String FOREIGN_TAGGED_NAME = "package-tagged.pdf";
    private static final String FOREIGN_LAYERED_NAME = "package-layered.pdf";
    private static final String FOREIGN_FDF_NAME = "package-programmatic.fdf";
    private static final String FOREIGN_XFDF_NAME = "package-programmatic.xfdf";

    private static PrintWriter trace;

    private PdfBoxInterchangeOracle()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "usage: PdfBoxInterchangeOracle <trace.tsv> <exchange-dir> <fixtures-dir>");
        }

        Path tracePath = Paths.get(args[0]).toAbsolutePath();
        Path exchange = Paths.get(args[1]).toAbsolutePath();
        Path fixtures = Paths.get(args[2]).toAbsolutePath();
        Files.createDirectories(exchange);
        writeArtifacts(exchange);

        try (PrintWriter output = new PrintWriter(
                Files.newBufferedWriter(tracePath, StandardCharsets.UTF_8)))
        {
            trace = output;
            observeOptionalContentModel(exchange);
            observeFdfModel();
            observeFixtures(fixtures);
            observeTaggedRoundTrip(exchange.resolve(FOREIGN_TAGGED_NAME));
            observeLayeredRoundTrip(exchange.resolve(FOREIGN_LAYERED_NAME));
            observeFdfRoundTrip(exchange.resolve(FOREIGN_FDF_NAME));
            observeXfdfRoundTrip(exchange.resolve(FOREIGN_XFDF_NAME));
            observeFailures();
        }
    }

    private static void observe(String family, String id, Object value)
    {
        String rendered;
        if (value == null)
        {
            rendered = "null";
        }
        else if (value instanceof Boolean)
        {
            rendered = Boolean.TRUE.equals(value) ? "true" : "false";
        }
        else
        {
            rendered = String.valueOf(value);
        }
        rendered = rendered.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        trace.printf("%s\t%s\t%s%n", family, id, rendered);
    }

    private static void writeArtifacts(Path exchange) throws Exception
    {
        writeTaggedPdf(exchange.resolve(TAGGED_NAME));
        writeLayeredPdf(exchange.resolve(LAYERED_NAME));
        writeProgrammaticFdf(
                exchange.resolve(FDF_NAME), exchange.resolve(XFDF_NAME));
    }

    private static void writeTaggedPdf(Path path) throws Exception
    {
        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(new PDRectangle(200, 100));
            page.setStructParents(7);
            document.addPage(page);

            PDMarkInfo markInfo = new PDMarkInfo();
            markInfo.setMarked(true);
            markInfo.setUserProperties(true);
            document.getDocumentCatalog().setMarkInfo(markInfo);

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            root.setParentTreeNextKey(8);
            Map<String, String> roleMap = new LinkedHashMap<>();
            roleMap.put("CustomSect", "Sect");
            root.setRoleMap(roleMap);
            document.getDocumentCatalog().setStructureTreeRoot(root);

            PDStructureElement section = new PDStructureElement("CustomSect", root);
            section.setElementIdentifier("section-1");
            section.setTitle("Tagged title");
            section.setLanguage("en-US");
            section.setAlternateDescription("Tagged alternate");
            section.setExpandedForm("Expanded section");
            section.setActualText("Logical section");
            section.setRevisionNumber(2);
            section.setPage(page);
            root.appendKid(section);

            PDLayoutAttributeObject layout = new PDLayoutAttributeObject();
            layout.setPlacement(PDLayoutAttributeObject.PLACEMENT_BLOCK);
            layout.setAllPaddings(4);
            section.addAttribute(layout);
            section.incrementRevisionNumber();
            layout.setSpaceBefore(3);
            section.addClassName("BodyClass");

            PDLayoutAttributeObject classLayout = new PDLayoutAttributeObject();
            classLayout.setPlacement(PDLayoutAttributeObject.PLACEMENT_INLINE);
            Map<String, Object> classMap = new LinkedHashMap<>();
            classMap.put("BodyClass", classLayout);
            root.setClassMap(classMap);

            PDStructureElement paragraph = new PDStructureElement("P", section);
            paragraph.setActualText("Actual tagged text");
            paragraph.setPage(page);
            paragraph.appendKid(0);
            section.appendKid(paragraph);

            PDMarkedContentReference markedReference = new PDMarkedContentReference();
            markedReference.setPage(page);
            markedReference.setMCID(1);
            section.appendKid(markedReference);

            PDStructureElementNameTreeNode idTree =
                    new PDStructureElementNameTreeNode();
            Map<String, PDStructureElement> names = new LinkedHashMap<>();
            names.put("section-1", section);
            idTree.setNames(names);
            root.setIDTree(idTree);

            COSArray parents = new COSArray();
            parents.add(paragraph);
            parents.add(section);
            PDNumberTreeNode parentTree = new PDNumberTreeNode(PDParentTreeValue.class);
            Map<Integer, COSObjectable> numbers = new LinkedHashMap<>();
            numbers.put(7, new PDParentTreeValue(parents));
            parentTree.setNumbers(numbers);
            root.setParentTree(parentTree);

            PDType1Font font =
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content =
                    new PDPageContentStream(document, page))
            {
                COSDictionary firstProperties = new COSDictionary();
                firstProperties.setInt(COSName.MCID, 0);
                firstProperties.setString(
                        COSName.ACTUAL_TEXT, "Actual tagged text");
                firstProperties.setString(COSName.LANG, "en-US");
                content.beginMarkedContent(
                        COSName.P, PDPropertyList.create(firstProperties));
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(10, 70);
                content.showText("Visible tagged text");
                content.endText();
                content.endMarkedContent();

                COSDictionary secondProperties = new COSDictionary();
                secondProperties.setInt(COSName.MCID, 1);
                secondProperties.setString(COSName.ALT, "Alternate span");
                content.beginMarkedContent(
                        COSName.getPDFName("Span"),
                        PDPropertyList.create(secondProperties));
                content.beginText();
                content.setFont(font, 10);
                content.newLineAtOffset(10, 45);
                content.showText("Second span");
                content.endText();
                content.endMarkedContent();
            }

            document.save(path.toFile());
        }
    }

    private static void writeLayeredPdf(Path path) throws Exception
    {
        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);

            PDOptionalContentProperties properties =
                    new PDOptionalContentProperties();
            PDOptionalContentGroup enabled =
                    new PDOptionalContentGroup("enabled");
            PDOptionalContentGroup disabled =
                    new PDOptionalContentGroup("disabled");
            properties.addGroup(enabled);
            properties.addGroup(disabled);
            properties.setGroupEnabled(disabled, false);
            document.getDocumentCatalog().setOCProperties(properties);

            PDOptionalContentMembershipDictionary anyOn =
                    membership(enabled, disabled, COSName.ANY_ON);
            PDOptionalContentMembershipDictionary allOn =
                    membership(enabled, disabled, COSName.ALL_ON);
            PDOptionalContentMembershipDictionary anyOff =
                    membership(enabled, disabled, COSName.ANY_OFF);
            PDOptionalContentMembershipDictionary allOff =
                    membership(enabled, disabled, COSName.ALL_OFF);

            try (PDPageContentStream content =
                    new PDPageContentStream(document, page))
            {
                drawLayerRect(content, anyOn, 0, 50, 50, 50, 0, 1, 0);
                drawLayerRect(content, allOn, 50, 50, 50, 50, 1, 0, 0);
                drawLayerRect(content, anyOff, 0, 0, 50, 50, 0, 0, 1);
                drawLayerRect(content, allOff, 50, 0, 50, 50, 1, 0, 1);
            }

            document.save(path.toFile());
        }
    }

    private static PDOptionalContentMembershipDictionary membership(
            PDOptionalContentGroup first,
            PDOptionalContentGroup second,
            COSName policy)
    {
        PDOptionalContentMembershipDictionary membership =
                new PDOptionalContentMembershipDictionary();
        membership.setOCGs(Arrays.asList(first, second));
        membership.setVisibilityPolicy(policy);
        return membership;
    }

    private static void drawLayerRect(
            PDPageContentStream content,
            PDPropertyList membership,
            float x,
            float y,
            float width,
            float height,
            float red,
            float green,
            float blue) throws IOException
    {
        content.beginMarkedContent(COSName.OC, membership);
        content.setNonStrokingColor(red, green, blue);
        content.addRect(x, y, width, height);
        content.fill();
        content.endMarkedContent();
    }

    private static void writeProgrammaticFdf(Path fdfPath, Path xfdfPath)
            throws Exception
    {
        try (FDFDocument document = newProgrammaticFdf())
        {
            document.save(fdfPath.toFile());
            document.saveXFDF(xfdfPath.toFile());
        }
    }

    private static FDFDocument newProgrammaticFdf() throws Exception
    {
        FDFDocument document = new FDFDocument();
        FDFDictionary dictionary = document.getCatalog().getFDF();
        PDSimpleFileSpecification file = new PDSimpleFileSpecification();
        file.setFile("forms/source-target.pdf");
        dictionary.setFile(file);
        dictionary.setEncoding("Shift-JIS");

        COSArray ids = new COSArray();
        ids.add(new COSString("original-id"));
        ids.add(new COSString("modified-id"));
        dictionary.setID(ids);

        FDFField alpha = new FDFField();
        alpha.setPartialFieldName("alpha");
        alpha.setValue("A&B<1>");
        alpha.setFieldFlags(3);

        FDFField choice = new FDFField();
        choice.setPartialFieldName("choice");
        choice.setValue(Arrays.asList("first", "second"));

        FDFField parent = new FDFField();
        parent.setPartialFieldName("parent");
        FDFField child = new FDFField();
        child.setPartialFieldName("child");
        child.setValue("nested");
        parent.setKids(Arrays.asList(child));

        dictionary.setFields(Arrays.asList(alpha, choice, parent));
        return document;
    }

    private static void observeTaggedRoundTrip(Path path) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            PDMarkInfo markInfo = document.getDocumentCatalog().getMarkInfo();
            PDStructureTreeRoot root =
                    document.getDocumentCatalog().getStructureTreeRoot();
            List<Object> rootKids = root.getKids();
            PDStructureElement section = (PDStructureElement) rootKids.get(0);
            List<Object> sectionKids = section.getKids();
            PDStructureElement paragraph =
                    (PDStructureElement) sectionKids.get(0);
            PDMarkedContentReference markedReference =
                    (PDMarkedContentReference) sectionKids.get(1);
            Revisions<PDAttributeObject> attributes = section.getAttributes();
            Revisions<String> classes = section.getClassNames();
            Object role = root.getRoleMap().get("CustomSect");
            Map<String, Object> classMap = root.getClassMap();
            PDStructureElement idValue =
                    root.getIDTree().getValue("section-1");
            Object parentValue = root.getParentTree().getValue(7);

            observe(
                    "structure-roundtrip",
                    "foreign-catalog",
                    markInfo.isMarked() + "|" + markInfo.usesUserProperties()
                            + "|" + rootKids.size() + "|"
                            + root.getParentTreeNextKey());
            observe(
                    "structure-model",
                    "logical-reading-data",
                    section.getStructureType() + "|"
                            + section.getStandardStructureType() + "|"
                            + section.getElementIdentifier() + "|"
                            + section.getTitle() + "|" + section.getLanguage()
                            + "|" + section.getAlternateDescription() + "|"
                            + section.getExpandedForm() + "|"
                            + section.getActualText());
            observe(
                    "structure-model",
                    "kid-order",
                    kidKind(sectionKids.get(0)) + ","
                            + kidKind(sectionKids.get(1)) + "|"
                            + paragraph.getActualText() + "|"
                            + markedReference.getMCID());
            observe(
                    "structure-attributes",
                    "revisions",
                    section.getRevisionNumber() + "|" + attributes.size() + "|"
                            + attributes.getRevisionNumber(0) + "|"
                            + classes.size() + "|" + classes.getObject(0) + "|"
                            + classes.getRevisionNumber(0));
            observe(
                    "structure-attributes",
                    "maps",
                    role + "|" + classMap.size() + "|"
                            + idValue.getElementIdentifier());
            observe(
                    "structure-parent-tree",
                    "number-tree",
                    root.getParentTree().getLowerLimit() + "|"
                            + root.getParentTree().getUpperLimit() + "|"
                            + parentTreeSize(parentValue));

            PDFMarkedContentExtractor extractor =
                    new PDFMarkedContentExtractor();
            extractor.processPage(document.getPage(0));
            List<PDMarkedContent> marked = extractor.getMarkedContents();
            observe(
                    "marked-content",
                    "extracted-order",
                    marked.stream()
                            .map(item -> item.getTag() + ":" + item.getMCID()
                                    + ":"
                                    + (item.getActualText() == null
                                            ? "-" : item.getActualText())
                                    + ":"
                                    + (item.getAlternateDescription() == null
                                            ? "-"
                                            : item.getAlternateDescription()))
                            .collect(Collectors.joining(",")));
            observe(
                    "marked-content",
                    "extracted-text",
                    marked.stream()
                            .map(item -> item.getContents().stream()
                                    .filter(TextPosition.class::isInstance)
                                    .map(TextPosition.class::cast)
                                    .map(TextPosition::getUnicode)
                                    .collect(Collectors.joining()))
                            .collect(Collectors.joining("|")));
        }
    }

    private static String kidKind(Object kid)
    {
        if (kid instanceof PDStructureElement)
        {
            return "StructElem";
        }
        if (kid instanceof PDMarkedContentReference)
        {
            return "MCR";
        }
        if (kid instanceof PDObjectReference)
        {
            return "OBJR";
        }
        if (kid instanceof Integer)
        {
            return "MCID";
        }
        return kid.getClass().getSimpleName();
    }

    private static int parentTreeSize(Object value)
    {
        PDParentTreeValue parent = (PDParentTreeValue) value;
        return ((COSArray) parent.getCOSObject()).size();
    }

    private static void observeOptionalContentModel(Path exchange)
            throws Exception
    {
        PDOptionalContentProperties properties =
                new PDOptionalContentProperties();
        PDOptionalContentGroup enabled = new PDOptionalContentGroup("enabled");
        PDOptionalContentGroup disabled = new PDOptionalContentGroup("disabled");
        properties.addGroup(enabled);
        properties.addGroup(disabled);
        boolean firstDisable = properties.setGroupEnabled(disabled, false);
        boolean secondDisable = properties.setGroupEnabled(disabled, false);

        observe(
                "optional-model",
                "groups",
                String.join(",", properties.getGroupNames()) + "|"
                        + properties.getOptionalContentGroups().size() + "|"
                        + properties.hasGroup("enabled"));
        observe(
                "optional-model",
                "base-and-overrides",
                properties.getBaseState() + "|"
                        + properties.isGroupEnabled(enabled) + "|"
                        + properties.isGroupEnabled(disabled) + "|"
                        + firstDisable + "|" + secondDisable);

        properties.setBaseState(PDOptionalContentProperties.BaseState.OFF);
        properties.setGroupEnabled(enabled, true);
        observe(
                "optional-model",
                "off-base",
                properties.getBaseState() + "|"
                        + properties.isGroupEnabled(enabled) + "|"
                        + properties.isGroupEnabled(disabled));

        COSDictionary usage = new COSDictionary();
        COSDictionary view = new COSDictionary();
        view.setItem(COSName.VIEW_STATE, COSName.OFF);
        usage.setItem(COSName.VIEW, view);
        COSDictionary print = new COSDictionary();
        print.setItem(COSName.PRINT_STATE, COSName.ON);
        usage.setItem(COSName.PRINT, print);
        COSDictionary export = new COSDictionary();
        export.setItem(COSName.EXPORT_STATE, COSName.OFF);
        usage.setItem(COSName.EXPORT, export);
        enabled.getCOSObject().setItem(COSName.USAGE, usage);
        observe(
                "optional-model",
                "usage-applications",
                enabled.getRenderState(RenderDestination.VIEW) + "|"
                        + enabled.getRenderState(RenderDestination.PRINT) + "|"
                        + enabled.getRenderState(RenderDestination.EXPORT));

        PDOptionalContentMembershipDictionary membership =
                membership(enabled, disabled, COSName.ALL_ON);
        observe(
                "optional-membership",
                "policy-and-order",
                membership.getVisibilityPolicy().getName() + "|"
                        + membership.getOCGs().stream()
                                .map(group -> ((PDOptionalContentGroup) group)
                                        .getName())
                                .collect(Collectors.joining(",")));

        try (PDDocument loaded =
                Loader.loadPDF(exchange.resolve(LAYERED_NAME).toFile()))
        {
            observeRenderedPixels(
                    loaded, "local-policy-pixels", "optional-render");
        }
    }

    private static void observeLayeredRoundTrip(Path path) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            PDOptionalContentProperties properties =
                    document.getDocumentCatalog().getOCProperties();
            PDFRenderer renderer = new PDFRenderer(document);
            observe(
                    "optional-roundtrip",
                    "foreign-layer-config",
                    String.join(",", properties.getGroupNames()) + "|"
                            + renderer.isGroupEnabled(
                                    properties.getGroup("enabled"))
                            + "|"
                            + renderer.isGroupEnabled(
                                    properties.getGroup("disabled")));
            observeRenderedPixels(
                    document, "foreign-policy-pixels", "optional-roundtrip");
        }
    }

    private static void observeRenderedPixels(
            PDDocument document, String id, String family) throws Exception
    {
        BufferedImage image = new PDFRenderer(document).renderImage(0, 1);
        int[][] points = {{25, 25}, {75, 25}, {25, 75}, {75, 75}};
        List<String> colors = new ArrayList<>();
        for (int[] point : points)
        {
            colors.add(String.format(
                    Locale.ROOT, "%06X",
                    image.getRGB(point[0], point[1]) & 0xFFFFFF));
        }
        observe(family, id, String.join(",", colors));
    }

    private static void observeFdfModel() throws Exception
    {
        try (FDFDocument document = newProgrammaticFdf())
        {
            FDFDictionary dictionary = document.getCatalog().getFDF();
            List<FDFField> fields = dictionary.getFields();
            @SuppressWarnings("unchecked")
            List<String> choice = (List<String>) fields.get(1).getValue();
            observe(
                    "fdf-model",
                    "fields",
                    fields.size() + "|"
                            + fields.stream().map(FDFField::getPartialFieldName)
                                    .collect(Collectors.joining(","))
                            + "|" + fields.get(0).getValue() + "|"
                            + String.join(",", choice) + "|"
                            + fields.get(2).getKids().get(0).getValue());
            observe(
                    "fdf-model",
                    "dictionary",
                    dictionary.getFile().getFile() + "|"
                            + dictionary.getEncoding() + "|"
                            + dictionary.getID().size() + "|"
                            + fields.get(0).getFieldFlags());

            FDFAnnotationText annotation = new FDFAnnotationText();
            annotation.setPage(2);
            annotation.setContents("Text & <markup>");
            annotation.setTitle("Reviewer");
            annotation.setPrinted(true);
            annotation.setRectangle(new PDRectangle(1, 2, 30, 40));
            annotation.setIcon("Comment");
            observe(
                    "fdf-model",
                    "annotation",
                    annotation.getPage() + "|" + annotation.getContents() + "|"
                            + annotation.getTitle() + "|"
                            + annotation.isPrinted() + "|"
                            + number(annotation.getRectangle().getWidth()) + "x"
                            + number(annotation.getRectangle().getHeight())
                            + "|" + annotation.getIcon());

            String xml = writeXfdfString(document);
            observe(
                    "xfdf-model",
                    "programmatic-order",
                    (xml.indexOf("name=\"alpha\"")
                            < xml.indexOf("name=\"choice\""))
                            + "|"
                            + (xml.indexOf("name=\"choice\"")
                                    < xml.indexOf("name=\"parent\""))
                            + "|" + xml.contains("A&amp;B&lt;1&gt;"));
            observe("xfdf-encoding", "programmatic-sha256", sha256(xml));
        }
    }

    private static String number(float value)
    {
        if (value == Math.rint(value))
        {
            return Integer.toString((int) value);
        }
        return Float.toString(value);
    }

    private static String writeXfdfString(FDFDocument document)
            throws Exception
    {
        StringWriter writer = new StringWriter();
        document.saveXFDF(new PrintWriter(writer));
        return writer.toString();
    }

    private static void observeFixtures(Path fixtures) throws Exception
    {
        for (String name : Arrays.asList("withcatalog.fdf", "nocatalog.fdf"))
        {
            Path path = fixtures.resolve(
                    Paths.get("org", "apache", "pdfbox", "pdfparser", name));
            try (FDFDocument document = Loader.loadFDF(path.toFile()))
            {
                List<FDFField> fields =
                        document.getCatalog().getFDF().getFields();
                observe(
                        "fdf-fixture",
                        name,
                        fields.stream()
                                .map(PdfBoxInterchangeOracle::fieldEntry)
                                .collect(Collectors.joining(",")));
            }
        }

        Path taggedFixture = fixtures.resolve(
                Paths.get("org", "apache", "pdfbox", "pdmodel",
                        "documentinterchange", "logicalstructure",
                        "PDFBOX-2725-878725.pdf"));
        try (PDDocument document = Loader.loadPDF(taggedFixture.toFile()))
        {
            PDStructureTreeRoot root =
                    document.getDocumentCatalog().getStructureTreeRoot();
            List<Object> kids = root.getKids();
            observe(
                    "structure-fixture",
                    "pdfbox-2725",
                    document.getNumberOfPages() + "|" + (root != null) + "|"
                            + kids.size() + "|"
                            + kids.stream()
                                    .filter(PDStructureElement.class::isInstance)
                                    .map(PDStructureElement.class::cast)
                                    .map(PDStructureElement::getStructureType)
                                    .collect(Collectors.joining(",")));
        }

        Path xfdfFixture = fixtures.resolve(
                Paths.get("org", "apache", "pdfbox", "pdmodel", "fdf",
                        "xfdf-test-document-annotations.xml"));
        try (FDFDocument document = Loader.loadXFDF(xfdfFixture.toFile()))
        {
            List<FDFAnnotation> annotations =
                    document.getCatalog().getFDF().getAnnotations();
            FDFAnnotationFreeText freeText = annotations.stream()
                    .filter(FDFAnnotationFreeText.class::isInstance)
                    .map(FDFAnnotationFreeText.class::cast)
                    .filter(annotation ->
                            "P&1 P&2 P&3".equals(annotation.getContents()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Expected rich-content free-text annotation"));
            observe(
                    "xfdf-annotations",
                    "fixture-types",
                    annotations.size() + "|"
                            + annotations.stream()
                                    .map(PdfBoxInterchangeOracle::annotationKind)
                                    .collect(Collectors.joining(",")));
            observe(
                    "xfdf-annotations",
                    "rich-content",
                    freeText.getContents() + "|"
                            + freeText.getRichContents().contains("P&amp;1")
                            + "|"
                            + freeText.getRichContents().contains("P&amp;2")
                            + "|"
                            + freeText.getRichContents().contains("P&amp;3"));
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document xmlDocument =
                factory.newDocumentBuilder().parse(xfdfFixture.toFile());
        Element root = xmlDocument.getDocumentElement();
        observe(
                "xfdf-namespace",
                "fixture-root",
                root.getLocalName() + "|" + root.getNamespaceURI() + "|"
                        + root.getAttributeNS(
                                "http://www.w3.org/XML/1998/namespace",
                                "space"));
    }

    private static String valueString(Object value)
    {
        if (value instanceof List<?>)
        {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }

    private static String fieldEntry(FDFField field)
    {
        try
        {
            return field.getPartialFieldName() + "="
                    + valueString(field.getValue());
        }
        catch (IOException exception)
        {
            throw new UncheckedIOException(exception);
        }
    }

    private static String annotationKind(FDFAnnotation annotation)
    {
        return annotation.getClass().getSimpleName().replace(
                "FDFAnnotation", "");
    }

    private static void observeFdfRoundTrip(Path path) throws Exception
    {
        try (FDFDocument document = Loader.loadFDF(path.toFile()))
        {
            FDFDictionary dictionary = document.getCatalog().getFDF();
            List<FDFField> fields = dictionary.getFields();
            observe(
                    "fdf-roundtrip",
                    "foreign-binary",
                    dictionary.getFile().getFile() + "|"
                            + dictionary.getEncoding() + "|"
                            + dictionary.getID().size() + "|"
                            + fields.stream().map(FDFField::getPartialFieldName)
                                    .collect(Collectors.joining(","))
                            + "|" + fields.get(0).getValue());
        }
    }

    private static void observeXfdfRoundTrip(Path path) throws Exception
    {
        byte[] bytes = Files.readAllBytes(path);
        try (FDFDocument document = Loader.loadXFDF(path.toFile()))
        {
            FDFDictionary dictionary = document.getCatalog().getFDF();
            List<FDFField> fields = dictionary.getFields();
            Element root = loadXml(bytes).getDocumentElement();
            observe(
                    "xfdf-roundtrip",
                    "foreign-xml",
                    dictionary.getFile().getFile() + "|"
                            + fields.stream().map(FDFField::getPartialFieldName)
                                    .collect(Collectors.joining(","))
                            + "|" + fields.get(0).getValue() + "|"
                            + fields.get(2).getKids().get(0).getValue());
            observe(
                    "xfdf-namespace",
                    "foreign-root",
                    root.getLocalName() + "|" + root.getNamespaceURI() + "|"
                            + root.getAttributeNS(
                                    "http://www.w3.org/XML/1998/namespace",
                                    "space"));
            observe(
                    "xfdf-encoding",
                    "foreign-bytes",
                    hasUtf8Declaration(bytes) + "|" + hasUtf8Bom(bytes) + "|"
                            + new String(bytes, StandardCharsets.UTF_8)
                                    .contains("A&amp;B&lt;1&gt;"));
        }
    }

    private static Document loadXml(byte[] bytes) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(bytes));
    }

    private static boolean hasUtf8Declaration(byte[] bytes)
    {
        return new String(bytes, StandardCharsets.UTF_8).startsWith(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    }

    private static boolean hasUtf8Bom(byte[] bytes)
    {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    private static String sha256(String value) throws Exception
    {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte octet : digest)
        {
            result.append(String.format(Locale.ROOT, "%02x", octet & 0xFF));
        }
        return result.toString();
    }

    private static void observeFailures() throws Exception
    {
        FDFField unknown = new FDFField();
        unknown.setValue(COSInteger.get(42));
        observe(
                "fdf-failure",
                "unknown-field-value",
                failureKind(unknown::getValue));
        observe(
                "xfdf-failure",
                "wrong-root",
                failureKind(() ->
                {
                    try (FDFDocument ignored = Loader.loadXFDF(
                            new ByteArrayInputStream(
                                    "<not-xfdf/>".getBytes(
                                            StandardCharsets.UTF_8))))
                    {
                        // Observation is the failure.
                    }
                }));
        observe(
                "xfdf-failure",
                "malformed-xml",
                failureKind(() ->
                {
                    try (FDFDocument ignored = Loader.loadXFDF(
                            new ByteArrayInputStream(
                                    "<xfdf><fields>".getBytes(
                                            StandardCharsets.UTF_8))))
                    {
                        // Observation is the failure.
                    }
                }));
    }

    private static String failureKind(ThrowingAction action)
    {
        try
        {
            action.run();
            return "none";
        }
        catch (IOException exception)
        {
            return "io";
        }
        catch (Exception exception)
        {
            return exception.getClass().getSimpleName();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }
}
