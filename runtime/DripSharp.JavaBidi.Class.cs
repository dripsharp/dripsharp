// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace DripSharp.Runtime
{
    internal enum BidiClass
    {
        LeftToRight, //L
        ArabicLetter, //AL
        ArabicNumber, //AN
        ParagraphSeparator, //B
        BoundaryNeutral, //BN
        CommonSeparator, //CS
        EuropeanNumber, //EN
        EuropeanSeparator, //ES
        EuropeanTerminator, //ET
        FirstStrongIsolate, //FSI
        LeftToRightEmbedding, //LRE
        LeftToRightIsolate, //LRI
        LeftToRightOverride, //LRO
        NonspacingMark, //NSM
        OtherNeutral, //ON
        PopDirectionalFormat, //PDF
        PopDirectionalIsolate, //PDI
        RightToLeft, //R
        RightToLeftEmbedding, //RLE
        RightToLeftIsolate, //RLI
        RightToLeftOverride, //RLO
        SegmentSeparator, //S
        WhiteSpace, //WS
    }
}
