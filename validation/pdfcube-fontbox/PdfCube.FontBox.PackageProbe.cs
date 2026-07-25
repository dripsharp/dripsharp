using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using PdfCube.FontBox.Afm;
using PdfCube.FontBox.Cff;
using PdfCube.FontBox.Cmap;
using PdfCube.FontBox.Encoding;
using PdfCube.FontBox.Pfb;
using PdfCube.FontBox.Ttf;
using PdfCube.FontBox.Ttf.Gsub;
using PdfCube.FontBox.Ttf.Model;
using PdfCube.FontBox.Type1;
using PdfCube.FontBox.Util.Autodetect;
using PdfCube.IO;

internal static class Program
{
    private static readonly List<string> Observations = [];

    private static void Main(string[] args)
    {
        if (args.Length is not (3 or 4 or 6))
            throw new ArgumentException(
                "Expected output trace, FontBox test resources, downloaded fonts, " +
                "optional canonical trace, and optional OS/architecture.");

        var resources = args[1];
        var fonts = args[2];
        ObserveEncodings();
        ObserveCff(Path.Combine(fonts, "SourceSansProBold.otf"));
        ObserveAfm(Path.Combine(resources, "afm"));
        ObserveCMaps(Path.Combine(resources, "cmap"));
        ObservePfbAndType1(
            Path.Combine(fonts, "OpenSans-Regular.pfb"),
            Path.Combine(fonts, "DejaVuSerifCondensed.pfb"));
        ObserveTrueType(Path.Combine(resources, "ttf"));
        ObserveOpenType(Path.Combine(resources, "ttf"), fonts);
        ObserveGsub(Path.Combine(resources, "ttf"));
        ObserveCollection(Path.Combine(resources, "ttf"));
        ObserveSubsetting(Path.Combine(resources, "ttf"));
        ObserveDiscovery(Path.Combine(resources, "ttf"), fonts);
        ObserveFailures(Path.Combine(resources, "afm"));

        File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
        if (args.Length >= 4)
            ValidateCanonical(args[3]);
        if (args.Length == 6)
            ValidateHost(args[4], args[5]);
        Console.WriteLine(
            $"PdfCube.FontBox package differential passed: {Observations.Count} observations.");
    }

    private static void ObserveEncodings()
    {
        var standard = StandardEncoding.Instance;
        Observe(
            "encoding",
            "standard",
            Join(
                standard.GetName(0),
                standard.GetName(32),
                standard.GetName(112),
                standard.GetName(172),
                standard.GetCode("space"),
                standard.GetCode("p"),
                standard.GetCode("guilsinglleft"),
                standard.GetCode("missing")));
        Observe(
            "encoding",
            "standard-map-view",
            Join(
                standard.GetCodeToNameMap().Count,
                FailureKind(() => standard.GetCodeToNameMap().Add(32, "changed"))));

        var mac = MacRomanEncoding.Instance;
        Observe(
            "encoding",
            "mac-roman",
            Join(
                mac.GetName(0),
                mac.GetName(32),
                mac.GetName(112),
                mac.GetName(167),
                mac.GetCode("germandbls")));

        Observe(
            "encoding",
            "cff-built-in",
            Join(
                CFFStandardEncoding.GetInstance().GetName(251),
                CFFStandardEncoding.GetInstance().GetCode("germandbls"),
                CFFExpertEncoding.GetInstance().GetName(112),
                CFFExpertEncoding.GetInstance().GetCode("Ucircumflexsmall"),
                CFFExpertCharset.GetInstance().GetSIDForGID(32),
                CFFExpertCharset.GetInstance().GetNameForGID(134)));
    }

    private static void ObserveCff(string sourceSans)
    {
        CFFType1Font font;
        using (var input = new RandomAccessReadBufferedFile(sourceSans))
        {
            font = (CFFType1Font)new CFFParser().Parse(input)[0];
        }

        Observe(
            "cff",
            "font-model",
            Join(
                font.GetName(),
                font.GetFontBBox().GetLowerLeftX(),
                font.GetFontBBox().GetLowerLeftY(),
                font.GetFontBBox().GetUpperRightX(),
                font.GetFontBBox().GetUpperRightY(),
                font.GetNumCharStrings(),
                font.GetCharStringBytes().Count));
        Observe(
            "cff",
            "charset-encoding",
            Join(
                font.GetCharset().IsCIDFont(),
                font.GetCharset().GetNameForGID(1),
                font.GetCharset().GetSIDForGID(300),
                font.GetCharset().GetSID("infinity"),
                font.GetEncoding().GetType().Name));
        Observe(
            "cff",
            "font-matrix-and-private",
            Join(
                NumberList(font.GetFontMatrix()),
                NumberList((System.Collections.IEnumerable)font.GetPrivateDict()["BlueValues"]),
                NumberList((System.Collections.IEnumerable)font.GetPrivateDict()["StemSnapV"])));

        var sample = Bytes(0, 1, 2, 255, 127, 128);
        Observe(
            "cff",
            "type1-font-util",
            Join(
                Type1FontUtil.HexEncode(sample),
                Unsigned(Type1FontUtil.HexDecode(Type1FontUtil.HexEncode(sample))),
                Unsigned(Type1FontUtil.EexecDecrypt(Type1FontUtil.EexecEncrypt(sample))),
                Unsigned(
                    Type1FontUtil.CharstringDecrypt(
                        Type1FontUtil.CharstringEncrypt(sample, 4), 4))));
    }

    private static void ObserveAfm(string afmDirectory)
    {
        FontMetrics metrics;
        using (var input = File.OpenRead(Path.Combine(afmDirectory, "Helvetica.afm")))
        {
            metrics = new AFMParser(input).Parse();
        }
        var ring = metrics.GetCharMetrics().Single(metric => metric.GetName() == "ring");
        Observe(
            "afm",
            "helvetica-model",
            Join(
                metrics.GetAFMVersion(),
                metrics.GetFontName(),
                metrics.GetWeight(),
                metrics.GetFontBBox().GetLowerLeftX(),
                metrics.GetFontBBox().GetUpperRightY(),
                metrics.GetComments().Count,
                metrics.GetCharMetrics().Count,
                metrics.GetKernPairs().Count));
        Observe(
            "afm",
            "char-metric",
            Join(
                ring.GetCharacterCode(),
                ring.GetWx(),
                ring.GetBoundingBox().GetLowerLeftX(),
                ring.GetBoundingBox().GetUpperRightY()));

        using var reducedInput = File.OpenRead(Path.Combine(afmDirectory, "Helvetica.afm"));
        var reduced = new AFMParser(reducedInput).Parse(true);
        Observe(
            "afm",
            "reduced-dataset",
            Join(reduced.GetCharMetrics().Count, reduced.GetKernPairs().Count));

        var nullableState = new FontMetrics();
        var withoutVector = nullableState.GetIsFixedV();
        nullableState.SetVVector([1, 2]);
        var inferredFromVector = nullableState.GetIsFixedV();
        nullableState.SetIsFixedV(false);
        Observe(
            "afm",
            "nullable-fixed-v",
            Join(withoutVector, inferredFromVector, nullableState.GetIsFixedV()));
    }

    private static void ObserveCMaps(string cmapDirectory)
    {
        CMap cmap;
        using (var input =
            new RandomAccessReadBufferedFile(Path.Combine(cmapDirectory, "CMapTest")))
        {
            cmap = new CMapParser().Parse(input);
        }
        Observe(
            "cmap",
            "fixture-mappings",
            Join(
                cmap.ToUnicode(Bytes(0, 1)),
                cmap.ToUnicode(Bytes(1, 32)),
                cmap.ToUnicode(Bytes(0, 10)),
                cmap.ToCID(Bytes(0, 65)),
                cmap.ToCID(Bytes(1, 24)),
                cmap.ToCID(Bytes(2, 8))));

        var identity = new CMapParser().ParsePredefined("Identity-H");
        var unicode = new CMapParser().ParsePredefined("Adobe-GB1-UCS2");
        Observe(
            "cmap",
            "embedded-resources",
            Join(
                identity.ToCID(Bytes(0, 65)),
                identity.ToCID(Bytes(48, 57)),
                unicode.ToUnicode(Bytes(0, 17)),
                unicode.GetName(),
                unicode.HasCIDMappings(),
                unicode.HasUnicodeMappings()));

        using (var input =
            new RandomAccessReadBufferedFile(
                Path.Combine(cmapDirectory, "CMapMalformedbfrange2")))
        {
            var lenient = new CMapParser().Parse(input);
            Observe(
                "cmap",
                "lenient-malformed-range",
                Join(lenient.ToUnicode(Bytes(0, 1)), Present(lenient.ToUnicode(Bytes(2, 241)))));
        }
        using (var input =
            new RandomAccessReadBufferedFile(
                Path.Combine(cmapDirectory, "CMapMalformedbfrange2")))
        {
            var strict = new CMapParser(true).Parse(input);
            Observe(
                "cmap",
                "strict-malformed-range",
                Join(
                    Present(strict.ToUnicode(Bytes(2, 240))),
                    Present(strict.ToUnicode(Bytes(2, 241)))));
        }

        var range = new CodespaceRange(Bytes(129, 64), Bytes(159, 252));
        Observe(
            "cmap",
            "codespace",
            Join(
                range.GetCodeLength(),
                range.Matches(Bytes(129, 64)),
                range.Matches(Bytes(144, 64)),
                range.Matches(Bytes(130, 32)),
                range.Matches(Bytes(160, 64))));

        const string directZeroSource =
            "begincmap\n" +
            "1 begincodespacerange\n<01> <01>\nendcodespacerange\n" +
            "1 begincidrange\n<01> <01> 5\nendcidrange\n" +
            "1 begincidchar\n<01> 0\nendcidchar\n" +
            "endcmap\n";
        var directZero =
            new CMapParser().Parse(
                new RandomAccessReadBuffer(
                    System.Text.Encoding.ASCII.GetBytes(directZeroSource)
                        .Select(value => unchecked((sbyte)value))
                        .ToArray()));
        Observe(
            "cmap",
            "direct-zero-precedes-range",
            Join(directZero.ToCID(Bytes(1))));
    }

    private static void ObservePfbAndType1(string openSans, string dejavu)
    {
        PfbParser pfb;
        using (var input = File.OpenRead(openSans))
        {
            pfb = new PfbParser(input);
        }
        Observe(
            "pfb",
            "segments",
            Join(
                pfb.GetLengths()[0],
                pfb.GetLengths()[1],
                pfb.GetLengths()[2],
                pfb.GetSegment1().Length,
                pfb.GetSegment2().Length,
                pfb.Size()));

        Type1Font openSansFont;
        using (var input = File.OpenRead(openSans))
        {
            openSansFont = Type1Font.CreateWithPFB(input);
        }
        using var path = openSansFont.GetPath("A");
        Observe(
            "type1",
            "open-sans",
            Join(
                openSansFont.GetVersion(),
                openSansFont.GetFontName(),
                openSansFont.GetFullName(),
                openSansFont.GetFamilyName(),
                openSansFont.GetWeight(),
                openSansFont.GetEncoding().GetType().Name,
                openSansFont.GetASCIISegment().Length,
                openSansFont.GetBinarySegment().Length,
                openSansFont.GetCharStringsDict().Count,
                openSansFont.HasGlyph("A"),
                path.Bounds.IsEmpty));

        Type1Font dejavuFont;
        using (var input = File.OpenRead(dejavu))
        {
            dejavuFont = Type1Font.CreateWithPFB(input);
        }
        Observe(
            "type1",
            "multiple-binary-segments",
            Join(
                dejavuFont.GetVersion(),
                dejavuFont.GetFontName(),
                dejavuFont.GetASCIISegment().Length,
                dejavuFont.GetBinarySegment().Length,
                dejavuFont.GetCharStringsDict().Count));
    }

    private static void ObserveTrueType(string ttfDirectory)
    {
        var liberation = Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf");
        var input = new RandomAccessReadBufferedFile(liberation);
        var font = new TTFParser().Parse(input);
        try
        {
            var tags = font.GetTables().Select(table => table.GetTag())
                .OrderBy(tag => tag, StringComparer.Ordinal)
                .ToList();
            Observe(
                "truetype",
                "directory-and-lifecycle",
                Join(
                    font.GetVersion(),
                    font.GetOriginalDataSize(),
                    input.IsClosed(),
                    tags.Count,
                    font.GetTables().All(table => table.GetInitialized()),
                    string.Join(",", tags)));

            Observe(
                "tables",
                "headers-names-and-os2",
                Join(
                    font.GetName(),
                    font.GetNaming().GetFontFamily(),
                    font.GetNaming().GetFontSubFamily(),
                    font.GetNaming().GetNameRecords().Count,
                    font.GetHeader().GetCreated().UtcDateTime.ToString(
                        "yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture),
                    font.GetHeader().GetUnitsPerEm(),
                    font.GetMaximumProfile().GetNumGlyphs(),
                    font.GetOS2Windows().GetWeightClass(),
                    font.GetOS2Windows().GetAchVendId()));

            var cmap = font.GetUnicodeCmapLookup();
            var a = cmap.GetGlyphId('A');
            var trademark = cmap.GetGlyphId(0x2122);
            var euro = cmap.GetGlyphId(0x20ac);
            Observe(
                "tables",
                "cmap-metrics-post",
                Join(
                    a,
                    trademark,
                    euro,
                    cmap.GetCharCodes(trademark),
                    font.GetAdvanceWidth(a),
                    font.GetHorizontalMetrics().GetLeftSideBearing(a),
                    font.GetPostScript().GetGlyphNames()[trademark],
                    font.GetPostScript().GetGlyphNames()[euro]));

            var glyph = font.GetGlyph().GetGlyph(131);
            var description = (GlyfCompositeDescript)glyph.GetDescription();
            Observe(
                "tables",
                "composite-glyph",
                Join(
                    glyph.GetNumberOfContours(),
                    glyph.GetXMinimum(),
                    glyph.GetYMinimum(),
                    glyph.GetXMaximum(),
                    glyph.GetYMaximum(),
                    description.IsComposite(),
                    description.GetComponentCount(),
                    description.GetComponents()[0].GetGlyphIndex(),
                    description.GetComponents()[1].GetGlyphIndex(),
                    FailureKind(() => description.GetComponents().RemoveAt(0))));

            using var original = font.GetOriginalData();
            var signature = new byte[4];
            _ = original.Read(signature, 0, signature.Length);
            Observe(
                "lifecycle",
                "owned-copy-after-source-close",
                Join(input.IsClosed(), string.Join(",", signature), font.GetOriginalDataSize()));

            var headerInput = new RandomAccessReadBufferedFile(liberation);
            var headers = new TTFParser().ParseTableHeaders(headerInput);
            Observe(
                "lifecycle",
                "selective-header-scan",
                Join(
                    headerInput.IsClosed(),
                    headers.GetError(),
                    headers.GetName(),
                    headers.GetFontFamily(),
                    headers.GetFontSubFamily(),
                    headers.GetHeaderMacStyle(),
                    headers.GetOS2Windows().GetWeightClass()));
        }
        finally
        {
            font.Dispose();
            font.Dispose();
        }
    }

    private static void ObserveOpenType(string ttfDirectory, string fonts)
    {
        var liberation = new OTFParser().Parse(
            new RandomAccessReadBufferedFile(
                Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf")));
        try
        {
            var gsub = liberation.GetGsub();
            Observe(
                "opentype",
                "truetype-outlines-and-layout",
                Join(
                    liberation.IsPostScript(),
                    liberation.IsSupportedOTF(),
                    liberation.HasLayoutTables(),
                    gsub.GetSupportedScriptTags().OrderBy(value => value, StringComparer.Ordinal)));
        }
        finally
        {
            liberation.Dispose();
        }

        var sourceSans = new OTFParser().Parse(
            new RandomAccessReadBufferedFile(Path.Combine(fonts, "SourceSansProBold.otf")));
        try
        {
            Observe(
                "opentype",
                "cff-outlines",
                Join(
                    sourceSans.GetName(),
                    sourceSans.IsPostScript(),
                    sourceSans.IsSupportedOTF(),
                    sourceSans.HasLayoutTables(),
                    sourceSans.GetCFF().GetFont().GetName(),
                    sourceSans.GetCFF().GetFont().GetNumCharStrings(),
                    FailureKind(() => sourceSans.GetGlyph())));
        }
        finally
        {
            sourceSans.Dispose();
        }

        var noto = new OTFParser(false).Parse(
            new RandomAccessReadBufferedFile(Path.Combine(fonts, "NotoSansSC-Regular.otf")));
        try
        {
            const int gid = 8712;
            Observe(
                "tables",
                "multiple-cmap-codes",
                Join(
                    noto.GetUnicodeCmapLookup().GetCharCodes(gid),
                    noto.GetCmap().GetSubtable(0, 4).GetCharCodes(gid),
                    noto.GetCmap().GetSubtable(0, 3).GetCharCodes(gid)));
        }
        finally
        {
            noto.Dispose();
        }
    }

    private static void ObserveGsub(string ttfDirectory)
    {
        var liberation = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(
                Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf")));
        try
        {
            var table = liberation.GetGsub();
            var defaultData = liberation.GetGsubData();
            var cyrillic = table.GetGsubData("cyrl");
            Observe(
                "gsub",
                "scripts-and-features",
                Join(
                    table.GetSupportedScriptTags()
                        .OrderBy(value => value, StringComparer.Ordinal),
                    defaultData.GetActiveScriptName(),
                    cyrillic.GetActiveScriptName(),
                    cyrillic.GetSupportedFeatures()
                        .OrderBy(value => value, StringComparer.Ordinal),
                    table.GetGsubData("missing") is null));
        }
        finally
        {
            liberation.Dispose();
        }

        var bengali = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(Path.Combine(ttfDirectory, "Lohit-Bengali.ttf")));
        try
        {
            var cmap = bengali.GetUnicodeCmapLookup();
            var data = bengali.GetGsubData();
            var worker = new GsubWorkerFactory().GetGsubWorker(cmap, data);
            Observe(
                "gsub",
                "bengali-model",
                Join(
                    data.GetLanguage(),
                    data.GetActiveScriptName(),
                    data.GetSupportedFeatures()
                        .OrderBy(value => value, StringComparer.Ordinal),
                    data.GetFeature("rphf").GetAllGlyphIdsForSubstitution().Count));
            Observe(
                "gsub",
                "bengali-shaping",
                Join(
                    worker.ApplyTransforms(GlyphIds(cmap, "আমি")),
                    worker.ApplyTransforms(GlyphIds(cmap, "ব্যাস")),
                    worker.ApplyTransforms(GlyphIds(cmap, "বেলা")),
                    worker.ApplyTransforms(GlyphIds(cmap, "দ্রুত"))));
        }
        finally
        {
            bengali.Dispose();
        }
    }

    private static void ObserveCollection(string ttfDirectory)
    {
        var collectionBytes = BuildCollection(
            File.ReadAllBytes(Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf")),
            File.ReadAllBytes(Path.Combine(ttfDirectory, "JosefinSans-Italic.ttf")));
        var names = new List<string>();
        using var collection = new TrueTypeCollection(
            new MemoryStream(collectionBytes, writable: false));
        collection.ProcessAllFonts(new FontNameProcessor(names));
        var selected = collection.GetFontByName("JosefinSans-Italic")
            ?? throw new InvalidOperationException(
                "TTC name lookup failed after observing: " + string.Join(",", names));
        Observe(
            "collection",
            "generated-two-font-ttc",
            Join(
                names,
                selected.GetName(),
                selected.GetNumberOfGlyphs(),
                selected.GetUnitsPerEm()));
    }

    private static void ObserveSubsetting(string ttfDirectory)
    {
        var liberation = Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf");
        var (first, gidMap) = CreateCompositeSubset(liberation);
        var (second, _) = CreateCompositeSubset(liberation);
        using (var subset = new TTFParser(true).Parse(
            new RandomAccessReadBuffer(ToSigned(first))))
        {
            var tags = subset.GetTables()
                .Select(table => table.GetTag())
                .OrderBy(tag => tag, StringComparer.Ordinal)
                .ToList();
            var post = subset.GetPostScript();
            Observe(
                "subsetting",
                "composite-closure-names-and-tables",
                Join(
                    first.Length,
                    first.SequenceEqual(second),
                    Convert.ToHexString(SHA256.HashData(first)).ToLowerInvariant(),
                    subset.GetNumberOfGlyphs(),
                    gidMap.Values,
                    subset.GetName(),
                    subset.GetNaming().GetFontFamily(),
                    subset.GetNaming().GetFontSubFamily(),
                    new[]
                    {
                        post.GetName(0),
                        post.GetName(1),
                        post.GetName(2),
                        post.GetName(3),
                        post.GetName(4)
                    },
                    subset.GetUnicodeCmapLookup().GetGlyphId('Ö'),
                    subset.GetUnicodeCmapLookup().GetGlyphId('\u200A'),
                    tags));
        }

        using (var full = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(liberation)))
        {
            var emptySubsetter = new TTFSubsetter(full);
            using var emptyOutput = new MemoryStream();
            emptySubsetter.WriteToStream(emptyOutput);
            using var empty = new TTFParser(true).Parse(
                new RandomAccessReadBuffer(ToSigned(emptyOutput.ToArray())));
            Observe(
                "subsetting",
                "empty-subset",
                Join(
                    empty.GetNumberOfGlyphs(),
                    empty.NameToGID(".notdef"),
                    empty.GetGlyph().GetGlyph(0) is not null));
        }

        using (var full = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(liberation)))
        {
            var required = new List<string>
            {
                "head", "hhea", "loca", "maxp", "glyf", "hmtx"
            };
            var selectedSubsetter = new TTFSubsetter(full, required);
            selectedSubsetter.Add('A');
            using var selectedOutput = new MemoryStream();
            selectedSubsetter.WriteToStream(selectedOutput);
            using var selected = new TTFParser(true).Parse(
                new RandomAccessReadBuffer(ToSigned(selectedOutput.ToArray())));
            var tags = selected.GetTables()
                .Select(table => table.GetTag())
                .OrderBy(tag => tag, StringComparer.Ordinal)
                .ToList();
            Observe(
                "subsetting",
                "required-table-selection",
                Join(
                    selected.GetNumberOfGlyphs(),
                    selected.GetAdvanceWidth(1),
                    selected.GetHorizontalMetrics().GetLeftSideBearing(1),
                    tags));
        }

        using (var full = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(liberation)))
        {
            var invisibleSubsetter = new TTFSubsetter(full);
            invisibleSubsetter.Add('A');
            invisibleSubsetter.Add('B');
            invisibleSubsetter.Add('\u200C');
            invisibleSubsetter.ForceInvisible('B');
            invisibleSubsetter.ForceInvisible('\u200C');
            using var invisibleOutput = new MemoryStream();
            invisibleSubsetter.WriteToStream(invisibleOutput);
            using var invisible = new TTFParser(true).Parse(
                new RandomAccessReadBuffer(ToSigned(invisibleOutput.ToArray())));
            using var aPath = invisible.GetPath("A");
            using var bPath = invisible.GetPath("B");
            using var zwnjPath = invisible.GetPath("uni200C");
            Observe(
                "subsetting",
                "forced-invisible-encoding",
                Join(
                    invisible.GetNumberOfGlyphs(),
                    aPath.Bounds.IsEmpty,
                    bPath.Bounds.IsEmpty,
                    zwnjPath.Bounds.IsEmpty,
                    invisible.GetWidth("A"),
                    invisible.GetWidth("B"),
                    invisible.GetWidth("uni200C")));
        }

        Observe(
            "subsetting",
            "invalid-glyph-failure",
            FailureKind(() =>
            {
                using var full = new TTFParser().Parse(
                    new RandomAccessReadBufferedFile(liberation));
                var invalid = new TTFSubsetter(full);
                invalid.AddGlyphIds(new HashSet<int> { 999999 });
                invalid.WriteToStream(new MemoryStream());
            }));
    }

    private static (byte[] Bytes, IDictionary<int, int> GidMap)
        CreateCompositeSubset(string liberation)
    {
        using var full = new TTFParser().Parse(
            new RandomAccessReadBufferedFile(liberation));
        var subsetter = new TTFSubsetter(full);
        subsetter.SetPrefix("ABCDEF+");
        subsetter.Add('Ö');
        subsetter.Add('\u200A');
        using var output = new MemoryStream();
        subsetter.WriteToStream(output);
        return (
            output.ToArray(),
            new Dictionary<int, int>(subsetter.GetGIDMap()));
    }

    private static void ObserveDiscovery(string ttfDirectory, string fonts)
    {
        var root = Path.Combine(
            Path.GetTempPath(),
            "fontbox-discovery-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var scan = Directory.CreateDirectory(Path.Combine(root, "scan")).FullName;
            var nested = Directory.CreateDirectory(Path.Combine(scan, "nested")).FullName;
            var hidden = Directory.CreateDirectory(Path.Combine(scan, ".hidden")).FullName;
            var liberation = Path.Combine(ttfDirectory, "LiberationSans-Regular.ttf");
            File.Copy(liberation, Path.Combine(scan, "Valid.TTF"));
            File.Copy(liberation, Path.Combine(nested, "Duplicate.ttf"));
            File.WriteAllBytes(Path.Combine(scan, "Corrupt.otf"), [0, 1, 2, 3]);
            File.WriteAllBytes(Path.Combine(scan, "TypeOne.PFB"), [0]);
            File.WriteAllBytes(Path.Combine(scan, "Collection.TTC"), [0]);
            File.WriteAllBytes(Path.Combine(scan, "fonts.dir"), [0]);
            File.WriteAllBytes(Path.Combine(scan, "notes.txt"), [0]);
            File.WriteAllBytes(Path.Combine(hidden, "Ignored.ttf"), [0]);

            var finder = new FontFileFinder();
            var discovered = RelativeUris(root, finder.Find(scan));
            Observe(
                "discovery",
                "extension-filter-missing-duplicate-and-corrupt",
                Join(
                    discovered,
                    finder.Find(Path.Combine(root, "missing")).Count,
                    File.ReadAllBytes(Path.Combine(scan, "Valid.TTF"))
                        .SequenceEqual(
                            File.ReadAllBytes(
                                Path.Combine(nested, "Duplicate.ttf")))));

            var collectionNames = new List<string>();
            var collectionBytes = BuildCollection(
                File.ReadAllBytes(liberation),
                File.ReadAllBytes(Path.Combine(ttfDirectory, "JosefinSans-Italic.ttf")));
            using (var collection = new TrueTypeCollection(
                new MemoryStream(collectionBytes, writable: false)))
            {
                collection.ProcessAllFonts(new FontNameProcessor(collectionNames));
            }
            string ttfName;
            using (var ttf = new TTFParser().Parse(
                new RandomAccessReadBufferedFile(liberation)))
            {
                ttfName = ttf.GetName();
            }
            bool cff;
            using (var otf = new OTFParser().Parse(
                new RandomAccessReadBufferedFile(
                    Path.Combine(fonts, "SourceSansProBold.otf"))))
            {
                cff = otf.IsPostScript();
            }
            int pfbSize;
            using (var input = File.OpenRead(Path.Combine(fonts, "OpenSans-Regular.pfb")))
            {
                pfbSize = new PfbParser(input).Size();
            }
            Observe(
                "discovery",
                "type-detection-and-failures",
                Join(
                    ttfName,
                    cff,
                    pfbSize,
                    collectionNames,
                    FailureKind(() => new TTFParser().Parse(
                        new RandomAccessReadBufferedFile(
                            Path.Combine(scan, "Corrupt.otf")))),
                    FailureKind(() => new TTFParser().Parse(
                        new RandomAccessReadBufferedFile(
                            Path.Combine(root, "missing.ttf"))))));

            var home = Directory.CreateDirectory(Path.Combine(root, "home")).FullName;
            var macFonts = Directory.CreateDirectory(
                Path.Combine(home, "Library", "Fonts")).FullName;
            var unixFonts = Directory.CreateDirectory(
                Path.Combine(home, ".fonts")).FullName;
            var windows = Directory.CreateDirectory(
                Path.Combine(root, "windows", "FONTS")).FullName;
            File.WriteAllBytes(Path.Combine(macFonts, "Mac.ttf"), [0]);
            File.WriteAllBytes(Path.Combine(unixFonts, "Unix.otf"), [0]);
            File.WriteAllBytes(Path.Combine(windows, "Windows.pfb"), [0]);
            SetJavaProperty("user.home", home);
            SetJavaProperty("env.windir", Path.Combine(root, "windows"));

            var matrix = new List<string>();
            var platforms = new[]
            {
                (Name: "Windows 11", Id: "windows"),
                (Name: "Mac OS X", Id: "mac"),
                (Name: "Linux", Id: "unix")
            };
            var architectures = new[] { "amd64", "aarch64" };
            foreach (var platform in platforms)
            {
                foreach (var architecture in architectures)
                {
                    SetJavaProperty("os.name", platform.Name);
                    SetJavaProperty("os.arch", architecture);
                    matrix.Add(
                        $"{platform.Id}/{architecture}=" +
                        Value(RelativeUris(root, new FontFileFinder().Find())));
                }
            }
            SetJavaProperty("os.name", "Plan 9");
            matrix.Add(
                "fallback=" +
                Value(RelativeUris(root, new FontFileFinder().Find())));
            Observe("discovery", "supported-host-selection", Join(matrix));

            SetJavaProperty("os.name", "Mac OS X");
            var cached = new FontFileFinder();
            var first = RelativeUris(root, cached.Find());
            SetJavaProperty("os.name", "Linux");
            var second = RelativeUris(root, cached.Find());
            var fresh = RelativeUris(root, new FontFileFinder().Find());
            Observe(
                "discovery",
                "finder-selection-cache-and-fallback",
                Join(first, second, fresh));

            var inaccessible = Directory.CreateDirectory(
                Path.Combine(root, "inaccessible")).FullName;
            File.WriteAllBytes(Path.Combine(inaccessible, "Blocked.ttf"), [0]);
            VerifyInaccessibleAdapter(inaccessible);
            UnixFileMode? previousMode = null;
            if (!OperatingSystem.IsWindows())
            {
                previousMode = File.GetUnixFileMode(inaccessible);
                File.SetUnixFileMode(inaccessible, UnixFileMode.None);
            }
            try
            {
                Observe(
                    "discovery",
                    "inaccessible-directory",
                    FailureKind(() => new FontFileFinder().Find(inaccessible)));
            }
            finally
            {
                if (!OperatingSystem.IsWindows() && previousMode is not null)
                    File.SetUnixFileMode(inaccessible, previousMode.Value);
            }
        }
        finally
        {
            if (Directory.Exists(root))
                Directory.Delete(root, recursive: true);
        }
    }

    private static List<string> RelativeUris(string root, IEnumerable<Uri> values)
    {
        var absoluteRoot = Path.GetFullPath(root);
        return values
            .Select(value => Path.GetFullPath(value.LocalPath))
            .Where(path => path.StartsWith(
                absoluteRoot + Path.DirectorySeparatorChar,
                OperatingSystem.IsWindows()
                    ? StringComparison.OrdinalIgnoreCase
                    : StringComparison.Ordinal))
            .Select(path => Path.GetRelativePath(absoluteRoot, path)
                .Replace(Path.DirectorySeparatorChar, '/'))
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToList();
    }

    private static void SetJavaProperty(string name, string value)
    {
        var compatibility = typeof(FontFileFinder).Assembly.GetType(
            "PdfCube.FB.Runtime.JavaCompat",
            throwOnError: true)!;
        var method = compatibility.GetMethod(
            "SetProperty",
            BindingFlags.NonPublic | BindingFlags.Static)
            ?? throw new InvalidOperationException(
                "FontBox Java system-property adapter is missing.");
        _ = method.Invoke(null, [name, value]);
    }

    private static void VerifyInaccessibleAdapter(string directory)
    {
        var adapter = typeof(FontFileFinder).Assembly.GetType(
            "PdfCube.FB.Runtime.PdfCubeFontDiscovery",
            throwOnError: true)!;
        var method = adapter.GetMethod(
            "FileListFiles",
            BindingFlags.NonPublic | BindingFlags.Static,
            binder: null,
            types:
            [
                typeof(FileInfo),
                typeof(Func<string, IEnumerable<string>>)
            ],
            modifiers: null)
            ?? throw new InvalidOperationException(
                "FontBox inaccessible-directory adapter is missing.");
        Func<string, IEnumerable<string>> inaccessible =
            _ => throw new UnauthorizedAccessException("focused inaccessible path");
        var result = method.Invoke(
            null,
            [new FileInfo(directory), inaccessible]);
        if (result is not null)
            throw new InvalidOperationException(
                "FontBox inaccessible-directory adapter did not match File.listFiles().");
    }

    private static sbyte[] ToSigned(IEnumerable<byte> values) =>
        values.Select(value => unchecked((sbyte)value)).ToArray();

    private static List<int> GlyphIds(CmapLookup cmap, string text) =>
        text.Select(character => cmap.GetGlyphId(character)).ToList();

    private static byte[] BuildCollection(byte[] first, byte[] second)
    {
        const int firstOffset = 20;
        var secondOffset = firstOffset + ((first.Length + 3) & ~3);
        var collection = new byte[secondOffset + second.Length];
        WriteInt(collection, 0, 0x74746366);
        WriteInt(collection, 4, 0x00010000);
        WriteInt(collection, 8, 2);
        WriteInt(collection, 12, firstOffset);
        WriteInt(collection, 16, secondOffset);
        CopyFontIntoCollection(first, collection, firstOffset);
        CopyFontIntoCollection(second, collection, secondOffset);
        return collection;
    }

    private static void CopyFontIntoCollection(byte[] font, byte[] collection, int offset)
    {
        Array.Copy(font, 0, collection, offset, font.Length);
        var tableCount = ReadUnsignedShort(collection, offset + 4);
        for (var index = 0; index < tableCount; index++)
        {
            var tableOffsetPosition = offset + 12 + index * 16 + 8;
            WriteInt(
                collection,
                tableOffsetPosition,
                ReadInt(collection, tableOffsetPosition) + offset);
        }
    }

    private static int ReadUnsignedShort(byte[] data, int offset) =>
        (data[offset] << 8) | data[offset + 1];

    private static int ReadInt(byte[] data, int offset) =>
        (data[offset] << 24) |
        (data[offset + 1] << 16) |
        (data[offset + 2] << 8) |
        data[offset + 3];

    private static void WriteInt(byte[] data, int offset, int value)
    {
        data[offset] = unchecked((byte)(value >> 24));
        data[offset + 1] = unchecked((byte)(value >> 16));
        data[offset + 2] = unchecked((byte)(value >> 8));
        data[offset + 3] = unchecked((byte)value);
    }

    private sealed class FontNameProcessor(List<string> names) :
        TrueTypeCollection.TrueTypeFontProcessor
    {
        public void Process(TrueTypeFont font) => names.Add(font.GetName());
    }

    private static void ObserveFailures(string afmDirectory)
    {
        Observe(
            "failure",
            "empty-pfb",
            FailureKind(() => Type1Font.CreateWithPFB([])));
        var negativeRecord =
            Bytes(128, 1, 1, 0, 0, 255, 255, 255, 255, 255, 255, 255, 39, 5, 248, 255, 210, 64);
        Observe(
            "failure",
            "negative-pfb-record",
            FailureKind(() => new PfbParser(negativeRecord)));
        Observe(
            "failure",
            "odd-hex",
            FailureKind(() => Type1FontUtil.HexDecode("123")));
        Observe(
            "failure",
            "missing-cmap-resource",
            FailureKind(() => new CMapParser().ParsePredefined("Missing-CMap")));
        Observe(
            "failure",
            "bad-codespace",
            FailureKind(() => new CodespaceRange(Bytes(1), Bytes(1, 32))));
        Observe(
            "failure",
            "malformed-cff",
            FailureKind(
                () => new CFFParser().Parse(new RandomAccessReadBuffer(Bytes(1, 0, 4, 4)))));
        Observe(
            "failure",
            "malformed-afm-start",
            FailureKind(
                () =>
                    new AFMParser(
                            new MemoryStream(System.Text.Encoding.ASCII.GetBytes("huhu"), writable: false))
                        .Parse()));
        Observe(
            "failure",
            "malformed-afm-number",
            FailureKind(
                () =>
                {
                    using var input =
                        File.OpenRead(Path.Combine(afmDirectory, "MalformedFloat.afm"));
                    new AFMParser(input).Parse();
                }));
        Observe(
            "failure",
            "malformed-truetype",
            FailureKind(() => new TTFParser().Parse(
                new RandomAccessReadBuffer(Bytes(0, 1, 2, 3)))));
        Observe(
            "failure",
            "bad-collection-header",
            FailureKind(() => new TrueTypeCollection(
                new MemoryStream(new byte[] { 0, 1, 2, 3 }, writable: false))));
        Observe(
            "failure",
            "bad-collection-count",
            FailureKind(() => new TrueTypeCollection(
                new MemoryStream(
                    new byte[]
                    {
                        0x74, 0x74, 0x63, 0x66, 0, 0, 0, 0,
                        0x7f, 0xff, 0xff, 0xff
                    },
                    writable: false))));
    }

    private static sbyte[] Bytes(params int[] values) =>
        values.Select(value => unchecked((sbyte)value)).ToArray();

    private static string Unsigned(IEnumerable<sbyte> values) =>
        string.Join(",", values.Select(value => unchecked((byte)value)));

    private static string NumberList(System.Collections.IEnumerable values) =>
        string.Join(
            ",",
            values.Cast<object>().Select(
                value => Convert.ToString(value, CultureInfo.InvariantCulture)));

    private static string Present(string value) => (value is not null).ToString();

    private static string Join(params object?[] values) =>
        string.Join("|", values.Select(Value));

    private static string Value(object? value)
    {
        if (value is null)
            return "null";
        if (value is string text)
            return text.ToLowerInvariant();
        if (value is System.Collections.IEnumerable sequence)
            return "[" + string.Join(
                ", ",
                sequence.Cast<object?>().Select(Value)) + "]";
        return Convert.ToString(value, CultureInfo.InvariantCulture)!.ToLowerInvariant();
    }

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (NotSupportedException)
        {
            return "unsupported";
        }
        catch (ArgumentException)
        {
            return "argument";
        }
        catch (InvalidOperationException)
        {
            return "state";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (Exception)
        {
            return "other";
        }
    }

    private static void ValidateCanonical(string canonicalPath)
    {
        var expected = File.ReadAllLines(canonicalPath, System.Text.Encoding.UTF8);
        if (!expected.SequenceEqual(Observations, StringComparer.Ordinal))
        {
            var mismatch = Enumerable.Range(0, Math.Max(expected.Length, Observations.Count))
                .First(index =>
                    index >= expected.Length ||
                    index >= Observations.Count ||
                    !string.Equals(expected[index], Observations[index], StringComparison.Ordinal));
            throw new InvalidOperationException(
                $"Canonical differential mismatch at line {mismatch + 1}: " +
                $"expected `{At(expected, mismatch)}`, observed `{At(Observations, mismatch)}`.");
        }
    }

    private static void ValidateHost(string expectedOs, string expectedArchitecture)
    {
        var osMatches = expectedOs switch
        {
            "linux" => RuntimeInformation.IsOSPlatform(OSPlatform.Linux),
            "windows" => RuntimeInformation.IsOSPlatform(OSPlatform.Windows),
            "macos" => RuntimeInformation.IsOSPlatform(OSPlatform.OSX),
            _ => false
        };
        var architectureMatches = expectedArchitecture switch
        {
            "x64" => RuntimeInformation.ProcessArchitecture == Architecture.X64,
            "arm64" => RuntimeInformation.ProcessArchitecture == Architecture.Arm64,
            _ => false
        };
        if (!osMatches || !architectureMatches)
            throw new InvalidOperationException(
                $"Expected {expectedOs}/{expectedArchitecture}, observed " +
                $"{RuntimeInformation.OSDescription}/{RuntimeInformation.ProcessArchitecture}.");
        Console.WriteLine(
            $"PdfCube.FontBox host smoke passed: {expectedOs}/{expectedArchitecture}.");
    }

    private static string At(IReadOnlyList<string> values, int index) =>
        index < values.Count ? values[index] : "<missing>";

    private static void Observe(string family, string id, string value) =>
        Observations.Add($"{family}\t{id}\t{value}");
}
