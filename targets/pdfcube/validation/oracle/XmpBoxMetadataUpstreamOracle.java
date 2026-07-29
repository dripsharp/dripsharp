import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import org.apache.xmpbox.DateConverter;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.schema.XMPSchemaFactory;
import org.apache.xmpbox.type.AbstractField;
import org.apache.xmpbox.type.AbstractStructuredType;
import org.apache.xmpbox.type.ArrayProperty;
import org.apache.xmpbox.type.BadFieldValueException;
import org.apache.xmpbox.type.BooleanType;
import org.apache.xmpbox.type.Cardinality;
import org.apache.xmpbox.type.DateType;
import org.apache.xmpbox.type.IntegerType;
import org.apache.xmpbox.type.JobType;
import org.apache.xmpbox.type.PropertiesDescription;
import org.apache.xmpbox.type.RealType;
import org.apache.xmpbox.type.TextType;
import org.apache.xmpbox.type.ThumbnailType;
import org.apache.xmpbox.type.TypeMapping;
import org.apache.xmpbox.type.Types;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpParsingException;
import org.apache.xmpbox.xml.XmpSerializer;

public final class XmpBoxMetadataUpstreamOracle {
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final List<String> observations = new ArrayList<>();

    static {
        observations.add("DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1");
    }

    private XmpBoxMetadataUpstreamOracle() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Expected output trace and XmpBox test-resource directory.");
        }

        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            observeMetadataAndNamespaces();
            observeTypeMapping();
            observeSimpleAndStructuredValues();
            observeArraysAndLanguageAlternatives();
            observeDates();
            observeInvalidValues();
            observeFixtures(new File(args[1]));
            observeDomParsing(new File(args[1]));
            observePdfaExtensions(new File(args[1]));
            observeSerializationAndRoundTrips(new File(args[1]), new File(args[0]));
            observeXmlSecurityAndLifetimes();
        } finally {
            TimeZone.setDefault(original);
        }

        Files.write(new File(args[0]).toPath(), observations, StandardCharsets.UTF_8);
        System.out.println(
                "Pinned reviewed PDFBox baseline XmpBox metadata oracle passed: "
                        + (observations.size() - 1)
                        + " observations.");
    }

    private static void observeMetadataAndNamespaces() {
        XMPMetadata metadata =
                XMPMetadata.createXMPMetadata("begin", "id", "bytes", "encoding");
        DublinCoreSchema first = metadata.createAndAddDublinCoreSchema();
        DublinCoreSchema second = new DublinCoreSchema(metadata, "customDc");
        metadata.addSchema(second);
        first.setFormat("application/pdf");
        second.setCoverage("custom coverage");

        observe(
                "namespace",
                "metadata-and-duplicate-schema",
                join(
                        metadata.getXpacketBegin(),
                        metadata.getXpacketId(),
                        metadata.getXpacketBytes(),
                        metadata.getXpacketEncoding(),
                        metadata.getAllSchemas().size(),
                        metadata.getDublinCoreSchema() == first,
                        metadata.getSchema("customDc", first.getNamespace()) == second,
                        metadata.getSchema("missing") == null));

        List<XMPSchema> copy = metadata.getAllSchemas();
        copy.clear();
        observe(
                "namespace",
                "schema-list-copy",
                join(copy.size(), metadata.getAllSchemas().size()));
    }

    private static void observeTypeMapping() throws Exception {
        XMPMetadata metadata = XMPMetadata.createXMPMetadata();
        TypeMapping mapping = metadata.getTypeMapping();
        XMPSchemaFactory factory =
                mapping.getSchemaFactory("http://purl.org/dc/elements/1.1/");
        List<String> propertyNames =
                new ArrayList<>(factory.getPropertyDefinition().getPropertiesNames());
        List<String> repeatedPropertyNames =
                new ArrayList<>(
                        XMPMetadata.createXMPMetadata()
                                .getTypeMapping()
                                .getSchemaFactory("http://purl.org/dc/elements/1.1/")
                                .getPropertyDefinition()
                                .getPropertiesNames());
        boolean deterministicRegistryOrder =
                new ArrayList<>(propertyNames).equals(repeatedPropertyNames);
        Collections.sort(propertyNames);

        observe(
                "registry",
                "built-in-schema",
                join(
                        factory.getNamespace(),
                        propertyNames.size(),
                        String.join(",", propertyNames),
                        factory.getPropertyType("title").type().name(),
                        factory.getPropertyType("title").card().name(),
                        deterministicRegistryOrder));

        String extensionNamespace = "urn:pdfcube:xmp:extension";
        mapping.addNewNameSpace(extensionNamespace, "pcx");
        XMPSchema extension =
                mapping.getAssociatedSchemaObject(metadata, extensionNamespace, "pcx");
        extension.addProperty(
                mapping.createText(extensionNamespace, "pcx", "sample", "extension-value"));
        observe(
                "registry",
                "runtime-extension",
                join(
                        mapping.isDefinedSchema(extensionNamespace),
                        mapping.isDefinedNamespace(extensionNamespace),
                        extension.getNamespace(),
                        extension.getPrefix(),
                        extension.getUnqualifiedTextPropertyValue("sample"),
                        metadata.getSchema(extensionNamespace) == extension));

        PropertiesDescription description = new PropertiesDescription();
        description.addNewProperty(
                "field", TypeMapping.createPropertyType(Types.Text, Cardinality.Simple));
        mapping.addToDefinedStructuredTypes("PdfCubeExtension", extensionNamespace, description);
        observe(
                "registry",
                "defined-structured-extension",
                join(
                        mapping.isDefinedType("PdfCubeExtension"),
                        mapping.isDefinedTypeNamespace(extensionNamespace),
                        mapping.getDefinedDescriptionByNamespace(extensionNamespace, "field")
                                .getPropertyType("field")
                                .type()
                                .name()));
    }

    private static void observeSimpleAndStructuredValues() throws Exception {
        XMPMetadata metadata = XMPMetadata.createXMPMetadata();
        TypeMapping mapping = metadata.getTypeMapping();
        BooleanType bool = mapping.createBoolean("urn:values", "v", "boolean", true);
        IntegerType integer = mapping.createInteger("urn:values", "v", "integer", 17);
        RealType real = mapping.createReal("urn:values", "v", "real", 1.25f);
        TextType text = mapping.createText("urn:values", "v", "text", "hello");
        DateType date =
                mapping.createDate(
                        "urn:values",
                        "v",
                        "date",
                        DateConverter.toCalendar("2024-03-04T05:06:07+05:30"));

        observe(
                "simple",
                "typed-values",
                join(
                        bool.getValue(),
                        bool.getStringValue(),
                        integer.getValue(),
                        integer.getStringValue(),
                        real.getValue(),
                        real.getStringValue(),
                        text.getValue(),
                        date.getStringValue(),
                        text.getNamespace(),
                        text.getPrefix()));

        AbstractStructuredType structured =
                mapping.instanciateStructuredType(Types.Job, "jobs");
        JobType job = (JobType) structured;
        job.setId("job-1");
        job.setName("translate");
        job.setUrl("https://example.test/job-1");
        observe(
                "structured",
                "job",
                join(
                        structured.getPropertyName(),
                        structured.getNamespace(),
                        structured.getPrefix(),
                        job.getId(),
                        job.getName(),
                        job.getUrl(),
                        job.getAllProperties().size()));
    }

    private static void observeArraysAndLanguageAlternatives() throws Exception {
        XMPMetadata metadata = XMPMetadata.createXMPMetadata();
        DublinCoreSchema dc = metadata.createAndAddDublinCoreSchema();
        dc.addCreator("first");
        dc.addCreator("second");
        dc.addSubject("alpha");
        dc.addSubject("beta");
        dc.removeSubject("alpha");
        observe(
                "array",
                "sequence-bag-order",
                join(
                        String.join(",", dc.getCreators()),
                        String.join(",", dc.getSubjects()),
                        dc.getCreatorsProperty().getArrayType().name(),
                        dc.getSubjectsProperty().getArrayType().name(),
                        failureKind(() -> dc.getCreators().add("forbidden"))));

        ArrayProperty identityArray =
                metadata.getTypeMapping()
                        .createArrayProperty(
                                "urn:identity", "id", "values", Cardinality.Seq);
        TextType same =
                metadata.getTypeMapping()
                        .createText(null, "rdf", "li", "same");
        TextType distinct =
                metadata.getTypeMapping()
                        .createText(null, "rdf", "li", "same");
        identityArray.addProperty(same);
        identityArray.addProperty(same);
        identityArray.addProperty(distinct);
        observe(
                "array",
                "identity-and-order",
                join(
                        identityArray.getAllProperties().size(),
                        identityArray.getContainer().containsProperty(same),
                        identityArray.getContainer().containsProperty(distinct),
                        String.join(",", identityArray.getElementsAsString())));

        dc.setTitle("fr-FR", "Titre");
        dc.setTitle(null, "Default");
        dc.setTitle("en-US", "Title");
        dc.setTitle("fr-FR", "Titre modifié");
        observe(
                "lang-alt",
                "default-first-and-update",
                join(
                        String.join(",", dc.getTitleLanguages()),
                        dc.getTitle(),
                        dc.getTitle("en-US"),
                        dc.getTitle("fr-FR"),
                        dc.getTitleProperty().getAllProperties().size()));
        dc.setTitle("en-US", null);
        observe(
                "lang-alt",
                "remove-language",
                join(
                        String.join(",", dc.getTitleLanguages()),
                        dc.getTitle("en-US") == null,
                        dc.getTitle()));
    }

    private static void observeDates() throws Exception {
        Calendar offset = DateConverter.toCalendar("2015-02-02T16:37:19.192+05:30");
        Calendar partial = DateConverter.toCalendar("2015-05");
        Calendar missingSeconds = DateConverter.toCalendar("2015-12-08T12:07-05:00");
        Calendar legacy = DateConverter.toCalendar("D:20150203101112+0530");
        observe(
                "date",
                "offset-and-format",
                join(
                        offset.toInstant().toEpochMilli(),
                        offset.getTimeZone().getRawOffset() / 60000,
                        DateConverter.toISO8601(offset, true)));
        observe(
                "date",
                "partial-and-missing-seconds",
                join(
                        partial.get(Calendar.YEAR),
                        partial.get(Calendar.MONTH) + 1,
                        partial.get(Calendar.DAY_OF_MONTH),
                        missingSeconds.get(Calendar.SECOND),
                        missingSeconds.toInstant().equals(
                                Instant.parse("2015-12-08T17:07:00Z"))));
        observe(
                "date",
                "legacy-pdf-offset",
                join(
                        legacy.toInstant().toEpochMilli(),
                        legacy.getTimeZone().getRawOffset() / 60000,
                        DateConverter.toISO8601(legacy)));
    }

    private static void observeInvalidValues() {
        XMPMetadata metadata = XMPMetadata.createXMPMetadata();
        PDFAIdentificationSchema pdfaid =
                metadata.createAndAddPDFAIdentificationSchema();
        observe(
                "invalid",
                "simple-values",
                join(
                        failureKind(
                                () ->
                                        new BooleanType(
                                                metadata,
                                                null,
                                                "test",
                                                "boolean",
                                                "not-a-boolean")),
                        failureKind(
                                () ->
                                        new IntegerType(
                                                metadata, null, "test", "integer", "not-an-int")),
                        failureKind(
                                () ->
                                        new RealType(
                                                metadata, null, "test", "real", "not-a-real")),
                        failureKind(
                                () ->
                                        new DateType(
                                                metadata, null, "test", "date", "not-a-date"))));
        observe(
                "invalid",
                "schema-validation",
                join(
                        failureKind(() -> pdfaid.setConformance("invalid")),
                        failureKind(() -> pdfaid.setPartValueWithString("invalid"))));
    }

    private static void observeFixtures(File resources) throws Exception {
        DomXmpParser parser = new DomXmpParser();
        XMPMetadata alt;
        try (FileInputStream input =
                new FileInputStream(
                        new File(resources, "org/apache/xmpbox/parser/AltBagSeqTest.xml"))) {
            alt = parser.parse(input);
        }

        DublinCoreSchema dc = alt.getDublinCoreSchema();
        XMPSchema extension = alt.getSchema("http://test.apache.com/xap/adn/");
        observe(
                "fixture",
                "alt-bag-seq-built-ins",
                join(
                        alt.getAllSchemas().size(),
                        dc.getFormat(),
                        dc.getDescription(),
                        dc.getTitle(),
                        String.join(",", dc.getSubjects()),
                        String.join(",", dc.getCreators()),
                        alt.getPDFAIdentificationSchema().getPart(),
                        alt.getPDFAIdentificationSchema().getConformance(),
                        alt.getXMPBasicSchema().getCreateDateProperty().getStringValue()));
        observe(
                "fixture",
                "alt-bag-seq-extension",
                join(
                        extension.getPrefix(),
                        extension.getUnqualifiedTextPropertyValue("nom"),
                        String.join(
                                ",",
                                extension.getUnqualifiedSequenceValueList("prenom")),
                        String.join(",", extension.getUnqualifiedBagValueList("bagTest")),
                        extension.getUnqualifiedLanguagePropertyValue("LangAltTest", null),
                        extension.getUnqualifiedLanguagePropertyValue(
                                "LangAltTest", "fr-FR")));

        XMPMetadata thumbs;
        try (FileInputStream input =
                new FileInputStream(
                        new File(resources, "org/apache/xmpbox/parser/ThumbisartorStyle.xml"))) {
            thumbs = new DomXmpParser().parse(input);
        }
        List<ThumbnailType> values = thumbs.getXMPBasicSchema().getThumbnailsProperty();
        ThumbnailType first = values.get(0);
        observe(
                "fixture",
                "structured-thumbnails",
                join(
                        values.size(),
                        first.getHeight(),
                        first.getWidth(),
                        first.getFormat(),
                        first.getImage(),
                        thumbs.getXMPMediaManagementSchema().getDocumentID()));
    }

    private static void observeDomParsing(File resources) throws Exception {
        String[] valid = {
            "org/apache/xmpbox/parser/structured_recursive.xml",
            "org/apache/xmpbox/parser/empty_list.xml",
            "org/apache/xmpbox/xml/PDFBOX-5649.xml",
            "org/apache/xmpbox/xml/PDFBOX-5835.xml",
            "validxmp/PDFBOX-6099.xmp",
            "validxmp/attr_as_props.xml",
            "validxmp/only_space_fields.xmp",
            "validxmp/override_ns.rdf"
        };
        for (String path : valid) {
            XMPMetadata metadata = parseFile(resources, path, true);
            observe(
                    "parser",
                    path,
                    join(
                            metadata.getAllSchemas().size(),
                            metadata.getXpacketId(),
                            metadata.getEndXPacket()));
        }

        String[] invalid = {
            "invalidxmp/invalidroot.xml",
            "invalidxmp/invalidroot2.xml",
            "invalidxmp/noroot.xml",
            "invalidxmp/noxpacket.xml",
            "invalidxmp/noxpacketend.xml",
            "invalidxmp/tworoot.xml",
            "invalidxmp/undefinedpropertyindefinedschema.xml",
            "invalidxmp/undefinedschema.xml",
            "invalidxmp/undefinedstructuredindefinedschema.xml"
        };
        for (String path : invalid) {
            observe(
                    "parser-failure",
                    path,
                    parsingFailure(() -> parseFile(resources, path, true)));
        }

        XMPMetadata prism = parseFile(resources, "undefinedxmp/prism.xmp", false);
        observe(
                "strict-lenient",
                "undefined-schema",
                join(
                        parsingFailure(
                                () -> parseFile(resources, "undefinedxmp/prism.xmp", true)),
                        prism.getSchema("http://prismstandard.org/namespaces/basic/2.0/")
                                .getUnqualifiedTextPropertyValue("aggregationType")));

        XMPMetadata noPacket = parseFile(resources, "invalidxmp/noxpacket.xml", false);
        observe(
                "strict-lenient",
                "missing-packet",
                join(
                        parsingFailure(
                                () -> parseFile(resources, "invalidxmp/noxpacket.xml", true)),
                        noPacket.getXpacketId(),
                        noPacket.getEndXPacket(),
                        noPacket.getAllSchemas().size()));

        String duplicate =
                packet(
                        "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" rdf:about=\"\">"
                                + "<dc:format>first</dc:format>"
                                + "<dc:format>second</dc:format>"
                                + "</rdf:Description>");
        XMPMetadata duplicateMetadata =
                new DomXmpParser().parse(duplicate.getBytes(StandardCharsets.UTF_8));
        observe(
                "parser",
                "duplicate-properties",
                join(
                        duplicateMetadata.getDublinCoreSchema().getAllProperties().size(),
                        duplicateMetadata.getDublinCoreSchema().getFormat()));

        String namespaceConflict =
                "<?xpacket begin=\"\" id=\"id\"?>"
                        + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                        + "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                        + " xmlns:dc=\"urn:conflict\"/>"
                        + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        observe(
                "parser-failure",
                "namespace-conflict",
                parsingFailure(
                        () ->
                                new DomXmpParser()
                                        .parse(
                                                namespaceConflict.getBytes(
                                                        StandardCharsets.UTF_8))));
    }

    private static void observePdfaExtensions(File resources) throws Exception {
        XMPMetadata dematbox =
                parseFile(resources, "org/apache/xmpbox/xml/PDFBOX-3882-dematbox.xml", true);
        XMPSchema extension =
                dematbox.getSchema("http://www.sagemcom.com/documents/xmlns/dematbox");
        ArrayProperty pageInfo = (ArrayProperty) extension.getProperty("PageInfo");
        AbstractStructuredType page =
                (AbstractStructuredType) pageInfo.getAllProperties().get(0);
        observe(
                "extension",
                "defined-structured-type",
                join(
                        extension.getPrefix(),
                        pageInfo.getArrayType(),
                        page.getProperty("number").toString(),
                        page.getProperty("origNumber").toString()));

        String isartor =
                new String(
                        Files.readAllBytes(
                                new File(
                                                resources,
                                                "org/apache/xmpbox/parser/isartorStyleXMPOK.xml")
                                        .toPath()),
                        StandardCharsets.UTF_8);
        String missingProperty =
                isartor.replaceFirst(
                        "(?s)<pdfaSchema:property>.*?</pdfaSchema:property>", "");
        DomXmpParser lenient = new DomXmpParser();
        lenient.setStrictParsing(false);
        XMPMetadata lenientMetadata =
                lenient.parse(missingProperty.getBytes(StandardCharsets.UTF_8));
        observe(
                "extension",
                "missing-property",
                join(
                        parsingFailure(
                                () ->
                                        new DomXmpParser()
                                                .parse(
                                                        missingProperty.getBytes(
                                                                StandardCharsets.UTF_8))),
                        lenientMetadata.getAllSchemas().size()));

        String unknownType =
                isartor.replaceFirst(
                        "(?s)<pdfaProperty:valueType>.*?</pdfaProperty:valueType>",
                        "<pdfaProperty:valueType>Bogus</pdfaProperty:valueType>");
        observe(
                "extension",
                "unknown-value-type",
                parsingFailure(
                        () ->
                                new DomXmpParser()
                                        .parse(
                                                unknownType.getBytes(
                                                        StandardCharsets.UTF_8))));

        String invalidNamespace =
                packet(
                        "<rdf:Description xmlns:pdfaExtension=\"urn:wrong\" rdf:about=\"\">"
                                + "<pdfaExtension:schemas><rdf:Bag/></pdfaExtension:schemas>"
                                + "</rdf:Description>");
        observe(
                "extension",
                "invalid-namespace",
                parsingFailure(
                        () ->
                                new DomXmpParser()
                                        .parse(
                                                invalidNamespace.getBytes(
                                                        StandardCharsets.UTF_8))));
    }

    private static void observeSerializationAndRoundTrips(File resources, File trace)
            throws Exception {
        XMPMetadata metadata =
                XMPMetadata.createXMPMetadata("\uFEFF", "packet-id", "4096", "UTF-8");
        metadata.setEndXPacket("r");
        DublinCoreSchema dc = metadata.createAndAddDublinCoreSchema();
        dc.setTitle("x-default", "A <tag> & \"quote\"");
        dc.setTitle("fr-FR", "Caf\u00E9");
        dc.addCreator("first");
        dc.addCreator("second");

        TrackingOutputStream first = new TrackingOutputStream();
        new XmpSerializer().serialize(metadata, first, true);
        byte[] firstBytes = first.toByteArray();
        Files.write(
                new File(trace.getParentFile(), "upstream-programmatic.xml").toPath(),
                firstBytes);
        TrackingOutputStream second = new TrackingOutputStream();
        new XmpSerializer().serialize(metadata, second, true);
        byte[] secondBytes = second.toByteArray();
        String serialized = new String(firstBytes, StandardCharsets.UTF_8);
        observe(
                "serialization",
                "deterministic-packet",
                join(
                        normalizedSha256(firstBytes),
                        normalizedSha256(secondBytes),
                        Arrays.equals(firstBytes, secondBytes),
                        firstBytes.length,
                        startsWithUtf8Bom(firstBytes),
                        serialized.startsWith("<?xpacket begin=\"\uFEFF\" id=\"packet-id\"?>"),
                        serialized.trim().endsWith("<?xpacket end=\"r\"?>"),
                        !serialized.contains("<?xml"),
                        serialized.endsWith("\n")));
        observe(
                "serialization",
                "escaping-and-order",
                join(
                        serialized.contains("A &lt;tag&gt; &amp; \"quote\""),
                        serialized.indexOf(">first<") < serialized.indexOf(">second<"),
                        serialized.indexOf("x-default") < serialized.indexOf("fr-FR"),
                        serialized.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")));
        observe(
                "serialization",
                "programmatic-bytes",
                Base64.getEncoder().encodeToString(firstBytes));

        ByteArrayOutputStream noPacket = new ByteArrayOutputStream();
        new XmpSerializer().serialize(metadata, noPacket, false);
        String noPacketXml = noPacket.toString(StandardCharsets.UTF_8.name());
        observe(
                "serialization",
                "without-packet",
                join(
                        !noPacketXml.contains("xpacket"),
                        noPacketXml.startsWith("<x:xmpmeta"),
                        normalizedSha256(noPacket.toByteArray())));

        XMPMetadata roundTrip = new DomXmpParser().parse(firstBytes);
        observe(
                "round-trip",
                "programmatic",
                join(
                        roundTrip.getAllSchemas().size(),
                        roundTrip.getDublinCoreSchema().getTitle(),
                        roundTrip.getDublinCoreSchema().getTitle("fr-FR"),
                        String.join(",", roundTrip.getDublinCoreSchema().getCreators()),
                        roundTrip.getEndXPacket()));

        String[] fixturePaths = {
            "org/apache/xmpbox/parser/structured_recursive.xml",
            "org/apache/xmpbox/parser/empty_list.xml",
            "org/apache/xmpbox/parser/AltBagSeqTest.xml",
            "org/apache/xmpbox/parser/ThumbisartorStyle.xml",
            "validxmp/attr_as_props.xml",
            "validxmp/only_space_fields.xmp"
        };
        for (String path : fixturePaths) {
            XMPMetadata parsed = parseFile(resources, path, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new XmpSerializer().serialize(parsed, output, true);
            Files.write(
                    new File(
                                    trace.getParentFile(),
                                    "upstream-" + path.replace('/', '_'))
                            .toPath(),
                    output.toByteArray());
            XMPMetadata reparsed = new DomXmpParser().parse(output.toByteArray());
            observe(
                    "round-trip",
                    path,
                    join(
                            normalizedSha256(output.toByteArray()),
                            parsed.getAllSchemas().size(),
                            reparsed.getAllSchemas().size()));
        }
    }

    private static void observeXmlSecurityAndLifetimes() throws Exception {
        String internalEntity =
                "<?xpacket begin=\"\" id=\"id\"?>"
                        + "<!DOCTYPE x:xmpmeta [<!ENTITY injected \"boom\">]>"
                        + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                        + "<rdf:Description rdf:about=\"\">&injected;</rdf:Description>"
                        + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        String externalEntity =
                "<?xpacket begin=\"\" id=\"id\"?>"
                        + "<!DOCTYPE x:xmpmeta [<!ENTITY injected SYSTEM \"file:///etc/passwd\">]>"
                        + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                        + "<rdf:Description rdf:about=\"\">&injected;</rdf:Description>"
                        + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        observe(
                "security",
                "doctype-and-entities",
                join(
                        parsingFailure(
                                () ->
                                        new DomXmpParser()
                                                .parse(
                                                        internalEntity.getBytes(
                                                                StandardCharsets.UTF_8))),
                        parsingFailure(
                                () ->
                                        new DomXmpParser()
                                                .parse(
                                                        externalEntity.getBytes(
                                                                StandardCharsets.UTF_8)))));

        TrackingInputStream successInput =
                new TrackingInputStream(packet("").getBytes(StandardCharsets.UTF_8));
        new DomXmpParser().parse(successInput);
        TrackingInputStream failureInput =
                new TrackingInputStream("<broken".getBytes(StandardCharsets.UTF_8));
        parsingFailure(() -> new DomXmpParser().parse(failureInput));
        TrackingOutputStream output = new TrackingOutputStream();
        new XmpSerializer().serialize(XMPMetadata.createXMPMetadata(), output, true);
        observe(
                "lifetime",
                "caller-owned-streams",
                join(!successInput.closed, !failureInput.closed, !output.closed));
    }

    private static XMPMetadata parseFile(File resources, String path, boolean strict)
            throws Exception {
        DomXmpParser parser = new DomXmpParser();
        parser.setStrictParsing(strict);
        try (FileInputStream input = new FileInputStream(new File(resources, path))) {
            return parser.parse(input);
        }
    }

    private static String packet(String descriptions) {
        return "<?xpacket begin=\"\" id=\"id\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + descriptions
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
    }

    private static String parsingFailure(ThrowingAction action) {
        try {
            action.run();
            return "none";
        } catch (XmpParsingException exception) {
            return exception.getErrorType().toString();
        } catch (Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private static String normalizedSha256(byte[] bytes) throws Exception {
        String normalized =
                new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest(normalized.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02X", value & 0xFF));
        }
        return result.toString();
    }

    private static boolean startsWithUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }

    private static String failureKind(ThrowingAction action) {
        try {
            action.run();
            return "none";
        } catch (BadFieldValueException exception) {
            return "bad-field";
        } catch (IllegalArgumentException exception) {
            return "argument";
        } catch (UnsupportedOperationException exception) {
            return "read-only";
        } catch (Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private static void observe(String family, String id, String value) {
        observations.add(family + "\t" + id + "\t" + value);
    }

    private static String join(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            if (result.length() != 0) {
                result.append('|');
            }
            result.append(value == null ? "<null>" : value);
        }
        return result.toString();
    }
}
