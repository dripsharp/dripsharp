// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace DripSharp.Runtime;

internal static class PdfCartonFontDiscovery
{
    internal static bool FileExists(global::System.IO.FileInfo file) =>
        global::System.IO.File.Exists(file.FullName);
}
