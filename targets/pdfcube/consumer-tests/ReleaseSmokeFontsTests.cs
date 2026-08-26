using DripSharp.PdfCarton.Fonts.Ttf;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public sealed class ReleaseSmokeFontsTests
{
    [Fact]
    public void EmbeddedLiberationSansLoadsThroughThePublicFontParser()
    {
        using Stream resource = typeof(PDDocument).Assembly.GetManifestResourceStream(
            "org.apache.pdfbox.resources.ttf.LiberationSans-Regular.ttf")
            ?? throw new InvalidOperationException("PdfCarton font resource is missing.");
        using TrueTypeFont font =
            new TTFParser().Parse(new RandomAccessReadBuffer(resource));

        Assert.Equal("LiberationSans", font.GetName());
        Assert.Equal(2048, font.GetUnitsPerEm());
        Assert.True(font.GetNumberOfGlyphs() > 1000);
        Assert.NotNull(font.GetCmap());
    }
}
