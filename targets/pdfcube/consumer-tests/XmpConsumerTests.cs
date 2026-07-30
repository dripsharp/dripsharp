using System.Text;
using DripSharp.PdfCarton.Xmp.Xml;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class XmpConsumerTests
{
    [Fact]
    public void MetadataFixtureParsesMutatesAndRoundTrips()
    {
        string fixture = Path.Combine(
            AppContext.BaseDirectory, "Fixtures", "metadata.xmp");
        var input = File.OpenRead(fixture);
        var metadata = new DomXmpParser().Parse(input);
        Assert.False(input.CanRead);

        var dublinCore = metadata.GetDublinCoreSchema();
        Assert.NotNull(dublinCore);
        Assert.Equal(
            "PdfCarton consumer fixture",
            dublinCore!.GetTitle("x-default"));

        dublinCore.SetTitle("en-US", "Generated repository");
        using var output = new MemoryStream();
        new XmpSerializer().Serialize(metadata, output, withXpacket: true);
        string xml = Encoding.UTF8.GetString(output.ToArray());
        Assert.Contains("Generated repository", xml);

        var reparsed =
            new DomXmpParser().Parse(new MemoryStream(output.ToArray()));
        Assert.Equal(
            "Generated repository",
            reparsed.GetDublinCoreSchema()!.GetTitle("en-US"));
    }
}
