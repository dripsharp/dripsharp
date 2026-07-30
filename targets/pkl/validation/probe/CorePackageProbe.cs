using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using DripSharp.Brine;

/** Package-only .NET probe for independently normalized DripSharp.Brine observations. */
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
            $"qualified={Encode(classInfo.GetQualifiedName())},display={Encode(classInfo.GetDisplayName())},equal={Lower(PClassInfo<object>.String.Equals(classInfo))},duration-type={Lower(PClassInfo<object>.Get("pkl.base", "Duration", new Uri("pkl:base")).ValueType == typeof(Duration))}");

        Write(writer, "@value-visitation", "VALUE", ObserveValueVisitation());
        Write(writer, "@value-conversion", "VALUE", ObserveValueConversion());
        Write(writer, "@value-equality-order", "VALUE", ObserveValueEqualityAndOrder());
        Write(writer, "@value-formatter", "FORMAT", ObserveValueFormatter());
        Write(writer, "@value-renderers", "RENDER", ObserveValueRenderers());
        Write(writer, "@idiomatic-data-api", "DOTNET", ObserveIdiomaticDataApi());
    }

    static string ObserveValueVisitation()
    {
        var properties = new Dictionary<string, object> { ["name"] = "Pigeon" };
        var obj = new PObject(PClassInfo<object>.Object.AsObject(), properties);
        var module = new PModule(new Uri("repl:visitor"), "visitor.module",
            PClassInfo<object>.ForModuleClass("visitor.module", new Uri("repl:visitor")), properties);
        var set = new HashSet<object> { "set" };
        var map = new Dictionary<object, object> { ["key"] = 1L };
        using Evaluator evaluator = Evaluator.Preconfigured();
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.Text(
            "class Bird { name: String }\ntypealias Name = String\n"));
        var reference = new Reference(obj, "data", new List<Composite> { obj }, PType.UNKNOWN);
        var values = new List<object>
        {
            PNull.GetInstance(), "text", true, 1L, 1.5d, Duration.OfSeconds(2),
            DataSize.OfBytes(3), new byte[] { 0, 127, 128, 255 },
            new Pair<object, object>("first", 2L), new List<object> { "list" },
            set, map, obj, module, schema.GetClasses()["Bird"],
            schema.GetTypeAliases()["Name"], new Regex("a.+b"), reference,
        };
        var observed = new List<string>();
        foreach (object value in values)
        {
            var recording = new RecordingVisitor();
            ((ValueVisitor)recording).Visit(value);
            observed.Add(recording.Observation);
        }
        var direct = new RecordingVisitor();
        module.Accept(direct);
        bool invalid = ThrowsArgument(() => ((ValueVisitor)new RecordingVisitor()).Visit(new object()));
        return string.Join(",", observed) + $"|direct={direct.Observation}|invalid={Lower(invalid)}";
    }

    static string ObserveValueConversion()
    {
        ValueConverter<string> converter = new RecordingConverter();
        var properties = new Dictionary<string, object> { ["name"] = "Pigeon" };
        var obj = new PObject(PClassInfo<object>.Object.AsObject(), properties);
        var module = new PModule(new Uri("repl:converter"), "converter.module",
            PClassInfo<object>.ForModuleClass("converter.module", new Uri("repl:converter")), properties);
        using Evaluator evaluator = Evaluator.Preconfigured();
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.Text(
            "class Bird { name: String }\ntypealias Name = String\n"));
        var reference = new Reference(obj, "data", new List<Composite> { obj }, PType.UNKNOWN);
        var values = new List<object>
        {
            PNull.GetInstance(), "text", true, 1L, 1.5d, Duration.OfSeconds(2),
            DataSize.OfBytes(3), new Pair<object, object>("first", 2L),
            new List<object> { "list" }, new HashSet<object> { "set" },
            new Dictionary<object, object> { ["key"] = 1L }, obj, module,
            schema.GetClasses()["Bird"], schema.GetTypeAliases()["Name"],
            new Regex("a.+b"), reference,
        };
        var observed = values.Select(converter.Convert).ToList();
        bool bytesRejected = ThrowsArgument(() => converter.Convert(new byte[] { 1 }));
        bool invalid = ThrowsArgument(() => converter.Convert(new object()));
        return string.Join(",", observed) +
            $"|direct-bytes={converter.ConvertBytes(new byte[] { 1 })}" +
            $"|bytes-rejected={Lower(bytesRejected)}|invalid={Lower(invalid)}";
    }

    static string ObserveValueEqualityAndOrder()
    {
        var leftProperties = new Dictionary<string, object> { ["name"] = "Pigeon", ["age"] = 42L };
        var rightProperties = new Dictionary<string, object> { ["age"] = 42L, ["name"] = "Pigeon" };
        var left = new PObject(PClassInfo<object>.Object.AsObject(), leftProperties);
        var right = new PObject(PClassInfo<object>.Object.AsObject(), rightProperties);
        var module = new PModule(new Uri("repl:equality"), "equality.module",
            PClassInfo<object>.ForModuleClass("equality.module", new Uri("repl:equality")), leftProperties);
        var moduleCopy = new PModule(new Uri("repl:equality"), "equality.module",
            PClassInfo<object>.ForModuleClass("equality.module", new Uri("repl:equality")), rightProperties);
        IReadOnlyDictionary<string, object> exportedProperties = left.GetProperties();
        bool protectedIdentity = !ReferenceEquals(exportedProperties, leftProperties) &&
            (exportedProperties is not IDictionary<string, object> mutableProperties ||
             RejectsMutation(() => mutableProperties.Add("other", 2L)));
        var typedPair = new Pair<long, string>(1L, "a");
        var erasedPair = new Pair<object, object>(1L, "a");
        string missingProperty;
        try
        {
            _ = left.GetProperty("missing");
            missingProperty = "no-error";
        }
        catch (NoSuchPropertyException error)
        {
            missingProperty = error.Message;
        }
        return $"object={Lower(left.Equals(right) && left.GetHashCode() == right.GetHashCode())}" +
            $"|module={Lower(module.Equals(moduleCopy) && module.GetHashCode() == moduleCopy.GetHashCode())}" +
            $"|pair={Lower(new Pair<object, object>("a", 1L).Equals(new Pair<object, object>("a", 1L)))}" +
            $"|pair-erased={Lower(typedPair.Equals(erasedPair) && erasedPair.Equals(typedPair))}" +
            $"|duration={Lower(Duration.OfSeconds(90).Equals(Duration.OfMinutes(1.5)))}" +
            $"|size={Lower(DataSize.OfKibibytes(2).Equals(DataSize.OfBytes(2048)))}" +
            $"|class={Lower(PClassInfo<object>.String.Equals(PClassInfo<object>.Get("pkl.base", "String", new Uri("pkl:base"))))}" +
            $"|order={string.Join(",", left.GetProperties().Keys)}" +
            $"|identity={Lower(protectedIdentity)}" +
            $"|missing={Encode(missingProperty)}" +
            $"|render={Encode(module.ToString())}";
    }

    static string ObserveValueFormatter()
    {
        ValueFormatter basic = ValueFormatter.Basic();
        ValueFormatter custom = ValueFormatter.WithCustomStringDelimiters();
        var multiline = new ValueFormatter(true, true);
        var builder = new StringWriter(CultureInfo.InvariantCulture);
        custom.FormatStringValue("\"\"start\\#\nnext\t\r", "  ", builder);
        return $"basic={Encode(basic.FormatStringValue("quote\"slash\\\n", ""))}" +
            $"|custom-quote={Encode(custom.FormatStringValue("\"", ""))}" +
            $"|custom-prefix={Encode(custom.FormatStringValue("\"\"start", ""))}" +
            $"|multiline={Encode(multiline.FormatStringValue("first\nsecond\"\"\"#\\##", "  "))}" +
            $"|builder={Encode(builder.ToString())}";
    }

    static string ObserveValueRenderers()
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(ModuleSource.Text(
            "name = \"Pigeon\"\nage = 3\nactive = true\nitems = List(\"a\", 2)\nnested { value = \"x\" }\nnullable = null\n"));
        var jsonWriter = new StringWriter(CultureInfo.InvariantCulture);
        ValueRenderers.Json(jsonWriter, "  ", true).RenderDocument(module);
        string json = jsonWriter.ToString();
        var pcfWriter = new StringWriter(CultureInfo.InvariantCulture);
        ValueRenderers.Pcf(pcfWriter, "  ", false, true).RenderDocument(module);
        string pcf = pcfWriter.ToString();
        var plistWriter = new StringWriter(CultureInfo.InvariantCulture);
        ValueRenderers.Plist(plistWriter, "  ").RenderDocument(module);
        string plist = plistWriter.ToString();
        var propertiesWriter = new StringWriter(CultureInfo.InvariantCulture);
        PModule propertiesModule = evaluator.Evaluate(ModuleSource.Text(
            "name = \"Pigeon\"\nage = 3\nactive = true\nnested { value = \"x\" }\nnullable = null\n"));
        ValueRenderers.Properties(propertiesWriter, false, true).RenderDocument(propertiesModule);
        string properties = propertiesWriter.ToString();
        return $"json={Encode(json)}|pcf={Encode(pcf)}|plist={Encode(plist)}|properties={Encode(properties)}" +
            $"|newline={Lower(json.EndsWith('\n') && pcf.EndsWith('\n') && plist.EndsWith('\n') && properties.EndsWith('\n'))}" +
            $"|invalid={Lower(InvalidRenderer(ValueRenderers.Json(new StringWriter(), "  ", false)))}:" +
            $"{Lower(InvalidRenderer(ValueRenderers.Pcf(new StringWriter(), "  ", false, false)))}:" +
            $"{Lower(InvalidRenderer(ValueRenderers.Plist(new StringWriter(), "  ")))}:" +
            $"{Lower(InvalidRenderer(ValueRenderers.Properties(new StringWriter(), false, true)))}";
    }

    static string ObserveIdiomaticDataApi()
    {
        ModuleSource source = ModuleSource.FromText("value = 1");
        var obj = new PObject(PClassInfo<object>.Object.AsObject(),
            new Dictionary<string, object> { ["value"] = 1L });
        var reference = new Reference(obj, "data", new List<Composite> { obj }, PType.UNKNOWN);
        using Evaluator evaluator = Evaluator.Preconfigured();
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromText(
            "class Bird { name: String }\ntypealias Name = String\n"));
        IReadOnlyDictionary<string, FileOutput> files = evaluator.EvaluateOutputFilesReadOnly(
            ModuleSource.FromText("output { files { [\"a.txt\"] { text = \"a\" } } }"));
        bool bytes = typeof(Evaluator).GetMethod(nameof(Evaluator.EvaluateOutputBytes))!.ReturnType == typeof(byte[]) &&
            PClassInfo<object>.Bytes.ValueType == typeof(byte[]);
        bool readOnly = RejectsMutation(() => ((IDictionary<string, object>)obj.Properties).Add("other", 2L)) &&
            RejectsMutation(() => ((IDictionary<string, PClass>)schema.Classes).Clear()) &&
            RejectsMutation(() => ((IDictionary<string, FileOutput>)files).Clear()) &&
            RejectsMutation(() => ((IList<Composite>)reference.Path).Add(obj)) &&
            schema.ModuleClass.Modifiers is not ISet<Modifier>;
        bool facades = source.Contents == "value = 1" &&
            source.SourceUri == ModuleSource.Text("x").GetUri() && obj.Properties.Count == 1 &&
            schema.Classes.ContainsKey("Bird") && schema.TypeAliases.ContainsKey("Name") &&
            files["a.txt"].Text == "a" && files["a.txt"].Bytes.SequenceEqual(new byte[] { 97 }) &&
            reference.Path.Count == 1 && ReferenceEquals(reference.Domain, obj) && readOnly;
        bool nullable = ModuleSource.FromUri(new Uri("file:///nullable.pkl")).Contents is null;
        return $"bytes={Lower(bytes)}|facades={Lower(facades)}|nullable={Lower(nullable)}";
    }

    static bool InvalidRenderer(ValueRenderer renderer)
    {
        try { renderer.RenderDocument(new object()); return false; }
        catch (Exception error) when (error is ArgumentException or RendererException) { return true; }
    }

    static bool ThrowsArgument(Action action)
    {
        try { action(); return false; }
        catch (ArgumentException) { return true; }
    }

    static bool RejectsMutation(Action action)
    {
        try { action(); return false; }
        catch (Exception error) when (error is NotSupportedException or InvalidCastException)
        { return true; }
    }

    sealed class RecordingVisitor : ValueVisitor
    {
        public string Observation { get; private set; } = "none";
        public void VisitNull() => Observation = "null";
        public void VisitString(string value) => Observation = "string";
        public void VisitBoolean(bool value) => Observation = "boolean";
        public void VisitInt(long value) => Observation = "int";
        public void VisitFloat(double value) => Observation = "float";
        public void VisitDuration(Duration value) => Observation = "duration";
        public void VisitDataSize(DataSize value) => Observation = "data-size";
        public void VisitBytes(byte[] value) => Observation = "bytes";
        public void VisitPair(Pair<object, object> value) => Observation = "pair";
        public void VisitList(IReadOnlyList<object> value) => Observation = "list";
        public void VisitSet(IReadOnlySet<object> value) => Observation = "set";
        public void VisitMap(IReadOnlyDictionary<object, object> value) => Observation = "map";
        public void VisitObject(PObject value) => Observation = "object";
        public void VisitModule(PModule value) => Observation = "module";
        public void VisitClass(PClass value) => Observation = "class";
        public void VisitTypeAlias(TypeAlias value) => Observation = "alias";
        public void VisitRegex(Regex value) => Observation = "regex";
        public void VisitReference(Reference value) => Observation = "reference";
    }

    sealed class RecordingConverter : ValueConverter<string>
    {
        public string ConvertNull() => "null";
        public string ConvertString(string value) => "string";
        public string ConvertBoolean(bool value) => "boolean";
        public string ConvertInt(long value) => "int";
        public string ConvertFloat(double value) => "float";
        public string ConvertDuration(Duration value) => "duration";
        public string ConvertDataSize(DataSize value) => "data-size";
        public string ConvertBytes(byte[] value) => "bytes";
        public string ConvertPair(Pair<object, object> value) => "pair";
        public string ConvertList(IReadOnlyList<object> value) => "list";
        public string ConvertSet(IReadOnlySet<object> value) => "set";
        public string ConvertMap(IReadOnlyDictionary<object, object> value) => "map";
        public string ConvertObject(PObject value) => "object";
        public string ConvertModule(PModule value) => "module";
        public string ConvertClass(PClass value) => "class";
        public string ConvertTypeAlias(TypeAlias value) => "alias";
        public string ConvertRegex(Regex value) => "regex";
        public string ConvertReference(Reference value) => "reference";
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
            if (Environment.GetEnvironmentVariable("DRIPSHARP_DIFFERENTIAL_DEBUG") is not null)
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

    static string NormalizeFileOutputs(IReadOnlyDictionary<string, FileOutput> outputs) =>
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
        if (value is byte[] bytes) return "bytes:" + HexBytes(bytes);
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

    static string NormalizeProperties(IReadOnlyDictionary<string, object> properties) =>
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

    static string HexBytes(byte[] bytes) =>
        string.Concat(bytes.Select(value => value.ToString("x2", CultureInfo.InvariantCulture)));

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
