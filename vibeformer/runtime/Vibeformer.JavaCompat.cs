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

namespace Vibeformer.Runtime;

internal delegate TResult JavaIntFunction<out TResult>(int value);
internal delegate int JavaToIntFunction<in TValue>(TValue value);

internal readonly struct JavaOptional<T>
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
}

internal static class JavaCompat
{
    internal static readonly TextWriter @out = Console.Out;
    internal static readonly TextWriter err = Console.Error;
    private static readonly Dictionary<string, string> SystemProperties = new(StringComparer.Ordinal);

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

    internal static int ParseInt(string value, int radix) => Convert.ToInt32(value, radix);

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

    internal static void ArrayCopy(object source, int sourceIndex, object destination, int destinationIndex, int length) =>
        Array.Copy((Array)source, sourceIndex, (Array)destination, destinationIndex, length);

    internal static IDictionary<string, string> GetEnvironment() =>
        Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? string.Empty,
                          StringComparer.Ordinal);

    internal static IDictionary<object, object> GetProperties() =>
        SystemProperties.ToDictionary(entry => (object)entry.Key, entry => (object)entry.Value);

    internal static string? GetProperty(string name) =>
        SystemProperties.TryGetValue(name, out var value) ? value : null;

    internal static string? SetProperty(string name, string value)
    {
        var previous = GetProperty(name);
        SystemProperties[name] = value;
        return previous;
    }

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

    internal static IList<T> ListOf<T>(params T[] values) => new ReadOnlyCollection<T>(values);

    internal static IList<T> AsList<T>(params T[] values) => new JavaArrayList<T>(values);

    internal static IList<T> UnmodifiableList<T>(IEnumerable<T> values) =>
        new ReadOnlyCollection<T>(values is IList<T> list ? list : values.ToList());

    internal static IList<T> SubList<T>(IEnumerable<T> values, int fromIndex, int toIndex) =>
        new JavaSubList<T>(values is IList<T> list ? list : values.ToList(), fromIndex, toIndex);

    internal static int ListCount<T>(IEnumerable<T> values) => values.Count();

    internal static bool ListIsEmpty<T>(IEnumerable<T> values) => !values.Any();

    internal static T ListGet<T>(IEnumerable<T> values, int index) =>
        values is IList<T> list ? list[index] : values.ElementAt(index);

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

    internal static JavaCollector Joining(string delimiter) => new(delimiter);

    internal static bool All(IEnumerable<int> values, Predicate<int> predicate) => values.All(value => predicate(value));

    internal static IEnumerable<T> Skip<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));

    internal static dynamic Collect<T>(IEnumerable<T> values, JavaCollector collector) =>
        string.Join(collector.Delimiter, values.Select(value => JavaString(value)));

    internal static IEnumerable<TResult> Map<T, TResult>(IEnumerable<T> values, Func<T, TResult> mapper) => values.Select(mapper);

    internal static IEnumerable<T> LoadServices<T>(Type serviceType, params object?[] ignored) =>
        AppDomain.CurrentDomain.GetAssemblies()
            .SelectMany(assembly =>
            {
                try { return assembly.GetTypes(); }
                catch (ReflectionTypeLoadException error) { return error.Types.Where(type => type is not null)!; }
            })
            .Where(type => type is not null && !type.IsAbstract && serviceType.IsAssignableFrom(type))
            .Select(type => (T)Activator.CreateInstance(type!)!);
}

internal sealed class JavaDeque<T>
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
    internal string Delimiter { get; }
    internal JavaCollector(string delimiter) => Delimiter = delimiter;
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
