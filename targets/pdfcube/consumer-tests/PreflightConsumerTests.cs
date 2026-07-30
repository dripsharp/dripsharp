using System.Text;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Preflight;
using DripSharp.PdfCarton.Preflight.Exception;
using DripSharp.PdfCarton.Preflight.Parser;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class PreflightConsumerTests
{
    [Fact]
    public void PublicValidatorReportsGapsAndRejectsMalformedInput()
    {
        string root = Path.Combine(
            Path.GetTempPath(),
            "pdfcarton-preflight-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string ordinary = Path.Combine(root, "ordinary.pdf");
            using (var source = new PDDocument())
            {
                source.AddPage(new PDPage());
                source.Save(ordinary);
            }

            var input = new RandomAccessReadBufferedFile(new FileInfo(ordinary));
            using (var document =
                   (PreflightDocument)new PreflightParser(input).Parse())
            {
                ValidationResult result = document.Validate();
                Assert.False(result.IsValid());
                Assert.NotEmpty(result.GetErrorsList());
            }
            Assert.True(input.IsClosed());

            string malformed = Path.Combine(root, "malformed.pdf");
            File.WriteAllText(malformed, "%PDF-1.4\nbroken", Encoding.ASCII);
            using var malformedInput =
                new RandomAccessReadBufferedFile(new FileInfo(malformed));
            var error = Assert.Throws<SyntaxValidationException>(
                () => new PreflightParser(malformedInput).Parse());
            Assert.False(error.GetResult().IsValid());
            Assert.NotEmpty(error.GetResult().GetErrorsList());
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
