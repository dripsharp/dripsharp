#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using DripSharp.Runtime;

namespace DripSharp.Validation.JavaCompatProof;

internal static class Program
{
    private const string Header = "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1";

    private sealed record Provenance(
        string CompatType,
        string JdkContract,
        string Targets,
        string[] ProofRows);

    private sealed record Observation(
        string Family,
        string Id,
        string Value);

    private sealed class ControlledInputStream : Stream
    {
        private readonly byte[] data;
        private readonly int maxChunk;
        private readonly int failurePosition;
        private int position;

        internal ControlledInputStream(sbyte[] bytes, int maxChunk, int failurePosition)
        {
            if (maxChunk <= 0 || failurePosition < -1) throw new ArgumentOutOfRangeException();
            data = bytes.Select(value => unchecked((byte)value)).ToArray();
            this.maxChunk = maxChunk;
            this.failurePosition = failurePosition;
        }

        internal bool Closed { get; private set; }
        internal int Cursor => position;
        internal int Remaining => Math.Max(0, data.Length - position);
        public override bool CanRead => !Closed;
        public override bool CanSeek => true;
        public override bool CanWrite => false;
        public override long Length { get { ThrowIfClosed(); return data.Length; } }
        public override long Position
        {
            get { ThrowIfClosed(); return position; }
            set
            {
                ThrowIfClosed();
                if (value < 0 || value > data.Length) throw new IOException("Invalid controlled position");
                position = checked((int)value);
            }
        }
        public override int Read(byte[] buffer, int offset, int count)
        {
            ThrowIfClosed();
            ArgumentNullException.ThrowIfNull(buffer);
            if (offset < 0 || count < 0 || offset > buffer.Length - count)
                throw new ArgumentOutOfRangeException();
            if (count == 0) return 0;
            if (failurePosition >= 0 && position >= failurePosition)
                throw new IOException("Injected read failure");
            if (position >= data.Length) return 0;
            var untilFailure = failurePosition < 0 ? int.MaxValue : failurePosition - position;
            var actual = Math.Min(count, Math.Min(maxChunk, Math.Min(Remaining, untilFailure)));
            if (actual <= 0) throw new IOException("Injected read failure");
            Array.Copy(data, position, buffer, offset, actual);
            position += actual;
            return actual;
        }
        public override int ReadByte()
        {
            var one = new byte[1];
            return Read(one, 0, 1) == 0 ? -1 : one[0];
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin)
        {
            var target = origin switch
            {
                SeekOrigin.Begin => offset,
                SeekOrigin.Current => position + offset,
                SeekOrigin.End => data.Length + offset,
                _ => throw new ArgumentOutOfRangeException(nameof(origin))
            };
            Position = Math.Min(data.Length, target);
            return position;
        }
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { Closed = true; base.Dispose(disposing); }
        private void ThrowIfClosed() { if (Closed) throw new IOException("Controlled input is closed"); }
    }

    private sealed class ProbeFilterInputStream : JavaFilterInputStream
    {
        internal ProbeFilterInputStream(Stream input) : base(input) { }
    }

    private sealed class ControlledOutputStream : Stream
    {
        private readonly MemoryStream bytes = new();
        private readonly int failurePosition;
        internal ControlledOutputStream(int failurePosition)
        {
            if (failurePosition < -1) throw new ArgumentOutOfRangeException(nameof(failurePosition));
            this.failurePosition = failurePosition;
        }
        internal bool Closed { get; private set; }
        internal int Flushes { get; private set; }
        internal byte[] Bytes => bytes.ToArray();
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => !Closed;
        public override long Length => bytes.Length;
        public override long Position { get => bytes.Position; set => throw new NotSupportedException(); }
        public override void Flush() { ThrowIfClosed(); Flushes++; }
        public override void Write(byte[] buffer, int offset, int count)
        {
            ThrowIfClosed();
            ArgumentNullException.ThrowIfNull(buffer);
            if (offset < 0 || count < 0 || offset > buffer.Length - count)
                throw new ArgumentOutOfRangeException();
            for (var index = 0; index < count; index++)
            {
                if (failurePosition >= 0 && bytes.Length >= failurePosition)
                    throw new IOException("Injected write failure");
                bytes.WriteByte(buffer[offset + index]);
            }
        }
        public override void WriteByte(byte value)
        {
            var one = new[] { value };
            Write(one, 0, 1);
        }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { Closed = true; base.Dispose(disposing); }
        private void ThrowIfClosed() { if (Closed) throw new IOException("Controlled output is closed"); }
    }

    private sealed class ProbeFilterOutputStream : JavaFilterOutputStream
    {
        internal ProbeFilterOutputStream(Stream output) : base(output) { }
    }

    private sealed record InputCase(Stream Stream, ControlledInputStream? Controlled, int ViewLength)
    {
        internal sbyte[]? LastBuffer { get; set; }
        internal bool CloseInvoked { get; set; }
    }

    private sealed record OutputCase(Stream Stream, ControlledOutputStream? Controlled)
    {
        internal bool CloseInvoked { get; set; }
    }

    private sealed record RandomCase(JavaRandomAccessFile File, string Path)
    {
        internal sbyte[]? LastBuffer { get; set; }
        internal bool Closed { get; set; }
    }

    private sealed class BufferCase : IDisposable
    {
        internal BufferCase(sbyte[] bytes)
        {
            Bytes = bytes;
            Views["root"] = JavaByteBuffer.wrap(bytes);
        }
        internal sbyte[] Bytes { get; }
        internal Dictionary<string, JavaByteBuffer> Views { get; } = new(StringComparer.Ordinal);
        internal string ActiveName { get; set; } = "root";
        internal JavaByteBuffer Active => Views[ActiveName];
        public void Dispose()
        {
            foreach (var view in Views.Values) view.Dispose();
        }
    }

    private sealed class DataCase : IDisposable
    {
        internal DataCase(sbyte[] bytes)
        {
            InputBytes = bytes;
            InputStream = JavaCompat.NewMemoryStream(bytes, 0, bytes.Length);
            Input = new JavaDataInputStream(InputStream);
            OutputStream = new JavaByteArrayOutputStream();
            Output = new JavaDataOutputStream(OutputStream);
        }
        internal sbyte[] InputBytes { get; }
        internal MemoryStream InputStream { get; }
        internal JavaDataInputStream Input { get; }
        internal JavaByteArrayOutputStream OutputStream { get; }
        internal JavaDataOutputStream Output { get; }
        internal sbyte[]? LastBuffer { get; set; }
        public void Dispose()
        {
            Input.Dispose();
            Output.Dispose();
        }
    }

    private sealed class CompressionCase
    {
        internal MemoryStream Output { get; } = new();
        internal string Stage { get; set; } = "created";
        internal List<int> OutputAfterWrites { get; } = new();
        internal bool CompareTiming { get; set; } = true;
    }

    private static int Main(string[] arguments)
    {
        try
        {
            if (arguments is ["--runtime-version"])
            {
                Console.WriteLine(System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription);
                return 0;
            }
            if (arguments is ["--cases", var inputFile, var resultFile])
            {
                RunCases(inputFile, resultFile);
                return 0;
            }
            if (arguments is ["--list-types"])
            {
                foreach (var type in RuntimeTypes().OrderBy(
                             TypeName, StringComparer.Ordinal))
                {
                    Console.WriteLine(TypeName(type));
                }

                return 0;
            }

            if (arguments is not [var output, var provenanceFile])
                throw new ArgumentException(
                    "Usage: DripSharp.JavaCompat.DirectProbe " +
                    "<output.tsv> <TypeProvenance.tsv>");

            var provenance = ReadProvenance(provenanceFile);
            ValidateRuntimeInventory(provenance);
            var observations = TypeObservations(provenance)
                .Concat(BehaviorObservations())
                .OrderBy(row => row.Family, StringComparer.Ordinal)
                .ThenBy(row => row.Id, StringComparer.Ordinal)
                .ToArray();
            ValidateProofRows(provenance, observations);
            WriteObservations(output, observations);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static object? CaseValue(string token)
    {
        if (token == "n:") return null;
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Ab:(?:[0-9a-f]{2})*\z"))
            return Convert.FromHexString(token[2..]).Select(b => unchecked((sbyte)b)).ToArray();
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Ai:(?:0|-?[1-9][0-9]*)\z"))
            return System.Numerics.BigInteger.Parse(token[2..], CultureInfo.InvariantCulture);
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Au:(?:[0-9a-f]{4})*\z"))
        {
            var value = new StringBuilder();
            for (var i = 2; i < token.Length; i += 4)
                value.Append((char)ushort.Parse(token.Substring(i, 4), NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture));
            return value.ToString();
        }
        throw new InvalidDataException("Invalid case value: " + token);
    }

    private static string StringToken(string? value) => value is null
        ? "n:"
        : "u:" + string.Concat(value.Select(character =>
            ((int)character).ToString("x4", CultureInfo.InvariantCulture)));

    private static int IntValue(object? value) => checked((int)(System.Numerics.BigInteger)value!);
    private static long LongValue(object? value) => checked((long)(System.Numerics.BigInteger)value!);
    private static string Hex(IEnumerable<sbyte> values) =>
        Convert.ToHexString(values.Select(value => unchecked((byte)value)).ToArray()).ToLowerInvariant();
    private static string Hex(IEnumerable<byte> values) =>
        Convert.ToHexString(values.ToArray()).ToLowerInvariant();
    private static string BufferResult(int count, sbyte[] buffer) =>
        StringToken(count.ToString(CultureInfo.InvariantCulture) + "|" + Hex(buffer));

    private static string InputState(InputCase? input)
    {
        if (input is null) return "n:";
        var buffer = input.LastBuffer is null ? "-" : Hex(input.LastBuffer);
        if (input.Controlled is { } controlled)
            return StringToken(string.Join("|", controlled.Cursor, controlled.Remaining,
                controlled.Closed.ToString().ToLowerInvariant(), buffer));
        var available = JavaCompat.InputStreamAvailable(input.Stream);
        return StringToken(string.Join("|", input.ViewLength - available, available,
            input.CloseInvoked.ToString().ToLowerInvariant(), buffer));
    }

    private static string OutputState(OutputCase? output)
    {
        if (output is null) return "n:";
        if (output.Controlled is { } controlled)
            return StringToken(string.Join("|", controlled.Bytes.Length, Hex(controlled.Bytes),
                controlled.Closed.ToString().ToLowerInvariant(), controlled.Flushes));
        var bytes = ((JavaByteArrayOutputStream)output.Stream).ToArray();
        return StringToken(string.Join("|", bytes.Length, Hex(bytes),
            output.CloseInvoked.ToString().ToLowerInvariant()));
    }

    private static string RandomState(RandomCase? random)
    {
        if (random is null) return "n:";
        var pointer = random.Closed ? "closed" : random.File.getFilePointer().ToString(CultureInfo.InvariantCulture);
        var bytes = File.ReadAllBytes(random.Path);
        var buffer = random.LastBuffer is null ? "-" : Hex(random.LastBuffer);
        return StringToken(string.Join("|", pointer, bytes.Length, Hex(bytes), buffer));
    }

    private static string BufferState(BufferCase? buffer)
    {
        if (buffer is null) return "n:";
        var views = buffer.Views.OrderBy(entry => entry.Key, StringComparer.Ordinal)
            .Select(entry => string.Join(",", entry.Key, entry.Value.position(), entry.Value.limit(),
                entry.Value.capacity(), entry.Value.remaining(),
                entry.Value.order() == JavaByteOrder.BigEndian ? "big" : "little",
                entry.Value.isReadOnly().ToString().ToLowerInvariant()));
        return StringToken(string.Join("|", Hex(buffer.Bytes), buffer.ActiveName,
            string.Join(";", views)));
    }

    private static string DataState(DataCase? data)
    {
        if (data is null) return "n:";
        var output = data.OutputStream.ToArray();
        return StringToken(string.Join("|", data.InputStream.Position, data.InputBytes.Length,
            Hex(output), data.LastBuffer is null ? "-" : Hex(data.LastBuffer)));
    }

    private static string CompressionState(CompressionCase? compression)
    {
        if (compression is null) return "n:";
        var bytes = compression.Output.ToArray();
        var signed = bytes.Select(value => unchecked((sbyte)value)).ToArray();
        var crc = new JavaCrc32();
        crc.Update(signed, 0, signed.Length);
        var values = new List<string>
        {
            Hex(bytes), JavaZlib.Adler32(bytes).ToString(CultureInfo.InvariantCulture),
            crc.GetValue().ToString(CultureInfo.InvariantCulture)
        };
        if (compression.CompareTiming)
        {
            values.Add(compression.Stage);
            values.Add(string.Join(",", compression.OutputAfterWrites));
        }
        return StringToken(string.Join("|", values));
    }

    private static sbyte[] RunCompressionRoundTrip(
        sbyte[] payload, int encodeChunk, int flushEvery, int decodeChunk,
        string sequence, CompressionCase observation)
    {
        if (payload.Length > 8192 || encodeChunk <= 0 || decodeChunk <= 0 ||
            flushEvery < 0 || sequence is not ("close" or "flush-close"))
            throw new ArgumentException("Invalid bounded compression case");
        observation.CompareTiming = false;

        using var encoded = new MemoryStream();
        var deflater = new JavaDeflater(JavaDeflater.DEFAULT_COMPRESSION);
        using (var compressor = new JavaDeflaterOutputStream(encoded, deflater))
        {
            var writes = 0;
            for (var offset = 0; offset < payload.Length; offset += encodeChunk)
            {
                var count = Math.Min(encodeChunk, payload.Length - offset);
                compressor.Write(payload, offset, count);
                writes++;
                if (flushEvery > 0 && writes % flushEvery == 0)
                    compressor.Flush();
            }
        }
        deflater.End();
        return RunCompressionDecode(
            encoded.ToArray().Select(value => unchecked((sbyte)value)).ToArray(),
            decodeChunk, sequence, observation);
    }

    private static sbyte[] RunCompressionDecode(
        sbyte[] encoded, int chunk, string sequence, CompressionCase observation)
    {
        if (encoded.Length > 16384 || chunk <= 0 ||
            sequence is not ("close" or "flush-close" or "write-byte-close"))
            throw new ArgumentException("Invalid bounded compression case");

        var inflater = new JavaInflaterOutputStream(observation.Output);
        if (sequence == "write-byte-close")
        {
            for (var offset = 0; offset < encoded.Length; offset++)
            {
                observation.Stage = "write-" + offset.ToString(CultureInfo.InvariantCulture);
                inflater.WriteByte(unchecked((byte)encoded[offset]));
                observation.OutputAfterWrites.Add(checked((int)observation.Output.Length));
            }
        }
        for (var offset = 0; sequence != "write-byte-close" && offset < encoded.Length; offset += chunk)
        {
            observation.Stage = "write-" + offset.ToString(CultureInfo.InvariantCulture);
            var count = Math.Min(chunk, encoded.Length - offset);
            var bytes = encoded
                .Skip(offset).Take(count)
                .Select(value => unchecked((byte)value)).ToArray();
            inflater.Write(bytes, 0, bytes.Length);
            observation.OutputAfterWrites.Add(checked((int)observation.Output.Length));
            if (sequence == "flush-close")
            {
                observation.Stage = "flush-" + offset.ToString(CultureInfo.InvariantCulture);
                inflater.Flush();
            }
        }
        observation.Stage = "close";
        inflater.Dispose();
        observation.Stage = "closed";
        return observation.Output.ToArray()
            .Select(value => unchecked((sbyte)value)).ToArray();
    }

    private static string RunInflate(
        sbyte[] encoded, int feed, int outputSize, string mode, CompressionCase observation)
    {
        if (encoded.Length > 65536 || feed <= 0 || outputSize <= 0 || outputSize > 65536 ||
            mode is not ("raw" or "zlib"))
            throw new ArgumentException("Invalid bounded inflate case");
        observation.CompareTiming = false;
        var inflater = new JavaInflater(mode == "raw");
        var trace = new List<string>();
        var output = new sbyte[outputSize];
        var offset = 0;
        try
        {
            for (var step = 0; step < 100000; step++)
            {
                if (inflater.Finished())
                {
                    trace.Add("F");
                    break;
                }
                if (inflater.NeedsInput())
                {
                    if (offset >= encoded.Length)
                    {
                        trace.Add("I");
                        break;
                    }
                    var part = Math.Min(feed, encoded.Length - offset);
                    inflater.SetInput(encoded, offset, part);
                    offset += part;
                }
                Array.Clear(output, 0, output.Length);
                int count;
                try
                {
                    count = inflater.Inflate(output);
                }
                catch (InvalidDataException)
                {
                    var prefix = output.Length;
                    while (prefix > 0 && output[prefix - 1] == 0) prefix--;
                    var crc = new JavaCrc32();
                    crc.Update(output, 0, output.Length);
                    trace.Add("E" + prefix.ToString(CultureInfo.InvariantCulture) + ":" +
                              crc.GetValue().ToString(CultureInfo.InvariantCulture));
                    break;
                }
                observation.Output.Write(
                    output.Select(value => unchecked((byte)value)).ToArray(), 0, count);
                if (count == 0 && !inflater.NeedsInput() && !inflater.Finished())
                {
                    trace.Add("S");
                    break;
                }
                trace.Add(count.ToString(CultureInfo.InvariantCulture) +
                          (inflater.Finished() ? "f" : "") + (inflater.NeedsInput() ? "n" : ""));
            }
        }
        finally
        {
            inflater.End();
        }
        return offset.ToString(CultureInfo.InvariantCulture) + "|" + string.Join(",", trace);
    }

    private static string FileInspection(JavaFile file) => string.Join("\u0001",
        file.Pathname, file.Name, file.Parent ?? "\u0002",
        file.IsAbsolute.ToString().ToLowerInvariant(), file.Exists.ToString().ToLowerInvariant(),
        file.IsFile.ToString().ToLowerInvariant(), file.IsDirectory.ToString().ToLowerInvariant(),
        JavaCompat.FileCanRead(file).ToString().ToLowerInvariant(),
        JavaCompat.FileCanWrite(file).ToString().ToLowerInvariant(),
        JavaCompat.FileIsHidden(file).ToString().ToLowerInvariant(),
        file.IsFile ? file.Length.ToString(CultureInfo.InvariantCulture) : "\u0002",
        file.GetHashCode().ToString(CultureInfo.InvariantCulture));

    private static string FileCaseRoot(string input, string caseId)
    {
        var parent = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(input))!, "file-sandboxes");
        var root = Path.Combine(parent, caseId);
        Directory.CreateDirectory(Path.Combine(root, "dir"));
        Directory.CreateDirectory(Path.Combine(root, "empty-dir"));
        Directory.CreateDirectory(Path.Combine(root, "unicode-\u00e9"));
        Directory.CreateDirectory(Path.Combine(root, ".hidden"));
        File.WriteAllText(Path.Combine(root, "file.txt"), "abc", new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(root, "unicode-\u00e9", "\u96ea.txt"), "snow", new UTF8Encoding(false));
        return root;
    }

    private static void RunCases(string input, string output)
    {
        var lines = File.ReadAllLines(input, Encoding.UTF8);
        if (lines.Length < 2 || lines[0] != "DRIPSHARP_DIFFERENTIAL_CASES_V1")
            throw new InvalidDataException("Invalid cases header or empty input");
        var results = new List<string> { "DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1" };
        var buffers = new Dictionary<string, BufferCase>();
        var dataCases = new Dictionary<string, DataCase>();
        var inputs = new Dictionary<string, InputCase>();
        var outputs = new Dictionary<string, OutputCase>();
        var randoms = new Dictionary<string, RandomCase>();
        var identities = new HashSet<string>();
        var sandboxParent = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(input))!, "file-sandboxes");
        if (Directory.Exists(sandboxParent)) Directory.Delete(sandboxParent, true);
        try
        {
            foreach (var line in lines.Skip(1))
            {
                var fields = line.Split('\t');
                if (fields.Length < 5) throw new InvalidDataException("Malformed case");
                if (fields.Take(5).Any(field => !System.Text.RegularExpressions.Regex.IsMatch(
                        field, @"\A[A-Za-z0-9_.-]+\z")))
                    throw new InvalidDataException("Invalid case identity");
                var key = fields[0] + "\t" + fields[1];
                if (!identities.Add(key + "\t" + fields[2]))
                    throw new InvalidDataException("Duplicate case step");
                var operation = fields[0] + "/" + fields[3];
                var arity = operation switch
                {
                    "floating/run" => 3,
                    "collections/run" => 2,
                    "charset/decode" or "charset/stream" or "charset/string-ops" => 3,
                    "numeric/int-div" or "numeric/int-rem" or "numeric/long-div" or "numeric/long-rem" => 2,
                    "byte-buffer/wrap" or "string/hash" or "long/hash" => 1,
                    "byte-buffer/capacity" or "byte-buffer/position" or "byte-buffer/limit" or
                    "byte-buffer/remaining" or "byte-buffer/is-read-only" or
                    "byte-buffer/mark" or "byte-buffer/reset" or "byte-buffer/clear" or
                    "byte-buffer/flip" or "byte-buffer/rewind" or "byte-buffer/duplicate" or
                    "byte-buffer/slice" or "byte-buffer/read-only" or "byte-buffer/get" or
                    "byte-buffer/get-short" or "byte-buffer/get-int" or "byte-buffer/get-long" => 0,
                    "byte-buffer/set-position" or "byte-buffer/set-limit" or "byte-buffer/select" or
                    "byte-buffer/order" or "byte-buffer/get-at" or "byte-buffer/get-short-at" or
                    "byte-buffer/get-int-at" or "byte-buffer/get-long-at" or "byte-buffer/put" or
                    "byte-buffer/put-short" or "byte-buffer/put-int" or "byte-buffer/put-long" => 1,
                    "byte-buffer/put-at" or "byte-buffer/put-short-at" or
                    "byte-buffer/put-int-at" or "byte-buffer/put-long-at" => 2,
                    "binary-data/open" => 1,
                    "binary-data/read-byte" or "binary-data/read-unsigned-byte" or
                    "binary-data/read-short" or "binary-data/read-unsigned-short" or
                    "binary-data/read-int" or "binary-data/read-long" or
                    "binary-data/flush" => 0,
                    "binary-data/write-byte" or "binary-data/write-short" or
                    "binary-data/write-int" or "binary-data/write-long" => 1,
                    "binary-data/read-fully" or "binary-data/write-buffer" => 3,
                    "compression/roundtrip" => 5,
                    "compression/decode" => 3,
                    "compression/inflate" => 4,
                    "byte-array-input/open" => 3,
                    "filter-input/open" => 3,
                    "byte-array-input/read-buffer" or "filter-input/read-buffer" => 3,
                    "byte-array-input/skip" or "filter-input/skip" or
                    "byte-array-input/mark" or "filter-input/mark" => 1,
                    "byte-array-input/read-one" or "filter-input/read-one" or
                    "byte-array-input/available" or "filter-input/available" or
                    "byte-array-input/mark-supported" or "filter-input/mark-supported" or
                    "byte-array-input/reset" or "filter-input/reset" or
                    "byte-array-input/close" or "filter-input/close" => 0,
                    "byte-array-output/open" => 1,
                    "filter-output/open" => 1,
                    "byte-array-output/write-one" or "filter-output/write-one" => 1,
                    "byte-array-output/write-buffer" or "filter-output/write-buffer" => 3,
                    "byte-array-output/size" or "byte-array-output/reset" or
                    "byte-array-output/close" or "filter-output/flush" or
                    "filter-output/close" => 0,
                    "random-access/open" => 2,
                    "random-access/read-buffer" => 3,
                    "random-access/read-fully" or "random-access/write-buffer" => 1,
                    "random-access/seek" or "random-access/set-length" or
                    "random-access/skip" => 1,
                    "random-access/read-one" or "random-access/length" or
                    "random-access/position" or "random-access/close" => 0,
                    "file/inspect" or "file/absolute" or "file/uri" or "file/to-path" or
                    "file/create" or "file/delete" or "file/open" or "file/list-count" => 1,
                    "file/parent-child" or "file/file-parent-child" or "file/equals" => 2,
                    _ => throw new InvalidDataException("Unknown operation: " + operation)
                };
                if (fields.Length != 5 + arity)
                    throw new InvalidDataException("Wrong argument count");
                var values = fields.Skip(5).Select(CaseValue).ToArray();
                if (operation == "byte-buffer/wrap" && values[0] is not null and not sbyte[] ||
                    operation == "binary-data/open" && values[0] is not sbyte[] ||
                    operation.StartsWith("compression/", StringComparison.Ordinal) &&
                    (values[0] is not sbyte[] || values[^1] is not string) ||
                    operation == "charset/decode" &&
                    (values[0] is not string || values[1] is not System.Numerics.BigInteger ||
                     values[2] is not string) ||
                    operation == "charset/stream" && values.Any(value => value is not string) ||
                    operation == "charset/string-ops" &&
                    (values[0] is not string || values[1] is not System.Numerics.BigInteger ||
                     values[2] is not System.Numerics.BigInteger) ||
                    operation == "string/hash" && values[0] is not null and not string ||
                    operation == "long/hash" && values[0] is not System.Numerics.BigInteger ||
                    operation is "byte-array-input/open" or "filter-input/open" &&
                    values[0] is not sbyte[] ||
                    operation.StartsWith("file/", StringComparison.Ordinal) &&
                    values.Any(value => value is not null and not string))
                    throw new InvalidDataException("Wrong argument type");
                var number = operation == "long/hash" ? (long)(System.Numerics.BigInteger)values[0]! : 0;
                var numericLeft = operation.StartsWith("numeric/", StringComparison.Ordinal) ? (long)(System.Numerics.BigInteger)values[0]! : 0;
                var numericRight = operation.StartsWith("numeric/", StringComparison.Ordinal) ? (long)(System.Numerics.BigInteger)values[1]! : 0;
                var caseRoot = operation.StartsWith("file/", StringComparison.Ordinal)
                    ? FileCaseRoot(input, fields[1]) : null;
                for (var index = 0; index < values.Length; index++)
                    if (values[index] is string text)
                        values[index] = text.Replace("$ROOT", caseRoot ?? "$ROOT", StringComparison.Ordinal);
                var result = "n:";
                var exception = "none";
                buffers.TryGetValue(key, out var buffer);
                dataCases.TryGetValue(key, out var dataCase);
                inputs.TryGetValue(key, out var inputCase);
                outputs.TryGetValue(key, out var outputCase);
                randoms.TryGetValue(key, out var randomCase);
                CompressionCase? compressionCase = null;
                try
                {
                    switch (operation)
                    {
                        case "floating/run": result = StringToken(FloatingProbe.Run((string)values[0]!, (string)values[1]!, (string)values[2]!)); break;
                        case "collections/run": result = StringToken(CollectionProbe.Run(IntValue(values[0]), (string)values[1]!)); break;
                        case "charset/decode": result = StringToken(CharsetProbe.Decode(
                            (string)values[0]!, IntValue(values[1]), (string)values[2]!)); break;
                        case "charset/stream": result = StringToken(CharsetProbe.Stream(
                            (string)values[0]!, (string)values[1]!, (string)values[2]!)); break;
                        case "charset/string-ops": result = StringToken(CharsetProbe.StringOps(
                            (string)values[0]!, IntValue(values[1]), IntValue(values[2]))); break;
                        case "numeric/int-div": result = "i:" + JavaCompat.IntegralDivide(unchecked((int)numericLeft), unchecked((int)numericRight)).ToString(CultureInfo.InvariantCulture); break;
                        case "numeric/int-rem": result = "i:" + JavaCompat.IntegralRemainder(unchecked((int)numericLeft), unchecked((int)numericRight)).ToString(CultureInfo.InvariantCulture); break;
                        case "numeric/long-div": result = "i:" + JavaCompat.IntegralDivide(numericLeft, numericRight).ToString(CultureInfo.InvariantCulture); break;
                        case "numeric/long-rem": result = "i:" + JavaCompat.IntegralRemainder(numericLeft, numericRight).ToString(CultureInfo.InvariantCulture); break;
                        case "byte-buffer/wrap":
                            var replacement = new BufferCase((sbyte[])values[0]!);
                            buffer?.Dispose();
                            buffers[key] = buffer = replacement;
                            break;
                        case "byte-buffer/capacity": result = "i:" + buffer!.Active.capacity(); break;
                        case "byte-buffer/position": result = "i:" + buffer!.Active.position(); break;
                        case "byte-buffer/limit": result = "i:" + buffer!.Active.limit(); break;
                        case "byte-buffer/remaining": result = "i:" + buffer!.Active.remaining(); break;
                        case "byte-buffer/is-read-only": result = "i:" + (buffer!.Active.isReadOnly() ? 1 : 0); break;
                        case "byte-buffer/set-position": buffer!.Active.position(IntValue(values[0])); result = "i:1"; break;
                        case "byte-buffer/set-limit": buffer!.Active.limit(IntValue(values[0])); result = "i:1"; break;
                        case "byte-buffer/mark": buffer!.Active.mark(); result = "i:1"; break;
                        case "byte-buffer/reset": buffer!.Active.reset(); result = "i:1"; break;
                        case "byte-buffer/clear": buffer!.Active.clear(); result = "i:1"; break;
                        case "byte-buffer/flip": buffer!.Active.flip(); result = "i:1"; break;
                        case "byte-buffer/rewind": buffer!.Active.rewind(); result = "i:1"; break;
                        case "byte-buffer/duplicate": buffer!.Views["view"] = buffer.Active.duplicate(); buffer.ActiveName = "view"; result = "i:1"; break;
                        case "byte-buffer/slice": buffer!.Views["view"] = buffer.Active.slice(); buffer.ActiveName = "view"; result = "i:1"; break;
                        case "byte-buffer/read-only": buffer!.Views["view"] = buffer.Active.asReadOnlyBuffer(); buffer.ActiveName = "view"; result = "i:1"; break;
                        case "byte-buffer/select": buffer!.ActiveName = (string)values[0]!; _ = buffer.Active; break;
                        case "byte-buffer/order": buffer!.Active.order((string)values[0]! == "big" ? JavaByteOrder.BigEndian : JavaByteOrder.LittleEndian); result = "i:1"; break;
                        case "byte-buffer/get": result = "i:" + buffer!.Active.get(); break;
                        case "byte-buffer/get-at": result = "i:" + buffer!.Active.get(IntValue(values[0])); break;
                        case "byte-buffer/get-short": result = "i:" + buffer!.Active.getShort(); break;
                        case "byte-buffer/get-short-at": result = "i:" + buffer!.Active.getShort(IntValue(values[0])); break;
                        case "byte-buffer/get-int": result = "i:" + buffer!.Active.getInt(); break;
                        case "byte-buffer/get-int-at": result = "i:" + buffer!.Active.getInt(IntValue(values[0])); break;
                        case "byte-buffer/get-long": result = "i:" + buffer!.Active.getLong(); break;
                        case "byte-buffer/get-long-at": result = "i:" + buffer!.Active.getLong(IntValue(values[0])); break;
                        case "byte-buffer/put": buffer!.Active.put(unchecked((sbyte)IntValue(values[0]))); result = "i:1"; break;
                        case "byte-buffer/put-at": buffer!.Active.put(IntValue(values[0]), unchecked((sbyte)IntValue(values[1]))); result = "i:1"; break;
                        case "byte-buffer/put-short": buffer!.Active.putShort(unchecked((short)IntValue(values[0]))); result = "i:1"; break;
                        case "byte-buffer/put-short-at": buffer!.Active.putShort(IntValue(values[0]), unchecked((short)IntValue(values[1]))); result = "i:1"; break;
                        case "byte-buffer/put-int": buffer!.Active.putInt(IntValue(values[0])); result = "i:1"; break;
                        case "byte-buffer/put-int-at": buffer!.Active.putInt(IntValue(values[0]), IntValue(values[1])); result = "i:1"; break;
                        case "byte-buffer/put-long": buffer!.Active.putLong(LongValue(values[0])); result = "i:1"; break;
                        case "byte-buffer/put-long-at": buffer!.Active.putLong(IntValue(values[0]), LongValue(values[1])); result = "i:1"; break;
                        case "binary-data/open":
                            dataCase?.Dispose();
                            dataCases[key] = dataCase = new DataCase((sbyte[])values[0]!);
                            break;
                        case "binary-data/read-byte": result = "i:" + dataCase!.Input.readByte(); break;
                        case "binary-data/read-unsigned-byte": result = "i:" + dataCase!.Input.readUnsignedByte(); break;
                        case "binary-data/read-short": result = "i:" + dataCase!.Input.readShort(); break;
                        case "binary-data/read-unsigned-short": result = "i:" + dataCase!.Input.readUnsignedShort(); break;
                        case "binary-data/read-int": result = "i:" + dataCase!.Input.readInt(); break;
                        case "binary-data/read-long": result = "i:" + dataCase!.Input.readLong(); break;
                        case "binary-data/read-fully":
                            dataCase!.LastBuffer = ((sbyte[])values[0]!).ToArray();
                            dataCase.Input.readFully(dataCase.LastBuffer, IntValue(values[1]), IntValue(values[2]));
                            result = StringToken(Hex(dataCase.LastBuffer));
                            break;
                        case "binary-data/write-byte": dataCase!.Output.writeByte(IntValue(values[0])); break;
                        case "binary-data/write-short": dataCase!.Output.writeShort(IntValue(values[0])); break;
                        case "binary-data/write-int": dataCase!.Output.writeInt(IntValue(values[0])); break;
                        case "binary-data/write-long": dataCase!.Output.writeLong(LongValue(values[0])); break;
                        case "binary-data/write-buffer": dataCase!.Output.write((sbyte[])values[0]!, IntValue(values[1]), IntValue(values[2])); break;
                        case "binary-data/flush": dataCase!.Output.flush(); break;
                        case "compression/roundtrip":
                            compressionCase = new CompressionCase();
                            result = "b:" + Hex(RunCompressionRoundTrip(
                                (sbyte[])values[0]!, IntValue(values[1]), IntValue(values[2]),
                                IntValue(values[3]), (string)values[4]!, compressionCase));
                            break;
                        case "compression/decode":
                            compressionCase = new CompressionCase();
                            result = "b:" + Hex(RunCompressionDecode(
                                (sbyte[])values[0]!, IntValue(values[1]),
                                (string)values[2]!, compressionCase));
                            break;
                        case "compression/inflate":
                            compressionCase = new CompressionCase();
                            result = StringToken(RunInflate(
                                (sbyte[])values[0]!, IntValue(values[1]), IntValue(values[2]),
                                (string)values[3]!, compressionCase));
                            break;
                        case "byte-array-input/open":
                            inputCase?.Stream.Dispose();
                            inputs[key] = inputCase = new InputCase(JavaCompat.NewMemoryStream(
                                (sbyte[])values[0]!, IntValue(values[1]), IntValue(values[2])), null,
                                IntValue(values[2]));
                            break;
                        case "filter-input/open":
                            inputCase?.Stream.Dispose();
                            var controlledInput = new ControlledInputStream(
                                (sbyte[])values[0]!, IntValue(values[1]), IntValue(values[2]));
                            inputs[key] = inputCase = new InputCase(
                                new ProbeFilterInputStream(controlledInput), controlledInput,
                                ((sbyte[])values[0]!).Length);
                            break;
                        case "byte-array-input/read-one":
                        case "filter-input/read-one":
                            var one = operation.StartsWith("filter-", StringComparison.Ordinal)
                                ? ((JavaFilterInputStream)inputCase!.Stream).Read()
                                : JavaCompat.InputStreamRead(inputCase!.Stream);
                            result = "i:" + one
                                .ToString(CultureInfo.InvariantCulture);
                            break;
                        case "byte-array-input/read-buffer":
                        case "filter-input/read-buffer":
                            inputCase!.LastBuffer = ((sbyte[])values[0]!).ToArray();
                            var read = operation.StartsWith("filter-", StringComparison.Ordinal)
                                ? ((JavaFilterInputStream)inputCase.Stream).Read(inputCase.LastBuffer,
                                    IntValue(values[1]), IntValue(values[2]))
                                : JavaCompat.InputStreamRead(inputCase.Stream, inputCase.LastBuffer,
                                    IntValue(values[1]), IntValue(values[2]));
                            result = BufferResult(read, inputCase.LastBuffer);
                            break;
                        case "byte-array-input/skip":
                        case "filter-input/skip":
                            var skipped = operation.StartsWith("filter-", StringComparison.Ordinal)
                                ? ((JavaFilterInputStream)inputCase!.Stream).Skip(LongValue(values[0]))
                                : JavaCompat.InputStreamSkip(inputCase!.Stream, LongValue(values[0]));
                            result = "i:" + skipped
                                .ToString(CultureInfo.InvariantCulture);
                            break;
                        case "byte-array-input/available":
                        case "filter-input/available":
                            var available = operation.StartsWith("filter-", StringComparison.Ordinal)
                                ? ((JavaFilterInputStream)inputCase!.Stream).Available()
                                : JavaCompat.InputStreamAvailable(inputCase!.Stream);
                            result = "i:" + available
                                .ToString(CultureInfo.InvariantCulture);
                            break;
                        case "byte-array-input/mark":
                        case "filter-input/mark":
                            if (operation.StartsWith("filter-", StringComparison.Ordinal))
                                ((JavaFilterInputStream)inputCase!.Stream).Mark(IntValue(values[0]));
                            else JavaCompat.InputStreamMark(inputCase!.Stream, IntValue(values[0]));
                            break;
                        case "byte-array-input/reset":
                        case "filter-input/reset":
                            if (operation.StartsWith("filter-", StringComparison.Ordinal))
                                ((JavaFilterInputStream)inputCase!.Stream).Reset();
                            else JavaCompat.InputStreamReset(inputCase!.Stream);
                            break;
                        case "byte-array-input/mark-supported":
                        case "filter-input/mark-supported":
                            var supported = operation.StartsWith("filter-", StringComparison.Ordinal)
                                ? ((JavaFilterInputStream)inputCase!.Stream).MarkSupported()
                                : JavaCompat.InputStreamMarkSupported(inputCase!.Stream);
                            result = "i:" + (supported ? "1" : "0");
                            break;
                        case "byte-array-input/close":
                        case "filter-input/close":
                            inputCase!.CloseInvoked = true;
                            inputCase.Stream.Dispose();
                            break;
                        case "byte-array-output/open":
                            outputCase?.Stream.Dispose();
                            outputs[key] = outputCase = new OutputCase(
                                new JavaByteArrayOutputStream(IntValue(values[0])), null);
                            break;
                        case "filter-output/open":
                            outputCase?.Stream.Dispose();
                            var controlledOutput = new ControlledOutputStream(IntValue(values[0]));
                            outputs[key] = outputCase = new OutputCase(
                                new ProbeFilterOutputStream(controlledOutput), controlledOutput);
                            break;
                        case "byte-array-output/write-one":
                        case "filter-output/write-one":
                            JavaCompat.OutputStreamWrite(outputCase!.Stream, IntValue(values[0]));
                            break;
                        case "byte-array-output/write-buffer":
                        case "filter-output/write-buffer":
                            JavaCompat.OutputStreamWrite(outputCase!.Stream, (sbyte[])values[0]!,
                                IntValue(values[1]), IntValue(values[2]));
                            break;
                        case "byte-array-output/size":
                            result = "i:" + ((JavaByteArrayOutputStream)outputCase!.Stream).Length
                                .ToString(CultureInfo.InvariantCulture);
                            break;
                        case "byte-array-output/reset":
                            JavaCompat.ResetMemoryStream((MemoryStream)outputCase!.Stream);
                            break;
                        case "filter-output/flush": outputCase!.Stream.Flush(); break;
                        case "byte-array-output/close":
                        case "filter-output/close":
                            outputCase!.CloseInvoked = true;
                            outputCase.Stream.Dispose();
                            break;
                        case "random-access/open":
                            if (randomCase is not null) randomCase.File.Dispose();
                            var randomPath = Path.Combine(FileCaseRoot(input, fields[1]), "random.bin");
                            File.WriteAllBytes(randomPath, ((sbyte[])values[0]!)
                                .Select(value => unchecked((byte)value)).ToArray());
                            randoms[key] = randomCase = new RandomCase(
                                new JavaRandomAccessFile(new JavaFile(randomPath), (string)values[1]!), randomPath);
                            break;
                        case "random-access/read-one":
                            result = "i:" + randomCase!.File.read().ToString(CultureInfo.InvariantCulture);
                            break;
                        case "random-access/read-buffer":
                            randomCase!.LastBuffer = ((sbyte[])values[0]!).ToArray();
                            var randomRead = randomCase.File.read(randomCase.LastBuffer,
                                IntValue(values[1]), IntValue(values[2]));
                            result = BufferResult(randomRead, randomCase.LastBuffer);
                            break;
                        case "random-access/read-fully":
                            randomCase!.LastBuffer = ((sbyte[])values[0]!).ToArray();
                            randomCase.File.readFully(randomCase.LastBuffer);
                            result = StringToken(Hex(randomCase.LastBuffer));
                            break;
                        case "random-access/write-buffer":
                            randomCase!.File.write((sbyte[])values[0]!);
                            break;
                        case "random-access/seek": randomCase!.File.seek(LongValue(values[0])); break;
                        case "random-access/skip":
                            result = "i:" + randomCase!.File.skipBytes(IntValue(values[0]))
                                .ToString(CultureInfo.InvariantCulture);
                            break;
                        case "random-access/length":
                            result = "i:" + randomCase!.File.length().ToString(CultureInfo.InvariantCulture);
                            break;
                        case "random-access/position":
                            result = "i:" + randomCase!.File.getFilePointer().ToString(CultureInfo.InvariantCulture);
                            break;
                        case "random-access/set-length": randomCase!.File.setLength(LongValue(values[0])); break;
                        case "random-access/close":
                            randomCase!.Closed = true;
                            randomCase.File.close();
                            break;
                        case "string/hash": result = "i:" + JavaCompat.StringHashCode((string)values[0]!).ToString(CultureInfo.InvariantCulture); break;
                        case "long/hash": result = "i:" + JavaCompat.LongHashCode(number).ToString(CultureInfo.InvariantCulture); break;
                        case "file/inspect": result = StringToken(FileInspection(new JavaFile((string)values[0]!))); break;
                        case "file/absolute": result = StringToken(new JavaFile((string)values[0]!).AbsolutePath); break;
                        case "file/parent-child": result = StringToken(new JavaFile((string?)values[0], (string)values[1]!).Pathname); break;
                        case "file/file-parent-child": result = StringToken(new JavaFile(
                            values[0] is null ? (JavaFile?)null : new JavaFile((string)values[0]!), (string)values[1]!).Pathname); break;
                        case "file/uri": result = StringToken(new JavaFile(new Uri((string)values[0]!, UriKind.RelativeOrAbsolute)).Pathname); break;
                        case "file/equals": result = "i:" + (new JavaFile((string)values[0]!).Equals(new JavaFile((string)values[1]!)) ? 1 : 0); break;
                        case "file/to-path": result = StringToken(JavaCompat.FileToPath(new JavaFile((string)values[0]!)).ToString()); break;
                        case "file/create": result = "i:" + (JavaCompat.FileCreateNewFile(new JavaFile((string)values[0]!)) ? 1 : 0); break;
                        case "file/delete": result = "i:" + (JavaCompat.FileDelete(new JavaFile((string)values[0]!)) ? 1 : 0); break;
                        case "file/open":
                            using (var stream = JavaCompat.OpenFileInput(new JavaFile((string)values[0]!)))
                                result = "i:" + stream.ReadByte().ToString(CultureInfo.InvariantCulture);
                            break;
                        case "file/list-count":
                            var children = new JavaFile((string)values[0]!).ListFiles();
                            result = children is null ? "n:" : "i:" + children.Length.ToString(CultureInfo.InvariantCulture);
                            break;
                    }
                }
                catch (DivideByZeroException) { exception = "arithmetic"; }
                catch (ArgumentNullException) { exception = "null-pointer"; }
                catch (NullReferenceException) { exception = "null-pointer"; }
                catch (EndOfStreamException) { exception = operation.StartsWith("random-access/", StringComparison.Ordinal) || operation.StartsWith("binary-data/", StringComparison.Ordinal) ? "eof" : operation.StartsWith("byte-buffer/put", StringComparison.Ordinal) ? "buffer-overflow" : "buffer-underflow"; }
                catch (InvalidOperationException) { exception = "invalid-mark"; }
                catch (NotSupportedException) { exception = "read-only"; }
                catch (FileNotFoundException) { exception = "file-not-found"; }
                catch (IndexOutOfRangeException) { exception = "index-out-of-bounds"; }
                catch (ArgumentOutOfRangeException) { exception = operation is "byte-buffer/set-position" or "byte-buffer/set-limit" ? "illegal-argument" : "index-out-of-bounds"; }
                catch (ArgumentException) { exception = operation == "file/to-path" ? "invalid-path" : "illegal-argument"; }
                catch (InvalidDataException) { exception = "io"; }
                catch (IOException) { exception = "io"; }
                var state = fields[0] switch
                {
                    "byte-array-input" or "filter-input" => InputState(inputCase),
                    "byte-array-output" or "filter-output" => OutputState(outputCase),
                    "random-access" => RandomState(randomCase),
                    "byte-buffer" => BufferState(buffer),
                    "binary-data" => DataState(dataCase),
                    "compression" => CompressionState(compressionCase),
                    _ => "n:"
                };
                results.Add(string.Join("\t", fields[0], fields[1], fields[2], fields[3],
                    exception, result, state));
            }
            File.WriteAllText(output, string.Join("\n", results) + "\n", new UTF8Encoding(false));
        }
        finally
        {
            foreach (var buffer in buffers.Values) buffer.Dispose();
            foreach (var data in dataCases.Values) data.Dispose();
            foreach (var stream in inputs.Values) stream.Stream.Dispose();
            foreach (var stream in outputs.Values) stream.Stream.Dispose();
            foreach (var random in randoms.Values) random.File.Dispose();
            if (Directory.Exists(sandboxParent)) Directory.Delete(sandboxParent, true);
        }
    }

    private static Type[] RuntimeTypes() =>
        typeof(JavaCompat).Assembly
            .GetTypes()
            .Where(type =>
                type.Namespace == "DripSharp.Runtime" &&
                type.DeclaringType is null)
            .ToArray();

    private static string TypeName(Type type) =>
        type.Name.Split('`')[0];

    private static Provenance[] ReadProvenance(string file)
    {
        var lines = File.ReadAllLines(file, Encoding.UTF8);
        if (lines.Length == 0 ||
            lines[0] != "compat-type\tjdk-contract\ttargets\tproof-rows")
        {
            throw new InvalidDataException(
                "JavaCompat provenance has the wrong header.");
        }

        return lines.Skip(1).Select((line, index) =>
        {
            var fields = line.Split('\t');
            if (fields.Length != 4 || fields.Any(string.IsNullOrWhiteSpace))
                throw new InvalidDataException(
                    $"Malformed JavaCompat provenance row {index + 2}.");
            return new Provenance(
                fields[0],
                fields[1],
                fields[2],
                fields[3].Split(',', StringSplitOptions.RemoveEmptyEntries));
        }).ToArray();
    }

    private static void ValidateRuntimeInventory(Provenance[] provenance)
    {
        var runtimeTypes = RuntimeTypes();
        var actual = runtimeTypes.Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        var expected = provenance.Select(row => row.CompatType)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (!actual.SequenceEqual(expected, StringComparer.Ordinal))
        {
            throw new InvalidDataException(
                "JavaCompat provenance does not exactly cover the compiled " +
                $"runtime inventory.\nExpected: {string.Join(", ", expected)}" +
                $"\nActual: {string.Join(", ", actual)}");
        }

        var publicTypes = runtimeTypes.Where(type => type.IsPublic)
            .Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (publicTypes.Length != 0)
        {
            throw new InvalidDataException(
                "The direct probe must retain package-internal JavaCompat " +
                $"visibility: {string.Join(", ", publicTypes)}");
        }
    }

    private static IEnumerable<Observation> TypeObservations(
        IEnumerable<Provenance> provenance) =>
        provenance.Select(row =>
            new Observation("type-contract", row.CompatType, "available"));

    private static void ValidateProofRows(
        IEnumerable<Provenance> provenance,
        IEnumerable<Observation> observations)
    {
        var identities = observations
            .Select(row => row.Family + "/" + row.Id)
            .ToHashSet(StringComparer.Ordinal);
        foreach (var row in provenance)
        {
            var requiredTypeRow = "type-contract/" + row.CompatType;
            if (!row.ProofRows.Contains(requiredTypeRow, StringComparer.Ordinal))
            {
                throw new InvalidDataException(
                    $"{row.CompatType} does not cite its direct type proof row.");
            }

            var missing = row.ProofRows
                .Where(proof => !identities.Contains(proof))
                .ToArray();
            if (missing.Length != 0)
            {
                throw new InvalidDataException(
                    $"{row.CompatType} cites missing proof rows: " +
                    string.Join(", ", missing));
            }
        }
    }

    private static IEnumerable<Observation> BehaviorObservations()
    {
        yield return Observe("atomic-primitives", AtomicPrimitives());
        yield return Observe("base64", Base64Contract());
        yield return Observe("bit-set", BitSetContract());
        yield return Observe("boxed-wrappers", BoxedWrappersContract());
        yield return Observe("byte-buffer", ByteBufferContract());
        yield return Observe("charset-malformed", CharsetMalformedContract());
        yield return Observe("collections", CollectionsContract());
        yield return Observe("compression", CompressionContract());
        yield return Observe("crc32", Crc32Contract());
        yield return Observe("data-output", DataOutputContract());
        yield return Observe("file-channel-read", FileChannelReadContract());
        yield return Observe("file-paths", FilePathsContract());
        yield return Observe("decimal-format", DecimalFormatContract());
        yield return Observe("message-digest", MessageDigestContract());
        yield return Observe("message-format", MessageFormatContract());
        yield return Observe("optional", OptionalContract());
        yield return Observe("regex", RegexContract());
        yield return Observe("signed-byte-or", SignedByteOrContract());
        yield return Observe("strict-math", StrictMathContract());
        yield return Observe("string-identity", StringIdentityContract());
        yield return Observe("string-tools", StringToolsContract());
        yield return Observe("time-format", TimeFormatContract());
        yield return Observe("uri", UriContract());
        yield return Observe("xpath", XPathContract());
    }

    private static Observation Observe(string id, string value) =>
        new("behavior", id, value);

    private static string FilePathsContract()
    {
        var values = new List<string>();
        foreach (var path in new[] { "", "missing-file-path", "a//b/", "a/../b", "\0", " " })
        {
            var file = new JavaFile(path);
            values.Add(Convert.ToBase64String(Encoding.UTF8.GetBytes(file.Pathname)));
            values.Add(Convert.ToBase64String(Encoding.UTF8.GetBytes(file.Name)));
            values.Add(file.Exists.ToString().ToLowerInvariant());
            values.Add(file.IsDirectory.ToString().ToLowerInvariant());
            values.Add(file.GetHashCode().ToString(CultureInfo.InvariantCulture));
        }
        foreach (string path in new[] { "/a/../b", "/a b", "/a#b", "/a%z", "/aé", "/a\0b" })
            values.Add(JavaCompat.UriToString(JavaCompat.FileToUri(new JavaFile(path))));
        foreach (string? parent in new string?[] { null, "", "a", "/" })
            values.Add(new JavaFile(parent, "/b").Pathname);
        try { _ = new JavaFile((string)null!); values.Add("accepted-null"); }
        catch (NullReferenceException) { values.Add("null-rejected"); }
        foreach (string path in new[] { "", "\0" })
        {
            try { _ = new JavaFile(path).OpenPath(); values.Add("opened"); }
            catch (FileNotFoundException) { values.Add("open-rejected"); }
            try { _ = new JavaFile(path).ToFileInfo(); throw new Exception("Unrepresentable file exported"); }
            catch (ArgumentException) { }
        }
        foreach (string uri in new[] { "file:/a/../b", "file:///a%20b", "file:/a%00b", "file:/", "file://host/a", "file:/a?x", "file:/a#x", "file:a", "/a" })
        {
            try { values.Add(new JavaFile(new Uri(uri, UriKind.RelativeOrAbsolute)).Pathname); }
            catch (ArgumentException) { values.Add("uri-rejected"); }
            catch (UriFormatException) { values.Add("uri-rejected"); }
        }
        foreach (string? parent in new string?[] { null, "", "a", "/" })
        {
            values.Add(new JavaFile(parent is null ? null : new JavaFile(parent), "b").Pathname);
            try { _ = new JavaFile(parent, (string)null!); values.Add("accepted-child"); }
            catch (NullReferenceException) { values.Add("child-rejected"); }
        }
        var empty = new JavaFile("");
        values.Add($"{Lower(JavaCompat.FileCanRead(empty))}:{Lower(JavaCompat.FileCanWrite(empty))}:{empty.Length}:{JavaCompat.FileLastModified(empty)}:{Lower(empty.ListFiles() is null)}");
        values.Add(JavaCompat.FileToPath(empty).ToString());
        try { _ = JavaCompat.FileToPath(new JavaFile("\0")); values.Add("path-accepted"); }
        catch (ArgumentException) { values.Add("path-rejected"); }
        try { _ = new JavaFile((Uri)null!); values.Add("accepted-null-uri"); }
        catch (NullReferenceException) { values.Add("null-uri-rejected"); }
        try { using var file = new JavaRandomAccessFile(new JavaFile(""), "bad"); values.Add("mode-accepted"); }
        catch (ArgumentException) { values.Add("mode-rejected"); }
        var command = OperatingSystem.IsWindows() ? new[] { "cmd.exe", "/c", "exit" } : new[] { "/bin/true" };
        try { using var process = new JavaProcessBuilder(command).Directory(new JavaFile("")).Start(); values.Add("process-started"); }
        catch (IOException) { values.Add("process-dir-rejected"); }
        string root = Path.Combine(Path.GetTempPath(), "dripsharp-file-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string spaced = Path.Combine(root, " ");
            Directory.CreateDirectory(spaced);
            string ordinary = Path.Combine(root, "ordinary");
            File.WriteAllText(ordinary, "abc");
            var directory = new JavaFile(spaced);
            var file = new JavaFile(ordinary);
            values.Add(directory.IsDirectory.ToString().ToLowerInvariant());
            values.Add(file.IsFile.ToString().ToLowerInvariant());
            values.Add(file.Length.ToString(CultureInfo.InvariantCulture));
            values.Add(new JavaFile(root).ListFiles()!.Length.ToString(CultureInfo.InvariantCulture));
            if (!JavaFile.FromFileInfo(file.ToFileInfo())!.Equals(file)) throw new Exception("FileInfo roundtrip failed");
        }
        finally { Directory.Delete(root, true); }
        return string.Join("|", values);
    }

    private static string AtomicPrimitives()
    {
        var boolean = new JavaAtomicBoolean();
        var integer = new JavaAtomicInteger(40);
        var first = new object();
        var second = new object();
        var reference = new JavaAtomicReference<object>(first);
        using var local = JavaThreadLocal<string>.WithInitial(() => "initial");
        var previous = boolean.GetAndSet(true);
        var swapped = boolean.CompareAndSet(true, false);
        local.Set("thread");
        return $"{Lower(previous)}|{Lower(swapped)}|" +
               $"{Lower(boolean.Get())}|{integer.IncrementAndGet()}|" +
               $"{Lower(ReferenceEquals(reference.GetAndSet(second), first))}|" +
               local.Get();
    }

    private static string Base64Contract()
    {
        var value = Encoding.UTF8.GetBytes("compat")
            .Select(item => unchecked((sbyte)item)).ToArray();
        return JavaBase64.GetEncoder().EncodeToString(value);
    }

    private static string BitSetContract()
    {
        var bits = new JavaBitSet();
        bits.set(2, 5);
        bits.clear(3);
        var trace = new StringBuilder();
        trace.Append(Bool(bits.get(2))).Append('|').Append(Bool(bits.get(3))).Append('|').Append(Int(bits.nextSetBit(3)));
        // Word boundaries, ranges inside one word and across words, empty ranges, growth.
        bits.set(63);
        bits.set(64);
        bits.set(127, 130);
        bits.set(200, 200);
        bits.set(1000);
        bits.set(320, 384);
        trace.Append('|').Append(BitSetRuns(bits));
        foreach (var from in new[] { 0, 5, 63, 64, 65, 130, 384, 1000, 1001, 5000 })
            trace.Append(',').Append(Int(bits.nextSetBit(from)));
        trace.Append(',').Append(Bool(bits.get(129))).Append(',').Append(Bool(bits.get(130)))
            .Append(',').Append(Bool(bits.get(5000))).Append(',').Append(Bool(bits.get(383)));
        // Clearing the highest bit shrinks the logical size; clearing past it is a no-op.
        bits.clear(1000);
        bits.clear(4000);
        bits.clear(64);
        bits.clear(383);
        trace.Append('|').Append(BitSetRuns(bits)).Append(',').Append(Int(bits.nextSetBit(383)))
            .Append(',').Append(Int(bits.nextSetBit(400)));
        bits.set(1, 191);
        trace.Append('|').Append(BitSetRuns(bits));
        bits.clear();
        trace.Append('|').Append(Int(bits.nextSetBit(0))).Append(',').Append(Bool(bits.get(2)));
        bits.set(70, 71);
        bits.set(0, 64);
        bits.set(65, 70);
        trace.Append('|').Append(BitSetRuns(bits));
        // ScratchFile: free pages set in bulk, taken with nextSetBit(0)/clear, freed and enlarged.
        bits.clear();
        bits.set(0, 100_000);
        long taken = 0;
        for (var i = 0; i < 25_600; i++)
        {
            var index = bits.nextSetBit(0);
            bits.clear(index);
            taken += index;
        }
        bits.set(7);
        bits.set(12_345);
        trace.Append('|').Append(Int(taken)).Append(',').Append(Int(bits.nextSetBit(0)))
            .Append(',').Append(Int(bits.nextSetBit(8))).Append(',').Append(Int(bits.nextSetBit(12_346)));
        bits.clear(7);
        bits.clear(12_345);
        bits.set(25_600, 100_000);
        for (var i = 0; i < 74_400; i++) bits.clear(bits.nextSetBit(0));
        trace.Append(',').Append(Int(bits.nextSetBit(0)));
        bits.set(100_000, 100_016);
        trace.Append(',').Append(BitSetRuns(bits));
        // Negative indexes and reversed ranges.
        trace.Append('|').Append(BitSetFailure(() => Bool(bits.get(-1))))
            .Append(',').Append(BitSetFailure(() => { bits.set(-2); return "null"; }))
            .Append(',').Append(BitSetFailure(() => { bits.clear(-3); return "null"; }))
            .Append(',').Append(BitSetFailure(() => bits.nextSetBit(-4).ToString(CultureInfo.InvariantCulture)))
            .Append(',').Append(BitSetFailure(() => { bits.set(-5, 2); return "null"; }))
            .Append(',').Append(BitSetFailure(() => { bits.set(2, -6); return "null"; }))
            .Append(',').Append(BitSetFailure(() => { bits.set(9, 8); return "null"; }))
            .Append(',').Append(BitSetRuns(bits));
        return trace.ToString();
    }

    private static string Bool(bool value) => value ? "true" : "false";
    private static string Int(long value) => value.ToString(CultureInfo.InvariantCulture);

    private static string BitSetRuns(JavaBitSet bits)
    {
        var runs = new StringBuilder("[");
        for (var index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1))
        {
            var start = index;
            while (bits.get(index + 1)) index++;
            runs.Append(Int(start)).Append('-').Append(Int(index)).Append(';');
        }
        return runs.Append(']').ToString();
    }

    private static string BitSetFailure(Func<string> operation)
    {
        try
        {
            return "ok:" + operation();
        }
        catch (ArgumentOutOfRangeException error)
        {
            return "index-out-of-bounds:" + error.Message;
        }
    }

    private static string ByteBufferContract()
    {
        using var buffer = JavaByteBuffer.wrap([1, 2, 3, 4, 5]);
        var integer = buffer.getInt();
        return $"{integer}|{buffer.Remaining}|{buffer.get()}";
    }

    private static string FileChannelReadContract()
    {
        static string Read(JavaFileChannel channel, JavaByteBuffer buffer)
        {
            try { return channel.read(buffer).ToString(CultureInfo.InvariantCulture); }
            catch (ArgumentException) { return "illegal-argument"; }
            catch (NotSupportedException) { return "read-only-buffer"; }
        }
        static string State(JavaByteBuffer buffer) =>
            $"{buffer.position()}:{buffer.limit()}:{buffer.capacity()}";
        static string Bytes(sbyte[] bytes) =>
            string.Join(",", bytes.Select(value => value.ToString(CultureInfo.InvariantCulture)));
        var values = new List<string>();
        string path = Path.Combine(Path.GetTempPath(), "dripsharp-channel-" + Guid.NewGuid().ToString("N"));
        File.WriteAllBytes(path, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
        try
        {
            using var channel = JavaFileChannel.open(path, JavaStandardOpenOption.READ);
            var backing = Enumerable.Repeat((sbyte)-1, 12).ToArray();
            using var parent = JavaByteBuffer.wrap(backing);
            parent.position(3);
            using var view = parent.slice();
            view.position(2).limit(6);
            values.Add(Read(channel, view));
            values.Add(State(view) + "/" + State(parent));
            values.Add(Bytes(backing));
            using var readOnlyParent = parent.asReadOnlyBuffer();
            values.Add(Read(channel, readOnlyParent) + "@" + State(readOnlyParent));
            view.clear().position(7);
            using var readOnlyView = view.slice().asReadOnlyBuffer();
            values.Add(Read(channel, readOnlyView) + "@" + State(readOnlyView));
            using var emptyReadOnly = JavaByteBuffer.allocate(2).position(2).asReadOnlyBuffer();
            values.Add(Read(channel, emptyReadOnly) + "@" + State(emptyReadOnly));
            using var mapped = channel.map(JavaFileChannelMapMode.READ_ONLY, 0, 4);
            values.Add(Read(channel, mapped) + "@" + State(mapped));
            values.Add(Bytes(backing));
            var next = new sbyte[3];
            using var nextBuffer = JavaByteBuffer.wrap(next);
            values.Add(Read(channel, nextBuffer) + "@" + State(nextBuffer) + "=" + Bytes(next));
            var tail = Enumerable.Repeat((sbyte)-1, 9).ToArray();
            using var tailParent = JavaByteBuffer.wrap(tail);
            tailParent.position(1).limit(8);
            using var tailView = tailParent.slice();
            values.Add(Read(channel, tailView) + "@" + State(tailView) + "=" + Bytes(tail));
            values.Add(Read(channel, tailView) + "@" + State(tailView));
            tailView.position(tailView.limit());
            values.Add(Read(channel, tailView) + "@" + State(tailView));
            using var afterEof = JavaByteBuffer.allocate(0).asReadOnlyBuffer();
            values.Add(Read(channel, afterEof) + "@" + State(afterEof));
            values.Add(Bytes(tail));
        }
        finally { File.Delete(path); }
        return string.Join("|", values);
    }

    private static string CharsetMalformedContract()
    {
        try
        {
            using var buffer = JavaByteBuffer.wrap(
                [unchecked((sbyte)0xc3), 0x28]);
            _ = new JavaCharsetDecoder(Encoding.UTF8).Decode(buffer);
            return "accepted";
        }
        catch (DecoderFallbackException)
        {
            return "rejected";
        }
    }

    private static string BoxedWrappersContract()
    {
        var floatNaNPayload = BitConverter.Int32BitsToSingle(0x7fc00001);
        var doubleNaNPayload = BitConverter.Int64BitsToDouble(0x7ff8000000000001L);
        var values = new object[]
        {
            'A', '\uffff', true, false,
            (sbyte)-1, (sbyte)-128, (short)-1, (short)-32768,
            0.0f, -0.0f, float.NaN, floatNaNPayload,
            BitConverter.Int32BitsToSingle(unchecked((int)0xffc00000)),
            float.PositiveInfinity, float.Epsilon, 1.5f,
            0.0d, -0.0d, double.NaN, doubleNaNPayload,
            double.NegativeInfinity, double.Epsilon, Math.PI,
        };
        var hashes = string.Join(",", values.Select(value =>
            JavaCompat.HashCode(value).ToString(CultureInfo.InvariantCulture)));
        var combined = JavaCompat.Hash('A', true, -0.0d, 1.5f);
        var equality = string.Join(",",
            Lower(JavaCompat.Equals(0.0d, -0.0d)),
            Lower(JavaCompat.Equals(double.NaN, doubleNaNPayload)),
            Lower(JavaCompat.Equals(0.0f, -0.0f)),
            Lower(JavaCompat.Equals(float.NaN, floatNaNPayload)),
            Lower(JavaCompat.Equals(1.5d, 1.5f)));
        var keys = new JavaLinkedHashMap<object, string>();
        foreach (var key in new object[]
                 { 0.0d, -0.0d, double.NaN, doubleNaNPayload, 0.0f, -0.0f })
            keys[key] = "x";
        return $"{hashes}|{combined.ToString(CultureInfo.InvariantCulture)}|" +
               $"{equality}|{keys.Count.ToString(CultureInfo.InvariantCulture)}";
    }

    private static string CollectionsContract()
    {
        var linked = new JavaLinkedHashMap<string, string>();
        linked.Add("first", "before");
        linked.Add("second", "two");
        using var entries = JavaCompat.MapEntrySet(
            (IDictionary<string, string>)linked).GetEnumerator();
        if (!entries.MoveNext())
            throw new InvalidDataException("Missing linked-map entry.");
        var entry = entries.Current;
        var prior = entry.SetValue("after");

        var deque = new JavaDeque<string>();
        deque.Add("tail");
        deque.Push("head");
        return $"{prior}|{entry.Value}|" +
               $"{string.Join(",", linked.Keys)}|{deque.Pop()},{deque.Pop()}";
    }

    private static string CompressionContract()
    {
        using var compressed = new MemoryStream();
        using (var compressor = new ZLibStream(
                   compressed, CompressionLevel.Optimal, leaveOpen: true))
        {
            compressor.Write(Encoding.UTF8.GetBytes("deflate-body"));
        }

        using var decoded = new MemoryStream();
        using var inflater = new JavaInflaterOutputStream(decoded);
        var encoded = compressed.ToArray();
        inflater.Write(encoded, 0, encoded.Length);
        inflater.Flush();
        return Encoding.UTF8.GetString(decoded.ToArray());
    }

    private static string Crc32Contract()
    {
        var crc = new JavaCrc32();
        var value = Encoding.ASCII.GetBytes("123456789")
            .Select(item => unchecked((sbyte)item)).ToArray();
        crc.Update(value, 0, value.Length);
        return crc.GetValue().ToString(CultureInfo.InvariantCulture);
    }

    private static string DataOutputContract()
    {
        using var output = new MemoryStream();
        using (var data = new JavaDataOutputStream(output))
        {
            data.writeByte(0x7f);
            data.writeShort(0x1234);
            data.writeInt(unchecked((int)0x89abcdef));
        }
        return Convert.ToHexString(output.ToArray()).ToLowerInvariant();
    }

    private static string DecimalFormatContract()
    {
        var format = new JavaDecimalFormat(
            "0.0000000", CultureInfo.InvariantCulture.NumberFormat);
        return format.Format(123456789.123456789d) + "|" +
               format.Format(-0.0d);
    }

    private static string MessageDigestContract()
    {
        var value = Encoding.ASCII.GetBytes("abc")
            .Select(item => unchecked((sbyte)item)).ToArray();
        var digest = JavaMessageDigest.GetInstance("SHA-256").Digest(value);
        return Convert.ToHexString(
            digest.Select(item => unchecked((byte)item)).ToArray())
            .ToLowerInvariant();
    }

    private static string MessageFormatContract()
    {
        var format = new JavaMessageFormat(
            "{0}={1,number,000.00}", CultureInfo.GetCultureInfo("en-US"));
        return format.Format(new object?[] { "value", 12.5d });
    }

    private static string OptionalContract()
    {
        var present = JavaOptional<string>.Of("x")
            .Map(value => value + "y").OrElse("missing");
        var empty = JavaOptional<string>.Empty().OrElse("fallback");
        return present + "|" + empty;
    }

    private static string RegexContract()
    {
        var pattern = JavaCompat.CompileRegex(@"(?<word>\p{L}+)");
        var matcher = JavaCompat.RegexMatcher(pattern, "é42");
        if (!matcher.Find())
            throw new InvalidDataException("Regex did not match.");
        return $"{matcher.Group("word")}|{matcher.Start()}|{matcher.End()}";
    }

    private static string SignedByteOrContract()
    {
        sbyte value = -128;
        var first = JavaCompat.OrAssign(ref value, 1);
        value = 1;
        var second = JavaCompat.OrAssign(ref value, 0x180);
        return $"{first}|{second}";
    }

    private static string StrictMathContract() =>
        string.Join("|",
            JavaCompat.StringValueOf(JavaStrictMath.Sin(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Cos(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Log10(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Pow(2.3d, -4.0d)));

    private static string StringIdentityContract() =>
        $"{JavaCompat.StringHashCode("DripSharp")}|" +
        $"{JavaCompat.ToHexString(-65536)}|" +
        $"{JavaCompat.ToHexString(-65536L)}|" +
        JavaCompat.StringValueOf((object?)null);

    private static string StringToolsContract()
    {
        var joiner = new JavaStringJoiner(",", "[", "]")
            .add("a").add("b");
        var tokenizer = new JavaStringTokenizer("one two\tthree");
        var tokens = new List<string>();
        while (tokenizer.hasMoreTokens())
            tokens.Add(tokenizer.nextToken());
        return joiner + "|" + string.Join(",", tokens);
    }

    private static string TimeFormatContract()
    {
        var instant = new DateTimeOffset(
            2024, 1, 2, 3, 4, 5, TimeSpan.Zero);
        return JavaDateTimeFormatter.Rfc1123.Format(instant) + "|" +
               JavaTimeUnits.ToTimeSpan(1500, JavaTimeUnit.MILLISECONDS)
                   .TotalMilliseconds.ToString(CultureInfo.InvariantCulture);
    }

    private static string UriContract()
    {
        var hostless = JavaCompat.NewUri("http:///submit");
        var basis = JavaCompat.CreateUri("file:///tmp/PklProject");
        var resolved = JavaCompat.ResolveUri(basis, ".");
        return $"{JavaCompat.UriHost(hostless) ?? "null"}|" +
               $"{JavaCompat.UriRawPath(hostless)}|" +
               $"{JavaCompat.UriAuthority(resolved) ?? "null"}|" +
               JavaCompat.UriRawPath(resolved);
    }

    private static string XPathContract()
    {
        var document = new XmlDocument();
        document.LoadXml("<root><item>value</item></root>");
        return JavaXPathFactory.Instance.NewXPath()
            .Evaluate("/root/item", document);
    }

    private static string Lower(bool value) =>
        value ? "true" : "false";

    private static void WriteObservations(
        string file,
        IEnumerable<Observation> observations)
    {
        var lines = new[] { Header }.Concat(observations.Select(row =>
            $"{row.Family}\t{row.Id}\t{row.Value}"));
        File.WriteAllText(
            file,
            string.Join("\n", lines) + "\n",
            new UTF8Encoding(false));
    }
}
