#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using DripSharp.Runtime;

namespace DripSharp.Validation.JavaCompatProof;

internal static class Program
{
    private const string Header = "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1";

    private sealed record Provenance(
        string CompatType,
        string JdkContract,
        string Targets,
        string[] ProofRows);

    private sealed record Observation(
        string Family,
        string Id,
        string Value);

    private static int Main(string[] arguments)
    {
        try
        {
            if (arguments is ["--list-types"])
            {
                foreach (var type in RuntimeTypes().OrderBy(
                             TypeName, StringComparer.Ordinal))
                {
                    Console.WriteLine(TypeName(type));
                }

                return 0;
            }

            if (arguments is not [var output, var provenanceFile])
                throw new ArgumentException(
                    "Usage: DripSharp.JavaCompat.DirectProbe " +
                    "<output.tsv> <TypeProvenance.tsv>");

            var provenance = ReadProvenance(provenanceFile);
            ValidateRuntimeInventory(provenance);
            var observations = TypeObservations(provenance)
                .Concat(BehaviorObservations())
                .OrderBy(row => row.Family, StringComparer.Ordinal)
                .ThenBy(row => row.Id, StringComparer.Ordinal)
                .ToArray();
            ValidateProofRows(provenance, observations);
            WriteObservations(output, observations);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static Type[] RuntimeTypes() =>
        typeof(JavaCompat).Assembly
            .GetTypes()
            .Where(type =>
                type.Namespace == "DripSharp.Runtime" &&
                type.DeclaringType is null)
            .ToArray();

    private static string TypeName(Type type) =>
        type.Name.Split('`')[0];

    private static Provenance[] ReadProvenance(string file)
    {
        var lines = File.ReadAllLines(file, Encoding.UTF8);
        if (lines.Length == 0 ||
            lines[0] != "compat-type\tjdk-contract\ttargets\tproof-rows")
        {
            throw new InvalidDataException(
                "JavaCompat provenance has the wrong header.");
        }

        return lines.Skip(1).Select((line, index) =>
        {
            var fields = line.Split('\t');
            if (fields.Length != 4 || fields.Any(string.IsNullOrWhiteSpace))
                throw new InvalidDataException(
                    $"Malformed JavaCompat provenance row {index + 2}.");
            return new Provenance(
                fields[0],
                fields[1],
                fields[2],
                fields[3].Split(',', StringSplitOptions.RemoveEmptyEntries));
        }).ToArray();
    }

    private static void ValidateRuntimeInventory(Provenance[] provenance)
    {
        var runtimeTypes = RuntimeTypes();
        var actual = runtimeTypes.Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        var expected = provenance.Select(row => row.CompatType)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (!actual.SequenceEqual(expected, StringComparer.Ordinal))
        {
            throw new InvalidDataException(
                "JavaCompat provenance does not exactly cover the compiled " +
                $"runtime inventory.\nExpected: {string.Join(", ", expected)}" +
                $"\nActual: {string.Join(", ", actual)}");
        }

        var publicTypes = runtimeTypes.Where(type => type.IsPublic)
            .Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (publicTypes.Length != 0)
        {
            throw new InvalidDataException(
                "The direct probe must retain package-internal JavaCompat " +
                $"visibility: {string.Join(", ", publicTypes)}");
        }
    }

    private static IEnumerable<Observation> TypeObservations(
        IEnumerable<Provenance> provenance) =>
        provenance.Select(row =>
            new Observation("type-contract", row.CompatType, "available"));

    private static void ValidateProofRows(
        IEnumerable<Provenance> provenance,
        IEnumerable<Observation> observations)
    {
        var identities = observations
            .Select(row => row.Family + "/" + row.Id)
            .ToHashSet(StringComparer.Ordinal);
        foreach (var row in provenance)
        {
            var requiredTypeRow = "type-contract/" + row.CompatType;
            if (!row.ProofRows.Contains(requiredTypeRow, StringComparer.Ordinal))
            {
                throw new InvalidDataException(
                    $"{row.CompatType} does not cite its direct type proof row.");
            }

            var missing = row.ProofRows
                .Where(proof => !identities.Contains(proof))
                .ToArray();
            if (missing.Length != 0)
            {
                throw new InvalidDataException(
                    $"{row.CompatType} cites missing proof rows: " +
                    string.Join(", ", missing));
            }
        }
    }

    private static IEnumerable<Observation> BehaviorObservations()
    {
        yield return Observe("atomic-primitives", AtomicPrimitives());
        yield return Observe("base64", Base64Contract());
        yield return Observe("bit-set", BitSetContract());
        yield return Observe("byte-buffer", ByteBufferContract());
        yield return Observe("charset-malformed", CharsetMalformedContract());
        yield return Observe("collections", CollectionsContract());
        yield return Observe("compression", CompressionContract());
        yield return Observe("crc32", Crc32Contract());
        yield return Observe("data-output", DataOutputContract());
        yield return Observe("decimal-format", DecimalFormatContract());
        yield return Observe("message-digest", MessageDigestContract());
        yield return Observe("message-format", MessageFormatContract());
        yield return Observe("optional", OptionalContract());
        yield return Observe("regex", RegexContract());
        yield return Observe("signed-byte-or", SignedByteOrContract());
        yield return Observe("strict-math", StrictMathContract());
        yield return Observe("string-identity", StringIdentityContract());
        yield return Observe("string-tools", StringToolsContract());
        yield return Observe("time-format", TimeFormatContract());
        yield return Observe("uri", UriContract());
        yield return Observe("xpath", XPathContract());
    }

    private static Observation Observe(string id, string value) =>
        new("behavior", id, value);

    private static string AtomicPrimitives()
    {
        var boolean = new JavaAtomicBoolean();
        var integer = new JavaAtomicInteger(40);
        var first = new object();
        var second = new object();
        var reference = new JavaAtomicReference<object>(first);
        using var local = JavaThreadLocal<string>.WithInitial(() => "initial");
        var previous = boolean.GetAndSet(true);
        var swapped = boolean.CompareAndSet(true, false);
        local.Set("thread");
        return $"{Lower(previous)}|{Lower(swapped)}|" +
               $"{Lower(boolean.Get())}|{integer.IncrementAndGet()}|" +
               $"{Lower(ReferenceEquals(reference.GetAndSet(second), first))}|" +
               local.Get();
    }

    private static string Base64Contract()
    {
        var value = Encoding.UTF8.GetBytes("compat")
            .Select(item => unchecked((sbyte)item)).ToArray();
        return JavaBase64.GetEncoder().EncodeToString(value);
    }

    private static string BitSetContract()
    {
        var bits = new JavaBitSet();
        bits.set(2, 5);
        bits.clear(3);
        return $"{bits.get(2)}|{bits.get(3)}|{bits.nextSetBit(3)}"
            .ToLowerInvariant();
    }

    private static string ByteBufferContract()
    {
        using var buffer = JavaByteBuffer.wrap([1, 2, 3, 4, 5]);
        var integer = buffer.getInt();
        return $"{integer}|{buffer.Remaining}|{buffer.get()}";
    }

    private static string CharsetMalformedContract()
    {
        try
        {
            using var buffer = JavaByteBuffer.wrap(
                [unchecked((sbyte)0xc3), 0x28]);
            _ = new JavaCharsetDecoder(Encoding.UTF8).Decode(buffer);
            return "accepted";
        }
        catch (DecoderFallbackException)
        {
            return "rejected";
        }
    }

    private static string CollectionsContract()
    {
        var linked = new JavaLinkedHashMap<string, string>();
        linked.Add("first", "before");
        linked.Add("second", "two");
        using var entries = JavaCompat.MapEntrySet(
            (IDictionary<string, string>)linked).GetEnumerator();
        if (!entries.MoveNext())
            throw new InvalidDataException("Missing linked-map entry.");
        var entry = entries.Current;
        var prior = entry.SetValue("after");

        var deque = new JavaDeque<string>();
        deque.Add("tail");
        deque.Push("head");
        return $"{prior}|{entry.Value}|" +
               $"{string.Join(",", linked.Keys)}|{deque.Pop()},{deque.Pop()}";
    }

    private static string CompressionContract()
    {
        using var compressed = new MemoryStream();
        using (var compressor = new ZLibStream(
                   compressed, CompressionLevel.Optimal, leaveOpen: true))
        {
            compressor.Write(Encoding.UTF8.GetBytes("deflate-body"));
        }

        using var decoded = new MemoryStream();
        using var inflater = new JavaInflaterOutputStream(decoded);
        var encoded = compressed.ToArray();
        inflater.Write(encoded, 0, encoded.Length);
        inflater.Flush();
        return Encoding.UTF8.GetString(decoded.ToArray());
    }

    private static string Crc32Contract()
    {
        var crc = new JavaCrc32();
        var value = Encoding.ASCII.GetBytes("123456789")
            .Select(item => unchecked((sbyte)item)).ToArray();
        crc.Update(value, 0, value.Length);
        return crc.GetValue().ToString(CultureInfo.InvariantCulture);
    }

    private static string DataOutputContract()
    {
        using var output = new MemoryStream();
        using (var data = new JavaDataOutputStream(output))
        {
            data.writeByte(0x7f);
            data.writeShort(0x1234);
            data.writeInt(unchecked((int)0x89abcdef));
        }
        return Convert.ToHexString(output.ToArray()).ToLowerInvariant();
    }

    private static string DecimalFormatContract()
    {
        var format = new JavaDecimalFormat(
            "0.0000000", CultureInfo.InvariantCulture.NumberFormat);
        return format.Format(123456789.123456789d) + "|" +
               format.Format(-0.0d);
    }

    private static string MessageDigestContract()
    {
        var value = Encoding.ASCII.GetBytes("abc")
            .Select(item => unchecked((sbyte)item)).ToArray();
        var digest = JavaMessageDigest.GetInstance("SHA-256").Digest(value);
        return Convert.ToHexString(
            digest.Select(item => unchecked((byte)item)).ToArray())
            .ToLowerInvariant();
    }

    private static string MessageFormatContract()
    {
        var format = new JavaMessageFormat(
            "{0}={1,number,000.00}", CultureInfo.GetCultureInfo("en-US"));
        return format.Format(new object?[] { "value", 12.5d });
    }

    private static string OptionalContract()
    {
        var present = JavaOptional<string>.Of("x")
            .Map(value => value + "y").OrElse("missing");
        var empty = JavaOptional<string>.Empty().OrElse("fallback");
        return present + "|" + empty;
    }

    private static string RegexContract()
    {
        var pattern = JavaCompat.CompileRegex(@"(?<word>\p{L}+)");
        var matcher = JavaCompat.RegexMatcher(pattern, "é42");
        if (!matcher.Find())
            throw new InvalidDataException("Regex did not match.");
        return $"{matcher.Group("word")}|{matcher.Start()}|{matcher.End()}";
    }

    private static string SignedByteOrContract()
    {
        sbyte value = -128;
        var first = JavaCompat.OrAssign(ref value, 1);
        value = 1;
        var second = JavaCompat.OrAssign(ref value, 0x180);
        return $"{first}|{second}";
    }

    private static string StrictMathContract() =>
        string.Join("|",
            JavaCompat.StringValueOf(JavaStrictMath.Sin(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Cos(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Log10(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Pow(2.3d, -4.0d)));

    private static string StringIdentityContract() =>
        $"{JavaCompat.StringHashCode("DripSharp")}|" +
        $"{JavaCompat.ToHexString(-65536)}|" +
        $"{JavaCompat.ToHexString(-65536L)}|" +
        JavaCompat.StringValueOf((object?)null);

    private static string StringToolsContract()
    {
        var joiner = new JavaStringJoiner(",", "[", "]")
            .add("a").add("b");
        var tokenizer = new JavaStringTokenizer("one two\tthree");
        var tokens = new List<string>();
        while (tokenizer.hasMoreTokens())
            tokens.Add(tokenizer.nextToken());
        return joiner + "|" + string.Join(",", tokens);
    }

    private static string TimeFormatContract()
    {
        var instant = new DateTimeOffset(
            2024, 1, 2, 3, 4, 5, TimeSpan.Zero);
        return JavaDateTimeFormatter.Rfc1123.Format(instant) + "|" +
               JavaTimeUnits.ToTimeSpan(1500, JavaTimeUnit.MILLISECONDS)
                   .TotalMilliseconds.ToString(CultureInfo.InvariantCulture);
    }

    private static string UriContract()
    {
        var hostless = JavaCompat.NewUri("http:///submit");
        var basis = JavaCompat.CreateUri("file:///tmp/PklProject");
        var resolved = JavaCompat.ResolveUri(basis, ".");
        return $"{JavaCompat.UriHost(hostless) ?? "null"}|" +
               $"{JavaCompat.UriRawPath(hostless)}|" +
               $"{JavaCompat.UriAuthority(resolved) ?? "null"}|" +
               JavaCompat.UriRawPath(resolved);
    }

    private static string XPathContract()
    {
        var document = new XmlDocument();
        document.LoadXml("<root><item>value</item></root>");
        return JavaXPathFactory.Instance.NewXPath()
            .Evaluate("/root/item", document);
    }

    private static string Lower(bool value) =>
        value ? "true" : "false";

    private static void WriteObservations(
        string file,
        IEnumerable<Observation> observations)
    {
        var lines = new[] { Header }.Concat(observations.Select(row =>
            $"{row.Family}\t{row.Id}\t{row.Value}"));
        File.WriteAllText(
            file,
            string.Join("\n", lines) + "\n",
            new UTF8Encoding(false));
    }
}
