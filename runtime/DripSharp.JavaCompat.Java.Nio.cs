// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Nio

internal static class JavaStandardCharsets
{
    internal static readonly Encoding UTF8 = new UTF8Encoding(false);
    // Java UTF-16 consumes an optional BOM and defaults to big-endian.
    // Keep a distinct instance so JavaCompat can retain that contract while
    // UTF-16BE remains a BOM-agnostic fixed-endian charset.
    internal static readonly Encoding UTF16 = new UnicodeEncoding(true, true);
    internal static readonly Encoding UTF16BE = Encoding.BigEndianUnicode;
    internal static readonly Encoding UTF16LE = Encoding.Unicode;
    internal static readonly Encoding USASCII = Encoding.ASCII;
    internal static readonly Encoding ISO88591 = Encoding.Latin1;
}

// java.nio.file.NoSuchFileException carries the missing path as its message.
// System.IO.FileNotFoundException instead decorates Message and therefore
// changes the evaluator diagnostic even when given the same path.
internal sealed class NoSuchFileException : IOException
{
    internal NoSuchFileException(string path) : base(path) { }
    internal NoSuchFileException(string path, Exception cause) : base(path, cause) { }
}
#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaByteBuffer : IDisposable
{
    private readonly sbyte[]? bytes;
    private readonly MemoryMappedFile? mappedFile;
    private readonly MemoryMappedViewAccessor? mappedView;
    private readonly bool ownsMapping;
    private readonly bool direct;
    private readonly int capacity;
    private int cursor;
    private int upperBound;
    private int markedCursor = -1;
    private bool disposed;

    private JavaByteBuffer(sbyte[] bytes, bool direct = false)
    {
        this.bytes = bytes;
        this.direct = direct;
        capacity = bytes.Length;
        upperBound = capacity;
    }

    private JavaByteBuffer(
        MemoryMappedFile mappedFile,
        MemoryMappedViewAccessor mappedView,
        int capacity,
        bool ownsMapping)
    {
        this.mappedFile = mappedFile;
        this.mappedView = mappedView;
        this.capacity = capacity;
        this.ownsMapping = ownsMapping;
        direct = true;
        upperBound = capacity;
    }

    internal static JavaByteBuffer Direct(sbyte[] bytes) => new(bytes, direct: true);
    internal static JavaByteBuffer Direct(
        MemoryMappedFile mappedFile,
        MemoryMappedViewAccessor mappedView,
        int capacity) => new(mappedFile, mappedView, capacity, ownsMapping: true);
    public static JavaByteBuffer allocate(int capacity) =>
        capacity < 0
            ? throw new ArgumentOutOfRangeException(nameof(capacity))
            : new JavaByteBuffer(new sbyte[capacity]);
    public static JavaByteBuffer wrap(sbyte[] bytes) =>
        new(bytes ?? throw new ArgumentNullException(nameof(bytes)));
    public sbyte[] array()
    {
        ThrowIfDisposed();
        if (direct || bytes is null)
            throw new NotSupportedException("A direct Java byte buffer has no accessible array.");
        return bytes;
    }
    public JavaByteBuffer clear()
    {
        ThrowIfDisposed();
        cursor = 0;
        upperBound = capacity;
        return this;
    }
    public JavaByteBuffer duplicate()
    {
        ThrowIfDisposed();
        var duplicate = mappedView is null
            ? new JavaByteBuffer(bytes!, direct)
            : new JavaByteBuffer(mappedFile!, mappedView, capacity, ownsMapping: false);
        duplicate.cursor = cursor;
        duplicate.upperBound = upperBound;
        return duplicate;
    }
    public sbyte get()
    {
        ThrowIfDisposed();
        if (cursor >= upperBound) throw new EndOfStreamException();
        return ReadByte(cursor++);
    }
    public sbyte get(int index)
    {
        ThrowIfDisposed();
        if ((uint)index >= (uint)upperBound) throw new ArgumentOutOfRangeException(nameof(index));
        return ReadByte(index);
    }
    public int getInt()
    {
        ThrowIfDisposed();
        if (4 > upperBound - cursor) throw new EndOfStreamException();
        uint value = 0;
        for (var index = 0; index < 4; index++)
            value = (value << 8) | unchecked((byte)get());
        return unchecked((int)value);
    }
    public JavaByteBuffer get(sbyte[] destination, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(destination);
        if (offset < 0 || length < 0 || offset + length > destination.Length)
            throw new ArgumentOutOfRangeException();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        if (mappedView is null)
        {
            Array.Copy(bytes!, cursor, destination, offset, length);
        }
        else
        {
            var unsigned = new byte[length];
            var read = mappedView.ReadArray(cursor, unsigned, 0, length);
            if (read != length) throw new EndOfStreamException();
            Buffer.BlockCopy(unsigned, 0, destination, offset, length);
        }
        cursor += length;
        return this;
    }
    public JavaByteBuffer get(sbyte[] destination) =>
        get(destination, 0, destination.Length);
    public JavaByteBuffer mark()
    {
        ThrowIfDisposed();
        markedCursor = cursor;
        return this;
    }
    public JavaByteBuffer reset()
    {
        ThrowIfDisposed();
        if (markedCursor < 0) throw new InvalidOperationException("ByteBuffer mark is not set.");
        cursor = markedCursor;
        return this;
    }
    public bool isDirect()
    {
        ThrowIfDisposed();
        return direct;
    }
    public int limit()
    {
        ThrowIfDisposed();
        return upperBound;
    }
    public JavaByteBuffer limit(int value)
    {
        ThrowIfDisposed();
        if (value < 0 || value > capacity) throw new ArgumentOutOfRangeException(nameof(value));
        upperBound = value;
        if (cursor > upperBound) cursor = upperBound;
        return this;
    }
    public int position()
    {
        ThrowIfDisposed();
        return cursor;
    }
    public JavaByteBuffer position(int value)
    {
        ThrowIfDisposed();
        if (value < 0 || value > upperBound) throw new ArgumentOutOfRangeException(nameof(value));
        cursor = value;
        return this;
    }
    public JavaByteBuffer put(sbyte value)
    {
        ThrowIfDisposed();
        if (cursor >= upperBound) throw new EndOfStreamException();
        if (mappedView is not null)
            throw new NotSupportedException("A read-only mapped Java byte buffer cannot be written.");
        bytes![cursor++] = value;
        return this;
    }
    public JavaByteBuffer put(sbyte[] source) => put(source, 0, source.Length);
    public JavaByteBuffer put(sbyte[] source, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        if (offset < 0 || length < 0 || offset + length > source.Length)
            throw new ArgumentOutOfRangeException();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        if (mappedView is not null)
            throw new NotSupportedException("A read-only mapped Java byte buffer cannot be written.");
        Array.Copy(source, offset, bytes!, cursor, length);
        cursor += length;
        return this;
    }
    public JavaByteBuffer putLong(long value)
    {
        ThrowIfDisposed();
        if (8 > upperBound - cursor) throw new EndOfStreamException();
        for (var shift = 56; shift >= 0; shift -= 8)
            put(unchecked((sbyte)(value >> shift)));
        return this;
    }
    public JavaByteBuffer rewind()
    {
        ThrowIfDisposed();
        cursor = 0;
        return this;
    }
    internal int Remaining
    {
        get
        {
            ThrowIfDisposed();
            return upperBound - cursor;
        }
    }
    internal sbyte[] ReadRemaining(int count)
    {
        count = Math.Min(count, Remaining);
        var result = new sbyte[count];
        get(result, 0, count);
        return result;
    }
    public override bool Equals(object? value)
    {
        if (ReferenceEquals(this, value)) return true;
        if (value is not JavaByteBuffer other) return false;
        ThrowIfDisposed();
        other.ThrowIfDisposed();
        if (Remaining != other.Remaining) return false;
        for (var offset = 0; offset < Remaining; offset++)
        {
            if (ReadByte(cursor + offset) !=
                other.ReadByte(other.cursor + offset))
                return false;
        }
        return true;
    }
    public override int GetHashCode()
    {
        ThrowIfDisposed();
        var result = 1;
        for (var index = upperBound - 1; index >= cursor; index--)
            result = unchecked(31 * result + ReadByte(index));
        return result;
    }
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        if (!ownsMapping) return;
        mappedView?.Dispose();
        mappedFile?.Dispose();
    }
    private sbyte ReadByte(int index) =>
        mappedView is null ? bytes![index] : unchecked((sbyte)mappedView.ReadByte(index));
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
enum JavaCodingErrorAction
{
    Report
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCharsetDecoder
{
    private readonly Encoding encoding;

    public JavaCharsetDecoder(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        this.encoding = (Encoding)encoding.Clone();
        this.encoding.DecoderFallback = DecoderFallback.ExceptionFallback;
    }

    public JavaCharsetDecoder ReportErrors(JavaCodingErrorAction action)
    {
        if (action != JavaCodingErrorAction.Report)
            throw new ArgumentOutOfRangeException(nameof(action));
        encoding.DecoderFallback = DecoderFallback.ExceptionFallback;
        return this;
    }

    public string Decode(JavaByteBuffer buffer)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        return encoding.GetString(JavaCompat.ToUnsignedBytes(
            buffer.ReadRemaining(buffer.Remaining)));
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaPath : IEquatable<JavaPath>
{
    internal string Value { get; }
    public JavaPath(string value) =>
        Value = value ?? throw new ArgumentNullException(nameof(value));
    public bool Equals(JavaPath? other) =>
        other is not null && string.Equals(Value, other.Value,
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    public override bool Equals(object? obj) => Equals(obj as JavaPath);
    public override int GetHashCode() =>
        (OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal)
            .GetHashCode(Value);
    public override string ToString() => Value;
    public static implicit operator string(JavaPath? path) => path?.Value!;
    public static implicit operator JavaPath(string path) => new(path);
}

internal enum JavaFileChannelMapMode { READ_ONLY }
internal enum JavaStandardOpenOption { READ }

internal sealed class JavaFileChannel : IDisposable
{
    private readonly FileStream stream;
    private bool disposed;

    private JavaFileChannel(string path) =>
        stream = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete);

    internal static JavaFileChannel open(string path, params object?[] _) => new(path);
    internal long size()
    {
        ThrowIfDisposed();
        return stream.Length;
    }
    internal JavaFileChannel position(long value)
    {
        ThrowIfDisposed();
        stream.Position = value;
        return this;
    }
    internal int read(JavaByteBuffer destination)
    {
        ThrowIfDisposed();
        var count = destination.Remaining;
        if (count == 0) return 0;
        var unsigned = new byte[count];
        var read = stream.Read(unsigned, 0, count);
        if (read == 0) return -1;
        var signed = new sbyte[read];
        Buffer.BlockCopy(unsigned, 0, signed, 0, read);
        destination.put(signed);
        return read;
    }
    internal void close() => Dispose();
    internal JavaByteBuffer map(JavaFileChannelMapMode mode, long offset, long size)
    {
        ThrowIfDisposed();
        if (mode != JavaFileChannelMapMode.READ_ONLY)
            throw new NotSupportedException($"Unsupported file-channel map mode {mode}.");
        if (offset < 0 || size < 0 || size > int.MaxValue ||
            offset > stream.Length || size > stream.Length - offset)
            throw new ArgumentOutOfRangeException();
        if (size == 0) return JavaByteBuffer.Direct(Array.Empty<sbyte>());
        var mappedFile = MemoryMappedFile.CreateFromFile(
            stream, null, 0, MemoryMappedFileAccess.Read,
            HandleInheritability.None, leaveOpen: true);
        var mappedView = mappedFile.CreateViewAccessor(
            offset, size, MemoryMappedFileAccess.Read);
        return JavaByteBuffer.Direct(mappedFile, mappedView, (int)size);
    }
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stream.Dispose();
    }
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

internal sealed class JavaFileSystem : IDisposable
{
    internal JavaFileSystemProvider Provider() => new();

    internal string GetPath(string first, params string[] more) =>
        Path.Combine(new[] { first }.Concat(more).ToArray());

    internal Predicate<string> GetPathMatcher(string _) => value => true;

    internal IEnumerable<DriveInfo> GetFileStores() => DriveInfo.GetDrives();

    internal object GetUserPrincipalLookupService() => new();

    internal JavaWatchService NewWatchService() => new();

    internal bool IsOpen() => true;

    internal bool IsReadOnly() => false;

    internal string GetSeparator() => Path.DirectorySeparatorChar.ToString();

    internal void Close() { }

    public void Dispose() => Close();

    internal ISet<string> SupportedFileAttributeViews() =>
        OperatingSystem.IsWindows()
            ? new HashSet<string>(StringComparer.Ordinal)
            : new HashSet<string>(new[] { "posix" }, StringComparer.Ordinal);
}

internal static class JavaFileSystems
{
    internal static JavaFileSystem GetDefault() => new();
    internal static JavaFileSystem GetFileSystem(Uri _) => new();
    internal static JavaFileSystem NewFileSystem(
        Uri _,
        IDictionary<string, object> __) =>
        new();
}

internal sealed class JavaFileSystemProvider
{
    internal static IEnumerable<JavaFileSystemProvider> InstalledProviders() =>
        new[] { new JavaFileSystemProvider() };

    internal string GetScheme() => "file";
}

internal sealed class JavaWatchService : IDisposable
{
    internal void Close() { }
    public void Dispose() => Close();
}

internal sealed record JavaUserPrincipal(string Name);
internal enum JavaAclEntryPermission
{
    APPEND_DATA, DELETE, DELETE_CHILD, EXECUTE, READ_ACL, READ_ATTRIBUTES,
    READ_DATA, READ_NAMED_ATTRS, SYNCHRONIZE, WRITE_ACL, WRITE_ATTRIBUTES,
    WRITE_DATA, WRITE_NAMED_ATTRS
}
internal enum JavaAclEntryType { ALLOW }
internal sealed record JavaAclEntry(
    JavaAclEntryType Type,
    JavaUserPrincipal Principal,
    ISet<JavaAclEntryPermission> Permissions)
{
    internal static JavaAclEntryBuilder newBuilder() => new();
}
internal sealed class JavaAclEntryBuilder
{
    private JavaAclEntryType type;
    private JavaUserPrincipal principal = new(Environment.UserName);
    private ISet<JavaAclEntryPermission> permissions = new HashSet<JavaAclEntryPermission>();
    internal JavaAclEntryBuilder setType(JavaAclEntryType value) { type = value; return this; }
    internal JavaAclEntryBuilder setPrincipal(JavaUserPrincipal value) { principal = value; return this; }
    internal JavaAclEntryBuilder setPermissions(ISet<JavaAclEntryPermission> value)
    {
        permissions = value;
        return this;
    }
    internal JavaAclEntry build() => new(type, principal, permissions);
}
internal sealed class JavaAclFileAttributeView
{
    internal JavaUserPrincipal getOwner() => new(Environment.UserName);
    internal void setAcl(IList<JavaAclEntry> _) { }
}
internal sealed record JavaFileAttribute<T>(T Value);

internal sealed class JavaPipe
{
    private const int DefaultCapacity = 1024;
    private readonly BlockingCollection<byte> bytes = new(DefaultCapacity);
    private int connected;
    private int readerClosed;

    internal void ConnectWriter()
    {
        if (Interlocked.Exchange(ref connected, 1) != 0)
            throw new IOException("Pipe is already connected.");
    }

    internal int Read(byte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        if (buffer.Length - offset < count) throw new ArgumentException("Invalid buffer range.");
        if (count == 0) return 0;
        try
        {
            if (!bytes.TryTake(out var first, Timeout.Infinite)) return 0;
            buffer[offset] = first;
            var read = 1;
            while (read < count && bytes.TryTake(out var next)) buffer[offset + read++] = next;
            return read;
        }
        catch (ThreadInterruptedException error)
        {
            throw new IOException("Interrupted while reading from a pipe.", error);
        }
    }

    internal void Write(byte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        ArgumentOutOfRangeException.ThrowIfNegative(offset);
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        if (buffer.Length - offset < count) throw new ArgumentException("Invalid buffer range.");
        try
        {
            for (var index = 0; index < count; index++)
            {
                if (Volatile.Read(ref readerClosed) != 0)
                    throw new IOException("Pipe reader is closed.");
                bytes.Add(buffer[offset + index]);
            }
        }
        catch (ThreadInterruptedException error)
        {
            throw new IOException("Interrupted while writing to a pipe.", error);
        }
        catch (InvalidOperationException error)
        {
            throw new IOException("Pipe is closed.", error);
        }
    }

    internal void CloseReader()
    {
        Interlocked.Exchange(ref readerClosed, 1);
        bytes.CompleteAdding();
    }

    internal void CloseWriter() => bytes.CompleteAdding();
}

internal sealed class JavaDirectoryStream<T> : IEnumerable<T>, IDisposable
{
    private readonly IEnumerable<T> entries;
    internal JavaDirectoryStream(string path) => entries = Directory.EnumerateFileSystemEntries(path).Select(value => (T)(object)value);
    public IEnumerator<T> GetEnumerator() => entries.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void Dispose() { }
    internal void Close() => Dispose();
}


internal static partial class JavaCompat
{
    internal static JavaStream<string> FindFiles(
        string basePath,
        int maxDepth,
        JavaBiPredicate<string, FileSystemInfo> predicate,
        params object[] ignoredOptions)
    {
        if (!Directory.Exists(basePath)) return new JavaStream<string>(Enumerable.Empty<string>());
        var root = Path.GetFullPath(basePath);
        return new JavaStream<string>(Directory.EnumerateFileSystemEntries(root, "*", SearchOption.AllDirectories)
            .Where(path => maxDepth == int.MaxValue ||
                Path.GetRelativePath(root, path).Count(character =>
                    character == Path.DirectorySeparatorChar || character == Path.AltDirectorySeparatorChar) < maxDepth)
            .Where(path => predicate(path, Directory.Exists(path)
                ? new DirectoryInfo(path)
                : new FileInfo(path))));
    }
    internal static JavaStream<JavaPath> FindFiles(
        JavaPath basePath,
        int maxDepth,
        JavaBiPredicate<JavaPath, FileSystemInfo> predicate,
        params object[] ignoredOptions) =>
        new(FindFiles(
                basePath.Value,
                maxDepth,
                (path, attributes) => predicate(new JavaPath(path), attributes),
                ignoredOptions)
            .Select(path => new JavaPath(path)));

    internal static bool IsRegularFile(FileSystemInfo attributes) => attributes is FileInfo;
    internal static bool IsRegularFile(string path) => File.Exists(path);

    internal static Encoding CharsetForName(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        return Encoding.GetEncoding(name);
    }

    internal static string CharsetName(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        return encoding.WebName;
    }
    internal static string CharBufferWrap(char[] value, int start, int length) =>
        new(value, start, length);

    internal static bool Exists(string path) => File.Exists(path) || Directory.Exists(path);
    internal static bool IsDirectory(string path) => Directory.Exists(path);
    internal static bool FileCanRead(FileInfo file)
    {
        try
        {
            if (Directory.Exists(file.FullName)) return true;
            using var stream = File.Open(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            return stream.CanRead;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
        catch (IOException)
        {
            return false;
        }
    }
    internal static bool FileIsHidden(FileInfo file) =>
        file.Name.StartsWith(".", StringComparison.Ordinal) ||
        (file.Exists && (file.Attributes & FileAttributes.Hidden) != 0);
    internal static FileInfo[] FileListFiles(FileInfo directory) =>
        Directory.Exists(directory.FullName)
            ? Directory.EnumerateFileSystemEntries(directory.FullName)
                .Select(path => new FileInfo(path))
                .ToArray()
            : Array.Empty<FileInfo>();
    internal static Uri FileToUri(FileInfo file) => new(file.FullName);
    internal static bool DeleteIfExists(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
            return true;
        }
        if (Directory.Exists(path))
        {
            Directory.Delete(path);
            return true;
        }
        return false;
    }
    internal static void CreateDirectories(string path) => Directory.CreateDirectory(path);
    internal static FileStream NewInputStream(string path, params object?[] _) => OpenFileRead(path);
    internal static string ReadString(string path) => File.ReadAllText(path, Encoding.UTF8);
    internal static string ReadString(string path, Encoding encoding)
    {
        if (Directory.Exists(path)) throw new IOException("Is a directory");
        return File.ReadAllText(path, encoding);
    }
    internal static string PathOf(string first, params string[] more)
    {
        // Path.of(first, more...) joins name elements even when a later string
        // begins with a platform separator. Path.Combine instead discards the
        // prefix for such strings, which can move translated cache paths out of
        // their intended root.
        var result = first;
        foreach (var value in more)
            result = Path.Join(result, value.TrimStart(Path.DirectorySeparatorChar,
                                                       Path.AltDirectorySeparatorChar));
        return result;
    }
    internal static string PathOfUri(Uri uri) =>
        uri.IsFile ? Uri.UnescapeDataString(uri.AbsolutePath) : uri.OriginalString;
    internal static bool PathIsAbsolute(string path) => Path.IsPathRooted(path);
    internal static string? PathRoot(string path) => Path.GetPathRoot(path);
    internal static string PathRelativize(string basis, string path) => Path.GetRelativePath(basis, path);
    internal static string PathResolve(string basis, string value) => Path.Combine(basis, value);
    internal static string PathResolveSibling(string basis, string value) =>
        Path.Combine(Path.GetDirectoryName(basis) ?? string.Empty, value);
    internal static string NormalizePath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        return Path.IsPathRooted(path)
            ? fullPath
            : Path.GetRelativePath(Environment.CurrentDirectory, fullPath);
    }
    internal static string RealPath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        var root = Path.GetPathRoot(fullPath) ??
            throw new IOException($"Path `{path}` has no filesystem root.");
        var current = root;
        var remainder = fullPath[root.Length..];
        foreach (var segment in remainder.Split(
                     new[] { Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar },
                     StringSplitOptions.RemoveEmptyEntries))
        {
            current = Path.Combine(current, segment);
            FileSystemInfo info = Directory.Exists(current)
                ? new DirectoryInfo(current)
                : new FileInfo(current);
            if (!info.Exists)
                throw new NoSuchFileException(path);
            if ((info.Attributes & FileAttributes.ReparsePoint) == 0) continue;
            var target = info.ResolveLinkTarget(returnFinalTarget: true) ??
                throw new IOException($"Cannot resolve symbolic link `{current}`.");
            current = Path.GetFullPath(target.FullName);
        }
        return Path.GetFullPath(current);
    }
    internal static bool PathStartsWith(string path, string basis)
    {
        var candidate = Path.GetFullPath(path);
        var root = Path.GetFullPath(basis);
        var comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (string.Equals(candidate, root, comparison)) return true;
        var relative = Path.GetRelativePath(root, candidate);
        return !Path.IsPathRooted(relative) &&
               !string.Equals(relative, "..", comparison) &&
               !relative.StartsWith(".." + Path.DirectorySeparatorChar, comparison) &&
               !relative.StartsWith(".." + Path.AltDirectorySeparatorChar, comparison);
    }
    internal static bool PathEndsWith(string path, string suffix)
    {
        var comparison = OperatingSystem.IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (Path.IsPathRooted(suffix))
            return string.Equals(Path.GetFullPath(path), Path.GetFullPath(suffix), comparison);
        var pathParts = path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(part => part.Length > 0).ToArray();
        var suffixParts = suffix.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(part => part.Length > 0).ToArray();
        if (suffixParts.Length > pathParts.Length) return false;
        for (var index = 1; index <= suffixParts.Length; index++)
            if (!string.Equals(pathParts[^index], suffixParts[^index], comparison)) return false;
        return true;
    }
    internal static int PathNameCount(string path) => path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
        .Count(segment => !string.IsNullOrEmpty(segment));
    internal static string PathName(string path, int index) =>
        path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(segment => !string.IsNullOrEmpty(segment)).ElementAt(index);
    internal static sbyte[] ReadAllBytes(string path)
    {
        using var stream = OpenFileRead(path);
        return ReadAllBytes(stream);
    }
    private static FileStream OpenFileRead(string path)
    {
        if (Directory.Exists(path)) throw new IOException("Is a directory");
        try
        {
            return File.OpenRead(path);
        }
        catch (DirectoryNotFoundException error)
        {
            throw new FileNotFoundException(error.Message, path, error);
        }
    }
    internal static sbyte[] ReadAllBytes(Stream stream)
    {
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    }
    internal static int InputStreamAvailable(Stream stream) =>
        stream.CanSeek
            ? checked((int)Math.Min(int.MaxValue, Math.Max(0, stream.Length - stream.Position)))
            : 0;
    internal static sbyte[] ReadNBytes(Stream stream, int count)
    {
        var bytes = new byte[count];
        var offset = 0;
        while (offset < count)
        {
            var read = stream.Read(bytes, offset, count - offset);
            if (read == 0) break;
            offset += read;
        }
        return bytes.Take(offset).Select(value => unchecked((sbyte)value)).ToArray();
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes) =>
        new(bytes.Select(value => unchecked((byte)value)).ToArray());
    internal static StringBuilder StringBuilderAppendInvariant(
        StringBuilder builder,
        object value)
    {
        ArgumentNullException.ThrowIfNull(builder);
        return builder.Append(StringValueOf(value));
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        if (offset < 0 || length < 0 || offset > bytes.Length - length)
            throw new IndexOutOfRangeException();
        return new MemoryStream(
            bytes.Skip(offset).Take(length).Select(value => unchecked((byte)value)).ToArray());
    }
    internal static sbyte[] ToSignedBytes(MemoryStream stream) =>
        stream.ToArray().Select(value => unchecked((sbyte)value)).ToArray();
    internal static string WriteString(string path, object value, params object?[] _)
    {
        File.WriteAllText(path, StringValueOf(value));
        return path;
    }
    internal static string Move(string source, string destination, params object?[] _)
    {
        File.Move(source, destination, true);
        return destination;
    }
    internal static string Copy(string source, string destination, params object?[] _)
    {
        File.Copy(source, destination, true);
        return destination;
    }
    internal static string Copy(Stream source, string destination, params object?[] _)
    {
        using var output = File.Create(destination);
        source.CopyTo(output);
        return destination;
    }
    internal static long Copy(string source, Stream destination)
    {
        using var input = File.OpenRead(source);
        input.CopyTo(destination);
        return input.Length;
    }
    internal static FileStream NewOutputStream(string path, params object?[] _) => File.Create(path);
    internal static StreamWriter NewFileWriter(string path, Encoding encoding) => new(path, false, encoding);
    internal static StreamWriter NewFileWriter(FileInfo file, Encoding encoding) =>
        NewFileWriter(file.FullName, encoding);
    internal static JavaStream<string> Walk(string path, params object?[] _) =>
        new(Directory.EnumerateFileSystemEntries(path, "*", SearchOption.AllDirectories).Prepend(path));
    internal static JavaStream<string> walk(string path, params object?[] options) =>
        Walk(path, options);
    internal static JavaStream<JavaPath> walk(JavaPath path, params object?[] _) =>
        new(Directory.EnumerateFileSystemEntries(path.Value, "*", SearchOption.AllDirectories)
            .Prepend(path.Value)
            .Select(value => new JavaPath(value)));
    internal static bool PathIsRegularFile(string path) => File.Exists(path);
    internal static ICollection<object> ObjectCollection(IEnumerable<object> values) => values.ToList();
    internal static IDictionary<object, object> ObjectMap(IDictionary values)
    {
        var result = new Dictionary<object, object>();
        foreach (DictionaryEntry entry in values) result[entry.Key] = entry.Value!;
        return result;
    }
    internal static IDictionary<object, object> ObjectMap<K, V>(IDictionary<K, V> values)
        where K : notnull => values.ToDictionary(entry => (object)entry.Key, entry => (object?)entry.Value!);
    internal static TextWriter WriterAppend(TextWriter writer, object? value)
    {
        writer.Write(StringValueOf(value));
        return writer;
    }
    internal static void WriterAppend(TextWriter writer, object? value, int start, int end) =>
        writer.Write(StringValueOf(value).AsSpan(start, end - start));
    internal static void SetPosixFilePermissions(string path, ISet<UnixFileMode> permissions)
    {
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, permissions.Aggregate((UnixFileMode)0, (mode, permission) => mode | permission));
    }
    internal static void setPosixFilePermissions(string path, ISet<UnixFileMode> permissions) =>
        SetPosixFilePermissions(path, permissions);
    internal static ISet<UnixFileMode> fromString(string permissions)
    {
        if (permissions.Length != 9)
            throw new ArgumentException("POSIX permissions must contain exactly nine characters.",
                nameof(permissions));
        var result = new HashSet<UnixFileMode>();
        var modes = new[]
        {
            UnixFileMode.UserRead, UnixFileMode.UserWrite, UnixFileMode.UserExecute,
            UnixFileMode.GroupRead, UnixFileMode.GroupWrite, UnixFileMode.GroupExecute,
            UnixFileMode.OtherRead, UnixFileMode.OtherWrite, UnixFileMode.OtherExecute
        };
        for (var index = 0; index < permissions.Length; index++)
        {
            var expected = (index % 3) switch { 0 => 'r', 1 => 'w', _ => 'x' };
            if (permissions[index] == expected) result.Add(modes[index]);
            else if (permissions[index] != '-')
                throw new ArgumentException($"Invalid POSIX permission `{permissions[index]}`.",
                    nameof(permissions));
        }
        return result;
    }
    internal static JavaFileAttribute<ISet<UnixFileMode>> asFileAttribute(
        ISet<UnixFileMode> permissions) => new(permissions);
    internal static string createTempDirectory(
        string prefix, params JavaFileAttribute<ISet<UnixFileMode>>[] attributes)
    {
        var path = Path.Combine(Path.GetTempPath(), prefix + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static string createTempFile(
        string prefix, string suffix,
        params JavaFileAttribute<ISet<UnixFileMode>>[] attributes) =>
        createTempFile(Path.GetTempPath(), prefix, suffix, attributes);
    internal static string createTempFile(
        string directory, string prefix, string suffix,
        params JavaFileAttribute<ISet<UnixFileMode>>[] attributes)
    {
        var path = Path.Combine(directory, prefix + Guid.NewGuid().ToString("N") + suffix);
        using (File.Create(path)) { }
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static JavaAclFileAttributeView? getFileAttributeView(
        string _, Type __, params object?[] ___) =>
        OperatingSystem.IsWindows() ? new JavaAclFileAttributeView() : null;
    internal static bool FileDelete(FileInfo file)
    {
        try
        {
            if (Directory.Exists(file.FullName)) Directory.Delete(file.FullName);
            else if (File.Exists(file.FullName)) File.Delete(file.FullName);
            return true;
        }
        catch
        {
            return false;
        }
    }
    internal static bool FileExists(FileInfo file) =>
        File.Exists(file.FullName) || Directory.Exists(file.FullName);
    internal static bool FileIsFile(FileInfo file) => File.Exists(file.FullName);
    internal static bool FileIsDirectory(FileInfo file) => Directory.Exists(file.FullName);
    internal static bool SetFileReadable(FileInfo _, bool __, bool ___) => true;
    internal static bool SetFileWritable(FileInfo _, bool __, bool ___) => true;
    internal static bool SetFileExecutable(FileInfo _, bool __, bool ___) => true;
    internal static string CreateTempFile(string prefix, string suffix, params object?[] _)
    {
        var path = Path.Combine(Path.GetTempPath(), prefix + Guid.NewGuid().ToString("N") + suffix);
        using (File.Create(path)) { }
        return path;
    }
    internal static bool IsSymbolicLink(string path) =>
        (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
    internal static JavaDirectoryStream<string> NewDirectoryStream(string path) => new(path);
    internal static JavaDirectoryStream<string> List(string path) => new(path);
    internal static bool SequenceEqual<T>(IEnumerable<T> left, IEnumerable<T> right) => left.SequenceEqual(right);
    internal static string IterableString<T>(string label, IEnumerable<T> values) =>
        label + "(" + string.Join(", ", values.Select(value => StringValueOf(value))) + ")";
    internal static Uri PathToUri(string path)
    {
        var pathUri = new Uri(Path.GetFullPath(path));
        return new Uri(pathUri.AbsoluteUri);
    }
}
