using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using DripSharp.Runtime;
using PdfCube.FontBox.Ttf.Gsub;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Pdfwriter.Compress;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Common;
using PdfCube.PdfBox.Pdmodel.Font;
using PdfCube.PdfBox.Pdmodel.Font.Encoding;
using PdfCube.PdfBox.Text;
using PdfCube.PdfBox.Util;
using SkiaSharp;

internal static class Program
{
    private static readonly List<string> Observations = new();

    private static string pdfboxRoot = null!;
    private static string testResources = null!;

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 3 or > 4)
                throw new ArgumentException(
                    "Expected output trace, PDFBox checkout, exchange directory, and optional --write-only.");
            if (args.Length == 4 && args[3] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            pdfboxRoot = args[1];
            testResources = Path.Combine(
                pdfboxRoot, "pdfbox", "src", "test", "resources");
            var exchange = args[2];
            Directory.CreateDirectory(exchange);
            var ownPdf = Path.Combine(exchange, "dotnet-font-text.pdf");
            WriteRepresentative(ownPdf);
            if (args.Length == 4)
                return 0;

            ObserveDirectModels();
            ObserveComplexText();
            ObserveExtraction(ownPdf, "synthetic");
            ObserveFixture(
                Path.Combine(
                    testResources, "input", "sample_fonts_solidconvertor.pdf"),
                "sample-fonts");
            ObserveFixture(
                Path.Combine(
                    testResources,
                    "input",
                    "PDFBOX-3127-RAU4G6QMOVRYBISJU7R6MOVZCRFUO7P4-VFont.pdf"),
                "vertical-font");
            ObserveFixture(
                Path.Combine(
                    testResources,
                    "org",
                    "apache",
                    "pdfbox",
                    "pdmodel",
                    "font",
                    "F001u_3_7j.pdf"),
                "f001");
            ObserveFixture(
                Path.Combine(testResources, "input", "FC60_Times.pdf"),
                "times");
            ObserveFixture(
                Path.Combine(
                    testResources,
                    "input",
                    "PDFBOX-3044-010197-p5-ligatures.pdf"),
                "type1c");
            ObserveFixture(
                Path.Combine(
                    testResources, "input", "PDFBOX-3053-reduced.pdf"),
                "type3");
            ObserveFixture(
                Path.Combine(
                    testResources, "input", "PDFBOX-3062-005717-p1.pdf"),
                "cid-type0");
            ObserveFixture(
                Path.Combine(
                    testResources,
                    "input",
                    "PDFBOX-4322-Empty-ToUnicode-reduced.pdf"),
                "empty-to-unicode");
            ObserveArticles(
                Path.Combine(
                    testResources, "input", "PDFBOX-3110-poems-beads.pdf"));
            ObserveCrossRuntime(Path.Combine(exchange, "java-font-text.pdf"));

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
        var ttf = Path.Combine(
            pdfboxRoot,
            "pdfbox",
            "src",
            "main",
            "resources",
            "org",
            "apache",
            "pdfbox",
            "resources",
            "ttf",
            "LiberationSans-Regular.ttf");
        var pfb = Path.Combine(
            pdfboxRoot,
            "fontbox",
            "target",
            "fonts",
            "DejaVuSerifCondensed.pfb");
        if (!File.Exists(ttf) || !File.Exists(pfb))
            throw new IOException("Representative upstream font fixture is missing");

        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(420, 520));
        document.AddPage(page);

        var standard = new PDType1Font(Standard14Fonts.FontName.Helvetica);
        var type0 = PDType0Font.Load(document, new FileInfo(ttf));
        var trueType = PDTrueTypeFont.Load(
            document, new FileInfo(ttf), WinAnsiEncoding.Instance);
        PDType1Font embeddedType1;
        using (var input = File.OpenRead(pfb))
        {
            embeddedType1 =
                new PDType1Font(document, input, WinAnsiEncoding.Instance);
        }

        using (var content = new PDPageContentStream(document, page))
        {
            Show(content, standard, 12, 40, 60, "bottom");
            Show(content, type0, 14, 40, 340, "Type0 café");
            Show(content, trueType, 13, 40, 290, "TrueType");
            Show(content, embeddedType1, 12, 40, 240, "Type1");
            Show(content, standard, 12, 40, 440, "top");

            content.BeginText();
            content.SetFont(standard, 12);
            content.SetTextMatrix(Matrix.GetTranslateInstance(40, 190));
            content.ShowTextWithPositioning(new object[] { "word", -2200f, "gap" });
            content.EndText();

            Show(content, standard, 12, 40, 140, "duplicate");
            Show(content, standard, 12, 40, 140, "duplicate");
        }
        document.Save(new FileInfo(path), CompressParameters.NoCompression);
    }

    private static void Show(
        PDPageContentStream content,
        PDFont font,
        float size,
        float x,
        float y,
        string text)
    {
        content.BeginText();
        content.SetFont(font, size);
        content.SetTextMatrix(Matrix.GetTranslateInstance(x, y));
        content.ShowText(text);
        content.EndText();
    }

    private static void ObserveDirectModels()
    {
        var ttf = Path.Combine(
            pdfboxRoot,
            "pdfbox",
            "src",
            "main",
            "resources",
            "org",
            "apache",
            "pdfbox",
            "resources",
            "ttf",
            "LiberationSans-Regular.ttf");
        var pfb = Path.Combine(
            pdfboxRoot,
            "fontbox",
            "target",
            "fonts",
            "DejaVuSerifCondensed.pfb");

        using var document = new PDDocument();
        PDFont standard =
            new PDType1Font(Standard14Fonts.FontName.Helvetica);
        PDType0Font type0;
        using (var input = File.OpenRead(ttf))
        {
            type0 = PDType0Font.Load(document, input, false);
        }
        var trueType = PDTrueTypeFont.Load(
            document, new FileInfo(ttf), WinAnsiEncoding.Instance);
        PDType1Font embeddedType1;
        using (var input = File.OpenRead(pfb))
        {
            embeddedType1 =
                new PDType1Font(document, input, WinAnsiEncoding.Instance);
        }

        Observe(
            "type-1",
            "standard",
            ((object)standard).GetType().Name,
            standard.GetSubType(),
            standard.GetName());
        Observe(
            "standard-font",
            "helvetica",
            standard.IsStandard14(),
            standard.IsEmbedded(),
            standard.GetFontDescriptor() is not null);
        Observe(
            "encoding",
            "win-ansi-and-standard",
            ((PDSimpleFont)standard).GetEncoding().GetEncodingName(),
            trueType.GetEncoding().GetEncodingName(),
            embeddedType1.GetEncoding().GetEncodingName());

        Observe(
            "type-0",
            "embedded-truetype",
            ((object)type0).GetType().Name,
            type0.GetSubType(),
            type0.IsEmbedded(),
            type0.IsDamaged());
        var descendant = type0.GetDescendantFont();
        Observe(
            "cid",
            "type0-descendant",
            ((object)descendant).GetType().Name,
            descendant.GetBaseFont(),
            descendant.IsEmbedded(),
            descendant.IsDamaged());
        Observe(
            "true-type",
            "simple-embedded",
            ((object)trueType).GetType().Name,
            trueType.IsEmbedded(),
            trueType.IsDamaged(),
            trueType.GetTrueTypeFont().GetName());
        Observe(
            "embedded",
            "three-paths",
            type0.IsEmbedded(),
            trueType.IsEmbedded(),
            embeddedType1.IsEmbedded());

        var type0Code = ReadCode(type0, "A");
        var trueTypeCode = ReadCode(trueType, "A");
        var standardCode = ReadCode(standard, "A");
        Observe(
            "to-unicode",
            "font-cmaps",
            type0Code,
            type0.ToUnicode(type0Code),
            trueTypeCode,
            trueType.ToUnicode(trueTypeCode),
            standardCode,
            standard.ToUnicode(standardCode));
        Observe(
            "glyph-mapping",
            "codes-to-glyphs",
            type0.GetDescendantFont().CodeToCID(type0Code),
            type0.GetDescendantFont().CodeToGID(type0Code),
            trueType.CodeToGID(trueTypeCode),
            trueType.HasGlyph(trueTypeCode));
        Observe(
            "width-advance",
            "font-widths",
            Number(standard.GetWidth(standardCode)),
            Number(standard.GetStringWidth("ABC")),
            Number(type0.GetWidth(type0Code)),
            Number(type0.GetStringWidth("ABC")),
            Number(trueType.GetWidth(trueTypeCode)));
        var displacement = type0.GetDisplacement(type0Code);
        Observe(
            "displacement",
            "horizontal",
            Number(displacement.GetX()),
            Number(displacement.GetY()),
            type0.IsVertical());
        Observe(
            "missing-glyph",
            "unassigned-code-point",
            Fails(() => type0.Encode("\u0378")),
            Fails(() => trueType.Encode("\u0378")),
            Fails(() => standard.Encode("\u0378")));

        var damaged = DamagedTrueType(document);
        var damagedSimple = (PDSimpleFont)damaged;
        Observe(
            "damaged-font",
            "invalid-embedded-truetype",
            damaged.IsDamaged(),
            damaged.IsEmbedded(),
            damagedSimple.GetFontBoxFont() is not null);
        Observe(
            "substituted",
            "damaged-font-substitution",
            !damaged.IsEmbedded(),
            damagedSimple.GetFontBoxFont() is not null,
            damaged.GetName());
        Observe(
            "fallback",
            "damaged-font-readable",
            damagedSimple.GetFontBoxFont() is not null,
            damaged.GetWidth(65) > 0,
            damaged.GetBoundingBox() is not null);

        ObserveTextPositions(standard);
    }

    private static void ObserveComplexText()
    {
        var fontboxResources = Path.Combine(
            pdfboxRoot, "fontbox", "src", "test", "resources");
        ObserveGsubCase(
            "bengali-conjunct",
            Path.Combine(fontboxResources, "ttf", "Lohit-Bengali.ttf"),
            "ক্ষীরের",
            true,
            false);
        ObserveGsubCase(
            "devanagari-combining",
            Path.Combine(fontboxResources, "ttf", "Lohit-Devanagari.ttf"),
            "य़ज़क़",
            true,
            true);
        ObserveGsubCase(
            "gujarati-conjunct",
            Path.Combine(fontboxResources, "ttf", "Lohit-Gujarati.ttf"),
            "ક્ષજ્ઞત્તશ્ર",
            true,
            false);
        ObserveGsubCase(
            "latin-ligature",
            Path.Combine(fontboxResources, "ttf", "JosefinSans-Italic.ttf"),
            "office",
            false,
            false);

        ObservePositionedComplexFixture(
            Path.Combine(
                testResources, "input", "PDFBOX-4531-bidi-ligature-1.pdf"),
            "arabic-hebrew-ligatures",
            true);
        ObservePositionedComplexFixture(
            Path.Combine(
                testResources,
                "input",
                "PDFBOX-5747-unicode-surrogate-with-diacritic-reduced.pdf"),
            "surrogate-combining-mark",
            false);

        var pdfCubeAssemblies = AppDomain.CurrentDomain.GetAssemblies()
            .Where(assembly =>
                assembly.GetName().Name?.StartsWith(
                    "PdfCube.", StringComparison.Ordinal) == true)
            .ToList();
        var harfBuzzAssemblyReferences = pdfCubeAssemblies
            .SelectMany(assembly => assembly.GetReferencedAssemblies())
            .Count(name =>
                name.Name?.Contains(
                    "HarfBuzz", StringComparison.OrdinalIgnoreCase) == true);
        var harfBuzzPublicReferences = pdfCubeAssemblies
            .SelectMany(assembly => assembly.GetExportedTypes())
            .SelectMany(type =>
                new[] { type.FullName ?? type.Name }
                    .Concat(type.GetMembers().Select(member => member.ToString() ?? "")))
            .Count(value =>
                value.Contains("HarfBuzz", StringComparison.OrdinalIgnoreCase));
        Observe(
            "harfbuzz-boundary",
            "public-package-closure",
            harfBuzzAssemblyReferences,
            harfBuzzPublicReferences);
    }

    private static void ObserveGsubCase(
        string id,
        string fontPath,
        string value,
        bool indic,
        bool combining)
    {
        if (!File.Exists(fontPath))
            throw new IOException(
                "Complex-text font fixture is missing: " + fontPath);

        using var document = new PDDocument();
        using var input = File.OpenRead(fontPath);
        var font = PDType0Font.Load(document, input, false);
        var cmap = font.GetCmapLookup();
        var originalGlyphs = value.EnumerateRunes()
            .Select(rune => cmap.GetGlyphId(rune.Value))
            .ToList();
        var worker = new GsubWorkerFactory()
            .GetGsubWorker(cmap, font.GetGsubData());
        var shapedGlyphs = worker.ApplyTransforms(originalGlyphs);
        var shapedBytes = EncodeGlyphs(font, shapedGlyphs);
        var directBytes = font.Encode(value);

        var page = new PDPage(new PDRectangle(420, 120));
        document.AddPage(page);
        using (var content = new PDPageContentStream(
                   document,
                   page,
                   PDPageContentStream.AppendMode.Overwrite,
                   false,
                   false))
        {
            Show(content, font, 18, 20, 60, value);
        }
        byte[] contentBytes;
        using (var content = page.GetContents())
        using (var output = new MemoryStream())
        {
            content.CopyTo(output);
            contentBytes = output.ToArray();
        }

        var glyphPositions = string.Join(
            ";", shapedGlyphs.Select(glyph => GlyphPosition(font, glyph)));
        if (indic)
        {
            Observe(
                "complex-text-indic",
                id,
                CodePoints(value),
                Integers(originalGlyphs),
                Integers(shapedGlyphs),
                originalGlyphs.Count,
                shapedGlyphs.Count);
        }
        if (combining)
        {
            Observe(
                "complex-text-combining",
                id,
                CodePoints(value),
                Integers(originalGlyphs),
                Integers(shapedGlyphs));
        }
        if (id.Contains("ligature", StringComparison.Ordinal))
        {
            Observe(
                "complex-text-ligature",
                id,
                CodePoints(value),
                Integers(originalGlyphs),
                Integers(shapedGlyphs));
        }
        var codePointCount = value.EnumerateRunes().Count();
        Observe(
            "complex-text-cluster",
            id,
            codePointCount,
            shapedGlyphs.Count,
            shapedGlyphs.Count < codePointCount);
        Observe(
            "complex-text-glyph-position",
            id,
            glyphPositions);
        Observe(
            "unicode-text-shaping",
            id,
            Hex(directBytes),
            Hex(shapedBytes),
            Hex(contentBytes),
            !directBytes.SequenceEqual(shapedBytes),
            ContainsBytes(contentBytes, PdfStringBytes(shapedBytes)));
    }

    private static void ObservePositionedComplexFixture(
        string path,
        string id,
        bool arabic)
    {
        using var document = Loader.LoadPDF(new FileInfo(path));
        var before = ContentBytes(document);
        var collector = new PositionCollector();
        var unsorted = collector.GetText(document);
        var after = ContentBytes(document);
        var sorted = Extract(document, true, true, true);
        var clusters = collector.ClusterCount();
        var positions = collector.DetailedSummary();

        Observe(
            "content-stream-integrity",
            id,
            Sha256(before),
            before.SequenceEqual(after),
            before.Length);
        Observe(
            "complex-text-glyph-position",
            id,
            collector.Positions.Count,
            positions);
        Observe(
            "complex-text-cluster",
            id,
            clusters,
            positions);
        if (arabic)
        {
            Observe(
                "complex-text-arabic",
                id,
                unsorted,
                clusters,
                positions);
            Observe(
                "complex-text-ligature",
                id,
                clusters,
                positions);
            Observe(
                "complex-text-direction",
                id,
                unsorted,
                sorted,
                positions);
        }
        else
        {
            Observe(
                "complex-text-combining",
                id,
                unsorted,
                clusters,
                positions);
        }
    }

    private static sbyte[] EncodeGlyphs(PDType0Font font, IList<int> glyphs)
    {
        var result = new List<sbyte>();
        foreach (var glyph in glyphs)
            result.AddRange(font.EncodeGlyphId(glyph));
        return result.ToArray();
    }

    private static string GlyphPosition(PDType0Font font, int glyph)
    {
        var encoded = font.EncodeGlyphId(glyph);
        var code = font.ReadCode(new MemoryStream(
            encoded.Select(item => unchecked((byte)item)).ToArray()));
        var displacement = font.GetDisplacement(code);
        return glyph + "@" + Number(displacement.GetX())
            + "," + Number(displacement.GetY());
    }

    private static byte[] ContentBytes(PDDocument document)
    {
        using var output = new MemoryStream();
        foreach (var page in document.GetPages())
        {
            using var input = page.GetContents();
            input.CopyTo(output);
            output.WriteByte(0xff);
        }
        return output.ToArray();
    }

    private static bool ContainsBytes(byte[] haystack, byte[] needle)
    {
        for (var start = 0; start <= haystack.Length - needle.Length; start++)
        {
            var matched = true;
            for (var offset = 0; offset < needle.Length; offset++)
            {
                if (haystack[start + offset] == needle[offset])
                    continue;
                matched = false;
                break;
            }
            if (matched)
                return true;
        }
        return false;
    }

    private static byte[] PdfStringBytes(sbyte[] value)
    {
        if (value.Any(item =>
                item < 0 || item == (sbyte)'\r' || item == (sbyte)'\n'))
            return System.Text.Encoding.ASCII.GetBytes(
                "<" + Hex(value).ToUpperInvariant() + ">");

        using var output = new MemoryStream();
        output.WriteByte((byte)'(');
        foreach (var item in value)
        {
            var current = unchecked((byte)item);
            if (current is (byte)'(' or (byte)')' or (byte)'\\')
                output.WriteByte((byte)'\\');
            output.WriteByte(current);
        }
        output.WriteByte((byte)')');
        return output.ToArray();
    }

    private static string CodePoints(string value) =>
        string.Join(
            ",",
            value.EnumerateRunes().Select(
                rune => rune.Value.ToString("X4", CultureInfo.InvariantCulture)));

    private static string Integers(IEnumerable<int> values) =>
        string.Join(",", values);

    private static string Hex(IEnumerable<sbyte> value) =>
        string.Concat(value.Select(
            item => unchecked((byte)item).ToString(
                "x2", CultureInfo.InvariantCulture)));

    private static string Hex(IEnumerable<byte> value) =>
        string.Concat(value.Select(
            item => item.ToString("x2", CultureInfo.InvariantCulture)));

    private static PDFont DamagedTrueType(PDDocument document)
    {
        var dictionary = new COSDictionary();
        dictionary.SetItem(COSName.Type, COSName.Font);
        dictionary.SetItem(COSName.Subtype, COSName.TrueType);
        dictionary.SetName(COSName.BaseFont, "LiberationSans");
        var descriptor = new COSDictionary();
        descriptor.SetItem(COSName.Type, COSName.FontDesc);
        descriptor.SetName(COSName.FontName, "LiberationSans");
        var invalid = new PDStream(
            document, new MemoryStream(new byte[] { 0, 1, 2, 3, 4 }));
        descriptor.SetItem(COSName.FontFile2, invalid);
        dictionary.SetItem(COSName.FontDesc, descriptor);
        return new PDTrueTypeFont(dictionary);
    }

    private static void ObserveTextPositions(PDFont font)
    {
        var first = new TextPosition(
            0,
            420,
            520,
            new Matrix(2, 0, 0, 3, 40, 440),
            52,
            440,
            9,
            12,
            4,
            "A",
            new[] { 65 },
            font,
            12,
            12);
        var second = new TextPosition(
            0,
            420,
            520,
            new Matrix(2, 0, 0, 3, 60, 440),
            72,
            440,
            9,
            12,
            4,
            "B",
            new[] { 66 },
            font,
            12,
            12);
        var diacritic = new TextPosition(
            0,
            420,
            520,
            new Matrix(1, 0, 0, 1, 44, 440),
            48,
            440,
            4,
            4,
            2,
            "`",
            new[] { 96 },
            font,
            12,
            12);
        Observe(
            "text-position",
            "public-construction",
            first.GetUnicode(),
            first.GetCharacterCodes()[0],
            Number(first.GetX()),
            Number(first.GetY()),
            Number(first.GetXDirAdj()),
            Number(first.GetYDirAdj()),
            Number(first.GetWidthDirAdj()),
            Number(first.GetHeightDir()),
            Number(first.GetXScale()),
            Number(first.GetYScale()),
            first.Contains(diacritic),
            first.CompletelyContains(diacritic));
        Observe(
            "text-matrix",
            "position-matrix",
            MatrixValue(first.GetTextMatrix()),
            Number(first.GetDir()),
            Number(first.GetFontSize()),
            Number(first.GetFontSizeInPt()));
        Observe(
            "text-position-comparator",
            "same-line",
            new TextPositionComparator().Compare(first, second),
            new TextPositionComparator().Compare(second, first));
        Observe(
            "positioned-text",
            "diacritic",
            diacritic.IsDiacritic(),
            first.IsDiacritic(),
            Merge(first, diacritic));
    }

    private static string Merge(TextPosition basis, TextPosition diacritic)
    {
        basis.MergeDiacritic(diacritic);
        return basis.GetUnicode();
    }

    private static void ObserveExtraction(string pdf, string id)
    {
        using var document = Loader.LoadPDF(new FileInfo(pdf));
        var unsorted = Extract(document, false, true, true);
        var sorted = Extract(document, true, true, true);
        var duplicates = Extract(document, true, false, true);
        Observe("extraction-api", id + "-get-text", unsorted);
        Observe("sorting", id, unsorted, sorted, unsorted != sorted);
        Observe(
            "duplicate-suppression",
            id,
            Count(duplicates, "duplicate"),
            Count(sorted, "duplicate"),
            duplicates.Length,
            sorted.Length);
        Observe(
            "word-separation",
            id,
            sorted.Contains("word<W>gap", StringComparison.Ordinal),
            sorted);
        Observe(
            "line-separation",
            id,
            Count(sorted, "<L>"),
            sorted.Contains("top<L>", StringComparison.Ordinal),
            sorted.Contains("bottom", StringComparison.Ordinal));

        var collector = new PositionCollector();
        collector.SetSortByPosition(true);
        collector.GetText(document);
        Observe(
            "positioned-text",
            id + "-positions",
            collector.Positions.Count,
            collector.Summary());

        var byArea = new PDFTextStripperByArea();
        byArea.SetLineSeparator("<L>");
        byArea.AddRegion("top", new SKRect(0, 0, 420, 260));
        byArea.ExtractRegions(document.GetPage(0));
        Observe(
            "extraction-api",
            id + "-by-area",
            byArea.GetRegions().Count,
            byArea.GetTextForRegion("top"));
    }

    private static string Extract(
        PDDocument document,
        bool sort,
        bool suppressDuplicates,
        bool separateByBeads)
    {
        var stripper = new PDFTextStripper();
        stripper.SetSortByPosition(sort);
        stripper.SetSuppressDuplicateOverlappingText(suppressDuplicates);
        stripper.SetShouldSeparateByBeads(separateByBeads);
        stripper.SetLineSeparator("<L>");
        stripper.SetWordSeparator("<W>");
        stripper.SetPageStart("<P>");
        stripper.SetPageEnd("</P>");
        stripper.SetParagraphStart("<G>");
        stripper.SetParagraphEnd("</G>");
        stripper.SetArticleStart("<A>");
        stripper.SetArticleEnd("</A>");
        return stripper.GetText(document);
    }

    private static void ObserveFixture(string pdf, string id)
    {
        using var document = Loader.LoadPDF(new FileInfo(pdf));
        var inventory = new List<string>();
        var vertical = false;
        var pageIndex = 0;
        foreach (var page in document.GetPages())
        {
            var resources = page.GetResources();
            if (resources is null)
            {
                pageIndex++;
                continue;
            }
            var names = resources.GetFontNames()
                .OrderBy(name => name.GetName(), StringComparer.Ordinal)
                .ToList();
            foreach (var resourceName in names)
            {
                var font = resources.GetFont(resourceName);
                var fontId =
                    id + "-p" + pageIndex + "-" + resourceName.GetName();
                var value = string.Join(
                    "|",
                    ((object)font).GetType().Name,
                    font.GetName(),
                    font.GetSubType(),
                    Text(font.IsEmbedded()),
                    Text(font.IsDamaged()),
                    Text(font.IsStandard14()),
                    Text(font.IsVertical()));
                inventory.Add(fontId + "=" + value);
                Observe("font-dictionary", fontId, value);
                ObserveFontFamily(font, fontId, value);
                vertical |= font.IsVertical();
            }
            pageIndex++;
        }
        var extracted = Extract(document, true, true, true);
        Observe(
            "representative-pdf",
            id,
            document.GetNumberOfPages(),
            inventory.Count,
            Sha256(extracted),
            extracted.Length);
        if (id == "vertical-font")
        {
            Observe(
                "vertical-writing",
                id,
                vertical,
                Sha256(extracted),
                extracted.Length,
                extracted.Count(character => character == '\n') + 1);
        }
    }

    private static void ObserveFontFamily(PDFont font, string id, string value)
    {
        switch (font)
        {
            case PDType0Font type0:
                Observe("type-0", id, value);
                Observe(
                    "cid",
                    id,
                    ((object)type0.GetDescendantFont()).GetType().Name,
                    type0.GetDescendantFont().IsEmbedded(),
                    type0.GetDescendantFont().IsDamaged());
                if (((object)type0.GetDescendantFont()).GetType().Name.Contains(
                        "Type0", StringComparison.Ordinal))
                {
                    Observe("cff", id, value);
                }
                break;
            case PDType1CFont:
                Observe("cff", id, value);
                Observe("type-1", id, value);
                break;
            case PDType1Font:
                Observe("type-1", id, value);
                break;
            case PDTrueTypeFont:
                Observe("true-type", id, value);
                break;
            case PDType3Font:
                Observe("type-3", id, value);
                break;
        }
    }

    private static void ObserveArticles(string pdf)
    {
        using var document = Loader.LoadPDF(new FileInfo(pdf));
        var separated = Extract(document, true, true, true);
        var together = Extract(document, true, true, false);
        Observe(
            "article-handling",
            "thread-beads",
            Sha256(separated),
            separated.Length,
            Count(separated, "<A>"),
            Sha256(together),
            together.Length,
            Count(together, "<A>"),
            separated != together);
    }

    private static void ObserveCrossRuntime(string pdf)
    {
        using var document = Loader.LoadPDF(new FileInfo(pdf));
        var extracted = Extract(document, true, true, true);
        var classes = document.GetPage(0).GetResources().GetFontNames()
            .Select(
                name => ((object)document.GetPage(0).GetResources().GetFont(name))
                    .GetType().Name)
            .OrderBy(name => name, StringComparer.Ordinal);
        Observe(
            "cross-reopen",
            "other-runtime-pdf",
            Sha256(extracted),
            extracted.Length,
            string.Join(",", classes));
    }

    private static int ReadCode(PDFont font, string value)
    {
        var encoded = font.Encode(value);
        return font.ReadCode(
            new MemoryStream(
                encoded.Select(item => unchecked((byte)item)).ToArray()));
    }

    private static bool Fails(Action action)
    {
        try
        {
            action();
            return false;
        }
        catch (Exception)
        {
            return true;
        }
    }

    private static int Count(string value, string needle)
    {
        var result = 0;
        var offset = 0;
        while ((offset = value.IndexOf(
                    needle, offset, StringComparison.Ordinal)) >= 0)
        {
            result++;
            offset += needle.Length;
        }
        return result;
    }

    private static string Sha256(string value) =>
        Sha256(System.Text.Encoding.UTF8.GetBytes(value));

    private static string Sha256(byte[] value) =>
        Convert.ToHexString(SHA256.HashData(value)).ToLowerInvariant();

    private static string MatrixValue(Matrix matrix) =>
        string.Join(
            ",",
            Number(matrix.GetScaleX()),
            Number(matrix.GetShearY()),
            Number(matrix.GetShearX()),
            Number(matrix.GetScaleY()),
            Number(matrix.GetTranslateX()),
            Number(matrix.GetTranslateY()));

    private static string Number(double value) =>
        value.ToString("F4", CultureInfo.InvariantCulture);

    private static void Observe(string family, string id, params object?[] values)
    {
        var value = string.Join("|", values.Select(Text));
        Observations.Add(family + "\t" + id + "\t" + value);
    }

    private static string Text(object? value)
    {
        var text = value switch
        {
            null => "null",
            bool boolean => boolean ? "true" : "false",
            IFormattable formattable => formattable.ToString(
                null, CultureInfo.InvariantCulture),
            _ => value.ToString() ?? "null"
        };
        return text
            .Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal);
    }

    private sealed class PositionCollector : PDFTextStripper
    {
        internal List<TextPosition> Positions { get; } = new();

        protected override void ProcessTextPosition(TextPosition text)
        {
            Positions.Add(text);
            base.ProcessTextPosition(text);
        }

        internal string Summary() =>
            string.Join(
                ";",
                Positions.Take(12).Select(
                    position => position.GetUnicode()
                        + "@" + Number(position.GetXDirAdj())
                        + "," + Number(position.GetYDirAdj())
                        + "," + Number(position.GetWidthDirAdj())));

        internal int ClusterCount() =>
            Positions.Count(position =>
                position.GetUnicode().EnumerateRunes().Count()
                    > position.GetCharacterCodes().Length);

        internal string DetailedSummary() =>
            string.Join(
                ";",
                Positions.Select(
                    position => position.GetUnicode()
                        + "[" + string.Join(",", position.GetCharacterCodes()) + "]"
                        + "@" + Number(position.GetDir())
                        + "," + Number(position.GetXDirAdj())
                        + "," + Number(position.GetYDirAdj())
                        + "," + Number(position.GetWidthDirAdj())));
    }
}
