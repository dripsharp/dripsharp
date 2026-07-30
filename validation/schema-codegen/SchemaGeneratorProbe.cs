using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.Brine;

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
        foreach (string file in new[]
        {
            "ContractBase.pkl", "ContractImported.pkl", "ContractMain.pkl",
            "PolymorphicLib.pkl", "PolymorphicModuleTest.pkl", "OverriddenProperty.pkl",
            "SchemaMethods.pkl"
        })
        {
            var schema = evaluator.EvaluateSchema(ModuleSource.PathFromPath(Path.Combine(fixtures, file)));
            if (file == "ContractMain.pkl") CheckRepresentativeContract(schema);
            if (file == "PolymorphicModuleTest.pkl") CheckPolymorphicContract(schema);
            if (file == "OverriddenProperty.pkl") CheckOverriddenContract(schema);
            if (file == "SchemaMethods.pkl") CheckMethodsContract(schema);
            string first = generator.Generate(schema);
            string second = generator.Generate(schema);
            Check(first == second, "repeated generation differs for " + file);
            File.WriteAllText(Path.Combine(generated, Path.GetFileNameWithoutExtension(file) + ".g.cs"), first, Utf8);
            Write(writer, "schema/" + file, "SCHEMA", Schema(schema));
            if (file == "ContractMain.pkl") CheckRepresentativeOutput(first);
            if (file == "PolymorphicModuleTest.pkl") CheckPolymorphicOutput(first);
            if (file == "OverriddenProperty.pkl") CheckOverriddenOutput(first);
        }

        var amendBase = evaluator.EvaluateSchema(
            ModuleSource.PathFromPath(Path.Combine(fixtures, "SchemaAmendBase.pkl")));
        var amend = evaluator.EvaluateSchema(
            ModuleSource.PathFromPath(Path.Combine(fixtures, "SchemaAmend.pkl")));
        Write(writer, "schema/SchemaAmendBase.pkl", "SCHEMA", Schema(amendBase));
        Write(writer, "schema/SchemaAmend.pkl", "SCHEMA", Schema(amend));
        Write(writer, "schema/pkl:base/generics", "SCHEMA_GENERICS",
            GenericContract(evaluator.EvaluateSchema(ModuleSource.Uri(new Uri("pkl:base")))));
        Write(writer, "schema/relationships", "SCHEMA_RELATIONSHIPS",
            RelationshipsContract(evaluator.EvaluateSchema(
                ModuleSource.PathFromPath(Path.Combine(fixtures, "ContractMain.pkl"))), amendBase, amend));
        WriteSchemaFailures(writer, evaluator, fixtures);

        var diagnostics = new List<string>();
        CheckDiagnostics(generator, evaluator, fixtures, "Collision.pkl", diagnostics, writer);
        CheckDiagnostics(generator, evaluator, fixtures, "GeneratedMemberCollision.pkl", diagnostics, writer);
        CheckDiagnostics(generator, evaluator, fixtures, "InheritedMemberCollision.pkl", diagnostics, writer);
        File.WriteAllLines(args[3], diagnostics, Utf8);

        var generatedMemberSchema = evaluator.EvaluateSchema(
            ModuleSource.PathFromPath(Path.Combine(fixtures, "GeneratedMemberCollision.pkl")));
        var exactGenerator = new CSharpGenerator(new CSharpGeneratorOptions
        {
            Namespace = "Pinned.Generated",
            EmitDocComments = false,
            EmitGeneratedLoaders = false,
            EmitValueSemantics = false
        });
        string exact = exactGenerator.Generate(generatedMemberSchema);
        string expectedExact = File.ReadAllText(
            Path.Combine(fixtures, "GeneratedMemberCollision.expected.cs.txt"), Utf8);
        Check(exact == expectedExact, "exact configured generator output differs from the pinned contract");

        var mainSchema = evaluator.EvaluateSchema(
            ModuleSource.PathFromPath(Path.Combine(fixtures, "ContractMain.pkl")));
        var mappedOptions = new CSharpGeneratorOptions { Namespace = "Pinned.Main" };
        mappedOptions.MapNamespace("contract.base", "Pinned.Base");
        mappedOptions.MapNamespace("contract.imported", "Pinned.Imported");
        string mapped = new CSharpGenerator(mappedOptions).Generate(mainSchema);
        foreach (string expected in new[]
        {
            "namespace Pinned.Main;",
            "public sealed partial class Main : global::Pinned.Base.Base",
            "public sealed partial class Service : global::Pinned.Base.Entity",
            "public global::Pinned.Imported.Endpoint Endpoint { get; }"
        }) Check(mapped.Contains(expected, StringComparison.Ordinal), "missing configured namespace mapping: " + expected);
        Console.WriteLine("Package-only schema traversal and deterministic C# generation passed.");
    }

    static void WriteSchemaFailures(StreamWriter writer, Evaluator evaluator, string fixtures)
    {
        Write(writer, "schema/failure/UserDefinedGenericClass.pkl", "SCHEMA_FAILURE",
            StableSchemaFailure(evaluator, fixtures, "UserDefinedGenericClass.pkl",
                "Only standard library members can have type parameters.", Array.Empty<string>()));
        Write(writer, "schema/failure/UserDefinedGenericMethod.pkl", "SCHEMA_FAILURE",
            StableSchemaFailure(evaluator, fixtures, "UserDefinedGenericMethod.pkl",
                "Only standard library members can have type parameters.", new[] { "DoIt" }));
        Write(writer, "schema/recursive/CyclicTypeAlias2.pkl", "SCHEMA_RECURSIVE_ALIAS",
            RecursiveAliasContract(evaluator, fixtures));
    }

    static string RecursiveAliasContract(Evaluator evaluator, string fixtures)
    {
        string first = SchemaFailure(evaluator, fixtures, "CyclicTypeAlias2.pkl",
            "Type alias definitions must not be cyclic.", new[] { "Baz", "Bar", "Foo" });
        var source = ModuleSource.PathFromPath(Path.Combine(fixtures, "CyclicTypeAlias2.pkl"));
        var second = evaluator.EvaluateSchema(source);
        var third = evaluator.EvaluateSchema(source);
        Check(ReferenceEquals(second, third) &&
              ReferenceEquals(second.GetModuleClass(), third.GetModuleClass()),
            "recursive alias schema cache identity contract");
        Check(second.GetTypeAliases().Keys.SequenceEqual(new[] { "Foo", "Bar", "Baz" }),
            "recursive alias declaration order contract");
        foreach (string name in new[] { "Foo", "Bar", "Baz" })
        {
            Check(ReferenceEquals(second.GetTypeAliases()[name], third.GetTypeAliases()[name]),
                "recursive alias identity contract for " + name);
            Check(ReferenceEquals(second.GetTypeAliases()[name].GetAliasedType(), PType.UNKNOWN),
                "recursive alias fallback type contract for " + name);
        }
        return "recursive(first=" + first +
            ";cachedSchema=true;aliases=[Foo:unknown, Bar:unknown, Baz:unknown])";
    }

    static string StableSchemaFailure(Evaluator evaluator, string fixtures, string file,
        string expectedMessage, IReadOnlyList<string> chain)
    {
        string first = SchemaFailure(evaluator, fixtures, file, expectedMessage, chain);
        string second = SchemaFailure(evaluator, fixtures, file, expectedMessage, chain);
        Check(first == second, file + " schema failure changed across repeated evaluation");
        return first;
    }

    static string SchemaFailure(Evaluator evaluator, string fixtures, string file,
        string expectedMessage, IReadOnlyList<string> chain)
    {
        try
        {
            evaluator.EvaluateSchema(ModuleSource.PathFromPath(Path.Combine(fixtures, file)));
            throw new InvalidOperationException(file + " unexpectedly exported a schema");
        }
        catch (PklException expected)
        {
            string message = expected.Message;
            Check(message.Contains(expectedMessage, StringComparison.Ordinal),
                file + " produced an unexpected schema failure");
            int previous = -1;
            foreach (string member in chain)
            {
                int index = message.IndexOf("#" + member, previous + 1, StringComparison.Ordinal);
                Check(index > previous, file + " omitted or reordered cycle member " + member +
                    ":\n" + message);
                previous = index;
            }
            return "rejected(message=" + Q(expectedMessage) + ";chain=" + Names(chain) + ")";
        }
    }

    static void CheckDiagnostics(CSharpGenerator generator, Evaluator evaluator, string fixtures,
        string file, ICollection<string> output, StreamWriter observations)
    {
        var schema = evaluator.EvaluateSchema(ModuleSource.PathFromPath(Path.Combine(fixtures, file)));
        var first = GenerateDiagnostics(generator, schema, file);
        var second = GenerateDiagnostics(generator, schema, file);
        Check(first.SequenceEqual(second), file + " diagnostics changed across repeated generation:\n" +
            string.Join("\n", first) + "\n---\n" + string.Join("\n", second));
        Check(first.Count > 0, file + " produced no collision diagnostics");
        for (int index = 0; index < first.Count; index++)
        {
            string diagnostic = first[index];
            output.Add(file + ": " + diagnostic);
            Write(observations, "codegen/" + file + "/" + index, "CODEGEN_FAILURE", diagnostic);
        }
    }

    static IReadOnlyList<string> GenerateDiagnostics(CSharpGenerator generator, ModuleSchema schema, string file)
    {
        try
        {
            generator.Generate(schema);
            throw new InvalidOperationException(file + " generated without a collision diagnostic");
        }
        catch (CSharpGenerationException error)
        {
            return error.Diagnostics.ToArray();
        }
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
            "public readonly partial record struct Transform<Input, Output>(global::System.Delegate Value)",
            "global::System.Collections.Generic.IReadOnlyDictionary<string, long>",
            "global::DripSharp.Brine.Pair<string, long>",
            "[global::System.Obsolete(\"Use contract.next.\")]",
            "[global::System.Obsolete(\"Use ApplicationService.\")]",
            "[global::System.Obsolete(\"Use displayName.\")]",
            "[global::DripSharp.Brine.PklName(\"first-name\")]",
            "[global::DripSharp.Brine.PklQualifiedName(\"contract.main#Service\")]",
            "IPklGeneratedLoader<Main>",
            "public override bool Equals(object? obj)",
            "public override int GetHashCode()",
            "public override string ToString()"
        }) Check(source.Contains(expected, StringComparison.Ordinal), "missing generated contract: " + expected);
        Check(!source.Contains("default!", StringComparison.Ordinal),
            "generated contract contains an unsafe default placeholder");
    }

    static void CheckPolymorphicOutput(string source)
    {
        foreach (string expected in new[]
        {
            "namespace Com.Example.PolymorphicModuleTest;",
            "public abstract partial class Dessert",
            "[global::DripSharp.Brine.PklQualifiedName(\"com.example.PolymorphicModuleTest#Strudel\")]",
            "public sealed partial class Strudel : Dessert",
            "global::System.Collections.Generic.IReadOnlyList<Dessert> Desserts",
            "global::System.Collections.Generic.IReadOnlyList<global::Com.Example.Lib.Airplane> Planes"
        }) Check(source.Contains(expected, StringComparison.Ordinal), "missing polymorphic generated contract: " + expected);
    }

    static void CheckOverriddenOutput(string source)
    {
        foreach (string expected in new[]
        {
            "namespace Com.Example.OverriddenProperty;",
            "public abstract partial class BaseClass",
            "public sealed partial class TheClass : BaseClass",
            "public new global::System.Collections.Generic.IReadOnlyList<Bar> Bar { get; }",
            "public sealed partial class Bar : BaseBar"
        }) Check(source.Contains(expected, StringComparison.Ordinal), "missing overridden generated contract: " + expected);
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
            .Append(";annotations=").Append(Annotations(schema.GetAnnotations()))
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
            .Append(";params=").Append(TypeParameters(value.GetTypeParameters()))
            .Append(";super=").Append(TypeValue(value.GetSupertype()))
            .Append(";doc=").Append(Q(value.GetDocComment()))
            .Append(";annotations=").Append(Annotations(value.GetAnnotations()))
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
                .Append(Modifiers(property)).Append(":annotations=")
                .Append(Annotations(property.GetAnnotations())).Append(';');
        }
        result.Append("];methods=[");
        foreach (var entry in value.GetMethods()) result.Append(MethodValue(entry.Value)).Append(';');
        return result.Append("])").ToString();
    }

    static string MethodValue(PClass.Method value)
    {
        var result = new StringBuilder("method(")
            .Append(Q(value.GetSimpleName()))
            .Append(";params=").Append(TypeParameters(value.GetTypeParameters()))
            .Append(";arguments=[");
        foreach (var entry in value.GetParameters())
            result.Append(Q(entry.Key)).Append(':').Append(TypeValue(entry.Value)).Append(';');
        return result.Append("];return=").Append(TypeValue(value.GetReturnType()))
            .Append(";doc=").Append(Q(value.GetInheritedDocComment()))
            .Append(";line=").Append(value.GetSourceLocation().StartLine).Append(':')
            .Append(value.GetSourceLocation().EndLine)
            .Append(";mods=").Append(Modifiers(value))
            .Append(";annotations=").Append(Annotations(value.GetAnnotations()))
            .Append(')').ToString();
    }

    static string Alias(TypeAlias value) =>
        "alias(" + Q(value.GetQualifiedName()) + ";doc=" + Q(value.GetDocComment()) +
        ";annotations=" + Annotations(value.GetAnnotations()) +
        ";line=" + value.GetSourceLocation().StartLine + ":" + value.GetSourceLocation().EndLine +
        ";params=" + TypeParameters(value.GetTypeParameters()) +
        ";type=" + TypeValue(value.GetAliasedType()) + ")";

    static string TypeParameters(IReadOnlyList<TypeParameter> values) => List(values.Select(value =>
        "parameter(" + Q(value.GetName()) + ";variance=" + Variance(value.GetVariance()) +
        ";index=" + value.GetIndex() + ";owner=" +
        Q(value.GetOwner().GetModuleName() + "#" + value.GetOwner().GetSimpleName()) + ")"));

    static string Variance(TypeParameter.Variance value) =>
        ReferenceEquals(value, TypeParameter.Variance.INVARIANT) ? "INVARIANT" :
        ReferenceEquals(value, TypeParameter.Variance.COVARIANT) ? "COVARIANT" :
        ReferenceEquals(value, TypeParameter.Variance.CONTRAVARIANT) ? "CONTRAVARIANT" :
        throw new ArgumentException("Unknown variance");

    static string Annotations(IReadOnlyList<PObject> values) => List(values.Select(Annotation));

    static string Annotation(PObject value) => "annotation(" + Q(value.GetClassInfo().GetQualifiedName()) + ";" +
        List(value.GetProperties().OrderBy(entry => entry.Key, StringComparer.Ordinal)
            .Select(entry => Q(entry.Key) + "=" + AnnotationValue(entry.Value))) + ")";

    static string AnnotationValue(object value) => value switch
    {
        PNull => "null",
        string text => "string(" + Q(text) + ")",
        bool boolean => "boolean(" + Lower(boolean) + ")",
        long integer => "int(" + integer.ToString(System.Globalization.CultureInfo.InvariantCulture) + ")",
        double floating => "float(" +
            unchecked((ulong)BitConverter.DoubleToInt64Bits(floating)).ToString("x16", System.Globalization.CultureInfo.InvariantCulture) + ")",
        PObject pObject => Annotation(pObject),
        IEnumerable<object> collection => "collection(" + List(collection.Select(AnnotationValue)) + ")",
        _ => throw new ArgumentException("Unsupported annotation value: " + value.GetType().FullName)
    };

    static void CheckRepresentativeContract(ModuleSchema schema)
    {
        Check(schema.GetAnnotations().Count == 1, "module annotation contract");
        var direction = schema.GetTypeAliases()["Direction"];
        Check(direction.GetAnnotations().Count == 1, "type-alias annotation contract");
        var transform = schema.GetTypeAliases()["Transform"];
        Check(transform.GetTypeParameters().Count == 2, "generic alias contract");
        Check(ReferenceEquals(transform.GetTypeParameters()[0].GetVariance(), TypeParameter.Variance.CONTRAVARIANT),
            "contravariant type parameter contract");
        Check(ReferenceEquals(transform.GetTypeParameters()[1].GetVariance(), TypeParameter.Variance.COVARIANT),
            "covariant type parameter contract");
        Check(transform.GetTypeParameters().All(item => ReferenceEquals(item.GetOwner(), transform)),
            "type parameter owner contract");
        Check(transform.GetAliasedType() is PType.Function, "function type contract");
        var service = schema.GetClasses()["Service"];
        Check(service.GetAnnotations().Count == 1, "class annotation contract");
        Check(service.GetProperties()["name"].GetAnnotations().Count == 1, "property annotation contract");
    }

    static void CheckMethodsContract(ModuleSchema schema)
    {
        var methods = schema.GetModuleClass().GetMethods();
        Check(methods.Keys.SequenceEqual(new[] { "methodb1", "methodb2", "methodb3" }),
            "module method declaration order");
        Check(ReferenceEquals(methods["methodb1"].GetReturnType(), PType.UNKNOWN),
            "untyped method return contract");
        var methodb2 = methods["methodb2"];
        Check(methodb2.GetParameters()["str"] is PType.Constrained,
            "constrained method parameter contract");
        Check(methodb2.GetReturnType() is PType.Constrained,
            "constrained method return contract");
        Check(methods["methodb3"].GetParameters().Keys.SequenceEqual(new[] { "x", "_#1", "i", "_#3" }),
            "unnamed method parameter contract");
        var record = schema.GetClasses()["Record"];
        var display = record.GetMethods()["display"];
        Check(ReferenceEquals(display.GetOwner(), record), "class method owner identity contract");
    }

    static string GenericContract(ModuleSchema schema)
    {
        var list = schema.GetClasses()["List"];
        var map = schema.GetClasses()["Map"];
        Check(list.GetTypeParameters().All(parameter => ReferenceEquals(parameter.GetOwner(), list)),
            "List type parameter owner identity contract");
        Check(map.GetTypeParameters().All(parameter => ReferenceEquals(parameter.GetOwner(), map)),
            "Map type parameter owner identity contract");
        var method = list.GetMethods()["map"];
        Check(method.GetTypeParameters().Count == 1, "List.map generic method contract");
        var resultParameter = method.GetTypeParameters()[0];
        Check(ReferenceEquals(resultParameter.GetOwner(), method),
            "List.map type parameter owner identity contract");
        var transform = (PType.Function)method.GetParameters()["transform"];
        var element = (PType.TypeVariable)transform.GetParameterTypes()[0];
        var result = (PType.TypeVariable)transform.GetReturnType();
        var returnType = (PType.Class)method.GetReturnType();
        var returnElement = (PType.TypeVariable)returnType.GetTypeArguments()[0];
        Check(ReferenceEquals(element.GetTypeParameter(), list.GetTypeParameters()[0]),
            "List.map parameter retains class type-parameter identity");
        Check(ReferenceEquals(result.GetTypeParameter(), resultParameter) &&
              ReferenceEquals(returnElement.GetTypeParameter(), resultParameter),
            "List.map result retains method type-parameter identity");
        return "generics(list=" + TypeParameters(list.GetTypeParameters()) +
            ";map=" + TypeParameters(map.GetTypeParameters()) +
            ";method=" + MethodValue(method) + ")";
    }

    static string RelationshipsContract(ModuleSchema extension, ModuleSchema amendBase, ModuleSchema amend)
    {
        Check(extension.IsExtend() && !extension.IsAmend(), "extended module relation contract");
        Check(extension.GetSupermodule() is not null &&
              ReferenceEquals(extension.GetModuleClass().GetSuperclass(),
                  extension.GetSupermodule()!.GetModuleClass()),
            "extended module class identity contract");
        Check(amend.IsAmend() && !amend.IsExtend() && amend.GetSupermodule() is not null,
            "amended module relation contract");
        Check(ReferenceEquals(amend.GetModuleClass(), amend.GetSupermodule()!.GetModuleClass()),
            "amended module class identity contract");
        Check(ReferenceEquals(amend.GetSupermodule()!.GetModuleClass(), amendBase.GetModuleClass()),
            "amended module base identity contract");
        Check(ReferenceEquals(amend.GetAllClasses(), amend.GetAllClasses()) &&
              ReferenceEquals(amend.GetAllTypeAliases(), amend.GetAllTypeAliases()),
            "amended aggregate maps are stable");
        Check(ReferenceEquals(amend.GetAllClasses()["Item"], amendBase.GetClasses()["Item"]) &&
              ReferenceEquals(amend.GetAllTypeAliases()["Name"], amendBase.GetTypeAliases()["Name"]),
            "amended inherited declaration identity contract");
        return "relations(extend=" + Q(extension.GetModuleName()) + "->" +
            Q(extension.GetSupermodule()!.GetModuleName()) +
            ";extendClassSuper=true;extendClasses=" + Names(extension.GetAllClasses().Keys) +
            ";extendAliases=" + Names(extension.GetAllTypeAliases().Keys) +
            ";amend=" + Q(amend.GetModuleName()) + "->" + Q(amend.GetSupermodule()!.GetModuleName()) +
            ";sameModuleClass=true;amendClasses=" + Names(amend.GetAllClasses().Keys) +
            ";amendAliases=" + Names(amend.GetAllTypeAliases().Keys) + ")";
    }

    static void CheckPolymorphicContract(ModuleSchema schema)
    {
        var dessert = schema.GetClasses()["Dessert"];
        Check(dessert.IsAbstract(), "abstract polymorphic base contract");
        Check(schema.GetClasses()["Strudel"].GetSuperclass() == dessert,
            "local concrete polymorphic subtype contract");
        var planes = schema.GetModuleClass().GetProperties()["planes"].GetType() as PType.Class;
        var airplane = planes?.GetTypeArguments().SingleOrDefault() as PType.Class;
        Check(airplane?.GetPClass().GetQualifiedName() == "com.example.lib#Airplane",
            "imported polymorphic base contract");
    }

    static void CheckOverriddenContract(ModuleSchema schema)
    {
        var baseClass = schema.GetClasses()["BaseClass"];
        var derived = schema.GetClasses()["TheClass"];
        Check(derived.GetSuperclass() == baseClass, "overridden property superclass contract");
        Check(baseClass.GetProperties().ContainsKey("bar") && derived.GetProperties().ContainsKey("bar"),
            "overridden property is declared in both schema classes");
        Check(TypeValue(baseClass.GetProperties()["bar"].GetType()) !=
              TypeValue(derived.GetProperties()["bar"].GetType()),
            "overridden property narrows its schema type");
    }

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
            PType.TypeVariable variable => TypeVariableValue(variable),
            _ => throw new ArgumentException("Unknown PType: " + value.GetType().FullName)
        };
    }

    static string TypeVariableValue(PType.TypeVariable variable)
    {
        var parameter = variable.GetTypeParameter();
        return "variable(" + Q(variable.GetName()) + ";owner=" +
            Q(parameter.GetOwner().GetModuleName() + "#" + parameter.GetOwner().GetSimpleName()) +
            ";index=" + parameter.GetIndex() + ")";
    }

    static string TypeArguments(IReadOnlyList<PType> values) => values.Count == 0 ? "" :
        "<" + List(values.Select(item => TypeValue(item))) + ">";
    static string List(IEnumerable<string> values) => "[" + string.Join(", ", values) + "]";
    static string Names(IEnumerable<string> values) => "[" + string.Join(", ", values) + "]";
    static string Lower(bool value) => value ? "true" : "false";
    static string Q(string? value) => value is null ? "-" : Convert.ToBase64String(Utf8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string value) =>
        writer.WriteLine(id + "\t" + kind + "\t" + Q(value));
    static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}
