// Ordinary generated-product support for Java contracts with no direct .NET API.
// This file is copied unchanged into disposable projects; it is not a second AST
// and contains no destination-product behavior.
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

public interface JavaCloneable
{
}

public enum JavaRoundingMode
{
    Ceiling
}

public sealed class JavaPriorityQueue<T>
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

public sealed class JavaIdentityHashMap<K, V> : Dictionary<K, V> where K : notnull
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

internal sealed class JavaAssertionError : Exception
{
    internal JavaAssertionError(object? detail)
        : base(JavaCompat.StringValueOf(detail), detail as Exception)
    {
    }
}

internal static class JavaXPathConstants
{
    internal static readonly XmlQualifiedName NODE = new("NODE");
    internal static readonly XmlQualifiedName NODESET = new("NODESET");
}

internal sealed class JavaXPathFactory
{
    internal static readonly JavaXPathFactory Instance = new();
    internal JavaXPath NewXPath() => new();
}

internal sealed class JavaXPath
{
    internal string Evaluate(string expression, object context)
    {
        ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as XmlNode
            ?? throw new ArgumentException("XPath context must be an XML node.", nameof(context));
        return node.SelectSingleNode(expression)?.InnerText ?? string.Empty;
    }

    internal object? Evaluate(string expression, object context, XmlQualifiedName returnType)
    {
        ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as XmlNode
            ?? throw new ArgumentException("XPath context must be an XML node.", nameof(context));
        if (returnType == JavaXPathConstants.NODESET) return node.SelectNodes(expression);
        if (returnType == JavaXPathConstants.NODE) return node.SelectSingleNode(expression);
        return Evaluate(expression, context);
    }
}

internal static class JavaStandardCharsets
{
    internal static readonly Encoding UTF8 = new UTF8Encoding(false);
    // Java UTF-16 consumes an optional BOM and defaults to big-endian.
    // Keep a distinct instance so JavaCompat can retain that contract while
    // UTF-16BE remains a BOM-agnostic fixed-endian charset.
    internal static readonly Encoding UTF16 = new UnicodeEncoding(true, true);
    internal static readonly Encoding UTF16BE = Encoding.BigEndianUnicode;
    internal static readonly Encoding UTF16LE = Encoding.Unicode;
    internal static readonly Encoding USASCII = Encoding.ASCII;
    internal static readonly Encoding ISO88591 = Encoding.Latin1;
}

internal delegate TResult JavaIntFunction<out TResult>(int value);
internal delegate int JavaToIntFunction<in TValue>(TValue value);
internal delegate long JavaToLongFunction<in TValue>(TValue value);
internal delegate bool JavaBiPredicate<in TLeft, in TRight>(TLeft left, TRight right);

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

internal
enum JavaTimeUnit { NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS }
internal static class JavaTimeUnits
{
    internal static TimeSpan ToTimeSpan(long value, JavaTimeUnit unit) => unit switch
    {
        JavaTimeUnit.NANOSECONDS => TimeSpan.FromTicks(value / 100),
        JavaTimeUnit.MICROSECONDS => TimeSpan.FromTicks(checked(value * 10)),
        JavaTimeUnit.MILLISECONDS => TimeSpan.FromMilliseconds(value),
        JavaTimeUnit.SECONDS => TimeSpan.FromSeconds(value),
        JavaTimeUnit.MINUTES => TimeSpan.FromMinutes(value),
        JavaTimeUnit.HOURS => TimeSpan.FromHours(value),
        JavaTimeUnit.DAYS => TimeSpan.FromDays(value),
        _ => throw new ArgumentOutOfRangeException(nameof(unit))
    };
}
internal enum JavaProcessRedirect { INHERIT }

// C# forbids goto from a finally clause, while Java permits a labeled break or
// continue to complete a finally clause abruptly. The recursive translator
// catches this internal signal at the nearest translated try boundary before
// ordinary Java exception handlers can observe it.
internal sealed class JavaLabeledControlFlowException(int branchId) : Exception
{
    internal int BranchId { get; } = branchId;
}

// Carries cancellation through ordinary translated Java blocking primitives.
// Product runtimes install a token at their evaluation boundary; the generic
// compatibility layer only observes that token and does not define product
// timeout policy.
internal sealed class JavaCancellationException : OperationCanceledException
{
    internal JavaCancellationException(CancellationToken token)
        : base("The translated Java operation was cancelled.", token) { }
}

[Serializable]
internal sealed class JavaNumberFormatException : ArgumentException
{
    internal JavaNumberFormatException(string message) : base(message) { }
    internal JavaNumberFormatException(string message, Exception cause)
        : base(message, cause) { }
}

internal sealed class JavaDecimalFormat
{
    private readonly string integerPattern;
    private readonly NumberFormatInfo format;
    private int maximumFractionDigits;
    private bool groupingUsed;

    internal JavaDecimalFormat(string pattern, NumberFormatInfo format)
    {
        this.format = (NumberFormatInfo)format.Clone();
        var decimalPoint = pattern.IndexOf('.');
        integerPattern = decimalPoint < 0 ? pattern : pattern[..decimalPoint];
        groupingUsed = integerPattern.Contains(',');
        maximumFractionDigits = decimalPoint < 0 ? 0 : pattern.Length - decimalPoint - 1;
    }

    internal static JavaDecimalFormat GetNumberInstance(CultureInfo culture) =>
        new("#,##0.###", culture.NumberFormat);

    private string Pattern =>
        (groupingUsed ? integerPattern : integerPattern.Replace(",", string.Empty, StringComparison.Ordinal)) +
        (maximumFractionDigits == 0 ? string.Empty : "." + new string('#', maximumFractionDigits));

    internal string Format(long value) => value.ToString(Pattern, format);
    internal string Format(double value) => value.ToString(Pattern, format);
    internal string Format(object? value) =>
        value is IFormattable formattable
            ? formattable.ToString(Pattern, format) ?? string.Empty
            : value?.ToString() ?? string.Empty;

    internal int GetMaximumFractionDigits() => maximumFractionDigits;

    internal void SetMaximumFractionDigits(int value)
    {
        maximumFractionDigits = Math.Max(0, value);
    }

    internal void SetGroupingUsed(bool value) => groupingUsed = value;
}

internal sealed class JavaMessageDigest
{
    private readonly IncrementalHash digest;

    private JavaMessageDigest(HashAlgorithmName algorithm) =>
        digest = IncrementalHash.CreateHash(algorithm);

    internal static JavaMessageDigest GetInstance(string algorithm)
    {
        var normalized = algorithm.Replace("-", string.Empty, StringComparison.Ordinal)
            .ToUpperInvariant();
        var name = normalized switch
        {
            "MD5" => HashAlgorithmName.MD5,
            "SHA1" => HashAlgorithmName.SHA1,
            "SHA256" => HashAlgorithmName.SHA256,
            "SHA384" => HashAlgorithmName.SHA384,
            "SHA512" => HashAlgorithmName.SHA512,
            _ => throw new CryptographicException($"Unsupported message digest `{algorithm}`")
        };
        return new JavaMessageDigest(name);
    }

    internal void Update(sbyte value) => digest.AppendData(new[] { unchecked((byte)value) });

    internal void Update(sbyte[] value) =>
        digest.AppendData(value.Select(item => unchecked((byte)item)).ToArray());

    internal void Update(sbyte[] value, int offset, int length) =>
        digest.AppendData(value.Skip(offset).Take(length)
            .Select(item => unchecked((byte)item)).ToArray());

    internal sbyte[] Digest() =>
        digest.GetHashAndReset().Select(item => unchecked((sbyte)item)).ToArray();

    internal sbyte[] Digest(sbyte[] value)
    {
        Update(value);
        return Digest();
    }

    internal static bool IsEqual(sbyte[] left, sbyte[] right) =>
        left.Length == right.Length &&
        CryptographicOperations.FixedTimeEquals(
            left.Select(item => unchecked((byte)item)).ToArray(),
            right.Select(item => unchecked((byte)item)).ToArray());
}

public interface JavaSecretKey
{
    sbyte[] GetEncoded();
}

public sealed class JavaSecurityProvider
{
}

public sealed class JavaAlgorithmParameters
{
    private readonly sbyte[] encoded;

    internal JavaAlgorithmParameters(sbyte[] encoded, sbyte[] iv)
    {
        this.encoded = (sbyte[])encoded.Clone();
        Iv = (sbyte[])iv.Clone();
    }

    internal sbyte[] Iv { get; }

    public sbyte[] GetEncoded(string format)
    {
        if (!string.Equals(format, "ASN.1", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException(
                $"Unsupported algorithm-parameter encoding `{format}`.");
        return (sbyte[])encoded.Clone();
    }
}

public sealed class JavaAlgorithmParameterGenerator
{
    private readonly string algorithm;

    private JavaAlgorithmParameterGenerator(string algorithm)
    {
        this.algorithm = algorithm;
    }

    public static JavaAlgorithmParameterGenerator GetInstance(
        string algorithm,
        JavaSecurityProvider _)
    {
        if (!string.Equals(
                algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal) &&
            !string.Equals(algorithm, "RC2", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException(
                $"Unsupported algorithm-parameter generator `{algorithm}`.");
        return new JavaAlgorithmParameterGenerator(algorithm);
    }

    public JavaAlgorithmParameters GenerateParameters()
    {
        _ = algorithm;
        var iv = new byte[8];
        RandomNumberGenerator.Fill(iv);
        var writer = new System.Formats.Asn1.AsnWriter(
            System.Formats.Asn1.AsnEncodingRules.DER);
        writer.PushSequence();
        writer.WriteInteger(58);
        writer.WriteOctetString(iv);
        writer.PopSequence();
        return new JavaAlgorithmParameters(
            JavaCompat.ToSignedBytes(writer.Encode()),
            JavaCompat.ToSignedBytes(iv));
    }
}

public sealed class JavaKeyGenerator
{
    private readonly string algorithm;
    private int keySize;

    private JavaKeyGenerator(string algorithm)
    {
        this.algorithm = algorithm;
        keySize = string.Equals(algorithm, "AES", StringComparison.OrdinalIgnoreCase)
            ? 256
            : 128;
    }

    public static JavaKeyGenerator GetInstance(string algorithm) =>
        new(ValidateAlgorithm(algorithm));

    public static JavaKeyGenerator GetInstance(
        string algorithm,
        JavaSecurityProvider _) =>
        new(ValidateAlgorithm(algorithm));

    public void Init(int bits)
    {
        if (bits <= 0 || bits % 8 != 0)
            throw new CryptographicException($"Invalid key size `{bits}`.");
        keySize = bits;
    }

    public void Init(int bits, JavaRandom _)
    {
        Init(bits);
    }

    public JavaSecretKey GenerateKey()
    {
        var key = new byte[keySize / 8];
        RandomNumberGenerator.Fill(key);
        return new JavaSecretKeySpec(
            JavaCompat.ToSignedBytes(key),
            string.Equals(algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal)
                ? "RC2"
                : algorithm);
    }

    private static string ValidateAlgorithm(string algorithm)
    {
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        if (!string.Equals(algorithm, "AES", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "RC2", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal))
            throw new CryptographicException(
                $"Unsupported key-generator algorithm `{algorithm}`.");
        return algorithm;
    }
}

public sealed class JavaSecretKeySpec : JavaSecretKey
{
    private readonly sbyte[] encoded;

    public JavaSecretKeySpec(sbyte[] encoded, string algorithm)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        this.encoded = (sbyte[])encoded.Clone();
        Algorithm = algorithm;
    }

    internal string Algorithm { get; }

    public sbyte[] GetEncoded() => (sbyte[])encoded.Clone();
}

public sealed class JavaIvParameterSpec
{
    public JavaIvParameterSpec(sbyte[] iv)
    {
        ArgumentNullException.ThrowIfNull(iv);
        Iv = (sbyte[])iv.Clone();
    }

    internal sbyte[] Iv { get; }
}

public sealed class JavaCipher : IDisposable
{
    public const int ENCRYPT_MODE = 1;
    public const int DECRYPT_MODE = 2;

    private readonly string transformation;
    private SymmetricAlgorithm? algorithm;
    private ICryptoTransform? transform;
    private RSA? rsa;
    private int asymmetricMode;

    private JavaCipher(string transformation)
    {
        ArgumentException.ThrowIfNullOrEmpty(transformation);
        this.transformation = transformation;
    }

    public static JavaCipher GetInstance(string transformation) => new(transformation);

    public static JavaCipher GetInstance(
        string transformation,
        JavaSecurityProvider _) =>
        new(transformation);

    public static int GetMaxAllowedKeyLength(string algorithm)
    {
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        return int.MaxValue;
    }

    public void Init(int mode, object key) => Init(mode, key, (JavaIvParameterSpec?)null);

    public void Init(int mode, object key, JavaAlgorithmParameters parameters)
    {
        ArgumentNullException.ThrowIfNull(parameters);
        Init(mode, key, new JavaIvParameterSpec(parameters.Iv));
    }

    public void Init(int mode, object key, JavaIvParameterSpec? parameters)
    {
        DisposeTransform();
        if (key is RSA rsaKey)
        {
            if (!string.Equals(
                    transformation, "1.2.840.113549.1.1.1", StringComparison.Ordinal) &&
                !string.Equals(transformation, "RSA", StringComparison.OrdinalIgnoreCase) &&
                !transformation.StartsWith("RSA/", StringComparison.OrdinalIgnoreCase))
                throw new CryptographicException(
                    $"Unsupported asymmetric cipher transformation `{transformation}`.");
            rsa = rsaKey;
            asymmetricMode = mode;
            return;
        }
        if (key is not JavaSecretKeySpec keySpec)
            throw new CryptographicException("Cipher key must be a SecretKeySpec.");
        if (!string.Equals(keySpec.Algorithm, "AES", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(keySpec.Algorithm, "RC2", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException($"Unsupported cipher key algorithm `{keySpec.Algorithm}`.");

        var parts = transformation.Split('/');
        var rc2 = string.Equals(
            transformation, "1.2.840.113549.3.2", StringComparison.Ordinal) ||
            string.Equals(transformation, "RC2", StringComparison.OrdinalIgnoreCase);
        if (!rc2 &&
            (parts.Length != 3 ||
             !string.Equals(parts[0], "AES", StringComparison.OrdinalIgnoreCase)))
            throw new CryptographicException(
                $"Unsupported cipher transformation `{transformation}`.");

        SymmetricAlgorithm symmetric = rc2 ? RC2.Create() : Aes.Create();
        symmetric.Key = JavaCompat.ToUnsignedBytes(keySpec.GetEncoded());
        symmetric.Mode = (rc2 ? "CBC" : parts[1].ToUpperInvariant()) switch
        {
            "CBC" => CipherMode.CBC,
            "ECB" => CipherMode.ECB,
            _ => throw new CryptographicException(
                $"Unsupported cipher mode `{parts[1]}`.")
        };
        symmetric.Padding = (rc2 ? "PKCS5PADDING" : parts[2].ToUpperInvariant()) switch
        {
            "NOPADDING" => PaddingMode.None,
            "PKCS5PADDING" => PaddingMode.PKCS7,
            _ => throw new CryptographicException(
                $"Unsupported cipher padding `{parts[2]}`.")
        };
        if (symmetric.Mode != CipherMode.ECB)
        {
            if (parameters is null)
                throw new CryptographicException("CBC mode requires an initialization vector.");
            symmetric.IV = JavaCompat.ToUnsignedBytes(parameters.Iv);
        }

        algorithm = symmetric;
        transform = mode switch
        {
            ENCRYPT_MODE => symmetric.CreateEncryptor(),
            DECRYPT_MODE => symmetric.CreateDecryptor(),
            _ => throw new CryptographicException($"Unsupported cipher mode constant `{mode}`.")
        };
    }

    public sbyte[]? Update(sbyte[] input, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(input);
        var current = RequireTransform();
        var source = JavaCompat.ToUnsignedBytes(input);
        var destination = new byte[length + current.OutputBlockSize];
        var written = current.TransformBlock(source, offset, length, destination, 0);
        return written == 0
            ? null
            : JavaCompat.ToSignedBytes(destination.AsSpan(0, written).ToArray());
    }

    public sbyte[] DoFinal() => DoFinal(Array.Empty<sbyte>());

    public sbyte[] DoFinal(sbyte[] input)
    {
        ArgumentNullException.ThrowIfNull(input);
        if (rsa is not null)
        {
            var asymmetricResult = asymmetricMode switch
            {
                ENCRYPT_MODE => rsa.Encrypt(
                    JavaCompat.ToUnsignedBytes(input), RSAEncryptionPadding.Pkcs1),
                DECRYPT_MODE => rsa.Decrypt(
                    JavaCompat.ToUnsignedBytes(input), RSAEncryptionPadding.Pkcs1),
                _ => throw new CryptographicException(
                    $"Unsupported cipher mode constant `{asymmetricMode}`.")
            };
            rsa = null;
            asymmetricMode = 0;
            return JavaCompat.ToSignedBytes(asymmetricResult);
        }
        var finalResult = RequireTransform().TransformFinalBlock(
            JavaCompat.ToUnsignedBytes(input), 0, input.Length);
        DisposeTransform();
        return JavaCompat.ToSignedBytes(finalResult);
    }

    internal CryptoStream CreateInputStream(Stream input)
    {
        ArgumentNullException.ThrowIfNull(input);
        var current = transform ??
            throw new InvalidOperationException("Cipher has not been initialized.");
        transform = null;
        return new CryptoStream(input, current, CryptoStreamMode.Read, leaveOpen: true);
    }

    private ICryptoTransform RequireTransform() =>
        transform ?? throw new InvalidOperationException("Cipher has not been initialized.");

    private void DisposeTransform()
    {
        transform?.Dispose();
        transform = null;
        algorithm?.Dispose();
        algorithm = null;
        rsa = null;
        asymmetricMode = 0;
    }

    public void Dispose() => DisposeTransform();
}

public sealed class JavaCipherInputStream : Stream
{
    private readonly JavaCipher cipher;
    private readonly CryptoStream stream;

    public JavaCipherInputStream(Stream input, JavaCipher cipher)
    {
        ArgumentNullException.ThrowIfNull(cipher);
        this.cipher = cipher;
        stream = cipher.CreateInputStream(input);
    }

    public override bool CanRead => stream.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush() => stream.Flush();
    public override int Read(byte[] buffer, int offset, int count) =>
        stream.Read(buffer, offset, count);
    public override int Read(Span<byte> buffer) => stream.Read(buffer);
    public override long Seek(long offset, SeekOrigin origin) =>
        throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            stream.Dispose();
            cipher.Dispose();
        }
        base.Dispose(disposing);
    }
}

public abstract class JavaReference<T> where T : class
{
    public abstract T? Get();
    public abstract void Clear();
}

public sealed class JavaSoftReference<T> : JavaReference<T> where T : class
{
    private WeakReference<T>? reference;

    public JavaSoftReference(T value)
    {
        ArgumentNullException.ThrowIfNull(value);
        reference = new WeakReference<T>(value);
    }

    public override T? Get()
    {
        var current = reference;
        return current is not null && current.TryGetTarget(out var value) ? value : null;
    }

    public override void Clear() => reference = null;
}

public sealed class JavaWeakReference<T> : JavaReference<T> where T : class
{
    private WeakReference<T>? reference;

    public JavaWeakReference(T value)
    {
        ArgumentNullException.ThrowIfNull(value);
        reference = new WeakReference<T>(value);
    }

    public override T? Get()
    {
        var current = reference;
        return current is not null && current.TryGetTarget(out var value) ? value : null;
    }

    public override void Clear() => reference = null;
}

public static class JavaBase64
{
    private static readonly JavaBase64Decoder Decoder = new();

    public static JavaBase64Decoder GetDecoder() => Decoder;
}

public sealed class JavaBase64Decoder
{
    public sbyte[] Decode(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return JavaCompat.ToSignedBytes(Convert.FromBase64String(value));
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

[AttributeUsage(AttributeTargets.Field, AllowMultiple = false, Inherited = false)]
internal sealed class JavaEnumNameAttribute(string name) : Attribute
{
    internal string Name { get; } = name;
}

internal sealed class JavaUriSyntaxException : UriFormatException
{
    internal string InputText { get; }
    internal string Reason { get; }
    internal int Index { get; }

    internal JavaUriSyntaxException(string input, string reason, int index = -1)
        : base(index < 0 ? $"{reason}: {input}" : $"{reason} at index {index}: {input}")
    {
        InputText = input;
        Reason = reason;
        Index = index;
    }
}

// java.nio.file.NoSuchFileException carries the missing path as its message.
// System.IO.FileNotFoundException instead decorates Message and therefore
// changes the evaluator diagnostic even when given the same path.
internal sealed class NoSuchFileException : IOException
{
    internal NoSuchFileException(string path) : base(path) { }
    internal NoSuchFileException(string path, Exception cause) : base(path, cause) { }
}

internal sealed class JavaFileNotFoundException : FileNotFoundException
{
    // Java's no-argument FileNotFoundException has a null message. The CLR
    // supplies a generic fallback message, which would become a spurious
    // destination diagnostic unless the Java contract is retained explicitly.
    public override string Message => null!;
}

internal static class JavaCancellation
{
    private sealed record Binding(object Owner, CancellationToken Token);
    private static readonly AsyncLocal<IReadOnlyList<Binding>?> Bindings = new();

    internal static CancellationToken CurrentToken =>
        Bindings.Value is { Count: > 0 } bindings
            ? bindings[^1].Token
            : CancellationToken.None;

    internal static void Push(object owner, CancellationToken token)
    {
        var bindings = Bindings.Value is { } current
            ? current.ToList()
            : new List<Binding>();
        bindings.Add(new Binding(owner, token));
        Bindings.Value = bindings;
    }

    internal static void Pop(object owner)
    {
        if (Bindings.Value is not { Count: > 0 } current ||
            !ReferenceEquals(current[^1].Owner, owner))
            throw new InvalidOperationException("Java cancellation scopes must be left in enter order.");
        Bindings.Value = current.Count == 1 ? null : current.Take(current.Count - 1).ToList();
    }

    internal static void ThrowIfCancellationRequested()
    {
        var token = CurrentToken;
        if (token.IsCancellationRequested) throw new JavaCancellationException(token);
    }
}

// Java's Future and CompletableFuture share one reference in APIs that cache
// an asynchronously completed result. TaskCompletionSource is the matching
// .NET primitive, while this small facade preserves Java's blocking get() and
// ExecutionException wrapping for translated callers.
internal sealed class JavaFuture<T>
{
    private readonly TaskCompletionSource<T>? completion;
    private readonly Task<T> task;

    internal JavaFuture()
    {
        completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        task = completion.Task;
    }

    private JavaFuture(Task<T> task) => this.task = task;
    internal Task CompletionTask => task;

    internal static JavaFuture<T> Run(Func<T> callable, CancellationToken cancellation) =>
        new(Task.Run(callable, cancellation));

    internal static JavaFuture<T> Run(Func<T> callable, CancellationToken cancellation,
        TaskScheduler scheduler) =>
        new(Task.Factory.StartNew(callable, cancellation,
            TaskCreationOptions.DenyChildAttach, scheduler));

    internal bool Complete(T value) => completion?.TrySetResult(value) ?? false;
    internal bool CompleteExceptionally(Exception error) =>
        completion?.TrySetException(error) ?? false;

    internal T Get()
    {
        var cancellation = JavaCancellation.CurrentToken;
        try
        {
            return cancellation.CanBeCanceled
                ? task.WaitAsync(cancellation).GetAwaiter().GetResult()
                : task.GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            throw new JavaCancellationException(cancellation);
        }
        catch (Exception error)
        {
            throw new AggregateException(error);
        }
    }

    internal T Get(long timeout, JavaTimeUnit unit)
    {
        try
        {
            return task.WaitAsync(JavaTimeUnits.ToTimeSpan(timeout, unit),
                    JavaCancellation.CurrentToken)
                .GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (JavaCancellation.CurrentToken.IsCancellationRequested)
        {
            throw new JavaCancellationException(JavaCancellation.CurrentToken);
        }
        catch (TimeoutException)
        {
            throw;
        }
        catch (Exception error)
        {
            throw new AggregateException(error);
        }
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaExecutorService
{
    private readonly object sync = new();
    private readonly List<Task> tasks = new();
    private readonly CancellationTokenSource cancellation = new();
    private readonly JavaFixedThreadTaskScheduler? scheduler;
    private bool shutdown;

    internal JavaExecutorService() { }

    internal JavaExecutorService(int workerCount) :
        this(workerCount, runnable => new JavaThread(runnable)) { }

    internal JavaExecutorService(int workerCount, JavaThreadFactory threadFactory) =>
        scheduler = new JavaFixedThreadTaskScheduler(workerCount, threadFactory);

    internal JavaFuture<T> Submit<T>(Func<T> callable) =>
        Track(scheduler is null
            ? JavaFuture<T>.Run(callable, Token())
            : JavaFuture<T>.Run(callable, Token(), scheduler));

    internal JavaFuture<object> Submit(Action runnable) =>
        Submit<object>(() =>
        {
            runnable();
            return null!;
        });

    internal void Shutdown()
    {
        lock (sync) shutdown = true;
        scheduler?.Complete();
    }

    internal IList<Action> ShutdownNow()
    {
        lock (sync) shutdown = true;
        cancellation.Cancel();
        scheduler?.Complete();
        return new List<Action>();
    }

    internal bool AwaitTermination(long timeout, JavaTimeUnit unit)
    {
        Task[] pending;
        lock (sync) pending = tasks.ToArray();
        var duration = JavaTimeUnits.ToTimeSpan(timeout, unit);
        var started = Stopwatch.StartNew();
        if (!Task.WhenAll(pending).Wait(duration)) return false;
        return scheduler?.AwaitTermination(duration - started.Elapsed) ?? true;
    }

    private CancellationToken Token()
    {
        lock (sync)
        {
            if (shutdown) throw new InvalidOperationException("Executor service is shut down.");
            return cancellation.Token;
        }
    }

    private JavaFuture<T> Track<T>(JavaFuture<T> future)
    {
        var marker = future.CompletionTask;
        lock (sync) tasks.Add(marker);
        _ = marker.ContinueWith(completed =>
        {
            lock (sync) tasks.Remove(completed);
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
        return future;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaByteBuffer : IDisposable
{
    private readonly sbyte[]? bytes;
    private readonly MemoryMappedFile? mappedFile;
    private readonly MemoryMappedViewAccessor? mappedView;
    private readonly bool ownsMapping;
    private readonly bool direct;
    private readonly int capacity;
    private int cursor;
    private int upperBound;
    private int markedCursor = -1;
    private bool disposed;

    private JavaByteBuffer(sbyte[] bytes, bool direct = false)
    {
        this.bytes = bytes;
        this.direct = direct;
        capacity = bytes.Length;
        upperBound = capacity;
    }

    private JavaByteBuffer(
        MemoryMappedFile mappedFile,
        MemoryMappedViewAccessor mappedView,
        int capacity,
        bool ownsMapping)
    {
        this.mappedFile = mappedFile;
        this.mappedView = mappedView;
        this.capacity = capacity;
        this.ownsMapping = ownsMapping;
        direct = true;
        upperBound = capacity;
    }

    internal static JavaByteBuffer Direct(sbyte[] bytes) => new(bytes, direct: true);
    internal static JavaByteBuffer Direct(
        MemoryMappedFile mappedFile,
        MemoryMappedViewAccessor mappedView,
        int capacity) => new(mappedFile, mappedView, capacity, ownsMapping: true);
    public static JavaByteBuffer allocate(int capacity) =>
        capacity < 0
            ? throw new ArgumentOutOfRangeException(nameof(capacity))
            : new JavaByteBuffer(new sbyte[capacity]);
    public static JavaByteBuffer wrap(sbyte[] bytes) =>
        new(bytes ?? throw new ArgumentNullException(nameof(bytes)));
    public sbyte[] array()
    {
        ThrowIfDisposed();
        if (direct || bytes is null)
            throw new NotSupportedException("A direct Java byte buffer has no accessible array.");
        return bytes;
    }
    public JavaByteBuffer clear()
    {
        ThrowIfDisposed();
        cursor = 0;
        upperBound = capacity;
        return this;
    }
    public JavaByteBuffer duplicate()
    {
        ThrowIfDisposed();
        var duplicate = mappedView is null
            ? new JavaByteBuffer(bytes!, direct)
            : new JavaByteBuffer(mappedFile!, mappedView, capacity, ownsMapping: false);
        duplicate.cursor = cursor;
        duplicate.upperBound = upperBound;
        return duplicate;
    }
    public sbyte get()
    {
        ThrowIfDisposed();
        if (cursor >= upperBound) throw new EndOfStreamException();
        return ReadByte(cursor++);
    }
    public sbyte get(int index)
    {
        ThrowIfDisposed();
        if ((uint)index >= (uint)upperBound) throw new ArgumentOutOfRangeException(nameof(index));
        return ReadByte(index);
    }
    public JavaByteBuffer get(sbyte[] destination, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(destination);
        if (offset < 0 || length < 0 || offset + length > destination.Length)
            throw new ArgumentOutOfRangeException();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        if (mappedView is null)
        {
            Array.Copy(bytes!, cursor, destination, offset, length);
        }
        else
        {
            var unsigned = new byte[length];
            var read = mappedView.ReadArray(cursor, unsigned, 0, length);
            if (read != length) throw new EndOfStreamException();
            Buffer.BlockCopy(unsigned, 0, destination, offset, length);
        }
        cursor += length;
        return this;
    }
    public JavaByteBuffer get(sbyte[] destination) =>
        get(destination, 0, destination.Length);
    public JavaByteBuffer mark()
    {
        ThrowIfDisposed();
        markedCursor = cursor;
        return this;
    }
    public JavaByteBuffer reset()
    {
        ThrowIfDisposed();
        if (markedCursor < 0) throw new InvalidOperationException("ByteBuffer mark is not set.");
        cursor = markedCursor;
        return this;
    }
    public bool isDirect()
    {
        ThrowIfDisposed();
        return direct;
    }
    public int limit()
    {
        ThrowIfDisposed();
        return upperBound;
    }
    public JavaByteBuffer limit(int value)
    {
        ThrowIfDisposed();
        if (value < 0 || value > capacity) throw new ArgumentOutOfRangeException(nameof(value));
        upperBound = value;
        if (cursor > upperBound) cursor = upperBound;
        return this;
    }
    public int position()
    {
        ThrowIfDisposed();
        return cursor;
    }
    public JavaByteBuffer position(int value)
    {
        ThrowIfDisposed();
        if (value < 0 || value > upperBound) throw new ArgumentOutOfRangeException(nameof(value));
        cursor = value;
        return this;
    }
    public JavaByteBuffer put(sbyte value)
    {
        ThrowIfDisposed();
        if (cursor >= upperBound) throw new EndOfStreamException();
        if (mappedView is not null)
            throw new NotSupportedException("A read-only mapped Java byte buffer cannot be written.");
        bytes![cursor++] = value;
        return this;
    }
    public JavaByteBuffer put(sbyte[] source) => put(source, 0, source.Length);
    public JavaByteBuffer put(sbyte[] source, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        if (offset < 0 || length < 0 || offset + length > source.Length)
            throw new ArgumentOutOfRangeException();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        if (mappedView is not null)
            throw new NotSupportedException("A read-only mapped Java byte buffer cannot be written.");
        Array.Copy(source, offset, bytes!, cursor, length);
        cursor += length;
        return this;
    }
    public JavaByteBuffer rewind()
    {
        ThrowIfDisposed();
        cursor = 0;
        return this;
    }
    internal int Remaining
    {
        get
        {
            ThrowIfDisposed();
            return upperBound - cursor;
        }
    }
    internal sbyte[] ReadRemaining(int count)
    {
        count = Math.Min(count, Remaining);
        var result = new sbyte[count];
        get(result, 0, count);
        return result;
    }
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        if (!ownsMapping) return;
        mappedView?.Dispose();
        mappedFile?.Dispose();
    }
    private sbyte ReadByte(int index) =>
        mappedView is null ? bytes![index] : unchecked((sbyte)mappedView.ReadByte(index));
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

public enum JavaCodingErrorAction
{
    Report
}

public sealed class JavaCharsetDecoder
{
    private readonly Encoding encoding;

    public JavaCharsetDecoder(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        this.encoding = (Encoding)encoding.Clone();
    }

    public JavaCharsetDecoder ReportErrors(JavaCodingErrorAction action)
    {
        if (action != JavaCodingErrorAction.Report)
            throw new ArgumentOutOfRangeException(nameof(action));
        encoding.DecoderFallback = DecoderFallback.ExceptionFallback;
        return this;
    }

    public string Decode(JavaByteBuffer buffer)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        return encoding.GetString(JavaCompat.ToUnsignedBytes(
            buffer.ReadRemaining(buffer.Remaining)));
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaPath : IEquatable<JavaPath>
{
    internal string Value { get; }
    public JavaPath(string value) =>
        Value = value ?? throw new ArgumentNullException(nameof(value));
    public bool Equals(JavaPath? other) =>
        other is not null && string.Equals(Value, other.Value,
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    public override bool Equals(object? obj) => Equals(obj as JavaPath);
    public override int GetHashCode() =>
        (OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal)
            .GetHashCode(Value);
    public override string ToString() => Value;
    public static implicit operator string(JavaPath path) => path.Value;
    public static implicit operator JavaPath(string path) => new(path);
}

internal enum JavaFileChannelMapMode { READ_ONLY }
internal enum JavaStandardOpenOption { READ }

internal sealed class JavaFileChannel : IDisposable
{
    private readonly FileStream stream;
    private bool disposed;

    private JavaFileChannel(string path) =>
        stream = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete);

    internal static JavaFileChannel open(string path, params object?[] _) => new(path);
    internal long size()
    {
        ThrowIfDisposed();
        return stream.Length;
    }
    internal JavaFileChannel position(long value)
    {
        ThrowIfDisposed();
        stream.Position = value;
        return this;
    }
    internal int read(JavaByteBuffer destination)
    {
        ThrowIfDisposed();
        var count = destination.Remaining;
        if (count == 0) return 0;
        var unsigned = new byte[count];
        var read = stream.Read(unsigned, 0, count);
        if (read == 0) return -1;
        var signed = new sbyte[read];
        Buffer.BlockCopy(unsigned, 0, signed, 0, read);
        destination.put(signed);
        return read;
    }
    internal void close() => Dispose();
    internal JavaByteBuffer map(JavaFileChannelMapMode mode, long offset, long size)
    {
        ThrowIfDisposed();
        if (mode != JavaFileChannelMapMode.READ_ONLY)
            throw new NotSupportedException($"Unsupported file-channel map mode {mode}.");
        if (offset < 0 || size < 0 || size > int.MaxValue ||
            offset > stream.Length || size > stream.Length - offset)
            throw new ArgumentOutOfRangeException();
        if (size == 0) return JavaByteBuffer.Direct(Array.Empty<sbyte>());
        var mappedFile = MemoryMappedFile.CreateFromFile(
            stream, null, 0, MemoryMappedFileAccess.Read,
            HandleInheritability.None, leaveOpen: true);
        var mappedView = mappedFile.CreateViewAccessor(
            offset, size, MemoryMappedFileAccess.Read);
        return JavaByteBuffer.Direct(mappedFile, mappedView, (int)size);
    }
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stream.Dispose();
    }
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

internal sealed class JavaRandomAccessFile : IDisposable
{
    private readonly FileStream stream;
    private bool disposed;

    internal JavaRandomAccessFile(FileInfo file, string mode)
    {
        ArgumentNullException.ThrowIfNull(file);
        stream = mode switch
        {
            "r" => new FileStream(file.FullName, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete),
            "rw" => new FileStream(file.FullName, FileMode.OpenOrCreate, FileAccess.ReadWrite,
                FileShare.Read),
            _ => throw new ArgumentException($"Unsupported random-access mode `{mode}`.", nameof(mode))
        };
    }
    internal long length()
    {
        ThrowIfDisposed();
        return stream.Length;
    }
    internal void readFully(sbyte[] destination)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(destination);
        var unsigned = new byte[destination.Length];
        var total = 0;
        while (total < unsigned.Length)
        {
            var read = stream.Read(unsigned, total, unsigned.Length - total);
            if (read == 0) throw new EndOfStreamException();
            total += read;
        }
        Buffer.BlockCopy(unsigned, 0, destination, 0, unsigned.Length);
    }
    internal void seek(long position)
    {
        ThrowIfDisposed();
        if (position < 0) throw new IOException("Negative seek offset");
        stream.Position = position;
    }
    internal void setLength(long length)
    {
        ThrowIfDisposed();
        stream.SetLength(length);
    }
    internal void write(sbyte[] source)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        var unsigned = new byte[source.Length];
        Buffer.BlockCopy(source, 0, unsigned, 0, source.Length);
        stream.Write(unsigned, 0, unsigned.Length);
    }
    internal void close() => Dispose();
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stream.Dispose();
    }
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
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

internal sealed class JavaMethodType : IEquatable<JavaMethodType>
{
    internal Type ReturnType { get; }
    internal IReadOnlyList<Type> ParameterTypes { get; }
    internal JavaMethodType(Type returnType, params Type[] parameterTypes)
    {
        ReturnType = returnType;
        ParameterTypes = parameterTypes;
    }
    internal static JavaMethodType methodType(Type returnType) => new(returnType);
    internal static JavaMethodType methodType(Type returnType, Type parameterType) =>
        new(returnType, parameterType);
    internal Type returnType() => ReturnType;
    public bool Equals(JavaMethodType? other) =>
        other is not null && ReturnType == other.ReturnType &&
        ParameterTypes.SequenceEqual(other.ParameterTypes);
    public override bool Equals(object? obj) => Equals(obj as JavaMethodType);
    public override int GetHashCode() =>
        ParameterTypes.Aggregate(ReturnType.GetHashCode(),
            (hash, parameter) => HashCode.Combine(hash, parameter));
}

internal sealed class JavaMethodHandle
{
    private readonly Func<object?[], object?> invoke;
    private readonly JavaMethodType methodType;
    internal JavaMethodHandle(Func<object?[], object?> invoke, JavaMethodType methodType)
    {
        this.invoke = invoke;
        this.methodType = methodType;
    }
    internal JavaMethodHandle asType(JavaMethodType _) => this;
    internal JavaMethodHandle bindTo(object? target) =>
        new(arguments => invoke(new[] { target }.Concat(arguments).ToArray()),
            new JavaMethodType(methodType.ReturnType,
                methodType.ParameterTypes.Skip(1).ToArray()));
    internal object? invokeExact(params object?[] arguments) => invoke(arguments);
    internal JavaMethodType type() => methodType;
}

internal sealed class JavaMethodHandlesLookup
{
    internal JavaMethodHandle findStatic(Type owner, string name, JavaMethodType methodType) =>
        FromMethod(owner.GetMethod(name, BindingFlags.Static | BindingFlags.Public |
            BindingFlags.NonPublic) ?? throw new MissingMethodException(owner.FullName, name),
            methodType);
    internal JavaMethodHandle findVirtual(Type owner, string name, JavaMethodType methodType) =>
        FromMethod(owner.GetMethod(name, BindingFlags.Instance | BindingFlags.Public |
            BindingFlags.NonPublic) ?? throw new MissingMethodException(owner.FullName, name),
            methodType);
    internal JavaMethodHandle unreflect(MethodInfo method) =>
        FromMethod(method, new JavaMethodType(method.ReturnType,
            (method.IsStatic ? Array.Empty<Type>() : new[] { method.DeclaringType! })
                .Concat(method.GetParameters().Select(parameter => parameter.ParameterType))
                .ToArray()));
    private static JavaMethodHandle FromMethod(MethodInfo method, JavaMethodType methodType) =>
        new(arguments =>
        {
            var target = method.IsStatic ? null : arguments[0];
            var parameters = method.IsStatic ? arguments : arguments.Skip(1).ToArray();
            return method.Invoke(target, parameters);
        }, methodType);
}

internal static class JavaMethodHandles
{
    internal static JavaMethodHandlesLookup lookup() => new();
    internal static JavaMethodHandle constant(Type type, object? value) =>
        new(_ => value, new JavaMethodType(type));
    internal static JavaMethodHandle dropArguments(
        JavaMethodHandle target, int _, params Type[] parameterTypes) =>
        new(arguments => target.invokeExact(arguments.Skip(parameterTypes.Length).ToArray()),
            new JavaMethodType(target.type().ReturnType,
                parameterTypes.Concat(target.type().ParameterTypes).ToArray()));
    internal static JavaMethodHandle filterReturnValue(
        JavaMethodHandle target, JavaMethodHandle filter) =>
        new(arguments => filter.invokeExact(target.invokeExact(arguments)),
            new JavaMethodType(filter.type().ReturnType, target.type().ParameterTypes.ToArray()));
    internal static JavaMethodHandle guardWithTest(
        JavaMethodHandle test, JavaMethodHandle target, JavaMethodHandle fallback) =>
        new(arguments => (bool)test.invokeExact(arguments)!
                ? target.invokeExact(arguments)
                : fallback.invokeExact(arguments),
            target.type());
}

internal sealed class JavaUnsafe
{
    internal static readonly JavaUnsafe theUnsafe = new();
    internal void invokeCleaner(JavaByteBuffer buffer) => buffer.Dispose();
}

internal sealed class JavaFileSystem
{
    internal ISet<string> supportedFileAttributeViews() =>
        OperatingSystem.IsWindows()
            ? new HashSet<string>(StringComparer.Ordinal)
            : new HashSet<string>(new[] { "posix" }, StringComparer.Ordinal);
}

internal static class JavaFileSystems
{
    internal static JavaFileSystem getDefault() => new();
}

internal sealed record JavaUserPrincipal(string Name);
internal enum JavaAclEntryPermission
{
    APPEND_DATA, DELETE, DELETE_CHILD, EXECUTE, READ_ACL, READ_ATTRIBUTES,
    READ_DATA, READ_NAMED_ATTRS, SYNCHRONIZE, WRITE_ACL, WRITE_ATTRIBUTES,
    WRITE_DATA, WRITE_NAMED_ATTRS
}
internal enum JavaAclEntryType { ALLOW }
internal sealed record JavaAclEntry(
    JavaAclEntryType Type,
    JavaUserPrincipal Principal,
    ISet<JavaAclEntryPermission> Permissions)
{
    internal static JavaAclEntryBuilder newBuilder() => new();
}
internal sealed class JavaAclEntryBuilder
{
    private JavaAclEntryType type;
    private JavaUserPrincipal principal = new(Environment.UserName);
    private ISet<JavaAclEntryPermission> permissions = new HashSet<JavaAclEntryPermission>();
    internal JavaAclEntryBuilder setType(JavaAclEntryType value) { type = value; return this; }
    internal JavaAclEntryBuilder setPrincipal(JavaUserPrincipal value) { principal = value; return this; }
    internal JavaAclEntryBuilder setPermissions(ISet<JavaAclEntryPermission> value)
    {
        permissions = value;
        return this;
    }
    internal JavaAclEntry build() => new(type, principal, permissions);
}
internal sealed class JavaAclFileAttributeView
{
    internal JavaUserPrincipal getOwner() => new(Environment.UserName);
    internal void setAcl(IList<JavaAclEntry> _) { }
}
internal sealed record JavaFileAttribute<T>(T Value);

internal sealed class JavaRuntime
{
    private static readonly JavaRuntime Instance = new();
    internal static JavaRuntime getRuntime() => Instance;
    internal void addShutdownHook(JavaThread thread) =>
        AppDomain.CurrentDomain.ProcessExit += (_, _) => thread.Start();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaInputStream : Stream
{
    public abstract int Read();

    public virtual int Read(sbyte[] buffer) => Read(buffer, 0, buffer.Length);

    public virtual int Read(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        if (offset < 0 || count < 0 || offset + count > buffer.Length)
            throw new ArgumentOutOfRangeException();
        if (count == 0) return 0;
        var first = Read();
        if (first < 0) return -1;
        buffer[offset] = unchecked((sbyte)first);
        var copied = 1;
        while (copied < count)
        {
            var next = Read();
            if (next < 0) break;
            buffer[offset + copied++] = unchecked((sbyte)next);
        }
        return copied;
    }

    public virtual int Available() => 0;
    public virtual long Skip(long count)
    {
        if (count <= 0) return 0;
        var skipped = 0L;
        while (skipped < count && Read() >= 0) skipped++;
        return skipped;
    }
    public virtual void Mark(int readLimit) => _ = readLimit;
    public virtual void Reset() =>
        throw new IOException("mark/reset is not supported by this input stream.");
    public virtual bool MarkSupported() => false;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var signed = new sbyte[count];
        var readCount = Read(signed, 0, count);
        if (readCount > 0) Buffer.BlockCopy(signed, 0, buffer, offset, readCount);
        return readCount;
    }

    public override int ReadByte() => Read();
    public override void Flush() { }
    public new virtual void Dispose() => base.Dispose();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterInputStream : JavaInputStream
{
    protected readonly Stream @in;

    protected JavaFilterInputStream(Stream input) =>
        @in = input ?? throw new ArgumentNullException(nameof(input));

    public override int Read() => @in.ReadByte();

    public override int Read(sbyte[] buffer, int offset, int count) =>
        JavaCompat.InputStreamRead(@in, buffer, offset, count);

    public override int Available() =>
        @in.CanSeek ? checked((int)Math.Min(int.MaxValue, @in.Length - @in.Position)) : 0;

    public override long Skip(long count)
    {
        if (count <= 0) return 0;
        if (@in.CanSeek)
        {
            var original = @in.Position;
            @in.Position = Math.Min(@in.Length, original + count);
            return @in.Position - original;
        }
        return base.Skip(count);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) @in.Dispose();
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaOutputStream : Stream
{
    public abstract void Write(int value);

    public virtual void Write(sbyte[] buffer) => Write(buffer, 0, buffer.Length);

    public virtual void Write(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        for (var index = 0; index < count; index++) Write(buffer[offset + index]);
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        var signed = new sbyte[count];
        Buffer.BlockCopy(buffer, offset, signed, 0, count);
        Write(signed, 0, count);
    }

    public override void WriteByte(byte value) => Write(value);
    public override void Flush() { }
    public new virtual void Dispose() => base.Dispose();
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}

internal sealed class JavaPipedInputStream : Stream
{
    private readonly JavaPipe pipe = new();

    internal JavaPipe Pipe => pipe;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count) =>
        pipe.Read(buffer, offset, count);
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing) pipe.CloseReader();
        base.Dispose(disposing);
    }
}

internal sealed class JavaPushbackInputStream : Stream
{
    private readonly Stream source;
    private readonly byte[] pushback;
    private int position;

    internal JavaPushbackInputStream(Stream source) =>
        (this.source, pushback, position) =
            (JavaCompat.RequireNonNull(source), new byte[1], 1);

    internal JavaPushbackInputStream(Stream source, int size)
    {
        if (size <= 0) throw new ArgumentOutOfRangeException(nameof(size));
        this.source = JavaCompat.RequireNonNull(source);
        pushback = new byte[size];
        position = size;
    }

    internal void Unread(int value)
    {
        if (position == 0) throw new IOException("Push back buffer is full");
        pushback[--position] = unchecked((byte)value);
    }

    internal void Unread(sbyte[] values, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || length < 0 || offset > values.Length - length)
            throw new IndexOutOfRangeException();
        if (length > position) throw new IOException("Push back buffer is full");
        position -= length;
        for (var index = 0; index < length; index++)
            pushback[position + index] = unchecked((byte)values[offset + index]);
    }

    public override int ReadByte()
    {
        return position < pushback.Length
            ? pushback[position++]
            : source.ReadByte();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (count == 0) return 0;
        var copied = 0;
        while (count > 0 && position < pushback.Length)
        {
            buffer[offset++] = pushback[position++];
            count--;
            copied++;
        }
        if (count == 0) return copied;
        return copied + source.Read(buffer, offset, count);
    }

    public override bool CanRead => source.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    protected override void Dispose(bool disposing)
    {
        if (disposing) source.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaSequenceInputStream : Stream
{
    private readonly Stream first;
    private readonly Stream second;
    private bool readingFirst = true;

    internal JavaSequenceInputStream(Stream first, Stream second)
    {
        this.first = first ?? throw new ArgumentNullException(nameof(first));
        this.second = second ?? throw new ArgumentNullException(nameof(second));
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (readingFirst)
        {
            var read = first.Read(buffer, offset, count);
            if (read != 0) return read;
            readingFirst = false;
        }
        return second.Read(buffer, offset, count);
    }

    public override int Read(Span<byte> buffer)
    {
        if (readingFirst)
        {
            var read = first.Read(buffer);
            if (read != 0) return read;
            readingFirst = false;
        }
        return second.Read(buffer);
    }

    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try
            {
                first.Dispose();
            }
            finally
            {
                second.Dispose();
            }
        }
        base.Dispose(disposing);
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

    protected override void Dispose(bool disposing)
    {
        if (disposing) compressed.Dispose();
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterOutputStream : JavaOutputStream
{
    protected readonly Stream @out;

    protected JavaFilterOutputStream(Stream output) => @out = output;
    public override bool CanWrite => @out.CanWrite;
    public override void Write(int value) => @out.WriteByte(unchecked((byte)value));
    public override void Write(sbyte[] buffer, int offset, int count) =>
        @out.Write(JavaCompat.ToUnsignedBytes(buffer), offset, count);
    public override void Flush() => @out.Flush();

    protected override void Dispose(bool disposing)
    {
        if (disposing) @out.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaPipedOutputStream : Stream
{
    private readonly object sync = new();
    private JavaPipe? pipe;
    private bool closed;

    internal void Connect(JavaPipedInputStream receiver)
    {
        ArgumentNullException.ThrowIfNull(receiver);
        lock (sync)
        {
            if (closed) throw new IOException("Pipe is closed.");
            if (pipe is not null) throw new IOException("Pipe is already connected.");
            receiver.Pipe.ConnectWriter();
            pipe = receiver.Pipe;
        }
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => !closed;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        JavaPipe connected;
        lock (sync)
        {
            if (closed) throw new IOException("Pipe is closed.");
            connected = pipe ?? throw new IOException("Pipe is not connected.");
        }
        connected.Write(buffer, offset, count);
    }

    public override void Flush() { }
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            JavaPipe? connected;
            lock (sync)
            {
                if (closed) return;
                closed = true;
                connected = pipe;
            }
            connected?.CloseWriter();
        }
        base.Dispose(disposing);
    }
}

internal sealed class JavaPipe
{
    private const int DefaultCapacity = 1024;
    private readonly BlockingCollection<byte> bytes = new(DefaultCapacity);
    private int connected;
    private int readerClosed;

    internal void ConnectWriter()
    {
        if (Interlocked.Exchange(ref connected, 1) != 0)
            throw new IOException("Pipe is already connected.");
    }

    internal int Read(byte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        if (buffer.Length - offset < count) throw new ArgumentException("Invalid buffer range.");
        if (count == 0) return 0;
        try
        {
            if (!bytes.TryTake(out var first, Timeout.Infinite)) return 0;
            buffer[offset] = first;
            var read = 1;
            while (read < count && bytes.TryTake(out var next)) buffer[offset + read++] = next;
            return read;
        }
        catch (ThreadInterruptedException error)
        {
            throw new IOException("Interrupted while reading from a pipe.", error);
        }
    }

    internal void Write(byte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        if (buffer.Length - offset < count) throw new ArgumentException("Invalid buffer range.");
        try
        {
            for (var index = 0; index < count; index++)
            {
                if (Volatile.Read(ref readerClosed) != 0)
                    throw new IOException("Pipe reader is closed.");
                bytes.Add(buffer[offset + index]);
            }
        }
        catch (ThreadInterruptedException error)
        {
            throw new IOException("Interrupted while writing to a pipe.", error);
        }
        catch (InvalidOperationException error)
        {
            throw new IOException("Pipe is closed.", error);
        }
    }

    internal void CloseReader()
    {
        Interlocked.Exchange(ref readerClosed, 1);
        bytes.CompleteAdding();
    }

    internal void CloseWriter() => bytes.CompleteAdding();
}

internal delegate JavaThread JavaThreadFactory(Action runnable);

internal sealed class JavaThread
{
    private readonly Thread thread;

    internal JavaThread(Action runnable) => thread = new Thread(() => runnable());
    internal JavaThread(Action runnable, string name) : this(runnable) => SetName(name);
    private JavaThread(Thread thread) => this.thread = thread;
    internal static JavaThread CurrentThread() => new(Thread.CurrentThread);
    internal static void Sleep(long milliseconds) => Thread.Sleep(checked((int)milliseconds));
    internal void SetDaemon(bool daemon) => thread.IsBackground = daemon;
    internal void SetName(string name) => thread.Name = name;
    internal void Start() => thread.Start();
    internal void Interrupt() => thread.Interrupt();
    internal bool Join(TimeSpan timeout) => thread.Join(timeout);
    internal long getId() => thread.ManagedThreadId;
}

internal sealed class JavaFixedThreadTaskScheduler : TaskScheduler
{
    private readonly BlockingCollection<Task> tasks = new();
    private readonly IReadOnlyList<JavaThread> workers;

    internal JavaFixedThreadTaskScheduler(int workerCount, JavaThreadFactory threadFactory)
    {
        if (workerCount <= 0) throw new ArgumentOutOfRangeException(nameof(workerCount));
        ArgumentNullException.ThrowIfNull(threadFactory);
        var created = new List<JavaThread>(workerCount);
        for (var index = 0; index < workerCount; index++)
        {
            var worker = threadFactory(Consume);
            if (worker is null)
                throw new InvalidOperationException("A Java thread factory returned null.");
            created.Add(worker);
        }
        workers = created;
        foreach (var worker in workers) worker.Start();
    }

    internal void Complete() => tasks.CompleteAdding();

    internal bool AwaitTermination(TimeSpan timeout)
    {
        if (timeout <= TimeSpan.Zero) return workers.All(worker => worker.Join(TimeSpan.Zero));
        var started = Stopwatch.StartNew();
        foreach (var worker in workers)
        {
            var remaining = timeout - started.Elapsed;
            if (remaining <= TimeSpan.Zero || !worker.Join(remaining)) return false;
        }
        return true;
    }

    protected override IEnumerable<Task>? GetScheduledTasks() => tasks.ToArray();
    protected override void QueueTask(Task task) => tasks.Add(task);
    protected override bool TryExecuteTaskInline(Task task, bool taskWasPreviouslyQueued) => false;

    private void Consume()
    {
        foreach (var task in tasks.GetConsumingEnumerable()) TryExecuteTask(task);
    }
}

internal sealed class JavaAtomicBoolean
{
    private int value;

    internal JavaAtomicBoolean(bool value = false) => this.value = value ? 1 : 0;
    internal bool Get() => Volatile.Read(ref value) != 0;
    internal void Set(bool replacement) => Volatile.Write(ref value, replacement ? 1 : 0);
    internal bool CompareAndSet(bool expected, bool replacement) =>
        Interlocked.CompareExchange(ref value, replacement ? 1 : 0, expected ? 1 : 0) ==
        (expected ? 1 : 0);
}

internal sealed class JavaAtomicInteger
{
    private int value;

    internal JavaAtomicInteger(int value = 0) => this.value = value;
    internal int IncrementAndGet() => Interlocked.Increment(ref value);
}

internal sealed class JavaAtomicReference<T> where T : class
{
    private T? value;

    internal JavaAtomicReference(T? value = null) => this.value = value;
    internal T Get() => Volatile.Read(ref value)!;
    internal void Set(T? replacement) => Volatile.Write(ref value, replacement);
    internal T GetAndSet(T? replacement) => Interlocked.Exchange(ref value, replacement)!;
}

internal sealed class JavaThreadLocal<T> : IDisposable
{
    private readonly ThreadLocal<T> value;

    private JavaThreadLocal(Func<T> supplier) => value = new ThreadLocal<T>(supplier);

    internal static JavaThreadLocal<T> WithInitial(Func<T> supplier)
    {
        ArgumentNullException.ThrowIfNull(supplier);
        return new JavaThreadLocal<T>(supplier);
    }

    internal T Get() => value.Value!;
    internal void Set(T replacement) => value.Value = replacement;
    public void Dispose() => value.Dispose();
}

public sealed class JavaDateTimeFormatter
{
    private enum FormatterKind
    {
        Rfc1123,
        IsoLocalDateTime,
        IsoLocalDateTimeOffset
    }

    internal static readonly JavaDateTimeFormatter Rfc1123 = new(FormatterKind.Rfc1123);
    internal static readonly JavaDateTimeFormatter IsoLocalDateTime =
        new(FormatterKind.IsoLocalDateTime);
    private readonly FormatterKind kind;

    private JavaDateTimeFormatter(FormatterKind kind) => this.kind = kind;

    internal string Format(DateTimeOffset value) =>
        kind == FormatterKind.Rfc1123
            ? value.UtcDateTime.ToString("ddd, d MMM yyyy HH:mm:ss 'GMT'", CultureInfo.InvariantCulture)
            : value.ToString("yyyy-MM-dd'T'HH:mm:ss.FFFFFFFK", CultureInfo.InvariantCulture);

    internal static JavaDateTimeFormatter IsoLocalDateTimeOffset() =>
        new(FormatterKind.IsoLocalDateTimeOffset);
}

public sealed class JavaParsePosition
{
    public JavaParsePosition(int index)
    {
        if (index < 0) throw new ArgumentOutOfRangeException(nameof(index));
        Index = index;
        ErrorIndex = -1;
    }

    public int Index { get; private set; }
    public int ErrorIndex { get; private set; }

    public int GetIndex() => Index;
    public void SetIndex(int index)
    {
        if (index < 0) throw new ArgumentOutOfRangeException(nameof(index));
        Index = index;
    }

    public int GetErrorIndex() => ErrorIndex;
    public void SetErrorIndex(int index) => ErrorIndex = index;
}

public sealed class JavaSimpleDateFormat
{
    private readonly string pattern;
    private readonly CultureInfo culture;
    private TimeZoneInfo timeZone = TimeZoneInfo.Local;
    private DateTimeOffset calendar = DateTimeOffset.Now;

    public JavaSimpleDateFormat(string pattern, CultureInfo culture)
    {
        ArgumentException.ThrowIfNullOrEmpty(pattern);
        this.pattern = ConvertPattern(pattern);
        this.culture = culture ?? throw new ArgumentNullException(nameof(culture));
    }

    public void SetTimeZone(TimeZoneInfo value)
    {
        timeZone = value ?? throw new ArgumentNullException(nameof(value));
    }

    public void SetCalendar(DateTimeOffset value)
    {
        calendar = value;
    }

    public string Format(DateTimeOffset? value)
    {
        var date = TimeZoneInfo.ConvertTime(
            value ?? DateTimeOffset.Now,
            timeZone);
        if (string.Equals(pattern, "zzz", StringComparison.Ordinal))
            return date.ToString("zzz", culture).Replace(":", "", StringComparison.Ordinal);
        return date.ToString(pattern, culture);
    }

    public DateTimeOffset? Parse(string text, JavaParsePosition position)
    {
        ArgumentNullException.ThrowIfNull(text);
        ArgumentNullException.ThrowIfNull(position);
        var start = position.Index;
        if (start > text.Length)
        {
            position.SetErrorIndex(start);
            return null;
        }
        for (var end = text.Length; end > start; end--)
        {
            var candidate = text[start..end];
            if (!DateTime.TryParseExact(
                    candidate,
                    pattern,
                    culture,
                    DateTimeStyles.AllowWhiteSpaces,
                    out var parsed))
                continue;
            var offset = timeZone.GetUtcOffset(parsed);
            calendar = new DateTimeOffset(
                DateTime.SpecifyKind(parsed, DateTimeKind.Unspecified),
                offset);
            position.SetIndex(end);
            position.SetErrorIndex(-1);
            return calendar;
        }
        position.SetErrorIndex(start);
        return null;
    }

    private static string ConvertPattern(string javaPattern)
    {
        var result = new StringBuilder(javaPattern.Length);
        var quoted = false;
        for (var index = 0; index < javaPattern.Length;)
        {
            var current = javaPattern[index];
            if (current == '\'')
            {
                quoted = !quoted;
                result.Append(current);
                index++;
                continue;
            }
            if (quoted || !char.IsLetter(current))
            {
                result.Append(current);
                index++;
                continue;
            }
            var end = index + 1;
            while (end < javaPattern.Length && javaPattern[end] == current) end++;
            var count = end - index;
            result.Append(current switch
            {
                'E' => count >= 4 ? "dddd" : "ddd",
                'a' => "tt",
                'z' => "zzz",
                _ => new string(current, count)
            });
            index = end;
        }
        return result.ToString();
    }
}

internal sealed class JavaDateTimeFormatterBuilder
{
    internal JavaDateTimeFormatterBuilder ParseCaseInsensitive() => this;
    internal JavaDateTimeFormatterBuilder Append(JavaDateTimeFormatter formatter) => this;
    internal JavaDateTimeFormatterBuilder ParseLenient() => this;
    internal JavaDateTimeFormatterBuilder AppendOffset(string pattern, string zeroOffsetText) => this;
    internal JavaDateTimeFormatterBuilder ParseStrict() => this;
    internal JavaDateTimeFormatter ToFormatter() => JavaDateTimeFormatter.IsoLocalDateTimeOffset();
}

public sealed class JavaKeyStore
{
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2Collection certificates = new();

    private JavaKeyStore() { }

    public static string GetDefaultType() => "PKCS12";

    public static JavaKeyStore GetInstance(string type)
    {
        ArgumentNullException.ThrowIfNull(type);
        if (!string.Equals(type, "PKCS12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PKCS#12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PFX", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported KeyStore type: {type}");
        return new JavaKeyStore();
    }

    public void Load(Stream input, char[]? password)
    {
        ArgumentNullException.ThrowIfNull(input);
        using var contents = new MemoryStream();
        input.CopyTo(contents);
        certificates.Clear();
        var flags =
            System.Security.Cryptography.X509Certificates.X509KeyStorageFlags.EphemeralKeySet |
            System.Security.Cryptography.X509Certificates.X509KeyStorageFlags.Exportable;
#if NET9_0_OR_GREATER
        certificates.AddRange(
            System.Security.Cryptography.X509Certificates.X509CertificateLoader
                .LoadPkcs12Collection(
                    contents.ToArray(),
                    password is null ? null : new string(password),
                    flags,
                    System.Security.Cryptography.X509Certificates.Pkcs12LoaderLimits.Defaults));
#else
        certificates.Import(
            contents.ToArray(),
            password is null ? null : new string(password),
            flags);
#endif
    }

    public int Size() => certificates.Count;

    public JavaIterator<string> Aliases() =>
        JavaCompat.Iterator(
            Enumerable.Range(0, certificates.Count)
                .Select(index => AliasFor(index, certificates[index])));

    public bool ContainsAlias(string? alias) =>
        TryFind(alias, out _);

    public System.Security.Cryptography.X509Certificates.X509Certificate2? GetCertificate(
        string? alias) =>
        TryFind(alias, out var certificate) ? certificate : null;

    public object? GetKey(string? alias, char[]? _)
    {
        if (!TryFind(alias, out var certificate) || certificate is null)
            return null;
        return (object?)certificate.GetRSAPrivateKey() ??
            (object?)certificate.GetECDsaPrivateKey() ??
            (object?)certificate.GetDSAPrivateKey();
    }

    internal System.Security.Cryptography.X509Certificates.X509Certificate2Collection Certificates =>
        certificates;

    private bool TryFind(
        string? alias,
        out System.Security.Cryptography.X509Certificates.X509Certificate2? certificate)
    {
        for (var index = 0; index < certificates.Count; index++)
        {
            if (string.Equals(
                    AliasFor(index, certificates[index]),
                    alias,
                    StringComparison.Ordinal))
            {
                certificate = certificates[index];
                return true;
            }
        }
        certificate = null;
        return false;
    }

    private static string AliasFor(
        int index,
        System.Security.Cryptography.X509Certificates.X509Certificate2 certificate) =>
        string.IsNullOrWhiteSpace(certificate.FriendlyName)
            ? index.ToString(CultureInfo.InvariantCulture)
            : certificate.FriendlyName;
}

internal sealed class JavaKeyManager
{
    internal JavaKeyManager(
        System.Security.Cryptography.X509Certificates.X509Certificate2 serverCertificate) =>
        ServerCertificate = serverCertificate;

    internal System.Security.Cryptography.X509Certificates.X509Certificate2 ServerCertificate { get; }
}

internal sealed class JavaKeyManagerFactory
{
    private JavaKeyManager? manager;

    private JavaKeyManagerFactory() { }

    internal static string GetDefaultAlgorithm() => "SunX509";

    internal static JavaKeyManagerFactory GetInstance(string algorithm)
    {
        ArgumentNullException.ThrowIfNull(algorithm);
        if (!string.Equals(algorithm, "SunX509", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "NewSunX509", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported KeyManagerFactory algorithm: {algorithm}");
        return new JavaKeyManagerFactory();
    }

    internal void Init(JavaKeyStore keyStore, char[]? password)
    {
        ArgumentNullException.ThrowIfNull(keyStore);
        var certificate = keyStore.Certificates.Cast<
            System.Security.Cryptography.X509Certificates.X509Certificate2>()
            .FirstOrDefault(candidate => candidate.HasPrivateKey);
        manager = certificate is null
            ? throw new System.Security.Cryptography.CryptographicException(
                "The KeyStore contains no private key certificate.")
            : new JavaKeyManager(certificate);
    }

    internal object[] GetKeyManagers() => manager is null
        ? throw new InvalidOperationException("KeyManagerFactory is not initialized.")
        : new object[] { manager };
}

internal sealed class JavaTrustManager
{
    internal JavaTrustManager(
        System.Security.Cryptography.X509Certificates.X509Certificate2Collection certificates) =>
        Certificates = certificates;

    internal System.Security.Cryptography.X509Certificates.X509Certificate2Collection Certificates { get; }
}

internal interface JavaX509TrustManager
{
    System.Security.Cryptography.X509Certificates.X509Certificate2[] GetAcceptedIssuers();
    void CheckServerTrusted(
        System.Security.Cryptography.X509Certificates.X509Certificate2[] chain,
        string authType);
    void CheckClientTrusted(
        System.Security.Cryptography.X509Certificates.X509Certificate2[] chain,
        string authType);
}

internal sealed class JavaTrustManagerFactory
{
    private JavaTrustManager? manager;

    private JavaTrustManagerFactory() { }

    internal static string GetDefaultAlgorithm() => "PKIX";

    internal static JavaTrustManagerFactory GetInstance(string algorithm)
    {
        ArgumentNullException.ThrowIfNull(algorithm);
        if (!string.Equals(algorithm, "PKIX", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "SunX509", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported TrustManagerFactory algorithm: {algorithm}");
        return new JavaTrustManagerFactory();
    }

    internal void Init(JavaKeyStore keyStore)
    {
        ArgumentNullException.ThrowIfNull(keyStore);
        var roots = new System.Security.Cryptography.X509Certificates.X509Certificate2Collection();
        roots.AddRange(keyStore.Certificates);
        manager = new JavaTrustManager(roots);
    }

    internal object[] GetTrustManagers() => manager is null
        ? throw new InvalidOperationException("TrustManagerFactory is not initialized.")
        : new object[] { manager };
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSslContext
{
    private readonly string protocol;
    private JavaSocketFactory? socketFactory;
    private JavaSslServerSocketFactory? serverSocketFactory;

    private JavaSslContext(string protocol) => this.protocol = protocol;

    public static JavaSslContext GetInstance(string protocol)
    {
        ArgumentNullException.ThrowIfNull(protocol);
        if (!string.Equals(protocol, "TLS", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(protocol, "SSL", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported SSLContext protocol: {protocol}");
        return new JavaSslContext(protocol);
    }

    internal void Init(object[]? keyManagers, object[]? trustManagers, object? secureRandom)
    {
        var serverCertificate = keyManagers?.OfType<JavaKeyManager>()
            .Select(manager => manager.ServerCertificate)
            .FirstOrDefault();
        var trustedRoots = trustManagers?.OfType<JavaTrustManager>()
            .Select(manager => manager.Certificates)
            .FirstOrDefault();
        var customTrustManager = trustManagers?.OfType<JavaX509TrustManager>().FirstOrDefault();
        socketFactory = new JavaSocketFactory(tls: true, trustedRoots, customTrustManager);
        serverSocketFactory = new JavaSslServerSocketFactory(serverCertificate);
    }

    internal JavaSocketFactory GetSocketFactory() => socketFactory ??
        throw new InvalidOperationException($"SSLContext {protocol} is not initialized.");

    internal JavaSslServerSocketFactory GetServerSocketFactory() => serverSocketFactory ??
        throw new InvalidOperationException($"SSLContext {protocol} is not initialized.");
}

internal sealed class JavaSocketFactory
{
    internal static readonly JavaSocketFactory Plain = new(false);
    internal static readonly JavaSocketFactory Default = new(true);
    private readonly bool tls;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2Collection? trustedRoots;
    private readonly JavaX509TrustManager? customTrustManager;

    internal JavaSocketFactory(
        bool tls,
        System.Security.Cryptography.X509Certificates.X509Certificate2Collection? trustedRoots = null,
        JavaX509TrustManager? customTrustManager = null)
    {
        this.tls = tls;
        this.trustedRoots = trustedRoots;
        this.customTrustManager = customTrustManager;
    }

    internal System.Net.Sockets.Socket CreateSocket(string host, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(host, port);
            Stream stream = new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
            if (tls)
            {
                var secure = new System.Net.Security.SslStream(
                    stream,
                    leaveInnerStreamOpen: false,
                    trustedRoots is null && customTrustManager is null
                        ? null
                        : ValidateRemoteCertificate);
                secure.AuthenticateAsClient(host);
                stream = secure;
            }
            JavaCompat.RegisterSocketStream(socket, stream);
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal System.Net.Sockets.Socket CreateSocket()
    {
        var socket = new System.Net.Sockets.Socket(
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        JavaCompat.RegisterPendingSocketFactory(socket, this);
        return socket;
    }

    internal Stream OpenStream(System.Net.Sockets.Socket socket)
    {
        var stream = new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
        if (!tls) return stream;
        var secure = new System.Net.Security.SslStream(
            stream,
            leaveInnerStreamOpen: false,
            trustedRoots is null && customTrustManager is null
                ? null
                : ValidateRemoteCertificate);
        var host = (socket.RemoteEndPoint as System.Net.IPEndPoint)?.Address.ToString() ??
            throw new InvalidOperationException("An unconnected SSL socket has no remote host.");
        secure.AuthenticateAsClient(host);
        return secure;
    }

    private bool ValidateRemoteCertificate(
        object sender,
        System.Security.Cryptography.X509Certificates.X509Certificate? certificate,
        System.Security.Cryptography.X509Certificates.X509Chain? chain,
        System.Net.Security.SslPolicyErrors errors)
    {
        if (certificate is null)
            return false;
        if (customTrustManager is not null)
        {
            var certificates = chain?.ChainElements
                .Cast<System.Security.Cryptography.X509Certificates.X509ChainElement>()
                .Select(element => element.Certificate)
                .ToArray() ?? new[] {
                    new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate)
                };
            customTrustManager.CheckServerTrusted(certificates, certificate.GetKeyAlgorithm());
            return true;
        }
        if (trustedRoots is null ||
            (errors & System.Net.Security.SslPolicyErrors.RemoteCertificateNameMismatch) != 0)
            return false;
        using var candidate =
            new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate);
        using var customChain = new System.Security.Cryptography.X509Certificates.X509Chain();
        customChain.ChainPolicy.TrustMode =
            System.Security.Cryptography.X509Certificates.X509ChainTrustMode.CustomRootTrust;
        customChain.ChainPolicy.CustomTrustStore.AddRange(trustedRoots);
        customChain.ChainPolicy.RevocationMode =
            System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck;
        return customChain.Build(candidate);
    }

    internal System.Net.Sockets.Socket CreateSocket(System.Net.IPAddress address, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            address.AddressFamily,
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(address, port);
            JavaCompat.RegisterSocketStream(
                socket, new System.Net.Sockets.NetworkStream(socket, ownsSocket: false));
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}

internal sealed class JavaSslServerSocketFactory
{
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate;

    internal JavaSslServerSocketFactory(
        System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate) =>
        this.serverCertificate = serverCertificate;

    internal JavaServerSocket CreateServerSocket(int port) =>
        new(port, tls: true, serverCertificate: serverCertificate);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaServerSocket : IDisposable
{
    private readonly System.Net.Sockets.TcpListener listener;
    private readonly bool tls;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate;
    private int closed;

    internal JavaServerSocket(int port) : this(port, tls: false, serverCertificate: null) { }

    internal JavaServerSocket(
        int port,
        bool tls,
        System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate)
    {
        this.tls = tls;
        this.serverCertificate = serverCertificate;
        listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Any, port);
        listener.Start();
    }

    internal System.Net.Sockets.Socket Accept()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref closed) != 0, this);
        var socket = listener.AcceptSocket();
        if (!tls) return socket;
        try
        {
            if (serverCertificate is null)
                throw new InvalidOperationException(
                    "The SSLContext has no server certificate configured.");
            var secure = new System.Net.Security.SslStream(
                new System.Net.Sockets.NetworkStream(socket, ownsSocket: false),
                leaveInnerStreamOpen: false);
            secure.AuthenticateAsServer(serverCertificate);
            JavaCompat.RegisterSocketStream(socket, secure);
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal bool IsClosed() => Volatile.Read(ref closed) != 0;

    internal void Close()
    {
        if (Interlocked.Exchange(ref closed, 1) == 0) listener.Stop();
    }

    public void Dispose() => Close();
}

public sealed class JavaRandom
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

public sealed class JavaCrc32
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

internal sealed class JavaProcessBuilder
{
    private readonly ProcessStartInfo startInfo = new()
    {
        UseShellExecute = false,
        RedirectStandardInput = true,
        RedirectStandardOutput = true
    };

    internal JavaProcessBuilder(IEnumerable<string> command)
    {
        using var parts = command.GetEnumerator();
        if (!parts.MoveNext()) throw new ArgumentException("Process command must not be empty.", nameof(command));
        startInfo.FileName = parts.Current;
        while (parts.MoveNext()) startInfo.ArgumentList.Add(parts.Current);
    }

    internal JavaProcessBuilder Directory(string directory)
    {
        startInfo.WorkingDirectory = directory;
        return this;
    }

    internal JavaProcessBuilder RedirectError(JavaProcessRedirect redirect)
    {
        startInfo.RedirectStandardError = redirect != JavaProcessRedirect.INHERIT;
        return this;
    }

    internal JavaProcess Start()
    {
        try
        {
            return new JavaProcess(Process.Start(startInfo) ??
                throw new IOException($"Could not start process `{startInfo.FileName}`."));
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            throw new IOException($"Could not start process `{startInfo.FileName}`.", error);
        }
    }
}

internal sealed class JavaProcess : IDisposable
{
    private readonly Process process;
    private readonly Stream inputStream;
    private readonly Stream outputStream;
    private readonly CancellationTokenRegistration cancellationRegistration;
    private int disposeStarted;

    internal JavaProcess(Process process)
    {
        this.process = process;
        inputStream = process.StandardOutput.BaseStream;
        outputStream = process.StandardInput.BaseStream;
        var cancellation = JavaCancellation.CurrentToken;
        if (cancellation.CanBeCanceled)
            cancellationRegistration = cancellation.Register(
                static state => ((JavaProcess)state!).CancelForEvaluation(), this);
    }

    internal bool IsAlive()
    {
        try { return Volatile.Read(ref disposeStarted) == 0 && !process.HasExited; }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException)
        {
            return false;
        }
    }

    internal Stream GetInputStream()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposeStarted) != 0, this);
        return inputStream;
    }

    internal Stream GetOutputStream()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposeStarted) != 0, this);
        return outputStream;
    }

    internal bool WaitFor(long timeout, JavaTimeUnit unit)
    {
        try
        {
            return process.WaitForExit(checked((int)Math.Min(timeout, int.MaxValue)));
        }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException)
        {
            return true;
        }
    }

    internal JavaProcess DestroyForcibly()
    {
        try
        {
            if (IsAlive()) process.Kill(entireProcessTree: true);
        }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException or ThreadInterruptedException) { }
        return this;
    }

    private void CancelForEvaluation()
    {
        // CancellationToken callbacks run synchronously on the thread closing
        // the evaluation context. Never let process teardown replace the
        // product's stable timeout/cancellation diagnostic.
        try { Terminate(); }
        catch (Exception error) when (error is not StackOverflowException and not OutOfMemoryException) { }
    }

    // Killing the owned process tree and closing both redirected pipes are
    // separate operations on .NET. Do both so a reader blocked in a pipe read
    // is released even when process-exit notification races disposal.
    internal JavaProcess Terminate()
    {
        DestroyForcibly();
        ClosePipes();
        return this;
    }

    internal void ClosePipes()
    {
        DisposePipe(outputStream);
        DisposePipe(inputStream);
    }

    private static void DisposePipe(Stream stream)
    {
        while (true)
        {
            try
            {
                stream.Dispose();
                return;
            }
            // Thread.Interrupt may race the SafePipeHandle spin wait used by
            // Stream.Dispose. The exception clears the interrupt; retry so the
            // redirected pipe is still deterministically released.
            catch (ThreadInterruptedException) { }
            catch (Exception error) when (error is IOException or ObjectDisposedException) { return; }
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposeStarted, 1) != 0) return;
        try
        {
            try
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
            }
            catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException or ThreadInterruptedException) { }
            ClosePipes();
        }
        finally
        {
            cancellationRegistration.Dispose();
            process.Dispose();
        }
    }
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

public class JavaMapEntry<K, V>
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
    T Next();
    void Remove() => throw new NotSupportedException(
        "This Java iterator does not expose mutable removal semantics.");
}

public interface JavaIterableContract<out T> : IEnumerable<T>
{
    JavaIterator<T> Iterator();

    IEnumerator<T> IEnumerable<T>.GetEnumerator()
    {
        var iterator = Iterator();
        while (iterator.HasNext())
            yield return iterator.Next();
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

public interface JavaListContract<T> : IList<T>
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
            yield return iterator.Next();
    }

    IEnumerator IEnumerable.GetEnumerator() =>
        ((IEnumerable<T>)this).GetEnumerator();
}

public interface JavaMapContract<K, V> : IDictionary<K, V>
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

internal static class JavaCompat
{
    private static readonly bool AssertionsEnabled =
        string.Equals(Environment.GetEnvironmentVariable("DRIPSHARP_JAVA_ASSERTIONS"),
            "true", StringComparison.OrdinalIgnoreCase);
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Net.Sockets.Socket, Stream> SocketStreams = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Net.Sockets.Socket, JavaSocketFactory> PendingSocketFactories = new();
    private static readonly System.Net.Http.HttpClient UrlClient = new();
    private sealed class ReadOnlyAdapterCache
    {
        internal Dictionary<Type, object> Values { get; } = new();
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<object, ReadOnlyAdapterCache>
        ReadOnlyAdapters = new();

    internal static void Assert(Func<bool> condition, Func<object?>? message = null)
    {
        if (AssertionsEnabled && !condition())
            throw new JavaAssertionError(message?.Invoke()?.ToString());
    }

    // Java compound assignment includes the narrowing conversion back to the
    // left-hand type. A ref helper also preserves Java's single evaluation of
    // array indexes and other assignable expressions.
    internal static sbyte AddAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target + value));
    internal static sbyte SubtractAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target - value));
    internal static sbyte MultiplyAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target * value));
    internal static sbyte DivideAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target / value));
    internal static sbyte RemainderAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target % value));
    internal static sbyte AndAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target & value));
    internal static sbyte OrAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target | value));
    internal static sbyte XorAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target ^ value));
    internal static sbyte ShiftLeftAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target << (value & 0x1f)));
    internal static sbyte ShiftRightAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target >> (value & 0x1f)));
    internal static sbyte UnsignedShiftRightAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)((uint)target >> (value & 0x1f)));

    internal static JavaIterator<T> Iterator<T>(IEnumerable<T> values) =>
        new JavaListIterator<T>(values);

    internal static JavaListIterator<T> ListIterator<T>(
        IList<T> values,
        int index = 0) =>
        new(values, index);

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

    private sealed class JavaRegex(
        string originalPattern,
        string translatedPattern,
        RegexOptions options,
        int flags,
        string[] groupNames,
        IReadOnlyDictionary<string, string> namedGroups)
        : Regex(translatedPattern, options)
    {
        internal int Flags { get; } = flags;
        internal string[] GroupNames { get; } = groupNames;
        internal IReadOnlyDictionary<string, string> NamedGroups { get; } = namedGroups;
        public override string ToString() => originalPattern;
    }

    private sealed class JavaUriText(string value)
    {
        internal string Value { get; } = value;
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, object>
        SingleSlashFileUris = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, JavaUriText>
        OriginalUriTexts = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Regex, JavaUriText>
        OriginalRegexPatterns = new();
    internal static readonly TextWriter @out = Console.Out;
    internal static readonly TextWriter err = Console.Error;
    private static readonly Dictionary<string, string> SystemProperties = new(StringComparer.Ordinal)
    {
        ["os.name"] = OperatingSystem.IsMacOS() ? "Mac OS X"
            : OperatingSystem.IsWindows() ? "Windows"
            : OperatingSystem.IsLinux() ? "Linux"
            : Environment.OSVersion.Platform.ToString(),
        ["os.version"] = Environment.OSVersion.VersionString,
        ["os.arch"] = RuntimeInformation.OSArchitecture switch
        {
            Architecture.X64 => "amd64",
            Architecture.Arm64 => "aarch64",
            Architecture.X86 => "x86",
            Architecture.Arm => "arm",
            var architecture => architecture.ToString().ToLowerInvariant()
        },
        ["java.version"] = Environment.Version.ToString(),
        ["user.home"] = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ["user.dir"] = Environment.CurrentDirectory,
        ["java.io.tmpdir"] = Path.GetTempPath(),
        ["file.separator"] = Path.DirectorySeparatorChar.ToString(),
        ["path.separator"] = Path.PathSeparator.ToString(),
        ["line.separator"] = Environment.NewLine
    };
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<IEnumerator, IteratorState>
        IteratorStates = new();
    private sealed class StreamMark
    {
        internal long Position;
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Stream, StreamMark>
        StreamMarks = new();

    private sealed class IteratorState
    {
        internal bool Prepared;
        internal bool Exhausted;
    }

    internal static T RequireNonNull<T>(T? value, string? message = null) =>
        value is null ? throw new NullReferenceException(message) : value;
    internal static bool nonNull(object? value) => value is not null;
    internal static T doPrivileged<T>(Func<T> action) => action();
    internal static Type ClassForName(string name) => name switch
    {
        "sun.misc.Unsafe" => typeof(JavaUnsafe),
        "java.nio.DirectByteBuffer" => typeof(JavaByteBuffer),
        _ => Type.GetType(name, throwOnError: true)!
    };
    internal static FieldInfo GetDeclaredField(Type type, string name) =>
        type.GetField(name, BindingFlags.Instance | BindingFlags.Static |
            BindingFlags.Public | BindingFlags.NonPublic) ??
        throw new MissingFieldException(type.FullName, name);
    internal static MethodInfo GetMethod(Type type, string name, params Type[] parameterTypes) =>
        type.GetMethod(name, BindingFlags.Instance | BindingFlags.Static |
            BindingFlags.Public | BindingFlags.NonPublic, parameterTypes) ??
        throw new MissingMethodException(type.FullName, name);
    internal static void SetAccessible(MemberInfo _, bool __) { }
    internal static T RequireNonNullElseGet<T>(T? value, Func<T> supplier) =>
        value is null ? RequireNonNull(supplier()) : value;
    internal static string? Getenv(string name) => Environment.GetEnvironmentVariable(name);
    internal static string? ExceptionMessage(Exception? exception)
    {
        if (exception is null) return null;
        var method = exception.GetType().GetMethod("GetMessage", Type.EmptyTypes);
        return method is null ? exception.Message : method.Invoke(exception, null) as string;
    }

    internal static JavaStream<string> FindFiles(
        string basePath,
        int maxDepth,
        JavaBiPredicate<string, FileSystemInfo> predicate,
        params object[] ignoredOptions)
    {
        if (!Directory.Exists(basePath)) return new JavaStream<string>(Enumerable.Empty<string>());
        var root = Path.GetFullPath(basePath);
        return new JavaStream<string>(Directory.EnumerateFileSystemEntries(root, "*", SearchOption.AllDirectories)
            .Where(path => maxDepth == int.MaxValue ||
                Path.GetRelativePath(root, path).Count(character =>
                    character == Path.DirectorySeparatorChar || character == Path.AltDirectorySeparatorChar) < maxDepth)
            .Where(path => predicate(path, Directory.Exists(path)
                ? new DirectoryInfo(path)
                : new FileInfo(path))));
    }

    internal static bool IsRegularFile(FileSystemInfo attributes) => attributes is FileInfo;
    internal static bool IsRegularFile(string path) => File.Exists(path);

    internal static string Concat(object? left, object? right) =>
        JavaString(left) + JavaString(right);

    private static string JavaString(object? value) => StringValueOf(value);

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
    internal static int CharacterType(int codePoint) => Rune.GetUnicodeCategory(new Rune(codePoint)) switch
    {
        UnicodeCategory.UppercaseLetter => 1,
        UnicodeCategory.LowercaseLetter => 2,
        UnicodeCategory.TitlecaseLetter => 3,
        UnicodeCategory.ModifierLetter => 4,
        UnicodeCategory.OtherLetter => 5,
        UnicodeCategory.NonSpacingMark => 6,
        UnicodeCategory.EnclosingMark => 7,
        UnicodeCategory.SpacingCombiningMark => 8,
        UnicodeCategory.DecimalDigitNumber => 9,
        UnicodeCategory.LetterNumber => 10,
        UnicodeCategory.OtherNumber => 11,
        UnicodeCategory.SpaceSeparator => 12,
        UnicodeCategory.LineSeparator => 13,
        UnicodeCategory.ParagraphSeparator => 14,
        UnicodeCategory.Control => 15,
        UnicodeCategory.Format => 16,
        UnicodeCategory.PrivateUse => 18,
        UnicodeCategory.Surrogate => 19,
        UnicodeCategory.DashPunctuation => 20,
        UnicodeCategory.OpenPunctuation => 21,
        UnicodeCategory.ClosePunctuation => 22,
        UnicodeCategory.ConnectorPunctuation => 23,
        UnicodeCategory.OtherPunctuation => 24,
        UnicodeCategory.MathSymbol => 25,
        UnicodeCategory.CurrencySymbol => 26,
        UnicodeCategory.ModifierSymbol => 27,
        UnicodeCategory.OtherSymbol => 28,
        UnicodeCategory.InitialQuotePunctuation => 29,
        UnicodeCategory.FinalQuotePunctuation => 30,
        _ => 0
    };
    internal static int CharacterDigit(char value, int radix)
    {
        var digit = value is >= '0' and <= '9' ? value - '0'
            : value is >= 'a' and <= 'z' ? value - 'a' + 10
            : value is >= 'A' and <= 'Z' ? value - 'A' + 10
            : (int)char.GetNumericValue(value);
        return digit >= 0 && digit < radix ? digit : -1;
    }
    internal static bool IsWhitespace(char value) => char.IsWhiteSpace(value);
    internal static int ToUpperCase(int codePoint) => Rune.ToUpperInvariant(new Rune(codePoint)).Value;
    internal static StringBuilder AppendCodePoint(StringBuilder builder, int codePoint) =>
        builder.Append(CodePointToString(codePoint));

    internal static int CodePointCount(string value, int beginIndex, int endIndex)
    {
        var count = 0;
        for (var index = beginIndex; index < endIndex; count++)
            index += char.IsSurrogatePair(value, index) ? 2 : 1;
        return count;
    }

    internal static bool EqualsIgnoreCase(string value, string? other) =>
        string.Equals(value, other, StringComparison.OrdinalIgnoreCase);
    internal static bool StringStartsWith(string value, string prefix) =>
        value.StartsWith(prefix, StringComparison.Ordinal);
    internal static bool StringStartsWith(string value, string prefix, int offset) =>
        offset >= 0 &&
        offset <= value.Length &&
        value.AsSpan(offset).StartsWith(prefix.AsSpan(), StringComparison.Ordinal);
    internal static bool StringEndsWith(string value, string suffix) =>
        value.EndsWith(suffix, StringComparison.Ordinal);
    internal static string StringSubstring(string value, int beginIndex, int endIndex) =>
        value.Substring(beginIndex, endIndex - beginIndex);
    internal static int StringIndexOf(string value, int character) =>
        value.IndexOf((char)character);
    internal static int StringIndexOf(string value, int character, int fromIndex) =>
        value.IndexOf((char)character, fromIndex);
    internal static int StringLastIndexOf(string value, int character) =>
        value.LastIndexOf((char)character);
    internal static bool StringContains(string value, string part) =>
        value.Contains(part, StringComparison.Ordinal);
    internal static string StringTrim(string value)
    {
        var start = 0;
        while (start < value.Length && value[start] <= '\u0020') start++;
        var end = value.Length;
        while (end > start && value[end - 1] <= '\u0020') end--;
        return value.Substring(start, end - start);
    }

    internal static int LongHashCode(long value) =>
        unchecked((int)(value ^ (long)((ulong)value >> 32)));

    internal static int StringHashCode(string value)
    {
        var result = 0;
        foreach (var character in value) result = unchecked(31 * result + character);
        return result;
    }

    internal static string StringValueOf(object? value) => value switch
    {
        null => "null",
        bool boolean => boolean ? "true" : "false",
        double number => JavaFloatingString(number),
        float number => JavaFloatingString(number),
        Uri uri => UriToString(uri),
        Regex regex => JavaCompat.RegexPattern(regex),
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString() ?? "null"
    };
    internal static string StringValueOf(char value) => value.ToString();
    internal static string StringValueOf(char[] value) => new(value);
    internal static string StringValueOf(bool value) => value ? "true" : "false";
    internal static string StringValueOf(int value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(long value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringJoin(string delimiter, IEnumerable<string> values) =>
        string.Join(delimiter, values);
    internal static string StringValueOf(float value) => JavaFloatingString(value);
    internal static string StringValueOf(double value) => JavaFloatingString(value);
    internal static string Normalize(string value, NormalizationForm form) =>
        value.Normalize(form);
    internal static StringBuilder AppendValue(StringBuilder builder, object? value)
    {
        builder.Append(StringValueOf(value));
        return builder;
    }
    internal static IEnumerable<string> StringLines(string value) => value.Replace("\r\n", "\n").Split('\n');
    internal static int ReaderRead(TextReader reader, char[] buffer, int index, int count)
    {
        var read = reader.Read(buffer, index, count);
        return read == 0 && count != 0 ? -1 : read;
    }
    internal static string[] StringSplit(string value, string pattern, int limit)
        => RegexSplit(CompileRegex(pattern), value, limit);
    internal static void ListAddFirst<T>(IList<T> values, T value) =>
        values.Insert(0, value);
    internal static bool StringMatches(string value, string pattern) => RegexMatcher(CompileRegex(pattern), value).Matches();
    internal static string StringReplaceAll(string value, string pattern, string replacement) =>
        RegexMatcher(CompileRegex(pattern), value).ReplaceAll(replacement);
    internal static string StringReplaceFirst(string value, string pattern, string replacement) =>
        RegexMatcher(CompileRegex(pattern), value).ReplaceFirst(replacement);
    internal static sbyte[] StringGetBytes(string value, Encoding encoding)
    {
        if (ReferenceEquals(encoding, JavaStandardCharsets.UTF16))
        {
            return new byte[] { 0xfe, 0xff }
                .Concat(Encoding.BigEndianUnicode.GetBytes(value))
                .Select(item => unchecked((sbyte)item))
                .ToArray();
        }
        if (encoding.CodePage == Encoding.UTF8.CodePage)
        {
            encoding = (Encoding)new UTF8Encoding(false, false).Clone();
            encoding.EncoderFallback = new EncoderReplacementFallback("?");
        }
        return encoding.GetBytes(value).Select(item => unchecked((sbyte)item)).ToArray();
    }
    internal static sbyte[] StringGetBytes(string value, string encoding)
    {
        if (encoding.Equals("UTF-16", StringComparison.OrdinalIgnoreCase))
        {
            var payload = Encoding.BigEndianUnicode.GetBytes(value);
            return new byte[] { 0xfe, 0xff }.Concat(payload)
                .Select(item => unchecked((sbyte)item)).ToArray();
        }
        if (encoding.Equals("UTF-16BE", StringComparison.OrdinalIgnoreCase))
            return Encoding.BigEndianUnicode.GetBytes(value)
                .Select(item => unchecked((sbyte)item)).ToArray();
        if (encoding.Equals("UTF-16LE", StringComparison.OrdinalIgnoreCase))
            return Encoding.Unicode.GetBytes(value)
                .Select(item => unchecked((sbyte)item)).ToArray();
        if (encoding.Equals("ISO-8859-1", StringComparison.OrdinalIgnoreCase))
        {
            var bytes = new List<sbyte>();
            foreach (var rune in value.EnumerateRunes())
                bytes.Add(unchecked((sbyte)(rune.Value <= 0xff ? rune.Value : '?')));
            return bytes.ToArray();
        }
        return StringGetBytes(value, Encoding.GetEncoding(encoding));
    }

    private static string JavaFloatingString(double value)
    {
        if (double.IsNaN(value)) return "NaN";
        if (double.IsPositiveInfinity(value)) return "Infinity";
        if (double.IsNegativeInfinity(value)) return "-Infinity";
        if (value == 0) return BitConverter.DoubleToInt64Bits(value) < 0 ? "-0.0" : "0.0";
        // Double.ToString("R") uses "5E-324" for the minimum subnormal,
        // whereas Java's canonical Double.toString representation is
        // "4.9E-324". Keep the exact Java spelling at this boundary.
        if (BitConverter.DoubleToInt64Bits(value) == 1) return "4.9E-324";
        if (BitConverter.DoubleToInt64Bits(value) == unchecked((long)0x8000000000000001UL))
            return "-4.9E-324";
        return JavaFiniteFloatingString(value.ToString("R", CultureInfo.InvariantCulture), Math.Abs(value));
    }

    private static string JavaFloatingString(float value)
    {
        if (float.IsNaN(value)) return "NaN";
        if (float.IsPositiveInfinity(value)) return "Infinity";
        if (float.IsNegativeInfinity(value)) return "-Infinity";
        if (value == 0) return BitConverter.SingleToInt32Bits(value) < 0 ? "-0.0" : "0.0";
        return JavaFiniteFloatingString(value.ToString("R", CultureInfo.InvariantCulture), Math.Abs((double)value));
    }

    private static string JavaFiniteFloatingString(string text, double magnitude)
    {
        var negative = text[0] == '-';
        if (negative) text = text[1..];
        var exponentIndex = text.IndexOfAny(new[] { 'E', 'e' });
        var exponent = 0;
        if (exponentIndex >= 0)
        {
            exponent = int.Parse(text[(exponentIndex + 1)..], CultureInfo.InvariantCulture);
            text = text[..exponentIndex];
        }
        var decimalIndex = text.IndexOf('.');
        var decimalPosition = (decimalIndex < 0 ? text.Length : decimalIndex) + exponent;
        var digits = text.Replace(".", "", StringComparison.Ordinal);
        while (digits.Length > 1 && digits[0] == '0')
        {
            digits = digits[1..];
            decimalPosition--;
        }
        while (digits.Length > 1 && digits[^1] == '0') digits = digits[..^1];

        string result;
        if (magnitude >= 1e7 || magnitude < 1e-3)
        {
            result = digits.Length == 1 ? digits + ".0" : digits[0] + "." + digits[1..];
            result += "E" + (decimalPosition - 1).ToString(CultureInfo.InvariantCulture);
        }
        else if (decimalPosition <= 0)
        {
            result = "0." + new string('0', -decimalPosition) + digits;
        }
        else if (decimalPosition >= digits.Length)
        {
            result = digits + new string('0', decimalPosition - digits.Length) + ".0";
        }
        else
        {
            result = digits.Insert(decimalPosition, ".");
        }
        return negative ? "-" + result : result;
    }

    // Port of java.lang.FdLibm.Pow from OpenJDK 21. StrictMath.pow is defined
    // in terms of this fdlibm implementation, and System.Math.Pow can differ
    // by one ulp for ordinary translated Java expressions.
    internal static double StrictPow(double x, double y)
    {
        double z, r, s, t, u, v, w;
        int i, j, k, n;

        if (y == 0.0) return 1.0;
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;

        var yAbs = Math.Abs(y);
        var xAbs = Math.Abs(x);
        if (y == 2.0) return x * x;
        if (y == 0.5)
        {
            if (x >= -double.MaxValue) return Math.Sqrt(x + 0.0);
        }
        else if (yAbs == 1.0)
        {
            return y == 1.0 ? x : 1.0 / x;
        }
        else if (double.IsPositiveInfinity(yAbs))
        {
            if (xAbs == 1.0) return y - y;
            if (xAbs > 1.0) return y >= 0 ? y : 0.0;
            return y < 0 ? -y : 0.0;
        }

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var yIsInt = 0;
        if (hx < 0)
        {
            if (yAbs >= 9007199254740992.0)
            {
                yIsInt = 2;
            }
            else if (yAbs >= 1.0)
            {
                var yAsLong = (long)yAbs;
                if ((double)yAsLong == yAbs) yIsInt = 2 - (int)(yAsLong & 1L);
            }
        }

        if (xAbs == 0.0 || double.IsPositiveInfinity(xAbs) || xAbs == 1.0)
        {
            z = xAbs;
            if (y < 0.0) z = 1.0 / z;
            if (hx < 0)
            {
                if (((ix - 0x3ff00000) | yIsInt) == 0) z = (z - z) / (z - z);
                else if (yIsInt == 1) z = -z;
            }
            return z;
        }

        n = (hx >> 31) + 1;
        if ((n | yIsInt) == 0) return (x - x) / (x - x);
        s = (n | (yIsInt - 1)) == 0 ? -1.0 : 1.0;

        double pH, pL, t1, t2;
        if (yAbs > 2147483903.9999998)
        {
            const double invLn2 = 1.44269504088896338700;
            const double invLn2H = 1.44269502162933349609;
            const double invLn2L = 1.92596299112661746887e-08;
            if (xAbs < 0.9999995231628418) return y < 0.0 ? s * double.PositiveInfinity : s * 0.0;
            if (xAbs > 1.0000009536743162) return y > 0.0 ? s * double.PositiveInfinity : s * 0.0;
            t = xAbs - 1.0;
            w = t * t * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
            u = invLn2H * t;
            v = t * invLn2L - w * invLn2;
            t1 = ClearLowWord(u + v);
            t2 = v - (t1 - u);
        }
        else
        {
            const double cp = 9.61796693925975554329e-01;
            const double cpH = 9.61796700954437255859e-01;
            const double cpL = -7.02846165095275826516e-09;
            ReadOnlySpan<double> bp = [1.0, 1.5];
            ReadOnlySpan<double> dpH = [0.0, 5.84962487220764160156e-01];
            ReadOnlySpan<double> dpL = [0.0, 1.35003920212974897128e-08];
            const double l1 = 5.99999999999994648725e-01;
            const double l2 = 4.28571428578550184252e-01;
            const double l3 = 3.33333329818377432918e-01;
            const double l4 = 2.72728123808534006489e-01;
            const double l5 = 2.30660745775561754067e-01;
            const double l6 = 2.06975017800338417784e-01;

            n = 0;
            if (ix < 0x00100000)
            {
                xAbs *= 9007199254740992.0;
                n -= 53;
                ix = HighWord(xAbs);
            }
            n += (ix >> 20) - 0x3ff;
            j = ix & 0x000fffff;
            ix = j | 0x3ff00000;
            if (j <= 0x3988E) k = 0;
            else if (j < 0xBB67A) k = 1;
            else
            {
                k = 0;
                n++;
                ix -= 0x00100000;
            }
            xAbs = WithHighWord(xAbs, ix);
            u = xAbs - bp[k];
            v = 1.0 / (xAbs + bp[k]);
            var ss = u * v;
            var sH = ClearLowWord(ss);
            var tH = WithHighWord(0.0, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
            var tL = xAbs - (tH - bp[k]);
            var sL = v * ((u - sH * tH) - sH * tL);
            var s2 = ss * ss;
            r = s2 * s2 * (l1 + s2 * (l2 + s2 * (l3 + s2 * (l4 + s2 * (l5 + s2 * l6)))));
            r += sL * (sH + ss);
            s2 = sH * sH;
            tH = ClearLowWord(3.0 + s2 + r);
            tL = r - ((tH - 3.0) - s2);
            u = sH * tH;
            v = sL * tH + tL * ss;
            pH = ClearLowWord(u + v);
            pL = v - (pH - u);
            var zH = cpH * pH;
            var zL = cpL * pH + pL * cp + dpL[k];
            t = n;
            t1 = ClearLowWord(((zH + zL) + dpH[k]) + t);
            t2 = zL - (((t1 - t) - dpH[k]) - zH);
        }

        var y1 = ClearLowWord(y);
        pL = (y - y1) * t1 + y * t2;
        pH = y1 * t1;
        z = pL + pH;
        j = HighWord(z);
        i = LowWord(z);
        if (j >= 0x40900000)
        {
            if (((j - 0x40900000) | i) != 0) return s * double.PositiveInfinity;
            const double ovt = 8.0085662595372944372e-17;
            if (pL + ovt > z - pH) return s * double.PositiveInfinity;
        }
        else if ((j & 0x7fffffff) >= 0x4090cc00)
        {
            if (((j - unchecked((int)0xc090cc00)) | i) != 0) return s * 0.0;
            if (pL <= z - pH) return s * 0.0;
        }

        const double p1 = 1.66666666666666019037e-01;
        const double p2 = -2.77777777770155933842e-03;
        const double p3 = 6.61375632143793436117e-05;
        const double p4 = -1.65339022054652515390e-06;
        const double p5 = 4.13813679705723846039e-08;
        const double lg2 = 6.93147180559945286227e-01;
        const double lg2H = 6.93147182464599609375e-01;
        const double lg2L = -1.90465429995776804525e-09;
        i = j & 0x7fffffff;
        k = (i >> 20) - 0x3ff;
        n = 0;
        if (i > 0x3fe00000)
        {
            n = j + (0x00100000 >> (k + 1));
            k = ((n & 0x7fffffff) >> 20) - 0x3ff;
            t = WithHighWord(0.0, n & ~(0x000fffff >> k));
            n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
            if (j < 0) n = -n;
            pH -= t;
        }
        t = ClearLowWord(pL + pH);
        u = t * lg2H;
        v = (pL - (t - pH)) * lg2 + t * lg2L;
        z = u + v;
        w = v - (z - u);
        t = z * z;
        t1 = z - t * (p1 + t * (p2 + t * (p3 + t * (p4 + t * p5))));
        r = z * t1 / (t1 - 2.0) - (w + z * w);
        z = 1.0 - (r - z);
        j = HighWord(z) + (n << 20);
        z = (j >> 20) <= 0 ? Math.ScaleB(z, n) : WithHighWord(z, j);
        return s * z;
    }

    // Ports of the corresponding OpenJDK fdlibm routines. StrictMath is
    // specified in terms of these algorithms; platform libm functions can
    // differ by one ulp, which is observable in rendered values.
    internal static double StrictLog(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ln2Hi = 6.93147180369123816490e-01;
        const double ln2Lo = 1.90821492927058770002e-10;
        const double lg1 = 6.666666666666735130e-01;
        const double lg2 = 3.999999999940941908e-01;
        const double lg3 = 2.857142874366239149e-01;
        const double lg4 = 2.222219843214978396e-01;
        const double lg5 = 1.818357216161805012e-01;
        const double lg6 = 1.531383769920937332e-01;
        const double lg7 = 1.479819860511658591e-01;

        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        hx &= 0x000fffff;
        var i = (hx + 0x95f64) & 0x100000;
        x = WithHighWord(x, hx | (i ^ 0x3ff00000));
        k += i >> 20;
        var f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3)
        {
            if (f == 0.0)
                return k == 0 ? 0.0 : k * ln2Hi + k * ln2Lo;
            var smallR = f * f * (0.5 - 0.33333333333333333 * f);
            return k == 0
                ? f - smallR
                : k * ln2Hi - ((smallR - k * ln2Lo) - f);
        }
        var s = f / (2.0 + f);
        var dk = (double)k;
        var z = s * s;
        i = hx - 0x6147a;
        var w = z * z;
        var j = 0x6b851 - hx;
        var t1 = w * (lg2 + w * (lg4 + w * lg6));
        var t2 = z * (lg1 + w * (lg3 + w * (lg5 + w * lg7)));
        i |= j;
        var r = t2 + t1;
        if (i > 0)
        {
            var hfsq = 0.5 * f * f;
            return k == 0
                ? f - (hfsq - s * (hfsq + r))
                : dk * ln2Hi - ((hfsq - (s * (hfsq + r) + dk * ln2Lo)) - f);
        }
        return k == 0
            ? f - s * (f - r)
            : dk * ln2Hi - ((s * (f - r) - dk * ln2Lo) - f);
    }

    internal static double StrictLog10(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ivln10 = 4.34294481903251816668e-01;
        const double log10_2hi = 3.01029995663611771306e-01;
        const double log10_2lo = 3.69423907715893078616e-13;
        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        var i = (int)((uint)k >> 31);
        hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
        var y = (double)(k + i);
        x = WithHighWord(x, hx);
        var z = y * log10_2lo + ivln10 * StrictLog(x);
        return z + y * log10_2hi;
    }

    private static int StrictRemPio2(double x, Span<double> y)
    {
        const double invpio2 = 6.36619772367581382433e-01;
        const double pio2_1 = 1.57079632673412561417e+00;
        const double pio2_1t = 6.07710050650619224932e-11;
        const double pio2_2 = 6.07710050630396597660e-11;
        const double pio2_2t = 2.02226624879595063154e-21;
        const double pio2_3 = 2.02226624871116645580e-21;
        const double pio2_3t = 8.47842766036889956997e-32;
        ReadOnlySpan<int> npio2Hw =
        [
            0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
            0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
            0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
            0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
            0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
            0x404858EB, 0x404921FB
        ];

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb)
        {
            y[0] = x;
            y[1] = 0.0;
            return 0;
        }
        if (ix < 0x4002d97c)
        {
            if (hx > 0)
            {
                var z = x - pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z - pio2_1t;
                    y[1] = (z - y[0]) - pio2_1t;
                }
                else
                {
                    z -= pio2_2;
                    y[0] = z - pio2_2t;
                    y[1] = (z - y[0]) - pio2_2t;
                }
                return 1;
            }
            else
            {
                var z = x + pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z + pio2_1t;
                    y[1] = (z - y[0]) + pio2_1t;
                }
                else
                {
                    z += pio2_2;
                    y[0] = z + pio2_2t;
                    y[1] = (z - y[0]) + pio2_2t;
                }
                return -1;
            }
        }
        var t = Math.Abs(x);
        var n = (int)(t * invpio2 + 0.5);
        var fn = (double)n;
        var r = t - fn * pio2_1;
        var w = fn * pio2_1t;
        if (n < 32 && ix != npio2Hw[n - 1])
        {
            y[0] = r - w;
        }
        else
        {
            var j = ix >> 20;
            y[0] = r - w;
            var i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
            if (i > 16)
            {
                t = r;
                w = fn * pio2_2;
                r = t - w;
                w = fn * pio2_2t - ((t - r) - w);
                y[0] = r - w;
                i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
                if (i > 49)
                {
                    t = r;
                    w = fn * pio2_3;
                    r = t - w;
                    w = fn * pio2_3t - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx >= 0) return n;
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }

    private static double StrictKernelSin(double x, double y, int iy)
    {
        const double s1 = -1.66666666666666324348e-01;
        const double s2 = 8.33333333332248946124e-03;
        const double s3 = -1.98412698298579493134e-04;
        const double s4 = 2.75573137070700676789e-06;
        const double s5 = -2.50507602534068634195e-08;
        const double s6 = 1.58969099521155010221e-10;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return x;
        var z = x * x;
        var v = z * x;
        var r = s2 + z * (s3 + z * (s4 + z * (s5 + z * s6)));
        return iy == 0
            ? x + v * (s1 + z * r)
            : x - ((z * (0.5 * y - v * r) - y) - v * s1);
    }

    private static double StrictKernelCos(double x, double y)
    {
        const double c1 = 4.16666666666666019037e-02;
        const double c2 = -1.38888888888741095749e-03;
        const double c3 = 2.48015872894767294178e-05;
        const double c4 = -2.75573143513906633035e-07;
        const double c5 = 2.08757232129817482790e-09;
        const double c6 = -1.13596475577881948265e-11;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return 1.0;
        var z = x * x;
        var r = z * (c1 + z * (c2 + z * (c3 + z * (c4 + z * (c5 + z * c6)))));
        if (ix < 0x3fd33333) return 1.0 - (0.5 * z - (z * r - x * y));
        var qx = ix > 0x3fe90000
            ? 0.28125
            : BitConverter.Int64BitsToDouble((long)(ix - 0x00200000) << 32);
        var hz = 0.5 * z - qx;
        var a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    internal static double StrictSin(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelSin(x, 0.0, 0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Sin(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelSin(y[0], y[1], 1),
            1 => StrictKernelCos(y[0], y[1]),
            2 => -StrictKernelSin(y[0], y[1], 1),
            _ => -StrictKernelCos(y[0], y[1])
        };
    }

    internal static double StrictCos(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelCos(x, 0.0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Cos(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelCos(y[0], y[1]),
            1 => -StrictKernelSin(y[0], y[1], 1),
            2 => -StrictKernelCos(y[0], y[1]),
            _ => StrictKernelSin(y[0], y[1], 1)
        };
    }

    internal static double StrictAsin(double x)
    {
        const double pio2Hi = 1.57079632679489655800e+00;
        const double pio2Lo = 6.12323399573676603587e-17;
        const double pio4Hi = 7.85398163397448278999e-01;
        const double pS0 = 1.66666666666666657415e-01;
        const double pS1 = -3.25565818622400915405e-01;
        const double pS2 = 2.01212532134862925881e-01;
        const double pS3 = -4.00555345006794114027e-02;
        const double pS4 = 7.91534994289814532176e-04;
        const double pS5 = 3.47933107596021167570e-05;
        const double qS1 = -2.40339491173441421878e+00;
        const double qS2 = 2.02094576023350569471e+00;
        const double qS3 = -6.88283971605453293030e-01;
        const double qS4 = 7.70381505559019352791e-02;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000)
        {
            if (((ix - 0x3ff00000) | LowWord(x)) == 0) return x * pio2Hi + x * pio2Lo;
            return double.NaN;
        }
        double t = 0.0;
        if (ix < 0x3fe00000)
        {
            if (ix < 0x3e400000) return x;
            t = x * x;
            var p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
            var q = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
            return x + x * (p / q);
        }
        var w = 1.0 - Math.Abs(x);
        t = w * 0.5;
        var pn = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
        var qn = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
        var s = Math.Sqrt(t);
        if (ix >= 0x3fef3333)
        {
            w = pn / qn;
            t = pio2Hi - (2.0 * (s + s * w) - pio2Lo);
        }
        else
        {
            w = ClearLowWord(s);
            var c = (t - w * w) / (s + w);
            var r = pn / qn;
            var p = 2.0 * s * r - (pio2Lo - 2.0 * c);
            var q = pio4Hi - 2.0 * w;
            t = pio4Hi - (p - q);
        }
        return hx > 0 ? t : -t;
    }

    internal static double StrictAtan(double x)
    {
        ReadOnlySpan<double> atanHi =
        [4.63647609000806093515e-01, 7.85398163397448278999e-01,
         9.82793723247329054082e-01, 1.57079632679489655800e+00];
        ReadOnlySpan<double> atanLo =
        [2.26987774529616870924e-17, 3.06161699786838301793e-17,
         1.39033110312319984516e-17, 6.12323399573676603587e-17];
        ReadOnlySpan<double> aT =
        [
            3.33333333333329318027e-01, -1.99999999998764832476e-01,
            1.42857142725034663711e-01, -1.11111104054623557880e-01,
            9.09088713343650656196e-02, -7.69187620504482999495e-02,
            6.66107313738753120669e-02, -5.83357013379057348645e-02,
            4.97687799461593236017e-02, -3.65315727442169155270e-02,
            1.62858201153657823623e-02
        ];
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        int id;
        if (ix >= 0x44100000)
        {
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && LowWord(x) != 0)) return x + x;
            return hx > 0 ? atanHi[3] + atanLo[3] : -atanHi[3] - atanLo[3];
        }
        if (ix < 0x3fdc0000)
        {
            if (ix < 0x3e200000) return x;
            id = -1;
        }
        else
        {
            x = Math.Abs(x);
            if (ix < 0x3ff30000)
            {
                if (ix < 0x3fe60000)
                {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                }
                else
                {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            }
            else if (ix < 0x40038000)
            {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            }
            else
            {
                id = 3;
                x = -1.0 / x;
            }
        }
        var z = x * x;
        var w = z * z;
        var s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
        var s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
        if (id < 0) return x - x * (s1 + s2);
        z = atanHi[id] - ((x * (s1 + s2) - atanLo[id]) - x);
        return hx < 0 ? -z : z;
    }

    internal static double StrictAtan2(double y, double x)
    {
        const double tiny = 1.0e-300;
        const double piOver4 = 7.8539816339744827900e-01;
        const double piOver2 = 1.5707963267948965580e+00;
        const double piLo = 1.2246467991473531772e-16;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var lx = LowWord(x);
        var hy = HighWord(y);
        var iy = hy & 0x7fffffff;
        var ly = LowWord(y);
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;
        if (((hx - 0x3ff00000) | lx) == 0) return StrictAtan(y);
        var m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
        if ((iy | ly) == 0)
            return m switch { 0 or 1 => y, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        if ((ix | lx) == 0) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        if (ix == 0x7ff00000)
        {
            if (iy == 0x7ff00000)
                return m switch
                {
                    0 => piOver4 + tiny,
                    1 => -piOver4 - tiny,
                    2 => 3.0 * piOver4 + tiny,
                    _ => -3.0 * piOver4 - tiny
                };
            return m switch { 0 => 0.0, 1 => -0.0, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        }
        if (iy == 0x7ff00000) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        var k = (iy - ix) >> 20;
        double z;
        if (k > 60) z = piOver2 + 0.5 * piLo;
        else if (hx < 0 && k < -60) z = 0.0;
        else z = StrictAtan(Math.Abs(y / x));
        return m switch
        {
            0 => z,
            1 => -z,
            2 => Math.PI - (z - piLo),
            _ => (z - piLo) - Math.PI
        };
    }

    private static int HighWord(double value) =>
        unchecked((int)(BitConverter.DoubleToInt64Bits(value) >> 32));

    private static int LowWord(double value) =>
        unchecked((int)BitConverter.DoubleToInt64Bits(value));

    private static double ClearLowWord(double value) =>
        BitConverter.Int64BitsToDouble(BitConverter.DoubleToInt64Bits(value) & unchecked((long)0xffffffff00000000UL));

    private static double WithHighWord(double value, int highWord) =>
        BitConverter.Int64BitsToDouble(
            (BitConverter.DoubleToInt64Bits(value) & 0x00000000ffffffffL) |
            ((long)highWord << 32));
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
        var field = value.GetType().GetFields(flags)
            .FirstOrDefault(candidate => ReferenceEquals(candidate.GetValue(null), value));
        return field?.GetCustomAttribute<JavaEnumNameAttribute>()?.Name
               ?? field?.Name
               ?? value.ToString()
               ?? string.Empty;
    }

    internal static int EnumOrdinal(object value)
    {
        var type = value.GetType();
        if (type.IsEnum) return Convert.ToInt32(value, CultureInfo.InvariantCulture);
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        var constants = type.GetFields(flags)
            .Where(field => type.IsAssignableFrom(field.FieldType))
            .OrderBy(field => field.MetadataToken)
            .ToArray();
        var ordinal = Array.FindIndex(constants, field => ReferenceEquals(field.GetValue(null), value));
        return ordinal >= 0 ? ordinal : throw new ArgumentException("Value is not a declared enum constant", nameof(value));
    }

    internal static T EnumValueOf<T>(string name)
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        var field = typeof(T).GetFields(flags)
            .Where(candidate => typeof(T).IsAssignableFrom(candidate.FieldType))
            .FirstOrDefault(candidate =>
                string.Equals(
                    candidate.GetCustomAttribute<JavaEnumNameAttribute>()?.Name
                    ?? candidate.Name,
                    name,
                    StringComparison.Ordinal));
        return field?.GetValue(null) is T value
            ? value
            : throw new ArgumentException($"No enum constant {typeof(T).FullName}.{name}", nameof(name));
    }

    internal static T[] EnumValues<T>()
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        return typeof(T).GetFields(flags)
            .Where(field => typeof(T).IsAssignableFrom(field.FieldType))
            .OrderBy(field => field.MetadataToken)
            .Select(field => (T)field.GetValue(null)!)
            .ToArray();
    }

    internal static int ReflectionFieldModifiers(FieldInfo field)
    {
        ArgumentNullException.ThrowIfNull(field);
        var modifiers = 0;
        if (field.IsPublic) modifiers |= 0x0001;
        if (field.IsPrivate) modifiers |= 0x0002;
        if (field.IsFamily) modifiers |= 0x0004;
        if (field.IsStatic) modifiers |= 0x0008;
        if (field.IsInitOnly || field.IsLiteral) modifiers |= 0x0010;
        return modifiers;
    }

    internal static bool ReflectionModifierIsFinal(int modifiers) =>
        (modifiers & 0x0010) != 0;

    internal static int ParseInt(string value)
    {
        try
        {
            return int.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }

    internal static int ParseInt(string value, int radix) =>
        checked((int)ParseSignedRadix(value, radix, int.MinValue, int.MaxValue));
    internal static bool ParseBoolean(string value) =>
        string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);

    internal static long ParseLong(string value)
    {
        try
        {
            return long.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static long ParseLong(string value, int radix) =>
        ParseSignedRadix(value, radix, long.MinValue, long.MaxValue);
    internal static long ParseLong(string value, int beginIndex, int endIndex, int radix) =>
        ParseLong(value.Substring(beginIndex, endIndex - beginIndex), radix);
    internal static sbyte ParseByte(string value, int radix)
    {
        try
        {
            return checked((sbyte)ParseSignedRadix(value, radix, sbyte.MinValue, sbyte.MaxValue));
        }
        catch (OverflowException error)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }

    private static long ParseSignedRadix(string value, int radix, long minimum, long maximum)
    {
        if (radix is < 2 or > 36) throw new ArgumentException($"Invalid radix {radix}.");
        if (string.IsNullOrEmpty(value))
            throw new JavaNumberFormatException("Input string was empty.");
        var index = 0;
        var negative = false;
        if (value[0] is '+' or '-')
        {
            negative = value[0] == '-';
            index++;
            if (index == value.Length)
                throw new JavaNumberFormatException($"Invalid number `{value}`.");
        }
        ulong magnitude = 0;
        var negativeLimit = unchecked((ulong)(-(minimum + 1))) + 1UL;
        var limit = negative ? negativeLimit : (ulong)maximum;
        for (; index < value.Length; index++)
        {
            var character = value[index];
            var digit = character is >= '0' and <= '9' ? character - '0'
                : character is >= 'a' and <= 'z' ? character - 'a' + 10
                : character is >= 'A' and <= 'Z' ? character - 'A' + 10
                : -1;
            if (digit < 0 || digit >= radix)
                throw new JavaNumberFormatException($"Invalid number `{value}` for radix {radix}.");
            if (magnitude > (limit - (uint)digit) / (uint)radix)
                throw new JavaNumberFormatException($"Number `{value}` is out of range.");
            magnitude = magnitude * (uint)radix + (uint)digit;
        }
        if (!negative) return (long)magnitude;
        return magnitude == negativeLimit ? minimum : -(long)magnitude;
    }
    internal static double ParseDouble(string value)
    {
        try
        {
            return double.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static float ParseFloat(string value)
    {
        try
        {
            return float.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static int CompareLong(long left, long right) => left.CompareTo(right);
    internal static int CompareInt(int left, int right) => left.CompareTo(right);
    internal static int CompareFloat(float left, float right) => left.CompareTo(right);
    internal static int CompareDouble(double left, double right)
    {
        if (left < right) return -1;
        if (left > right) return 1;
        if (double.IsNaN(left)) return double.IsNaN(right) ? 0 : 1;
        if (double.IsNaN(right)) return -1;
        var leftBits = BitConverter.DoubleToInt64Bits(left);
        var rightBits = BitConverter.DoubleToInt64Bits(right);
        return leftBits == rightBits ? 0 : leftBits < rightBits ? -1 : 1;
    }
    internal static int StringCompareTo(string left, string right) =>
        string.Compare(left, right, StringComparison.Ordinal);
    internal static int LongLeadingZeros(long value) => BitOperations.LeadingZeroCount(unchecked((ulong)value));
    internal static int LongTrailingZeros(long value) => BitOperations.TrailingZeroCount(unchecked((ulong)value));
    internal static int IntLeadingZeros(int value) => BitOperations.LeadingZeroCount(unchecked((uint)value));
    internal static int HighestOneBit(int value) =>
        value == 0 ? 0 : 1 << (31 - BitOperations.LeadingZeroCount(unchecked((uint)value)));
    internal static int FloatToIntBits(float value) =>
        float.IsNaN(value) ? 0x7fc00000 : BitConverter.SingleToInt32Bits(value);
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
    internal static string ToStringRadix(BigInteger value, int radix)
    {
        const string digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        if (radix is < 2 or > 36) radix = 10;
        if (value.IsZero) return "0";
        var negative = value.Sign < 0;
        var remaining = BigInteger.Abs(value);
        var result = new StringBuilder();
        while (!remaining.IsZero)
        {
            remaining = BigInteger.DivRem(remaining, radix, out var remainder);
            result.Append(digits[(int)remainder]);
        }
        if (negative) result.Append('-');
        var characters = result.ToString().ToCharArray();
        Array.Reverse(characters);
        return new string(characters);
    }
    internal static int ToUnsignedInt(sbyte value) => unchecked((byte)value);

    internal static int CharacterCharCount(int codePoint) => codePoint >= 0x10000 ? 2 : 1;

    internal static string CharacterName(int codePoint)
    {
        if (!IsValidCodePoint(codePoint))
            throw new ArgumentException("Invalid Unicode code point.", nameof(codePoint));
        if (codePoint is >= 'A' and <= 'Z')
            return $"LATIN CAPITAL LETTER {(char)codePoint}";
        if (codePoint is >= 'a' and <= 'z')
            return $"LATIN SMALL LETTER {char.ToUpperInvariant((char)codePoint)}";
        if (codePoint is >= '0' and <= '9')
        {
            string[] digitNames =
                ["ZERO", "ONE", "TWO", "THREE", "FOUR",
                 "FIVE", "SIX", "SEVEN", "EIGHT", "NINE"];
            return $"DIGIT {digitNames[codePoint - '0']}";
        }
        if (codePoint == ' ') return "SPACE";
        return $"U+{codePoint:X4}";
    }

    internal static bool CharacterIsDefined(int codePoint)
    {
        if (codePoint is >= 0xd800 and <= 0xdfff) return true;
        return Rune.IsValid(codePoint) &&
            Rune.GetUnicodeCategory(new Rune(codePoint)) !=
                UnicodeCategory.OtherNotAssigned;
    }
    internal static bool IsBmpCodePoint(int codePoint) => (uint)codePoint <= 0xffff;
    internal static bool IsValidCodePoint(int codePoint) => (uint)codePoint <= 0x10ffff;

    internal static IEnumerable<int> StringCodePoints(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        for (var index = 0; index < value.Length; index++)
        {
            var first = value[index];
            if (char.IsHighSurrogate(first) &&
                index + 1 < value.Length &&
                char.IsLowSurrogate(value[index + 1]))
            {
                yield return char.ConvertToUtf32(first, value[++index]);
            }
            else
            {
                yield return first;
            }
        }
    }

    internal static int StringCodePointCount(string value, int beginIndex, int endIndex)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (beginIndex < 0 || endIndex > value.Length || beginIndex > endIndex)
        {
            throw new IndexOutOfRangeException();
        }
        var count = 0;
        for (var index = beginIndex; index < endIndex; index++, count++)
        {
            if (char.IsHighSurrogate(value[index]) &&
                index + 1 < endIndex &&
                char.IsLowSurrogate(value[index + 1]))
            {
                index++;
            }
        }
        return count;
    }

    internal static Encoding CharsetForName(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        return Encoding.GetEncoding(name);
    }

    internal static string CharsetName(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        return encoding.WebName;
    }
    internal static int ToUnsignedInt(byte value) => value;
    internal static long ToUnsignedLong(sbyte value) => unchecked((byte)value);
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
    internal static int FloorDiv(int left, int right)
    {
        if (left == int.MinValue && right == -1) return int.MinValue;
        var quotient = left / right;
        var remainder = left % right;
        return remainder != 0 && (left ^ right) < 0 ? quotient - 1 : quotient;
    }
    internal static double ToDegrees(double value) => value * (180d / Math.PI);
    internal static double ToRadians(double value) => value * (Math.PI / 180d);
    internal static long AddExact(long left, long right) => checked(left + right);
    internal static long MultiplyExact(long left, long right) => checked(left * right);
    internal static int MultiplyExactInt(int left, int right) => checked(left * right);
    internal static double SignumDouble(double value) => double.IsNaN(value) || value == 0.0
        ? value
        : value > 0.0 ? 1.0 : -1.0;
    internal static float SignumFloat(float value) => float.IsNaN(value) || value == 0.0f
        ? value
        : value > 0.0f ? 1.0f : -1.0f;
    internal static long SubtractExact(long left, long right) => checked(left - right);
    internal static long NegateExact(long value) => checked(-value);
    internal static int ToIntExact(long value) => checked((int)value);
    internal static int AddExactInt(int left, int right) => checked(left + right);
    internal static int GetExponent(double value) => Math.ILogB(value);
    internal static BigInteger NewBigInteger(int signum, sbyte[] magnitude) =>
        new BigInteger(magnitude.Select(value => unchecked((byte)value)).ToArray(), true, true) * Math.Sign(signum);
    internal static BigInteger NewBigInteger(int signum, byte[] magnitude) =>
        new BigInteger(magnitude, true, true) * Math.Sign(signum);
    internal static sbyte[] BigIntegerToByteArray(BigInteger value) =>
        ToSignedBytes(value.ToByteArray(isUnsigned: false, isBigEndian: true));
    internal static BigInteger BigIntegerMod(BigInteger value, BigInteger modulus)
    {
        if (modulus.Sign <= 0)
            throw new ArithmeticException("BigInteger modulus must be positive.");
        var remainder = value % modulus;
        return remainder.Sign < 0 ? remainder + modulus : remainder;
    }
    internal static int BigIntegerIntValue(BigInteger value) =>
        unchecked((int)(uint)(value & uint.MaxValue));

    internal static IComparer<T> ComparatorComparing<T, U>(Func<T, U> extractor)
    {
        ArgumentNullException.ThrowIfNull(extractor);
        return Comparer<T>.Create(
            (left, right) => JavaCompare(extractor(left), extractor(right)));
    }
    internal static TimeSpan DurationOfSeconds(long seconds) => TimeSpan.FromSeconds(seconds);
    internal static TimeSpan DurationOfSeconds(long seconds, long nanos) =>
        TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    internal static TimeZoneInfo GetTimeZone(string id)
    {
        if (string.Equals(id, "UTC", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(id, "GMT", StringComparison.OrdinalIgnoreCase))
            return TimeZoneInfo.Utc;
        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById(id);
        }
        catch (TimeZoneNotFoundException)
        {
            return TimeZoneInfo.Utc;
        }
        catch (InvalidTimeZoneException)
        {
            return TimeZoneInfo.Utc;
        }
    }
    internal static DateTimeOffset CalendarInstance(TimeZoneInfo zone) =>
        TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, zone);
    internal static DateTimeOffset CalendarClear(DateTimeOffset value) =>
        new(1970, 1, 1, 0, 0, 0, value.Offset);
    internal static TimeZoneInfo NewSimpleTimeZone(int rawOffsetMilliseconds, string id) =>
        TimeZoneInfo.CreateCustomTimeZone(
            id,
            TimeSpan.FromMilliseconds(rawOffsetMilliseconds),
            id,
            id);
    private sealed class JavaTimeZoneMetadata
    {
        internal string Id = "";
        internal int? RawOffsetMilliseconds;
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        TimeZoneInfo, JavaTimeZoneMetadata> TimeZoneMetadata = new();
    internal static int TimeZoneRawOffset(TimeZoneInfo zone) =>
        TimeZoneMetadata.TryGetValue(zone, out var metadata) &&
        metadata.RawOffsetMilliseconds.HasValue
            ? metadata.RawOffsetMilliseconds.Value
            : checked((int)zone.BaseUtcOffset.TotalMilliseconds);
    internal static void TimeZoneSetId(TimeZoneInfo zone, string id)
    {
        var metadata = TimeZoneMetadata.GetOrCreateValue(zone);
        metadata.Id = id;
    }
    internal static string TimeZoneId(TimeZoneInfo zone) =>
        TimeZoneMetadata.TryGetValue(zone, out var metadata) &&
        !string.IsNullOrEmpty(metadata.Id)
            ? metadata.Id
            : zone.Id;
    internal static int TimeZoneOffset(TimeZoneInfo zone, long unixTimeMilliseconds)
    {
        if (TimeZoneMetadata.TryGetValue(zone, out var metadata) &&
            metadata.RawOffsetMilliseconds.HasValue)
            return metadata.RawOffsetMilliseconds.Value;
        var instant = DateTimeOffset.FromUnixTimeMilliseconds(unixTimeMilliseconds);
        return checked((int)zone.GetUtcOffset(instant).TotalMilliseconds);
    }
    internal static void TimeZoneSetRawOffset(
        TimeZoneInfo zone,
        int rawOffsetMilliseconds)
    {
        var metadata = TimeZoneMetadata.GetOrCreateValue(zone);
        metadata.RawOffsetMilliseconds = rawOffsetMilliseconds;
    }
    internal static void CalendarSetLenient(DateTimeOffset _, bool __)
    {
    }
    internal static DateTimeOffset CalendarSetTimeZone(
        DateTimeOffset value,
        TimeZoneInfo zone)
    {
        var offset = TimeSpan.FromMilliseconds(TimeZoneRawOffset(zone));
        return new DateTimeOffset(value.DateTime, offset);
    }
    internal static TimeZoneInfo CalendarGetTimeZone(DateTimeOffset value) =>
        NewSimpleTimeZone(
            checked((int)value.Offset.TotalMilliseconds),
            value.Offset == TimeSpan.Zero ? "GMT" : $"GMT{value:zzz}");
    internal static DateTimeOffset CalendarAdd(
        DateTimeOffset value,
        int field,
        int amount) =>
        field switch
        {
            1 => value.AddYears(amount),
            2 => value.AddMonths(amount),
            5 => value.AddDays(amount),
            10 or 11 => value.AddHours(amount),
            12 => value.AddMinutes(amount),
            13 => value.AddSeconds(amount),
            14 => value.AddMilliseconds(amount),
            _ => throw new ArgumentOutOfRangeException(nameof(field))
        };
    internal static DateTimeOffset CalendarSet(
        DateTimeOffset value,
        int year,
        int zeroBasedMonth,
        int day,
        int hour,
        int minute,
        int second) =>
        new(year, zeroBasedMonth + 1, day, hour, minute, second, value.Offset);
    internal static DateTimeOffset CalendarSet(DateTimeOffset value, int field, int fieldValue) =>
        field == 14
            ? new DateTimeOffset(value.Year, value.Month, value.Day, value.Hour, value.Minute,
                value.Second, fieldValue, value.Offset)
            : throw new ArgumentOutOfRangeException(nameof(field));
    internal static int CalendarGet(DateTimeOffset value, int field) => field switch
    {
        1 => value.Year,
        2 => value.Month - 1,
        5 => value.Day,
        11 => value.Hour,
        12 => value.Minute,
        13 => value.Second,
        14 => value.Millisecond,
        15 => checked((int)value.Offset.TotalMilliseconds),
        16 => 0,
        _ => throw new ArgumentOutOfRangeException(nameof(field))
    };
    internal static DateTimeOffset ParseZonedDateTime(
        string value,
        JavaDateTimeFormatter formatter) =>
        DateTimeOffset.Parse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces);
    internal static DateTime ParseLocalDateTime(
        string value,
        JavaDateTimeFormatter formatter) =>
        DateTime.Parse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces | DateTimeStyles.RoundtripKind);
    internal static TimeSpan ZoneIdOf(string id) =>
        string.Equals(id, "UTC", StringComparison.OrdinalIgnoreCase) ||
        string.Equals(id, "GMT", StringComparison.OrdinalIgnoreCase) ||
        string.Equals(id, "Z", StringComparison.OrdinalIgnoreCase)
            ? TimeSpan.Zero
            : TimeZoneInfo.FindSystemTimeZoneById(id).GetUtcOffset(DateTime.UtcNow);
    internal static DateTimeOffset LocalDateTimeAtZone(DateTime value, TimeSpan offset) =>
        new(DateTime.SpecifyKind(value, DateTimeKind.Unspecified), offset);
    internal static T ClassCast<T>(Type type, object value) =>
        type.IsInstanceOfType(value) ? (T)value : throw new InvalidCastException();
    internal static Type ClassAsSubclass(Type type, Type parentType) =>
        parentType.IsAssignableFrom(type)
            ? type
            : throw new InvalidCastException(
                $"{type.FullName ?? type.Name} is not a subclass of {parentType.FullName ?? parentType.Name}.");
    private static string? ClassResourceName(Assembly assembly, Type? type, string name)
    {
        var absolute = name.TrimStart('/').Replace('/', '.');
        var relative = name.StartsWith('/') || type?.Namespace is null
            ? absolute
            : type.Namespace + "." + absolute;
        if (assembly.GetManifestResourceInfo(relative) is not null) return relative;
        if (assembly.GetManifestResourceInfo(absolute) is not null) return absolute;
        var suffix = "." + absolute;
        var matches = assembly.GetManifestResourceNames()
            .Where(candidate => candidate.EndsWith(suffix, StringComparison.Ordinal))
            .Take(2)
            .ToArray();
        return matches.Length == 1 ? matches[0] : null;
    }
    internal static Uri? ClassGetResource(Type type, string name) =>
        ClassResourceName(type.Assembly, type, name) is { } resource ? new Uri("resource:///" + resource) : null;
    internal static Uri? ClassGetResource(Assembly assembly, string name) =>
        assembly.GetManifestResourceInfo(name.TrimStart('/')) is null ? null : new Uri("resource:///" + name.TrimStart('/'));
    internal static Stream? ClassGetResourceAsStream(Type type, string name) =>
        ClassResourceName(type.Assembly, type, name) is { } resource
            ? type.Assembly.GetManifestResourceStream(resource)
            : null;
    internal static Stream? ClassGetResourceAsStream(Assembly assembly, string name) =>
        assembly.GetManifestResourceStream(name.TrimStart('/'));
    internal static T? ClassGetAnnotation<T>(Type type, Type annotationType) where T : class =>
        type.GetCustomAttributes(true).FirstOrDefault(annotationType.IsInstanceOfType) as T;
    internal static ConstructorInfo ClassGetDeclaredConstructor(Type type, params Type[] parameterTypes) =>
        type.GetConstructor(
            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance,
            binder: null,
            types: parameterTypes,
            modifiers: null)
        ?? throw new MissingMethodException(type.FullName, ".ctor");
    internal static T ConstructorInvoke<T>(ConstructorInfo constructor, params object?[] arguments) =>
        (T)constructor.Invoke(arguments);
    internal static T? FieldGetAnnotation<T>(FieldInfo field, Type annotationType) where T : class =>
        field.GetCustomAttributes(true).FirstOrDefault(annotationType.IsInstanceOfType) as T;
    internal static bool MemberIsAnnotationPresent(MemberInfo member, Type annotationType) =>
        member.GetCustomAttributes(true).Any(annotationType.IsInstanceOfType);
    private sealed class XmlQualifiedNameMetadata
    {
        internal string Prefix = "";
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Xml.XmlQualifiedName, XmlQualifiedNameMetadata> XmlQualifiedNameMetadataTable = new();
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(string localName) =>
        NewXmlQualifiedName("", localName, "");
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(string namespaceUri, string localName) =>
        NewXmlQualifiedName(namespaceUri, localName, "");
    internal static System.Xml.XmlQualifiedName NewXmlQualifiedName(
        string namespaceUri,
        string localName,
        string prefix)
    {
        var name = new System.Xml.XmlQualifiedName(localName, namespaceUri);
        XmlQualifiedNameMetadataTable.Add(name, new XmlQualifiedNameMetadata { Prefix = prefix });
        return name;
    }
    internal static string XmlQualifiedNamePrefix(System.Xml.XmlQualifiedName name) =>
        XmlQualifiedNameMetadataTable.TryGetValue(name, out var metadata) ? metadata.Prefix : "";
    internal static string? XmlNodePrefix(System.Xml.XmlNode node) =>
        string.IsNullOrEmpty(node.Prefix) ? null : node.Prefix;
    internal static string? XmlNodeNamespaceUri(System.Xml.XmlNode node) =>
        string.IsNullOrEmpty(node.NamespaceURI) ? null : node.NamespaceURI;
    internal static System.Xml.XmlAttribute XmlAttributeItem(
        System.Xml.XmlAttributeCollection attributes,
        int index) =>
        attributes
            .Cast<System.Xml.XmlAttribute>()
            .OrderBy(attribute => attribute.Name, StringComparer.Ordinal)
            .ElementAtOrDefault(index)!;
    internal static System.Xml.XmlReaderSettings NewXmlReaderSettings() =>
        new()
        {
            DtdProcessing = System.Xml.DtdProcessing.Prohibit,
            XmlResolver = null,
            CloseInput = true
        };
    internal static System.Xml.XmlReaderSettings XmlReaderSettingsClone(
        System.Xml.XmlReaderSettings settings) =>
        settings.Clone();
    internal static void XmlReaderSetFeature(
        System.Xml.XmlReaderSettings settings,
        string feature,
        bool enabled)
    {
        switch (feature)
        {
            case "http://apache.org/xml/features/disallow-doctype-decl":
                settings.DtdProcessing = enabled
                    ? System.Xml.DtdProcessing.Prohibit
                    : System.Xml.DtdProcessing.Parse;
                break;
            case "http://xml.org/sax/features/external-general-entities":
            case "http://xml.org/sax/features/external-parameter-entities":
            case "http://apache.org/xml/features/nonvalidating/load-external-dtd":
                if (enabled)
                    throw new System.Xml.XmlException(
                        $"External XML feature '{feature}' is not supported.");
                settings.XmlResolver = null;
                break;
            default:
                throw new System.Xml.XmlException($"Unknown XML feature '{feature}'.");
        }
    }
    internal static void XmlReaderSetXIncludeAware(
        System.Xml.XmlReaderSettings settings,
        bool enabled)
    {
        _ = settings;
        if (enabled) throw new System.Xml.XmlException("XInclude is not supported.");
    }
    internal static void XmlReaderSetExpandEntityReferences(
        System.Xml.XmlReaderSettings settings,
        bool enabled)
    {
        _ = settings;
        if (enabled)
            throw new System.Xml.XmlException(
                "Entity-reference expansion requires an enabled DTD.");
    }
    internal static void XmlReaderSetNamespaceAware(
        System.Xml.XmlReaderSettings settings,
        bool enabled)
    {
        _ = settings;
        if (!enabled)
            throw new System.Xml.XmlException(
                "System.Xml readers are always namespace-aware.");
    }
    internal static void XmlSetErrorHandler(
        System.Xml.XmlReaderSettings settings,
        object? errorHandler)
    {
        _ = settings;
        _ = errorHandler;
    }
    internal static System.Xml.XmlDocument XmlParse(
        System.Xml.XmlReaderSettings settings,
        Stream input)
    {
        using var reader = System.Xml.XmlReader.Create(input, settings);
        var document = new System.Xml.XmlDocument { PreserveWhitespace = true };
        document.Load(reader);
        if (document.FirstChild is System.Xml.XmlDeclaration declaration)
            document.RemoveChild(declaration);
        NormalizeJavaDomWhitespace(document, document);
        return document;
    }
    private static void NormalizeJavaDomWhitespace(
        System.Xml.XmlDocument document,
        System.Xml.XmlNode parent)
    {
        foreach (System.Xml.XmlNode child in parent.ChildNodes.Cast<System.Xml.XmlNode>().ToArray())
        {
            if (child.NodeType is System.Xml.XmlNodeType.Whitespace
                or System.Xml.XmlNodeType.SignificantWhitespace)
            {
                if (parent is System.Xml.XmlDocument)
                    parent.RemoveChild(child);
                else
                    parent.ReplaceChild(
                        document.CreateTextNode(child.Value ?? ""),
                        child);
            }
            else
            {
                NormalizeJavaDomWhitespace(document, child);
            }
        }
    }
    internal static System.Xml.XmlWriterSettings XmlWriterSettingsClone(
        System.Xml.XmlWriterSettings settings) =>
        settings.Clone();
    internal static void XmlSetOutputProperty(
        System.Xml.XmlWriterSettings settings,
        string name,
        string value)
    {
        switch (name)
        {
            case "indent":
                settings.Indent = string.Equals(value, "yes", StringComparison.OrdinalIgnoreCase);
                break;
            case "{http://xml.apache.org/xslt}indent-amount":
                settings.IndentChars = new string(' ', ParseInt(value, 10));
                break;
            case "encoding":
                settings.Encoding =
                    string.Equals(value, "UTF-8", StringComparison.OrdinalIgnoreCase)
                        ? new UTF8Encoding(encoderShouldEmitUTF8Identifier: false)
                        : Encoding.GetEncoding(value);
                break;
            case "omit-xml-declaration":
                settings.OmitXmlDeclaration =
                    string.Equals(value, "yes", StringComparison.OrdinalIgnoreCase);
                break;
            default:
                throw new ArgumentException($"Unsupported XML output property '{name}'.", nameof(name));
        }
    }
    internal static void XmlTransform(
        System.Xml.XmlWriterSettings settings,
        System.Xml.XmlNode source,
        Stream result)
    {
        settings.CloseOutput = false;
        using var writer = System.Xml.XmlWriter.Create(result, settings);
        var namespaces = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["xml"] = "http://www.w3.org/XML/1998/namespace",
            ["xmlns"] = "http://www.w3.org/2000/xmlns/"
        };
        WriteJavaDomNode(writer, source, namespaces);
        writer.WriteWhitespace(settings.NewLineChars);
        writer.Flush();
    }
    private static void WriteJavaDomNode(
        System.Xml.XmlWriter writer,
        System.Xml.XmlNode node,
        IReadOnlyDictionary<string, string> inheritedNamespaces)
    {
        switch (node.NodeType)
        {
            case System.Xml.XmlNodeType.Document:
                foreach (System.Xml.XmlNode child in node.ChildNodes)
                {
                    if (child is System.Xml.XmlProcessingInstruction instruction)
                    {
                        writer.WriteRaw(
                            $"<?{instruction.Name} {instruction.Value}?>");
                    }
                    else
                    {
                        WriteJavaDomNode(writer, child, inheritedNamespaces);
                    }
                }
                return;
            case System.Xml.XmlNodeType.Element:
                var element = (System.Xml.XmlElement)node;
                var namespaces =
                    new Dictionary<string, string>(inheritedNamespaces, StringComparer.Ordinal);
                if (!string.IsNullOrEmpty(element.NamespaceURI))
                    namespaces[element.Prefix] = element.NamespaceURI;
                var orderedAttributes =
                    element.Attributes
                        .Cast<System.Xml.XmlAttribute>()
                        .Where(
                            attribute =>
                                string.Equals(
                                    attribute.Name,
                                    "xmlns",
                                    StringComparison.Ordinal) ||
                                string.Equals(
                                    attribute.Prefix,
                                    "xmlns",
                                    StringComparison.Ordinal))
                        .OrderBy(
                            attribute => attribute.LocalName,
                            StringComparer.Ordinal)
                        .Concat(
                            element.Attributes
                                .Cast<System.Xml.XmlAttribute>()
                                .Where(
                                    attribute =>
                                        !string.Equals(
                                            attribute.Name,
                                            "xmlns",
                                            StringComparison.Ordinal) &&
                                        !string.Equals(
                                            attribute.Prefix,
                                            "xmlns",
                                            StringComparison.Ordinal)));
                foreach (System.Xml.XmlAttribute attribute in orderedAttributes)
                {
                    if (string.Equals(attribute.Name, "xmlns", StringComparison.Ordinal))
                        namespaces[""] = attribute.Value;
                    else if (string.Equals(attribute.Prefix, "xmlns", StringComparison.Ordinal))
                        namespaces[attribute.LocalName] = attribute.Value;
                    else if (!string.IsNullOrEmpty(attribute.Prefix) &&
                             !string.IsNullOrEmpty(attribute.NamespaceURI))
                        namespaces[attribute.Prefix] = attribute.NamespaceURI;
                }
                var elementNamespace =
                    ResolveJavaDomNamespace(element.Prefix, element.NamespaceURI, namespaces);
                writer.WriteStartElement(element.Prefix, element.LocalName, elementNamespace);
                foreach (System.Xml.XmlAttribute attribute in orderedAttributes)
                {
                    if (string.Equals(attribute.Name, "xmlns", StringComparison.Ordinal))
                    {
                        if (!inheritedNamespaces.TryGetValue("", out var inheritedDefault) ||
                            !string.Equals(
                                inheritedDefault,
                                attribute.Value,
                                StringComparison.Ordinal))
                        {
                            writer.WriteAttributeString("xmlns", attribute.Value);
                        }
                    }
                    else if (string.Equals(attribute.Prefix, "xmlns", StringComparison.Ordinal))
                    {
                        if (!inheritedNamespaces.TryGetValue(
                                attribute.LocalName,
                                out var inheritedNamespace) ||
                            !string.Equals(
                                inheritedNamespace,
                                attribute.Value,
                                StringComparison.Ordinal))
                        {
                            writer.WriteAttributeString(
                                "xmlns", attribute.LocalName, null, attribute.Value);
                        }
                    }
                    else
                    {
                        var attributeNamespace =
                            ResolveJavaDomNamespace(
                                attribute.Prefix, attribute.NamespaceURI, namespaces);
                        writer.WriteAttributeString(
                            attribute.Prefix,
                            attribute.LocalName,
                            attributeNamespace,
                            attribute.Value);
                    }
                }
                foreach (System.Xml.XmlNode child in element.ChildNodes)
                    WriteJavaDomNode(writer, child, namespaces);
                writer.WriteEndElement();
                return;
            case System.Xml.XmlNodeType.Text:
                writer.WriteString(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.CDATA:
                writer.WriteCData(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.Whitespace:
            case System.Xml.XmlNodeType.SignificantWhitespace:
                writer.WriteWhitespace(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.Comment:
                writer.WriteComment(node.Value ?? "");
                return;
            case System.Xml.XmlNodeType.ProcessingInstruction:
                writer.WriteProcessingInstruction(node.Name, node.Value);
                return;
            case System.Xml.XmlNodeType.XmlDeclaration:
                return;
            default:
                throw new System.Xml.XmlException(
                    $"Unsupported Java DOM node type '{node.NodeType}'.");
        }
    }
    private static string ResolveJavaDomNamespace(
        string prefix,
        string namespaceUri,
        IReadOnlyDictionary<string, string> namespaces)
    {
        if (string.IsNullOrEmpty(prefix) || !string.IsNullOrEmpty(namespaceUri))
            return namespaceUri;
        return namespaces.TryGetValue(prefix, out var resolved)
            ? resolved
            : throw new System.Xml.XmlException(
                $"XML prefix '{prefix}' has no in-scope namespace declaration.");
    }
    private static (string Prefix, string LocalName) SplitXmlQualifiedName(string qualifiedName)
    {
        var separator = qualifiedName.IndexOf(':');
        return separator < 0
            ? ("", qualifiedName)
            : (qualifiedName[..separator], qualifiedName[(separator + 1)..]);
    }
    internal static System.Xml.XmlElement XmlCreateElementNs(
        System.Xml.XmlDocument document,
        string namespaceUri,
        string qualifiedName)
    {
        var (prefix, localName) = SplitXmlQualifiedName(qualifiedName);
        return document.CreateElement(prefix, localName, namespaceUri);
    }
    internal static void XmlSetAttributeNs(
        System.Xml.XmlElement element,
        string namespaceUri,
        string qualifiedName,
        string value)
    {
        var (prefix, localName) = SplitXmlQualifiedName(qualifiedName);
        var attribute = element.OwnerDocument.CreateAttribute(prefix, localName, namespaceUri);
        attribute.Value = value;
        element.Attributes.SetNamedItem(attribute);
    }
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

    internal static string Formatted(string format, params object?[] arguments) =>
        JavaStringFormat(CultureInfo.CurrentCulture, format, arguments);

    internal static string JavaStringFormat(string format, params object?[] arguments) =>
        JavaStringFormat(CultureInfo.CurrentCulture, format, arguments);

    internal static string JavaStringFormat(CultureInfo locale, string format,
        params object?[] arguments)
    {
        var result = new StringBuilder();
        var nextArgument = 0;
        for (var index = 0; index < format.Length; index++)
        {
            if (format[index] != '%' || index + 1 >= format.Length)
            {
                result.Append(format[index]);
                continue;
            }

            var cursor = index + 1;
            if (format[cursor] == '%')
            {
                result.Append('%');
                index = cursor;
                continue;
            }
            if (format[cursor] == 'n')
            {
                result.Append(Environment.NewLine);
                index = cursor;
                continue;
            }

            int? explicitArgument = null;
            var digitsStart = cursor;
            while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
            if (cursor < format.Length && cursor > digitsStart && format[cursor] == '$')
            {
                explicitArgument = int.Parse(format[digitsStart..cursor],
                    CultureInfo.InvariantCulture) - 1;
                cursor++;
            }
            else
            {
                cursor = digitsStart;
            }

            var flagsStart = cursor;
            while (cursor < format.Length && "-#+ 0,(<".Contains(format[cursor])) cursor++;
            var flags = format[flagsStart..cursor];
            var widthStart = cursor;
            while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
            var width = cursor > widthStart
                ? int.Parse(format[widthStart..cursor], CultureInfo.InvariantCulture)
                : 0;
            int? precision = null;
            if (cursor < format.Length && format[cursor] == '.')
            {
                cursor++;
                var precisionStart = cursor;
                while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
                if (precisionStart == cursor) throw new FormatException("Invalid Java format precision");
                precision = int.Parse(format[precisionStart..cursor], CultureInfo.InvariantCulture);
            }
            if (cursor < format.Length && format[cursor] is 't' or 'T') cursor++;
            if (cursor >= format.Length) throw new FormatException("Invalid Java format conversion");
            var conversion = format[cursor];

            var argumentIndex = explicitArgument ?? nextArgument++;
            if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                throw new FormatException("Missing Java format argument");
            var rendered = FormatJavaArgument(arguments[argumentIndex], conversion, precision, locale);
            if (conversion is >= 'A' and <= 'Z') rendered = rendered.ToUpper(locale);
            if (flags.Contains('+') && rendered.Length > 0 && rendered[0] != '-') rendered = "+" + rendered;
            if (width > rendered.Length)
            {
                var padding = new string(flags.Contains('0') && !flags.Contains('-') ? '0' : ' ',
                    width - rendered.Length);
                rendered = flags.Contains('-') ? rendered + padding : padding + rendered;
            }
            result.Append(rendered);
            index = cursor;
        }
        return result.ToString();
    }

    private static string FormatJavaArgument(object? value, char conversion, int? precision,
        CultureInfo locale)
    {
        switch (char.ToLowerInvariant(conversion))
        {
            case 's':
            {
                var rendered = StringValueOf(value);
                return precision is { } limit && rendered.Length > limit ? rendered[..limit] : rendered;
            }
            case 'b': return value is null ? "false" : value is bool boolean ? StringValueOf(boolean) : "true";
            case 'c': return value is char character
                ? character.ToString()
                : char.ConvertFromUtf32(Convert.ToInt32(value, CultureInfo.InvariantCulture));
            case 'd': return Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(locale);
            case 'o': return Convert.ToString(Convert.ToInt64(value, CultureInfo.InvariantCulture), 8)!;
            case 'x': return Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString("x", locale);
            case 'f': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("F" + (precision ?? 6), locale);
            case 'e': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("E" + (precision ?? 6), locale);
            case 'g': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("G" + (precision ?? 6), locale);
            case 'h': return JavaHashCode(value).ToString("x", CultureInfo.InvariantCulture);
            default: return StringValueOf(value);
        }
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

    internal static string CharBufferWrap(char[] value, int start, int length) =>
        new(value, start, length);

    internal static bool ReaderReady(TextReader reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        return reader.Peek() >= 0;
    }

    internal static StringBuilder AppendRange(StringBuilder builder, string value, int start, int end) =>
        builder.Append(value, start, end - start);

    internal static StringBuilder Reverse(StringBuilder builder)
    {
        var runes = builder.ToString().EnumerateRunes().ToArray();
        Array.Reverse(runes);
        builder.Clear();
        foreach (var rune in runes) builder.Append(rune.ToString());
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

    internal static void ResetMemoryStream(MemoryStream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        stream.SetLength(0);
        stream.Position = 0;
    }

    internal static string MemoryStreamToString(MemoryStream stream, string encodingName)
    {
        ArgumentNullException.ThrowIfNull(stream);
        return CharsetForName(encodingName).GetString(stream.ToArray());
    }

    internal static Stream OpenFileInput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileInput(file.FullName);
    }

    internal static Stream OpenFileInput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
    }

    internal static Stream OpenFileOutput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileOutput(file.FullName);
    }

    internal static Stream OpenFileOutput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
    }

    internal static long FileLastModified(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        file.Refresh();
        return file.Exists
            ? new DateTimeOffset(file.LastWriteTimeUtc).ToUnixTimeMilliseconds()
            : 0;
    }

    internal static FileInfo NewFileInfo(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri || !uri.IsFile)
            throw new ArgumentException("File URI must be absolute and use the file scheme.", nameof(uri));
        return new FileInfo(uri.LocalPath);
    }

    internal static FileInfo NewFileInfo(string parent, string child)
    {
        ArgumentNullException.ThrowIfNull(parent);
        ArgumentNullException.ThrowIfNull(child);
        return new FileInfo(Path.Combine(parent, child));
    }

    internal static bool FileCanWrite(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        try
        {
            if (Directory.Exists(file.FullName))
            {
                var probe = Path.Combine(file.FullName, $".dripsharp-write-{Guid.NewGuid():N}.tmp");
                using (new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
                File.Delete(probe);
                return true;
            }
            if (!file.Exists) return false;
            using var stream = new FileStream(
                file.FullName, FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    internal static void WriterWriteCharCode(TextWriter writer, int value)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.Write(unchecked((char)value));
    }

    internal static bool FileEquals(FileInfo file, object? other)
    {
        ArgumentNullException.ThrowIfNull(file);
        return other is FileInfo candidate &&
            string.Equals(
                file.ToString(),
                candidate.ToString(),
                OperatingSystem.IsWindows()
                    ? StringComparison.OrdinalIgnoreCase
                    : StringComparison.Ordinal);
    }

    internal static bool ArrayEquals<T>(T[]? left, T[]? right) =>
        ReferenceEquals(left, right) ||
        (left is not null && right is not null && left.AsSpan().SequenceEqual(right));

    internal static IDictionary<string, string> GetEnvironment() =>
        Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? string.Empty,
                          StringComparer.Ordinal);

    internal static IDictionary<string, string> GetProperties() =>
        new Dictionary<string, string>(SystemProperties, StringComparer.Ordinal);

    internal static string? GetProperty(string name) =>
        SystemProperties.TryGetValue(name, out var value) ? value : null;

    internal static string GetProperty(string name, string fallback) =>
        GetProperty(name) ?? fallback;

    internal static T Clone<T>(T value) =>
        value is TimeZoneInfo or string
            ? value
            : value is ICloneable cloneable
                ? (T)cloneable.Clone()!
                : throw new NotSupportedException(
                    $"Java clone is not available for destination type {value!.GetType()}.");

    internal static string? SetProperty(string name, string value)
    {
        var previous = GetProperty(name);
        SystemProperties[name] = value;
        return previous;
    }

    private static void ValidateJavaUriText(string value)
    {
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (current <= 0x20 || current == 0x7f || current == '^')
                throw new JavaUriSyntaxException(value, "Illegal character in path", index);
            if (current != '%') continue;
            if (index + 2 >= value.Length ||
                !Uri.IsHexDigit(value[index + 1]) || !Uri.IsHexDigit(value[index + 2]))
                throw new JavaUriSyntaxException(value, "Malformed escape pair", index);
            index += 2;
        }
    }

    internal static Uri CreateUri(string value)
    {
        ValidateJavaUriText(value);
        if (Regex.IsMatch(value, @"(?i)^file:[^/]"))
        {
            // System.Uri rejects Java's opaque `file:path` form before the
            // translated file reader can apply its purpose-built diagnostic.
            // Keep a valid CLR carrier while retaining the Java URI spelling
            // and opaque semantics through the compatibility accessors.
            var opaqueFile = new Uri("file:///" + value["file:".Length..], UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(opaqueFile, _ => new JavaUriText(value));
            return opaqueFile;
        }
        if (Regex.IsMatch(value, @"(?i)^file:/[^/]"))
        {
            var singleSlash = new Uri("file:///" + value["file:/".Length..], UriKind.Absolute);
            _ = SingleSlashFileUris.GetValue(singleSlash, _ => new object());
            return singleSlash;
        }
        if (Regex.IsMatch(value, @"(?i)^file:///[a-z]:$"))
        {
            var driveOnly = new Uri(value + "/", UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(driveOnly, _ => new JavaUriText(value));
            return driveOnly;
        }
        if (!value.StartsWith("file:", StringComparison.OrdinalIgnoreCase) &&
            Regex.IsMatch(value, @"^[A-Za-z][A-Za-z0-9+.-]*:///"))
        {
            // java.net.URI accepts an absolute hierarchical URI with an empty
            // authority (for example, `http:///path`). System.Uri rejects the
            // same spelling because HTTP requires a host. Preserve the Java
            // text and its empty authority on an otherwise valid CLR carrier;
            // the URI accessors below read the preserved spelling.
            var authority = value.IndexOf(":///", StringComparison.Ordinal);
            var carrier = new Uri(
                value[..(authority + 3)] + "dripsharp.invalid/" + value[(authority + 4)..],
                UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(carrier, _ => new JavaUriText(value));
            return carrier;
        }
        if (Regex.IsMatch(value, @"(?i)(?:^|/)%2e(?:%2e)?(?:/|$)"))
        {
            var options = new UriCreationOptions
            {
                DangerousDisablePathAndQueryCanonicalization = true
            };
            return new Uri(value, in options);
        }
        return new Uri(value, UriKind.RelativeOrAbsolute);
    }
    internal static string NewString(char[] value) => new(value);
    internal static string NewString(char[] value, int offset, int count) => new(value, offset, count);
    internal static string NewString(int[] codePoints, int offset, int count) =>
        string.Concat(codePoints.Skip(offset).Take(count).Select(CodePointToString));
    internal static string NewString(sbyte[] value, Encoding encoding) =>
        DecodeJavaBytes(value.Select(item => unchecked((byte)item)).ToArray(), encoding);
    internal static string NewString(sbyte[] value, int offset, int count, Encoding encoding) =>
        DecodeJavaBytes(
            value.Skip(offset).Take(count).Select(item => unchecked((byte)item)).ToArray(),
            encoding);
    internal static string NewString(byte[] value, Encoding encoding) =>
        DecodeJavaBytes(value, encoding);
    internal static string NewString(object value) => StringValueOf(value);

    private static string DecodeJavaBytes(byte[] value, Encoding encoding)
    {
        if (!ReferenceEquals(encoding, JavaStandardCharsets.UTF16))
            return encoding.GetString(value);
        if (value.Length >= 2 && value[0] == 0xfe && value[1] == 0xff)
            return Encoding.BigEndianUnicode.GetString(value, 2, value.Length - 2);
        if (value.Length >= 2 && value[0] == 0xff && value[1] == 0xfe)
            return Encoding.Unicode.GetString(value, 2, value.Length - 2);
        return Encoding.BigEndianUnicode.GetString(value);
    }

    internal static Uri NewUri(string value) => CreateUri(value);
    internal static string UriToString(Uri value) =>
        OriginalUriTexts.TryGetValue(value, out var original)
            ? original.Value
            : SingleSlashFileUris.TryGetValue(value, out _) && value.IsAbsoluteUri && value.IsFile
            ? "file:" + value.AbsolutePath + value.Query + value.Fragment
            : value.IsAbsoluteUri && value.IsFile &&
              !value.OriginalString.StartsWith("file:", StringComparison.OrdinalIgnoreCase)
            // Idiomatic .NET callers commonly construct a file URI directly
            // from an absolute path. System.Uri keeps that bare path as its
            // OriginalString even though the URI's scheme is `file`; Java's
            // URI.toString() carrier must expose the scheme for allowlist and
            // other URI-pattern behavior. Explicit Java URI spellings were
            // handled by the preserved-text branches above.
            ? value.AbsoluteUri
            : value.OriginalString;

    internal static bool UriUsesSingleSlashFileSyntax(Uri value) =>
        SingleSlashFileUris.TryGetValue(value, out _);

    private static bool IsUriUnreserved(char value) =>
        value is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or
            '-' or '.' or '_' or '~';

    private static string QuoteUriComponent(string value, string allowedPunctuation)
    {
        StringBuilder? result = null;
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (IsUriUnreserved(current) || allowedPunctuation.Contains(current) ||
                (current > 0x7f && !char.IsControl(current) && !char.IsWhiteSpace(current)))
            {
                result?.Append(current);
                continue;
            }

            result ??= new StringBuilder(value.Length + 8).Append(value, 0, index);
            foreach (var octet in Encoding.UTF8.GetBytes(new[] { current }))
                result.Append('%').Append(octet.ToString("X2", CultureInfo.InvariantCulture));
        }
        return result?.ToString() ?? value;
    }

    internal static Uri NewUri(string? scheme, string? schemeSpecificPart, string? fragment)
    {
        var text = (scheme is null ? string.Empty : scheme + ":") +
                   QuoteUriComponent(schemeSpecificPart ?? string.Empty, ":/?[]@!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }
    internal static UriFormatException NewUriSyntaxException(string input, string reason) =>
        new JavaUriSyntaxException(input, reason);
    internal static UriFormatException NewUriSyntaxException(string input, string reason, int index) =>
        new JavaUriSyntaxException(input, reason, index);
    internal static string UriSyntaxReason(UriFormatException error) =>
        error is JavaUriSyntaxException syntax ? syntax.Reason : error.Message;
    internal static int UriSyntaxIndex(UriFormatException error) =>
        error is JavaUriSyntaxException syntax ? syntax.Index : -1;
    internal static string UriSyntaxInput(UriFormatException error)
    {
        if (error is JavaUriSyntaxException syntax) return syntax.InputText;
        var separator = error.Message.LastIndexOf(": ", StringComparison.Ordinal);
        return separator >= 0 ? error.Message[(separator + 2)..] : error.Message;
    }
    internal static IOException NewIOException() => new();
    internal static IOException NewIOException(string? message) => new(message);
    internal static IOException NewIOException(Exception cause) => new(cause.Message, cause);
    internal static IOException NewIOException(string? message, Exception? cause) => new(message, cause);
    internal static FileNotFoundException NewFileNotFoundException() => new JavaFileNotFoundException();
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
        => NewUri(scheme, null, host, -1, path, null, fragment);

    internal static Uri NewUri(string? scheme, string? userInfo, string? host, int port,
        string? path, string? query, string? fragment)
    {
        if (host is null && (userInfo is not null || port != -1))
            throw new UriFormatException("User info and port require a URI host.");
        var text = scheme is null ? string.Empty : scheme + ":";
        if (host is not null)
        {
            text += "//";
            if (userInfo is not null)
                text += QuoteUriComponent(userInfo, ":!$&'()*+,;=") + "@";
            text += host;
            if (port != -1) text += ":" + port.ToString(CultureInfo.InvariantCulture);
        }
        text += QuoteUriComponent(path ?? string.Empty, ":@/!$&'()*+,;=");
        if (query is not null)
            text += "?" + QuoteUriComponent(query, ":@/?!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }

    private static string UriTextBeforeFragment(Uri uri)
    {
        var text = OriginalUriTexts.TryGetValue(uri, out var original)
            ? original.Value
            : uri.OriginalString;
        var fragment = text.IndexOf('#');
        return fragment < 0 ? text : text[..fragment];
    }

    internal static string? UriScheme(Uri uri) => uri.IsAbsoluteUri ? uri.Scheme : null;

    private static string? DecodeUriComponent(string? value) =>
        value is null ? null : Uri.UnescapeDataString(value);

    internal static string? UriRawSchemeSpecificPart(Uri uri)
    {
        var text = UriTextBeforeFragment(uri);
        var colon = text.IndexOf(':');
        return colon < 0 ? text : text[(colon + 1)..];
    }

    internal static string? UriSchemeSpecificPart(Uri uri) =>
        DecodeUriComponent(UriRawSchemeSpecificPart(uri));

    internal static string? UriRawFragment(Uri uri)
    {
        var text = uri.OriginalString;
        var marker = text.IndexOf('#');
        return marker < 0 ? null : text[(marker + 1)..];
    }

    internal static string? UriFragment(Uri uri) => DecodeUriComponent(UriRawFragment(uri));

    internal static string? UriRawQuery(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var marker = schemeSpecificPart.IndexOf('?');
        return marker < 0 ? null : schemeSpecificPart[(marker + 1)..];
    }

    internal static string? UriQuery(Uri uri) => DecodeUriComponent(UriRawQuery(uri));

    internal static string? UriRawAuthority(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        if (!schemeSpecificPart.StartsWith("//", StringComparison.Ordinal)) return null;
        var end = schemeSpecificPart.Length;
        var slash = schemeSpecificPart.IndexOf('/', 2);
        var query = schemeSpecificPart.IndexOf('?', 2);
        if (slash >= 0) end = Math.Min(end, slash);
        if (query >= 0) end = Math.Min(end, query);
        return end == 2 ? null : schemeSpecificPart[2..end];
    }

    internal static string? UriAuthority(Uri uri) => DecodeUriComponent(UriRawAuthority(uri));

    internal static string? UriHost(Uri uri)
    {
        if (OriginalUriTexts.TryGetValue(uri, out _) && UriRawAuthority(uri) is null)
            return null;
        return uri.IsAbsoluteUri && !string.IsNullOrEmpty(uri.Host) ? uri.Host : null;
    }

    internal static string? UriRawUserInfo(Uri uri)
    {
        var authority = UriRawAuthority(uri);
        if (authority is null) return null;
        var marker = authority.LastIndexOf('@');
        return marker < 0 ? null : authority[..marker];
    }

    internal static string? UriUserInfo(Uri uri) => DecodeUriComponent(UriRawUserInfo(uri));

    internal static int UriPort(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return -1;
        var authority = UriRawAuthority(uri);
        if (authority is null) return -1;
        var userInfo = authority.LastIndexOf('@');
        if (userInfo >= 0) authority = authority[(userInfo + 1)..];
        var closeBracket = authority.LastIndexOf(']');
        var colon = authority.LastIndexOf(':');
        return colon > closeBracket && int.TryParse(authority[(colon + 1)..], out var port) ? port : -1;
    }

    internal static string? UriRawPath(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var query = schemeSpecificPart.IndexOf('?');
        var pathEnd = query < 0 ? schemeSpecificPart.Length : query;
        if (schemeSpecificPart.StartsWith("//", StringComparison.Ordinal))
        {
            var pathStart = schemeSpecificPart.IndexOf('/', 2);
            return pathStart < 0 || pathStart >= pathEnd
                ? string.Empty
                : schemeSpecificPart[pathStart..pathEnd];
        }
        return schemeSpecificPart[..pathEnd];
    }

    internal static string? UriPath(Uri uri) => DecodeUriComponent(UriRawPath(uri));

    internal static Uri ResolveUri(Uri basis, string value) => ResolveUri(basis, CreateUri(value));
    internal static Uri ResolveUri(Uri basis, Uri value)
    {
        if (value.IsAbsoluteUri) return value;
        // java.net.URI.resolve("") resolves to the base URI's containing
        // directory; System.Uri otherwise preserves the base file itself.
        if (value.OriginalString.Length == 0) value = CreateUri(".");
        if (OriginalUriTexts.TryGetValue(basis, out var originalBasis) &&
            Regex.IsMatch(originalBasis.Value, @"(?i)^file:///[a-z]:$") &&
            value.OriginalString == ".")
            return new Uri("file:///", UriKind.Absolute);
        // java.net.URI.resolve leaves a relative reference relative when the
        // base URI is opaque. System.Uri instead interprets it as a new opaque
        // scheme-specific part (for example, `repl:foo.config`).
        if (basis.IsAbsoluteUri && UriIsOpaque(basis)) return value;
        if (basis.IsAbsoluteUri) return new Uri(basis, value);
        var basisText = basis.OriginalString;
        var rooted = basisText.StartsWith("/", StringComparison.Ordinal);
        var dummyBasis = new Uri("https://dripsharp.invalid/" + basisText.TrimStart('/'));
        var resolved = new Uri(dummyBasis, value);
        var text = resolved.PathAndQuery + resolved.Fragment;
        if (!rooted) text = text.TrimStart('/');
        return new Uri(text, UriKind.Relative);
    }
    internal static Uri ResolveLocalDependencyUri(Uri basis, Uri value)
    {
        var resolved = ResolveUri(basis, value);
        if (resolved.IsAbsoluteUri && resolved.IsFile)
            _ = SingleSlashFileUris.GetValue(resolved, _ => new object());
        return resolved;
    }
    internal static Uri NormalizeUri(Uri uri) => uri;
    internal static Uri RelativizeUri(Uri basis, Uri value)
    {
        if (!basis.IsAbsoluteUri || !value.IsAbsoluteUri ||
            !string.Equals(UriScheme(basis), UriScheme(value), StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(UriRawAuthority(basis), UriRawAuthority(value), StringComparison.Ordinal))
            return value;
        var basePath = UriRawPath(basis) ?? string.Empty;
        var valuePath = UriRawPath(value) ?? string.Empty;
        if (!valuePath.StartsWith(basePath, StringComparison.Ordinal)) return value;
        var relative = valuePath[basePath.Length..];
        var query = UriRawQuery(value);
        var fragment = UriRawFragment(value);
        if (query is not null) relative += "?" + query;
        if (fragment is not null) relative += "#" + fragment;
        return CreateUri(relative);
    }
    internal static bool UriIsOpaque(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return false;
        var original = OriginalUriTexts.TryGetValue(uri, out var preserved)
            ? preserved.Value
            : uri.OriginalString;
        var colon = original.IndexOf(':');
        return colon >= 0 && (colon + 1 == original.Length || original[colon + 1] != '/');
    }
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

    internal static V Unbox<V>(V? value) where V : struct =>
        value ?? throw new NullReferenceException("Cannot unbox a null Java boxed value.");

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

    private const int JavaRegexUnixLines = 0x01;
    private const int JavaRegexCaseInsensitive = 0x02;
    private const int JavaRegexComments = 0x04;
    private const int JavaRegexMultiline = 0x08;
    private const int JavaRegexLiteral = 0x10;
    private const int JavaRegexDotAll = 0x20;
    private const int JavaRegexUnicodeCase = 0x40;
    private const int JavaRegexCanonEq = 0x80;
    private const int JavaRegexUnicodeCharacterClass = 0x100;
    private const int JavaRegexAllFlags = 0x1ff;

    private sealed class JavaCodePointSet
    {
        private readonly List<(int Start, int End)> ranges;

        private JavaCodePointSet(IEnumerable<(int Start, int End)> source)
        {
            ranges = new List<(int Start, int End)>();
            foreach (var range in source.OrderBy(value => value.Start).ThenBy(value => value.End))
            {
                if (range.End < range.Start) continue;
                var start = Math.Max(0, range.Start);
                var end = Math.Min(0x10ffff, range.End);
                if (start > end) continue;
                if (ranges.Count != 0 && start <= ranges[^1].End + 1)
                {
                    var previous = ranges[^1];
                    ranges[^1] = (previous.Start, Math.Max(previous.End, end));
                }
                else
                {
                    ranges.Add((start, end));
                }
            }
        }

        internal static JavaCodePointSet Empty { get; } = new(Array.Empty<(int, int)>());
        internal static JavaCodePointSet All { get; } = new(new[] { (0, 0x10ffff) });
        internal static JavaCodePointSet Range(int start, int end) => new(new[] { (start, end) });

        internal static JavaCodePointSet FromPredicate(Func<int, bool> predicate)
        {
            var result = new List<(int Start, int End)>();
            var start = -1;
            for (var codePoint = 0; codePoint <= 0x10ffff; codePoint++)
            {
                var included = codePoint is < 0xd800 or > 0xdfff && predicate(codePoint);
                if (included && start < 0) start = codePoint;
                if (!included && start >= 0)
                {
                    result.Add((start, codePoint - 1));
                    start = -1;
                }
            }
            if (start >= 0) result.Add((start, 0x10ffff));
            return new JavaCodePointSet(result);
        }

        internal bool TrySingle(out int codePoint)
        {
            if (ranges.Count == 1 && ranges[0].Start == ranges[0].End)
            {
                codePoint = ranges[0].Start;
                return true;
            }
            codePoint = -1;
            return false;
        }

        internal bool Contains(int codePoint)
        {
            var lower = 0;
            var upper = ranges.Count - 1;
            while (lower <= upper)
            {
                var middle = lower + (upper - lower) / 2;
                if (codePoint < ranges[middle].Start) upper = middle - 1;
                else if (codePoint > ranges[middle].End) lower = middle + 1;
                else return true;
            }
            return false;
        }

        internal static JavaCodePointSet Parse(string value)
        {
            if (value.Length == 0) return Empty;
            return new JavaCodePointSet(value.Split(',').Select(encoded =>
            {
                var separator = encoded.IndexOf('-');
                var start = int.Parse(separator < 0 ? encoded : encoded[..separator],
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
                var end = separator < 0 ? start : int.Parse(encoded[(separator + 1)..],
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
                return (start, end);
            }));
        }

        internal JavaCodePointSet Union(JavaCodePointSet other) => new(ranges.Concat(other.ranges));

        internal JavaCodePointSet CaseFold(bool unicode)
        {
            var result = this;
            if (!unicode)
            {
                for (var lower = 'a'; lower <= 'z'; lower++)
                {
                    var upper = char.ToUpperInvariant(lower);
                    if (Contains(lower) || Contains(upper))
                        result = result.Union(Range(lower, lower)).Union(Range(upper, upper));
                }
                return result;
            }
            foreach (var mapping in JavaRegexUnicode.Value.CaseFolds)
            {
                if (Contains(mapping.Upper) || Contains(mapping.Folded))
                    result = result.Union(Range(mapping.Candidate, mapping.Candidate));
            }
            return result;
        }

        internal JavaCodePointSet Intersect(JavaCodePointSet other)
        {
            var result = new List<(int, int)>();
            var left = 0;
            var right = 0;
            while (left < ranges.Count && right < other.ranges.Count)
            {
                var start = Math.Max(ranges[left].Start, other.ranges[right].Start);
                var end = Math.Min(ranges[left].End, other.ranges[right].End);
                if (start <= end) result.Add((start, end));
                if (ranges[left].End < other.ranges[right].End) left++;
                else right++;
            }
            return new JavaCodePointSet(result);
        }

        internal JavaCodePointSet Except(JavaCodePointSet other)
        {
            var result = new List<(int, int)>();
            foreach (var source in ranges)
            {
                var cursor = source.Start;
                foreach (var removed in other.ranges)
                {
                    if (removed.End < cursor) continue;
                    if (removed.Start > source.End) break;
                    if (removed.Start > cursor) result.Add((cursor, removed.Start - 1));
                    cursor = Math.Max(cursor, removed.End + 1);
                    if (cursor > source.End) break;
                }
                if (cursor <= source.End) result.Add((cursor, source.End));
            }
            return new JavaCodePointSet(result);
        }

        internal JavaCodePointSet Complement() => All.Except(this);

        private static string Unit(int value) => $"\\u{value:X4}";
        private static string UnitRange(int start, int end) =>
            start == end ? Unit(start) : "[" + Unit(start) + "-" + Unit(end) + "]";

        internal string ToRegex()
        {
            if (ranges.Count == 0) return "(?!)";
            var bmp = new List<(int Start, int End)>();
            var astral = new List<string>();
            foreach (var range in ranges)
            {
                if (range.Start <= 0xffff)
                {
                    var bmpEnd = Math.Min(range.End, 0xffff);
                    if (range.Start < 0xd800)
                        bmp.Add((range.Start, Math.Min(bmpEnd, 0xd7ff)));
                    if (bmpEnd >= 0xd800 && range.Start <= 0xdbff)
                        astral.Add(UnitRange(Math.Max(range.Start, 0xd800), Math.Min(bmpEnd, 0xdbff)) +
                                    "(?![\\uDC00-\\uDFFF])");
                    if (bmpEnd >= 0xdc00 && range.Start <= 0xdfff)
                        astral.Add("(?<![\\uD800-\\uDBFF])" +
                                    UnitRange(Math.Max(range.Start, 0xdc00), Math.Min(bmpEnd, 0xdfff)));
                    if (bmpEnd >= 0xe000)
                        bmp.Add((Math.Max(range.Start, 0xe000), bmpEnd));
                }
                if (range.End <= 0xffff) continue;
                var start = Math.Max(range.Start, 0x10000);
                var startHigh = char.ConvertFromUtf32(start)[0];
                var startLow = char.ConvertFromUtf32(start)[1];
                var endHigh = char.ConvertFromUtf32(range.End)[0];
                var endLow = char.ConvertFromUtf32(range.End)[1];
                if (startHigh == endHigh)
                {
                    astral.Add(Unit(startHigh) + "[" + Unit(startLow) + "-" + Unit(endLow) + "]");
                    continue;
                }
                astral.Add(Unit(startHigh) + "[" + Unit(startLow) + "-\\uDFFF]");
                if (startHigh + 1 <= endHigh - 1)
                    astral.Add("[" + Unit(startHigh + 1) + "-" + Unit(endHigh - 1) + "][\\uDC00-\\uDFFF]");
                astral.Add(Unit(endHigh) + "[\\uDC00-" + Unit(endLow) + "]");
            }

            var alternatives = new List<string>(astral);
            if (bmp.Count != 0)
            {
                var builder = new StringBuilder("[");
                foreach (var range in bmp)
                {
                    builder.Append(Unit(range.Start));
                    if (range.End != range.Start) builder.Append('-').Append(Unit(range.End));
                }
                alternatives.Add(builder.Append(']').ToString());
            }
            return alternatives.Count == 1
                ? alternatives[0]
                : "(?:" + string.Join("|", alternatives) + ")";
        }
    }

    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, JavaCodePointSet>
        JavaRegexPropertySets = new(StringComparer.Ordinal);

    private sealed class JavaRegexUnicodeDatabase
    {
        internal Dictionary<string, JavaCodePointSet> Blocks { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> AlgorithmicNameBlocks { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> Scripts { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> Properties { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, int> Names { get; } = new(StringComparer.Ordinal);
        internal List<(int Candidate, int Upper, int Folded)> CaseFolds { get; } = new();
        internal JavaCodePointSet[] GraphemeTypes { get; } =
            Enumerable.Repeat(JavaCodePointSet.Empty, 15).ToArray();
        internal Dictionary<string, JavaCodePointSet> IndicConjunct { get; } = new(StringComparer.Ordinal);

        internal JavaRegexUnicodeDatabase()
        {
            var compressed = Convert.FromBase64String(JavaRegexUnicodeData.GzipBase64);
            using var input = new MemoryStream(compressed, writable: false);
            using var gzip = new GZipStream(input, CompressionMode.Decompress);
            using var reader = new StreamReader(gzip, Encoding.UTF8, false, 65536);
            while (reader.ReadLine() is { } line)
            {
                var fields = line.Split('\t');
                if (fields.Length != 3) continue;
                switch (fields[0])
                {
                    case "B": Blocks[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "A": AlgorithmicNameBlocks[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "S": Scripts[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "P": Properties[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "N": Names[fields[1]] = int.Parse(fields[2], NumberStyles.AllowHexSpecifier,
                        CultureInfo.InvariantCulture); break;
                    case "G": GraphemeTypes[int.Parse(fields[1], CultureInfo.InvariantCulture)] =
                        JavaCodePointSet.Parse(fields[2]); break;
                    case "I": IndicConjunct[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "F": {
                        var values = fields[2].Split(',');
                        CaseFolds.Add((
                            int.Parse(fields[1], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture),
                            int.Parse(values[0], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture),
                            int.Parse(values[1], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture)));
                        break;
                    }
                }
            }
        }
    }

    private static readonly Lazy<JavaRegexUnicodeDatabase> JavaRegexUnicode =
        new(() => new JavaRegexUnicodeDatabase());

    private static string NormalizeJavaRegexPropertyName(string value) =>
        new(value.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    private static int JavaRegexNamedCodePoint(string rawName)
    {
        var name = rawName.Trim().ToUpperInvariant();
        var database = JavaRegexUnicode.Value;
        if (database.Names.TryGetValue(name, out var named)) return named;

        var separator = Math.Max(name.LastIndexOf(' '), name.LastIndexOf('-'));
        if (separator > 0 && int.TryParse(name[(separator + 1)..], NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var codePoint) && codePoint <= 0x10ffff)
        {
            var prefix = NormalizeJavaRegexPropertyName(name[..separator]);
            if (database.AlgorithmicNameBlocks.TryGetValue(prefix, out var block) && block.Contains(codePoint))
                return codePoint;
        }
        throw new ArgumentException("Unknown character name [" + rawName + "]");
    }

    private static string JavaGraphemeClusterPattern()
    {
        var data = JavaRegexUnicode.Value;
        string Type(int index) => data.GraphemeTypes[index].ToRegex();
        string Either(params string[] values) => "(?:" + string.Join("|", values) + ")";

        var cr = Type(1);
        var lf = Type(2);
        var control = Either(cr, lf, Type(3));
        var extend = Type(4);
        var zwj = Type(5);
        var regionalIndicator = Type(6);
        var prepend = Type(7);
        var spacingMark = Type(8);
        var l = Type(9);
        var v = Type(10);
        var t = Type(11);
        var lv = Type(12);
        var lvt = Type(13);
        var pictographic = Type(14);
        var noBreak = Either(extend, zwj, spacingMark);
        var hangul = Either(
            l + "+(?:(?:" + v + "|" + lv + ")" + v + "*" + t + "*|" + lvt + t + "*)?",
            "(?:" + lv + v + "*|" + v + "+)" + t + "*",
            "(?:" + lvt + "|" + t + ")" + t + "*");
        var emojiMarks = Either(extend, spacingMark);
        var emoji = pictographic + emojiMarks + "*(?:" + zwj + "+" + pictographic +
            emojiMarks + "*)*";
        var indicConsonant = data.IndicConjunct["consonant"].ToRegex();
        var indicExtend = data.IndicConjunct["extend"].ToRegex();
        var indicLinker = data.IndicConjunct["linker"].ToRegex();
        var indicPart = Either(indicExtend, indicLinker);
        var indic = indicConsonant + "(?:" + indicPart + "*" + indicLinker + indicPart + "*" +
            indicConsonant + ")+";
        var ordinary = Either(Type(0), extend, zwj, spacingMark);
        var core = Either(indic, emoji, regionalIndicator + regionalIndicator + "?", hangul, ordinary);
        return Either(cr + lf, control, prepend + "*" + core + noBreak + "*", prepend + "+");
    }

    private static UnicodeCategory UnicodeCategoryOf(int codePoint) =>
        Rune.GetUnicodeCategory(new Rune(codePoint));

    private static bool IsUnicodeWhitespace(int codePoint) =>
        codePoint is >= 0x0009 and <= 0x000d or 0x0020 or 0x0085 or 0x00a0 or 0x1680 or
            >= 0x2000 and <= 0x200a or 0x2028 or 0x2029 or 0x202f or 0x205f or 0x3000;

    private static bool IsJavaWhitespace(int codePoint)
    {
        if (codePoint is >= 0x0009 and <= 0x000d or >= 0x001c and <= 0x001f) return true;
        if (codePoint is 0x00a0 or 0x2007 or 0x202f) return false;
        return UnicodeCategoryOf(codePoint) is UnicodeCategory.SpaceSeparator or
            UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator;
    }

    private static bool IsUnicodeWord(int codePoint) =>
        codePoint is 0x200c or 0x200d || UnicodeCategoryOf(codePoint) is
            UnicodeCategory.UppercaseLetter or UnicodeCategory.LowercaseLetter or
            UnicodeCategory.TitlecaseLetter or UnicodeCategory.ModifierLetter or
            UnicodeCategory.OtherLetter or UnicodeCategory.NonSpacingMark or
            UnicodeCategory.SpacingCombiningMark or UnicodeCategory.EnclosingMark or
            UnicodeCategory.DecimalDigitNumber or UnicodeCategory.LetterNumber or
            UnicodeCategory.ConnectorPunctuation;

    private static JavaCodePointSet JavaRegexPropertySet(
        string rawName, bool unicodeClasses, bool caseInsensitive)
    {
        var cacheKey = (unicodeClasses ? "U:" : "A:") + (caseInsensitive ? "I:" : "S:") + rawName;
        return JavaRegexPropertySets.GetOrAdd(cacheKey, _ =>
        {
            var database = JavaRegexUnicode.Value;
            var equals = rawName.IndexOf('=');
            if (equals >= 0)
            {
                var property = NormalizeJavaRegexPropertyName(rawName[..equals]);
                var value = NormalizeJavaRegexPropertyName(rawName[(equals + 1)..]);
                var selected = property switch
                {
                    "sc" or "script" => database.Scripts.GetValueOrDefault(value),
                    "blk" or "block" => database.Blocks.GetValueOrDefault(value),
                    "gc" or "generalcategory" => RegexUnicodeProperty(value),
                    _ => null
                };
                return selected ?? throw new ArgumentException(
                    "Unknown Unicode property {name=<" + rawName[..equals] + ">, value=<" +
                    rawName[(equals + 1)..] + ">}");
            }

            if (rawName.StartsWith("In", StringComparison.Ordinal))
            {
                var block = NormalizeJavaRegexPropertyName(rawName[2..]);
                return database.Blocks.GetValueOrDefault(block) ?? throw new ArgumentException(
                    "Unknown character property name {" + rawName + "}");
            }
            if (rawName.StartsWith("Is", StringComparison.Ordinal))
            {
                var value = NormalizeJavaRegexPropertyName(rawName[2..]);
                return RegexUnicodeProperty(value) ?? database.Scripts.GetValueOrDefault(value) ??
                    throw new ArgumentException("Unknown character property name {" + rawName + "}");
            }

            var normalized = NormalizeJavaRegexPropertyName(rawName);
            return RegexUnicodeProperty(normalized) ?? throw new ArgumentException(
                "Unknown character property name {" + rawName + "}");

            JavaCodePointSet? RegexUnicodeProperty(string value)
            {
                var key = (unicodeClasses ? "u" : "a") + (caseInsensitive ? "i" : "s") + value;
                return database.Properties.GetValueOrDefault(key);
            }
        });
    }

    private sealed class JavaRegexTranslator
    {
        private readonly string pattern;
        private int index;
        private readonly List<string> groupNames = new() { string.Empty };
        private readonly Dictionary<string, string> namedGroups = new(StringComparer.Ordinal);

        internal JavaRegexTranslator(string pattern) => this.pattern = pattern;
        internal string[] GroupNames => groupNames.ToArray();
        internal IReadOnlyDictionary<string, string> NamedGroups => namedGroups;

        internal string Translate(int flags)
        {
            var mode = EffectiveFlags(flags);
            var result = TranslateSequence(ref mode, false);
            if (index != pattern.Length) throw new ArgumentException("Unexpected ')' near index " + index);
            return result;
        }

        private static int EffectiveFlags(int flags) =>
            (flags & JavaRegexUnicodeCharacterClass) != 0 ? flags | JavaRegexUnicodeCase : flags;

        private void SkipIgnored(int mode)
        {
            if ((mode & JavaRegexComments) == 0) return;
            while (index < pattern.Length)
            {
                if (char.IsWhiteSpace(pattern[index]))
                {
                    index++;
                    continue;
                }
                if (pattern[index] != '#') break;
                while (index < pattern.Length && pattern[index] is not '\n' and not '\r') index++;
            }
        }

        private string TranslateSequence(ref int mode, bool closesAtParenthesis)
        {
            var result = new StringBuilder();
            while (true)
            {
                SkipIgnored(mode);
                if (index == pattern.Length)
                {
                    if (closesAtParenthesis) throw new ArgumentException("Unclosed group near index " + index);
                    break;
                }
                if (pattern[index] == ')')
                {
                    if (!closesAtParenthesis) break;
                    index++;
                    break;
                }
                if (pattern[index] == '|')
                {
                    result.Append('|');
                    index++;
                    continue;
                }

                var atom = pattern[index] == '(' ? TranslateGroup(ref mode) : TranslateAtom(mode);
                if (atom is null) continue;
                result.Append(TranslateQuantifier(atom, mode));
            }
            return result.ToString();
        }

        private string? TranslateGroup(ref int mode)
        {
            index++;
            if (index >= pattern.Length || pattern[index] != '?')
            {
                var groupName = "j" + groupNames.Count.ToString(CultureInfo.InvariantCulture);
                groupNames.Add(groupName);
                var nestedMode = mode;
                return "(?<" + groupName + ">" + TranslateSequence(ref nestedMode, true) + ")";
            }

            index++;
            if (index >= pattern.Length) throw new ArgumentException("Unknown inline modifier near index " + index);
            if (pattern[index] == '<')
            {
                if (index + 1 < pattern.Length && pattern[index + 1] is '=' or '!')
                {
                    var prefix = pattern[index + 1] == '=' ? "(?<=" : "(?<!";
                    index += 2;
                    var lookbehindMode = mode;
                    return prefix + TranslateSequence(ref lookbehindMode, true) + ")";
                }
                var end = pattern.IndexOf('>', index + 1);
                if (end < 0) throw new ArgumentException("named capturing group is missing trailing '>'");
                var javaName = pattern[(index + 1)..end];
                if (javaName.Length == 0 || !char.IsAsciiLetter(javaName[0]) ||
                    javaName.Skip(1).Any(character => !char.IsAsciiLetterOrDigit(character)))
                    throw new ArgumentException("capturing group name does not start with a Latin letter");
                if (namedGroups.ContainsKey(javaName))
                    throw new ArgumentException("Named capturing group <" + javaName + "> is already defined");
                index = end + 1;
                var groupName = "j" + groupNames.Count.ToString(CultureInfo.InvariantCulture);
                groupNames.Add(groupName);
                namedGroups.Add(javaName, groupName);
                var namedGroupMode = mode;
                return "(?<" + groupName + ">" + TranslateSequence(ref namedGroupMode, true) + ")";
            }
            if (pattern[index] is ':' or '=' or '!' or '>')
            {
                var marker = pattern[index++];
                var prefix = marker switch
                {
                    ':' => "(?:",
                    '=' => "(?=",
                    '!' => "(?!",
                    _ => "(?>"
                };
                var nestedMode = mode;
                return prefix + TranslateSequence(ref nestedMode, true) + ")";
            }

            var changedMode = mode;
            var enable = true;
            var sawFlag = false;
            while (index < pattern.Length)
            {
                if (pattern[index] == '-')
                {
                    enable = false;
                    index++;
                    continue;
                }
                var flag = pattern[index] switch
                {
                    'd' => JavaRegexUnixLines,
                    'i' => JavaRegexCaseInsensitive,
                    'm' => JavaRegexMultiline,
                    's' => JavaRegexDotAll,
                    'u' => JavaRegexUnicodeCase,
                    'x' => JavaRegexComments,
                    'U' => JavaRegexUnicodeCharacterClass,
                    _ => 0
                };
                if (flag == 0) break;
                sawFlag = true;
                index++;
                if (enable) changedMode |= flag;
                else changedMode &= ~flag;
                changedMode = EffectiveFlags(changedMode);
            }
            if (!sawFlag || index >= pattern.Length || pattern[index] is not ')' and not ':')
                throw new ArgumentException("Unknown inline modifier near index " + index);
            if (pattern[index++] == ')')
            {
                mode = changedMode;
                return null;
            }
            return "(?:" + TranslateSequence(ref changedMode, true) + ")";
        }

        private string TranslateAtom(int mode)
        {
            var current = pattern[index++];
            return current switch
            {
                '[' => ParseClass(mode).ToRegex(),
                '.' => Dot(mode),
                '^' => StartAnchor(mode),
                '$' => EndAnchor(mode),
                '\\' => TranslateEscape(mode),
                '*' or '+' or '?' => throw new ArgumentException("Dangling meta character '" + current + "' near index " + (index - 1)),
                _ => Literal(ReadCodePoint(current), mode)
            };
        }

        private int ReadCodePoint(char first)
        {
            if (char.IsHighSurrogate(first) && index < pattern.Length && char.IsLowSurrogate(pattern[index]))
                return char.ConvertToUtf32(first, pattern[index++]);
            return first;
        }

        private string TranslateQuantifier(string atom, int mode)
        {
            SkipIgnored(mode);
            if (index >= pattern.Length) return atom;
            string? quantifier = null;
            if (pattern[index] is '?' or '*' or '+')
            {
                quantifier = pattern[index++].ToString();
            }
            else if (pattern[index] == '{')
            {
                var match = Regex.Match(pattern[index..], @"^\{\d+(?:,\d*)?\}");
                if (match.Success)
                {
                    quantifier = match.Value;
                    index += match.Length;
                }
            }
            if (quantifier is null) return atom;
            // A Java regex atom can translate to more than one .NET regex atom.
            // Supplementary code points are the important example: .NET regexes
            // operate on UTF-16 units, so their translated surrogate pair must
            // remain one unit when Java applies a quantifier.
            var quantified = "(?:" + atom + ")" + quantifier;
            SkipIgnored(mode);
            if (index < pattern.Length && pattern[index] == '?')
            {
                index++;
                return quantified + "?";
            }
            if (index < pattern.Length && pattern[index] == '+')
            {
                index++;
                return "(?>" + quantified + ")";
            }
            return quantified;
        }

        private string TranslateEscape(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Unexpected internal error near index " + index);
            var escaped = pattern[index++];
            return escaped switch
            {
                'Q' => Quoted(mode),
                'E' => throw new ArgumentException("Illegal/unsupported escape sequence near index " + (index - 1)),
                'd' => PredefinedClass('d', mode).ToRegex(),
                'D' => PredefinedClass('d', mode).Complement().ToRegex(),
                's' => PredefinedClass('s', mode).ToRegex(),
                'S' => PredefinedClass('s', mode).Complement().ToRegex(),
                'w' => PredefinedClass('w', mode).ToRegex(),
                'W' => PredefinedClass('w', mode).Complement().ToRegex(),
                'h' => PredefinedClass('h', mode).ToRegex(),
                'H' => PredefinedClass('h', mode).Complement().ToRegex(),
                'v' => PredefinedClass('v', mode).ToRegex(),
                'V' => PredefinedClass('v', mode).Complement().ToRegex(),
                'p' => ParseProperty(mode, false).ToRegex(),
                'P' => ParseProperty(mode, true).ToRegex(),
                'A' => "\\A",
                'G' => "\\G",
                'Z' => FinalTerminatorAnchor(mode),
                'z' => "\\z",
                'R' => "(?:\\r\\n|[\\n\\u000B\\f\\r\\u0085\\u2028\\u2029])",
                'X' => JavaGraphemeClusterPattern(),
                'b' when index + 2 < pattern.Length && pattern.Substring(index, 3) == "{g}" => GraphemeBoundary(),
                'b' => WordBoundary(mode, false),
                'B' => WordBoundary(mode, true),
                'k' => NamedBackReference(mode),
                '0' => Octal(mode),
                'x' => HexEscape(mode),
                'u' => FixedHexEscape(4, mode),
                'N' => NamedCharacter(mode),
                't' => Literal('\t', mode),
                'n' => Literal('\n', mode),
                'r' => Literal('\r', mode),
                'f' => Literal('\f', mode),
                'a' => Literal('\a', mode),
                'e' => Literal(0x1b, mode),
                'c' => ControlEscape(mode),
                >= '1' and <= '9' => NumericBackReference(escaped, mode),
                _ when char.IsAsciiLetter(escaped) => throw new ArgumentException(
                    "Illegal/unsupported escape sequence near index " + (index - 1)),
                _ => Literal(escaped, mode)
            };
        }

        private string Quoted(int mode)
        {
            var end = pattern.IndexOf("\\E", index, StringComparison.Ordinal);
            if (end < 0) end = pattern.Length;
            var value = pattern[index..end];
            index = end == pattern.Length ? end : end + 2;
            var result = new StringBuilder();
            foreach (var rune in value.EnumerateRunes()) result.Append(Literal(rune.Value, mode));
            return "(?:" + result + ")";
        }

        private string NamedBackReference(int mode)
        {
            if (index >= pattern.Length || pattern[index++] != '<')
                throw new ArgumentException("named capturing group is missing trailing '>'");
            var end = pattern.IndexOf('>', index);
            if (end < 0) throw new ArgumentException("named capturing group is missing trailing '>'");
            var name = pattern[index..end];
            index = end + 1;
            if (!namedGroups.TryGetValue(name, out var translated))
                throw new ArgumentException("named capturing group <" + name + "> does not exist");
            return CaseScope("\\k<" + translated + ">", mode);
        }

        private string NumericBackReference(char first, int mode)
        {
            var number = first - '0';
            while (index < pattern.Length && char.IsAsciiDigit(pattern[index]))
            {
                var candidate = checked(number * 10 + pattern[index] - '0');
                if (candidate >= groupNames.Count) break;
                number = candidate;
                index++;
            }
            if (number >= groupNames.Count) return "(?!)";
            return CaseScope("\\k<" + groupNames[number] + ">", mode);
        }

        private string Octal(int mode)
        {
            var value = 0;
            var digits = 0;
            while (digits < 3 && index < pattern.Length && pattern[index] is >= '0' and <= '7' &&
                   (digits < 2 || value <= 0x1f))
            {
                value = value * 8 + pattern[index++] - '0';
                digits++;
            }
            if (digits == 0) throw new ArgumentException("Illegal octal escape sequence near index " + index);
            return Literal(value, mode);
        }

        private string HexEscape(int mode)
        {
            if (index < pattern.Length && pattern[index] == '{')
            {
                var end = pattern.IndexOf('}', ++index);
                if (end < 0) throw new ArgumentException("Unclosed hexadecimal escape sequence near index " + index);
                var digits = pattern[index..end];
                index = end + 1;
                if (!int.TryParse(digits, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var codePoint) ||
                    codePoint > 0x10ffff)
                    throw new ArgumentException("Hexadecimal codepoint is too big near index " + index);
                return Literal(codePoint, mode);
            }
            return FixedHexEscape(2, mode);
        }

        private string FixedHexEscape(int digits, int mode)
        {
            if (index + digits > pattern.Length ||
                !int.TryParse(pattern.Substring(index, digits), NumberStyles.AllowHexSpecifier,
                              CultureInfo.InvariantCulture, out var value))
                throw new ArgumentException("Illegal hexadecimal escape sequence near index " + index);
            index += digits;
            return Literal(value, mode);
        }

        private string NamedCharacter(int mode)
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Illegal character name escape sequence near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character name escape sequence near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            return Literal(JavaRegexNamedCodePoint(name), mode);
        }

        private string ControlEscape(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Illegal control escape sequence near index " + index);
            return Literal(pattern[index++] ^ 64, mode);
        }

        private JavaCodePointSet ParseClass(int mode)
        {
            var negate = index < pattern.Length && pattern[index] == '^';
            if (negate) index++;
            var result = ParseClassUnion(mode, true);
            while (index + 1 < pattern.Length && pattern[index] == '&' && pattern[index + 1] == '&')
            {
                index += 2;
                result = result.Intersect(ParseClassUnion(mode, false));
            }
            if (index >= pattern.Length || pattern[index++] != ']')
                throw new ArgumentException("Unclosed character class near index " + index);
            return negate ? result.Complement() : result;
        }

        private JavaCodePointSet ParseClassUnion(int mode, bool first)
        {
            var result = JavaCodePointSet.Empty;
            var items = 0;
            while (index < pattern.Length)
            {
                SkipIgnored(mode);
                if (index >= pattern.Length) break;
                if (pattern[index] == ']' && !(first && items == 0)) break;
                if (index + 1 < pattern.Length && pattern[index] == '&' && pattern[index + 1] == '&') break;
                var item = ParseClassAtom(mode);
                if (index < pattern.Length && pattern[index] == '-' &&
                    index + 1 < pattern.Length && pattern[index + 1] != ']')
                {
                    index++;
                    var end = ParseClassAtom(mode);
                    if (!item.TrySingle(out var start) || !end.TrySingle(out var finish) || finish < start)
                        throw new ArgumentException("Illegal character range near index " + index);
                    item = JavaCodePointSet.Range(start, finish);
                }
                if ((mode & JavaRegexCaseInsensitive) != 0)
                    item = item.CaseFold((mode & JavaRegexUnicodeCase) != 0);
                result = result.Union(item);
                items++;
                first = false;
            }
            return result;
        }

        private JavaCodePointSet ParseClassAtom(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Unclosed character class near index " + index);
            var current = pattern[index++];
            if (current == '[') return ParseClass(mode);
            if (current != '\\')
            {
                var codePoint = ReadCodePoint(current);
                return JavaCodePointSet.Range(codePoint, codePoint);
            }
            if (index >= pattern.Length) throw new ArgumentException("Unclosed character class near index " + index);
            var escaped = pattern[index++];
            return escaped switch
            {
                'd' => PredefinedClass('d', mode),
                'D' => PredefinedClass('d', mode).Complement(),
                's' => PredefinedClass('s', mode),
                'S' => PredefinedClass('s', mode).Complement(),
                'w' => PredefinedClass('w', mode),
                'W' => PredefinedClass('w', mode).Complement(),
                'h' => PredefinedClass('h', mode),
                'H' => PredefinedClass('h', mode).Complement(),
                'v' => PredefinedClass('v', mode),
                'V' => PredefinedClass('v', mode).Complement(),
                'p' => ParseProperty(mode, false),
                'P' => ParseProperty(mode, true),
                'b' => JavaCodePointSet.Range('\b', '\b'),
                't' => JavaCodePointSet.Range('\t', '\t'),
                'n' => JavaCodePointSet.Range('\n', '\n'),
                'r' => JavaCodePointSet.Range('\r', '\r'),
                'f' => JavaCodePointSet.Range('\f', '\f'),
                'a' => JavaCodePointSet.Range('\a', '\a'),
                'e' => JavaCodePointSet.Range(0x1b, 0x1b),
                '0' => ParseClassOctalSet(),
                'x' => ParseClassHex(),
                'u' => ParseClassFixedHex(4),
                'N' => ParseClassNamedCharacter(),
                'Q' => ParseClassQuoted(),
                'c' => ParseClassControl(),
                _ when char.IsAsciiLetter(escaped) => throw new ArgumentException(
                    "Illegal/unsupported escape sequence near index " + (index - 1)),
                _ => JavaCodePointSet.Range(escaped, escaped)
            };
        }

        private int ParseClassOctal()
        {
            var value = 0;
            var digits = 0;
            while (digits < 3 && index < pattern.Length && pattern[index] is >= '0' and <= '7' &&
                   (digits < 2 || value <= 0x1f))
            {
                value = value * 8 + pattern[index++] - '0';
                digits++;
            }
            if (digits == 0) throw new ArgumentException("Illegal octal escape sequence near index " + index);
            return value;
        }

        private JavaCodePointSet ParseClassOctalSet()
        {
            var value = ParseClassOctal();
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassHex()
        {
            if (index < pattern.Length && pattern[index] == '{')
            {
                var end = pattern.IndexOf('}', ++index);
                if (end < 0) throw new ArgumentException("Unclosed hexadecimal escape sequence near index " + index);
                var digits = pattern[index..end];
                index = end + 1;
                if (!int.TryParse(digits, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var codePoint) ||
                    codePoint > 0x10ffff)
                    throw new ArgumentException("Hexadecimal codepoint is too big near index " + index);
                return JavaCodePointSet.Range(codePoint, codePoint);
            }
            return ParseClassFixedHex(2);
        }

        private JavaCodePointSet ParseClassFixedHex(int digits)
        {
            if (index + digits > pattern.Length ||
                !int.TryParse(pattern.Substring(index, digits), NumberStyles.AllowHexSpecifier,
                              CultureInfo.InvariantCulture, out var value))
                throw new ArgumentException("Illegal hexadecimal escape sequence near index " + index);
            index += digits;
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassControl()
        {
            if (index >= pattern.Length) throw new ArgumentException("Illegal control escape sequence near index " + index);
            var value = pattern[index++] ^ 64;
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassNamedCharacter()
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Illegal character name escape sequence near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character name escape sequence near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            var codePoint = JavaRegexNamedCodePoint(name);
            return JavaCodePointSet.Range(codePoint, codePoint);
        }

        private JavaCodePointSet ParseClassQuoted()
        {
            var end = pattern.IndexOf("\\E", index, StringComparison.Ordinal);
            if (end < 0) end = pattern.Length;
            var result = JavaCodePointSet.Empty;
            while (index < end)
            {
                var codePoint = ReadCodePoint(pattern[index++]);
                result = result.Union(JavaCodePointSet.Range(codePoint, codePoint));
            }
            index = end == pattern.Length ? end : end + 2;
            return result;
        }

        private JavaCodePointSet ParseProperty(int mode, bool negate)
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Unknown character property name near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character family near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            var set = JavaRegexPropertySet(
                name,
                (mode & JavaRegexUnicodeCharacterClass) != 0,
                (mode & JavaRegexCaseInsensitive) != 0);
            return negate ? set.Complement() : set;
        }

        private static JavaCodePointSet PredefinedClass(char kind, int mode)
        {
            var unicode = (mode & JavaRegexUnicodeCharacterClass) != 0;
            return kind switch
            {
                'd' when unicode => JavaRegexPropertySet("Digit", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                'd' => JavaCodePointSet.Range('0', '9'),
                's' when unicode => JavaRegexPropertySet("Space", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                's' => JavaCodePointSet.Range('\t', '\r').Union(JavaCodePointSet.Range(' ', ' ')),
                'w' when unicode => JavaRegexPropertySet("Word", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                'w' => JavaCodePointSet.Range('0', '9').Union(JavaCodePointSet.Range('A', 'Z'))
                    .Union(JavaCodePointSet.Range('_', '_')).Union(JavaCodePointSet.Range('a', 'z')),
                'h' => JavaRegexPropertySets.GetOrAdd("fixed:h", _ => JavaCodePointSet.FromPredicate(codePoint =>
                    codePoint is '\t' or 0x20 or 0xa0 or 0x1680 or 0x180e or
                        >= 0x2000 and <= 0x200a or 0x202f or 0x205f or 0x3000)),
                'v' => JavaCodePointSet.Range('\n', '\r').Union(JavaCodePointSet.Range(0x85, 0x85))
                    .Union(JavaCodePointSet.Range(0x2028, 0x2029)),
                _ => throw new ArgumentException("Unknown predefined character class")
            };
        }

        internal static string Literal(int codePoint, int mode)
        {
            if ((mode & JavaRegexCanonEq) != 0 && codePoint is < 0xd800 or > 0xdfff)
            {
                var source = char.ConvertFromUtf32(codePoint);
                var normalized = source.Normalize(NormalizationForm.FormD);
                if (!string.Equals(source, normalized, StringComparison.Ordinal))
                {
                    var result = new StringBuilder();
                    foreach (var rune in normalized.EnumerateRunes())
                        result.Append(Literal(rune.Value, mode & ~JavaRegexCanonEq));
                    return "(?:" + result + ")";
                }
            }
            var literal = JavaCodePointSet.Range(codePoint, codePoint);
            if ((mode & JavaRegexCaseInsensitive) != 0)
                literal = literal.CaseFold((mode & JavaRegexUnicodeCase) != 0);
            return literal.ToRegex();
        }

        private static string CaseScope(string value, int mode) =>
            (mode & (JavaRegexCaseInsensitive | JavaRegexUnicodeCase)) ==
                (JavaRegexCaseInsensitive | JavaRegexUnicodeCase)
                ? "(?i:" + value + ")"
                : value;

        private static string Dot(int mode)
        {
            var excluded = (mode & JavaRegexDotAll) != 0
                ? JavaCodePointSet.Empty
                : (mode & JavaRegexUnixLines) != 0
                    ? JavaCodePointSet.Range('\n', '\n')
                    : JavaCodePointSet.Range('\n', '\r').Union(JavaCodePointSet.Range(0x85, 0x85))
                        .Union(JavaCodePointSet.Range(0x2028, 0x2029));
            return excluded.Complement().ToRegex();
        }

        private static string StartAnchor(int mode)
        {
            if ((mode & JavaRegexMultiline) == 0) return "\\A";
            return (mode & JavaRegexUnixLines) != 0
                ? "(?:\\A|(?<=\\n)(?!\\z))"
                : "(?:\\A|(?<=\\n)(?!\\z)|(?<=\\r)(?!\\n)(?!\\z)|(?<=[\\u0085\\u2028\\u2029])(?!\\z))";
        }

        private static string EndAnchor(int mode)
        {
            if ((mode & JavaRegexMultiline) == 0) return FinalTerminatorAnchor(mode);
            return (mode & JavaRegexUnixLines) != 0
                ? "(?=\\n|\\z)"
                : "(?=\\r\\n|\\r(?!\\n)|(?<!\\r)\\n|[\\u0085\\u2028\\u2029]|\\z)";
        }

        private static string FinalTerminatorAnchor(int mode) =>
            (mode & JavaRegexUnixLines) != 0
                ? "(?=\\n?\\z)"
                : "(?=\\r\\n\\z|[\\n\\r\\u0085\\u2028\\u2029]?\\z)";

        private static string WordBoundary(int mode, bool negate)
        {
            var word = PredefinedClass('w', mode).ToRegex();
            var boundary = "(?:(?<=" + word + ")(?!(?:" + word + "))|(?<!" + word + ")(?=" + word + "))";
            return negate ? "(?!(?:" + boundary + "))" : boundary;
        }

        private string GraphemeBoundary()
        {
            index += 3;
            return "(?:(?=\\A)|(?=\\z)|(?<![\\p{M}\\u200D])(?=[^\\p{M}]))";
        }
    }

    private static string TranslateJavaRegex(string pattern)
    {
        var translator = new JavaRegexTranslator(pattern);
        return translator.Translate(0);
    }

    private static string JavaRegexSyntaxMessage(string pattern, ArgumentException error)
    {
        if (pattern.StartsWith('*') || pattern.StartsWith('+') || pattern.StartsWith('?'))
            return $"Dangling meta character '{pattern[0]}' near index 0\n{pattern}\n^";

        var depth = 0;
        var escaped = false;
        foreach (var current in pattern)
        {
            if (escaped)
            {
                escaped = false;
                continue;
            }
            if (current == '\\')
            {
                escaped = true;
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
        }
        if (depth > 0) return $"Unclosed group near index {pattern.Length}\n{pattern}";
        return error.Message;
    }

    private static Regex CompileRegexCore(string pattern, int flags)
    {
        if ((flags & ~JavaRegexAllFlags) != 0)
            throw new ArgumentException("Unknown flag 0x" + (flags & ~JavaRegexAllFlags).ToString("x", CultureInfo.InvariantCulture));
        try
        {
            var effectiveFlags = (flags & JavaRegexUnicodeCharacterClass) != 0
                ? flags | JavaRegexUnicodeCase
                : flags;
            string translated;
            string[] groupNames;
            IReadOnlyDictionary<string, string> namedGroups;
            if ((flags & JavaRegexLiteral) != 0)
            {
                var literal = new StringBuilder(pattern.Length);
                for (var index = 0; index < pattern.Length; index++)
                {
                    var codePoint = char.IsHighSurrogate(pattern[index]) && index + 1 < pattern.Length &&
                        char.IsLowSurrogate(pattern[index + 1])
                        ? char.ConvertToUtf32(pattern[index], pattern[++index])
                        : pattern[index];
                    literal.Append(JavaRegexTranslator.Literal(codePoint, effectiveFlags));
                }
                translated = literal.ToString();
                groupNames = new[] { string.Empty };
                namedGroups = new Dictionary<string, string>();
            }
            else
            {
                var translator = new JavaRegexTranslator(pattern);
                translated = translator.Translate(effectiveFlags);
                groupNames = translator.GroupNames;
                namedGroups = translator.NamedGroups;
            }
            var options = RegexOptions.CultureInvariant;
            var result = new JavaRegex(pattern, translated, options, effectiveFlags, groupNames, namedGroups);
            _ = OriginalRegexPatterns.GetValue(result, _ => new JavaUriText(pattern));
            return result;
        }
        catch (ArgumentException error)
        {
            throw new ArgumentException(JavaRegexSyntaxMessage(pattern, error), error);
        }
    }

    internal static Regex CompileRegex(string pattern) => CompileRegexCore(pattern, 0);
    internal static Regex CompileRegex(string pattern, int flags) => CompileRegexCore(pattern, flags);
    internal static Regex CompileLiteralRegex(string pattern) =>
        CompileRegexCore(pattern, JavaRegexLiteral | JavaRegexUnicodeCase);
    internal static string RegexPattern(Regex pattern) =>
        OriginalRegexPatterns.TryGetValue(pattern, out var original)
            ? original.Value
            : pattern.ToString();
    internal static int RegexFlags(Regex pattern) => pattern is JavaRegex javaRegex ? javaRegex.Flags : 0;
    internal static int RegexGroupCount(Regex pattern) => pattern is JavaRegex javaRegex
        ? javaRegex.GroupNames.Length - 1
        : pattern.GetGroupNumbers().Length - 1;
    internal static string RegexGroupName(Regex pattern, int group)
    {
        if (pattern is not JavaRegex javaRegex) return group.ToString(CultureInfo.InvariantCulture);
        if (group < 0 || group >= javaRegex.GroupNames.Length)
            throw new ArgumentOutOfRangeException(nameof(group), "No group " + group);
        return group == 0 ? "0" : javaRegex.GroupNames[group];
    }
    internal static string RegexGroupName(Regex pattern, string group)
    {
        if (pattern is not JavaRegex javaRegex) return group;
        return javaRegex.NamedGroups.TryGetValue(group, out var translated)
            ? translated
            : throw new ArgumentException("No group with name <" + group + ">");
    }
    internal static string QuoteRegex(string value) => "\\Q" + value.Replace("\\E", "\\E\\\\E\\Q", StringComparison.Ordinal) + "\\E";
    internal static JavaRegexMatcher RegexMatcher(Regex pattern, string input) => new(pattern, input);
    internal static string[] RegexSplit(Regex pattern, string input, int limit)
    {
        var result = new List<string>();
        var matcher = new JavaRegexMatcher(pattern, input);
        var start = 0;
        var matched = false;
        while ((limit <= 0 || result.Count < limit - 1) && matcher.Find())
        {
            var matchStart = matcher.Start();
            var matchEnd = matcher.End();
            if (matchStart == 0 && matchEnd == 0) continue;
            matched = true;
            result.Add(input.Substring(start, matchStart - start));
            start = matchEnd;
        }
        if (!matched) return new[] { input };
        result.Add(input.Substring(start));
        if (limit == 0)
        {
            while (result.Count != 0 && result[^1].Length == 0) result.RemoveAt(result.Count - 1);
        }
        return result.ToArray();
    }
    internal static string QuoteReplacement(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("$", "\\$", StringComparison.Ordinal);
    internal static string Encode(string value, Encoding encoding) => Uri.EscapeDataString(value);

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
    internal static ISet<T> EnumSetOf<T>(T value) => new HashSet<T> { value };
    internal static ISet<T> EnumSetCopyOf<T>(IEnumerable<T> values) => new HashSet<T>(values);

    internal static ReadOnlyCollection<T> UnmodifiableList<T>(IEnumerable<T> values) =>
        new(values is IList<T> list ? list : values.ToList());
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
    internal static IDictionary<TKey, TValue> NewJavaDictionary<TKey, TValue>(params object?[] arguments)
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
    internal static decimal BigDecimalParse(string value) =>
        decimal.Parse(
            value,
            NumberStyles.Number | NumberStyles.AllowExponent,
            CultureInfo.InvariantCulture);

    internal static decimal BigDecimalValueOf(double value) =>
        BigDecimalParse(value.ToString("R", CultureInfo.InvariantCulture));

    internal static decimal BigDecimalSetScale(
        decimal value,
        int scale,
        JavaRoundingMode roundingMode)
    {
        if (scale is < 0 or > 28) throw new ArithmeticException("Scale is outside System.Decimal range.");
        return roundingMode switch
        {
            JavaRoundingMode.Ceiling => decimal.Round(
                value,
                scale,
                MidpointRounding.ToPositiveInfinity),
            _ => throw new ArgumentOutOfRangeException(nameof(roundingMode))
        };
    }

    internal static int BigDecimalIntValue(decimal value) =>
        decimal.ToInt32(decimal.Truncate(value));

    internal static decimal BigDecimalStripTrailingZeros(decimal value)
    {
        if (value == 0) return decimal.Zero;
        return decimal.Parse(
            value.ToString("G29", CultureInfo.InvariantCulture),
            NumberStyles.Number,
            CultureInfo.InvariantCulture);
    }

    internal static string BigDecimalToPlainString(decimal value) =>
        value.ToString(CultureInfo.InvariantCulture);

    internal static string? XmlEncoding(XmlDocument document) =>
        document.FirstChild is XmlDeclaration declaration &&
        !string.IsNullOrEmpty(declaration.Encoding)
            ? declaration.Encoding
            : null;

    internal static string? XmlInputEncoding(XmlDocument document) =>
        XmlEncoding(document);

    internal static decimal DecimalDivide(decimal left, decimal right, int scale, object rounding)
    {
        var rounded = decimal.Round(
            left / right,
            scale,
            string.Equals(rounding.ToString(), "DOWN", StringComparison.Ordinal)
                ? MidpointRounding.ToZero
                : MidpointRounding.ToEven);
        // BigDecimal.toString() retains the requested division scale. Reparse a fixed-point
        // representation so System.Decimal carries the same scale in its value bits.
        return decimal.Parse(
            rounded.ToString("F" + scale, System.Globalization.CultureInfo.InvariantCulture),
            System.Globalization.NumberStyles.Number,
            System.Globalization.CultureInfo.InvariantCulture);
    }
    internal static IEnumerable<T> Filter<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Where(predicate);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values) =>
        values.OrderBy(value => value, Comparer<T>.Create(JavaCompare));
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, IComparer<T> comparer) => values.OrderBy(value => value, comparer);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, Comparison<T> comparison) =>
        values.OrderBy(value => value, Comparer<T>.Create(comparison));
    internal static Comparison<T> NaturalOrder<T>() => JavaCompare;
    internal static Comparison<T> ComparingInt<T>(Func<T, int> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> Comparing<T>(Func<T, IComparable> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> ThenComparing<T>(Comparison<T> first, Comparison<T> second) =>
        (left, right) => { var result = first(left, right); return result != 0 ? result : second(left, right); };
    internal static Comparison<T> ReverseComparison<T>(Comparison<T> comparison) =>
        (left, right) => comparison(right, left);
    internal static T[] ToArray<T>(IEnumerable<T> values) => values.ToArray();
    internal static T[] CollectionToArray<T>(IEnumerable<T> values, T[] target)
    {
        var source = values.ToArray();
        if (target.Length < source.Length) return source;
        Array.Copy(source, target, source.Length);
        if (target.Length > source.Length) target[source.Length] = default!;
        return target;
    }
    internal static T[] ToArrayLoose<T>(System.Collections.IEnumerable values) =>
        values.Cast<object?>().Select(value => (T)value!).ToArray();
    internal static IList<T> ToListValues<T>(IEnumerable<T> values) => values.ToList();
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
    internal static void OutputStreamWrite(Stream stream, sbyte[] values) =>
        stream.Write(ToUnsignedBytes(values));
    internal static void OutputStreamWrite(Stream stream, sbyte[] values, int offset, int count)
    {
        var buffer = new byte[count];
        for (var index = 0; index < count; index++)
            buffer[index] = unchecked((byte)values[offset + index]);
        stream.Write(buffer, 0, buffer.Length);
    }
    internal static void OutputStreamWrite(Stream stream, int value) =>
        stream.WriteByte(unchecked((byte)value));
    internal static void OutputStreamWrite(JavaDataOutputStream stream, sbyte[] values) =>
        stream.write(values);
    internal static void OutputStreamWrite(
        JavaDataOutputStream stream,
        sbyte[] values,
        int offset,
        int count) =>
        stream.write(values, offset, count);
    internal static bool InputStreamMarkSupported(Stream stream) => stream.CanSeek;
    internal static void InputStreamMark(Stream stream, int _)
    {
        if (stream.CanSeek) StreamMarks.GetOrCreateValue(stream).Position = stream.Position;
    }
    internal static void InputStreamReset(Stream stream)
    {
        if (!stream.CanSeek || !StreamMarks.TryGetValue(stream, out var mark))
            throw new IOException("Stream mark is not available.");
        stream.Position = mark.Position;
    }
    internal static long InputStreamSkip(Stream stream, long count)
    {
        if (count <= 0) return 0;
        if (stream.CanSeek)
        {
            var available = Math.Max(0, stream.Length - stream.Position);
            var skipped = Math.Min(available, count);
            stream.Position += skipped;
            return skipped;
        }
        var buffer = new byte[8192];
        long total = 0;
        while (total < count)
        {
            var read = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, count - total));
            if (read == 0) break;
            total += read;
        }
        return total;
    }
    internal static int InputStreamRead(Stream stream) => stream.ReadByte();
    internal static int InputStreamRead(Stream stream, sbyte[] values) =>
        InputStreamRead(stream, values, 0, values.Length);
    internal static int InputStreamRead(Stream stream, sbyte[] values, int offset, int count)
    {
        if (count == 0) return 0;
        var buffer = new byte[count];
        var read = stream.Read(buffer, 0, count);
        if (read == 0) return -1;
        for (var index = 0; index < read; index++)
            values[offset + index] = unchecked((sbyte)buffer[index]);
        return read;
    }
    internal static void MemoryStreamWriteTo(MemoryStream source, Stream destination)
    {
        if (!source.TryGetBuffer(out var contents))
            contents = new ArraySegment<byte>(source.ToArray());
        destination.Write(contents.AsSpan(0, checked((int)source.Length)));
    }
    internal static void RegisterSocketStream(System.Net.Sockets.Socket socket, Stream stream) =>
        SocketStreams.Add(socket, stream);
    internal static void RegisterPendingSocketFactory(
        System.Net.Sockets.Socket socket,
        JavaSocketFactory factory) => PendingSocketFactories.Add(socket, factory);
    internal static System.Net.IPAddress InetSocketAddressAddress(
        System.Net.IPEndPoint endpoint) => endpoint.Address;
    internal static Stream SocketStream(System.Net.Sockets.Socket socket)
    {
        if (SocketStreams.TryGetValue(socket, out var stream)) return stream;
        if (!PendingSocketFactories.TryGetValue(socket, out var factory))
            return new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
        stream = factory.OpenStream(socket);
        PendingSocketFactories.Remove(socket);
        SocketStreams.Add(socket, stream);
        return stream;
    }
    internal static bool SocketIsClosed(System.Net.Sockets.Socket socket) =>
        socket.SafeHandle.IsClosed;
    internal static bool SocketIsConnected(System.Net.Sockets.Socket socket) => socket.Connected;
    internal static void SocketSetSoTimeout(System.Net.Sockets.Socket socket, int timeout) =>
        socket.ReceiveTimeout = timeout;
    internal static void ForEach<T>(IEnumerable<T> values, Action<T> action)
    {
        foreach (var value in values) action(value);
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
    internal static IEnumerable<T> Stream<T>(IEnumerable<T> values) => values;
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

    internal static bool Exists(string path) => File.Exists(path) || Directory.Exists(path);
    internal static bool IsDirectory(string path) => Directory.Exists(path);
    internal static bool FileCanRead(FileInfo file)
    {
        try
        {
            if (Directory.Exists(file.FullName)) return true;
            using var stream = File.Open(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            return stream.CanRead;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
        catch (IOException)
        {
            return false;
        }
    }
    internal static bool FileIsHidden(FileInfo file) =>
        file.Name.StartsWith(".", StringComparison.Ordinal) ||
        (file.Exists && (file.Attributes & FileAttributes.Hidden) != 0);
    internal static FileInfo[] FileListFiles(FileInfo directory) =>
        Directory.Exists(directory.FullName)
            ? Directory.EnumerateFileSystemEntries(directory.FullName)
                .Select(path => new FileInfo(path))
                .ToArray()
            : Array.Empty<FileInfo>();
    internal static Uri FileToUri(FileInfo file) => new(file.FullName);
    internal static void DeleteIfExists(string path)
    {
        if (File.Exists(path)) File.Delete(path);
        else if (Directory.Exists(path)) Directory.Delete(path);
    }
    internal static void CreateDirectories(string path) => Directory.CreateDirectory(path);
    internal static FileStream NewInputStream(string path, params object?[] _) => OpenFileRead(path);
    internal static string ReadString(string path) => File.ReadAllText(path, Encoding.UTF8);
    internal static string ReadString(string path, Encoding encoding)
    {
        if (Directory.Exists(path)) throw new IOException("Is a directory");
        return File.ReadAllText(path, encoding);
    }
    internal static string PathOf(string first, params string[] more)
    {
        // Path.of(first, more...) joins name elements even when a later string
        // begins with a platform separator. Path.Combine instead discards the
        // prefix for such strings, which can move translated cache paths out of
        // their intended root.
        var result = first;
        foreach (var value in more)
            result = Path.Join(result, value.TrimStart(Path.DirectorySeparatorChar,
                                                       Path.AltDirectorySeparatorChar));
        return result;
    }
    internal static string PathOfUri(Uri uri) =>
        uri.IsFile ? Uri.UnescapeDataString(uri.AbsolutePath) : uri.OriginalString;
    internal static bool PathIsAbsolute(string path) => Path.IsPathRooted(path);
    internal static string? PathRoot(string path) => Path.GetPathRoot(path);
    internal static string PathRelativize(string basis, string path) => Path.GetRelativePath(basis, path);
    internal static string PathResolve(string basis, string value) => Path.Combine(basis, value);
    internal static string PathResolveSibling(string basis, string value) =>
        Path.Combine(Path.GetDirectoryName(basis) ?? string.Empty, value);
    internal static string NormalizePath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        return Path.IsPathRooted(path)
            ? fullPath
            : Path.GetRelativePath(Environment.CurrentDirectory, fullPath);
    }
    internal static string RealPath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        var root = Path.GetPathRoot(fullPath) ??
            throw new IOException($"Path `{path}` has no filesystem root.");
        var current = root;
        var remainder = fullPath[root.Length..];
        foreach (var segment in remainder.Split(
                     new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar },
                     StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, segment);
            FileSystemInfo info = Directory.Exists(current)
                ? new DirectoryInfo(current)
                : new FileInfo(current);
            if (!info.Exists)
                throw new NoSuchFileException(path);
            if ((info.Attributes & FileAttributes.ReparsePoint) == 0) continue;
            var target = info.ResolveLinkTarget(returnFinalTarget: true) ??
                throw new IOException($"Cannot resolve symbolic link `{current}`.");
            current = Path.GetFullPath(target.FullName);
        }
        return Path.GetFullPath(current);
    }
    internal static bool PathStartsWith(string path, string basis)
    {
        var candidate = Path.GetFullPath(path);
        var root = Path.GetFullPath(basis);
        var comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (string.Equals(candidate, root, comparison)) return true;
        var relative = Path.GetRelativePath(root, candidate);
        return !Path.IsPathRooted(relative) &&
               !string.Equals(relative, "..", comparison) &&
               !relative.StartsWith(".." + Path.DirectorySeparatorChar, comparison) &&
               !relative.StartsWith(".." + Path.AltDirectorySeparatorChar, comparison);
    }
    internal static int PathNameCount(string path) => path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
        .Count(segment => !string.IsNullOrEmpty(segment));
    internal static string PathName(string path, int index) =>
        path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(segment => !string.IsNullOrEmpty(segment)).ElementAt(index);
    internal static int CompareUri(Uri left, Uri right) => string.CompareOrdinal(left.OriginalString, right.OriginalString);
    internal static Stream OpenStream(Uri uri) =>
        uri.IsFile
            ? OpenFileRead(PathOfUri(uri))
            : new System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
    internal static Stream OpenInputStream(string path) => OpenFileRead(path);

    internal static long FileLength(string path) => File.Exists(path)
        ? new FileInfo(path).Length
        : 0L;

    internal static Stream OpenUrlStream(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        return uri.IsFile
            ? OpenFileRead(uri.LocalPath)
            : UrlClient.GetStreamAsync(uri).GetAwaiter().GetResult();
    }

    internal static string UrlDecode(string value, string encoding)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(encoding);
        if (!string.Equals(encoding, "UTF-8", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(encoding, "UTF8", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException($"Unsupported URL decoder encoding: {encoding}", nameof(encoding));
        return System.Net.WebUtility.UrlDecode(value);
    }
    internal static sbyte[] ReadAllBytes(string path)
    {
        using var stream = OpenFileRead(path);
        return ReadAllBytes(stream);
    }
    private static FileStream OpenFileRead(string path)
    {
        if (Directory.Exists(path)) throw new IOException("Is a directory");
        try
        {
            return File.OpenRead(path);
        }
        catch (DirectoryNotFoundException error)
        {
            throw new FileNotFoundException(error.Message, path, error);
        }
    }
    internal static sbyte[] ReadAllBytes(Stream stream)
    {
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    }
    internal static int InputStreamAvailable(Stream stream) =>
        stream.CanSeek
            ? checked((int)Math.Min(int.MaxValue, Math.Max(0, stream.Length - stream.Position)))
            : 0;
    internal static sbyte[] ReadNBytes(Stream stream, int count)
    {
        var bytes = new byte[count];
        var offset = 0;
        while (offset < count)
        {
            var read = stream.Read(bytes, offset, count - offset);
            if (read == 0) break;
            offset += read;
        }
        return bytes.Take(offset).Select(value => unchecked((sbyte)value)).ToArray();
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes) =>
        new(bytes.Select(value => unchecked((byte)value)).ToArray());
    internal static StringBuilder StringBuilderAppendInvariant(
        StringBuilder builder,
        object value)
    {
        ArgumentNullException.ThrowIfNull(builder);
        if (value is bool boolean) return builder.Append(boolean ? "true" : "false");
        return builder.Append(((IFormattable)value).ToString(null, CultureInfo.InvariantCulture));
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (offset < 0 || length < 0 || offset > bytes.Length - length)
            throw new IndexOutOfRangeException();
        return new MemoryStream(
            bytes.Skip(offset).Take(length).Select(value => unchecked((byte)value)).ToArray());
    }
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
    internal static long Copy(string source, Stream destination)
    {
        using var input = File.OpenRead(source);
        input.CopyTo(destination);
        return input.Length;
    }
    internal static FileStream NewOutputStream(string path, params object?[] _) => File.Create(path);
    internal static StreamWriter NewFileWriter(string path, Encoding encoding) => new(path, false, encoding);
    internal static JavaStream<string> Walk(string path, params object?[] _) =>
        new(Directory.EnumerateFileSystemEntries(path, "*", SearchOption.AllDirectories).Prepend(path));
    internal static JavaStream<string> walk(string path, params object?[] options) =>
        Walk(path, options);
    internal static JavaStream<JavaPath> walk(JavaPath path, params object?[] _) =>
        new(Directory.EnumerateFileSystemEntries(path.Value, "*", SearchOption.AllDirectories)
            .Prepend(path.Value)
            .Select(value => new JavaPath(value)));
    internal static bool PathIsRegularFile(string path) => File.Exists(path);
    internal static ICollection<object> ObjectCollection(IEnumerable<object> values) => values.ToList();
    internal static IDictionary<object, object> ObjectMap(IDictionary values)
    {
        var result = new Dictionary<object, object>();
        foreach (DictionaryEntry entry in values) result[entry.Key] = entry.Value!;
        return result;
    }
    internal static IDictionary<object, object> ObjectMap<K, V>(IDictionary<K, V> values)
        where K : notnull => values.ToDictionary(entry => (object)entry.Key, entry => (object?)entry.Value!);
    internal static void WriterAppend(TextWriter writer, object? value, int start, int end) =>
        writer.Write(StringValueOf(value).AsSpan(start, end - start));
    internal static void SetPosixFilePermissions(string path, ISet<UnixFileMode> permissions)
    {
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, permissions.Aggregate((UnixFileMode)0, (mode, permission) => mode | permission));
    }
    internal static void setPosixFilePermissions(string path, ISet<UnixFileMode> permissions) =>
        SetPosixFilePermissions(path, permissions);
    internal static ISet<UnixFileMode> fromString(string permissions)
    {
        if (permissions.Length != 9)
            throw new ArgumentException("POSIX permissions must contain exactly nine characters.",
                nameof(permissions));
        var result = new HashSet<UnixFileMode>();
        var modes = new[]
        {
            UnixFileMode.UserRead, UnixFileMode.UserWrite, UnixFileMode.UserExecute,
            UnixFileMode.GroupRead, UnixFileMode.GroupWrite, UnixFileMode.GroupExecute,
            UnixFileMode.OtherRead, UnixFileMode.OtherWrite, UnixFileMode.OtherExecute
        };
        for (var index = 0; index < permissions.Length; index++)
        {
            var expected = (index % 3) switch { 0 => 'r', 1 => 'w', _ => 'x' };
            if (permissions[index] == expected) result.Add(modes[index]);
            else if (permissions[index] != '-')
                throw new ArgumentException($"Invalid POSIX permission `{permissions[index]}`.",
                    nameof(permissions));
        }
        return result;
    }
    internal static JavaFileAttribute<ISet<UnixFileMode>> asFileAttribute(
        ISet<UnixFileMode> permissions) => new(permissions);
    internal static string createTempDirectory(
        string prefix, params JavaFileAttribute<ISet<UnixFileMode>>[] attributes)
    {
        var path = Path.Combine(Path.GetTempPath(), prefix + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static string createTempFile(
        string prefix, string suffix,
        params JavaFileAttribute<ISet<UnixFileMode>>[] attributes) =>
        createTempFile(Path.GetTempPath(), prefix, suffix, attributes);
    internal static string createTempFile(
        string directory, string prefix, string suffix,
        params JavaFileAttribute<ISet<UnixFileMode>>[] attributes)
    {
        var path = Path.Combine(directory, prefix + Guid.NewGuid().ToString("N") + suffix);
        using (File.Create(path)) { }
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static JavaAclFileAttributeView? getFileAttributeView(
        string _, Type __, params object?[] ___) =>
        OperatingSystem.IsWindows() ? new JavaAclFileAttributeView() : null;
    internal static bool FileDelete(FileInfo file)
    {
        try
        {
            if (Directory.Exists(file.FullName)) Directory.Delete(file.FullName);
            else if (File.Exists(file.FullName)) File.Delete(file.FullName);
            return true;
        }
        catch
        {
            return false;
        }
    }
    internal static bool FileExists(FileInfo file) =>
        File.Exists(file.FullName) || Directory.Exists(file.FullName);
    internal static bool FileIsDirectory(FileInfo file) => Directory.Exists(file.FullName);
    internal static bool SetFileReadable(FileInfo _, bool __, bool ___) => true;
    internal static bool SetFileWritable(FileInfo _, bool __, bool ___) => true;
    internal static bool SetFileExecutable(FileInfo _, bool __, bool ___) => true;
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

    internal static long DurationToMillis(TimeSpan value) => checked((long)value.TotalMilliseconds);
    internal static long DurationGetSeconds(TimeSpan value) => checked((long)value.TotalSeconds);
    internal static int DurationGetNano(TimeSpan value) => checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
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
    internal static global::System.Net.IPEndPoint NewIpEndPoint(string host, int port) =>
        new(global::System.Net.Dns.GetHostAddresses(host)[0], port);
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
    internal static Uri PathToUri(string path)
    {
        var pathUri = new Uri(Path.GetFullPath(path));
        return new Uri(pathUri.AbsoluteUri);
    }
}

internal
sealed class JavaRegexMatcher
{
    private readonly Regex regex;
    private readonly string input;
    private int regionStart;
    private int regionEnd;
    private int nextIndex;
    private int appendIndex;
    private Match? current;
    private int currentOffset;
    private int[]? currentBoundaryMap;

    internal JavaRegexMatcher(Regex regex, string input)
    {
        this.regex = regex;
        this.input = input;
        regionEnd = input.Length;
    }

    private Match Current() => current ?? throw new InvalidOperationException("No successful match is available");

    private bool Accept(Match match, int offset)
    {
        if (!match.Success)
        {
            current = null;
            return false;
        }
        current = match;
        currentOffset = offset;
        var end = offset + OriginalBoundary(match.Index + match.Length);
        nextIndex = match.Length == 0 ? AdvanceCodePoint(end) : end;
        return true;
    }

    private int AdvanceCodePoint(int position)
    {
        if (position >= regionEnd) return regionEnd + 1;
        // Matcher.find() advances one UTF-16 code unit after an empty match,
        // including into a surrogate pair. Pattern.split therefore exposes
        // the same Java String boundaries for an empty delimiter.
        return position + 1;
    }

    private Match MatchRegion(int absoluteStart)
    {
        var region = input.Substring(regionStart, regionEnd - regionStart);
        var startWithinRegion = Math.Max(0, absoluteStart - regionStart);
        currentBoundaryMap = null;
        if ((JavaCompat.RegexFlags(regex) & 0x80) != 0)
        {
            var normalized = new StringBuilder(region.Length);
            var boundaries = new List<int> { 0 };
            for (var sourceIndex = 0; sourceIndex < region.Length;)
            {
                var sourceLength = char.IsSurrogatePair(region, sourceIndex) ? 2 : 1;
                var unit = region.Substring(sourceIndex, sourceLength).Normalize(NormalizationForm.FormD);
                normalized.Append(unit);
                for (var unitIndex = 1; unitIndex <= unit.Length; unitIndex++)
                    boundaries.Add(unitIndex == unit.Length ? sourceIndex + sourceLength : sourceIndex);
                sourceIndex += sourceLength;
            }
            region = normalized.ToString();
            currentBoundaryMap = boundaries.ToArray();
            startWithinRegion = Array.FindIndex(currentBoundaryMap,
                boundary => boundary >= startWithinRegion);
            if (startWithinRegion < 0) startWithinRegion = region.Length;
        }
        return regex.Match(region, startWithinRegion);
    }

    internal bool Find() => nextIndex <= regionEnd && Accept(MatchRegion(Math.Max(regionStart, nextIndex)), regionStart);
    internal bool Find(int start)
    {
        if (start < 0 || start > input.Length) throw new ArgumentOutOfRangeException(nameof(start));
        current = null;
        nextIndex = start;
        return Find();
    }
    internal bool Matches()
    {
        var match = MatchRegion(regionStart);
        if (!match.Success || match.Index != 0 ||
            OriginalBoundary(match.Index + match.Length) != regionEnd - regionStart)
        {
            current = null;
            return false;
        }
        return Accept(match, regionStart);
    }
    internal bool LookingAt()
    {
        var match = MatchRegion(regionStart);
        if (!match.Success || match.Index != 0)
        {
            current = null;
            return false;
        }
        return Accept(match, regionStart);
    }
    internal JavaRegexMatcher Region(int start, int end)
    {
        if (start < 0 || start > input.Length) throw new ArgumentOutOfRangeException(nameof(start));
        if (end < 0 || end > input.Length) throw new ArgumentOutOfRangeException(nameof(end));
        if (start > end) throw new ArgumentOutOfRangeException(nameof(start), "start > end");
        regionStart = start;
        regionEnd = end;
        nextIndex = start;
        current = null;
        return this;
    }
    private Group CurrentGroup(int index) => Current().Groups[JavaCompat.RegexGroupName(regex, index)];
    private Group CurrentGroup(string name) => Current().Groups[JavaCompat.RegexGroupName(regex, name)];
    private int OriginalBoundary(int normalizedBoundary) => currentBoundaryMap is null
        ? normalizedBoundary
        : currentBoundaryMap[Math.Min(normalizedBoundary, currentBoundaryMap.Length - 1)];
    private int AbsoluteStart(Group group) => currentOffset + OriginalBoundary(group.Index);
    private int AbsoluteEnd(Group group) => currentOffset + OriginalBoundary(group.Index + group.Length);
    private string GroupValue(Group group) => input.Substring(AbsoluteStart(group), AbsoluteEnd(group) - AbsoluteStart(group));
    internal string Group() => GroupValue(Current());
    internal string Group(int index) => CurrentGroup(index).Success ? GroupValue(CurrentGroup(index)) : null!;
    internal string Group(string name) => CurrentGroup(name).Success ? GroupValue(CurrentGroup(name)) : null!;
    internal int GroupCount() => JavaCompat.RegexGroupCount(regex);
    internal int Start() => AbsoluteStart(Current());
    internal int Start(int index) => CurrentGroup(index).Success ? AbsoluteStart(CurrentGroup(index)) : -1;
    internal int End() => AbsoluteEnd(Current());
    internal int End(int index) => CurrentGroup(index).Success
        ? AbsoluteEnd(CurrentGroup(index))
        : -1;
    internal JavaRegexMatcher ToMatchResult()
    {
        var result = new JavaRegexMatcher(regex, input)
        {
            regionStart = regionStart,
            regionEnd = regionEnd,
            nextIndex = nextIndex,
            appendIndex = appendIndex,
            current = Current(),
            currentOffset = currentOffset,
            currentBoundaryMap = currentBoundaryMap
        };
        return result;
    }
    private string ExpandReplacement(string replacement)
    {
        var result = new StringBuilder(replacement.Length);
        var groupCount = GroupCount();
        for (var index = 0; index < replacement.Length; index++)
        {
            var current = replacement[index];
            if (current == '\\')
            {
                if (++index == replacement.Length)
                    throw new ArgumentException("character to be escaped is missing");
                result.Append(replacement[index]);
                continue;
            }
            if (current != '$')
            {
                result.Append(current);
                continue;
            }
            if (++index == replacement.Length)
                throw new ArgumentException("Illegal group reference: group index is missing");
            if (replacement[index] == '{')
            {
                var end = replacement.IndexOf('}', index + 1);
                if (end < 0) throw new ArgumentException("named capturing group is missing trailing '}'");
                var name = replacement[(index + 1)..end];
                if (name.Length == 0) throw new ArgumentException("named capturing group has 0 length name");
                if (!char.IsAsciiLetter(name[0]) || name.Skip(1).Any(character => !char.IsAsciiLetterOrDigit(character)))
                    throw new ArgumentException("named capturing group has invalid name");
                result.Append(Group(name));
                index = end;
                continue;
            }
            if (!char.IsAsciiDigit(replacement[index]))
                throw new ArgumentException("Illegal group reference");
            var group = replacement[index] - '0';
            if (group > groupCount) throw new ArgumentOutOfRangeException(null, "No group " + group);
            while (index + 1 < replacement.Length && char.IsAsciiDigit(replacement[index + 1]))
            {
                var candidate = checked(group * 10 + replacement[index + 1] - '0');
                if (candidate > groupCount) break;
                group = candidate;
                index++;
            }
            result.Append(Group(group));
        }
        return result.ToString();
    }
    private string Replace(string replacement, bool firstOnly)
    {
        var result = new StringBuilder(input.Length);
        while (Find())
        {
            AppendReplacement(result, replacement);
            if (firstOnly) break;
        }
        AppendTail(result);
        return result.ToString();
    }
    internal string ReplaceAll(string replacement) => Replace(replacement, false);
    internal string ReplaceFirst(string replacement) => Replace(replacement, true);
    internal JavaRegexMatcher AppendReplacement(StringBuilder buffer, string replacement)
    {
        var matchIndex = Start();
        buffer.Append(input, appendIndex, matchIndex - appendIndex);
        buffer.Append(ExpandReplacement(replacement));
        appendIndex = End();
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

internal sealed class JavaDataOutputStream : Stream
{
    private readonly Stream output;

    internal JavaDataOutputStream(Stream output) =>
        this.output = output ?? throw new ArgumentNullException(nameof(output));

    internal void write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void Write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void Write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void writeByte(int value) => output.WriteByte(unchecked((byte)value));

    internal void writeShort(int value)
    {
        Span<byte> bytes = stackalloc byte[2];
        System.Buffers.Binary.BinaryPrimitives.WriteInt16BigEndian(bytes, unchecked((short)value));
        output.Write(bytes);
    }

    internal void writeInt(int value)
    {
        Span<byte> bytes = stackalloc byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(bytes, value);
        output.Write(bytes);
    }

    internal void writeLong(long value)
    {
        Span<byte> bytes = stackalloc byte[8];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(bytes, value);
        output.Write(bytes);
    }

    internal void flush() => output.Flush();
    public override bool CanRead => false;
    public override bool CanSeek => output.CanSeek;
    public override bool CanWrite => output.CanWrite;
    public override long Length => output.Length;
    public override long Position
    {
        get => output.Position;
        set => output.Position = value;
    }
    public override void Flush() => output.Flush();
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => output.Seek(offset, origin);
    public override void SetLength(long value) => output.SetLength(value);
    public override void Write(byte[] buffer, int offset, int count) =>
        output.Write(buffer, offset, count);
    public override void Write(ReadOnlySpan<byte> buffer) => output.Write(buffer);
    public override void WriteByte(byte value) => output.WriteByte(value);
    protected override void Dispose(bool disposing)
    {
        if (disposing) output.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaLineNumberReader : IDisposable
{
    private readonly TextReader reader;

    internal JavaLineNumberReader(TextReader reader) =>
        this.reader = reader ?? throw new ArgumentNullException(nameof(reader));

    internal string? ReadLine() => reader.ReadLine();
    public void Dispose() => reader.Dispose();
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

public sealed class JavaDeque<T> : ICollection<T>
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

internal
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
    internal JavaMessageFormat(string pattern) : this(pattern, CultureInfo.CurrentCulture) { }
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
                if (close < 0)
                    throw new FormatException("Invalid Java MessageFormat placeholder");
                var placeholder = pattern.Substring(index + 1, close - index - 1);
                var fields = placeholder.Split(',', 3, StringSplitOptions.TrimEntries);
                if (fields.Length == 0 ||
                    !int.TryParse(fields[0], NumberStyles.None, CultureInfo.InvariantCulture,
                                  out var argumentIndex))
                    throw new FormatException("Invalid Java MessageFormat placeholder");
                if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                    throw new FormatException("Java MessageFormat argument index is out of range");
                result.Append(FormatArgument(arguments[argumentIndex], fields));
                index = close;
                continue;
            }
            result.Append(current);
        }
        return result.ToString();
    }

    private string FormatArgument(object? argument, string[] fields)
    {
        if (fields.Length == 1) return FormatDefault(argument);
        if (!string.Equals(fields[1], "number", StringComparison.OrdinalIgnoreCase))
            throw new FormatException($"Unsupported Java MessageFormat type `{fields[1]}`");
        if (argument is null) return "null";
        if (fields.Length == 2 || string.IsNullOrEmpty(fields[2]))
            return FormatDefault(argument);

        var style = fields[2];
        var decimalIndex = style.IndexOf('.');
        var integerPattern = decimalIndex < 0 ? style : style[..decimalIndex];
        var fractionPattern = decimalIndex < 0 ? string.Empty : style[(decimalIndex + 1)..];
        if (integerPattern.Any(character => character is not '#' and not '0' and not ',') ||
            fractionPattern.Any(character => character is not '#' and not '0'))
            throw new FormatException($"Unsupported Java DecimalFormat pattern `{style}`");

        var minimumIntegerDigits = Math.Max(1, integerPattern.Count(character => character == '0'));
        var minimumFractionDigits = fractionPattern.Count(character => character == '0');
        var maximumFractionDigits = fractionPattern.Length;
        var grouping = integerPattern.Contains(',');
        var custom = (grouping ? "#,##" : string.Empty) + new string('0', minimumIntegerDigits);
        if (maximumFractionDigits > 0)
            custom += "." + new string('0', minimumFractionDigits) +
                      new string('#', maximumFractionDigits - minimumFractionDigits);
        return argument is IFormattable formattable
            ? formattable.ToString(custom, locale)
            : Convert.ToString(argument, locale) ?? "null";
    }

    private string FormatDefault(object? argument) => argument switch
    {
        null => "null",
        sbyte or byte or short or ushort or int or uint or long or ulong =>
            ((IFormattable)argument).ToString("N0", locale),
        Uri uri => JavaCompat.UriToString(uri),
        Regex regex => JavaCompat.RegexPattern(regex),
        _ => Convert.ToString(argument, locale) ?? "null"
    };
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
