using System.Text;
using DripSharp.PdfCarton.Xmp.Xml;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public sealed class ReleaseSmokeXmpTests
{
    [Fact]
    public void MetadataParsesSerializesAndRoundTrips()
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

        dublinCore.SetTitle("en-US", "PdfCarton release smoke");
        using var serialized = new MemoryStream();
        new XmpSerializer().Serialize(metadata, serialized, withXpacket: true);
        byte[] bytes = serialized.ToArray();
        Assert.Contains(
            "PdfCarton release smoke",
            Encoding.UTF8.GetString(bytes));

        var reparsed = new DomXmpParser().Parse(new MemoryStream(bytes));
        Assert.Equal(
            "PdfCarton release smoke",
            reparsed.GetDublinCoreSchema()!.GetTitle("en-US"));
    }
}
