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
using System.Text;

namespace DripSharp.Runtime;

internal sealed class JavaBidi
{
    internal const int DirectionLeftToRight = 0;
    internal const int DirectionRightToLeft = 1;
    internal const int DirectionDefaultLeftToRight = -2;
    internal const int DirectionDefaultRightToLeft = -1;

    private readonly int baseLevel;
    private readonly bool mixed;
    private readonly Run[] runs;
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
            mixed = false;
            runs = [];
            return;
        }

        baseLevel = 0;
        var charLevels = new sbyte[paragraph.Length];
        var directionState = new DirectionState();
        var paragraphStart = 0;
        var firstParagraph = true;
        while (paragraphStart < paragraph.Length)
        {
            var paragraphLimit = FindParagraphLimit(paragraph, paragraphStart);
            var data = new BidiData();
            data.Append(paragraph.AsSpan(
                paragraphStart, paragraphLimit - paragraphStart));
            data.ParagraphEmbeddingLevel = ResolveRequestedLevel(data, direction);
            var paragraphLevel = data.ParagraphEmbeddingLevel;
            if (firstParagraph)
            {
                baseLevel = paragraphLevel;
                directionState.AddLevel(baseLevel);
            }

            var algorithm = new BidiAlgorithm();
            algorithm.Process(data);
            var codePointLevels = algorithm.ResolvedLevels.Span.ToArray();
            var followingLevel = paragraphLevel;
            for (var index = codePointLevels.Length - 1;
                    index >= 0;
                    index--)
            {
                if (IsRemovedByRuleX9(data.Classes[index]))
                {
                    // java.text.Bidi assigns X9 formatting codes to the
                    // following logical run during its L1 adjustment.
                    codePointLevels[index] = followingLevel;
                }
                else
                {
                    followingLevel = codePointLevels[index];
                }
            }
            CollectResolvedDirection(
                ref directionState,
                data.Classes,
                codePointLevels,
                paragraphLevel);

            var codePointIndex = 0;
            var charIndex = paragraphStart;
            while (charIndex < paragraphLimit)
            {
                GetCodePointAt(paragraph, charIndex, out var charCount);
                var level = codePointLevels[codePointIndex++];
                for (var offset = 0; offset < charCount; offset++)
                {
                    charLevels[charIndex + offset] = level;
                }
                charIndex += charCount;
            }

            firstParagraph = false;
            paragraphStart = paragraphLimit;
        }
        mixed = directionState.Resolve() == ResolvedDirection.Mixed;
        if (!mixed)
        {
            Array.Fill(charLevels, checked((sbyte)baseLevel));
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
        return mixed;
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
        JavaBidiMirroringData.IsMirrored(codepoint);

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
        var isolateDepth = 0;
        foreach (var bidiClass in data.Classes)
        {
            if (bidiClass is BidiClass.LeftToRightIsolate
                or BidiClass.RightToLeftIsolate
                or BidiClass.FirstStrongIsolate)
            {
                isolateDepth++;
                continue;
            }
            if (bidiClass == BidiClass.PopDirectionalIsolate)
            {
                if (isolateDepth > 0)
                {
                    isolateDepth--;
                }
                continue;
            }
            if (isolateDepth != 0)
            {
                continue;
            }
            if (bidiClass == BidiClass.LeftToRight)
            {
                return 0;
            }
            if (bidiClass is BidiClass.RightToLeft or BidiClass.ArabicLetter)
            {
                return 1;
            }
        }
        return direction == DirectionDefaultRightToLeft ? (sbyte)1 : (sbyte)0;
    }

    private static void CollectResolvedDirection(
        ref DirectionState state,
        ArraySlice<BidiClass> classes,
        sbyte[] levels,
        sbyte paragraphLevel)
    {
        var statusStack = new Stack<OverrideStatus>();
        statusStack.Push(new OverrideStatus(
            BidiClass.OtherNeutral, false));
        var validIsolateCount = 0;

        for (var index = 0; index < classes.Length; index++)
        {
            var bidiClass = classes[index];
            if (bidiClass == BidiClass.FirstStrongIsolate)
            {
                bidiClass = ResolveFirstStrongIsolate(classes, index);
            }

            switch (bidiClass)
            {
                case BidiClass.LeftToRightEmbedding:
                case BidiClass.RightToLeftEmbedding:
                    statusStack.Push(new OverrideStatus(
                        BidiClass.OtherNeutral, false));
                    state.AddClass(BidiClass.BoundaryNeutral);
                    break;
                case BidiClass.LeftToRightOverride:
                    statusStack.Push(new OverrideStatus(
                        BidiClass.LeftToRight, false));
                    state.AddClass(BidiClass.BoundaryNeutral);
                    break;
                case BidiClass.RightToLeftOverride:
                    statusStack.Push(new OverrideStatus(
                        BidiClass.RightToLeft, false));
                    state.AddClass(BidiClass.BoundaryNeutral);
                    break;
                case BidiClass.PopDirectionalFormat:
                    if (statusStack.Count > 1
                        && !statusStack.Peek().Isolate)
                    {
                        statusStack.Pop();
                    }
                    state.AddClass(BidiClass.BoundaryNeutral);
                    break;
                case BidiClass.LeftToRightIsolate:
                case BidiClass.RightToLeftIsolate:
                    state.AddClass(bidiClass);
                    if (levels[index] != paragraphLevel)
                    {
                        state.AddLevel(levels[index]);
                    }
                    statusStack.Push(new OverrideStatus(
                        BidiClass.OtherNeutral, true));
                    validIsolateCount++;
                    break;
                case BidiClass.PopDirectionalIsolate:
                    if (validIsolateCount > 0)
                    {
                        while (statusStack.Count > 1
                            && !statusStack.Peek().Isolate)
                        {
                            statusStack.Pop();
                        }
                        if (statusStack.Count > 1)
                        {
                            statusStack.Pop();
                        }
                        validIsolateCount--;
                    }
                    state.AddClass(bidiClass);
                    if (levels[index] != paragraphLevel)
                    {
                        state.AddLevel(levels[index]);
                    }
                    break;
                case BidiClass.BoundaryNeutral:
                    state.AddClass(bidiClass);
                    break;
                default:
                    var overrideClass = statusStack.Peek().OverrideClass;
                    state.AddClass(
                        overrideClass == BidiClass.OtherNeutral
                            ? bidiClass
                            : overrideClass);
                    if (levels[index] != paragraphLevel)
                    {
                        state.AddLevel(levels[index]);
                    }
                    break;
            }
        }
    }

    private static BidiClass ResolveFirstStrongIsolate(
        ArraySlice<BidiClass> classes, int start)
    {
        var isolateDepth = 0;
        for (var index = start + 1; index < classes.Length; index++)
        {
            var bidiClass = classes[index];
            if (bidiClass is BidiClass.LeftToRightIsolate
                or BidiClass.RightToLeftIsolate
                or BidiClass.FirstStrongIsolate)
            {
                isolateDepth++;
                continue;
            }
            if (bidiClass == BidiClass.PopDirectionalIsolate)
            {
                if (isolateDepth == 0)
                {
                    break;
                }
                isolateDepth--;
                continue;
            }
            if (isolateDepth != 0)
            {
                continue;
            }
            if (bidiClass == BidiClass.LeftToRight)
            {
                return BidiClass.LeftToRightIsolate;
            }
            if (bidiClass is BidiClass.RightToLeft or BidiClass.ArabicLetter)
            {
                return BidiClass.RightToLeftIsolate;
            }
        }
        return BidiClass.LeftToRightIsolate;
    }

    private static int FindParagraphLimit(string text, int start)
    {
        var index = start;
        while (index < text.Length)
        {
            var codePoint = GetCodePointAt(text, index, out var charCount);
            index += charCount;
            if (BidiUnicodeData.GetBiDiClass(codePoint)
                == BidiClass.ParagraphSeparator)
            {
                if (codePoint == '\r'
                    && index < text.Length
                    && text[index] == '\n')
                {
                    index++;
                }
                break;
            }
        }
        return index;
    }

    private static uint GetCodePointAt(
        ReadOnlySpan<char> text, int index, out int charCount)
    {
        var high = text[index];
        if (char.IsHighSurrogate(high)
            && index + 1 < text.Length
            && char.IsLowSurrogate(text[index + 1]))
        {
            charCount = 2;
            return checked((uint)char.ConvertToUtf32(high, text[index + 1]));
        }
        charCount = 1;
        return high;
    }

    private static bool IsRemovedByRuleX9(BidiClass bidiClass) =>
        bidiClass is BidiClass.LeftToRightEmbedding
            or BidiClass.RightToLeftEmbedding
            or BidiClass.LeftToRightOverride
            or BidiClass.RightToLeftOverride
            or BidiClass.PopDirectionalFormat
            or BidiClass.BoundaryNeutral;

    private enum ResolvedDirection
    {
        LeftToRight,
        RightToLeft,
        Mixed
    }

    private struct DirectionState
    {
        private bool hasLeftToRight;
        private bool hasRightToLeft;
        private bool hasArabicNumber;
        private bool hasPossibleNeutral;

        internal void AddLevel(int level)
        {
            if ((level & 1) == 0)
            {
                hasLeftToRight = true;
            }
            else
            {
                hasRightToLeft = true;
            }
        }

        internal void AddClass(BidiClass bidiClass)
        {
            switch (bidiClass)
            {
                case BidiClass.LeftToRight:
                case BidiClass.EuropeanNumber:
                case BidiClass.LeftToRightIsolate:
                    hasLeftToRight = true;
                    break;
                case BidiClass.ArabicNumber:
                    hasLeftToRight = true;
                    hasArabicNumber = true;
                    break;
                case BidiClass.RightToLeft:
                case BidiClass.ArabicLetter:
                case BidiClass.RightToLeftIsolate:
                    hasRightToLeft = true;
                    break;
            }

            if (bidiClass is BidiClass.OtherNeutral
                or BidiClass.CommonSeparator
                or BidiClass.EuropeanSeparator
                or BidiClass.EuropeanTerminator
                or BidiClass.ParagraphSeparator
                or BidiClass.SegmentSeparator
                or BidiClass.WhiteSpace
                or BidiClass.BoundaryNeutral
                or BidiClass.LeftToRightIsolate
                or BidiClass.RightToLeftIsolate
                or BidiClass.FirstStrongIsolate
                or BidiClass.PopDirectionalIsolate)
            {
                hasPossibleNeutral = true;
            }
        }

        internal readonly ResolvedDirection Resolve()
        {
            if (!hasRightToLeft
                && !(hasArabicNumber && hasPossibleNeutral))
            {
                return ResolvedDirection.LeftToRight;
            }
            return !hasLeftToRight
                ? ResolvedDirection.RightToLeft
                : ResolvedDirection.Mixed;
        }
    }

    private readonly record struct OverrideStatus(
        BidiClass OverrideClass, bool Isolate);
    private readonly record struct Run(int Start, int Limit, int Level);
}
