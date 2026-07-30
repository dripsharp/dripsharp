// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Idiomatic .NET configuration binding and deterministic C# schema generation
// for Pkl.Core. This is durable destination product code; translated Java
// output remains disposable.
#nullable enable

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

namespace Pkl.Core;

[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Enum |
                AttributeTargets.Property | AttributeTargets.Field | AttributeTargets.Parameter,
                Inherited = true)]
public sealed class PklNameAttribute : Attribute
{
    public PklNameAttribute(string name) => Name = name ?? throw new ArgumentNullException(nameof(name));
    public string Name { get; }
}

[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Enum,
                Inherited = false)]
public sealed class PklQualifiedNameAttribute : Attribute
{
    public PklQualifiedNameAttribute(string qualifiedName) =>
        QualifiedName = qualifiedName ?? throw new ArgumentNullException(nameof(qualifiedName));
    public string QualifiedName { get; }
}

[AttributeUsage(AttributeTargets.Property | AttributeTargets.Field, Inherited = true)]
public sealed class PklIgnoreAttribute : Attribute { }

[AttributeUsage(AttributeTargets.Property | AttributeTargets.Field, Inherited = true)]
public sealed class PklRequiredAttribute : Attribute { }

[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct, Inherited = false)]
public sealed class PklTypeAliasAttribute : Attribute { }

public sealed class PklBindException : Exception
{
    public PklBindException(string message) : base(message) { }
    public PklBindException(string message, Exception innerException) : base(message, innerException) { }

    internal PklBindException(string message, string pklPath, Type? sourceType,
        Type targetType, string reason, Exception? innerException = null)
        : base(message, innerException)
    {
        PklPath = pklPath;
        SourceType = sourceType;
        TargetType = targetType;
        Reason = reason;
    }

    /// <summary>The stable root-to-leaf Pkl path at which binding failed.</summary>
    public string? PklPath { get; }

    /// <summary>The exported Pkl value type, or <see langword="null"/> for a null value.</summary>
    public Type? SourceType { get; }

    /// <summary>The requested .NET target type.</summary>
    public Type? TargetType { get; }

    /// <summary>A stable, culture-independent failure reason.</summary>
    public string? Reason { get; }
}

public sealed class NoSuchChildException : Exception
{
    public NoSuchChildException(string qualifiedName, string childName)
        : base(qualifiedName.Length == 0
            ? $"Configuration root has no child `{childName}`."
            : $"Configuration node `{qualifiedName}` has no child `{childName}`.")
    {
        QualifiedName = qualifiedName;
        ChildName = childName;
    }

    public string QualifiedName { get; }
    public string ChildName { get; }
}

public interface IPklGeneratedLoader<out T>
{
    T Load(object? value, ConfigBinder binder);
}

public sealed class ConfigBinderOptions
{
    private readonly Dictionary<Type, object> generatedLoaders = new();
    private readonly List<CustomConversion> conversions = new();
    private readonly Dictionary<(Type BaseType, string QualifiedName), Type> typeMappings = new();

    internal sealed record CustomConversion(
        Type SourceType, Type TargetType, Func<object, ConfigBinder, object?> Convert);

    public bool IgnoreUnknownProperties { get; set; }
    public bool PropertyNamesCaseInsensitive { get; set; }
    public bool UseGeneratedLoaders { get; set; } = true;
    public bool AllowLossyNumericConversions { get; set; }

    public ConfigBinderOptions AddGeneratedLoader<T>(IPklGeneratedLoader<T> loader)
    {
        ArgumentNullException.ThrowIfNull(loader);
        generatedLoaders[typeof(T)] = loader;
        return this;
    }

    public ConfigBinderOptions AddConversion<TSource, TTarget>(
        Func<TSource, ConfigBinder, TTarget> conversion)
    {
        ArgumentNullException.ThrowIfNull(conversion);
        conversions.RemoveAll(item => item.SourceType == typeof(TSource) && item.TargetType == typeof(TTarget));
        conversions.Add(new CustomConversion(typeof(TSource), typeof(TTarget),
            (value, binder) => conversion((TSource)value, binder)));
        return this;
    }

    public ConfigBinderOptions AddTypeMapping<TBase, TDerived>(string pklQualifiedName)
        where TDerived : TBase
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pklQualifiedName);
        var derived = typeof(TDerived);
        if (derived.IsAbstract || derived.IsInterface || derived.ContainsGenericParameters)
            throw new ArgumentException($"Mapped type {derived.FullName} must be a closed, constructible type.",
                nameof(TDerived));
        typeMappings[(typeof(TBase), pklQualifiedName)] = derived;
        return this;
    }

    internal bool TryGetGeneratedLoader(Type type, out object loader) =>
        generatedLoaders.TryGetValue(type, out loader!);

    internal bool TryGetConversion(object value, Type targetType, string path,
        out Func<object, ConfigBinder, object?> conversion)
    {
        var sourceType = value.GetType();
        var candidates = conversions.Where(item => item.TargetType == targetType &&
                item.SourceType.IsAssignableFrom(sourceType))
            .ToArray();
        if (candidates.Length == 0)
        {
            conversion = null!;
            return false;
        }

        var exact = candidates.SingleOrDefault(item => item.SourceType == sourceType);
        if (exact is not null)
        {
            conversion = exact.Convert;
            return true;
        }

        var mostSpecific = candidates.Where(candidate => !candidates.Any(other =>
                candidate != other && candidate.SourceType.IsAssignableFrom(other.SourceType)))
            .OrderBy(item => item.SourceType.FullName, StringComparer.Ordinal)
            .ToArray();
        if (mostSpecific.Length != 1)
            throw new PklBindException(
                $"Cannot bind {path} ({sourceType.FullName}) to {targetType.FullName}: " +
                "multiple custom conversions are equally specific: " +
                string.Join(", ", mostSpecific.Select(item => item.SourceType.FullName)) + ".",
                path, sourceType, targetType, "multiple custom conversions are equally specific");
        conversion = mostSpecific[0].Convert;
        return true;
    }

    internal bool TryGetTypeMapping(Type baseType, string qualifiedName, out Type derivedType) =>
        typeMappings.TryGetValue((baseType, qualifiedName), out derivedType!);
}

public sealed class ConfigBinder
{
    private static readonly NullabilityInfoContext Nullability = new();
    private readonly ConfigBinderOptions options;
    private readonly Dictionary<(Type BaseType, string QualifiedName), Type?> discoveredTypeMappings = new();

    private sealed class BindingNullability
    {
        private BindingNullability(NullabilityInfo info, Func<NullabilityInfo, NullabilityState> state)
        {
            AllowsNull = state(info) != NullabilityState.NotNull;
            Element = info.ElementType is null ? null : new BindingNullability(info.ElementType, state);
            GenericArguments = info.GenericTypeArguments
                .Select(argument => new BindingNullability(argument, state)).ToArray();
        }

        public bool AllowsNull { get; }
        public BindingNullability? Element { get; }
        public IReadOnlyList<BindingNullability> GenericArguments { get; }

        public BindingNullability? GenericArgument(int index) =>
            index < GenericArguments.Count ? GenericArguments[index] : null;

        public static BindingNullability ForRead(NullabilityInfo info) =>
            new(info, item => item.ReadState);

        public static BindingNullability ForWrite(NullabilityInfo info) =>
            new(info, item => item.WriteState);
    }

    public ConfigBinder(ConfigBinderOptions? options = null) =>
        this.options = options ?? new ConfigBinderOptions();

    public ConfigBinderOptions Options => options;

    public T Bind<T>(object? value) =>
        (T)BindAt(value, typeof(T), "$", allowsNull: !typeof(T).IsValueType)!;

    /// <summary>Binds a value while rejecting a null root for every target type.</summary>
    public T BindRequired<T>(object? value) =>
        (T)BindAt(value, typeof(T), "$", allowsNull: Nullable.GetUnderlyingType(typeof(T)) is not null)!;

    /// <summary>Binds a value while allowing a null root for reference and nullable value types.</summary>
    public T? BindNullable<T>(object? value) =>
        (T?)BindAt(value, typeof(T), "$", allowsNull: true);

    public T BindGenerated<T>(object? value) =>
        (T)BindCore(value, typeof(T), "$", new HashSet<object>(ReferenceEqualityComparer.Instance),
            allowsNull: !typeof(T).IsValueType, skipGeneratedLoader: true)!;

    public object? Bind(object? value, Type targetType)
    {
        ArgumentNullException.ThrowIfNull(targetType);
        return BindAt(value, targetType, "$", allowsNull: !targetType.IsValueType);
    }

    public object? Bind(object? value, Type targetType, bool allowNull)
    {
        ArgumentNullException.ThrowIfNull(targetType);
        return BindAt(value, targetType, "$", allowNull);
    }

    internal object? BindAt(object? value, Type targetType, string path, bool allowsNull) =>
        BindCore(value, targetType, path, new HashSet<object>(ReferenceEqualityComparer.Instance),
            allowsNull, skipGeneratedLoader: false);

    private object? BindCore(object? value, Type targetType, string path, ISet<object> active,
        bool allowsNull, bool skipGeneratedLoader = false, BindingNullability? nullability = null)
    {
        if (value is not null && options.TryGetConversion(value, targetType, path, out var conversion))
        {
            try
            {
                var converted = conversion(value, this);
                if (converted is null)
                {
                    if (Nullable.GetUnderlyingType(targetType) is not null || allowsNull) return null;
                    throw Error(path, value, targetType, "the custom conversion returned null");
                }
                if (!targetType.IsInstanceOfType(converted) && Nullable.GetUnderlyingType(targetType) is null)
                    throw Error(path, value, targetType,
                        $"the custom conversion returned incompatible type {converted.GetType().FullName}");
                return converted;
            }
            catch (PklBindException) { throw; }
            catch (Exception error)
            {
                throw Error(path, value, targetType, "the custom conversion failed", error);
            }
        }

        if (!skipGeneratedLoader && TryGetGeneratedLoader(targetType, out var loader))
            return InvokeGeneratedLoader(loader, value, targetType, path);

        var nullableType = Nullable.GetUnderlyingType(targetType);
        if (targetType == typeof(PNull) && ReferenceEquals(value, PNull.GetInstance())) return value;
        if (value is null || ReferenceEquals(value, PNull.GetInstance()))
        {
            if (nullableType is not null || allowsNull) return null;
            throw Error(path, value, targetType, "null is not allowed");
        }

        if (nullableType is not null)
            return BindCore(value, nullableType, path, active, allowsNull: false,
                nullability: nullability?.GenericArgument(0));

        targetType = ResolvePolymorphicTarget(value, targetType, path);
        if (targetType == typeof(object) ||
            (targetType.IsInstanceOfType(value) && !RequiresRecursiveBinding(targetType))) return value;

        if (targetType.GetCustomAttribute<PklTypeAliasAttribute>() is not null)
            return BindAlias(value, targetType, path, active);
        if (targetType == typeof(string))
            return value switch
            {
                string text => text,
                Regex regex => regex.ToString(),
                PObject pklObject when IsSemanticVersion(pklObject) => SemanticVersionString(pklObject),
                _ => throw Error(path, value, targetType, "the source is not a string, Regex, or semantic Version")
            };
        if (targetType == typeof(Uri))
            return value is string uri ? CreateUri(uri, path, targetType) :
                throw Error(path, value, targetType, "the source is not a string URI");
        if (targetType == typeof(FileInfo))
            return value is string file ? CreateFileInfo(file, path, targetType) :
                throw Error(path, value, targetType, "the source is not a string path");
        if (targetType == typeof(DirectoryInfo))
            return value is string directory ? CreateDirectoryInfo(directory, path, targetType) :
                throw Error(path, value, targetType, "the source is not a string path");
        if (targetType == typeof(Regex))
            return value is string pattern ? CreateRegex(pattern, path, targetType) :
                throw Error(path, value, targetType, "the source is not a Regex or string pattern");
        if (targetType == typeof(TimeSpan))
            return value is Duration duration ? CreateTimeSpan(duration, path, targetType) :
                throw Error(path, value, targetType, "the source is not a Pkl Duration");
        if (targetType == typeof(Version) && value is string version)
            return CreateVersion(version, path, targetType);
        if (targetType == typeof(DurationUnit) && value is string durationUnit)
            return DurationUnit.Parse(durationUnit) ??
                throw Error(path, value, targetType, $"`{durationUnit}` is not a Pkl duration unit");
        if (targetType == typeof(DataSizeUnit) && value is string dataSizeUnit)
            return DataSizeUnit.Parse(dataSizeUnit) ??
                throw Error(path, value, targetType, $"`{dataSizeUnit}` is not a Pkl data-size unit");
        if (targetType == typeof(byte[]) && value is sbyte[] signedBytes)
            return signedBytes.Select(item => unchecked((byte)item)).ToArray();
        if (targetType == typeof(sbyte[]) && value is byte[] bytes)
            return bytes.Select(item => unchecked((sbyte)item)).ToArray();
        if (targetType == typeof(ReadOnlyMemory<byte>) && TryGetBytes(value, out var readOnlyBytes))
            return new ReadOnlyMemory<byte>(readOnlyBytes);
        if (targetType == typeof(Memory<byte>) && TryGetBytes(value, out var writableBytes))
            return new Memory<byte>(writableBytes);
        if (targetType.IsEnum) return BindEnum(value, targetType, path);
        if (IsNumeric(targetType)) return BindNumber(value, targetType, path);
        if (targetType == typeof(bool))
            return value is bool boolean ? boolean : throw Error(path, value, targetType, "the source is not a Boolean");
        if (targetType == typeof(char))
            return value is string character && character.Length == 1 ? character[0] :
                throw Error(path, value, targetType, "the source is not a single-character string");

        if (TryGetPairTypes(targetType, out var firstType, out var secondType))
            return Track(value, targetType, path, active,
                () => BindPair(value, targetType, firstType, secondType, path, active, nullability));
        if (TryGetDictionaryTypes(targetType, out var keyType, out var valueType))
            return Track(value, targetType, path, active,
                () => BindDictionary(value, targetType, keyType, valueType, path, active, nullability));
        if (TryGetCollectionElementType(targetType, out var elementType))
            return Track(value, targetType, path, active,
                () => BindCollection(value, targetType, elementType, path, active, nullability));

        if (!TryGetProperties(value, out var properties))
            throw Error(path, value, targetType, "the source is not a Pkl object or string-keyed mapping");
        return Track(value, targetType, path, active,
            () => BindObject(properties, targetType, path, active));
    }

    private Type ResolvePolymorphicTarget(object value, Type targetType, string path)
    {
        if (value is not PObject pklObject) return targetType;
        var qualifiedName = pklObject.GetClassInfo().GetQualifiedName();
        if (options.TryGetTypeMapping(targetType, qualifiedName, out var configured)) return configured;

        var key = (targetType, qualifiedName);
        if (!discoveredTypeMappings.TryGetValue(key, out var discovered))
        {
            var candidates = targetType.Assembly.GetTypes()
                .Where(type => !type.IsAbstract && !type.IsInterface && !type.ContainsGenericParameters &&
                    targetType.IsAssignableFrom(type) &&
                    string.Equals(type.GetCustomAttribute<PklQualifiedNameAttribute>()?.QualifiedName,
                        qualifiedName, StringComparison.Ordinal))
                .OrderBy(type => type.FullName, StringComparer.Ordinal).ToArray();
            if (candidates.Length > 1)
                throw Error(path, value, targetType, $"multiple generated types map Pkl class `{qualifiedName}`: " +
                    string.Join(", ", candidates.Select(type => type.FullName)));
            discovered = candidates.SingleOrDefault();
            discoveredTypeMappings[key] = discovered;
        }
        if (discovered is not null) return discovered;

        var declaredName = targetType.GetCustomAttribute<PklQualifiedNameAttribute>()?.QualifiedName;
        if (declaredName is not null && !string.Equals(declaredName, qualifiedName, StringComparison.Ordinal))
            throw Error(path, value, targetType,
                $"Pkl class `{qualifiedName}` does not match generated target `{declaredName}`");
        return targetType;
    }

    private bool TryGetGeneratedLoader(Type targetType, out object loader)
    {
        if (options.TryGetGeneratedLoader(targetType, out loader)) return true;
        if (!options.UseGeneratedLoaders) return false;
        var property = targetType.GetProperty("PklLoader", BindingFlags.Public | BindingFlags.Static);
        var contract = typeof(IPklGeneratedLoader<>).MakeGenericType(targetType);
        if (property is null || !contract.IsAssignableFrom(property.PropertyType)) return false;
        loader = property.GetValue(null) ??
            throw new PklBindException($"Generated loader property {targetType.FullName}.PklLoader returned null.");
        return true;
    }

    private object? InvokeGeneratedLoader(object loader, object? value, Type targetType, string path)
    {
        try
        {
            var result = typeof(IPklGeneratedLoader<>).MakeGenericType(targetType)
                .GetMethod(nameof(IPklGeneratedLoader<object>.Load))!
                .Invoke(loader, new object?[] { value, this });
            var nullable = Nullable.GetUnderlyingType(targetType);
            if (result is null && targetType.IsValueType && nullable is null)
                throw Error(path, value, targetType, "the generated loader returned null");
            if (result is not null && !targetType.IsInstanceOfType(result) &&
                (nullable is null || !nullable.IsInstanceOfType(result)))
                throw Error(path, value, targetType,
                    $"the generated loader returned incompatible type {result.GetType().FullName}");
            return result;
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            throw Error(path, value, targetType,
                "the generated loader failed: " + error.InnerException.Message, error.InnerException);
        }
    }

    private object BindAlias(object value, Type targetType, string path, ISet<object> active)
    {
        var constructors = targetType.GetConstructors(BindingFlags.Public | BindingFlags.Instance)
            .Where(item => item.GetParameters().Length == 1).ToArray();
        if (constructors.Length != 1)
            throw Error(path, value, targetType, "a Pkl type-alias target must have exactly one public single-argument constructor");
        var constructor = constructors[0];
        var parameter = constructor.GetParameters()[0];
        var nullability = BindingNullability.ForRead(Nullability.Create(parameter));
        var converted = BindCore(value, parameter.ParameterType, path, active,
            nullability.AllowsNull, skipGeneratedLoader: false, nullability: nullability);
        try { return constructor.Invoke(new[] { converted }); }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        { throw Error(path, value, targetType, "the type-alias constructor failed", error.InnerException); }
    }

    private object BindObject(IReadOnlyDictionary<string, object?> properties, Type targetType,
        string path, ISet<object> active)
    {
        if (targetType.IsInterface || targetType.IsAbstract)
            throw Error(path, properties, targetType, "the target object type cannot be constructed");
        var comparer = options.PropertyNamesCaseInsensitive ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
        var source = CopyProperties(properties, comparer, path, targetType);
        var consumed = new HashSet<string>(comparer);
        foreach (var name in IgnoredMemberNames(targetType)) consumed.Add(name);
        var instance = ConstructObject(source, consumed, targetType, path, active);

        var members = WritableMembers(targetType, path);
        foreach (var member in members)
        {
            var name = PklName(member);
            if (!source.TryGetValue(name, out var item))
            {
                if (IsRequired(member))
                    throw Error(path + "." + name, null, MemberType(member),
                        $"required Pkl property `{name}` is missing while constructing {targetType.FullName}");
                continue;
            }
            consumed.Add(name);
            switch (member)
            {
                case PropertyInfo property:
                {
                    var nullability = BindingNullability.ForWrite(Nullability.Create(property));
                    property.SetValue(instance, BindCore(item, property.PropertyType, path + "." + name,
                        active, nullability.AllowsNull, nullability: nullability));
                    break;
                }
                case FieldInfo field:
                {
                    var nullability = BindingNullability.ForWrite(Nullability.Create(field));
                    field.SetValue(instance, BindCore(item, field.FieldType, path + "." + name,
                        active, nullability.AllowsNull, nullability: nullability));
                    break;
                }
            }
        }

        if (!options.IgnoreUnknownProperties)
        {
            var unknown = source.Keys.Where(name => !consumed.Contains(name))
                .OrderBy(name => name, StringComparer.Ordinal).ToArray();
            if (unknown.Length != 0)
                throw Error(path, properties, targetType, "unknown Pkl properties: " +
                    string.Join(", ", unknown.Select(name => "`" + name + "`")));
        }
        return instance;
    }

    private static Dictionary<string, object?> CopyProperties(IReadOnlyDictionary<string, object?> properties,
        StringComparer comparer, string path, Type targetType)
    {
        var result = new Dictionary<string, object?>(comparer);
        foreach (var entry in properties.OrderBy(item => item.Key, StringComparer.Ordinal))
            if (!result.TryAdd(entry.Key, entry.Value))
                throw Error(path + "." + entry.Key, entry.Value, targetType,
                    $"property name `{entry.Key}` is ambiguous under the configured comparison");
        return result;
    }

    private object ConstructObject(IReadOnlyDictionary<string, object?> source, ISet<string> consumed,
        Type targetType, string path, ISet<object> active)
    {
        var constructors = targetType.GetConstructors(BindingFlags.Instance | BindingFlags.Public)
            .OrderByDescending(item => item.GetParameters().Length)
            .ThenBy(ConstructorSignature, StringComparer.Ordinal).ToArray();
        var applicable = constructors.Where(constructor => constructor.GetParameters()
                .All(parameter => source.ContainsKey(PklName(parameter)) || parameter.HasDefaultValue))
            .ToArray();
        if (applicable.Length != 0)
        {
            var parameterCount = applicable[0].GetParameters().Length;
            var best = applicable.Where(item => item.GetParameters().Length == parameterCount).ToArray();
            if (best.Length != 1)
                throw Error(path, source, targetType,
                    "multiple public constructors are equally applicable: " +
                    string.Join(", ", best.Select(ConstructorSignature)));
            var constructor = best[0];
            var parameters = constructor.GetParameters();
            try
            {
                var arguments = parameters.Select(parameter =>
                {
                    var name = PklName(parameter);
                    if (!source.TryGetValue(name, out var item)) return parameter.DefaultValue;
                    consumed.Add(name);
                    var nullability = BindingNullability.ForRead(Nullability.Create(parameter));
                    return BindCore(item, parameter.ParameterType, path + "." + name, active,
                        nullability.AllowsNull, nullability: nullability);
                }).ToArray();
                return constructor.Invoke(arguments);
            }
            catch (PklBindException) { throw; }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            { throw Error(path, source, targetType, "the selected constructor failed", error.InnerException); }
        }
        if (targetType.IsValueType && constructors.Length == 0)
            return Activator.CreateInstance(targetType)!;
        var required = constructors.SelectMany(item => item.GetParameters())
            .Select(PklName).Distinct(StringComparer.Ordinal).OrderBy(name => name, StringComparer.Ordinal);
        throw Error(path, source, targetType, "no public constructor matches; constructor properties are [" +
            string.Join(", ", required) + "]");
    }

    private static IReadOnlyList<MemberInfo> WritableMembers(Type targetType, string path)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.DeclaredOnly;
        var hierarchy = TypeHierarchy(targetType).ToArray();
        var candidates = hierarchy.SelectMany(type => type.GetProperties(flags)
                .Where(item => item.SetMethod is { IsPublic: true } && item.GetIndexParameters().Length == 0 &&
                               item.GetCustomAttribute<PklIgnoreAttribute>() is null))
            .Cast<MemberInfo>()
            .Concat(hierarchy.SelectMany(type => type.GetFields(flags).Where(item => !item.IsInitOnly &&
                item.GetCustomAttribute<PklIgnoreAttribute>() is null)))
            .OrderByDescending(item => InheritanceDepth(item.DeclaringType))
            .ThenBy(item => item.Name, StringComparer.Ordinal).ToArray();
        var selected = new List<MemberInfo>();
        foreach (var group in candidates.GroupBy(PklName, StringComparer.Ordinal))
        {
            var depth = group.Max(item => InheritanceDepth(item.DeclaringType));
            var mostDerived = group.Where(item => InheritanceDepth(item.DeclaringType) == depth).ToArray();
            if (mostDerived.Length != 1)
                throw Error(path + "." + group.Key, null, targetType,
                    $"multiple writable members map to `{group.Key}`");
            selected.Add(mostDerived[0]);
        }
        return selected.OrderBy(PklName, StringComparer.Ordinal).ToArray();
    }

    private object BindCollection(object value, Type targetType, Type elementType, string path,
        ISet<object> active, BindingNullability? nullability)
    {
        if (value is not IEnumerable source || value is string || value is IDictionary)
            throw Error(path, value, targetType, "the source is not a collection");
        var elementNullability = targetType.IsArray ? nullability?.Element : nullability?.GenericArgument(0);
        var items = source.Cast<object?>().Select((item, index) =>
            BindCore(item, elementType, $"{path}[{index}]", active,
                elementNullability?.AllowsNull ?? !elementType.IsValueType,
                nullability: elementNullability)).ToArray();
        if (targetType.IsArray)
        {
            var result = Array.CreateInstance(elementType, items.Length);
            for (var index = 0; index < items.Length; index++) result.SetValue(items[index], index);
            return result;
        }
        var concrete = !targetType.IsInterface && !targetType.IsAbstract ? targetType :
            IsSetType(targetType) ? typeof(HashSet<>).MakeGenericType(elementType) :
            typeof(List<>).MakeGenericType(elementType);
        var typedItems = Array.CreateInstance(elementType, items.Length);
        for (var index = 0; index < items.Length; index++) typedItems.SetValue(items[index], index);
        var enumerableType = typeof(IEnumerable<>).MakeGenericType(elementType);
        var enumerableConstructor = concrete.GetConstructor(new[] { enumerableType });
        if (enumerableConstructor is not null)
            return enumerableConstructor.Invoke(new object[] { typedItems });
        var collection = Activator.CreateInstance(concrete) ??
            throw Error(path, value, targetType, "the target collection could not be created");
        var add = concrete.GetMethod("Add", new[] { elementType }) ?? concrete.GetInterfaces()
            .Select(type => type.GetMethod("Add", new[] { elementType })).FirstOrDefault(method => method is not null);
        if (add is null) throw Error(path, value, targetType, "the target collection has no compatible Add method");
        foreach (var item in items) add.Invoke(collection, new[] { item });
        return collection;
    }

    private object BindDictionary(object value, Type targetType, Type keyType, Type valueType,
        string path, ISet<object> active, BindingNullability? nullability)
    {
        if (!TryGetMapEntries(value, out var source))
            throw Error(path, value, targetType, "the source is not a mapping");
        var keyNullability = nullability?.GenericArgument(0);
        var valueNullability = nullability?.GenericArgument(1);
        var concrete = !targetType.IsInterface && !targetType.IsAbstract ? targetType :
            typeof(Dictionary<,>).MakeGenericType(keyType, valueType);
        var result = Activator.CreateInstance(concrete) ??
            throw Error(path, value, targetType, "the target dictionary could not be created");
        var dictionary = result as IDictionary;
        var add = dictionary is null
            ? concrete.GetMethod("Add", new[] { keyType, valueType })
            : null;
        if (dictionary is null && add is null)
            throw Error(path, value, targetType, "the target dictionary has no compatible Add method");
        var index = 0;
        foreach (var entry in source)
        {
            var keyPath = $"{path}{{key:{index}}}";
            var key = BindCore(entry.Key, keyType, keyPath, active,
                keyNullability?.AllowsNull ?? !keyType.IsValueType, nullability: keyNullability);
            var itemPath = path + "[" + FormatPathKey(entry.Key) + "]";
            var item = BindCore(entry.Value, valueType, itemPath, active,
                valueNullability?.AllowsNull ?? !valueType.IsValueType, nullability: valueNullability);
            try
            {
                if (dictionary is not null) dictionary.Add(key!, item);
                else add!.Invoke(result, new[] { key, item });
            }
            catch (Exception error) when (error is ArgumentException or TargetInvocationException)
            {
                var cause = error is TargetInvocationException { InnerException: not null } invocation
                    ? invocation.InnerException
                    : error;
                throw Error(keyPath, entry.Key, keyType,
                    "the converted map key is null, duplicated, or rejected by the target map", cause);
            }
            index++;
        }
        return result;
    }

    private object BindPair(object value, Type targetType, Type firstType, Type secondType,
        string path, ISet<object> active, BindingNullability? nullability)
    {
        if (!TryReadPair(value, out var rawFirst, out var rawSecond))
            throw Error(path, value, targetType, "the source is not a Pkl Pair");
        var firstNullability = nullability?.GenericArgument(0);
        var secondNullability = nullability?.GenericArgument(1);
        var first = BindCore(rawFirst, firstType, path + ".first", active,
            firstNullability?.AllowsNull ?? !firstType.IsValueType, nullability: firstNullability);
        var second = BindCore(rawSecond, secondType, path + ".second", active,
            secondNullability?.AllowsNull ?? !secondType.IsValueType, nullability: secondNullability);
        var constructor = targetType.GetConstructor(new[] { firstType, secondType });
        if (constructor is null) throw Error(path, value, targetType, "the target Pair has no compatible constructor");
        return constructor.Invoke(new[] { first, second });
    }

    private static object BindEnum(object value, Type targetType, string path)
    {
        if (value is not string text)
            throw Error(path, value, targetType, "enum values must come from Pkl strings");
        foreach (var field in targetType.GetFields(BindingFlags.Public | BindingFlags.Static)
                     .OrderBy(item => item.Name, StringComparer.Ordinal))
            if (string.Equals(PklName(field), text, StringComparison.Ordinal)) return field.GetValue(null)!;
        var normalized = Identifier(text);
        foreach (var name in Enum.GetNames(targetType))
            if (string.Equals(name, normalized, StringComparison.OrdinalIgnoreCase))
                return Enum.Parse(targetType, name, ignoreCase: false);
        throw Error(path, value, targetType, $"`{text}` is not a declared enum value");
    }

    private object BindNumber(object value, Type targetType, string path)
    {
        try
        {
            if (value is long integer) return ConvertInteger(integer, targetType);
            if (value is double floating) return ConvertFloating(floating, targetType);
            throw Error(path, value, targetType, "the source is not a Pkl Int or Float");
        }
        catch (OverflowException error) { throw Error(path, value, targetType, "numeric overflow", error); }
        catch (ArithmeticException error) { throw Error(path, value, targetType, error.Message, error); }
    }

    private object ConvertInteger(long value, Type targetType)
    {
        checked
        {
            if (targetType == typeof(sbyte)) return (sbyte)value;
            if (targetType == typeof(byte)) return (byte)value;
            if (targetType == typeof(short)) return (short)value;
            if (targetType == typeof(ushort)) return (ushort)value;
            if (targetType == typeof(int)) return (int)value;
            if (targetType == typeof(uint)) return (uint)value;
            if (targetType == typeof(long)) return value;
            if (targetType == typeof(ulong)) return (ulong)value;
            if (targetType == typeof(nint)) return (nint)value;
            if (targetType == typeof(nuint)) return (nuint)value;
            if (targetType == typeof(Int128)) return (Int128)value;
            if (targetType == typeof(UInt128)) return (UInt128)value;
            if (targetType == typeof(decimal)) return (decimal)value;
            if (targetType == typeof(BigInteger)) return new BigInteger(value);
        }
        if (targetType == typeof(double))
        {
            var result = (double)value;
            if (!options.AllowLossyNumericConversions && (long)result != value)
                throw new ArithmeticException("the conversion would lose integer precision");
            return result;
        }
        if (targetType == typeof(float))
        {
            var result = (float)value;
            if (!options.AllowLossyNumericConversions && (long)result != value)
                throw new ArithmeticException("the conversion would lose integer precision");
            return result;
        }
        if (targetType == typeof(Half))
        {
            var result = (Half)value;
            if (!Half.IsFinite(result)) throw new OverflowException();
            if (!options.AllowLossyNumericConversions && (long)result != value)
                throw new ArithmeticException("the conversion would lose integer precision");
            return result;
        }
        throw new InvalidOperationException("unsupported numeric target");
    }

    private object ConvertFloating(double value, Type targetType)
    {
        if (targetType == typeof(double)) return value;
        if (targetType == typeof(float))
        {
            var result = checked((float)value);
            if (double.IsFinite(value) && !float.IsFinite(result)) throw new OverflowException();
            if (!options.AllowLossyNumericConversions && (double)result != value)
                throw new ArithmeticException("the conversion would lose floating-point precision");
            return result;
        }
        if (targetType == typeof(Half))
        {
            var result = (Half)value;
            if (double.IsFinite(value) && !Half.IsFinite(result)) throw new OverflowException();
            if (!options.AllowLossyNumericConversions && (double)result != value)
                throw new ArithmeticException("the conversion would lose floating-point precision");
            return result;
        }
        if (targetType == typeof(decimal))
        {
            if (!double.IsFinite(value)) throw new OverflowException();
            var result = checked((decimal)value);
            if (!options.AllowLossyNumericConversions && (double)result != value)
                throw new ArithmeticException("the conversion would lose floating-point precision");
            return result;
        }
        if (targetType == typeof(BigInteger))
        {
            if (!double.IsFinite(value) || Math.Truncate(value) != value)
                throw new ArithmeticException("a non-integral Pkl Float cannot be converted to an integral target");
            return new BigInteger(value);
        }
        if (!double.IsFinite(value) || Math.Truncate(value) != value)
            throw new ArithmeticException("a non-integral Pkl Float cannot be converted to an integral target");
        if (value < long.MinValue || value > long.MaxValue)
            throw new OverflowException();
        return ConvertInteger(checked((long)value), targetType);
    }

    private static Uri CreateUri(string value, string path, Type targetType)
    {
        try { return new Uri(value, UriKind.RelativeOrAbsolute); }
        catch (UriFormatException error) { throw Error(path, value, targetType, "invalid URI", error); }
    }

    private static FileInfo CreateFileInfo(string value, string path, Type targetType)
    {
        try { return new FileInfo(value); }
        catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException)
        { throw Error(path, value, targetType, "invalid file path", error); }
    }

    private static DirectoryInfo CreateDirectoryInfo(string value, string path, Type targetType)
    {
        try { return new DirectoryInfo(value); }
        catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException)
        { throw Error(path, value, targetType, "invalid directory path", error); }
    }

    private static Regex CreateRegex(string value, string path, Type targetType)
    {
        try { return new Regex(value); }
        catch (ArgumentException error) { throw Error(path, value, targetType, "invalid regular expression", error); }
    }

    private static TimeSpan CreateTimeSpan(Duration value, string path, Type targetType)
    {
        try { return value.ToJavaDuration(); }
        catch (ArithmeticException error)
        { throw Error(path, value, targetType, "the Pkl Duration is outside the TimeSpan range", error); }
    }

    private static Version CreateVersion(string value, string path, Type targetType)
    {
        try { return Version.Parse(value); }
        catch (ArgumentException error)
        { throw Error(path, value, targetType, "invalid semantic version", error); }
    }

    private static bool IsSemanticVersion(PObject value) =>
        string.Equals(value.GetClassInfo().GetQualifiedName(), "pkl.semver#Version", StringComparison.Ordinal);

    private static string SemanticVersionString(PObject value)
    {
        var properties = value.GetProperties();
        var result = new StringBuilder()
            .Append(Convert.ToString(properties["major"], CultureInfo.InvariantCulture)).Append('.')
            .Append(Convert.ToString(properties["minor"], CultureInfo.InvariantCulture)).Append('.')
            .Append(Convert.ToString(properties["patch"], CultureInfo.InvariantCulture));
        if (properties.TryGetValue("preRelease", out var preRelease) &&
            preRelease is not null && !ReferenceEquals(preRelease, PNull.GetInstance()))
            result.Append('-').Append((string)preRelease);
        if (properties.TryGetValue("build", out var build) &&
            build is not null && !ReferenceEquals(build, PNull.GetInstance()))
            result.Append('+').Append((string)build);
        return result.ToString();
    }

    private static bool TryGetProperties(object value, out IReadOnlyDictionary<string, object?> result)
    {
        if (value is PObject pklObject)
        {
            result = pklObject.GetProperties().ToDictionary(entry => entry.Key, entry => (object?)entry.Value,
                StringComparer.Ordinal);
            return true;
        }
        if (value is IDictionary dictionary)
        {
            var converted = new Dictionary<string, object?>(StringComparer.Ordinal);
            foreach (DictionaryEntry entry in dictionary)
                if (entry.Key is string name) converted[name] = entry.Value;
                else { result = null!; return false; }
            result = converted;
            return true;
        }
        result = null!;
        return false;
    }

    private static bool TryGetMapEntries(object value,
        out IReadOnlyList<KeyValuePair<object?, object?>> result)
    {
        if (value is PObject pklObject)
        {
            result = pklObject.GetProperties()
                .Select(entry => new KeyValuePair<object?, object?>(entry.Key, entry.Value))
                .ToArray();
            return true;
        }
        if (value is IDictionary dictionary)
        {
            var entries = new List<KeyValuePair<object?, object?>>(dictionary.Count);
            foreach (DictionaryEntry entry in dictionary)
                entries.Add(new KeyValuePair<object?, object?>(entry.Key, entry.Value));
            result = entries;
            return true;
        }
        if (value is IEnumerable enumerable && value is not string)
        {
            var entries = new List<KeyValuePair<object?, object?>>();
            foreach (var entry in enumerable)
            {
                if (entry is null)
                {
                    result = null!;
                    return false;
                }
                var entryType = entry.GetType();
                var key = entryType.GetProperty("Key", BindingFlags.Public | BindingFlags.Instance);
                var item = entryType.GetProperty("Value", BindingFlags.Public | BindingFlags.Instance);
                if (key is null || item is null)
                {
                    result = null!;
                    return false;
                }
                entries.Add(new KeyValuePair<object?, object?>(key.GetValue(entry), item.GetValue(entry)));
            }
            result = entries;
            return true;
        }
        result = null!;
        return false;
    }

    private static bool TryGetBytes(object value, out byte[] result)
    {
        switch (value)
        {
            case byte[] bytes:
                result = bytes.ToArray();
                return true;
            case sbyte[] bytes:
                result = bytes.Select(item => unchecked((byte)item)).ToArray();
                return true;
            default:
                result = null!;
                return false;
        }
    }

    private static bool TryGetDictionaryTypes(Type type, out Type key, out Type value)
    {
        var match = type.GetInterfaces().Concat(new[] { type }).FirstOrDefault(candidate =>
            candidate.IsGenericType && candidate.GetGenericTypeDefinition() is var definition &&
            (definition == typeof(IDictionary<,>) || definition == typeof(IReadOnlyDictionary<,>)));
        if (match is null) { key = value = null!; return false; }
        (key, value) = (match.GetGenericArguments()[0], match.GetGenericArguments()[1]);
        return true;
    }

    private static bool TryGetCollectionElementType(Type type, out Type element)
    {
        if (type.IsArray) { element = type.GetElementType()!; return true; }
        var definitions = new[] { typeof(IEnumerable<>), typeof(ICollection<>), typeof(IList<>),
            typeof(ISet<>), typeof(IReadOnlySet<>), typeof(IReadOnlyList<>), typeof(IReadOnlyCollection<>) };
        var match = type.GetInterfaces().Concat(new[] { type }).FirstOrDefault(candidate =>
            candidate.IsGenericType && definitions.Contains(candidate.GetGenericTypeDefinition()));
        if (match is null) { element = null!; return false; }
        element = match.GetGenericArguments()[0];
        return true;
    }

    private static bool TryGetPairTypes(Type type, out Type first, out Type second)
    {
        if (type.IsGenericType && type.GetGenericTypeDefinition() is var definition &&
            (definition == typeof(Pair<,>) || definition == typeof(KeyValuePair<,>) ||
             definition == typeof(Tuple<,>) || definition == typeof(ValueTuple<,>)))
        {
            var arguments = type.GetGenericArguments();
            first = arguments[0]; second = arguments[1]; return true;
        }
        first = second = null!; return false;
    }

    private static bool TryReadPair(object value, out object? first, out object? second)
    {
        var valueType = value.GetType();
        var firstMember = (MemberInfo?)valueType.GetMethod("GetFirst", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetProperty("First", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetProperty("Key", BindingFlags.Public | BindingFlags.Instance) ??
            (MemberInfo?)valueType.GetProperty("Item1", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetField("Item1", BindingFlags.Public | BindingFlags.Instance);
        var secondMember = (MemberInfo?)valueType.GetMethod("GetSecond", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetProperty("Second", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetProperty("Value", BindingFlags.Public | BindingFlags.Instance) ??
            (MemberInfo?)valueType.GetProperty("Item2", BindingFlags.Public | BindingFlags.Instance) ??
            valueType.GetField("Item2", BindingFlags.Public | BindingFlags.Instance);
        if (firstMember is null || secondMember is null)
        {
            first = second = null;
            return false;
        }
        first = ReadMember(firstMember, value);
        second = ReadMember(secondMember, value);
        return true;
    }

    private static object? ReadMember(MemberInfo member, object value) => member switch
    {
        MethodInfo method => method.Invoke(value, null),
        PropertyInfo property => property.GetValue(value),
        FieldInfo field => field.GetValue(value),
        _ => throw new InvalidOperationException("Unsupported pair member.")
    };

    private static bool IsSetType(Type type) => type.GetInterfaces().Concat(new[] { type }).Any(candidate =>
        candidate.IsGenericType && candidate.GetGenericTypeDefinition() is var definition &&
        (definition == typeof(ISet<>) || definition == typeof(IReadOnlySet<>)));
    private static bool RequiresRecursiveBinding(Type type) =>
        type != typeof(byte[]) && type != typeof(sbyte[]) &&
        (TryGetPairTypes(type, out _, out _) || TryGetDictionaryTypes(type, out _, out _) ||
         TryGetCollectionElementType(type, out _));
    private static bool IsNumeric(Type type) => type == typeof(BigInteger) || type == typeof(Half) ||
        type == typeof(nint) || type == typeof(nuint) || type == typeof(Int128) || type == typeof(UInt128) ||
        Type.GetTypeCode(type) is >= TypeCode.SByte and <= TypeCode.Decimal;
    private static string PklName(MemberInfo member) => member.GetCustomAttribute<PklNameAttribute>()?.Name ?? member.Name;
    private static string PklName(ParameterInfo parameter) => parameter.GetCustomAttribute<PklNameAttribute>()?.Name ?? parameter.Name!;
    private static Type MemberType(MemberInfo member) => member switch
    {
        PropertyInfo property => property.PropertyType,
        FieldInfo field => field.FieldType,
        _ => throw new InvalidOperationException("Unsupported bindable member.")
    };
    private static bool IsRequired(MemberInfo member) => member.GetCustomAttribute<PklRequiredAttribute>() is not null ||
        member.GetCustomAttributes().Any(attribute => attribute.GetType().FullName ==
            "System.Runtime.CompilerServices.RequiredMemberAttribute");
    private static IEnumerable<string> IgnoredMemberNames(Type targetType)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public;
        return targetType.GetProperties(flags).Cast<MemberInfo>()
            .Concat(targetType.GetFields(flags))
            .Where(member => member.GetCustomAttribute<PklIgnoreAttribute>() is not null)
            .Select(PklName)
            .Distinct(StringComparer.Ordinal);
    }
    private static int InheritanceDepth(Type? type) { var depth = 0; while (type is not null) { depth++; type = type.BaseType; } return depth; }
    private static IEnumerable<Type> TypeHierarchy(Type type)
    {
        for (Type? current = type; current is not null && current != typeof(object); current = current.BaseType)
            yield return current;
    }
    private static string ConstructorSignature(ConstructorInfo constructor) =>
        string.Join("|", constructor.GetParameters().Select(item => item.ParameterType.FullName + ":" + PklName(item)));
    private static string FormatPathKey(object? value) => value is string text ? "\"" + text.Replace("\"", "\\\"", StringComparison.Ordinal) + "\"" :
        Convert.ToString(value, CultureInfo.InvariantCulture) ?? "null";
    private static object Track(object value, Type targetType, string path, ISet<object> active, Func<object> bind)
    {
        if (!active.Add(value)) throw Error(path, value, targetType, "cyclic object graphs cannot be bound");
        try { return bind(); }
        finally { active.Remove(value); }
    }
    private static PklBindException Error(string path, object? value, Type type, string reason,
        Exception? inner = null) =>
        new($"Cannot bind {path} ({value?.GetType().FullName ?? "null"}) to {type.FullName}: {reason}.",
            path, value?.GetType(), type, reason, inner);
    private static string Identifier(string value) => CSharpGenerator.ToIdentifier(value);
}

/// <summary>A navigable node in an evaluated Pkl configuration tree.</summary>
public sealed class Config
{
    private readonly ConfigBinder binder;

    internal Config(string qualifiedName, object? rawValue, ConfigBinder binder)
    {
        QualifiedName = qualifiedName;
        RawValue = rawValue;
        this.binder = binder;
    }

    /// <summary>The dot-qualified node name, or an empty string for the root.</summary>
    public string QualifiedName { get; }

    /// <summary>The exported Pkl value represented by this node.</summary>
    public object? RawValue { get; }

    public Config this[string childName] => Get(childName);
    public Config this[int index] => Get(index);

    public IReadOnlyList<string> ChildNames
    {
        get
        {
            if (RawValue is PObject pklObject)
                return pklObject.GetProperties().Keys.ToArray();
            if (RawValue is IDictionary dictionary)
                return dictionary.Keys.Cast<object?>().Select(FormatChildName).ToArray();
            if (RawValue is IEnumerable enumerable && RawValue is not string)
                return enumerable.Cast<object?>().Select((_, index) =>
                    index.ToString(CultureInfo.InvariantCulture)).ToArray();
            return Array.Empty<string>();
        }
    }

    public Config Get(string childName)
    {
        ArgumentNullException.ThrowIfNull(childName);
        if (RawValue is PObject pklObject &&
            pklObject.GetProperties().TryGetValue(childName, out var property))
            return Child(childName, property);
        if (RawValue is IDictionary dictionary && dictionary.Contains(childName))
            return Child(childName, dictionary[childName]);
        throw new NoSuchChildException(QualifiedName, childName);
    }

    public bool TryGet(string childName, out Config? child)
    {
        ArgumentNullException.ThrowIfNull(childName);
        if (RawValue is PObject pklObject &&
            pklObject.GetProperties().TryGetValue(childName, out var property))
        {
            child = Child(childName, property);
            return true;
        }
        if (RawValue is IDictionary dictionary && dictionary.Contains(childName))
        {
            child = Child(childName, dictionary[childName]);
            return true;
        }
        child = null;
        return false;
    }

    public Config Get(int index)
    {
        if (index >= 0 && RawValue is IList list && index < list.Count)
            return IndexedChild(index, list[index]);
        if (index >= 0 && RawValue is IEnumerable enumerable && RawValue is not string)
        {
            var current = 0;
            foreach (var value in enumerable)
            {
                if (current == index) return IndexedChild(index, value);
                current++;
            }
        }
        throw new NoSuchChildException(QualifiedName, index.ToString(CultureInfo.InvariantCulture));
    }

    public Config Get(object key)
    {
        ArgumentNullException.ThrowIfNull(key);
        if (RawValue is IDictionary dictionary && dictionary.Contains(key))
            return key is string childName
                ? Child(childName, dictionary[key])
                : KeyedChild(key, dictionary[key]);
        if (key is string name) return Get(name);
        if (key is int index) return Get(index);
        throw new NoSuchChildException(QualifiedName, FormatChildName(key));
    }

    public Config GetKey(object key)
    {
        ArgumentNullException.ThrowIfNull(key);
        if (RawValue is IDictionary dictionary && dictionary.Contains(key))
            return KeyedChild(key, dictionary[key]);
        throw new NoSuchChildException(QualifiedName, FormatChildName(key));
    }

    public Config GetPath(params object[] path)
    {
        ArgumentNullException.ThrowIfNull(path);
        return path.Aggregate(this, (node, segment) => node.Get(segment));
    }

    public T As<T>() where T : notnull =>
        (T)binder.BindAt(RawValue, typeof(T), BindingPath, allowsNull: false)!;

    public T? AsNullable<T>() =>
        (T?)binder.BindAt(RawValue, typeof(T), BindingPath, allowsNull: true);

    public object As(Type targetType)
    {
        ArgumentNullException.ThrowIfNull(targetType);
        return binder.BindAt(RawValue, targetType, BindingPath, allowsNull: false)!;
    }

    public object? AsNullable(Type targetType)
    {
        ArgumentNullException.ThrowIfNull(targetType);
        return binder.BindAt(RawValue, targetType, BindingPath, allowsNull: true);
    }

    private string BindingPath => QualifiedName.Length == 0 ? "$" : "$." + QualifiedName;

    private Config Child(string name, object? value) =>
        new(QualifiedName.Length == 0 ? name : QualifiedName + "." + name, value, binder);

    private Config IndexedChild(int index, object? value) =>
        new(QualifiedName + "[" + index.ToString(CultureInfo.InvariantCulture) + "]", value, binder);

    private Config KeyedChild(object key, object? value) =>
        new(QualifiedName + "[" + FormatChildName(key) + "]", value, binder);

    private static string FormatChildName(object? value) => value switch
    {
        null => "null",
        string text => text,
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString() ?? string.Empty
    };
}

/// <summary>Builds configuration evaluators from Pkl evaluator policy and .NET binding options.</summary>
public sealed class ConfigEvaluatorBuilder
{
    private EvaluatorBuilder evaluatorBuilder;
    private ConfigBinderOptions binderOptions;

    private ConfigEvaluatorBuilder(EvaluatorBuilder evaluatorBuilder, ConfigBinderOptions binderOptions)
    {
        this.evaluatorBuilder = evaluatorBuilder;
        this.binderOptions = binderOptions;
    }

    public static ConfigEvaluatorBuilder Preconfigured() =>
        new(EvaluatorBuilder.Preconfigured(), new ConfigBinderOptions());

    public static ConfigEvaluatorBuilder Unconfigured() =>
        new(EvaluatorBuilder.Unconfigured(), new ConfigBinderOptions());

    public EvaluatorBuilder EvaluatorBuilder => evaluatorBuilder;
    public ConfigBinderOptions BinderOptions => binderOptions;

    public ConfigEvaluatorBuilder SetEvaluatorBuilder(EvaluatorBuilder builder)
    {
        evaluatorBuilder = builder ?? throw new ArgumentNullException(nameof(builder));
        return this;
    }

    public ConfigEvaluatorBuilder SetBinderOptions(ConfigBinderOptions options)
    {
        binderOptions = options ?? throw new ArgumentNullException(nameof(options));
        return this;
    }

    public ConfigEvaluatorBuilder ConfigureBinder(Action<ConfigBinderOptions> configure)
    {
        ArgumentNullException.ThrowIfNull(configure);
        configure(binderOptions);
        return this;
    }

    public ConfigEvaluatorBuilder AddEnvironmentVariable(string name, string value)
    { evaluatorBuilder.AddEnvironmentVariable(name, value); return this; }

    public ConfigEvaluatorBuilder AddEnvironmentVariables(IReadOnlyDictionary<string, string> values)
    { evaluatorBuilder.AddEnvironmentVariables(values); return this; }

    public ConfigEvaluatorBuilder SetEnvironmentVariables(IReadOnlyDictionary<string, string> values)
    { evaluatorBuilder.SetEnvironmentVariables(values); return this; }

    public IReadOnlyDictionary<string, string> EnvironmentVariables =>
        evaluatorBuilder.GetEnvironmentVariables();

    public ConfigEvaluatorBuilder AddExternalProperty(string name, string value)
    { evaluatorBuilder.AddExternalProperty(name, value); return this; }

    public ConfigEvaluatorBuilder AddExternalProperties(IReadOnlyDictionary<string, string> values)
    { evaluatorBuilder.AddExternalProperties(values); return this; }

    public ConfigEvaluatorBuilder SetExternalProperties(IReadOnlyDictionary<string, string> values)
    { evaluatorBuilder.SetExternalProperties(values); return this; }

    public IReadOnlyDictionary<string, string> ExternalProperties =>
        evaluatorBuilder.GetExternalProperties();

    public ConfigEvaluatorBuilder SetAllowedModules(IReadOnlyCollection<Regex> patterns)
    { evaluatorBuilder.SetAllowedModules(patterns); return this; }

    public ConfigEvaluatorBuilder SetAllowedResources(IReadOnlyCollection<Regex> patterns)
    { evaluatorBuilder.SetAllowedResources(patterns); return this; }

    public ConfigEvaluatorBuilder SetSecurityManager(SecurityManager? manager)
    { evaluatorBuilder.SetSecurityManager(manager); return this; }

    public ConfigEvaluatorBuilder SetRootDirectory(string? path)
    { evaluatorBuilder.SetRootDir(path); return this; }

    public ConfigEvaluatorBuilder SetModuleCacheDirectory(string? path)
    { evaluatorBuilder.SetModuleCacheDir(path); return this; }

    public ConfigEvaluatorBuilder SetTimeout(TimeSpan? timeout)
    { evaluatorBuilder.SetTimeout(timeout); return this; }

    public ConfigEvaluatorBuilder SetTraceMode(EvaluatorSettings.TraceMode mode)
    { evaluatorBuilder.SetTraceMode(mode); return this; }

    public ConfigEvaluator Build() =>
        new(evaluatorBuilder.Build(), new ConfigBinder(binderOptions), ownsEvaluator: true);
}

public sealed class ConfigEvaluator : IDisposable
{
    private readonly Evaluator evaluator;
    private readonly ConfigBinder binder;
    private readonly bool ownsEvaluator;
    private bool disposed;

    public ConfigEvaluator(Evaluator? evaluator = null, ConfigBinder? binder = null)
        : this(evaluator, binder, ownsEvaluator: false) { }

    public ConfigEvaluator(Evaluator? evaluator, ConfigBinder? binder, bool ownsEvaluator)
    {
        this.evaluator = evaluator ?? Evaluator.Preconfigured();
        this.binder = binder ?? new ConfigBinder();
        this.ownsEvaluator = evaluator is null || ownsEvaluator;
    }

    public static ConfigEvaluator Preconfigured() => ConfigEvaluatorBuilder.Preconfigured().Build();

    public ConfigBinder Binder { get { ThrowIfDisposed(); return binder; } }
    public Evaluator Evaluator { get { ThrowIfDisposed(); return evaluator; } }
    public bool OwnsEvaluator => ownsEvaluator;
    public bool IsDisposed => disposed;

    public Config Evaluate(ModuleSource source)
    { ThrowIfDisposed(); return new Config("", evaluator.Evaluate(source), binder); }

    public Config EvaluateOutputValue(ModuleSource source)
    { ThrowIfDisposed(); return new Config("", evaluator.EvaluateOutputValue(source), binder); }

    public Config EvaluateExpression(ModuleSource source, string expression)
    { ThrowIfDisposed(); return new Config("", evaluator.EvaluateExpression(source, expression), binder); }

    public T Evaluate<T>(ModuleSource source) where T : notnull => Evaluate(source).As<T>();
    public T EvaluateOutputValue<T>(ModuleSource source) where T : notnull => EvaluateOutputValue(source).As<T>();
    public T EvaluateExpression<T>(ModuleSource source, string expression) where T : notnull =>
        EvaluateExpression(source, expression).As<T>();

    public object Evaluate(ModuleSource source, Type targetType) => Evaluate(source).As(targetType);
    public object EvaluateOutputValue(ModuleSource source, Type targetType) =>
        EvaluateOutputValue(source).As(targetType);
    public object EvaluateExpression(ModuleSource source, string expression, Type targetType) =>
        EvaluateExpression(source, expression).As(targetType);

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        if (ownsEvaluator) evaluator.Dispose();
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

public sealed class CSharpGenerationException : Exception
{
    public CSharpGenerationException(IEnumerable<string> diagnostics)
        : base("C# schema generation failed:\n" + string.Join("\n", diagnostics.OrderBy(item => item, StringComparer.Ordinal)))
    {
        Diagnostics = diagnostics.OrderBy(item => item, StringComparer.Ordinal).ToArray();
    }

    public IReadOnlyList<string> Diagnostics { get; }
}

public sealed class CSharpGeneratorOptions
{
    private readonly SortedDictionary<string, string> namespaceMappings =
        new(StringComparer.Ordinal);

    public string? Namespace { get; set; }
    public bool EmitDocComments { get; set; } = true;
    public bool EmitGeneratedLoaders { get; set; } = true;
    public bool EmitValueSemantics { get; set; } = true;
    public IReadOnlyDictionary<string, string> NamespaceMappings => namespaceMappings;

    public CSharpGeneratorOptions MapNamespace(string moduleName, string @namespace)
    {
        ArgumentException.ThrowIfNullOrEmpty(moduleName);
        ArgumentException.ThrowIfNullOrEmpty(@namespace);
        namespaceMappings[moduleName] = @namespace;
        return this;
    }
}

public sealed class CSharpGenerator
{
    private const string ValueSemanticsHelper = "PklGeneratedModelSemantics";

    private static readonly HashSet<string> Keywords = new(StringComparer.Ordinal)
    {
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true",
        "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual",
        "void", "volatile", "while"
    };

    private readonly CSharpGeneratorOptions options;
    private string moduleName = "";
    private string moduleNamespace = "";
    private readonly Dictionary<string, string> localTypes = new(StringComparer.Ordinal);
    private readonly HashSet<string> enumAliases = new(StringComparer.Ordinal);

    private readonly record struct MemberDeclaration(
        string Symbol, string Description, bool IsInherited, string? PklName);

    public CSharpGenerator(CSharpGeneratorOptions? options = null) =>
        this.options = options ?? new CSharpGeneratorOptions();

    public string Generate(Evaluator evaluator, ModuleSource source)
    {
        ArgumentNullException.ThrowIfNull(evaluator);
        ArgumentNullException.ThrowIfNull(source);
        return Generate(evaluator.EvaluateSchema(source));
    }

    public string Generate(ModuleSchema schema)
    {
        ArgumentNullException.ThrowIfNull(schema);
        moduleName = schema.GetModuleName();
        moduleNamespace = options.Namespace ?? NamespaceForModule(moduleName);
        localTypes.Clear();
        enumAliases.Clear();

        if (options.EmitValueSemantics)
            RegisterLocal("generated-helper:value-semantics", ValueSemanticsHelper);
        var moduleSymbol = ToIdentifier(schema.GetShortModuleName());
        RegisterLocal("module:" + moduleName, moduleSymbol);
        foreach (var alias in Ordered(schema.GetTypeAliases()))
        {
            var symbol = ToIdentifier(alias.Value.GetSimpleName());
            RegisterLocal("alias:" + alias.Value.GetQualifiedName(), symbol);
            if (StringLiteralValues(alias.Value.GetAliasedType()) is not null)
                enumAliases.Add(alias.Value.GetQualifiedName());
        }
        foreach (var pClass in Ordered(schema.GetClasses()))
            RegisterLocal("class:" + pClass.Value.GetQualifiedName(), ToIdentifier(pClass.Value.GetSimpleName()));
        ValidateTopLevelCollisions();

        var output = new StringBuilder("// <auto-generated />\n#nullable enable\n\n");
        output.Append("namespace ").Append(moduleNamespace).Append(";\n\n");
        if (options.EmitValueSemantics) EmitValueSemanticsHelper(output);
        foreach (var alias in Ordered(schema.GetTypeAliases())) EmitAlias(output, alias.Value);
        EmitClass(output, schema.GetModuleClass(), moduleSymbol, moduleClass: true);
        foreach (var pClass in Ordered(schema.GetClasses()))
            EmitClass(output, pClass.Value, LocalClassSymbol(pClass.Value), moduleClass: false);
        return output.ToString().TrimEnd('\n') + "\n";
    }

    private void RegisterLocal(string key, string symbol) => localTypes[key] = symbol;

    private static void EmitValueSemanticsHelper(StringBuilder output)
    {
        output.Append("""
file static class PklGeneratedModelSemantics
{
    public static bool ValueEquals(object? left, object? right)
    {
        if (global::System.Object.ReferenceEquals(left, right)) return true;
        if (left is null || right is null) return false;
        if (left is global::System.Text.RegularExpressions.Regex leftRegex &&
            right is global::System.Text.RegularExpressions.Regex rightRegex)
            return global::System.String.Equals(leftRegex.ToString(), rightRegex.ToString(),
                global::System.StringComparison.Ordinal);
        if (left is global::System.Collections.IDictionary leftDictionary &&
            right is global::System.Collections.IDictionary rightDictionary)
            return DictionaryEquals(leftDictionary, rightDictionary);
        if (left is global::System.Collections.IEnumerable leftEnumerable &&
            right is global::System.Collections.IEnumerable rightEnumerable &&
            left is not string && right is not string)
            return IsSet(left) && IsSet(right)
                ? SetEquals(leftEnumerable, rightEnumerable)
                : SequenceEquals(leftEnumerable, rightEnumerable);
        return left.Equals(right);
    }

    public static int ValueHash(object? value)
    {
        if (value is null) return 0;
        if (value is global::System.Text.RegularExpressions.Regex regex)
            return OrdinalHash(regex.ToString());
        if (value is global::System.Collections.IDictionary dictionary)
        {
            var dictionaryHash = dictionary.Count;
            foreach (global::System.Collections.DictionaryEntry entry in dictionary)
                dictionaryHash ^= unchecked((ValueHash(entry.Key) * 397) ^ ValueHash(entry.Value));
            return dictionaryHash;
        }
        if (value is global::System.Collections.IEnumerable enumerable && value is not string)
        {
            if (IsSet(value))
            {
                var setHash = 0;
                foreach (var item in enumerable) setHash ^= ValueHash(item);
                return setHash;
            }
            var sequenceHash = 1;
            foreach (var item in enumerable)
                sequenceHash = unchecked(31 * sequenceHash + ValueHash(item));
            return sequenceHash;
        }
        return value is string text ? OrdinalHash(text) : value.GetHashCode();
    }

    public static string Format(object? value)
    {
        if (value is null) return "null";
        if (value is global::System.Text.RegularExpressions.Regex regex) return regex.ToString();
        if (value is global::System.Collections.IDictionary dictionary)
        {
            var entries = new global::System.Collections.Generic.List<string>();
            foreach (global::System.Collections.DictionaryEntry entry in dictionary)
                entries.Add(Format(entry.Key) + "=" + Format(entry.Value));
            return "{" + string.Join(", ", entries) + "}";
        }
        if (value is global::System.Collections.IEnumerable enumerable && value is not string)
        {
            var items = new global::System.Collections.Generic.List<string>();
            foreach (var item in enumerable) items.Add(Format(item));
            return "[" + string.Join(", ", items) + "]";
        }
        return global::System.Convert.ToString(value,
            global::System.Globalization.CultureInfo.InvariantCulture) ?? "null";
    }

    private static bool DictionaryEquals(
        global::System.Collections.IDictionary left,
        global::System.Collections.IDictionary right)
    {
        if (left.Count != right.Count) return false;
        foreach (global::System.Collections.DictionaryEntry leftEntry in left)
        {
            var found = false;
            foreach (global::System.Collections.DictionaryEntry rightEntry in right)
            {
                if (!ValueEquals(leftEntry.Key, rightEntry.Key)) continue;
                if (!ValueEquals(leftEntry.Value, rightEntry.Value)) return false;
                found = true;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    private static bool SequenceEquals(
        global::System.Collections.IEnumerable left,
        global::System.Collections.IEnumerable right)
    {
        var leftEnumerator = left.GetEnumerator();
        var rightEnumerator = right.GetEnumerator();
        try
        {
            while (true)
            {
                var hasLeft = leftEnumerator.MoveNext();
                var hasRight = rightEnumerator.MoveNext();
                if (hasLeft != hasRight) return false;
                if (!hasLeft) return true;
                if (!ValueEquals(leftEnumerator.Current, rightEnumerator.Current)) return false;
            }
        }
        finally
        {
            (leftEnumerator as global::System.IDisposable)?.Dispose();
            (rightEnumerator as global::System.IDisposable)?.Dispose();
        }
    }

    private static bool SetEquals(
        global::System.Collections.IEnumerable left,
        global::System.Collections.IEnumerable right)
    {
        var remaining = new global::System.Collections.Generic.List<object?>();
        foreach (var item in right) remaining.Add(item);
        foreach (var leftItem in left)
        {
            var index = remaining.FindIndex(rightItem => ValueEquals(leftItem, rightItem));
            if (index < 0) return false;
            remaining.RemoveAt(index);
        }
        return remaining.Count == 0;
    }

    private static bool IsSet(object value)
    {
        foreach (var implemented in value.GetType().GetInterfaces())
        {
            if (!implemented.IsGenericType) continue;
            var definition = implemented.GetGenericTypeDefinition();
            if (definition == typeof(global::System.Collections.Generic.ISet<>) ||
                definition == typeof(global::System.Collections.Generic.IReadOnlySet<>)) return true;
        }
        return false;
    }

    private static int OrdinalHash(string value)
    {
        var result = unchecked((int)2166136261);
        foreach (var character in value) result = unchecked((result ^ character) * 16777619);
        return result;
    }
}

""");
    }

    private void ValidateTopLevelCollisions()
    {
        var collisions = localTypes.GroupBy(item => item.Value, StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => $"symbol collision `{group.Key}`: " +
                string.Join(", ", group.Select(item => item.Key).OrderBy(item => item, StringComparer.Ordinal)))
            .ToArray();
        if (collisions.Length != 0) throw new CSharpGenerationException(collisions);
    }

    private void EmitAlias(StringBuilder output, TypeAlias alias)
    {
        EmitDocs(output, alias.GetDocComment());
        EmitDeprecation(output, alias.GetAnnotations());
        var name = LocalAliasSymbol(alias);
        var literals = StringLiteralValues(alias.GetAliasedType());
        ValidateAliasMemberCollisions(alias, name, literals);
        output.Append("[global::Pkl.Core.PklName(\"").Append(Escape(alias.GetSimpleName())).Append("\")]\n");
        output.Append("[global::Pkl.Core.PklQualifiedName(\"").Append(Escape(alias.GetQualifiedName()))
            .Append("\")]\n");
        if (literals is not null)
        {
            output.Append("public enum ").Append(name).Append("\n{\n");
            foreach (var value in literals)
                output.Append("    [global::Pkl.Core.PklName(\"").Append(Escape(value)).Append("\")]\n")
                    .Append("    ").Append(ToIdentifier(value)).Append(",\n");
            output.Append("}\n\n");
            return;
        }

        output.Append("[global::Pkl.Core.PklTypeAlias]\npublic readonly partial record struct ")
            .Append(name).Append(TypeParameters(alias.GetTypeParameters())).Append('(')
            .Append(TypeName(alias.GetAliasedType())).Append(" Value)\n{\n");
        EmitAliasValueSemantics(output, name + TypeArguments(alias.GetTypeParameters()));
        EmitLoader(output, name + TypeArguments(alias.GetTypeParameters()), moduleClass: false,
            hidesBaseMembers: false, indent: "    ");
        output.Append("}\n\n");
    }

    private void EmitAliasValueSemantics(StringBuilder output, string typeName)
    {
        if (!options.EmitValueSemantics) return;
        output.Append("    public bool Equals(").Append(typeName).Append(" other) =>\n")
            .Append("        ").Append(ValueSemanticsHelper).Append(".ValueEquals(Value, other.Value);\n\n")
            .Append("    public override int GetHashCode() => ")
            .Append(ValueSemanticsHelper).Append(".ValueHash(Value);\n\n")
            .Append("    public override string ToString() => \"").Append(typeName)
            .Append(" { Value = \" + ").Append(ValueSemanticsHelper).Append(".Format(Value) + \" }\";\n\n");
    }

    private void ValidateAliasMemberCollisions(
        TypeAlias alias, string name, IReadOnlyList<string>? literals)
    {
        var declarations = new List<MemberDeclaration>
        {
            new(name, $"containing type `{name}`", IsInherited: false, PklName: null)
        };
        if (literals is not null)
        {
            declarations.AddRange(literals.Select(value => new MemberDeclaration(
                ToIdentifier(value), $"enum value `{value}`", IsInherited: false, PklName: value)));
        }
        else
        {
            declarations.Add(new("Value", "generated property `Value`", IsInherited: false, PklName: null));
            AddGeneratedValueDeclarations(declarations);
            AddGeneratedLoaderDeclarations(declarations, moduleClass: false);
        }
        ThrowMemberCollisions($"type alias `{alias.GetQualifiedName()}`", declarations);
    }

    private void EmitClass(StringBuilder output, PClass pClass, string name, bool moduleClass)
    {
        EmitDocs(output, pClass.GetDocComment());
        EmitDeprecation(output, pClass.GetAnnotations());
        output.Append("[global::Pkl.Core.PklName(\"").Append(Escape(moduleClass ? moduleName : pClass.GetSimpleName()))
            .Append("\")]\n");
        output.Append("[global::Pkl.Core.PklQualifiedName(\"").Append(Escape(pClass.GetQualifiedName()))
            .Append("\")]\n");
        var typeParameters = moduleClass ? "" : TypeParameters(pClass.GetTypeParameters());
        output.Append("public ");
        if (pClass.IsAbstract()) output.Append("abstract ");
        else if (!pClass.IsOpen()) output.Append("sealed ");
        output.Append("partial class ").Append(name).Append(typeParameters);
        var baseType = BaseType(pClass);
        if (baseType is not null) output.Append(" : ").Append(baseType);
        output.Append("\n{\n");

        var properties = Ordered(pClass.GetProperties()).Where(entry => !entry.Value.IsHidden() && !entry.Value.IsExternal()).ToArray();
        ValidateMemberCollisions(pClass, name, properties, moduleClass);
        foreach (var entry in properties)
        {
            var property = entry.Value;
            EmitDocs(output, property.GetInheritedDocComment(), "    ");
            EmitDeprecation(output, property.GetAnnotations(), "    ");
            output.Append("    [global::Pkl.Core.PklName(\"").Append(Escape(property.GetSimpleName())).Append("\")]\n")
                .Append("    [global::Pkl.Core.PklRequired]\n")
                .Append("    public ");
            if (HasInheritedProperty(pClass, property.GetSimpleName())) output.Append("new ");
            output.Append(TypeName(property.GetType())).Append(' ')
                .Append(ToIdentifier(property.GetSimpleName())).Append(" { get; }\n\n");
        }
        EmitConstructor(output, pClass, name, properties, baseType is not null);
        EmitClassValueSemantics(output, pClass, name, moduleClass);
        EmitLoader(output, name + typeParameters, moduleClass, baseType is not null, "    ");
        output.Append("}\n\n");
    }

    private void ValidateMemberCollisions(PClass owner, string name,
        IReadOnlyList<KeyValuePair<string, PClass.Property>> properties, bool moduleClass)
    {
        var declarations = new List<MemberDeclaration>
        {
            new(name, $"containing type `{name}`", IsInherited: false, PklName: null)
        };
        declarations.AddRange(properties.Select(entry => new MemberDeclaration(
            ToIdentifier(entry.Value.GetSimpleName()), $"property `{entry.Value.GetSimpleName()}`",
            IsInherited: false, PklName: entry.Value.GetSimpleName())));
        AddGeneratedValueDeclarations(declarations);
        AddGeneratedLoaderDeclarations(declarations, moduleClass);

        for (var superclass = owner.GetSuperclass();
             superclass is not null && !superclass.GetInfo().IsStandardLibraryClass();
             superclass = superclass.GetSuperclass())
        {
            var inheritedOwner = superclass;
            declarations.AddRange(Ordered(inheritedOwner.GetProperties())
                .Where(entry => !entry.Value.IsHidden() && !entry.Value.IsExternal())
                .Select(entry => new MemberDeclaration(
                    ToIdentifier(entry.Value.GetSimpleName()),
                    $"inherited property `{entry.Value.GetSimpleName()}` from `{inheritedOwner.GetQualifiedName()}`",
                    IsInherited: true, PklName: entry.Value.GetSimpleName())));
        }
        ThrowMemberCollisions($"`{owner.GetQualifiedName()}`", declarations);
    }

    private void EmitConstructor(
        StringBuilder output,
        PClass pClass,
        string name,
        IReadOnlyList<KeyValuePair<string, PClass.Property>> declaredProperties,
        bool hasBaseType)
    {
        var allProperties = VisibleProperties(pClass.GetAllProperties());
        output.Append("    ").Append(pClass.IsAbstract() ? "protected " : "public ")
            .Append(name).Append('(');
        for (var index = 0; index < allProperties.Length; index++)
        {
            var property = allProperties[index].Value;
            if (index != 0) output.Append(", ");
            output.Append("[global::Pkl.Core.PklName(\"").Append(Escape(property.GetSimpleName()))
                .Append("\")] ").Append(TypeName(property.GetType())).Append(' ')
                .Append(ParameterIdentifier(property));
        }
        output.Append(')');
        if (hasBaseType)
        {
            var superclass = pClass.GetSuperclass()!;
            var baseProperties = VisibleProperties(superclass.GetAllProperties());
            output.Append("\n        : base(")
                .Append(string.Join(", ", baseProperties.Select(entry =>
                    ParameterIdentifier(allProperties.Single(item => string.Equals(
                        item.Value.GetSimpleName(), entry.Value.GetSimpleName(), StringComparison.Ordinal)).Value))))
                .Append(')');
        }
        if (declaredProperties.Count == 0)
        {
            output.Append(" { }\n\n");
            return;
        }
        output.Append("\n    {\n");
        foreach (var entry in declaredProperties)
        {
            var property = entry.Value;
            output.Append("        ").Append(ToIdentifier(property.GetSimpleName())).Append(" = ")
                .Append(ParameterIdentifier(property)).Append(";\n");
        }
        output.Append("    }\n\n");
    }

    private void EmitClassValueSemantics(
        StringBuilder output, PClass pClass, string name, bool moduleClass)
    {
        if (!options.EmitValueSemantics) return;
        var properties = VisibleProperties(pClass.GetAllProperties());
        var typeName = name + (moduleClass ? "" : TypeArguments(pClass.GetTypeParameters()));
        output.Append("    public override bool Equals(object? obj)\n    {\n")
            .Append("        if (global::System.Object.ReferenceEquals(this, obj)) return true;\n")
            .Append("        if (obj is not ").Append(typeName)
            .Append(" other || obj.GetType() != GetType()) return false;\n");
        if (properties.Length == 0)
        {
            output.Append("        return true;\n");
        }
        else
        {
            output.Append("        return ");
            for (var index = 0; index < properties.Length; index++)
            {
                if (index != 0) output.Append(" &&\n            ");
                var symbol = ToIdentifier(properties[index].Value.GetSimpleName());
                output.Append(ValueSemanticsHelper).Append(".ValueEquals(")
                    .Append(symbol).Append(", other.").Append(symbol).Append(')');
            }
            output.Append(";\n");
        }
        output.Append("    }\n\n")
            .Append("    public override int GetHashCode()\n    {\n")
            .Append("        var result = 1;\n");
        foreach (var entry in properties)
        {
            var symbol = ToIdentifier(entry.Value.GetSimpleName());
            output.Append("        result = unchecked(31 * result + ").Append(ValueSemanticsHelper)
                .Append(".ValueHash(").Append(symbol).Append("));\n");
        }
        output.Append("        return result;\n    }\n\n")
            .Append("    public override string ToString()\n    {\n")
            .Append("        var builder = new global::System.Text.StringBuilder(\"")
            .Append(Escape(name)).Append(" {\");\n");
        foreach (var entry in properties)
        {
            var property = entry.Value;
            output.Append("        builder.Append(\"\\n    ").Append(Escape(property.GetSimpleName()))
                .Append(" = \").Append(").Append(ValueSemanticsHelper).Append(".Format(")
                .Append(ToIdentifier(property.GetSimpleName())).Append("));\n");
        }
        output.Append("        return builder.Append(\"\\n}\").ToString();\n")
            .Append("    }\n\n");
    }

    private static KeyValuePair<string, PClass.Property>[] VisibleProperties(
        IReadOnlyDictionary<string, PClass.Property> properties) =>
        Ordered(properties).Where(entry => !entry.Value.IsHidden() && !entry.Value.IsExternal()).ToArray();

    private static string ParameterIdentifier(PClass.Property property) =>
        "pkl" + ToIdentifier(property.GetSimpleName()).TrimStart('@');

    private static bool HasInheritedProperty(PClass owner, string pklName)
    {
        for (var superclass = owner.GetSuperclass();
             superclass is not null && !superclass.GetInfo().IsStandardLibraryClass();
             superclass = superclass.GetSuperclass())
            if (superclass.GetProperties().Values.Any(property =>
                    string.Equals(property.GetSimpleName(), pklName, StringComparison.Ordinal)))
                return true;
        return false;
    }

    private void AddGeneratedLoaderDeclarations(
        ICollection<MemberDeclaration> declarations, bool moduleClass)
    {
        if (!options.EmitGeneratedLoaders) return;
        declarations.Add(new("PklLoader", "generated property `PklLoader`", IsInherited: false, PklName: null));
        declarations.Add(new("GeneratedLoader", "generated nested type `GeneratedLoader`", IsInherited: false, PklName: null));
        declarations.Add(new("FromPkl", "generated method `FromPkl`", IsInherited: false, PklName: null));
        if (moduleClass)
            declarations.Add(new("Load", "generated method `Load`", IsInherited: false, PklName: null));
    }

    private void AddGeneratedValueDeclarations(ICollection<MemberDeclaration> declarations)
    {
        if (!options.EmitValueSemantics) return;
        declarations.Add(new("Equals", "generated method `Equals`", IsInherited: false, PklName: null));
        declarations.Add(new("GetHashCode", "generated method `GetHashCode`", IsInherited: false, PklName: null));
        declarations.Add(new("ToString", "generated method `ToString`", IsInherited: false, PklName: null));
    }

    private static void ThrowMemberCollisions(
        string owner, IEnumerable<MemberDeclaration> declarations)
    {
        var collisions = declarations.GroupBy(item => item.Symbol, StringComparer.Ordinal)
            .Where(group =>
            {
                var current = group.Where(item => !item.IsInherited).ToArray();
                var inherited = group.Where(item => item.IsInherited).ToArray();
                return current.Length > 1 || current.Any(candidate => inherited.Any(prior =>
                    !string.Equals(candidate.PklName, prior.PklName, StringComparison.Ordinal)));
            })
            .Select(group => $"member collision in {owner} for `{group.Key}`: " +
                string.Join(", ", group.Select(item => item.Description)
                    .OrderBy(item => item, StringComparer.Ordinal)))
            .ToArray();
        if (collisions.Length != 0) throw new CSharpGenerationException(collisions);
    }

    private void EmitLoader(StringBuilder output, string typeName, bool moduleClass,
        bool hidesBaseMembers, string indent)
    {
        if (!options.EmitGeneratedLoaders) return;
        var hiding = hidesBaseMembers ? "new " : "";
        output.Append(indent).Append("public static ").Append(hiding)
            .Append("global::Pkl.Core.IPklGeneratedLoader<").Append(typeName)
            .Append("> PklLoader { get; } = new GeneratedLoader();\n\n")
            .Append(indent).Append("private sealed class GeneratedLoader : global::Pkl.Core.IPklGeneratedLoader<")
            .Append(typeName).Append(">\n").Append(indent).Append("{\n")
            .Append(indent).Append("    public ").Append(typeName)
            .Append(" Load(object? value, global::Pkl.Core.ConfigBinder binder) => ")
            .Append("binder.BindGenerated<").Append(typeName).Append(">(value);\n")
            .Append(indent).Append("}\n\n")
            .Append(indent).Append("public static ").Append(hiding).Append(typeName)
            .Append(" FromPkl(object? value, global::Pkl.Core.ConfigBinder? binder = null) =>\n")
            .Append(indent).Append("    (binder ?? new global::Pkl.Core.ConfigBinder()).BindGenerated<")
            .Append(typeName).Append(">(value);\n");
        if (moduleClass)
            output.Append("\n").Append(indent).Append("public static ").Append(hiding).Append(typeName)
                .Append(" Load(global::Pkl.Core.Evaluator evaluator, global::Pkl.Core.ModuleSource source, ")
                .Append("global::Pkl.Core.ConfigBinder? binder = null) =>\n")
                .Append(indent).Append("    FromPkl(evaluator.Evaluate(source), binder);\n");
    }

    private string? BaseType(PClass pClass)
    {
        var supertype = pClass.GetSupertype();
        if (supertype is null || supertype is not PType.Class classType) return null;
        var superclass = classType.GetPClass();
        return superclass.GetInfo().IsStandardLibraryClass() ? null : TypeName(supertype);
    }

    private string TypeName(PType type)
    {
        if (ReferenceEquals(type, PType.UNKNOWN)) return "object?";
        if (ReferenceEquals(type, PType.MODULE)) return "object";
        if (ReferenceEquals(type, PType.NOTHING)) return "object";
        if (type is PType.Nullable nullable) return MakeNullable(TypeName(nullable.GetBaseType()));
        if (type is PType.Constrained constrained) return TypeName(constrained.GetBaseType());
        if (type is PType.Alias alias) return AliasTypeName(alias);
        if (type is PType.StringLiteral) return "string";
        if (type is PType.TypeVariable variable) return ToIdentifier(variable.GetName());
        if (type is PType.Function) return "global::System.Delegate";
        if (type is PType.Union union) return UnionTypeName(union);
        if (type is not PType.Class classType) return "object";
        var pClass = classType.GetPClass();
        var arguments = classType.GetTypeArguments();
        return pClass.GetQualifiedName() switch
        {
            "pkl.base#String" => "string",
            "pkl.base#Boolean" => "bool",
            "pkl.base#Int" => "long",
            "pkl.base#Float" or "pkl.base#Number" => "double",
            "pkl.base#Duration" => "global::Pkl.Core.Duration",
            "pkl.base#DurationUnit" => "global::Pkl.Core.DurationUnit",
            "pkl.base#DataSize" => "global::Pkl.Core.DataSize",
            "pkl.base#DataSizeUnit" => "global::Pkl.Core.DataSizeUnit",
            "pkl.base#Regex" => "global::System.Text.RegularExpressions.Regex",
            "pkl.base#Bytes" => "byte[]",
            "pkl.base#Collection" => $"global::System.Collections.Generic.IReadOnlyCollection<{Argument(arguments, 0)}>",
            "pkl.base#List" or "pkl.base#Listing" =>
                $"global::System.Collections.Generic.IReadOnlyList<{Argument(arguments, 0)}>",
            "pkl.base#Set" => $"global::System.Collections.Generic.IReadOnlySet<{Argument(arguments, 0)}>",
            "pkl.base#Map" or "pkl.base#Mapping" =>
                $"global::System.Collections.Generic.IReadOnlyDictionary<{Argument(arguments, 0)}, {Argument(arguments, 1)}>",
            "pkl.base#Pair" => $"global::Pkl.Core.Pair<{Argument(arguments, 0)}, {Argument(arguments, 1)}>",
            "pkl.base#Any" or "pkl.base#Dynamic" => "object?",
            "pkl.base#Typed" or "pkl.base#NonNull" => "object",
            "pkl.base#Null" => "object?",
            _ => ClassTypeName(pClass, arguments)
        };
    }

    private string AliasTypeName(PType.Alias alias)
    {
        var typeAlias = alias.GetTypeAlias();
        var name = typeAlias.GetModuleName() == moduleName ? LocalAliasSymbol(typeAlias) :
            "global::" + NamespaceForModule(typeAlias.GetModuleName()) + "." + ToIdentifier(typeAlias.GetSimpleName());
        if (enumAliases.Contains(typeAlias.GetQualifiedName())) return name;
        var arguments = alias.GetTypeArguments();
        return arguments.Count == 0 ? name : name + "<" + string.Join(", ", arguments.Select(TypeName)) + ">";
    }

    private string ClassTypeName(PClass pClass, IReadOnlyList<PType> arguments)
    {
        var name = pClass.GetModuleName() == moduleName ? LocalClassSymbol(pClass) :
            "global::" + NamespaceForModule(pClass.GetModuleName()) + "." +
            ToIdentifier(pClass.IsModuleClass() ? LastModulePart(pClass.GetModuleName()) : pClass.GetSimpleName());
        return arguments.Count == 0 ? name : name + "<" + string.Join(", ", arguments.Select(TypeName)) + ">";
    }

    private string UnionTypeName(PType.Union union)
    {
        var elements = union.GetElementTypes();
        if (elements.All(item => item is PType.StringLiteral)) return "string";
        var nonNull = elements.Where(item => !IsNullType(item)).ToArray();
        if (nonNull.Length == 1 && nonNull.Length != elements.Count) return MakeNullable(TypeName(nonNull[0]));
        var names = nonNull.Select(TypeName).Distinct(StringComparer.Ordinal).ToArray();
        return names.Length == 1 ? names[0] : "object";
    }

    private static bool IsNullType(PType type) => type is PType.Class classType &&
        classType.GetPClass().GetQualifiedName() == "pkl.base#Null";
    private static string MakeNullable(string type) => type.EndsWith("?", StringComparison.Ordinal) ? type : type + "?";
    private string Argument(IReadOnlyList<PType> arguments, int index) => index < arguments.Count ? TypeName(arguments[index]) : "object";

    private static IReadOnlyList<string>? StringLiteralValues(PType type)
    {
        if (type is PType.Constrained constrained) return StringLiteralValues(constrained.GetBaseType());
        if (type is PType.Union union && union.GetElementTypes().All(item => item is PType.StringLiteral))
            return union.GetElementTypes().Cast<PType.StringLiteral>().Select(item => item.GetLiteral()).ToArray();
        if (type is PType.StringLiteral literal) return new[] { literal.GetLiteral() };
        return null;
    }

    private string LocalAliasSymbol(TypeAlias alias) =>
        localTypes.TryGetValue("alias:" + alias.GetQualifiedName(), out var symbol) ? symbol : ToIdentifier(alias.GetSimpleName());
    private string LocalClassSymbol(PClass pClass) => pClass.IsModuleClass()
        ? localTypes["module:" + moduleName]
        : localTypes.TryGetValue("class:" + pClass.GetQualifiedName(), out var symbol) ? symbol : ToIdentifier(pClass.GetSimpleName());
    private string NamespaceForModule(string name) => options.NamespaceMappings.TryGetValue(name, out var mapped) ? mapped :
        string.Join(".", name.Split('.', StringSplitOptions.RemoveEmptyEntries).Select(ToIdentifier));
    private static string LastModulePart(string name) => name.Split('.', StringSplitOptions.RemoveEmptyEntries).LastOrDefault() ?? "Module";
    private static IEnumerable<KeyValuePair<string, T>> Ordered<T>(IEnumerable<KeyValuePair<string, T>> values) =>
        values.OrderBy(entry => entry.Value is Member member ? member.GetSourceLocation().StartLine : int.MaxValue)
            .ThenBy(entry => entry.Key, StringComparer.Ordinal);

    private static string TypeParameters(IReadOnlyList<TypeParameter> parameters) => parameters.Count == 0 ? "" :
        "<" + string.Join(", ", parameters.Select(item => ToIdentifier(item.GetName()))) + ">";
    private static string TypeArguments(IReadOnlyList<TypeParameter> parameters) => TypeParameters(parameters);

    private void EmitDocs(StringBuilder output, string? comment, string indent = "")
    {
        if (!options.EmitDocComments || string.IsNullOrWhiteSpace(comment)) return;
        output.Append(indent).Append("/// <summary>\n");
        foreach (var line in comment.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n'))
            output.Append(indent).Append("/// ").Append(System.Security.SecurityElement.Escape(line.Trim())).Append("\n");
        output.Append(indent).Append("/// </summary>\n");
    }

    private static void EmitDeprecation(
        StringBuilder output, IReadOnlyList<PObject> annotations, string indent = "")
    {
        var annotation = annotations.FirstOrDefault(item =>
            string.Equals(item.GetClassInfo().GetQualifiedName(), "pkl.base#Deprecated",
                StringComparison.Ordinal));
        if (annotation is null) return;
        var properties = annotation.GetProperties();
        if (properties.TryGetValue("message", out var value) && value is string message)
            output.Append(indent).Append("[global::System.Obsolete(\"")
                .Append(Escape(message)).Append("\")]\n");
        else
            output.Append(indent).Append("[global::System.Obsolete]\n");
    }

    internal static string ToIdentifier(string value)
    {
        var output = new StringBuilder();
        var capitalize = true;
        foreach (var character in value)
        {
            if (character is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9')
            {
                output.Append(capitalize ? char.ToUpperInvariant(character) : character);
                capitalize = false;
            }
            else if (character == '_') capitalize = true;
            else if (character <= 127) capitalize = true;
            else
            {
                output.Append('U').Append(((int)character).ToString("X4", CultureInfo.InvariantCulture));
                capitalize = true;
            }
        }
        if (output.Length == 0) output.Append("Value");
        if (char.IsDigit(output[0])) output.Insert(0, '_');
        var result = output.ToString();
        return Keywords.Contains(result) ? "@" + result : result;
    }

    private static string Escape(string value) => value.Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("\"", "\\\"", StringComparison.Ordinal)
        .Replace("\r", "\\r", StringComparison.Ordinal).Replace("\n", "\\n", StringComparison.Ordinal);
}
