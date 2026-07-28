using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.IO;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Pdfwriter.Compress;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Common;
using PdfCube.PdfBox.Pdmodel.Graphics.State;

internal static class Program
{
    private static readonly List<string> Observations = new();
    private static string exchange = null!;

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 2 or > 3)
                throw new ArgumentException(
                    "Expected output trace, exchange directory, and optional --write-only.");
            if (args.Length == 3 && args[2] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            exchange = args[1];
            Directory.CreateDirectory(exchange);
            WriteRepresentative(Path.Combine(exchange, "dotnet-lifecycle.pdf"));
            if (args.Length == 3)
                return 0;

            ObserveCatalogAndInformation();
            ObservePageInheritance();
            ObservePageTreeMutation();
            ObserveMalformedPageTrees();
            ObserveResources();
            ObserveContentStreams();
            ObserveImport();
            ObserveScratchAndStreamLifetime();
            ObserveLifecycleFailures();
            ObserveCrossRuntimeReopen(
                Path.Combine(exchange, "java-lifecycle.pdf"));

            File.WriteAllLines(output, Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void WriteRepresentative(string path)
    {
        using var document = new PDDocument();
        var info = document.GetDocumentInformation();
        info.SetTitle("Lifecycle Contract");
        info.SetAuthor("Apache PDFBox");
        info.SetCustomMetadataValue("Probe", "document-model");
        info.SetCreationDate(
            new DateTimeOffset(2020, 5, 4, 3, 2, 1, TimeSpan.FromHours(-7)));
        info.SetTrapped("Unknown");
        document.GetDocumentCatalog().SetLanguage("en-US");
        document.SetVersion(1.5f);

        var first = new PDPage(PDRectangle.A4);
        first.SetRotation(90);
        document.AddPage(first);
        using (var content = new PDPageContentStream(
                   document, first, PDPageContentStream.AppendMode.Overwrite, false))
        {
            content.AppendRawCommands("0 0 m\n10 10 l\nS\n");
        }

        var second = new PDPage(new PDRectangle(320, 240));
        second.SetResources(new PDResources());
        document.AddPage(second);
        document.Save(new FileInfo(path), CompressParameters.NoCompression);
    }

    private static void ObserveCatalogAndInformation()
    {
        using var document = new PDDocument();
        var catalog = document.GetDocumentCatalog();
        var catalogAgain = document.GetDocumentCatalog();
        var pages = catalog.GetPages();
        Observe(
            "catalog",
            "default-and-caching",
            ReferenceEquals(catalog, catalogAgain),
            catalog.GetCOSObject().GetCOSName(COSName.Type)?.GetName(),
            pages.GetCOSObject().GetCOSName(COSName.Type)?.GetName(),
            pages.GetCount(),
            document.GetNumberOfPages());

        catalog.SetLanguage("fr-CA");
        Observe(
            "catalog",
            "mutation",
            catalog.GetLanguage(),
            ReferenceEquals(pages.GetCOSObject(), document.GetPages().GetCOSObject()));

        var info = document.GetDocumentInformation();
        info.SetTitle("Title");
        info.SetAuthor("Author");
        info.SetSubject("Subject");
        info.SetKeywords("one two");
        info.SetCreator("Creator");
        info.SetProducer("Producer");
        info.SetCustomMetadataValue("Custom", "Value");
        info.SetTrapped("True");
        var date =
            new DateTimeOffset(2021, 2, 3, 4, 5, 6, TimeSpan.FromHours(5.5));
        info.SetCreationDate(date);
        info.SetModificationDate(date.AddMinutes(7));
        Observe(
            "document-info",
            "roundtrip",
            ReferenceEquals(info, document.GetDocumentInformation()),
            info.GetTitle(),
            info.GetAuthor(),
            info.GetSubject(),
            info.GetKeywords(),
            info.GetCreator(),
            info.GetProducer(),
            info.GetCustomMetadataValue("Custom"),
            info.GetTrapped(),
            info.GetCreationDate().ToUnixTimeMilliseconds(),
            info.GetCreationDate().Offset.TotalMinutes,
            info.GetModificationDate().ToUnixTimeMilliseconds(),
            info.GetModificationDate().Offset.TotalMinutes,
            string.Join(",", info.GetMetadataKeys().OrderBy(value => value)));

        info.SetTitle(null!);
        info.SetCustomMetadataValue("Custom", null!);
        info.SetTrapped(null!);
        Observe(
            "document-info",
            "null-and-validation",
            info.GetTitle() is null,
            info.GetCustomMetadataValue("Custom") is null,
            info.GetTrapped() is null,
            Fails<ArgumentException>(() => info.SetTrapped("Maybe")));

        Observe(
            "document-version",
            "upgrade-and-downgrade",
            document.GetVersion(),
            document.GetDocument().GetVersion(),
            catalog.GetVersion());
        document.SetVersion(1.3f);
        var afterDowngrade = document.GetVersion();
        document.SetVersion(1.7f);
        Observe(
            "document-version",
            "upgrade-and-downgrade-result",
            afterDowngrade,
            document.GetVersion(),
            document.GetDocument().GetVersion(),
            catalog.GetVersion());
    }

    private static void ObservePageInheritance()
    {
        var parent = new COSDictionary();
        parent.SetItem(COSName.Type, COSName.Pages);
        parent.SetItem(COSName.MediaBox, new PDRectangle(10, 20, 300, 400));
        parent.SetItem(COSName.CropBox, new PDRectangle(20, 30, 250, 350));
        parent.SetInt(COSName.Rotate, -90);
        var resourceDictionary = new COSDictionary();
        parent.SetItem(COSName.Resources, resourceDictionary);

        var pageDictionary = new COSDictionary();
        pageDictionary.SetItem(COSName.Type, COSName.Page);
        pageDictionary.SetItem(COSName.Parent, parent);
        var page = new PDPage(pageDictionary);
        var media = page.GetMediaBox();
        var crop = page.GetCropBox();
        Observe(
            "page-inheritance",
            "boxes-rotation-resources",
            media.GetLowerLeftX(),
            media.GetLowerLeftY(),
            media.GetWidth(),
            media.GetHeight(),
            crop.GetLowerLeftX(),
            crop.GetLowerLeftY(),
            crop.GetWidth(),
            crop.GetHeight(),
            page.GetRotation(),
            ReferenceEquals(resourceDictionary, page.GetResources().GetCOSObject()));

        var cyclePage = new COSDictionary();
        cyclePage.SetItem(COSName.Type, COSName.Page);
        var cycleParent = new COSDictionary();
        cycleParent.SetItem(COSName.Type, COSName.Pages);
        cyclePage.SetItem(COSName.Parent, cycleParent);
        cycleParent.SetItem(COSName.Parent, cyclePage);
        var loopPage = new PDPage(cyclePage);
        Observe(
            "page-inheritance",
            "cycle-and-default",
            loopPage.GetResources() is null,
            loopPage.GetMediaBox().GetWidth(),
            loopPage.GetMediaBox().GetHeight());
    }

    private static void ObservePageTreeMutation()
    {
        using var document = new PDDocument();
        var one = NamedPage("one");
        var two = NamedPage("two");
        var three = NamedPage("three");
        var four = NamedPage("four");
        document.AddPage(one);
        document.AddPage(two);
        document.GetPages().InsertBefore(three, two);
        document.GetPages().InsertAfter(four, two);

        Observe(
            "page-tree-mutation",
            "insert-and-iterate",
            document.GetPages().GetCount(),
            PageOrder(document.GetPages()),
            document.GetPages().IndexOf(one),
            document.GetPages().IndexOf(three),
            document.GetPages().IndexOf(two),
            document.GetPages().IndexOf(four),
            document.GetPages().IndexOf(new PDPage()));

        document.RemovePage(three);
        document.RemovePage(0);
        Observe(
            "page-tree-mutation",
            "remove",
            document.GetNumberOfPages(),
            PageOrder(document.GetPages()),
            document.GetPage(0).GetCOSObject()
                .GetString(COSName.GetPDFName("Probe")));
    }

    private static void ObserveMalformedPageTrees()
    {
        var root = PageTreeRoot(3);
        var kids = root.GetCOSArray(COSName.Kids);
        kids.Add((COSBase)null!);
        kids.Add(COSInteger.Get(7));
        var invalid = new COSDictionary();
        invalid.SetItem(COSName.Type, COSName.Xobject);
        kids.Add(invalid);
        var malformed = new PDPageTree(root);
        Observe(
            "page-tree-malformed",
            "null-nondictionary-invalid-type",
            PageOrder(malformed),
            kids.GetObject(0) is COSDictionary,
            malformed.Get(0).GetCOSObject().GetCOSName(COSName.Type)?.GetName());

        var cycleRoot = PageTreeRoot(1);
        var child = PageTreeRoot(1);
        cycleRoot.GetCOSArray(COSName.Kids).Add(child);
        child.GetCOSArray(COSName.Kids).Add(cycleRoot);
        var cyclic = new PDPageTree(cycleRoot);
        Observe(
            "page-tree-malformed",
            "cycle",
            PageOrder(cyclic),
            Fails<InvalidOperationException>(() => cyclic.Get(0)));

        var empty = new PDPageTree();
        var iterator = empty.Iterator();
        Observe(
            "page-tree-error",
            "bounds-iterator-constructor",
            Fails<ArgumentOutOfRangeException>(() => empty.Get(-1)),
            Fails<ArgumentOutOfRangeException>(() => empty.Get(0)),
            Fails<InvalidOperationException>(() => iterator.Next()),
            Fails<NotSupportedException>(() => iterator.Remove()),
            Fails<ArgumentException>(() => new PDPageTree(null!)));
    }

    private static void ObserveResources()
    {
        var resources = new PDResources();
        var firstState = new PDExtendedGraphicsState();
        firstState.SetLineWidth(2.5f);
        var firstName = resources.Add(firstState);
        var duplicateName = resources.Add(firstState);
        var secondName = resources.Add(new PDExtendedGraphicsState());
        Observe(
            "resource-ownership",
            "names-and-dictionary",
            firstName.GetName(),
            duplicateName.GetName(),
            secondName.GetName(),
            string.Join(
                ",",
                resources.GetExtGStateNames()
                    .Select(name => name.GetName())
                    .OrderBy(name => name)));

        var indirectDictionary = new COSDictionary();
        indirectDictionary.SetFloat(COSName.Lw, 4.5f);
        var indirect = new COSObject(indirectDictionary);
        var kindDictionary = new COSDictionary();
        var name = COSName.GetPDFName("gs9");
        kindDictionary.SetItem(name, indirect);
        var resourceDictionary = new COSDictionary();
        resourceDictionary.SetItem(COSName.ExtGState, kindDictionary);
        var cache = new DefaultResourceCache();
        var cachedResources = new PDResources(resourceDictionary, cache);
        var loadedFirst = cachedResources.GetExtGState(name);
        var loadedSecond = cachedResources.GetExtGState(name);
        var removed = cache.RemoveExtState(indirect);
        var loadedThird = cachedResources.GetExtGState(name);
        Observe(
            "resource-cache",
            "indirect-identity-removal",
            ReferenceEquals(loadedFirst, loadedSecond),
            ReferenceEquals(loadedFirst, removed),
            !ReferenceEquals(loadedFirst, loadedThird),
            loadedThird.GetLineWidth());

        using var document = new PDDocument();
        var customCache = new DefaultResourceCache(false);
        document.SetResourceCache(customCache);
        var page = new PDPage();
        document.AddPage(page);
        Observe(
            "resource-ownership",
            "document-page-cache",
            ReferenceEquals(customCache, document.GetResourceCache()),
            page.GetResourceCache() is null,
            ReferenceEquals(customCache, document.GetPage(0).GetResourceCache()));
    }

    private static void ObserveContentStreams()
    {
        using var document = new PDDocument();
        var page = new PDPage();
        document.AddPage(page);
        var overwrite = new PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.Overwrite, false);
        overwrite.AppendRawCommands("0 0 m\n");
        overwrite.Dispose();
        overwrite.Dispose();
        using (var append = new PDPageContentStream(
                   document, page, PDPageContentStream.AppendMode.Append, false))
        {
            append.AppendRawCommands("1 1 l\n");
        }
        using (var prepend = new PDPageContentStream(
                   document, page, PDPageContentStream.AppendMode.Prepend, false))
        {
            prepend.AppendRawCommands("q\n");
        }
        Observe(
            "content-stream",
            "overwrite-append-prepend",
            page.HasContents(),
            CountContentStreams(page),
            CollapseWhitespace(ReadAll(page.GetContents())),
            page.GetResources() is not null);

        using var invalidContent = new PDPageContentStream(
            document, new PDPage(), PDPageContentStream.AppendMode.Overwrite, false);
        var invalidColor =
            Fails<ArgumentException>(
                () => invalidContent.SetNonStrokingColor(1.1f, 0, 0));
        invalidContent.BeginText();
        var invalidPath =
            Fails<InvalidOperationException>(() => invalidContent.MoveTo(0, 0));
        var nestedText =
            Fails<InvalidOperationException>(() => invalidContent.BeginText());
        invalidContent.EndText();
        Observe(
            "content-error",
            "validation-and-text-mode",
            invalidColor,
            invalidPath,
            nestedText);

        var emptyPage = new PDPage();
        using var emptyContents = emptyPage.GetContents();
        Observe(
            "content-stream",
            "missing",
            emptyPage.HasContents(),
            ReadAll(emptyContents).Length);
    }

    private static void ObserveImport()
    {
        using var source = new PDDocument();
        var sourcePage = new PDPage(new PDRectangle(210, 310));
        sourcePage.SetCropBox(new PDRectangle(10, 20, 180, 260));
        sourcePage.SetRotation(270);
        source.AddPage(sourcePage);
        using (var content = new PDPageContentStream(
                   source,
                   sourcePage,
                   PDPageContentStream.AppendMode.Overwrite,
                   false))
        {
            content.AppendRawCommands("2 3 m\n");
        }
        var inheritedResources = new PDResources();
        inheritedResources.Add(new PDExtendedGraphicsState());
        source.GetPages().GetCOSObject()
            .SetItem(COSName.Resources, inheritedResources);
        var inheritedPage = new PDPage();
        source.AddPage(inheritedPage);

        using var destination = new PDDocument();
        var imported = destination.ImportPage(sourcePage);
        Observe(
            "import",
            "page-content-attributes",
            destination.GetNumberOfPages(),
            imported.GetMediaBox().GetWidth(),
            imported.GetMediaBox().GetHeight(),
            imported.GetCropBox().GetLowerLeftX(),
            imported.GetCropBox().GetLowerLeftY(),
            imported.GetRotation(),
            CollapseWhitespace(ReadAll(imported.GetContents())),
            imported.GetCOSObject().ContainsKey(COSName.Parent),
            ReferenceEquals(
                destination.GetResourceCache(), imported.GetResourceCache()));
        Observe(
            "import",
            "inherited-resource-policy",
            inheritedPage.GetResources() is not null,
            !inheritedPage.GetCOSObject().ContainsKey(COSName.Resources),
            destination.ImportPage(inheritedPage).GetResources() is null,
            destination.GetNumberOfPages());
    }

    private static void ObserveScratchAndStreamLifetime()
    {
        var payload = Enumerable.Range(0, 131_072)
            .Select(index => unchecked((byte)(index * 31)))
            .ToArray();
        var input = new TrackingMemoryStream(payload);
        byte[] saved;
        var scratch = new PDDocument(IOUtils.CreateTempFileOnlyStreamCache());
        var page = new PDPage();
        page.SetContents(new PDStream(scratch, input));
        scratch.AddPage(page);
        using (var output = new MemoryStream())
        {
            scratch.Save(output, CompressParameters.NoCompression);
            saved = output.ToArray();
        }
        var scratchClosedBefore = scratch.GetDocument().IsClosed();
        scratch.Dispose();
        Observe(
            "scratch-storage",
            "temp-file-cache",
            input.Closed,
            saved.Length > payload.Length,
            scratchClosedBefore,
            scratch.GetDocument().IsClosed());

        using (var reopened = Loader.LoadPDF(ToSigned(saved)))
        {
            using var contents = reopened.GetPage(0).GetContents();
            Observe(
                "scratch-storage",
                "reopen",
                reopened.GetNumberOfPages(),
                ReadAll(contents).Length);
        }

        using var document = new PDDocument();
        document.AddPage(new PDPage());
        var saveOutput = new TrackingMemoryStream();
        document.Save(saveOutput, CompressParameters.NoCompression);
        var openAfterSave = !saveOutput.Closed && saveOutput.CanWrite;
        saveOutput.Dispose();
        Observe(
            "stream-lifetime",
            "input-output-ownership",
            input.Closed,
            openAfterSave);
    }

    private static void ObserveLifecycleFailures()
    {
        var document = new PDDocument();
        document.AddPage(new PDPage());
        var incrementalFailure =
            Fails<InvalidOperationException>(
                () => document.SaveIncremental(new MemoryStream()));
        document.Dispose();
        document.Dispose();
        var closed = document.GetDocument().IsClosed();
        var saveFailure =
            Fails<IOException>(() => document.Save(new MemoryStream()));
        Observe(
            "document-lifecycle",
            "close-and-save-failures",
            closed,
            incrementalFailure,
            saveFailure);

        var invalidBytes = Encoding.UTF8.GetBytes("<script language='JavaScript'>");
        var byteFailure =
            Fails<IOException>(() => Loader.LoadPDF(ToSigned(invalidBytes)));
        var invalidPath = Path.Combine(exchange, "invalid-dotnet.pdf");
        File.WriteAllBytes(invalidPath, invalidBytes);
        var fileFailure =
            Fails<IOException>(() => Loader.LoadPDF(new FileInfo(invalidPath)));
        File.Delete(invalidPath);
        Observe(
            "loader-error",
            "invalid-byte-and-file",
            byteFailure,
            fileFailure,
            !File.Exists(invalidPath));
    }

    private static void ObserveCrossRuntimeReopen(string path)
    {
        using var loaded = Loader.LoadPDF(new FileInfo(path));
        var firstContent = CollapseWhitespace(ReadAll(loaded.GetPage(0).GetContents()));
        var original =
            string.Join(
                "|",
                loaded.GetNumberOfPages(),
                loaded.GetDocumentInformation().GetTitle(),
                loaded.GetDocumentInformation().GetAuthor(),
                loaded.GetDocumentCatalog().GetLanguage(),
                loaded.GetVersion().ToString(CultureInfo.InvariantCulture),
                loaded.GetDocumentInformation().GetCreationDate()
                    .ToUnixTimeMilliseconds(),
                loaded.GetDocumentInformation().GetCreationDate()
                    .Offset.TotalMinutes,
                loaded.GetPage(0).GetRotation(),
                firstContent);

        loaded.GetDocumentInformation().SetTitle("Mutated");
        loaded.AddPage(new PDPage(new PDRectangle(100, 200)));
        loaded.RemovePage(0);
        using var mutatedBytes = new MemoryStream();
        loaded.Save(mutatedBytes, CompressParameters.NoCompression);
        using var mutated = Loader.LoadPDF(ToSigned(mutatedBytes.ToArray()));
        Observe(
            "package-reopen",
            "cross-runtime-create-load-mutate",
            original,
            mutated.GetNumberOfPages(),
            mutated.GetDocumentInformation().GetTitle(),
            mutated.GetPage(1).GetMediaBox().GetWidth(),
            mutated.GetPage(1).GetMediaBox().GetHeight());

        var copy = Path.Combine(exchange, "delete-after-close-dotnet.pdf");
        File.Copy(path, copy, true);
        var deletionDocument = Loader.LoadPDF(new FileInfo(copy));
        deletionDocument.Dispose();
        File.Delete(copy);
        Observe(
            "document-lifecycle",
            "loaded-file-release",
            deletionDocument.GetDocument().IsClosed(),
            !File.Exists(copy));
    }

    private static PDPage NamedPage(string name)
    {
        var page = new PDPage();
        page.GetCOSObject().SetString(COSName.GetPDFName("Probe"), name);
        return page;
    }

    private static COSDictionary PageTreeRoot(int count)
    {
        var root = new COSDictionary();
        root.SetItem(COSName.Type, COSName.Pages);
        root.SetItem(COSName.Kids, new COSArray());
        root.SetInt(COSName.Count, count);
        return root;
    }

    private static string PageOrder(PDPageTree tree)
    {
        var values = new List<string>();
        var iterator = tree.Iterator();
        while (iterator.HasNext())
        {
            var page = iterator.Next()!;
            values.Add(
                page.GetCOSObject().GetString(COSName.GetPDFName("Probe"))
                ?? page.GetCOSObject().GetCOSName(COSName.Type)?.GetName()
                ?? "null");
        }
        return string.Join(",", values);
    }

    private static int CountContentStreams(PDPage page)
    {
        var count = 0;
        var iterator = page.GetContentStreams();
        while (iterator.HasNext())
        {
            iterator.Next();
            count++;
        }
        return count;
    }

    private static byte[] ReadAll(Stream stream)
    {
        using (stream)
        using (var output = new MemoryStream())
        {
            stream.CopyTo(output);
            return output.ToArray();
        }
    }

    private static string CollapseWhitespace(byte[] bytes) =>
        string.Join(
            " ",
            Encoding.ASCII.GetString(bytes)
                .Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));

    private static sbyte[] ToSigned(byte[] bytes)
    {
        var signed = new sbyte[bytes.Length];
        Buffer.BlockCopy(bytes, 0, signed, 0, bytes.Length);
        return signed;
    }

    private static bool Fails<TException>(Action action)
        where TException : Exception
    {
        try
        {
            action();
            return false;
        }
        catch (TException)
        {
            return true;
        }
    }

    private static void Observe(string family, string id, params object?[] values)
    {
        var rendered = string.Join("|", values.Select(Render));
        Observations.Add($"{family}\t{id}\t{rendered}");
    }

    private static string Render(object? value) =>
        value switch
        {
            null => "null",
            bool boolean => boolean ? "true" : "false",
            float number => number.ToString("0.0####", CultureInfo.InvariantCulture),
            double number => number.ToString("0.0####", CultureInfo.InvariantCulture),
            IFormattable formattable =>
                formattable.ToString(null, CultureInfo.InvariantCulture)
                    .Replace('\t', ' ')
                    .Replace('\n', ' '),
            _ => value.ToString()!.Replace('\t', ' ').Replace('\n', ' ')
        };

    private sealed class TrackingMemoryStream : MemoryStream
    {
        internal TrackingMemoryStream()
        {
        }

        internal TrackingMemoryStream(byte[] bytes)
            : base(bytes)
        {
        }

        internal bool Closed { get; private set; }

        protected override void Dispose(bool disposing)
        {
            Closed = true;
            base.Dispose(disposing);
        }
    }
}
