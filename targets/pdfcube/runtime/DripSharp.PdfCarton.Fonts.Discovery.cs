// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused destination compatibility for FontBox host font discovery.
#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security;

namespace DripSharp.Runtime;

internal static class PdfCartonFontDiscovery
{
    internal static bool FileExists(FileInfo file)
    {
        try
        {
            return File.Exists(file.FullName) || Directory.Exists(file.FullName);
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileIsDirectory(FileInfo file)
    {
        try
        {
            return Directory.Exists(file.FullName);
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileCanRead(FileInfo file)
    {
        try
        {
            if (Directory.Exists(file.FullName))
            {
                using var entries = Directory
                    .EnumerateFileSystemEntries(file.FullName)
                    .GetEnumerator();
                _ = entries.MoveNext();
                return true;
            }

            if (!File.Exists(file.FullName))
            {
                return false;
            }

            using var stream = File.Open(
                file.FullName,
                FileMode.Open,
                FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete);
            return stream.CanRead;
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileIsHidden(FileInfo file)
    {
        if (file.Name.StartsWith(".", StringComparison.Ordinal))
        {
            return true;
        }

        try
        {
            return FileExists(file) &&
                (File.GetAttributes(file.FullName) & FileAttributes.Hidden) != 0;
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static FileInfo[]? FileListFiles(FileInfo directory) =>
        FileListFiles(directory, Directory.EnumerateFileSystemEntries);

    internal static Uri FileToUri(FileInfo file) =>
        new(Path.GetFullPath(file.FullName), UriKind.Absolute);

    // Kept internal so package-only verification can exercise the inaccessible
    // directory path without publishing a filesystem abstraction.
    internal static FileInfo[]? FileListFiles(
        FileInfo directory,
        Func<string, IEnumerable<string>> enumerate)
    {
        try
        {
            if (!Directory.Exists(directory.FullName))
            {
                return null;
            }

            return enumerate(directory.FullName)
                .Select(path => new FileInfo(path))
                .ToArray();
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            // java.io.File.listFiles() returns null when the directory cannot
            // be enumerated. FontFileFinder treats that as an unreadable path.
            return null;
        }
    }

    private static bool IsInaccessible(Exception error) =>
        error is UnauthorizedAccessException or IOException or SecurityException;
}
