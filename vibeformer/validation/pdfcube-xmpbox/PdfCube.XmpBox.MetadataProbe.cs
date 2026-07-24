#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using PdfCube.XmpBox;
using PdfCube.XmpBox.Schema;
using PdfCube.XmpBox.Type;
using PdfCube.XmpBox.Xml;

internal static class Program
{
    private static readonly List<string> Observations = [];

    private static void Main(string[] args)
    {
        if (args.Length != 2)
        {
            throw new ArgumentException(
                "Expected output trace and XmpBox test-resource directory.");
        }

        CultureInfo originalCulture = CultureInfo.CurrentCulture;
        CultureInfo originalUiCulture = CultureInfo.CurrentUICulture;
        CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentUICulture = CultureInfo.InvariantCulture;
        try
        {
            ObserveMetadataAndNamespaces();
            ObserveTypeMapping();
            ObserveSimpleAndStructuredValues();
            ObserveArraysAndLanguageAlternatives();
            ObserveDates();
            ObserveInvalidValues();
            ObserveFixtures(args[1]);
        }
        finally
        {
            CultureInfo.CurrentCulture = originalCulture;
            CultureInfo.CurrentUICulture = originalUiCulture;
        }

        File.WriteAllLines(args[0], Observations);
        Console.WriteLine(
            "Generated PdfCube.XmpBox metadata probe passed: " +
            Observations.Count.ToString(CultureInfo.InvariantCulture) +
            " observations.");
    }

    private static void ObserveMetadataAndNamespaces()
    {
        XMPMetadata metadata =
            XMPMetadata.CreateXMPMetadata("begin", "id", "bytes", "encoding");
        DublinCoreSchema first = metadata.CreateAndAddDublinCoreSchema();
        DublinCoreSchema second = new(metadata, "customDc");
        metadata.AddSchema(second);
        first.SetFormat("application/pdf");
        second.SetCoverage("custom coverage");

        Observe(
            "namespace",
            "metadata-and-duplicate-schema",
            Join(
                metadata.GetXpacketBegin(),
                metadata.GetXpacketId(),
                metadata.GetXpacketBytes(),
                metadata.GetXpacketEncoding(),
                metadata.GetAllSchemas().Count,
                ReferenceEquals(metadata.GetDublinCoreSchema(), first),
                ReferenceEquals(metadata.GetSchema("customDc", first.GetNamespace()), second),
                metadata.GetSchema("missing") is null));

        IList<XMPSchema> copy = metadata.GetAllSchemas();
        copy.Clear();
        Observe(
            "namespace",
            "schema-list-copy",
            Join(copy.Count, metadata.GetAllSchemas().Count));
    }

    private static void ObserveTypeMapping()
    {
        XMPMetadata metadata = XMPMetadata.CreateXMPMetadata();
        TypeMapping mapping = metadata.GetTypeMapping();
        XMPSchemaFactory factory =
            mapping.GetSchemaFactory("http://purl.org/dc/elements/1.1/");
        List<string> propertyNames =
            factory.GetPropertyDefinition().GetPropertiesNames().ToList();
        List<string> repeatedPropertyNames =
            XMPMetadata.CreateXMPMetadata()
                .GetTypeMapping()
                .GetSchemaFactory("http://purl.org/dc/elements/1.1/")
                .GetPropertyDefinition()
                .GetPropertiesNames()
                .ToList();
        bool deterministicRegistryOrder =
            propertyNames.SequenceEqual(repeatedPropertyNames);
        propertyNames.Sort(StringComparer.Ordinal);

        Observe(
            "registry",
            "built-in-schema",
            Join(
                factory.GetNamespace(),
                propertyNames.Count,
                string.Join(",", propertyNames),
                factory.GetPropertyType("title").Type().ToString(),
                factory.GetPropertyType("title").Card().ToString(),
                deterministicRegistryOrder));

        const string extensionNamespace = "urn:pdfcube:xmp:extension";
        mapping.AddNewNameSpace(extensionNamespace, "pcx");
        XMPSchema extension =
            mapping.GetAssociatedSchemaObject(metadata, extensionNamespace, "pcx");
        extension.AddProperty(
            mapping.CreateText(
                extensionNamespace,
                "pcx",
                "sample",
                "extension-value"));
        Observe(
            "registry",
            "runtime-extension",
            Join(
                mapping.IsDefinedSchema(extensionNamespace),
                mapping.IsDefinedNamespace(extensionNamespace),
                extension.GetNamespace(),
                extension.GetPrefix(),
                extension.GetUnqualifiedTextPropertyValue("sample"),
                ReferenceEquals(metadata.GetSchema(extensionNamespace), extension)));

        PropertiesDescription description = new();
        description.AddNewProperty(
            "field",
            TypeMapping.CreatePropertyType(Types.Text, Cardinality.Simple));
        mapping.AddToDefinedStructuredTypes(
            "PdfCubeExtension",
            extensionNamespace,
            description);
        Observe(
            "registry",
            "defined-structured-extension",
            Join(
                mapping.IsDefinedType("PdfCubeExtension"),
                mapping.IsDefinedTypeNamespace(extensionNamespace),
                mapping.GetDefinedDescriptionByNamespace(extensionNamespace, "field")
                    .GetPropertyType("field").Type().ToString()));
    }

    private static void ObserveSimpleAndStructuredValues()
    {
        XMPMetadata metadata = XMPMetadata.CreateXMPMetadata();
        TypeMapping mapping = metadata.GetTypeMapping();
        BooleanType boolean = mapping.CreateBoolean(
            "urn:values", "v", "boolean", true);
        IntegerType integer = mapping.CreateInteger(
            "urn:values", "v", "integer", 17);
        RealType real = mapping.CreateReal(
            "urn:values", "v", "real", 1.25f);
        TextType text = mapping.CreateText(
            "urn:values", "v", "text", "hello");
        DateType date = mapping.CreateDate(
            "urn:values",
            "v",
            "date",
            DateConverter.ToCalendar("2024-03-04T05:06:07+05:30"));

        Observe(
            "simple",
            "typed-values",
            Join(
                boolean.GetValue(),
                boolean.GetStringValue(),
                integer.GetValue(),
                integer.GetStringValue(),
                real.GetValue(),
                real.GetStringValue(),
                text.GetValue(),
                date.GetStringValue(),
                text.GetNamespace(),
                text.GetPrefix()));

        AbstractStructuredType structured =
            mapping.InstanciateStructuredType(Types.Job, "jobs");
        JobType job = (JobType)structured;
        job.SetId("job-1");
        job.SetName("translate");
        job.SetUrl("https://example.test/job-1");
        Observe(
            "structured",
            "job",
            Join(
                structured.GetPropertyName(),
                structured.GetNamespace(),
                structured.GetPrefix(),
                job.GetId(),
                job.GetName(),
                job.GetUrl(),
                job.GetAllProperties().Count));
    }

    private static void ObserveArraysAndLanguageAlternatives()
    {
        XMPMetadata metadata = XMPMetadata.CreateXMPMetadata();
        DublinCoreSchema dc = metadata.CreateAndAddDublinCoreSchema();
        dc.AddCreator("first");
        dc.AddCreator("second");
        dc.AddSubject("alpha");
        dc.AddSubject("beta");
        dc.RemoveSubject("alpha");
        Observe(
            "array",
            "sequence-bag-order",
            Join(
                string.Join(",", dc.GetCreators()),
                string.Join(",", dc.GetSubjects()),
                dc.GetCreatorsProperty().GetArrayType().ToString(),
                dc.GetSubjectsProperty().GetArrayType().ToString(),
                FailureKind(() => dc.GetCreators().Add("forbidden"))));

        ArrayProperty identityArray =
            metadata.GetTypeMapping().CreateArrayProperty(
                "urn:identity", "id", "values", Cardinality.Seq);
        TextType same =
            metadata.GetTypeMapping().CreateText(
                null!, "rdf", "li", "same");
        TextType distinct =
            metadata.GetTypeMapping().CreateText(
                null!, "rdf", "li", "same");
        identityArray.AddProperty(same);
        identityArray.AddProperty(same);
        identityArray.AddProperty(distinct);
        Observe(
            "array",
            "identity-and-order",
            Join(
                identityArray.GetAllProperties().Count,
                identityArray.GetContainer().ContainsProperty(same),
                identityArray.GetContainer().ContainsProperty(distinct),
                string.Join(",", identityArray.GetElementsAsString())));

        dc.SetTitle("fr-FR", "Titre");
        dc.SetTitle(null!, "Default");
        dc.SetTitle("en-US", "Title");
        dc.SetTitle("fr-FR", "Titre modifié");
        Observe(
            "lang-alt",
            "default-first-and-update",
            Join(
                string.Join(",", dc.GetTitleLanguages()),
                dc.GetTitle(),
                dc.GetTitle("en-US"),
                dc.GetTitle("fr-FR"),
                dc.GetTitleProperty().GetAllProperties().Count));
        dc.SetTitle("en-US", null!);
        Observe(
            "lang-alt",
            "remove-language",
            Join(
                string.Join(",", dc.GetTitleLanguages()),
                dc.GetTitle("en-US") is null,
                dc.GetTitle()));
    }

    private static void ObserveDates()
    {
        DateTimeOffset offset =
            DateConverter.ToCalendar("2015-02-02T16:37:19.192+05:30");
        DateTimeOffset partial = DateConverter.ToCalendar("2015-05");
        DateTimeOffset missingSeconds =
            DateConverter.ToCalendar("2015-12-08T12:07-05:00");
        DateTimeOffset legacy =
            DateConverter.ToCalendar("D:20150203101112+0530");
        Observe(
            "date",
            "offset-and-format",
            Join(
                offset.ToUnixTimeMilliseconds(),
                (int)offset.Offset.TotalMinutes,
                DateConverter.ToISO8601(offset, true)));
        Observe(
            "date",
            "partial-and-missing-seconds",
            Join(
                partial.Year,
                partial.Month,
                partial.Day,
                missingSeconds.Second,
                missingSeconds.ToUniversalTime() ==
                    DateTimeOffset.Parse(
                        "2015-12-08T17:07:00Z",
                        CultureInfo.InvariantCulture)));
        Observe(
            "date",
            "legacy-pdf-offset",
            Join(
                legacy.ToUnixTimeMilliseconds(),
                (int)legacy.Offset.TotalMinutes,
                DateConverter.ToISO8601(legacy)));
    }

    private static void ObserveInvalidValues()
    {
        XMPMetadata metadata = XMPMetadata.CreateXMPMetadata();
        PDFAIdentificationSchema pdfaid =
            metadata.CreateAndAddPDFAIdentificationSchema();
        Observe(
            "invalid",
            "simple-values",
            Join(
                FailureKind(
                    () => _ = new BooleanType(
                        metadata, null!, "test", "boolean", "not-a-boolean")),
                FailureKind(
                    () => _ = new IntegerType(
                        metadata, null!, "test", "integer", "not-an-int")),
                FailureKind(
                    () => _ = new RealType(
                        metadata, null!, "test", "real", "not-a-real")),
                FailureKind(
                    () => _ = new DateType(
                        metadata, null!, "test", "date", "not-a-date"))));
        Observe(
            "invalid",
            "schema-validation",
            Join(
                FailureKind(() => pdfaid.SetConformance("invalid")),
                FailureKind(() => pdfaid.SetPartValueWithString("invalid"))));
    }

    private static void ObserveFixtures(string resources)
    {
        DomXmpParser parser = new();
        XMPMetadata alt;
        using (FileStream input = File.OpenRead(
            Path.Combine(
                resources,
                "org",
                "apache",
                "xmpbox",
                "parser",
                "AltBagSeqTest.xml")))
        {
            alt = parser.Parse(input);
        }

        DublinCoreSchema dc = alt.GetDublinCoreSchema();
        XMPSchema extension =
            alt.GetSchema("http://test.apache.com/xap/adn/");
        Observe(
            "fixture",
            "alt-bag-seq-built-ins",
            Join(
                alt.GetAllSchemas().Count,
                dc.GetFormat(),
                dc.GetDescription(),
                dc.GetTitle(),
                string.Join(",", dc.GetSubjects()),
                string.Join(",", dc.GetCreators()),
                alt.GetPDFAIdentificationSchema().GetPart(),
                alt.GetPDFAIdentificationSchema().GetConformance(),
                alt.GetXMPBasicSchema().GetCreateDateProperty().GetStringValue()));
        Observe(
            "fixture",
            "alt-bag-seq-extension",
            Join(
                extension.GetPrefix(),
                extension.GetUnqualifiedTextPropertyValue("nom"),
                string.Join(
                    ",",
                    extension.GetUnqualifiedSequenceValueList("prenom")),
                string.Join(",", extension.GetUnqualifiedBagValueList("bagTest")),
                extension.GetUnqualifiedLanguagePropertyValue(
                    "LangAltTest", null!),
                extension.GetUnqualifiedLanguagePropertyValue(
                    "LangAltTest", "fr-FR")));

        XMPMetadata thumbs;
        using (FileStream input = File.OpenRead(
            Path.Combine(
                resources,
                "org",
                "apache",
                "xmpbox",
                "parser",
                "ThumbisartorStyle.xml")))
        {
            thumbs = new DomXmpParser().Parse(input);
        }
        IList<ThumbnailType> values =
            thumbs.GetXMPBasicSchema().GetThumbnailsProperty();
        ThumbnailType first = values[0];
        Observe(
            "fixture",
            "structured-thumbnails",
            Join(
                values.Count,
                first.GetHeight(),
                first.GetWidth(),
                first.GetFormat(),
                first.GetImage(),
                thumbs.GetXMPMediaManagementSchema().GetDocumentID()));
    }

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (BadFieldValueException)
        {
            return "bad-field";
        }
        catch (ArgumentException)
        {
            return "argument";
        }
        catch (NotSupportedException)
        {
            return "read-only";
        }
        catch (Exception exception)
        {
            return exception.GetType().Name;
        }
    }

    private static void Observe(string family, string id, string value)
    {
        Observations.Add(family + "\t" + id + "\t" + value);
    }

    private static string Join(params object?[] values)
    {
        return string.Join(
            "|",
            values.Select(
                value => value is null
                    ? "<null>"
                    : value switch
                    {
                        bool boolean => boolean ? "true" : "false",
                        IFormattable formattable =>
                            formattable.ToString(
                                null,
                                CultureInfo.InvariantCulture),
                        _ => value.ToString()
                    }));
    }
}
