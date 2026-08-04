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

// JDK compatibility area: Java.Time

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSqlDate
{
    private static readonly Regex Pattern =
        new(@"^(\d{4})-(\d{1,2})-(\d{1,2})$", RegexOptions.CultureInvariant);
    private readonly DateOnly value;

    private JavaSqlDate(DateOnly value) => this.value = value;

    public static JavaSqlDate ValueOf(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var match = Pattern.Match(text);
        if (!match.Success)
            throw new ArgumentException("Invalid JDBC date escape value.", nameof(text));
        try
        {
            return new JavaSqlDate(
                new DateOnly(
                    int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture),
                    int.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture),
                    int.Parse(match.Groups[3].Value, CultureInfo.InvariantCulture)));
        }
        catch (ArgumentOutOfRangeException error)
        {
            throw new ArgumentException("Invalid JDBC date escape value.", nameof(text), error);
        }
    }

    public override string ToString() =>
        value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSqlTime
{
    private static readonly Regex Pattern =
        new(@"^(\d{1,2}):(\d{1,2}):(\d{1,2})$", RegexOptions.CultureInvariant);
    private readonly TimeOnly value;

    private JavaSqlTime(TimeOnly value) => this.value = value;

    public static JavaSqlTime ValueOf(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var match = Pattern.Match(text);
        if (!match.Success)
            throw new ArgumentException("Invalid JDBC time escape value.", nameof(text));
        try
        {
            return new JavaSqlTime(
                new TimeOnly(
                    int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture),
                    int.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture),
                    int.Parse(match.Groups[3].Value, CultureInfo.InvariantCulture)));
        }
        catch (ArgumentOutOfRangeException error)
        {
            throw new ArgumentException("Invalid JDBC time escape value.", nameof(text), error);
        }
    }

    public override string ToString() =>
        value.ToString("HH:mm:ss", CultureInfo.InvariantCulture);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSqlTimestamp
{
    private static readonly Regex Pattern = new(
        @"^(\d{4})-(\d{1,2})-(\d{1,2}) (\d{1,2}):(\d{1,2}):(\d{1,2})(?:\.(\d{1,9}))?$",
        RegexOptions.CultureInvariant);
    private readonly DateTime value;
    private readonly string fraction;

    private JavaSqlTimestamp(DateTime value, string fraction)
    {
        this.value = value;
        this.fraction = fraction;
    }

    public static JavaSqlTimestamp ValueOf(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var match = Pattern.Match(text);
        if (!match.Success)
            throw new ArgumentException("Invalid JDBC timestamp escape value.", nameof(text));
        try
        {
            var value = new DateTime(
                int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture),
                int.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture),
                int.Parse(match.Groups[3].Value, CultureInfo.InvariantCulture),
                int.Parse(match.Groups[4].Value, CultureInfo.InvariantCulture),
                int.Parse(match.Groups[5].Value, CultureInfo.InvariantCulture),
                int.Parse(match.Groups[6].Value, CultureInfo.InvariantCulture),
                DateTimeKind.Unspecified);
            var fraction = match.Groups[7].Success
                ? match.Groups[7].Value.TrimEnd('0')
                : "0";
            return new JavaSqlTimestamp(value, fraction.Length == 0 ? "0" : fraction);
        }
        catch (ArgumentOutOfRangeException error)
        {
            throw new ArgumentException(
                "Invalid JDBC timestamp escape value.", nameof(text), error);
        }
    }

    public override string ToString() =>
        value.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture) + "." + fraction;
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
#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaDateTimeFormatter
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

internal sealed class JavaDateTimeFormatterBuilder
{
    internal JavaDateTimeFormatterBuilder ParseCaseInsensitive() => this;
    internal JavaDateTimeFormatterBuilder Append(JavaDateTimeFormatter formatter) => this;
    internal JavaDateTimeFormatterBuilder ParseLenient() => this;
    internal JavaDateTimeFormatterBuilder AppendOffset(string pattern, string zeroOffsetText) => this;
    internal JavaDateTimeFormatterBuilder ParseStrict() => this;
    internal JavaDateTimeFormatter ToFormatter() => JavaDateTimeFormatter.IsoLocalDateTimeOffset();
}


internal static partial class JavaCompat
{
    internal static TimeSpan DurationOfSeconds(long seconds) => TimeSpan.FromSeconds(seconds);
    internal static TimeSpan DurationOfSeconds(long seconds, long nanos) =>
        TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    internal static TimeZoneInfo GetTimeZone(string id)
    {
        if (string.Equals(id, "UTC", StringComparison.OrdinalIgnoreCase))
            return TimeZoneInfo.Utc;
        if (string.Equals(id, "GMT", StringComparison.OrdinalIgnoreCase))
            return NewSimpleTimeZone(0, "GMT");
        if (string.Equals(id, "PST", StringComparison.OrdinalIgnoreCase))
            return NewSimpleTimeZone(-8 * 60 * 60 * 1000, "PST");
        if (string.Equals(id, "EST", StringComparison.OrdinalIgnoreCase))
            return NewSimpleTimeZone(-5 * 60 * 60 * 1000, "EST");
        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById(id);
        }
        catch (TimeZoneNotFoundException)
        {
            return NewSimpleTimeZone(0, "GMT");
        }
        catch (InvalidTimeZoneException)
        {
            return NewSimpleTimeZone(0, "GMT");
        }
    }
    private static readonly ConcurrentDictionary<
        (long Ticks, long OffsetTicks), TimeZoneInfo> CalendarZones = new();
    private static (long Ticks, long OffsetTicks) CalendarKey(DateTimeOffset value) =>
        (value.Ticks, value.Offset.Ticks);
    private static DateTimeOffset RememberCalendarZone(
        DateTimeOffset value,
        TimeZoneInfo zone)
    {
        CalendarZones[CalendarKey(value)] = zone;
        return value;
    }
    private static bool TryCalendarZone(DateTimeOffset value, out TimeZoneInfo zone) =>
        CalendarZones.TryGetValue(CalendarKey(value), out zone!);
    private static TimeSpan CalendarZoneOffset(TimeZoneInfo zone, DateTime value) =>
        TimeZoneMetadata.TryGetValue(zone, out var metadata) &&
        metadata.RawOffsetMilliseconds.HasValue
            ? TimeSpan.FromMilliseconds(metadata.RawOffsetMilliseconds.Value)
            : zone.GetUtcOffset(value);
    internal static DateTimeOffset CalendarInstance(TimeZoneInfo zone) =>
        RememberCalendarZone(TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, zone), zone);
    private static DateTimeOffset CalendarValue(DateTimeOffset? value) =>
        value ?? throw new NullReferenceException();
    internal static long CalendarGetTimeInMillis(DateTimeOffset value) =>
        value.ToUnixTimeMilliseconds();
    internal static long CalendarGetTimeInMillis(DateTimeOffset? value) =>
        CalendarGetTimeInMillis(CalendarValue(value));
    internal static int CalendarCompareTo(DateTimeOffset? value, DateTimeOffset? other) =>
        CalendarValue(value).CompareTo(CalendarValue(other));
    internal static DateTimeOffset CalendarClear(DateTimeOffset value) =>
        new(1970, 1, 1, 0, 0, 0, value.Offset);
    internal static DateTimeOffset CalendarClear(DateTimeOffset? value) =>
        CalendarClear(CalendarValue(value));
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
    internal static void CalendarSetLenient(DateTimeOffset? value, bool lenient) =>
        CalendarSetLenient(CalendarValue(value), lenient);
    internal static DateTimeOffset CalendarSetTimeZone(
        DateTimeOffset value,
        TimeZoneInfo zone)
    {
        var offset = CalendarZoneOffset(zone, value.UtcDateTime);
        return RememberCalendarZone(value.ToOffset(offset), zone);
    }
    internal static DateTimeOffset CalendarSetTimeZone(
        DateTimeOffset? value,
        TimeZoneInfo zone) =>
        CalendarSetTimeZone(CalendarValue(value), zone);
    internal static TimeZoneInfo CalendarGetTimeZone(DateTimeOffset value) =>
        TryCalendarZone(value, out var zone)
            ? zone
            : value.Offset == TimeSpan.Zero
            ? TimeZoneInfo.Utc
            : NewSimpleTimeZone(
                checked((int)value.Offset.TotalMilliseconds), $"GMT{value:zzz}");
    internal static TimeZoneInfo CalendarGetTimeZone(DateTimeOffset? value) =>
        CalendarGetTimeZone(CalendarValue(value));
    internal static DateTimeOffset CalendarAdd(
        DateTimeOffset value,
        int field,
        int amount)
    {
        var result = field switch
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
        if (!TryCalendarZone(value, out var zone)) return result;
        var local = DateTime.SpecifyKind(result.DateTime, DateTimeKind.Unspecified);
        return RememberCalendarZone(new DateTimeOffset(local, CalendarZoneOffset(zone, local)), zone);
    }
    internal static DateTimeOffset CalendarAdd(
        DateTimeOffset? value,
        int field,
        int amount) =>
        CalendarAdd(CalendarValue(value), field, amount);
    internal static DateTimeOffset CalendarSet(
        DateTimeOffset value,
        int year,
        int zeroBasedMonth,
        int day,
        int hour,
        int minute,
        int second)
    {
        var local = new DateTime(
            Math.Max(1, year), zeroBasedMonth + 1, day, hour, minute, second,
            DateTimeKind.Unspecified);
        if (!TryCalendarZone(value, out var zone))
            return new DateTimeOffset(local, value.Offset);
        return RememberCalendarZone(new DateTimeOffset(local, CalendarZoneOffset(zone, local)), zone);
    }
    internal static DateTimeOffset CalendarSet(
        DateTimeOffset? value,
        int year,
        int zeroBasedMonth,
        int day,
        int hour,
        int minute,
        int second) =>
        CalendarSet(CalendarValue(value), year, zeroBasedMonth, day, hour, minute, second);
    internal static DateTimeOffset CalendarSet(DateTimeOffset value, int field, int fieldValue)
    {
        var result = field == 14
            ? new DateTimeOffset(value.Year, value.Month, value.Day, value.Hour, value.Minute,
                value.Second, fieldValue, value.Offset)
            : throw new ArgumentOutOfRangeException(nameof(field));
        return TryCalendarZone(value, out var zone)
            ? RememberCalendarZone(result, zone)
            : result;
    }
    internal static DateTimeOffset CalendarSet(
        DateTimeOffset? value,
        int field,
        int fieldValue) =>
        CalendarSet(CalendarValue(value), field, fieldValue);
    internal static int CalendarGet(DateTimeOffset value, int field)
    {
        var totalOffset = checked((int)value.Offset.TotalMilliseconds);
        var rawOffset = TryCalendarZone(value, out var zone)
            ? TimeZoneRawOffset(zone)
            : totalOffset;
        return field switch
        {
            1 => value.Year,
            2 => value.Month - 1,
            5 => value.Day,
            11 => value.Hour,
            12 => value.Minute,
            13 => value.Second,
            14 => value.Millisecond,
            15 => rawOffset,
            16 => totalOffset - rawOffset,
            _ => throw new ArgumentOutOfRangeException(nameof(field))
        };
    }
    internal static int CalendarGet(DateTimeOffset? value, int field) =>
        CalendarGet(CalendarValue(value), field);
    internal static DateTimeOffset ParseZonedDateTime(
        string value,
        JavaDateTimeFormatter formatter)
    {
        _ = formatter;
        if (!Regex.IsMatch(value, @"(?:Z|[+-]\d{2}:\d{2})$", RegexOptions.CultureInvariant))
            throw new FormatException("A zoned date-time requires an explicit UTC offset.");
        return DateTimeOffset.Parse(
            value,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces);
    }
    internal static DateTimeOffset ToInstant(DateTimeOffset value) =>
        value.ToUniversalTime();
    internal static DateTimeOffset ToInstant(DateTimeOffset? value) =>
        (value ?? throw new NullReferenceException()).ToUniversalTime();
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
    internal static long DurationToMillis(TimeSpan value) => checked((long)value.TotalMilliseconds);
    internal static long DurationGetSeconds(TimeSpan value) => checked((long)value.TotalSeconds);
    internal static int DurationGetNano(TimeSpan value) => checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
}
