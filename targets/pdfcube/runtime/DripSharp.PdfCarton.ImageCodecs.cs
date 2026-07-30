// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// PdfCarton adapters for pinned, source-built JBIG2 and JPEG 2000 decoders.
#nullable enable

using System;
using System.IO;
using System.Linq;
using CoreJ2K;
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

    private static SKBitmap DecodeJbig2(byte[] encoded)
    {
        var decoder = new JBIG2StreamDecoder
        {
            TolerateMissingSegments = false
        };
        var samples = decoder.DecodeJBIG2(encoded, out var width, out var height);
        if (width <= 0 || height <= 0 ||
            samples.Length != checked(width * height * 3))
        {
            throw new IOException("JBIG2 decoder returned invalid image dimensions.");
        }

        var colorModel = PdfCartonFontCompat.IndexColorModel(
            1,
            2,
            [0, -1],
            [0, -1],
            [0, -1]);
        var raster = colorModel.CreateCompatibleWritableRaster(width, height);
        var values = new int[checked(width * height)];
        for (var index = 0; index < values.Length; index++)
            values[index] = samples[index * 3] < 128 ? 0 : 1;
        raster.SetPixels(0, 0, width, height, values);
        return PdfCartonFontCompat.CreateImage(colorModel, raster, false, null);
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
        for (var component = 0; component < image.NumberOfComponents; component++)
        {
            raster.SetSamples(
                0,
                0,
                image.Width,
                image.Height,
                component,
                image.GetComponent(component));
        }
        return PdfCartonFontCompat.CreateImage(
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
