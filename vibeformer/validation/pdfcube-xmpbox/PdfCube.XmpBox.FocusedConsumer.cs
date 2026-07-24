#nullable enable

using System;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.XmpBox;
using PdfCube.XmpBox.Xml;

internal static class Program
{
    private static void Main()
    {
        var metadata = XMPMetadata.CreateXMPMetadata(
            "\uFEFF",
            "package-consumer",
            "4096",
            "UTF-8");
        metadata.SetEndXPacket("r");
        var dublinCore = metadata.CreateAndAddDublinCoreSchema();
        dublinCore.SetTitle("x-default", "Translated <XMP> & \"metadata\"");
        dublinCore.SetTitle("en-US", "Translated XMP");
        dublinCore.AddCreator("Vibeformer first");
        dublinCore.AddCreator("Vibeformer second");

        using var first = new MemoryStream();
        new XmpSerializer().Serialize(metadata, first, withXpacket: true);
        Assert(first.CanWrite,
            "XMP serialization must leave the caller's output stream open.");
        using var second = new MemoryStream();
        new XmpSerializer().Serialize(metadata, second, withXpacket: true);
        var firstBytes = first.ToArray();
        var secondBytes = second.ToArray();
        Assert(firstBytes.SequenceEqual(secondBytes),
            "XMP serialization must be deterministic for unchanged metadata.");
        Assert(!StartsWithUtf8Bom(firstBytes),
            "UTF-8 XMP serialization must not prepend a stream BOM.");

        var xml = Encoding.UTF8.GetString(firstBytes);
        Assert(xml.StartsWith(
                "<?xpacket begin=\"\uFEFF\" id=\"package-consumer\"?>",
                StringComparison.Ordinal),
            "XMP serialization must preserve the packet start.");
        Assert(xml.TrimEnd().EndsWith(
                "<?xpacket end=\"r\"?>",
                StringComparison.Ordinal) &&
               xml.EndsWith("\n", StringComparison.Ordinal),
            "XMP serialization must preserve the packet end and trailing padding.");
        Assert(!xml.Contains("<?xml", StringComparison.Ordinal),
            "XMP serialization must omit the XML declaration.");
        Assert(xml.Contains(
                "Translated &lt;XMP&gt; &amp; \"metadata\"",
                StringComparison.Ordinal),
            "XMP serialization must escape XML text without over-escaping quotes.");
        Assert(
            xml.IndexOf("Vibeformer first", StringComparison.Ordinal) <
            xml.IndexOf("Vibeformer second", StringComparison.Ordinal),
            "XMP serialization must preserve array ordering.");
        Assert(xml.Contains("http://purl.org/dc/elements/1.1/", StringComparison.Ordinal),
            "XMP serialization must preserve schema namespaces.");

        var serializedInput = new MemoryStream(firstBytes);
        var parsed = new DomXmpParser().Parse(serializedInput);
        Assert(!serializedInput.CanRead,
            "DOM parsing must close the consumed input stream like upstream.");
        var parsedDublinCore = parsed.GetDublinCoreSchema();
        Assert(parsedDublinCore is not null,
            "DOM parsing must reconstruct the Dublin Core schema.");
        Assert(string.Equals(
                parsedDublinCore!.GetTitle("en-US"),
                "Translated XMP",
                StringComparison.Ordinal),
            "DOM parsing must preserve language-qualified text.");
        Assert(parsedDublinCore.GetCreators().Count == 2 &&
               string.Equals(
                   parsedDublinCore.GetCreators()[0],
                   "Vibeformer first",
                   StringComparison.Ordinal) &&
               string.Equals(
                   parsedDublinCore.GetCreators()[1],
                   "Vibeformer second",
                   StringComparison.Ordinal),
            "DOM parsing must preserve ordered collection values.");

        parsedDublinCore.SetTitle("en-US", "Mutated package metadata");
        parsedDublinCore.RemoveCreator("Vibeformer first");
        Assert(string.Equals(
                   parsedDublinCore.GetTitle("en-US"),
                   "Mutated package metadata",
                   StringComparison.Ordinal) &&
               parsedDublinCore.GetCreators().Count == 1 &&
               string.Equals(
                   parsedDublinCore.GetCreators()[0],
                   "Vibeformer second",
                   StringComparison.Ordinal),
            "Parsed metadata must support property replacement and removal.");
        AssertBadFieldFailure(
            () => parsedDublinCore.SetAbout(
                new PdfCube.XmpBox.Type.Attribute(
                    "urn:pdfcube:invalid",
                    "not-about",
                    "value")),
            "Schema validation must reject an invalid rdf:about attribute.");
        using var mutatedOutput = new MemoryStream();
        new XmpSerializer().Serialize(parsed, mutatedOutput, withXpacket: true);
        var reparsedMutation =
            new DomXmpParser().Parse(new MemoryStream(mutatedOutput.ToArray()));
        Assert(string.Equals(
                   reparsedMutation.GetDublinCoreSchema()!.GetTitle("en-US"),
                   "Mutated package metadata",
                   StringComparison.Ordinal) &&
               reparsedMutation.GetDublinCoreSchema()!.GetCreators().Count == 1,
            "Mutated metadata must survive a package-only serialization round trip.");

        using var withoutPacket = new MemoryStream();
        new XmpSerializer().Serialize(metadata, withoutPacket, withXpacket: false);
        var withoutPacketBytes = withoutPacket.ToArray();
        var withoutPacketXml = Encoding.UTF8.GetString(withoutPacketBytes);
        Assert(!StartsWithUtf8Bom(withoutPacketBytes) &&
               withoutPacketXml.StartsWith("<x:xmpmeta", StringComparison.Ordinal) &&
               !withoutPacketXml.Contains("xpacket", StringComparison.Ordinal),
            "XMP serialization without a packet must begin at x:xmpmeta.");

        const string extensionPacket =
            "<?xpacket begin=\"\" id=\"extension\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"" +
            " xmlns:pdfaExtension=\"http://www.aiim.org/pdfa/ns/extension/\"" +
            " xmlns:pdfaSchema=\"http://www.aiim.org/pdfa/ns/schema#\"" +
            " xmlns:pdfaProperty=\"http://www.aiim.org/pdfa/ns/property#\">" +
            "<pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType=\"Resource\">" +
            "<pdfaSchema:schema>Package Extension</pdfaSchema:schema>" +
            "<pdfaSchema:namespaceURI>urn:pdfcube:package:extension/</pdfaSchema:namespaceURI>" +
            "<pdfaSchema:prefix>pcx</pdfaSchema:prefix>" +
            "<pdfaSchema:property><rdf:Seq><rdf:li rdf:parseType=\"Resource\">" +
            "<pdfaProperty:name>sample</pdfaProperty:name>" +
            "<pdfaProperty:valueType>Text</pdfaProperty:valueType>" +
            "<pdfaProperty:category>external</pdfaProperty:category>" +
            "<pdfaProperty:description>Package sample</pdfaProperty:description>" +
            "</rdf:li></rdf:Seq></pdfaSchema:property>" +
            "</rdf:li></rdf:Bag></pdfaExtension:schemas></rdf:Description>" +
            "<rdf:Description rdf:about=\"\"" +
            " xmlns:pcx=\"urn:pdfcube:package:extension/\">" +
            "<pcx:sample>extension value</pcx:sample></rdf:Description>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        var extensionInput = new MemoryStream(Encoding.UTF8.GetBytes(extensionPacket));
        var extension = new DomXmpParser().Parse(extensionInput);
        Assert(string.Equals(
                extension
                    .GetSchema("urn:pdfcube:package:extension/")
                    .GetUnqualifiedTextPropertyValue("sample"),
                "extension value",
                StringComparison.Ordinal),
            "DOM parsing must register and consume PDF/A extension schemas.");
        using var extensionOutput = new MemoryStream();
        new XmpSerializer().Serialize(extension, extensionOutput, withXpacket: true);
        var extensionRoundTrip =
            new DomXmpParser().Parse(new MemoryStream(extensionOutput.ToArray()));
        Assert(string.Equals(
                extensionRoundTrip
                    .GetSchema("urn:pdfcube:package:extension/")
                    .GetUnqualifiedTextPropertyValue("sample"),
                "extension value",
                StringComparison.Ordinal),
            "PDF/A extension schemas must survive serialization round trips.");

        AssertParsingFailure(
            "<broken",
            XmpParsingException.ErrorType.Undefined,
            "Malformed XML must be rejected.");
        AssertParsingFailure(
            "<?xpacket begin=\"\" id=\"security\"?>" +
            "<!DOCTYPE x:xmpmeta [<!ENTITY injected SYSTEM \"file:///etc/passwd\">]>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\">&injected;</rdf:Description>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>",
            XmpParsingException.ErrorType.Undefined,
            "DTD and external-entity input must be rejected.");

        Console.WriteLine("PdfCube.XmpBox focused behavior passed.");
    }

    private static void AssertParsingFailure(
        string xml,
        XmpParsingException.ErrorType expected,
        string message)
    {
        var input = new MemoryStream(Encoding.UTF8.GetBytes(xml));
        try
        {
            _ = new DomXmpParser().Parse(input);
            throw new InvalidOperationException(message);
        }
        catch (XmpParsingException exception)
        {
            Assert(ReferenceEquals(exception.GetErrorType(), expected), message);
            Assert(!input.CanRead,
                "Failed DOM parsing must still close the consumed input stream.");
        }
    }

    private static void AssertBadFieldFailure(Action action, string message)
    {
        try
        {
            action();
            throw new InvalidOperationException(message);
        }
        catch (PdfCube.XmpBox.Type.BadFieldValueException)
        {
        }
    }

    private static bool StartsWithUtf8Bom(byte[] bytes)
    {
        return bytes.Length >= 3 &&
               bytes[0] == 0xEF &&
               bytes[1] == 0xBB &&
               bytes[2] == 0xBF;
    }

    private static void Assert(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
