#nullable disable
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.IO;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Multipdf;
using PdfCube.PdfBox.Pdfwriter.Compress;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Common;
using PdfCube.PdfBox.Pdmodel.Documentinterchange.Logicalstructure;
using PdfCube.PdfBox.Pdmodel.Graphics.Form;
using PdfCube.PdfBox.Pdmodel.Graphics.Optionalcontent;
using PdfCube.PdfBox.Pdmodel.Graphics.State;
using PdfCube.PdfBox.Pdmodel.Interactive.Action;
using PdfCube.PdfBox.Pdmodel.Interactive.Annotation;
using PdfCube.PdfBox.Pdmodel.Interactive.Documentnavigation.Destination;
using PdfCube.PdfBox.Pdmodel.Interactive.Documentnavigation.Outline;
using PdfCube.PdfBox.Pdmodel.Interactive.Form;
using SkiaSharp;

internal static class Program
{
    private static readonly COSName Probe = COSName.GetPDFName("Probe");
    private static readonly COSName Left = COSName.GetPDFName("Left");
    private static readonly COSName Right = COSName.GetPDFName("Right");
    private static readonly COSName Payload = COSName.GetPDFName("Payload");
    private static readonly COSName SharedState = COSName.GetPDFName("SharedState");
    private static readonly List<string> Observations = new();

    private static string exchange = "";
    private static string fixtures = "";

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 3 or > 4)
                throw new ArgumentException(
                    "Expected output trace, exchange directory, fixture directory, and optional --write-only.");
            if (args.Length == 4 && args[3] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            exchange = args[1];
            fixtures = args[2];
            Directory.CreateDirectory(exchange);

            WriteCrossRuntimeDocument(
                Path.Combine(exchange, "dotnet-manipulation.pdf"));
            if (args.Length == 4)
                return 0;

            ObserveCloneUtility();
            ObserveImportAndResourceCollisions();
            ObserveSplittingAndExtraction();
            ObserveStructuredSplitFixture();
            ObserveAppendMerge();
            ObserveFileMergeAndRepeatedSources();
            ObserveOverlay();
            ObserveLayerUtility();
            ObserveFailures();
            ObserveCrossRuntimeReopen(
                Path.Combine(exchange, "java-manipulation.pdf"));

            File.WriteAllLines(output, Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveCloneUtility()
    {
        using var source = new PDDocument();
        using var destination = new PDDocument();
        var shared = new COSDictionary();
        shared.SetString(Probe, "shared");

        var stream = source.GetDocument().CreateCOSStream();
        using (var output = stream.CreateRawOutputStream())
        {
            WriteAscii(output, "clone-stream-payload");
        }
        stream.SetItem(Probe, shared);
        stream.SetInt(COSName.Length1, 20);

        var array = new COSArray();
        array.Add(shared);
        array.Add(shared);
        var root = new COSDictionary();
        root.SetItem(Left, shared);
        root.SetItem(Right, shared);
        root.SetItem(Payload, stream);
        root.SetItem(COSName.Kids, array);

        var cloner = new ExposedCloner(destination);
        var cloned = cloner.CloneForNewDocument(root);
        var clonedAgain = cloner.CloneForNewDocument(root);
        var left = cloned.GetCOSDictionary(Left);
        var right = cloned.GetCOSDictionary(Right);
        var clonedStream = cloned.GetCOSStream(Payload);
        var clonedArray = cloned.GetCOSArray(COSName.Kids);

        Require(!ReferenceEquals(cloned, root), "Clone root must be distinct");
        Require(ReferenceEquals(cloned, clonedAgain),
            "Repeated clone must be deduplicated");
        Require(ReferenceEquals(left, right),
            "Shared dictionary identity must be preserved");
        Require(ReferenceEquals(left, clonedArray.GetObject(0)),
            "Array reference must reuse clone");
        Require(ReferenceEquals(left, clonedArray.GetObject(1)),
            "Repeated array reference must reuse clone");
        Require(ReferenceEquals(clonedStream.GetDictionaryObject(Probe), left),
            "Stream metadata reference must reuse clone");

        Observe(
            "clone-identity",
            "deduplication",
            !ReferenceEquals(cloned, root),
            ReferenceEquals(cloned, clonedAgain),
            ReferenceEquals(left, right),
            ReferenceEquals(left, clonedArray.GetObject(0)),
            ReferenceEquals(left, clonedArray.GetObject(1)));
        Observe(
            "clone-reference",
            "source-destination-separation",
            !ReferenceEquals(left, shared),
            !ReferenceEquals(clonedStream, stream),
            ReferenceEquals(clonedStream.GetDictionaryObject(Probe), left),
            left.GetString(Probe));
        Observe(
            "clone-stream",
            "raw-bytes-and-metadata",
            ReadAscii(clonedStream.CreateRawInputStream()),
            clonedStream.GetInt(COSName.Length1),
            ReferenceEquals(clonedStream.GetCOSObject(), clonedStream));
    }

    private static void ObserveImportAndResourceCollisions()
    {
        var importedPath = Path.Combine(exchange, "dotnet-imported-pages.pdf");
        using (var source = new PDDocument())
        using (var destination = new PDDocument())
        {
            var sourcePage = new PDPage(new PDRectangle(10, 20, 210, 320));
            sourcePage.SetCropBox(new PDRectangle(20, 30, 180, 250));
            sourcePage.SetRotation(90);
            sourcePage.GetCOSObject().SetString(Probe, "import-source");
            sourcePage.SetResources(ResourcesWithAlpha(0.25f));
            source.AddPage(sourcePage);
            WritePageContent(source, sourcePage, "%import-source\n0 0 m 10 10 l S\n");

            var note = new PDAnnotationText();
            note.SetContents("imported note");
            note.SetRectangle(new PDRectangle(25, 35, 20, 20));
            sourcePage.GetAnnotations().Add(note);

            var first = destination.ImportPage(sourcePage);
            var second = destination.ImportPage(sourcePage);
            Require(!ReferenceEquals(first.GetCOSObject(), sourcePage.GetCOSObject()),
                "Imported page must be a new object");
            Require(!ReferenceEquals(first.GetCOSObject(), second.GetCOSObject()),
                "Repeated imports must create separate pages");
            Require(!ReferenceEquals(first.GetContents(), sourcePage.GetContents()),
                "Imported content stream must be owned by destination");

            Observe(
                "import-page",
                "geometry-annotations-and-stream",
                PageGeometry(first),
                first.GetRotation(),
                first.GetAnnotations().Count,
                ReadPageContent(first).Contains("%import-source",
                    StringComparison.Ordinal),
                first.GetResources().GetExtGState(SharedState)
                    .GetNonStrokingAlphaConstant());
            Observe(
                "repeated-import",
                "identity-and-order",
                destination.GetNumberOfPages(),
                !ReferenceEquals(first.GetCOSObject(), second.GetCOSObject()),
                !ReferenceEquals(first.GetContents(), second.GetContents()),
                PageOrder(destination),
                ReadPageContent(second).Contains("%import-source",
                    StringComparison.Ordinal));

            destination.Save(
                new FileInfo(importedPath), CompressParameters.NoCompression);
        }

        using (var reopened = Loader.LoadPDF(new FileInfo(importedPath)))
        {
            Observe(
                "import-page",
                "destination-lifecycle-reopen",
                reopened.GetNumberOfPages(),
                PageOrder(reopened),
                PageGeometry(reopened.GetPage(0)),
                reopened.GetPage(0).GetAnnotations().Count,
                ReadPageContent(reopened.GetPage(1)).Contains(
                    "%import-source", StringComparison.Ordinal));
        }

        using var firstSource =
            OnePageDocument("collision-a", 220, 310, 0.25f);
        using var secondSource =
            OnePageDocument("collision-b", 420, 210, 0.75f);
        using var collisionDestination = new PDDocument();
        var merger = new PDFMergerUtility();
        merger.AppendDocument(collisionDestination, firstSource);
        merger.AppendDocument(collisionDestination, secondSource);
        var firstAlpha = collisionDestination.GetPage(0).GetResources()
            .GetExtGState(SharedState).GetNonStrokingAlphaConstant();
        var secondAlpha = collisionDestination.GetPage(1).GetResources()
            .GetExtGState(SharedState).GetNonStrokingAlphaConstant();
        Require(firstAlpha == 0.25f && secondAlpha == 0.75f,
            "Page-local resources must survive name collisions");
        Observe(
            "resource-collision",
            "page-local-same-name",
            PageOrder(collisionDestination),
            firstAlpha,
            secondAlpha,
            ResourceNames(collisionDestination.GetPage(0)),
            ResourceNames(collisionDestination.GetPage(1)));
    }

    private static void ObserveSplittingAndExtraction()
    {
        var splitOnePath = Path.Combine(exchange, "dotnet-split-one.pdf");
        var splitTwoPath = Path.Combine(exchange, "dotnet-split-two.pdf");
        using (var source = new PDDocument())
        {
            source.SetVersion(1.7f);
            source.GetDocumentInformation().SetTitle("split-source");
            source.GetDocumentCatalog().SetLanguage("en-GB");
            for (var index = 1; index <= 5; index++)
            {
                var page =
                    new PDPage(new PDRectangle(
                        100 + index * 10, 200 + index * 5));
                page.GetCOSObject().SetString(Probe, $"p{index}");
                source.AddPage(page);
                WritePageContent(source, page, $"%p{index}\n");
            }

            var splitter = new Splitter();
            splitter.SetStartPage(2);
            splitter.SetEndPage(5);
            splitter.SetSplitAtPage(2);
            var splits = splitter.Split(source);
            Require(splits.Count == 2, "Expected two split documents");
            Require(
                $"{PageOrder(splits[0])}|{PageOrder(splits[1])}"
                    == "p2,p3|p4,p5",
                "Split page order differs");
            Observe(
                "split-order",
                "range-and-chunks",
                splits.Count,
                SplitSizes(splits),
                $"{PageOrder(splits[0])}|{PageOrder(splits[1])}",
                splits[0].GetVersion(),
                splits[0].GetDocumentInformation().GetTitle(),
                splits[1].GetDocumentCatalog().GetLanguage());

            splits[0].Save(
                new FileInfo(splitOnePath), CompressParameters.NoCompression);
            splits[1].Save(
                new FileInfo(splitTwoPath), CompressParameters.NoCompression);
            foreach (var split in splits)
                split.Dispose();

            using var extracted = new PageExtractor(source, 2, 4).Extract();
            using var empty = new PageExtractor(source, 4, 3).Extract();
            Require(PageOrder(extracted) == "p2,p3,p4",
                "PageExtractor must retain inclusive ordering");
            Require(empty.GetNumberOfPages() == 0,
                "Reversed extraction range must be empty");
            Observe(
                "page-extractor",
                "inclusive-clamped-and-empty",
                extracted.GetNumberOfPages(),
                PageOrder(extracted),
                PageGeometry(extracted.GetPage(0)),
                empty.GetNumberOfPages());
        }

        using var first = Loader.LoadPDF(new FileInfo(splitOnePath));
        using var second = Loader.LoadPDF(new FileInfo(splitTwoPath));
        Observe(
            "split-lifecycle",
            "saved-before-source-close",
            first.GetNumberOfPages(),
            second.GetNumberOfPages(),
            $"{PageOrder(first)}|{PageOrder(second)}",
            ReadPageContent(first.GetPage(0)).Contains(
                "%p2", StringComparison.Ordinal),
            ReadPageContent(second.GetPage(1)).Contains(
                "%p5", StringComparison.Ordinal));
    }

    private static void ObserveStructuredSplitFixture()
    {
        var fixture = Fixture("input/merge/PDFBOX-5762-722238.pdf");
        using var source = Loader.LoadPDF(new FileInfo(fixture));
        var splitter = new Splitter();
        splitter.SetStartPage(1);
        splitter.SetEndPage(2);
        splitter.SetSplitAtPage(2);
        var splits = splitter.Split(source);
        Require(splits.Count == 1,
            "Structured fixture should produce one split");
        using var split = splits[0];
        var root = split.GetDocumentCatalog().GetStructureTreeRoot();
        var annotations = split.GetPage(0).GetAnnotations();
        var destinations = new List<int>();
        foreach (var annotation in annotations)
        {
            if (annotation is not PDAnnotationLink link)
                continue;
            var destination = (link.GetAction() as PDActionGoTo)
                ?.GetDestination() as PDPageDestination;
            destinations.Add(
                destination?.GetPage() is null
                    ? -1
                    : split.GetPages().IndexOf(destination.GetPage()));
        }
        Require(root is not null,
            "Structured split must retain structure root");
        Require(annotations.Count == 5,
            "Structured split must retain fixture annotations");
        Observe(
            "split-structure",
            "structure-annotations-and-destinations",
            split.GetNumberOfPages(),
            root is not null,
            ParentTreeEntryCount(root.GetParentTree()),
            root.GetRoleMap().Count,
            annotations.Count,
            string.Join(",", destinations));
    }

    private static void ObserveAppendMerge()
    {
        var mergedPath = Path.Combine(exchange, "dotnet-rich-merge.pdf");
        using (var first =
               FeatureDocument("alpha", 0.25f, 1.4f, 210, 310))
        using (var second =
               FeatureDocument("beta", 0.75f, 1.7f, 410, 210))
        using (var destination = new PDDocument())
        {
            var merger = new PDFMergerUtility();
            merger.AppendDocument(destination, first);
            merger.AppendDocument(destination, second);
            Require(PageOrder(destination) == "alpha-1,beta-1",
                "Append merge order differs");
            Require(FormNames(destination) == "alpha-field,beta-field",
                "Append merge must retain form fields");
            Require(OutlineTitles(destination)
                    == "alpha-outline,beta-outline",
                "Append merge must retain outline ordering");
            Require(OptionalGroupNames(destination)
                    == "alpha-layer,beta-layer",
                "Append merge must retain optional-content groups");
            Require(
                destination.GetDocumentCatalog().GetStructureTreeRoot()
                    is not null,
                "Append merge must retain logical structure");

            Observe(
                "merge-model",
                "rich-document-structures",
                PageOrder(destination),
                destination.GetVersion(),
                destination.GetDocumentInformation().GetTitle(),
                MetadataText(destination),
                FormNames(destination),
                OutlineTitles(destination),
                OptionalGroupNames(destination),
                StructureKidCount(destination),
                AnnotationCounts(destination));
            Observe(
                "resource-collision",
                "append-merge-page-local",
                destination.GetPage(0).GetResources()
                    .GetExtGState(SharedState)
                    .GetNonStrokingAlphaConstant(),
                destination.GetPage(1).GetResources()
                    .GetExtGState(SharedState)
                    .GetNonStrokingAlphaConstant(),
                PageGeometry(destination.GetPage(0)),
                PageGeometry(destination.GetPage(1)));

            destination.Save(
                new FileInfo(mergedPath), CompressParameters.NoCompression);
        }

        using var reopened = Loader.LoadPDF(new FileInfo(mergedPath));
        Observe(
            "merge-reopen",
            "rich-document-structures",
            PageOrder(reopened),
            reopened.GetVersion(),
            reopened.GetDocumentInformation().GetTitle(),
            MetadataText(reopened),
            FormNames(reopened),
            OutlineTitles(reopened),
            OptionalGroupNames(reopened),
            StructureKidCount(reopened),
            AnnotationCounts(reopened));
    }

    private static void ObserveFileMergeAndRepeatedSources()
    {
        var firstPath = Path.Combine(exchange, "dotnet-file-source-a.pdf");
        var secondPath = Path.Combine(exchange, "dotnet-file-source-b.pdf");
        var mergedPath = Path.Combine(exchange, "dotnet-file-merged.pdf");
        using (var first = OnePageDocument("file-a", 200, 300, 0.2f))
        using (var second = OnePageDocument("file-b", 500, 240, 0.8f))
        {
            first.Save(
                new FileInfo(firstPath), CompressParameters.NoCompression);
            second.Save(
                new FileInfo(secondPath), CompressParameters.NoCompression);
        }

        var merger = new PDFMergerUtility();
        merger.AddSource(new FileInfo(firstPath));
        merger.AddSource(new FileInfo(secondPath));
        merger.AddSource(new FileInfo(firstPath));
        merger.SetDestinationFileName(mergedPath);
        merger.MergeDocuments(
            IOUtils.CreateMemoryOnlyStreamCache(),
            CompressParameters.NoCompression);

        using var merged = Loader.LoadPDF(new FileInfo(mergedPath));
        Require(PageOrder(merged) == "file-a,file-b,file-a",
            "File merge must preserve source order and repeated imports");
        Observe(
            "repeated-import",
            "file-merge-source-list",
            merged.GetNumberOfPages(),
            PageOrder(merged),
            PageGeometry(merged.GetPage(0)),
            PageGeometry(merged.GetPage(1)),
            PageGeometry(merged.GetPage(2)));
        Observe(
            "merge-reopen",
            "file-api-output",
            new FileInfo(mergedPath).Length > 0,
            merged.GetNumberOfPages(),
            PageOrder(merged),
            ReadPageContent(merged.GetPage(0)).Contains(
                "%file-a", StringComparison.Ordinal),
            ReadPageContent(merged.GetPage(1)).Contains(
                "%file-b", StringComparison.Ordinal));
    }

    private static void ObserveOverlay()
    {
        using (var input = MixedPageDocument("overlay-base"))
        using (var overlayDocument =
               OnePageDocument("overlay-mark", 120, 80, 0.6f))
        using (var overlay = new Overlay())
        {
            overlay.SetInputPDF(input);
            overlay.SetDefaultOverlayPDF(overlayDocument);
            overlay.SetOverlayPosition(Overlay.Position.Foreground);
            overlay.SetAdjustRotation(true);
            var result =
                overlay.OverlayDocuments(new Dictionary<int, PDDocument>());
            Require(ReferenceEquals(result, input),
                "Overlay must mutate and return the input document");
            Observe(
                "overlay-order",
                "foreground-save-restore",
                StreamKinds(result.GetPage(0)),
                StreamKinds(result.GetPage(1)),
                StreamKinds(result.GetPage(2)),
                ReferenceEquals(result, input));
            Observe(
                "overlay-geometry",
                "mixed-page-size-and-rotation",
                PageGeometry(result.GetPage(0)),
                result.GetPage(0).GetRotation(),
                PageGeometry(result.GetPage(1)),
                result.GetPage(1).GetRotation(),
                PageGeometry(result.GetPage(2)),
                result.GetPage(2).GetRotation(),
                XObjectCounts(result));
        }

        using (var input = MixedPageDocument("specific-base"))
        using (var firstOverlay =
               OnePageDocument("specific-one", 90, 60, 0.3f))
        using (var secondOverlay =
               OnePageDocument("specific-two", 140, 100, 0.7f))
        using (var overlay = new Overlay())
        {
            overlay.SetInputPDF(input);
            overlay.SetOverlayPosition(Overlay.Position.Background);
            var specifics = new SortedDictionary<int, PDDocument>
            {
                [1] = firstOverlay,
                [3] = secondOverlay
            };
            var result = overlay.OverlayDocuments(specifics);
            Observe(
                "overlay-order",
                "specific-background-pages",
                StreamKinds(result.GetPage(0)),
                StreamKinds(result.GetPage(1)),
                StreamKinds(result.GetPage(2)),
                XObjectCounts(result));
        }
    }

    private static void ObserveLayerUtility()
    {
        var layerPath = Path.Combine(exchange, "dotnet-layered-import.pdf");
        using (var source = new PDDocument())
        using (var target = new PDDocument())
        {
            var sourcePage =
                new PDPage(new PDRectangle(0, 0, 300, 200));
            sourcePage.SetCropBox(new PDRectangle(10, 20, 210, 170));
            sourcePage.SetRotation(90);
            sourcePage.SetResources(ResourcesWithAlpha(0.4f));
            source.AddPage(sourcePage);
            WritePageContent(
                source, sourcePage, "%layer-source\n0 0 m 30 30 l S\n");
            var pageMetadata = new PDMetadata(
                source,
                new MemoryStream(
                    Encoding.UTF8.GetBytes("<page-meta>layer</page-meta>")));
            sourcePage.SetMetadata(pageMetadata);
            var sourceProperties = new PDOptionalContentProperties();
            sourceProperties.AddGroup(
                new PDOptionalContentGroup("source-oc"));
            source.GetDocumentCatalog().SetOCProperties(sourceProperties);

            var targetPage = new PDPage(new PDRectangle(400, 300));
            target.AddPage(targetPage);
            WritePageContent(target, targetPage, "%layer-target\n");

            var utility = new LayerUtility(target);
            utility.WrapInSaveRestore(targetPage);
            var form = utility.ImportPageAsForm(source, sourcePage);
            var layer = utility.AppendFormAsLayer(
                targetPage,
                form,
                SKMatrix.CreateTranslation(12, 18),
                "imported-layer");
            Require(OptionalGroupNames(target)
                    == "source-oc,imported-layer",
                "Layer import must preserve source and appended optional content");

            Observe(
                "layer-import",
                "form-geometry-resources-and-metadata",
                form.GetBBox().GetLowerLeftX(),
                form.GetBBox().GetLowerLeftY(),
                form.GetBBox().GetWidth(),
                form.GetBBox().GetHeight(),
                form.GetMatrix().GetScaleX(),
                form.GetMatrix().GetScaleY(),
                form.GetResources().GetExtGStateNames().Any(),
                form.GetCOSObject().ContainsKey(COSName.Metadata));
            Observe(
                "layer-optional",
                "wrap-append-and-group-order",
                ReferenceEquals(utility.GetDocument(), target),
                layer.GetName(),
                OptionalGroupNames(target),
                StreamKinds(targetPage),
                Fails<ArgumentException>(
                    () => utility.AppendFormAsLayer(
                        targetPage,
                        form,
                        SKMatrix.CreateIdentity(),
                        "imported-layer")));

            target.Save(
                new FileInfo(layerPath), CompressParameters.NoCompression);
        }

        using var reopened = Loader.LoadPDF(new FileInfo(layerPath));
        Observe(
            "layer-optional",
            "serialization-reopen",
            reopened.GetNumberOfPages(),
            OptionalGroupNames(reopened),
            StreamKinds(reopened.GetPage(0)),
            XObjectCount(reopened.GetPage(0)));
    }

    private static void ObserveFailures()
    {
        var splitter = new Splitter();
        var invalidSplit =
            Fails<ArgumentException>(() => splitter.SetSplitAtPage(0));
        var invalidStart =
            Fails<ArgumentException>(() => splitter.SetStartPage(0));
        splitter.SetStartPage(3);
        var invalidEnd =
            Fails<ArgumentException>(() => splitter.SetEndPage(2));
        Observe(
            "malformed-input",
            "split-configuration",
            invalidSplit,
            invalidStart,
            invalidEnd);

        using (var document = new PDDocument())
        {
            var page = new PDPage();
            document.AddPage(page);
            page.GetCOSObject().SetItem(COSName.Contents, COSInteger.One);
            var utility = new LayerUtility(document);
            Observe(
                "layer-failure",
                "unknown-content-object",
                Fails<IOException>(() => utility.WrapInSaveRestore(page)));
        }

        using (var overlay = new Overlay())
        {
            Observe(
                "overlay-failure",
                "missing-input-document",
                Fails<ArgumentException>(
                    () => overlay.overlay(new Dictionary<int, string>())));
        }

        var malformed = Path.Combine(exchange, "malformed-manipulation.pdf");
        File.WriteAllText(
            malformed,
            "%PDF-1.7\n1 0 obj\n<< /Type /Catalog",
            Encoding.ASCII);
        var malformedOutput =
            Path.Combine(exchange, "malformed-merge-output.pdf");
        var malformedMerger = new PDFMergerUtility();
        malformedMerger.AddSource(new FileInfo(malformed));
        malformedMerger.SetDestinationFileName(malformedOutput);
        Observe(
            "malformed-input",
            "merge-truncated-source",
            Fails<IOException>(
                () => malformedMerger.MergeDocuments(
                    IOUtils.CreateMemoryOnlyStreamCache())));

        var encrypted = Fixture(
            "org/apache/pdfbox/encryption/PasswordSample-40bit.pdf");
        var encryptedOutput =
            Path.Combine(exchange, "encrypted-merge-output.pdf");
        var encryptedMerger = new PDFMergerUtility();
        encryptedMerger.AddSource(new FileInfo(encrypted));
        encryptedMerger.SetDestinationFileName(encryptedOutput);
        Observe(
            "encrypted-input",
            "merge-without-credential",
            Fails<IOException>(
                () => encryptedMerger.MergeDocuments(
                    IOUtils.CreateMemoryOnlyStreamCache())));

        var closedSource =
            OnePageDocument("closed-source", 100, 100, 0.5f);
        var openDestination = new PDDocument();
        closedSource.Dispose();
        var closedSourceFailure = Fails<IOException>(
            () => new PDFMergerUtility().AppendDocument(
                openDestination, closedSource));
        openDestination.Dispose();
        var openSource =
            OnePageDocument("open-source", 100, 100, 0.5f);
        var closedDestinationFailure = Fails<IOException>(
            () => new PDFMergerUtility().AppendDocument(
                openDestination, openSource));
        openSource.Dispose();
        Observe(
            "merge-lifecycle",
            "closed-source-and-destination",
            closedSourceFailure,
            closedDestinationFailure);
    }

    private static void WriteCrossRuntimeDocument(string path)
    {
        using var first =
            OnePageDocument("cross-a", 200, 300, 0.25f);
        using var second =
            OnePageDocument("cross-b", 420, 220, 0.75f);
        using var overlayDocument =
            OnePageDocument("cross-overlay", 80, 60, 0.5f);
        using var destination = new PDDocument();
        var merger = new PDFMergerUtility();
        merger.AppendDocument(destination, first);
        merger.AppendDocument(destination, second);

        using (var overlay = new Overlay())
        {
            overlay.SetInputPDF(destination);
            overlay.SetDefaultOverlayPDF(overlayDocument);
            overlay.SetOverlayPosition(Overlay.Position.Foreground);
            overlay.OverlayDocuments(new Dictionary<int, PDDocument>());
        }

        var layerUtility = new LayerUtility(destination);
        var form = layerUtility.ImportPageAsForm(overlayDocument, 0);
        layerUtility.AppendFormAsLayer(
            destination.GetPage(0),
            form,
            SKMatrix.CreateTranslation(5, 7),
            "cross-layer");
        destination.Save(
            new FileInfo(path), CompressParameters.NoCompression);
    }

    private static void ObserveCrossRuntimeReopen(string path)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        Require(document.GetNumberOfPages() == 2,
            "Foreign manipulation output must contain two pages");
        Require(PageOrder(document) == "cross-a,cross-b",
            "Foreign manipulation output page order differs");
        Observe(
            "cross-reopen",
            "foreign-merge-overlay-layer",
            document.GetNumberOfPages(),
            PageOrder(document),
            PageGeometry(document.GetPage(0)),
            PageGeometry(document.GetPage(1)),
            StreamKinds(document.GetPage(0)),
            StreamKinds(document.GetPage(1)),
            OptionalGroupNames(document),
            XObjectCounts(document));
    }

    private static PDDocument OnePageDocument(
        string id, float width, float height, float alpha)
    {
        var document = new PDDocument();
        var page = new PDPage(new PDRectangle(width, height));
        page.GetCOSObject().SetString(Probe, id);
        page.SetResources(ResourcesWithAlpha(alpha));
        document.AddPage(page);
        WritePageContent(document, page, $"%{id}\n/SharedState gs\n");
        return document;
    }

    private static PDDocument MixedPageDocument(string prefix)
    {
        var document = new PDDocument();
        var geometry = new[]
        {
            (Width: 200f, Height: 300f),
            (Width: 400f, Height: 200f),
            (Width: 260f, Height: 360f)
        };
        var rotations = new[] { 0, 90, 270 };
        for (var index = 0; index < geometry.Length; index++)
        {
            var page = new PDPage(
                new PDRectangle(
                    geometry[index].Width, geometry[index].Height));
            page.SetRotation(rotations[index]);
            page.GetCOSObject().SetString(
                Probe, $"{prefix}-{index + 1}");
            page.SetResources(
                ResourcesWithAlpha(0.2f + index * 0.2f));
            document.AddPage(page);
            WritePageContent(
                document, page, $"%{prefix}-{index + 1}\n");
        }
        return document;
    }

    private static PDDocument FeatureDocument(
        string prefix,
        float alpha,
        float version,
        float width,
        float height)
    {
        var document =
            OnePageDocument($"{prefix}-1", width, height, alpha);
        document.SetVersion(version);
        document.GetDocumentInformation().SetTitle($"{prefix}-title");
        document.GetDocumentCatalog().SetLanguage("en-US");
        document.GetDocumentCatalog().SetMetadata(
            new PDMetadata(
                document,
                new MemoryStream(
                    Encoding.UTF8.GetBytes(
                        $"<metadata>{prefix}</metadata>"))));

        var page = document.GetPage(0);
        var note = new PDAnnotationText();
        note.SetContents($"{prefix}-annotation");
        note.SetRectangle(new PDRectangle(10, 10, 20, 20));
        page.GetAnnotations().Add(note);

        var form = new PDAcroForm(document);
        form.SetNeedAppearances(true);
        form.SetDefaultResources(new PDResources());
        document.GetDocumentCatalog().SetAcroForm(form);
        form = document.GetDocumentCatalog().GetAcroForm();
        var field = new PDTextField(form);
        field.SetPartialName($"{prefix}-field");
        field.SetValue($"{prefix}-value");
        form.SetFields(new List<PDField> { field });

        var outline = new PDDocumentOutline();
        var item = new PDOutlineItem();
        item.SetTitle($"{prefix}-outline");
        item.SetDestination(page);
        outline.AddLast(item);
        document.GetDocumentCatalog().SetDocumentOutline(outline);

        var properties = new PDOptionalContentProperties();
        properties.AddGroup(
            new PDOptionalContentGroup($"{prefix}-layer"));
        document.GetDocumentCatalog().SetOCProperties(properties);

        var root = new PDStructureTreeRoot();
        var element = new PDStructureElement("Sect", root);
        element.SetTitle($"{prefix}-structure");
        element.SetPage(page);
        root.AppendKid(element);
        page.SetStructParents(0);
        var parentValues = new COSArray();
        parentValues.Add(element);
        var parentTree =
            new PDNumberTreeNode(typeof(PDParentTreeValue));
        parentTree.SetNumbers(
            new Dictionary<int, COSObjectable>
            {
                [0] = new PDParentTreeValue(parentValues)
            });
        root.SetParentTree(parentTree);
        root.SetParentTreeNextKey(1);
        document.GetDocumentCatalog().SetStructureTreeRoot(root);
        return document;
    }

    private static PDResources ResourcesWithAlpha(float alpha)
    {
        var resources = new PDResources();
        var state = new PDExtendedGraphicsState();
        state.SetNonStrokingAlphaConstant(alpha);
        resources.Put(SharedState, state);
        return resources;
    }

    private static void WritePageContent(
        PDDocument document, PDPage page, string content)
    {
        using var stream = new PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.Overwrite,
            false);
        stream.AppendRawCommands(content);
    }

    private static string ReadPageContent(PDPage page)
    {
        var input = page.GetContents();
        return input is null ? "" : ReadAscii(input);
    }

    private static string ReadAscii(Stream input)
    {
        using (input)
        using (var output = new MemoryStream())
        {
            input.CopyTo(output);
            return Encoding.Latin1.GetString(output.ToArray());
        }
    }

    private static void WriteAscii(Stream output, string value)
    {
        var bytes = Encoding.ASCII.GetBytes(value);
        output.Write(bytes, 0, bytes.Length);
    }

    private static string PageOrder(PDDocument document) =>
        string.Join(
            ",",
            document.GetPages().Select(
                page => page.GetCOSObject().GetString(Probe, "-")));

    private static string PageGeometry(PDPage page)
    {
        var media = page.GetMediaBox();
        var crop = page.GetCropBox();
        return Numbers(
                   media.GetLowerLeftX(),
                   media.GetLowerLeftY(),
                   media.GetWidth(),
                   media.GetHeight())
               + "/"
               + Numbers(
                   crop.GetLowerLeftX(),
                   crop.GetLowerLeftY(),
                   crop.GetWidth(),
                   crop.GetHeight());
    }

    private static string SplitSizes(IList<PDDocument> documents) =>
        string.Join(
            ",", documents.Select(document => document.GetNumberOfPages()));

    private static string ResourceNames(PDPage page) =>
        string.Join(
            ",",
            page.GetResources().GetExtGStateNames()
                .Select(name => name.GetName())
                .OrderBy(name => name, StringComparer.Ordinal));

    private static string FormNames(PDDocument document)
    {
        var form = document.GetDocumentCatalog().GetAcroForm();
        return form is null
            ? ""
            : string.Join(
                ",",
                form.GetFieldTree()
                    .Select(field => field.GetFullyQualifiedName())
                    .OrderBy(name => name, StringComparer.Ordinal));
    }

    private static string OutlineTitles(PDDocument document)
    {
        var outline = document.GetDocumentCatalog().GetDocumentOutline();
        return outline is null
            ? ""
            : string.Join(
                ",", outline.Children().Select(item => item.GetTitle()));
    }

    private static string OptionalGroupNames(PDDocument document)
    {
        var properties = document.GetDocumentCatalog().GetOCProperties();
        return properties is null
            ? ""
            : string.Join(",", properties.GetGroupNames());
    }

    private static int StructureKidCount(PDDocument document)
    {
        var root = document.GetDocumentCatalog().GetStructureTreeRoot();
        return root is null ? 0 : root.GetKids().Count;
    }

    private static string AnnotationCounts(PDDocument document) =>
        string.Join(
            ",",
            document.GetPages()
                .Select(page => page.GetAnnotations().Count));

    private static string MetadataText(PDDocument document)
    {
        var metadata = document.GetDocumentCatalog().GetMetadata();
        return metadata is null
            ? ""
            : ReadAscii(metadata.ExportXMPMetadata());
    }

    private static string StreamKinds(PDPage page)
    {
        var kinds = new List<string>();
        var streams = page.GetContentStreams();
        while (streams.HasNext())
        {
            var value = ReadAscii(streams.Next().CreateInputStream()).Trim();
            if (value == "q")
                kinds.Add("q");
            else if (value == "Q")
                kinds.Add("Q");
            else if (value.Contains(" Do", StringComparison.Ordinal))
                kinds.Add("form");
            else if (value.Contains('%'))
            {
                var start = value.IndexOf('%') + 1;
                var end = value.IndexOf('\n', start);
                kinds.Add(
                    end < 0
                        ? value[start..]
                        : value.Substring(start, end - start));
            }
            else
                kinds.Add("content");
        }
        return string.Join(",", kinds);
    }

    private static string XObjectCounts(PDDocument document) =>
        string.Join(
            ",", document.GetPages().Select(XObjectCount));

    private static int XObjectCount(PDPage page) =>
        page.GetResources().GetXObjectNames().Count();

    private static int ParentTreeEntryCount(PDNumberTreeNode tree)
    {
        if (tree is null)
            return 0;
        var count = tree.GetNumbers()?.Count ?? 0;
        var kids = tree.GetKids();
        if (kids is not null)
        {
            foreach (var kid in kids)
                count += ParentTreeEntryCount(kid);
        }
        return count;
    }

    private static string Fixture(string relative) =>
        Path.Combine(
            new[] { fixtures }
                .Concat(relative.Split('/'))
                .ToArray());

    private static string Numbers(params float[] values) =>
        string.Join("x", values.Select(value => Render(value)));

    private static void Require(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }

    private static bool Fails<TException>(Action operation)
        where TException : Exception
    {
        try
        {
            operation();
            return false;
        }
        catch (TException)
        {
            return true;
        }
    }

    private static void Observe(
        string family, string id, params object[] values)
    {
        var value = string.Join("|", values.Select(Render));
        Observations.Add($"{family}\t{id}\t{value}");
    }

    private static string Render(object value)
    {
        if (value is null)
            return "-";
        if (value is bool boolean)
            return boolean ? "true" : "false";
        if (value is float or double)
        {
            var number = Convert.ToDouble(value, CultureInfo.InvariantCulture);
            if (Math.Abs(number) < 0.0005d)
                number = 0d;
            return number.ToString("0.###", CultureInfo.InvariantCulture);
        }
        return Convert.ToString(value, CultureInfo.InvariantCulture)
            .Replace('\t', ' ')
            .Replace('\r', ' ')
            .Replace('\n', ' ');
    }

    private sealed class ExposedCloner : PDFCloneUtility
    {
        internal ExposedCloner(PDDocument destination)
            : base(destination)
        {
        }
    }
}
