#nullable enable

using System;
using System.IO;
using System.Text;
using PdfCube.XmpBox;
using PdfCube.XmpBox.Xml;

internal static class Program
{
    private static void Main()
    {
        var metadata = XMPMetadata.CreateXMPMetadata();
        var dublinCore = metadata.CreateAndAddDublinCoreSchema();
        dublinCore.SetTitle("en-US", "Translated XMP");
        dublinCore.AddCreator("Vibeformer");

        using var serialized = new MemoryStream();
        new XmpSerializer().Serialize(metadata, serialized, withXpacket: true);
        var xml = Encoding.UTF8.GetString(serialized.ToArray());
        Assert(xml.Contains("Translated XMP", StringComparison.Ordinal),
            "XMP serialization must preserve text properties.");
        Assert(xml.Contains("Vibeformer", StringComparison.Ordinal),
            "XMP serialization must preserve array properties.");
        Assert(xml.Contains("http://purl.org/dc/elements/1.1/", StringComparison.Ordinal),
            "XMP serialization must preserve schema namespaces.");

        serialized.Position = 0;
        var parsed = new DomXmpParser().Parse(serialized);
        var parsedDublinCore = parsed.GetDublinCoreSchema();
        Assert(parsedDublinCore is not null,
            "DOM parsing must reconstruct the Dublin Core schema.");
        Assert(string.Equals(
                parsedDublinCore!.GetTitle("en-US"),
                "Translated XMP",
                StringComparison.Ordinal),
            "DOM parsing must preserve language-qualified text.");
        Assert(parsedDublinCore.GetCreators().Count == 1 &&
               string.Equals(
                   parsedDublinCore.GetCreators()[0],
                   "Vibeformer",
                   StringComparison.Ordinal),
            "DOM parsing must preserve ordered collection values.");

        Console.WriteLine("PdfCube.XmpBox focused behavior passed.");
    }

    private static void Assert(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
