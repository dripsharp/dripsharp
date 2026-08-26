using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public sealed class ReleaseSmokePdfTests
{
    [Fact]
    public void DocumentCreationSaveAndReloadExposeObservableBehavior()
    {
        string file = Path.Combine(
            Path.GetTempPath(),
            $"pdfcarton-release-document-{Guid.NewGuid():N}.pdf");
        try
        {
            using (var document = new PDDocument())
            {
                document.GetDocumentInformation().SetTitle(
                    "PdfCarton release smoke");
                document.AddPage(new PDPage(PDRectangle.A4));
                document.Save(file);
            }

            Assert.True(new FileInfo(file).Length > 100);
            using var reopened = Loader.LoadPDF(new FileInfo(file));
            Assert.Equal(1, reopened.GetNumberOfPages());
            Assert.Equal(
                "PdfCarton release smoke",
                reopened.GetDocumentInformation().GetTitle());
            Assert.Equal(PDRectangle.A4.GetWidth(), reopened.GetPage(0).GetMediaBox().GetWidth());
            Assert.Equal(PDRectangle.A4.GetHeight(), reopened.GetPage(0).GetMediaBox().GetHeight());
        }
        finally
        {
            File.Delete(file);
        }
    }
}
