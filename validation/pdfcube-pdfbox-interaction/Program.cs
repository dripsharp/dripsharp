using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Pdfwriter.Compress;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Common;
using PdfCube.PdfBox.Pdmodel.Common.Filespecification;
using PdfCube.PdfBox.Pdmodel.Fdf;
using PdfCube.PdfBox.Pdmodel.Interactive.Action;
using PdfCube.PdfBox.Pdmodel.Interactive.Annotation;
using PdfCube.PdfBox.Pdmodel.Interactive.Documentnavigation.Destination;
using PdfCube.PdfBox.Pdmodel.Interactive.Documentnavigation.Outline;
using PdfCube.PdfBox.Pdmodel.Interactive.Form;
using PdfCube.PdfBox.Pdmodel.Interactive.Pagenavigation;

internal static class Program
{
    private static readonly List<string> Observations = new();
    private static string exchange = null!;
    private static string fixtures = null!;

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 3 or > 4)
                throw new ArgumentException(
                    "Expected output trace, exchange directory, fixture directory, "
                    + "and optional --write-only.");
            if (args.Length == 4 && args[3] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            exchange = args[1];
            fixtures = args[2];
            Directory.CreateDirectory(exchange);

            WriteRepresentative(Path.Combine(exchange, "dotnet-interaction.pdf"));
            if (args.Length == 4)
                return 0;

            ObserveFormModel();
            ObserveFormImportExport();
            ObserveFlatten();
            ObserveMalformedForms();
            ObserveAnnotationFixture();
            ObserveAnnotationModel();
            ObserveActions();
            ObserveDestinationsAndOutlines();
            ObservePageLabels();
            ObserveAttachments();
            ObserveCrossRuntimeReopen(
                Path.Combine(exchange, "java-interaction.pdf"));

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
        var first = new PDPage(PDRectangle.A4);
        var second = new PDPage(new PDRectangle(400, 300));
        document.AddPage(first);
        document.AddPage(second);
        var catalog = document.GetDocumentCatalog();

        var form = new PDAcroForm(document);
        form.SetNeedAppearances(true);
        form.SetDefaultResources(new PDResources());
        form.SetDefaultAppearance("/Helv 0 Tf 0 g");
        catalog.SetAcroForm(form);
        form = catalog.GetAcroForm();
        var text = new PDTextField(form);
        text.SetPartialName("customer-name");
        text.SetAlternateFieldName("Customer name");
        text.SetRequired(true);
        AddWidget(text, first, new PDRectangle(40, 700, 180, 24));
        text.SetValue("Ada Lovelace");
        var choice = new PDComboBox(form);
        choice.SetPartialName("customer-plan");
        choice.SetOptions(
            new List<string> { "basic", "pro" },
            new List<string> { "Basic", "Professional" });
        AddWidget(choice, first, new PDRectangle(40, 660, 180, 24));
        choice.SetValue("pro");
        form.SetFields(new List<PDField> { text, choice });
        form.SetCalcOrder(new List<PDField> { text, choice });

        var note = new PDAnnotationText();
        note.SetRectangle(new PDRectangle(30, 600, 32, 32));
        note.SetContents("Review this page");
        note.SetName(PDAnnotationText.NameComment);
        note.SetOpen(true);
        note.SetPrinted(true);
        first.GetAnnotations().Add(note);

        var uri = new PDActionURI();
        uri.SetURI("https://pdfbox.apache.org/");
        uri.SetTrackMousePosition(true);
        var chainedScript =
            new PDActionJavaScript("app.alert('stored, not executed');");
        uri.SetNext(new List<PDAction> { chainedScript });
        var link = new PDAnnotationLink();
        link.SetRectangle(new PDRectangle(40, 560, 220, 24));
        link.SetContents("PDFBox");
        link.SetHighlightMode(PDAnnotationLink.HighlightModeOutline);
        var linkBorder = new PDBorderStyleDictionary();
        linkBorder.SetStyle(PDBorderStyleDictionary.StyleDashed);
        linkBorder.SetWidth(2);
        var dash = new COSArray();
        dash.Add(COSInteger.Get(3));
        dash.Add(COSInteger.Get(2));
        linkBorder.SetDashStyle(dash);
        link.SetBorderStyle(linkBorder);
        link.SetAction(uri);
        first.GetAnnotations().Add(link);

        var square = new PDAnnotationSquare();
        square.SetRectangle(new PDRectangle(300, 560, 80, 60));
        square.SetContents("Manual appearance");
        var effect = new PDBorderEffectDictionary();
        effect.SetStyle(PDBorderEffectDictionary.StyleCloudy);
        effect.SetIntensity(1.5f);
        square.SetBorderEffect(effect);
        square.SetRectDifferences(2, 3, 4, 5);
        var appearance = new PDAppearanceStream(document);
        appearance.SetBBox(new PDRectangle(80, 60));
        appearance.SetResources(new PDResources());
        using (var stream = appearance.GetCOSObject().CreateOutputStream())
        {
            var bytes = Encoding.ASCII.GetBytes("0 0 80 60 re S\n");
            stream.Write(bytes, 0, bytes.Length);
        }
        var appearanceDictionary = new PDAppearanceDictionary();
        appearanceDictionary.SetNormalAppearance(appearance);
        square.SetAppearance(appearanceDictionary);
        first.GetAnnotations().Add(square);

        var xyz = new PDPageXYZDestination();
        xyz.SetPage(second);
        xyz.SetLeft(12);
        xyz.SetTop(250);
        xyz.SetZoom(1.25f);
        var destinations = new PDDestinationNameTreeNode();
        destinations.SetNames(
            new SortedDictionary<string, PDPageDestination>
            {
                ["chapter.two"] = xyz
            });

        var namedScript =
            new PDActionJavaScript("this.print({bUI:false});");
        var scripts = new PDJavascriptNameTreeNode();
        scripts.SetNames(
            new SortedDictionary<string, PDActionJavaScript>
            {
                ["print.silent"] = namedScript
            });

        var attachmentBytes =
            Encoding.UTF8.GetBytes("PdfCube representative attachment\n");
        var embedded =
            new PDEmbeddedFile(document, new MemoryStream(attachmentBytes));
        embedded.SetSubtype("text/plain");
        embedded.SetSize(attachmentBytes.Length);
        embedded.SetCheckSum("0123456789abcdef");
        embedded.SetCreationDate(FixedCalendar());
        embedded.SetModDate(FixedCalendar());
        var specification = new PDComplexFileSpecification();
        specification.SetFile("contract.txt");
        specification.SetFileUnicode("contract.txt");
        specification.SetFileDescription("Interaction contract");
        specification.SetEmbeddedFile(embedded);
        specification.SetEmbeddedFileUnicode(embedded);
        var embeddedFiles = new PDEmbeddedFilesNameTreeNode();
        embeddedFiles.SetNames(
            new SortedDictionary<string, PDComplexFileSpecification>
            {
                ["contract.txt"] = specification
            });

        var names = new PDDocumentNameDictionary(catalog);
        names.SetDests(destinations);
        names.SetJavascript(scripts);
        names.SetEmbeddedFiles(embeddedFiles);
        catalog.SetNames(names);

        var outline = new PDDocumentOutline();
        var chapter = new PDOutlineItem();
        chapter.SetTitle("Chapter Two");
        chapter.SetBold(true);
        chapter.SetItalic(true);
        chapter.SetDestination(xyz);
        var child = new PDOutlineItem();
        child.SetTitle("Named Child");
        var goTo = new PDActionGoTo();
        goTo.SetDestination(new PDNamedDestination("chapter.two"));
        child.SetAction(goTo);
        chapter.AddLast(child);
        chapter.OpenNode();
        outline.AddLast(chapter);
        outline.OpenNode();
        catalog.SetDocumentOutline(outline);

        var labels = new PDPageLabels(document);
        var cover = new PDPageLabelRange();
        cover.SetPrefix("Cover-");
        labels.SetLabelItem(0, cover);
        var body = new PDPageLabelRange();
        body.SetStyle(PDPageLabelRange.StyleRomanLower);
        body.SetPrefix("Body-");
        body.SetStart(4);
        labels.SetLabelItem(1, body);
        catalog.SetPageLabels(labels);

        var thread = new PDThread();
        var threadInfo = new PDDocumentInformation();
        threadInfo.SetTitle("Review thread");
        thread.SetThreadInfo(threadInfo);
        var firstBead = new PDThreadBead();
        firstBead.SetPage(first);
        firstBead.SetRectangle(new PDRectangle(20, 20, 100, 50));
        firstBead.SetThread(thread);
        var secondBead = new PDThreadBead();
        secondBead.SetPage(second);
        secondBead.SetRectangle(new PDRectangle(30, 30, 120, 60));
        secondBead.SetThread(thread);
        firstBead.AppendBead(secondBead);
        thread.SetFirstBead(firstBead);
        catalog.SetThreads(new List<PDThread> { thread });

        catalog.SetOpenAction(
            new PDActionJavaScript("console.println('model only');"));
        document.GetDocumentInformation().SetTitle("Interaction Contract");
        document.Save(new FileInfo(path), CompressParameters.NoCompression);
    }

    private static void AddWidget(
        PDField field,
        PDPage page,
        PDRectangle rectangle)
    {
        var widget = field.GetWidgets()[0];
        widget.SetRectangle(rectangle);
        widget.SetPage(page);
        page.GetAnnotations().Add(widget);
    }

    private static void ObserveFormModel()
    {
        using (var document = Loader.LoadPDF(
                   new FileInfo(Fixture(
                       "org/apache/pdfbox/pdmodel/interactive/form/"
                       + "MultilineFields.pdf"))))
        {
            var form = document.GetDocumentCatalog().GetAcroForm();
            var fields = new List<string>();
            var widgets = 0;
            var widgetAppearances = 0;
            var iterator = form.GetFieldTree().Iterator();
            while (iterator.HasNext())
            {
                var field = iterator.Next()!;
                fields.Add(
                    Render(field.GetFullyQualifiedName())
                    + ":"
                    + field.GetType().Name);
                widgets += field.GetWidgets().Count;
                widgetAppearances += field.GetWidgets().Count(
                    widget => widget.GetNormalAppearanceStream() is not null);
            }
            fields.Sort(StringComparer.Ordinal);
            Observe(
                "form-model",
                "upstream-field-tree",
                form.GetFields().Count,
                fields.Count,
                widgets,
                widgetAppearances,
                fields[0],
                fields[^1],
                form.GetDefaultAppearance(),
                form.GetNeedAppearances());
        }

        using (var document = new PDDocument())
        {
            document.AddPage(new PDPage());
            var form = new PDAcroForm(document);
            form.SetNeedAppearances(true);
            form.SetDefaultResources(new PDResources());
            document.GetDocumentCatalog().SetAcroForm(form);
            form = document.GetDocumentCatalog().GetAcroForm();
            var text = new PDTextField(form);
            text.SetPartialName("invoice-number");
            text.SetAlternateFieldName("Invoice number");
            text.SetMappingName("invoice-id");
            text.SetRequired(true);
            text.SetMaxLen(12);
            text.SetMultiline(false);
            text.SetValue("INV-42");
            var choice = new PDComboBox(form);
            choice.SetPartialName("invoice-status");
            choice.SetOptions(
                new List<string> { "new", "paid" },
                new List<string> { "New", "Paid" });
            choice.SetValue("paid");
            form.SetFields(new List<PDField> { text, choice });
            form.SetCalcOrder(new List<PDField> { choice, text });
            form.SetCacheFields(true);
            Observe(
                "form-model",
                "mutation-and-calculation-order",
                form.GetFields().Count,
                form.GetField("invoice-number").GetValueAsString(),
                form.GetField("invoice-status").GetValueAsString(),
                text.GetAlternateFieldName(),
                text.GetMappingName(),
                text.IsRequired(),
                text.GetMaxLen(),
                choice.HasSeparateExportAndDisplayValues(),
                JoinFields(form.GetCalcOrder()),
                form.IsCachingFields());
        }
    }

    private static void ObserveFormImportExport()
    {
        var fdfPath = Path.Combine(exchange, "dotnet-export.fdf");
        var xfdfPath = Path.Combine(exchange, "dotnet-export.xfdf");
        using (var document = new PDDocument())
        {
            document.AddPage(new PDPage());
            var form = new PDAcroForm(document);
            form.SetNeedAppearances(true);
            form.SetDefaultResources(new PDResources());
            document.GetDocumentCatalog().SetAcroForm(form);
            form = document.GetDocumentCatalog().GetAcroForm();
            var text = new PDTextField(form);
            text.SetPartialName("account-owner");
            text.SetValue("Before");
            form.SetFields(new List<PDField> { text });

            using var exported = form.ExportFDF();
            var fields = exported.GetCatalog().GetFDF().GetFields();
            fields[0].SetValue("After");
            form.ImportFDF(exported);
            exported.Save(new FileInfo(fdfPath));
            exported.SaveXFDF(new FileInfo(xfdfPath));
            Observe(
                "form-import-export",
                "export-mutate-import",
                fields.Count,
                fields[0].GetPartialFieldName(),
                fields[0].GetValue(),
                text.GetValue(),
                new FileInfo(fdfPath).Length > 0,
                File.ReadAllText(xfdfPath).Contains(
                    "account-owner",
                    StringComparison.Ordinal));
        }
        using var loadedFdf = Loader.LoadFDF(new FileInfo(fdfPath));
        using var loadedXfdf = Loader.LoadXFDF(new FileInfo(xfdfPath));
        Observe(
            "form-import-export",
            "fdf-xfdf-reopen",
            loadedFdf.GetCatalog().GetFDF().GetFields().Count,
            loadedFdf.GetCatalog().GetFDF().GetFields()[0].GetValue(),
            loadedXfdf.GetCatalog().GetFDF().GetFields().Count,
            loadedXfdf.GetCatalog().GetFDF().GetFields()[0].GetValue());
    }

    private static void ObserveFlatten()
    {
        var source = Fixture(
            "org/apache/pdfbox/pdmodel/interactive/form/MultilineFields.pdf");
        var flattened = Path.Combine(exchange, "dotnet-flattened.pdf");
        int beforeFields;
        int beforeAnnotations;
        using (var document = Loader.LoadPDF(new FileInfo(source)))
        {
            var form = document.GetDocumentCatalog().GetAcroForm();
            beforeFields = form.GetFields().Count;
            beforeAnnotations = document.GetPage(0).GetAnnotations().Count;
            var field = form.GetField("AlignLeft-Filled");
            form.Flatten(new List<PDField> { field }, false);
            document.Save(
                new FileInfo(flattened),
                CompressParameters.NoCompression);
            Observe(
                "form-flatten",
                "focused-upstream-field",
                beforeFields,
                form.GetFields().Count,
                form.GetField("AlignLeft-Filled") is null,
                beforeAnnotations,
                document.GetPage(0).GetAnnotations().Count);
        }
        using var reopened = Loader.LoadPDF(new FileInfo(flattened));
        Observe(
            "form-flatten",
            "serialization-reopen",
            reopened.GetDocumentCatalog().GetAcroForm().GetFields().Count,
            reopened.GetDocumentCatalog().GetAcroForm()
                .GetField("AlignLeft-Filled") is null,
            reopened.GetPage(0).GetAnnotations().Count,
            CountContentStreams(reopened.GetPage(0)));
    }

    private static void ObserveMalformedForms()
    {
        using (var document = new PDDocument())
        {
            var form = new PDAcroForm(document);
            document.GetDocumentCatalog().SetAcroForm(form);
            form.GetCOSObject().RemoveItem(COSName.Fields);
            Observe(
                "form-malformed",
                "missing-required-fields",
                form.GetFields() is not null,
                form.GetFields().Count == 0,
                form.GetField("missing") is null);
        }

        string invalidType;
        using (var document = new PDDocument())
        {
            var dictionary = new COSDictionary();
            var fields = new COSArray();
            var field = new COSDictionary();
            field.SetName(COSName.Ft, "UnknownField");
            field.SetString(COSName.T, "broken");
            fields.Add(field);
            dictionary.SetItem(COSName.Fields, fields);
            var form = new PDAcroForm(document, dictionary);
            invalidType = FailureCategory(() => form.GetFields());
        }

        string invalidAppearance;
        using (var document = new PDDocument())
        {
            var page = new PDPage();
            document.AddPage(page);
            var form = new PDAcroForm(document);
            document.GetDocumentCatalog().SetAcroForm(form);
            form.SetDefaultResources(new PDResources());
            var text = new PDTextField(form);
            text.SetPartialName("SampleField");
            text.SetDefaultAppearance("/Helv 0 tf 0 g");
            form.GetFields().Add(text);
            AddWidget(text, page, new PDRectangle(50, 750, 200, 20));
            invalidAppearance = FailureCategory(() => text.SetValue("value"));
        }
        Observe(
            "form-malformed",
            "unknown-type-and-bad-appearance",
            invalidType,
            invalidAppearance);
    }

    private static void ObserveAnnotationFixture()
    {
        using var document = Loader.LoadPDF(
            new FileInfo(Fixture(
                "org/apache/pdfbox/pdmodel/interactive/annotation/"
                + "AnnotationTypes.pdf")));
        var subtypes = new SortedDictionary<string, int>(StringComparer.Ordinal);
        var appearances = 0;
        var borderStyles = 0;
        var pages = document.GetPages().Iterator();
        while (pages.HasNext())
        {
            foreach (var annotation in pages.Next()!.GetAnnotations())
            {
                var subtype = Render(annotation.GetSubtype());
                subtypes[subtype] = subtypes.GetValueOrDefault(subtype) + 1;
                if (annotation.GetNormalAppearanceStream() is not null)
                    appearances++;
                if (annotation.GetCOSObject().ContainsKey(COSName.Bs))
                    borderStyles++;
            }
        }
        Observe(
            "annotation-fixture",
            "upstream-types-and-appearances",
            document.GetNumberOfPages(),
            subtypes.Values.Sum(),
            JoinCounts(subtypes),
            appearances,
            borderStyles);
    }

    private static void ObserveAnnotationModel()
    {
        var text = new PDAnnotationText();
        text.SetContents("A note");
        text.SetName(PDAnnotationText.NameKey);
        text.SetOpen(true);
        text.SetHidden(true);
        text.SetPrinted(true);
        text.SetNoZoom(true);
        text.SetAnnotationName("note-1");
        text.SetModifiedDate("D:20200102030405Z");
        text.SetRectangle(new PDRectangle(1, 2, 30, 40));

        var link = new PDAnnotationLink();
        var uri = new PDActionURI();
        uri.SetURI("https://example.test/π");
        link.SetAction(uri);
        link.SetHighlightMode(PDAnnotationLink.HighlightModePush);
        link.SetQuadPoints(new float[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        var border = new PDBorderStyleDictionary();
        border.SetStyle(PDBorderStyleDictionary.StyleUnderline);
        border.SetWidth(3.5f);
        link.SetBorderStyle(border);

        Observe(
            "annotation-model",
            "flags-border-link-and-factory",
            text.GetSubtype(),
            text.GetContents(),
            text.GetName(),
            text.GetOpen(),
            text.IsHidden(),
            text.IsPrinted(),
            text.IsNoZoom(),
            text.GetAnnotationName(),
            text.GetRectangle().GetWidth(),
            text.GetRectangle().GetHeight(),
            link.GetHighlightMode(),
            link.GetBorderStyle().GetStyle(),
            link.GetBorderStyle().GetWidth(),
            link.GetQuadPoints().Length,
            ((PDActionURI)link.GetAction()).GetURI(),
            PDAnnotation.CreateAnnotation(text.GetCOSObject()).GetType().Name,
            PDAnnotation.CreateAnnotation(link.GetCOSObject()).GetType().Name);

        var supported = new List<PDAnnotation>
        {
            new PDAnnotationFileAttachment(),
            new PDAnnotationLine(),
            new PDAnnotationLink(),
            new PDAnnotationPopup(),
            new PDAnnotationRubberStamp(),
            new PDAnnotationSquare(),
            new PDAnnotationCircle(),
            new PDAnnotationPolygon(),
            new PDAnnotationPolyline(),
            new PDAnnotationInk(),
            new PDAnnotationText(),
            new PDAnnotationHighlight(),
            new PDAnnotationUnderline(),
            new PDAnnotationStrikeout(),
            new PDAnnotationSquiggly(),
            new PDAnnotationWidget(),
            new PDAnnotationFreeText(),
            new PDAnnotationCaret(),
            new PDAnnotationSound()
        };
        var factoryTypes = supported.Select(annotation =>
            annotation.GetSubtype()
            + ":"
            + PDAnnotation.CreateAnnotation(annotation.GetCOSObject())
                .GetType()
                .Name);
        var unsupported = new COSDictionary();
        unsupported.SetName(COSName.Subtype, "3D");
        Observe(
            "annotation-model",
            "supported-and-unknown-subtypes",
            supported.Count,
            string.Join(",", factoryTypes),
            PDAnnotation.CreateAnnotation(unsupported).GetType().Name,
            FailureCategory(
                () => PDAnnotation.CreateAnnotation(new COSString("bad"))));

        using var document = new PDDocument();
        var stream = new PDAppearanceStream(document);
        stream.SetBBox(new PDRectangle(10, 20));
        stream.SetResources(new PDResources());
        var dictionary = new PDAppearanceDictionary();
        dictionary.SetNormalAppearance(stream);
        var square = new PDAnnotationSquare();
        square.SetAppearance(dictionary);
        var entry = square.GetAppearance().GetNormalAppearance();
        Observe(
            "annotation-appearance",
            "stream-dictionary",
            entry.IsStream(),
            entry.IsSubDictionary(),
            entry.GetAppearanceStream().GetBBox().GetWidth(),
            entry.GetAppearanceStream().GetBBox().GetHeight(),
            square.GetNormalAppearanceStream() is not null);
    }

    private static void ObserveActions()
    {
        var fit = new PDPageFitDestination();
        fit.SetPageNumber(2);
        fit.SetFitBoundingBox(true);
        var goTo = new PDActionGoTo();
        goTo.SetDestination(new PDNamedDestination("page.two"));
        var uri = new PDActionURI();
        uri.SetURI("https://pdfbox.apache.org/");
        uri.SetTrackMousePosition(true);
        var javascript = new PDActionJavaScript("var answer = 42;");
        var named = new PDActionNamed();
        named.SetN("NextPage");
        var launch = new PDActionLaunch();
        launch.SetF("readme.txt");
        launch.SetD("/tmp");
        launch.SetO("open");
        launch.SetP("--safe");
        var reset = new PDActionResetForm();
        reset.SetFlags(1);
        var resetFields = new COSArray();
        resetFields.Add(new COSString("invoice-number"));
        reset.SetFields(resetFields);
        var submit = new PDActionSubmitForm();
        submit.SetFlags(4);
        var hide = new PDActionHide();
        hide.SetT(new COSString("invoice-number"));
        hide.SetH(true);
        uri.SetNext(new List<PDAction> { javascript, named });

        var actions = new List<PDAction>
        {
            goTo,
            uri,
            javascript,
            named,
            launch,
            reset,
            submit,
            hide
        };
        var reconstructed = actions
            .Select(action =>
                ((object)PDActionFactory.CreateAction(action.GetCOSObject()))
                    .GetType()
                    .Name
                + ":"
                + action.GetSubType())
            .ToList();
        Observe(
            "action",
            "factory-and-properties",
            string.Join(",", reconstructed),
            fit.GetPageNumber(),
            fit.FitBoundingBox(),
            uri.GetURI(),
            uri.ShouldTrackMousePosition(),
            uri.GetNext().Count,
            javascript.GetAction(),
            named.GetN(),
            launch.GetF(),
            launch.GetD(),
            launch.GetO(),
            launch.GetP(),
            reset.GetFlags(),
            reset.GetFields().Size(),
            submit.GetFlags(),
            hide.GetH());

        var fdfJavaScript = new FDFJavaScript();
        fdfJavaScript.SetBefore("beforeImport();");
        fdfJavaScript.SetAfter("afterImport();");
        fdfJavaScript.SetDoc(
            new Dictionary<string, PDActionJavaScript>
            {
                ["validate"] = new PDActionJavaScript("validate();")
            });
        Observe(
            "javascript",
            "fdf-model",
            fdfJavaScript.GetBefore(),
            fdfJavaScript.GetAfter(),
            fdfJavaScript.GetDoc().Count,
            !fdfJavaScript.GetDoc().ContainsKey("validate"));
    }

    private static void ObserveDestinationsAndOutlines()
    {
        using (var document = new PDDocument())
        {
            var first = new PDPage();
            var second = new PDPage();
            document.AddPage(first);
            document.AddPage(second);
            var xyz = new PDPageXYZDestination();
            xyz.SetPage(second);
            xyz.SetLeft(25);
            xyz.SetTop(700);
            xyz.SetZoom(1.5f);
            var tree = new PDDestinationNameTreeNode();
            tree.SetNames(
                new Dictionary<string, PDPageDestination>
                {
                    ["target"] = xyz
                });
            var names = new PDDocumentNameDictionary(
                document.GetDocumentCatalog());
            names.SetDests(tree);
            document.GetDocumentCatalog().SetNames(names);

            var outline = new PDDocumentOutline();
            var root = new PDOutlineItem();
            root.SetTitle("Root");
            root.SetDestination(xyz);
            root.SetBold(true);
            var child = new PDOutlineItem();
            child.SetTitle("Child");
            child.SetDestination(new PDNamedDestination("target"));
            root.AddLast(child);
            root.OpenNode();
            outline.AddLast(root);
            outline.OpenNode();
            document.GetDocumentCatalog().SetDocumentOutline(outline);

            Observe(
                "destination",
                "xyz-and-named",
                xyz.RetrievePageNumber(),
                xyz.GetLeft(),
                xyz.GetTop(),
                xyz.GetZoom(),
                tree.GetValue("target").RetrievePageNumber(),
                document.GetDocumentCatalog()
                    .FindNamedDestinationPage(new PDNamedDestination("target"))
                    .RetrievePageNumber());
            Observe(
                "named-destination",
                "name-tree-limits-and-lookup",
                tree.GetNames().Count,
                tree.GetLowerLimit(),
                tree.GetUpperLimit(),
                tree.GetValue("missing") is null);
            Observe(
                "outline",
                "mutation-and-resolution",
                outline.GetOpenCount(),
                root.GetOpenCount(),
                root.IsNodeOpen(),
                root.GetTitle(),
                root.IsBold(),
                root.GetFirstChild().GetTitle(),
                document.GetPages().IndexOf(root.FindDestinationPage(document)),
                document.GetPages().IndexOf(child.FindDestinationPage(document)));
        }

        using (var document = Loader.LoadPDF(
                   new FileInfo(Fixture(
                       "org/apache/pdfbox/pdmodel/with_outline.pdf"))))
        {
            var outline = document.GetDocumentCatalog().GetDocumentOutline();
            var titles = new List<string>();
            CollectOutlineTitles(outline.GetFirstChild(), titles);
            Observe(
                "outline",
                "upstream-fixture",
                titles.Count,
                string.Join(",", titles),
                outline.GetFirstChild().FindDestinationPage(document) is not null);
        }
    }

    private static void ObservePageLabels()
    {
        using (var document = new PDDocument())
        {
            for (var i = 0; i < 5; i++)
                document.AddPage(new PDPage());
            var labels = new PDPageLabels(document);
            var front = new PDPageLabelRange();
            front.SetStyle(PDPageLabelRange.StyleRomanLower);
            front.SetPrefix("Front-");
            labels.SetLabelItem(0, front);
            var body = new PDPageLabelRange();
            body.SetStyle(PDPageLabelRange.StyleDecimal);
            body.SetPrefix("P-");
            body.SetStart(10);
            labels.SetLabelItem(2, body);
            document.GetDocumentCatalog().SetPageLabels(labels);
            Observe(
                "page-label",
                "mutation",
                labels.GetPageRangeCount(),
                Join(labels.GetLabelsByPageIndices()),
                RenderIntegerSet(labels.GetPageIndices()),
                labels.GetPageIndicesByLabels()["P-10"]);
        }

        using (var document = Loader.LoadPDF(
                   new FileInfo(Fixture(
                       "org/apache/pdfbox/pdmodel/test_pagelabels.pdf"))))
        {
            var labels = document.GetDocumentCatalog().GetPageLabels();
            var rendered = labels.GetLabelsByPageIndices();
            Observe(
                "page-label",
                "upstream-fixture",
                document.GetNumberOfPages(),
                labels.GetPageRangeCount(),
                rendered.Length,
                rendered[0],
                rendered[^1],
                RenderIntegerSet(labels.GetPageIndices()));
        }
    }

    private static void ObserveAttachments()
    {
        using (var document = Loader.LoadPDF(
                   new FileInfo(Fixture(
                       "org/apache/pdfbox/pdmodel/common/"
                       + "testPDF_multiFormatEmbFiles.pdf"))))
        {
            var tree =
                document.GetDocumentCatalog().GetNames().GetEmbeddedFiles();
            var kids = tree.GetKids()?.Count ?? 0;
            var names = new List<string>();
            var variants = 0;
            if (tree.GetNames() is { } rootNames)
                names.AddRange(rootNames.Keys);
            if (tree.GetKids() is { } childNodes)
            {
                foreach (var kid in childNodes)
                {
                    if (kid.GetNames() is not { } childNames)
                        continue;
                    names.AddRange(childNames.Keys);
                    foreach (var specification in childNames.Values)
                    {
                        variants += specification.GetEmbeddedFile() is not null ? 1 : 0;
                        variants += specification.GetEmbeddedFileMac() is not null ? 1 : 0;
                        variants += specification.GetEmbeddedFileDos() is not null ? 1 : 0;
                        variants += specification.GetEmbeddedFileUnix() is not null ? 1 : 0;
                    }
                }
            }
            names.Sort(StringComparer.Ordinal);
            Observe(
                "attachment",
                "upstream-fixture",
                kids,
                names.Count,
                string.Join(",", names),
                variants);
        }

        using (var document = new PDDocument())
        {
            var bytes = Encoding.UTF8.GetBytes("attachment-data");
            var embedded =
                new PDEmbeddedFile(document, new MemoryStream(bytes));
            embedded.SetSubtype("text/plain");
            embedded.SetSize(bytes.Length);
            embedded.SetCheckSum("checksum");
            var specification = new PDComplexFileSpecification();
            specification.SetFile("data.txt");
            specification.SetFileUnicode("δ-data.txt");
            specification.SetFileDescription("Data");
            specification.SetVolatile(true);
            specification.SetEmbeddedFile(embedded);
            specification.SetEmbeddedFileUnicode(embedded);
            Observe(
                "attachment",
                "mutation",
                specification.GetFilename(),
                specification.GetFile(),
                specification.GetFileUnicode(),
                specification.GetFileDescription(),
                specification.IsVolatile(),
                specification.GetEmbeddedFile().GetSubtype(),
                specification.GetEmbeddedFile().GetSize(),
                specification.GetEmbeddedFile().GetCheckSum(),
                Encoding.UTF8.GetString(
                    ToUnsigned(specification.GetEmbeddedFile().ToByteArray())));
        }
    }

    private static void ObserveCrossRuntimeReopen(string path)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        var catalog = document.GetDocumentCatalog();
        var form = catalog.GetAcroForm();
        var openAction = (PDActionJavaScript)catalog.GetOpenAction();
        var names = catalog.GetNames();
        var destination = names.GetDests().GetValue("chapter.two");
        var namedScript = names.GetJavaScript().GetValue("print.silent");
        var specification =
            names.GetEmbeddedFiles().GetValue("contract.txt");
        var outline = catalog.GetDocumentOutline();
        var chapter = outline.GetFirstChild();
        var thread = catalog.GetThreads()[0];
        var firstBead = thread.GetFirstBead();
        var annotations = document.GetPage(0).GetAnnotations();
        var subtypes = new SortedDictionary<string, int>(StringComparer.Ordinal);
        foreach (var annotation in annotations)
        {
            var subtype = annotation.GetSubtype();
            subtypes[subtype] = subtypes.GetValueOrDefault(subtype) + 1;
        }
        var formAppearances = 0;
        var fieldIterator = form.GetFieldTree().Iterator();
        while (fieldIterator.HasNext())
        {
            formAppearances += fieldIterator.Next()!.GetWidgets().Count(
                widget => widget.GetNormalAppearanceStream() is not null);
        }

        Observe(
            "representative-reopen",
            "cross-runtime-model",
            document.GetNumberOfPages(),
            document.GetDocumentInformation().GetTitle(),
            form.GetFields().Count,
            form.GetField("customer-name").GetValueAsString(),
            form.GetField("customer-plan").GetValueAsString(),
            formAppearances,
            JoinFields(form.GetCalcOrder()),
            JoinCounts(subtypes),
            chapter.GetTitle(),
            chapter.IsBold(),
            chapter.IsItalic(),
            document.GetPages().IndexOf(chapter.FindDestinationPage(document)),
            destination.RetrievePageNumber(),
            Join(catalog.GetPageLabels().GetLabelsByPageIndices()),
            thread.GetThreadInfo().GetTitle(),
            firstBead.GetNextBead() is not null,
            firstBead.GetNextBead().GetPreviousBead() is not null,
            specification.GetFileUnicode(),
            specification.GetEmbeddedFile().GetSize(),
            Encoding.UTF8.GetString(
                    ToUnsigned(specification.GetEmbeddedFile().ToByteArray()))
                .Trim());
        var link = (PDAnnotationLink)annotations.First(
            annotation => annotation is PDAnnotationLink);
        Observe(
            "javascript",
            "stored-without-execution",
            openAction.GetAction(),
            names.GetJavaScript().GetNames().Count,
            namedScript.GetAction(),
            ((PDActionJavaScript)((PDActionURI)link.GetAction()).GetNext()[0])
                .GetAction());
        var square = (PDAnnotationSquare)annotations.First(
            annotation => annotation is PDAnnotationSquare);
        Observe(
            "annotation-appearance",
            "serialization-reopen",
            square.GetNormalAppearanceStream() is not null,
            square.GetNormalAppearanceStream().GetBBox().GetWidth(),
            square.GetNormalAppearanceStream().GetBBox().GetHeight(),
            square.GetBorderEffect().GetStyle(),
            square.GetBorderEffect().GetIntensity(),
            Join(square.GetRectDifferences()));
        Observe(
            "thread",
            "serialization-reopen",
            catalog.GetThreads().Count,
            thread.GetThreadInfo().GetTitle(),
            document.GetPages().IndexOf(firstBead.GetPage()),
            document.GetPages().IndexOf(firstBead.GetNextBead().GetPage()),
            firstBead.GetRectangle().GetWidth(),
            firstBead.GetNextBead().GetRectangle().GetHeight());
        Observe(
            "named-destination",
            "serialization-reopen",
            names.GetDests().GetNames().Count,
            names.GetDests().GetLowerLimit(),
            names.GetDests().GetUpperLimit(),
            destination.RetrievePageNumber());
    }

    private static void CollectOutlineTitles(
        PDOutlineItem? item,
        ICollection<string> titles)
    {
        for (var current = item; current is not null; current = current.GetNextSibling())
        {
            titles.Add(current.GetTitle());
            if (current.GetFirstChild() is { } child)
                CollectOutlineTitles(child, titles);
        }
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

    private static DateTimeOffset FixedCalendar() =>
        new(2020, 5, 4, 3, 2, 1, TimeSpan.FromHours(-7));

    private static string Fixture(string relative) =>
        Path.Combine(fixtures, relative.Replace('/', Path.DirectorySeparatorChar));

    private static string JoinFields(IList<PDField> fields) =>
        string.Join(",", fields.Select(field => field.GetFullyQualifiedName()));

    private static string JoinCounts(IEnumerable<KeyValuePair<string, int>> counts) =>
        string.Join(",", counts.Select(entry => $"{entry.Key}={entry.Value}"));

    private static string Join(IEnumerable<string> values) =>
        string.Join(",", values.Select(Render));

    private static string Join(IEnumerable<float> values) =>
        string.Join(",", values.Select(value => Render(value)));

    private static string RenderIntegerSet(IEnumerable<int> values) =>
        "[" + string.Join(", ", values) + "]";

    private static string FailureCategory(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (ArgumentException)
        {
            return "argument";
        }
        catch (NotSupportedException)
        {
            return "unsupported";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (NullReferenceException)
        {
            return "null";
        }
        catch
        {
            return "other";
        }
    }

    private static byte[] ToUnsigned(sbyte[] bytes)
    {
        var unsigned = new byte[bytes.Length];
        Buffer.BlockCopy(bytes, 0, unsigned, 0, bytes.Length);
        return unsigned;
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
}
