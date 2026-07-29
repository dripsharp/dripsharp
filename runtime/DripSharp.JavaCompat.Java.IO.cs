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

// JDK compatibility area: Java.IO


internal sealed class JavaFileNotFoundException : FileNotFoundException
{
    // Java's no-argument FileNotFoundException has a null message. The CLR
    // supplies a generic fallback message, which would become a spurious
    // destination diagnostic unless the Java contract is retained explicitly.
    public override string Message => null!;
}

internal sealed class JavaRandomAccessFile : IDisposable
{
    private readonly FileStream stream;
    private bool disposed;

    internal JavaRandomAccessFile(FileInfo file, string mode)
    {
        ArgumentNullException.ThrowIfNull(file);
        stream = mode switch
        {
            "r" => new FileStream(file.FullName, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete),
            "rw" => new FileStream(file.FullName, FileMode.OpenOrCreate, FileAccess.ReadWrite,
                FileShare.Read),
            _ => throw new ArgumentException($"Unsupported random-access mode `{mode}`.", nameof(mode))
        };
    }
    internal long length()
    {
        ThrowIfDisposed();
        return stream.Length;
    }
    internal void readFully(sbyte[] destination)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(destination);
        var unsigned = new byte[destination.Length];
        var total = 0;
        while (total < unsigned.Length)
        {
            var read = stream.Read(unsigned, total, unsigned.Length - total);
            if (read == 0) throw new EndOfStreamException();
            total += read;
        }
        Buffer.BlockCopy(unsigned, 0, destination, 0, unsigned.Length);
    }
    internal void seek(long position)
    {
        ThrowIfDisposed();
        if (position < 0) throw new IOException("Negative seek offset");
        stream.Position = position;
    }
    internal void setLength(long length)
    {
        ThrowIfDisposed();
        stream.SetLength(length);
    }
    internal void write(sbyte[] source)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        var unsigned = new byte[source.Length];
        Buffer.BlockCopy(source, 0, unsigned, 0, source.Length);
        stream.Write(unsigned, 0, unsigned.Length);
    }
    internal void close() => Dispose();
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stream.Dispose();
    }
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(disposed, this);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaInputStream : Stream, IDisposable
{
    public abstract int Read();

    public virtual int Read(sbyte[] buffer) => Read(buffer, 0, buffer.Length);

    public virtual int Read(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        if (offset < 0 || count < 0 || offset + count > buffer.Length)
            throw new ArgumentOutOfRangeException();
        if (count == 0) return 0;
        var first = Read();
        if (first < 0) return -1;
        buffer[offset] = unchecked((sbyte)first);
        var copied = 1;
        while (copied < count)
        {
            var next = Read();
            if (next < 0) break;
            buffer[offset + copied++] = unchecked((sbyte)next);
        }
        return copied;
    }

    public virtual int Available() => 0;
    public virtual long Skip(long count)
    {
        if (count <= 0) return 0;
        var skipped = 0L;
        while (skipped < count && Read() >= 0) skipped++;
        return skipped;
    }
    public virtual void Mark(int readLimit) => _ = readLimit;
    public virtual void Reset() =>
        throw new IOException("mark/reset is not supported by this input stream.");
    public virtual bool MarkSupported() => false;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var signed = new sbyte[count];
        var readCount = Read(signed, 0, count);
        if (readCount > 0) Buffer.BlockCopy(signed, 0, buffer, offset, readCount);
        return Math.Max(0, readCount);
    }

    public override int ReadByte() => Read();
    public override void Flush() { }
    public new virtual void Dispose() => base.Dispose();
    void IDisposable.Dispose() => Dispose();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterInputStream : JavaInputStream
{
    protected readonly Stream @in;

    protected JavaFilterInputStream(Stream input) =>
        @in = input ?? throw new ArgumentNullException(nameof(input));

    public override int Read() => @in.ReadByte();

    public override int Read(sbyte[] buffer, int offset, int count) =>
        JavaCompat.InputStreamRead(@in, buffer, offset, count);

    public override int Available() =>
        @in.CanSeek ? checked((int)Math.Min(int.MaxValue, @in.Length - @in.Position)) : 0;

    public override long Skip(long count)
    {
        if (count <= 0) return 0;
        if (@in.CanSeek)
        {
            var original = @in.Position;
            @in.Position = Math.Min(@in.Length, original + count);
            return @in.Position - original;
        }
        return base.Skip(count);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) ((IDisposable)@in).Dispose();
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaOutputStream : Stream, IDisposable
{
    private bool disposeDispatching;

    public abstract void Write(int value);

    public virtual void Write(sbyte[] buffer) => Write(buffer, 0, buffer.Length);

    public virtual void Write(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        for (var index = 0; index < count; index++) Write(buffer[offset + index]);
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        var signed = new sbyte[count];
        Buffer.BlockCopy(buffer, offset, signed, 0, count);
        Write(signed, 0, count);
    }

    public override void WriteByte(byte value) => Write(value);
    public override void Flush() { }
    public new virtual void Dispose()
    {
        if (disposeDispatching)
        {
            base.Dispose();
            return;
        }
        disposeDispatching = true;
        try
        {
            base.Dispose();
        }
        finally
        {
            disposeDispatching = false;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !disposeDispatching)
        {
            disposeDispatching = true;
            try
            {
                Dispose();
            }
            finally
            {
                disposeDispatching = false;
            }
        }
        base.Dispose(disposing);
    }

    void IDisposable.Dispose() => Dispose();
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaByteArrayOutputStream : MemoryStream
{
    public JavaByteArrayOutputStream()
    {
    }

    public JavaByteArrayOutputStream(int capacity)
        : base(capacity)
    {
    }

    protected override void Dispose(bool disposing)
    {
        // java.io.ByteArrayOutputStream.close() has no effect. Its content,
        // size, reset, and write operations remain available after close.
    }
}

internal sealed class JavaPipedInputStream : Stream
{
    private readonly JavaPipe pipe = new();

    internal JavaPipe Pipe => pipe;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count) =>
        pipe.Read(buffer, offset, count);
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing) pipe.CloseReader();
        base.Dispose(disposing);
    }
}

internal sealed class JavaPushbackInputStream : Stream
{
    private readonly Stream source;
    private readonly byte[] pushback;
    private int position;

    internal JavaPushbackInputStream(Stream source) =>
        (this.source, pushback, position) =
            (JavaCompat.RequireNonNull(source), new byte[1], 1);

    internal JavaPushbackInputStream(Stream source, int size)
    {
        if (size <= 0) throw new ArgumentOutOfRangeException(nameof(size));
        this.source = JavaCompat.RequireNonNull(source);
        pushback = new byte[size];
        position = size;
    }

    internal void Unread(int value)
    {
        if (position == 0) throw new IOException("Push back buffer is full");
        pushback[--position] = unchecked((byte)value);
    }

    internal void Unread(sbyte[] values, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || length < 0 || offset > values.Length - length)
            throw new IndexOutOfRangeException();
        if (length > position) throw new IOException("Push back buffer is full");
        position -= length;
        for (var index = 0; index < length; index++)
            pushback[position + index] = unchecked((byte)values[offset + index]);
    }

    public override int ReadByte()
    {
        return position < pushback.Length
            ? pushback[position++]
            : source.ReadByte();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (count == 0) return 0;
        var copied = 0;
        while (count > 0 && position < pushback.Length)
        {
            buffer[offset++] = pushback[position++];
            count--;
            copied++;
        }
        if (count == 0) return copied;
        return copied + source.Read(buffer, offset, count);
    }

    public override bool CanRead => source.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    protected override void Dispose(bool disposing)
    {
        if (disposing) source.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaSequenceInputStream : Stream
{
    private readonly Stream first;
    private readonly Stream second;
    private bool readingFirst = true;

    internal JavaSequenceInputStream(Stream first, Stream second)
    {
        this.first = first ?? throw new ArgumentNullException(nameof(first));
        this.second = second ?? throw new ArgumentNullException(nameof(second));
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (readingFirst)
        {
            var read = first.Read(buffer, offset, count);
            if (read != 0) return read;
            readingFirst = false;
        }
        return second.Read(buffer, offset, count);
    }

    public override int Read(Span<byte> buffer)
    {
        if (readingFirst)
        {
            var read = first.Read(buffer);
            if (read != 0) return read;
            readingFirst = false;
        }
        return second.Read(buffer);
    }

    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try
            {
                first.Dispose();
            }
            finally
            {
                second.Dispose();
            }
        }
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterOutputStream : JavaOutputStream
{
    protected readonly Stream @out;

    protected JavaFilterOutputStream(Stream output) => @out = output;
    public override bool CanWrite => @out.CanWrite;
    public override void Write(int value) => @out.WriteByte(unchecked((byte)value));
    public override void Write(sbyte[] buffer, int offset, int count) =>
        base.Write(buffer, offset, count);
    public override void Flush() => @out.Flush();

    public override void Dispose()
    {
        ((IDisposable)@out).Dispose();
        base.Dispose();
    }
}

internal sealed class JavaPipedOutputStream : Stream
{
    private readonly object sync = new();
    private JavaPipe? pipe;
    private bool closed;

    internal void Connect(JavaPipedInputStream receiver)
    {
        ArgumentNullException.ThrowIfNull(receiver);
        lock (sync)
        {
            if (closed) throw new IOException("Pipe is closed.");
            if (pipe is not null) throw new IOException("Pipe is already connected.");
            receiver.Pipe.ConnectWriter();
            pipe = receiver.Pipe;
        }
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => !closed;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        JavaPipe connected;
        lock (sync)
        {
            if (closed) throw new IOException("Pipe is closed.");
            connected = pipe ?? throw new IOException("Pipe is not connected.");
        }
        connected.Write(buffer, offset, count);
    }

    public override void Flush() { }
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            JavaPipe? connected;
            lock (sync)
            {
                if (closed) return;
                closed = true;
                connected = pipe;
            }
            connected?.CloseWriter();
        }
        base.Dispose(disposing);
    }
}

internal sealed class JavaDataOutputStream : Stream
{
    private readonly Stream output;

    internal JavaDataOutputStream(Stream output) =>
        this.output = output ?? throw new ArgumentNullException(nameof(output));

    internal void write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void Write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void Write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void writeByte(int value) => output.WriteByte(unchecked((byte)value));

    internal void writeShort(int value)
    {
        Span<byte> bytes = stackalloc byte[2];
        System.Buffers.Binary.BinaryPrimitives.WriteInt16BigEndian(bytes, unchecked((short)value));
        output.Write(bytes);
    }

    internal void writeInt(int value)
    {
        Span<byte> bytes = stackalloc byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(bytes, value);
        output.Write(bytes);
    }

    internal void writeLong(long value)
    {
        Span<byte> bytes = stackalloc byte[8];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(bytes, value);
        output.Write(bytes);
    }

    internal void flush() => output.Flush();
    public override bool CanRead => false;
    public override bool CanSeek => output.CanSeek;
    public override bool CanWrite => output.CanWrite;
    public override long Length => output.Length;
    public override long Position
    {
        get => output.Position;
        set => output.Position = value;
    }
    public override void Flush() => output.Flush();
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => output.Seek(offset, origin);
    public override void SetLength(long value) => output.SetLength(value);
    public override void Write(byte[] buffer, int offset, int count) =>
        output.Write(buffer, offset, count);
    public override void Write(ReadOnlySpan<byte> buffer) => output.Write(buffer);
    public override void WriteByte(byte value) => output.WriteByte(value);
    protected override void Dispose(bool disposing)
    {
        if (disposing) output.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaLineNumberReader : IDisposable
{
    private readonly TextReader reader;

    internal JavaLineNumberReader(TextReader reader) =>
        this.reader = reader ?? throw new ArgumentNullException(nameof(reader));

    internal string? ReadLine() => reader.ReadLine();
    public void Dispose() => reader.Dispose();
}

internal sealed class JavaPrintWriter
{
    private readonly TextWriter writer;
    public JavaPrintWriter(TextWriter writer) => this.writer = writer;
    public void Print(object? value) => writer.Write(value);
    public void Println(object? value = null) => writer.WriteLine(value);
    public void Flush() => writer.Flush();
}


internal static partial class JavaCompat
{
    private sealed class StreamMark
    {
        internal long Position;
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Stream, StreamMark>
        StreamMarks = new();
    internal static int ReaderRead(TextReader reader, char[] buffer, int index, int count)
    {
        var read = reader.Read(buffer, index, count);
        return read == 0 && count != 0 ? -1 : read;
    }
    internal static bool ReaderReady(TextReader reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        return reader.Peek() >= 0;
    }

    internal static void ResetMemoryStream(MemoryStream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        stream.SetLength(0);
        stream.Position = 0;
    }

    internal static string MemoryStreamToString(MemoryStream stream, string encodingName)
    {
        ArgumentNullException.ThrowIfNull(stream);
        return CharsetForName(encodingName).GetString(stream.ToArray());
    }

    internal static Stream OpenFileInput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileInput(file.FullName);
    }

    internal static Stream OpenFileInput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
    }

    internal static TextReader OpenFileReader(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return new StreamReader(file.FullName);
    }

    internal static Stream OpenFileOutput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileOutput(file.FullName);
    }

    internal static Stream OpenFileOutput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
    }

    internal static long FileLastModified(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        file.Refresh();
        return file.Exists
            ? new DateTimeOffset(file.LastWriteTimeUtc).ToUnixTimeMilliseconds()
            : 0;
    }

    internal static FileInfo NewFileInfo(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri || !uri.IsFile)
            throw new ArgumentException("File URI must be absolute and use the file scheme.", nameof(uri));
        return new FileInfo(uri.LocalPath);
    }

    internal static FileInfo NewFileInfo(string parent, string child)
    {
        ArgumentNullException.ThrowIfNull(parent);
        ArgumentNullException.ThrowIfNull(child);
        return new FileInfo(Path.Combine(parent, child));
    }

    internal static bool FileCanWrite(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        try
        {
            if (Directory.Exists(file.FullName))
            {
                var probe = Path.Combine(file.FullName, $".dripsharp-write-{Guid.NewGuid():N}.tmp");
                using (new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
                File.Delete(probe);
                return true;
            }
            if (!file.Exists) return false;
            using var stream = new FileStream(
                file.FullName, FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    internal static void WriterWriteCharCode(TextWriter writer, int value)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.Write(unchecked((char)value));
    }

    internal static bool FileEquals(FileInfo file, object? other)
    {
        ArgumentNullException.ThrowIfNull(file);
        return other is FileInfo candidate &&
            string.Equals(
                file.ToString(),
                candidate.ToString(),
                OperatingSystem.IsWindows()
                    ? StringComparison.OrdinalIgnoreCase
                    : StringComparison.Ordinal);
    }

    internal static IOException NewIOException() => new();
    internal static IOException NewIOException(string? message) => new(message);
    internal static IOException NewIOException(Exception cause) => new(cause.Message, cause);
    internal static IOException NewIOException(string? message, Exception? cause) => new(message, cause);
    internal static FileNotFoundException NewFileNotFoundException() => new JavaFileNotFoundException();
    internal static void OutputStreamWrite(Stream stream, sbyte[] values) =>
        stream.Write(ToUnsignedBytes(values));
    internal static void OutputStreamWrite(Stream stream, sbyte[] values, int offset, int count)
    {
        var buffer = new byte[count];
        for (var index = 0; index < count; index++)
            buffer[index] = unchecked((byte)values[offset + index]);
        stream.Write(buffer, 0, buffer.Length);
    }
    internal static void OutputStreamWrite(Stream stream, int value) =>
        stream.WriteByte(unchecked((byte)value));
    internal static void OutputStreamWrite(JavaDataOutputStream stream, sbyte[] values) =>
        stream.write(values);
    internal static void OutputStreamWrite(
        JavaDataOutputStream stream,
        sbyte[] values,
        int offset,
        int count) =>
        stream.write(values, offset, count);
    internal static bool InputStreamMarkSupported(Stream stream) => stream.CanSeek;
    internal static void InputStreamMark(Stream stream, int _)
    {
        if (stream.CanSeek) StreamMarks.GetOrCreateValue(stream).Position = stream.Position;
    }
    internal static void InputStreamReset(Stream stream)
    {
        if (!stream.CanSeek || !StreamMarks.TryGetValue(stream, out var mark))
            throw new IOException("Stream mark is not available.");
        stream.Position = mark.Position;
    }
    internal static long InputStreamSkip(Stream stream, long count)
    {
        if (count <= 0) return 0;
        if (stream.CanSeek)
        {
            var available = Math.Max(0, stream.Length - stream.Position);
            var skipped = Math.Min(available, count);
            stream.Position += skipped;
            return skipped;
        }
        var buffer = new byte[8192];
        long total = 0;
        while (total < count)
        {
            var read = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, count - total));
            if (read == 0) break;
            total += read;
        }
        return total;
    }
    internal static int InputStreamRead(Stream stream) => stream.ReadByte();
    internal static int InputStreamRead(Stream stream, sbyte[] values) =>
        InputStreamRead(stream, values, 0, values.Length);
    internal static int InputStreamRead(Stream stream, sbyte[] values, int offset, int count)
    {
        if (count == 0) return 0;
        var buffer = new byte[count];
        var read = stream.Read(buffer, 0, count);
        if (read == 0) return -1;
        for (var index = 0; index < read; index++)
            values[offset + index] = unchecked((sbyte)buffer[index]);
        return read;
    }
    internal static void MemoryStreamWriteTo(MemoryStream source, Stream destination)
    {
        if (!source.TryGetBuffer(out var contents))
            contents = new ArraySegment<byte>(source.ToArray());
        destination.Write(contents.AsSpan(0, checked((int)source.Length)));
    }
}
