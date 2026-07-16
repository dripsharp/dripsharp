using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Pkl.Core;

/** Package-only schema traversal and deterministic C# generation probe. */
static class SchemaGeneratorProbe
{
    static readonly UTF8Encoding Utf8 = new(false);

    public static void Main(string[] args)
    {
        if (args.Length != 4)
            throw new ArgumentException("fixture directory, generated directory, observations, and diagnostics are required");
        string fixtures = Path.GetFullPath(args[0]);
        string generated = Path.GetFullPath(args[1]);
        Directory.CreateDirectory(generated);
        using var evaluator = Evaluator.Preconfigured();
        using var writer = new StreamWriter(args[2], false, Utf8);
        var generator = new CSharpGenerator();
        foreach (string file in new[] { "ContractBase.pkl", "ContractImported.pkl", "ContractMain.pkl" })
        {
            var schema = evaluator.EvaluateSchema(ModuleSource.PathFromPath(Path.Combine(fixtures, file)));
            string first = generator.Generate(schema);
            string second = generator.Generate(schema);
            Check(first == second, "repeated generation differs for " + file);
            File.WriteAllText(Path.Combine(generated, Path.GetFileNameWithoutExtension(file) + ".g.cs"), first, Utf8);
            Write(writer, "schema/" + file, "SCHEMA", Schema(schema));
            if (file == "ContractMain.pkl") CheckRepresentativeOutput(first);
        }

        var collision = evaluator.EvaluateSchema(
            ModuleSource.PathFromPath(Path.Combine(fixtures, "Collision.pkl")));
        try
        {
            generator.Generate(collision);
            throw new InvalidOperationException("collision fixture generated without a diagnostic");
        }
        catch (CSharpGenerationException error)
        {
            Check(error.Diagnostics.Count == 1, "collision fixture must produce one deterministic diagnostic");
            string diagnostic = error.Diagnostics[0];
            Check(diagnostic.Contains("symbol collision `FooBar`", StringComparison.Ordinal), "collision symbol");
            Check(diagnostic.Contains("contract.collision#foo-bar", StringComparison.Ordinal), "hyphen collision source");
            Check(diagnostic.Contains("contract.collision#foo bar", StringComparison.Ordinal), "space collision source");
            File.WriteAllText(args[3], diagnostic + "\n", Utf8);
        }
        Console.WriteLine("Package-only schema traversal and deterministic C# generation passed.");
    }

    static void CheckRepresentativeOutput(string source)
    {
        foreach (string expected in new[]
        {
            "namespace Contract.Main;",
            "public sealed partial class Main : global::Contract.Base.Base",
            "public sealed partial class Service : global::Contract.Base.Entity",
            "public enum Direction",
            "public readonly partial record struct Email(string Value)",
            "global::System.Collections.Generic.IReadOnlyDictionary<string, long>",
            "global::Pkl.Core.Pair<string, long>",
            "[global::Pkl.Core.PklName(\"first-name\")]",
            "IPklGeneratedLoader<Main>"
        }) Check(source.Contains(expected, StringComparison.Ordinal), "missing generated contract: " + expected);
    }

    static string Schema(ModuleSchema schema)
    {
        var result = new StringBuilder();
        result.Append("module(").Append(Q(schema.GetModuleName()))
            .Append(";file=").Append(Q(Path.GetFileName(schema.GetModuleUri().LocalPath)))
            .Append(";amend=").Append(Lower(schema.IsAmend()))
            .Append(";extend=").Append(Lower(schema.IsExtend()))
            .Append(";super=").Append(Q(schema.GetSupermodule()?.GetModuleName() ?? ""))
            .Append(";doc=").Append(Q(schema.GetDocComment()))
            .Append(";imports=[");
        foreach (var entry in schema.GetImports())
            result.Append(Q(entry.Key)).Append('=').Append(Q(Path.GetFileName(entry.Value.LocalPath))).Append(';');
        result.Append("];moduleClass=").Append(PClassValue(schema.GetModuleClass())).Append(";classes=[");
        foreach (var entry in schema.GetClasses()) result.Append(PClassValue(entry.Value)).Append(';');
        result.Append("];aliases=[");
        foreach (var entry in schema.GetTypeAliases()) result.Append(Alias(entry.Value)).Append(';');
        return result.Append("])").ToString();
    }

    static string PClassValue(PClass value)
    {
        var result = new StringBuilder("class(")
            .Append(Q(value.GetQualifiedName()))
            .Append(";moduleClass=").Append(Lower(value.IsModuleClass()))
            .Append(";abstract=").Append(Lower(value.IsAbstract()))
            .Append(";open=").Append(Lower(value.IsOpen()))
            .Append(";super=").Append(TypeValue(value.GetSupertype()))
            .Append(";doc=").Append(Q(value.GetDocComment()))
            .Append(";line=").Append(value.GetSourceLocation().StartLine).Append(':')
            .Append(value.GetSourceLocation().EndLine).Append(";properties=[");
        foreach (var entry in value.GetProperties())
        {
            var property = entry.Value;
            result.Append(Q(property.GetSimpleName())).Append(':')
                .Append(TypeValue(property.GetType())).Append(":doc=")
                .Append(Q(property.GetInheritedDocComment())).Append(":line=")
                .Append(property.GetSourceLocation().StartLine).Append(':')
                .Append(property.GetSourceLocation().EndLine).Append(":mods=")
                .Append(Modifiers(property)).Append(';');
        }
        return result.Append("])").ToString();
    }

    static string Alias(TypeAlias value) =>
        "alias(" + Q(value.GetQualifiedName()) + ";doc=" + Q(value.GetDocComment()) +
        ";line=" + value.GetSourceLocation().StartLine + ":" + value.GetSourceLocation().EndLine +
        ";params=" + List(value.GetTypeParameters().Select(item => Q(item.GetName()))) +
        ";type=" + TypeValue(value.GetAliasedType()) + ")";

    static string Modifiers(Member value) =>
        List(value.GetModifiers().Select(item => item.ToString()).OrderBy(item => item, StringComparer.Ordinal));

    static string TypeValue(PType? value)
    {
        if (value is null) return "none";
        if (ReferenceEquals(value, PType.UNKNOWN)) return "unknown";
        if (ReferenceEquals(value, PType.NOTHING)) return "nothing";
        if (ReferenceEquals(value, PType.MODULE)) return "module";
        return value switch
        {
            PType.StringLiteral literal => "literal(" + Q(literal.GetLiteral()) + ")",
            PType.Class pClass => "class(" + Q(pClass.GetPClass().GetQualifiedName()) +
                TypeArguments(pClass.GetTypeArguments()) + ")",
            PType.Nullable nullable => "nullable(" + TypeValue(nullable.GetBaseType()) + ")",
            PType.Constrained constrained => "constrained(" + TypeValue(constrained.GetBaseType()) + ";" +
                List(constrained.GetConstraints().Select(Q)) + ")",
            PType.Alias alias => "alias(" + Q(alias.GetTypeAlias().GetQualifiedName()) +
                TypeArguments(alias.GetTypeArguments()) + "=" + TypeValue(alias.GetAliasedType()) + ")",
            PType.Function function => "function(" +
                List(function.GetParameterTypes().Select(item => TypeValue(item))) + "->" +
                TypeValue(function.GetReturnType()) + ")",
            PType.Union union => "union(" + List(union.GetElementTypes().Select(item => TypeValue(item))) + ")",
            PType.TypeVariable variable => "variable(" + Q(variable.GetName()) + ")",
            _ => throw new ArgumentException("Unknown PType: " + value.GetType().FullName)
        };
    }

    static string TypeArguments(IList<PType> values) => values.Count == 0 ? "" :
        "<" + List(values.Select(item => TypeValue(item))) + ">";
    static string List(IEnumerable<string> values) => "[" + string.Join(", ", values) + "]";
    static string Lower(bool value) => value ? "true" : "false";
    static string Q(string? value) => value is null ? "-" : Convert.ToBase64String(Utf8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string value) =>
        writer.WriteLine(id + "\t" + kind + "\t" + Q(value));
    static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}
