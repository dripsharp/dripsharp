// Java-compatible Unicode Bidirectional Algorithm facade.
//
// The underlying UAX #9 implementation and generated Unicode trie are vendored
// from AvaloniaUI/Avalonia commit 0b3243e9c074d6d77f8e6fba5b718c0ef89c9d9c.
// BidiAlgorithm and its supporting buffers are Copyright (c) Six Labors and
// licensed under the Apache License, Version 2.0. UnicodeTrie is Copyright
// (c) 2019 Topten Software and licensed under the Apache License, Version 2.0.
#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace DripSharp.Runtime;

internal sealed class JavaBidi
{
    internal const int DirectionLeftToRight = 0;
    internal const int DirectionRightToLeft = 1;
    internal const int DirectionDefaultLeftToRight = -2;
    internal const int DirectionDefaultRightToLeft = -1;

    private readonly int baseLevel;
    private readonly Run[] runs;
    private static readonly Lazy<HashSet<int>> MirroredCodePoints = new(LoadMirroredCodePoints);

    internal JavaBidi(string paragraph, int direction)
    {
        ArgumentNullException.ThrowIfNull(paragraph);
        if (direction is not (DirectionLeftToRight
            or DirectionRightToLeft
            or DirectionDefaultLeftToRight
            or DirectionDefaultRightToLeft))
        {
            throw new ArgumentException("bad direction flag", nameof(direction));
        }

        if (paragraph.Length == 0)
        {
            baseLevel = direction is DirectionRightToLeft
                or DirectionDefaultRightToLeft ? 1 : 0;
            runs = [];
            return;
        }

        var data = new BidiData();
        data.Append(paragraph);
        data.ParagraphEmbeddingLevel = ResolveRequestedLevel(data, direction);

        var algorithm = new BidiAlgorithm();
        algorithm.Process(data);
        baseLevel = algorithm.ResolvedParagraphEmbeddingLevel;

        var charLevels = new sbyte[paragraph.Length];
        var codePointIndex = 0;
        var charIndex = 0;
        foreach (var rune in paragraph.EnumerateRunes())
        {
            var level = algorithm.ResolvedLevels[codePointIndex++];
            for (var offset = 0; offset < rune.Utf16SequenceLength; offset++)
            {
                charLevels[charIndex++] = level;
            }
        }

        var collected = new List<Run>();
        var start = 0;
        while (start < charLevels.Length)
        {
            var level = charLevels[start];
            var limit = start + 1;
            while (limit < charLevels.Length && charLevels[limit] == level)
            {
                limit++;
            }
            collected.Add(new Run(start, limit, level));
            start = limit;
        }
        runs = collected.ToArray();
    }

    internal bool IsMixed()
    {
        var hasLeftToRight = false;
        var hasRightToLeft = false;
        foreach (var run in runs)
        {
            if ((run.Level & 1) == 0)
            {
                hasLeftToRight = true;
            }
            else
            {
                hasRightToLeft = true;
            }
        }
        return hasLeftToRight && hasRightToLeft;
    }

    internal int GetBaseLevel() => baseLevel;
    internal int GetRunCount() => runs.Length;
    internal int GetRunLevel(int run) => GetRun(run).Level;
    internal int GetRunStart(int run) => GetRun(run).Start;
    internal int GetRunLimit(int run) => GetRun(run).Limit;

    internal static void ReorderVisually<T>(
        sbyte[] levels, int levelStart, T[] objects, int objectStart, int count)
    {
        ArgumentNullException.ThrowIfNull(levels);
        ArgumentNullException.ThrowIfNull(objects);
        if (levelStart < 0 || objectStart < 0 || count < 0
            || levelStart > levels.Length - count
            || objectStart > objects.Length - count)
        {
            throw new ArgumentException("bad range");
        }
        if (count <= 1)
        {
            return;
        }

        var lowestOddLevel = int.MaxValue;
        var highestLevel = 0;
        for (var i = 0; i < count; i++)
        {
            var level = levels[levelStart + i] & 0xff;
            highestLevel = Math.Max(highestLevel, level);
            if ((level & 1) != 0)
            {
                lowestOddLevel = Math.Min(lowestOddLevel, level);
            }
        }
        if (lowestOddLevel == int.MaxValue)
        {
            return;
        }

        for (var level = highestLevel; level >= lowestOddLevel; level--)
        {
            var index = 0;
            while (index < count)
            {
                while (index < count && (levels[levelStart + index] & 0xff) < level)
                {
                    index++;
                }
                var begin = index;
                while (index < count && (levels[levelStart + index] & 0xff) >= level)
                {
                    index++;
                }
                Array.Reverse(objects, objectStart + begin, index - begin);
            }
        }
    }

    internal static bool IsMirrored(int codepoint) =>
        MirroredCodePoints.Value.Contains(codepoint)
        || BidiUnicodeData.GetBiDiPairedBracket((uint)codepoint) != 0;

    private Run GetRun(int run) =>
        (uint)run < (uint)runs.Length
            ? runs[run]
            : throw new ArgumentException("bad run index", nameof(run));

    private static sbyte ResolveRequestedLevel(BidiData data, int direction)
    {
        if (direction == DirectionLeftToRight)
        {
            return 0;
        }
        if (direction == DirectionRightToLeft)
        {
            return 1;
        }
        if (direction == DirectionDefaultLeftToRight)
        {
            return 2;
        }

        foreach (var bidiClass in data.Classes)
        {
            if (bidiClass == BidiClass.LeftToRight)
            {
                return 2;
            }
            if (bidiClass is BidiClass.RightToLeft or BidiClass.ArabicLetter)
            {
                return 2;
            }
        }
        return 1;
    }

    private static HashSet<int> LoadMirroredCodePoints()
    {
        var result = new HashSet<int>();
        var assembly = typeof(JavaBidi).Assembly;
        var resourceName = assembly.GetManifestResourceNames()
            .SingleOrDefault(name => name.EndsWith(
                "BidiMirroring.txt", StringComparison.Ordinal));
        if (resourceName is null)
        {
            return result;
        }

        using var stream = assembly.GetManifestResourceStream(resourceName);
        if (stream is null)
        {
            return result;
        }
        using var reader = new StreamReader(stream, Encoding.ASCII, true);
        while (reader.ReadLine() is { } line)
        {
            var comment = line.IndexOf('#');
            if (comment >= 0)
            {
                line = line[..comment];
            }
            var separator = line.IndexOf(';');
            if (separator < 0)
            {
                continue;
            }
            if (int.TryParse(line.AsSpan(0, separator).Trim(),
                    NumberStyles.AllowHexSpecifier,
                    CultureInfo.InvariantCulture,
                    out var codepoint))
            {
                result.Add(codepoint);
            }
        }
        return result;
    }

    private readonly record struct Run(int Start, int Limit, int Level);
}
