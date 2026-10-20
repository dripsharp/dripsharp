using DripSharp.PdfCarton.IO;
using Compat = DripSharp.Runtime;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class IoConsumerTests
{
    [Fact]
    public void RandomAccessBufferPreservesUnsignedReadsSeekingAndViews()
    {
        using var input =
            new RandomAccessReadBuffer(new sbyte[] { 1, -2, 3, 4 });
        Assert.Equal(1, input.Read());
        Assert.Equal(254, input.Read());
        input.Seek(1);
        Assert.Equal(254, input.Read());

        using RandomAccessRead view = input.CreateView(2, 2);
        Assert.Equal(3, view.Read());
        Assert.Equal(4, view.Read());
        Assert.Equal(-1, view.Read());
    }

    // IOUtils.copy moves 4 KiB chunks between Java and .NET streams; each chunk
    // used to cost one to three temporary arrays, so copies allocated a
    // multiple of the data size.
    [Fact]
    public void IOUtilsCopyAcrossTheStreamBoundaryAllocatesIndependentlyOfSize()
    {
        const int size = 4 << 20;
        var payload = Payload(size);
        var path = Path.GetTempFileName();
        try
        {
            File.WriteAllBytes(path, payload);
            var sink = new MemoryStream(size);
            var writer = new CountingWrite();
            var copies = new (string Name, Func<long> Copy)[]
            {
                ("FileStream to MemoryStream", () =>
                {
                    sink.SetLength(0);
                    using var input = new FileStream(path, FileMode.Open, FileAccess.Read);
                    return IOUtils.Copy(input, sink);
                }),
                ("MemoryStream to RandomAccessOutputStream", () =>
                {
                    writer.Clear();
                    using var output = new RandomAccessOutputStream(writer);
                    return IOUtils.Copy(new MemoryStream(payload, writable: false), output);
                }),
                ("RandomAccessInputStream to FileStream", () =>
                {
                    using var input = new RandomAccessInputStream(
                        new RandomAccessReadBuffer((sbyte[])(Array)payload));
                    using var output = new FileStream(path + ".out", FileMode.Create, FileAccess.Write);
                    return IOUtils.Copy(input, output);
                }),
                ("RandomAccessInputStream to MemoryStream with Stream.CopyTo", () =>
                {
                    sink.SetLength(0);
                    using var input = new RandomAccessInputStream(
                        new RandomAccessReadBuffer((sbyte[])(Array)payload));
                    input.CopyTo(sink);
                    return sink.Length;
                }),
            };
            foreach (var (name, copy) in copies)
            {
                Assert.Equal(size, copy());
                var allocated = GC.GetAllocatedBytesForCurrentThread();
                Assert.Equal(size, copy());
                allocated = GC.GetAllocatedBytesForCurrentThread() - allocated;
                Assert.True(allocated < 256 * 1024, $"{name} allocated {allocated} bytes for {size}.");
            }
            Assert.Equal(size, writer.Count);
            Assert.Equal(payload, sink.ToArray());
            Assert.Equal(payload, File.ReadAllBytes(path + ".out"));
        }
        finally
        {
            File.Delete(path);
            File.Delete(path + ".out");
        }
    }

    [Fact]
    public void JavaStreamOverridesSeeCallerArraysAndShortReads()
    {
        var payload = Payload(10_000);
        var output = new RecordingOutput();
        Assert.Equal(payload.Length, IOUtils.Copy(new MemoryStream(payload), output));
        Assert.Equal(payload, output.Bytes.ToArray());
        // IOUtils.copy reuses one buffer, and the override receives that array.
        Assert.Single(output.Arrays);

        var copied = new MemoryStream();
        new TrickleInput(payload).CopyTo(copied);
        Assert.Equal(payload, copied.ToArray());

        var sliced = new sbyte[8];
        Assert.Equal(3, new TrickleInput(payload).Read(sliced, 2, 6));
        Assert.Equal(new sbyte[] { 0, 0, (sbyte)payload[0], (sbyte)payload[1], (sbyte)payload[2], 0, 0, 0 }, sliced);
    }

    // Native .NET streams other than FileStream/MemoryStream are read through
    // pooled 64 KiB chunks. Seekable streams keep filling while whole chunks
    // arrive; a later failure is a short read; others return one chunk.
    [Fact]
    public void NativeStreamBoundaryPreservesChunkedReadsAndFailures()
    {
        const int offset = 7;
        const int request = 200_000;
        var payload = Payload(request);

        (int Result, sbyte[] Destination, ScriptedStream Stream) Read(
            int available, bool seekable, int failingRead = 0)
        {
            var stream = new ScriptedStream(payload.AsSpan(0, available).ToArray(), seekable, failingRead);
            var destination = new sbyte[offset + request + 3];
            Array.Fill(destination, (sbyte)0x55);
            var result = new Filter(stream).Read(destination, offset, request);
            return (result, destination, stream);
        }

        void AssertFilled(sbyte[] destination, int count)
        {
            var expected = new sbyte[destination.Length];
            Array.Fill(expected, (sbyte)0x55);
            Array.Copy((sbyte[])(Array)payload, 0, expected, offset, count);
            Assert.Equal(expected, destination);
        }

        var whole = Read(request, seekable: true);
        Assert.Equal(request, whole.Result);
        Assert.Equal(4, whole.Stream.Reads);
        AssertFilled(whole.Destination, request);

        var shortFile = Read(100_000, seekable: true);
        Assert.Equal(100_000, shortFile.Result);
        Assert.Equal(2, shortFile.Stream.Reads);
        AssertFilled(shortFile.Destination, 100_000);

        var laterFailure = Read(request, seekable: true, failingRead: 2);
        Assert.Equal(65536, laterFailure.Result);
        Assert.Equal(2, laterFailure.Stream.Reads);
        AssertFilled(laterFailure.Destination, 65536);

        var firstFailure = Assert.Throws<IOException>(() => Read(request, seekable: true, failingRead: 1));
        Assert.Same(ScriptedStream.Failure, firstFailure);

        var pipe = Read(request, seekable: false);
        Assert.Equal(65536, pipe.Result);
        Assert.Equal(1, pipe.Stream.Reads);
        AssertFilled(pipe.Destination, 65536);

        var empty = Read(0, seekable: true);
        Assert.Equal(-1, empty.Result);
        AssertFilled(empty.Destination, 0);
    }

    private static byte[] Payload(int size)
    {
        var payload = new byte[size];
        for (var index = 0; index < size; index++)
            payload[index] = unchecked((byte)(index * 31 + (index >> 12)));
        return payload;
    }

    private sealed class Filter(Stream input) : Compat.JavaFilterInputStream(input);

    private sealed class ScriptedStream(byte[] data, bool seekable, int failingRead) : Stream
    {
        internal static readonly IOException Failure = new("scripted read failure");
        private int position;
        internal int Reads;
        public override bool CanRead => true;
        public override bool CanSeek => seekable;
        public override bool CanWrite => false;
        public override long Length => seekable ? data.Length : throw new NotSupportedException();
        public override long Position
        {
            get => seekable ? position : throw new NotSupportedException();
            set => position = seekable ? checked((int)value) : throw new NotSupportedException();
        }
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (++Reads == failingRead) throw Failure;
            var length = Math.Min(count, data.Length - position);
            Buffer.BlockCopy(data, position, buffer, offset, length);
            position += length;
            return length;
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private sealed class CountingWrite : RandomAccessWrite
    {
        internal long Count;
        public void Write(int b) => Count++;
        public void Write(sbyte[] b) => Count += b.Length;
        public void Write(sbyte[] b, int offset, int length) => Count += length;
        public void Clear() => Count = 0;
        public void Dispose() { }
    }

    private sealed class RecordingOutput : Compat.JavaOutputStream
    {
        internal readonly MemoryStream Bytes = new();
        internal readonly HashSet<sbyte[]> Arrays = new(ReferenceEqualityComparer.Instance);
        public override void Write(int value) => Bytes.WriteByte(unchecked((byte)value));
        public override void Write(sbyte[] buffer, int offset, int count)
        {
            Arrays.Add(buffer);
            Bytes.Write((byte[])(Array)buffer, offset, count);
        }
    }

    // Returns at most three bytes per bulk read, through System.arraycopy-like
    // Array.Copy into the caller's array.
    private sealed class TrickleInput(byte[] bytes) : Compat.JavaInputStream
    {
        private readonly sbyte[] source = (sbyte[])(Array)bytes.Clone();
        private int position;
        public override int Read() => position < source.Length ? (byte)source[position++] : -1;
        public override int Read(sbyte[] buffer, int offset, int count)
        {
            if (position >= source.Length) return -1;
            var length = Math.Min(Math.Min(3, count), source.Length - position);
            Array.Copy(source, position, buffer, offset, length);
            position += length;
            return length;
        }
    }
}
