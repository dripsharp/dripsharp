// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// PdfCarton adapters for pinned, source-built JBIG2 and JPEG 2000 decoders.
#nullable enable

using System;
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
        using var stream = new MemoryStream(encoded, writable: false);
        using var image = J2kImage.FromStream(stream, out var metadata);
        var hasAlpha = metadata.ChannelDefinitions?.HasAlphaChannel == true;
        var colorComponents = image.NumberOfComponents - (hasAlpha ? 1 : 0);
        if (colorComponents <= 0 || colorComponents > 15)
            throw new IOException(
                $"JPEG 2000 image has unsupported component count {image.NumberOfComponents}.");

        var bits = image.BitDepths.ToArray();
        var dataType = bits.Max() switch
        {
            <= 8 => PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
            <= 16 => PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT,
            _ => PdfCartonFontCompat.DATA_BUFFER_TYPE_INT
        };
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

    private static JavaColorSpace CreateColorSpace(int components) =>
        components switch
        {
            1 => new JavaColorSpace(JavaColorSpace.CS_GRAY),
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
