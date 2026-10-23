// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Collections;
using System.Globalization;
using System.Numerics;
using System.Text;

namespace PdfCli;

/// <summary>A JSON object whose members keep their insertion order.</summary>
internal sealed class JObj : List<KeyValuePair<string, object?>>
{
    public static JObj Of(params object?[] keysAndValues)
    {
        var obj = new JObj();
        for (var i = 0; i < keysAndValues.Length; i += 2)
            obj.Add((string)keysAndValues[i]!, keysAndValues[i + 1]);
        return obj;
    }

    public void Add(string key, object? value) => Add(new KeyValuePair<string, object?>(key, value));

    /// <summary>A JSON object whose keys are sorted in ordinal UTF-16 order.</summary>
    public static JObj Sorted(IEnumerable<KeyValuePair<string, object?>> members)
    {
        var obj = new JObj();
        obj.AddRange(members.OrderBy(m => m.Key, StringComparer.Ordinal));
        return obj;
    }
}

/// <summary>A duration in milliseconds, written rounded to 3 fractional digits.</summary>
internal readonly record struct Millis(double Value)
{
    public static Millis FromNanos(double nanos) => new(nanos / 1.0e6);
}

/// <summary>
/// The canonical JSON writer of validation/pdfcarton-cli/contract.md: one line,
/// ordered objects, and the contract's number rules.
/// </summary>
internal static class CanonicalJson
{
    public static string Write(object? value)
    {
        var sb = new StringBuilder();
        WriteValue(sb, value);
        return sb.ToString();
    }

    private static void WriteValue(StringBuilder sb, object? value)
    {
        switch (value)
        {
            case null:
                sb.Append("null");
                break;
            case JObj obj:
                sb.Append('{');
                for (var i = 0; i < obj.Count; i++)
                {
                    if (i > 0) sb.Append(',');
                    WriteString(sb, obj[i].Key);
                    sb.Append(':');
                    WriteValue(sb, obj[i].Value);
                }
                sb.Append('}');
                break;
            case string s:
                WriteString(sb, s);
                break;
            case bool b:
                sb.Append(b ? "true" : "false");
                break;
            case float f:
                sb.Append(FloatText(f));
                break;
            case Millis m:
                sb.Append(MillisText(m.Value));
                break;
            case int or long or short or sbyte or byte or BigInteger:
                sb.Append(Convert.ToString(value, CultureInfo.InvariantCulture));
                break;
            case IEnumerable items:
                sb.Append('[');
                var first = true;
                foreach (var item in items)
                {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteValue(sb, item);
                }
                sb.Append(']');
                break;
            default:
                throw new ArgumentException($"No canonical JSON form for {value.GetType()}");
        }
    }

    private static void WriteString(StringBuilder sb, string s)
    {
        sb.Append('"');
        for (var i = 0; i < s.Length; i++)
        {
            var c = s[i];
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\b': sb.Append("\\b"); break;
                case '\t': sb.Append("\\t"); break;
                case '\n': sb.Append("\\n"); break;
                case '\f': sb.Append("\\f"); break;
                case '\r': sb.Append("\\r"); break;
                default:
                    if (c < 0x20)
                    {
                        sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                    }
                    else if (char.IsHighSurrogate(c))
                    {
                        if (i + 1 < s.Length && char.IsLowSurrogate(s[i + 1]))
                        {
                            sb.Append(c).Append(s[i + 1]);
                            i++;
                        }
                        else
                        {
                            sb.Append('�');
                        }
                    }
                    else if (char.IsLowSurrogate(c))
                    {
                        sb.Append('�');
                    }
                    else
                    {
                        sb.Append(c);
                    }
                    break;
            }
        }
        sb.Append('"');
    }

    /// <summary>
    /// The shortest decimal that rounds back to <paramref name="f"/>, with
    /// Java's (JDK 19+) Float.toString digit choice, in plain notation.
    /// </summary>
    internal static string FloatText(float f)
    {
        if (float.IsNaN(f) || float.IsInfinity(f)) return "null";
        if (f == 0f) return "0";
        var (negative, digits, pointIndex) = Decompose(f.ToString("R", CultureInfo.InvariantCulture));
        if (digits.Length == 1)
        {
            // Java considers at least two significant digits: use the value
            // rounded to two significant digits when it round-trips.
            var two = f.ToString("E1", CultureInfo.InvariantCulture);
            if (float.Parse(two, CultureInfo.InvariantCulture) == f)
                (negative, digits, pointIndex) = Decompose(two);
        }
        return Plain(negative, digits, pointIndex);
    }

    /// <summary>
    /// Splits a .NET number text into its sign, its significant digits (no
    /// leading or trailing zeros) and the position of the decimal point
    /// relative to the first of those digits.
    /// </summary>
    private static (bool Negative, string Digits, int PointIndex) Decompose(string text)
    {
        var negative = text.StartsWith('-');
        if (negative) text = text[1..];
        var exponent = 0;
        var e = text.IndexOfAny(['E', 'e']);
        if (e >= 0)
        {
            exponent = int.Parse(text[(e + 1)..], NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture);
            text = text[..e];
        }
        var dot = text.IndexOf('.');
        var mantissa = dot >= 0 ? text.Remove(dot, 1) : text;
        var pointIndex = (dot >= 0 ? dot : text.Length) + exponent;
        var lead = 0;
        while (lead < mantissa.Length - 1 && mantissa[lead] == '0') lead++;
        mantissa = mantissa[lead..];
        pointIndex -= lead;
        mantissa = mantissa.TrimEnd('0');
        if (mantissa.Length == 0) mantissa = "0";
        return (negative, mantissa, pointIndex);
    }

    private static string Plain(bool negative, string digits, int pointIndex)
    {
        var sb = new StringBuilder();
        if (negative) sb.Append('-');
        if (pointIndex <= 0)
            sb.Append("0.").Append('0', -pointIndex).Append(digits);
        else if (pointIndex >= digits.Length)
            sb.Append(digits).Append('0', pointIndex - digits.Length);
        else
            sb.Append(digits, 0, pointIndex).Append('.').Append(digits, pointIndex, digits.Length - pointIndex);
        return sb.ToString();
    }

    /// <summary>
    /// The exact binary64 value rounded half-to-even to 3 fractional digits,
    /// in plain notation without trailing fractional zeros.
    /// </summary>
    internal static string MillisText(double value)
    {
        if (double.IsNaN(value) || double.IsInfinity(value)) return "null";
        var negative = value < 0;
        var bits = BitConverter.DoubleToInt64Bits(Math.Abs(value));
        var exponentBits = (int)((bits >> 52) & 0x7FF);
        var fraction = bits & 0xFFFFFFFFFFFFFL;
        BigInteger mantissa = exponentBits == 0 ? fraction : fraction | (1L << 52);
        var exponent = exponentBits == 0 ? -1074 : exponentBits - 1075;
        BigInteger thousandths;
        if (exponent >= 0)
        {
            thousandths = mantissa * 1000 << exponent;
        }
        else
        {
            var numerator = mantissa * 1000;
            var denominator = BigInteger.One << -exponent;
            thousandths = BigInteger.DivRem(numerator, denominator, out var remainder);
            var twice = remainder * 2;
            if (twice > denominator || (twice == denominator && !thousandths.IsEven))
                thousandths += 1;
        }
        if (thousandths.IsZero) return "0";
        var text = thousandths.ToString(CultureInfo.InvariantCulture).PadLeft(4, '0');
        var integral = text[..^3];
        var fractional = text[^3..].TrimEnd('0');
        return (negative ? "-" : "") + integral + (fractional.Length > 0 ? "." + fractional : "");
    }
}
