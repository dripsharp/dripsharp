using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Common.Filespecification;
using DripSharp.PdfCarton.Pdmodel.Documentinterchange.Logicalstructure;
using DripSharp.PdfCarton.Pdmodel.Documentinterchange.Markedcontent;
using DripSharp.PdfCarton.Pdmodel.Documentinterchange.Taggedpdf;
using DripSharp.PdfCarton.Pdmodel.Fdf;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Graphics.Optionalcontent;
using DripSharp.PdfCarton.Rendering;
using DripSharp.PdfCarton.Text;

internal static class Program
{
    private const string TaggedName = "package-tagged.pdf";
    private const string LayeredName = "package-layered.pdf";
    private const string FdfName = "package-programmatic.fdf";
    private const string XfdfName = "package-programmatic.xfdf";
    private const string ForeignTaggedName = "upstream-tagged.pdf";
    private const string ForeignLayeredName = "upstream-layered.pdf";
    private const string ForeignFdfName = "upstream-programmatic.fdf";
    private const string ForeignXfdfName = "upstream-programmatic.xfdf";

    private static StreamWriter trace = null!;

    private static int Main(string[] args)
    {
        if (args.Length is < 3 or > 4)
        {
            Console.Error.WriteLine(
                "usage: Program <trace.tsv> <exchange-dir> <fixtures-dir> [--write-only]");
            return 2;
        }

        var tracePath = Path.GetFullPath(args[0]);
        var exchange = Path.GetFullPath(args[1]);
        var fixtures = Path.GetFullPath(args[2]);
        Directory.CreateDirectory(exchange);
        WriteArtifacts(exchange);

        if (args.Length == 4)
        {
            if (!string.Equals(args[3], "--write-only", StringComparison.Ordinal))
            {
                throw new ArgumentException($"Unknown mode: {args[3]}");
            }
            return 0;
        }

        using (trace = new StreamWriter(
                   tracePath, false, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)))
        {
            ObserveOptionalContentModel(exchange);
            ObserveFdfModel();
            ObserveFixtures(fixtures);
            ObserveTaggedRoundTrip(Path.Combine(exchange, ForeignTaggedName));
            ObserveLayeredRoundTrip(Path.Combine(exchange, ForeignLayeredName));
            ObserveFdfRoundTrip(Path.Combine(exchange, ForeignFdfName));
            ObserveXfdfRoundTrip(Path.Combine(exchange, ForeignXfdfName));
            ObserveFailures();
        }

        return 0;
    }

    private static void Observe(string family, string id, object value)
    {
        var rendered = value switch
        {
            null => "null",
            bool flag => flag ? "true" : "false",
            float number => number.ToString("0.######", CultureInfo.InvariantCulture),
            double number => number.ToString("0.######", CultureInfo.InvariantCulture),
            _ => Convert.ToString(value, CultureInfo.InvariantCulture) ?? "null"
        };
        rendered = rendered
            .Replace("True", "true", StringComparison.Ordinal)
            .Replace("False", "false", StringComparison.Ordinal)
            .Replace('\t', ' ')
            .Replace('\r', ' ')
            .Replace('\n', ' ');
        trace.Write(family);
        trace.Write('\t');
        trace.Write(id);
        trace.Write('\t');
        trace.WriteLine(rendered);
    }

    private static void WriteArtifacts(string exchange)
    {
        WriteTaggedPdf(Path.Combine(exchange, TaggedName));
        WriteLayeredPdf(Path.Combine(exchange, LayeredName));
        WriteProgrammaticFdf(
            Path.Combine(exchange, FdfName),
            Path.Combine(exchange, XfdfName));
    }

    private static void WriteTaggedPdf(string path)
    {
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(200, 100));
        page.SetStructParents(7);
        document.AddPage(page);

        var catalog = document.GetDocumentCatalog();
        var markInfo = new PDMarkInfo();
        markInfo.SetMarked(true);
        markInfo.SetUserProperties(true);
        catalog.SetMarkInfo(markInfo);

        var root = new PDStructureTreeRoot();
        root.SetParentTreeNextKey(8);
        root.SetRoleMap(new Dictionary<string, string> { ["CustomSect"] = "Sect" });
        catalog.SetStructureTreeRoot(root);

        var section = new PDStructureElement("CustomSect", root);
        section.SetElementIdentifier("section-1");
        section.SetTitle("Tagged title");
        section.SetLanguage("en-US");
        section.SetAlternateDescription("Tagged alternate");
        section.SetExpandedForm("Expanded section");
        section.SetActualText("Logical section");
        section.SetRevisionNumber(2);
        section.SetPage(page);
        root.AppendKid(section);

        var layout = new PDLayoutAttributeObject();
        layout.SetPlacement(PDLayoutAttributeObject.PlacementBlock);
        layout.SetAllPaddings(4);
        section.AddAttribute(layout);
        section.IncrementRevisionNumber();
        layout.SetSpaceBefore(3);
        section.AddClassName("BodyClass");

        var classLayout = new PDLayoutAttributeObject();
        classLayout.SetPlacement(PDLayoutAttributeObject.PlacementInline);
        root.SetClassMap(
            new Dictionary<string, object> { ["BodyClass"] = classLayout });

        var paragraph = new PDStructureElement("P", section);
        paragraph.SetActualText("Actual tagged text");
        paragraph.SetPage(page);
        paragraph.AppendKid(0);
        section.AppendKid(paragraph);

        var markedReference = new PDMarkedContentReference();
        markedReference.SetPage(page);
        markedReference.SetMCID(1);
        section.AppendKid(markedReference);

        var idTree = new DripSharp.PdfCarton.Pdmodel.PDStructureElementNameTreeNode();
        idTree.SetNames(
            new Dictionary<string, PDStructureElement> { ["section-1"] = section });
        root.SetIDTree(idTree);

        var parents = new COSArray();
        parents.Add(paragraph);
        parents.Add(section);
        var parentTree = new PDNumberTreeNode(typeof(PDParentTreeValue));
        parentTree.SetNumbers(
            new Dictionary<int, COSObjectable>
            {
                [7] = new PDParentTreeValue(parents)
            });
        root.SetParentTree(parentTree);

        var font = new PDType1Font(Standard14Fonts.FontName.Helvetica);
        using (var content = new PDPageContentStream(document, page))
        {
            var firstProperties = new COSDictionary();
            firstProperties.SetInt(COSName.Mcid, 0);
            firstProperties.SetString(COSName.ActualText, "Actual tagged text");
            firstProperties.SetString(COSName.Lang, "en-US");
            content.BeginMarkedContent(COSName.P, PDPropertyList.Create(firstProperties));
            content.BeginText();
            content.SetFont(font, 12);
            content.NewLineAtOffset(10, 70);
            content.ShowText("Visible tagged text");
            content.EndText();
            content.EndMarkedContent();

            var secondProperties = new COSDictionary();
            secondProperties.SetInt(COSName.Mcid, 1);
            secondProperties.SetString(COSName.Alt, "Alternate span");
            content.BeginMarkedContent(
                COSName.GetPDFName("Span"), PDPropertyList.Create(secondProperties));
            content.BeginText();
            content.SetFont(font, 10);
            content.NewLineAtOffset(10, 45);
            content.ShowText("Second span");
            content.EndText();
            content.EndMarkedContent();
        }

        document.Save(path);
    }

    private static void WriteLayeredPdf(string path)
    {
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(100, 100));
        document.AddPage(page);

        var properties = new PDOptionalContentProperties();
        var enabled = new PDOptionalContentGroup("enabled");
        var disabled = new PDOptionalContentGroup("disabled");
        properties.AddGroup(enabled);
        properties.AddGroup(disabled);
        properties.SetGroupEnabled(disabled, false);
        document.GetDocumentCatalog().SetOCProperties(properties);

        var anyOn = Membership(enabled, disabled, COSName.AnyOn);
        var allOn = Membership(enabled, disabled, COSName.AllOn);
        var anyOff = Membership(enabled, disabled, COSName.AnyOff);
        var allOff = Membership(enabled, disabled, COSName.AllOff);

        using (var content = new PDPageContentStream(document, page))
        {
            DrawLayerRect(content, anyOn, 0, 50, 50, 50, 0, 1, 0);
            DrawLayerRect(content, allOn, 50, 50, 50, 50, 1, 0, 0);
            DrawLayerRect(content, anyOff, 0, 0, 50, 50, 0, 0, 1);
            DrawLayerRect(content, allOff, 50, 0, 50, 50, 1, 0, 1);
        }

        document.Save(path);
    }

    private static PDOptionalContentMembershipDictionary Membership(
        PDOptionalContentGroup first,
        PDOptionalContentGroup second,
        COSName policy)
    {
        var membership = new PDOptionalContentMembershipDictionary();
        membership.SetOCGs(new List<PDPropertyList> { first, second });
        membership.SetVisibilityPolicy(policy);
        return membership;
    }

    private static void DrawLayerRect(
        PDPageContentStream content,
        PDPropertyList membership,
        float x,
        float y,
        float width,
        float height,
        float red,
        float green,
        float blue)
    {
        content.BeginMarkedContent(COSName.Oc, membership);
        content.SetNonStrokingColor(red, green, blue);
        content.AddRect(x, y, width, height);
        content.Fill();
        content.EndMarkedContent();
    }

    private static void WriteProgrammaticFdf(string fdfPath, string xfdfPath)
    {
        using var document = NewProgrammaticFdf();
        document.Save(fdfPath);
        document.SaveXFDF(xfdfPath);
    }

    private static FDFDocument NewProgrammaticFdf()
    {
        var document = new FDFDocument();
        var dictionary = document.GetCatalog().GetFDF();
        var file = new PDSimpleFileSpecification();
        file.SetFile("forms/source-target.pdf");
        dictionary.SetFile(file);
        dictionary.SetEncoding("Shift-JIS");

        var ids = new COSArray();
        ids.Add(new COSString("original-id"));
        ids.Add(new COSString("modified-id"));
        dictionary.SetID(ids);

        var alpha = new FDFField();
        alpha.SetPartialFieldName("alpha");
        alpha.SetValue("A&B<1>");
        alpha.SetFieldFlags(3);

        var choice = new FDFField();
        choice.SetPartialFieldName("choice");
        choice.SetValue(new List<string> { "first", "second" });

        var parent = new FDFField();
        parent.SetPartialFieldName("parent");
        var child = new FDFField();
        child.SetPartialFieldName("child");
        child.SetValue("nested");
        parent.SetKids(new List<FDFField> { child });

        dictionary.SetFields(new List<FDFField> { alpha, choice, parent });
        return document;
    }

    private static void ObserveTaggedRoundTrip(string path)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        var catalog = document.GetDocumentCatalog();
        var markInfo = catalog.GetMarkInfo();
        var root = catalog.GetStructureTreeRoot();
        var rootKids = root.GetKids();
        var section = (PDStructureElement)rootKids[0];
        var sectionKids = section.GetKids();
        var paragraph = (PDStructureElement)sectionKids[0];
        var markedReference = (PDMarkedContentReference)sectionKids[1];
        var attributes = section.GetAttributes();
        var classes = section.GetClassNames();
        var role = root.GetRoleMap()["CustomSect"];
        var classMap = root.GetClassMap();
        var idValue = root.GetIDTree().GetValue("section-1");
        var parentValue = root.GetParentTree().GetValue(7);

        Observe(
            "structure-roundtrip",
            "foreign-catalog",
            $"{markInfo.IsMarked()}|{markInfo.UsesUserProperties()}|{rootKids.Count}|{root.GetParentTreeNextKey()}");
        Observe(
            "structure-model",
            "logical-reading-data",
            $"{section.GetStructureType()}|{section.GetStandardStructureType()}|{section.GetElementIdentifier()}|{section.GetTitle()}|{section.GetLanguage()}|{section.GetAlternateDescription()}|{section.GetExpandedForm()}|{section.GetActualText()}");
        Observe(
            "structure-model",
            "kid-order",
            $"{KidKind(sectionKids[0])},{KidKind(sectionKids[1])}|{paragraph.GetActualText()}|{markedReference.GetMCID()}");
        Observe(
            "structure-attributes",
            "revisions",
            $"{section.GetRevisionNumber()}|{attributes.Size()}|{attributes.GetRevisionNumber(0)}|{classes.Size()}|{classes.GetObject(0)}|{classes.GetRevisionNumber(0)}");
        Observe(
            "structure-attributes",
            "maps",
            $"{role}|{classMap.Count}|{idValue.GetElementIdentifier()}");
        Observe(
            "structure-parent-tree",
            "number-tree",
            $"{root.GetParentTree().GetLowerLimit()}|{root.GetParentTree().GetUpperLimit()}|{ParentTreeSize(parentValue)}");

        var extractor = new PDFMarkedContentExtractor();
        extractor.ProcessPage(document.GetPage(0));
        var marked = extractor.GetMarkedContents();
        Observe(
            "marked-content",
            "extracted-order",
            string.Join(
                ",",
                marked.Select(
                    item =>
                        $"{item.GetTag()}:{item.GetMCID()}:{item.GetActualText() ?? "-"}:{item.GetAlternateDescription() ?? "-"}")));
        Observe(
            "marked-content",
            "extracted-text",
            string.Join(
                "|",
                marked.Select(
                    item => string.Concat(
                        item.GetContents()
                            .OfType<TextPosition>()
                            .Select(position => position.GetUnicode())))));
    }

    private static string KidKind(object kid) =>
        kid switch
        {
            PDStructureElement => "StructElem",
            PDMarkedContentReference => "MCR",
            PDObjectReference => "OBJR",
            int => "MCID",
            _ => kid.GetType().Name
        };

    private static int ParentTreeSize(object value)
    {
        var parent = (PDParentTreeValue)value;
        return ((COSArray)parent.GetCOSObject()).Size();
    }

    private static void ObserveOptionalContentModel(string exchange)
    {
        var properties = new PDOptionalContentProperties();
        var enabled = new PDOptionalContentGroup("enabled");
        var disabled = new PDOptionalContentGroup("disabled");
        properties.AddGroup(enabled);
        properties.AddGroup(disabled);
        var firstDisable = properties.SetGroupEnabled(disabled, false);
        var secondDisable = properties.SetGroupEnabled(disabled, false);

        Observe(
            "optional-model",
            "groups",
            $"{string.Join(",", properties.GetGroupNames())}|{properties.GetOptionalContentGroups().Count}|{properties.HasGroup("enabled")}");
        Observe(
            "optional-model",
            "base-and-overrides",
            $"{properties.GetBaseState()}|{properties.IsGroupEnabled(enabled)}|{properties.IsGroupEnabled(disabled)}|{firstDisable}|{secondDisable}");

        properties.SetBaseState(PDOptionalContentProperties.BaseState.Off);
        properties.SetGroupEnabled(enabled, true);
        Observe(
            "optional-model",
            "off-base",
            $"{properties.GetBaseState()}|{properties.IsGroupEnabled(enabled)}|{properties.IsGroupEnabled(disabled)}");

        var usage = new COSDictionary();
        var view = new COSDictionary();
        view.SetItem(COSName.ViewState, COSName.OFF);
        usage.SetItem(COSName.View, view);
        var print = new COSDictionary();
        print.SetItem(COSName.PrintState, COSName.On);
        usage.SetItem(COSName.Print, print);
        var export = new COSDictionary();
        export.SetItem(COSName.ExportState, COSName.OFF);
        usage.SetItem(COSName.Export, export);
        enabled.GetCOSObject().SetItem(COSName.Usage, usage);
        Observe(
            "optional-model",
            "usage-applications",
            $"{enabled.GetRenderState(RenderDestination.View)}|{enabled.GetRenderState(RenderDestination.Print)}|{enabled.GetRenderState(RenderDestination.Export)}");

        var membership = Membership(enabled, disabled, COSName.AllOn);
        Observe(
            "optional-membership",
            "policy-and-order",
            $"{membership.GetVisibilityPolicy().GetName()}|{string.Join(",", membership.GetOCGs().Select(group => ((PDOptionalContentGroup)group).GetName()))}");

        using var loaded = Loader.LoadPDF(
            new FileInfo(Path.Combine(exchange, LayeredName)));
        ObserveRenderedPixels(loaded, "local-policy-pixels", "optional-render");
    }

    private static void ObserveLayeredRoundTrip(string path)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        var properties = document.GetDocumentCatalog().GetOCProperties();
        var renderer = new PDFRenderer(document);
        Observe(
            "optional-roundtrip",
            "foreign-layer-config",
            $"{string.Join(",", properties.GetGroupNames())}|{renderer.IsGroupEnabled(properties.GetGroup("enabled"))}|{renderer.IsGroupEnabled(properties.GetGroup("disabled"))}");
        ObserveRenderedPixels(document, "foreign-policy-pixels", "optional-roundtrip");
    }

    private static void ObserveRenderedPixels(
        PDDocument document, string id, string family)
    {
        using var bitmap = new PDFRenderer(document).RenderImage(0, 1);
        var points = new[] { (25, 25), (75, 25), (25, 75), (75, 75) };
        Observe(
            family,
            id,
            string.Join(
                ",",
                points.Select(point =>
                {
                    var color = bitmap.GetPixel(point.Item1, point.Item2);
                    return $"{color.Red:X2}{color.Green:X2}{color.Blue:X2}";
                })));
    }

    private static void ObserveFdfModel()
    {
        using var document = NewProgrammaticFdf();
        var dictionary = document.GetCatalog().GetFDF();
        var fields = dictionary.GetFields();
        var choice = (IList<string>)fields[1].GetValue();
        Observe(
            "fdf-model",
            "fields",
            $"{fields.Count}|{string.Join(",", fields.Select(field => field.GetPartialFieldName()))}|{fields[0].GetValue()}|{string.Join(",", choice)}|{fields[2].GetKids()[0].GetValue()}");
        Observe(
            "fdf-model",
            "dictionary",
            $"{dictionary.GetFile().GetFile()}|{dictionary.GetEncoding()}|{dictionary.GetID().Size()}|{fields[0].GetFieldFlags()}");

        var annotation = new FDFAnnotationText();
        annotation.SetPage(2);
        annotation.SetContents("Text & <markup>");
        annotation.SetTitle("Reviewer");
        annotation.SetPrinted(true);
        annotation.SetRectangle(new PDRectangle(1, 2, 30, 40));
        annotation.SetIcon("Comment");
        Observe(
            "fdf-model",
            "annotation",
            $"{annotation.GetPage()}|{annotation.GetContents()}|{annotation.GetTitle()}|{annotation.IsPrinted()}|{annotation.GetRectangle().GetWidth()}x{annotation.GetRectangle().GetHeight()}|{annotation.GetIcon()}");

        var xml = WriteXfdfString(document);
        Observe(
            "xfdf-model",
            "programmatic-order",
            $"{xml.IndexOf("name=\"alpha\"", StringComparison.Ordinal) < xml.IndexOf("name=\"choice\"", StringComparison.Ordinal)}|{xml.IndexOf("name=\"choice\"", StringComparison.Ordinal) < xml.IndexOf("name=\"parent\"", StringComparison.Ordinal)}|{xml.Contains("A&amp;B&lt;1&gt;", StringComparison.Ordinal)}");
        Observe("xfdf-encoding", "programmatic-sha256", Sha256(xml));
    }

    private static string WriteXfdfString(FDFDocument document)
    {
        var writer = new StringWriter(CultureInfo.InvariantCulture);
        document.SaveXFDF(writer);
        return writer.ToString();
    }

    private static void ObserveFixtures(string fixtures)
    {
        foreach (var name in new[] { "withcatalog.fdf", "nocatalog.fdf" })
        {
            using var document = Loader.LoadFDF(
                Path.Combine(fixtures, "org", "apache", "pdfbox", "pdfparser", name));
            var fields = document.GetCatalog().GetFDF().GetFields();
            Observe(
                "fdf-fixture",
                name,
                string.Join(
                    ",",
                    fields.Select(
                        field => $"{field.GetPartialFieldName()}={field.GetValue()}")));
        }

        var taggedFixture = Path.Combine(
            fixtures, "org", "apache", "pdfbox", "pdmodel",
            "documentinterchange", "logicalstructure", "PDFBOX-2725-878725.pdf");
        using (var document = Loader.LoadPDF(new FileInfo(taggedFixture)))
        {
            var root = document.GetDocumentCatalog().GetStructureTreeRoot();
            var kids = root.GetKids();
            Observe(
                "structure-fixture",
                "pdfbox-2725",
                $"{document.GetNumberOfPages()}|{(root != null)}|{kids.Count}|{string.Join(",", kids.OfType<PDStructureElement>().Select(element => element.GetStructureType()))}");
        }

        var xfdfFixture = Path.Combine(
            fixtures, "org", "apache", "pdfbox", "pdmodel", "fdf",
            "xfdf-test-document-annotations.xml");
        using (var document = Loader.LoadXFDF(xfdfFixture))
        {
            var annotations = document.GetCatalog().GetFDF().GetAnnotations();
            var freeText = annotations
                .OfType<FDFAnnotationFreeText>()
                .First(annotation => annotation.GetContents() == "P&1 P&2 P&3");
            Observe(
                "xfdf-annotations",
                "fixture-types",
                $"{annotations.Count}|{string.Join(",", annotations.Select(AnnotationKind))}");
            Observe(
                "xfdf-annotations",
                "rich-content",
                $"{freeText.GetContents()}|{freeText.GetRichContents().Contains("P&amp;1", StringComparison.Ordinal)}|{freeText.GetRichContents().Contains("P&amp;2", StringComparison.Ordinal)}|{freeText.GetRichContents().Contains("P&amp;3", StringComparison.Ordinal)}");

            var xmlDocument = new XmlDocument();
            xmlDocument.Load(xfdfFixture);
            var root = xmlDocument.DocumentElement!;
            Observe(
                "xfdf-namespace",
                "fixture-root",
                $"{root.LocalName}|{root.NamespaceURI}|{root.GetAttribute("space", "http://www.w3.org/XML/1998/namespace")}");
        }
    }

    private static string AnnotationKind(FDFAnnotation annotation) =>
        annotation.GetType().Name.Replace("FDFAnnotation", string.Empty);

    private static void ObserveFdfRoundTrip(string path)
    {
        using var document = Loader.LoadFDF(path);
        var dictionary = document.GetCatalog().GetFDF();
        var fields = dictionary.GetFields();
        Observe(
            "fdf-roundtrip",
            "foreign-binary",
            $"{dictionary.GetFile().GetFile()}|{dictionary.GetEncoding()}|{dictionary.GetID().Size()}|{string.Join(",", fields.Select(field => field.GetPartialFieldName()))}|{fields[0].GetValue()}");
    }

    private static void ObserveXfdfRoundTrip(string path)
    {
        var bytes = File.ReadAllBytes(path);
        using var document = Loader.LoadXFDF(path);
        var dictionary = document.GetCatalog().GetFDF();
        var fields = dictionary.GetFields();
        var root = LoadXml(bytes).DocumentElement!;
        Observe(
            "xfdf-roundtrip",
            "foreign-xml",
            $"{dictionary.GetFile().GetFile()}|{string.Join(",", fields.Select(field => field.GetPartialFieldName()))}|{fields[0].GetValue()}|{fields[2].GetKids()[0].GetValue()}");
        Observe(
            "xfdf-namespace",
            "foreign-root",
            $"{root.LocalName}|{root.NamespaceURI}|{root.GetAttribute("space", "http://www.w3.org/XML/1998/namespace")}");
        Observe(
            "xfdf-encoding",
            "foreign-bytes",
            $"{HasUtf8Declaration(bytes)}|{HasUtf8Bom(bytes)}|{Encoding.UTF8.GetString(bytes).Contains("A&amp;B&lt;1&gt;", StringComparison.Ordinal)}");
    }

    private static XmlDocument LoadXml(byte[] bytes)
    {
        var document = new XmlDocument();
        using var input = new MemoryStream(bytes);
        document.Load(input);
        return document;
    }

    private static bool HasUtf8Declaration(byte[] bytes) =>
        Encoding.UTF8.GetString(bytes)
            .StartsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", StringComparison.Ordinal);

    private static bool HasUtf8Bom(byte[] bytes) =>
        bytes.Length >= 3
        && bytes[0] == 0xEF
        && bytes[1] == 0xBB
        && bytes[2] == 0xBF;

    private static string Sha256(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)))
            .ToLowerInvariant();

    private static void ObserveFailures()
    {
        var unknown = new FDFField();
        unknown.SetValue(COSInteger.Get(42));
        Observe(
            "fdf-failure",
            "unknown-field-value",
            FailureKind(() => { _ = unknown.GetValue(); }));
        Observe(
            "xfdf-failure",
            "wrong-root",
            FailureKind(
                () =>
                {
                    using var input = new MemoryStream(
                        Encoding.UTF8.GetBytes("<not-xfdf/>"));
                    using var ignored = Loader.LoadXFDF(input);
                }));
        Observe(
            "xfdf-failure",
            "malformed-xml",
            FailureKind(
                () =>
                {
                    using var input = new MemoryStream(
                        Encoding.UTF8.GetBytes("<xfdf><fields>"));
                    using var ignored = Loader.LoadXFDF(input);
                }));
    }

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (XmlException)
        {
            return "xml";
        }
        catch (Exception exception)
        {
            return exception.GetType().Name;
        }
    }
}
