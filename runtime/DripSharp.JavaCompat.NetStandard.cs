// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Compile-time identities used by modern C# syntax and nullable metadata but
// absent from the netstandard2.0 reference surface. They remain internal and
// are omitted entirely from net10 execution projects.
#if NETSTANDARD2_0
#nullable enable
namespace System.Runtime.CompilerServices
{
    internal sealed class IsExternalInit
    {
    }
}

namespace System
{
    internal readonly struct Index
    {
        private readonly int value;

        internal Index(int value, bool fromEnd = false)
        {
            if (value < 0) throw new ArgumentOutOfRangeException(nameof(value));
            this.value = fromEnd ? ~value : value;
        }

        internal int Value => value < 0 ? ~value : value;
        internal bool IsFromEnd => value < 0;
        internal static Index Start => new(0);
        internal static Index End => new(0, fromEnd: true);
        internal int GetOffset(int length) => IsFromEnd ? length - Value : Value;
        public static implicit operator Index(int value) => new(value);
    }

    internal readonly struct Range
    {
        internal Range(Index start, Index end)
        {
            Start = start;
            End = end;
        }

        internal Index Start { get; }
        internal Index End { get; }
        internal static Range All => new(Index.Start, Index.End);
        internal static Range StartAt(Index start) => new(start, Index.End);
        internal static Range EndAt(Index end) => new(Index.Start, end);

        internal ValueTuple<int, int> GetOffsetAndLength(int length)
        {
            var start = Start.GetOffset(length);
            var end = End.GetOffset(length);
            if ((uint)start > (uint)length || (uint)end > (uint)length || end < start)
                throw new ArgumentOutOfRangeException(nameof(length));
            return new ValueTuple<int, int>(start, end - start);
        }
    }
}

namespace System.Diagnostics.CodeAnalysis
{
    [AttributeUsage(AttributeTargets.Field | AttributeTargets.Parameter |
                    AttributeTargets.Property | AttributeTargets.ReturnValue,
                    Inherited = false)]
    internal sealed class MaybeNullAttribute : Attribute
    {
    }

    [AttributeUsage(AttributeTargets.Parameter, Inherited = false)]
    internal sealed class MaybeNullWhenAttribute : Attribute
    {
        internal MaybeNullWhenAttribute(bool returnValue) =>
            ReturnValue = returnValue;

        internal bool ReturnValue { get; }
    }
}

namespace DripSharp.Runtime
{
    internal sealed class JavaReferenceEqualityComparer :
        global::System.Collections.Generic.IEqualityComparer<object>
    {
        internal static readonly JavaReferenceEqualityComparer Instance = new();

        private JavaReferenceEqualityComparer()
        {
        }

        public new bool Equals(object? left, object? right) =>
            global::System.Object.ReferenceEquals(left, right);

        public int GetHashCode(object value) =>
            global::System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);
    }

    internal static partial class JavaCompat
    {
        internal static void ThrowIfNull(object? value, string? parameterName = null)
        {
            if (value is null)
                throw new global::System.ArgumentNullException(parameterName);
        }

        internal static void ThrowIfNullOrEmpty(string? value, string? parameterName = null)
        {
            if (global::System.String.IsNullOrEmpty(value))
                throw new global::System.ArgumentException(
                    "The value cannot be null or empty.", parameterName);
        }

        internal static void ThrowIfNullOrWhiteSpace(
            string? value,
            string? parameterName = null)
        {
            if (global::System.String.IsNullOrWhiteSpace(value))
                throw new global::System.ArgumentException(
                    "The value cannot be null or whitespace.", parameterName);
        }

        internal static void ThrowIfDisposed(bool condition, object instance)
        {
            if (condition)
                throw new global::System.ObjectDisposedException(
                    instance.GetType().FullName);
        }

        internal static string ReplaceOrdinal(
            string value,
            string oldValue,
            string newValue) => value.Replace(oldValue, newValue);

        internal static double Clamp(double value, double minimum, double maximum) =>
            value < minimum ? minimum : value > maximum ? maximum : value;

        internal static float Clamp(float value, float minimum, float maximum) =>
            value < minimum ? minimum : value > maximum ? maximum : value;

        internal static int Clamp(int value, int minimum, int maximum) =>
            value < minimum ? minimum : value > maximum ? maximum : value;

        internal static bool IsFinite(float value) =>
            !global::System.Single.IsNaN(value) &&
            !global::System.Single.IsInfinity(value);

        internal static float Pow(float value, float power) =>
            (float)global::System.Math.Pow(value, power);

        internal static float Cbrt(float value) =>
            value < 0
                ? (float)-global::System.Math.Pow(-value, 1d / 3d)
                : (float)global::System.Math.Pow(value, 1d / 3d);

        internal static float Round(float value) =>
            (float)global::System.Math.Round(value);

        internal static int TrailingZeroCount(uint value)
        {
            if (value == 0) return 32;
            var count = 0;
            while ((value & 1) == 0)
            {
                count++;
                value >>= 1;
            }
            return count;
        }

        internal static void EnsureCapacity<T>(
            global::System.Collections.Generic.ICollection<T> collection,
            int capacity)
        {
            if (collection is global::System.Collections.Generic.List<T> list &&
                list.Capacity < capacity)
                list.Capacity = capacity;
        }

        internal static double CopySign(double value, double sign) =>
            global::System.Math.Abs(value) * (sign < 0 ||
                (sign == 0 && global::System.BitConverter.DoubleToInt64Bits(sign) < 0) ? -1 : 1);

        internal static double Cbrt(double value) =>
            value < 0
                ? -global::System.Math.Pow(-value, 1d / 3d)
                : global::System.Math.Pow(value, 1d / 3d);

        internal static async global::System.Threading.Tasks.Task<byte[]> ReadAsByteArrayAsync(
            global::System.Net.Http.HttpContent content,
            global::System.Threading.CancellationToken cancellationToken)
        {
            using (var source = await content.ReadAsStreamAsync().ConfigureAwait(false))
            using (var destination = new global::System.IO.MemoryStream())
            {
                await source.CopyToAsync(destination, 81920, cancellationToken)
                    .ConfigureAwait(false);
                return destination.ToArray();
            }
        }

        internal static global::System.IO.Stream ReadAsStream(
            global::System.Net.Http.HttpContent content) =>
            content.ReadAsStreamAsync().GetAwaiter().GetResult();

        internal static async global::System.Threading.Tasks.Task<global::System.IO.Stream>
            GetStreamAsync(
                global::System.Net.Http.HttpClient client,
                global::System.Uri uri,
                global::System.Threading.CancellationToken cancellationToken)
        {
            var bytes = await client.GetByteArrayAsync(uri).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            return new global::System.IO.MemoryStream(bytes, writable: false);
        }

        internal static global::System.Threading.Tasks.Task<int> ReadAsync(
            global::System.IO.Stream stream,
            byte[] buffer,
            int offset,
            int count,
            global::System.Threading.CancellationToken cancellationToken) =>
            stream.ReadAsync(buffer, offset, count, cancellationToken);

        internal static void ReadExactly(global::System.IO.Stream stream, byte[] buffer)
        {
            var offset = 0;
            while (offset < buffer.Length)
            {
                var read = stream.Read(buffer, offset, buffer.Length - offset);
                if (read == 0) throw new global::System.IO.EndOfStreamException();
                offset += read;
            }
        }

        internal static void WriteBytes(global::System.IO.Stream stream, byte[] buffer) =>
            stream.Write(buffer, 0, buffer.Length);

        internal static string PortableRelativePath(string basis, string path)
        {
            var fullBasis = global::System.IO.Path.GetFullPath(basis); var fullPath = global::System.IO.Path.GetFullPath(path);
            if (global::System.String.Equals(fullBasis.TrimEnd(global::System.IO.Path.DirectorySeparatorChar, global::System.IO.Path.AltDirectorySeparatorChar), fullPath.TrimEnd(global::System.IO.Path.DirectorySeparatorChar, global::System.IO.Path.AltDirectorySeparatorChar), global::System.StringComparison.Ordinal)) return ".";
            var basisUri = new global::System.Uri(AppendDirectorySeparator(fullBasis));
            var pathUri = new global::System.Uri(fullPath);
            var relative = global::System.Uri.UnescapeDataString(
                basisUri.MakeRelativeUri(pathUri).ToString());
            return relative.Replace('/', global::System.IO.Path.DirectorySeparatorChar);
        }
        private static string AppendDirectorySeparator(string path) =>
            path.EndsWith(global::System.IO.Path.DirectorySeparatorChar.ToString(),
                global::System.StringComparison.Ordinal)
                ? path
                : path + global::System.IO.Path.DirectorySeparatorChar;

        internal static void SetRequestVersionOrLower(
            global::System.Net.Http.HttpRequestMessage request)
        {
            var property = request.GetType().GetProperty("VersionPolicy");
            var enumType = property?.PropertyType;
            if (property is not null && enumType is not null)
                property.SetValue(request, global::System.Enum.Parse(
                    enumType, "RequestVersionOrLower"), null);
        }

        internal static void SetConnectTimeout(object handler, global::System.TimeSpan timeout)
        {
            var property = handler.GetType().GetProperty("ConnectTimeout");
            if (property?.CanWrite == true) property.SetValue(handler, timeout, null);
        }

        internal static void CopyTrailingHeaders(object source, object destination)
        {
            var sourceHeaders = source.GetType().GetProperty("TrailingHeaders")
                ?.GetValue(source, null);
            var destinationHeaders = destination.GetType().GetProperty("TrailingHeaders")
                ?.GetValue(destination, null);
            if (sourceHeaders is null || destinationHeaders is null) return;
            var add = destinationHeaders.GetType().GetMethod(
                "TryAddWithoutValidation",
                new[] { typeof(string), typeof(global::System.Collections.Generic.IEnumerable<string>) });
            if (add is null) return;
            foreach (var entry in (global::System.Collections.IEnumerable)sourceHeaders)
            {
                var entryType = entry.GetType();
                var key = (string)entryType.GetProperty("Key")!.GetValue(entry, null)!;
                var values = (global::System.Collections.Generic.IEnumerable<string>)
                    entryType.GetProperty("Value")!.GetValue(entry, null)!;
                add.Invoke(destinationHeaders, new object[] { key, values });
            }
        }
    }

    internal class ArgumentNullException : global::System.ArgumentNullException
    {
        internal ArgumentNullException()
        {
        }

        internal ArgumentNullException(string? parameterName) : base(parameterName)
        {
        }

        internal ArgumentNullException(string? parameterName, string? message)
            : base(parameterName, message)
        {
        }

        internal static void ThrowIfNull(object? value, string? parameterName = null)
        {
            if (value is null) throw new ArgumentNullException(parameterName);
        }
    }

    internal class ArgumentException : global::System.ArgumentException
    {
        internal ArgumentException()
        {
        }

        internal ArgumentException(string? message) : base(message)
        {
        }

        internal ArgumentException(string? message, string? parameterName)
            : base(message, parameterName)
        {
        }

        internal ArgumentException(string? message, global::System.Exception? innerException)
            : base(message, innerException)
        {
        }

        internal ArgumentException(
            string? message,
            string? parameterName,
            global::System.Exception? innerException)
            : base(message, parameterName, innerException)
        {
        }

        internal static void ThrowIfNullOrEmpty(string? value, string? parameterName = null)
        {
            if (string.IsNullOrEmpty(value))
                throw new ArgumentException("The value cannot be null or empty.", parameterName);
        }
    }

    internal class ArgumentOutOfRangeException : global::System.ArgumentOutOfRangeException
    {
        internal ArgumentOutOfRangeException()
        {
        }

        internal ArgumentOutOfRangeException(string? parameterName) : base(parameterName)
        {
        }

        internal ArgumentOutOfRangeException(string? parameterName, string? message)
            : base(parameterName, message)
        {
        }

        internal static void ThrowIfNegative(int value, string? parameterName = null)
        {
            if (value < 0) throw new ArgumentOutOfRangeException(parameterName);
        }
    }

    internal class ObjectDisposedException : global::System.ObjectDisposedException
    {
        internal ObjectDisposedException(string? objectName) : base(objectName)
        {
        }

        internal static void ThrowIf(bool condition, object instance)
        {
            if (condition)
                throw new ObjectDisposedException(instance.GetType().FullName);
        }
    }

    internal readonly struct Rune
    {
        internal Rune(int value)
        {
            if (!IsValid(value)) throw new ArgumentOutOfRangeException(nameof(value));
            Value = value;
        }

        internal int Value { get; }
        public override string ToString() => char.ConvertFromUtf32(Value);
        internal static bool IsValid(int value) =>
            value >= 0 && value <= 0x10ffff && (value < 0xd800 || value > 0xdfff);

        internal static global::System.Globalization.UnicodeCategory GetUnicodeCategory(Rune value)
        {
            var text = char.ConvertFromUtf32(value.Value);
            return global::System.Globalization.CharUnicodeInfo.GetUnicodeCategory(text, 0);
        }

        internal static bool IsLetter(Rune value)
        {
            var category = GetUnicodeCategory(value);
            return category == global::System.Globalization.UnicodeCategory.UppercaseLetter ||
                   category == global::System.Globalization.UnicodeCategory.LowercaseLetter ||
                   category == global::System.Globalization.UnicodeCategory.TitlecaseLetter ||
                   category == global::System.Globalization.UnicodeCategory.ModifierLetter ||
                   category == global::System.Globalization.UnicodeCategory.OtherLetter;
        }

        internal static bool IsUpper(Rune value) =>
            GetUnicodeCategory(value) ==
            global::System.Globalization.UnicodeCategory.UppercaseLetter;

        internal static Rune ToUpperInvariant(Rune value)
        {
            var text = char.ConvertFromUtf32(value.Value).ToUpperInvariant();
            return new Rune(char.ConvertToUtf32(text, 0));
        }
    }

    internal static class NetStandardStringExtensions
    {
        internal static bool StartsWith(this string value, char prefix) =>
            value.Length != 0 && value[0] == prefix;

        internal static bool EndsWith(this string value, char suffix) =>
            value.Length != 0 && value[value.Length - 1] == suffix;

        internal static string Replace(
            this string value,
            string oldValue,
            string newValue,
            global::System.StringComparison comparisonType)
        {
            if (comparisonType != global::System.StringComparison.Ordinal)
                throw new global::System.NotSupportedException(
                    "Only ordinal replacement is supported by the Java compatibility layer.");
            return value.Replace(oldValue, newValue);
        }

        internal static TValue GetValueOrDefault<TKey, TValue>(
            this global::System.Collections.Generic.IDictionary<TKey, TValue> dictionary,
            TKey key) =>
            dictionary.TryGetValue(key, out var value) ? value : default!;

        internal static global::System.Collections.Generic.IEnumerable<Rune> EnumerateRunes(
            this string value)
        {
            for (var index = 0; index < value.Length; index++)
            {
                var current = value[index];
                if (char.IsHighSurrogate(current) && index + 1 < value.Length &&
                    char.IsLowSurrogate(value[index + 1]))
                {
                    yield return new Rune(char.ConvertToUtf32(current, value[++index]));
                }
                else if (char.IsSurrogate(current))
                {
                    yield return new Rune(0xfffd);
                }
                else
                {
                    yield return new Rune(current);
                }
            }
        }
    }
}
#endif
