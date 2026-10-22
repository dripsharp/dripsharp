// SPDX-FileCopyrightText: 1991-1998 Thomas G. Lane
// SPDX-FileCopyrightText: 2002-2018 Guido Vollbeding
// SPDX-FileCopyrightText: 1999 Ken Murchison
// SPDX-FileCopyrightText: 2009-2026 D. R. Commander
// SPDX-FileCopyrightText: 2009 Pierre Ossman for Cendio AB
// SPDX-FileCopyrightText: 2013 Linaro Limited
// SPDX-FileCopyrightText: 2015, 2020 Google, Inc.
// SPDX-FileCopyrightText: 2018 Matthias Räncker
// SPDX-FileCopyrightText: 2019-2020 Arm Limited
// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: IJG AND Apache-2.0

// A C# port of parts of the libjpeg API library of libjpeg-turbo 3.2.0
// (https://github.com/libjpeg-turbo/libjpeg-turbo, tag 3.2.0, commit
// c85e6b905bf237038faa936dab160ebfc5da0344): jdhuff.c, jdphuff.c,
// jdcoefct.c, jidctint.c, jdsample.c, jdinput.c, jdmarker.c, jdmaster.c and
// jutils.c. This software is based in part on the work of the Independent
// JPEG Group. The port is modified from those files by Isak Sky; the NOTICE
// file beside it reproduces their copyright notices and lists the changes,
// and README.ijg gives the IJG License's conditions of distribution and use.
#nullable enable

using System;
using System.Buffers;
using System.Runtime.InteropServices;

namespace DripSharp.Runtime;

// The stored component samples of a JPEG, without colour conversion: what
// ImageIO's readRaster returns for a four-component (CMYK or YCCK) JPEG and
// DCTFilter then interprets. Skia only decodes to RGB, folding K into the
// colour, so these samples are decoded here. The result matches the libjpeg
// behind the reference OpenJDK 25's ImageIO (the repository's pinned Java
// toolchain, which links libjpeg-turbo 3.2), checked byte for byte: the
// ISLOW integer IDCT, triangle-filter ("fancy") h2v1, h2v2 and h1v2
// upsampling, replication for other integral ratios, zero coefficients for
// the rest of a restart interval whose entropy-coded data ends early, and
// libjpeg-turbo's block smoothing of incomplete progressive images.
internal static class PdfCartonJpegSamples
{
    // Returns null for a process outside baseline, extended sequential and
    // progressive Huffman coding with 8-bit samples (lossless, hierarchical
    // and arithmetic coding, 12-bit samples, DNL-defined heights), and for
    // fractional sampling ratios, which libjpeg rejects.
    internal static JavaRaster? Decode(byte[] data)
    {
        ArgumentNullException.ThrowIfNull(data);
        return new Decoder(data).Decode();
    }

    // Natural (row-major) positions of the zig-zag coefficient order, with
    // libjpeg's 16 extra entries that absorb a corrupt run past 63.
    private static readonly int[] NaturalOrder =
    [
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63, 63
    ];

    // Block smoothing estimates the first ten zig-zag coefficients.
    private const int SmoothedCoefficients = 10;

    // libjpeg's IDCT output range limit: the descaled value masked to ten
    // bits, read as signed, plus 128, clamped to 0..255.
    private static readonly byte[] RangeLimit = CreateRangeLimit();

    private static byte[] CreateRangeLimit()
    {
        var table = new byte[1024];
        for (var index = 0; index < table.Length; index++)
        {
            var value = (index >= 512 ? index - 1024 : index) + 128;
            table[index] = (byte)Math.Min(255, Math.Max(0, value));
        }
        return table;
    }

    private sealed class Component
    {
        internal int Id;
        internal int H;
        internal int V;
        internal int QuantizationTable;
        internal int[]? Quantization;
        internal int DcTable;
        internal int AcTable;
        internal int Prediction;
        internal int BlocksPerLine;
        internal int BlocksPerColumn;
        // Blocks that hold image samples (libjpeg's width_in_blocks).
        internal int UsedBlocksPerLine;
        internal int UsedBlocksPerColumn;
        // Sample columns and rows of the component (downsampled size).
        internal int Width;
        internal int Height;
        internal short[] Coefficients = [];
        // The point transform (Al) of the last progressive scan of each of
        // the first ten zig-zag coefficients, -1 before any scan, and its
        // value before that scan: libjpeg-turbo's coef_bits, which decide
        // block smoothing.
        internal readonly int[] CoefficientBits = [-1, -1, -1, -1, -1, -1, -1, -1, -1, -1];
        internal readonly int[] PreviousCoefficientBits = new int[SmoothedCoefficients];
    }

    private sealed class HuffmanTable
    {
        // False for counts that overflow the code space or use an all-ones
        // code, which libjpeg rejects.
        internal readonly bool Valid = true;
        // Codes of up to FastBits bits: (length << 8 | value), 0 if longer.
        internal const int FastBits = 9;
        internal readonly ushort[] Fast = new ushort[1 << FastBits];
        internal readonly int[] MaxCode = new int[18];
        internal readonly int[] ValueOffset = new int[17];
        internal readonly byte[] Values = new byte[256];

        internal HuffmanTable(ReadOnlySpan<byte> counts, ReadOnlySpan<byte> values)
        {
            values.CopyTo(Values);
            var code = 0;
            var index = 0;
            for (var length = 1; length <= 16; length++)
            {
                var count = counts[length - 1];
                ValueOffset[length] = index - code;
                if (count > 0)
                {
                    if (code + count >= 1 << length)
                    {
                        Valid = false;
                        return;
                    }
                    for (var i = 0; i < count; i++, index++, code++)
                    {
                        if (length > FastBits) continue;
                        var shift = FastBits - length;
                        var entry = (ushort)(length << 8 | Values[index]);
                        for (var fill = 0; fill < 1 << shift; fill++)
                            Fast[(code << shift) | fill] = entry;
                    }
                    MaxCode[length] = code - 1;
                }
                else
                {
                    MaxCode[length] = -1;
                }
                code <<= 1;
            }
            MaxCode[17] = int.MaxValue;
        }
    }

    private sealed class Decoder
    {
        private readonly byte[] data;
        private readonly int[]?[] quantizationTables = new int[]?[4];
        private readonly HuffmanTable?[] dcTables = new HuffmanTable?[4];
        private readonly HuffmanTable?[] acTables = new HuffmanTable?[4];
        private Component[] components = [];
        private int width;
        private int height;
        private int maxH;
        private int maxV;
        private int mcusPerLine;
        private int mcusPerColumn;
        private bool progressive;
        private int restartInterval;
        private int position;
        private ulong bits;
        private int bitCount;
        private bool atMarker;
        // Zero bits loaded past the end of the entropy-coded data.
        private int paddingBits;
        // Set when a read used padding: like libjpeg, the decoder then leaves
        // the rest of the restart interval's blocks zero.
        private bool insufficientData;
        private int endOfBandRun;
        // The number of scans started (libjpeg's input_scan_number).
        private int scanNumber;
        private int lastGoodRow;

        internal Decoder(byte[] data) => this.data = data;

        // The coefficient and sample buffers are pooled: only the returned
        // raster outlives the decode.
        internal JavaRaster? Decode()
        {
            try
            {
                return DecodeImage();
            }
            finally
            {
                // A rejected frame header can leave components unset.
                foreach (var component in components)
                {
                    if (component is not null) ReturnCoefficients(component);
                }
            }
        }

        private static void ReturnCoefficients(Component component)
        {
            if (component.Coefficients.Length == 0) return;
            ArrayPool<short>.Shared.Return(component.Coefficients);
            component.Coefficients = [];
        }

        private JavaRaster? DecodeImage()
        {
            position = FindStartOfImage();
            if (position < 0) return null;
            var frame = false;
            var scanned = false;
            while (true)
            {
                var marker = NextMarker();
                if (marker < 0 || marker == 0xd9) break;
                if (marker is 0x01 or 0xd8 or >= 0xd0 and <= 0xd7) continue;
                if (position + 2 > data.Length) break;
                var length = data[position] << 8 | data[position + 1];
                if (length < 2 || position + length > data.Length) break;
                var segment = new ReadOnlySpan<byte>(data, position + 2, length - 2);
                var next = position + length;
                switch (marker)
                {
                    case 0xc0 or 0xc1 or 0xc2:
                        if (frame || !ReadFrame(segment, marker == 0xc2)) return null;
                        frame = true;
                        break;
                    case >= 0xc3 and <= 0xcf and not 0xc4 and not 0xc8 and not 0xcc:
                        return null;
                    case 0xc4:
                        if (!ReadHuffmanTables(segment)) return null;
                        break;
                    case 0xdb:
                        if (!ReadQuantizationTables(segment)) return null;
                        break;
                    case 0xdd:
                        if (segment.Length < 2) return null;
                        restartInterval = segment[0] << 8 | segment[1];
                        break;
                    case 0xda:
                        if (!frame) return null;
                        position = next;
                        if (!ReadScan(segment)) return null;
                        scanned = true;
                        continue;
                }
                position = next;
            }
            return frame && scanned ? Output() : null;
        }

        private int FindStartOfImage()
        {
            for (var index = 0; index + 1 < data.Length; index++)
            {
                if (data[index] == 0xff && data[index + 1] == 0xd8) return index + 2;
            }
            return -1;
        }

        // Skips to the next marker and returns its code, positioned after it.
        private int NextMarker()
        {
            while (position + 1 < data.Length)
            {
                if (data[position] != 0xff)
                {
                    position++;
                    continue;
                }
                var code = data[position + 1];
                if (code == 0xff)
                {
                    position++;
                    continue;
                }
                position += 2;
                if (code != 0) return code;
            }
            return -1;
        }

        private bool ReadFrame(ReadOnlySpan<byte> segment, bool isProgressive)
        {
            if (segment.Length < 6 || segment[0] != 8) return false;
            height = segment[1] << 8 | segment[2];
            width = segment[3] << 8 | segment[4];
            var count = segment[5];
            if (width == 0 || height == 0 || count == 0 || segment.Length < 6 + count * 3)
                return false;
            progressive = isProgressive;
            components = new Component[count];
            for (var index = 0; index < count; index++)
            {
                var offset = 6 + index * 3;
                var component = new Component
                {
                    Id = segment[offset],
                    H = segment[offset + 1] >> 4,
                    V = segment[offset + 1] & 15,
                    QuantizationTable = segment[offset + 2]
                };
                if (component.H is < 1 or > 4 || component.V is < 1 or > 4 ||
                    component.QuantizationTable > 3)
                    return false;
                components[index] = component;
                maxH = Math.Max(maxH, component.H);
                maxV = Math.Max(maxV, component.V);
            }
            mcusPerLine = (width + 8 * maxH - 1) / (8 * maxH);
            mcusPerColumn = (height + 8 * maxV - 1) / (8 * maxV);
            foreach (var component in components)
            {
                if (maxH % component.H != 0 || maxV % component.V != 0) return false;
                component.Width = (int)(((long)width * component.H + maxH - 1) / maxH);
                component.Height = (int)(((long)height * component.V + maxV - 1) / maxV);
                component.UsedBlocksPerLine = (component.Width + 7) / 8;
                component.UsedBlocksPerColumn = (component.Height + 7) / 8;
                component.BlocksPerLine = mcusPerLine * component.H;
                component.BlocksPerColumn = mcusPerColumn * component.V;
                var length = checked(component.BlocksPerLine * component.BlocksPerColumn * 64);
                component.Coefficients = ArrayPool<short>.Shared.Rent(length);
                Array.Clear(component.Coefficients, 0, length);
            }
            return true;
        }

        private bool ReadQuantizationTables(ReadOnlySpan<byte> segment)
        {
            while (segment.Length > 0)
            {
                var precision = segment[0] >> 4;
                var id = segment[0] & 15;
                var size = precision == 0 ? 64 : 128;
                if (id > 3 || precision > 1 || segment.Length < 1 + size) return false;
                var table = new int[64];
                for (var index = 0; index < 64; index++)
                {
                    table[NaturalOrder[index]] = precision == 0
                        ? segment[1 + index]
                        : segment[1 + index * 2] << 8 | segment[2 + index * 2];
                }
                quantizationTables[id] = table;
                segment = segment.Slice(1 + size);
            }
            return true;
        }

        private bool ReadHuffmanTables(ReadOnlySpan<byte> segment)
        {
            while (segment.Length > 0)
            {
                if (segment.Length < 17) return false;
                var tableClass = segment[0] >> 4;
                var id = segment[0] & 15;
                var counts = segment.Slice(1, 16);
                var total = 0;
                foreach (var count in counts) total += count;
                if (tableClass > 1 || id > 3 || total > 256 || segment.Length < 17 + total)
                    return false;
                var values = segment.Slice(17, total);
                var table = new HuffmanTable(counts, values);
                if (!table.Valid) return false;
                // libjpeg rejects DC symbols above 15.
                if (tableClass == 0)
                {
                    foreach (var value in values)
                        if (value > 15) return false;
                }
                if (tableClass == 0) dcTables[id] = table;
                else acTables[id] = table;
                segment = segment.Slice(17 + total);
            }
            return true;
        }

        private bool ReadScan(ReadOnlySpan<byte> segment)
        {
            if (segment.Length < 1) return false;
            var count = segment[0];
            if (count is < 1 or > 4 || segment.Length < 4 + count * 2) return false;
            var scan = new Component[count];
            for (var index = 0; index < count; index++)
            {
                var id = segment[1 + index * 2];
                var component = Array.Find(components, candidate => candidate.Id == id);
                if (component is null) return false;
                component.DcTable = segment[2 + index * 2] >> 4;
                component.AcTable = segment[2 + index * 2] & 15;
                if (component.DcTable > 3 || component.AcTable > 3) return false;
                // libjpeg latches a component's table when its first scan starts.
                component.Quantization ??= quantizationTables[component.QuantizationTable];
                if (component.Quantization is null) return false;
                scan[index] = component;
            }
            var spectralStart = segment[1 + count * 2];
            var spectralEnd = segment[2 + count * 2];
            var approximationHigh = segment[3 + count * 2] >> 4;
            var approximationLow = segment[3 + count * 2] & 15;
            if (progressive)
            {
                if (spectralEnd > 63 || spectralStart > spectralEnd ||
                    (spectralStart == 0) != (spectralEnd == 0) ||
                    (spectralStart > 0 && count != 1) ||
                    (approximationHigh != 0 && approximationLow != approximationHigh - 1) ||
                    approximationLow > 13)
                    return false;
            }
            else
            {
                spectralStart = 0;
                spectralEnd = 63;
                approximationHigh = 0;
                approximationLow = 0;
            }
            foreach (var component in scan)
            {
                if (spectralStart == 0 && approximationHigh == 0 &&
                    dcTables[component.DcTable] is null)
                    return false;
                if (spectralEnd > 0 && acTables[component.AcTable] is null) return false;
            }
            scanNumber++;
            if (progressive)
            {
                // jdphuff's start_pass: the previous scan's precision, then
                // this scan's point transform for its coefficients.
                foreach (var component in scan)
                {
                    var bits = component.CoefficientBits;
                    for (var k = Math.Min((int)spectralStart, 1); k < SmoothedCoefficients; k++)
                    {
                        component.PreviousCoefficientBits[k] = scanNumber > 1 ? bits[k] : 0;
                    }
                    for (var k = spectralStart; k <= Math.Min((int)spectralEnd, SmoothedCoefficients - 1); k++)
                        bits[k] = approximationLow;
                }
            }

            ResetEntropy();
            foreach (var component in scan) component.Prediction = 0;
            var single = count == 1;
            var mcus = single
                ? scan[0].UsedBlocksPerLine * scan[0].UsedBlocksPerColumn
                : mcusPerLine * mcusPerColumn;
            var restartsLeft = restartInterval;
            for (var mcu = 0; mcu < mcus; mcu++)
            {
                // libjpeg-turbo's last_good_iMCU_row: the iMCU row of the last
                // MCU started before the data ran out.
                if (!insufficientData)
                {
                    lastGoodRow = single
                        ? mcu / scan[0].UsedBlocksPerLine / scan[0].V
                        : mcu / mcusPerLine;
                }
                if (restartInterval > 0)
                {
                    if (restartsLeft == 0)
                    {
                        Restart();
                        foreach (var component in scan) component.Prediction = 0;
                        restartsLeft = restartInterval;
                    }
                    restartsLeft--;
                }
                if (insufficientData) continue;
                if (single)
                {
                    var component = scan[0];
                    var row = mcu / component.UsedBlocksPerLine;
                    var column = mcu % component.UsedBlocksPerLine;
                    DecodeBlock(component, row, column, spectralStart, spectralEnd,
                        approximationHigh, approximationLow);
                    continue;
                }
                var mcuRow = mcu / mcusPerLine;
                var mcuColumn = mcu % mcusPerLine;
                foreach (var component in scan)
                {
                    for (var v = 0; v < component.V; v++)
                    {
                        for (var h = 0; h < component.H; h++)
                        {
                            DecodeBlock(component, mcuRow * component.V + v,
                                mcuColumn * component.H + h, spectralStart, spectralEnd,
                                approximationHigh, approximationLow);
                        }
                    }
                }
            }
            return true;
        }

        private void DecodeBlock(
            Component component, int row, int column,
            int spectralStart, int spectralEnd, int approximationHigh, int approximationLow)
        {
            var offset = (row * component.BlocksPerLine + column) * 64;
            var block = component.Coefficients.AsSpan(offset, 64);
            if (!progressive)
            {
                DecodeSequential(component, block);
            }
            else if (spectralStart == 0)
            {
                if (approximationHigh == 0)
                {
                    var difference = DecodeDifference(dcTables[component.DcTable]!);
                    component.Prediction += difference;
                    block[0] = (short)(component.Prediction << approximationLow);
                }
                else if (ReadBit() != 0)
                {
                    block[0] = (short)((ushort)block[0] | 1 << approximationLow);
                }
            }
            else if (approximationHigh == 0)
            {
                DecodeAcFirst(acTables[component.AcTable]!, block,
                    spectralStart, spectralEnd, approximationLow);
            }
            else
            {
                DecodeAcRefine(acTables[component.AcTable]!, block,
                    spectralStart, spectralEnd, approximationLow);
            }
        }

        private void DecodeSequential(Component component, Span<short> block)
        {
            component.Prediction += DecodeDifference(dcTables[component.DcTable]!);
            block[0] = (short)component.Prediction;
            var table = acTables[component.AcTable]!;
            for (var k = 1; k < 64; k++)
            {
                var symbol = DecodeSymbol(table);
                var run = symbol >> 4;
                var size = symbol & 15;
                if (size != 0)
                {
                    k += run;
                    block[NaturalOrder[k]] = (short)Extend(ReadBits(size), size);
                }
                else if (run == 15)
                {
                    k += 15;
                }
                else
                {
                    break;
                }
            }
        }

        private void DecodeAcFirst(
            HuffmanTable table, Span<short> block, int start, int end, int shift)
        {
            if (endOfBandRun > 0)
            {
                endOfBandRun--;
                return;
            }
            for (var k = start; k <= end; k++)
            {
                var symbol = DecodeSymbol(table);
                var run = symbol >> 4;
                var size = symbol & 15;
                if (size != 0)
                {
                    k += run;
                    block[NaturalOrder[k]] =
                        (short)(Extend(ReadBits(size), size) * (1 << shift));
                }
                else if (run == 15)
                {
                    k += 15;
                }
                else
                {
                    endOfBandRun = 1 << run;
                    if (run != 0) endOfBandRun += ReadBits(run);
                    endOfBandRun--;
                    break;
                }
            }
        }

        // libjpeg's decode_mcu_AC_refine.
        private void DecodeAcRefine(
            HuffmanTable table, Span<short> block, int start, int end, int shift)
        {
            var plus = 1 << shift;
            var minus = -1 << shift;
            var k = start;
            if (endOfBandRun == 0)
            {
                for (; k <= end; k++)
                {
                    var symbol = DecodeSymbol(table);
                    var run = symbol >> 4;
                    var size = symbol & 15;
                    var value = 0;
                    if (size != 0)
                    {
                        value = ReadBit() != 0 ? plus : minus;
                    }
                    else if (run != 15)
                    {
                        endOfBandRun = 1 << run;
                        if (run != 0) endOfBandRun += ReadBits(run);
                        break;
                    }
                    do
                    {
                        var position = NaturalOrder[k];
                        if (block[position] != 0)
                        {
                            Refine(block, position, plus, minus);
                        }
                        else
                        {
                            if (--run < 0) break;
                        }
                        k++;
                    }
                    while (k <= end);
                    if (value != 0) block[NaturalOrder[k]] = (short)value;
                }
            }
            if (endOfBandRun > 0)
            {
                for (; k <= end; k++)
                {
                    var position = NaturalOrder[k];
                    if (block[position] != 0) Refine(block, position, plus, minus);
                }
                endOfBandRun--;
            }
        }

        private void Refine(Span<short> block, int position, int plus, int minus)
        {
            if (ReadBit() == 0 || (block[position] & plus) != 0) return;
            block[position] = (short)(block[position] + (block[position] >= 0 ? plus : minus));
        }

        private int DecodeDifference(HuffmanTable table)
        {
            var size = DecodeSymbol(table);
            return size == 0 ? 0 : Extend(ReadBits(size), size);
        }

        private static int Extend(int value, int size) =>
            value < 1 << (size - 1) ? value - (1 << size) + 1 : value;

        private int DecodeSymbol(HuffmanTable table)
        {
            if (bitCount < 16) Fill();
            var entry = table.Fast[(int)(bits >> (64 - HuffmanTable.FastBits))];
            if (entry != 0)
            {
                Consume(entry >> 8);
                return entry & 0xff;
            }
            var code = (int)(bits >> 48);
            for (var length = HuffmanTable.FastBits + 1; length <= 16; length++)
            {
                var prefix = code >> (16 - length);
                if (prefix <= table.MaxCode[length])
                {
                    Consume(length);
                    return table.Values[(prefix + table.ValueOffset[length]) & 0xff];
                }
            }
            // libjpeg treats an invalid code as a zero symbol.
            Consume(16);
            return 0;
        }

        private int ReadBits(int count)
        {
            if (count == 0) return 0;
            if (bitCount < count) Fill();
            var value = (int)(bits >> (64 - count));
            Consume(count);
            return value;
        }

        private int ReadBit() => ReadBits(1);

        private void Consume(int count)
        {
            var dataBits = bitCount - paddingBits;
            if (count > dataBits)
            {
                insufficientData = true;
                paddingBits -= count - dataBits;
            }
            bits <<= count;
            bitCount -= count;
        }

        // Loads entropy-coded bytes, unstuffing 0xFF 0x00. A marker ends the
        // data: like libjpeg, the decoder then reads zero bits.
        private void Fill()
        {
            while (bitCount <= 56)
            {
                var value = 0;
                if (!atMarker && position < data.Length)
                {
                    value = data[position];
                    if (value != 0xff)
                    {
                        position++;
                    }
                    else
                    {
                        var next = position + 1;
                        while (next < data.Length && data[next] == 0xff) next++;
                        if (next < data.Length && data[next] == 0)
                        {
                            position = next + 1;
                        }
                        else
                        {
                            atMarker = true;
                            value = 0;
                            paddingBits += 8;
                        }
                    }
                }
                else
                {
                    paddingBits += 8;
                }
                bits |= (ulong)value << (56 - bitCount);
                bitCount += 8;
            }
        }

        private void ResetEntropy()
        {
            bits = 0;
            bitCount = 0;
            atMarker = false;
            paddingBits = 0;
            insufficientData = false;
            endOfBandRun = 0;
        }

        // Discards the interval's remaining bits and reads its RSTn marker.
        // Like libjpeg, data that ran out stays out when no RSTn follows
        // (another marker, such as EOI, or the end of the stream).
        private void Restart()
        {
            var exhausted = insufficientData;
            ResetEntropy();
            insufficientData = exhausted;
            while (position + 1 < data.Length)
            {
                if (data[position] != 0xff)
                {
                    position++;
                    continue;
                }
                var code = data[position + 1];
                if (code is 0 or 0xff)
                {
                    position++;
                    continue;
                }
                if (code is >= 0xd0 and <= 0xd7)
                {
                    position += 2;
                    insufficientData = false;
                }
                else
                {
                    atMarker = true;
                }
                return;
            }
            atMarker = true;
        }

        private JavaRaster Output()
        {
            var raster = new JavaRaster(
                PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, width, height, components.Length);
            var output = MemoryMarshal.AsBytes(raster.GetByteData().AsSpan());
            var bands = components.Length;
            var row = new byte[width + 2 * 8 * maxH];
            var smoothing = UseBlockSmoothing();
            for (var band = 0; band < bands; band++)
            {
                var component = components[band];
                var stride = component.BlocksPerLine * 8;
                var plane = ArrayPool<byte>.Shared.Rent(
                    checked(stride * component.UsedBlocksPerColumn * 8));
                InverseTransform(component, stride, plane, smoothing);
                ReturnCoefficients(component);
                UpsampleInto(component, plane, stride, row, output, band);
                ArrayPool<byte>.Shared.Return(plane);
            }
            return raster;
        }

        // Expands a component's samples to the image size and stores them as
        // one band of the interleaved output.
        private void UpsampleInto(
            Component component, byte[] plane, int stride, byte[] row, Span<byte> output, int band)
        {
            var bands = components.Length;
            var expandX = maxH / component.H;
            var expandY = maxV / component.V;
            var fancyX = expandX == 2 && component.Width > 2;
            var fancyY = fancyX && expandY == 2;
            var fancyVertical = expandX == 1 && expandY == 2;
            for (var y = 0; y < height; y++)
            {
                var sourceRow = y / expandY;
                var line = plane.AsSpan(sourceRow * stride, component.Width);
                // The row above for even rows, below for odd rows, the edge
                // row itself at the top and bottom.
                var neighbour = Math.Min(
                    component.Height - 1,
                    Math.Max(0, (y & 1) == 0 ? sourceRow - 1 : sourceRow + 1));
                if (fancyY)
                {
                    UpsampleH2V2(line, plane.AsSpan(neighbour * stride, component.Width), row);
                }
                else if (fancyVertical)
                {
                    var other = plane.AsSpan(neighbour * stride, component.Width);
                    var bias = (y & 1) == 0 ? 1 : 2;
                    for (var x = 0; x < width; x++)
                        row[x] = (byte)((line[x] * 3 + other[x] + bias) >> 2);
                }
                else if (fancyX && expandY == 1)
                {
                    UpsampleH2V1(line, row);
                }
                else if (expandX == 1)
                {
                    line.CopyTo(row);
                }
                else
                {
                    for (var x = 0; x < width; x++) row[x] = line[x / expandX];
                }
                var target = y * width * bands + band;
                for (var x = 0; x < width; x++, target += bands) output[target] = row[x];
            }
        }

        private static void UpsampleH2V1(ReadOnlySpan<byte> input, Span<byte> output)
        {
            var last = input.Length - 1;
            output[0] = input[0];
            output[1] = (byte)((input[0] * 3 + input[1] + 2) >> 2);
            for (var x = 1; x < last; x++)
            {
                var value = input[x] * 3;
                output[2 * x] = (byte)((value + input[x - 1] + 1) >> 2);
                output[2 * x + 1] = (byte)((value + input[x + 1] + 2) >> 2);
            }
            output[2 * last] = (byte)((input[last] * 3 + input[last - 1] + 1) >> 2);
            output[2 * last + 1] = input[last];
        }

        private static void UpsampleH2V2(
            ReadOnlySpan<byte> input, ReadOnlySpan<byte> neighbour, Span<byte> output)
        {
            var last = input.Length - 1;
            var current = input[0] * 3 + neighbour[0];
            var next = input[1] * 3 + neighbour[1];
            output[0] = (byte)((current * 4 + 8) >> 4);
            output[1] = (byte)((current * 3 + next + 7) >> 4);
            for (var x = 1; x < last; x++)
            {
                var previous = current;
                current = next;
                next = input[x + 1] * 3 + neighbour[x + 1];
                output[2 * x] = (byte)((current * 3 + previous + 8) >> 4);
                output[2 * x + 1] = (byte)((current * 3 + next + 7) >> 4);
            }
            var before = current;
            current = next;
            output[2 * last] = (byte)((current * 3 + before + 8) >> 4);
            output[2 * last + 1] = (byte)((current * 4 + 7) >> 4);
        }

        // smoothing_ok (libjpeg-turbo, which the pinned OpenJDK's ImageIO
        // links): a progressive image whose first nine AC coefficients are not
        // all complete, every component with a DC scan and nonzero quantizers
        // for the estimated coefficients. Block smoothing is on by default.
        private bool UseBlockSmoothing()
        {
            if (!progressive) return false;
            var useful = false;
            foreach (var component in components)
            {
                var q = component.Quantization;
                if (q is null || component.CoefficientBits[0] < 0) return false;
                foreach (var position in SmoothedPositions)
                    if (q[position] == 0) return false;
                for (var k = 1; k < SmoothedCoefficients; k++)
                    if (component.CoefficientBits[k] != 0) useful = true;
            }
            return useful;
        }

        // Natural positions of zig-zag coefficients 0..9.
        private static readonly int[] SmoothedPositions = [0, 1, 8, 16, 9, 2, 3, 10, 17, 24];

        // jpeg_idct_islow over the blocks that hold samples, after
        // decompress_smooth_data's estimates when smoothing.
        private void InverseTransform(
            Component component, int stride, byte[] plane, bool smoothing)
        {
            var workspace = new int[64];
            var smoothed = smoothing ? new short[64] : null;
            var dc = smoothing ? new int[25] : null;
            // A component no scan carried has no table: libjpeg's zero
            // multipliers make its samples 128.
            var quantization = component.Quantization ?? new int[64];
            // Previous-scan precision, -1 when this was the first scan.
            var previousBits = new int[SmoothedCoefficients];
            for (var k = 0; k < SmoothedCoefficients; k++)
                previousBits[k] = scanNumber > 1 ? component.PreviousCoefficientBits[k] : -1;
            var lastRow = component.UsedBlocksPerColumn - 1;
            var lastColumn = component.UsedBlocksPerLine - 1;
            var lastIMcuRow = mcusPerColumn - 1;
            for (var blockRow = 0; blockRow <= lastRow; blockRow++)
            {
                var iMcuRow = blockRow / component.V;
                // Rows past the point where the last scan's data ran out keep
                // the previous scan's precision.
                var bits = iMcuRow > lastGoodRow ? previousBits : component.CoefficientBits;
                if (smoothed is not null)
                    NeighbourRows(component, blockRow, iMcuRow, lastIMcuRow, rowsForDc);
                for (var blockColumn = 0; blockColumn <= lastColumn; blockColumn++)
                {
                    var offset = (blockRow * component.BlocksPerLine + blockColumn) * 64;
                    ReadOnlySpan<short> block = component.Coefficients.AsSpan(offset, 64);
                    if (smoothed is not null)
                    {
                        block.CopyTo(smoothed);
                        for (var r = 0; r < 5; r++)
                        {
                            for (var c = 0; c < 5; c++)
                            {
                                var column = Math.Min(lastColumn, Math.Max(0, blockColumn + c - 2));
                                dc![r * 5 + c] = component.Coefficients[
                                    (rowsForDc[r] * component.BlocksPerLine + column) * 64];
                            }
                        }
                        Smooth(quantization, bits, dc!, smoothed);
                        block = smoothed;
                    }
                    InverseTransformBlock(
                        block, quantization, workspace,
                        plane.AsSpan(blockRow * 8 * stride + blockColumn * 8), stride);
                }
            }
        }

        private readonly int[] rowsForDc = new int[5];

        // The block rows decompress_smooth_data reads for rows -2..+2. Its
        // image_block_row arithmetic uses the last iMCU row's block count, so
        // a partial last iMCU row reaches into the padding rows (clamped to
        // the stored ones here).
        private static void NeighbourRows(
            Component component, int blockRow, int iMcuRow, int lastIMcuRow, int[] rows)
        {
            var blockRows = component.V;
            if (iMcuRow == lastIMcuRow)
            {
                blockRows = component.UsedBlocksPerColumn % component.V;
                if (blockRows == 0) blockRows = component.V;
            }
            var rowInIMcu = blockRow % component.V;
            var imageBlockRow = iMcuRow * blockRows + rowInIMcu;
            var imageBlockRows = blockRows * (lastIMcuRow + 1);
            var previous = imageBlockRow > 0 ? blockRow - 1 : blockRow;
            var beforePrevious = imageBlockRow > 1 ? blockRow - 2 : previous;
            var next = imageBlockRow < imageBlockRows - 1 ? blockRow + 1 : blockRow;
            var afterNext = imageBlockRow < imageBlockRows - 2 ? blockRow + 2 : next;
            var limit = component.BlocksPerColumn - 1;
            rows[0] = Math.Min(limit, Math.Max(0, beforePrevious));
            rows[1] = Math.Min(limit, Math.Max(0, previous));
            rows[2] = blockRow;
            rows[3] = Math.Min(limit, next);
            rows[4] = Math.Min(limit, afterNext);
        }

        // decompress_smooth_data's estimates from the 5x5 neighbourhood of DC
        // values (dc[0] is DC01, dc[12] the block's own): with no AC data at
        // all, a Gaussian-like DC interpolation that also replaces the DC and
        // estimates AC03, AC12, AC21 and AC30; otherwise JPEG K.8 widened to
        // 5x5. An estimate replaces only a zero, incomplete coefficient.
        private static void Smooth(int[] q, int[] bits, int[] dc, short[] block)
        {
            var changeDc = true;
            for (var k = 1; k < SmoothedCoefficients; k++)
                if (bits[k] != -1) changeDc = false;
            long q00 = q[0];
            Estimate(block, 1, bits[1], q[1], q00 * (changeDc
                ? -dc[0] - dc[1] + dc[3] + dc[4] - 3 * dc[5] + 13 * dc[6] -
                  13 * dc[8] + 3 * dc[9] - 3 * dc[10] + 38 * dc[11] - 38 * dc[13] +
                  3 * dc[14] - 3 * dc[15] + 13 * dc[16] - 13 * dc[18] + 3 * dc[19] -
                  dc[20] - dc[21] + dc[23] + dc[24]
                : -7 * dc[10] + 50 * dc[11] - 50 * dc[13] + 7 * dc[14]));
            Estimate(block, 8, bits[2], q[8], q00 * (changeDc
                ? -dc[0] - 3 * dc[1] - 3 * dc[2] - 3 * dc[3] - dc[4] - dc[5] +
                  13 * dc[6] + 38 * dc[7] + 13 * dc[8] - dc[9] + dc[15] -
                  13 * dc[16] - 38 * dc[17] - 13 * dc[18] + dc[19] + dc[20] +
                  3 * dc[21] + 3 * dc[22] + 3 * dc[23] + dc[24]
                : -7 * dc[2] + 50 * dc[7] - 50 * dc[17] + 7 * dc[22]));
            Estimate(block, 16, bits[3], q[16], q00 * (changeDc
                ? dc[2] + 2 * dc[6] + 7 * dc[7] + 2 * dc[8] - 5 * dc[11] - 14 * dc[12] -
                  5 * dc[13] + 2 * dc[16] + 7 * dc[17] + 2 * dc[18] + dc[22]
                : -dc[2] + 13 * dc[7] - 24 * dc[12] + 13 * dc[17] - dc[22]));
            Estimate(block, 9, bits[4], q[9], q00 * (changeDc
                ? -dc[0] + dc[4] + 9 * dc[6] - 9 * dc[8] - 9 * dc[16] +
                  9 * dc[18] + dc[20] - dc[24]
                : dc[9] + dc[15] - 10 * dc[16] + 10 * dc[18] - dc[1] - dc[19] + dc[21] -
                  dc[23] + dc[3] - dc[5] + 10 * dc[6] - 10 * dc[8]));
            Estimate(block, 2, bits[5], q[2], q00 * (changeDc
                ? 2 * dc[6] - 5 * dc[7] + 2 * dc[8] + dc[10] + 7 * dc[11] - 14 * dc[12] +
                  7 * dc[13] + dc[14] + 2 * dc[16] - 5 * dc[17] + 2 * dc[18]
                : -dc[10] + 13 * dc[11] - 24 * dc[12] + 13 * dc[13] - dc[14]));
            if (!changeDc) return;
            Estimate(block, 3, bits[6], q[3],
                q00 * (dc[6] - dc[8] + 2 * dc[11] - 2 * dc[13] + dc[16] - dc[18]));
            Estimate(block, 10, bits[7], q[10],
                q00 * (dc[6] - 3 * dc[7] + dc[8] - dc[16] + 3 * dc[17] - dc[18]));
            Estimate(block, 17, bits[8], q[17],
                q00 * (dc[6] - dc[8] - 3 * dc[11] + 3 * dc[13] + dc[16] - dc[18]));
            Estimate(block, 24, bits[9], q[24],
                q00 * (dc[6] + 2 * dc[7] + dc[8] - dc[16] - 2 * dc[17] - dc[18]));
            var dcNumber = q00 *
                (-2 * dc[0] - 6 * dc[1] - 8 * dc[2] - 6 * dc[3] - 2 * dc[4] -
                 6 * dc[5] + 6 * dc[6] + 42 * dc[7] + 6 * dc[8] - 6 * dc[9] -
                 8 * dc[10] + 42 * dc[11] + 152 * dc[12] + 42 * dc[13] - 8 * dc[14] -
                 6 * dc[15] + 6 * dc[16] + 42 * dc[17] + 6 * dc[18] - 6 * dc[19] -
                 2 * dc[20] - 6 * dc[21] - 8 * dc[22] - 6 * dc[23] - 2 * dc[24]);
            var dcValue = ((q00 << 7) + Math.Abs(dcNumber)) / (q00 << 8);
            block[0] = (short)(dcNumber >= 0 ? dcValue : -dcValue);
        }

        private static void Estimate(short[] block, int position, int al, long q, long num)
        {
            if (al == 0 || block[position] != 0) return;
            var pred = ((q << 7) + Math.Abs(num)) / (q << 8);
            if (al > 0 && pred >= 1L << al) pred = (1L << al) - 1;
            block[position] = (short)(num >= 0 ? pred : -pred);
        }

        private const int ConstBits = 13;
        private const int Pass1Bits = 2;
        private const long Fix0298631336 = 2446;
        private const long Fix0390180644 = 3196;
        private const long Fix0541196100 = 4433;
        private const long Fix0765366865 = 6270;
        private const long Fix0899976223 = 7373;
        private const long Fix1175875602 = 9633;
        private const long Fix1501321110 = 12299;
        private const long Fix1847759065 = 15137;
        private const long Fix1961570560 = 16069;
        private const long Fix2053119869 = 16819;
        private const long Fix2562915447 = 20995;
        private const long Fix3072711026 = 25172;

        private static long Descale(long value, int bits) =>
            (value + (1L << (bits - 1))) >> bits;

        private static long Dequantize(ReadOnlySpan<short> block, int[] quantization, int index) =>
            (long)block[index] * quantization[index];

        private static void InverseTransformBlock(
            ReadOnlySpan<short> block, int[] quantization, int[] workspace,
            Span<byte> output, int stride)
        {
            for (var column = 0; column < 8; column++)
            {
                if (block[8 + column] == 0 && block[16 + column] == 0 &&
                    block[24 + column] == 0 && block[32 + column] == 0 &&
                    block[40 + column] == 0 && block[48 + column] == 0 &&
                    block[56 + column] == 0)
                {
                    var dc = block[column] * quantization[column] << Pass1Bits;
                    for (var row = 0; row < 8; row++) workspace[row * 8 + column] = dc;
                    continue;
                }
                OddEven(
                    Dequantize(block, quantization, column),
                    Dequantize(block, quantization, 8 + column),
                    Dequantize(block, quantization, 16 + column),
                    Dequantize(block, quantization, 24 + column),
                    Dequantize(block, quantization, 32 + column),
                    Dequantize(block, quantization, 40 + column),
                    Dequantize(block, quantization, 48 + column),
                    Dequantize(block, quantization, 56 + column),
                    out var r0, out var r1, out var r2, out var r3,
                    out var r4, out var r5, out var r6, out var r7);
                const int shift = ConstBits - Pass1Bits;
                workspace[column] = (int)Descale(r0, shift);
                workspace[8 + column] = (int)Descale(r1, shift);
                workspace[16 + column] = (int)Descale(r2, shift);
                workspace[24 + column] = (int)Descale(r3, shift);
                workspace[32 + column] = (int)Descale(r4, shift);
                workspace[40 + column] = (int)Descale(r5, shift);
                workspace[48 + column] = (int)Descale(r6, shift);
                workspace[56 + column] = (int)Descale(r7, shift);
            }
            for (var row = 0; row < 8; row++)
            {
                var w = workspace.AsSpan(row * 8, 8);
                var line = output.Slice(row * stride, 8);
                if (w[1] == 0 && w[2] == 0 && w[3] == 0 && w[4] == 0 &&
                    w[5] == 0 && w[6] == 0 && w[7] == 0)
                {
                    line.Fill(RangeLimit[(int)Descale(w[0], Pass1Bits + 3) & 1023]);
                    continue;
                }
                OddEven(
                    w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7],
                    out var r0, out var r1, out var r2, out var r3,
                    out var r4, out var r5, out var r6, out var r7);
                const int shift = ConstBits + Pass1Bits + 3;
                line[0] = RangeLimit[(int)Descale(r0, shift) & 1023];
                line[1] = RangeLimit[(int)Descale(r1, shift) & 1023];
                line[2] = RangeLimit[(int)Descale(r2, shift) & 1023];
                line[3] = RangeLimit[(int)Descale(r3, shift) & 1023];
                line[4] = RangeLimit[(int)Descale(r4, shift) & 1023];
                line[5] = RangeLimit[(int)Descale(r5, shift) & 1023];
                line[6] = RangeLimit[(int)Descale(r6, shift) & 1023];
                line[7] = RangeLimit[(int)Descale(r7, shift) & 1023];
            }
        }

        // One jidctint.c pass over eight inputs, before descaling.
        private static void OddEven(
            long in0, long in1, long in2, long in3, long in4, long in5, long in6, long in7,
            out long out0, out long out1, out long out2, out long out3,
            out long out4, out long out5, out long out6, out long out7)
        {
            var z1 = (in2 + in6) * Fix0541196100;
            var tmp2 = z1 + in6 * -Fix1847759065;
            var tmp3 = z1 + in2 * Fix0765366865;
            var tmp0 = (in0 + in4) << ConstBits;
            var tmp1 = (in0 - in4) << ConstBits;
            var tmp10 = tmp0 + tmp3;
            var tmp13 = tmp0 - tmp3;
            var tmp11 = tmp1 + tmp2;
            var tmp12 = tmp1 - tmp2;

            tmp0 = in7;
            tmp1 = in5;
            tmp2 = in3;
            tmp3 = in1;
            z1 = tmp0 + tmp3;
            var z2 = tmp1 + tmp2;
            var z3 = tmp0 + tmp2;
            var z4 = tmp1 + tmp3;
            var z5 = (z3 + z4) * Fix1175875602;
            tmp0 *= Fix0298631336;
            tmp1 *= Fix2053119869;
            tmp2 *= Fix3072711026;
            tmp3 *= Fix1501321110;
            z1 *= -Fix0899976223;
            z2 *= -Fix2562915447;
            z3 *= -Fix1961570560;
            z4 *= -Fix0390180644;
            z3 += z5;
            z4 += z5;
            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            out0 = tmp10 + tmp3;
            out7 = tmp10 - tmp3;
            out1 = tmp11 + tmp2;
            out6 = tmp11 - tmp2;
            out2 = tmp12 + tmp1;
            out5 = tmp12 - tmp1;
            out3 = tmp13 + tmp0;
            out4 = tmp13 - tmp0;
        }
    }
}
