// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// PdfCarton adapters for pinned, source-built JBIG2 and JPEG 2000 decoders,
// and for the JPEG component-sample decoder ported from libjpeg-turbo
// (vendor/pdfcube/jpeg).
#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using CoreJ2K;
using CoreJ2K.j2k.codestream;
using CoreJ2K.j2k.codestream.reader;
using CoreJ2K.j2k.fileformat.reader;
using CoreJ2K.j2k.util;
using JBig2Decoder.NETStandard;
using SkiaSharp;

namespace DripSharp.Runtime;

internal static class PdfCartonImageCodecs
{
    internal static bool Supports(string formatName) =>
        IsJbig2(formatName) || IsJpx(formatName);

    // The stored samples of a JPEG (ImageIO's readRaster), or null when its
    // coding process is not decoded here.
    internal static JavaRaster? ReadJpegSamples(byte[] encoded) =>
        PdfCartonJpegSamples.Decode(encoded);

    internal static SKBitmap Decode(string formatName, byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        ArgumentNullException.ThrowIfNull(encoded);
        try
        {
            return IsJbig2(formatName)
                ? DecodeJbig2(encoded)
                : IsJpx(formatName)
                    ? DecodeJpx(encoded)
                    : throw new IOException(
                        $"Unsupported internal image codec `{formatName}`.");
        }
        catch (IOException)
        {
            throw;
        }
        catch (Exception error)
        {
            throw new IOException(
                $"Unable to decode {formatName} image data.",
                error);
        }
    }

    // The size of a JPX image from its JP2 boxes and codestream main header
    // (SIZ), which is the size Decode returns, without decoding it. JBIG2 has
    // no such read: null.
    internal static SKSizeI? ReadSize(string formatName, byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        ArgumentNullException.ThrowIfNull(encoded);
        if (!IsJpx(formatName)) return null;
        try
        {
            using var stream = new MemoryStream(
                encoded, 0, encoded.Length, writable: false, publiclyVisible: true);
            var input = new ISRandomAccessIO(stream);
            var fileFormat = new FileFormatReader(input);
            fileFormat.readFileFormat();
            if (fileFormat.JP2FFUsed) input.seek(fileFormat.FirstCodeStreamPos);
            var header = new HeaderDecoder(
                input,
                new ParameterList(J2kImage.GetDefaultDecoderParameterList()),
                new HeaderInfo());
            return new SKSizeI(header.ImgWidth, header.ImgHeight);
        }
        catch (IOException)
        {
            throw;
        }
        catch (Exception error)
        {
            throw new IOException(
                $"Unable to decode {formatName} image data.",
                error);
        }
    }

    private static SKBitmap DecodeJbig2(byte[] encoded)
    {
        var page = Jbig2PageReader.Read(encoded);
        var width = (int)page.GetWidth();
        var height = (int)page.GetHeight();
        if (width <= 0 || height <= 0 ||
            page.data.Size() < (long)width * height)
        {
            throw new IOException("JBIG2 decoder returned invalid image dimensions.");
        }

        var colorModel = PdfCartonFontCompat.IndexColorModel(
            1,
            2,
            [0, -1],
            [0, -1],
            [0, -1]);
        var raster = JavaRaster.Packed(
            width,
            height,
            1,
            PackPage(page.data.w, width, height));
        // JBIG2Filter reads the raster; a draw renders the pixels on demand.
        return PdfCartonFontCompat.CreateDeferredImage(colorModel, raster, false, null);
    }

    // Packs the page's row-major bit set (pixel i is bit i % 64 of word
    // i / 64, 1 = black) MSB-first into rows of (width + 7) / 8 bytes, as
    // palette indices (0 = black, 1 = white) with zero row padding.
    private static sbyte[] PackPage(long[] words, int width, int height)
    {
        var stride = (width + 7) / 8;
        var packed = new sbyte[checked(stride * height)];
        long rowStart = 0;
        for (var row = 0; row < height; row++, rowStart += width)
        {
            var offset = row * stride;
            for (var column = 0; column < width; column += 8)
            {
                var index = rowStart + column;
                var word = (int)(index >> 6);
                var shift = (int)(index & 63);
                var bits = (ulong)words[word] >> shift;
                if (shift > 56 && word + 1 < words.Length)
                    bits |= (ulong)words[word + 1] << (64 - shift);
                var count = Math.Min(8, width - column);
                var white = ~(uint)bits & ((1u << count) - 1);
                packed[offset++] = unchecked((sbyte)ReversedBits[white]);
            }
        }
        return packed;
    }

    private static readonly byte[] ReversedBits = CreateReversedBits();

    private static byte[] CreateReversedBits()
    {
        var table = new byte[256];
        for (var value = 0; value < table.Length; value++)
        {
            var reversed = 0;
            for (var bit = 0; bit < 8; bit++)
                if ((value & 1 << bit) != 0) reversed |= 0x80 >> bit;
            table[value] = (byte)reversed;
        }
        return table;
    }

    // The pinned decoder's only entry point, DecodeJBIG2, expands the page to
    // 3 bytes of RGB per pixel. Read runs DecodeJBIG2's steps up to the page
    // bitmap through the decoder's private members instead: the vendored
    // source is an exact pinned snapshot (doc/targets/pdfbox/dependencies.md)
    // and is not patched. Advancing the pin must keep these members.
    private static class Jbig2PageReader
    {
        private const BindingFlags Private = BindingFlags.Instance | BindingFlags.NonPublic;

        private static readonly FieldInfo ReaderField = Field("reader");
        private static readonly FieldInfo PagesKnownField = Field("noOfPagesKnown");
        private static readonly FieldInfo RandomAccessField = Field("randomAccessOrganisation");
        private static readonly FieldInfo PageCountField = Field("noOfPages");
        private static readonly FieldInfo HuffmanField = Field("huffmanDecoder");
        private static readonly FieldInfo MmrField = Field("mmrDecoder");
        private static readonly FieldInfo ArithmeticField = Field("arithmeticDecoder");
        private static readonly Func<JBIG2StreamDecoder, bool> CheckHeader =
            Method<Func<JBIG2StreamDecoder, bool>>("CheckHeader");
        private static readonly Action<JBIG2StreamDecoder> SetFileHeaderFlags =
            Method<Action<JBIG2StreamDecoder>>("SetFileHeaderFlags");
        private static readonly Func<JBIG2StreamDecoder, int> GetNoOfPages =
            Method<Func<JBIG2StreamDecoder, int>>("GetNoOfPages");
        private static readonly Action<JBIG2StreamDecoder> ReadSegments =
            Method<Action<JBIG2StreamDecoder>>("ReadSegments");

        internal static JBIG2Bitmap Read(byte[] data)
        {
            // A new decoder is already in ResetDecoder's state and has no
            // global data: JBIG2Filter prepends JBIG2Globals to the stream.
            var decoder = new JBIG2StreamDecoder
            {
                TolerateMissingSegments = false
            };
            var reader = new Big2StreamReader(data);
            ReaderField.SetValue(decoder, reader);
            if (!CheckHeader(decoder))
            {
                // An embedded stream: sequential organisation, one page.
                PagesKnownField.SetValue(decoder, true);
                RandomAccessField.SetValue(decoder, false);
                PageCountField.SetValue(decoder, 1);
                reader.MovePointer(-8);
            }
            else
            {
                SetFileHeaderFlags(decoder);
                if ((bool)PagesKnownField.GetValue(decoder)!)
                    PageCountField.SetValue(decoder, GetNoOfPages(decoder));
            }

            HuffmanField.SetValue(decoder, new HuffmanDecoder(reader));
            MmrField.SetValue(decoder, new MMRDecoder(reader));
            ArithmeticField.SetValue(decoder, new ArithmeticDecoder(reader));
            ReadSegments(decoder);
            return decoder.GetPageAsJBIG2Bitmap(1);
        }

        private static FieldInfo Field(string name) =>
            typeof(JBIG2StreamDecoder).GetField(name, Private) ??
            throw new MissingFieldException(nameof(JBIG2StreamDecoder), name);

        private static T Method<T>(string name)
            where T : Delegate =>
            (T)(typeof(JBIG2StreamDecoder).GetMethod(name, Private) ??
                throw new MissingMethodException(nameof(JBIG2StreamDecoder), name))
                .CreateDelegate(typeof(T));
    }

    private static SKBitmap DecodeJpx(byte[] encoded)
    {
        var normalized = JpxTilePartCounts.Normalize(JpxChannelDefinitions.Normalize(encoded));
        var palette = JpxIndexPalette.Read(normalized);
        if (palette is not null) return DecodeIndexedJpx(normalized, palette);
        using var stream = new MemoryStream(normalized, writable: false);
        using var image = J2kImage.FromStream(stream, out var metadata);
        var hasAlpha = metadata.ChannelDefinitions?.HasAlphaChannel == true;
        var colorComponents = image.NumberOfComponents - (hasAlpha ? 1 : 0);
        if (colorComponents <= 0 || colorComponents > 15)
            throw new IOException(
                $"JPEG 2000 image has unsupported component count {image.NumberOfComponents}.");

        var bits = image.BitDepths.ToArray();
        var dataType = DataType(bits.Max());
        var colorSpace = CreateColorSpace(colorComponents);
        var colorModel =
            new JavaColorModel(colorSpace, hasAlpha, dataType, bits);
        var raster = colorModel.CreateCompatibleWritableRaster(
            image.Width,
            image.Height);
        // One component buffer serves every component.
        var samples = new int[checked(image.Width * image.Height)];
        for (var component = 0; component < image.NumberOfComponents; component++)
        {
            image.CopyComponentTo(component, samples);
            raster.SetSamples(
                0,
                0,
                image.Width,
                image.Height,
                component,
                samples);
        }
        // JPXFilter reads the raster; a draw renders the pixels on demand.
        return PdfCartonFontCompat.CreateDeferredImage(
            colorModel,
            raster,
            isRasterPremultiplied: false,
            null);
    }

    // A palettized JP2 decodes to its palette indexes, as jai-imageio-jpeg2000
    // 1.4.0 reads it for Java PDFBox, so JPXFilter writes the indexes.
    private static SKBitmap DecodeIndexedJpx(byte[] encoded, JpxIndexPalette palette)
    {
        var parameters = new ParameterList(J2kImage.GetDefaultDecoderParameterList());
        parameters["nocolorspace"] = "on";
        using var stream = new MemoryStream(encoded, writable: false);
        using var image = J2kImage.FromStream(stream, out _, parameters);
        if (image.NumberOfComponents != 1)
            throw new IOException(
                $"Palettized JPEG 2000 image decoded {image.NumberOfComponents} index components.");
        var colorModel = palette.CreateColorModel(image.BitDepths[0]);
        var raster = colorModel.CreateCompatibleWritableRaster(image.Width, image.Height);
        var samples = new int[checked(image.Width * image.Height)];
        image.CopyComponentTo(0, samples);
        raster.SetSamples(0, 0, image.Width, image.Height, 0, samples);
        // JPXFilter reads the raster; a draw renders the pixels on demand.
        return PdfCartonFontCompat.CreateDeferredImage(
            colorModel,
            raster,
            isRasterPremultiplied: false,
            null);
    }

    internal static int DataType(int bits) =>
        bits switch
        {
            <= 8 => PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
            <= 16 => PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT,
            _ => PdfCartonFontCompat.DATA_BUFFER_TYPE_INT
        };

    private static JavaColorSpace CreateColorSpace(int components) =>
        components switch
        {
            1 => PdfCartonFontCompat.GetColorSpace(JavaColorSpace.CS_GRAY),
            3 => PdfCartonFontCompat.GetColorSpace(JavaColorSpace.CS_sRGB),
            4 => new JavaColorSpace(JavaColorSpace.TYPE_CMYK),
            _ => new JavaColorSpace(0, components + 10, components)
        };

    private static bool IsJbig2(string formatName) =>
        formatName.Equals("JBIG2", StringComparison.OrdinalIgnoreCase);

    private static bool IsJpx(string formatName) =>
        formatName.Equals("JPEG2000", StringComparison.OrdinalIgnoreCase) ||
        formatName.Equals("JPEG 2000", StringComparison.OrdinalIgnoreCase) ||
        formatName.Equals("JP2", StringComparison.OrdinalIgnoreCase) ||
        formatName.Equals("JPX", StringComparison.OrdinalIgnoreCase);
}

// The palette (pclr) of a JP2 file whose codestream has a single component,
// the palette index. For such files jai-imageio-jpeg2000 1.4.0 returns the
// indexes, not the colours (checked against Java PDFBox 3.0.8 JPXFilter). A
// palette of three or four columns gives an IndexColorModel of the index
// precision: channel c (red, green, blue, then alpha) holds the low eight
// bits of the column the c-th component mapping (cmap) entry names, whatever
// its mapping type, or of column c without a cmap; a cmap without an entry
// for each column, or naming a column past the palette, fails the read. Any
// other column count gives grey indexes: 1-, 2- and 4-bit indexes as an
// IndexColorModel of an even grey ramp, wider ones as CS_GRAY samples of 8
// or 16 bits. Files with more components keep the decoder's palette
// expansion.
internal sealed class JpxIndexPalette
{
    private readonly int[][] entries;
    private readonly int columnCount;
    private readonly int[]? mappedColumns;

    private JpxIndexPalette(int[][] entries, int columnCount, int[]? mappedColumns)
    {
        this.entries = entries;
        this.columnCount = columnCount;
        this.mappedColumns = mappedColumns;
    }

    internal static JpxIndexPalette? Read(byte[] encoded)
    {
        using var stream = new MemoryStream(
            encoded, 0, encoded.Length, writable: false, publiclyVisible: true);
        var input = new ISRandomAccessIO(stream);
        var fileFormat = new FileFormatReader(input);
        fileFormat.readFileFormat();
        var palette = fileFormat.Metadata.Palette;
        if (!fileFormat.JP2FFUsed || palette?.Entries is not { Length: > 0 } entries ||
            entries.Any(entry => entry is null || entry.Length < palette.NumColumns))
            return null;
        input.seek(fileFormat.FirstCodeStreamPos);
        var header = new HeaderDecoder(
            input,
            new ParameterList(J2kImage.GetDefaultDecoderParameterList()),
            new HeaderInfo());
        if (header.NumComps != 1) return null;

        var mapping = fileFormat.Metadata.ComponentMapping;
        int[]? mappedColumns = null;
        if (mapping is not null)
        {
            mappedColumns = new int[mapping.NumChannels];
            for (var channel = 0; channel < mappedColumns.Length; channel++)
                mappedColumns[channel] = mapping.GetPaletteColumn(channel);
        }
        return new JpxIndexPalette(entries, palette.NumColumns, mappedColumns);
    }

    internal JavaColorModel CreateColorModel(int indexBits)
    {
        if (columnCount is 3 or 4)
            return new JavaColorModel(
                indexBits,
                entries.Length,
                Channel(0),
                Channel(1),
                Channel(2),
                columnCount == 4 ? Channel(3) : null);
        if (indexBits is 1 or 2 or 4)
        {
            var levels = 1 << indexBits;
            var ramp = new sbyte[levels];
            for (var level = 0; level < levels; level++)
                ramp[level] = unchecked((sbyte)(level * 255 / (levels - 1)));
            return new JavaColorModel(indexBits, levels, ramp, ramp, ramp);
        }
        var bits = indexBits <= 8 ? 8 : indexBits <= 16 ? 16 : indexBits;
        return new JavaColorModel(
            PdfCartonFontCompat.GetColorSpace(JavaColorSpace.CS_GRAY),
            false,
            PdfCartonImageCodecs.DataType(bits),
            [bits]);
    }

    private sbyte[] Channel(int channel)
    {
        var column = mappedColumns is null ? channel
            : channel < mappedColumns.Length ? mappedColumns[channel]
            : -1;
        if ((uint)column >= (uint)columnCount)
            throw new IOException(
                $"JPEG 2000 palette has no column for colour channel {channel}.");
        var values = new sbyte[entries.Length];
        for (var entry = 0; entry < values.Length; entry++)
            values[entry] = unchecked((sbyte)entries[entry][column]);
        return values;
    }
}

// The pinned CoreJ2K maps decoded component c to the cdef channel whose
// association is c + 1, or to channel c + 1 when no channel has it
// (ColorSpace.GetChannelDefinition). That is right for colour channels, whose
// association is their colour number, but an opacity, premultiplied-opacity
// or unspecified channel (association 0, a colour number or 65535) is either
// never found, so its component indexes past the codestream's, or shadows the
// colour channel it is associated with. ISO/IEC 15444-1 I.5.3.6 orders the
// colour channels by association; the other channels follow in channel
// order. The snapshot is not patched: Normalize rewrites a cdef box that the
// decoder would misread so that each channel, listed or not, carries its
// decoded position + 1 as its association, which the decoder maps as the
// standard does. Channel types are kept; an unlisted channel is unspecified,
// and a channel described more than once with one non-colour type (for
// example as the opacity of each colour) decodes once. The decoder keeps only
// the last description of a channel anyway.
// Files this does not recognise, files with a palette (whose cdef channels
// follow the palette, which the decoder applies after this mapping) and cdef
// boxes the decoder already reads correctly are returned unchanged.
internal static class JpxChannelDefinitions
{
    private const uint SignatureBox = 0x6A502020; // "jP  "
    private const uint HeaderBox = 0x6A703268; // "jp2h"
    private const uint ImageHeaderBox = 0x69686472; // "ihdr"
    private const uint PaletteBox = 0x70636C72; // "pclr"
    private const uint ChannelDefinitionBox = 0x63646566; // "cdef"
    private const uint CodestreamBox = 0x6A703263; // "jp2c"
    private const int ColourType = 0;
    private const int UnspecifiedType = 0xFFFF;

    internal static byte[] Normalize(byte[] encoded)
    {
        var end = encoded.Length;
        if (!TryReadBox(encoded, 0, end, out var type, out _, out var position) ||
            type != SignatureBox)
            return encoded;

        int header, headerContent, headerEnd;
        while (true)
        {
            if (!TryReadBox(encoded, position, end, out type, out var content, out var next) ||
                type == CodestreamBox)
                return encoded;
            if (type == HeaderBox)
            {
                (header, headerContent, headerEnd) = (position, content, next);
                break;
            }
            position = next;
        }

        var components = 0;
        int definition = -1, definitionContent = 0, definitionEnd = 0;
        for (position = headerContent; position < headerEnd;)
        {
            if (!TryReadBox(encoded, position, headerEnd, out type, out var content, out var next))
                return encoded;
            if (type == PaletteBox) return encoded;
            if (type == ImageHeaderBox)
            {
                if (next - content < 14) return encoded;
                components = ReadUInt16(encoded, content + 8);
            }
            else if (type == ChannelDefinitionBox)
            {
                if (definition >= 0) return encoded;
                (definition, definitionContent, definitionEnd) = (position, content, next);
            }
            position = next;
        }
        if (components == 0 || definition < 0 || definitionEnd - definitionContent < 2)
            return encoded;

        var count = ReadUInt16(encoded, definitionContent);
        if (definitionEnd - definitionContent < 2 + 6 * count) return encoded;
        var listed = new bool[components];
        var types = new int[components];
        var associations = new int[components];
        for (var index = 0; index < count; index++)
        {
            var entry = definitionContent + 2 + 6 * index;
            var channel = ReadUInt16(encoded, entry);
            if (channel >= components) return encoded;
            var channelType = ReadUInt16(encoded, entry + 2);
            if (listed[channel])
            {
                if (channelType == ColourType || types[channel] != channelType)
                    return encoded;
                continue;
            }
            listed[channel] = true;
            types[channel] = channelType;
            associations[channel] = ReadUInt16(encoded, entry + 4);
        }

        bool IsColour(int channel) =>
            listed[channel] && types[channel] == ColourType &&
            associations[channel] is > 0 and < 0xFFFF;
        var order = Enumerable.Range(0, components)
            .OrderBy(channel => IsColour(channel) ? 0 : 1)
            .ThenBy(channel => IsColour(channel) ? associations[channel] : 0)
            .ThenBy(channel => channel)
            .ToArray();
        var decoded = new int[components];
        var unchanged = count == components;
        for (var index = 0; index < components; index++)
        {
            decoded[order[index]] = index + 1;
            unchanged &= associations[order[index]] == index + 1;
        }
        if (unchanged) return encoded;

        var replacement = new byte[2 + 6 * components];
        WriteUInt16(replacement, 0, components);
        for (var channel = 0; channel < components; channel++)
        {
            WriteUInt16(replacement, 2 + 6 * channel, channel);
            WriteUInt16(replacement, 4 + 6 * channel,
                listed[channel] ? types[channel] : UnspecifiedType);
            WriteUInt16(replacement, 6 + 6 * channel, decoded[channel]);
        }

        var growth = replacement.Length - (definitionEnd - definitionContent);
        var result = new byte[encoded.Length + growth];
        Buffer.BlockCopy(encoded, 0, result, 0, definitionContent);
        Buffer.BlockCopy(replacement, 0, result, definitionContent, replacement.Length);
        Buffer.BlockCopy(
            encoded,
            definitionEnd,
            result,
            definitionEnd + growth,
            encoded.Length - definitionEnd);
        Grow(result, header, growth);
        Grow(result, definition, growth);
        return result;
    }

    // A box from start: its type, content start and end. Lengths 0 (to the
    // end) and 1 (64-bit) are read as ISO/IEC 15444-1 I.4 defines them.
    internal static bool TryReadBox(
        byte[] data, int start, int end, out uint type, out int content, out int boxEnd)
    {
        type = 0;
        content = 0;
        boxEnd = 0;
        if (start < 0 || end - start < 8) return false;
        long length = ReadUInt32(data, start);
        type = ReadUInt32(data, start + 4);
        content = start + 8;
        if (length == 1)
        {
            if (end - start < 16) return false;
            var high = ReadUInt32(data, start + 8);
            if (high != 0) return false;
            length = ReadUInt32(data, start + 12);
            content = start + 16;
        }
        else if (length == 0)
        {
            length = end - start;
        }
        if (length < content - start || length > end - start) return false;
        boxEnd = start + (int)length;
        return true;
    }

    // Adds growth to the length of the box at start, which precedes the
    // change; a box running to the end (length 0) needs no change.
    private static void Grow(byte[] data, int start, int growth)
    {
        var length = ReadUInt32(data, start);
        if (length == 1)
            WriteUInt32(data, start + 12, ReadUInt32(data, start + 12) + (uint)growth);
        else if (length != 0)
            WriteUInt32(data, start, length + (uint)growth);
    }

    internal static int ReadUInt16(byte[] data, int offset) =>
        (data[offset] << 8) | data[offset + 1];

    internal static uint ReadUInt32(byte[] data, int offset) =>
        ((uint)data[offset] << 24) | ((uint)data[offset + 1] << 16) |
        ((uint)data[offset + 2] << 8) | data[offset + 3];

    private static void WriteUInt16(byte[] data, int offset, int value)
    {
        data[offset] = (byte)(value >> 8);
        data[offset + 1] = (byte)value;
    }

    private static void WriteUInt32(byte[] data, int offset, uint value)
    {
        data[offset] = (byte)(value >> 24);
        data[offset + 1] = (byte)(value >> 16);
        data[offset + 2] = (byte)(value >> 8);
        data[offset + 3] = (byte)value;
    }
}

// Some encoders write the index of a tile's last tile-part in the SOT
// marker's TNsot, where ISO/IEC 15444-1 A.4.2 puts the number of tile-parts,
// so each tile has one tile-part more than it announces.
// jai-imageio-jpeg2000 reads every tile-part present. The vendored decoder
// does too (the CoreJ2K 0383d9f backport), but its header decoder sizes the
// packed packet header (PPT) bookkeeping of a tile from the first TNsot it
// reads and cannot take in the PPT markers of a tile-part past that count.
// Like JpxChannelDefinitions, this leaves the snapshot unpatched: in a tile
// with a tile-part whose TPsot reaches its non-zero TNsot, Normalize raises
// every TNsot, including 0 (count not given, from which the decoder also
// sizes the tile), to one past the tile's highest TPsot, so the decoder sizes
// the tile from its real count. Codestreams it does not recognise, or whose
// counts are consistent, are returned unchanged and without a copy.
internal static class JpxTilePartCounts
{
    private const int StartOfCodestream = 0xFF4F;
    private const int StartOfTilePart = 0xFF90;
    private const uint CodestreamBox = 0x6A703263; // "jp2c"

    internal static byte[] Normalize(byte[] encoded)
    {
        if (!TryFindCodestream(encoded, out var start, out var end) ||
            !TryFindFirstTilePart(encoded, start, end, out var first) ||
            !Undercounts(encoded, first, end, null, null))
            return encoded;

        var counts = new Dictionary<int, int>();
        var undercounting = new HashSet<int>();
        Undercounts(encoded, first, end, counts, undercounting);
        var result = (byte[])encoded.Clone();
        for (var sot = first; IsTilePart(result, sot, end);)
        {
            var tile = JpxChannelDefinitions.ReadUInt16(result, sot + 4);
            if (undercounting.Contains(tile) && result[sot + 11] < counts[tile])
                result[sot + 11] = (byte)counts[tile];
            if (!TryNext(result, sot, end, out sot)) break;
        }
        return result;
    }

    // Whether a tile-part's TPsot reaches its non-zero TNsot. Optionally
    // records each tile's highest TPsot + 1, and the tiles where one does
    // and that count stays within 255.
    private static bool Undercounts(
        byte[] data, int sot, int end, Dictionary<int, int>? counts, HashSet<int>? undercounting)
    {
        var found = false;
        while (IsTilePart(data, sot, end))
        {
            var tile = JpxChannelDefinitions.ReadUInt16(data, sot + 4);
            var tilePart = data[sot + 10];
            var announced = data[sot + 11];
            var under = announced != 0 && tilePart >= announced && tilePart < 255;
            found |= under;
            if (counts != null)
            {
                counts.TryGetValue(tile, out var count);
                counts[tile] = Math.Max(count, tilePart + 1);
            }
            if (under) undercounting?.Add(tile);
            if (!TryNext(data, sot, end, out sot)) break;
        }
        if (counts != null && undercounting != null)
            undercounting.RemoveWhere(tile => counts[tile] > 255);
        return found;
    }

    private static bool IsTilePart(byte[] data, int sot, int end) =>
        sot >= 0 && end - sot >= 12 &&
        JpxChannelDefinitions.ReadUInt16(data, sot) == StartOfTilePart &&
        JpxChannelDefinitions.ReadUInt16(data, sot + 2) == 10;

    // The next tile-part after the one at sot; Psot 0 runs to the end.
    private static bool TryNext(byte[] data, int sot, int end, out int next)
    {
        var length = JpxChannelDefinitions.ReadUInt32(data, sot + 6);
        next = length == 0 || length > (uint)(end - sot) ? end : sot + (int)length;
        return next < end;
    }

    // The codestream of a raw J2K stream or the first jp2c box of a JP2 file.
    private static bool TryFindCodestream(byte[] data, out int start, out int end)
    {
        (start, end) = (0, data.Length);
        if (data.Length >= 2 && JpxChannelDefinitions.ReadUInt16(data, 0) == StartOfCodestream)
            return true;
        for (var position = 0;
             JpxChannelDefinitions.TryReadBox(data, position, data.Length, out var type, out var content, out var next);
             position = next)
        {
            if (type != CodestreamBox) continue;
            (start, end) = (content, next);
            return end - start >= 2 &&
                   JpxChannelDefinitions.ReadUInt16(data, start) == StartOfCodestream;
        }
        return false;
    }

    // The first SOT after the main header's marker segments. Markers FF30 to
    // FF3F have no segment (ISO/IEC 15444-1 A.1), as the decoder reads them.
    private static bool TryFindFirstTilePart(byte[] data, int start, int end, out int sot)
    {
        for (sot = start + 2; end - sot >= 4;)
        {
            var marker = JpxChannelDefinitions.ReadUInt16(data, sot);
            if (marker == StartOfTilePart) return true;
            if (marker >> 8 != 0xFF) return false;
            sot += marker is >= 0xFF30 and <= 0xFF3F
                ? 2
                : 2 + JpxChannelDefinitions.ReadUInt16(data, sot + 2);
        }
        return false;
    }
}
