// Idiomatic .NET configuration binding and deterministic C# schema generation
// for Pkl.Core. This is durable destination product code; translated Java
// output remains disposable.
#nullable enable

using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
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
}

public interface IPklGeneratedLoader<out T>
{
    T Load(object? value, ConfigBinder binder);
}

public sealed class ConfigBinderOptions
{
    private readonly Dictionary<Type, object> generatedLoaders = new();

    public bool IgnoreUnknownProperties { get; set; }
    public bool PropertyNamesCaseInsensitive { get; set; }
    public bool UseGeneratedLoaders { get; set; } = true;

    public ConfigBinderOptions AddGeneratedLoader<T>(IPklGeneratedLoader<T> loader)
    {
        ArgumentNullException.ThrowIfNull(loader);
        generatedLoaders[typeof(T)] = loader;
        return this;
    }

    internal bool TryGetGeneratedLoader(Type type, out object loader) =>
        generatedLoaders.TryGetValue(type, out loader!);
}

public sealed class ConfigBinder
{
    private static readonly NullabilityInfoContext Nullability = new();
    private readonly ConfigBinderOptions options;

    public ConfigBinder(ConfigBinderOptions? options = null) =>
        this.options = options ?? new ConfigBinderOptions();

    public ConfigBinderOptions Options => options;

    public T Bind<T>(object? value) =>
        (T)BindCore(value, typeof(T), "$", new HashSet<object>(ReferenceEqualityComparer.Instance),
            allowsNull: !typeof(T).IsValueType, skipGeneratedLoader: false)!;

    public T BindGenerated<T>(object? value) =>
        (T)BindCore(value, typeof(T), "$", new HashSet<object>(ReferenceEqualityComparer.Instance),
            allowsNull: !typeof(T).IsValueType, skipGeneratedLoader: true)!;

    public object? Bind(object? value, Type targetType)
    {
        ArgumentNullException.ThrowIfNull(targetType);
        return BindCore(value, targetType, "$", new HashSet<object>(ReferenceEqualityComparer.Instance),
            allowsNull: !targetType.IsValueType, skipGeneratedLoader: false);
    }

    private object? BindCore(object? value, Type targetType, string path, ISet<object> active,
        bool allowsNull, bool skipGeneratedLoader = false)
    {
        if (!skipGeneratedLoader && TryGetGeneratedLoader(targetType, out var loader))
            return InvokeGeneratedLoader(loader, value, targetType, path);

        var nullableType = Nullable.GetUnderlyingType(targetType);
        if (value is null || ReferenceEquals(value, PNull.GetInstance()))
        {
            if (nullableType is not null || allowsNull) return null;
            throw Error(path, value, targetType, "null is not allowed");
        }

        if (nullableType is not null)
            return BindCore(value, nullableType, path, active, allowsNull: false);
        if (targetType == typeof(object) || targetType.IsInstanceOfType(value)) return value;

        if (targetType.GetCustomAttribute<PklTypeAliasAttribute>() is not null)
            return BindAlias(value, targetType, path, active);
        if (targetType == typeof(string))
            return value is string text ? text : throw Error(path, value, targetType, "the source is not a string");
        if (targetType == typeof(Uri))
            return value is string uri ? CreateUri(uri, path, targetType) :
                throw Error(path, value, targetType, "the source is not a string URI");
        if (targetType == typeof(Regex))
            return value is string pattern ? CreateRegex(pattern, path, targetType) :
                throw Error(path, value, targetType, "the source is not a Regex or string pattern");
        if (targetType == typeof(byte[]) && value is sbyte[] signedBytes)
            return signedBytes.Select(item => unchecked((byte)item)).ToArray();
        if (targetType == typeof(sbyte[]) && value is byte[] bytes)
            return bytes.Select(item => unchecked((sbyte)item)).ToArray();
        if (targetType.IsEnum) return BindEnum(value, targetType, path);
        if (IsNumeric(targetType)) return BindNumber(value, targetType, path);
        if (targetType == typeof(bool))
            return value is bool boolean ? boolean : throw Error(path, value, targetType, "the source is not a Boolean");
        if (targetType == typeof(char))
            return value is string character && character.Length == 1 ? character[0] :
                throw Error(path, value, targetType, "the source is not a single-character string");

        if (TryGetPairTypes(targetType, out var firstType, out var secondType))
            return Track(value, targetType, path, active,
                () => BindPair(value, targetType, firstType, secondType, path, active));
        if (TryGetDictionaryTypes(targetType, out var keyType, out var valueType))
            return Track(value, targetType, path, active,
                () => BindDictionary(value, targetType, keyType, valueType, path, active));
        if (TryGetCollectionElementType(targetType, out var elementType))
            return Track(value, targetType, path, active,
                () => BindCollection(value, targetType, elementType, path, active));

        if (!TryGetProperties(value, out var properties))
            throw Error(path, value, targetType, "the source is not a Pkl object or string-keyed mapping");
        return Track(value, targetType, path, active,
            () => BindObject(properties, targetType, path, active));
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
            return typeof(IPklGeneratedLoader<>).MakeGenericType(targetType)
                .GetMethod(nameof(IPklGeneratedLoader<object>.Load))!
                .Invoke(loader, new object?[] { value, this });
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            throw new PklBindException($"Generated loader for {targetType.FullName} failed at {path}: " +
                error.InnerException.Message, error.InnerException);
        }
    }

    private object BindAlias(object value, Type targetType, string path, ISet<object> active)
    {
        var constructor = targetType.GetConstructors(BindingFlags.Public | BindingFlags.Instance)
            .SingleOrDefault(item => item.GetParameters().Length == 1);
        if (constructor is null)
            throw Error(path, value, targetType, "a Pkl type-alias target must have exactly one public single-argument constructor");
        var parameter = constructor.GetParameters()[0];
        var converted = BindCore(value, parameter.ParameterType, path, active,
            AllowsNull(parameter), skipGeneratedLoader: false);
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
        var instance = ConstructObject(source, consumed, targetType, path, active);

        var members = WritableMembers(targetType, path);
        foreach (var member in members)
        {
            var name = PklName(member);
            if (!source.TryGetValue(name, out var item))
            {
                if (IsRequired(member))
                    throw new PklBindException($"Cannot bind {path} to {targetType.FullName}: missing required Pkl property `{name}`.");
                continue;
            }
            consumed.Add(name);
            switch (member)
            {
                case PropertyInfo property:
                    property.SetValue(instance, BindCore(item, property.PropertyType, path + "." + name,
                        active, AllowsNull(property)));
                    break;
                case FieldInfo field:
                    field.SetValue(instance, BindCore(item, field.FieldType, path + "." + name,
                        active, AllowsNull(field)));
                    break;
            }
        }

        if (!options.IgnoreUnknownProperties)
        {
            var unknown = source.Keys.Where(name => !consumed.Contains(name))
                .OrderBy(name => name, StringComparer.Ordinal).ToArray();
            if (unknown.Length != 0)
                throw new PklBindException($"Cannot bind {path} to {targetType.FullName}: unknown Pkl properties: " +
                    string.Join(", ", unknown.Select(name => "`" + name + "`")) + ".");
        }
        return instance;
    }

    private static Dictionary<string, object?> CopyProperties(IReadOnlyDictionary<string, object?> properties,
        StringComparer comparer, string path, Type targetType)
    {
        var result = new Dictionary<string, object?>(comparer);
        foreach (var entry in properties.OrderBy(item => item.Key, StringComparer.Ordinal))
            if (!result.TryAdd(entry.Key, entry.Value))
                throw new PklBindException($"Cannot bind {path} to {targetType.FullName}: property name `{entry.Key}` " +
                    "is ambiguous under the configured comparison.");
        return result;
    }

    private object ConstructObject(IReadOnlyDictionary<string, object?> source, ISet<string> consumed,
        Type targetType, string path, ISet<object> active)
    {
        var constructors = targetType.GetConstructors(BindingFlags.Instance | BindingFlags.Public)
            .OrderByDescending(item => item.GetParameters().Length)
            .ThenBy(ConstructorSignature, StringComparer.Ordinal).ToArray();
        foreach (var constructor in constructors)
        {
            var parameters = constructor.GetParameters();
            if (parameters.Any(parameter => !source.ContainsKey(PklName(parameter)) && !parameter.HasDefaultValue))
                continue;
            try
            {
                var arguments = parameters.Select(parameter =>
                {
                    var name = PklName(parameter);
                    if (!source.TryGetValue(name, out var item)) return parameter.DefaultValue;
                    consumed.Add(name);
                    return BindCore(item, parameter.ParameterType, path + "." + name, active,
                        AllowsNull(parameter));
                }).ToArray();
                return constructor.Invoke(arguments);
            }
            catch (PklBindException) { throw; }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            { throw new PklBindException($"Constructor for {targetType.FullName} failed at {path}.", error.InnerException); }
        }
        var required = constructors.SelectMany(item => item.GetParameters())
            .Select(PklName).Distinct(StringComparer.Ordinal).OrderBy(name => name, StringComparer.Ordinal);
        throw new PklBindException($"Cannot bind {path} to {targetType.FullName}: no public constructor matches; " +
            "constructor properties are [" + string.Join(", ", required) + "].");
    }

    private static IReadOnlyList<MemberInfo> WritableMembers(Type targetType, string path)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public;
        var candidates = targetType.GetProperties(flags)
            .Where(item => item.SetMethod is not null && item.GetIndexParameters().Length == 0 &&
                           item.GetCustomAttribute<PklIgnoreAttribute>() is null)
            .Cast<MemberInfo>()
            .Concat(targetType.GetFields(flags).Where(item => !item.IsInitOnly &&
                item.GetCustomAttribute<PklIgnoreAttribute>() is null))
            .OrderByDescending(item => InheritanceDepth(item.DeclaringType))
            .ThenBy(item => item.Name, StringComparer.Ordinal).ToArray();
        var selected = new Dictionary<string, MemberInfo>(StringComparer.Ordinal);
        foreach (var member in candidates)
        {
            var name = PklName(member);
            if (!selected.TryGetValue(name, out var prior)) selected[name] = member;
            else if (prior.DeclaringType == member.DeclaringType)
                throw new PklBindException($"Cannot bind {path} to {targetType.FullName}: multiple writable members map to `{name}`.");
        }
        return selected.Values.OrderBy(PklName, StringComparer.Ordinal).ToArray();
    }

    private object BindCollection(object value, Type targetType, Type elementType, string path, ISet<object> active)
    {
        if (value is not IEnumerable source || value is string || value is IDictionary)
            throw Error(path, value, targetType, "the source is not a collection");
        var items = source.Cast<object?>().Select((item, index) =>
            BindCore(item, elementType, $"{path}[{index}]", active, !elementType.IsValueType)).ToArray();
        if (targetType.IsArray)
        {
            var result = Array.CreateInstance(elementType, items.Length);
            for (var index = 0; index < items.Length; index++) result.SetValue(items[index], index);
            return result;
        }
        var concrete = !targetType.IsInterface && !targetType.IsAbstract ? targetType :
            IsSetType(targetType) ? typeof(HashSet<>).MakeGenericType(elementType) :
            typeof(List<>).MakeGenericType(elementType);
        var collection = Activator.CreateInstance(concrete) ??
            throw Error(path, value, targetType, "the target collection could not be created");
        var add = concrete.GetMethod("Add", new[] { elementType }) ?? concrete.GetInterfaces()
            .Select(type => type.GetMethod("Add", new[] { elementType })).FirstOrDefault(method => method is not null);
        if (add is null) throw Error(path, value, targetType, "the target collection has no compatible Add method");
        foreach (var item in items) add.Invoke(collection, new[] { item });
        return collection;
    }

    private object BindDictionary(object value, Type targetType, Type keyType, Type valueType,
        string path, ISet<object> active)
    {
        if (value is not IDictionary source)
            throw Error(path, value, targetType, "the source is not a mapping");
        var concrete = !targetType.IsInterface && !targetType.IsAbstract ? targetType :
            typeof(Dictionary<,>).MakeGenericType(keyType, valueType);
        if (Activator.CreateInstance(concrete) is not IDictionary result)
            throw Error(path, value, targetType, "the target dictionary could not be created");
        var index = 0;
        foreach (DictionaryEntry entry in source)
        {
            var key = BindCore(entry.Key, keyType, $"{path}{{key:{index}}}", active, !keyType.IsValueType);
            var itemPath = path + "[" + FormatPathKey(entry.Key) + "]";
            result.Add(key!, BindCore(entry.Value, valueType, itemPath, active, !valueType.IsValueType));
            index++;
        }
        return result;
    }

    private object BindPair(object value, Type targetType, Type firstType, Type secondType,
        string path, ISet<object> active)
    {
        var valueType = value.GetType();
        var firstMethod = valueType.GetMethod("GetFirst", BindingFlags.Public | BindingFlags.Instance);
        var secondMethod = valueType.GetMethod("GetSecond", BindingFlags.Public | BindingFlags.Instance);
        if (firstMethod is null || secondMethod is null)
            throw Error(path, value, targetType, "the source is not a Pkl Pair");
        var first = BindCore(firstMethod.Invoke(value, null), firstType, path + ".first", active, !firstType.IsValueType);
        var second = BindCore(secondMethod.Invoke(value, null), secondType, path + ".second", active, !secondType.IsValueType);
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

    private static object BindNumber(object value, Type targetType, string path)
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

    private static object ConvertInteger(long value, Type targetType)
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
            if (targetType == typeof(decimal)) return (decimal)value;
        }
        if (targetType == typeof(double))
        {
            var result = (double)value;
            if ((long)result != value) throw new ArithmeticException("the conversion would lose integer precision");
            return result;
        }
        if (targetType == typeof(float))
        {
            var result = (float)value;
            if ((long)result != value) throw new ArithmeticException("the conversion would lose integer precision");
            return result;
        }
        throw new InvalidOperationException("unsupported numeric target");
    }

    private static object ConvertFloating(double value, Type targetType)
    {
        if (targetType == typeof(double)) return value;
        if (targetType == typeof(float))
        {
            var result = checked((float)value);
            if ((double)result != value) throw new ArithmeticException("the conversion would lose floating-point precision");
            return result;
        }
        if (!double.IsFinite(value) || Math.Truncate(value) != value)
            throw new ArithmeticException("a non-integral Pkl Float cannot be converted to an integral target");
        if (value < long.MinValue || value > long.MaxValue)
            throw new OverflowException();
        return ConvertInteger((long)value, targetType);
    }

    private static Uri CreateUri(string value, string path, Type targetType)
    {
        try { return new Uri(value, UriKind.RelativeOrAbsolute); }
        catch (UriFormatException error) { throw Error(path, value, targetType, "invalid URI", error); }
    }

    private static Regex CreateRegex(string value, string path, Type targetType)
    {
        try { return new Regex(value); }
        catch (ArgumentException error) { throw Error(path, value, targetType, "invalid regular expression", error); }
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
        if (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(Pair<,>))
        {
            var arguments = type.GetGenericArguments();
            first = arguments[0]; second = arguments[1]; return true;
        }
        first = second = null!; return false;
    }

    private static bool IsSetType(Type type) => type.GetInterfaces().Concat(new[] { type }).Any(candidate =>
        candidate.IsGenericType && candidate.GetGenericTypeDefinition() is var definition &&
        (definition == typeof(ISet<>) || definition == typeof(IReadOnlySet<>)));
    private static bool IsNumeric(Type type) => Type.GetTypeCode(type) is >= TypeCode.SByte and <= TypeCode.Decimal;
    private static string PklName(MemberInfo member) => member.GetCustomAttribute<PklNameAttribute>()?.Name ?? member.Name;
    private static string PklName(ParameterInfo parameter) => parameter.GetCustomAttribute<PklNameAttribute>()?.Name ?? parameter.Name!;
    private static bool IsRequired(MemberInfo member) => member.GetCustomAttribute<PklRequiredAttribute>() is not null ||
        member.GetCustomAttributes().Any(attribute => attribute.GetType().FullName ==
            "System.Runtime.CompilerServices.RequiredMemberAttribute");
    private static bool AllowsNull(ParameterInfo parameter) => Nullability.Create(parameter).ReadState != NullabilityState.NotNull;
    private static bool AllowsNull(PropertyInfo property) => Nullability.Create(property).WriteState != NullabilityState.NotNull;
    private static bool AllowsNull(FieldInfo field) => Nullability.Create(field).WriteState != NullabilityState.NotNull;
    private static int InheritanceDepth(Type? type) { var depth = 0; while (type is not null) { depth++; type = type.BaseType; } return depth; }
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
    private static PklBindException Error(string path, object? value, Type type, string reason, Exception? inner = null) =>
        inner is null ? new PklBindException($"Cannot bind {path} ({value?.GetType().FullName ?? "null"}) to {type.FullName}: {reason}.") :
            new PklBindException($"Cannot bind {path} ({value?.GetType().FullName ?? "null"}) to {type.FullName}: {reason}.", inner);
    private static string Identifier(string value) => CSharpGenerator.ToIdentifier(value);
}

public sealed class ConfigEvaluator : IDisposable
{
    private readonly Evaluator evaluator;
    private readonly bool ownsEvaluator;
    private bool disposed;

    public ConfigEvaluator(Evaluator? evaluator = null, ConfigBinder? binder = null)
    {
        this.evaluator = evaluator ?? Evaluator.Preconfigured();
        ownsEvaluator = evaluator is null;
        Binder = binder ?? new ConfigBinder();
    }

    public ConfigBinder Binder { get; }
    public Evaluator Evaluator { get { ThrowIfDisposed(); return evaluator; } }
    public T Evaluate<T>(ModuleSource source) { ThrowIfDisposed(); return Binder.Bind<T>(evaluator.Evaluate(source)); }
    public T EvaluateOutputValue<T>(ModuleSource source) { ThrowIfDisposed(); return Binder.Bind<T>(evaluator.EvaluateOutputValue(source)); }
    public T EvaluateExpression<T>(ModuleSource source, string expression)
    { ThrowIfDisposed(); return Binder.Bind<T>(evaluator.EvaluateExpression(source, expression)); }

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
    public string? Namespace { get; set; }
    public bool EmitDocComments { get; set; } = true;
    public bool EmitGeneratedLoaders { get; set; } = true;
    public IDictionary<string, string> NamespaceMappings { get; } =
        new SortedDictionary<string, string>(StringComparer.Ordinal);
}

public sealed class CSharpGenerator
{
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
        foreach (var alias in Ordered(schema.GetTypeAliases())) EmitAlias(output, alias.Value);
        EmitClass(output, schema.GetModuleClass(), moduleSymbol, moduleClass: true);
        foreach (var pClass in Ordered(schema.GetClasses()))
            EmitClass(output, pClass.Value, LocalClassSymbol(pClass.Value), moduleClass: false);
        return output.ToString();
    }

    private void RegisterLocal(string key, string symbol) => localTypes[key] = symbol;

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
        var name = LocalAliasSymbol(alias);
        var literals = StringLiteralValues(alias.GetAliasedType());
        output.Append("[global::Pkl.Core.PklName(\"").Append(Escape(alias.GetSimpleName())).Append("\")]\n");
        if (literals is not null)
        {
            output.Append("public enum ").Append(name).Append("\n{\n");
            var symbols = literals.Select(value => (Value: value, Symbol: ToIdentifier(value))).ToArray();
            var collisions = symbols.GroupBy(item => item.Symbol, StringComparer.Ordinal)
                .Where(group => group.Count() > 1)
                .Select(group => $"enum `{alias.GetQualifiedName()}` value collision `{group.Key}`: " +
                    string.Join(", ", group.Select(item => "`" + item.Value + "`").OrderBy(item => item, StringComparer.Ordinal)))
                .ToArray();
            if (collisions.Length != 0) throw new CSharpGenerationException(collisions);
            foreach (var item in symbols)
                output.Append("    [global::Pkl.Core.PklName(\"").Append(Escape(item.Value)).Append("\")]\n")
                    .Append("    ").Append(item.Symbol).Append(",\n");
            output.Append("}\n\n");
            return;
        }

        output.Append("[global::Pkl.Core.PklTypeAlias]\npublic readonly partial record struct ")
            .Append(name).Append(TypeParameters(alias.GetTypeParameters())).Append('(')
            .Append(TypeName(alias.GetAliasedType())).Append(" Value)\n{\n");
        EmitLoader(output, name + TypeArguments(alias.GetTypeParameters()), moduleClass: false,
            hidesBaseMembers: false, indent: "    ");
        output.Append("}\n\n");
    }

    private void EmitClass(StringBuilder output, PClass pClass, string name, bool moduleClass)
    {
        EmitDocs(output, pClass.GetDocComment());
        output.Append("[global::Pkl.Core.PklName(\"").Append(Escape(moduleClass ? moduleName : pClass.GetSimpleName()))
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
        ValidatePropertyCollisions(pClass, properties);
        foreach (var entry in properties)
        {
            var property = entry.Value;
            EmitDocs(output, property.GetInheritedDocComment(), "    ");
            output.Append("    [global::Pkl.Core.PklName(\"").Append(Escape(property.GetSimpleName())).Append("\")]\n")
                .Append("    [global::Pkl.Core.PklRequired]\n")
                .Append("    public required ").Append(TypeName(property.GetType())).Append(' ')
                .Append(ToIdentifier(property.GetSimpleName())).Append(" { get; init; }\n\n");
        }
        EmitLoader(output, name + typeParameters, moduleClass, baseType is not null, "    ");
        output.Append("}\n\n");
    }

    private void ValidatePropertyCollisions(PClass owner,
        IReadOnlyList<KeyValuePair<string, PClass.Property>> properties)
    {
        var collisions = properties.GroupBy(entry => ToIdentifier(entry.Value.GetSimpleName()), StringComparer.Ordinal)
            .Where(group => group.Count() > 1)
            .Select(group => $"property collision in `{owner.GetQualifiedName()}` for `{group.Key}`: " +
                string.Join(", ", group.Select(item => "`" + item.Value.GetSimpleName() + "`")
                    .OrderBy(item => item, StringComparer.Ordinal))).ToArray();
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
        if (ReferenceEquals(type, PType.UNKNOWN) || ReferenceEquals(type, PType.MODULE)) return "object";
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
            "pkl.base#Any" or "pkl.base#Dynamic" or "pkl.base#Typed" or "pkl.base#NonNull" => "object",
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

    private string ClassTypeName(PClass pClass, IList<PType> arguments)
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
    private string Argument(IList<PType> arguments, int index) => index < arguments.Count ? TypeName(arguments[index]) : "object";

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
    private static IEnumerable<KeyValuePair<string, T>> Ordered<T>(IDictionary<string, T> values) =>
        values.OrderBy(entry => entry.Value is Member member ? member.GetSourceLocation().StartLine : int.MaxValue)
            .ThenBy(entry => entry.Key, StringComparer.Ordinal);

    private static string TypeParameters(IList<TypeParameter> parameters) => parameters.Count == 0 ? "" :
        "<" + string.Join(", ", parameters.Select(item => ToIdentifier(item.GetName()))) + ">";
    private static string TypeArguments(IList<TypeParameter> parameters) => TypeParameters(parameters);

    private void EmitDocs(StringBuilder output, string? comment, string indent = "")
    {
        if (!options.EmitDocComments || string.IsNullOrWhiteSpace(comment)) return;
        output.Append(indent).Append("/// <summary>\n");
        foreach (var line in comment.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n'))
            output.Append(indent).Append("/// ").Append(System.Security.SecurityElement.Escape(line.Trim())).Append("\n");
        output.Append(indent).Append("/// </summary>\n");
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
