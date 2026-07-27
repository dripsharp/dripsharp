#nullable enable

using System;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.FontBox.Util;
using PdfCube.IO;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Common;
using PdfCube.Preflight;
using PdfCube.Preflight.Exception;
using PdfCube.Preflight.Parser;
using PdfCube.XmpBox;
using PdfCube.XmpBox.Xml;

internal static class Program
{
    private static void Main()
    {
        var root = Path.Combine(
            Path.GetTempPath(),
            "pdfcube-family-consumer-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(root);
            VerifyIoAndFontBox();
            var xmp = CreateAndRoundTripXmp();
            var pdf = CreateEditAndReopenPdf(root, xmp);
            ValidateAndRejectMalformedPdf(root, pdf);
            Console.WriteLine(
                "Complete PdfCube package family runtime workflow passed.");
        }
        finally
        {
            if (Directory.Exists(root))
                Directory.Delete(root, recursive: true);
        }
    }

    private static void VerifyIoAndFontBox()
    {
        using var input =
            new RandomAccessReadBuffer(new sbyte[] { 1, -2, 3 });
        Assert(
            input.Read() == 1 &&
            input.Read() == 254 &&
            input.Read() == 3 &&
            input.Read() == -1,
            "PdfCube.IO random access must preserve unsigned reads and EOF.");

        var bounds = new BoundingBox(1, 2, 6, 10);
        Assert(
            bounds.GetWidth() == 5 &&
            bounds.GetHeight() == 8 &&
            bounds.Contains(3, 4) &&
            !bounds.Contains(7, 4),
            "PdfCube.FontBox must consume PdfCube.IO and preserve geometry.");
    }

    private static byte[] CreateAndRoundTripXmp()
    {
        var metadata = XMPMetadata.CreateXMPMetadata(
            "\uFEFF",
            "family-consumer",
            "4096",
            "UTF-8");
        metadata.SetEndXPacket("r");
        var dublinCore = metadata.CreateAndAddDublinCoreSchema();
        dublinCore.SetTitle("x-default", "PdfCube family metadata");
        dublinCore.AddCreator("DripSharp");

        using var serialized = new MemoryStream();
        new XmpSerializer().Serialize(metadata, serialized, withXpacket: true);
        var bytes = serialized.ToArray();
        var parsedInput = new MemoryStream(bytes);
        var parsed = new DomXmpParser().Parse(parsedInput);
        Assert(
            !parsedInput.CanRead,
            "PdfCube.XmpBox parsing must close its consumed input.");
        Assert(
            string.Equals(
                parsed.GetDublinCoreSchema()?.GetTitle("x-default"),
                "PdfCube family metadata",
                StringComparison.Ordinal),
            "PdfCube.XmpBox metadata must survive serialization and parsing.");
        return bytes;
    }

    private static string CreateEditAndReopenPdf(string root, byte[] xmp)
    {
        var first = Path.Combine(root, "created.pdf");
        var edited = Path.Combine(root, "edited.pdf");
        using (var document = new PDDocument())
        {
            document.GetDocumentInformation().SetTitle("Created");
            document.GetDocumentCatalog().SetLanguage("en-US");
            document.GetDocumentCatalog().SetMetadata(
                new PDMetadata(document, new MemoryStream(xmp)));
            document.AddPage(new PDPage(PDRectangle.A4));
            document.Save(first);
        }

        using (var document = Loader.LoadPDF(new FileInfo(first)))
        {
            Assert(
                document.GetNumberOfPages() == 1 &&
                string.Equals(
                    document.GetDocumentInformation().GetTitle(),
                    "Created",
                    StringComparison.Ordinal) &&
                document.GetDocumentCatalog().GetMetadata() is not null,
                "PdfCube.PdfBox must reopen the created metadata-bearing PDF.");
            document.GetDocumentInformation().SetTitle("Edited");
            document.AddPage(new PDPage(new PDRectangle(320, 240)));
            document.Save(edited);
        }

        using (var document = Loader.LoadPDF(new FileInfo(edited)))
        {
            using var metadata =
                document.GetDocumentCatalog().GetMetadata()?.ExportXMPMetadata();
            var metadataText =
                metadata is null ? "" : ReadUtf8(metadata);
            Assert(
                document.GetNumberOfPages() == 2 &&
                string.Equals(
                    document.GetDocumentInformation().GetTitle(),
                    "Edited",
                    StringComparison.Ordinal) &&
                metadata is not null &&
                metadataText.Contains(
                    "PdfCube family metadata",
                    StringComparison.Ordinal),
                "PdfCube.PdfBox edits and XMP metadata must survive reopen.");
        }
        return edited;
    }

    private static void ValidateAndRejectMalformedPdf(
        string root,
        string pdf)
    {
        var source = new RandomAccessReadBufferedFile(new FileInfo(pdf));
        var parser = new PreflightParser(source);
        using (var document = (PreflightDocument)parser.Parse())
        {
            var result = document.Validate();
            Assert(
                !result.IsValid() &&
                result.GetErrorsList().Count > 0,
                "PdfCube.Preflight must validate the ordinary PDF and report PDF/A gaps.");
        }
        Assert(
            source.IsClosed(),
            "PdfCube.Preflight document disposal must close PdfCube.IO input.");

        var malformed = Path.Combine(root, "malformed.pdf");
        File.WriteAllText(
            malformed,
            "%PDF-1.4\nbroken",
            Encoding.ASCII);
        var malformedSource =
            new RandomAccessReadBufferedFile(new FileInfo(malformed));
        try
        {
            var malformedParser = new PreflightParser(malformedSource);
            try
            {
                _ = malformedParser.Parse();
                throw new InvalidOperationException(
                    "Malformed PDF input must fail Preflight parsing.");
            }
            catch (SyntaxValidationException error)
            {
                Assert(
                    !error.GetResult().IsValid() &&
                    error.GetResult().GetErrorsList().Count > 0,
                    "Malformed PDF failure must expose deterministic validation errors.");
            }
        }
        finally
        {
            malformedSource.Dispose();
        }
        Assert(
            malformedSource.IsClosed(),
            "Malformed PdfCube.IO input must support deterministic cleanup.");
    }

    private static string ReadUtf8(Stream input)
    {
        using var reader = new StreamReader(
            input,
            Encoding.UTF8,
            detectEncodingFromByteOrderMarks: true,
            leaveOpen: true);
        return reader.ReadToEnd();
    }

    private static void Assert(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
