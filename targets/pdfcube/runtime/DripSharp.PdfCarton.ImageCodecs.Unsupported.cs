// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

#nullable enable

using System;
using SkiaSharp;

// The managed image type lives in the FontBox assembly only.
using PdfCartonImage = global::DripSharp.PdfCarton.Runtime.Fonts.PdfCartonImage;

namespace DripSharp.Runtime;

internal static class PdfCartonImageCodecs
{
    internal static bool Supports(string formatName)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        return false;
    }

    internal static SKSizeI? ReadSize(string formatName, byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        ArgumentNullException.ThrowIfNull(encoded);
        return null;
    }

    internal static JavaRaster? ReadJpegSamples(byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        return null;
    }

    internal static PdfCartonImage Decode(string formatName, byte[] encoded) =>
        throw new NotSupportedException(
            $"The `{formatName}` image codec is not part of this product.");
}
