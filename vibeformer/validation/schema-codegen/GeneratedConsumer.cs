using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using Contract.Main;
using Pkl.Core;

/** Compiles with emitted C# and executes binding through package-only references. */
static class GeneratedConsumer
{
    static readonly UTF8Encoding Utf8 = new(false);

    sealed class ConstructorModel
    {
        public ConstructorModel([PklName("name")] string name) => Name = name;
        public string Name { get; }
        [PklName("tags"), PklRequired] public IReadOnlyList<string> Tags { get; set; } = Array.Empty<string>();
    }

    sealed class Projection
    {
        public Projection(string value) => Value = value;
        public string Value { get; }
    }

    sealed class ProjectionLoader : IPklGeneratedLoader<Projection>
    {
        public bool Invoked { get; private set; }
        public Projection Load(object? value, ConfigBinder binder)
        {
            Invoked = true;
            return new Projection("custom:" + binder.Bind<string>(value));
        }
    }

    sealed class KnownOnly
    {
        [PklName("known"), PklRequired] public required string Known { get; init; }
    }

    sealed class CountModel
    {
        [PklName("count"), PklRequired] public required int Count { get; init; }
    }

    sealed class NonNullModel
    {
        [PklName("name"), PklRequired] public required string Name { get; init; }
    }

    sealed class CycleModel
    {
        [PklName("next"), PklRequired] public required CycleModel Next { get; init; }
    }

    sealed class MetadataModel
    {
        [PklName("display-name"), PklRequired] public required string DisplayName { get; init; }
        [PklIgnore] public string Ignored { get; set; } = "default";
    }

    sealed class NestedGenericModel
    {
        [PklName("values"), PklRequired] public required IReadOnlyList<string> Values { get; init; }
        [PklName("mapping"), PklRequired]
        public required IReadOnlyDictionary<string, string> Mapping { get; init; }
        [PklName("pair"), PklRequired] public required Pair<string, string> Pair { get; init; }
    }

    sealed class NullableNestedGenericModel
    {
        [PklName("values"), PklRequired] public required IReadOnlyList<string?> Values { get; init; }
        [PklName("mapping"), PklRequired]
        public required IReadOnlyDictionary<string, string?> Mapping { get; init; }
        [PklName("pair"), PklRequired] public required Pair<string?, string?> Pair { get; init; }
    }

    public static void Main(string[] args)
    {
        if (args.Length != 3)
            throw new ArgumentException("fixture directory, observations, and diagnostics are required");
        string sourceFile = Path.Combine(Path.GetFullPath(args[0]), "ContractMain.pkl");
        using var evaluator = Evaluator.Preconfigured();
        var source = ModuleSource.PathFromPath(sourceFile);
        var generated = global::Contract.Main.Main.Load(evaluator, source);

        Check(generated.BaseName == "base", "inherited module property");
        Check(generated.Service.Id == "svc-1", "inherited class property");
        Check(generated.Service.Endpoint.Host == "localhost" && generated.Service.Endpoint.Port == 8080,
            "imported generated type");
        Check(generated.Service.Direction == Direction.SouthEast, "string-literal enum binding");
        Check(generated.Service.Email.Value == "ops@example.test", "constrained alias binding");
        Check(generated.Bag.Value.SequenceEqual(new[] { "one", "two" }), "collection alias binding");
        Check(generated.Service.Maybe is null, "nullable binding");
        Check(generated.Service.Tags.SequenceEqual(new[] { "api", "stable" }), "nested generic binding");
        Check(generated.Service.Pair.GetFirst() == "attempts" && generated.Service.Pair.GetSecond() == 3,
            "generic pair binding");
        Check(generated.Service.Bytes.SequenceEqual(new byte[] { 0, 127, 128, 255 }), "byte binding");
        Check(generated.Class.Event == "created" && generated.Class.FirstName == "Ada", "quoted identifiers");

        using (var writer = new StreamWriter(args[1], append: true, Utf8))
        {
            Write(writer, "generated/contract", "GENERATED_CONTRACT", GeneratedContract());
            Write(writer, "values/ContractMain.pkl", "VALUES", Values(generated));
            WriteSelectedBindingFailures(writer);
        }

        RunFocusedBindingFailures(evaluator, args[2]);
        Console.WriteLine("Independently compiled generated C# binding consumer passed.");
    }

    static string GeneratedContract()
    {
        var generatedTypes = typeof(global::Contract.Main.Main).Assembly.GetTypes()
            .Where(type => type.IsPublic && type.Namespace is not null &&
                type.Namespace.StartsWith("Contract.", StringComparison.Ordinal) &&
                type.GetCustomAttribute<PklNameAttribute>() is not null)
            .OrderBy(type => CanonicalType(type, null), StringComparer.Ordinal);
        return "types=[" + string.Join(";", generatedTypes.Select(GeneratedType)) + "]";
    }

    static string GeneratedType(Type type)
    {
        string clrName = CanonicalType(type, null);
        string pklName = type.GetCustomAttribute<PklNameAttribute>()!.Name;
        bool alias = type.GetCustomAttribute<PklTypeAliasAttribute>() is not null;
        string kind = type.IsEnum ? "enum" : alias ? "alias" : "class";
        string parameters = "[" + string.Join(",", type.GetGenericArguments()
            .Where(item => item.IsGenericParameter).Select(item => item.Name)) + "]";
        string properties;
        string enumValues;
        if (type.IsEnum)
        {
            properties = "[]";
            enumValues = "[" + string.Join(",", type.GetFields(BindingFlags.Public | BindingFlags.Static)
                .OrderBy(field => field.MetadataToken)
                .Select(field => field.Name + "=" + field.GetCustomAttribute<PklNameAttribute>()!.Name)) + "]";
        }
        else if (alias)
        {
            var value = type.GetProperty("Value", BindingFlags.Public | BindingFlags.Instance)
                ?? throw new InvalidOperationException(clrName + " has no alias Value property");
            properties = "[Value:" + CanonicalType(value.PropertyType,
                new NullabilityInfoContext().Create(value)) + "]";
            enumValues = "[]";
        }
        else
        {
            var nullability = new NullabilityInfoContext();
            properties = "[" + string.Join(",", type.GetProperties(
                    BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
                .Select(property => new
                {
                    Property = property,
                    Name = property.GetCustomAttribute<PklNameAttribute>()
                })
                .Where(item => item.Name is not null)
                .OrderBy(item => item.Name!.Name, StringComparer.Ordinal)
                .Select(item => item.Name!.Name + "=" + item.Property.Name + ":" +
                    CanonicalType(item.Property.PropertyType, nullability.Create(item.Property)) +
                    ":required=" + Lower(item.Property.GetCustomAttribute<PklRequiredAttribute>() is not null))) + "]";
            enumValues = "[]";
        }

        Type? baseType = type.IsValueType || type.BaseType == typeof(object) ? null : type.BaseType;
        return "type(clr=" + clrName + ";pkl=" + pklName + ";kind=" + kind +
            ";value=" + Lower(type.IsValueType && !type.IsEnum) +
            ";sealed=" + Lower(type.IsSealed) +
            ";abstract=" + Lower(type.IsAbstract && !type.IsSealed) +
            ";base=" + (baseType is null ? "System.Object" : CanonicalType(baseType, null)) +
            ";parameters=" + parameters +
            ";properties=" + properties +
            ";enum=" + enumValues +
            ";loader=" + Lower(HasGeneratedLoader(type)) +
            ";fromPkl=" + Lower(HasDeclaredStaticMethod(type, "FromPkl")) +
            ";load=" + Lower(HasDeclaredStaticMethod(type, "Load")) + ")";
    }

    static bool HasGeneratedLoader(Type type)
    {
        var property = type.GetProperty("PklLoader", BindingFlags.Public | BindingFlags.Static |
            BindingFlags.DeclaredOnly);
        return property is not null && property.PropertyType.IsGenericType &&
            property.PropertyType.GetGenericTypeDefinition() == typeof(IPklGeneratedLoader<>) &&
            property.PropertyType.GetGenericArguments()[0] == type;
    }

    static bool HasDeclaredStaticMethod(Type type, string name) =>
        type.GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly)
            .Any(method => method.Name == name);

    static string CanonicalType(Type type, NullabilityInfo? nullability)
    {
        if (type.IsArray)
            return CanonicalType(type.GetElementType()!, nullability?.ElementType) + "[]";
        if (type.IsGenericParameter)
            return type.Name + NullableSuffix(type, nullability);
        string name = type.IsGenericType
            ? type.GetGenericTypeDefinition().FullName!.Split('`')[0]
            : type.FullName!;
        if (type.IsGenericType)
        {
            var arguments = type.GetGenericArguments();
            var nullabilityArguments = nullability?.GenericTypeArguments ?? Array.Empty<NullabilityInfo>();
            name += "<" + string.Join(",", arguments.Select((argument, index) =>
                CanonicalType(argument, index < nullabilityArguments.Length
                    ? nullabilityArguments[index]
                    : null))) + ">";
        }
        return name + NullableSuffix(type, nullability);
    }

    static string NullableSuffix(Type type, NullabilityInfo? nullability) =>
        !type.IsValueType && nullability?.ReadState == NullabilityState.Nullable ? "?" : "";

    static void WriteSelectedBindingFailures(StreamWriter writer)
    {
        var binder = new ConfigBinder();
        ObserveBindingFailure(writer, "binding/incompatible-scalar", "String->Int32",
            () => binder.Bind<int>("bad"));
        ObserveBindingFailure(writer, "binding/integer-overflow", "Int->Int32",
            () => binder.Bind<int>(long.MaxValue));
        ObserveBindingFailure(writer, "binding/non-nullable", "Null->Int32",
            () => binder.Bind<int>(PNull.GetInstance()));
        ObserveBindingFailure(writer, "binding/nested-list", "List<Int>->List<String>",
            () => binder.Bind<IReadOnlyList<string>>(new List<object?> { 1L }));
        ObserveBindingFailure(writer, "binding/nested-map", "Map<String,Int>->Map<String,String>",
            () => binder.Bind<IReadOnlyDictionary<string, string>>(
                new Dictionary<string, object?> { ["bad"] = 1L }));
        ObserveBindingFailure(writer, "binding/nested-pair", "Pair<String,Int>->Pair<String,String>",
            () => binder.Bind<Pair<string, string>>(new Pair<object?, object?>("left", 1L)));
    }

    static void ObserveBindingFailure(StreamWriter writer, string id, string contract, Action action)
    {
        try
        {
            action();
            throw new InvalidOperationException(id + " unexpectedly bound successfully");
        }
        catch (PklBindException)
        {
            Write(writer, id, "BINDING_FAILURE", "conversion-failed(" + contract + ")");
        }
    }

    static string Lower(bool value) => value ? "true" : "false";

    static void RunFocusedBindingFailures(Evaluator evaluator, string diagnosticsFile)
    {
        var binder = new ConfigBinder();
        var constructor = binder.Bind<ConstructorModel>(new Dictionary<string, object?>
        {
            ["name"] = "constructed",
            ["tags"] = new List<object?> { "one", "two" }
        });
        Check(constructor.Name == "constructed" && constructor.Tags.SequenceEqual(new[] { "one", "two" }),
            "constructor and settable-member binding");

        var metadata = binder.Bind<MetadataModel>(new Dictionary<string, object?>
        {
            ["display-name"] = "named",
            ["Ignored"] = "must-not-bind"
        });
        Check(metadata.DisplayName == "named" && metadata.Ignored == "default", "name and ignore metadata");
        var caseInsensitive = new ConfigBinder(new ConfigBinderOptions { PropertyNamesCaseInsensitive = true })
            .Bind<MetadataModel>(new Dictionary<string, object?> { ["DISPLAY-NAME"] = "case-insensitive" });
        Check(caseInsensitive.DisplayName == "case-insensitive", "case-insensitive name option");

        var projectionLoader = new ProjectionLoader();
        var custom = new ConfigBinder(new ConfigBinderOptions().AddGeneratedLoader(projectionLoader))
            .Bind<Projection>("value");
        Check(projectionLoader.Invoked && custom.Value == "custom:value", "registered generated loader");

        ExpectBindFailure(
            () => binder.Bind<KnownOnly>(new Dictionary<string, object?> { ["known"] = "yes", ["extra"] = 1L }),
            "$", typeof(KnownOnly), "unknown Pkl properties");
        var ignored = new ConfigBinder(new ConfigBinderOptions { IgnoreUnknownProperties = true })
            .Bind<KnownOnly>(new Dictionary<string, object?> { ["known"] = "yes", ["extra"] = 1L });
        Check(ignored.Known == "yes", "ignore-unknown option");

        ExpectBindFailure(
            () => binder.Bind<CountModel>(new Dictionary<string, object?> { ["count"] = "bad" }),
            "$.count", typeof(int), "not a Pkl Int or Float");
        ExpectBindFailure(
            () => binder.Bind<CountModel>(new Dictionary<string, object?>()),
            "$", typeof(CountModel), "missing required Pkl property `count`");
        ExpectBindFailure(
            () => binder.Bind<CountModel>(new Dictionary<string, object?> { ["count"] = long.MaxValue }),
            "$.count", typeof(int), "numeric overflow");
        ExpectBindFailure(
            () => binder.Bind<NonNullModel>(new Dictionary<string, object?> { ["name"] = PNull.GetInstance() }),
            "$.name", typeof(string), "null is not allowed");

        var validMapping = new Dictionary<string, object?> { ["ok"] = "value" };
        var validPair = new Pair<object?, object?>("left", "right");
        ExpectBindFailure(
            () => binder.Bind<NestedGenericModel>(new Dictionary<string, object?>
            {
                ["values"] = new List<object?> { "ok", PNull.GetInstance() },
                ["mapping"] = validMapping,
                ["pair"] = validPair
            }),
            "$.values[1]", typeof(string), "null is not allowed");
        ExpectBindFailure(
            () => binder.Bind<NestedGenericModel>(new Dictionary<string, object?>
            {
                ["values"] = new List<object?> { "ok" },
                ["mapping"] = new Dictionary<string, object?> { ["bad"] = PNull.GetInstance() },
                ["pair"] = validPair
            }),
            "$.mapping[\"bad\"]", typeof(string), "null is not allowed");
        ExpectBindFailure(
            () => binder.Bind<NestedGenericModel>(new Dictionary<string, object?>
            {
                ["values"] = new List<object?> { "ok" },
                ["mapping"] = validMapping,
                ["pair"] = new Pair<object?, object?>("left", PNull.GetInstance())
            }),
            "$.pair.second", typeof(string), "null is not allowed");
        var nullableNested = binder.Bind<NullableNestedGenericModel>(new Dictionary<string, object?>
        {
            ["values"] = new List<object?> { "ok", PNull.GetInstance() },
            ["mapping"] = new Dictionary<string, object?> { ["ok"] = PNull.GetInstance() },
            ["pair"] = new Pair<object?, object?>(PNull.GetInstance(), PNull.GetInstance())
        });
        Check(nullableNested.Values[1] is null && nullableNested.Mapping["ok"] is null &&
              nullableNested.Pair.GetFirst() is null && nullableNested.Pair.GetSecond() is null,
            "nullable nested generic arguments");

        const long largestExactDoubleInteger = 9_007_199_254_740_992L;
        Check(binder.Bind<double>(largestExactDoubleInteger) == largestExactDoubleInteger,
            "exact integer-to-double conversion");
        Check(binder.Bind<int>(42.0d) == 42, "exact integral Float conversion");
        ExpectBindFailure(() => binder.Bind<double>(largestExactDoubleInteger + 1),
            "$", typeof(double), "lose integer precision");
        ExpectBindFailure(() => binder.Bind<int>(42.5d),
            "$", typeof(int), "non-integral Pkl Float");
        ExpectBindFailure(() => binder.Bind<long>(9_223_372_036_854_775_808d),
            "$", typeof(long), "numeric overflow");

        var cycle = new Dictionary<string, object?>();
        cycle["next"] = cycle;
        ExpectBindFailure(() => binder.Bind<CycleModel>(cycle), "$.next", typeof(CycleModel), "cyclic object graphs");

        var disposed = new ConfigEvaluator(evaluator);
        disposed.Dispose();
        try
        {
            disposed.Evaluate<KnownOnly>(ModuleSource.Uri(new Uri("file:///disposed-config-evaluator.pkl")));
            throw new InvalidOperationException("disposed ConfigEvaluator accepted evaluation");
        }
        catch (ObjectDisposedException) { }

        File.WriteAllText(diagnosticsFile,
            "constructor-and-members=passed\n" +
            "metadata-options=passed\n" +
            "custom-loader=passed\n" +
            "unknown=$\n" +
            "incompatible=$.count\n" +
            "missing=$\n" +
            "overflow=$.count\n" +
            "nullability=$.name\n" +
            "nested-list-nullability=$.values[1]\n" +
            "nested-map-nullability=$.mapping[\"bad\"]\n" +
            "nested-pair-nullability=$.pair.second\n" +
            "nullable-nested-generics=passed\n" +
            "numeric-exactness=passed\n" +
            "cycle=$.next\n" +
            "disposed=passed\n", Utf8);
    }

    static void ExpectBindFailure(Action action, string path, Type targetType, string reason)
    {
        try
        {
            action();
            throw new InvalidOperationException("expected PklBindException");
        }
        catch (PklBindException error)
        {
            Check(error.Message.Contains(path, StringComparison.Ordinal), "failure path: " + error.Message);
            Check(error.Message.Contains(targetType.FullName!, StringComparison.Ordinal), "failure target: " + error.Message);
            Check(error.Message.Contains(reason, StringComparison.Ordinal), "failure reason: " + error.Message);
        }
    }

    static string Values(global::Contract.Main.Main module)
    {
        var service = module.Service;
        return "values(base=" + Q(module.BaseName) +
            ";id=" + Q(service.Id) +
            ";name=" + Q(service.Name) +
            ";endpoint=" + Q(service.Endpoint.Host) + ":" + service.Endpoint.Port.ToString(CultureInfo.InvariantCulture) +
            ";direction=" + Q(EnumPklName(service.Direction)) +
            ";email=" + Q(service.Email.Value) +
            ";maybe=" + (service.Maybe is null ? "null" : "unexpected") +
            ";constrained=" + service.Constrained.ToString(CultureInfo.InvariantCulture) +
            ";choice=" + Q((string) service.Choice) +
            ";tags=" + Sequence(service.Tags, false) +
            ";listing=" + Sequence(service.Listing, false) +
            ";names=" + Sequence(service.Names, true) +
            ";weights=" + Mapping(service.Weights) +
            ";mapping=" + Mapping(service.Mapping) +
            ";pair=" + Q(service.Pair.GetFirst()) + ":" + service.Pair.GetSecond().ToString(CultureInfo.InvariantCulture) +
            ";bytes=" + string.Concat(service.Bytes.Select(item => item.ToString("x2", CultureInfo.InvariantCulture))) +
            ";pattern=" + Q(service.Pattern.ToString()) +
            ";duration=" + Bits(service.Duration.GetValue()) + "@" + service.Duration.GetUnit().GetSymbol() +
            ";size=" + Bits(service.Size.GetValue()) + "@" + service.Size.GetUnit().GetSymbol() +
            ";bag=" + Sequence(module.Bag.Value, false) +
            ";quoted=" + Q(module.Class.Event) + ":" + Q(module.Class.FirstName) + ")";
    }

    static string EnumPklName<T>(T value) where T : struct, Enum
    {
        var field = typeof(T).GetField(value.ToString())!;
        return field.GetCustomAttributes(typeof(PklNameAttribute), false).Cast<PklNameAttribute>().Single().Name;
    }

    static string Sequence<T>(IEnumerable<T> values, bool sorted)
    {
        var items = values.Select(Scalar).ToList();
        if (sorted) items.Sort(StringComparer.Ordinal);
        return "[" + string.Join(", ", items) + "]";
    }

    static string Mapping<TKey, TValue>(IReadOnlyDictionary<TKey, TValue> values) where TKey : notnull =>
        "[" + string.Join(", ", values.Select(entry => Scalar(entry.Key) + "=" + Scalar(entry.Value))
            .OrderBy(item => item, StringComparer.Ordinal)) + "]";
    static string Scalar<T>(T value) => value is string text ? Q(text) : Convert.ToString(value, CultureInfo.InvariantCulture)!;
    static string Bits(double value) => unchecked((ulong)BitConverter.DoubleToInt64Bits(value)).ToString("x16", CultureInfo.InvariantCulture);
    static string Q(string value) => Convert.ToBase64String(Utf8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string value) =>
        writer.WriteLine(id + "\t" + kind + "\t" + Q(value));
    static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}
