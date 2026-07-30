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

// JDK compatibility area: Java.Text

internal sealed class JavaDecimalFormat
{
    private readonly string integerPattern;
    private NumberFormatInfo format;
    private int minimumFractionDigits;
    private int maximumFractionDigits;
    private bool groupingUsed;

    internal JavaDecimalFormat()
        : this("#,##0.###", CultureInfo.CurrentCulture.NumberFormat) { }

    internal JavaDecimalFormat(string pattern, NumberFormatInfo format)
    {
        this.format = (NumberFormatInfo)format.Clone();
        var decimalPoint = pattern.IndexOf('.');
        integerPattern = decimalPoint < 0 ? pattern : pattern[..decimalPoint];
        groupingUsed = integerPattern.Contains(',');
        var fractionPattern = decimalPoint < 0 ? string.Empty : pattern[(decimalPoint + 1)..];
        minimumFractionDigits = fractionPattern.Count(character => character == '0');
        maximumFractionDigits = fractionPattern.Length;
    }

    internal static JavaDecimalFormat GetNumberInstance(CultureInfo culture) =>
        new("#,##0.###", culture.NumberFormat);

    private string Pattern =>
        (groupingUsed ? integerPattern : integerPattern.Replace(",", string.Empty, StringComparison.Ordinal)) +
        (maximumFractionDigits == 0
            ? string.Empty
            : "." + new string('0', minimumFractionDigits) +
              new string('#', maximumFractionDigits - minimumFractionDigits));

    internal string Format(long value)
    {
        var integer = new BigInteger(value);
        var negative = integer.Sign < 0;
        if (negative) integer = BigInteger.Negate(integer);
        return RenderFixed(integer * PowerOfTen(maximumFractionDigits), negative);
    }

    internal string Format(double value)
    {
        if (double.IsNaN(value)) return format.NaNSymbol;
        if (double.IsPositiveInfinity(value)) return format.PositiveInfinitySymbol;
        if (double.IsNegativeInfinity(value))
            return format.NegativeSign + format.PositiveInfinitySymbol;

        // DecimalFormat rounds the exact binary double, while retaining the
        // shortest round-trippable decimal coefficient for finite magnitudes.
        // Consulting the binary value at a decimal halfway boundary preserves
        // Java's HALF_EVEN results for values such as 2.675 and 2.625 without
        // expanding Double.MAX_VALUE beyond the digits exposed by
        // Double.toString().
        var bits = unchecked((ulong)BitConverter.DoubleToInt64Bits(value));
        var negative = (bits & (1UL << 63)) != 0;
        var magnitude = value == 0d ? 0d : Math.Abs(value);
        var shortest = magnitude.ToString("R", CultureInfo.InvariantCulture);
        ParseDecimal(shortest, out var coefficient, out var decimalExponent);

        var scaledExponent = decimalExponent + maximumFractionDigits;
        BigInteger rounded;
        if (scaledExponent >= 0)
        {
            rounded = coefficient * PowerOfTen(scaledExponent);
        }
        else
        {
            var divisor = PowerOfTen(-scaledExponent);
            rounded = BigInteger.DivRem(coefficient, divisor, out var remainder);
            var halfComparison = (remainder << 1).CompareTo(divisor);
            var roundUp = halfComparison > 0;
            if (halfComparison == 0)
            {
                var exactComparison = CompareExactBinaryToDecimal(
                    bits & 0x7fff_ffff_ffff_ffffUL, coefficient, decimalExponent);
                roundUp = exactComparison > 0 ||
                          (exactComparison == 0 && !rounded.IsEven);
            }
            if (roundUp) rounded += BigInteger.One;
        }
        return RenderFixed(rounded, negative);
    }

    internal string Format(object? value) =>
        value switch
        {
            double number => Format(number),
            float number => Format(number),
            long integer => Format(integer),
            int integer => Format(integer),
            short integer => Format(integer),
            sbyte integer => Format(integer),
            byte integer => Format(integer),
            IFormattable formattable =>
                formattable.ToString(Pattern, format) ?? string.Empty,
            _ => value?.ToString() ?? string.Empty
        };

    internal int GetMaximumFractionDigits() => maximumFractionDigits;

    private string RenderFixed(BigInteger scaledMagnitude, bool negative)
    {
        var digits = scaledMagnitude.ToString(CultureInfo.InvariantCulture);
        string integer;
        string fraction;
        if (maximumFractionDigits == 0)
        {
            integer = digits;
            fraction = string.Empty;
        }
        else if (digits.Length <= maximumFractionDigits)
        {
            integer = "0";
            fraction = new string('0', maximumFractionDigits - digits.Length) + digits;
        }
        else
        {
            var split = digits.Length - maximumFractionDigits;
            integer = digits[..split];
            fraction = digits[split..];
        }

        var minimumIntegerDigits = integerPattern.Count(character => character == '0');
        integer = integer.PadLeft(minimumIntegerDigits, '0');
        if (groupingUsed) integer = GroupInteger(integer);
        while (fraction.Length > minimumFractionDigits &&
               fraction.EndsWith("0", StringComparison.Ordinal))
            fraction = fraction[..^1];
        var result = fraction.Length == 0
            ? integer
            : integer + format.NumberDecimalSeparator + fraction;
        return negative ? format.NegativeSign + result : result;
    }

    private string GroupInteger(string integer)
    {
        var lastComma = integerPattern.LastIndexOf(',');
        var groupingSize = lastComma < 0
            ? 0
            : integerPattern[(lastComma + 1)..].Count(
                character => character is '0' or '#');
        if (groupingSize <= 0 || integer.Length <= groupingSize) return integer;
        var firstGroupSize = integer.Length % groupingSize;
        if (firstGroupSize == 0) firstGroupSize = groupingSize;
        var builder = new StringBuilder(integer.Length + integer.Length / groupingSize);
        builder.Append(integer, 0, firstGroupSize);
        for (var index = firstGroupSize; index < integer.Length; index += groupingSize)
        {
            builder.Append(format.NumberGroupSeparator);
            builder.Append(integer, index, groupingSize);
        }
        return builder.ToString();
    }

    private static void ParseDecimal(
        string value,
        out BigInteger coefficient,
        out int decimalExponent)
    {
        var exponentIndex = value.IndexOfAny(['E', 'e']);
        var exponent = exponentIndex < 0
            ? 0
            : int.Parse(value[(exponentIndex + 1)..], CultureInfo.InvariantCulture);
        var significand = exponentIndex < 0 ? value : value[..exponentIndex];
        var decimalIndex = significand.IndexOf('.');
        var fractionDigits = decimalIndex < 0 ? 0 : significand.Length - decimalIndex - 1;
        var digits = decimalIndex < 0
            ? significand
            : significand.Remove(decimalIndex, 1);
        coefficient = BigInteger.Parse(digits, CultureInfo.InvariantCulture);
        decimalExponent = exponent - fractionDigits;
    }

    private static int CompareExactBinaryToDecimal(
        ulong magnitudeBits,
        BigInteger decimalCoefficient,
        int decimalExponent)
    {
        if (magnitudeBits == 0) return decimalCoefficient.IsZero ? 0 : -1;
        var exponentBits = (int)((magnitudeBits >> 52) & 0x7ffUL);
        var fractionBits = magnitudeBits & 0x000f_ffff_ffff_ffffUL;
        var significand = new BigInteger(
            exponentBits == 0 ? fractionBits : fractionBits | (1UL << 52));
        var binaryExponent = exponentBits == 0 ? -1074 : exponentBits - 1023 - 52;

        var binaryNumerator =
            binaryExponent >= 0 ? significand << binaryExponent : significand;
        var binaryDenominator = binaryExponent < 0
            ? BigInteger.One << -binaryExponent
            : BigInteger.One;
        var decimalNumerator = decimalExponent >= 0
            ? decimalCoefficient * PowerOfTen(decimalExponent)
            : decimalCoefficient;
        var decimalDenominator = decimalExponent < 0
            ? PowerOfTen(-decimalExponent)
            : BigInteger.One;
        return (binaryNumerator * decimalDenominator)
            .CompareTo(decimalNumerator * binaryDenominator);
    }

    private static BigInteger PowerOfTen(int exponent) =>
        BigInteger.Pow(10, exponent);

    internal void SetDecimalFormatSymbols(NumberFormatInfo value)
    {
        format = (NumberFormatInfo)value.Clone();
    }

    internal void SetMinimumFractionDigits(int value)
    {
        minimumFractionDigits = Math.Max(0, value);
        maximumFractionDigits = Math.Max(maximumFractionDigits, minimumFractionDigits);
    }

    internal void SetMaximumFractionDigits(int value)
    {
        maximumFractionDigits = Math.Max(0, value);
        minimumFractionDigits = Math.Min(minimumFractionDigits, maximumFractionDigits);
    }

    internal void SetGroupingUsed(bool value) => groupingUsed = value;
}

// StrictMath requires reproducible fdlibm results rather than the
// platform-dependent libm results exposed by System.Math. These routines are
// adapted from the Freely Distributable Math Library algorithms used by
// OpenJDK.
#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaParsePosition
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

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSimpleDateFormat
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
                'Z' => "zzz",
                _ => new string(current, count)
            });
            index = end;
        }
        return result.ToString();
    }
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


internal static partial class JavaCompat
{
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
            var dateTimeConversion =
                cursor < format.Length && format[cursor] is 't' or 'T';
            if (dateTimeConversion) cursor++;
            if (cursor >= format.Length) throw new FormatException("Invalid Java format conversion");
            var conversion = format[cursor];

            var argumentIndex = explicitArgument ?? nextArgument++;
            if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                throw new FormatException("Missing Java format argument");
            var rendered = dateTimeConversion
                ? FormatJavaDateArgument(arguments[argumentIndex], conversion, locale)
                : FormatJavaArgument(arguments[argumentIndex], conversion, precision, locale);
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

    private static string FormatJavaDateArgument(
        object? value,
        char conversion,
        CultureInfo locale)
    {
        var date = value switch
        {
            DateTimeOffset dateTimeOffset => dateTimeOffset,
            DateTime dateTime => new DateTimeOffset(dateTime),
            long milliseconds => DateTimeOffset.FromUnixTimeMilliseconds(milliseconds),
            _ => throw new FormatException(
                $"Unsupported Java date/time format argument: {value?.GetType().FullName ?? "null"}")
        };
        return conversion switch
        {
            'Y' => date.ToString("yyyy", locale),
            'y' => date.ToString("yy", locale),
            'm' => date.ToString("MM", locale),
            'd' => date.ToString("dd", locale),
            'e' => date.Day.ToString(locale).PadLeft(2),
            'H' => date.ToString("HH", locale),
            'I' => date.ToString("hh", locale),
            'M' => date.ToString("mm", locale),
            'S' => date.ToString("ss", locale),
            'L' => date.ToString("fff", locale),
            'N' => (date.Ticks % TimeSpan.TicksPerSecond * 100)
                .ToString("D9", CultureInfo.InvariantCulture),
            'p' => date.ToString("tt", locale).ToLower(locale),
            'z' => date.ToString("zzz", locale)
                .Replace(":", "", StringComparison.Ordinal),
            'Z' => TimeZoneInfo.Local.IsDaylightSavingTime(date.DateTime)
                ? TimeZoneInfo.Local.DaylightName
                : TimeZoneInfo.Local.StandardName,
            's' => date.ToUnixTimeSeconds().ToString(locale),
            'Q' => date.ToUnixTimeMilliseconds().ToString(locale),
            'B' => date.ToString("MMMM", locale),
            'b' or 'h' => date.ToString("MMM", locale),
            'A' => date.ToString("dddd", locale),
            'a' => date.ToString("ddd", locale),
            'j' => date.DayOfYear.ToString("D3", locale),
            'R' => date.ToString("HH:mm", locale),
            'T' => date.ToString("HH:mm:ss", locale),
            'D' => date.ToString("MM/dd/yy", locale),
            'F' => date.ToString("yyyy-MM-dd", locale),
            'c' => date.ToString("ddd MMM dd HH:mm:ss zzz yyyy", locale),
            _ => throw new FormatException(
                $"Unsupported Java date/time format conversion: {conversion}")
        };
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

}
