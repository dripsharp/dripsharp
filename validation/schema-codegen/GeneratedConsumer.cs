using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using Contract.Main;
using DripSharp.Brine;
using Poly = Com.Example.PolymorphicModuleTest;
using PolyLib = Com.Example.Lib;
using OverrideFixture = Com.Example.OverriddenProperty;

#pragma warning disable CS0618 // This consumer deliberately validates generated deprecation metadata.

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

    sealed class CustomConversionModel
    {
        [PklName("values"), PklRequired]
        public required IReadOnlyDictionary<string, string> Values { get; init; }
    }

    sealed class ReadOnlySetOnly<T> : IReadOnlySet<T> where T : notnull
    {
        readonly List<T> order = new();
        readonly HashSet<T> values = new();

        public ReadOnlySetOnly(IEnumerable<T> items)
        {
            foreach (T item in items)
                if (values.Add(item)) order.Add(item);
        }

        public int Count => values.Count;
        public bool Contains(T item) => values.Contains(item);
        public bool IsProperSubsetOf(IEnumerable<T> other) => values.IsProperSubsetOf(other);
        public bool IsProperSupersetOf(IEnumerable<T> other) => values.IsProperSupersetOf(other);
        public bool IsSubsetOf(IEnumerable<T> other) => values.IsSubsetOf(other);
        public bool IsSupersetOf(IEnumerable<T> other) => values.IsSupersetOf(other);
        public bool Overlaps(IEnumerable<T> other) => values.Overlaps(other);
        public bool SetEquals(IEnumerable<T> other) => values.SetEquals(other);
        public IEnumerator<T> GetEnumerator() => order.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    abstract class ManualDessert { }

    sealed class ManualStrudel : ManualDessert
    {
        [PklName("numberOfRolls"), PklRequired] public required long NumberOfRolls { get; init; }
    }

    public static void Main(string[] args)
    {
        if (args.Length != 3)
            throw new ArgumentException("fixture directory, observations, and diagnostics are required");
        string sourceFile = Path.Combine(Path.GetFullPath(args[0]), "ContractMain.pkl");
        using var evaluator = Evaluator.Preconfigured();
        var source = ModuleSource.PathFromPath(sourceFile);
        var generated = global::Contract.Main.Main.Load(evaluator, source);
        var equivalentGenerated = global::Contract.Main.Main.Load(evaluator, source);
        var polymorphic = Poly.PolymorphicModuleTest.Load(evaluator,
            ModuleSource.PathFromPath(Path.Combine(Path.GetFullPath(args[0]), "PolymorphicModuleTest.pkl")));
        var overridden = OverrideFixture.OverriddenProperty.Load(evaluator,
            ModuleSource.PathFromPath(Path.Combine(Path.GetFullPath(args[0]), "OverriddenProperty.pkl")));

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
        Check(generated.Equals(equivalentGenerated) &&
              generated.GetHashCode() == equivalentGenerated.GetHashCode(),
            "generated models use structural equality and consistent hashing");
        Check(generated.Service.Equals(equivalentGenerated.Service) &&
              generated.Service.Bytes.SequenceEqual(equivalentGenerated.Service.Bytes) &&
              generated.Service.Pattern.ToString() == equivalentGenerated.Service.Pattern.ToString(),
            "nested generated models compare bytes, regex, and collections by value");
        var readOnlySetFirst = WithNames(generated.Service,
            new ReadOnlySetOnly<string>(new[] { "alpha", "beta" }));
        var readOnlySetSecond = WithNames(generated.Service,
            new ReadOnlySetOnly<string>(new[] { "beta", "alpha" }));
        Check(readOnlySetFirst.Equals(readOnlySetSecond) &&
              readOnlySetFirst.GetHashCode() == readOnlySetSecond.GetHashCode(),
            "generated models treat IReadOnlySet values as order-independent sets");
        Check(generated.ToString().Contains("service =", StringComparison.Ordinal) &&
              generated.Service.ToString().Contains("bytes =", StringComparison.Ordinal),
            "generated models expose stable property-oriented string representations");
        Check(polymorphic.Desserts[0] is Poly.Strudel { NumberOfRolls: 3 } &&
              polymorphic.Desserts[1] is Poly.TurkishDelight { IsOfferedToEdmund: true },
            "local polymorphic generated binding");
        Check(polymorphic.Planes[0] is PolyLib.Jet
              { Name: "Concorde", NumSeats: 128, IsSuperSonic: true } &&
              polymorphic.Planes[1] is PolyLib.Propeller
              { Name: "Cessna 172", NumSeats: 4, IsTurboprop: true },
            "imported polymorphic generated binding");
        Check(overridden.TheClass.Bar.Count == 1 && overridden.TheClass.Bar[0].Prop1 == "hello" &&
              overridden.TheClass.Bar[0].Prop2 == "hello again",
            "narrowed overridden property binding");
        OverrideFixture.BaseClass overriddenBase = overridden.TheClass;
        Check(overriddenBase.Bar.Count == 1 && overriddenBase.Bar[0] is OverrideFixture.Bar &&
              overriddenBase.Bar[0].Prop1 == "hello", "base view of overridden property binding");

        using (var writer = new StreamWriter(args[1], append: true, Utf8))
        {
            Write(writer, "generated/contract", "GENERATED_CONTRACT", GeneratedContract());
            Write(writer, "generated/set-contract", "GENERATED_SET_CONTRACT", GeneratedSetContract());
            Write(writer, "values/ContractMain.pkl", "VALUES", Values(generated));
            Write(writer, "values/PolymorphicModuleTest.pkl", "VALUES", PolymorphicValues(polymorphic));
            Write(writer, "values/OverriddenProperty.pkl", "VALUES", OverriddenValues(overridden));
            Write(writer, "binding/conversion-matrix", "BINDING", ConversionMatrix());
            Write(writer, "binding/collection-matrix", "BINDING", CollectionMatrix());
            WriteSelectedBindingFailures(writer);
        }

        RunFocusedBindingFailures(evaluator, args[0], args[2]);
        Console.WriteLine("Independently compiled generated C# binding consumer passed.");
    }

    static Service WithNames(Service value, IReadOnlySet<string> names) => new(
        value.Id, value.Name, value.Endpoint, value.Direction, value.Email, value.Maybe,
        value.Constrained, value.Choice, value.Tags, value.Listing, names, value.Weights,
        value.Mapping, value.Pair, value.Bytes, value.Pattern, value.Duration, value.Size);

    static string GeneratedContract()
    {
        var generatedTypes = typeof(global::Contract.Main.Main).Assembly.GetTypes()
            .Where(type => type.IsPublic && type.GetCustomAttribute<PklNameAttribute>() is not null &&
                type.GetCustomAttribute<PklQualifiedNameAttribute>() is not null)
            .OrderBy(type => CanonicalType(type, null), StringComparer.Ordinal);
        return "types=[" + string.Join(";", generatedTypes.Select(GeneratedType)) + "]";
    }

    static string GeneratedSetContract()
    {
        var nullability = new NullabilityInfoContext();
        var property = typeof(Service).GetProperty(nameof(Service.Names)) ??
            throw new InvalidOperationException("contract.main#Service.Names is missing");
        var parameter = typeof(Service).GetConstructors().Single().GetParameters().Single(item =>
            item.GetCustomAttribute<PklNameAttribute>()?.Name == "names");
        string propertyType = CanonicalType(property.PropertyType, nullability.Create(property));
        string parameterType = CanonicalType(parameter.ParameterType, nullability.Create(parameter));
        return "contract.main#Service.names(property=" + propertyType +
            ";constructor=" + parameterType + ")";
    }

    static string GeneratedType(Type type)
    {
        string clrName = CanonicalType(type, null);
        string pklName = type.GetCustomAttribute<PklNameAttribute>()!.Name;
        string qualifiedName = type.GetCustomAttribute<PklQualifiedNameAttribute>()!.QualifiedName;
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
                    ":required=" + Lower(item.Property.GetCustomAttribute<PklRequiredAttribute>() is not null) +
                    ":override=" + Lower(IsPklOverride(type, item.Name!.Name)))) + "]";
            enumValues = "[]";
        }

        Type? baseType = type.IsValueType || type.BaseType == typeof(object) ? null : type.BaseType;
        return "type(clr=" + clrName + ";pkl=" + pklName + ";qualified=" + qualifiedName + ";kind=" + kind +
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

    static bool IsPklOverride(Type type, string pklName)
    {
        for (Type? current = type.BaseType; current is not null && current != typeof(object); current = current.BaseType)
            if (current.GetProperties(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly)
                .Any(property => property.GetCustomAttribute<PklNameAttribute>()?.Name == pklName))
                return true;
        return false;
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

    static string PolymorphicValues(Poly.PolymorphicModuleTest module) =>
        "polymorphic(desserts=[" + string.Join(",", module.Desserts.Select(item =>
            QualifiedName(item.GetType()) + ":" + (item switch
            {
                Poly.Strudel strudel => strudel.NumberOfRolls.ToString(CultureInfo.InvariantCulture),
                Poly.TurkishDelight delight => Lower(delight.IsOfferedToEdmund),
                _ => "unexpected"
            }))) + "];planes=[" + string.Join(",", module.Planes.Select(item =>
            QualifiedName(item.GetType()) + ":" + item.Name + ":" +
            item.NumSeats.ToString(CultureInfo.InvariantCulture) + ":" + (item switch
            {
                PolyLib.Jet jet => Lower(jet.IsSuperSonic),
                PolyLib.Propeller propeller => Lower(propeller.IsTurboprop),
                _ => "unexpected"
            }))) + "])";

    static string OverriddenValues(OverrideFixture.OverriddenProperty module)
    {
        OverrideFixture.BaseClass baseView = module.TheClass;
        return "override(type=" + QualifiedName(module.TheClass.GetType()) + ";bar=[" +
            string.Join(",", module.TheClass.Bar.Select(item => QualifiedName(item.GetType()) + ":" +
                item.Prop1 + ":" + item.Prop2)) + "];base=[" +
            string.Join(",", baseView.Bar.Select(item => QualifiedName(item.GetType()) + ":" + item.Prop1)) + "])";
    }

    static string QualifiedName(Type type) =>
        type.GetCustomAttribute<PklQualifiedNameAttribute>()?.QualifiedName ??
        throw new InvalidOperationException(type.FullName + " has no Pkl qualified-name metadata");

    static PObject SemanticVersion(long major, long minor, long patch, object preRelease, object build) =>
        new(PClassInfo<object>.Get("pkl.semver", "Version", new Uri("pkl:semver")),
            new Dictionary<string, object>
            {
                ["major"] = major,
                ["minor"] = minor,
                ["patch"] = patch,
                ["preRelease"] = preRelease,
                ["build"] = build
            });

    static string ConversionMatrix()
    {
        var binder = new ConfigBinder(new ConfigBinderOptions { AllowLossyNumericConversions = true });
        object semanticVersion = SemanticVersion(1, 2, 3, "rc.1", "456.789");
        var duration = Duration.OfMinutes(100);
        var regex = new Regex("(?i)\\w*");
        var bytes = new byte[] { 0, 1, 127, 128, 255 };
        var timeSpan = binder.Bind<TimeSpan>(duration);
        long seconds = timeSpan.Ticks / TimeSpan.TicksPerSecond;
        long nanos = timeSpan.Ticks % TimeSpan.TicksPerSecond * 100;
        return "identity[pnull=" + Lower(ReferenceEquals(
                   binder.Bind<PNull>(PNull.GetInstance()), PNull.GetInstance())) +
               ";bool=" + Lower(binder.Bind<bool>(true)) +
               ";string=" + binder.Bind<string>("value") +
               ";int=" + binder.Bind<long>(42L).ToString(CultureInfo.InvariantCulture) +
               ";float=" + DoubleBits(binder.Bind<double>(3.25d)) +
               ";duration=" + binder.Bind<Duration>(duration).GetUnit().GetSymbol() +
               ";bytes=" + Hex(binder.Bind<byte[]>(bytes)) + "]" +
               ";numeric[int8=" + binder.Bind<sbyte>(42L).ToString(CultureInfo.InvariantCulture) +
               ",int16=" + binder.Bind<short>(42L).ToString(CultureInfo.InvariantCulture) +
               ",int32=" + binder.Bind<int>(42L).ToString(CultureInfo.InvariantCulture) +
               ",int64=" + binder.Bind<long>(42L).ToString(CultureInfo.InvariantCulture) +
               ",float32=" + FloatBits(binder.Bind<float>(42L)) +
               ",float64=" + DoubleBits(binder.Bind<double>(42L)) +
               ",bigint=" + binder.Bind<BigInteger>(42L).ToString(CultureInfo.InvariantCulture) +
               ",decimal=" + binder.Bind<decimal>(42L).ToString(CultureInfo.InvariantCulture) +
               ",from-float32=" + FloatBits(binder.Bind<float>(3.25d)) +
               ",from-float-decimal=" + binder.Bind<decimal>(3.25d).ToString(CultureInfo.InvariantCulture) + "]" +
               ";misc[char=" + binder.Bind<char>("x") +
               ",uri=" + binder.Bind<Uri>("relative/path") +
               ",url=" + binder.Bind<Uri>("https://example.test/path") +
               ",file=" + binder.Bind<FileInfo>("relative/path") +
               ",path=" + binder.Bind<FileInfo>("relative/path") +
               ",regex=" + binder.Bind<Regex>("(?i)\\w*") +
               ",regex-string=" + binder.Bind<string>(regex) +
               ",duration=" + seconds.ToString(CultureInfo.InvariantCulture) + ":" +
                   nanos.ToString(CultureInfo.InvariantCulture) +
               ",version=" + binder.Bind<DripSharp.Brine.Version>(semanticVersion) +
               ",version-string=" + binder.Bind<string>(semanticVersion) +
               ",parsed-version=" + binder.Bind<DripSharp.Brine.Version>("2.3.4-beta+5") +
               ",duration-unit=" + binder.Bind<DurationUnit>("min").GetSymbol() +
               ",data-size-unit=" + binder.Bind<DataSizeUnit>("gb").GetSymbol() + "]";
    }

    static string CollectionMatrix()
    {
        var binder = new ConfigBinder(new ConfigBinderOptions { AllowLossyNumericConversions = true });
        var array = binder.Bind<int[]>(new List<object?> { 1L, 2L, 3L });
        var list = binder.Bind<IReadOnlyList<float>>(new List<object?> { 1.0d, 2.0d, 3.25d });
        var set = binder.Bind<IReadOnlySet<string>>(new List<object?> { "beta", "alpha", "beta" });
        Check(set.Count == 2 && set.SetEquals(new[] { "alpha", "beta" }),
            "List<T> to IReadOnlySet<T> binding preserves set semantics");
        var map = binder.Bind<IReadOnlyDictionary<int, double>>(new Dictionary<object, object?>
        {
            [1L] = 2L,
            [2L] = 4.5d
        });
        var pair = binder.Bind<Pair<int, Duration>>(new Pair<object?, object?>(1L, Duration.OfSeconds(3)));
        var nested = binder.Bind<IReadOnlyDictionary<string, IReadOnlyList<int>>>(
            new Dictionary<string, object?> { ["items"] = new List<object?> { 4L, 5L } });
        var nullable = binder.Bind<IReadOnlyList<string?>>(new List<object?> { "value", PNull.GetInstance() });
        var customBinder = new ConfigBinder(new ConfigBinderOptions()
            .AddConversion<long, string>((value, _) => value.ToString(CultureInfo.InvariantCulture)));
        var custom = customBinder.Bind<IReadOnlyDictionary<string, string>>(
            new Dictionary<string, object?> { ["answer"] = 42L });
        return "collections[array=" + string.Join(",", array) +
               ";list=" + string.Join(",", list.Select(FloatBits)) +
               ";set=" + string.Join(",", set.OrderBy(item => item, StringComparer.Ordinal)) +
               ";map=" + string.Join(",", map.Select(entry => entry.Key.ToString(CultureInfo.InvariantCulture) +
                   "=" + DoubleBits(entry.Value)).OrderBy(item => item, StringComparer.Ordinal)) +
               ";pair=" + pair.GetFirst().ToString(CultureInfo.InvariantCulture) + ":" +
                   pair.GetSecond().GetValue().ToString("0.0###############", CultureInfo.InvariantCulture) + "@" +
                   pair.GetSecond().GetUnit().GetSymbol() +
               ";nested=" + string.Join(",", nested["items"]) +
               ";nullable=" + nullable[0] + ":" + (nullable[1] is null ? "null" : "unexpected") +
               ";custom=" + custom["answer"] + "]";
    }

    static string FloatBits(float value) =>
        unchecked((uint)BitConverter.SingleToInt32Bits(value)).ToString("x8", CultureInfo.InvariantCulture);
    static string DoubleBits(double value) =>
        unchecked((ulong)BitConverter.DoubleToInt64Bits(value)).ToString("x16", CultureInfo.InvariantCulture);
    static string Hex(IEnumerable<byte> values) =>
        string.Concat(values.Select(value => value.ToString("x2", CultureInfo.InvariantCulture)));

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
        ObserveBindingFailure(writer, "binding/invalid-character", "String->Char",
            () => binder.Bind<char>("too long"));
        ObserveBindingFailure(writer, "binding/invalid-uri", "String->Uri",
            () => binder.Bind<Uri>("http://[invalid"));
        ObserveBindingFailure(writer, "binding/invalid-regex", "String->Regex",
            () => binder.Bind<Regex>("["));
        ObserveBindingFailure(writer, "binding/duration-overflow", "Duration->TimeSpan",
            () => binder.Bind<TimeSpan>(Duration.OfSeconds(double.PositiveInfinity)));
        ObserveBindingFailure(writer, "binding/version-overflow", "VersionObject->Version",
            () => binder.Bind<DripSharp.Brine.Version>(SemanticVersion(999_999_999_999_999, 0, 0,
                PNull.GetInstance(), PNull.GetInstance())));
        ObserveBindingFailure(writer, "binding/invalid-version", "String->Version",
            () => binder.Bind<DripSharp.Brine.Version>("not-a-version"));
        ObserveBindingFailure(writer, "binding/invalid-unit", "String->DurationUnit",
            () => binder.Bind<DurationUnit>("fortnight"));
        var custom = new ConfigBinder(new ConfigBinderOptions().AddConversion<long, string>((_, _) =>
            throw new InvalidOperationException("deliberate custom conversion failure")));
        ObserveBindingFailure(writer, "binding/custom-conversion", "Int->CustomString",
            () => custom.Bind<string>(42L));
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

    static void RunFocusedBindingFailures(Evaluator evaluator, string fixtureDirectory, string diagnosticsFile)
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

        var customConversion = new ConfigBinder(new ConfigBinderOptions()
                .AddConversion<long, string>((value, _) => "number:" + value.ToString(CultureInfo.InvariantCulture)))
            .Bind<CustomConversionModel>(new Dictionary<string, object?>
            {
                ["values"] = new Dictionary<string, object?> { ["answer"] = 42L }
            });
        Check(customConversion.Values["answer"] == "number:42", "registered recursive custom conversion");

        var rawPolymorphic = evaluator.Evaluate(ModuleSource.PathFromPath(
            Path.Combine(Path.GetFullPath(fixtureDirectory), "PolymorphicModuleTest.pkl")));
        var rawDesserts = ((IEnumerable)rawPolymorphic.GetProperty("desserts")).Cast<object>().ToArray();
        var mappedDessert = new ConfigBinder(new ConfigBinderOptions()
                .AddTypeMapping<ManualDessert, ManualStrudel>(
                    "com.example.PolymorphicModuleTest#Strudel"))
            .Bind<ManualDessert>(rawDesserts[0]);
        Check(mappedDessert is ManualStrudel { NumberOfRolls: 3 }, "explicit polymorphic type mapping");

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
            "$", typeof(CountModel), "required Pkl property `count` is missing");
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

        ExpectBindFailure(() => binder.Bind<char>("too long"),
            "$", typeof(char), "single-character");
        ExpectBindFailure(() => binder.Bind<Uri>("http://[invalid"),
            "$", typeof(Uri), "invalid URI");
        ExpectBindFailure(() => binder.Bind<Regex>("["),
            "$", typeof(Regex), "invalid regular expression");
        ExpectBindFailure(() => binder.Bind<TimeSpan>(Duration.OfSeconds(double.PositiveInfinity)),
            "$", typeof(TimeSpan), "outside the TimeSpan range");
        ExpectBindFailure(() => binder.Bind<DripSharp.Brine.Version>(
                SemanticVersion(999_999_999_999_999, 0, 0, PNull.GetInstance(), PNull.GetInstance())),
            "$.major", typeof(int), "numeric overflow");
        ExpectBindFailure(() => binder.Bind<DripSharp.Brine.Version>("not-a-version"),
            "$", typeof(DripSharp.Brine.Version), "invalid semantic version");
        ExpectBindFailure(() => binder.Bind<DurationUnit>("fortnight"),
            "$", typeof(DurationUnit), "not a Pkl duration unit");
        var failedCustom = new ConfigBinder(new ConfigBinderOptions().AddConversion<long, string>((_, _) =>
            throw new InvalidOperationException("deliberate custom conversion failure")));
        ExpectBindFailure(() => failedCustom.Bind<CustomConversionModel>(new Dictionary<string, object?>
            {
                ["values"] = new Dictionary<string, object?> { ["bad"] = 1L }
            }), "$.values[\"bad\"]", typeof(string), "custom conversion failed");
        ExpectBindFailure(() => binder.Bind<Poly.TurkishDelight>(rawDesserts[0]),
            "$", typeof(Poly.TurkishDelight), "does not match generated target");

        var cycle = new Dictionary<string, object?>();
        cycle["next"] = cycle;
        ExpectBindFailure(() => binder.Bind<CycleModel>(cycle), "$.next", typeof(CycleModel), "cyclic object graphs");

        using (var configEvaluator = new ConfigEvaluator(evaluator, binder))
        {
            Check(ReferenceEquals(configEvaluator.Evaluator, evaluator) &&
                  ReferenceEquals(configEvaluator.Binder, binder),
                "ConfigEvaluator did not retain supplied evaluator and binder");
            KnownOnly evaluated = configEvaluator.Evaluate<KnownOnly>(
                ModuleSource.Text("known = \"evaluated\"\n"));
            long expression = configEvaluator.EvaluateExpression<long>(
                ModuleSource.Text("value = 41\n"), "value + 1");
            Check(evaluated.Known == "evaluated" && expression == 42,
                "ConfigEvaluator end-to-end evaluation and binding");

            Config navigation = configEvaluator.Evaluate(ModuleSource.Text(
                "name = \"Ada\"\nitems = List(1, 2)\nmaybe = null\n"));
            Check(navigation.ChildNames.SequenceEqual(new[] { "name", "items", "maybe" }) &&
                  navigation["name"].QualifiedName == "name" &&
                  navigation["name"].As<string>() == "Ada" &&
                  navigation["items"][1].As<long>() == 2 &&
                  navigation.GetPath("items", 0).As<long>() == 1 &&
                  navigation["maybe"].AsNullable<string>() is null &&
                  navigation.TryGet("name", out Config? name) && name is not null &&
                  name.As<string>() == "Ada",
                "Config navigation and typed access");
            try
            {
                navigation.Get("missing");
                throw new InvalidOperationException("missing Config child was accepted");
            }
            catch (NoSuchChildException error)
            {
                Check(error.QualifiedName == "" && error.ChildName == "missing",
                    "missing Config child diagnostic");
            }
        }
        Check((long)evaluator.EvaluateExpression(ModuleSource.Text("value = 1\n"), "value") == 1,
            "disposing a non-owning ConfigEvaluator disposed its evaluator");

        var replacementEvaluatorBuilder = EvaluatorBuilder.Preconfigured();
        var replacementBinderOptions = new ConfigBinderOptions { IgnoreUnknownProperties = true };
        var builder = ConfigEvaluatorBuilder.Unconfigured()
            .SetEvaluatorBuilder(replacementEvaluatorBuilder)
            .SetBinderOptions(replacementBinderOptions)
            .ConfigureBinder(options => options.PropertyNamesCaseInsensitive = true)
            .AddEnvironmentVariable("first", "one")
            .AddEnvironmentVariables(new Dictionary<string, string> { ["second"] = "two" })
            .SetEnvironmentVariables(new Dictionary<string, string> { ["only"] = "environment" })
            .AddExternalProperty("first", "one")
            .AddExternalProperties(new Dictionary<string, string> { ["second"] = "two" })
            .SetExternalProperties(new Dictionary<string, string> { ["only"] = "external" });
        Check(ReferenceEquals(builder.EvaluatorBuilder, replacementEvaluatorBuilder) &&
              ReferenceEquals(builder.BinderOptions, replacementBinderOptions) &&
              builder.BinderOptions.PropertyNamesCaseInsensitive &&
              builder.EnvironmentVariables.Count == 1 &&
              builder.EnvironmentVariables["only"] == "environment" &&
              builder.ExternalProperties.Count == 1 &&
              builder.ExternalProperties["only"] == "external",
            "ConfigEvaluatorBuilder settings and replacement semantics");
        using (var built = builder.Build())
        {
            Check(built.OwnsEvaluator, "built ConfigEvaluator does not own its evaluator");
            KnownOnly builtValue = built.Evaluate<KnownOnly>(
                ModuleSource.Text("known = \"built\"\nextra = 1\n"));
            Check(builtValue.Known == "built", "ConfigEvaluatorBuilder binder options were not applied");
        }

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
            "generated-readonly-set=passed\n" +
            "metadata-options=passed\n" +
            "custom-loader=passed\n" +
            "custom-conversion=passed\n" +
            "explicit-polymorphism=passed\n" +
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
            "conversion-failures=passed\n" +
            "polymorphic-mismatch=$\n" +
            "cycle=$.next\n" +
            "config-evaluator=passed\n" +
            "config-navigation=passed\n" +
            "config-builder=passed\n" +
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
