import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
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

public final class XmpBoxMetadataUpstreamOracle {
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final List<String> observations = new ArrayList<>();

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
        } finally {
            TimeZone.setDefault(original);
        }

        Files.write(new File(args[0]).toPath(), observations, StandardCharsets.UTF_8);
        System.out.println(
                "Pinned PDFBox 3.0.8 XmpBox metadata oracle passed: "
                        + observations.size()
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
