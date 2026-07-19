using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Pkl.Core;

/** Package-only .NET probe for independently normalized Pkl.Core observations. */
static class CorePackageProbe
{
    private static readonly Encoding JavaUtf8 = Encoding.GetEncoding(
        Encoding.UTF8.CodePage,
        new EncoderReplacementFallback("?"),
        new DecoderReplacementFallback("\uFFFD"));

    public static void Main(string[] args)
    {
        if (args.Length != 2) throw new ArgumentException("manifest and output paths are required");
        string output = Path.GetFullPath(args[1]);
        string work = Path.Combine(Path.GetDirectoryName(output)!, "package-work");
        Directory.CreateDirectory(work);

        using var writer = new StreamWriter(output, false, new UTF8Encoding(false));
        WriteValueModelObservations(writer);
        foreach (string line in File.ReadLines(args[0], Encoding.UTF8))
        {
            if (line.Length == 0) continue;
            string[] fields = line.Split('\t');
            if (fields.Length != 4) throw new ArgumentException("invalid core manifest line");
            string id = fields[0];
            string operation = fields[1];
            string source = Decode(fields[2]);
            string argument = Decode(fields[3]);
            PrepareFixtures(work, operation, argument);
            string module = Path.Combine(work, SafeName(id) + ".pkl");
            File.WriteAllText(module, source, new UTF8Encoding(false));
            using Evaluator evaluator = Evaluator.Preconfigured();
            Write(writer, id, operation, Observe(evaluator, operation, module, argument));
        }
    }

    static void WriteValueModelObservations(StreamWriter writer)
    {
        Duration duration = Duration.OfSeconds(90);
        Write(writer, "@duration", "VALUE",
            $"{Normalize(duration)}|minutes={DoubleBits(duration.InMinutes())}|iso={Encode(duration.ToIsoString())}|equal={Lower(duration.Equals(Duration.OfMinutes(1.5)))}");

        DataSize dataSize = DataSize.OfKibibytes(2);
        Write(writer, "@data-size", "VALUE",
            $"{Normalize(dataSize)}|bytes={DoubleBits(dataSize.InBytes())}|equal={Lower(dataSize.Equals(DataSize.OfBytes(2048)))}");

        var pair = new Pair<object, object>("answer", 42L);
        Write(writer, "@pair", "VALUE", $"{Normalize(pair)}|render={Encode(pair.ToString())}");
        Write(writer, "@null", "VALUE",
            $"{Normalize(PNull.GetInstance())}|singleton={Lower(ReferenceEquals(PNull.GetInstance(), PNull.GetInstance()))}|render={Encode(PNull.GetInstance().ToString())}");

        ModuleSource uri = ModuleSource.Uri(new Uri("file:///independent-core-oracle.pkl"));
        Write(writer, "@module-source", "RUNTIME",
            $"uri={Encode(uri.GetUri().AbsoluteUri)},contents-null={Lower(uri.GetContents() is null)}");

        PClassInfo<object> classInfo = PClassInfo<object>.Get(
            "pkl.base", "String", new Uri("pkl:base"));
        Write(writer, "@class-info", "VALUE",
            $"qualified={Encode(classInfo.GetQualifiedName())},display={Encode(classInfo.GetDisplayName())},equal={Lower(PClassInfo<object>.String.Equals(classInfo))}");
    }

    static string Observe(Evaluator evaluator, string operation, string module, string argument)
    {
        try
        {
            ModuleSource source = ModuleSource.Uri(new Uri(Path.GetFullPath(module)));
            if (operation == "OUTPUT_FILES")
                return "OK|" + NormalizeFileOutputs(evaluator.EvaluateOutputFiles(source));
            object result = operation switch
            {
                "EVALUATE" or "LOCAL_IMPORT" or "FILE_RESOURCE" => evaluator.Evaluate(source),
                "EXPRESSION" => evaluator.EvaluateExpression(source, argument),
                "EXPRESSION_STRING" => evaluator.EvaluateExpressionString(source, argument),
                "OUTPUT_TEXT" => evaluator.EvaluateOutputText(source),
                "OUTPUT_BYTES" => evaluator.EvaluateOutputBytes(source),
                "OUTPUT_VALUE" => evaluator.EvaluateOutputValue(source),
                "OUTPUT_VALUE_AS_STRING" => evaluator.EvaluateOutputValueAs(source, PClassInfo<object>.String),
                "SECURITY_DENIED" => EvaluateWithDeniedModules(source),
                _ => throw new ArgumentException("unknown operation: " + operation),
            };
            return "OK|" + Normalize(result);
        }
        catch (PklException error)
        {
            if (Environment.GetEnvironmentVariable("VIBEFORMER_DIFFERENTIAL_DEBUG") is not null)
                Console.Error.WriteLine(error);
            return "ERROR|" + NormalizeError(error.Message);
        }
    }

    static object EvaluateWithDeniedModules(ModuleSource source)
    {
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetAllowedModules(new List<Regex>()).Build();
        return evaluator.Evaluate(source);
    }

    static void PrepareFixtures(string work, string operation, string argument)
    {
        switch (operation)
        {
            case "LOCAL_IMPORT":
                File.WriteAllText(Path.Combine(work, "dependency.pkl"), argument, new UTF8Encoding(false));
                break;
            case "FILE_RESOURCE":
                File.WriteAllText(Path.Combine(work, "resource.txt"), argument, new UTF8Encoding(false));
                break;
        }
    }

    static string NormalizeFileOutputs(IDictionary<string, FileOutput> outputs) =>
        "files{" + string.Join(",", outputs.OrderBy(entry => entry.Key, StringComparer.Ordinal)
            .Select(entry => Encode(entry.Key) + "=text:" + Encode(entry.Value.GetText()) + "," +
                Normalize(entry.Value.GetBytes()))) + "}";

    static string Normalize(object? value)
    {
        if (value is null || ReferenceEquals(value, PNull.GetInstance())) return "null";
        if (value is string text) return "string:" + Encode(text);
        if (value is bool boolean) return "bool:" + Lower(boolean);
        if (value is sbyte or byte or short or ushort or int or uint or long)
            return "int:" + Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture);
        if (value is float or double)
            return "float:" + DoubleBits(Convert.ToDouble(value, CultureInfo.InvariantCulture));
        if (value is sbyte[] bytes) return "bytes:" + HexBytes(bytes);
        if (value is Regex regex) return "regex:" + Encode(regex.ToString());
        if (value is Duration duration)
            return $"duration:{DoubleBits(duration.GetValue())}@{duration.GetUnit().GetSymbol()}";
        if (value is DataSize size)
            return $"data-size:{DoubleBits(size.GetValue())}@{size.GetUnit().GetSymbol()}";
        if (value is Pair<object, object> pair)
            return $"pair({Normalize(pair.GetFirst())},{Normalize(pair.GetSecond())})";
        if (value is PModule module)
            return "module:" + Encode(module.GetModuleName()) + NormalizeProperties(module.GetProperties());
        if (value is PObject obj)
            return "object:" + Encode(obj.GetClassInfo().GetQualifiedName()) + NormalizeProperties(obj.GetProperties());
        if (value is IDictionary dictionary) return NormalizeMap(dictionary);
        if (value is ISet<object> set)
        {
            var values = set.Select(Normalize).OrderBy(item => item, StringComparer.Ordinal);
            return "set[" + string.Join(",", values) + "]";
        }
        if (value is IEnumerable enumerable)
        {
            var values = new List<string>();
            foreach (object? item in enumerable) values.Add(Normalize(item));
            return "list[" + string.Join(",", values) + "]";
        }
        throw new ArgumentException("unsupported exported value: " + value.GetType().FullName);
    }

    static string NormalizeProperties(IDictionary<string, object> properties) =>
        "{" + string.Join(",", properties.OrderBy(entry => entry.Key, StringComparer.Ordinal)
            .Select(entry => Encode(entry.Key) + "=" + Normalize(entry.Value))) + "}";

    static string NormalizeMap(IDictionary dictionary)
    {
        var entries = new List<string>();
        foreach (DictionaryEntry entry in dictionary)
            entries.Add(Normalize(entry.Key) + "=" + Normalize(entry.Value));
        entries.Sort(StringComparer.Ordinal);
        return "map{" + string.Join(",", entries) + "}";
    }

    static string NormalizeError(string message)
    {
        if (message.Contains("Unexpected token", StringComparison.Ordinal)) return "syntax:unexpected-token";
        if (message.Contains("Expected value of type `String`, but got type `Int`", StringComparison.Ordinal))
            return "type:expected-string-got-int";
        if (message.Contains("Cannot find property `missing`", StringComparison.Ordinal)) return "evaluation:missing-property";
        string? missingMember = NormalizeMissingMemberError(message);
        if (missingMember is not null) return missingMember;
        if (message.Contains("output.value", StringComparison.Ordinal) &&
            message.Contains("String", StringComparison.Ordinal) &&
            message.Contains("Int", StringComparison.Ordinal))
            return "output-value-type:expected-string-got-int";
        if (message.Contains("does not match any entry in the module allowlist", StringComparison.Ordinal))
            return "security:module-not-allowed";
        return "other:" + Encode(message.Split('\n')[0].TrimEnd('\r'));
    }

    static string? NormalizeMissingMemberError(string message)
    {
        string[] lines = message.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        string? diagnostic = lines.FirstOrDefault(line =>
            line.StartsWith("Cannot find property `", StringComparison.Ordinal) ||
            line.StartsWith("Cannot find method `", StringComparison.Ordinal));
        if (diagnostic is null) return null;

        int header = Array.IndexOf(lines, "Did you mean any of the following?");
        if (header < 0) return null;
        var suggestions = lines.Skip(header + 1).TakeWhile(line => line.Length > 0).ToArray();
        if (suggestions.Length == 0) return null;
        return "missing-member:" + Encode(diagnostic) +
            "|suggestions:" + Encode(string.Join("\n", suggestions));
    }

    static string HexBytes(sbyte[] bytes) =>
        string.Concat(bytes.Select(value => unchecked((byte)value).ToString("x2", CultureInfo.InvariantCulture)));

    static string DoubleBits(double value) =>
        unchecked((ulong)BitConverter.DoubleToInt64Bits(value)).ToString("x16", CultureInfo.InvariantCulture);

    static string SafeName(string id) => new(id.Select(character =>
        char.IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-' ? character : '_').ToArray());

    static string Lower(bool value) => value ? "true" : "false";
    static string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));
    // Java's UTF-8 encoder writes an unpaired UTF-16 surrogate as '?'. Regex
    // zero-width matches can expose surrogate halves, so use the same transport
    // fallback when normalizing otherwise-identical JVM and .NET observations.
    static string Encode(string value) => Convert.ToBase64String(JavaUtf8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string observation) =>
        writer.WriteLine($"{id}\t{kind}\t{Encode(observation)}");
}
