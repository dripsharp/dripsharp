using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using PdfCube.IO;
using DripSharp.Runtime;

internal static class Program
{
    private sealed class TrackingMemoryStream(byte[] bytes) : MemoryStream(bytes)
    {
        internal bool WasDisposed { get; private set; }

        protected override void Dispose(bool disposing)
        {
            WasDisposed = true;
            base.Dispose(disposing);
        }
    }

    private static readonly List<string> Observations = [];

    private static void Main(string[] args)
    {
        if (args.Length is not (1 or 2 or 4))
            throw new ArgumentException(
                "Expected output trace, optional canonical trace, and optional OS/architecture.");

        ObserveBuffersSeekViewsAndEof();
        ObserveNonSeekableAndSequenceReads();
        ObserveFileBackedAndMemoryMappedReads();
        ObserveScratchStorageAndLimits();

        File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
        if (args.Length >= 2)
            ValidateCanonical(args[1]);
        if (args.Length == 4)
            ValidateHost(args[2], args[3]);

        Console.WriteLine(
            $"PdfCube.IO package differential passed: {Observations.Count} observations.");
    }

    private static void ObserveBuffersSeekViewsAndEof()
    {
        var buffer = new RandomAccessReadBuffer(Bytes(10, 20, 30, 255, 50));
        RandomAccessRead memory = buffer;
        Observe(
            "buffer",
            "memory-read",
            Join(memory.Length(), memory.GetPosition(), memory.Peek(), memory.GetPosition(), memory.Read()));

        memory.Skip(2);
        var afterSkip = memory.Read();
        memory.Rewind(2);
        var afterRewind = memory.Read();
        memory.Seek(99);
        Observe(
            "seek-rewind",
            "memory-positioning",
            Join(afterSkip, afterRewind, memory.GetPosition(), memory.Length()));
        Observe("eof", "memory-end", Join(memory.Read(), memory.IsEOF()));

        memory.Seek(0);
        var view = memory.CreateView(1, 3);
        var selected = new sbyte[3];
        ((RandomAccessRead)view).ReadFully(selected);
        Observe(
            "views",
            "memory-bounds",
            Join(view.Length(), view.GetPosition(), view.IsEOF(), Unsigned(selected)));
        Observe("failure", "nested-view", FailureKind(() => view.CreateView(0, 1)));
        view.Dispose();
        memory.Seek(0);
        Observe("lifecycle", "view-parent-independent", Join(view.IsClosed(), memory.Read()));

        Observe("failure", "negative-seek", FailureKind(() => memory.Seek(-1)));
        memory.Seek(4);
        Observe(
            "failure",
            "premature-eof",
            FailureKind(() => memory.ReadFully(new sbyte[2])));
        buffer.Dispose();
        buffer.Dispose();
        Observe(
            "lifecycle",
            "memory-close-idempotent",
            Join(buffer.IsClosed(), FailureKind(() => buffer.Read())));

        var readWrite = new RandomAccessReadWriteBuffer(3);
        readWrite.Write(Bytes(1, 2, 3, 4, 5, 6, 7));
        readWrite.Seek(2);
        var middle = new sbyte[4];
        var count = readWrite.Read(middle, 0, middle.Length);
        Observe("buffer", "chunked-write-read", Join(readWrite.Length(), count, Unsigned(middle)));
        readWrite.Clear();
        readWrite.Write(Bytes(200, 201, 202));
        readWrite.Seek(0);
        var rewritten = new sbyte[3];
        ((RandomAccessRead)readWrite).ReadFully(rewritten);
        Observe("buffer", "clear-and-rewrite", Join(readWrite.Length(), Unsigned(rewritten)));
        readWrite.Dispose();
    }

    private static void ObserveNonSeekableAndSequenceReads()
    {
        var sourceBytes = Enumerable.Range(0, 5000)
            .Select(index => (byte)(index % 251))
            .ToArray();
        var source = new TrackingMemoryStream(sourceBytes);
        var input = new NonSeekableRandomAccessReadInputStream(source);
        var first = new sbyte[4200];
        var firstCount = input.Read(first, 0, first.Length);
        input.Rewind(200);
        var replay = new sbyte[200];
        input.ReadFully(replay, 0, replay.Length);
        input.Skip(1000);
        Observe(
            "buffer",
            "non-seekable-rewind",
            Join(
                firstCount,
                unchecked((byte)replay[0]),
                unchecked((byte)replay[199]),
                input.GetPosition(),
                input.IsEOF()));
        Observe("failure", "non-seekable-seek", FailureKind(() => input.Seek(0)));
        input.Dispose();
        input.Dispose();
        Observe(
            "lifecycle",
            "non-seekable-close",
            Join(source.WasDisposed, input.IsClosed(), FailureKind(() => input.Read())));

        var sequence = new SequenceRandomAccessRead(
            new List<RandomAccessRead>
            {
                new RandomAccessReadBuffer(Bytes(1, 2)),
                new RandomAccessReadBuffer(Bytes(3, 4, 5))
            });
        var combined = new sbyte[5];
        var count = sequence.Read(combined, 0, combined.Length);
        sequence.Seek(1);
        Observe("buffer", "sequence-read", Join(count, Unsigned(combined), sequence.Read()));
        sequence.Dispose();
    }

    private static void ObserveFileBackedAndMemoryMappedReads()
    {
        var file = Path.GetTempFileName();
        var bytes = Enumerable.Range(0, 9000)
            .Select(index => (byte)(index % 253))
            .ToArray();
        File.WriteAllBytes(file, bytes);
        try
        {
            var buffered = new RandomAccessReadBufferedFile(file);
            buffered.Seek(4094);
            var boundary = new sbyte[6];
            var first = buffered.Read(boundary, 0, boundary.Length);
            var second = buffered.Read(boundary, first, boundary.Length - first);
            var view = buffered.CreateView(5000, 4);
            var viewBytes = new sbyte[4];
            ((RandomAccessRead)view).ReadFully(viewBytes);
            view.Dispose();
            buffered.Seek(0);
            Observe(
                "file-backed",
                "buffered-page-and-view",
                Join(
                    buffered.Length(),
                    first,
                    second,
                    Unsigned(boundary),
                    Unsigned(viewBytes),
                    buffered.Read()));
            buffered.Dispose();
            buffered.Dispose();
            Observe(
                "lifecycle",
                "buffered-file-close",
                Join(buffered.IsClosed(), FailureKind(() => buffered.Read())));

            var mapped = new RandomAccessReadMemoryMappedFile(new JavaPath(file));
            mapped.Seek(4095);
            var mappedBytes = new sbyte[4];
            var mappedCount = mapped.Read(mappedBytes, 0, mappedBytes.Length);
            var mappedView = mapped.CreateView(7000, 3);
            var mappedViewBytes = new sbyte[3];
            ((RandomAccessRead)mappedView).ReadFully(mappedViewBytes);
            mappedView.Dispose();
            mapped.Seek(9000);
            Observe(
                "memory-mapped",
                "mapped-read-view-eof",
                Join(
                    mapped.Length(),
                    mappedCount,
                    Unsigned(mappedBytes),
                    Unsigned(mappedViewBytes),
                    mapped.Read(),
                    mapped.IsEOF()));
            mapped.Dispose();
            mapped.Dispose();
            Observe(
                "lifecycle",
                "memory-map-close",
                Join(mapped.IsClosed(), FailureKind(() => mapped.Read())));
        }
        finally
        {
            File.Delete(file);
        }
        Observe("file-backed", "file-release", Join(File.Exists(file)));
    }

    private static void ObserveScratchStorageAndLimits()
    {
        var setting = MemoryUsageSetting.SetupMixed(4096, 12288);
        Observe(
            "memory-limits",
            "setting",
            Join(
                setting.UseMainMemory(),
                setting.UseTempFile(),
                setting.IsMainMemoryRestricted(),
                setting.IsStorageRestricted(),
                setting.GetMaxMainMemoryBytes(),
                setting.GetMaxStorageBytes()));

        var limited = new ScratchFile(MemoryUsageSetting.SetupMainMemoryOnly(4096));
        var limitedBuffer = limited.CreateBuffer();
        Observe(
            "memory-limits",
            "main-memory-overflow",
            FailureKind(() => limitedBuffer.Write(new sbyte[4097])));
        limited.Dispose();
        limited.Dispose();
        Observe(
            "lifecycle",
            "scratch-closes-buffer",
            Join(limitedBuffer.IsClosed(), FailureKind(() => limited.CreateBuffer())));

        var directory = Directory.CreateTempSubdirectory("pdfcube-io-scratch-");
        try
        {
            var scratch = new ScratchFile(
                MemoryUsageSetting.SetupTempFileOnly()
                    .SetTempDir(new FileInfo(directory.FullName)));
            var buffer = scratch.CreateBuffer();
            var expected = Enumerable.Range(0, 5000)
                .Select(index => unchecked((sbyte)(index % 256)))
                .ToArray();
            var expectedSum = expected.Sum(value => (long)unchecked((byte)value));
            buffer.Write(expected);
            var existedDuringWrite = Directory.EnumerateFiles(directory.FullName).Any();
            buffer.Seek(0);
            var actual = new sbyte[expected.Length];
            buffer.ReadFully(actual);
            var actualSum = actual.Sum(value => (long)unchecked((byte)value));
            buffer.Clear();
            buffer.Write(Bytes(33, 44));
            buffer.Seek(0);
            Observe(
                "scratch-storage",
                "temp-file-roundtrip",
                Join(
                    existedDuringWrite,
                    expectedSum,
                    actualSum,
                    unchecked((byte)actual[0]),
                    unchecked((byte)actual[^1]),
                    buffer.Length(),
                    buffer.Read()));
            buffer.Dispose();
            buffer.Dispose();
            scratch.Dispose();
            scratch.Dispose();
            Observe(
                "lifecycle",
                "scratch-cleanup",
                Join(buffer.IsClosed(), !Directory.EnumerateFiles(directory.FullName).Any()));
        }
        finally
        {
            directory.Delete(recursive: true);
        }
    }

    private static void ValidateCanonical(string canonicalPath)
    {
        var expected = File.ReadAllLines(canonicalPath, Encoding.UTF8);
        if (!expected.SequenceEqual(Observations, StringComparer.Ordinal))
        {
            var mismatch = Enumerable.Range(0, Math.Max(expected.Length, Observations.Count))
                .First(index =>
                    index >= expected.Length ||
                    index >= Observations.Count ||
                    !string.Equals(expected[index], Observations[index], StringComparison.Ordinal));
            throw new InvalidOperationException(
                $"Canonical differential mismatch at line {mismatch + 1}.");
        }
    }

    private static void ValidateHost(string expectedOs, string expectedArchitecture)
    {
        var osMatches = expectedOs switch
        {
            "linux" => RuntimeInformation.IsOSPlatform(OSPlatform.Linux),
            "windows" => RuntimeInformation.IsOSPlatform(OSPlatform.Windows),
            "macos" => RuntimeInformation.IsOSPlatform(OSPlatform.OSX),
            _ => false
        };
        var architectureMatches = expectedArchitecture switch
        {
            "x64" => RuntimeInformation.ProcessArchitecture == Architecture.X64,
            "arm64" => RuntimeInformation.ProcessArchitecture == Architecture.Arm64,
            _ => false
        };
        if (!osMatches || !architectureMatches)
            throw new InvalidOperationException(
                $"Expected {expectedOs}/{expectedArchitecture}, observed " +
                $"{RuntimeInformation.OSDescription}/{RuntimeInformation.ProcessArchitecture}.");
        Console.WriteLine($"PdfCube.IO host smoke passed: {expectedOs}/{expectedArchitecture}.");
    }

    private static sbyte[] Bytes(params int[] values) =>
        values.Select(value => unchecked((sbyte)value)).ToArray();

    private static string Unsigned(IEnumerable<sbyte> bytes) =>
        string.Join(",", bytes.Select(value => unchecked((byte)value)));

    private static string Join(params object[] values) =>
        string.Join(
            "|",
            values.Select(value =>
                Convert.ToString(value, CultureInfo.InvariantCulture)!.ToLowerInvariant()));

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (EndOfStreamException)
        {
            return "eof";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (Exception)
        {
            return "other";
        }
    }

    private static void Observe(string family, string id, string value) =>
        Observations.Add($"{family}\t{id}\t{value}");
}
