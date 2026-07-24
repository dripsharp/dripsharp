using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.FontBox.Afm;
using PdfCube.FontBox.Cff;
using PdfCube.FontBox.Cmap;
using PdfCube.FontBox.Encoding;
using PdfCube.FontBox.Pfb;
using PdfCube.FontBox.Type1;
using PdfCube.IO;

internal static class Program
{
    private static readonly List<string> Observations = [];

    private static void Main(string[] args)
    {
        if (args.Length is not (3 or 4))
            throw new ArgumentException(
                "Expected output trace, FontBox test resources, downloaded fonts, " +
                "and optional canonical trace.");

        var resources = args[1];
        var fonts = args[2];
        ObserveEncodings();
        ObserveCff(Path.Combine(fonts, "SourceSansProBold.otf"));
        ObserveAfm(Path.Combine(resources, "afm"));
        ObserveCMaps(Path.Combine(resources, "cmap"));
        ObservePfbAndType1(
            Path.Combine(fonts, "OpenSans-Regular.pfb"),
            Path.Combine(fonts, "DejaVuSerifCondensed.pfb"));
        ObserveFailures(Path.Combine(resources, "afm"));

        File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
        if (args.Length == 4)
            ValidateCanonical(args[3]);
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
        string.Join(
            "|",
            values.Select(value =>
                value is null
                    ? "null"
                    : Convert.ToString(value, CultureInfo.InvariantCulture)!.ToLowerInvariant()));

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

    private static string At(IReadOnlyList<string> values, int index) =>
        index < values.Count ? values[index] : "<missing>";

    private static void Observe(string family, string id, string value) =>
        Observations.Add($"{family}\t{id}\t{value}");
}
