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
        return value.ToOffset(offset);
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
    internal static long DurationToMillis(TimeSpan value) => checked((long)value.TotalMilliseconds);
    internal static long DurationGetSeconds(TimeSpan value) => checked((long)value.TotalSeconds);
    internal static int DurationGetNano(TimeSpan value) => checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
}
