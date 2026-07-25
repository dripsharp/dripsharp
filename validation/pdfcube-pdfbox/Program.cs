using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using PdfCube.IO;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Filter;
using PdfCube.PdfBox.Pdfparser;
using PdfCube.PdfBox.Pdfwriter.Compress;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Encryption;

internal static class Program
{
    private static readonly List<string> Observations = new();
    private static string resources = null!;
    private static string exchange = null!;

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 3 or > 4)
                throw new ArgumentException(
                    "Expected output trace, PdfBox test resources, exchange directory, " +
                    "and optional --write-only.");
            if (args.Length == 4 && args[3] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            resources = args[1];
            exchange = args[2];
            Directory.CreateDirectory(exchange);

            ObserveCOS();
            ObserveFilters();
            ObservePredictors();
            ObserveObjectStreams();
            var full = WriteFull("dotnet");
            WriteIncremental("dotnet", full);
            ObserveParsing(full);
            ObserveFixtures();
            if (args.Length == 3)
                ObserveCrossRuntime("java");

            File.WriteAllLines(output, Observations, new UTF8Encoding(false));
            Console.WriteLine(
                $"PdfCube.PdfBox low-level probe passed: {Observations.Count} observations.");
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveCOS()
    {
        Observe(
            "cos-identity",
            "singletons-and-caches",
            Join(
                ReferenceEquals(COSBoolean.GetBoolean(true), COSBoolean.True),
                ReferenceEquals(COSBoolean.GetBoolean(false), COSBoolean.False),
                ReferenceEquals(COSInteger.Get(42), COSInteger.Get(42)),
                ReferenceEquals(
                    COSName.GetPDFName("Custom"), COSName.GetPDFName("Custom")),
                ReferenceEquals(COSNull.Null, COSNull.Null)));

        var state = new COSDocumentState();
        state.SetParsing(false);
        var array = new COSArray();
        array.GetUpdateState().SetOriginDocumentState(state);
        var indirect = new COSDictionary();
        var indirectKey = new COSObjectKey(7, 2);
        indirect.SetDirect(false);
        indirect.SetKey(indirectKey);
        array.Add(indirect);
        Observe(
            "cos-mutation",
            "array-wrap-dereference-update",
            Join(
                array.IsDirect(),
                array.Size(),
                array.Get(0) is COSObject,
                ReferenceEquals(array.GetObject(0), indirect),
                array.IndexOfObject(indirect),
                ((COSUpdateInfo)array).IsNeedToBeUpdated()));

        var dictionary = new COSDictionary();
        dictionary.GetUpdateState().SetOriginDocumentState(state);
        dictionary.SetInt(COSName.Count, 3);
        dictionary.SetString(COSName.Title, "low-level");
        dictionary.SetItem(COSName.A, array);
        dictionary.RemoveItem(COSName.Count);
        Observe(
            "cos-mutation",
            "dictionary-set-remove-path",
            Join(
                dictionary.ContainsKey(COSName.Title),
                dictionary.ContainsKey(COSName.Count),
                ReferenceEquals(
                    (COSDictionary)dictionary.GetObjectFromPath("A/\\[0\\]"), indirect),
                dictionary.GetString(COSName.Title),
                ((COSUpdateInfo)dictionary).IsNeedToBeUpdated()));

        var key = new COSObjectKey(12, 4);
        var same = new COSObjectKey(12, 4, 8);
        var first = new COSDictionary();
        var second = new COSDictionary();
        first.SetItem(COSName.Be, COSName.Be);
        second.SetItem(COSName.Be, COSName.Be);
        var stream = new COSStream();
        stream.SetItem(COSName.Be, COSName.Be);
        Observe(
            "cos-equality",
            "values-keys-and-stream-distinction",
            Join(
                new COSString("same").Equals(new COSString("same")),
                new COSFloat(1.25F).Equals(new COSFloat(1.25F)),
                key.Equals(same),
                key.GetHashCode() == same.GetHashCode(),
                key.CompareTo(new COSObjectKey(13, 0)) < 0,
                first.Equals(second),
                !first.Equals(stream),
                !stream.Equals(first)));
        stream.Dispose();

        var root = new COSDictionary();
        var child = new COSDictionary();
        var rootKey = new COSObjectKey(1, 0);
        var childKey = new COSObjectKey(2, 0);
        var leafKey = new COSObjectKey(3, 0);
        root.SetKey(rootKey);
        child.SetKey(childKey);
        var children = new COSArray();
        children.Add(new COSObject(child, childKey));
        children.Add(new COSObject(COSInteger.One, leafKey));
        root.SetItem(COSName.Kids, children);
        var traversed = new List<COSObjectKey>();
        root.GetIndirectObjectKeys(traversed);
        traversed.Sort();
        Observe("cos-traversal", "indirect-object-keys", JoinObjectKeys(traversed));

        var document = new COSDocument();
        var pooled1 = document.GetObjectFromPool(new COSObjectKey(31, 0));
        var pooled2 = document.GetObjectFromPool(new COSObjectKey(31, 0, 9));
        var owned = document.CreateCOSStream();
        using (var output = owned.CreateOutputStream())
            output.Write(Bytes(1, 2, 3, 4));
        Observe(
            "cos-identity",
            "document-object-pool",
            Join(
                ReferenceEquals(pooled1, pooled2),
                pooled1.GetObjectNumber(),
                pooled1.GetGenerationNumber(),
                document.GetXrefTable().Count));
        document.Dispose();
        document.Dispose();
        Observe(
            "cos-lifecycle",
            "document-and-owned-stream-close",
            Join(document.IsClosed(), FailureKind(() => owned.CreateInputStream())));

        using var regression = new COSDocument();
        regression.AddXRefTable(new Dictionary<COSObjectKey, long>());
        Observe(
            "cos-traversal",
            "null-xref-regression",
            Join(
                regression.GetObjectsByType(COSName.T).Count,
                regression.GetLinearizedDictionary() is null));
    }

    private static void ObserveFilters()
    {
        string[] names =
        [
            "FlateDecode", "Fl", "DCTDecode", "DCT", "CCITTFaxDecode", "CCF",
            "LZWDecode", "LZW", "ASCIIHexDecode", "AHx", "ASCII85Decode", "A85",
            "RunLengthDecode", "RL", "Crypt", "JPXDecode", "JBIG2Decode"
        ];
        var types = names
            .Select(name => FilterFactory.Instance.GetFilter(name).GetType().Name)
            .ToList();
        Observe("filter-factory", "production-names-and-aliases", Join(types));

        var sample = FilterSample();
        COSName[] roundTripNames =
        [
            COSName.Ascii85Decode,
            COSName.AsciiHexDecode,
            COSName.FlateDecode,
            COSName.LzwDecode,
            COSName.RunLengthDecode,
            COSName.Crypt
        ];
        foreach (var name in roundTripNames)
        {
            var filter = FilterFactory.Instance.GetFilter(name);
            var parameters = new COSDictionary();
            if (COSName.Crypt.Equals(name))
                parameters.SetItem(COSName.Name, COSName.Identity);
            using var input = new MemoryStream(sample, writable: false);
            using var encoded = new MemoryStream();
            filter.Encode(input, encoded, parameters, 0);
            var encodedBytes = encoded.ToArray();
            using var encodedInput = new MemoryStream(encodedBytes, writable: false);
            using var decoded = new MemoryStream();
            filter.Decode(encodedInput, decoded, parameters, 0);
            var decodedBytes = decoded.ToArray();
            Observe(
                "filter-roundtrip",
                name.GetName(),
                COSName.FlateDecode.Equals(name)
                    ? Join(
                        "runtime-zlib",
                        encodedBytes.Length > 2,
                        sample.SequenceEqual(decodedBytes),
                        Sha256(decodedBytes))
                    : Join(
                        encodedBytes.Length,
                        Sha256(encodedBytes),
                        sample.SequenceEqual(decodedBytes),
                        Sha256(decodedBytes)));
        }

        var ccitt = FilterFactory.Instance.GetFilter(COSName.CcittfaxDecode);
        var bitmap = Bytes(
            0x00, 0xff, 0x55, 0xaa, 0x0f, 0xf0, 0x33, 0xcc,
            0x81, 0x7e, 0x18, 0xe7, 0x42, 0xbd, 0x24, 0xdb);
        var ccittParameters = new COSDictionary();
        ccittParameters.SetInt(COSName.Columns, 16);
        ccittParameters.SetInt(COSName.Rows, 8);
        ccittParameters.SetItem(COSName.Filter, COSName.CcittfaxDecode);
        var ccittDecode = new COSDictionary();
        ccittDecode.SetInt(COSName.Columns, 16);
        ccittDecode.SetInt(COSName.Rows, 8);
        ccittDecode.SetInt(COSName.K, -1);
        ccittDecode.SetBoolean(COSName.BlackIs1, true);
        ccittParameters.SetItem(COSName.DecodeParms, ccittDecode);
        using var ccittInput = new MemoryStream(bitmap, writable: false);
        using var ccittEncoded = new MemoryStream();
        ccitt.Encode(ccittInput, ccittEncoded, ccittParameters, 0);
        var ccittEncodedBytes = ccittEncoded.ToArray();
        using var ccittEncodedInput =
            new MemoryStream(ccittEncodedBytes, writable: false);
        using var ccittDecoded = new MemoryStream();
        ccitt.Decode(ccittEncodedInput, ccittDecoded, ccittParameters, 0);
        var ccittDecodedBytes = ccittDecoded.ToArray();
        Observe(
            "filter-roundtrip",
            "CCITTFaxDecode",
            Join(
                ccittEncodedBytes.Length,
                Sha256(ccittEncodedBytes),
                bitmap.SequenceEqual(ccittDecodedBytes),
                Sha256(ccittDecodedBytes)));

        var jpeg = Path.Combine(
            resources, "org", "apache", "pdfbox", "pdmodel", "graphics", "image",
            "jpeg.jpg");
        var jpegBytes = File.ReadAllBytes(jpeg);
        using var jpegInput = new MemoryStream(jpegBytes, writable: false);
        using var dctDecoded = new MemoryStream();
        FilterFactory.Instance.GetFilter(COSName.DctDecode).Decode(
            jpegInput,
            dctDecoded,
            new COSDictionary(),
            0,
            DecodeOptions.Default);
        Observe(
            "filter-roundtrip",
            "DCTDecode-fixture",
            Join(jpegBytes.Length, dctDecoded.Length, Sha256(dctDecoded.ToArray())));

        Observe(
            "filter-error",
            "invalid-and-provider-dependent",
            Join(
                FailureKind(() => FilterFactory.Instance.GetFilter("NoSuchFilter")),
                FailureKind(
                    () => FilterFactory.Instance.GetFilter(COSName.JpxDecode).Decode(
                        new MemoryStream(),
                        new MemoryStream(),
                        new COSDictionary(),
                        0)),
                FailureKind(
                    () => FilterFactory.Instance.GetFilter(COSName.Jbig2Decode).Decode(
                        new MemoryStream(),
                        new MemoryStream(),
                        new COSDictionary(),
                        0))));
    }

    private static void ObservePredictors()
    {
        ObservePredictor(2, 1, 8, Bytes(0x5d), Bytes(0x69));
        ObservePredictor(2, 2, 4, Bytes(0x1b), Bytes(0x1e));
        ObservePredictor(2, 4, 2, Bytes(0x13), Bytes(0x14));
        ObservePredictor(2, 8, 5, Bytes(1, 1, 1, 1, 1), Bytes(1, 2, 3, 4, 5));
        ObservePredictor(
            2,
            16,
            3,
            Bytes(0, 1, 0, 2, 0, 3),
            Bytes(0, 1, 0, 3, 0, 6));

        var first = Bytes(3, 5, 8, 13, 21);
        var second = Bytes(34, 55, 89, 144, 233);
        for (var png = 0; png <= 4; png++)
        {
            var encoded = Concat(
                [(byte)png],
                EncodePngRow(first, null, png),
                [(byte)png],
                EncodePngRow(second, first, png));
            ObservePredictor(10 + png, 8, 5, encoded, Concat(first, second));
        }
        var adaptive = Concat(
            [0],
            EncodePngRow(first, null, 0),
            [4],
            EncodePngRow(second, first, 4));
        ObservePredictor(15, 8, 5, adaptive, Concat(first, second));
    }

    private static void ObservePredictor(
        int predictor, int bits, int columns, byte[] encodedRows, byte[] expected)
    {
        var flate = FilterFactory.Instance.GetFilter(COSName.FlateDecode);
        using var predictorInput = new MemoryStream(encodedRows, writable: false);
        using var compressed = new MemoryStream();
        flate.Encode(predictorInput, compressed, new COSDictionary(), 0);
        var parameters = new COSDictionary();
        parameters.SetItem(COSName.Filter, COSName.FlateDecode);
        var decode = new COSDictionary();
        decode.SetInt(COSName.Predictor, predictor);
        decode.SetInt(COSName.Colors, 1);
        decode.SetInt(COSName.BitsPerComponent, bits);
        decode.SetInt(COSName.Columns, columns);
        parameters.SetItem(COSName.DecodeParms, decode);
        using var compressedInput =
            new MemoryStream(compressed.ToArray(), writable: false);
        using var actual = new MemoryStream();
        flate.Decode(compressedInput, actual, parameters, 0);
        var actualBytes = actual.ToArray();
        Observe(
            "filter-predictor",
            $"{predictor}-bpc-{bits}",
            Join(Hex(expected), Hex(actualBytes), expected.SequenceEqual(actualBytes)));
    }

    private static void ObserveObjectStreams()
    {
        using var stream = new COSStream();
        stream.SetItem(COSName.N, COSInteger.Two);
        stream.SetItem(COSName.First, COSInteger.Get(8));
        using (var output = stream.CreateOutputStream())
            output.Write(Encoding.ASCII.GetBytes("4 0 6 5 true false"));
        var parser = new PDFObjectStreamParser(stream, null);
        var offsets = parser.ReadObjectNumbers();
        var objects = new PDFObjectStreamParser(stream, null).ParseAllObjects();
        Observe(
            "parser-object-stream",
            "offsets-and-values",
            Join(
                offsets.Count,
                offsets[4],
                offsets[6],
                objects.Count,
                ReferenceEquals(objects[new COSObjectKey(4, 0)], COSBoolean.True),
                ReferenceEquals(objects[new COSObjectKey(6, 0)], COSBoolean.False)));
    }

    private static string WriteFull(string implementation)
    {
        var full = Path.Combine(exchange, implementation + "-full.pdf");
        var compressed = Path.Combine(exchange, implementation + "-compressed.pdf");
        using (var document = new PDDocument())
        {
            document.AddPage(new PDPage());
            document.GetDocumentInformation().SetTitle("baseline");
            document.GetDocumentInformation().SetCustomMetadataValue(
                "Probe", "low-level");
            document.Save(new FileInfo(full), CompressParameters.NoCompression);
        }
        using (var document = new PDDocument())
        {
            document.AddPage(new PDPage());
            document.GetDocumentInformation().SetTitle("compressed");
            document.Save(
                new FileInfo(compressed), CompressParameters.DefaultCompression);
        }
        Observe("writer-full", "uncompressed", InspectPdf(full));
        Observe("writer-xref", "compressed-object-stream", InspectPdf(compressed));
        Observe(
            "byte-invariant",
            "full-save-markers",
            InspectMarkers(File.ReadAllBytes(full), false));
        Observe(
            "byte-invariant",
            "compressed-save-markers",
            InspectMarkers(File.ReadAllBytes(compressed), false));
        return full;
    }

    private static void WriteIncremental(string implementation, string full)
    {
        var incremental =
            Path.Combine(exchange, implementation + "-incremental.pdf");
        using (var document = Loader.LoadPDF(new FileInfo(full)))
        {
            document.GetDocumentInformation().SetTitle("incremental");
            ((COSUpdateInfo)document.GetDocumentInformation().GetCOSObject())
                .SetNeedToBeUpdated(true);
            using var output = File.Create(incremental);
            document.SaveIncremental(output);
        }
        Observe(
            "writer-incremental",
            "append-and-reopen",
            Join(
                new FileInfo(incremental).Length > new FileInfo(full).Length,
                InspectPdf(incremental),
                InspectMarkers(File.ReadAllBytes(incremental), true)));
    }

    private static void ObserveParsing(string full)
    {
        var valid = File.ReadAllBytes(full);
        var damagedHeader = (byte[])valid.Clone();
        damagedHeader[1] = (byte)'X';
        Observe(
            "parser-strict",
            "damaged-header",
            FailureKind(() => Parse(damagedHeader, false)));
        Observe(
            "parser-lenient",
            "damaged-header",
            InspectDocument(Parse(damagedHeader, true)));

        var damagedXref = DamageStartXref(valid);
        Observe(
            "parser-strict",
            "damaged-startxref",
            FailureKind(() => Parse(damagedXref, false)));
        Observe(
            "parser-recovery",
            "damaged-startxref",
            InspectDocument(Parse(damagedXref, true)));
    }

    private static void ObserveFixtures()
    {
        var parserResources =
            Path.Combine(resources, "org", "apache", "pdfbox", "pdfparser");
        Observe(
            "parser-fixture",
            "simple-form",
            InspectPdf(Path.Combine(parserResources, "SimpleForm2Fields.pdf")));
        Observe(
            "parser-fixture",
            "compressed-acroform",
            InspectPdf(Path.Combine(resources, "input", "compression", "acroform.pdf")));
        Observe(
            "parser-recovery",
            "missing-catalog",
            FailureKind(
                () => Loader.LoadPDF(
                    new FileInfo(Path.Combine(parserResources, "MissingCatalog.pdf")))));

        var encrypted = Path.Combine(
            resources,
            "org",
            "apache",
            "pdfbox",
            "encryption",
            "PasswordSample-128bit.pdf");
        using var user = Loader.LoadPDF(new FileInfo(encrypted), "user");
        using var owner = Loader.LoadPDF(new FileInfo(encrypted), "owner");
        Observe(
            "parser-encryption",
            "password-and-permissions",
            Join(
                user.GetNumberOfPages(),
                user.IsEncrypted(),
                user.GetCurrentAccessPermission().IsOwnerPermission(),
                owner.GetCurrentAccessPermission().IsOwnerPermission(),
                FailureKind(
                    () => Loader.LoadPDF(new FileInfo(encrypted), "wrong"))));
    }

    private static void ObserveCrossRuntime(string implementation)
    {
        var full = Path.Combine(exchange, implementation + "-full.pdf");
        var compressed = Path.Combine(exchange, implementation + "-compressed.pdf");
        var incremental =
            Path.Combine(exchange, implementation + "-incremental.pdf");
        if (!File.Exists(full) || !File.Exists(compressed) || !File.Exists(incremental))
            throw new IOException("Missing cross-runtime PDFBox output");
        Observe("cross-reopen", "other-full", InspectPdf(full));
        Observe("cross-reopen", "other-compressed", InspectPdf(compressed));
        Observe("cross-reopen", "other-incremental", InspectPdf(incremental));
    }

    private static PDDocument Parse(byte[] bytes, bool lenient)
    {
        var parser = new PDFParser(
            new RandomAccessReadBuffer(new MemoryStream(bytes, writable: false)));
        return parser.Parse(lenient);
    }

    private static string InspectPdf(string path)
    {
        return InspectDocument(Loader.LoadPDF(new FileInfo(path)));
    }

    private static string InspectDocument(PDDocument document)
    {
        using (document)
        {
            var cos = document.GetDocument();
            long maximum = 0;
            var objectStreamEntries = 0;
            foreach (var entry in cos.GetXrefTable())
            {
                if (entry.Key is not null)
                    maximum = Math.Max(maximum, entry.Key.GetNumber());
                if (entry.Value < 0)
                    objectStreamEntries++;
            }
            var size = cos.GetTrailer().GetLong(COSName.Size);
            return Join(
                document.GetNumberOfPages(),
                document.GetDocumentInformation().GetTitle(),
                cos.IsXRefStream(),
                cos.GetXrefTable().Count,
                size,
                maximum + 1 == size,
                objectStreamEntries);
        }
    }

    private static string InspectMarkers(byte[] bytes, bool incremental)
    {
        var text = Encoding.Latin1.GetString(bytes);
        return Join(
            text.StartsWith("%PDF-", StringComparison.Ordinal),
            Count(text, "startxref"),
            Count(text, "%%EOF"),
            incremental ? Count(text, "%%EOF") >= 2 : Count(text, "%%EOF") == 1,
            text.LastIndexOf("startxref", StringComparison.Ordinal)
                < text.LastIndexOf("%%EOF", StringComparison.Ordinal));
    }

    private static byte[] DamageStartXref(byte[] source)
    {
        var damaged = (byte[])source.Clone();
        var marker = Encoding.ASCII.GetBytes("startxref\n");
        var start = LastIndexOf(damaged, marker) + marker.Length;
        if (start < marker.Length)
            throw new ArgumentException("PDF has no startxref marker");
        while (start < damaged.Length && damaged[start] is >= (byte)'0' and <= (byte)'9')
            damaged[start++] = (byte)'9';
        return damaged;
    }

    private static int LastIndexOf(byte[] source, byte[] marker)
    {
        for (var i = source.Length - marker.Length; i >= 0; i--)
        {
            var found = true;
            for (var j = 0; j < marker.Length; j++)
            {
                if (source[i + j] == marker[j])
                    continue;
                found = false;
                break;
            }
            if (found)
                return i;
        }
        return -1;
    }

    private static byte[] EncodePngRow(byte[] row, byte[]? prior, int filter)
    {
        var encoded = new byte[row.Length];
        for (var i = 0; i < row.Length; i++)
        {
            var left = i == 0 ? 0 : row[i - 1];
            var up = prior is null ? 0 : prior[i];
            var upLeft = prior is null || i == 0 ? 0 : prior[i - 1];
            var prediction = filter switch
            {
                0 => 0,
                1 => left,
                2 => up,
                3 => (left + up) / 2,
                4 => Paeth(left, up, upLeft),
                _ => throw new ArgumentException("Unknown PNG predictor")
            };
            encoded[i] = unchecked((byte)(row[i] - prediction));
        }
        return encoded;
    }

    private static int Paeth(int left, int up, int upLeft)
    {
        var value = left + up - upLeft;
        var leftDistance = Math.Abs(value - left);
        var upDistance = Math.Abs(value - up);
        var upLeftDistance = Math.Abs(value - upLeft);
        if (leftDistance <= upDistance && leftDistance <= upLeftDistance)
            return left;
        return upDistance <= upLeftDistance ? up : upLeft;
    }

    private static byte[] FilterSample()
    {
        var sample = new byte[1024];
        for (var i = 0; i < sample.Length; i++)
            sample[i] = unchecked((byte)(i % 17 < 6 ? i % 4 : i * 37 + 11));
        return sample;
    }

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (InvalidPasswordException)
        {
            return "invalid-password";
        }
        catch (NotSupportedException)
        {
            return "unsupported";
        }
        catch (ArgumentException)
        {
            return "invalid-argument";
        }
        catch (InvalidOperationException)
        {
            return "invalid-operation";
        }
        catch (EndOfStreamException)
        {
            return "eof";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (Exception error)
        {
            return error.GetType().Name;
        }
    }

    private static void Observe(string family, string id, object value)
    {
        Observations.Add($"{family}\t{id}\t{Value(value)}");
    }

    private static string Join(params object[] values)
    {
        return string.Join("|", values.Select(Value));
    }

    private static string Join(IEnumerable<string> values)
    {
        return string.Join(",", values.Select(Value));
    }

    private static string JoinObjectKeys(IEnumerable<COSObjectKey> values)
    {
        return string.Join(
            ",",
            values.Select(value =>
                $"{value.GetNumber().ToString(CultureInfo.InvariantCulture)}:" +
                value.GetGeneration().ToString(CultureInfo.InvariantCulture)));
    }

    private static string Value(object value)
    {
        return value switch
        {
            null => "null",
            bool boolean => boolean ? "true" : "false",
            IFormattable formattable =>
                formattable.ToString(null, CultureInfo.InvariantCulture)
                    .Replace('\t', ' ')
                    .Replace('\n', ' '),
            _ => value.ToString()!.Replace('\t', ' ').Replace('\n', ' ')
        };
    }

    private static string Sha256(byte[] bytes)
    {
        return Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    }

    private static string Hex(byte[] bytes)
    {
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    private static int Count(string value, string needle)
    {
        var count = 0;
        var offset = 0;
        while ((offset = value.IndexOf(needle, offset, StringComparison.Ordinal)) >= 0)
        {
            count++;
            offset += needle.Length;
        }
        return count;
    }

    private static byte[] Bytes(params int[] values)
    {
        return values.Select(value => unchecked((byte)value)).ToArray();
    }

    private static byte[] Concat(params byte[][] values)
    {
        var result = new byte[values.Sum(value => value.Length)];
        var offset = 0;
        foreach (var value in values)
        {
            Buffer.BlockCopy(value, 0, result, offset, value.Length);
            offset += value.Length;
        }
        return result;
    }
}
