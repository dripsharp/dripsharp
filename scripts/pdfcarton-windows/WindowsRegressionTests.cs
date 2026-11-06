using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Fonts.Util.Autodetect;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Rendering;
using DripSharp.PdfCarton.Text;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public class WindowsRegressionTests
{
    [Fact]
    public void InstalledFontsRoundtripThroughDiscoveryAndRenderPdf()
    {
        Assert.True(OperatingSystem.IsWindows() && Environment.Is64BitProcess);
        var discovered = new FontFileFinder().Find();
        Assert.NotEmpty(discovered);
        var source = discovered.Select(uri => uri.LocalPath)
            .First(path => Path.GetExtension(path).Equals(".ttf", StringComparison.OrdinalIgnoreCase));
        string root = Path.Combine(Path.GetTempPath(), "pdfcarton-windows-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            foreach (string name in new[] { "plain.ttf", "with spaces.ttf", "100%real.ttf", "unicode-é-漢.ttf" })
            {
                string dir = Path.Combine(root, Path.GetFileNameWithoutExtension(name));
                Directory.CreateDirectory(dir);
                string file = Path.Combine(dir, name);
                File.Copy(source, file);
                Uri uri = Assert.Single(new FontFileFinder().Find(dir));
                Assert.Equal(file, uri.LocalPath);
            }
            Assert.NotNull(FontMappers.Instance().GetTrueTypeFont("Arial", null).GetFont());
            string pdf = Path.Combine(root, "discovered-font.pdf");
            using (var document = new PDDocument())
            {
                var page = new PDPage();
                document.AddPage(page);
                var font = PDType0Font.Load(document, new FileInfo(source));
                using (var content = new PDPageContentStream(document, page))
                {
                    content.BeginText();
                    content.SetFont(font, 12);
                    content.NewLineAtOffset(40, 700);
                    content.ShowText("Windows discovered font");
                    content.EndText();
                }
                document.Save(pdf);
            }
            using var reopened = Loader.LoadPDF(new FileInfo(pdf));
            Assert.Contains("Windows discovered font", new PDFTextStripper().GetText(reopened));
            var image = new PDFRenderer(reopened).RenderImage(0);
            Assert.True(image.Width > 0 && image.Height > 0);
            Console.WriteLine($"Windows discovered {discovered.Count} fonts; PDF saved, reloaded and rendered with native Skia.");
        }
        finally { Directory.Delete(root, true); }
    }
}
