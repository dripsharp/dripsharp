// Destination-specific bridges between translated Java call sites and the
// focused Pkl.Core runtime substrate. Generic Java compatibility source must
// not acquire compile-time product dependencies from these capabilities.
#nullable enable

namespace Pkl.Core.Runtime;

internal static class PklRuntimeBridge
{
    internal static global::System.TimeSpan DurationOf(long value, object unit) => unit switch
    {
        JavaTemporalUnit.NANOS => global::System.TimeSpan.FromTicks(value / 100),
        JavaTemporalUnit.MICROS => global::System.TimeSpan.FromTicks(value * 10),
        JavaTemporalUnit.MILLIS => global::System.TimeSpan.FromMilliseconds(value),
        JavaTemporalUnit.SECONDS => global::System.TimeSpan.FromSeconds(value),
        JavaTemporalUnit.MINUTES => global::System.TimeSpan.FromMinutes(value),
        JavaTemporalUnit.HOURS => global::System.TimeSpan.FromHours(value),
        JavaTemporalUnit.DAYS => global::System.TimeSpan.FromDays(value),
        _ => throw new global::System.ArgumentOutOfRangeException(nameof(unit))
    };

    internal static JavaTuple2<T[], T[]> SplitArray<T>(T[] source, int index) =>
        new(source[..index], source[index..]);

    internal static int[][] SplitArray(int[] source, int index) =>
        new[] { source[..index], source[index..] };

    internal static global::Pkl.Core.Pair<object, object> ObjectPair<F, S>(
        global::Pkl.Core.Pair<F, S> pair) =>
        new(pair.GetFirst(), pair.GetSecond());

    internal static GraalCollections.EconomicMap<K, V> CreateEconomicMap<K, V>()
        where K : notnull => new();

    internal static GraalCollections.EconomicMap<K, V> CreateEconomicMap<K, V>(int capacity)
        where K : notnull => new(capacity);

    internal static GraalCollections.UnmodifiableEconomicMap<K, V> EmptyEconomicMap<K, V>()
        where K : notnull => new GraalCollections.EconomicMap<K, V>();

    internal static global::System.Collections.Generic.IDictionary<K, V>
        MapOfEntriesLoose<K, V>(params object[] entries) where K : notnull
    {
        var result = new global::System.Collections.Generic.Dictionary<K, V>();
        foreach (var entry in entries)
        {
            var entryType = entry.GetType();
            var key = (K)entryType.GetProperty("Key")!.GetValue(entry)!;
            var rawValue = entryType.GetProperty("Value")!.GetValue(entry)!;
            if (rawValue is V converted)
            {
                result[key] = converted;
                continue;
            }
            var rawType = rawValue.GetType();
            if (!rawType.IsGenericType || !typeof(V).IsGenericType ||
                rawType.GetGenericTypeDefinition() != typeof(global::Pkl.Core.PClassInfo<>) ||
                typeof(V).GetGenericTypeDefinition() != typeof(global::Pkl.Core.PClassInfo<>))
            {
                result[key] = (V)rawValue;
                continue;
            }
            var moduleName = rawType.GetMethod("GetModuleName")!.Invoke(rawValue, null);
            var className = rawType.GetMethod("GetSimpleName")!.Invoke(rawValue, null);
            var moduleUri = rawType.GetMethod("GetModuleUri")!.Invoke(rawValue, null);
            var javaType = rawType.GetField(
                "javaClass",
                global::System.Reflection.BindingFlags.Instance |
                global::System.Reflection.BindingFlags.NonPublic)!.GetValue(rawValue);
            result[key] = (V)global::System.Activator.CreateInstance(
                typeof(V),
                global::System.Reflection.BindingFlags.Instance |
                global::System.Reflection.BindingFlags.NonPublic,
                binder: null,
                args: new[] { moduleName, className, javaType, moduleUri },
                culture: null)!;
        }
        return result;
    }

    internal static bool PClassInfoEquals<T>(
        global::Pkl.Core.PClassInfo<T> left, object? right)
    {
        if (global::System.Object.ReferenceEquals(left, right)) return true;
        if (right is null) return false;
        var rightType = right.GetType();
        if (!rightType.IsGenericType ||
            rightType.GetGenericTypeDefinition() != typeof(global::Pkl.Core.PClassInfo<>))
            return false;
        var rightName = rightType.GetMethod("GetQualifiedName")!.Invoke(right, null) as string;
        return global::System.String.Equals(
            left.GetQualifiedName(), rightName, global::System.StringComparison.Ordinal);
    }

    internal static bool IsRrbTreeLeaf(object? value)
    {
        if (value is null) return false;
        var type = value.GetType();
        return type.IsGenericType &&
               type.Name.StartsWith("Leaf`", global::System.StringComparison.Ordinal) &&
               type.DeclaringType?.IsGenericType == true &&
               type.DeclaringType.Name.StartsWith("RrbTree`", global::System.StringComparison.Ordinal) &&
               type.Namespace == "Pkl.Core.Util.Paguro";
    }

    internal static global::System.Net.WebProxy NewWebProxy(
        JavaProxyType type, global::System.Net.IPEndPoint endpoint) =>
        new(new global::System.UriBuilder(
            "http", endpoint.Address.ToString(), endpoint.Port).Uri);

    internal static void VisitVmValue(dynamic visitor, object value)
    {
        if (value is VmValue vmValue) vmValue.Accept(visitor);
        else if (value is string text) visitor.VisitString(text);
        else if (value is bool boolean) visitor.VisitBoolean(boolean);
        else if (value is long integer) visitor.VisitInt(integer);
        else if (value is double floating) visitor.VisitFloat(floating);
        else throw new global::System.ArgumentException(
            "Unknown VM value type: " + value.GetType().FullName);
    }
}
