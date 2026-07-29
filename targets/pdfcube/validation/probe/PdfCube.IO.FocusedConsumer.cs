using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using PdfCube.IO;
using DripSharp.Runtime;

internal static class Program
{
    private sealed class ThrowingDisposable : IDisposable
    {
        internal int Attempts { get; private set; }

        public void Dispose()
        {
            Attempts++;
            throw new IOException("close failed");
        }
    }

    private sealed class TrackingMemoryStream(byte[] bytes) : MemoryStream(bytes)
    {
        internal bool WasDisposed { get; private set; }

        protected override void Dispose(bool disposing)
        {
            WasDisposed = true;
            base.Dispose(disposing);
        }
    }

    private static void Main()
    {
        VerifyLoggingContractAndStreamUtilities();
        VerifyByteBufferAndMemoryRandomAccess();
        VerifyReadWriteAndStreamAdapters();
        VerifyNonSeekableInput();
        VerifyFileRandomAccess();
        VerifyScratchFiles();
        Console.WriteLine("PdfCube.IO focused behavior passed.");
    }

    private static void VerifyLoggingContractAndStreamUtilities()
    {
        var closeMethod = typeof(IOUtils).GetMethod(
            nameof(IOUtils.CloseAndLogException),
            [typeof(IDisposable), typeof(ILogger), typeof(string), typeof(IOException)]);
        Assert(closeMethod is not null, "IOUtils must expose the approved ILogger signature.");
        Assert(closeMethod!.ReturnType == typeof(IOException),
            "CloseAndLogException must preserve its IOException return contract.");

        using var input = new MemoryStream([0, 127, 128, 255]);
        AssertBytes(IOUtils.ToByteArray(input), 0, 127, 128, 255);

        using var copyInput = new MemoryStream([1, 2, 3, 4, 5]);
        using var copyOutput = new MemoryStream();
        Assert(IOUtils.Copy(copyInput, copyOutput) == 5, "Copy must report its byte count.");
        Assert(copyOutput.ToArray().SequenceEqual(new byte[] { 1, 2, 3, 4, 5 }),
            "Copy must preserve stream content.");

        using var populateInput = new MemoryStream([9, 8, 7]);
        var populated = new sbyte[5];
        Assert(IOUtils.PopulateBuffer(populateInput, populated) == 3,
            "PopulateBuffer must stop at EOF.");
        AssertBytes(populated, 9, 8, 7, 0, 0);

        var quiet = new ThrowingDisposable();
        IOUtils.CloseQuietly(quiet);
        Assert(quiet.Attempts == 1, "CloseQuietly must attempt cleanup once.");

        var closeFailure = new ThrowingDisposable();
        var returned = IOUtils.CloseAndLogException(
            closeFailure, NullLogger.Instance, "test resource", null!);
        Assert(returned?.Message == "close failed",
            "CloseAndLogException must return a newly raised IOException.");

        var initial = new IOException("initial failure");
        returned = IOUtils.CloseAndLogException(
            new ThrowingDisposable(), NullLogger.Instance, "test resource", initial);
        Assert(ReferenceEquals(returned, initial),
            "CloseAndLogException must preserve an initial IOException.");

        var protectedRoot = Directory.CreateTempSubdirectory("pdfcube-io-protected-");
        try
        {
            var protectedPath = IOUtils.CreateProtectedTempFile(
                new JavaPath(protectedRoot.FullName), "io-", ".tmp");
            var protectedFile = (string)protectedPath;
            Assert(File.Exists(protectedFile), "Protected temp-file creation must produce a file.");
            File.Delete(protectedFile);
        }
        finally
        {
            protectedRoot.Delete(recursive: true);
        }
    }

    private static void VerifyByteBufferAndMemoryRandomAccess()
    {
        using (var byteBuffer = JavaByteBuffer.allocate(4))
        {
            byteBuffer.put(Signed(1, 128, 255, 4)).rewind();
            Assert(byteBuffer.get() == 1, "ByteBuffer must read the first byte.");
            Assert(unchecked((byte)byteBuffer.get()) == 128,
                "ByteBuffer must preserve signed byte storage.");
            var duplicate = byteBuffer.duplicate().rewind();
            Assert(duplicate.position() == 0 && duplicate.limit() == 4,
                "ByteBuffer duplicates must have independent cursors and matching limits.");
            duplicate.Dispose();
        }

        var buffer = new RandomAccessReadBuffer(Signed(10, 20, 30, 255, 50));
        RandomAccessRead random = buffer;
        Assert(random.Length() == 5 && random.GetPosition() == 0,
            "Memory random access must expose length and initial position.");
        Assert(random.Peek() == 10 && random.GetPosition() == 0,
            "Peek must not advance the random-access position.");
        Assert(random.Read() == 10, "Read must advance through memory content.");
        random.Skip(2);
        Assert(random.Read() == 255, "Skip must move relative to the current position.");
        random.Rewind(2);
        Assert(random.Read() == 30, "Rewind must restore prior bytes.");
        Throws<IOException>(() => random.Seek(-1), "Negative memory seeks must fail.");
        random.Seek(4);
        Throws<EndOfStreamException>(
            () => random.ReadFully(new sbyte[2]),
            "ReadFully must reject premature EOF.");

        using (var view = random.CreateView(1, 3))
        {
            Assert(view.Length() == 3, "A view must report its bounded length.");
            var viewBytes = new sbyte[3];
            ((RandomAccessRead)view).ReadFully(viewBytes);
            AssertBytes(viewBytes, 20, 30, 255);
            Assert(view.IsEOF(), "A fully read view must report EOF.");
        }
        random.Seek(0);
        Assert(random.Read() == 10, "Disposing a memory view must not close its parent.");
        buffer.Dispose();
        buffer.Dispose();
        Assert(buffer.IsClosed(), "Memory buffer cleanup must be idempotent.");
        Throws<IOException>(() => buffer.Read(), "Reads after memory cleanup must fail.");

        var tracked = new TrackingMemoryStream([6, 7, 8]);
        using var streamBuffer = RandomAccessReadBuffer.CreateBufferFromStream(tracked);
        Assert(tracked.WasDisposed, "CreateBufferFromStream must close its source stream.");
        Assert(streamBuffer.Read() == 6, "Stream-backed buffers must retain copied content.");
    }

    private static void VerifyReadWriteAndStreamAdapters()
    {
        using var readWrite = new RandomAccessReadWriteBuffer(3);
        readWrite.Write(Signed(1, 2, 3, 4, 5, 6, 7));
        Assert(readWrite.Length() == 7, "Writes must expand across buffer chunks.");
        readWrite.Seek(2);
        var middle = new sbyte[4];
        Assert(readWrite.Read(middle, 0, middle.Length) == 4,
            "Chunked reads must span chunk boundaries.");
        AssertBytes(middle, 3, 4, 5, 6);

        readWrite.Clear();
        using (var output = new RandomAccessOutputStream(readWrite))
        {
            output.Write(200);
            output.Write(Signed(201, 202), 0, 2);
        }
        Assert(readWrite.Length() == 3, "RandomAccessOutputStream must delegate every write.");
        readWrite.Seek(0);
        using var input = new RandomAccessInputStream(readWrite);
        Assert(input.Available() == 3, "RandomAccessInputStream must expose remaining bytes.");
        Assert(input.Read() == 200, "RandomAccessInputStream must preserve unsigned reads.");
        Assert(input.Skip(1) == 1 && input.Read() == 202,
            "RandomAccessInputStream skip must update its independent position.");
    }

    private static void VerifyNonSeekableInput()
    {
        var bytes = Enumerable.Range(0, 5000).Select(index => (byte)(index % 251)).ToArray();
        var source = new TrackingMemoryStream(bytes);
        var input = new NonSeekableRandomAccessReadInputStream(source);
        var first = new sbyte[4200];
        Assert(input.Read(first, 0, first.Length) == first.Length,
            "Non-seekable reads must cross internal buffers.");
        Assert(input.GetPosition() == first.Length, "Non-seekable position must track reads.");
        input.Rewind(200);
        Assert(input.GetPosition() == 4000, "Non-seekable rewind must cross a buffer boundary.");
        var replay = new sbyte[200];
        input.ReadFully(replay, 0, replay.Length);
        Assert(replay.Select(value => unchecked((byte)value))
            .SequenceEqual(bytes.Skip(4000).Take(200)),
            "Non-seekable rewind must replay the original bytes.");
        input.Skip(1000);
        Assert(input.IsEOF(), "Skipping past the source end must reach EOF.");
        Throws<IOException>(() => input.Seek(0), "Non-seekable input must reject seeks.");
        input.Dispose();
        input.Dispose();
        Assert(source.WasDisposed && input.IsClosed(),
            "Non-seekable cleanup must close its source and report closed.");
        Throws<IOException>(() => input.Read(), "Reads after non-seekable cleanup must fail.");
    }

    private static void VerifyFileRandomAccess()
    {
        var file = Path.GetTempFileName();
        var bytes = Enumerable.Range(0, 9000).Select(index => (byte)(index % 253)).ToArray();
        File.WriteAllBytes(file, bytes);
        try
        {
            var buffered = new RandomAccessReadBufferedFile(file);
            Assert(buffered.Length() == bytes.Length, "Buffered files must report source length.");
            buffered.Seek(4094);
            var pageBoundary = new sbyte[6];
            Assert(buffered.Read(pageBoundary, 0, pageBoundary.Length) == 2,
                "A buffered read must stop at its current page boundary.");
            AssertBytes(pageBoundary[..2], bytes[4094], bytes[4095]);
            Assert(buffered.Read(pageBoundary, 2, 4) == 4,
                "A following buffered read must continue on the next page.");
            AssertBytes(pageBoundary, bytes.Skip(4094).Take(6).Select(value => (int)value).ToArray());
            using (var view = buffered.CreateView(5000, 4))
            {
                var selected = new sbyte[4];
                ((RandomAccessRead)view).ReadFully(selected);
                AssertBytes(selected, bytes.Skip(5000).Take(4).Select(value => (int)value).ToArray());
            }
            buffered.Seek(0);
            Assert(buffered.Read() == bytes[0],
                "Disposing a buffered-file view must not close its parent.");
            buffered.Dispose();
            buffered.Dispose();
            Assert(buffered.IsClosed(), "Buffered-file cleanup must be idempotent.");
            Throws<IOException>(() => buffered.Read(),
                "Buffered-file reads after cleanup must fail.");

            var mapped = new RandomAccessReadMemoryMappedFile(new JavaPath(file));
            Assert(mapped.Length() == bytes.Length, "Memory maps must expose source length.");
            mapped.Seek(4095);
            var mappedBytes = new sbyte[4];
            Assert(mapped.Read(mappedBytes, 0, mappedBytes.Length) == 4,
                "Memory maps must read across ordinary file offsets.");
            AssertBytes(mappedBytes, bytes.Skip(4095).Take(4).Select(value => (int)value).ToArray());
            using (var writer = new FileStream(
                       file, FileMode.Open, FileAccess.Write,
                       FileShare.ReadWrite | FileShare.Delete))
            {
                writer.Position = 4095;
                writer.WriteByte(77);
                writer.Flush(flushToDisk: true);
            }
            mapped.Seek(4095);
            Assert(mapped.Read() == 77,
                "Memory maps must observe changes made through the underlying file.");
            using (var view = mapped.CreateView(7000, 3))
            {
                var selected = new sbyte[3];
                ((RandomAccessRead)view).ReadFully(selected);
                AssertBytes(selected, bytes.Skip(7000).Take(3).Select(value => (int)value).ToArray());
            }
            mapped.Seek(0);
            Assert(mapped.Read() == bytes[0],
                "Disposing a memory-map view must not close its parent.");
            mapped.Dispose();
            mapped.Dispose();
            Assert(mapped.IsClosed(), "Memory-map cleanup must be idempotent.");
            Throws<IOException>(() => mapped.Read(), "Memory-map reads after cleanup must fail.");

            using var sequence = new SequenceRandomAccessRead(
                new List<RandomAccessRead>
                {
                    new RandomAccessReadBuffer(Signed(1, 2)),
                    new RandomAccessReadBuffer(Signed(3, 4, 5))
                });
            var sequenceBytes = new sbyte[5];
            Assert(sequence.Read(sequenceBytes, 0, sequenceBytes.Length) == 5,
                "Sequence reads must span component boundaries.");
            AssertBytes(sequenceBytes, 1, 2, 3, 4, 5);
            sequence.Seek(1);
            Assert(sequence.Read() == 2, "Sequence seeks must select the correct component.");
        }
        finally
        {
            File.Delete(file);
        }
        Assert(!File.Exists(file), "File-backed readers must release the source for deletion.");
    }

    private static void VerifyScratchFiles()
    {
        var limitedScratch = new ScratchFile(MemoryUsageSetting.SetupMainMemoryOnly(4096));
        var limitedBuffer = limitedScratch.CreateBuffer();
        try
        {
            Throws<IOException>(
                () => limitedBuffer.Write(new sbyte[4097]),
                "Main-memory scratch limits must reject excess pages.");
        }
        finally
        {
            limitedScratch.Dispose();
            limitedScratch.Dispose();
        }
        Assert(limitedBuffer.IsClosed(), "Closing a scratch file must close its buffers.");
        Throws<IOException>(() => limitedScratch.CreateBuffer(),
            "Closed scratch files must reject new buffers.");

        var scratchDirectory = Directory.CreateTempSubdirectory("pdfcube-io-scratch-");
        try
        {
            var setting = MemoryUsageSetting.SetupTempFileOnly()
                .SetTempDir(new FileInfo(scratchDirectory.FullName));
            var scratch = new ScratchFile(setting);
            var buffer = scratch.CreateBuffer();
            var bytes = Enumerable.Range(0, 5000)
                .Select(index => unchecked((sbyte)(index % 256))).ToArray();
            buffer.Write(bytes);
            Assert(Directory.EnumerateFiles(scratchDirectory.FullName).Any(),
                "Temp-file scratch mode must allocate backing storage.");
            buffer.Seek(0);
            var actual = new sbyte[bytes.Length];
            ((RandomAccessRead)buffer).ReadFully(actual);
            Assert(actual.SequenceEqual(bytes), "Scratch buffers must preserve multi-page content.");
            buffer.Clear();
            Assert(buffer.Length() == 0, "Scratch buffer Clear must reset its length.");
            buffer.Write(Signed(33, 44));
            buffer.Seek(0);
            Assert(buffer.Read() == 33, "Cleared scratch buffers must remain reusable.");
            buffer.Dispose();
            buffer.Dispose();
            Assert(buffer.IsClosed(), "Scratch-buffer cleanup must be idempotent.");
            scratch.Dispose();
            scratch.Dispose();
            Assert(!Directory.EnumerateFiles(scratchDirectory.FullName).Any(),
                "Scratch-file cleanup must delete backing files.");
        }
        finally
        {
            scratchDirectory.Delete(recursive: true);
        }
    }

    private static sbyte[] Signed(params int[] bytes) =>
        bytes.Select(value => unchecked((sbyte)value)).ToArray();

    private static void AssertBytes(sbyte[] actual, params int[] expected)
    {
        Assert(actual.Select(value => unchecked((byte)value))
                .SequenceEqual(expected.Select(value => unchecked((byte)value))),
            $"Byte mismatch. Expected [{string.Join(",", expected)}], " +
            $"actual [{string.Join(",", actual.Select(value => unchecked((byte)value)))}].");
    }

    private static T Throws<T>(Action action, string message) where T : Exception
    {
        try
        {
            action();
        }
        catch (T exception)
        {
            return exception;
        }
        throw new InvalidOperationException(message);
    }

    private static void Assert(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
