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

// JDK compatibility area: Java.Util.Regex

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
                if (!JavaCompat.IsAsciiLetter(name[0]) || name.Skip(1).Any(character => !JavaCompat.IsAsciiLetterOrDigit(character)))
                    throw new ArgumentException("named capturing group has invalid name");
                result.Append(Group(name));
                index = end;
                continue;
            }
            if (!JavaCompat.IsAsciiDigit(replacement[index]))
                throw new ArgumentException("Illegal group reference");
            var group = replacement[index] - '0';
            if (group > groupCount) throw new ArgumentOutOfRangeException(null, "No group " + group);
            while (index + 1 < replacement.Length && JavaCompat.IsAsciiDigit(replacement[index + 1]))
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


internal static partial class JavaCompat
{
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

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Regex, JavaUriText>
        OriginalRegexPatterns = new();
    internal static string[] StringSplit(string value, string pattern, int limit)
        => RegexSplit(CompileRegex(pattern), value, limit);
    internal static bool StringMatches(string value, string pattern) => RegexMatcher(CompileRegex(pattern), value).Matches();
    internal static string StringReplaceAll(string value, string pattern, string replacement) =>
        RegexMatcher(CompileRegex(pattern), value).ReplaceAll(replacement);
    internal static string StringReplaceFirst(string value, string pattern, string replacement) =>
        RegexMatcher(CompileRegex(pattern), value).ReplaceFirst(replacement);
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
                if (javaName.Length == 0 || !JavaCompat.IsAsciiLetter(javaName[0]) ||
                    javaName.Skip(1).Any(character => !JavaCompat.IsAsciiLetterOrDigit(character)))
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
                _ when JavaCompat.IsAsciiLetter(escaped) => throw new ArgumentException(
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
            while (index < pattern.Length && JavaCompat.IsAsciiDigit(pattern[index]))
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
                _ when JavaCompat.IsAsciiLetter(escaped) => throw new ArgumentException(
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

    private static string JavaRegexSyntaxMessage(
        string pattern,
        global::System.ArgumentException error)
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
        catch (global::System.ArgumentException error)
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
}
