// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused lookup facade over the pinned generated Avalonia bidirectional trie.
#nullable enable

namespace DripSharp.Runtime;

internal static class BidiUnicodeData
{
    internal static BidiClass GetBiDiClass(uint codepoint) =>
        (BidiClass)((BiDiTrie.Trie.Get(codepoint) >> 18) & 0x1f);

    internal static BidiPairedBracketType GetBiDiPairedBracketType(uint codepoint) =>
        (BidiPairedBracketType)((BiDiTrie.Trie.Get(codepoint) >> 16) & 0x03);

    internal static uint GetBiDiPairedBracket(uint codepoint) =>
        BiDiTrie.Trie.Get(codepoint) & 0xffff;

    internal static uint GetCanonicalType(uint codepoint) =>
        codepoint switch
        {
            0x3008 => 0x2329,
            0x3009 => 0x232a,
            _ => codepoint
        };
}
