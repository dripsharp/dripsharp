#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using DripSharp.PdfCarton.Xmp;
using DripSharp.PdfCarton.Xmp.Schema;
using DripSharp.PdfCarton.Xmp.Type;
using DripSharp.PdfCarton.Xmp.Xml;
using Architecture = System.Runtime.InteropServices.Architecture;
using OSPlatform = System.Runtime.InteropServices.OSPlatform;
using RuntimeInformation = System.Runtime.InteropServices.RuntimeInformation;

internal static class Program
{
    private static readonly List<string> Observations =
        ["DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1"];

    private static void Main(string[] args)
    {
        if (args.Length is not (2 or 3 or 5))
        {
            throw new ArgumentException(
                "Expected output trace, XmpBox test-resource directory, " +
                "optional canonical trace, and optional OS/architecture.");
        }

        CultureInfo originalCulture = CultureInfo.CurrentCulture;
        CultureInfo originalUiCulture = CultureInfo.CurrentUICulture;
        string? originalTimezone = Environment.GetEnvironmentVariable("TZ");
        CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentUICulture = CultureInfo.InvariantCulture;
        Environment.SetEnvironmentVariable("TZ", "UTC");
        TimeZoneInfo.ClearCachedData();
        try
        {
            ObserveMetadataAndNamespaces();
            ObserveTypeMapping();
            ObserveSimpleAndStructuredValues();
            ObserveArraysAndLanguageAlternatives();
            ObserveDates();
            ObserveInvalidValues();
            ObserveFixtures(args[1]);
            ObserveDomParsing(args[1]);
            ObservePdfaExtensions(args[1]);
            ObserveSerializationAndRoundTrips(args[1], args[0]);
            ObserveXmlSecurityAndLifetimes();
        }
        finally
        {
            CultureInfo.CurrentCulture = originalCulture;
            CultureInfo.CurrentUICulture = originalUiCulture;
            Environment.SetEnvironmentVariable("TZ", originalTimezone);
            TimeZoneInfo.ClearCachedData();
        }

        File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
        if (args.Length >= 3)
            ValidateCanonical(args[2]);
        if (args.Length == 5)
            ValidateHost(args[3], args[4]);
        Console.WriteLine(
            "DripSharp.PdfCarton.Xmp package differential passed: " +
            (Observations.Count - 1).ToString(CultureInfo.InvariantCulture) +
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
            "PdfCartonExtension",
            extensionNamespace,
            description);
        Observe(
            "registry",
            "defined-structured-extension",
            Join(
                mapping.IsDefinedType("PdfCartonExtension"),
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
            DateConverter.ToCalendar("2015-02-02T16:37:19.192+05:30")
            ?? throw new InvalidDataException("Offset timestamp did not parse.");
        DateTimeOffset partial = DateConverter.ToCalendar("2015-05")
            ?? throw new InvalidDataException("Partial timestamp did not parse.");
        DateTimeOffset missingSeconds =
            DateConverter.ToCalendar("2015-12-08T12:07-05:00")
            ?? throw new InvalidDataException("Minute timestamp did not parse.");
        DateTimeOffset legacy =
            DateConverter.ToCalendar("D:20150203101112+0530")
            ?? throw new InvalidDataException("Legacy timestamp did not parse.");
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

    private static void ObserveDomParsing(string resources)
    {
        string[] valid =
        [
            "org/apache/xmpbox/parser/structured_recursive.xml",
            "org/apache/xmpbox/parser/empty_list.xml",
            "org/apache/xmpbox/xml/PDFBOX-5649.xml",
            "org/apache/xmpbox/xml/PDFBOX-5835.xml",
            "validxmp/PDFBOX-6099.xmp",
            "validxmp/attr_as_props.xml",
            "validxmp/only_space_fields.xmp",
            "validxmp/override_ns.rdf"
        ];
        foreach (string path in valid)
        {
            XMPMetadata metadata = ParseFile(resources, path, strict: true);
            Observe(
                "parser",
                path,
                Join(
                    metadata.GetAllSchemas().Count,
                    metadata.GetXpacketId(),
                    metadata.GetEndXPacket()));
        }

        string[] invalid =
        [
            "invalidxmp/invalidroot.xml",
            "invalidxmp/invalidroot2.xml",
            "invalidxmp/noroot.xml",
            "invalidxmp/noxpacket.xml",
            "invalidxmp/noxpacketend.xml",
            "invalidxmp/tworoot.xml",
            "invalidxmp/undefinedpropertyindefinedschema.xml",
            "invalidxmp/undefinedschema.xml",
            "invalidxmp/undefinedstructuredindefinedschema.xml"
        ];
        foreach (string path in invalid)
        {
            Observe(
                "parser-failure",
                path,
                ParsingFailure(() => ParseFile(resources, path, strict: true)));
        }

        XMPMetadata prism =
            ParseFile(resources, "undefinedxmp/prism.xmp", strict: false);
        Observe(
            "strict-lenient",
            "undefined-schema",
            Join(
                ParsingFailure(
                    () => ParseFile(resources, "undefinedxmp/prism.xmp", strict: true)),
                prism.GetSchema("http://prismstandard.org/namespaces/basic/2.0/")
                    .GetUnqualifiedTextPropertyValue("aggregationType")));

        XMPMetadata noPacket =
            ParseFile(resources, "invalidxmp/noxpacket.xml", strict: false);
        Observe(
            "strict-lenient",
            "missing-packet",
            Join(
                ParsingFailure(
                    () => ParseFile(resources, "invalidxmp/noxpacket.xml", strict: true)),
                noPacket.GetXpacketId(),
                noPacket.GetEndXPacket(),
                noPacket.GetAllSchemas().Count));

        string duplicate =
            Packet(
                "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" rdf:about=\"\">" +
                "<dc:format>first</dc:format>" +
                "<dc:format>second</dc:format>" +
                "</rdf:Description>");
        XMPMetadata duplicateMetadata =
            new DomXmpParser().Parse(SignedBytes(Encoding.UTF8.GetBytes(duplicate)));
        Observe(
            "parser",
            "duplicate-properties",
            Join(
                duplicateMetadata.GetDublinCoreSchema().GetAllProperties().Count,
                duplicateMetadata.GetDublinCoreSchema().GetFormat()));

        string namespaceConflict =
            "<?xpacket begin=\"\" id=\"id\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
            " xmlns:dc=\"urn:conflict\"/>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        Observe(
            "parser-failure",
            "namespace-conflict",
            ParsingFailure(
                () =>
                    new DomXmpParser().Parse(
                        SignedBytes(Encoding.UTF8.GetBytes(namespaceConflict)))));
    }

    private static void ObservePdfaExtensions(string resources)
    {
        XMPMetadata dematbox =
            ParseFile(
                resources,
                "org/apache/xmpbox/xml/PDFBOX-3882-dematbox.xml",
                strict: true);
        XMPSchema extension =
            dematbox.GetSchema("http://www.sagemcom.com/documents/xmlns/dematbox");
        ArrayProperty pageInfo = (ArrayProperty)extension.GetProperty("PageInfo");
        AbstractStructuredType page =
            (AbstractStructuredType)pageInfo.GetAllProperties()[0];
        Observe(
            "extension",
            "defined-structured-type",
            Join(
                extension.GetPrefix(),
                pageInfo.GetArrayType(),
                page.GetProperty("number").ToString(),
                page.GetProperty("origNumber").ToString()));

        string isartor =
            File.ReadAllText(
                Path.Combine(
                    resources,
                    "org",
                    "apache",
                    "xmpbox",
                    "parser",
                    "isartorStyleXMPOK.xml"),
                Encoding.UTF8);
        string missingProperty =
            new Regex(
                    "<pdfaSchema:property>.*?</pdfaSchema:property>",
                    RegexOptions.Singleline,
                    TimeSpan.FromSeconds(1))
                .Replace(isartor, "", count: 1);
        DomXmpParser lenient = new();
        lenient.SetStrictParsing(false);
        XMPMetadata lenientMetadata =
            lenient.Parse(SignedBytes(Encoding.UTF8.GetBytes(missingProperty)));
        Observe(
            "extension",
            "missing-property",
            Join(
                ParsingFailure(
                    () =>
                        new DomXmpParser().Parse(
                            SignedBytes(Encoding.UTF8.GetBytes(missingProperty)))),
                lenientMetadata.GetAllSchemas().Count));

        string unknownType =
            new Regex(
                    "<pdfaProperty:valueType>.*?</pdfaProperty:valueType>",
                    RegexOptions.Singleline,
                    TimeSpan.FromSeconds(1))
                .Replace(
                    isartor,
                    "<pdfaProperty:valueType>Bogus</pdfaProperty:valueType>",
                    count: 1);
        Observe(
            "extension",
            "unknown-value-type",
            ParsingFailure(
                () =>
                    new DomXmpParser().Parse(
                        SignedBytes(Encoding.UTF8.GetBytes(unknownType)))));

        string invalidNamespace =
            Packet(
                "<rdf:Description xmlns:pdfaExtension=\"urn:wrong\" rdf:about=\"\">" +
                "<pdfaExtension:schemas><rdf:Bag/></pdfaExtension:schemas>" +
                "</rdf:Description>");
        Observe(
            "extension",
            "invalid-namespace",
            ParsingFailure(
                () =>
                    new DomXmpParser().Parse(
                        SignedBytes(Encoding.UTF8.GetBytes(invalidNamespace)))));
    }

    private static void ObserveSerializationAndRoundTrips(
        string resources,
        string trace)
    {
        XMPMetadata metadata =
            XMPMetadata.CreateXMPMetadata("\uFEFF", "packet-id", "4096", "UTF-8");
        metadata.SetEndXPacket("r");
        DublinCoreSchema dc = metadata.CreateAndAddDublinCoreSchema();
        dc.SetTitle("x-default", "A <tag> & \"quote\"");
        dc.SetTitle("fr-FR", "Caf\u00E9");
        dc.AddCreator("first");
        dc.AddCreator("second");

        TrackingMemoryStream first = new();
        new XmpSerializer().Serialize(metadata, first, withXpacket: true);
        byte[] firstBytes = first.ToArray();
        File.WriteAllBytes(
            Path.Combine(
                Path.GetDirectoryName(trace)!,
                "generated-programmatic.xml"),
            firstBytes);
        TrackingMemoryStream second = new();
        new XmpSerializer().Serialize(metadata, second, withXpacket: true);
        byte[] secondBytes = second.ToArray();
        string serialized = Encoding.UTF8.GetString(firstBytes);
        Observe(
            "serialization",
            "deterministic-packet",
            Join(
                NormalizedSha256(firstBytes),
                NormalizedSha256(secondBytes),
                firstBytes.SequenceEqual(secondBytes),
                firstBytes.Length,
                StartsWithUtf8Bom(firstBytes),
                serialized.StartsWith(
                    "<?xpacket begin=\"\uFEFF\" id=\"packet-id\"?>",
                    StringComparison.Ordinal),
                serialized.EndsWith(
                    "<?xpacket end=\"r\"?>",
                    StringComparison.Ordinal) ||
                    serialized.TrimEnd().EndsWith(
                        "<?xpacket end=\"r\"?>",
                        StringComparison.Ordinal),
                !serialized.Contains("<?xml", StringComparison.Ordinal),
                serialized.EndsWith("\n", StringComparison.Ordinal)));
        Observe(
            "serialization",
            "escaping-and-order",
            Join(
                serialized.Contains(
                    "A &lt;tag&gt; &amp; \"quote\"",
                    StringComparison.Ordinal),
                serialized.IndexOf(">first<", StringComparison.Ordinal) <
                    serialized.IndexOf(">second<", StringComparison.Ordinal),
                serialized.IndexOf("x-default", StringComparison.Ordinal) <
                    serialized.IndexOf("fr-FR", StringComparison.Ordinal),
                serialized.Contains(
                    "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"",
                    StringComparison.Ordinal)));
        Observe(
            "serialization",
            "programmatic-bytes",
            Convert.ToBase64String(firstBytes));

        using MemoryStream noPacket = new();
        new XmpSerializer().Serialize(metadata, noPacket, withXpacket: false);
        string noPacketXml = Encoding.UTF8.GetString(noPacket.ToArray());
        Observe(
            "serialization",
            "without-packet",
            Join(
                !noPacketXml.Contains("xpacket", StringComparison.Ordinal),
                noPacketXml.StartsWith("<x:xmpmeta", StringComparison.Ordinal),
                NormalizedSha256(noPacket.ToArray())));

        XMPMetadata roundTrip = new DomXmpParser().Parse(SignedBytes(firstBytes));
        Observe(
            "round-trip",
            "programmatic",
            Join(
                roundTrip.GetAllSchemas().Count,
                roundTrip.GetDublinCoreSchema().GetTitle(),
                roundTrip.GetDublinCoreSchema().GetTitle("fr-FR"),
                string.Join(",", roundTrip.GetDublinCoreSchema().GetCreators()),
                roundTrip.GetEndXPacket()));

        string[] fixturePaths =
        [
            "org/apache/xmpbox/parser/structured_recursive.xml",
            "org/apache/xmpbox/parser/empty_list.xml",
            "org/apache/xmpbox/parser/AltBagSeqTest.xml",
            "org/apache/xmpbox/parser/ThumbisartorStyle.xml",
            "validxmp/attr_as_props.xml",
            "validxmp/only_space_fields.xmp"
        ];
        foreach (string path in fixturePaths)
        {
            XMPMetadata parsed = ParseFile(resources, path, strict: true);
            using MemoryStream output = new();
            new XmpSerializer().Serialize(parsed, output, withXpacket: true);
            File.WriteAllBytes(
                Path.Combine(
                    Path.GetDirectoryName(trace)!,
                    "generated-" + path.Replace('/', '_')),
                output.ToArray());
            XMPMetadata reparsed =
                new DomXmpParser().Parse(SignedBytes(output.ToArray()));
            Observe(
                "round-trip",
                path,
                Join(
                    NormalizedSha256(output.ToArray()),
                    parsed.GetAllSchemas().Count,
                    reparsed.GetAllSchemas().Count));
        }
    }

    private static void ObserveXmlSecurityAndLifetimes()
    {
        string internalEntity =
            "<?xpacket begin=\"\" id=\"id\"?>" +
            "<!DOCTYPE x:xmpmeta [<!ENTITY injected \"boom\">]>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\">&injected;</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        string externalEntity =
            "<?xpacket begin=\"\" id=\"id\"?>" +
            "<!DOCTYPE x:xmpmeta [<!ENTITY injected SYSTEM \"file:///etc/passwd\">]>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\">&injected;</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        Observe(
            "security",
            "doctype-and-entities",
            Join(
                ParsingFailure(
                    () =>
                        new DomXmpParser().Parse(
                            SignedBytes(Encoding.UTF8.GetBytes(internalEntity)))),
                ParsingFailure(
                    () =>
                        new DomXmpParser().Parse(
                            SignedBytes(Encoding.UTF8.GetBytes(externalEntity))))));

        TrackingMemoryStream successInput =
            new(Encoding.UTF8.GetBytes(Packet("")));
        new DomXmpParser().Parse(successInput);
        TrackingMemoryStream failureInput =
            new(Encoding.UTF8.GetBytes("<broken"));
        _ = ParsingFailure(() => new DomXmpParser().Parse(failureInput));
        TrackingMemoryStream output = new();
        new XmpSerializer().Serialize(
            XMPMetadata.CreateXMPMetadata(),
            output,
            withXpacket: true);
        Observe(
            "lifetime",
            "caller-owned-streams",
            Join(
                !successInput.Closed,
                !failureInput.Closed,
                !output.Closed));
    }

    private static XMPMetadata ParseFile(
        string resources,
        string path,
        bool strict)
    {
        DomXmpParser parser = new();
        parser.SetStrictParsing(strict);
        using FileStream input =
            File.OpenRead(Path.Combine(resources, path.Replace('/', Path.DirectorySeparatorChar)));
        return parser.Parse(input);
    }

    private static string Packet(string descriptions)
    {
        return
            "<?xpacket begin=\"\" id=\"id\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            descriptions +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
    }

    private static string ParsingFailure(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (XmpParsingException exception)
        {
            return exception.GetErrorType().ToString();
        }
        catch (Exception exception)
        {
            return exception.GetType().Name;
        }
    }

    private static string NormalizedSha256(byte[] bytes)
    {
        string normalized =
            Encoding.UTF8.GetString(bytes).Replace("\r\n", "\n", StringComparison.Ordinal);
        return Convert.ToHexString(
            SHA256.HashData(Encoding.UTF8.GetBytes(normalized)));
    }

    private static sbyte[] SignedBytes(byte[] bytes)
    {
        sbyte[] result = new sbyte[bytes.Length];
        Buffer.BlockCopy(bytes, 0, result, 0, bytes.Length);
        return result;
    }

    private static bool StartsWithUtf8Bom(byte[] bytes)
    {
        return bytes.Length >= 3 &&
            bytes[0] == 0xEF &&
            bytes[1] == 0xBB &&
            bytes[2] == 0xBF;
    }

    private sealed class TrackingMemoryStream : MemoryStream
    {
        internal TrackingMemoryStream()
        {
        }

        internal TrackingMemoryStream(byte[] bytes)
            : base(bytes)
        {
        }

        internal bool Closed { get; private set; }

        protected override void Dispose(bool disposing)
        {
            Closed = true;
            base.Dispose(disposing);
        }
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

    private static void ValidateCanonical(string canonicalPath)
    {
        string[] expected = File.ReadAllLines(canonicalPath, Encoding.UTF8);
        if (!expected.SequenceEqual(Observations, StringComparer.Ordinal))
        {
            int mismatch =
                Enumerable.Range(
                        0,
                        Math.Max(expected.Length, Observations.Count))
                    .First(
                        index =>
                            index >= expected.Length ||
                            index >= Observations.Count ||
                            !string.Equals(
                                expected[index],
                                Observations[index],
                                StringComparison.Ordinal));
            throw new InvalidOperationException(
                $"Canonical differential mismatch at line {mismatch + 1}: " +
                $"expected `{At(expected, mismatch)}`, " +
                $"observed `{At(Observations, mismatch)}`.");
        }
    }

    private static void ValidateHost(
        string expectedOs,
        string expectedArchitecture)
    {
        bool osMatches = expectedOs switch
        {
            "linux" => RuntimeInformation.IsOSPlatform(OSPlatform.Linux),
            "windows" => RuntimeInformation.IsOSPlatform(OSPlatform.Windows),
            "macos" => RuntimeInformation.IsOSPlatform(OSPlatform.OSX),
            _ => false
        };
        bool architectureMatches = expectedArchitecture switch
        {
            "x64" => RuntimeInformation.ProcessArchitecture == Architecture.X64,
            "arm64" => RuntimeInformation.ProcessArchitecture == Architecture.Arm64,
            _ => false
        };
        if (!osMatches || !architectureMatches)
        {
            throw new InvalidOperationException(
                $"Expected {expectedOs}/{expectedArchitecture}, observed " +
                $"{RuntimeInformation.OSDescription}/" +
                $"{RuntimeInformation.ProcessArchitecture}.");
        }
        Console.WriteLine(
            $"DripSharp.PdfCarton.Xmp host smoke passed: " +
            $"{expectedOs}/{expectedArchitecture}.");
    }

    private static string At(IReadOnlyList<string> values, int index)
    {
        return index < values.Count ? values[index] : "<missing>";
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
