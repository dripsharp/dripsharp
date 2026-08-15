// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace DripSharp.PdfCarton.Tests;

internal sealed class UpstreamTestCaseOrderer : global::Xunit.v3.ITestCaseOrderer
{
    public global::System.Collections.Generic.IReadOnlyCollection<TTestCase>
        OrderTestCases<TTestCase>(
            global::System.Collections.Generic.IReadOnlyCollection<TTestCase> testCases)
        where TTestCase : notnull, global::Xunit.Sdk.ITestCase =>
        testCases
            .OrderBy(testCase => testCase.TestMethod?.MethodName ?? string.Empty,
                     global::System.StringComparer.Ordinal)
            .ToArray();
}

internal static class Support
{
    private static readonly object WorkingFixtureLock = new();
    private static bool WorkingFixturesInitialized;
    private static readonly object MaterializedResourceLock = new();
    private static readonly object DeleteOnExitLock = new();
    private static readonly global::System.Collections.Generic.HashSet<string>
        DeleteOnExitPaths = new(global::System.StringComparer.Ordinal);
    private static readonly (
        global::System.IO.UnixFileMode Source,
        global::DripSharp.Runtime.JavaUnixFileMode Target)[] PosixPermissionMappings =
    {
        (global::System.IO.UnixFileMode.UserRead,
         global::DripSharp.Runtime.JavaUnixFileMode.UserRead),
        (global::System.IO.UnixFileMode.UserWrite,
         global::DripSharp.Runtime.JavaUnixFileMode.UserWrite),
        (global::System.IO.UnixFileMode.UserExecute,
         global::DripSharp.Runtime.JavaUnixFileMode.UserExecute),
        (global::System.IO.UnixFileMode.GroupRead,
         global::DripSharp.Runtime.JavaUnixFileMode.GroupRead),
        (global::System.IO.UnixFileMode.GroupWrite,
         global::DripSharp.Runtime.JavaUnixFileMode.GroupWrite),
        (global::System.IO.UnixFileMode.GroupExecute,
         global::DripSharp.Runtime.JavaUnixFileMode.GroupExecute),
        (global::System.IO.UnixFileMode.OtherRead,
         global::DripSharp.Runtime.JavaUnixFileMode.OtherRead),
        (global::System.IO.UnixFileMode.OtherWrite,
         global::DripSharp.Runtime.JavaUnixFileMode.OtherWrite),
        (global::System.IO.UnixFileMode.OtherExecute,
         global::DripSharp.Runtime.JavaUnixFileMode.OtherExecute)
    };
    private static bool DeleteOnExitRegistered;
    internal static JavaErrorStream ErrorStream { get; } = new();

    [global::System.Runtime.CompilerServices.ModuleInitializer]
    internal static void InitializeTestEnvironment()
    {
        global::System.Environment.SetEnvironmentVariable("TZ", "UTC");
        global::System.TimeZoneInfo.ClearCachedData();
    }

    internal static void CloseQuietly(object? closeable)
    {
        try
        {
            switch (closeable)
            {
                case global::System.Action action:
                    action();
                    break;
                case global::System.IDisposable disposable:
                    disposable.Dispose();
                    break;
            }
        }
        catch
        {
            // This is the exact contract of Java IOUtils.closeQuietly.
        }
    }

    internal static global::System.IO.IOException? CloseAndLogException(
        object? closeable,
        global::Microsoft.Extensions.Logging.ILogger _,
        string __,
        global::System.IO.IOException? initial)
    {
        try
        {
            switch (closeable)
            {
                case global::System.Action action:
                    action();
                    break;
                case global::System.IDisposable disposable:
                    disposable.Dispose();
                    break;
            }
        }
        catch (global::System.IO.IOException error)
        {
            return initial ?? error;
        }
        return initial;
    }

    internal static global::System.IO.TextReader NewInputStreamReader(
        global::System.IO.Stream input, string encoding) =>
        new global::System.IO.StreamReader(
            input, EncodingByName(encoding), true, 1024, false);

    internal static global::System.IO.TextReader NewInputStreamReader(
        global::System.IO.Stream input, global::System.Text.Encoding encoding) =>
        new global::System.IO.StreamReader(input, encoding, true, 1024, false);

    internal static global::System.IO.StreamWriter NewFileWriter(
        global::System.IO.FileInfo file) => new(file.FullName, false);

    internal static void WriteAllBytes(string path, sbyte[] values)
    {
        byte[] bytes = new byte[values.Length];
        global::System.Buffer.BlockCopy(values, 0, bytes, 0, values.Length);
        global::System.IO.File.WriteAllBytes(path, bytes);
    }

    internal static void RunWithTimeout(global::System.Action action, long milliseconds)
    {
        global::System.Threading.Tasks.Task task =
            global::System.Threading.Tasks.Task.Run(action);
        if (!task.Wait(global::System.TimeSpan.FromMilliseconds(milliseconds)))
        {
            throw new global::System.TimeoutException(
                $"Upstream JUnit timeout expired after {milliseconds} ms.");
        }
        task.GetAwaiter().GetResult();
    }

    internal static T TheoryArgument<T>(object? value)
    {
        if (value is null) return default!;
        if (value is T typed) return typed;
        global::System.Type target = typeof(T);
        if (target.IsGenericType &&
            value is global::System.Collections.IEnumerable values)
        {
            global::System.Type definition = target.GetGenericTypeDefinition();
            if (definition == typeof(global::System.Collections.Generic.IEnumerable<>) ||
                definition == typeof(global::System.Collections.Generic.ICollection<>) ||
                definition == typeof(global::System.Collections.Generic.IList<>) ||
                definition == typeof(global::System.Collections.Generic.IReadOnlyCollection<>) ||
                definition == typeof(global::System.Collections.Generic.IReadOnlyList<>))
            {
                global::System.Type listType = typeof(global::System.Collections.Generic.List<>)
                    .MakeGenericType(target.GetGenericArguments()[0]);
                var converted = (global::System.Collections.IList)
                    global::System.Activator.CreateInstance(listType)!;
                foreach (object? item in values) converted.Add(item);
                return (T)converted;
            }
        }
        return (T)value;
    }

    internal static global::System.IO.Stream ResourceStream(
        global::System.Type owner, string name)
    {
        string logicalName = ResourceLogicalName(owner, name);
        global::System.Reflection.Assembly[] assemblies =
            global::System.AppDomain.CurrentDomain.GetAssemblies();
        global::System.Array.Sort(
            assemblies,
            (left, right) => global::System.StringComparer.Ordinal.Compare(
                left.FullName, right.FullName));
        foreach (global::System.Reflection.Assembly assembly in assemblies)
        {
            global::System.IO.Stream? stream =
                assembly.GetManifestResourceStream(logicalName);
            if (stream is not null) return stream;
        }
        return global::System.IO.File.OpenRead(ResourcePath(owner, name));
    }

    internal static global::System.Uri ResourceUri(
        global::System.Type owner, string name)
    {
        try
        {
            return new global::System.Uri(ResourcePath(owner, name));
        }
        catch (global::System.IO.FileNotFoundException)
        {
            string logicalName = ResourceLogicalName(owner, name);
            string root = global::System.IO.Path.GetFullPath(global::System.IO.Path.Combine(
                global::System.AppContext.BaseDirectory, "MaterializedResources"));
            string path = global::System.IO.Path.GetFullPath(
                global::System.IO.Path.Combine(root, logicalName));
            lock (MaterializedResourceLock)
            {
                if (!global::System.IO.File.Exists(path))
                {
                    using global::System.IO.Stream source = ResourceStream(owner, name);
                    global::System.IO.Directory.CreateDirectory(root);
                    using global::System.IO.FileStream destination =
                        global::System.IO.File.Create(path);
                    source.CopyTo(destination);
                }
            }
            return new global::System.Uri(path);
        }
    }

    internal static global::System.IO.DriveInfo FileStore(string path)
    {
        string fullPath = global::System.IO.Path.GetFullPath(path);
        string root = global::System.IO.Path.GetPathRoot(fullPath)!;
        return new global::System.IO.DriveInfo(root);
    }

    internal static bool SupportsFileAttributeView(
        global::System.IO.DriveInfo _, string view) =>
        view.Equals("posix", global::System.StringComparison.OrdinalIgnoreCase) &&
        !global::System.OperatingSystem.IsWindows();

    internal static global::System.Collections.Generic.ISet<
        global::DripSharp.Runtime.JavaUnixFileMode> GetPosixFilePermissions(string path) =>
        ToJavaUnixFileModes(global::System.IO.File.GetUnixFileMode(path));

    internal static global::System.Collections.Generic.ISet<
        global::DripSharp.Runtime.JavaUnixFileMode> ToJavaUnixFileModes(
            global::System.IO.UnixFileMode mode)
    {
        var result = new global::System.Collections.Generic.HashSet<
            global::DripSharp.Runtime.JavaUnixFileMode>();
        foreach ((global::System.IO.UnixFileMode source,
                  global::DripSharp.Runtime.JavaUnixFileMode target) in
                 PosixPermissionMappings)
        {
            if ((mode & source) == source)
                result.Add(target);
        }
        return result;
    }

    internal static global::System.IO.StreamWriter NewBufferedWriter(
        string path, params object?[] _) =>
        new(path, false, new global::System.Text.UTF8Encoding(false));

    internal static string OutputText(global::System.IO.MemoryStream output) =>
        global::System.Text.Encoding.UTF8.GetString(output.ToArray());

    internal static void SetDefaultTimeZone(global::System.TimeZoneInfo zone)
    {
        global::System.Environment.SetEnvironmentVariable("TZ", zone.Id);
        global::System.TimeZoneInfo.ClearCachedData();
    }

    internal static global::System.Text.Encoding EncodingByName(string name) =>
        name.Equals("UnicodeBig", global::System.StringComparison.OrdinalIgnoreCase)
            ? global::DripSharp.Runtime.JavaStandardCharsets.UTF16
            : global::System.Text.Encoding.GetEncoding(name);

    internal static bool Mkdirs(global::System.IO.FileInfo directory)
    {
        bool absent = !global::System.IO.Directory.Exists(directory.FullName);
        global::System.IO.Directory.CreateDirectory(directory.FullName);
        return absent;
    }

    internal static global::System.IO.FileInfo ParentFile(
        global::System.IO.FileInfo file) =>
        new(global::System.IO.Path.GetDirectoryName(file.FullName)!);

    internal static global::System.IO.FileInfo[] ListFiles(
        global::System.IO.FileInfo directory,
        global::System.Func<global::System.IO.FileInfo, string, bool> filter) =>
        global::System.IO.Directory.EnumerateFileSystemEntries(directory.FullName)
            .Select(path => new global::System.IO.FileInfo(path))
            .Where(file => filter(directory, file.Name))
            .ToArray();

    internal static global::System.Collections.Generic.ICollection<
        global::System.IO.FileInfo> ListFiles(
            global::System.IO.FileInfo directory,
            string[]? extensions,
            bool recursive)
    {
        global::System.IO.SearchOption search = recursive
            ? global::System.IO.SearchOption.AllDirectories
            : global::System.IO.SearchOption.TopDirectoryOnly;
        global::System.Collections.Generic.HashSet<string>? accepted = extensions is null
            ? null
            : new global::System.Collections.Generic.HashSet<string>(
                extensions.Select(extension => extension.TrimStart('.')),
                global::System.StringComparer.OrdinalIgnoreCase);
        return global::System.IO.Directory.EnumerateFiles(
                directory.FullName, "*", search)
            .Where(path => accepted is null ||
                accepted.Contains(global::System.IO.Path.GetExtension(path).TrimStart('.')))
            .Select(path => new global::System.IO.FileInfo(path))
            .ToArray();
    }

    internal static global::System.Collections.Generic.ICollection<object>
        ListFilesObjects(
            global::System.IO.FileInfo directory,
            string[]? extensions,
            bool recursive) =>
        ListFiles(directory, extensions, recursive).Cast<object>().ToArray();

    internal static void DeleteOnExit(global::System.IO.FileInfo file)
    {
        lock (DeleteOnExitLock)
        {
            DeleteOnExitPaths.Add(file.FullName);
            if (DeleteOnExitRegistered) return;
            global::System.AppDomain.CurrentDomain.ProcessExit += (_, _) =>
            {
                lock (DeleteOnExitLock)
                {
                    foreach (string path in DeleteOnExitPaths)
                    {
                        try
                        {
                            global::System.IO.File.Delete(path);
                        }
                        catch (global::System.IO.IOException)
                        {
                        }
                        catch (global::System.UnauthorizedAccessException)
                        {
                        }
                    }
                }
            };
            DeleteOnExitRegistered = true;
        }
    }

    internal static bool IsWhitespace(int codePoint) =>
        global::System.Text.Rune.IsWhiteSpace(new global::System.Text.Rune(codePoint));

    internal static char[] ToChars(int codePoint) =>
        char.ConvertFromUtf32(codePoint).ToCharArray();

    internal static bool IsISOControl(int codePoint) =>
        codePoint is >= 0 and <= 0x1f or >= 0x7f and <= 0x9f;

    internal static decimal DecimalPow(decimal value, int exponent, object _)
    {
        decimal result = decimal.One;
        for (int remaining = global::System.Math.Abs(exponent);
             remaining > 0;
             remaining--)
        {
            result *= value;
        }
        return exponent < 0 ? decimal.One / result : result;
    }

    internal static long? MaxLong(global::System.Collections.Generic.IEnumerable<long> values)
    {
        using global::System.Collections.Generic.IEnumerator<long> iterator =
            values.GetEnumerator();
        if (!iterator.MoveNext()) return null;
        long maximum = iterator.Current;
        while (iterator.MoveNext()) maximum = global::System.Math.Max(maximum, iterator.Current);
        return maximum;
    }

    internal static global::System.DateTimeOffset CalendarFromUnixTimeMilliseconds(
        long milliseconds)
    {
        const long minimum = -62135596800000;
        const long maximum = 253402300799999;
        global::System.Numerics.BigInteger range =
            (global::System.Numerics.BigInteger)maximum - minimum + 1;
        global::System.Numerics.BigInteger offset =
            ((global::System.Numerics.BigInteger)milliseconds - minimum) % range;
        if (offset.Sign < 0) offset += range;
        return global::System.DateTimeOffset.FromUnixTimeMilliseconds(
            minimum + (long)offset);
    }

    internal static global::System.DateTimeOffset GregorianCalendar(
        int year, int zeroBasedMonth, int day)
    {
        var local = new global::System.DateTime(year, zeroBasedMonth + 1, day);
        return new global::System.DateTimeOffset(
            local, global::System.TimeZoneInfo.Local.GetUtcOffset(local));
    }


    internal static global::System.DateTimeOffset GregorianCalendar(
        int year,
        int zeroBasedMonth,
        int day,
        int hour,
        int minute,
        int second)
    {
        var local = new global::System.DateTime(
            year, zeroBasedMonth + 1, day, hour, minute, second);
        return new global::System.DateTimeOffset(
            local, global::System.TimeZoneInfo.Local.GetUtcOffset(local));
    }

    internal static void SetDefaultCulture(
        global::System.Globalization.CultureInfo culture)
    {
        global::System.Globalization.CultureInfo.CurrentCulture = culture;
        global::System.Globalization.CultureInfo.CurrentUICulture = culture;
    }

    internal static global::System.Collections.Generic.IEnumerable<T> Limit<T>(
        global::System.Collections.Generic.IEnumerable<T> values,
        long count) =>
        values.Take(checked((int)count));

    internal static int ColorRgb(global::DripSharp.Runtime.JavaColor color) =>
        unchecked((int)(uint)(global::SkiaSharp.SKColor)color);

    internal static global::SkiaSharp.SKBitmap CreateCompatibleImage(
        int width, int height, int transparency) =>
        global::DripSharp.Runtime.PdfCartonFontCompat.CreateCompatibleImage(
            width, height, transparency);

    internal static bool IsAlphaPremultiplied(global::SkiaSharp.SKBitmap image) =>
        image.AlphaType == global::SkiaSharp.SKAlphaType.Premul;

    internal static global::SkiaSharp.SKBitmap Subimage(
        global::SkiaSharp.SKBitmap source,
        int x,
        int y,
        int width,
        int height)
    {
        var result = new global::SkiaSharp.SKBitmap(
            width, height, source.ColorType, source.AlphaType);
        if (!source.ExtractSubset(
                result,
                new global::SkiaSharp.SKRectI(x, y, x + width, y + height)))
        {
            result.Dispose();
            throw new global::System.ArgumentException(
                "Requested subimage is outside the source image.");
        }
        global::DripSharp.Runtime.PdfCartonFontCompat.RegisterImageType(
            result,
            global::DripSharp.Runtime.PdfCartonFontCompat.GetImageType(source));
        return result;
    }

    internal static global::DripSharp.Runtime.JavaRaster CopyImageData(
        global::SkiaSharp.SKBitmap source,
        global::DripSharp.Runtime.JavaRaster? destination)
    {
        global::DripSharp.Runtime.JavaRaster sourceRaster =
            global::DripSharp.Runtime.PdfCartonFontCompat.GetRaster(source);
        if (destination is null) return sourceRaster.DeepCopy();
        for (int y = 0; y < sourceRaster.Height; y++)
        {
            for (int x = 0; x < sourceRaster.Width; x++)
            {
                destination.SetPixel(
                    x, y, sourceRaster.GetPixel(x, y, (int[]?)null));
            }
        }
        return destination;
    }

    internal static int ColorModelTransparency(
        global::DripSharp.Runtime.JavaColorModel model) =>
        model.Transparency;

    internal static int[] ComponentSizes(
        global::DripSharp.Runtime.JavaColorModel model)
    {
        int components = model.NumberOfComponents;
        int componentBits = model.PixelSize / components;
        return global::System.Linq.Enumerable.Repeat(componentBits, components)
            .ToArray();
    }

    internal static void SetDataElements(
        global::DripSharp.Runtime.JavaRaster raster,
        int x,
        int y,
        int width,
        int height,
        object values)
    {
        if (height == 1 && values is int[] integers && integers.Length >= width)
        {
            for (int offset = 0; offset < width; offset++)
            {
                raster.SetDataElements(x + offset, y, new[] { integers[offset] });
            }
            return;
        }
        throw new global::System.ArgumentException(
            "Unsupported rectangular raster data layout.", nameof(values));
    }

    internal static global::SkiaSharp.SKBitmap ReadImage(global::System.Uri uri)
    {
        using global::System.IO.Stream input = uri.IsFile
            ? global::System.IO.File.OpenRead(uri.LocalPath)
            : new global::System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
        return global::DripSharp.Runtime.PdfCartonFontCompat.ReadImage(input);
    }

    internal static bool WriteImage(
        global::SkiaSharp.SKBitmap image,
        string format,
        global::System.IO.FileInfo destination)
    {
        global::SkiaSharp.SKEncodedImageFormat encodedFormat =
            format.Equals("jpg", global::System.StringComparison.OrdinalIgnoreCase) ||
            format.Equals("jpeg", global::System.StringComparison.OrdinalIgnoreCase)
                ? global::SkiaSharp.SKEncodedImageFormat.Jpeg
                : global::SkiaSharp.SKEncodedImageFormat.Png;
        global::System.IO.Directory.CreateDirectory(destination.DirectoryName!);
        using global::SkiaSharp.SKImage encodedImage =
            global::SkiaSharp.SKImage.FromBitmap(image);
        using global::SkiaSharp.SKData data = encodedImage.Encode(encodedFormat, 100);
        using global::System.IO.Stream output = destination.Open(
            global::System.IO.FileMode.Create,
            global::System.IO.FileAccess.Write,
            global::System.IO.FileShare.None);
        data.SaveTo(output);
        return true;
    }

    internal static bool WriteImage(
        global::SkiaSharp.SKBitmap image,
        string format,
        global::System.IO.Stream output)
    {
        global::SkiaSharp.SKEncodedImageFormat encodedFormat =
            format.Equals("jpg", global::System.StringComparison.OrdinalIgnoreCase) ||
            format.Equals("jpeg", global::System.StringComparison.OrdinalIgnoreCase)
                ? global::SkiaSharp.SKEncodedImageFormat.Jpeg
                : global::SkiaSharp.SKEncodedImageFormat.Png;
        using global::SkiaSharp.SKImage encodedImage =
            global::SkiaSharp.SKImage.FromBitmap(image);
        using global::SkiaSharp.SKData data = encodedImage.Encode(encodedFormat, 100);
        data.SaveTo(output);
        return true;
    }

    internal static global::DripSharp.Runtime.JavaIterator<
        global::DripSharp.Runtime.JavaImageReader> GetImageReaders(object _) =>
        global::DripSharp.Runtime.JavaCompat.Iterator(
            new[] { new global::DripSharp.Runtime.JavaImageReader("TIFF") });

    internal static int ImageCount(
        global::DripSharp.Runtime.JavaImageReader reader)
    {
        var inputField = typeof(global::DripSharp.Runtime.JavaImageReader).GetField(
            "input",
            global::System.Reflection.BindingFlags.Instance |
            global::System.Reflection.BindingFlags.NonPublic);
        var input = (global::DripSharp.Runtime.JavaImageInputStream?)
            inputField?.GetValue(reader);
        if (input is null) return 0;
        byte[] bytes = input.Bytes;
        if (bytes.Length < 8) return 1;
        bool littleEndian = bytes[0] == (byte)'I' && bytes[1] == (byte)'I';
        bool bigEndian = bytes[0] == (byte)'M' && bytes[1] == (byte)'M';
        if (!littleEndian && !bigEndian) return 1;

        ushort ReadUInt16(int offset) => littleEndian
            ? global::System.Buffers.Binary.BinaryPrimitives.ReadUInt16LittleEndian(
                bytes.AsSpan(offset, 2))
            : global::System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(
                bytes.AsSpan(offset, 2));
        uint ReadUInt32(int offset) => littleEndian
            ? global::System.Buffers.Binary.BinaryPrimitives.ReadUInt32LittleEndian(
                bytes.AsSpan(offset, 4))
            : global::System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(
                bytes.AsSpan(offset, 4));

        uint offset = ReadUInt32(4);
        var visited = new global::System.Collections.Generic.HashSet<uint>();
        int count = 0;
        while (offset != 0 && offset <= bytes.Length - 2 && visited.Add(offset))
        {
            int directory = checked((int)offset);
            int nextOffset = checked(directory + 2 + 12 * ReadUInt16(directory));
            if (nextOffset > bytes.Length - 4) break;
            count++;
            offset = ReadUInt32(nextOffset);
        }
        return global::System.Math.Max(1, count);
    }

    internal static global::DripSharp.Runtime.JavaRaster CreatePackedRaster(
        int dataType,
        int width,
        int height,
        int[] masks,
        global::DripSharp.Runtime.JavaPoint _)
    {
        global::System.ArgumentNullException.ThrowIfNull(masks);
        return new global::DripSharp.Runtime.JavaRaster(
            dataType, width, height, 1);
    }

    internal static global::System.IO.FileInfo TestFile(string path)
        => new(TestPath(string.Empty, path));

    internal static string TestPath(string path) => TestPath(string.Empty, path);

    internal static string TestPath(string module, string path)
    {
        if (path is null) return null!;
        if (path.Equals("target/fonts/Keyboard.ttf",
                        global::System.StringComparison.Ordinal))
        {
            path = "target/fonts/PdfCartonCmap01.ttf";
        }
        if (global::System.Uri.TryCreate(
                path, global::System.UriKind.Absolute, out global::System.Uri? uri) &&
            uri.Scheme.Equals("https", global::System.StringComparison.OrdinalIgnoreCase) &&
            uri.Host.Equals(
                "issues.apache.org", global::System.StringComparison.OrdinalIgnoreCase))
        {
            string[] segments = uri.AbsolutePath.Split(
                '/', global::System.StringSplitOptions.RemoveEmptyEntries);
            if (segments.Length >= 5 &&
                segments[0].Equals("jira", global::System.StringComparison.Ordinal) &&
                segments[1].Equals("secure", global::System.StringComparison.Ordinal) &&
                segments[2].Equals("attachment", global::System.StringComparison.Ordinal) &&
                global::System.Linq.Enumerable.All(
                    segments[3], global::System.Char.IsAsciiDigit))
            {
                string fixture = ContainedFixturePath(
                    global::System.IO.Path.Combine("remote", segments[3]),
                    allowDirectory: false);
                return new global::System.Uri(fixture).AbsoluteUri;
            }
        }
        const string sourceFixturePrefix = "src/test/resources/";
        if (path.StartsWith(sourceFixturePrefix, global::System.StringComparison.Ordinal))
        {
            return WritableFixture(path.Substring(sourceFixturePrefix.Length)).FullName;
        }
        string[] fixturePrefixes = { "target/test-classes/", "build/resources/test/" };
        foreach (string prefix in fixturePrefixes)
        {
            if (!path.StartsWith(prefix, global::System.StringComparison.Ordinal)) continue;
            return ContainedFixturePath(
                path.Substring(prefix.Length), allowDirectory: true);
        }
        if (path.StartsWith("src/test/java/", global::System.StringComparison.Ordinal) ||
            path.Equals("target/fonts", global::System.StringComparison.Ordinal) ||
            path.StartsWith("target/fonts/", global::System.StringComparison.Ordinal) ||
            path.Equals("target/imgs", global::System.StringComparison.Ordinal) ||
            path.StartsWith("target/imgs/", global::System.StringComparison.Ordinal) ||
            path.Equals("target/pdfs", global::System.StringComparison.Ordinal) ||
            path.StartsWith("target/pdfs/", global::System.StringComparison.Ordinal))
        {
            string relative = string.IsNullOrEmpty(module)
                ? path
                : global::System.IO.Path.Combine("modules", module, path);
            return ContainedFixturePath(relative, allowDirectory: true);
        }
        (string Prefix, string Root)[] mutablePaths =
        {
            ("target/test-output-ext", "TestOutputExternal"),
            ("target/test-input-ext", "TestInputExternal"),
            ("target/test-output", "TestOutput")
        };
        foreach ((string prefix, string root) in mutablePaths)
        {
            if (!path.Equals(prefix, global::System.StringComparison.Ordinal) &&
                !path.StartsWith(prefix + "/", global::System.StringComparison.Ordinal))
            {
                continue;
            }
            string relative = path.Substring(prefix.Length).TrimStart('/');
            return MutableArtifactPath(root, relative);
        }
        return path;
    }

    private static global::System.IO.FileInfo WritableFixture(string relative)
    {
        string source = ContainedFixturePath(relative, allowDirectory: true);
        string root = MutableArtifactRoot("WritableFixtures");
        string destination = MutableArtifactPath("WritableFixtures", relative);
        lock (WorkingFixtureLock)
        {
            if (!WorkingFixturesInitialized)
            {
                ResetMutableArtifactRoot("WritableFixtures");
                global::System.IO.Directory.CreateDirectory(root);
                WorkingFixturesInitialized = true;
            }
            if (global::System.IO.Directory.Exists(source))
            {
                CopyFixtureDirectory(source, destination);
            }
            else if (!global::System.IO.File.Exists(destination))
            {
                global::System.IO.Directory.CreateDirectory(
                    global::System.IO.Path.GetDirectoryName(destination)!);
                global::System.IO.File.Copy(source, destination);
            }
        }
        return new global::System.IO.FileInfo(destination);
    }

    internal static string ResetMutableTestArtifactsForContract()
    {
        string name = global::System.IO.Path.Combine(
            "LifecycleContractArtifacts",
            global::System.Guid.NewGuid().ToString("N"));
        string probe = MutableArtifactPath(name, "probe.txt");
        global::System.IO.Directory.CreateDirectory(
            global::System.IO.Path.GetDirectoryName(probe)!);
        global::System.IO.File.WriteAllText(probe, "mutable lifecycle probe");
        ResetMutableArtifactRoot(name);
        return probe;
    }

    private static void ResetMutableArtifactRoot(string name)
    {
        string root = MutableArtifactRoot(name);
        if (global::System.IO.Directory.Exists(root))
            global::System.IO.Directory.Delete(root, recursive: true);
    }

    private static string MutableArtifactRoot(string name)
    {
        string baseDirectory = global::System.IO.Path.GetFullPath(
            global::System.AppContext.BaseDirectory);
        string root = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(baseDirectory, name));
        if (!root.StartsWith(
                baseDirectory + global::System.IO.Path.DirectorySeparatorChar,
                global::System.StringComparison.Ordinal) ||
            root.Equals(
                global::System.IO.Path.Combine(baseDirectory, "Fixtures"),
                global::System.StringComparison.Ordinal))
        {
            throw new global::System.IO.IOException(
                $"Mutable test artifact root escapes the test output: {name}");
        }
        return root;
    }

    private static string MutableArtifactPath(string rootName, string relative)
    {
        string root = MutableArtifactRoot(rootName);
        string path = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(root, relative));
        if (!path.Equals(root, global::System.StringComparison.Ordinal) &&
            !path.StartsWith(
                root + global::System.IO.Path.DirectorySeparatorChar,
                global::System.StringComparison.Ordinal))
        {
            throw new global::System.IO.IOException(
                $"Mutable test artifact path escapes its contained root: {relative}");
        }
        return path;
    }

    private static void CopyFixtureDirectory(string source, string destination)
    {
        global::System.IO.Directory.CreateDirectory(destination);
        foreach (string directory in global::System.IO.Directory.EnumerateDirectories(
                     source, "*", global::System.IO.SearchOption.AllDirectories))
        {
            global::System.IO.Directory.CreateDirectory(global::System.IO.Path.Combine(
                destination, global::System.IO.Path.GetRelativePath(source, directory)));
        }
        foreach (string file in global::System.IO.Directory.EnumerateFiles(
                     source, "*", global::System.IO.SearchOption.AllDirectories))
        {
            string copy = global::System.IO.Path.Combine(
                destination, global::System.IO.Path.GetRelativePath(source, file));
            if (!global::System.IO.File.Exists(copy))
                global::System.IO.File.Copy(file, copy);
        }
    }

    private static string ResourceLogicalName(
        global::System.Type owner, string name)
    {
        string relative;
        if (name.StartsWith("/", global::System.StringComparison.Ordinal))
        {
            relative = name.TrimStart('/');
        }
        else
        {
            string ownerNamespace = owner.Namespace ?? string.Empty;
            string ownerPackage = ownerNamespace
                .Replace("DripSharp.PdfCarton.Preflight", "org.apache.pdfbox.preflight",
                    global::System.StringComparison.Ordinal)
                .Replace("DripSharp.PdfCarton.Fonts", "org.apache.fontbox",
                    global::System.StringComparison.Ordinal)
                .Replace("DripSharp.PdfCarton.Xmp", "org.apache.xmpbox",
                    global::System.StringComparison.Ordinal)
                .Replace("DripSharp.PdfCarton.IO", "org.apache.pdfbox.io",
                    global::System.StringComparison.Ordinal)
                .Replace("DripSharp.PdfCarton", "org.apache.pdfbox",
                    global::System.StringComparison.Ordinal);
            relative = string.IsNullOrEmpty(ownerPackage)
                ? name
                : ownerPackage + "." + name;
        }
        return relative.Replace('/', '.');
    }

    private static string ResourcePath(global::System.Type owner, string name)
    {
        string relative;
        if (name.StartsWith("/", global::System.StringComparison.Ordinal))
        {
            relative = name.TrimStart('/');
        }
        else
        {
            string ownerNamespace = owner.Namespace ?? string.Empty;
            (string Prefix, string Package)[] mappings =
            {
                ("DripSharp.PdfCarton.Preflight", "org/apache/pdfbox/preflight"),
                ("DripSharp.PdfCarton.Fonts", "org/apache/fontbox"),
                ("DripSharp.PdfCarton.Xmp", "org/apache/xmpbox"),
                ("DripSharp.PdfCarton.IO", "org/apache/pdfbox/io"),
                ("DripSharp.PdfCarton", "org/apache/pdfbox")
            };
            string package = string.Empty;
            foreach ((string prefix, string sourcePackage) in mappings)
            {
                if (!ownerNamespace.Equals(prefix, global::System.StringComparison.Ordinal) &&
                    !ownerNamespace.StartsWith(
                        prefix + ".", global::System.StringComparison.Ordinal)) continue;
                string suffix = ownerNamespace.Substring(prefix.Length).TrimStart('.');
                package = string.IsNullOrEmpty(suffix)
                    ? sourcePackage
                    : sourcePackage + "/" + string.Join(
                        '/',
                        global::System.Linq.Enumerable.Select(
                            suffix.Split('.'), segment => segment.ToLowerInvariant()));
                break;
            }
            relative = string.IsNullOrEmpty(package)
                ? name
                : package + "/" + name;
        }
        return ContainedFixturePath(
            relative.Replace('/', global::System.IO.Path.DirectorySeparatorChar),
            allowDirectory: false);
    }

    private static string ContainedFixturePath(string relative, bool allowDirectory)
    {
        string root = global::System.IO.Path.GetFullPath(global::System.IO.Path.Combine(
            global::System.AppContext.BaseDirectory, "Fixtures"));
        string path = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(root, relative));
        if (!path.StartsWith(
                root + global::System.IO.Path.DirectorySeparatorChar,
                global::System.StringComparison.Ordinal) ||
            (!allowDirectory && !global::System.IO.File.Exists(path)))
        {
            throw new global::System.IO.FileNotFoundException(
                $"PdfCarton fixture is missing or escapes its contained root: {relative}",
                path);
        }
        return path;
    }
}

public sealed class PdfCartonTestSupportContractTests
{
    [global::Xunit.Fact]
    public void MutableArtifactCleanupPreservesGovernedFixturesAndBuildInputs()
    {
        GeneratedSuiteIntegrityTests.VerifyGovernedFixtures();
        string assembly = typeof(PdfCartonTestSupportContractTests).Assembly.Location;
        global::Xunit.Assert.True(global::System.IO.File.Exists(assembly), assembly);

        string writableFixture = Support.TestPath(
            "io", "src/test/resources/org/apache/pdfbox/io/RandomAccessReadFile1.txt");
        string testOutput = Support.TestPath(
            "pdfbox", "target/test-output/lifecycle/probe.txt");
        string separator =
            global::System.IO.Path.DirectorySeparatorChar.ToString();
        global::Xunit.Assert.Contains(
            separator + "WritableFixtures" + separator,
            writableFixture,
            global::System.StringComparison.Ordinal);
        global::Xunit.Assert.Contains(
            separator + "TestOutput" + separator,
            testOutput,
            global::System.StringComparison.Ordinal);

        string lifecycleProbe = Support.ResetMutableTestArtifactsForContract();

        global::Xunit.Assert.False(global::System.IO.File.Exists(lifecycleProbe));
        GeneratedSuiteIntegrityTests.VerifyGovernedFixtures();
        global::Xunit.Assert.True(global::System.IO.File.Exists(assembly), assembly);
        string restoredFixture = Support.TestPath(
            "io", "src/test/resources/org/apache/pdfbox/io/RandomAccessReadFile1.txt");
        global::Xunit.Assert.True(
            global::System.IO.File.Exists(restoredFixture), restoredFixture);
    }

    [global::Xunit.Fact]
    public void PosixPermissionsUseTranslatedJavaContract()
    {
        var mappings = new[]
        {
            (global::System.IO.UnixFileMode.UserRead,
             global::DripSharp.Runtime.JavaUnixFileMode.UserRead),
            (global::System.IO.UnixFileMode.UserWrite,
             global::DripSharp.Runtime.JavaUnixFileMode.UserWrite),
            (global::System.IO.UnixFileMode.UserExecute,
             global::DripSharp.Runtime.JavaUnixFileMode.UserExecute),
            (global::System.IO.UnixFileMode.GroupRead,
             global::DripSharp.Runtime.JavaUnixFileMode.GroupRead),
            (global::System.IO.UnixFileMode.GroupWrite,
             global::DripSharp.Runtime.JavaUnixFileMode.GroupWrite),
            (global::System.IO.UnixFileMode.GroupExecute,
             global::DripSharp.Runtime.JavaUnixFileMode.GroupExecute),
            (global::System.IO.UnixFileMode.OtherRead,
             global::DripSharp.Runtime.JavaUnixFileMode.OtherRead),
            (global::System.IO.UnixFileMode.OtherWrite,
             global::DripSharp.Runtime.JavaUnixFileMode.OtherWrite),
            (global::System.IO.UnixFileMode.OtherExecute,
             global::DripSharp.Runtime.JavaUnixFileMode.OtherExecute)
        };

        foreach ((global::System.IO.UnixFileMode source,
                  global::DripSharp.Runtime.JavaUnixFileMode expected) in mappings)
        {
            global::System.Collections.Generic.ISet<
                global::DripSharp.Runtime.JavaUnixFileMode> actual =
                Support.ToJavaUnixFileModes(source);

            global::Xunit.Assert.Single(actual);
            global::Xunit.Assert.Contains(expected, actual);
        }
    }
}

public sealed class JavaRandom
{
    private const long Multiplier = 0x5deece66dL;
    private const long Addend = 0xbL;
    private const long Mask = (1L << 48) - 1;
    private long Seed;

    internal JavaRandom() : this(global::System.DateTime.UtcNow.Ticks)
    {
    }

    internal JavaRandom(long seed)
    {
        SetSeed(seed);
    }

    internal void SetSeed(long seed) => Seed = (seed ^ Multiplier) & Mask;

    private int Next(int bits)
    {
        Seed = (Seed * Multiplier + Addend) & Mask;
        return (int)(Seed >>> (48 - bits));
    }

    internal void NextBytes(sbyte[] destination)
    {
        global::System.ArgumentNullException.ThrowIfNull(destination);
        for (int index = 0; index < destination.Length;)
        {
            int value = NextInt();
            for (int remaining = global::System.Math.Min(
                     destination.Length - index, sizeof(int));
                 remaining-- > 0; value >>= 8)
            {
                destination[index++] = unchecked((sbyte)value);
            }
        }
    }

    internal int NextInt() => Next(32);

    internal int NextInt(int bound)
    {
        if (bound <= 0)
            throw new global::System.ArgumentException("bound must be positive");
        if ((bound & -bound) == bound)
            return (int)((bound * (long)Next(31)) >> 31);
        int bits;
        int value;
        do
        {
            bits = Next(31);
            value = bits % bound;
        }
        while (bits - value + (bound - 1) < 0);
        return value;
    }

    internal bool NextBoolean() => Next(1) != 0;

    internal float NextFloat() => Next(24) / ((float)(1 << 24));

    internal double NextDouble() =>
        (((long)Next(26) << 27) + Next(27)) / (double)(1L << 53);

    internal long NextLong() => ((long)Next(32) << 32) + Next(32);
}

internal sealed class JavaErrorStream : global::System.IO.Stream
{
    private readonly global::System.IO.Stream Output =
        global::System.Console.OpenStandardError();

    internal void WriteLine(object? value) =>
        global::System.Console.Error.WriteLine(value);

    internal void WriteLine(string? value) =>
        global::System.Console.Error.WriteLine(value);

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new global::System.NotSupportedException();
    public override long Position
    {
        get => throw new global::System.NotSupportedException();
        set => throw new global::System.NotSupportedException();
    }
    public override void Flush() => Output.Flush();
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new global::System.NotSupportedException();
    public override long Seek(long offset, global::System.IO.SeekOrigin origin) =>
        throw new global::System.NotSupportedException();
    public override void SetLength(long value) =>
        throw new global::System.NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) =>
        Output.Write(buffer, offset, count);
    protected override void Dispose(bool disposing)
    {
        // Java tests may close System.err through IOUtils; keep the process stream alive.
    }
}

internal sealed class JavaTestThread
{
    private readonly global::System.Action Runnable;
    private readonly global::System.Threading.Thread Thread;
    private global::System.Action<JavaTestThread, global::System.Exception>? Handler;

    internal JavaTestThread(global::System.Action runnable)
    {
        Runnable = runnable;
        Thread = new global::System.Threading.Thread(Run);
    }

    internal JavaTestThread(global::System.Action runnable, string name) : this(runnable)
    {
        Thread.Name = name;
    }

    internal void SetUncaughtExceptionHandler(
        global::System.Action<JavaTestThread, global::System.Exception> handler) =>
        Handler = handler;

    internal void Start() => Thread.Start();

    private void Run()
    {
        try
        {
            Runnable();
        }
        catch (global::System.Exception error)
        {
            Handler?.Invoke(this, error);
        }
    }
}

internal sealed class JavaLocaleBuilder
{
    private string LanguageTag = global::System.Globalization.CultureInfo.InvariantCulture.Name;

    internal JavaLocaleBuilder SetLanguageTag(string languageTag)
    {
        LanguageTag = languageTag;
        return this;
    }

    internal global::System.Globalization.CultureInfo Build()
    {
        string baseTag = LanguageTag.Split("-u-", 2,
            global::System.StringSplitOptions.None)[0];
        return global::System.Globalization.CultureInfo.GetCultureInfo(baseTag);
    }
}

internal sealed class JavaDirectColorModel : global::DripSharp.Runtime.JavaColorModel
{
    private readonly int[] Masks;

    internal JavaDirectColorModel(
        global::DripSharp.Runtime.JavaColorSpace colorSpace,
        int bits,
        int redMask,
        int greenMask,
        int blueMask,
        int alphaMask,
        bool isAlphaPremultiplied,
        int dataType)
        : base(
            colorSpace,
            alphaMask != 0,
            dataType,
            alphaMask == 0
                ? new[] { redMask, greenMask, blueMask }
                : new[] { redMask, greenMask, blueMask, alphaMask },
            true)
    {
        _ = bits;
        _ = isAlphaPremultiplied;
        Masks = alphaMask == 0
            ? new[] { redMask, greenMask, blueMask }
            : new[] { redMask, greenMask, blueMask, alphaMask };
    }

    internal int[] GetMasks() => (int[])Masks.Clone();
}

public static class JavaTestXPathConstants
{
    public static readonly global::System.Xml.XmlQualifiedName NODE = new("NODE");
    public static readonly global::System.Xml.XmlQualifiedName NODESET = new("NODESET");
}

public sealed class JavaTestXPathFactory
{
    public static readonly JavaTestXPathFactory Instance = new();

    public JavaTestXPath NewXPath() => new();
}

public sealed class JavaTestXPath
{
    public string Evaluate(string expression, object context)
    {
        global::System.ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as global::System.Xml.XmlNode ??
            throw new global::System.ArgumentException(
                "XPath context must be an XML node.", nameof(context));
        return node.SelectSingleNode(expression)?.InnerText ?? string.Empty;
    }

    public object? Evaluate(
        string expression,
        object context,
        global::System.Xml.XmlQualifiedName returnType)
    {
        global::System.ArgumentException.ThrowIfNullOrEmpty(expression);
        var node = context as global::System.Xml.XmlNode ??
            throw new global::System.ArgumentException(
                "XPath context must be an XML node.", nameof(context));
        if (returnType == JavaTestXPathConstants.NODESET)
            return node.SelectNodes(expression);
        if (returnType == JavaTestXPathConstants.NODE)
            return node.SelectSingleNode(expression);
        return Evaluate(expression, context);
    }
}

internal static class JavaDiffUtils
{
    internal static JavaPatch<T> Diff<T>(
        global::System.Collections.Generic.IList<T> original,
        global::System.Collections.Generic.IList<T> revised)
    {
        var patch = new JavaPatch<T>();
        if (!original.SequenceEqual(revised))
        {
            patch.AddDelta(new JavaChangeDelta<T>(
                new JavaChunk<T>(original),
                new JavaChunk<T>(revised)));
        }
        return patch;
    }
}

internal sealed class JavaPatch<T>
{
    private readonly global::System.Collections.Generic.List<JavaDelta<T>> Deltas = new();

    internal void AddDelta(JavaDelta<T> delta) => Deltas.Add(delta);

    internal global::System.Collections.Generic.IList<JavaDelta<T>> GetDeltas() =>
        Deltas;
}

internal abstract class JavaDelta<T>
{
    private readonly JavaChunk<T> Original;
    private readonly JavaChunk<T> Revised;

    protected JavaDelta(JavaChunk<T> original, JavaChunk<T> revised)
    {
        Original = original;
        Revised = revised;
    }

    internal JavaChunk<T> GetOriginal() => Original;

    internal JavaChunk<T> GetRevised() => Revised;

    public override string ToString() => $"{Original} -> {Revised}";
}

internal interface JavaChangeDelta
{
}

internal sealed class JavaChangeDelta<T> : JavaDelta<T>, JavaChangeDelta
{
    internal JavaChangeDelta(JavaChunk<T> original, JavaChunk<T> revised)
        : base(original, revised)
    {
    }
}

internal interface JavaDeleteDelta
{
}

internal sealed class JavaDeleteDelta<T> : JavaDelta<T>, JavaDeleteDelta
{
    internal JavaDeleteDelta(JavaChunk<T> original, JavaChunk<T> revised)
        : base(original, revised)
    {
    }
}

internal interface JavaInsertDelta
{
}

internal sealed class JavaInsertDelta<T> : JavaDelta<T>, JavaInsertDelta
{
    internal JavaInsertDelta(JavaChunk<T> original, JavaChunk<T> revised)
        : base(original, revised)
    {
    }
}

internal sealed class JavaChunk<T>
{
    private readonly global::System.Collections.Generic.IList<T> Lines;

    internal JavaChunk(global::System.Collections.Generic.IList<T> lines) =>
        Lines = new global::System.Collections.Generic.List<T>(lines);

    public override string ToString() =>
        $"[{string.Join(", ", Lines)}]";
}
