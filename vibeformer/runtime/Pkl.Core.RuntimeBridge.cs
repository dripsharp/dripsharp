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
