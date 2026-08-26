using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Preflight;
using DripSharp.PdfCarton.Preflight.Parser;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public sealed class ReleaseSmokePreflightTests
{
    [Fact]
    public void OrdinaryPdfRunsThroughPreflightAndReportsNotPdfA()
    {
        string file = Path.Combine(
            Path.GetTempPath(),
            $"pdfcarton-release-preflight-{Guid.NewGuid():N}.pdf");
        try
        {
            using (var source = new PDDocument())
            {
                source.AddPage(new PDPage());
                source.Save(file);
            }

            var input = new RandomAccessReadBufferedFile(new FileInfo(file));
            using (var document =
                   (PreflightDocument)new PreflightParser(input).Parse())
            {
                ValidationResult result = document.Validate();
                Assert.False(result.IsValid());
                Assert.NotEmpty(result.GetErrorsList());
                Assert.All(
                    result.GetErrorsList(),
                    error => Assert.False(string.IsNullOrWhiteSpace(error.GetErrorCode())));
            }
            Assert.True(input.IsClosed());
        }
        finally
        {
            File.Delete(file);
        }
    }
}
