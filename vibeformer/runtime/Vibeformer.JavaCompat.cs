// Ordinary generated-product support for Java contracts with no direct .NET API.
// This file is copied unchanged into disposable projects; it is not a second AST
// and contains no Pkl parser behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;

namespace Vibeformer.Runtime;

internal delegate TResult JavaIntFunction<out TResult>(int value);
internal delegate int JavaToIntFunction<in TValue>(TValue value);
internal enum JavaTimeUnit { MILLISECONDS }

internal sealed class JavaOptional<T>
{
    private readonly T? value;
    private readonly bool present;

    private JavaOptional(T? value, bool present)
    {
        this.value = value;
        this.present = present;
    }

    internal static JavaOptional<T> Empty() => new(default, false);
    internal static JavaOptional<T> Of(T value) => new(JavaCompat.RequireNonNull(value), true);
    internal static JavaOptional<T> OfNullable(T? value) => new(value, value is not null);
    internal bool IsPresent() => present;
    internal bool IsEmpty() => !present;
    internal T Get() => present ? value! : throw new InvalidOperationException("Optional is empty");
    internal T OrElse(T fallback) => present ? value! : fallback;
    internal void IfPresent(Action<T> action) { if (present) action(value!); }
    internal void IfPresentOrElse(Action<T> action, Action emptyAction) { if (present) action(value!); else emptyAction(); }
    internal T OrElseThrow() => Get();
    internal JavaOptional<R> Map<R>(Func<T, R> mapper) => present ? JavaOptional<R>.OfNullable(mapper(value!)) : JavaOptional<R>.Empty();
    internal R Match<R>(Func<T, R> presentCase, Func<R> emptyCase) =>
        present ? presentCase(value!) : emptyCase();
}

internal static class JavaCompat
{
    internal static readonly TextWriter @out = Console.Out;
    internal static readonly TextWriter err = Console.Error;
    private static readonly Dictionary<string, string> SystemProperties = new(StringComparer.Ordinal);
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<IEnumerator, IteratorState>
        IteratorStates = new();

    private sealed class IteratorState
    {
        internal bool Prepared;
        internal bool Exhausted;
    }

    internal static T RequireNonNull<T>(T? value, string? message = null) =>
        value is null ? throw new NullReferenceException(message) : value;

    internal static string Concat(object? left, object? right) =>
        JavaString(left) + JavaString(right);

    private static string JavaString(object? value) => value?.ToString() ?? "null";

    internal static bool IsDigit(int codePoint) => Rune.GetUnicodeCategory(new Rune(codePoint)) == UnicodeCategory.DecimalDigitNumber;

    internal static bool IsLetterOrDigit(int codePoint) =>
        Rune.IsLetter(new Rune(codePoint)) || IsDigit(codePoint);

    internal static bool IsUnicodeIdentifierStart(int codePoint)
    {
        var category = Rune.GetUnicodeCategory(new Rune(codePoint));
        return Rune.IsLetter(new Rune(codePoint)) ||
               category is UnicodeCategory.LetterNumber or UnicodeCategory.CurrencySymbol or UnicodeCategory.ConnectorPunctuation;
    }

    internal static bool IsUnicodeIdentifierPart(int codePoint)
    {
        var category = Rune.GetUnicodeCategory(new Rune(codePoint));
        return IsUnicodeIdentifierStart(codePoint) ||
               category is UnicodeCategory.DecimalDigitNumber or UnicodeCategory.NonSpacingMark or
                   UnicodeCategory.SpacingCombiningMark or UnicodeCategory.Format;
    }

    internal static string CodePointToString(int codePoint)
    {
        // Character.toString(int) preserves every BMP char value, including an
        // unpaired surrogate. ConvertFromUtf32 is stricter and rejects those.
        if (codePoint is >= char.MinValue and <= char.MaxValue) return ((char)codePoint).ToString();
        return char.ConvertFromUtf32(codePoint);
    }

    internal static int CodePointAt(string value, int index) => char.ConvertToUtf32(value, index);

    internal static int CodePointCount(string value, int beginIndex, int endIndex)
    {
        var count = 0;
        for (var index = beginIndex; index < endIndex; count++)
            index += char.IsSurrogatePair(value, index) ? 2 : 1;
        return count;
    }

    internal static bool EqualsIgnoreCase(string value, string? other) =>
        string.Equals(value, other, StringComparison.OrdinalIgnoreCase);

    internal static int StringHashCode(string value)
    {
        var result = 0;
        foreach (var character in value) result = unchecked(31 * result + character);
        return result;
    }

    internal static string StringValueOf(object? value) => value?.ToString() ?? "null";
    internal static string StringValueOf(char value) => value.ToString();
    internal static string StringValueOf(char[] value) => new(value);
    internal static string StringValueOf(bool value) => value ? "true" : "false";
    internal static string StringValueOf(int value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(long value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(float value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(double value) => value.ToString(CultureInfo.InvariantCulture);
    internal static IEnumerable<string> StringLines(string value) => value.Replace("\r\n", "\n").Split('\n');
    internal static bool StringMatches(string value, string pattern) => Regex.IsMatch(value, "\\A(?:" + pattern + ")\\z");
    internal static string StringReplaceAll(string value, string pattern, string replacement) =>
        Regex.Replace(value, pattern, replacement);
    internal static sbyte[] StringGetBytes(string value, Encoding encoding) =>
        encoding.GetBytes(value).Select(item => unchecked((sbyte)item)).ToArray();
    internal static sbyte[] StringGetBytes(string value, string encoding) => StringGetBytes(value, Encoding.GetEncoding(encoding));
    internal static StringBuilder StringBuilderDelete(StringBuilder value, int start, int end) =>
        value.Remove(start, end - start);

    internal static IEnumerable<int> CodePoints(string value)
    {
        for (var index = 0; index < value.Length;)
        {
            var codePoint = char.ConvertToUtf32(value, index);
            yield return codePoint;
            index += char.IsSurrogatePair(value, index) ? 2 : 1;
        }
    }

    internal static string EnumName(object value)
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        return value.GetType().GetFields(flags).FirstOrDefault(field => ReferenceEquals(field.GetValue(null), value))?.Name
               ?? value.ToString() ?? string.Empty;
    }

    internal static int EnumOrdinal(object value) => Convert.ToInt32(value, CultureInfo.InvariantCulture);

    internal static int ParseInt(string value) => int.Parse(value, CultureInfo.InvariantCulture);

    internal static int ParseInt(string value, int radix) => Convert.ToInt32(value, radix);

    internal static long ParseLong(string value) => long.Parse(value, CultureInfo.InvariantCulture);
    internal static long ParseLong(string value, int radix) => Convert.ToInt64(value, radix);
    internal static long ParseLong(string value, int beginIndex, int endIndex, int radix) =>
        Convert.ToInt64(value.Substring(beginIndex, endIndex - beginIndex), radix);
    internal static sbyte ParseByte(string value, int radix) => checked((sbyte)Convert.ToInt32(value, radix));
    internal static double ParseDouble(string value) => double.Parse(value, CultureInfo.InvariantCulture);
    internal static int CompareLong(long left, long right) => left.CompareTo(right);
    internal static int CompareInt(int left, int right) => left.CompareTo(right);
    internal static int CompareDouble(double left, double right) => left.CompareTo(right);
    internal static int LongLeadingZeros(long value) => BitOperations.LeadingZeroCount(unchecked((ulong)value));
    internal static int LongTrailingZeros(long value) => BitOperations.TrailingZeroCount(unchecked((ulong)value));
    internal static int IntLeadingZeros(int value) => BitOperations.LeadingZeroCount(unchecked((uint)value));
    internal static int Signum(long value) => Math.Sign(value);
    internal static string ToHexString(long value) => unchecked((ulong)value).ToString("x", CultureInfo.InvariantCulture);
    internal static string ToUnsignedString(long value, int radix)
    {
        const string digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        if (radix is < 2 or > 36) radix = 10;
        var remaining = unchecked((ulong)value);
        if (remaining == 0) return "0";
        var buffer = new char[64];
        var index = buffer.Length;
        while (remaining != 0)
        {
            buffer[--index] = digits[(int)(remaining % (uint)radix)];
            remaining /= (uint)radix;
        }
        return new string(buffer, index, buffer.Length - index);
    }
    internal static string ToStringRadix(long value, int radix)
    {
        if (value >= 0) return ToUnsignedString(value, radix);
        return "-" + ToUnsignedString(unchecked(-value), radix);
    }
    internal static string ToStringRadix(int value, int radix) => ToStringRadix((long)value, radix);
    internal static int ToUnsignedInt(sbyte value) => unchecked((byte)value);
    internal static long ToUnsignedLong(sbyte value) => unchecked((byte)value);
    internal static bool IsBmpCodePoint(int value) => value is >= char.MinValue and <= char.MaxValue;
    internal static bool IsValidCodePoint(int value) => Rune.IsValid(value);
    internal static bool IsUpperCase(int value) => Rune.IsUpper(new Rune(value));
    internal static bool IsUpperCase(char value) => char.IsUpper(value);
    internal static int ToTitleCase(int value) => value is >= char.MinValue and <= char.MaxValue
        ? char.ToUpperInvariant((char)value)
        : value;
    internal static long MathRound(double value) => double.IsNaN(value) ? 0
        : value >= long.MaxValue ? long.MaxValue
        : value <= long.MinValue ? long.MinValue
        : (long)Math.Floor(value + 0.5d);
    internal static int MathRoundFloat(float value) => float.IsNaN(value) ? 0
        : value >= int.MaxValue ? int.MaxValue
        : value <= int.MinValue ? int.MinValue
        : (int)Math.Floor(value + 0.5f);
    internal static long AddExact(long left, long right) => checked(left + right);
    internal static long MultiplyExact(long left, long right) => checked(left * right);
    internal static int MultiplyExactInt(int left, int right) => checked(left * right);
    internal static double SignumDouble(double value) => Math.Sign(value);
    internal static long SubtractExact(long left, long right) => checked(left - right);
    internal static long NegateExact(long value) => checked(-value);
    internal static int ToIntExact(long value) => checked((int)value);
    internal static int AddExactInt(int left, int right) => checked(left + right);
    internal static int GetExponent(double value) => Math.ILogB(value);
    internal static BigInteger NewBigInteger(int signum, sbyte[] magnitude) =>
        new BigInteger(magnitude.Select(value => unchecked((byte)value)).ToArray(), true, true) * Math.Sign(signum);
    internal static BigInteger NewBigInteger(int signum, byte[] magnitude) =>
        new BigInteger(magnitude, true, true) * Math.Sign(signum);
    internal static TimeSpan DurationOfSeconds(long seconds) => TimeSpan.FromSeconds(seconds);
    internal static TimeSpan DurationOfSeconds(long seconds, long nanos) =>
        TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    #if VIBEFORMER_PKL_CORE
    internal static TimeSpan DurationOf(long value, object unit) => unit switch
    {
        global::Pkl.Core.Runtime.JavaTemporalUnit.NANOS => TimeSpan.FromTicks(value / 100),
        global::Pkl.Core.Runtime.JavaTemporalUnit.MICROS => TimeSpan.FromTicks(value * 10),
        global::Pkl.Core.Runtime.JavaTemporalUnit.MILLIS => TimeSpan.FromMilliseconds(value),
        global::Pkl.Core.Runtime.JavaTemporalUnit.SECONDS => TimeSpan.FromSeconds(value),
        global::Pkl.Core.Runtime.JavaTemporalUnit.MINUTES => TimeSpan.FromMinutes(value),
        global::Pkl.Core.Runtime.JavaTemporalUnit.HOURS => TimeSpan.FromHours(value),
        global::Pkl.Core.Runtime.JavaTemporalUnit.DAYS => TimeSpan.FromDays(value),
        _ => throw new ArgumentOutOfRangeException(nameof(unit))
    };
    #endif
    internal static T ClassCast<T>(Type type, object value) =>
        type.IsInstanceOfType(value) ? (T)value : throw new InvalidCastException();
    internal static Uri? ClassGetResource(Type type, string name) =>
        type.Assembly.GetManifestResourceInfo(name.TrimStart('/')) is null ? null : new Uri("resource:///" + name.TrimStart('/'));
    internal static Uri? ClassGetResource(Assembly assembly, string name) =>
        assembly.GetManifestResourceInfo(name.TrimStart('/')) is null ? null : new Uri("resource:///" + name.TrimStart('/'));
    internal static Stream? ClassGetResourceAsStream(Type type, string name) =>
        type.Assembly.GetManifestResourceStream(name.TrimStart('/'));
    internal static Stream? ClassGetResourceAsStream(Assembly assembly, string name) =>
        assembly.GetManifestResourceStream(name.TrimStart('/'));
    internal static T? ClassGetAnnotation<T>(Type type, Type annotationType) where T : class =>
        type.GetCustomAttributes(annotationType, true).FirstOrDefault() as T;
    internal static System.Diagnostics.StackFrame[] GetStackTrace(Exception exception) =>
        new System.Diagnostics.StackTrace(exception, true).GetFrames();
    internal static void SetStackTrace(Exception exception, object? stackTrace)
    {
        // System.Exception has no writable stack trace. Keep the Java call as
        // an explicit compatibility boundary while retaining the exception.
        _ = exception;
        _ = stackTrace;
    }
    internal static void PrintStackTrace(Exception exception) => Console.Error.WriteLine(exception);
    internal static void PrintStackTrace(Exception exception, object writer)
    {
        if (writer is TextWriter textWriter) textWriter.WriteLine(exception);
        else Console.Error.WriteLine(exception);
    }

    internal static string Formatted(string format, params object?[] arguments)
    {
        var result = new StringBuilder();
        var argument = 0;
        for (var index = 0; index < format.Length; index++)
        {
            if (format[index] != '%' || index + 1 >= format.Length)
            {
                result.Append(format[index]);
                continue;
            }

            var conversion = format[++index];
            if (conversion == '%')
            {
                result.Append('%');
                continue;
            }

            if (argument >= arguments.Length) throw new FormatException("Missing Java format argument");
            result.Append(JavaString(arguments[argument++]));
        }
        return result.ToString();
    }

    internal static int IndexOfCodePoint(string value, int codePoint, int fromIndex) =>
        value.IndexOf(char.ConvertFromUtf32(codePoint), Math.Max(0, fromIndex), StringComparison.Ordinal);

    internal static string Repeat(string value, int count)
    {
        if (count < 0) throw new ArgumentException("count is negative", nameof(count));
        return string.Concat(Enumerable.Repeat(value, count));
    }

    internal static bool StartsWith(string value, string prefix) => value.StartsWith(prefix, StringComparison.Ordinal);
    internal static bool RegionMatches(string value, bool ignoreCase, int thisOffset, string other, int otherOffset, int length) =>
        string.Compare(value, thisOffset, other, otherOffset, length,
            ignoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal) == 0;

    internal static string Substring(string value, int begin, int end) => value.Substring(begin, end - begin);

    internal static StringBuilder AppendRange(StringBuilder builder, string value, int start, int end) =>
        builder.Append(value, start, end - start);

    internal static StringBuilder Reverse(StringBuilder builder)
    {
        var chars = builder.ToString().ToCharArray();
        Array.Reverse(chars);
        builder.Clear().Append(chars);
        return builder;
    }
    internal static void Reverse<T>(IList<T> values)
    {
        for (var left = 0; left < values.Count / 2; left++)
        {
            var right = values.Count - left - 1;
            (values[left], values[right]) = (values[right], values[left]);
        }
    }

    internal static void ArrayCopy(object source, int sourceIndex, object destination, int destinationIndex, int length) =>
        Array.Copy((Array)source, sourceIndex, (Array)destination, destinationIndex, length);

    internal static T[] ArrayCopy<T>(T[] source, int length, Type? _) => CopyOf(source, length);

    internal static IDictionary<string, string> GetEnvironment() =>
        Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? string.Empty,
                          StringComparer.Ordinal);

    internal static IDictionary<string, string> GetProperties() =>
        new Dictionary<string, string>(SystemProperties, StringComparer.Ordinal);

    internal static string? GetProperty(string name) =>
        SystemProperties.TryGetValue(name, out var value) ? value : null;

    internal static string? SetProperty(string name, string value)
    {
        var previous = GetProperty(name);
        SystemProperties[name] = value;
        return previous;
    }

    internal static Uri CreateUri(string value) => new(value, UriKind.RelativeOrAbsolute);
    internal static string NewString(char[] value) => new(value);
    internal static string NewString(char[] value, int offset, int count) => new(value, offset, count);
    internal static string NewString(int[] codePoints, int offset, int count) =>
        string.Concat(codePoints.Skip(offset).Take(count).Select(CodePointToString));
    internal static string NewString(sbyte[] value, Encoding encoding) =>
        encoding.GetString(value.Select(item => unchecked((byte)item)).ToArray());
    internal static string NewString(sbyte[] value, int offset, int count, Encoding encoding) =>
        encoding.GetString(value.Skip(offset).Take(count).Select(item => unchecked((byte)item)).ToArray());
    internal static string NewString(byte[] value, Encoding encoding) => encoding.GetString(value);
    internal static string NewString(object value) => StringValueOf(value);
    internal static Uri NewUri(string value) => CreateUri(value);
    internal static Uri NewUri(string? scheme, string? schemeSpecificPart, string? fragment)
    {
        var text = (scheme is null ? string.Empty : scheme + ":") + (schemeSpecificPart ?? string.Empty);
        if (fragment is not null) text += "#" + fragment;
        return CreateUri(text);
    }
    internal static UriFormatException NewUriSyntaxException(string input, string reason) =>
        new($"{reason}: {input}");
    internal static UriFormatException NewUriSyntaxException(string input, string reason, int index) =>
        new($"{reason} at index {index}: {input}");
    internal static IOException NewIOException() => new();
    internal static IOException NewIOException(string? message) => new(message);
    internal static IOException NewIOException(Exception cause) => new(cause.Message, cause);
    internal static IOException NewIOException(string? message, Exception? cause) => new(message, cause);
    internal static Exception NewException() => new();
    internal static Exception NewException(string? message) => new(message);
    internal static Exception NewException(Exception cause) => new(cause.Message, cause);
    internal static Exception NewException(string? message, Exception? cause) => new(message, cause);
    internal static ArgumentException NewArgumentException() => new();
    internal static ArgumentException NewArgumentException(string? message) => new(message);
    internal static ArgumentException NewArgumentException(Exception cause) => new(cause.Message, cause);
    internal static ArgumentException NewArgumentException(string? message, Exception? cause) => new(message, cause);
    internal static InvalidOperationException NewInvalidOperationException() => new();
    internal static InvalidOperationException NewInvalidOperationException(string? message) => new(message);
    internal static InvalidOperationException NewInvalidOperationException(Exception cause) => new(cause.Message, cause);
    internal static InvalidOperationException NewInvalidOperationException(string? message, Exception? cause) => new(message, cause);
    internal static TypeInitializationException NewTypeInitializationException(Exception cause) =>
        new(cause.GetType().FullName, cause);
    internal static Uri NewUri(string? scheme, string? host, string? path, string? fragment)
    {
        var builder = new UriBuilder(scheme ?? string.Empty, host ?? string.Empty) { Path = path ?? string.Empty };
        if (fragment is not null) builder.Fragment = fragment;
        return builder.Uri;
    }
    internal static Uri NewUri(string? scheme, string? userInfo, string? host, int port,
        string? path, string? query, string? fragment)
    {
        var builder = new UriBuilder(scheme ?? string.Empty, host ?? string.Empty, port, path ?? string.Empty)
        {
            Query = query ?? string.Empty,
            Fragment = fragment ?? string.Empty,
            UserName = userInfo ?? string.Empty
        };
        return builder.Uri;
    }

    private static string UriTextBeforeFragment(Uri uri)
    {
        var text = uri.OriginalString;
        var fragment = text.IndexOf('#');
        return fragment < 0 ? text : text[..fragment];
    }

    internal static string? UriScheme(Uri uri) => uri.IsAbsoluteUri ? uri.Scheme : null;

    internal static string? UriSchemeSpecificPart(Uri uri)
    {
        var text = UriTextBeforeFragment(uri);
        var colon = text.IndexOf(':');
        return colon < 0 ? text : text[(colon + 1)..];
    }

    internal static string? UriFragment(Uri uri)
    {
        var text = uri.OriginalString;
        var marker = text.IndexOf('#');
        return marker < 0 ? null : text[(marker + 1)..];
    }

    internal static string? UriQuery(Uri uri)
    {
        var text = UriTextBeforeFragment(uri);
        var marker = text.IndexOf('?');
        return marker < 0 ? null : text[(marker + 1)..];
    }

    internal static string? UriAuthority(Uri uri) =>
        uri.IsAbsoluteUri && uri.OriginalString.Contains("//", StringComparison.Ordinal)
            ? uri.GetComponents(UriComponents.StrongAuthority, UriFormat.UriEscaped)
            : null;

    internal static string? UriHost(Uri uri) => uri.IsAbsoluteUri && !string.IsNullOrEmpty(uri.Host) ? uri.Host : null;
    internal static string? UriUserInfo(Uri uri) => uri.IsAbsoluteUri && !string.IsNullOrEmpty(uri.UserInfo) ? uri.UserInfo : null;

    internal static int UriPort(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return -1;
        var authority = UriAuthority(uri);
        if (authority is null) return -1;
        var closeBracket = authority.LastIndexOf(']');
        var colon = authority.LastIndexOf(':');
        return colon > closeBracket && int.TryParse(authority[(colon + 1)..], out var port) ? port : -1;
    }

    internal static string? UriPath(Uri uri)
    {
        if (uri.IsAbsoluteUri) return uri.GetComponents(UriComponents.Path, UriFormat.UriEscaped) is { } path
            ? (uri.OriginalString.Contains("://", StringComparison.Ordinal) && !path.StartsWith('/') ? "/" + path : path)
            : null;
        var text = UriTextBeforeFragment(uri);
        var query = text.IndexOf('?');
        return query < 0 ? text : text[..query];
    }

    internal static Uri ResolveUri(Uri basis, string value) => ResolveUri(basis, CreateUri(value));
    internal static Uri ResolveUri(Uri basis, Uri value) => value.IsAbsoluteUri ? value : new Uri(basis, value);
    internal static Uri NormalizeUri(Uri uri) => uri;
    internal static Uri RelativizeUri(Uri basis, Uri value) =>
        basis.IsAbsoluteUri && value.IsAbsoluteUri ? basis.MakeRelativeUri(value) : value;
    internal static bool UriIsOpaque(Uri uri) => !uri.IsAbsoluteUri || string.IsNullOrEmpty(uri.AbsolutePath);
    internal static Exception InitCause(Exception exception, Exception cause) =>
        new Exception(exception.Message, cause);

    internal static int IdentityHashCode(object value) =>
        System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);

    internal static long NanoTime() =>
        checked((long)(System.Diagnostics.Stopwatch.GetTimestamp() *
                       (1_000_000_000.0 / System.Diagnostics.Stopwatch.Frequency)));

    internal static string Format(JavaFormat format, object? value) => format.Format(value);

    internal static bool Add<T>(ICollection<T> collection, T value)
    {
        collection.Add(value);
        return true;
    }
    internal static bool Add<T>(ICollection<T> collection, object? value)
    {
        collection.Add((T)value!);
        return true;
    }

    internal static bool AddAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var changed = false;
        foreach (var value in values)
        {
            collection.Add(value);
            changed = true;
        }
        return changed;
    }

    internal static int CollectionCount<T>(IEnumerable<T> collection) => collection.Count();
    internal static bool CollectionIsEmpty<T>(IEnumerable<T> collection) => !collection.Any();
    internal static int CollectionCount(IEnumerable collection) => collection.Cast<object?>().Count();
    internal static bool CollectionIsEmpty(IEnumerable collection) => !collection.Cast<object?>().Any();
    internal static bool CollectionContains<T>(IEnumerable<T> collection, object? value) =>
        value is T typed && collection.Contains(typed);
    internal static bool ContainsAll<T>(IEnumerable<T> collection, IEnumerable<T> values)
    {
        var set = new HashSet<T>(collection);
        return values.All(set.Contains);
    }
    internal static bool RemoveAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var changed = false;
        foreach (var value in values.ToArray()) changed |= collection.Remove(value);
        return changed;
    }
    internal static bool RetainAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var retained = new HashSet<T>(values);
        var changed = false;
        foreach (var value in collection.Where(value => !retained.Contains(value)).ToArray())
            changed |= collection.Remove(value);
        return changed;
    }

    internal static IList<T> Mutable<T>(IList<T> values) => new List<T>(values);
    internal static ISet<T> Mutable<T>(ISet<T> values) => new HashSet<T>(values);
    internal static IDictionary<K, V> Mutable<K, V>(IDictionary<K, V> values) where K : notnull =>
        new Dictionary<K, V>(values);
    internal static IList<T> Assoc<T>(IList<T> values, int index, T value)
    {
        var result = new List<T>(values);
        result[index] = value;
        return result;
    }
    internal static IDictionary<K, V> Assoc<K, V>(IDictionary<K, V> values, K key, V value) where K : notnull
    {
        var result = new Dictionary<K, V>(values) { [key] = value };
        return result;
    }
    internal static IDictionary<K, V> Without<K, V>(IDictionary<K, V> values, K key) where K : notnull
    {
        var result = new Dictionary<K, V>(values);
        result.Remove(key);
        return result;
    }
    internal static ISet<T> Without<T>(ISet<T> values, T value)
    {
        var result = new HashSet<T>(values);
        result.Remove(value);
        return result;
    }

    internal static bool MapContainsKey<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.ContainsKey(typed);

    internal static IEnumerable<KeyValuePair<K, V>> MapEntrySet<K, V>(IDictionary<K, V> map) where K : notnull => map;

    internal static bool MapIsEmpty<K, V>(IDictionary<K, V> map) where K : notnull => map.Count == 0;
    internal static ISet<K> MapKeySet<K, V>(IDictionary<K, V> map) where K : notnull => new HashSet<K>(map.Keys);
    internal static int MapCount<K, V>(IDictionary<K, V> map) where K : notnull => map.Count;
    internal static bool MapContainsValue<K, V>(IDictionary<K, V> map, object? value) where K : notnull =>
        value is V typed && map.Values.Contains(typed);
    internal static V MapRemove<K, V>(IDictionary<K, V> map, object? key) where K : notnull
    {
        if (key is K typed && map.Remove(typed, out var value)) return value;
        return default!;
    }
    internal static V ComputeIfAbsent<K, V>(IDictionary<K, V> map, K key, Func<K, V> factory) where K : notnull
    {
        if (map.TryGetValue(key, out var value)) return value;
        value = factory(key);
        map[key] = value;
        return value;
    }
    internal static V MapGetOrDefault<K, V>(IDictionary<K, V> map, K key, V fallback) where K : notnull =>
        map.TryGetValue(key, out var value) ? value : fallback;
    internal static V MapPutIfAbsent<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        if (map.TryGetValue(key, out var previous)) return previous;
        map[key] = value;
        return default!;
    }

    internal static void MapPutAll<K, V>(IDictionary<K, V> map, IEnumerable<KeyValuePair<K, V>> values) where K : notnull
    {
        foreach (var (key, value) in values) map[key] = value;
    }

    internal static V MapGet<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : default!;

    internal static V MapPut<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        var previous = map.TryGetValue(key, out var oldValue) ? oldValue : default!;
        map[key] = value;
        return previous;
    }

    internal static KeyValuePair<K, V> MapEntry<K, V>(K key, V value) where K : notnull => new(key, value);

    internal static IDictionary<K, V> MapOfEntries<K, V>(params KeyValuePair<K, V>[] entries) where K : notnull =>
        entries.ToDictionary(entry => entry.Key, entry => entry.Value);
    internal static IDictionary<K, V> MapOfEntriesLoose<K, V>(params object[] entries) where K : notnull
    {
        var result = new Dictionary<K, V>();
        foreach (var entry in entries)
        {
            var type = entry.GetType();
            var key = (K)type.GetProperty("Key")!.GetValue(entry)!;
            var rawValue = type.GetProperty("Value")!.GetValue(entry)!;
            V value;
            if (rawValue is V converted)
            {
                value = converted;
            }
            else if (rawValue.GetType().IsGenericType && typeof(V).IsGenericType &&
                     rawValue.GetType().GetGenericTypeDefinition().FullName == "Pkl.Core.PClassInfo`1" &&
                     typeof(V).GetGenericTypeDefinition().FullName == "Pkl.Core.PClassInfo`1")
            {
                var sourceType = rawValue.GetType();
                var moduleName = sourceType.GetMethod("GetModuleName")!.Invoke(rawValue, null);
                var className = sourceType.GetMethod("GetSimpleName")!.Invoke(rawValue, null);
                var moduleUri = sourceType.GetMethod("GetModuleUri")!.Invoke(rawValue, null);
                value = (V)Activator.CreateInstance(typeof(V),
                    System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic,
                    binder: null,
                    args: new[] { moduleName, className, typeof(object), moduleUri },
                    culture: null)!;
            }
            else
            {
                value = (V)rawValue;
            }
            result[key] = value;
        }
        return result;
    }
    internal static IDictionary<K, V> MapOf<K, V>(params object[] values) where K : notnull
    {
        var result = new Dictionary<K, V>();
        for (var index = 0; index < values.Length; index += 2)
            result[(K)values[index]] = (V)values[index + 1];
        return result;
    }

    internal static Regex CompileRegex(string pattern) => new(pattern);
    internal static Regex CompileRegex(string pattern, int flags) => new(pattern, RegexOptions.CultureInvariant);
    internal static JavaRegexMatcher RegexMatcher(Regex pattern, string input) => new(pattern, input);
    internal static string QuoteReplacement(string value) => value.Replace("$", "$$", StringComparison.Ordinal);
    internal static string Encode(string value, Encoding encoding) => Uri.EscapeDataString(value);

    internal static IList<T> ListOf<T>(params T[] values) => new ReadOnlyCollection<T>(values);

    internal static IList<T> AsList<T>(params T[] values) => new JavaArrayList<T>(values);

    internal static HashSet<T> SetOf<T>(params T[] values) => new(values);
    internal static HashSet<T> SetOfValues<T>(IEnumerable<T> values) => new(values);
    internal static ISet<T> EnumSetNoneOf<T>(Type _) => new HashSet<T>();
    internal static ISet<T> EnumSetAllOf<T>(Type type) =>
        new HashSet<T>(type.GetFields(BindingFlags.Public | BindingFlags.Static)
            .Where(field => type.IsAssignableFrom(field.FieldType))
            .Select(field => (T)field.GetValue(null)!));
    internal static ISet<T> EnumSetOf<T>(T value) => new HashSet<T> { value };
    internal static ISet<T> EnumSetCopyOf<T>(IEnumerable<T> values) => new HashSet<T>(values);

    internal static IList<T> UnmodifiableList<T>(IEnumerable<T> values) =>
        new ReadOnlyCollection<T>(values is IList<T> list ? list : values.ToList());

    internal static IList<T> SubList<T>(IEnumerable<T> values, int fromIndex, int toIndex) =>
        new JavaSubList<T>(values is IList<T> list ? list : values.ToList(), fromIndex, toIndex);

    internal static T[] CopyOf<T>(T[] source, int length)
    {
        var result = new T[length];
        Array.Copy(source, result, Math.Min(source.Length, length));
        return result;
    }
    internal static T[] CopyOfRange<T>(T[] source, int fromIndex, int toIndex) => source[fromIndex..toIndex];
    internal static void Fill<T>(T[] values, T value) => Array.Fill(values, value);
    internal static void Fill<T>(T[] values, int fromIndex, int toIndex, T value) =>
        Array.Fill(values, value, fromIndex, toIndex - fromIndex);
    internal static T[] EmptyArray<T>() => Array.Empty<T>();
    internal static string ArrayString(Array value) => string.Join(", ", value.Cast<object?>().Select(StringValueOf));
    internal static string ArrayString<T>(T[] value) => ArrayString((Array)value);
    internal static string IndentSpace(int count) => new(' ', Math.Max(0, count));
    internal static T[] InsertIntoArrayAt<T>(T value, T[] source, int index, Type? _) =>
        SpliceIntoArrayAt(value, source, index, null);
    internal static T[] SpliceIntoArrayAt<T>(T value, T[] source, int index, Type? _)
    {
        var result = new T[source.Length + 1];
        Array.Copy(source, 0, result, 0, index);
        result[index] = value;
        Array.Copy(source, index, result, index + 1, source.Length - index);
        return result;
    }
    internal static T[] SpliceIntoArrayAt<T>(T[] values, T[] source, int index, Type? _)
    {
        var result = new T[source.Length + values.Length];
        Array.Copy(source, 0, result, 0, index);
        Array.Copy(values, 0, result, index, values.Length);
        Array.Copy(source, index, result, index + values.Length, source.Length - index);
        return result;
    }
    internal static T[] ReplaceInArrayAt<T>(T value, T[] source, int index, Type? _)
    {
        var result = (T[])source.Clone();
        result[index] = value;
        return result;
    }

#if VIBEFORMER_PKL_CORE
    internal static global::Pkl.Core.Runtime.JavaTuple2<T[], T[]> SplitArray<T>(T[] source, int index) =>
        new(source[..index], source[index..]);
    internal static int[][] SplitArray(int[] source, int index) => new[] { source[..index], source[index..] };
#endif

    internal static int ListCount<T>(IEnumerable<T> values) => values.Count();

    internal static bool ListIsEmpty<T>(IEnumerable<T> values) => !values.Any();

    internal static T ListGet<T>(IEnumerable<T> values, int index) =>
        values is IList<T> list ? list[index] : values.ElementAt(index);

    internal static bool ListAddAll<T>(IList<T> values, int index, IEnumerable<T> added)
    {
        var changed = false;
        foreach (var value in added) { values.Insert(index++, value); changed = true; }
        return changed;
    }

    internal static T ListSet<T>(IList<T> values, int index, T value)
    {
        var previous = values[index];
        values[index] = value;
        return previous;
    }

    internal static void ListAdd<T>(IList<T> values, int index, T value) => values.Insert(index, value);
    internal static T ListRemove<T>(IList<T> values, int index)
    {
        var previous = values[index];
        values.RemoveAt(index);
        return previous;
    }
    internal static int ListLastIndexOf<T>(IList<T> values, object? value)
    {
        for (var index = values.Count - 1; index >= 0; index--)
            if (Equals(values[index], value)) return index;
        return -1;
    }
    internal static IEnumerator<T> ReverseIterator<T>(IEnumerable<T> values, int index) =>
        values.Take(index).Reverse().GetEnumerator();

    internal static bool IteratorHasNext(IEnumerator iterator)
    {
        var state = IteratorStates.GetValue(iterator, _ => new IteratorState());
        if (!state.Prepared && !state.Exhausted)
        {
            state.Prepared = iterator.MoveNext();
            state.Exhausted = !state.Prepared;
        }
        return state.Prepared;
    }

    internal static T IteratorNext<T>(IEnumerator<T> iterator)
    {
        var state = IteratorStates.GetValue(iterator, _ => new IteratorState());
        if (!state.Prepared)
        {
            if (state.Exhausted || !iterator.MoveNext())
            {
                state.Exhausted = true;
                throw new InvalidOperationException("Iterator is exhausted");
            }
        }
        state.Prepared = false;
        return iterator.Current;
    }

    internal static long IteratorNextLong(IEnumerator<long> iterator) => IteratorNext(iterator);

    internal static T DequeGetFirst<T>(JavaDeque<T> deque) => deque.GetFirst();
    internal static T? DequePeek<T>(JavaDeque<T> deque) => deque.Peek();
    internal static T DequePop<T>(JavaDeque<T> deque) => deque.Pop();
    internal static void DequePush<T>(JavaDeque<T> deque, T value) => deque.Push(value);

    internal new static bool Equals(object? left, object? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left is null || right is null) return false;
        if (IsJavaList(left)) return IsJavaList(right) && ListsEqual((IEnumerable)left, (IEnumerable)right);
        return left.Equals(right);
    }

    internal static bool DeepEquals(object? left, object? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left is not Array leftArray || right is not Array rightArray)
            return left is not Array && right is not Array && Equals(left, right);
        if (leftArray.Rank != 1 || rightArray.Rank != 1) return false;
        if (leftArray.Length != rightArray.Length) return false;
        var leftElement = leftArray.GetType().GetElementType()!;
        var rightElement = rightArray.GetType().GetElementType()!;
        var primitiveElements = leftElement.IsValueType || rightElement.IsValueType;
        if (primitiveElements && leftElement != rightElement) return false;
        for (var index = 0; index < leftArray.Length; index++)
        {
            var equal = primitiveElements
                ? object.Equals(leftArray.GetValue(index), rightArray.GetValue(index))
                : DeepEquals(leftArray.GetValue(index), rightArray.GetValue(index));
            if (!equal) return false;
        }
        return true;
    }

    internal static int Hash(params object?[] values)
    {
        unchecked
        {
            var result = 1;
            foreach (var value in values) result = 31 * result + JavaHashCode(value);
            return result;
        }
    }

    private static bool IsJavaList(object value) =>
        value is not Array && value is IEnumerable &&
        (value is IList || value.GetType().GetInterfaces().Any(type =>
            type.IsGenericType && type.GetGenericTypeDefinition() == typeof(IList<>)));

    private static bool ListsEqual(IEnumerable left, IEnumerable right)
    {
        var leftEnumerator = left.GetEnumerator();
        var rightEnumerator = right.GetEnumerator();
        try
        {
            while (true)
            {
                var leftHasValue = leftEnumerator.MoveNext();
                var rightHasValue = rightEnumerator.MoveNext();
                if (leftHasValue != rightHasValue) return false;
                if (!leftHasValue) return true;
                if (!Equals(leftEnumerator.Current, rightEnumerator.Current)) return false;
            }
        }
        finally
        {
            (leftEnumerator as IDisposable)?.Dispose();
            (rightEnumerator as IDisposable)?.Dispose();
        }
    }

    private static int JavaHashCode(object? value)
    {
        if (value is null) return 0;
        if (!IsJavaList(value)) return value.GetHashCode();
        unchecked
        {
            var result = 1;
            foreach (var element in (IEnumerable)value) result = 31 * result + JavaHashCode(element);
            return result;
        }
    }

    internal static JavaResourceBundle GetResourceBundle(string baseName, CultureInfo locale) => new(baseName, locale);

    internal static string GetResourceString(JavaResourceBundle bundle, string name) => bundle.GetString(name);

    internal static JavaCollector Joining(string delimiter) =>
        new(values => string.Join(delimiter, values.Select(JavaString)));

    internal static bool All(IEnumerable<int> values, Predicate<int> predicate) => values.All(value => predicate(value));

    internal static IEnumerable<T> Skip<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));

    internal static dynamic Collect<T>(IEnumerable<T> values, JavaCollector collector) => collector.Collect(values.Cast<object?>());

    internal static IEnumerable<TResult> Map<T, TResult>(IEnumerable<T> values, Func<T, TResult> mapper) => values.Select(mapper);
    internal static IEnumerable<T> Filter<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Where(predicate);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values) => values.OrderBy(value => value);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, IComparer<T> comparer) => values.OrderBy(value => value, comparer);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, Comparison<T> comparison) =>
        values.OrderBy(value => value, Comparer<T>.Create(comparison));
    internal static Comparison<T> NaturalOrder<T>() => Comparer<T>.Default.Compare;
    internal static Comparison<T> ComparingInt<T>(Func<T, int> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> Comparing<T>(Func<T, IComparable> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> ThenComparing<T>(Comparison<T> first, Comparison<T> second) =>
        (left, right) => { var result = first(left, right); return result != 0 ? result : second(left, right); };
    internal static Comparison<T> ReverseComparison<T>(Comparison<T> comparison) =>
        (left, right) => comparison(right, left);
    internal static T[] ToArray<T>(IEnumerable<T> values) => values.ToArray();
    internal static T[] ToArrayLoose<T>(System.Collections.IEnumerable values) =>
        values.Cast<object?>().Select(value => (T)value!).ToArray();
    internal static IList<T> ToListValues<T>(IEnumerable<T> values) => values.ToList();
    internal static void ForEach<T>(IEnumerable<T> values, Action<T> action)
    {
        foreach (var value in values) action(value);
    }
    internal static void ForEach<K, V>(IDictionary<K, V> values, Action<K, V> action) where K : notnull
    {
        foreach (var entry in values) action(entry.Key, entry.Value);
    }
    internal static void ForEach<K, V>(IReadOnlyDictionary<K, V> values, Action<K, V> action) where K : notnull
    {
        foreach (var entry in values) action(entry.Key, entry.Value);
    }
    internal static T? FirstOrDefault<T>(IEnumerable<T> values) => values.FirstOrDefault();
    internal static bool Any<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Any(predicate);
    internal static bool AllValues<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.All(predicate);
    internal static IEnumerable<T> ConcatValues<T>(IEnumerable<T> left, IEnumerable<T> right) => left.Concat(right);
    internal static IEnumerable<T> TakeValues<T>(IEnumerable<T> values, long count) => values.Take(checked((int)count));
    internal static IEnumerable<T> DropValues<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));
    internal static JavaOptional<int> MaxOptional(IEnumerable<int> values) =>
        values.Any() ? JavaOptional<int>.Of(values.Max()) : JavaOptional<int>.Empty();
    internal static JavaOptional<T> ReduceOptional<T>(IEnumerable<T> values, Func<T, T, T> reducer)
    {
        using var iterator = values.GetEnumerator();
        if (!iterator.MoveNext()) return JavaOptional<T>.Empty();
        var result = iterator.Current;
        while (iterator.MoveNext()) result = reducer(result, iterator.Current);
        return JavaOptional<T>.Of(result);
    }
    internal static Func<T, T> AndThen<T>(Func<T, T> first, Func<T, T> second) => value => second(first(value));
    internal static void ForEachRemaining<T>(IEnumerator<T> iterator, Action<T> action)
    {
        while (iterator.MoveNext()) action(iterator.Current);
    }

    internal static IEnumerable<T> LoadServices<T>(Type serviceType, params object?[] ignored) =>
        AppDomain.CurrentDomain.GetAssemblies()
            .SelectMany(assembly =>
            {
                try { return assembly.GetTypes(); }
                catch (ReflectionTypeLoadException error) { return error.Types.Where(type => type is not null)!; }
            })
            .Where(type => type is not null && !type.IsAbstract && serviceType.IsAssignableFrom(type))
            .Select(type => (T)Activator.CreateInstance(type!)!);

    internal static int HashCode(object? value) => JavaHashCode(value);
    internal static IEnumerable<T> Stream<T>(IEnumerable<T> values) => values;
    internal static IEnumerable<object> BoxValues<T>(IEnumerable<T> values) => values.Cast<object>();
    internal static JavaCollector ToList<T>() => new(values => values.Cast<T>().ToList());
    internal static JavaCollector ToSet<T>() => new(values => new HashSet<T>(values.Cast<T>()));
    internal static JavaCollector ToCollection<C>(Func<C> supplier)
    {
        return new JavaCollector(values =>
        {
            dynamic collection = supplier()!;
            foreach (var value in values) collection.Add((dynamic)value!);
            return collection;
        });
    }

    internal static bool Exists(string path) => File.Exists(path) || Directory.Exists(path);
    internal static bool IsDirectory(string path) => Directory.Exists(path);
    internal static void DeleteIfExists(string path)
    {
        if (File.Exists(path)) File.Delete(path);
        else if (Directory.Exists(path)) Directory.Delete(path);
    }
    internal static void CreateDirectories(string path) => Directory.CreateDirectory(path);
    internal static FileStream NewInputStream(string path, params object?[] _) => File.OpenRead(path);
    internal static string ReadString(string path, Encoding encoding) => File.ReadAllText(path, encoding);
    internal static string PathOf(string first, params string[] more) =>
        more.Length == 0 ? first : Path.Combine(new[] { first }.Concat(more).ToArray());
    internal static string PathOfUri(Uri uri) => uri.IsFile ? uri.LocalPath : uri.OriginalString;
    internal static string PathResolve(string basis, string value) => Path.Combine(basis, value);
    internal static string PathResolveSibling(string basis, string value) =>
        Path.Combine(Path.GetDirectoryName(basis) ?? string.Empty, value);
    internal static int PathNameCount(string path) => path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
        .Count(segment => !string.IsNullOrEmpty(segment));
    internal static string PathName(string path, int index) =>
        path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(segment => !string.IsNullOrEmpty(segment)).ElementAt(index);
    internal static int CompareUri(Uri left, Uri right) => string.CompareOrdinal(left.OriginalString, right.OriginalString);
    internal static Stream OpenStream(Uri uri) => new System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
    internal static sbyte[] ReadAllBytes(string path) => File.ReadAllBytes(path).Select(value => unchecked((sbyte)value)).ToArray();
    internal static sbyte[] ReadAllBytes(Stream stream)
    {
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes) =>
        new(bytes.Select(value => unchecked((byte)value)).ToArray());
    internal static sbyte[] ToSignedBytes(MemoryStream stream) =>
        stream.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    internal static string WriteString(string path, object value, params object?[] _)
    {
        File.WriteAllText(path, StringValueOf(value));
        return path;
    }
    internal static string Move(string source, string destination, params object?[] _)
    {
        File.Move(source, destination, true);
        return destination;
    }
    internal static string Copy(string source, string destination, params object?[] _)
    {
        File.Copy(source, destination, true);
        return destination;
    }
    internal static string Copy(Stream source, string destination, params object?[] _)
    {
        using var output = File.Create(destination);
        source.CopyTo(output);
        return destination;
    }
    internal static void SetPosixFilePermissions(string path, ISet<UnixFileMode> permissions)
    {
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, permissions.Aggregate((UnixFileMode)0, (mode, permission) => mode | permission));
    }
    internal static string CreateTempFile(string prefix, string suffix, params object?[] _)
    {
        var path = Path.Combine(Path.GetTempPath(), prefix + Guid.NewGuid().ToString("N") + suffix);
        using (File.Create(path)) { }
        return path;
    }
    internal static bool IsSymbolicLink(string path) =>
        (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
    internal static JavaDirectoryStream<string> NewDirectoryStream(string path) => new(path);
    internal static JavaDirectoryStream<string> List(string path) => new(path);
    internal static bool SequenceEqual<T>(IEnumerable<T> left, IEnumerable<T> right) => left.SequenceEqual(right);
    internal static string IterableString<T>(string label, IEnumerable<T> values) =>
        label + "(" + string.Join(", ", values.Select(value => StringValueOf(value))) + ")";
    internal static System.Net.IPAddress GetByName(string name) => System.Net.Dns.GetHostAddresses(name)[0];
    internal static bool GetBoolean(string name) => bool.TryParse(GetProperty(name), out var value) && value;
    internal static IList<T> NCopies<T>(int count, T value) => Enumerable.Repeat(value, count).ToList();
    internal static T Min<T>(T left, T right) where T : IComparable<T> => left.CompareTo(right) <= 0 ? left : right;
    internal static T Min<T>(IEnumerable<T> values) => values.Min(Comparer<T>.Default)!;

#if VIBEFORMER_PKL_CORE
    internal static global::Pkl.Core.Pair<object, object> ObjectPair<F, S>(global::Pkl.Core.Pair<F, S> pair) =>
        new(pair.GetFirst(), pair.GetSecond());
#endif
    internal static long DurationToMillis(TimeSpan value) => checked((long)value.TotalMilliseconds);
    internal static long DurationGetSeconds(TimeSpan value) => checked((long)value.TotalSeconds);
    internal static int DurationGetNano(TimeSpan value) => checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
#if VIBEFORMER_PKL_CORE
    internal static global::Pkl.Core.Runtime.GraalCollections.EconomicMap<K, V> CreateEconomicMap<K, V>() where K : notnull => new();
    internal static global::Pkl.Core.Runtime.GraalCollections.EconomicMap<K, V> CreateEconomicMap<K, V>(int capacity) where K : notnull => new(capacity);
    internal static global::Pkl.Core.Runtime.GraalCollections.UnmodifiableEconomicMap<K, V> EmptyEconomicMap<K, V>() where K : notnull =>
        new global::Pkl.Core.Runtime.GraalCollections.EconomicMap<K, V>();
    internal static global::System.Net.IPEndPoint NewIpEndPoint(string host, int port) =>
        new(global::System.Net.Dns.GetHostAddresses(host)[0], port);
    internal static global::System.Net.WebProxy NewWebProxy(
        global::Pkl.Core.Runtime.JavaProxyType type, global::System.Net.IPEndPoint endpoint) =>
        new(new UriBuilder("http", endpoint.Address.ToString(), endpoint.Port).Uri);
    internal static void VisitVmValue(dynamic visitor, object value)
    {
        if (value is global::Pkl.Core.Runtime.VmValue vmValue) vmValue.Accept(visitor);
        else if (value is string text) visitor.VisitString(text);
        else if (value is bool boolean) visitor.VisitBoolean(boolean);
        else if (value is long integer) visitor.VisitInt(integer);
        else if (value is double floating) visitor.VisitFloat(floating);
        else throw new ArgumentException("Unknown VM value type: " + value.GetType().FullName);
    }
#endif
    internal static V OrganicGet<K, V>(IDictionary<K, V> values, K key) where K : notnull => MapGet(values, key);
    internal static T OrganicGet<T>(IList<T> values, int index) => values[index];
    internal static V OrganicPut<K, V>(IDictionary<K, V> values, K key, V value) where K : notnull => MapPut(values, key, value);
    internal static ISet<T> OrganicPut<T>(ISet<T> values, T value) { values.Add(value); return values; }
    internal static Uri PathToUri(string path) => new(Path.GetFullPath(path));
}

#if VIBEFORMER_PKL_CORE
public
#else
internal
#endif
sealed class JavaRegexMatcher
{
    private readonly Regex regex;
    private readonly string input;
    private int regionStart;
    private int regionEnd;
    private int nextIndex;
    private int appendIndex;
    private Match? current;

    internal JavaRegexMatcher(Regex regex, string input)
    {
        this.regex = regex;
        this.input = input;
        regionEnd = input.Length;
    }

    private Match Current() => current ?? throw new InvalidOperationException("No successful match is available");

    private bool Accept(Match match)
    {
        if (!match.Success || match.Index + match.Length > regionEnd)
        {
            current = null;
            return false;
        }
        current = match;
        nextIndex = match.Length == 0 ? match.Index + 1 : match.Index + match.Length;
        return true;
    }

    internal bool Find() => nextIndex <= regionEnd && Accept(regex.Match(input, Math.Max(regionStart, nextIndex)));
    internal bool Matches()
    {
        var match = regex.Match(input, regionStart);
        return Accept(match) && match.Index == regionStart && match.Index + match.Length == regionEnd;
    }
    internal bool LookingAt()
    {
        var match = regex.Match(input, regionStart);
        return Accept(match) && match.Index == regionStart;
    }
    internal JavaRegexMatcher Region(int start, int end)
    {
        regionStart = start;
        regionEnd = end;
        nextIndex = start;
        current = null;
        return this;
    }
    internal string Group() => Current().Value;
    internal string Group(int index) => Current().Groups[index].Success ? Current().Groups[index].Value : null!;
    internal string Group(string name) => Current().Groups[name].Success ? Current().Groups[name].Value : null!;
    internal int GroupCount() => regex.GetGroupNumbers().Length - 1;
    internal int Start() => Current().Index;
    internal int Start(int index) => Current().Groups[index].Success ? Current().Groups[index].Index : -1;
    internal int End() => Current().Index + Current().Length;
    internal int End(int index) => Current().Groups[index].Success
        ? Current().Groups[index].Index + Current().Groups[index].Length
        : -1;
    internal string ReplaceAll(string replacement) => regex.Replace(input, replacement);
    internal string ReplaceFirst(string replacement) => regex.Replace(input, replacement, 1);
    internal JavaRegexMatcher AppendReplacement(StringBuilder buffer, string replacement)
    {
        var match = Current();
        buffer.Append(input, appendIndex, match.Index - appendIndex);
        buffer.Append(match.Result(replacement));
        appendIndex = match.Index + match.Length;
        return this;
    }
    internal StringBuilder AppendTail(StringBuilder buffer)
    {
        buffer.Append(input, appendIndex, input.Length - appendIndex);
        appendIndex = input.Length;
        return buffer;
    }
}

internal sealed class JavaDirectoryStream<T> : IEnumerable<T>, IDisposable
{
    private readonly IEnumerable<T> entries;
    internal JavaDirectoryStream(string path) => entries = Directory.EnumerateFileSystemEntries(path).Select(value => (T)(object)value);
    public IEnumerator<T> GetEnumerator() => entries.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void Dispose() { }
    internal void Close() => Dispose();
}

internal sealed class JavaDeque<T> : ICollection<T>
{
    private readonly LinkedList<T> values = new();
    internal T GetFirst() => values.First is { } first
        ? first.Value
        : throw new InvalidOperationException("Deque is empty");
    internal T? Peek() => values.First is null ? default : values.First.Value;
    internal T Pop()
    {
        var value = GetFirst();
        values.RemoveFirst();
        return value;
    }
    internal void Push(T value) => values.AddFirst(value);
    internal void AddFirst(T value) => values.AddFirst(value);
    internal bool IsEmpty() => values.Count == 0;
    internal IEnumerator<T> DescendingIterator() => values.Reverse().GetEnumerator();
    public int Count => values.Count;
    public bool IsReadOnly => false;
    public void Add(T item) => values.AddLast(item);
    public void Clear() => values.Clear();
    public bool Contains(T item) => values.Contains(item);
    public void CopyTo(T[] array, int arrayIndex) => values.CopyTo(array, arrayIndex);
    public bool Remove(T item) => values.Remove(item);
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaResourceBundle
{
    private readonly IReadOnlyDictionary<string, string> resources;

    internal JavaResourceBundle(string baseName, CultureInfo locale)
    {
        _ = locale;
        var resourceName = baseName + ".properties";
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new MissingManifestResourceException(resourceName);
        resources = ReadProperties(stream);
    }

    internal string GetString(string name) =>
        resources.TryGetValue(name, out var value) ? value : throw new MissingManifestResourceException(name);

    private static IReadOnlyDictionary<string, string> ReadProperties(Stream stream)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        using var reader = new StreamReader(stream, Encoding.Latin1, false, 1024, leaveOpen: true);
        var logicalLine = new StringBuilder();
        var continued = false;

        while (reader.ReadLine() is { } physicalLine)
        {
            if (continued) physicalLine = physicalLine.TrimStart(' ', '\t', '\f');
            logicalLine.Append(physicalLine);
            var slashCount = 0;
            for (var index = logicalLine.Length - 1; index >= 0 && logicalLine[index] == '\\'; index--) slashCount++;
            if (slashCount % 2 == 1)
            {
                logicalLine.Length--;
                continued = true;
                continue;
            }

            AddProperty(result, logicalLine.ToString());
            logicalLine.Clear();
            continued = false;
        }

        if (logicalLine.Length > 0) AddProperty(result, logicalLine.ToString());
        return result;
    }

    private static void AddProperty(IDictionary<string, string> properties, string line)
    {
        var start = 0;
        while (start < line.Length && char.IsWhiteSpace(line[start])) start++;
        if (start == line.Length || line[start] is '#' or '!') return;

        var escaped = false;
        var keyEnd = start;
        while (keyEnd < line.Length)
        {
            var current = line[keyEnd];
            if (!escaped && (current is '=' or ':' || char.IsWhiteSpace(current))) break;
            if (current == '\\' && !escaped) escaped = true;
            else escaped = false;
            keyEnd++;
        }

        var valueStart = keyEnd;
        while (valueStart < line.Length && char.IsWhiteSpace(line[valueStart])) valueStart++;
        if (valueStart < line.Length && line[valueStart] is '=' or ':') valueStart++;
        while (valueStart < line.Length && char.IsWhiteSpace(line[valueStart])) valueStart++;

        properties[Unescape(line[start..keyEnd])] = Unescape(line[valueStart..]);
    }

    private static string Unescape(string value)
    {
        var result = new StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (value[index] != '\\' || index + 1 == value.Length)
            {
                result.Append(value[index]);
                continue;
            }

            var escaped = value[++index];
            switch (escaped)
            {
                case 't': result.Append('\t'); break;
                case 'n': result.Append('\n'); break;
                case 'r': result.Append('\r'); break;
                case 'f': result.Append('\f'); break;
                case 'u':
                    if (index + 4 >= value.Length)
                        throw new FormatException("Incomplete Unicode escape in Java properties resource");
                    result.Append((char)Convert.ToInt32(value.Substring(index + 1, 4), 16));
                    index += 4;
                    break;
                default: result.Append(escaped); break;
            }
        }
        return result.ToString();
    }
}

internal sealed class JavaPrintWriter
{
    private readonly TextWriter writer;
    public JavaPrintWriter(TextWriter writer) => this.writer = writer;
    public void Print(object? value) => writer.Write(value);
    public void Println(object? value = null) => writer.WriteLine(value);
    public void Flush() => writer.Flush();
}

internal class JavaFormat
{
    internal virtual string Format(object? value) => value?.ToString() ?? "null";
}

internal sealed class JavaMessageFormat : JavaFormat
{
    private readonly string pattern;
    private readonly CultureInfo locale;
    internal JavaMessageFormat(string pattern, CultureInfo locale)
    {
        this.pattern = pattern;
        this.locale = locale;
    }
    internal override string Format(object? value)
    {
        var arguments = value as object?[] ?? new[] { value };
        var result = new StringBuilder(pattern.Length);
        var quoted = false;
        for (var index = 0; index < pattern.Length; index++)
        {
            var current = pattern[index];
            if (current == '\'')
            {
                if (index + 1 < pattern.Length && pattern[index + 1] == '\'')
                {
                    result.Append('\'');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && current == '{')
            {
                var close = pattern.IndexOf('}', index + 1);
                if (close < 0 || !int.TryParse(pattern.AsSpan(index + 1, close - index - 1),
                                               NumberStyles.None, CultureInfo.InvariantCulture,
                                               out var argumentIndex))
                    throw new FormatException("Invalid Java MessageFormat placeholder");
                if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                    throw new FormatException("Java MessageFormat argument index is out of range");
                result.Append(Convert.ToString(arguments[argumentIndex], locale) ?? "null");
                index = close;
                continue;
            }
            result.Append(current);
        }
        return result.ToString();
    }
}

internal sealed class JavaCollector
{
    private readonly Func<IEnumerable<object?>, object> collector;
    internal JavaCollector(Func<IEnumerable<object?>, object> collector) => this.collector = collector;
    internal object Collect(IEnumerable<object?> values) => collector(values);
}

internal sealed class JavaArrayList<T> : Collection<T>
{
    internal JavaArrayList(IList<T> values) : base(values) { }
}

internal sealed class JavaSubList<T> : IList<T>
{
    private readonly IList<T> source;
    private int offset;
    private int count;
    internal JavaSubList(IList<T> source, int fromIndex, int toIndex)
    {
        if (fromIndex < 0 || toIndex > source.Count || fromIndex > toIndex) throw new ArgumentOutOfRangeException();
        this.source = source;
        offset = fromIndex;
        count = toIndex - fromIndex;
    }
    public T this[int index] { get => source[Checked(index)]; set => source[Checked(index)] = value; }
    public int Count => count;
    public bool IsReadOnly => source.IsReadOnly;
    public void Add(T item) => Insert(count, item);
    public void Clear() { for (var index = count - 1; index >= 0; index--) RemoveAt(index); }
    public bool Contains(T item) => this.Any(value => EqualityComparer<T>.Default.Equals(value, item));
    public void CopyTo(T[] array, int arrayIndex) { foreach (var item in this) array[arrayIndex++] = item; }
    public IEnumerator<T> GetEnumerator() { for (var index = 0; index < count; index++) yield return source[offset + index]; }
    public int IndexOf(T item) { var index = 0; foreach (var value in this) { if (EqualityComparer<T>.Default.Equals(value, item)) return index; index++; } return -1; }
    public void Insert(int index, T item) { if (index < 0 || index > count) throw new ArgumentOutOfRangeException(nameof(index)); source.Insert(offset + index, item); count++; }
    public bool Remove(T item) { var index = IndexOf(item); if (index < 0) return false; RemoveAt(index); return true; }
    public void RemoveAt(int index) { source.RemoveAt(Checked(index)); count--; }
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    private int Checked(int index) => index >= 0 && index < count ? offset + index : throw new ArgumentOutOfRangeException(nameof(index));
}
