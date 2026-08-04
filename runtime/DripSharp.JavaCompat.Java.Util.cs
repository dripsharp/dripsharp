// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Util

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaPriorityQueue<T>
{
    private readonly List<T> values;
    private readonly IComparer<T> comparer = Comparer<T>.Default;

    public JavaPriorityQueue() : this(0)
    {
    }

    public JavaPriorityQueue(int initialCapacity)
    {
        if (initialCapacity < 0) throw new ArgumentException("Initial capacity cannot be negative.");
        values = new List<T>(initialCapacity);
    }

    public int Count => values.Count;

    public bool Add(T value)
    {
        values.Add(value);
        var index = values.Count - 1;
        while (index > 0)
        {
            var parent = (index - 1) / 2;
            if (comparer.Compare(values[index], values[parent]) >= 0) break;
            (values[index], values[parent]) = (values[parent], values[index]);
            index = parent;
        }
        return true;
    }

    public T? Peek() => values.Count == 0 ? default : values[0];

    public T? Poll()
    {
        if (values.Count == 0) return default;
        var result = values[0];
        var last = values[^1];
        values.RemoveAt(values.Count - 1);
        if (values.Count == 0) return result;
        values[0] = last;
        var index = 0;
        while (true)
        {
            var left = index * 2 + 1;
            if (left >= values.Count) break;
            var right = left + 1;
            var child = right < values.Count &&
                        comparer.Compare(values[right], values[left]) < 0
                ? right
                : left;
            if (comparer.Compare(values[index], values[child]) <= 0) break;
            (values[index], values[child]) = (values[child], values[index]);
            index = child;
        }
        return result;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaIdentityHashMap<K, V> : Dictionary<K, V> where K : notnull
{
    private sealed class IdentityComparer : IEqualityComparer<K>
    {
        public bool Equals(K? left, K? right) => ReferenceEquals(left, right);
        public int GetHashCode(K value) =>
            System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);
    }

    public JavaIdentityHashMap() : base(new IdentityComparer())
    {
    }
}

internal sealed class JavaMapBackedSet<T> : ISet<T> where T : notnull
{
    private readonly IDictionary<T, bool> map;

    internal JavaMapBackedSet(IDictionary<T, bool> map)
    {
        ArgumentNullException.ThrowIfNull(map);
        if (map.Count != 0) throw new ArgumentException("Backing map must be empty.");
        this.map = map;
    }

    public int Count => map.Count;
    public bool IsReadOnly => map.IsReadOnly;
    public bool Add(T item)
    {
        if (map.ContainsKey(item)) return false;
        map.Add(item, true);
        return true;
    }
    void ICollection<T>.Add(T item) => Add(item);
    public void Clear() => map.Clear();
    public bool Contains(T item) => map.ContainsKey(item);
    public void CopyTo(T[] array, int arrayIndex) => map.Keys.CopyTo(array, arrayIndex);
    public bool Remove(T item) => map.Remove(item);
    public IEnumerator<T> GetEnumerator() => map.Keys.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void ExceptWith(IEnumerable<T> other)
    {
        foreach (var item in other) Remove(item);
    }
    public void IntersectWith(IEnumerable<T> other)
    {
        var retained = new HashSet<T>(other);
        foreach (var item in map.Keys.ToArray())
            if (!retained.Contains(item)) Remove(item);
    }
    public bool IsProperSubsetOf(IEnumerable<T> other) => map.Keys.ToHashSet().IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<T> other) => map.Keys.ToHashSet().IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<T> other) => map.Keys.ToHashSet().IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<T> other) => map.Keys.ToHashSet().IsSupersetOf(other);
    public bool Overlaps(IEnumerable<T> other) => map.Keys.ToHashSet().Overlaps(other);
    public bool SetEquals(IEnumerable<T> other) => map.Keys.ToHashSet().SetEquals(other);
    public void SymmetricExceptWith(IEnumerable<T> other)
    {
        foreach (var item in other.ToArray())
            if (!Remove(item)) Add(item);
    }
    public void UnionWith(IEnumerable<T> other)
    {
        foreach (var item in other) Add(item);
    }
}

internal delegate TResult JavaIntFunction<out TResult>(int value);
internal delegate int JavaToIntFunction<in TValue>(TValue value);
internal delegate long JavaToLongFunction<in TValue>(TValue value);
#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
delegate bool JavaBiPredicate<in TLeft, in TRight>(TLeft left, TRight right);

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaLogLevel
{
    internal static readonly JavaLogLevel All = new("ALL", int.MinValue);
    internal static readonly JavaLogLevel Finest = new("FINEST", 300);
    internal static readonly JavaLogLevel Finer = new("FINER", 400);
    internal static readonly JavaLogLevel Fine = new("FINE", 500);
    internal static readonly JavaLogLevel Config = new("CONFIG", 700);
    internal static readonly JavaLogLevel Info = new("INFO", 800);
    internal static readonly JavaLogLevel Warning = new("WARNING", 900);
    internal static readonly JavaLogLevel Severe = new("SEVERE", 1000);
    internal static readonly JavaLogLevel Off = new("OFF", int.MaxValue);

    internal string Name { get; }
    internal int Value { get; }

    private JavaLogLevel(string name, int value)
    {
        Name = name;
        Value = value;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaLogger
{
    private static readonly ConcurrentDictionary<string, JavaLogger> Loggers = new();
    private readonly string name;
    private JavaLogLevel? level;

    private JavaLogger(string name) => this.name = name;

    internal static JavaLogger GetLogger(string name)
    {
        ArgumentNullException.ThrowIfNull(name);
        return Loggers.GetOrAdd(name, static value => new JavaLogger(value));
    }

    internal bool IsLoggable(JavaLogLevel candidate)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        var threshold = level ?? JavaLogLevel.Info;
        return threshold != JavaLogLevel.Off && candidate.Value >= threshold.Value;
    }

    internal void SetLevel(JavaLogLevel? value) => level = value;

    internal void Fine(string message) => Log(JavaLogLevel.Fine, message);
    internal void Info(string message) => Log(JavaLogLevel.Info, message);
    internal void Warning(string message) => Log(JavaLogLevel.Warning, message);

    internal void Log(JavaLogLevel candidate, string message) =>
        Log(candidate, message, null);

    internal void Log(JavaLogLevel candidate, string message, Exception? error)
    {
        ArgumentNullException.ThrowIfNull(message);
        if (!IsLoggable(candidate)) return;
        var rendered = $"{candidate.Name}: {name}: {message}";
        if (error is not null) rendered += Environment.NewLine + error;
        if (candidate.Value >= JavaLogLevel.Severe.Value)
            Trace.TraceError(rendered);
        else if (candidate.Value >= JavaLogLevel.Warning.Value)
            Trace.TraceWarning(rendered);
        else
            Trace.TraceInformation(rendered);
    }
}

internal interface IJavaEconomicMapCursor<out K, out V>
{
    bool Advance();
    K GetKey();
    V GetValue();
}

internal interface IJavaEconomicMap<K, out V> where K : notnull
{
    V? Get(K key);
    bool ContainsKey(K key);
    int Size();
    IJavaEconomicMapCursor<K, V> GetEntries();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
static class JavaBase64
{
    private static readonly JavaBase64Decoder Decoder = new();
    private static readonly JavaBase64Encoder Encoder = new();

    public static JavaBase64Decoder GetDecoder() => Decoder;
    public static JavaBase64Encoder GetEncoder() => Encoder;
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaBase64Decoder
{
    public sbyte[] Decode(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        try
        {
            return JavaCompat.ToSignedBytes(Convert.FromBase64String(value));
        }
        catch (FormatException error)
        {
            const string alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
            var invalid = value.FirstOrDefault(character => !alphabet.Contains(character));
            var message = invalid == default
                ? error.Message
                : $"Illegal base64 character {((int)invalid):x}";
            throw new ArgumentException(message, error);
        }
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaBase64Encoder
{
    public string EncodeToString(sbyte[] value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return Convert.ToBase64String(JavaCompat.ToUnsignedBytes(value));
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaWeakHashMap<K, V> : IDictionary<K, V> where K : class
{
    private sealed class Entry
    {
        internal Entry(K key, V value)
        {
            Key = new WeakReference<K>(key);
            Value = value;
        }

        internal WeakReference<K> Key { get; }
        internal V Value { get; set; }
    }

    private readonly List<Entry> entries = new();

    public V this[K key]
    {
        get => TryGetValue(key, out var value)
            ? value
            : throw new KeyNotFoundException();
        set
        {
            ArgumentNullException.ThrowIfNull(key);
            if (Find(key) is { } entry)
                entry.Value = value;
            else
                entries.Add(new Entry(key, value));
        }
    }

    public ICollection<K> Keys => Snapshot()
        .Select(pair => pair.Key)
        .ToArray();

    public ICollection<V> Values => Snapshot()
        .Select(pair => pair.Value)
        .ToArray();

    public int Count
    {
        get
        {
            RemoveCollectedEntries();
            return entries.Count;
        }
    }

    public bool IsReadOnly => false;

    public void Add(K key, V value)
    {
        ArgumentNullException.ThrowIfNull(key);
        if (ContainsKey(key)) throw new ArgumentException("An item with the same key already exists.");
        entries.Add(new Entry(key, value));
    }

    public bool ContainsKey(K key)
    {
        ArgumentNullException.ThrowIfNull(key);
        return Find(key) is not null;
    }

    public bool Remove(K key)
    {
        ArgumentNullException.ThrowIfNull(key);
        RemoveCollectedEntries();
        for (var index = 0; index < entries.Count; index++)
        {
            if (entries[index].Key.TryGetTarget(out var candidate) &&
                JavaCompat.Equals(candidate, key))
            {
                entries.RemoveAt(index);
                return true;
            }
        }
        return false;
    }

    public bool TryGetValue(K key, out V value)
    {
        ArgumentNullException.ThrowIfNull(key);
        if (Find(key) is { } entry)
        {
            value = entry.Value;
            return true;
        }
        value = default!;
        return false;
    }

    public void Add(KeyValuePair<K, V> item) => Add(item.Key, item.Value);

    public void Clear() => entries.Clear();

    public bool Contains(KeyValuePair<K, V> item) =>
        TryGetValue(item.Key, out var value) &&
        EqualityComparer<V>.Default.Equals(value, item.Value);

    public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex) =>
        Snapshot().CopyTo(array, arrayIndex);

    public bool Remove(KeyValuePair<K, V> item) =>
        Contains(item) && Remove(item.Key);

    public IEnumerator<KeyValuePair<K, V>> GetEnumerator() =>
        Snapshot().GetEnumerator();

    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() =>
        GetEnumerator();

    private Entry? Find(K key)
    {
        RemoveCollectedEntries();
        foreach (var entry in entries)
        {
            if (entry.Key.TryGetTarget(out var candidate) &&
                JavaCompat.Equals(candidate, key))
            {
                return entry;
            }
        }
        return null;
    }

    private List<KeyValuePair<K, V>> Snapshot()
    {
        RemoveCollectedEntries();
        var snapshot = new List<KeyValuePair<K, V>>(entries.Count);
        foreach (var entry in entries)
        {
            if (entry.Key.TryGetTarget(out var key))
                snapshot.Add(new KeyValuePair<K, V>(key, entry.Value));
        }
        return snapshot;
    }

    private void RemoveCollectedEntries()
    {
        for (var index = entries.Count - 1; index >= 0; index--)
        {
            if (!entries[index].Key.TryGetTarget(out _))
                entries.RemoveAt(index);
        }
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaStack<T> : IEnumerable<T>
{
    private readonly List<T> values = new();

    public int Count => values.Count;
    public bool IsEmpty => values.Count == 0;

    public T Push(T value)
    {
        values.Add(value);
        return value;
    }

    public T Pop()
    {
        if (values.Count == 0) throw new InvalidOperationException("Stack is empty.");
        var index = values.Count - 1;
        var value = values[index];
        values.RemoveAt(index);
        return value;
    }

    public T Peek() =>
        values.Count == 0
            ? throw new InvalidOperationException("Stack is empty.")
            : values[^1];

    public T Get(int index) => values[index];

    public bool AddAll(IEnumerable<T> additions)
    {
        ArgumentNullException.ThrowIfNull(additions);
        var originalCount = values.Count;
        values.AddRange(additions);
        return values.Count != originalCount;
    }

    public IList<T> SubList(int fromIndex, int toIndex)
    {
        if (fromIndex < 0 || toIndex < fromIndex || toIndex > values.Count)
            throw new ArgumentOutOfRangeException();
        return values.GetRange(fromIndex, toIndex - fromIndex);
    }

    public void Clear() => values.Clear();

    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaHashtable<K, V> : IDictionary<K, V> where K : notnull
{
    private readonly Dictionary<K, V> values = new();
    private readonly object sync = new();

    private static K RequireKey(K key) =>
        key is null ? throw new ArgumentNullException(nameof(key)) : key;

    private static V RequireValue(V value) =>
        value is null ? throw new ArgumentNullException(nameof(value)) : value;

    public V this[K key]
    {
        get { lock (sync) return values[RequireKey(key)]; }
        set { lock (sync) values[RequireKey(key)] = RequireValue(value); }
    }

    public ICollection<K> Keys
    {
        get { lock (sync) return values.Keys.ToArray(); }
    }

    public ICollection<V> Values
    {
        get { lock (sync) return values.Values.ToArray(); }
    }

    public int Count
    {
        get { lock (sync) return values.Count; }
    }

    public bool IsReadOnly => false;

    public void Add(K key, V value)
    {
        lock (sync) values.Add(RequireKey(key), RequireValue(value));
    }

    public void Add(KeyValuePair<K, V> item) => Add(item.Key, item.Value);

    public void Clear()
    {
        lock (sync) values.Clear();
    }

    public bool Contains(KeyValuePair<K, V> item)
    {
        lock (sync)
            return ((ICollection<KeyValuePair<K, V>>)values).Contains(item);
    }

    public bool ContainsKey(K key)
    {
        lock (sync) return values.ContainsKey(RequireKey(key));
    }

    public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex)
    {
        lock (sync)
            ((ICollection<KeyValuePair<K, V>>)values).CopyTo(array, arrayIndex);
    }

    public IEnumerator<KeyValuePair<K, V>> GetEnumerator()
    {
        lock (sync) return values.ToArray().AsEnumerable().GetEnumerator();
    }

    public bool Remove(K key)
    {
        lock (sync) return values.Remove(RequireKey(key));
    }

    public bool Remove(KeyValuePair<K, V> item)
    {
        lock (sync)
            return ((ICollection<KeyValuePair<K, V>>)values).Remove(item);
    }

    public bool TryGetValue(K key, out V value)
    {
        lock (sync)
        {
            if (values.TryGetValue(RequireKey(key), out var found))
            {
                value = found;
                return true;
            }
            value = default!;
            return false;
        }
    }

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaBitSet
{
    private readonly HashSet<int> values = new();
    internal void clear() => values.Clear();
    internal void clear(int index) => values.Remove(index);
    internal bool get(int index) => values.Contains(index);
    internal int nextSetBit(int fromIndex) =>
        values.Where(value => value >= fromIndex).DefaultIfEmpty(-1).Min();
    internal void set(int index) => values.Add(index);
    internal void set(int fromIndex, int toIndex)
    {
        for (var index = fromIndex; index < toIndex; index++) values.Add(index);
    }
}

internal sealed class JavaInflaterOutputStream : Stream
{
    private readonly Stream destination;
    private readonly MemoryStream compressed = new();
    private int emitted;
    private bool disposed;

    internal JavaInflaterOutputStream(Stream destination) => this.destination = destination;

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => !disposed;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush()
    {
        var position = compressed.Position;
        compressed.Position = 0;
        using (var inflater = new ZLibStream(compressed, CompressionMode.Decompress, leaveOpen: true))
        using (var decoded = new MemoryStream())
        {
            inflater.CopyTo(decoded);
            var bytes = decoded.ToArray();
            if (bytes.Length > emitted)
                destination.Write(bytes, emitted, bytes.Length - emitted);
            emitted = bytes.Length;
        }
        compressed.Position = position;
        destination.Flush();
    }
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => compressed.Write(buffer, offset, count);

    protected override void Dispose(bool disposing)
    {
        if (!disposing || disposed) return;
        disposed = true;
        Flush();
        compressed.Dispose();
        destination.Dispose();
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaInflater
{
    private readonly MemoryStream compressed = new();
    private readonly bool rawDeflate;
    private int emitted;
    private bool needsInput = true;
    private bool ended;

    internal JavaInflater(bool nowrap) => rawDeflate = nowrap;

    public bool Finished() => false;
    public bool NeedsInput() => needsInput;

    public void SetInput(sbyte[] input, int offset, int length)
    {
        ObjectDisposedException.ThrowIf(ended, this);
        ArgumentNullException.ThrowIfNull(input);
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfNegative(length);
        if (offset > input.Length - length)
            throw new ArgumentException("The input range exceeds the supplied buffer.");
        compressed.Position = compressed.Length;
        compressed.Write(JavaCompat.ToUnsignedBytes(input), offset, length);
        needsInput = false;
    }

    public int Inflate(sbyte[] output)
    {
        ObjectDisposedException.ThrowIf(ended, this);
        ArgumentNullException.ThrowIfNull(output);
        if (output.Length == 0) return 0;

        var inputPosition = compressed.Position;
        compressed.Position = 0;
        using var decoded = new MemoryStream();
        using (Stream inflater = rawDeflate
                   ? new DeflateStream(compressed, CompressionMode.Decompress, leaveOpen: true)
                   : new ZLibStream(compressed, CompressionMode.Decompress, leaveOpen: true))
        {
            inflater.CopyTo(decoded);
        }
        compressed.Position = inputPosition;

        var available = checked((int)decoded.Length - emitted);
        if (available <= 0)
        {
            needsInput = true;
            return 0;
        }
        var count = Math.Min(output.Length, available);
        var bytes = decoded.GetBuffer();
        for (var index = 0; index < count; index++)
            output[index] = unchecked((sbyte)bytes[emitted + index]);
        emitted += count;
        needsInput = count == available;
        return count;
    }

    public void End()
    {
        if (ended) return;
        ended = true;
        compressed.Dispose();
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaDeflater
{
    public const int DEFAULT_COMPRESSION = -1;
    public const int BEST_COMPRESSION = 9;

    internal JavaDeflater(int level)
    {
        if (level is < DEFAULT_COMPRESSION or > BEST_COMPRESSION)
            throw new ArgumentOutOfRangeException(nameof(level));
        CompressionLevel = level switch
        {
            <= 1 => System.IO.Compression.CompressionLevel.Fastest,
            >= 8 => System.IO.Compression.CompressionLevel.SmallestSize,
            _ => System.IO.Compression.CompressionLevel.Optimal
        };
    }

    internal System.IO.Compression.CompressionLevel CompressionLevel { get; }
    public void End()
    {
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaDeflaterOutputStream : JavaOutputStream
{
    private readonly ZLibStream compressed;

    internal JavaDeflaterOutputStream(Stream destination, JavaDeflater deflater)
    {
        ArgumentNullException.ThrowIfNull(destination);
        ArgumentNullException.ThrowIfNull(deflater);
        compressed = new ZLibStream(destination, deflater.CompressionLevel, leaveOpen: true);
    }

    public override void Write(int value) => compressed.WriteByte(unchecked((byte)value));
    public override void Write(sbyte[] buffer, int offset, int count) =>
        compressed.Write(JavaCompat.ToUnsignedBytes(buffer), offset, count);
    public override void Flush() => compressed.Flush();

    public override void Dispose()
    {
        compressed.Dispose();
        base.Dispose();
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaRandom
{
    public void NextBytes(sbyte[] destination)
    {
        ArgumentNullException.ThrowIfNull(destination);
        RandomNumberGenerator.Fill(MemoryMarshal.AsBytes(destination.AsSpan()));
    }

    public int NextInt()
    {
        Span<byte> bytes = stackalloc byte[sizeof(int)];
        RandomNumberGenerator.Fill(bytes);
        return BitConverter.ToInt32(bytes);
    }

    public long NextLong()
    {
        Span<byte> bytes = stackalloc byte[sizeof(long)];
        RandomNumberGenerator.Fill(bytes);
        return BitConverter.ToInt64(bytes);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCrc32
{
    private uint crc = uint.MaxValue;

    public void Update(sbyte[] values, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || length < 0 || offset > values.Length - length)
            throw new IndexOutOfRangeException();
        for (var index = offset; index < offset + length; index++)
        {
            crc ^= unchecked((byte)values[index]);
            for (var bit = 0; bit < 8; bit++)
            {
                crc = (crc >> 1) ^ ((crc & 1) == 0 ? 0u : 0xedb88320u);
            }
        }
    }

    public long GetValue() => crc ^ uint.MaxValue;
}

internal sealed class JavaProperties
{
    private readonly Dictionary<string, string> values = new(StringComparer.Ordinal);

    internal void Load(Stream stream)
    {
        using var reader = new StreamReader(stream, Encoding.Latin1, false, 1024, leaveOpen: true);
        string? pending = null;
        while (reader.ReadLine() is { } physicalLine)
        {
            var line = pending is null ? physicalLine : pending + physicalLine.TrimStart();
            var trailingSlashes = line.Reverse().TakeWhile(character => character == '\\').Count();
            if ((trailingSlashes & 1) == 1)
            {
                pending = line[..^1];
                continue;
            }
            pending = null;
            var trimmed = line.TrimStart();
            if (trimmed.Length == 0 || trimmed[0] is '#' or '!') continue;
            var separator = -1;
            var escaped = false;
            for (var index = 0; index < trimmed.Length; index++)
            {
                var character = trimmed[index];
                if (!escaped && (character is '=' or ':' || char.IsWhiteSpace(character)))
                {
                    separator = index;
                    break;
                }
                escaped = !escaped && character == '\\';
                if (character != '\\') escaped = false;
            }
            var key = separator < 0 ? trimmed : trimmed[..separator];
            var valueStart = separator < 0 ? trimmed.Length : separator;
            while (valueStart < trimmed.Length && char.IsWhiteSpace(trimmed[valueStart])) valueStart++;
            if (valueStart < trimmed.Length && trimmed[valueStart] is '=' or ':') valueStart++;
            while (valueStart < trimmed.Length && char.IsWhiteSpace(trimmed[valueStart])) valueStart++;
            values[Unescape(key)] = Unescape(trimmed[valueStart..]);
        }
    }

    internal string? GetProperty(string key) => values.TryGetValue(key, out var value) ? value : null;
    internal string? GetProperty(string key, string? fallback) =>
        values.TryGetValue(key, out var value) ? value : fallback;

    private static string Unescape(string value) => Regex.Replace(
        value,
        @"\\(u[0-9A-Fa-f]{4}|.)",
        match => match.Groups[1].Value switch
        {
            "t" => "\t",
            "n" => "\n",
            "r" => "\r",
            "f" => "\f",
            var escaped when escaped.StartsWith('u') =>
                ((char)Convert.ToInt32(escaped[1..], 16)).ToString(),
            var escaped => escaped
        });
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface IJavaOptional
{
    bool HasValue { get; }
    object? BoxedValue { get; }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaOptional<T> : IJavaOptional
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
    internal T OrElseGet(Func<T> supplier) => present ? value! : supplier();
    internal void IfPresent(Action<T> action) { if (present) action(value!); }
    internal void IfPresentOrElse(Action<T> action, Action emptyAction) { if (present) action(value!); else emptyAction(); }
    internal T OrElseThrow() => Get();
    internal T OrElseThrow(Func<Exception> exceptionSupplier) =>
        present ? value! : throw exceptionSupplier();
    internal JavaOptional<R> Map<R>(Func<T, R> mapper) => present ? JavaOptional<R>.OfNullable(mapper(value!)) : JavaOptional<R>.Empty();
    internal R Match<R>(Func<T, R> presentCase, Func<R> emptyCase) =>
        present ? presentCase(value!) : emptyCase();
    bool IJavaOptional.HasValue => present;
    object? IJavaOptional.BoxedValue => value;
    public override bool Equals(object? other) =>
        other is IJavaOptional optional && present == optional.HasValue &&
        (!present || JavaCompat.Equals(value, optional.BoxedValue));
    public override int GetHashCode() => present ? JavaCompat.HashCode(value) : 0;
}

// A Java Map.Entry is a reference object whose value can remain backed by the
// source map. KeyValuePair cannot model setValue(), so translated declarations
// use this reusable compatibility type instead of taking an entry snapshot.
internal interface JavaMapValueUpdater<K, V>
{
    void ReplaceValueWithoutAccess(K key, V value);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaMapEntry<K, V>
{
    private readonly IDictionary<K, V>? source;
    private readonly K key;
    private V value;
    private readonly bool mutable;

    protected JavaMapEntry()
    {
        key = default!;
        value = default!;
    }

    internal JavaMapEntry(IDictionary<K, V> source, K key)
    {
        this.source = source;
        this.key = key;
        value = source.TryGetValue(key, out var current) ? current : default!;
    }

    internal JavaMapEntry(K key, V value)
        : this(key, value, mutable: false)
    {
    }

    protected JavaMapEntry(K key, V value, bool mutable)
    {
        this.key = key;
        this.value = value;
        this.mutable = mutable;
    }

    public virtual K Key => key;
    public virtual V Value => source is not null && source.TryGetValue(key, out var current)
        ? current
        : value;

    public virtual V SetValue(V replacement)
    {
        if (source is null)
        {
            if (!mutable) throw new NotSupportedException("This Java map entry is immutable.");
            var previousValue = value;
            value = replacement;
            return previousValue;
        }
        var previous = Value;
        if (source is JavaMapValueUpdater<K, V> linked)
            linked.ReplaceValueWithoutAccess(key, replacement);
        else
            source[key] = replacement;
        value = replacement;
        return previous;
    }

    public override bool Equals(object? other)
    {
        return other is JavaMapEntry<K, V> entry &&
               JavaCompat.Equals(Key, entry.Key) &&
               JavaCompat.Equals(Value, entry.Value);
    }

    public override int GetHashCode() =>
        JavaCompat.HashCode(Key) ^ JavaCompat.HashCode(Value);

    public override string ToString() => $"{Key}={Value}";
}

internal sealed class JavaSimpleEntry<K, V> : JavaMapEntry<K, V> where K : notnull
{
    internal JavaSimpleEntry(K key, V value) : base(key, value, mutable: true) { }
}

internal sealed class JavaSimpleImmutableEntry<K, V> : JavaMapEntry<K, V> where K : notnull
{
    internal JavaSimpleImmutableEntry(K key, V value) : base(key, value, mutable: false) { }
}

internal interface JavaRemovableIterator
{
    void MarkReturned();
    void Remove();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaIterator<out T>
{
    bool HasNext();
    [return: System.Diagnostics.CodeAnalysis.MaybeNull]
    T Next();
    void Remove() => throw new NotSupportedException(
        "This Java iterator does not expose mutable removal semantics.");
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaIterableContract<out T> : IEnumerable<T>
{
    JavaIterator<T> Iterator();

    IEnumerator<T> IEnumerable<T>.GetEnumerator()
    {
        var iterator = Iterator();
        while (iterator.HasNext())
            yield return iterator.Next()!;
    }

    IEnumerator IEnumerable.GetEnumerator() =>
        ((IEnumerable<T>)this).GetEnumerator();
}

internal sealed class JavaIterableAdapter<T> : JavaIterableContract<T>
{
    private readonly Func<JavaIterator<T>> iteratorFactory;

    internal JavaIterableAdapter(Func<JavaIterator<T>> iteratorFactory) =>
        this.iteratorFactory = iteratorFactory ??
            throw new ArgumentNullException(nameof(iteratorFactory));

    public JavaIterator<T> Iterator() => iteratorFactory();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaListContract<T> : IList<T>
{
    int Size();
    bool Contains(object? value);
    JavaIterator<T> Iterator();
    new bool Add(T value);
    bool Remove(object? value);
    T Get(int index);
    T Set(int index, T value);
    void Add(int index, T value);
    T Remove(int index);
    int IndexOf(object? value);
    new void Clear();

    int ICollection<T>.Count => Size();
    bool ICollection<T>.IsReadOnly => false;

    T IList<T>.this[int index]
    {
        get => Get(index);
        set => Set(index, value);
    }

    void ICollection<T>.Add(T item) => Add(item);
    bool ICollection<T>.Contains(T item) => Contains(item);

    void ICollection<T>.CopyTo(T[] array, int arrayIndex)
    {
        ArgumentNullException.ThrowIfNull(array);
        foreach (var item in (IEnumerable<T>)this)
            array[arrayIndex++] = item;
    }

    bool ICollection<T>.Remove(T item) => Remove(item);
    int IList<T>.IndexOf(T item) => IndexOf(item);
    void IList<T>.Insert(int index, T item) => Add(index, item);
    void IList<T>.RemoveAt(int index) => Remove(index);

    IEnumerator<T> IEnumerable<T>.GetEnumerator()
    {
        var iterator = Iterator();
        while (iterator.HasNext())
            yield return iterator.Next()!;
    }

    IEnumerator IEnumerable.GetEnumerator() =>
        ((IEnumerable<T>)this).GetEnumerator();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaMapContract<K, V> : IDictionary<K, V>
{
    int Size();
    bool ContainsKey(object? key);
    V Get(object? key);
    V Put(K key, V value);
    V Remove(object? key);
    new void Clear();
    ISet<K> KeySet();
    new ICollection<V> Values();
    ISet<JavaMapEntry<K, V>> EntrySet();

    V IDictionary<K, V>.this[K key]
    {
        get => Get(key);
        set => Put(key, value);
    }

    ICollection<K> IDictionary<K, V>.Keys => KeySet();
    ICollection<V> IDictionary<K, V>.Values => Values();
    int ICollection<KeyValuePair<K, V>>.Count => Size();
    bool ICollection<KeyValuePair<K, V>>.IsReadOnly => false;
    void IDictionary<K, V>.Add(K key, V value) => Put(key, value);
    bool IDictionary<K, V>.ContainsKey(K key) => ContainsKey(key);

    bool IDictionary<K, V>.Remove(K key)
    {
        if (!ContainsKey(key))
            return false;
        Remove(key);
        return true;
    }

    bool IDictionary<K, V>.TryGetValue(K key, out V value)
    {
        if (ContainsKey(key))
        {
            value = Get(key);
            return true;
        }
        value = default!;
        return false;
    }

    void ICollection<KeyValuePair<K, V>>.Add(KeyValuePair<K, V> item) =>
        Put(item.Key, item.Value);

    bool ICollection<KeyValuePair<K, V>>.Contains(KeyValuePair<K, V> item) =>
        ContainsKey(item.Key) &&
        JavaCompat.Equals(Get(item.Key), item.Value);

    void ICollection<KeyValuePair<K, V>>.CopyTo(
        KeyValuePair<K, V>[] array,
        int arrayIndex)
    {
        ArgumentNullException.ThrowIfNull(array);
        foreach (var item in (IEnumerable<KeyValuePair<K, V>>)this)
            array[arrayIndex++] = item;
    }

    bool ICollection<KeyValuePair<K, V>>.Remove(KeyValuePair<K, V> item)
    {
        if (!((ICollection<KeyValuePair<K, V>>)this).Contains(item))
            return false;
        Remove(item.Key);
        return true;
    }

    IEnumerator<KeyValuePair<K, V>>
        IEnumerable<KeyValuePair<K, V>>.GetEnumerator()
    {
        foreach (var entry in EntrySet())
            yield return new KeyValuePair<K, V>(entry.Key, entry.Value);
    }

    IEnumerator IEnumerable.GetEnumerator() =>
        ((IEnumerable<KeyValuePair<K, V>>)this).GetEnumerator();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaListIterator<T> : JavaIterator<T>
{
    private readonly IList<T>? list;
    private readonly IEnumerator<T>? iterator;
    private int cursor;
    private int lastReturned = -1;
    private bool prepared;
    private bool hasNext;

    internal JavaListIterator(IEnumerable<T> values)
        : this(values, 0)
    {
    }

    internal JavaListIterator(IEnumerable<T> values, int index)
    {
        list = values as IList<T>;
        if (list is not null)
        {
            if (index < 0 || index > list.Count)
                throw new IndexOutOfRangeException();
            cursor = index;
        }
        else
        {
            if (index != 0)
                throw new NotSupportedException(
                    "Indexed iteration requires an IList source.");
            iterator = values.GetEnumerator();
        }
    }

    public bool HasNext()
    {
        if (list is not null) return cursor < list.Count;
        if (!prepared)
        {
            hasNext = iterator!.MoveNext();
            prepared = true;
        }
        return hasNext;
    }

    public T Next()
    {
        if (!HasNext()) throw new InvalidOperationException("Iterator has no next element.");
        if (list is not null)
        {
            lastReturned = cursor;
            return list[cursor++];
        }
        prepared = false;
        return iterator!.Current;
    }

    public void Remove()
    {
        if (list is null)
            throw new NotSupportedException(
                "This Java iterator does not expose mutable removal semantics.");
        if (lastReturned < 0)
            throw new InvalidOperationException(
                "Iterator.remove() requires one preceding next() call.");
        list.RemoveAt(lastReturned);
        if (lastReturned < cursor) cursor--;
        lastReturned = -1;
    }

    public bool HasPrevious() => list is not null && cursor > 0;

    public T Previous()
    {
        if (list is null || cursor <= 0)
            throw new InvalidOperationException("Iterator has no previous element.");
        cursor--;
        lastReturned = cursor;
        return list[cursor];
    }

    public int NextIndex() => cursor;
    public int PreviousIndex() => cursor - 1;

    public void Set(T value)
    {
        if (list is null)
            throw new NotSupportedException(
                "This Java iterator does not expose mutable set semantics.");
        if (lastReturned < 0)
            throw new InvalidOperationException(
                "Iterator.set() requires one preceding next() or previous() call.");
        list[lastReturned] = value;
    }

    public void Add(T value)
    {
        if (list is null)
            throw new NotSupportedException(
                "This Java iterator does not expose mutable add semantics.");
        list.Insert(cursor, value);
        cursor++;
        lastReturned = -1;
    }
}

internal interface JavaReadOnlyAdapter
{
    object MutableSource { get; }
}

internal sealed class JavaReadOnlyList<T> : IReadOnlyList<T>, JavaReadOnlyAdapter
{
    private readonly IList<T> values;

    public JavaReadOnlyList(IList<T> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public T this[int index] => values[index];
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaReadOnlyDictionary<K, V> : IReadOnlyDictionary<K, V>, JavaReadOnlyAdapter
{
    private readonly IDictionary<K, V> values;

    public JavaReadOnlyDictionary(IDictionary<K, V> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public IEnumerable<K> Keys => values.Keys;
    public IEnumerable<V> Values => values.Values;
    public V this[K key] => values[key];
    public bool ContainsKey(K key) => values.ContainsKey(key);
    public bool TryGetValue(K key, out V value) => values.TryGetValue(key, out value!);
    public IEnumerator<KeyValuePair<K, V>> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaReadOnlySet<T> : IReadOnlySet<T>, JavaReadOnlyAdapter
{
    private readonly ISet<T> values;

    public JavaReadOnlySet(ISet<T> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public bool Contains(T item) => values.Contains(item);
    public bool IsProperSubsetOf(IEnumerable<T> other) => values.IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<T> other) => values.IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<T> other) => values.IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<T> other) => values.IsSupersetOf(other);
    public bool Overlaps(IEnumerable<T> other) => values.Overlaps(other);
    public bool SetEquals(IEnumerable<T> other) => values.SetEquals(other);
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaUnmodifiableSet<T> : ISet<T>
{
    private readonly ISet<T> values;
    internal JavaUnmodifiableSet(ISet<T> values) => this.values = values;
    public int Count => values.Count;
    public bool IsReadOnly => true;
    bool ISet<T>.Add(T item) => throw new NotSupportedException();
    void ICollection<T>.Add(T item) => throw new NotSupportedException();
    public void ExceptWith(IEnumerable<T> other) => throw new NotSupportedException();
    public void IntersectWith(IEnumerable<T> other) => throw new NotSupportedException();
    public bool IsProperSubsetOf(IEnumerable<T> other) => values.IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<T> other) => values.IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<T> other) => values.IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<T> other) => values.IsSupersetOf(other);
    public bool Overlaps(IEnumerable<T> other) => values.Overlaps(other);
    public bool SetEquals(IEnumerable<T> other) => values.SetEquals(other);
    public void SymmetricExceptWith(IEnumerable<T> other) => throw new NotSupportedException();
    public void UnionWith(IEnumerable<T> other) => throw new NotSupportedException();
    public void Clear() => throw new NotSupportedException();
    public bool Contains(T item) => values.Contains(item);
    public void CopyTo(T[] array, int arrayIndex) => values.CopyTo(array, arrayIndex);
    public bool Remove(T item) => throw new NotSupportedException();
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaMapEntrySet<K, V> : ISet<JavaMapEntry<K, V>> where K : notnull
{
    private readonly IDictionary<K, V> source;

    internal JavaMapEntrySet(IDictionary<K, V> source) => this.source = source;

    public int Count => source.Count;
    public bool IsReadOnly => false;

    bool ISet<JavaMapEntry<K, V>>.Add(JavaMapEntry<K, V> item) =>
        throw new NotSupportedException("Java Map.entrySet does not support add().");

    void ICollection<JavaMapEntry<K, V>>.Add(JavaMapEntry<K, V> item) =>
        throw new NotSupportedException("Java Map.entrySet does not support add().");

    public void Clear() => source.Clear();

    public bool Contains(JavaMapEntry<K, V> item) =>
        source.TryGetValue(item.Key, out var value) && JavaCompat.Equals(value, item.Value);

    public void CopyTo(JavaMapEntry<K, V>[] array, int arrayIndex)
    {
        foreach (var entry in this) array[arrayIndex++] = entry;
    }

    public bool Remove(JavaMapEntry<K, V> item)
    {
        if (!Contains(item)) return false;
        return source.Remove(item.Key);
    }

    public IEnumerator<JavaMapEntry<K, V>> GetEnumerator() => new Enumerator(source);
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public void ExceptWith(IEnumerable<JavaMapEntry<K, V>> other)
    {
        var removed = other.ToList();
        foreach (var entry in removed) Remove(entry);
    }

    public void IntersectWith(IEnumerable<JavaMapEntry<K, V>> other)
    {
        var retained = new HashSet<JavaMapEntry<K, V>>(other);
        foreach (var entry in this.Where(entry => !retained.Contains(entry)).ToList()) Remove(entry);
    }

    public bool IsProperSubsetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsProperSubsetOf(other);

    public bool IsProperSupersetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsProperSupersetOf(other);

    public bool IsSubsetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsSubsetOf(other);

    public bool IsSupersetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsSupersetOf(other);

    public bool Overlaps(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().Overlaps(other);

    public bool SetEquals(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().SetEquals(other);

    public void SymmetricExceptWith(IEnumerable<JavaMapEntry<K, V>> other) =>
        throw new NotSupportedException("Java Map.entrySet does not support adding entries.");

    public void UnionWith(IEnumerable<JavaMapEntry<K, V>> other) =>
        throw new NotSupportedException("Java Map.entrySet does not support adding entries.");

    private HashSet<JavaMapEntry<K, V>> Snapshot() => new(this);

    private sealed class Enumerator : IEnumerator<JavaMapEntry<K, V>>, JavaRemovableIterator
    {
        private readonly IDictionary<K, V> source;
        private readonly IList<K> keys;
        private int index = -1;
        private K? preparedKey;
        private K? returnedKey;
        private bool hasPreparedKey;
        private bool canRemove;

        internal Enumerator(IDictionary<K, V> source)
        {
            this.source = source;
            keys = source.Keys.ToList();
        }

        public JavaMapEntry<K, V> Current { get; private set; } = default!;
        object IEnumerator.Current => Current;

        public bool MoveNext()
        {
            while (++index < keys.Count)
            {
                var key = keys[index];
                if (!source.ContainsKey(key)) continue;
                preparedKey = key;
                hasPreparedKey = true;
                Current = new JavaMapEntry<K, V>(source, key);
                return true;
            }
            preparedKey = default;
            hasPreparedKey = false;
            Current = default!;
            return false;
        }

        public void MarkReturned()
        {
            if (!hasPreparedKey)
                throw new InvalidOperationException("Iterator has no current map entry.");
            returnedKey = preparedKey;
            canRemove = true;
        }

        public void Remove()
        {
            if (!canRemove)
                throw new InvalidOperationException("Iterator.remove() requires one preceding next().");
            source.Remove(returnedKey!);
            canRemove = false;
        }

        public void Reset() => throw new NotSupportedException();
        public void Dispose() { }
    }
}

internal sealed class JavaMapKeySet<K, V> : ISet<K> where K : notnull
{
    private readonly IDictionary<K, V> source;

    private sealed class KeyComparer : IEqualityComparer<K>
    {
        public bool Equals(K? left, K? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(K value) => JavaCompat.HashCode(value);
    }

    internal JavaMapKeySet(IDictionary<K, V> source) => this.source = source;

    private HashSet<K> Snapshot() => new(source.Keys, new KeyComparer());
    public int Count => source.Count;
    public bool IsReadOnly => false;
    bool ISet<K>.Add(K item) =>
        throw new NotSupportedException("Java Map.keySet does not support add().");
    void ICollection<K>.Add(K item) =>
        throw new NotSupportedException("Java Map.keySet does not support add().");
    public void Clear() => source.Clear();
    public bool Contains(K item) => source.ContainsKey(item);
    public void CopyTo(K[] array, int arrayIndex)
    {
        foreach (var item in source.Keys) array[arrayIndex++] = item;
    }
    public bool Remove(K item) => source.Remove(item);
    public IEnumerator<K> GetEnumerator() => source.Keys.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void ExceptWith(IEnumerable<K> other)
    {
        foreach (var item in other.ToList()) source.Remove(item);
    }
    public void IntersectWith(IEnumerable<K> other)
    {
        var retained = new HashSet<K>(other, new KeyComparer());
        foreach (var item in source.Keys.Where(item => !retained.Contains(item)).ToList())
            source.Remove(item);
    }
    public bool IsProperSubsetOf(IEnumerable<K> other) => Snapshot().IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<K> other) => Snapshot().IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<K> other) => Snapshot().IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<K> other) => Snapshot().IsSupersetOf(other);
    public bool Overlaps(IEnumerable<K> other) => Snapshot().Overlaps(other);
    public bool SetEquals(IEnumerable<K> other) => Snapshot().SetEquals(other);
    public void SymmetricExceptWith(IEnumerable<K> other) =>
        throw new NotSupportedException("Java Map.keySet does not support adding keys.");
    public void UnionWith(IEnumerable<K> other) =>
        throw new NotSupportedException("Java Map.keySet does not support adding keys.");
    public override string ToString() =>
        "[" + string.Join(", ", source.Keys.Select(item => JavaCompat.StringValueOf(item))) + "]";
}

internal sealed class JavaStringJoiner
{
    private readonly string delimiter;
    private readonly string prefix;
    private readonly string suffix;
    private readonly List<string> values = new();

    internal JavaStringJoiner(string delimiter, string prefix, string suffix)
    {
        this.delimiter = delimiter ?? throw new ArgumentNullException(nameof(delimiter));
        this.prefix = prefix ?? throw new ArgumentNullException(nameof(prefix));
        this.suffix = suffix ?? throw new ArgumentNullException(nameof(suffix));
    }

    internal JavaStringJoiner add(string value)
    {
        values.Add(value ?? "null");
        return this;
    }

    public override string ToString() => prefix + string.Join(delimiter, values) + suffix;
    internal string toString() => ToString();
}

internal sealed class JavaStringTokenizer
{
    private readonly string[] tokens;
    private int index;

    internal JavaStringTokenizer(string value) : this(value, " \t\n\r\f") { }

    internal JavaStringTokenizer(string value, string delimiters)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(delimiters);
        tokens = value.Split(delimiters.ToCharArray(), StringSplitOptions.RemoveEmptyEntries);
    }

    internal int countTokens() => tokens.Length - index;
    internal bool hasMoreTokens() => index < tokens.Length;
    internal string nextToken() =>
        hasMoreTokens() ? tokens[index++] : throw new InvalidOperationException("No more tokens");
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaDeque<T> : ICollection<T>
{
    private readonly LinkedList<T> values = new();
    internal JavaDeque()
    {
    }
    internal JavaDeque(int initialCapacity)
    {
        if (initialCapacity < 0) throw new ArgumentException("Initial capacity must not be negative.");
    }
    internal T GetFirst() => values.First is { } first
        ? first.Value
        : throw new InvalidOperationException("Deque is empty");
    internal T? Peek() => values.First is null ? default : values.First.Value;
    internal T? Poll()
    {
        if (values.First is not { } first) return default;
        values.RemoveFirst();
        return first.Value;
    }
    internal T Pop()
    {
        var value = GetFirst();
        values.RemoveFirst();
        return value;
    }
    internal void Push(T value) => values.AddFirst(value);
    internal void AddLast(T value) => values.AddLast(value);
    internal bool Offer(T value)
    {
        values.AddLast(value);
        return true;
    }
    internal void AddFirst(T value) => values.AddFirst(value);
    internal bool IsEmpty() => values.Count == 0;
    internal JavaIterator<T> DescendingIterator() =>
        JavaCompat.Iterator(values.Reverse());
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

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaLinkedHashMap<K, V> :
    IDictionary<K, V>,
    IDictionary,
    JavaMapValueUpdater<K, V>
    where K : notnull
{
    private sealed class Entry(K key, V value)
    {
        internal K Key { get; } = key;
        internal V Value { get; set; } = value;
    }

    private sealed class KeyComparer : IEqualityComparer<K>
    {
        public bool Equals(K? left, K? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(K value) => JavaCompat.HashCode(value);
    }

    private readonly Dictionary<K, LinkedListNode<Entry>> entries;
    private readonly LinkedList<Entry> order = new();
    private readonly bool accessOrder;

    public JavaLinkedHashMap() : this(0, 0.75f, false) { }
    public JavaLinkedHashMap(int initialCapacity) : this(initialCapacity, 0.75f, false) { }
    public JavaLinkedHashMap(int initialCapacity, float loadFactor)
        : this(initialCapacity, loadFactor, false) { }
    public JavaLinkedHashMap(int initialCapacity, float loadFactor, bool accessOrder)
    {
        if (initialCapacity < 0) throw new ArgumentOutOfRangeException(nameof(initialCapacity));
        if (!(loadFactor > 0) || float.IsNaN(loadFactor))
            throw new ArgumentOutOfRangeException(nameof(loadFactor));
        entries = new Dictionary<K, LinkedListNode<Entry>>(initialCapacity, new KeyComparer());
        this.accessOrder = accessOrder;
    }
    public JavaLinkedHashMap(IEnumerable<KeyValuePair<K, V>> values) : this()
    {
        PutAll(values);
    }

    protected internal virtual bool RemoveEldestEntry(JavaMapEntry<K, V> eldest) => false;

    public int Count => entries.Count;
    public bool IsReadOnly => false;
    bool IDictionary.IsFixedSize => false;
    bool IDictionary.IsReadOnly => false;
    bool ICollection.IsSynchronized => false;
    object ICollection.SyncRoot => this;
    public ICollection<K> Keys => new KeyCollection(this);
    public ICollection<V> Values => new ValueCollection(this);
    ICollection IDictionary.Keys => Keys.ToList();
    ICollection IDictionary.Values => Values.ToList();
    public V this[K key]
    {
        get
        {
            if (!entries.TryGetValue(key, out var node)) throw new KeyNotFoundException();
            RecordAccess(node);
            return node.Value.Value;
        }
        set => Put(key, value);
    }
    object? IDictionary.this[object key]
    {
        get => key is K typed && TryGetValue(typed, out var value) ? value : null;
        set => Put(RequireKey(key), RequireValue(value));
    }

    public int Size() => Count;

    internal V Get(K key)
    {
        if (!entries.TryGetValue(key, out var node)) return default!;
        RecordAccess(node);
        return node.Value.Value;
    }

    internal V GetOrDefault(K key, V fallback)
    {
        if (!entries.TryGetValue(key, out var node)) return fallback;
        RecordAccess(node);
        return node.Value.Value;
    }

    internal V PutIfAbsent(K key, V value)
    {
        if (entries.TryGetValue(key, out var node))
        {
            var previous = node.Value.Value;
            RecordAccess(node);
            if (previous is not null) return previous;
        }
        return Put(key, value);
    }

    internal V ComputeIfAbsent(K key, Func<K, V> factory)
    {
        var present = entries.TryGetValue(key, out var node);
        if (present)
        {
            var current = node!.Value.Value;
            RecordAccess(node);
            if (current is not null) return current;
        }
        var value = factory(key);
        if (value is null) return default!;
        Put(key, value);
        return value;
    }

    internal V Put(K key, V value)
    {
        if (entries.TryGetValue(key, out var existing))
        {
            var previous = existing.Value.Value;
            existing.Value.Value = value;
            RecordAccess(existing);
            return previous;
        }

        var node = order.AddLast(new Entry(key, value));
        entries.Add(key, node);
        var eldest = order.First!;
        if (RemoveEldestEntry(new JavaMapEntry<K, V>(this, eldest.Value.Key)))
            Remove(eldest.Value.Key);
        return default!;
    }

    internal void PutAll(IEnumerable<KeyValuePair<K, V>> values)
    {
        foreach (var (key, value) in values) Put(key, value);
    }

    internal void ReplaceValueWithoutAccess(K key, V value)
    {
        if (!entries.TryGetValue(key, out var node)) throw new KeyNotFoundException();
        node.Value.Value = value;
    }

    void JavaMapValueUpdater<K, V>.ReplaceValueWithoutAccess(K key, V value) =>
        ReplaceValueWithoutAccess(key, value);

    internal ISet<K> KeySet() => new KeySetView(this);

    private void RecordAccess(LinkedListNode<Entry> node)
    {
        if (!accessOrder || ReferenceEquals(order.Last, node)) return;
        order.Remove(node);
        order.AddLast(node);
    }

    public void Add(K key, V value)
    {
        if (entries.ContainsKey(key)) throw new ArgumentException("An item with the same key has already been added.");
        Put(key, value);
    }

    public bool ContainsKey(K key) => entries.ContainsKey(key);

    public bool Remove(K key)
    {
        if (!entries.Remove(key, out var node)) return false;
        order.Remove(node);
        return true;
    }

    public bool Remove(K key, out V value)
    {
        if (!entries.TryGetValue(key, out var node))
        {
            value = default!;
            return false;
        }
        value = node.Value.Value;
        entries.Remove(key);
        order.Remove(node);
        return true;
    }

    public bool TryGetValue(K key, out V value)
    {
        if (entries.TryGetValue(key, out var node))
        {
            value = node.Value.Value;
            return true;
        }
        value = default!;
        return false;
    }

    public void Add(KeyValuePair<K, V> item) => Add(item.Key, item.Value);
    void IDictionary.Add(object key, object? value) => Add(RequireKey(key), RequireValue(value));
    public void Clear()
    {
        entries.Clear();
        order.Clear();
    }

    public bool Contains(KeyValuePair<K, V> item) =>
        entries.TryGetValue(item.Key, out var node) && JavaCompat.Equals(node.Value.Value, item.Value);
    bool IDictionary.Contains(object key) => key is K typed && ContainsKey(typed);

    public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex)
    {
        foreach (var item in this) array[arrayIndex++] = item;
    }
    void ICollection.CopyTo(Array array, int index)
    {
        foreach (var item in this)
            array.SetValue(new DictionaryEntry(item.Key!, item.Value), index++);
    }

    public bool Remove(KeyValuePair<K, V> item) => Contains(item) && Remove(item.Key);
    void IDictionary.Remove(object key)
    {
        if (key is K typed) Remove(typed);
    }

    public IEnumerator<KeyValuePair<K, V>> GetEnumerator()
    {
        foreach (var entry in order)
            yield return new KeyValuePair<K, V>(entry.Key, entry.Value);
    }

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    IDictionaryEnumerator IDictionary.GetEnumerator() => new DictionaryEnumerator(this);

    private static K RequireKey(object key) => key is K typed
        ? typed
        : throw new ArgumentException($"Key must be assignable to {typeof(K)}.", nameof(key));

    private static V RequireValue(object? value)
    {
        if (value is V typed) return typed;
        if (value is null && default(V) is null) return default!;
        throw new ArgumentException($"Value must be assignable to {typeof(V)}.", nameof(value));
    }

    private sealed class DictionaryEnumerator(JavaLinkedHashMap<K, V> source) : IDictionaryEnumerator
    {
        private readonly IEnumerator<KeyValuePair<K, V>> inner = source.GetEnumerator();
        public DictionaryEntry Entry => new(Key!, Value);
        public object Key => inner.Current.Key;
        public object? Value => inner.Current.Value;
        public object Current => Entry;
        public bool MoveNext() => inner.MoveNext();
        public void Reset() => inner.Reset();
    }

    private sealed class KeyCollection(JavaLinkedHashMap<K, V> source) : ICollection<K>
    {
        public int Count => source.Count;
        public bool IsReadOnly => false;
        public void Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(K item) => source.ContainsKey(item);
        public void CopyTo(K[] array, int arrayIndex)
        {
            foreach (var item in this) array[arrayIndex++] = item;
        }
        public bool Remove(K item) => source.Remove(item);
        public IEnumerator<K> GetEnumerator()
        {
            foreach (var entry in source.order) yield return entry.Key;
        }
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class ValueCollection(JavaLinkedHashMap<K, V> source) : ICollection<V>
    {
        public int Count => source.Count;
        public bool IsReadOnly => false;
        public void Add(V item) => throw new NotSupportedException("Java Map.values does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(V item) => source.order.Any(entry => JavaCompat.Equals(entry.Value, item));
        public void CopyTo(V[] array, int arrayIndex)
        {
            foreach (var item in this) array[arrayIndex++] = item;
        }
        public bool Remove(V item)
        {
            var node = source.order.First;
            while (node is not null)
            {
                if (JavaCompat.Equals(node.Value.Value, item)) return source.Remove(node.Value.Key);
                node = node.Next;
            }
            return false;
        }
        public IEnumerator<V> GetEnumerator()
        {
            foreach (var entry in source.order) yield return entry.Value;
        }
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class KeySetView(JavaLinkedHashMap<K, V> source) : ISet<K>
    {
        private HashSet<K> Snapshot() => new(source.Keys, new KeyComparer());
        public int Count => source.Count;
        public bool IsReadOnly => false;
        bool ISet<K>.Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        void ICollection<K>.Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(K item) => source.ContainsKey(item);
        public void CopyTo(K[] array, int arrayIndex) => source.Keys.CopyTo(array, arrayIndex);
        public void ExceptWith(IEnumerable<K> other)
        {
            foreach (var item in other.ToList()) source.Remove(item);
        }
        public void IntersectWith(IEnumerable<K> other)
        {
            var retained = new HashSet<K>(other, new KeyComparer());
            foreach (var item in source.Keys.Where(item => !retained.Contains(item)).ToList()) source.Remove(item);
        }
        public bool IsProperSubsetOf(IEnumerable<K> other) => Snapshot().IsProperSubsetOf(other);
        public bool IsProperSupersetOf(IEnumerable<K> other) => Snapshot().IsProperSupersetOf(other);
        public bool IsSubsetOf(IEnumerable<K> other) => Snapshot().IsSubsetOf(other);
        public bool IsSupersetOf(IEnumerable<K> other) => Snapshot().IsSupersetOf(other);
        public bool Overlaps(IEnumerable<K> other) => Snapshot().Overlaps(other);
        public bool SetEquals(IEnumerable<K> other) => Snapshot().SetEquals(other);
        public void SymmetricExceptWith(IEnumerable<K> other) =>
            throw new NotSupportedException("Java Map.keySet does not support adding keys.");
        public void UnionWith(IEnumerable<K> other) =>
            throw new NotSupportedException("Java Map.keySet does not support adding keys.");
        public bool Remove(K item) => source.Remove(item);
        public IEnumerator<K> GetEnumerator() => source.Keys.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        public override string ToString() =>
            "[" + string.Join(", ", source.Keys.Select(item => JavaCompat.StringValueOf(item))) + "]";
    }
}

internal
sealed class JavaLinkedList<T> : IList<T>
{
    private readonly List<T> values = new();

    public T this[int index] { get => values[index]; set => values[index] = value; }
    public int Count => values.Count;
    public bool IsReadOnly => false;
    public void Add(T item) => values.Add(item);
    public void AddFirst(T item) => values.Insert(0, item);
    public void Clear() => values.Clear();
    public bool Contains(T item) => values.Contains(item);
    public void CopyTo(T[] array, int arrayIndex) => values.CopyTo(array, arrayIndex);
    public int IndexOf(T item) => values.IndexOf(item);
    public void Insert(int index, T item) => values.Insert(index, item);
    public bool Remove(T item) => values.Remove(item);
    public void RemoveAt(int index) => values.RemoveAt(index);
    public IEnumerator<T> GetEnumerator() => new Enumerator(values);
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    private sealed class Enumerator : IEnumerator<T>, JavaRemovableIterator
    {
        private readonly List<T> values;
        private int index = -1;
        private bool canRemove;

        internal Enumerator(List<T> values) => this.values = values;
        public T Current { get; private set; } = default!;
        object IEnumerator.Current => Current!;
        public bool MoveNext()
        {
            if (++index >= values.Count)
            {
                Current = default!;
                return false;
            }
            Current = values[index];
            return true;
        }
        public void MarkReturned() => canRemove = true;
        public void Remove()
        {
            if (!canRemove) throw new InvalidOperationException(
                "Iterator.remove() requires one preceding next().");
            values.RemoveAt(index--);
            canRemove = false;
        }
        public void Reset() => throw new NotSupportedException();
        public void Dispose() { }
    }
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

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaStream<T> : IEnumerable<T>, IDisposable
{
    private readonly IEnumerable<T> source;
    internal JavaStream(IEnumerable<T> source) => this.source = source;
    public IEnumerator<T> GetEnumerator() => source.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void Dispose() { }
}

internal sealed class JavaSynchronizedList<T> : IList<T>
{
    private readonly IList<T> source;
    private readonly object sync = new();
    internal JavaSynchronizedList(IList<T> source) =>
        this.source = source ?? throw new ArgumentNullException(nameof(source));
    public T this[int index]
    {
        get { lock (sync) return source[index]; }
        set { lock (sync) source[index] = value; }
    }
    public int Count { get { lock (sync) return source.Count; } }
    public bool IsReadOnly => source.IsReadOnly;
    public void Add(T item) { lock (sync) source.Add(item); }
    public void Clear() { lock (sync) source.Clear(); }
    public bool Contains(T item) { lock (sync) return source.Contains(item); }
    public void CopyTo(T[] array, int arrayIndex)
    {
        lock (sync) source.CopyTo(array, arrayIndex);
    }
    public IEnumerator<T> GetEnumerator()
    {
        lock (sync) return source.ToList().GetEnumerator();
    }
    public int IndexOf(T item) { lock (sync) return source.IndexOf(item); }
    public void Insert(int index, T item) { lock (sync) source.Insert(index, item); }
    public bool Remove(T item) { lock (sync) return source.Remove(item); }
    public void RemoveAt(int index) { lock (sync) source.RemoveAt(index); }
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
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

internal static partial class JavaCompat
{
    private sealed class ReadOnlyAdapterCache
    {
        internal Dictionary<Type, object> Values { get; } = new();
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<object, ReadOnlyAdapterCache>
        ReadOnlyAdapters = new();

    internal static JavaIterator<T> Iterator<T>(IEnumerable<T> values) =>
        new JavaListIterator<T>(values);

    internal static IEnumerator<T> AsEnumerator<T>(JavaIterator<T> iterator)
    {
        while (iterator.HasNext())
            yield return iterator.Next()!;
    }

    internal static JavaListIterator<T> ListIterator<T>(
        IList<T> values,
        int index = 0) =>
        new(values, index);

    internal static JavaListIterator<T> ListIterator<T>(
        IEnumerable<T> values,
        int index) =>
        new(values.ToList(), index);

    internal static JavaIterator<T> EmptyJavaIterator<T>() =>
        new JavaListIterator<T>(Array.Empty<T>());

    private static object ReadOnlyAdapter(Type targetType, object source, Func<object> create)
    {
        var cache = ReadOnlyAdapters.GetOrCreateValue(source);
        lock (cache.Values)
        {
            if (cache.Values.TryGetValue(targetType, out var existing)) return existing;
            var adapter = create();
            cache.Values.Add(targetType, adapter);
            return adapter;
        }
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<IEnumerator, IteratorState>
        IteratorStates = new();

    private sealed class IteratorState
    {
        internal bool Prepared;
        internal bool Exhausted;
    }
    internal static void ListAddFirst<T>(IList<T> values, T value) =>
        values.Insert(0, value);
    internal static IComparer<T> ComparatorComparing<T, U>(Func<T, U> extractor)
    {
        ArgumentNullException.ThrowIfNull(extractor);
        return Comparer<T>.Create(
            (left, right) => JavaCompare(extractor(left), extractor(right)));
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

    internal static bool ArrayEquals<T>(T[]? left, T[]? right) =>
        ReferenceEquals(left, right) ||
        (left is not null && right is not null && left.AsSpan().SequenceEqual(right));

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

    internal static bool AddAll<T>(ICollection<T> collection, System.Collections.IEnumerable values)
    {
        var changed = false;
        foreach (var value in values)
        {
            collection.Add((T)value!);
            changed = true;
        }
        return changed;
    }
    internal static bool RemoveIf<T>(ICollection<T> collection, Func<T, bool> predicate)
    {
        var removed = false;
        foreach (var value in collection.Where(predicate).ToList())
            removed |= collection.Remove(value);
        return removed;
    }

    internal static int CollectionCount<T>(IEnumerable<T> collection) => collection.Count();
    internal static bool CollectionIsEmpty<T>(IEnumerable<T> collection) => !collection.Any();
    internal static int CollectionCount(IEnumerable collection) => collection.Cast<object?>().Count();
    internal static bool CollectionIsEmpty(IEnumerable collection) => !collection.Cast<object?>().Any();
    internal static bool CollectionContains<T>(IEnumerable<T> collection, object? value) =>
        value is T typed && collection.Contains(typed);
    internal static bool CollectionRemove<T>(ICollection<T> collection, object? value) =>
        value is T typed && collection.Remove(typed);
    internal static bool ContainsAll<T>(IEnumerable<T> collection, System.Collections.IEnumerable values)
    {
        var set = new HashSet<T>(collection);
        return values.Cast<object?>().All(value => value is T typed && set.Contains(typed));
    }
    internal static bool RemoveAll<T>(ICollection<T> collection, System.Collections.IEnumerable values)
    {
        var changed = false;
        foreach (var value in values.Cast<object?>().ToArray())
            if (value is T typed) changed |= collection.Remove(typed);
        return changed;
    }
    internal static bool RetainAll<T>(ICollection<T> collection, System.Collections.IEnumerable values)
    {
        var retained = values.Cast<object?>().ToHashSet();
        var changed = false;
        foreach (var value in collection.Where(value => !retained.Contains(value)).ToArray())
            changed |= collection.Remove(value);
        return changed;
    }

    internal static IList<T> Mutable<T>(IList<T> values) => new List<T>(values);
    internal static ISet<T> Mutable<T>(ISet<T> values) =>
        new HashSet<T>(values, new JavaEqualityComparer<T>());
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
        var result = new HashSet<T>(values, new JavaEqualityComparer<T>());
        result.Remove(value);
        return result;
    }

    internal static bool MapContainsKey<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.ContainsKey(typed);
    internal static bool MapContainsKey<K, V>(IReadOnlyDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.ContainsKey(typed);

    internal static ISet<JavaMapEntry<K, V>> MapEntrySet<K, V>(IDictionary<K, V> map) where K : notnull =>
        new JavaMapEntrySet<K, V>(map);
    internal static ISet<JavaMapEntry<K, V>> MapEntrySet<K, V>(SortedDictionary<K, V> map) where K : notnull =>
        new JavaMapEntrySet<K, V>(map);
    internal static IEnumerable<KeyValuePair<K, V>> MapEntrySet<K, V>(IReadOnlyDictionary<K, V> map)
        where K : notnull => map;

    internal static bool MapIsEmpty<K, V>(IDictionary<K, V> map) where K : notnull => map.Count == 0;
    internal static bool MapIsEmpty<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull => map.Count == 0;
    internal static K SortedFirstKey<K, V>(IDictionary<K, V> map) where K : notnull =>
        map.Keys.First();
    internal static K SortedLastKey<K, V>(IDictionary<K, V> map) where K : notnull =>
        map.Keys.Last();
    internal static IDictionary<K, V> SortedSubMap<K, V>(
        IDictionary<K, V> map, K lower, K upper) where K : notnull =>
        map.Where(pair => JavaCompare(pair.Key, lower) >= 0
            && JavaCompare(pair.Key, upper) < 0)
            .ToDictionary(pair => pair.Key, pair => pair.Value);
    internal static ISet<T> SortedHeadSet<T>(ISet<T> values, T upper) =>
        new SortedSet<T>(values.Where(value => JavaCompare(value, upper) < 0),
            Comparer<T>.Create(JavaCompare));
    internal static ISet<T> SortedSubSet<T>(ISet<T> values, T lower, T upper) =>
        new SortedSet<T>(values.Where(value =>
                JavaCompare(value, lower) >= 0 && JavaCompare(value, upper) < 0),
            Comparer<T>.Create(JavaCompare));
    internal static T SortedFirst<T>(ISet<T> values) => values.First();
    internal static T SortedLast<T>(ISet<T> values) => values.Last();
    internal static ISet<K> MapKeySet<K, V>(IDictionary<K, V> map) where K : notnull =>
        map is JavaLinkedHashMap<K, V> linked
            ? linked.KeySet()
            : new JavaMapKeySet<K, V>(map);
    internal static ISet<K> MapKeySet<K, V>(SortedDictionary<K, V> map) where K : notnull =>
        new JavaMapKeySet<K, V>(map);
    internal static ISet<K> MapKeySet<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull =>
        new HashSet<K>(map.Keys, new JavaEqualityComparer<K>());
    internal static int MapCount<K, V>(IDictionary<K, V> map) where K : notnull => map.Count;
    internal static int MapCount<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull => map.Count;
    internal static bool MapContainsValue<K, V>(IDictionary<K, V> map, object? value) where K : notnull =>
        value is V typed && map.Values.Contains(typed);
    internal static bool MapContainsValue<K, V>(IReadOnlyDictionary<K, V> map, object? value) where K : notnull =>
        value is V typed && map.Values.Contains(typed);
    internal static V MapRemove<K, V>(IDictionary<K, V> map, object? key) where K : notnull
    {
        if (key is K typed && map.Remove(typed, out var value)) return value;
        return default!;
    }
    internal static V ComputeIfAbsent<K, V>(IDictionary<K, V> map, K key, Func<K, V> factory) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked) return linked.ComputeIfAbsent(key, factory);
        if (map.TryGetValue(key, out var value) && value is not null) return value;
        value = factory(key);
        if (value is null) return default!;
        map[key] = value;
        return value;
    }
    internal static V MapMerge<K, V>(
        IDictionary<K, V> map,
        K key,
        V value,
        Func<V, V, V> remappingFunction) where K : notnull
    {
        if (value is null) throw new NullReferenceException();
        if (!map.TryGetValue(key, out var previous) || previous is null)
        {
            map[key] = value;
            return value;
        }
        var merged = remappingFunction(previous, value);
        if (merged is null)
        {
            map.Remove(key);
            return default!;
        }
        map[key] = merged;
        return merged;
    }
    internal static V MapGetOrDefault<K, V>(IDictionary<K, V> map, K key, V fallback) where K : notnull =>
        map is JavaLinkedHashMap<K, V> linked
            ? linked.GetOrDefault(key, fallback)
            : map.TryGetValue(key, out var value) ? value : fallback;
    internal static V MapGetOrDefault<K, V>(IReadOnlyDictionary<K, V> map, K key, V fallback) where K : notnull =>
        map.TryGetValue(key, out var value) ? value : fallback;
    internal static V MapPutIfAbsent<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        if (map is ConcurrentDictionary<K, V> concurrent)
            return concurrent.TryAdd(key, value) ? default! : concurrent[key];
        if (map is JavaLinkedHashMap<K, V> linked) return linked.PutIfAbsent(key, value);
        if (map.TryGetValue(key, out var previous) && previous is not null) return previous;
        return MapPut(map, key, value);
    }

    internal static void MapPutAll<K, V>(IDictionary<K, V> map, IEnumerable<KeyValuePair<K, V>> values) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked)
        {
            linked.PutAll(values);
            return;
        }
        foreach (var (key, value) in values) map[key] = value;
    }

    internal static V MapGet<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed
            ? map is JavaLinkedHashMap<K, V> linked
                ? linked.Get(typed)
                : map.TryGetValue(typed, out var value) ? value : default!
            : default!;
    internal static V MapGet<K, V>(ConcurrentDictionary<K, V> map, object? key)
        where K : notnull =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : default!;
    internal static V MapGet<K, V>(IReadOnlyDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : default!;

    internal static V? MapGetNullable<K, V>(IDictionary<K, V> map, object? key)
        where K : notnull
        where V : struct =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : null;
    internal static V? MapGetNullable<K, V>(SortedDictionary<K, V> map, object? key)
        where K : notnull
        where V : struct =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : null;
    internal static V? MapGetNullable<K, V>(IReadOnlyDictionary<K, V> map, object? key)
        where K : notnull
        where V : struct =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : null;

    internal static V MapPut<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked) return linked.Put(key, value);
        var previous = map.TryGetValue(key, out var oldValue) ? oldValue : default!;
        map[key] = value;
        return previous;
    }

    internal static JavaMapEntry<K, V> MapEntry<K, V>(K key, V value) where K : notnull => new(key, value);

    internal static IDictionary<K, V> MapOfEntries<K, V>(params JavaMapEntry<K, V>[] entries) where K : notnull =>
        entries.ToDictionary(entry => entry.Key, entry => entry.Value);
    internal static IDictionary<K, V> MapOf<K, V>(params object[] values) where K : notnull
    {
        var result = new Dictionary<K, V>();
        for (var index = 0; index < values.Length; index += 2)
            result[(K)values[index]] = (V)values[index + 1];
        return result;
    }

    internal static ReadOnlyCollection<T> ListOf<T>(params T[] values) => new(values);

    internal static IList<T> AsList<T>(params T[] values) => new JavaArrayList<T>(values);

    internal static HashSet<T> SetOf<T>(params T[] values) =>
        new(values, new JavaEqualityComparer<T>());
    internal static HashSet<T> SetOfValues<T>(IEnumerable<T> values) =>
        new(values, new JavaEqualityComparer<T>());
    internal static ISet<T> EnumSetNoneOf<T>(Type _) => new HashSet<T>();
    internal static ISet<T> EnumSetAllOf<T>(Type type) =>
        new HashSet<T>(type.GetFields(BindingFlags.Public | BindingFlags.Static)
            .Where(field => type.IsAssignableFrom(field.FieldType))
            .Select(field => (T)field.GetValue(null)!));
    internal static ISet<T> EnumSetOf<T>(params T[] values) => new HashSet<T>(values);
    internal static ISet<T> EnumSetCopyOf<T>(IEnumerable<T> values) => new HashSet<T>(values);

    internal static ReadOnlyCollection<T> UnmodifiableList<T>(IEnumerable<T> values) =>
        new(values is IList<T> list ? list : values.ToList());
    internal static ReadOnlyCollection<T> ListCopyOf<T>(IEnumerable<T> values)
    {
        var copy = values.ToList();
        if (copy.Any(value => value is null)) throw new NullReferenceException();
        return new(copy);
    }
    internal static ISet<T> UnmodifiableSet<T>(ISet<T> values) =>
        new JavaUnmodifiableSet<T>(values);
    internal static ISet<T> EmptySet<T>() =>
        new JavaUnmodifiableSet<T>(new HashSet<T>(new JavaEqualityComparer<T>()));
    internal static ISet<T> NewSetFromMap<T>(IDictionary<T, bool> map) where T : notnull =>
        new JavaMapBackedSet<T>(map);

    internal static IDictionary<K, V> UnmodifiableMap<K, V>(IDictionary<K, V> values)
        where K : notnull => new ReadOnlyDictionary<K, V>(values);
    internal static IDictionary<K, V> EmptyMap<K, V>() where K : notnull =>
        new ReadOnlyDictionary<K, V>(new Dictionary<K, V>());

    internal static IList<T> SubList<T>(IEnumerable<T> values, int fromIndex, int toIndex) =>
        new JavaSubList<T>(values is IList<T> list ? list : values.ToList(), fromIndex, toIndex);
    // Java generic casts may legally carry null even when the declaration is
    // not annotated. Keep that runtime behavior while presenting the helper's
    // declared result as the Java target type; generated nullable APIs still
    // surface their own explicit `?` contract.
    internal static IList<T> CastList<T>(object? values) => values is null
        ? null!
        : ((IEnumerable)values).Cast<object?>().Select(value => (T)value!).ToList();
    internal static ICollection<T> CastCollection<T>(object? values)
    {
        if (values is null) return null!;
        if (values is ICollection<T> typed) return typed;
        return ((IEnumerable)values).Cast<object?>().Select(value => (T)value!).ToList();
    }
    internal static IDictionary<TKey, TValue> CastDictionary<TKey, TValue>(object? values)
        where TKey : notnull
    {
        if (values is null) return null!;
        if (values is IDictionary<TKey, TValue> typed) return typed;
        var result = new Dictionary<TKey, TValue>();
        foreach (var entry in (IEnumerable)values)
        {
            var type = entry!.GetType();
            var key = type.GetProperty("Key")!.GetValue(entry);
            var value = type.GetProperty("Value")!.GetValue(entry);
            result.Add((TKey)ConvertCastValue(typeof(TKey), key)!,
                (TValue)ConvertCastValue(typeof(TValue), value)!);
        }
        return result;
    }
    internal static IDictionary CastRawDictionary(object? values)
    {
        if (values is null) return null!;
        if (values is IDictionary dictionary) return dictionary;
        var result = new Dictionary<object, object>();
        foreach (var entry in (IEnumerable)values)
        {
            var type = entry!.GetType();
            var key = type.GetProperty("Key")!.GetValue(entry);
            var value = type.GetProperty("Value")!.GetValue(entry);
            result.Add(key!, value!);
        }
        return result;
    }
    private static object? ConvertCastValue(Type targetType, object? value)
    {
        if (value is null || targetType.IsInstanceOfType(value)) return value;
        if (!targetType.IsGenericType) return value;
        var definition = targetType.GetGenericTypeDefinition();
        var methodName = definition == typeof(IDictionary<,>)
            ? nameof(CastDictionary)
            : definition == typeof(IList<>) ? nameof(CastList) : null;
        if (methodName is null) return value;
        var method = typeof(JavaCompat).GetMethods(BindingFlags.Static | BindingFlags.NonPublic)
            .Single(candidate => candidate.Name == methodName && candidate.IsGenericMethodDefinition);
        return method.MakeGenericMethod(targetType.GetGenericArguments()).Invoke(null, new[] { value });
    }
    internal static Dictionary<TKey, TValue> NewJavaDictionary<TKey, TValue>(params object?[] arguments)
        where TKey : notnull
    {
        var comparer = new JavaEqualityComparer<TKey>();
        if (arguments.Length == 0) return new Dictionary<TKey, TValue>(comparer);
        if (arguments.Length == 1 && arguments[0] is int capacity)
            return new Dictionary<TKey, TValue>(capacity, comparer);
        if (arguments.Length == 1 && arguments[0] is IEnumerable<KeyValuePair<TKey, TValue>> values)
            return new Dictionary<TKey, TValue>(values, comparer);
        throw new ArgumentException("Unsupported Java HashMap constructor arguments.");
    }

    internal static SortedDictionary<TKey, TValue> NewSortedDictionary<TKey, TValue>()
        where TKey : notnull
    {
        return new SortedDictionary<TKey, TValue>(Comparer<TKey>.Create(JavaCompare));
    }

    internal static SortedSet<T> NewSortedSet<T>() =>
        new(Comparer<T>.Create(JavaCompare));

    private static int JavaCompare<T>(T? left, T? right)
    {
        if (ReferenceEquals(left, right)) return 0;
        if (left is null) return -1;
        if (right is null) return 1;
        if (left is Uri leftUri && right is Uri rightUri)
            return string.Compare(leftUri.OriginalString, rightUri.OriginalString,
                StringComparison.Ordinal);
        if (left is string leftString && right is string rightString)
            return string.Compare(leftString, rightString, StringComparison.Ordinal);
        if (left is IComparable<T> generic) return generic.CompareTo(right);
        if (left is IComparable comparable) return comparable.CompareTo(right);
        var method = left.GetType().GetMethod("CompareTo", new[] { right.GetType() });
        if (method is not null) return (int)method.Invoke(left, new object?[] { right })!;
        throw new ArgumentException($"{left.GetType()} does not implement Java Comparable semantics.");
    }

    internal static int CompareNatural<T>(T? left, T? right) =>
        JavaCompare(left, right);

    private sealed class JavaEqualityComparer<T> : IEqualityComparer<T>
    {
        public bool Equals(T? left, T? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(T value) => JavaHashCode(value);
    }

    internal static T[] CopyOf<T>(T[] source, int length)
    {
        var result = new T[length];
        Array.Copy(source, result, Math.Min(source.Length, length));
        return result;
    }
    internal static T[][] NewJaggedArray<T>(int outerLength, int innerLength)
    {
        if (outerLength < 0 || innerLength < 0)
            throw new ArgumentOutOfRangeException("Java array dimensions cannot be negative.");
        return Enumerable.Range(0, outerLength).Select(_ => new T[innerLength]).ToArray();
    }
    internal static T[] CopyOfRange<T>(T[] source, int fromIndex, int toIndex) => source[fromIndex..toIndex];
    internal static void Fill<T>(T[] values, T value) => Array.Fill(values, value);
    internal static void Fill<T>(T[] values, int fromIndex, int toIndex, T value) =>
        Array.Fill(values, value, fromIndex, toIndex - fromIndex);
    internal static T[] EmptyArray<T>() => Array.Empty<T>();

    internal static T[] SingleElementArray<T>(T value) => new[] { value };
    internal static IEnumerator<T> EmptyIterator<T>() => Enumerable.Empty<T>().GetEnumerator();
    internal static string ArrayString(Array value) => string.Join(", ", value.Cast<object?>().Select(StringValueOf));
    internal static string ArrayString<T>(T[] value) => ArrayString((Array)value);
    internal static string ArrayToString(Array value) => "[" + ArrayString(value) + "]";
    internal static string DeepArrayString(Array value) =>
        "[" + string.Join(", ", value.Cast<object?>().Select(item =>
            item is Array nested ? DeepArrayString(nested) : StringValueOf(item))) + "]";
    internal static int BinarySearch(int[] values, int value) => Array.BinarySearch(values, value);
    internal static int BinarySearch<T>(T[] values, T value, IComparer<T> comparer) =>
        Array.BinarySearch(values, value, comparer);
    internal static int BinarySearch<T>(T[] values, T value, Comparison<T> comparison) =>
        Array.BinarySearch(values, value, Comparer<T>.Create(comparison));
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
    internal static int ListIndexOf<T>(IList<T> values, object? value)
    {
        for (var index = 0; index < values.Count; index++)
            if (Equals(values[index], value)) return index;
        return -1;
    }
    internal static void SortList<T>(IList<T> values, IComparer<T>? comparer = null)
    {
        var sorted = values.OrderBy(value => value, comparer ?? Comparer<T>.Create(JavaCompare)).ToArray();
        for (var index = 0; index < sorted.Length; index++) values[index] = sorted[index];
    }
    internal static void SortList<T>(IList<T> values, Comparison<T> comparison) =>
        SortList(values, Comparer<T>.Create(comparison));
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
        if (iterator is JavaRemovableIterator removable) removable.MarkReturned();
        return iterator.Current;
    }

    internal static long IteratorNextLong(IEnumerator<long> iterator) => IteratorNext(iterator);
    internal static void IteratorRemove(IEnumerator iterator)
    {
        if (iterator is not JavaRemovableIterator removable)
            throw new NotSupportedException("This translated Java iterator does not support remove().");
        removable.Remove();
    }

    internal static T DequeGetFirst<T>(JavaDeque<T> deque) => deque.GetFirst();
    internal static T DequePeek<T>(JavaDeque<T> deque) => deque.Peek()!;
    internal static T DequePop<T>(JavaDeque<T> deque) => deque.Pop();
    internal static void DequePush<T>(JavaDeque<T> deque, T value) => deque.Push(value);

    internal new static bool Equals(object? left, object? right)
    {
        if (left is JavaReadOnlyAdapter leftAdapter) left = leftAdapter.MutableSource;
        if (right is JavaReadOnlyAdapter rightAdapter) right = rightAdapter.MutableSource;
        if (ReferenceEquals(left, right)) return true;
        if (left is null || right is null) return false;
        if (left is Uri leftUri)
            return right is Uri rightUri && UriEquals(leftUri, rightUri);
        if (IsJavaList(left)) return IsJavaList(right) && ListsEqual((IEnumerable)left, (IEnumerable)right);
        if (IsJavaSet(left)) return IsJavaSet(right) && SetsEqual((IEnumerable)left, (IEnumerable)right);
        if (left is IDictionary leftMap)
            return right is IDictionary rightMap && MapsEqual(leftMap, rightMap);
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

    internal static int ArrayHash(Array? values)
    {
        if (values is null) return 0;
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
            type.IsGenericType &&
            (type.GetGenericTypeDefinition() == typeof(IList<>) ||
             type.GetGenericTypeDefinition() == typeof(IReadOnlyList<>))));

    private static bool IsJavaSet(object value) =>
        value is IEnumerable && value.GetType().GetInterfaces().Any(type =>
            type.IsGenericType &&
            (type.GetGenericTypeDefinition() == typeof(ISet<>) ||
             type.GetGenericTypeDefinition() == typeof(IReadOnlySet<>)));

    internal static bool IsSet(object? value) => value is not null && IsJavaSet(value);

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

    private static bool SetsEqual(IEnumerable left, IEnumerable right)
    {
        var leftValues = left.Cast<object?>().ToList();
        var rightValues = right.Cast<object?>().ToList();
        return leftValues.Count == rightValues.Count &&
               leftValues.All(leftValue => rightValues.Any(rightValue => Equals(leftValue, rightValue)));
    }

    private static bool MapsEqual(IDictionary left, IDictionary right)
    {
        if (left.Count != right.Count) return false;
        foreach (DictionaryEntry entry in left)
            if (!right.Contains(entry.Key) || !Equals(entry.Value, right[entry.Key])) return false;
        return true;
    }

    private static int JavaHashCode(object? value)
    {
        if (value is JavaReadOnlyAdapter adapter) value = adapter.MutableSource;
        if (value is null) return 0;
        if (value is Uri uri)
        {
            var schemeHash = StringComparer.OrdinalIgnoreCase.GetHashCode(UriScheme(uri) ?? "");
            var fragmentHash = UriEscapedHashCode(UriRawFragment(uri));
            if (UriIsOpaque(uri))
                return System.HashCode.Combine(
                    schemeHash,
                    UriEscapedHashCode(UriRawSchemeSpecificPart(uri)),
                    fragmentHash);
            var host = UriHost(uri);
            var authorityHash = host is null
                ? UriEscapedHashCode(UriRawAuthority(uri))
                : System.HashCode.Combine(
                    UriEscapedHashCode(UriRawUserInfo(uri)),
                    StringComparer.OrdinalIgnoreCase.GetHashCode(host),
                    UriPort(uri));
            return System.HashCode.Combine(
                schemeHash,
                authorityHash,
                UriEscapedHashCode(UriRawPath(uri)),
                UriEscapedHashCode(UriRawQuery(uri)),
                fragmentHash);
        }
        if (value is IDictionary map)
        {
            var result = 0;
            foreach (DictionaryEntry entry in map)
                result += JavaHashCode(entry.Key) ^ JavaHashCode(entry.Value);
            return result;
        }
        if (IsJavaSet(value))
        {
            unchecked
            {
                var result = 0;
                foreach (var element in (IEnumerable)value) result += JavaHashCode(element);
                return result;
            }
        }
        if (!IsJavaList(value)) return value.GetHashCode();
        unchecked
        {
            var result = 1;
            foreach (var element in (IEnumerable)value) result = 31 * result + JavaHashCode(element);
            return result;
        }
    }

    private static string? NormalizeUriEscapes(string? value)
    {
        if (value is null) return null;
        StringBuilder? normalized = null;
        for (var index = 0; index + 2 < value.Length; index++)
        {
            if (value[index] != '%' || !Uri.IsHexDigit(value[index + 1]) ||
                !Uri.IsHexDigit(value[index + 2]))
                continue;
            var upperFirst = char.ToUpperInvariant(value[index + 1]);
            var upperSecond = char.ToUpperInvariant(value[index + 2]);
            if (upperFirst == value[index + 1] && upperSecond == value[index + 2])
            {
                index += 2;
                continue;
            }
            normalized ??= new StringBuilder(value);
            normalized[index + 1] = upperFirst;
            normalized[index + 2] = upperSecond;
            index += 2;
        }
        return normalized?.ToString() ?? value;
    }

    private static bool UriEscapedEquals(string? left, string? right) =>
        string.Equals(NormalizeUriEscapes(left), NormalizeUriEscapes(right),
                      StringComparison.Ordinal);

    private static int UriEscapedHashCode(string? value) =>
        StringComparer.Ordinal.GetHashCode(NormalizeUriEscapes(value) ?? "");

    private static bool UriEquals(Uri left, Uri right)
    {
        if (!string.Equals(UriScheme(left), UriScheme(right), StringComparison.OrdinalIgnoreCase) ||
            !UriEscapedEquals(UriRawFragment(left), UriRawFragment(right)))
            return false;
        var leftOpaque = UriIsOpaque(left);
        var rightOpaque = UriIsOpaque(right);
        if (leftOpaque || rightOpaque)
            return leftOpaque && rightOpaque &&
                   UriEscapedEquals(UriRawSchemeSpecificPart(left),
                                    UriRawSchemeSpecificPart(right));
        if (!UriEscapedEquals(UriRawPath(left), UriRawPath(right)) ||
            !UriEscapedEquals(UriRawQuery(left), UriRawQuery(right)))
            return false;
        var leftHost = UriHost(left);
        var rightHost = UriHost(right);
        if (leftHost is null || rightHost is null)
            return leftHost is null && rightHost is null &&
                   UriEscapedEquals(UriRawAuthority(left), UriRawAuthority(right));
        return string.Equals(leftHost, rightHost, StringComparison.OrdinalIgnoreCase) &&
               UriPort(left) == UriPort(right) &&
               UriEscapedEquals(UriRawUserInfo(left), UriRawUserInfo(right));
    }

    internal static JavaResourceBundle GetResourceBundle(string baseName, CultureInfo locale) => new(baseName, locale);

    internal static string GetResourceString(JavaResourceBundle bundle, string name) => bundle.GetString(name);

    internal static JavaCollector Joining(string delimiter) =>
        new(values => string.Join(delimiter, values.Select(JavaString)));

    internal static JavaCollector Joining(string delimiter, string prefix, string suffix) =>
        new(values => prefix + string.Join(delimiter, values.Select(JavaString)) + suffix);

    internal static bool All(IEnumerable<int> values, Predicate<int> predicate) => values.All(value => predicate(value));

    internal static IEnumerable<T> Skip<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));

    internal static dynamic Collect<T>(IEnumerable<T> values, JavaCollector collector) => collector.Collect(values.Cast<object?>());

    internal static JavaCollector ToMap<T, K, V>(Func<T, K> keySelector, Func<T, V> valueSelector)
        where K : notnull =>
        new(values => values.Cast<T>().ToDictionary(keySelector, valueSelector));

    internal static IEnumerable<TResult> Map<T, TResult>(IEnumerable<T> values, Func<T, TResult> mapper) => values.Select(mapper);
    internal static IEnumerable<long> MapToLong<T>(IEnumerable<T> values, JavaToLongFunction<T> mapper) =>
        values.Select(value => mapper(value));
    internal static long Sum(IEnumerable<long> values) => values.Sum();
    internal static IEnumerable<T> Filter<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Where(predicate);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values) =>
        values.OrderBy(value => value, Comparer<T>.Create(JavaCompare));
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, IComparer<T> comparer) => values.OrderBy(value => value, comparer);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, Comparison<T> comparison) =>
        values.OrderBy(value => value, Comparer<T>.Create(comparison));
    internal static IComparer<T> NaturalOrder<T>() =>
        Comparer<T>.Create(JavaCompare);
    internal static Comparison<T> ToComparison<T>(IComparer<T> comparer) =>
        comparer.Compare;
    internal static Comparison<T> ToComparison<T>(Comparison<T> comparison) =>
        comparison;
    internal static int ComparatorCompare<T>(
        IComparer<T> comparer,
        T left,
        T right) =>
        comparer.Compare(left, right);
    internal static int ComparatorCompare<T>(
        Comparison<T> comparison,
        T left,
        T right) =>
        comparison(left, right);
    internal static IComparer<T> ComparingInt<T>(Func<T, int> selector) =>
        Comparer<T>.Create((left, right) => selector(left).CompareTo(selector(right)));
    internal static IComparer<T> Comparing<T>(Func<T, IComparable> selector) =>
        Comparer<T>.Create((left, right) => selector(left).CompareTo(selector(right)));
    internal static IComparer<T> ThenComparingInt<T>(
        IComparer<T> first,
        Func<T, int> selector) =>
        ThenComparing(first, ComparingInt(selector));
    internal static IComparer<T> ThenComparing<T>(IComparer<T> first, IComparer<T> second) =>
        Comparer<T>.Create(
            (left, right) =>
            {
                var result = first.Compare(left, right);
                return result != 0 ? result : second.Compare(left, right);
            });
    internal static IComparer<T> ThenComparing<T>(
        IComparer<T> first,
        Comparison<T> second) =>
        ThenComparing(first, Comparer<T>.Create(second));
    internal static Comparison<T> ThenComparingInt<T>(
        Comparison<T> first,
        Func<T, int> selector) =>
        ThenComparing(first, ToComparison(ComparingInt(selector)));
    internal static Comparison<T> ThenComparing<T>(
        Comparison<T> first,
        IComparer<T> second) =>
        ThenComparing(first, second.Compare);
    internal static Comparison<T> ThenComparing<T>(
        Comparison<T> first,
        Comparison<T> second) =>
        (left, right) =>
        {
            var result = first(left, right);
            return result != 0 ? result : second(left, right);
        };
    internal static Comparison<T> ReverseComparison<T>(Comparison<T> comparison) =>
        (left, right) => comparison(right, left);
    internal static T[] ToArray<T>(IEnumerable<T> values) => values.ToArray();
    internal static object[] ToObjectArray(System.Collections.IEnumerable values) =>
        values.Cast<object?>().ToArray()!;
    internal static TTarget[] CollectionToArray<TSource, TTarget>(
        IEnumerable<TSource> values, TTarget[] target)
    {
        var source = values.Select(value => (TTarget)(object?)value!).ToArray();
        if (target.Length < source.Length) return source;
        Array.Copy(source, target, source.Length);
        if (target.Length > source.Length) target[source.Length] = default!;
        return target;
    }
    internal static TTarget[] CollectionToArray<TSource, TTarget>(
        IEnumerable<TSource> values, Func<int, TTarget[]> generator) =>
        CollectionToArray(values, generator(0));
    internal static ICollection<object> CastObjects(System.Collections.IEnumerable values) =>
        values is null ? null! : values.Cast<object>().ToList();
    internal static T CastReference<T>(object? value) => (T)value!;
    internal static IComparer<object> EraseComparer<T>(IComparer<T> comparer) =>
        Comparer<object>.Create(
            (left, right) => comparer.Compare((T)left, (T)right));
    internal static T[] ToArrayLoose<T>(System.Collections.IEnumerable values) =>
        values.Cast<object?>().Select(value => (T)value!).ToArray();
    internal static IList<T> ToListValues<T>(IEnumerable<T> values) => values is null ? null! : values.ToList();
    internal static IReadOnlyList<T> ToReadOnlyList<T>(IEnumerable<T> values) =>
        values as IReadOnlyList<T> ?? values.ToList();
    internal static IReadOnlyCollection<T> ToReadOnlyCollection<T>(IEnumerable<T> values) =>
        values as IReadOnlyCollection<T> ?? values.ToList();
    internal static IReadOnlySet<T> ToReadOnlySet<T>(IEnumerable<T> values) =>
        values as IReadOnlySet<T> ?? values.ToHashSet();
    internal static IReadOnlyDictionary<K, V> ToReadOnlyDictionary<K, V>(
        IEnumerable<KeyValuePair<K, V>> values) where K : notnull =>
        values as IReadOnlyDictionary<K, V> ??
        values.ToDictionary(entry => entry.Key, entry => entry.Value);
    internal static T ToReadOnly<T>(object? value) =>
        (T)ToReadOnlyValue(typeof(T), value)!;
    private static object? ToReadOnlyValue(Type targetType, object? value)
    {
        if (value is null || !targetType.IsGenericType) return value;
        if (value is JavaReadOnlyAdapter && targetType.IsInstanceOfType(value)) return value;

        var definition = targetType.GetGenericTypeDefinition();
        var arguments = targetType.GetGenericArguments();
        if (definition == typeof(IReadOnlyList<>) ||
            definition == typeof(IReadOnlyCollection<>))
        {
            var mutableType = typeof(IList<>).MakeGenericType(arguments[0]);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                var transformed = (IList)Activator.CreateInstance(
                    typeof(List<>).MakeGenericType(arguments[0]))!;
                foreach (var item in (IEnumerable)value)
                    transformed.Add(ToReadOnlyValue(arguments[0], item));
                result = transformed;
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlyList<>).MakeGenericType(arguments[0]), result)!);
        }

        if (definition == typeof(IReadOnlySet<>))
        {
            var mutableType = typeof(ISet<>).MakeGenericType(arguments[0]);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                result = Activator.CreateInstance(typeof(HashSet<>).MakeGenericType(arguments[0]))!;
                var add = result.GetType().GetMethod("Add", arguments)!;
                foreach (var item in (IEnumerable)value)
                    add.Invoke(result, new[] { ToReadOnlyValue(arguments[0], item) });
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlySet<>).MakeGenericType(arguments[0]), result)!);
        }

        if (definition == typeof(IReadOnlyDictionary<,>))
        {
            var mutableType = typeof(IDictionary<,>).MakeGenericType(arguments);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                result = Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(arguments))!;
                var add = result.GetType().GetMethod("Add", arguments)!;
                foreach (var entry in (IEnumerable)value)
                {
                    var entryType = entry.GetType();
                    var key = entryType.GetProperty("Key")!.GetValue(entry);
                    var item = entryType.GetProperty("Value")!.GetValue(entry);
                    add.Invoke(result, new[]
                    {
                        ToReadOnlyValue(arguments[0], key),
                        ToReadOnlyValue(arguments[1], item)
                    });
                }
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlyDictionary<,>).MakeGenericType(arguments), result)!);
        }

        return value;
    }
    internal static T ToMutable<T>(object? value) =>
        (T)ToMutableValue(typeof(T), value)!;
    private static object? ToMutableValue(Type targetType, object? value)
    {
        if (value is JavaReadOnlyAdapter adapter &&
            targetType.IsInstanceOfType(adapter.MutableSource))
            return adapter.MutableSource;
        // Arrays satisfy IList<T> in .NET but Java arrays are not Lists: retaining one here
        // breaks Java List structural equality and hashing for public read-only inputs.
        if (value is null || (value is not Array && targetType.IsInstanceOfType(value))) return value;
        if (!targetType.IsGenericType) return value;

        var definition = targetType.GetGenericTypeDefinition();
        var arguments = targetType.GetGenericArguments();
        if (definition == typeof(IList<>) || definition == typeof(ICollection<>))
        {
            var result = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(arguments[0]))!;
            foreach (var item in (IEnumerable)value)
                result.Add(ToMutableValue(arguments[0], item));
            return result;
        }

        if (definition == typeof(ISet<>))
        {
            var result = Activator.CreateInstance(typeof(HashSet<>).MakeGenericType(arguments[0]))!;
            var add = result.GetType().GetMethod("Add", arguments)!;
            foreach (var item in (IEnumerable)value)
                add.Invoke(result, new[] { ToMutableValue(arguments[0], item) });
            return result;
        }

        if (definition == typeof(IDictionary<,>))
        {
            var result = Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(arguments))!;
            var add = result.GetType().GetMethod("Add", arguments)!;
            foreach (var entry in (IEnumerable)value)
            {
                var entryType = entry.GetType();
                var key = entryType.GetProperty("Key")!.GetValue(entry);
                var item = entryType.GetProperty("Value")!.GetValue(entry);
                add.Invoke(result, new[]
                {
                    ToMutableValue(arguments[0], key),
                    ToMutableValue(arguments[1], item)
                });
            }
            return result;
        }

        return value;
    }
    internal static IDictionary<K, V> ToDictionaryValues<K, V>(
        IEnumerable<KeyValuePair<K, V>> values) where K : notnull =>
        values.ToDictionary(entry => entry.Key, entry => entry.Value);

    internal static byte[] ToUnsignedBytes(sbyte[] values) =>
        values.Select(value => unchecked((byte)value)).ToArray();
    internal static byte[] ToUnsignedBytes(byte[] values) => values;
    internal static sbyte[] ToSignedBytes(byte[] values) =>
        values.Select(value => unchecked((sbyte)value)).ToArray();
    internal static void ForEach<T>(IEnumerable<T> values, Action<T> action)
    {
        foreach (var value in values) action(value);
    }
    internal static void ReplaceAll<T>(IList<T> values, Func<T, T> operation)
    {
        ArgumentNullException.ThrowIfNull(operation);
        for (var index = 0; index < values.Count; index++)
            values[index] = operation(values[index]);
    }
    internal static IEnumerable<T> StreamOf<T>(params T[] values) => values;
    internal static IEnumerable<R> FlatMap<T, R>(IEnumerable<T> values,
        Func<T, IEnumerable<R>> mapper) => values.SelectMany(mapper);
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
    internal static bool NoValues<T>(IEnumerable<T> values, Func<T, bool> predicate) => !values.Any(predicate);
    internal static IEnumerable<T> ConcatValues<T>(IEnumerable<T> left, IEnumerable<T> right) => left.Concat(right);
    internal static IEnumerable<T> TakeValues<T>(IEnumerable<T> values, long count) => values.Take(checked((int)count));
    internal static IEnumerable<T> DropValues<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));
    internal static JavaOptional<int> MaxOptional(IEnumerable<int> values) =>
        values.Any() ? JavaOptional<int>.Of(values.Max()) : JavaOptional<int>.Empty();
    internal static int? MaxOptionalInt(IEnumerable<int> values) =>
        values.Any() ? values.Max() : null;
    internal static void OptionalLongIfPresent(long? value, Action<long> consumer)
    {
        if (value.HasValue) consumer(value.Value);
    }
    internal static JavaOptional<T> ReduceOptional<T>(IEnumerable<T> values, Func<T, T, T> reducer)
    {
        using var iterator = values.GetEnumerator();
        if (!iterator.MoveNext()) return JavaOptional<T>.Empty();
        var result = iterator.Current;
        while (iterator.MoveNext()) result = reducer(result, iterator.Current);
        return JavaOptional<T>.Of(result);
    }
    internal static JavaOptional<T> FindFirstOptional<T>(IEnumerable<T> values)
    {
        using var iterator = values.GetEnumerator();
        return iterator.MoveNext()
            ? JavaOptional<T>.Of(iterator.Current)
            : JavaOptional<T>.Empty();
    }
    internal static Func<T, T> AndThen<T>(Func<T, T> first, Func<T, T> second) => value => second(first(value));
    internal static void ForEachRemaining<T>(IEnumerator<T> iterator, Action<T> action)
    {
        while (iterator.MoveNext()) action(iterator.Current);
    }
    internal static void ForEachRemaining<T>(JavaIterator<T> iterator, Action<T> action)
    {
        while (iterator.HasNext()) action(iterator.Next()!);
    }

    internal static IEnumerable<T> LoadServices<T>(Type serviceType, params object?[] ignored) =>
        AppDomain.CurrentDomain.GetAssemblies()
            .SelectMany(assembly =>
            {
                try { return assembly.GetTypes(); }
                catch (ReflectionTypeLoadException error) { return error.Types.Where(type => type is not null)!; }
            })
            .Where(type => type is not null && !type.IsAbstract && serviceType.IsAssignableFrom(type)
                           && type.GetConstructor(Type.EmptyTypes) is not null)
            .Select(type => (T)Activator.CreateInstance(type!)!);

    internal static int HashCode(object? value) => JavaHashCode(value);
    internal static JavaStream<T> Stream<T>(IEnumerable<T> values) => new(values);
    internal static IEnumerable<object> BoxValues<T>(IEnumerable<T> values) => values.Cast<object>();
    internal static JavaCollector ToList<T>() => new(values => values.Cast<T>().ToList());
    internal static JavaCollector ToSet<T>() => new(values => new HashSet<T>(values.Cast<T>()));
    internal static JavaCollector ToCollection<C>(Func<C> supplier)
    {
        return new JavaCollector(values =>
        {
            object collection = supplier()!;
            var collectionInterface = collection.GetType().GetInterfaces().FirstOrDefault(type =>
                type.IsGenericType && type.GetGenericTypeDefinition() == typeof(ICollection<>)) ??
                throw new InvalidOperationException(
                    $"Collector target `{collection.GetType()}` is not a Java collection.");
            var add = collectionInterface.GetMethod(nameof(ICollection<object>.Add))!;
            foreach (var value in values) add.Invoke(collection, new[] { value });
            return collection;
        });
    }

    internal static IList<T> NCopies<T>(int count, T value) => Enumerable.Repeat(value, count).ToList();
    internal static T Min<T>(T left, T right) where T : IComparable<T> => left.CompareTo(right) <= 0 ? left : right;
    internal static T Min<T>(IEnumerable<T> values) => values.Min(Comparer<T>.Default)!;
    internal static T CollectionMin<T>(IEnumerable<T> values) =>
        values.Aggregate((left, right) => JavaCompare(left, right) <= 0 ? left : right);
    internal static T CollectionMax<T>(IEnumerable<T> values) =>
        values.Aggregate((left, right) => JavaCompare(left, right) >= 0 ? left : right);
    internal static IComparer<T> ReverseComparer<T>() =>
        Comparer<T>.Create((left, right) => Comparer<T>.Default.Compare(right, left));
    internal static IList<T> SynchronizedList<T>(IList<T> values) =>
        new JavaSynchronizedList<T>(values);
    internal static JavaStream<T> StreamFilter<T>(
        IEnumerable<T> values, Func<T, bool> predicate) =>
        new(values.Where(predicate));
    internal static JavaStream<T> StreamSorted<T>(IEnumerable<T> values) =>
        new(values.OrderBy(value => value, Comparer<T>.Create(JavaCompare)));
    internal static JavaStream<T> StreamSorted<T>(
        IEnumerable<T> values, IComparer<T> comparer) =>
        new(values.OrderBy(value => value, comparer));
    internal static JavaStream<T> StreamSorted<T>(
        IEnumerable<T> values, Comparison<T> comparison) =>
        new(values.OrderBy(value => value, Comparer<T>.Create(comparison)));

    internal static bool EconomicMapEquals<K, V>(
        IJavaEconomicMap<K, V> left,
        IJavaEconomicMap<K, V> right)
        where K : notnull
    {
        if (ReferenceEquals(left, right)) return true;
        if (left.Size() != right.Size()) return false;
        var cursor = left.GetEntries();
        while (cursor.Advance())
        {
            K key = cursor.GetKey();
            object? leftValue = cursor.GetValue();
            object? rightValue = right.Get(key);
            if (rightValue is null)
            {
                if (leftValue is not null || !right.ContainsKey(key)) return false;
            }
            else if (!Equals(rightValue, leftValue)) return false;
        }
        return true;
    }
    internal static V OrganicGet<K, V>(IDictionary<K, V> values, K key) where K : notnull => MapGet(values, key);
    internal static T OrganicGet<T>(IList<T> values, int index) => values[index];
    internal static V OrganicPut<K, V>(IDictionary<K, V> values, K key, V value) where K : notnull => MapPut(values, key, value);
    internal static ISet<T> OrganicPut<T>(ISet<T> values, T value) { values.Add(value); return values; }
    internal static ISet<T> Assoc<T>(ISet<T> values, T value)
    {
        var result = new HashSet<T>(values, new JavaEqualityComparer<T>());
        result.Add(value);
        return result;
    }
}
