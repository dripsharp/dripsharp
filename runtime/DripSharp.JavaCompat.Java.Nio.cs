// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

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
using Microsoft.Win32.SafeHandles;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Nio

internal static class JavaStandardCharsets
{
    private static Encoding WithJavaDecoderReplacement(Encoding encoding)
    {
        var result = (Encoding)encoding.Clone();
        result.DecoderFallback = new DecoderReplacementFallback("\ufffd");
        return result;
    }

    internal static readonly Encoding UTF8 = WithJavaDecoderReplacement(new UTF8Encoding(false));
    // Java UTF-16 consumes an optional BOM and defaults to big-endian.
    // Keep a distinct instance so JavaCompat can retain that contract while
    // UTF-16BE remains a BOM-agnostic fixed-endian charset.
    internal static readonly Encoding UTF16 = WithJavaDecoderReplacement(new UnicodeEncoding(true, true));
    internal static readonly Encoding UTF16BE = WithJavaDecoderReplacement(Encoding.BigEndianUnicode);
    internal static readonly Encoding UTF16LE = WithJavaDecoderReplacement(Encoding.Unicode);
    internal static readonly Encoding USASCII = WithJavaDecoderReplacement(Encoding.ASCII);
    internal static readonly Encoding ISO88591 = WithJavaDecoderReplacement(Encoding.GetEncoding(28591));
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
enum JavaByteOrder
{
    BigEndian,
    LittleEndian
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
    private readonly bool readOnly;
    private readonly int storageOffset;
    private readonly int bufferCapacity;
    private int cursor;
    private int upperBound;
    private int markedCursor = -1;
    private JavaByteOrder byteOrder = JavaByteOrder.BigEndian;
    private bool disposed;

    private JavaByteBuffer(
        sbyte[] bytes,
        bool direct = false,
        int storageOffset = 0,
        int? viewCapacity = null,
        bool readOnly = false)
    {
        this.bytes = bytes;
        this.direct = direct;
        this.readOnly = readOnly;
        this.storageOffset = storageOffset;
        bufferCapacity = viewCapacity ?? bytes.Length;
        upperBound = bufferCapacity;
    }

    private JavaByteBuffer(
        MemoryMappedFile mappedFile,
        MemoryMappedViewAccessor mappedView,
        int capacity,
        bool ownsMapping,
        int storageOffset = 0)
    {
        this.mappedFile = mappedFile;
        this.mappedView = mappedView;
        bufferCapacity = capacity;
        this.ownsMapping = ownsMapping;
        this.storageOffset = storageOffset;
        direct = true;
        readOnly = true;
        upperBound = bufferCapacity;
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
        if (direct || readOnly || bytes is null)
            throw new NotSupportedException("This Java byte buffer has no accessible array.");
        return bytes;
    }
    public int capacity()
    {
        ThrowIfDisposed();
        return bufferCapacity;
    }
    public JavaByteBuffer clear()
    {
        ThrowIfDisposed();
        cursor = 0;
        upperBound = bufferCapacity;
        markedCursor = -1;
        return this;
    }
    public JavaByteBuffer duplicate()
    {
        ThrowIfDisposed();
        var duplicate = mappedView is null
            ? new JavaByteBuffer(bytes!, direct, storageOffset, bufferCapacity, readOnly)
            : new JavaByteBuffer(mappedFile!, mappedView, bufferCapacity, ownsMapping: false, storageOffset);
        duplicate.cursor = cursor;
        duplicate.upperBound = upperBound;
        duplicate.markedCursor = markedCursor;
        return duplicate;
    }
    public JavaByteBuffer slice()
    {
        ThrowIfDisposed();
        var remaining = upperBound - cursor;
        return mappedView is null
            ? new JavaByteBuffer(bytes!, direct, storageOffset + cursor, remaining, readOnly)
            : new JavaByteBuffer(mappedFile!, mappedView, remaining, ownsMapping: false,
                storageOffset + cursor);
    }
    public JavaByteBuffer asReadOnlyBuffer()
    {
        ThrowIfDisposed();
        var result = mappedView is null
            ? new JavaByteBuffer(bytes!, direct, storageOffset, bufferCapacity, readOnly: true)
            : new JavaByteBuffer(mappedFile!, mappedView, bufferCapacity, ownsMapping: false, storageOffset);
        result.cursor = cursor;
        result.upperBound = upperBound;
        result.markedCursor = markedCursor;
        return result;
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
        var result = unchecked((int)ReadRelative(4));
        return result;
    }
    public int getInt(int index) => unchecked((int)ReadAbsolute(index, 4));
    public short getShort() => unchecked((short)ReadRelative(2));
    public short getShort(int index) => unchecked((short)ReadAbsolute(index, 2));
    public long getLong() => unchecked((long)ReadRelative(8));
    public long getLong(int index) => unchecked((long)ReadAbsolute(index, 8));
    public JavaByteBuffer get(sbyte[] destination, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(destination);
        if (offset < 0 || length < 0 || offset > destination.Length - length)
            throw new ArgumentOutOfRangeException();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        if (mappedView is null)
        {
            Array.Copy(bytes!, storageOffset + cursor, destination, offset, length);
        }
        else
        {
            var unsigned = new byte[length];
            var read = mappedView.ReadArray(storageOffset + cursor, unsigned, 0, length);
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
        if (value < 0 || value > bufferCapacity) throw new ArgumentOutOfRangeException(nameof(value));
        upperBound = value;
        if (cursor > upperBound) cursor = upperBound;
        if (markedCursor > upperBound) markedCursor = -1;
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
        if (markedCursor > cursor) markedCursor = -1;
        return this;
    }
    public JavaByteBuffer flip()
    {
        ThrowIfDisposed();
        upperBound = cursor;
        cursor = 0;
        markedCursor = -1;
        return this;
    }
    public int remaining()
    {
        ThrowIfDisposed();
        return upperBound - cursor;
    }
    public bool isReadOnly()
    {
        ThrowIfDisposed();
        return readOnly;
    }
    public JavaByteOrder order()
    {
        ThrowIfDisposed();
        return byteOrder;
    }
    public JavaByteBuffer order(JavaByteOrder value)
    {
        ThrowIfDisposed();
        byteOrder = value;
        return this;
    }
    public JavaByteBuffer put(sbyte value)
    {
        ThrowIfDisposed();
        ThrowIfReadOnly();
        if (cursor >= upperBound) throw new EndOfStreamException();
        WriteByte(cursor++, value);
        return this;
    }
    public JavaByteBuffer put(int index, sbyte value)
    {
        ThrowIfDisposed();
        ThrowIfReadOnly();
        CheckAbsolute(index, 1);
        WriteByte(index, value);
        return this;
    }
    public JavaByteBuffer put(sbyte[] source) => put(source, 0, source.Length);
    public JavaByteBuffer put(sbyte[] source, int offset, int length)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        if (offset < 0 || length < 0 || offset > source.Length - length)
            throw new ArgumentOutOfRangeException();
        ThrowIfReadOnly();
        if (length > upperBound - cursor) throw new EndOfStreamException();
        Array.Copy(source, offset, bytes!, storageOffset + cursor, length);
        cursor += length;
        return this;
    }
    // FileChannel.read(ByteBuffer): one read of up to remaining() bytes from
    // the channel's FileStream straight into the backing array. Like
    // IOUtil.read, a read-only destination is an IllegalArgumentException
    // even when it has nothing remaining.
    internal int ReadFrom(FileStream stream)
    {
        ThrowIfDisposed();
        if (readOnly) throw new ArgumentException("Read-only buffer");
        var count = upperBound - cursor;
        if (count == 0) return 0;
        var read = stream.Read(JavaCompat.AsUnsignedBytes(bytes!), storageOffset + cursor, count);
        if (read == 0) return -1;
        cursor += read;
        return read;
    }
    public JavaByteBuffer putShort(short value)
    {
        WriteRelative(unchecked((ushort)value), 2);
        return this;
    }
    public JavaByteBuffer putShort(int index, short value)
    {
        WriteAbsolute(index, unchecked((ushort)value), 2);
        return this;
    }
    public JavaByteBuffer putInt(int value)
    {
        WriteRelative(unchecked((uint)value), 4);
        return this;
    }
    public JavaByteBuffer putInt(int index, int value)
    {
        WriteAbsolute(index, unchecked((uint)value), 4);
        return this;
    }
    public JavaByteBuffer putLong(long value)
    {
        WriteRelative(unchecked((ulong)value), 8);
        return this;
    }
    public JavaByteBuffer putLong(int index, long value)
    {
        WriteAbsolute(index, unchecked((ulong)value), 8);
        return this;
    }
    public JavaByteBuffer rewind()
    {
        ThrowIfDisposed();
        cursor = 0;
        markedCursor = -1;
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
    private ulong ReadRelative(int width)
    {
        ThrowIfDisposed();
        if (width > upperBound - cursor) throw new EndOfStreamException();
        var result = ReadValue(cursor, width);
        cursor += width;
        return result;
    }
    private ulong ReadAbsolute(int index, int width)
    {
        ThrowIfDisposed();
        CheckAbsolute(index, width);
        return ReadValue(index, width);
    }
    private ulong ReadValue(int index, int width)
    {
        ulong result = 0;
        if (byteOrder == JavaByteOrder.BigEndian)
            for (var offset = 0; offset < width; offset++)
                result = (result << 8) | unchecked((byte)ReadByte(index + offset));
        else
            for (var offset = width - 1; offset >= 0; offset--)
                result = (result << 8) | unchecked((byte)ReadByte(index + offset));
        return result;
    }
    private void WriteRelative(ulong value, int width)
    {
        ThrowIfDisposed();
        ThrowIfReadOnly();
        if (width > upperBound - cursor) throw new EndOfStreamException();
        WriteValue(cursor, value, width);
        cursor += width;
    }
    private void WriteAbsolute(int index, ulong value, int width)
    {
        ThrowIfDisposed();
        ThrowIfReadOnly();
        CheckAbsolute(index, width);
        WriteValue(index, value, width);
    }
    private void WriteValue(int index, ulong value, int width)
    {
        for (var offset = 0; offset < width; offset++)
        {
            var shift = byteOrder == JavaByteOrder.BigEndian
                ? 8 * (width - 1 - offset)
                : 8 * offset;
            WriteByte(index + offset, unchecked((sbyte)(value >> shift)));
        }
    }
    private void CheckAbsolute(int index, int width)
    {
        if (index < 0 || width > upperBound - index)
            throw new ArgumentOutOfRangeException(nameof(index));
    }
    private sbyte ReadByte(int index) =>
        mappedView is null
            ? bytes![storageOffset + index]
            : unchecked((sbyte)mappedView.ReadByte(storageOffset + index));
    private void WriteByte(int index, sbyte value)
    {
        if (mappedView is null) bytes![storageOffset + index] = value;
        else mappedView.Write(storageOffset + index, unchecked((byte)value));
    }
    private void ThrowIfReadOnly()
    {
        if (readOnly) throw new NotSupportedException("A read-only Java byte buffer cannot be written.");
    }
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
enum JavaCodingErrorAction
{
    Report,
    Replace
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCharsetDecoder
{
    private readonly Encoding encoding;
    private readonly bool javaUtf16;

    public JavaCharsetDecoder(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        javaUtf16 = ReferenceEquals(encoding, JavaStandardCharsets.UTF16);
        this.encoding = (Encoding)encoding.Clone();
        this.encoding.DecoderFallback = DecoderFallback.ExceptionFallback;
    }

    public JavaCharsetDecoder ReportErrors(JavaCodingErrorAction action)
    {
        encoding.DecoderFallback = action switch
        {
            JavaCodingErrorAction.Report => DecoderFallback.ExceptionFallback,
            JavaCodingErrorAction.Replace => new DecoderReplacementFallback("\ufffd"),
            _ => throw new ArgumentOutOfRangeException(nameof(action))
        };
        return this;
    }

    public string Decode(JavaByteBuffer buffer)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        var start = buffer.position();
        using var view = buffer.duplicate();
        var bytes = JavaCompat.ToUnsignedBytes(view.ReadRemaining(view.Remaining));
        try
        {
            var result = Decode(bytes);
            buffer.position(start + bytes.Length);
            return result;
        }
        catch (DecoderFallbackException error)
        {
            var bom = javaUtf16 && bytes.Length >= 2 &&
                      ((bytes[0] == 0xfe && bytes[1] == 0xff) ||
                       (bytes[0] == 0xff && bytes[1] == 0xfe))
                ? 2 : 0;
            buffer.position(start + Math.Max(0, Math.Min(bom + error.Index, bytes.Length)));
            throw;
        }
    }

    private string Decode(byte[] bytes)
    {
        if (!javaUtf16) return encoding.GetString(bytes);
        var offset = 0;
        Encoding selected = Encoding.BigEndianUnicode;
        if (bytes.Length >= 2 && bytes[0] == 0xfe && bytes[1] == 0xff)
        {
            offset = 2;
        }
        else if (bytes.Length >= 2 && bytes[0] == 0xff && bytes[1] == 0xfe)
        {
            selected = Encoding.Unicode;
            offset = 2;
        }
        selected = (Encoding)selected.Clone();
        selected.DecoderFallback = encoding.DecoderFallback;
        return selected.GetString(bytes, offset, bytes.Length - offset);
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
            JavaCompat.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    public override bool Equals(object? obj) => Equals(obj as JavaPath);
    public override int GetHashCode() =>
        (JavaCompat.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal)
            .GetHashCode(Value);
    public override string ToString() => Value;
    public static implicit operator string(JavaPath? path) => path?.Value!;
    public static implicit operator JavaPath(string path) => new(path);
}

internal enum JavaFileChannelMapMode { READ_ONLY }
internal enum JavaStandardOpenOption { READ }

[Flags]
internal enum JavaUnixFileMode : uint
{
    OtherExecute = 0x001,
    OtherWrite = 0x002,
    OtherRead = 0x004,
    GroupExecute = 0x008,
    GroupWrite = 0x010,
    GroupRead = 0x020,
    UserExecute = 0x040,
    UserWrite = 0x080,
    UserRead = 0x100
}

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
        return destination.ReadFrom(stream);
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
        JavaCompat.IsWindows()
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
                RelativePath(root, path).Count(character =>
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
        if (name.Equals("UTF-8", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("UTF8", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.UTF8;
        if (name.Equals("UTF-16", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.UTF16;
        if (name.Equals("UTF-16BE", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.UTF16BE;
        if (name.Equals("UTF-16LE", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.UTF16LE;
        if (name.Equals("US-ASCII", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("ASCII", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.USASCII;
        if (name.Equals("ISO-8859-1", StringComparison.OrdinalIgnoreCase) ||
            name.Equals("ISO8859-1", StringComparison.OrdinalIgnoreCase))
            return JavaStandardCharsets.ISO88591;
        var encoding = (Encoding)Encoding.GetEncoding(name).Clone();
        encoding.DecoderFallback = new DecoderReplacementFallback("\ufffd");
        return encoding;
    }

    internal static string CharsetName(Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        return encoding.WebName;
    }
    internal static bool CharsetCanEncode(Encoding encoding, string value)
    {
        ArgumentNullException.ThrowIfNull(encoding);
        ArgumentNullException.ThrowIfNull(value);
        var strict = (Encoding)encoding.Clone();
        strict.EncoderFallback = EncoderFallback.ExceptionFallback;
        try
        {
            _ = strict.GetByteCount(value);
            return true;
        }
        catch (EncoderFallbackException)
        {
            return false;
        }
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
    internal static bool FileCreateNewFile(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        try
        {
            using var stream = new FileStream(
                file.FullName,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None);
            return true;
        }
        catch (IOException) when (File.Exists(file.FullName))
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
            result = Path.Combine(result, value.TrimStart(Path.DirectorySeparatorChar,
                                                          Path.AltDirectorySeparatorChar));
        return result;
    }
    internal static string PathOfUri(Uri uri) =>
        uri.IsFile ? Uri.UnescapeDataString(uri.AbsolutePath) : uri.OriginalString;
    internal static bool PathIsAbsolute(string path) => Path.IsPathRooted(path);
    internal static string? PathRoot(string path) => Path.GetPathRoot(path);
    internal static JavaPath? PathParent(string path)
    {
        var parent = Path.GetDirectoryName(path);
        return parent is null ? null : new JavaPath(parent);
    }
    internal static string PathRelativize(string basis, string path) => RelativePath(basis, path);
    internal static string PathResolve(string basis, string value) => Path.Combine(basis, value);
    internal static string PathResolveSibling(string basis, string value) =>
        Path.Combine(Path.GetDirectoryName(basis) ?? string.Empty, value);
    internal static string NormalizePath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        return Path.IsPathRooted(path)
            ? fullPath
            : RelativePath(Environment.CurrentDirectory, fullPath);
    }
    internal static string RealPath(string path)
    {
        var fullPath = Path.GetFullPath(path);
        if (!File.Exists(fullPath) && !Directory.Exists(fullPath))
            throw new NoSuchFileException(path);
        if (!IsWindows())
        {
            var pointer = RealPathNative(fullPath, IntPtr.Zero);
            if (pointer == IntPtr.Zero)
                throw new IOException(
                    $"realpath failed for `{path}` with native error {Marshal.GetLastWin32Error()}.");
            try
            {
                return Marshal.PtrToStringAnsi(pointer) ??
                    throw new IOException($"realpath returned no value for `{path}`.");
            }
            finally
            {
                FreeNative(pointer);
            }
        }
        using var handle = CreateFileNative(
            fullPath,
            0,
            FileShare.ReadWrite | FileShare.Delete,
            IntPtr.Zero,
            FileMode.Open,
            0x02000000,
            IntPtr.Zero);
        if (handle.IsInvalid)
            throw new IOException(
                $"CreateFile failed for `{path}` with native error {Marshal.GetLastWin32Error()}.");
        var capacity = 512;
        while (true)
        {
            var resolved = new StringBuilder(capacity);
            var length = GetFinalPathNameByHandleNative(handle, resolved, resolved.Capacity, 0);
            if (length == 0)
                throw new IOException(
                    $"GetFinalPathNameByHandle failed for `{path}` with native error {Marshal.GetLastWin32Error()}.");
            if (length < resolved.Capacity)
                return resolved.ToString().StartsWith("\\\\?\\", StringComparison.Ordinal)
                    ? resolved.ToString().Substring(4)
                    : resolved.ToString();
            capacity = checked((int)length + 1);
        }
    }
    internal static bool PathStartsWith(string path, string basis)
    {
        var candidate = Path.GetFullPath(path);
        var root = Path.GetFullPath(basis);
        var comparison = IsWindows()
            ? StringComparison.OrdinalIgnoreCase
            : StringComparison.Ordinal;
        if (string.Equals(candidate, root, comparison)) return true;
        var relative = RelativePath(root, candidate);
        return !Path.IsPathRooted(relative) &&
               !string.Equals(relative, "..", comparison) &&
               !relative.StartsWith(".." + Path.DirectorySeparatorChar, comparison) &&
               !relative.StartsWith(".." + Path.AltDirectorySeparatorChar, comparison);
    }
    internal static bool PathEndsWith(string path, string suffix)
    {
        var comparison = IsWindows()
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
        return ToSignedBytes(buffer);
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
        return ToSignedBytes(bytes, 0, offset);
    }
    private sealed class JavaByteArrayInputStream : MemoryStream
    {
        internal JavaByteArrayInputStream(byte[] bytes)
            : base(bytes, writable: false)
        {
        }

        internal int ReadJava(byte[] buffer, int offset, int count)
        {
            if (Position >= Length) return -1;
            return Read(buffer, offset, count);
        }

        protected override void Dispose(bool disposing)
        {
            // java.io.ByteArrayInputStream.close() has no effect. Reads,
            // available, skip, mark, and reset remain usable after close.
        }
    }
    internal static MemoryStream NewMemoryStream(sbyte[] bytes)
    {
        var stream = new JavaByteArrayInputStream(ToUnsignedBytes(bytes));
        InputStreamMark(stream, int.MaxValue);
        return stream;
    }
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
        var stream = new JavaByteArrayInputStream(ToUnsignedBytes(bytes, offset, length));
        InputStreamMark(stream, int.MaxValue);
        return stream;
    }
    internal static sbyte[] ToSignedBytes(MemoryStream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        // One copy straight out of the stream's buffer. An exposable stream
        // (ByteArrayOutputStream, default MemoryStreams) hands it over directly;
        // a non-exposable open stream passes it to WriteTo in one Write call.
        // Neither moves the position.
        if (stream.TryGetBuffer(out var contents))
            return ToSignedBytes(contents.Array!, contents.Offset, contents.Count);
        if (!stream.CanRead)
            return ToSignedBytes(stream.ToArray()); // closed: only ToArray still works
        var sink = new SignedByteSink(checked((int)stream.Length));
        stream.WriteTo(sink);
        var result = sink.Result;
        if (result.Length != sink.Length) Array.Resize(ref result, checked((int)sink.Length));
        return result;
    }
    private sealed class SignedByteSink : Stream
    {
        private int length;
        internal SignedByteSink(int capacity) => Result = new sbyte[capacity];
        internal sbyte[] Result { get; private set; }
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => length;
        public override long Position
        {
            get => length;
            set => throw new NotSupportedException();
        }
        public override void Write(byte[] buffer, int offset, int count)
        {
            if (Result.Length - length < count)
            {
                var grown = new sbyte[checked(length + count)];
                Buffer.BlockCopy(Result, 0, grown, 0, length);
                Result = grown;
            }
            Buffer.BlockCopy(buffer, offset, Result, length, count);
            length += count;
        }
        public override void Flush() { }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
    }
    internal static string WriteString(string path, object value, params object?[] _)
    {
        File.WriteAllText(path, StringValueOf(value));
        return path;
    }
    internal static string WriteAllBytes(string path, sbyte[] bytes, params object?[] _)
    {
        File.WriteAllBytes(path, ToUnsignedBytes(bytes));
        return path;
    }
    internal static string Move(string source, string destination, params object?[] _)
    {
        if (File.Exists(destination)) File.Delete(destination);
        File.Move(source, destination);
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
    internal static StreamWriter NewFileWriter(FileInfo file) => new(file.FullName, false);
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
        writer.Write(StringValueOf(value).Substring(start, end - start));

    [DllImport("libc", EntryPoint = "chmod", SetLastError = true)]
    private static extern int Chmod(string path, uint mode);

    [DllImport("libc", EntryPoint = "realpath", SetLastError = true)]
    private static extern IntPtr RealPathNative(string path, IntPtr buffer);

    [DllImport("libc", EntryPoint = "free")]
    private static extern void FreeNative(IntPtr pointer);

    [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode,
        SetLastError = true)]
    private static extern SafeFileHandle CreateFileNative(
        string path,
        uint desiredAccess,
        FileShare shareMode,
        IntPtr securityAttributes,
        FileMode creationDisposition,
        uint flagsAndAttributes,
        IntPtr templateFile);

    [DllImport("kernel32.dll", EntryPoint = "GetFinalPathNameByHandleW", CharSet = CharSet.Unicode,
        SetLastError = true)]
    private static extern uint GetFinalPathNameByHandleNative(
        SafeFileHandle handle,
        StringBuilder path,
        int pathLength,
        uint flags);

    private static string RelativePath(string basis, string path)
    {
        var basisFull = Path.GetFullPath(basis);
        var pathFull = Path.GetFullPath(path);
        var basisRoot = Path.GetPathRoot(basisFull);
        var pathRoot = Path.GetPathRoot(pathFull);
        if (!string.Equals(
                basisRoot,
                pathRoot,
                IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            return pathFull;
        if (!basisFull.EndsWith(Path.DirectorySeparatorChar))
            basisFull += Path.DirectorySeparatorChar;
        var relative = new Uri(basisFull).MakeRelativeUri(new Uri(pathFull)).ToString();
        relative = Uri.UnescapeDataString(relative)
            .Replace('/', Path.DirectorySeparatorChar);
        return relative.Length == 0 ? "." : relative;
    }

    internal static void SetPosixFilePermissions(string path, ISet<JavaUnixFileMode> permissions)
    {
        if (!IsWindows())
        {
            var mode = permissions.Aggregate(
                (JavaUnixFileMode)0, (value, permission) => value | permission);
            if (Chmod(path, (uint)mode) != 0)
                throw new IOException(
                    $"chmod failed for `{path}` with native error {Marshal.GetLastWin32Error()}.");
        }
    }
    internal static void setPosixFilePermissions(string path, ISet<JavaUnixFileMode> permissions) =>
        SetPosixFilePermissions(path, permissions);
    internal static ISet<JavaUnixFileMode> fromString(string permissions)
    {
        if (permissions.Length != 9)
            throw new ArgumentException("POSIX permissions must contain exactly nine characters.",
                nameof(permissions));
        var result = new HashSet<JavaUnixFileMode>();
        var modes = new[]
        {
            JavaUnixFileMode.UserRead, JavaUnixFileMode.UserWrite, JavaUnixFileMode.UserExecute,
            JavaUnixFileMode.GroupRead, JavaUnixFileMode.GroupWrite, JavaUnixFileMode.GroupExecute,
            JavaUnixFileMode.OtherRead, JavaUnixFileMode.OtherWrite, JavaUnixFileMode.OtherExecute
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
    internal static JavaFileAttribute<ISet<JavaUnixFileMode>> asFileAttribute(
        ISet<JavaUnixFileMode> permissions) => new(permissions);
    internal static string createTempDirectory(
        string prefix, params JavaFileAttribute<ISet<JavaUnixFileMode>>[] attributes)
    {
        var path = Path.Combine(Path.GetTempPath(), prefix + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static string createTempFile(
        string prefix, string suffix,
        params JavaFileAttribute<ISet<JavaUnixFileMode>>[] attributes) =>
        createTempFile(Path.GetTempPath(), prefix, suffix, attributes);
    internal static string createTempFile(
        string directory, string prefix, string suffix,
        params JavaFileAttribute<ISet<JavaUnixFileMode>>[] attributes)
    {
        var path = Path.Combine(directory, prefix + Guid.NewGuid().ToString("N") + suffix);
        using (File.Create(path)) { }
        if (attributes.Length > 0) SetPosixFilePermissions(path, attributes[0].Value);
        return path;
    }
    internal static JavaAclFileAttributeView? getFileAttributeView(
        string _, Type __, params object?[] ___) =>
        IsWindows() ? new JavaAclFileAttributeView() : null;
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
