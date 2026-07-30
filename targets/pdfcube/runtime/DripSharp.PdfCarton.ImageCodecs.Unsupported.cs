// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

#nullable enable

using System;
using SkiaSharp;

namespace DripSharp.Runtime;

internal static class PdfCartonImageCodecs
{
    internal static bool Supports(string formatName)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        return false;
    }

    internal static SKBitmap Decode(string formatName, byte[] encoded) =>
        throw new NotSupportedException(
            $"The `{formatName}` image codec is not part of this product.");
}
