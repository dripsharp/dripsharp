using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.Runtime;

internal static class Program
{
    private static readonly List<string> Observations = new();
    private static readonly Dictionary<char, char> MirroringCharMap = new();

    private readonly record struct Case(string Id, string Text);

    private static readonly Case[] Cases =
    [
        new("empty", ""),
        new("ltr", "plain Latin"),
        new("rtl-hebrew", "\u05D0\u05D1\u05D2"),
        new("rtl-arabic", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        new("ltr-rtl", "abc \u05D0\u05D1\u05D2"),
        new("rtl-ltr", "\u05D0\u05D1\u05D2 abc"),
        new("rtl-number", "\u05D0\u05D1 123 \u05D2"),
        new("arabic-number", "\u0627\u0628 12.34 \u062C"),
        new("neutral", "  (123) - "),
        new("nested-embedding", "a \u202B\u05D0 12 \u202Axy\u202C \u05D1\u202C z"),
        new("nested-override", "a \u202Eab\u202C \u202D\u05D0\u05D1\u202C z"),
        new("isolates", "a\u2067\u05D0 12\u2066xy\u2069\u05D1\u2069z"),
        new("isolate-only", "\u2067\u05D0\u05D1\u2069"),
        new("unclosed-isolate-only", "\u2067\u05D0\u05D1"),
        new("unclosed-controls", "\u202B\u05D0\u202A12\u2067\u05D1"),
        new("stray-controls", "a\u202C\u2069\u202C\u05D0\u2069z"),
        new("paragraph-break", "abc\n\u05D0\u05D1"),
        new("unpaired-surrogates", "\uD800a\uDC00\u05D0\uDFFF"),
        new("supplementary-rtl", "a \U0001E900\U0001E901 12 z"),
        new("supplementary-neutral", "\u05D0\U0001F600(\u05D1"),
        new("brackets", "abc [\u05D0\u05D1 (12)] xyz"),
        new("ligatures", "\u05D0 \uFB01 \uFEFB abc")
    ];

    private static readonly int[] Directions =
    [
        JavaBidi.DirectionLeftToRight,
        JavaBidi.DirectionRightToLeft,
        JavaBidi.DirectionDefaultLeftToRight,
        JavaBidi.DirectionDefaultRightToLeft
    ];

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2)
            {
                throw new ArgumentException(
                    "Expected output trace and PDFBox BidiMirroring.txt.");
            }
            LoadMirroring(args[1]);

            foreach (var value in Cases)
            {
                ObserveCase(value);
            }
            foreach (var value in StressCases())
            {
                ObserveCase(value);
            }
            foreach (var value in RandomCases())
            {
                Observe("input", value.Id, Utf16(value.Text));
                foreach (var direction in Directions)
                {
                    ObserveBidi(
                        $"{value.Id}-{direction}", value.Text, direction);
                }
            }

            sbyte[][] reorderCases =
            [
                [0, 1, 1],
                [1, 1, 0],
                [0, 1, 2, 2, 1, 0],
                [3, 2, 2, 1],
                [0, 0, 0],
                [126, 125, 124, 125]
            ];
            for (var index = 0; index < reorderCases.Length; index++)
            {
                ObserveReorder($"case-{index}", reorderCases[index]);
            }

            int[] mirrored =
            [
                '(', ')', '<', '>', '[', ']', '{', '}',
                0x2045, 0x2046, 0x2140, 0x2201, 0x2211, 0x221B,
                0x22A6, 0x22A7, 0x22A8, 0x22A9, 0x1D6DB, 0x1D715,
                0x0041, 0x05D0, 0x1F600
            ];
            foreach (var codePoint in mirrored)
            {
                Observe(
                    "mirrored",
                    $"u+{codePoint:x4}",
                    Lower(JavaBidi.IsMirrored(codePoint)));
            }
            ObserveMirroredProperty();

            File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveBidi(string id, string text, int direction)
    {
        var bidi = new JavaBidi(text, direction);
        var value = new StringBuilder();
        value.Append("base=").Append(bidi.GetBaseLevel());
        value.Append(",mixed=").Append(Lower(bidi.IsMixed()));
        value.Append(",ltr=").Append(Lower(IsLeftToRight(bidi, text.Length)));
        value.Append(",rtl=").Append(Lower(IsRightToLeft(bidi, text.Length)));
        value.Append(",runs=").Append(bidi.GetRunCount());
        for (var run = 0; run < bidi.GetRunCount(); run++)
        {
            value.Append(';')
                .Append(bidi.GetRunStart(run)).Append('-')
                .Append(bidi.GetRunLimit(run)).Append('-')
                .Append(bidi.GetRunLevel(run));
        }
        Observe("analysis", id, value);
    }

    private static void ObserveCase(Case value)
    {
        foreach (var direction in Directions)
        {
            ObserveBidi(
                $"{value.Id}-{direction}", value.Text, direction);
        }
        Observe(
            "direction", value.Id, Utf16(HandleDirection(value.Text)));
    }

    private static IEnumerable<Case> StressCases()
    {
        yield return new Case(
            "deep-embedding",
            "a" + new string('\u202B', 130) + "\u05D0\u05D1 12"
                + new string('\u202C', 130) + "z");
        yield return new Case(
            "deep-isolates",
            "a" + new string('\u2067', 130) + "\u05D0\u05D1 12"
                + new string('\u2069', 130) + "z");
        yield return new Case(
            "overflow-overrides",
            string.Concat(Enumerable.Repeat("\u202D\u202E", 70))
                + "abc \u05D0\u05D1" + new string('\u202C', 140));
        yield return new Case(
            "stray-controls-heavy",
            string.Concat(Enumerable.Repeat("\u202C\u2069", 130))
                + "a \u05D0 12");
        yield return new Case(
            "crlf-reset",
            "a\u202B\u05D0\r\n\u202Eabc\u202C\r\u05D1\nz");
    }

    private static bool IsLeftToRight(JavaBidi bidi, int length)
    {
        return !bidi.IsMixed() && bidi.GetBaseLevel() == 0;
    }

    private static bool IsRightToLeft(JavaBidi bidi, int length)
    {
        return !bidi.IsMixed() && bidi.GetBaseLevel() == 1;
    }

    private static void ObserveReorder(string id, sbyte[] levels)
    {
        var objects = Enumerable.Range(0, levels.Length + 2).ToArray();
        JavaBidi.ReorderVisually(levels, 0, objects, 1, levels.Length);
        Observe("reorder", id, string.Join(",", objects));
    }

    private static IEnumerable<Case> RandomCases()
    {
        int[] alphabet =
        [
            0x0041, 0x0062, 0x05D0, 0x05D1, 0x0627, 0x0644,
            0x0031, 0x0661, 0x002B, 0x002C, 0x002E, 0x0024,
            0x0301, 0x0020, 0x000A, 0x0009, 0x00AD,
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069,
            0x0028, 0x0029, 0x005B, 0x005D,
            0x200E, 0x200F, 0x061C, 0x1E900, 0x1F600
        ];
        uint state = 0x5EEDB1D1;
        for (var caseIndex = 0; caseIndex < 250; caseIndex++)
        {
            state = Next(state);
            var length = 1 + (int)(state % 40);
            var text = new StringBuilder();
            for (var index = 0; index < length; index++)
            {
                state = Next(state);
                text.Append(char.ConvertFromUtf32(
                    alphabet[state % (uint)alphabet.Length]));
            }
            yield return new Case($"random-{caseIndex}", text.ToString());
        }
    }

    private static uint Next(uint state) =>
        unchecked(state * 1664525U + 1013904223U);

    private static void ObserveMirroredProperty()
    {
        const ulong offset = 1469598103934665603UL;
        const ulong prime = 1099511628211UL;
        var hash = offset;
        var count = 0;
        for (var codePoint = 0; codePoint <= 0x10FFFF; codePoint++)
        {
            if (JavaBidi.IsMirrored(codePoint))
            {
                count++;
                hash ^= (uint)codePoint;
                hash *= prime;
            }
        }
        Observe("mirrored", "all", $"{count}:{hash:x}");
    }

    private static string HandleDirection(string word)
    {
        var bidi = new JavaBidi(
            word, JavaBidi.DirectionDefaultLeftToRight);
        if (!bidi.IsMixed()
            && bidi.GetBaseLevel() == JavaBidi.DirectionLeftToRight)
        {
            return word;
        }

        var runCount = bidi.GetRunCount();
        var levels = new sbyte[runCount];
        var runs = new int[runCount];
        for (var index = 0; index < runCount; index++)
        {
            levels[index] = checked((sbyte)bidi.GetRunLevel(index));
            runs[index] = index;
        }
        JavaBidi.ReorderVisually(levels, 0, runs, 0, runCount);

        var result = new StringBuilder();
        for (var visualRun = 0; visualRun < runCount; visualRun++)
        {
            var index = runs[visualRun];
            var start = bidi.GetRunStart(index);
            var end = bidi.GetRunLimit(index);
            if ((levels[index] & 1) != 0)
            {
                while (--end >= start)
                {
                    var character = word[end];
                    var codePoint = CodePointAt(word, end);
                    if (JavaBidi.IsMirrored(codePoint))
                    {
                        result.Append(MirroringCharMap.GetValueOrDefault(
                            character, character));
                    }
                    else
                    {
                        result.Append(character);
                    }
                }
            }
            else
            {
                result.Append(word, start, end - start);
            }
        }
        return result.ToString();
    }

    private static void LoadMirroring(string path)
    {
        foreach (var original in File.ReadLines(path, Encoding.ASCII))
        {
            var line = original;
            var comment = line.IndexOf('#');
            if (comment >= 0)
            {
                line = line[..comment];
            }
            var fields = line.Split(';');
            if (fields.Length == 2)
            {
                MirroringCharMap.Add(
                    (char)int.Parse(
                        fields[0].Trim(),
                        NumberStyles.AllowHexSpecifier,
                        CultureInfo.InvariantCulture),
                    (char)int.Parse(
                        fields[1].Trim(),
                        NumberStyles.AllowHexSpecifier,
                        CultureInfo.InvariantCulture));
            }
        }
    }

    private static string Utf16(string value) =>
        string.Join(",", value.Select(character => ((int)character).ToString(
            "x4", CultureInfo.InvariantCulture)));

    private static int CodePointAt(string value, int index)
    {
        var high = value[index];
        if (char.IsHighSurrogate(high)
            && index + 1 < value.Length
            && char.IsLowSurrogate(value[index + 1]))
        {
            return char.ConvertToUtf32(high, value[index + 1]);
        }
        return high;
    }

    private static string Lower(bool value) => value ? "true" : "false";

    private static void Observe(string family, string id, object value)
    {
        Observations.Add($"{family}\t{id}\t{value}");
    }
}
