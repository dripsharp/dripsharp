using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using Pkl.Core;

/** Package-only .NET probe for independently normalized Pkl.Core observations. */
static class CorePackageProbe
{
    public static void Main(string[] args)
    {
        if (args.Length != 2) throw new ArgumentException("manifest and output paths are required");
        string output = Path.GetFullPath(args[1]);
        string work = Path.Combine(Path.GetDirectoryName(output)!, "package-work");
        Directory.CreateDirectory(work);

        using var writer = new StreamWriter(output, false, new UTF8Encoding(false));
        using Evaluator evaluator = Evaluator.Preconfigured();
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
            string module = Path.Combine(work, SafeName(id) + ".pkl");
            File.WriteAllText(module, source, new UTF8Encoding(false));
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
    }

    static string Observe(Evaluator evaluator, string operation, string module, string argument)
    {
        try
        {
            ModuleSource source = ModuleSource.Uri(new Uri(Path.GetFullPath(module)));
            object result = operation switch
            {
                "EVALUATE" => evaluator.Evaluate(source),
                "EXPRESSION" => evaluator.EvaluateExpression(source, argument),
                "OUTPUT_VALUE" => evaluator.EvaluateOutputValue(source),
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

    static string Normalize(object? value)
    {
        if (value is null || ReferenceEquals(value, PNull.GetInstance())) return "null";
        if (value is string text) return "string:" + Encode(text);
        if (value is bool boolean) return "bool:" + Lower(boolean);
        if (value is sbyte or byte or short or ushort or int or uint or long)
            return "int:" + Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(CultureInfo.InvariantCulture);
        if (value is float or double)
            return "float:" + DoubleBits(Convert.ToDouble(value, CultureInfo.InvariantCulture));
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
        return "other:" + Encode(message.Split('\n')[0].TrimEnd('\r'));
    }

    static string DoubleBits(double value) =>
        unchecked((ulong)BitConverter.DoubleToInt64Bits(value)).ToString("x16", CultureInfo.InvariantCulture);

    static string SafeName(string id) => new(id.Select(character =>
        char.IsAsciiLetterOrDigit(character) || character is '_' or '.' or '-' ? character : '_').ToArray());

    static string Lower(bool value) => value ? "true" : "false";
    static string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));
    static string Encode(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string observation) =>
        writer.WriteLine($"{id}\t{kind}\t{Encode(observation)}");
}
