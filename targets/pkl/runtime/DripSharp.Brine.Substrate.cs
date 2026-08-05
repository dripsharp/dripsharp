// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused .NET substrate for the Truffle and Graal collection contracts used
// by the generated Brine Pkl product. This is product runtime code, not a
// reconstructed frontend model.
#nullable enable

using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;

namespace DripSharp.Brine.Runtime
{
    internal readonly struct JavaAppendable
    {
        private readonly object value;
        private JavaAppendable(object value) => this.value = value;
        public static implicit operator JavaAppendable(System.IO.TextWriter value) => new(value);
        public static implicit operator JavaAppendable(System.Text.StringBuilder value) => new(value);
        public JavaAppendable Append(char item) { if (value is System.IO.TextWriter writer) writer.Write(item); else ((System.Text.StringBuilder)value).Append(item); return this; }
        public JavaAppendable Append(string? item) { var rendered = item ?? "null"; if (value is System.IO.TextWriter writer) writer.Write(rendered); else ((System.Text.StringBuilder)value).Append(rendered); return this; }
        public JavaAppendable Append(string? item, int start, int end) => Append((item ?? "null").Substring(start, end - start));
        public JavaAppendable Append(object? item) => Append(global::DripSharp.Runtime.JavaCompat.StringValueOf(item));
    }

    internal delegate T JavaBinaryOperator<T>(T left, T right);
    internal delegate T JavaLongFunction<T>(long value);
    internal sealed partial class VmList
    {
        internal static VmList Create(IEnumerable<object> values) => CreateFromUnmodIterable(values);
    }
    internal sealed partial class VmBytes
    {
        internal VmBytes(byte[] bytes) :
            this(bytes.Select(value => unchecked((sbyte)value)).ToArray()) { }
    }
    internal sealed partial class VmSet
    {
        internal static VmSet Create(ISet<object> values, global::DripSharp.Brine.Util.Paguro.RrbTree<object> order) =>
            Create(values, (global::DripSharp.Brine.Util.Paguro.RrbTree<object>.ImRrbt<object>)(object)order);
    }
    internal static class ExcludedMessagePack
    {
        // General Pkl binary serialization remains excluded. The stream overloads
        // implement only the private framing required by configured external readers.
        public static ExcludedMessagePackPacker NewDefaultBufferPacker() => new();
        public static ExcludedMessagePackPacker NewDefaultPacker(System.IO.Stream stream) => new(stream);
        public static ExcludedMessagePackUnpacker NewDefaultUnpacker(byte[] bytes) => new();
        public static ExcludedMessagePackUnpacker NewDefaultUnpacker(System.IO.Stream stream) => new(stream);
    }
    internal sealed class ExcludedMessagePackPacker : IDisposable
    {
        private readonly System.IO.Stream? stream;
        private static NotSupportedException Excluded() => new("MessagePack is excluded from the DripSharp product target.");
        public ExcludedMessagePackPacker() { }
        internal ExcludedMessagePackPacker(System.IO.Stream stream) => this.stream = stream;
        private System.IO.Stream ExternalReaderStream() => stream ?? throw Excluded();
        public void Clear() { }
        public sbyte[] ToByteArray() => throw Excluded();
        public ExcludedMessagePackPacker PackNil() { ExternalReaderStream().WriteByte(0xc0); return this; }
        public ExcludedMessagePackPacker PackBoolean(bool value) { ExternalReaderStream().WriteByte(value ? (byte)0xc3 : (byte)0xc2); return this; }
        public ExcludedMessagePackPacker PackInt(int value) => PackLong(value);
        public ExcludedMessagePackPacker PackLong(long value)
        {
            var output = ExternalReaderStream();
            if (value >= 0 && value <= 0x7f) output.WriteByte((byte)value);
            else if (value >= -32 && value < 0) output.WriteByte(unchecked((byte)value));
            else { output.WriteByte(0xd3); WriteUnsigned(unchecked((ulong)value), 8); }
            return this;
        }
        public ExcludedMessagePackPacker PackLong(long? value) => value.HasValue ? PackLong(value.Value) : PackNil();
        public ExcludedMessagePackPacker PackByte(sbyte value) => PackLong(value);
        public ExcludedMessagePackPacker PackBinaryHeader(int length) { ExternalReaderStream().WriteByte(0xc6); WriteUnsigned((uint)length, 4); return this; }
        public ExcludedMessagePackPacker WritePayload(sbyte[] value)
        {
            var bytes = new byte[value.Length];
            for (var index = 0; index < value.Length; index++) bytes[index] = unchecked((byte)value[index]);
            ExternalReaderStream().Write(bytes);
            return this;
        }
        public ExcludedMessagePackPacker AddPayload(sbyte[] value) => WritePayload(value);
        public ExcludedMessagePackPacker PackDouble(double value)
        {
            ExternalReaderStream().WriteByte(0xcb);
            WriteUnsigned(unchecked((ulong)BitConverter.DoubleToInt64Bits(value)), 8);
            return this;
        }
        public ExcludedMessagePackPacker PackString(string value)
        {
            var bytes = System.Text.Encoding.UTF8.GetBytes(value);
            if (bytes.Length < 32) ExternalReaderStream().WriteByte((byte)(0xa0 | bytes.Length));
            else { ExternalReaderStream().WriteByte(0xdb); WriteUnsigned((uint)bytes.Length, 4); }
            ExternalReaderStream().Write(bytes);
            return this;
        }
        public ExcludedMessagePackPacker PackArrayHeader(int size) { WriteHeader(size, 0x90, 0xdd); return this; }
        public ExcludedMessagePackPacker PackMapHeader(int size) { WriteHeader(size, 0x80, 0xdf); return this; }
        private void WriteHeader(int size, int fixedPrefix, byte extendedPrefix)
        {
            if (size < 0) throw new ArgumentOutOfRangeException(nameof(size));
            if (size < 16) ExternalReaderStream().WriteByte((byte)(fixedPrefix | size));
            else { ExternalReaderStream().WriteByte(extendedPrefix); WriteUnsigned((uint)size, 4); }
        }
        private void WriteUnsigned(ulong value, int count)
        {
            for (var shift = (count - 1) * 8; shift >= 0; shift -= 8)
                ExternalReaderStream().WriteByte((byte)(value >> shift));
        }
        public void Flush() => ExternalReaderStream().Flush();
        public void Close() { }
        public void Dispose() { }
    }
    internal sealed class ExcludedMessagePackUnpacker : IDisposable
    {
        private readonly System.IO.Stream? stream;
        private int lookahead = -2;
        private static NotSupportedException Excluded() => new("MessagePack is excluded from the DripSharp product target.");
        public ExcludedMessagePackUnpacker() { }
        internal ExcludedMessagePackUnpacker(System.IO.Stream stream) => this.stream = stream;
        private System.IO.Stream ExternalReaderStream() => stream ?? throw Excluded();
        public bool HasNext()
        {
            if (lookahead == -2) lookahead = ExternalReaderStream().ReadByte();
            return lookahead >= 0;
        }
        private byte ReadPrefix()
        {
            int value;
            if (lookahead != -2) { value = lookahead; lookahead = -2; }
            else value = ExternalReaderStream().ReadByte();
            if (value < 0) throw new System.IO.EndOfStreamException();
            return (byte)value;
        }
        public int UnpackArrayHeader()
        {
            var prefix = ReadPrefix();
            if ((prefix & 0xf0) == 0x90) return prefix & 0x0f;
            if (prefix == 0xdc) return checked((int)ReadUnsigned(2));
            if (prefix == 0xdd) return checked((int)ReadUnsigned(4));
            throw new NotSupportedException("Expected a MessagePack array header.");
        }
        public int UnpackInt() => UnpackValue().AsIntegerValue().AsInt();
        internal ExcludedMessagePackValue UnpackValue() => ReadValue(ReadPrefix());
        private ExcludedMessagePackValue ReadValue(byte prefix)
        {
            if (prefix <= 0x7f) return new((long)prefix);
            if (prefix >= 0xe0) return new((long)unchecked((sbyte)prefix));
            if ((prefix & 0xf0) == 0x80) return ReadMap(prefix & 0x0f);
            if ((prefix & 0xf0) == 0x90) return ReadArray(prefix & 0x0f);
            if ((prefix & 0xe0) == 0xa0) return ReadString(prefix & 0x1f);
            return prefix switch
            {
                0xc0 => new ExcludedMessagePackValue((object?)null),
                0xc2 => new ExcludedMessagePackValue(false),
                0xc3 => new ExcludedMessagePackValue(true),
                0xc4 => ReadBinary(checked((int)ReadUnsigned(1))),
                0xc5 => ReadBinary(checked((int)ReadUnsigned(2))),
                0xc6 => ReadBinary(checked((int)ReadUnsigned(4))),
                0xcc => new ExcludedMessagePackValue(checked((long)ReadUnsigned(1))),
                0xcd => new ExcludedMessagePackValue(checked((long)ReadUnsigned(2))),
                0xce => new ExcludedMessagePackValue(checked((long)ReadUnsigned(4))),
                0xcf => new ExcludedMessagePackValue(unchecked((long)ReadUnsigned(8))),
                0xd0 => new ExcludedMessagePackValue((long)unchecked((sbyte)ReadUnsigned(1))),
                0xd1 => new ExcludedMessagePackValue((long)unchecked((short)ReadUnsigned(2))),
                0xd2 => new ExcludedMessagePackValue((long)unchecked((int)ReadUnsigned(4))),
                0xd3 => new ExcludedMessagePackValue(unchecked((long)ReadUnsigned(8))),
                0xd9 => ReadString(checked((int)ReadUnsigned(1))),
                0xda => ReadString(checked((int)ReadUnsigned(2))),
                0xdb => ReadString(checked((int)ReadUnsigned(4))),
                0xdc => ReadArray(checked((int)ReadUnsigned(2))),
                0xdd => ReadArray(checked((int)ReadUnsigned(4))),
                0xde => ReadMap(checked((int)ReadUnsigned(2))),
                0xdf => ReadMap(checked((int)ReadUnsigned(4))),
                _ => throw new NotSupportedException($"Unsupported MessagePack prefix: 0x{prefix:x2}")
            };
        }
        private ulong ReadUnsigned(int count)
        {
            ulong result = 0;
            for (var index = 0; index < count; index++)
            {
                var value = ExternalReaderStream().ReadByte();
                if (value < 0) throw new System.IO.EndOfStreamException();
                result = (result << 8) | (byte)value;
            }
            return result;
        }
        private byte[] ReadBytes(int length)
        {
            var result = new byte[length];
            ExternalReaderStream().ReadExactly(result);
            return result;
        }
        private ExcludedMessagePackValue ReadString(int length) => new(System.Text.Encoding.UTF8.GetString(ReadBytes(length)));
        private ExcludedMessagePackValue ReadBinary(int length)
        {
            var bytes = ReadBytes(length);
            var result = new sbyte[length];
            for (var index = 0; index < length; index++) result[index] = unchecked((sbyte)bytes[index]);
            return new(result);
        }
        private ExcludedMessagePackValue ReadArray(int count)
        {
            var result = new List<ExcludedMessagePackValue>(count);
            for (var index = 0; index < count; index++) result.Add(UnpackValue());
            return new(result);
        }
        private ExcludedMessagePackValue ReadMap(int count)
        {
            var result = new Dictionary<ExcludedMessagePackValue, ExcludedMessagePackValue>(count);
            for (var index = 0; index < count; index++) result.Add(UnpackValue(), UnpackValue());
            return new(result);
        }
        public void Close() { }
        public void Dispose() { }
    }
    internal sealed class ExcludedMessagePackValue : IEnumerable<ExcludedMessagePackValue>
    {
        private readonly object? value;
        public ExcludedMessagePackValue() { }
        public ExcludedMessagePackValue(string value) => this.value = value;
        internal ExcludedMessagePackValue(object? value) => this.value = value;
        private ExcludedMessagePackValue Require(Func<object?, bool> predicate, string kind) =>
            predicate(value) ? this : throw new NotSupportedException($"Expected a MessagePack {kind} value.");
        public ExcludedMessagePackValue AsArrayValue() => Require(item => item is IList<ExcludedMessagePackValue>, "array");
        public ExcludedMessagePackValue AsBinaryValue() => Require(item => item is sbyte[], "binary");
        public ExcludedMessagePackValue AsBooleanValue() => Require(item => item is bool, "boolean");
        public ExcludedMessagePackValue AsIntegerValue() => Require(item => item is long, "integer");
        public ExcludedMessagePackValue AsMapValue() => Require(item => item is IDictionary<ExcludedMessagePackValue, ExcludedMessagePackValue>, "map");
        public ExcludedMessagePackValue AsStringValue() => Require(item => item is string, "string");
        public sbyte[] AsByteArray() => (sbyte[])AsBinaryValue().value!;
        public bool GetBoolean() => (bool)AsBooleanValue().value!;
        public int AsInt() => checked((int)AsLong());
        public long AsLong() => (long)AsIntegerValue().value!;
        public string AsString() => (string)AsStringValue().value!;
        public int Size() => value switch { IList<ExcludedMessagePackValue> list => list.Count, IDictionary<ExcludedMessagePackValue, ExcludedMessagePackValue> map => map.Count, _ => throw new NotSupportedException("Expected a MessagePack collection value.") };
        public IList<ExcludedMessagePackValue> List() => (IList<ExcludedMessagePackValue>)AsArrayValue().value!;
        public IDictionary<ExcludedMessagePackValue, ExcludedMessagePackValue> Map() => (IDictionary<ExcludedMessagePackValue, ExcludedMessagePackValue>)AsMapValue().value!;
        public ISet<KeyValuePair<ExcludedMessagePackValue, ExcludedMessagePackValue>> EntrySet() => new HashSet<KeyValuePair<ExcludedMessagePackValue, ExcludedMessagePackValue>>(Map());
        public IEnumerator<ExcludedMessagePackValue> GetEnumerator() => List().GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        public override bool Equals(object? other) => other is ExcludedMessagePackValue item && Equals(value, item.value);
        public override int GetHashCode() => value?.GetHashCode() ?? 0;
    }
    internal class JavaTuple2<A, B>
    {
        public readonly A _1; public readonly B _2;
        public JavaTuple2(A first, B second) { _1 = first; _2 = second; }
        public static JavaTuple2<X, Y> Of<X, Y>(X first, Y second) => new(first, second);
    }
    internal class JavaTuple4<A, B, C, D>
    {
        public readonly A _1; public readonly B _2; public readonly C _3; public readonly D _4;
        public JavaTuple4(A first, B second, C third, D fourth) { _1 = first; _2 = second; _3 = third; _4 = fourth; }
    }
    internal sealed class JavaIdentityDictionary<K, V> : Dictionary<K, V> where K : notnull
    {
        private sealed class IdentityComparer : IEqualityComparer<K>
        {
            public bool Equals(K? left, K? right) => ReferenceEquals(left, right);
            public int GetHashCode(K value) => System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);
        }
        public JavaIdentityDictionary() : base(new IdentityComparer()) { }
    }
    internal static class JavaBase64
    {
        public static JavaBase64Encoder GetEncoder() => new(false);
        public static JavaBase64Encoder GetUrlEncoder() => new(true);
        public static JavaBase64Decoder GetDecoder() => new(false);
        public static JavaBase64Decoder GetUrlDecoder() => new(true);
    }
    internal sealed class JavaBase64Encoder
    {
        private readonly bool url; public JavaBase64Encoder(bool url) => this.url = url;
        public string EncodeToString(byte[] bytes)
        { var value = Convert.ToBase64String(bytes); return url ? value.TrimEnd('=').Replace('+', '-').Replace('/', '_') : value; }
        public string EncodeToString(sbyte[] bytes) => EncodeToString(bytes.Select(value => unchecked((byte)value)).ToArray());
        public JavaBase64Encoder WithoutPadding() => this;
    }
    internal sealed class JavaBase64Decoder
    {
        private readonly bool url; public JavaBase64Decoder(bool url) => this.url = url;
        public sbyte[] Decode(string value)
        {
            try
            {
                if (url) value = value.Replace('-', '+').Replace('_', '/');
                value = value.PadRight((value.Length + 3) / 4 * 4, '=');
                return Convert.FromBase64String(value).Select(item => unchecked((sbyte)item)).ToArray();
            }
            catch (FormatException error)
            {
                var alphabet = url
                    ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_="
                    : "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
                var invalid = value.FirstOrDefault(character => !alphabet.Contains(character));
                var message = invalid == default
                    ? error.Message
                    : $"Illegal base64 character {((int)invalid):x}";
                throw new ArgumentException(message, error);
            }
        }
    }

    internal sealed class JavaOptional<T>
    {
        private readonly T? value;
        private readonly bool present;
        private JavaOptional(T? value, bool present) { this.value = value; this.present = present; }
        public static JavaOptional<T> Empty() => new(default, false);
        public static JavaOptional<T> Of(T value) => new(value ?? throw new NullReferenceException(), true);
        public static JavaOptional<T> OfNullable(T? value) => new(value, value is not null);
        public bool IsPresent() => present;
        public bool IsEmpty() => !present;
        public T Get() => present ? value! : throw new InvalidOperationException("Optional is empty");
        public T OrElse(T fallback) => present ? value! : fallback;
        public void IfPresent(Action<T> action) { if (present) action(value!); }
        public void IfPresentOrElse(Action<T> action, Action emptyAction) { if (present) action(value!); else emptyAction(); }
        public T OrElseThrow() => Get();
        public JavaOptional<R> Map<R>(Func<T, R> mapper) => present ? JavaOptional<R>.OfNullable(mapper(value!)) : JavaOptional<R>.Empty();
        public R Match<R>(Func<T, R> presentCase, Func<R> emptyCase) =>
            present ? presentCase(value!) : emptyCase();
    }

    internal sealed class JavaPrintWriter : System.IO.TextWriter
    {
        private readonly System.IO.TextWriter writer;
        public JavaPrintWriter(System.IO.TextWriter writer) => this.writer = writer;
        public override System.Text.Encoding Encoding => writer.Encoding;
        public override void Write(char value) => writer.Write(value);
        public override void Write(string? value) => writer.Write(value);
        public override void WriteLine(object? value) => writer.WriteLine(value);
        public void Print(object? value) => writer.Write(value);
        public void Println(object? value = null) => writer.WriteLine(value);
        public override void Flush() => writer.Flush();
    }

    internal enum JavaTemporalUnit { NANOS, MICROS, MILLIS, SECONDS, MINUTES, HOURS, DAYS }
    internal enum JavaRoundingMode { UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY }

    internal sealed class JavaScheduledExecutor
    {
        private sealed class ScheduledWork
        {
            internal readonly System.Threading.CancellationTokenSource Cancellation = new();
            internal System.Threading.Tasks.Task Task = System.Threading.Tasks.Task.CompletedTask;
        }

        private readonly object lifecycleLock = new();
        private readonly Dictionary<object, ScheduledWork> pending =
            new(ReferenceEqualityComparer.Instance);
        private bool shutdown;

        public System.Threading.Tasks.Task Schedule(Action action, long delay, object unit) =>
            ScheduleCore(action, action, delay);

        public System.Threading.Tasks.Task Schedule(object runnable, long delay, object unit) =>
            ScheduleCore(runnable, () =>
                runnable.GetType().GetMethod(
                    "Run",
                    System.Reflection.BindingFlags.Instance |
                    System.Reflection.BindingFlags.Public |
                    System.Reflection.BindingFlags.NonPublic)!.Invoke(runnable, null), delay);

        private System.Threading.Tasks.Task ScheduleCore(object key, Action action, long delay)
        {
            var work = new ScheduledWork();
            lock (lifecycleLock)
            {
                ObjectDisposedException.ThrowIf(shutdown, this);
                pending[key] = work;
                work.Task = System.Threading.Tasks.Task.Run(async () =>
                {
                    try
                    {
                        await System.Threading.Tasks.Task.Delay(
                            TimeSpan.FromMilliseconds(delay), work.Cancellation.Token);
                        action();
                    }
                    catch (System.OperationCanceledException)
                        when (work.Cancellation.IsCancellationRequested) { }
                    finally
                    {
                        lock (lifecycleLock)
                        {
                            if (pending.TryGetValue(key, out var current) &&
                                ReferenceEquals(current, work))
                                pending.Remove(key);
                        }
                        work.Cancellation.Dispose();
                    }
                });
                return work.Task;
            }
        }

        public bool Cancel(object key)
        {
            ScheduledWork? work;
            lock (lifecycleLock) pending.TryGetValue(key, out work);
            if (work is null) return true;
            try { work.Cancellation.Cancel(); }
            catch (ObjectDisposedException) { }
            WaitForTask(work.Task);
            return true;
        }

        public void WaitFor(object key)
        {
            ScheduledWork? work;
            lock (lifecycleLock) pending.TryGetValue(key, out work);
            if (work is not null) WaitForTask(work.Task);
            // The scheduler can finish and remove its bookkeeping before the
            // evaluation thread observes it. Consume a concurrently delivered
            // Thread.Interrupt even in that race so it cannot escape into the
            // caller's next unrelated blocking operation.
            try { System.Threading.Thread.Sleep(0); }
            catch (System.Threading.ThreadInterruptedException) { }
        }

        public void Shutdown()
        {
            ScheduledWork[] work;
            lock (lifecycleLock)
            {
                if (shutdown) return;
                shutdown = true;
                work = pending.Values.ToArray();
            }
            foreach (var item in work)
            {
                try { item.Cancellation.Cancel(); }
                catch (ObjectDisposedException) { }
            }
        }

        private static void WaitForTask(System.Threading.Tasks.Task task)
        {
            while (true)
            {
                try
                {
                    task.GetAwaiter().GetResult();
                    return;
                }
                // Context cancellation interrupts blocking translated work.
                // If the interrupt is delivered while timeout cleanup is
                // joining the scheduler task, consume it and re-observe the
                // task so the public result remains the timeout diagnostic.
                catch (System.Threading.ThreadInterruptedException) { }
                catch (System.OperationCanceledException) { return; }
            }
        }
    }

    internal sealed class JavaThread
    {
        private readonly System.Threading.Thread thread;
        private System.Exception? failure;

        public JavaThread(Action action, string name)
        {
            thread = new System.Threading.Thread(() =>
            {
                try { action(); }
                // An uncaught exception ends a Java thread without terminating
                // the JVM. Capture the equivalent .NET failure so translated
                // background workers cannot bring down the consumer process.
                catch (System.Exception error) { failure = error; }
            }) { Name = name };
        }
        private JavaThread(System.Threading.Thread thread) => this.thread = thread;
        public static JavaThread CurrentThread() => new(System.Threading.Thread.CurrentThread);
        public void Interrupt() => thread.Interrupt();
        public void SetDaemon(bool daemon) => thread.IsBackground = daemon;
        public void Start() => thread.Start();
        private bool IsCurrentThread() => ReferenceEquals(thread, System.Threading.Thread.CurrentThread);
        internal bool Join(TimeSpan timeout)
        {
            if (IsCurrentThread()) return true;
            try { return thread.Join(timeout); }
            catch (System.Threading.ThreadInterruptedException) { return !thread.IsAlive; }
            catch (System.Threading.ThreadStateException) { return !thread.IsAlive; }
        }
        internal System.Exception? Failure => failure;
    }

    internal static class JavaConcurrency
    {
        public static JavaScheduledExecutor NewSingleThreadScheduledExecutor(
            Func<Action, JavaThread> threadFactory) => new();
    }

    internal sealed class JavaAtomicBoolean
    {
        private int value;
        public JavaAtomicBoolean(bool value = false) => this.value = value ? 1 : 0;
        public bool Get() => System.Threading.Volatile.Read(ref value) != 0;
        public void Set(bool next) => System.Threading.Volatile.Write(ref value, next ? 1 : 0);
        public bool GetAndSet(bool next) => System.Threading.Interlocked.Exchange(ref value, next ? 1 : 0) != 0;
        public bool CompareAndSet(bool expected, bool next) =>
            System.Threading.Interlocked.CompareExchange(ref value, next ? 1 : 0, expected ? 1 : 0) == (expected ? 1 : 0);
    }
    internal sealed class JavaAtomicLong
    {
        private long value;
        public JavaAtomicLong(long value = 0) => this.value = value;
        public long Get() => System.Threading.Interlocked.Read(ref value);
        public void Set(long next) => System.Threading.Interlocked.Exchange(ref value, next);
        public long IncrementAndGet() => System.Threading.Interlocked.Increment(ref value);
    }
    internal sealed class JavaAtomicReference<T> where T : class
    {
        private T? value;
        public JavaAtomicReference(T? value = null) => this.value = value;
        public T? Get() => System.Threading.Volatile.Read(ref value);
        public void Set(T? next) => System.Threading.Volatile.Write(ref value, next);
        public bool CompareAndSet(T? expected, T? next) =>
            ReferenceEquals(System.Threading.Interlocked.CompareExchange(ref value, next, expected), expected);
    }

    internal sealed class JavaByteBuffer
    {
        private readonly byte[] bytes;
        private int position;
        private JavaByteBuffer(byte[] bytes) => this.bytes = bytes;
        public static JavaByteBuffer Wrap(byte[] bytes) => new(bytes);
        public static JavaByteBuffer Wrap(sbyte[] bytes) => new(bytes.Select(value => unchecked((byte)value)).ToArray());
        public static JavaByteBuffer Allocate(int capacity) => new(new byte[capacity]);
        public JavaByteBuffer PutLong(long value)
        {
            System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(bytes.AsSpan(position, 8), value);
            position += 8;
            return this;
        }
        public int GetInt()
        {
            var value = System.Buffers.Binary.BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(position, 4));
            position += 4;
            return value;
        }
        public long GetLong()
        {
            var value = System.Buffers.Binary.BinaryPrimitives.ReadInt64BigEndian(bytes.AsSpan(position, 8));
            position += 8;
            return value;
        }
        public sbyte[] Array() => bytes.Select(value => unchecked((sbyte)value)).ToArray();
        public byte[] UnsignedArray() => (byte[])bytes.Clone();
        public int Remaining() => bytes.Length - position;
        public JavaByteBuffer Flip() { position = 0; return this; }
    }

    internal class JavaUrlConnection
    {
        private readonly Uri? uri;
        public JavaUrlConnection() { }
        public JavaUrlConnection(Uri uri) => this.uri = uri;
        public virtual void Connect() { }
        public virtual Uri GetURL() => uri ?? throw new InvalidOperationException("URL is unavailable");
        public virtual System.IO.Stream GetInputStream()
        {
            if (uri is null) throw new NotSupportedException();
            if (uri.IsFile) return System.IO.File.OpenRead(uri.LocalPath);
            return new System.Net.Http.HttpClient().GetStreamAsync(
                uri, global::DripSharp.Runtime.JavaCancellation.CurrentToken).GetAwaiter().GetResult();
        }
        public virtual void SetUseCaches(bool value) { }
    }
    internal sealed class JavaJarConnection : JavaUrlConnection
    {
        public override System.IO.Stream GetInputStream() => throw new NotSupportedException("JAR URL streams require a resolved module path.");
    }

    internal sealed class JavaBufferedWriter : System.IO.TextWriter
    {
        private readonly System.IO.TextWriter writer;
        public JavaBufferedWriter(System.IO.TextWriter writer) => this.writer = writer;
        public override System.Text.Encoding Encoding => writer.Encoding;
        public override void Write(char value) => writer.Write(value);
        public override void Write(string? value) => writer.Write(value);
        public override void Flush() => writer.Flush();
        protected override void Dispose(bool disposing) { if (disposing) writer.Dispose(); base.Dispose(disposing); }
    }
    internal sealed class JavaCharsetDecoder
    {
        private readonly System.Text.Encoding encoding;
        public JavaCharsetDecoder(System.Text.Encoding encoding)
        {
            this.encoding = (System.Text.Encoding)encoding.Clone();
            this.encoding.DecoderFallback = System.Text.DecoderFallback.ExceptionFallback;
        }
        public string Decode(JavaByteBuffer buffer) => encoding.GetString(buffer.UnsignedArray());
    }
    internal sealed class JavaCharsetEncoder
    {
        private readonly System.Text.Encoding encoding;
        public JavaCharsetEncoder(System.Text.Encoding encoding) => this.encoding = encoding;
        public JavaByteBuffer Encode(string value) => JavaByteBuffer.Wrap(encoding.GetBytes(value));
    }

    internal enum JavaFileVisitResult { CONTINUE, TERMINATE, SKIP_SUBTREE, SKIP_SIBLINGS }
    internal enum JavaCopyOption { REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE }
    internal sealed class JavaWatchService : IDisposable { public void Close() { } public void Dispose() { } }
    internal class JavaSimpleFileVisitor<T>
    {
        public virtual JavaFileVisitResult VisitFile(T file, System.IO.FileSystemInfo attributes) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult PreVisitDirectory(T directory, System.IO.FileSystemInfo attributes) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult PostVisitDirectory(T directory, System.IO.IOException? error) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult VisitFileFailed(T file, System.IO.IOException error) => JavaFileVisitResult.CONTINUE;
    }
    internal class JavaFileSystem : IDisposable
    {
        public virtual JavaFileSystemProvider Provider() => new();
        public virtual string GetPath(string first, params string[] more) => System.IO.Path.Combine(new[] { first }.Concat(more).ToArray());
        public virtual Predicate<string> GetPathMatcher(string syntaxAndPattern) => value => true;
        public virtual IEnumerable<string> GetRootDirectories() => System.IO.DriveInfo.GetDrives().Select(drive => drive.RootDirectory.FullName);
        public virtual IEnumerable<System.IO.DriveInfo> GetFileStores() => System.IO.DriveInfo.GetDrives();
        public virtual ISet<string> SupportedFileAttributeViews() => new HashSet<string>();
        public virtual object GetUserPrincipalLookupService() => new();
        public virtual JavaWatchService NewWatchService() => new();
        public virtual bool IsOpen() => true;
        public virtual bool IsReadOnly() => false;
        public virtual string GetSeparator() => System.IO.Path.DirectorySeparatorChar.ToString();
        public virtual void Close() { }
        public void Dispose() => Close();
    }

    internal sealed class JavaFileSystemAlreadyExistsException : System.IO.IOException
    {
        public JavaFileSystemAlreadyExistsException(string? message = null) : base(message) { }
    }

    internal sealed class JavaZipFileSystem : JavaFileSystem
    {
        private readonly string root;
        private bool open = true;

        public JavaZipFileSystem(Uri uri)
        {
            var text = uri.OriginalString;
            if (!text.StartsWith("jar:", StringComparison.OrdinalIgnoreCase))
                throw new System.IO.IOException($"Expected a jar URI, got `{uri}`.");
            var nested = text[4..];
            var separator = nested.IndexOf("!/", StringComparison.Ordinal);
            if (separator >= 0) nested = nested[..separator];
            if (!Uri.TryCreate(nested, UriKind.Absolute, out var archiveUri) || !archiveUri.IsFile)
                throw new System.IO.IOException($"JAR URI `{uri}` does not identify a local archive.");

            root = System.IO.Path.Combine(System.IO.Path.GetTempPath(),
                "dripsharp-modulepath-" + Guid.NewGuid().ToString("N"));
            System.IO.Directory.CreateDirectory(root);
            try
            {
                using var archive = System.IO.Compression.ZipFile.OpenRead(archiveUri.LocalPath);
                foreach (var entry in archive.Entries)
                {
                    var entryPath = entry.FullName.Replace('\\', '/');
                    var segments = entryPath.Split('/', StringSplitOptions.RemoveEmptyEntries);
                    if (System.IO.Path.IsPathRooted(entryPath) ||
                        segments.Any(segment => segment is "." or ".."))
                        throw new System.IO.IOException(
                            $"Archive `{archiveUri.LocalPath}` contains unsafe entry `{entry.FullName}`.");
                    if (segments.Length == 0) continue;
                    var destination = System.IO.Path.GetFullPath(
                        System.IO.Path.Combine(new[] { root }.Concat(segments).ToArray()));
                    var relative = System.IO.Path.GetRelativePath(root, destination);
                    if (System.IO.Path.IsPathRooted(relative) || relative == ".." ||
                        relative.StartsWith(".." + System.IO.Path.DirectorySeparatorChar,
                            StringComparison.Ordinal))
                        throw new System.IO.IOException(
                            $"Archive `{archiveUri.LocalPath}` entry `{entry.FullName}` escapes its root.");
                    if (entryPath.EndsWith("/", StringComparison.Ordinal))
                    {
                        System.IO.Directory.CreateDirectory(destination);
                        continue;
                    }
                    System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(destination)!);
                    System.IO.Compression.ZipFileExtensions.ExtractToFile(
                        entry, destination, overwrite: false);
                }
            }
            catch
            {
                try { System.IO.Directory.Delete(root, recursive: true); }
                catch { }
                throw;
            }
        }

        public override IEnumerable<string> GetRootDirectories() => new[] { root };
        public override bool IsOpen() => open;
        public override bool IsReadOnly() => true;
        public override string GetPath(string first, params string[] more)
        {
            var candidate = System.IO.Path.GetFullPath(System.IO.Path.Combine(
                new[] { root, first.TrimStart('/', '\\') }.Concat(more).ToArray()));
            var relative = System.IO.Path.GetRelativePath(root, candidate);
            if (System.IO.Path.IsPathRooted(relative) || relative == ".." ||
                relative.StartsWith(".." + System.IO.Path.DirectorySeparatorChar,
                    StringComparison.Ordinal) ||
                relative.StartsWith(".." + System.IO.Path.AltDirectorySeparatorChar,
                    StringComparison.Ordinal))
                throw new System.IO.IOException(
                    $"Archive path `{first}` escapes its package root.");
            return candidate;
        }
        public override void Close()
        {
            if (!open) return;
            open = false;
            if (System.IO.Directory.Exists(root))
                System.IO.Directory.Delete(root, recursive: true);
        }
    }

    internal class JavaProxySelector
    {
        public static JavaProxySelector GetDefault() => new();
        public virtual IList<System.Net.WebProxy> Select(Uri uri) => new[] { new System.Net.WebProxy() };
        public virtual void ConnectFailed(Uri uri, System.Net.EndPoint address, System.IO.IOException error) { }
    }
    internal static class JavaFileSystems
    {
        public static JavaFileSystem GetDefault() => new();
        public static JavaFileSystem GetFileSystem(Uri uri) => new();
        public static JavaFileSystem NewFileSystem(Uri uri, IDictionary<string, object> environment) =>
            string.Equals(uri.Scheme, "jar", StringComparison.OrdinalIgnoreCase)
                ? new JavaZipFileSystem(uri)
                : new JavaFileSystem();
    }
    internal sealed class JavaFileSystemProvider
    {
        public static IEnumerable<JavaFileSystemProvider> InstalledProviders() => new[] { new JavaFileSystemProvider() };
        public string GetScheme() => "file";
    }
    internal abstract class JavaFileTypeDetector
    {
        public abstract string? ProbeContentType(string path);
    }
    internal sealed class JavaZipEntry
    {
        private readonly string name;
        internal DateTime? TimeLocal { get; private set; }
        public JavaZipEntry(string name) => this.name = name;
        public JavaZipEntry(System.IO.Compression.ZipArchiveEntry entry) => name = entry.FullName;
        public string GetName() => name;
        public bool IsDirectory() => name.EndsWith("/", StringComparison.Ordinal);
        public void SetTimeLocal(DateTime value) => TimeLocal = value;
    }
    internal sealed class JavaZipInputStream : IDisposable
    {
        private readonly System.IO.Compression.ZipArchive archive; private int index = -1; private System.IO.Stream? current;
        public JavaZipInputStream(System.IO.Stream stream) => archive = new(stream, System.IO.Compression.ZipArchiveMode.Read);
        public JavaZipEntry? GetNextEntry()
        {
            current?.Dispose();
            index++;
            if (index >= archive.Entries.Count) return null;
            var entry = archive.Entries[index];
            var entryPath = entry.FullName.Replace('\\', '/');
            var segments = entryPath.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (entryPath.IndexOf('\0') >= 0 || System.IO.Path.IsPathRooted(entryPath) ||
                segments.Any(segment => segment is "." or ".."))
                throw new System.IO.IOException(
                    $"Package archive contains unsafe entry `{entry.FullName}`.");
            current = entry.Open();
            return new(entry);
        }
        public byte[] ReadAllBytes() { using var memory = new System.IO.MemoryStream(); current!.CopyTo(memory); return memory.ToArray(); }
        public void CloseEntry() { current?.Dispose(); current = null; }
        public void Dispose() => archive.Dispose();
    }
    internal sealed class JavaZipOutputStream : System.IO.Stream
    {
        private readonly System.IO.Compression.ZipArchive archive; private System.IO.Stream? current;
        public JavaZipOutputStream(System.IO.Stream stream) => archive = new(stream, System.IO.Compression.ZipArchiveMode.Create, true);
        public void PutNextEntry(JavaZipEntry entry) { current?.Dispose(); var created = archive.CreateEntry(entry.GetName()); if (entry.TimeLocal is DateTime value) created.LastWriteTime = value; current = created.Open(); }
        public void Write(byte[] bytes) => current!.Write(bytes);
        public void CloseEntry() { current?.Dispose(); current = null; }
        public void Finish() => Dispose();
        public override bool CanRead => false; public override bool CanSeek => false; public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => current?.Flush();
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, System.IO.SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => current!.SetLength(value);
        public override void Write(byte[] buffer, int offset, int count) => current!.Write(buffer, offset, count);
        protected override void Dispose(bool disposing) { if (disposing) { current?.Dispose(); archive.Dispose(); } base.Dispose(disposing); }
    }

    internal sealed class JavaMessageDigest
    {
        private readonly System.Security.Cryptography.IncrementalHash hash;
        private JavaMessageDigest(System.Security.Cryptography.HashAlgorithmName algorithm) =>
            hash = System.Security.Cryptography.IncrementalHash.CreateHash(algorithm);
        public static JavaMessageDigest GetInstance(string name) =>
            new(new System.Security.Cryptography.HashAlgorithmName(name.Replace("-", "", StringComparison.Ordinal)));
        public void Update(byte[] bytes) => hash.AppendData(bytes);
        public void Update(sbyte[] bytes) => hash.AppendData(bytes.Select(value => unchecked((byte)value)).ToArray());
        public sbyte[] Digest() => hash.GetHashAndReset().Select(value => unchecked((sbyte)value)).ToArray();
        public sbyte[] Digest(sbyte[] bytes)
        {
            Update(bytes);
            return Digest();
        }
    }

    internal sealed class JavaDigestInputStream : System.IO.Stream
    {
        private readonly System.IO.Stream stream;
        private readonly JavaMessageDigest digest;
        public JavaDigestInputStream(System.IO.Stream stream, JavaMessageDigest digest)
        { this.stream = stream; this.digest = digest; }
        public override int Read(byte[] buffer, int offset, int count)
        { var read = stream.Read(buffer, offset, count); digest.Update(buffer[offset..(offset + read)]); return read; }
        public byte[] ReadAllBytes() { using var output = new System.IO.MemoryStream(); CopyTo(output); return output.ToArray(); }
        public JavaMessageDigest GetMessageDigest() => digest;
        public override bool CanRead => stream.CanRead; public override bool CanSeek => false; public override bool CanWrite => false;
        public override long Length => stream.Length; public override long Position { get => stream.Position; set => throw new NotSupportedException(); }
        public override void Flush() => stream.Flush(); public override long Seek(long o, System.IO.SeekOrigin so) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException(); public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing) stream.Dispose();
            base.Dispose(disposing);
        }
    }

    internal sealed class JavaDigestOutputStream : System.IO.Stream
    {
        private readonly System.IO.Stream stream; private readonly JavaMessageDigest digest;
        public JavaDigestOutputStream(System.IO.Stream stream, JavaMessageDigest digest) { this.stream = stream; this.digest = digest; }
        public override void Write(byte[] buffer, int offset, int count) { stream.Write(buffer, offset, count); digest.Update(buffer[offset..(offset + count)]); }
        public JavaMessageDigest GetMessageDigest() => digest;
        public override bool CanRead => false; public override bool CanSeek => false; public override bool CanWrite => stream.CanWrite;
        public override long Length => stream.Length; public override long Position { get => stream.Position; set => throw new NotSupportedException(); }
        public override void Flush() => stream.Flush(); public override int Read(byte[] b, int o, int c) => throw new NotSupportedException();
        public override long Seek(long o, System.IO.SeekOrigin so) => throw new NotSupportedException(); public override void SetLength(long v) => stream.SetLength(v);
    }

    internal sealed class JavaSecureRandom { }
    internal sealed class JavaKeyStore
    {
        private readonly List<System.Security.Cryptography.X509Certificates.X509Certificate2> certificates = new();
        internal IReadOnlyList<System.Security.Cryptography.X509Certificates.X509Certificate2> Certificates => certificates;
        public static string GetDefaultType() => "X509";
        public static JavaKeyStore GetInstance(string type) => new();
        public void Load(object? parameter) { }
        public void SetCertificateEntry(string alias, System.Security.Cryptography.X509Certificates.X509Certificate2 certificate) =>
            certificates.Add(certificate);
    }
    internal sealed class JavaSslContext
    {
        private readonly List<System.Security.Cryptography.X509Certificates.X509Certificate2> trustAnchors = new();
        internal IReadOnlyList<System.Security.Cryptography.X509Certificates.X509Certificate2> TrustAnchors => trustAnchors;
        public static JavaSslContext GetDefault() => new();
        public static JavaSslContext GetInstance(string protocol) => new();
        public void Init(object? keyManagers, object? trustManagers, JavaSecureRandom random)
        {
            trustAnchors.Clear();
            if (trustManagers is not IEnumerable<object> managers) return;
            trustAnchors.AddRange(managers.OfType<System.Security.Cryptography.X509Certificates.X509Certificate2>());
            foreach (var manager in managers.OfType<global::DripSharp.Runtime.JavaTrustManager>())
                trustAnchors.AddRange(manager.Certificates);
        }
    }
    internal sealed class JavaTrustManagerFactory
    {
        private JavaKeyStore? keyStore;
        public static JavaTrustManagerFactory GetInstance(string algorithm) => new();
        public void Init(JavaKeyStore keyStore) => this.keyStore = keyStore;
        public object[] GetTrustManagers() => keyStore?.Certificates.Cast<object>().ToArray() ?? Array.Empty<object>();
    }

    internal enum JavaHttpRedirect { NEVER, NORMAL, ALWAYS }
    internal enum JavaProxyType { DIRECT, HTTP, SOCKS }
    internal enum JavaHttpVersion { HTTP_1_1, HTTP_2 }
    internal delegate T JavaHttpBodyHandler<T>(System.Net.Http.HttpResponseMessage response);

    internal sealed class JavaHttpResponse<T>
    {
        private readonly int statusCode;
        private readonly JavaHttpRequest request;
        private readonly Uri uri;
        private readonly JavaHttpHeaders headers;
        private readonly JavaHttpVersion version;
        private readonly T body;
        public JavaHttpResponse(System.Net.Http.HttpResponseMessage response, T body)
        {
            statusCode = (int)response.StatusCode;
            request = new JavaHttpRequest(response.RequestMessage!);
            uri = response.RequestMessage?.RequestUri ?? new Uri("about:blank");
            headers = new JavaHttpHeaders(response.Headers.Concat(response.Content.Headers)
                .ToDictionary(header => header.Key,
                              header => (IReadOnlyList<string>)header.Value.ToList(),
                              StringComparer.OrdinalIgnoreCase));
            version = response.Version.Major >= 2 ? JavaHttpVersion.HTTP_2 : JavaHttpVersion.HTTP_1_1;
            this.body = body;
        }
        public int StatusCode() => statusCode;
        public T Body() => body;
        public JavaHttpRequest Request() => request;
        public JavaOptional<JavaHttpResponse<T>> PreviousResponse() => JavaOptional<JavaHttpResponse<T>>.Empty();
        public Uri Uri() => uri;
        public JavaHttpHeaders Headers() => headers;
        public JavaHttpVersion Version() => version;
    }

    internal sealed class JavaHttpRequest
    {
        internal System.Net.Http.HttpRequestMessage Message { get; }
        private readonly TimeSpan? timeout;
        public JavaHttpRequest(System.Net.Http.HttpRequestMessage message, TimeSpan? timeout = null)
        { Message = message; this.timeout = timeout; }
        public static Builder NewBuilder() => new();
        public static Builder NewBuilder(Uri uri) => new Builder().Uri(uri);
        public Uri Uri() => Message.RequestUri!;
        public JavaHttpHeaders Headers() => new(Message.Headers.Concat(Message.Content?.Headers ?? Enumerable.Empty<KeyValuePair<string, IEnumerable<string>>>())
            .ToDictionary(header => header.Key,
                          header => (IReadOnlyList<string>)header.Value.ToList(),
                          StringComparer.OrdinalIgnoreCase));
        public bool ExpectContinue() => Message.Headers.ExpectContinue ?? false;
        public string Method() => Message.Method.Method;
        public JavaOptional<TimeSpan> Timeout() => timeout.HasValue
            ? JavaOptional<TimeSpan>.Of(timeout.Value)
            : JavaOptional<TimeSpan>.Empty();
        public JavaOptional<JavaHttpVersion> Version() =>
            JavaOptional<JavaHttpVersion>.Of(Message.Version.Major >= 2 ? JavaHttpVersion.HTTP_2 : JavaHttpVersion.HTTP_1_1);
        public JavaOptional<object> BodyPublisher() => JavaOptional<object>.OfNullable(Message.Content);
        public sealed class Builder
        {
            private readonly System.Net.Http.HttpRequestMessage message = new();
            private TimeSpan? timeout;
            public Builder Uri(Uri uri) { message.RequestUri = uri; return this; }
            public Builder Timeout(TimeSpan timeout) { this.timeout = timeout; return this; }
            public Builder Version(JavaHttpVersion version)
            {
                message.Version = version == JavaHttpVersion.HTTP_2
                    ? System.Net.HttpVersion.Version20
                    : System.Net.HttpVersion.Version11;
                message.VersionPolicy = System.Net.Http.HttpVersionPolicy.RequestVersionOrLower;
                return this;
            }
            public Builder Header(string name, string value) { message.Headers.TryAddWithoutValidation(name, value); return this; }
            public Builder SetHeader(string name, string value) { message.Headers.Remove(name); return Header(name, value); }
            public Builder ExpectContinue(bool value) { message.Headers.ExpectContinue = value; return this; }
            public Builder Method(string method, object? body)
            {
                message.Method = new System.Net.Http.HttpMethod(method);
                message.Content = body as System.Net.Http.HttpContent;
                return this;
            }
            public Builder GET() { message.Method = System.Net.Http.HttpMethod.Get; return this; }
            public Builder DELETE() { message.Method = System.Net.Http.HttpMethod.Delete; return this; }
            public JavaHttpRequest Build() => new(message, timeout);
        }
    }

    internal static class JavaHttpBodyPublishers
    {
        public static object NoBody() => new object();
    }

    internal sealed class JavaHttpHeaders
    {
        private readonly IReadOnlyDictionary<string, IReadOnlyList<string>> values;
        public JavaHttpHeaders(IReadOnlyDictionary<string, IReadOnlyList<string>> values) => this.values = values;
        public JavaOptional<string> FirstValue(string name) =>
            values.TryGetValue(name, out var entries) && entries.Count > 0
                ? JavaOptional<string>.Of(entries[0]) : JavaOptional<string>.Empty();
        public IReadOnlyDictionary<string, IReadOnlyList<string>> Map() => values;
    }

    internal static class JavaHttpBodyHandlers
    {
        public static JavaHttpBodyHandler<System.IO.Stream> OfInputStream() =>
            response => new ResponseOwningStream(response.Content.ReadAsStream(), response);
        public static JavaHttpBodyHandler<sbyte[]> OfByteArray() =>
            response => response.Content.ReadAsByteArrayAsync(
                    global::DripSharp.Runtime.JavaCancellation.CurrentToken).GetAwaiter().GetResult()
                .Select(value => unchecked((sbyte)value)).ToArray();

        private sealed class ResponseOwningStream : System.IO.Stream
        {
            private readonly System.IO.Stream stream;
            private readonly System.Net.Http.HttpResponseMessage response;
            internal ResponseOwningStream(System.IO.Stream stream, System.Net.Http.HttpResponseMessage response)
            { this.stream = stream; this.response = response; }
            public override bool CanRead => stream.CanRead;
            public override bool CanSeek => stream.CanSeek;
            public override bool CanWrite => false;
            public override long Length => stream.Length;
            public override long Position { get => stream.Position; set => stream.Position = value; }
            public override void Flush() => stream.Flush();
            public override int Read(byte[] buffer, int offset, int count) =>
                stream.ReadAsync(buffer.AsMemory(offset, count),
                    global::DripSharp.Runtime.JavaCancellation.CurrentToken).AsTask()
                    .GetAwaiter().GetResult();
            public override long Seek(long offset, System.IO.SeekOrigin origin) => stream.Seek(offset, origin);
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
            protected override void Dispose(bool disposing)
            {
                if (disposing)
                {
                    stream.Dispose();
                    response.Dispose();
                }
                base.Dispose(disposing);
            }
        }
    }

    internal sealed class JavaHttpClient : IDisposable
    {
        private readonly System.Net.Http.HttpClient client;
        private readonly List<System.Security.Cryptography.X509Certificates.X509Certificate2> trustAnchors;
        private bool disposed;
        private JavaHttpClient(
            TimeSpan connectTimeout,
            JavaSslContext sslContext,
            JavaProxySelector proxySelector)
        {
#pragma warning disable SYSLIB0057 // net8-compatible certificate construction
            trustAnchors = sslContext.TrustAnchors
                .Select(certificate => new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate.RawData))
                .ToList();
#pragma warning restore SYSLIB0057
            var handler = new System.Net.Http.SocketsHttpHandler
            {
                AllowAutoRedirect = false,
                ConnectTimeout = connectTimeout,
                Proxy = new SelectorWebProxy(proxySelector),
                UseProxy = true
            };
            if (trustAnchors.Count > 0)
            {
                handler.SslOptions.RemoteCertificateValidationCallback = (_, certificate, _, errors) =>
                    ValidateServerCertificate(certificate, errors);
            }
            client = new System.Net.Http.HttpClient(handler, disposeHandler: true);
        }
        public static Builder NewBuilder() => new();
        public JavaHttpResponse<T> Send<T>(JavaHttpRequest request, JavaHttpBodyHandler<T> handler)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            var evaluationCancellation = global::DripSharp.Runtime.JavaCancellation.CurrentToken;
            using var cancellation = evaluationCancellation.CanBeCanceled
                ? System.Threading.CancellationTokenSource.CreateLinkedTokenSource(evaluationCancellation)
                : new System.Threading.CancellationTokenSource();
            if (request.Timeout().IsPresent()) cancellation.CancelAfter(request.Timeout().Get());
            System.Net.Http.HttpResponseMessage response;
            try
            {
                response = client.Send(
                    request.Message,
                    System.Net.Http.HttpCompletionOption.ResponseHeadersRead,
                    cancellation.Token);
            }
            catch (OperationCanceledException) when (evaluationCancellation.IsCancellationRequested)
            {
                throw new global::DripSharp.Runtime.JavaCancellationException(
                    evaluationCancellation);
            }
            catch (OperationCanceledException error) when (cancellation.IsCancellationRequested)
            {
                throw new System.IO.IOException(
                    $"HTTP request to `{request.Uri()}` timed out.", error);
            }
            try
            {
                var body = handler(response);
                var result = new JavaHttpResponse<T>(response, body);
                if (body is not System.IO.Stream) response.Dispose();
                return result;
            }
            catch
            {
                response.Dispose();
                throw;
            }
        }
        public void Close() => Dispose();
        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            client.Dispose();
            foreach (var certificate in trustAnchors) certificate.Dispose();
            trustAnchors.Clear();
        }

        private bool ValidateServerCertificate(
            System.Security.Cryptography.X509Certificates.X509Certificate? certificate,
            System.Net.Security.SslPolicyErrors errors)
        {
            if (certificate is null ||
                (errors & (System.Net.Security.SslPolicyErrors.RemoteCertificateNameMismatch |
                           System.Net.Security.SslPolicyErrors.RemoteCertificateNotAvailable)) != 0)
                return false;
#pragma warning disable SYSLIB0057 // net8-compatible certificate construction
            using var serverCertificate = new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate);
#pragma warning restore SYSLIB0057
            using var chain = new System.Security.Cryptography.X509Certificates.X509Chain();
            chain.ChainPolicy.TrustMode = System.Security.Cryptography.X509Certificates.X509ChainTrustMode.CustomRootTrust;
            chain.ChainPolicy.RevocationMode = System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck;
            foreach (var trustAnchor in trustAnchors) chain.ChainPolicy.CustomTrustStore.Add(trustAnchor);
            return chain.Build(serverCertificate);
        }

        private sealed class SelectorWebProxy : System.Net.IWebProxy
        {
            private readonly JavaProxySelector selector;
            internal SelectorWebProxy(JavaProxySelector selector) => this.selector = selector;
            public System.Net.ICredentials? Credentials { get; set; }
            public Uri GetProxy(Uri destination)
            {
                var selected = selector.Select(destination).FirstOrDefault();
                if (selected is null || selected.Address is null || selected.IsBypassed(destination))
                    return destination;
                return selected.GetProxy(destination);
            }
            public bool IsBypassed(Uri host) => GetProxy(host) == host;
        }

        public sealed class Builder
        {
            private TimeSpan connectTimeout = TimeSpan.FromSeconds(60);
            private JavaSslContext sslContext = JavaSslContext.GetDefault();
            private JavaProxySelector proxySelector = JavaProxySelector.GetDefault();
            public Builder ConnectTimeout(TimeSpan timeout) { connectTimeout = timeout; return this; }
            public Builder FollowRedirects(JavaHttpRedirect redirect) => this;
            public Builder Version(JavaHttpVersion version) => this;
            public Builder SslContext(object context)
            { sslContext = (JavaSslContext)context; return this; }
            public Builder Proxy(object proxySelector)
            { this.proxySelector = (JavaProxySelector)proxySelector; return this; }
            public JavaHttpClient Build() => new(connectTimeout, sslContext, proxySelector);
        }
    }
}

namespace DripSharp.Runtime
{
    // Pkl deliberately represents java.math.BigDecimal with System.Decimal.
    // These operations are destination-specific so targets that preserve the
    // complete Java decimal model remain on JavaBigDecimal.
    internal static partial class JavaCompat
    {
        internal static decimal BigDecimalParse(string value) =>
            decimal.Parse(
                value,
                global::System.Globalization.NumberStyles.Number |
                    global::System.Globalization.NumberStyles.AllowExponent,
                global::System.Globalization.CultureInfo.InvariantCulture);

        internal static decimal BigDecimalValueOf(double value) =>
            BigDecimalParse(value.ToString(
                "R", global::System.Globalization.CultureInfo.InvariantCulture));

        internal static decimal BigDecimalMultiply(decimal left, decimal right) =>
            checked(left * right);

        internal static decimal BigDecimalDivide(
            decimal left,
            decimal right,
            int scale,
            global::DripSharp.Runtime.JavaRoundingMode roundingMode) =>
            BigDecimalRound(left / right, scale, roundingMode);

        internal static decimal BigDecimalSetScale(
            decimal value,
            int scale,
            global::DripSharp.Runtime.JavaRoundingMode roundingMode) =>
            BigDecimalRound(value, scale, roundingMode);

        private static decimal BigDecimalRound(
            decimal value,
            int scale,
            global::DripSharp.Runtime.JavaRoundingMode roundingMode)
        {
            if (scale is < 0 or > 28)
                throw new global::System.ArithmeticException(
                    "Scale is outside System.Decimal range.");
            return roundingMode switch
            {
                global::DripSharp.Runtime.JavaRoundingMode.Down => decimal.Round(
                    value, scale, global::System.MidpointRounding.ToZero),
                global::DripSharp.Runtime.JavaRoundingMode.Ceiling => decimal.Round(
                    value, scale, global::System.MidpointRounding.ToPositiveInfinity),
                global::DripSharp.Runtime.JavaRoundingMode.Floor => decimal.Round(
                    value, scale, global::System.MidpointRounding.ToNegativeInfinity),
                global::DripSharp.Runtime.JavaRoundingMode.HalfUp => decimal.Round(
                    value, scale, global::System.MidpointRounding.AwayFromZero),
                global::DripSharp.Runtime.JavaRoundingMode.HalfEven => decimal.Round(
                    value, scale, global::System.MidpointRounding.ToEven),
                global::DripSharp.Runtime.JavaRoundingMode.Unnecessary
                    when value == decimal.Round(
                        value, scale, global::System.MidpointRounding.ToZero) => value,
                _ => throw new global::System.ArgumentOutOfRangeException(
                    nameof(roundingMode))
            };
        }

        internal static int BigDecimalIntValue(decimal value) =>
            decimal.ToInt32(decimal.Truncate(value));

        internal static decimal BigDecimalStripTrailingZeros(decimal value)
        {
            if (value == 0) return decimal.Zero;
            return decimal.Parse(
                value.ToString(
                    "G29", global::System.Globalization.CultureInfo.InvariantCulture),
                global::System.Globalization.NumberStyles.Number,
                global::System.Globalization.CultureInfo.InvariantCulture);
        }

        internal static string BigDecimalToPlainString(decimal value) =>
            value.ToString(global::System.Globalization.CultureInfo.InvariantCulture);

        internal static string BigDecimalToString(decimal value) =>
            value.ToString(global::System.Globalization.CultureInfo.InvariantCulture);
    }
}

namespace DripSharp.Brine.Runtime.Polyglot
{
    internal sealed class PolyglotException : Exception
    {
        public PolyglotException(string? message = null, Exception? cause = null)
            : base(message, cause) { }

        public bool IsCancelled() => false;
    }

    internal sealed class Engine
    {
        public static Builder NewBuilder(params string[] languages) => new();

        public sealed class Builder
        {
            public Builder Option(string key, string value) => this;
            public Engine Build() => new();
        }
    }

    internal sealed class Context : IDisposable
    {
        private readonly object lifecycleLock = new();
        private readonly System.Threading.CancellationTokenSource cancellation = new();
        private readonly Dictionary<System.Threading.Thread, int> executingThreads = new();
        private readonly System.Threading.ManualResetEventSlim noExecutions = new(true);
        private global::DripSharp.Brine.Runtime.VmContext? vmContext;
        private bool initialized;
        private bool closed;
        public static Builder NewBuilder(params string[] languages) => new();
        public void Initialize(string language)
        {
            lock (lifecycleLock)
            {
                ObjectDisposedException.ThrowIf(closed, this);
                if (language != "pkl" || initialized) return;
                var vmLanguage = new global::DripSharp.Brine.Runtime.VmLanguage();
                vmContext = vmLanguage.CreateContext(
                    new global::DripSharp.Brine.Runtime.Truffle.api.TruffleLanguage.Env());
                initialized = true;
            }
        }
        public void Enter()
        {
            global::DripSharp.Brine.Runtime.VmContext context;
            var thread = System.Threading.Thread.CurrentThread;
            var cancellationPushed = false;
            lock (lifecycleLock)
            {
                ObjectDisposedException.ThrowIf(closed, this);
                context = initialized && vmContext is not null
                    ? vmContext
                    : throw new InvalidOperationException("Pkl context has not been initialized.");
                noExecutions.Reset();
                executingThreads.TryGetValue(thread, out var depth);
                executingThreads[thread] = depth + 1;
            }
            try
            {
                global::DripSharp.Runtime.JavaCancellation.Push(this, cancellation.Token);
                cancellationPushed = true;
                global::DripSharp.Brine.Runtime.Truffle.api.TruffleLanguage.InstallContext(
                    typeof(global::DripSharp.Brine.Runtime.VmLanguage), context, this);
            }
            catch
            {
                if (cancellationPushed)
                    global::DripSharp.Runtime.JavaCancellation.Pop(this);
                UnregisterExecution(thread);
                throw;
            }
        }
        public void Leave()
        {
            try
            {
                global::DripSharp.Brine.Runtime.Truffle.api.TruffleLanguage.RemoveContext(
                    typeof(global::DripSharp.Brine.Runtime.VmLanguage), this);
            }
            finally
            {
                global::DripSharp.Runtime.JavaCancellation.Pop(this);
                UnregisterExecution(System.Threading.Thread.CurrentThread);
            }
        }
        public void Close() => Close(false);
        public void Close(bool cancelIfExecuting)
        {
            System.Threading.Thread[] activeThreads;
            lock (lifecycleLock)
            {
                if (!closed)
                {
                    closed = true;
                    initialized = false;
                    vmContext = null;
                }
                activeThreads = executingThreads.Keys.ToArray();
            }
            cancellation.Cancel();
            var currentThread = System.Threading.Thread.CurrentThread;
            foreach (var thread in activeThreads)
            {
                if (ReferenceEquals(thread, currentThread)) continue;
                try { thread.Interrupt(); }
                catch (System.Threading.ThreadStateException) { }
            }
            if (cancelIfExecuting && !activeThreads.Contains(currentThread)) noExecutions.Wait();
            global::DripSharp.Brine.Runtime.Truffle.api.TruffleLanguage.RemoveContext(
                typeof(global::DripSharp.Brine.Runtime.VmLanguage), this, removeAll: true);
        }
        internal bool IsClosed
        {
            get { lock (lifecycleLock) return closed; }
        }
        internal bool IsCancellationRequested => cancellation.IsCancellationRequested;
        public void Dispose() => Close(false);

        private void UnregisterExecution(System.Threading.Thread thread)
        {
            lock (lifecycleLock)
            {
                if (!executingThreads.TryGetValue(thread, out var depth)) return;
                if (depth == 1) executingThreads.Remove(thread);
                else executingThreads[thread] = depth - 1;
                if (executingThreads.Count == 0) noExecutions.Set();
            }
        }

        public sealed class Builder
        {
            public Builder Engine(Engine engine) => this;
            public Context Build() => new();
        }
    }
}

namespace DripSharp.Brine.Runtime.GraalCollections
{
    internal class UnmodifiableEconomicMap<K, V> :
        global::DripSharp.Runtime.IJavaEconomicMap<K, V> where K : notnull
    {
        protected readonly Dictionary<K, V> Values;

        protected UnmodifiableEconomicMap(Dictionary<K, V>? values = null) =>
            Values = values ?? new Dictionary<K, V>();

        internal V? Get(K key) => Values.TryGetValue(key, out var value) ? value : default;
        internal bool ContainsKey(K key) => Values.ContainsKey(key);
        internal int Size() => Values.Count;
        internal bool IsEmpty() => Values.Count == 0;
        internal IEnumerable<V> GetValues() => Values.Values;
        internal IEnumerable<K> GetKeys() => Values.Keys;
        internal UnmodifiableMapCursor<K, V> GetEntries() => new(Values.GetEnumerator());

        V? global::DripSharp.Runtime.IJavaEconomicMap<K, V>.Get(K key) => Get(key);
        bool global::DripSharp.Runtime.IJavaEconomicMap<K, V>.ContainsKey(K key) => ContainsKey(key);
        int global::DripSharp.Runtime.IJavaEconomicMap<K, V>.Size() => Size();
        global::DripSharp.Runtime.IJavaEconomicMapCursor<K, V>
            global::DripSharp.Runtime.IJavaEconomicMap<K, V>.GetEntries() => GetEntries();
    }

    internal sealed class EconomicMap<K, V> : UnmodifiableEconomicMap<K, V> where K : notnull
    {
        internal EconomicMap(int capacity = 0) : base(new Dictionary<K, V>(capacity)) { }

        internal V? Put(K key, V? value)
        {
            Values.TryGetValue(key, out var previous);
            Values[key] = value!;
            return previous;
        }

        internal void PutAll(UnmodifiableEconomicMap<K, V> other)
        {
            foreach (var entry in other.GetEntries().Entries()) Values[entry.Key] = entry.Value;
        }
        internal V? PutIfAbsent(K key, V value)
        {
            if (Values.TryGetValue(key, out var previous)) return previous;
            Values[key] = value;
            return default;
        }
        internal V? RemoveKey(K key)
        {
            if (Values.Remove(key, out var previous)) return previous;
            return default;
        }
        internal void Clear() => Values.Clear();
        internal EconomicMap<K, V> DeepCopy()
        {
            var result = new EconomicMap<K, V>(Values.Count);
            foreach (var entry in Values) result.Values[entry.Key] = entry.Value;
            return result;
        }
        internal static EconomicMap<K, V> Create() => new();
        internal static EconomicMap<K, V> Create(int capacity) => new(capacity);
        internal static EconomicMap<K2, V2> Create<K2, V2>() where K2 : notnull => new();
        internal static EconomicMap<K2, V2> Create<K2, V2>(int capacity) where K2 : notnull => new(capacity);
        internal static UnmodifiableEconomicMap<K2, V2> EmptyMap<K2, V2>() where K2 : notnull => new EconomicMap<K2, V2>();
    }

    internal static class EconomicMap
    {
        internal static EconomicMap<K, V> Create<K, V>() where K : notnull => new();
        internal static EconomicMap<K, V> Create<K, V>(int capacity) where K : notnull => new(capacity);
    }

    internal class UnmodifiableEconomicSet<T> : IEnumerable<T> where T : notnull
    {
        protected readonly HashSet<T> Values;
        protected UnmodifiableEconomicSet(HashSet<T>? values = null) => Values = values ?? new HashSet<T>();
        internal bool Contains(T value) => Values.Contains(value);
        internal int Size() => Values.Count;
        internal bool IsEmpty() => Values.Count == 0;
        internal IEnumerable<T> Items() => Values;
        public IEnumerator<T> GetEnumerator() => Values.GetEnumerator();
        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    }

    internal sealed class EconomicSet<T> : UnmodifiableEconomicSet<T> where T : notnull
    {
        internal EconomicSet(int capacity = 0) : base(new HashSet<T>(capacity)) { }
        internal bool Add(T value) => Values.Add(value);
        internal void AddAll(UnmodifiableEconomicSet<T> other) => Values.UnionWith(other.Items());
        internal void Clear() => Values.Clear();
        internal static EconomicSet<T> Create() => new();
        internal static EconomicSet<T> Create(int capacity) => new(capacity);
        internal static EconomicSet<T2> Create<T2>() where T2 : notnull => new();
        internal static EconomicSet<T2> Create<T2>(int capacity) where T2 : notnull => new(capacity);
    }

    internal static class EconomicSet
    {
        internal static EconomicSet<T> Create<T>() where T : notnull => new();
        internal static EconomicSet<T> Create<T>(int capacity) where T : notnull => new(capacity);
    }

    internal sealed class UnmodifiableMapCursor<K, V> :
        global::DripSharp.Runtime.IJavaEconomicMapCursor<K, V> where K : notnull
    {
        private readonly IEnumerator<KeyValuePair<K, V>> entries;
        internal UnmodifiableMapCursor(IEnumerator<KeyValuePair<K, V>> entries) => this.entries = entries;
        internal bool Advance() => entries.MoveNext();
        internal K GetKey() => entries.Current.Key;
        internal V GetValue() => entries.Current.Value;

        bool global::DripSharp.Runtime.IJavaEconomicMapCursor<K, V>.Advance() => Advance();
        K global::DripSharp.Runtime.IJavaEconomicMapCursor<K, V>.GetKey() => GetKey();
        V global::DripSharp.Runtime.IJavaEconomicMapCursor<K, V>.GetValue() => GetValue();
        internal IEnumerable<KeyValuePair<K, V>> Entries()
        {
            while (entries.MoveNext()) yield return entries.Current;
        }
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api.source
{
    internal sealed class Source
    {
        private readonly string characters;
        private readonly string name;
        private readonly Uri? uri;
        private Source(string characters, string name, Uri? uri)
        { this.characters = characters; this.name = name; this.uri = uri; }

        internal static Builder NewBuilder(string language, string characters, string name) =>
            new(characters, name);
        internal string GetName() => name;
        internal int GetLength() => characters.Length;
        internal string GetCharacters() => characters;
        internal string GetCharacters(int line)
        {
            if (line < 1) throw new ArgumentOutOfRangeException(nameof(line));
            var currentLine = 1;
            var lineStart = 0;
            for (var index = 0; index <= characters.Length; index++)
            {
                if (index != characters.Length && characters[index] != '\n') continue;
                if (currentLine == line)
                {
                    var lineEnd = index;
                    if (lineEnd > lineStart && characters[lineEnd - 1] == '\r') lineEnd--;
                    return characters.Substring(lineStart, lineEnd - lineStart);
                }
                currentLine++;
                lineStart = index + 1;
            }
            throw new ArgumentOutOfRangeException(nameof(line));
        }
        internal SourceSection CreateSection(int start, int length) => new(this, start, length, false);
        internal SourceSection CreateSection(int line)
        {
            if (line < 1) throw new ArgumentOutOfRangeException(nameof(line));
            var currentLine = 1;
            var lineStart = 0;
            for (var index = 0; index <= characters.Length; index++)
            {
                if (index != characters.Length && characters[index] != '\n') continue;
                if (currentLine == line)
                {
                    var lineEnd = index;
                    if (lineEnd > lineStart && characters[lineEnd - 1] == '\r') lineEnd--;
                    return CreateSection(lineStart, lineEnd - lineStart);
                }
                currentLine++;
                lineStart = index + 1;
            }
            throw new ArgumentOutOfRangeException(nameof(line));
        }
        internal SourceSection CreateSourceSection(int start, int length) => CreateSection(start, length);
        internal SourceSection CreateUnavailableSection() => new(this, 0, 0, true);
        internal Uri? GetUri() => uri;
        internal Uri? GetURI() => uri;

        public sealed class Builder
        {
            private readonly string characters;
            private readonly string name;
            private Uri? uri;
            internal Builder(string characters, string name)
            { this.characters = characters; this.name = name; }
            internal Builder MimeType(string value) => this;
            internal Builder Uri(Uri value) { uri = value; return this; }
            internal Builder Cached(bool value) => this;
            internal Source Build() => new(characters, name, uri);
        }
    }

    internal sealed class SourceSection
    {
        private readonly Source source;
        private readonly int start;
        private readonly int length;
        private readonly bool unavailable;
        internal SourceSection(Source source, int start, int length, bool unavailable)
        { this.source = source; this.start = start; this.length = length; this.unavailable = unavailable; }
        internal Source GetSource() => source;
        internal int GetCharIndex() => start;
        internal int GetCharLength() => length;
        internal int GetLength() => length;
        internal int GetCharEndIndex() => start + length;
        internal bool IsAvailable() => !unavailable;
        internal string GetCharacters() => unavailable ? string.Empty : source.GetCharacters().Substring(start, length);
        internal int GetStartLine() => GetPosition(start).line;
        internal int GetStartColumn() => GetPosition(start).column;
        internal int GetEndLine() => GetPosition(length == 0 ? start : start + length - 1).line;
        internal int GetEndColumn() => GetPosition(length == 0 ? start : start + length - 1).column;

        private (int line, int column) GetPosition(int offset)
        {
            var characters = source.GetCharacters();
            var boundedOffset = Math.Clamp(offset, 0, characters.Length);
            var line = 1;
            var column = 1;
            for (var index = 0; index < boundedOffset; index++)
            {
                if (characters[index] == '\n') { line++; column = 1; }
                else column++;
            }
            return (line, column);
        }
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api.frame
{
    internal enum FrameSlotKind { Illegal, Object, Long, Double, Boolean }

    internal sealed class FrameSlotTypeException : Exception { }

    internal class Frame
    {
        protected readonly object?[] Arguments;
        private readonly Dictionary<int, object?> values = new();
        private readonly Dictionary<int, object?> auxiliaryValues = new();
        private readonly FrameDescriptor descriptor;

        internal Frame(object?[]? arguments = null, FrameDescriptor? descriptor = null)
        {
            Arguments = arguments ?? Array.Empty<object?>();
            this.descriptor = descriptor ?? new FrameDescriptor();
        }
        internal object?[] GetArguments() => Arguments;
        internal FrameDescriptor GetFrameDescriptor() => descriptor;
        internal object GetValue(int slot) => values.TryGetValue(slot, out var value) ? value! : null!;
        internal object GetObject(int slot) => GetValue(slot);
        internal long GetLong(int slot) => GetValue(slot) is long value ? value : throw new FrameSlotTypeException();
        internal double GetDouble(int slot) => GetValue(slot) is double value ? value : throw new FrameSlotTypeException();
        internal bool GetBoolean(int slot) => GetValue(slot) is bool value ? value : throw new FrameSlotTypeException();
        internal void SetObject(int slot, object? value) => values[slot] = value;
        internal void SetLong(int slot, long value) => values[slot] = value;
        internal void SetDouble(int slot, double value) => values[slot] = value;
        internal void SetBoolean(int slot, bool value) => values[slot] = value;
        internal object? GetAuxiliarySlot(int slot) =>
            auxiliaryValues.TryGetValue(slot, out var value) ? value : null;
        internal void SetAuxiliarySlot(int slot, object? value) => auxiliaryValues[slot] = value;
        protected void CopyStateTo(Frame target)
        {
            foreach (var entry in values) target.values[entry.Key] = entry.Value;
            foreach (var entry in auxiliaryValues) target.auxiliaryValues[entry.Key] = entry.Value;
        }
    }

    internal class VirtualFrame : Frame
    {
        internal VirtualFrame(object?[]? arguments = null) : base(arguments) { }
        internal VirtualFrame(object?[]? arguments, FrameDescriptor? descriptor) : base(arguments, descriptor) { }
        internal MaterializedFrame Materialize()
        {
            var result = new MaterializedFrame(Arguments, GetFrameDescriptor());
            CopyStateTo(result);
            return result;
        }
    }

    internal sealed class MaterializedFrame : VirtualFrame
    {
        internal MaterializedFrame(object?[]? arguments = null, FrameDescriptor? descriptor = null) :
            base(arguments, descriptor) { }
    }

    internal sealed class FrameDescriptor
    {
        private readonly Dictionary<int, FrameSlotKind> slotKinds = new();
        private readonly Dictionary<int, object?> slotNames = new();
        private readonly Dictionary<object, int> auxiliarySlots = new();
        private int slots;

        internal static Builder NewBuilder(int capacity) => new(capacity);
        internal static Builder NewBuilder() => new(0);
        internal FrameSlotKind GetSlotKind(int slot) =>
            slotKinds.TryGetValue(slot, out var kind) ? kind : FrameSlotKind.Illegal;
        internal void SetSlotKind(int slot, FrameSlotKind kind) => slotKinds[slot] = kind;
        internal IDictionary<object, int> GetAuxiliarySlots() => auxiliarySlots;
        internal int GetNumberOfSlots() => slots;
        internal int GetNumberOfAuxiliarySlots() => auxiliarySlots.Count;
        internal object? GetSlotName(int slot) =>
            slotNames.TryGetValue(slot, out var name) ? name : null;
        internal int FindOrAddAuxiliarySlot(object key)
        {
            if (auxiliarySlots.TryGetValue(key, out var slot)) return slot;
            slot = auxiliarySlots.Count;
            auxiliarySlots[key] = slot;
            return slot;
        }
        public sealed class Builder
        {
            private int slots;
            private readonly Dictionary<int, FrameSlotKind> slotKinds = new();
            private readonly Dictionary<int, object?> slotNames = new();
            internal Builder(int capacity) => slots = 0;
            internal int AddSlot(FrameSlotKind kind, object? identifier, object? info)
            {
                var slot = slots++;
                slotKinds[slot] = kind;
                slotNames[slot] = identifier;
                return slot;
            }
            internal FrameDescriptor Build()
            {
                var result = new FrameDescriptor { slots = slots };
                foreach (var entry in slotKinds) result.slotKinds[entry.Key] = entry.Value;
                foreach (var entry in slotNames) result.slotNames[entry.Key] = entry.Value;
                return result;
            }
        }
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api.nodes
{
    using DripSharp.Brine.Runtime.Truffle.api.frame;
    using DripSharp.Brine.Runtime.Truffle.api.source;

    internal class Node
    {
        private Node? parent;
        public virtual SourceSection? GetSourceSection() => null;
        internal Node? GetParent() => parent;
        internal T? Insert<T>(T? child) where T : Node
        {
            if (child is not null)
            {
                child.parent = this;
                child.AdoptChildren();
            }
            return child;
        }
        internal RootNode? GetRootNode()
        {
            Node? current = this;
            while (current is not null && current is not RootNode) current = current.parent;
            return current as RootNode;
        }
        internal void AdoptChildren() =>
            AdoptChildren(new HashSet<Node>(ReferenceEqualityComparer.Instance));

        private void AdoptChildren(ISet<Node> visited)
        {
            if (!visited.Add(this)) return;
            for (Type? type = GetType(); type is not null && type != typeof(object); type = type.BaseType)
            {
                foreach (var field in type.GetFields(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic |
                    BindingFlags.DeclaredOnly))
                {
                    if (field.DeclaringType == typeof(Node) && field.Name == nameof(parent)) continue;
                    var value = field.GetValue(this);
                    if (value is Node child)
                    {
                        AdoptChild(child, visited);
                    }
                    else if (value is IEnumerable children && ContainsNodes(field.FieldType))
                    {
                        foreach (var item in children)
                            if (item is Node itemNode) AdoptChild(itemNode, visited);
                    }
                }
            }
        }

        private void AdoptChild(Node child, ISet<Node> visited)
        {
            if (ReferenceEquals(child, this)) return;
            // Root nodes are independent call targets, not AST children of
            // the node that happens to retain them. Treating nested member
            // roots as children makes their bodies report the surrounding
            // module as their root and breaks both member names and inserted
            // diagnostic frames. A body can also move from an unresolved
            // member root to its resolved root, so normal adoption must update
            // the parent instead of preserving the stale owner.
            if (child is RootNode) return;
            child.parent = this;
            child.AdoptChildren(visited);
        }

        private static bool ContainsNodes(Type type)
        {
            if (type.IsArray)
                return type.GetElementType() is { } elementType &&
                       typeof(Node).IsAssignableFrom(elementType);
            return type.GetInterfaces().Concat(new[] { type }).Any(candidate =>
                candidate.IsGenericType &&
                candidate.GetGenericTypeDefinition() == typeof(IEnumerable<>) &&
                typeof(Node).IsAssignableFrom(candidate.GetGenericArguments()[0]));
        }
        internal Node DeepCopy() => DeepCopy(new Dictionary<Node, Node>(ReferenceEqualityComparer.Instance));

        private Node DeepCopy(IDictionary<Node, Node> copies)
        {
            if (copies.TryGetValue(this, out var existing)) return existing;
            var copy = (Node)MemberwiseClone();
            copies[this] = copy;
            copy.parent = null;
            foreach (var field in NodeFields(GetType()))
            {
                if (field.DeclaringType == typeof(Node) && field.Name == nameof(parent)) continue;
                var value = field.GetValue(this);
                if (value is Node child)
                {
                    field.SetValue(copy, child.DeepCopy(copies));
                }
                else if (value is Array array &&
                         field.FieldType.GetElementType() is { } elementType &&
                         typeof(Node).IsAssignableFrom(elementType))
                {
                    var cloned = (Array)array.Clone();
                    for (var index = 0; index < cloned.Length; index++)
                        if (array.GetValue(index) is Node item)
                            cloned.SetValue(item.DeepCopy(copies), index);
                    field.SetValue(copy, cloned);
                }
            }
            copy.AdoptChildren();
            return copy;
        }

        internal T Replace<T>(T replacement) where T : Node
        {
            var oldParent = parent;
            if (oldParent is not null)
            {
                foreach (var field in NodeFields(oldParent.GetType()))
                {
                    var value = field.GetValue(oldParent);
                    if (ReferenceEquals(value, this))
                    {
                        field.SetValue(oldParent, replacement);
                        break;
                    }
                    if (value is Array array &&
                        field.FieldType.GetElementType() is { } elementType &&
                        typeof(Node).IsAssignableFrom(elementType))
                    {
                        var replaced = false;
                        for (var index = 0; index < array.Length; index++)
                        {
                            if (!ReferenceEquals(array.GetValue(index), this)) continue;
                            array.SetValue(replacement, index);
                            replaced = true;
                            break;
                        }
                        if (replaced) break;
                    }
                }
            }
            replacement.parent = oldParent;
            replacement.AdoptChildren();
            parent = null;
            return replacement;
        }

        internal bool Accept(Func<Node, bool> visitor) =>
            Accept(visitor, new HashSet<Node>(ReferenceEqualityComparer.Instance));

        private bool Accept(Func<Node, bool> visitor, ISet<Node> visited)
        {
            if (!visited.Add(this)) return true;
            if (!visitor(this)) return false;
            foreach (var field in NodeFields(GetType()))
            {
                if (field.DeclaringType == typeof(Node) && field.Name == nameof(parent)) continue;
                var value = field.GetValue(this);
                if (value is Node child)
                {
                    if (!child.Accept(visitor, visited)) return false;
                }
                else if (value is IEnumerable children && ContainsNodes(field.FieldType))
                {
                    foreach (var item in children)
                        if (item is Node itemNode && !itemNode.Accept(visitor, visited)) return false;
                }
            }
            return true;
        }

        private static IEnumerable<FieldInfo> NodeFields(Type type)
        {
            for (Type? current = type; current is not null && current != typeof(object); current = current.BaseType)
                foreach (var field in current.GetFields(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic |
                    BindingFlags.DeclaredOnly))
                    yield return field;
        }

        internal void SetParent(Node? value) => parent = value;
    }

    // Truffle uses this exception family for non-error interpreter control
    // flow.  Keeping it distinct from ordinary failures preserves the
    // generated evaluator's catch behavior.
    internal class ControlFlowException : Exception { }

    internal sealed class UnexpectedResultException : Exception
    {
        private readonly object result;

        public UnexpectedResultException(object result) => this.result = result;

        public object GetResult() => result;
    }

    internal static class LoopNode
    {
        // Truffle consumes this count as a compilation profile hint. The CLR
        // has no corresponding interpreter notification, so evaluation keeps
        // the observable loop behavior while deliberately ignoring the hint.
        public static void ReportLoopCount(Node node, long count) { }
    }

    [AttributeUsage(AttributeTargets.Class, Inherited = true)]
    internal sealed class NodeInfo : Attribute
    {
        private readonly string shortName;
        public NodeInfo(string shortName = "") => this.shortName = shortName;
        public string ShortName() => shortName;
    }

    internal abstract class RootNode : Node
    {
        private readonly FrameDescriptor descriptor;
        private DripSharp.Brine.Runtime.Truffle.api.RootCallTarget? callTarget;
        protected RootNode(object? language, FrameDescriptor descriptor) => this.descriptor = descriptor;
        public virtual object? Execute(VirtualFrame frame) => null;
        public virtual string GetName() => GetType().Name;
        public virtual bool IsInternal() => false;
        internal FrameDescriptor GetFrameDescriptor() => descriptor;
        internal DripSharp.Brine.Runtime.Truffle.api.RootCallTarget GetCallTarget()
        {
            lock (this)
            {
                return callTarget ??= new DripSharp.Brine.Runtime.Truffle.api.RootCallTarget(this);
            }
        }
    }

    internal sealed class DirectCallNode : Node
    {
        private readonly DripSharp.Brine.Runtime.Truffle.api.CallTarget target;
        private DirectCallNode(DripSharp.Brine.Runtime.Truffle.api.CallTarget target) => this.target = target;
        internal static DirectCallNode Create(DripSharp.Brine.Runtime.Truffle.api.CallTarget target) => new(target);
        internal object? Call(params object?[] arguments) => target.CallFrom(this, arguments);
    }

    internal sealed class IndirectCallNode : Node
    {
        internal static IndirectCallNode Create() => new();
        internal static IndirectCallNode GetUncached() => new();
        internal object? Call(DripSharp.Brine.Runtime.Truffle.api.CallTarget target, params object?[] arguments) =>
            target.CallFrom(this, arguments);
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api.instrumentation
{
    using DripSharp.Brine.Runtime.Truffle.api.frame;
    using DripSharp.Brine.Runtime.Truffle.api.nodes;

    internal interface InstrumentableNode
    {
        public interface WrapperNode
        {
            Node GetDelegateNode();
            ProbeNode GetProbeNode();
        }
    }

    internal class ProbeNode : Node
    {
        public static readonly object UNWIND_ACTION_REENTER = new();

        public virtual void OnEnter(VirtualFrame frame) { }
        public virtual void OnReturnValue(VirtualFrame frame, object? value) { }
        public virtual object? OnReturnExceptionalOrUnwind(
            VirtualFrame frame,
            Exception exception,
            bool wasOnReturnExecuted) => null;
    }

    internal sealed class EventContext
    {
        private readonly Node instrumentedNode;
        internal EventContext(Node instrumentedNode) => this.instrumentedNode = instrumentedNode;
        public Node GetInstrumentedNode() => instrumentedNode;
    }

    internal abstract class ExecutionEventNode : Node
    {
        protected internal virtual void OnReturnValue(VirtualFrame frame, object? result) { }
    }

    internal delegate ExecutionEventNode ExecutionEventNodeFactory(EventContext context);

    internal sealed class EventBinding<T> : IDisposable
    {
        private Action? dispose;
        internal EventBinding(Action dispose) => this.dispose = dispose;
        public void Dispose() => System.Threading.Interlocked.Exchange(ref dispose, null)?.Invoke();
    }

    internal sealed class Instrumenter
    {
        [ThreadStatic]
        private static List<Instrumenter>? active;

        private sealed class Registration
        {
            private readonly Dictionary<Node, ExecutionEventNode> eventNodes =
                new(ReferenceEqualityComparer.Instance);

            internal Registration(SourceSectionFilter filter, ExecutionEventNodeFactory factory)
            {
                Filter = filter;
                Factory = factory;
            }

            internal SourceSectionFilter Filter { get; }
            internal ExecutionEventNodeFactory Factory { get; }
            internal ExecutionEventNode For(Node node)
            {
                if (!eventNodes.TryGetValue(node, out var result))
                {
                    result = Factory(new EventContext(node));
                    eventNodes[node] = result;
                }
                return result;
            }
        }

        private sealed class InstrumentationProbe : ProbeNode
        {
            private readonly Instrumenter owner;
            private readonly Node instrumentedNode;

            internal InstrumentationProbe(Instrumenter owner, Node instrumentedNode)
            {
                this.owner = owner;
                this.instrumentedNode = instrumentedNode;
            }

            public override void OnReturnValue(VirtualFrame frame, object? value) =>
                owner.NotifyReturn(instrumentedNode, frame, value);
        }

        private readonly List<Registration> registrations = new();

        public EventBinding<ExecutionEventNodeFactory> AttachExecutionEventFactory(
            SourceSectionFilter filter, ExecutionEventNodeFactory factory) => Attach(filter, factory);

        public EventBinding<T> AttachExecutionEventFactory<T>(SourceSectionFilter filter, T factory) where T : Delegate
        {
            if (factory is not ExecutionEventNodeFactory typedFactory)
                throw new ArgumentException("Unsupported execution event factory delegate.", nameof(factory));
            var binding = Attach(filter, typedFactory);
            return new EventBinding<T>(binding.Dispose);
        }

        private EventBinding<ExecutionEventNodeFactory> Attach(
            SourceSectionFilter filter, ExecutionEventNodeFactory factory)
        {
            var registration = new Registration(filter, factory);
            lock (registrations)
            {
                if (registrations.Count == 0)
                {
                    active ??= new List<Instrumenter>();
                    active.Add(this);
                }
                registrations.Add(registration);
            }
            InstrumentTree(DripSharp.Brine.Runtime.Truffle.api.CallTarget.GetCurrentRoot());
            return new EventBinding<ExecutionEventNodeFactory>(() =>
            {
                lock (registrations)
                {
                    registrations.Remove(registration);
                    if (registrations.Count == 0) active?.Remove(this);
                }
            });
        }

        internal static void InstrumentActive(RootNode? root)
        {
            if (root is null || active is null) return;
            foreach (var instrumenter in active.ToArray()) instrumenter.InstrumentTree(root);
        }

        private void NotifyReturn(Node node, VirtualFrame frame, object? value)
        {
            Registration[] current;
            lock (registrations) current = registrations.ToArray();
            foreach (var registration in current)
                if (registration.Filter.Matches(node))
                    registration.For(node).OnReturnValue(frame, value);
        }

        private void InstrumentTree(RootNode? root)
        {
            if (root is null) return;
            InstrumentChildren(root, new HashSet<Node>(ReferenceEqualityComparer.Instance));
        }

        private void InstrumentChildren(Node parent, ISet<Node> visited)
        {
            if (!visited.Add(parent)) return;
            if (parent is InstrumentableNode.WrapperNode wrapper)
            {
                var delegateNode = wrapper.GetDelegateNode();
                delegateNode.SetParent(parent);
                InstrumentChildren(delegateNode, visited);
                return;
            }
            for (Type? type = parent.GetType(); type is not null && type != typeof(object); type = type.BaseType)
            {
                foreach (var field in type.GetFields(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic |
                    BindingFlags.DeclaredOnly))
                {
                    if (field.DeclaringType == typeof(Node) && field.Name == "parent") continue;
                    var value = field.GetValue(parent);
                    if (value is Node child)
                    {
                        var replacement = WrapIfNeeded(child);
                        if (!ReferenceEquals(replacement, child)) field.SetValue(parent, replacement);
                        replacement.SetParent(parent);
                        InstrumentChildren(replacement, visited);
                    }
                    else if (value is Array array &&
                             field.FieldType.GetElementType() is { } elementType &&
                             typeof(Node).IsAssignableFrom(elementType))
                    {
                        for (var index = 0; index < array.Length; index++)
                        {
                            if (array.GetValue(index) is not Node item) continue;
                            var replacement = WrapIfNeeded(item);
                            if (!ReferenceEquals(replacement, item)) array.SetValue(replacement, index);
                            replacement.SetParent(parent);
                            InstrumentChildren(replacement, visited);
                        }
                    }
                }
            }
        }

        private Node WrapIfNeeded(Node node)
        {
            if (node is InstrumentableNode.WrapperNode) return node;
            var type = node.GetType();
            var isInstrumentable = type.GetMethod("IsInstrumentable", Type.EmptyTypes);
            if (isInstrumentable?.Invoke(node, null) is not true) return node;
            var createWrapper = type.GetMethod("CreateWrapper", new[] { typeof(ProbeNode) });
            if (createWrapper is null) return node;
            var wrapper = createWrapper.Invoke(node, new object[] { new InstrumentationProbe(this, node) }) as Node;
            if (wrapper is null) return node;
            node.SetParent(wrapper);
            return wrapper;
        }
    }

    internal sealed class SourceSectionFilter
    {
        private readonly Type[] tags;
        private SourceSectionFilter(Type[] tags) => this.tags = tags;
        public static Builder NewBuilder() => new();

        internal bool Matches(Node node)
        {
            if (tags.Length == 0) return true;
            var hasTag = node.GetType().GetMethod("HasTag", new[] { typeof(Type) });
            return hasTag is not null && tags.Any(tag => hasTag.Invoke(node, new object[] { tag }) is true);
        }

        public sealed class Builder
        {
            private Type[] tags = Array.Empty<Type>();
            public Builder TagIs(params Type[] tags)
            {
                this.tags = tags;
                return this;
            }
            public SourceSectionFilter Build() => new(tags);
        }
    }

    internal class Tag { }
}

namespace DripSharp.Brine.Runtime.Truffle.api.dsl
{
    using DripSharp.Brine.Runtime.Truffle.api.nodes;

    internal static class DSLSupport
    {
        public static bool AssertIdempotence(bool value) => value;
    }

    internal sealed class UnsupportedSpecializationException : Exception
    {
        public UnsupportedSpecializationException(
            Node node,
            Node[] suppliedNodes,
            params object?[] suppliedValues)
            : base("No generated Truffle specialization accepts the supplied values.") { }
    }

    internal static class InlineSupport
    {
        public class ReferenceField
        {
            protected readonly string FieldName;

            protected ReferenceField(string fieldName) => FieldName = fieldName;

            // The Truffle generator calls the raw Java factory and relies on
            // assignment conversion to its parameterized updater type.  A
            // dynamic result preserves that generated call shape in C# while
            // constructing the exact closed updater requested by valueType.
            public static dynamic Create(object updater, string fieldName, Type valueType)
            {
                var closedType = typeof(ReferenceField<>).MakeGenericType(valueType);
                return Activator.CreateInstance(closedType, fieldName)!;
            }

            public static ReferenceField<T> Create<T>(object updater, string fieldName, Type valueType) where T : class =>
                new(fieldName);
        }

        public sealed class ReferenceField<T> : ReferenceField where T : class
        {
            public ReferenceField(string fieldName) : base(fieldName) { }

            private System.Reflection.FieldInfo Field(object receiver) =>
                receiver.GetType().GetField(
                    FieldName,
                    System.Reflection.BindingFlags.Instance |
                    System.Reflection.BindingFlags.Public |
                    System.Reflection.BindingFlags.NonPublic)
                ?? throw new MissingFieldException(receiver.GetType().FullName, FieldName);

            public T? GetVolatile(object receiver)
            {
                lock (receiver) return (T?)Field(receiver).GetValue(receiver);
            }

            public bool CompareAndSet(object receiver, T? expected, T? update)
            {
                lock (receiver)
                {
                    var field = Field(receiver);
                    if (!ReferenceEquals(field.GetValue(receiver), expected)) return false;
                    field.SetValue(receiver, update);
                    return true;
                }
            }
        }
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api.exception
{
    using DripSharp.Brine.Runtime.Truffle.api.nodes;

    internal class AbstractTruffleException : Exception
    {
        internal const int UNLIMITED_STACK_TRACE = -1;
        private readonly Node? location;
        private readonly List<DripSharp.Brine.Runtime.Truffle.api.TruffleStackTraceElement> truffleStackTrace = new();
        protected AbstractTruffleException(string? message, Exception? cause, int stackTraceLimit, Node? location)
            : base(message, cause) => this.location = location;

        internal Node? GetLocation() => location;
        internal void AddTruffleStackFrame(DripSharp.Brine.Runtime.Truffle.api.TruffleStackTraceElement frame) =>
            truffleStackTrace.Add(frame);
        internal IReadOnlyList<DripSharp.Brine.Runtime.Truffle.api.TruffleStackTraceElement> GetTruffleStackTrace() =>
            truffleStackTrace;
    }
}

namespace DripSharp.Brine.Runtime.Truffle.api
{
    using DripSharp.Brine.Runtime.Truffle.api.exception;
    using DripSharp.Brine.Runtime.Truffle.api.frame;
    using DripSharp.Brine.Runtime.Truffle.api.nodes;
    using DripSharp.Brine.Runtime.Truffle.api.source;

    internal class CallTarget
    {
        private const int MaxPklCallDepth = 256;
        [ThreadStatic]
        private static CallTarget? current;
        [ThreadStatic]
        private static int callDepth;
        private readonly RootNode? root;
        internal CallTarget(RootNode? root = null)
        {
            this.root = root;
            root?.AdoptChildren();
        }

        internal virtual object? Call(params object?[] arguments) => CallFrom(null, arguments);

        private static bool IsExternalMemberRoot(RootNode? root) =>
            root is global::DripSharp.Brine.Ast.MemberNode member &&
            member.GetBodyNode() is global::DripSharp.Brine.Stdlib.ExternalMemberNode;

        internal object? CallFrom(Node? location, params object?[] arguments)
        {
            global::DripSharp.Runtime.JavaCancellation.ThrowIfCancellationRequested();
            if (callDepth >= MaxPklCallDepth)
                throw new global::DripSharp.Brine.Runtime.VmStackOverflowException(
                    new StackOverflowException("Maximum Pkl call depth exceeded."));
            var caller = current;
            current = this;
            callDepth++;
            try
            {
                DripSharp.Brine.Runtime.Truffle.api.instrumentation.Instrumenter.InstrumentActive(root);
                return root?.Execute(new VirtualFrame(arguments, root.GetFrameDescriptor()));
            }
            catch (AbstractTruffleException exception)
            {
                if (exception.GetTruffleStackTrace().Count == 0)
                {
                    // A direct external call can report the exact caller node
                    // as its exception location. Collapse that duplicate
                    // location to the caller frame; distinct locations retain
                    // both the external member and its caller.
                    if (caller is not null && location is not null &&
                        exception.GetLocation() is null &&
                        IsExternalMemberRoot(root) &&
                        exception is global::DripSharp.Brine.Runtime.VmException vmException &&
                        vmException.GetSourceSection() is null)
                        exception.AddTruffleStackFrame(
                            new TruffleStackTraceElement(location, caller));
                    else
                    {
                        exception.AddTruffleStackFrame(
                            new TruffleStackTraceElement(exception.GetLocation() ?? location, this));
                        if (caller is not null && location is not null)
                            exception.AddTruffleStackFrame(
                                new TruffleStackTraceElement(location, caller));
                    }
                }
                else
                {
                    // A top-level Call() has no caller location. Reusing the
                    // original exception location here duplicates the import
                    // frame already captured at the nested call boundary.
                    if (location is not null || caller is not null)
                        exception.AddTruffleStackFrame(
                            new TruffleStackTraceElement(location ?? exception.GetLocation(), caller ?? this));
                }
                throw;
            }
            finally
            {
                callDepth--;
                current = caller;
            }
        }
        internal RootNode? GetRootNode() => root;
        internal static RootNode? GetCurrentRoot() => current?.root;
    }

    internal sealed class RootCallTarget : CallTarget
    {
        internal RootCallTarget(RootNode root) : base(root) { }
    }

    internal static class CompilerDirectives
    {
        internal static void TransferToInterpreter() { }
        internal static void TransferToInterpreterAndInvalidate() { }
        internal static void Blackhole(object? value) { }
    }

    internal static class CompilerAsserts
    {
        internal static void NeverPartOfCompilation() { }
    }

    internal static class TruffleOptions
    {
        internal const bool AOT = false;
    }

    internal static class Truffle
    {
        private static readonly TruffleRuntime Runtime = new();
        internal static TruffleRuntime GetRuntime() => Runtime;
    }

    internal sealed class TruffleRuntime
    {
        internal VirtualFrame CreateVirtualFrame(object?[] arguments, FrameDescriptor descriptor) =>
            new(arguments, descriptor);
        internal MaterializedFrame CreateMaterializedFrame(object?[] arguments) => new(arguments);
        internal IndirectCallNode CreateIndirectCallNode() => IndirectCallNode.GetUncached();
        internal RootCallTarget CreateCallTarget(RootNode root) => root.GetCallTarget();
    }

    internal sealed class ContextThreadLocal<T> where T : class
    {
        private readonly System.Threading.ThreadLocal<T> local;
        internal ContextThreadLocal(Func<object?, System.Threading.Thread, T> factory) =>
            local = new System.Threading.ThreadLocal<T>(
                () => factory(null, System.Threading.Thread.CurrentThread));
        public T Get() => local.Value!;
    }

    internal sealed class ContextLocalSupport
    {
        public ContextThreadLocal<T> CreateContextThreadLocal<T>(
            Func<object?, System.Threading.Thread, T> factory) where T : class => new(factory);
    }

    internal abstract class TruffleLanguage<TContext> where TContext : class
    {
        protected readonly ContextLocalSupport locals = new();
        protected internal abstract TContext CreateContext(TruffleLanguage.Env env);
        protected internal abstract CallTarget Parse(TruffleLanguage.ParsingRequest request);
    }

    internal static class TruffleLanguage
    {
        private sealed record InstalledContext(object Value, global::DripSharp.Brine.Runtime.Polyglot.Context Owner);

        private static readonly System.Threading.AsyncLocal<Dictionary<Type, IReadOnlyList<InstalledContext>>?> Contexts = new();
        internal static void InstallContext(
            Type languageType,
            object context,
            global::DripSharp.Brine.Runtime.Polyglot.Context owner)
        {
            var contexts = Contexts.Value is { } existing
                ? new Dictionary<Type, IReadOnlyList<InstalledContext>>(existing)
                : new Dictionary<Type, IReadOnlyList<InstalledContext>>();
            var stack = contexts.TryGetValue(languageType, out var current)
                ? current.ToList()
                : new List<InstalledContext>();
            stack.Add(new InstalledContext(context, owner));
            contexts[languageType] = stack;
            Contexts.Value = contexts;
        }
        internal static void RemoveContext(
            Type languageType,
            global::DripSharp.Brine.Runtime.Polyglot.Context owner,
            bool removeAll = false)
        {
            if (Contexts.Value is not { } existing) return;
            if (!existing.TryGetValue(languageType, out var current)) return;
            IReadOnlyList<InstalledContext> remaining;
            if (removeAll)
            {
                remaining = current.Where(entry => !ReferenceEquals(entry.Owner, owner)).ToList();
            }
            else
            {
                if (current.Count == 0 || !ReferenceEquals(current[^1].Owner, owner))
                    throw new InvalidOperationException("Pkl contexts must be left in enter order.");
                remaining = current.Take(current.Count - 1).ToList();
            }
            var contexts = new Dictionary<Type, IReadOnlyList<InstalledContext>>(existing);
            if (remaining.Count == 0) contexts.Remove(languageType);
            else contexts[languageType] = remaining;
            Contexts.Value = contexts;
        }
        public sealed class Env
        {
            public dynamic Lookup(Type serviceType) => Activator.CreateInstance(serviceType)!;
            public T Lookup<T>(Type serviceType) => (T)Activator.CreateInstance(serviceType)!;
        }

        public sealed class ParsingRequest { }

        public sealed class LanguageReference<TLanguage> where TLanguage : class
        {
            private readonly Lazy<TLanguage> language;
            private LanguageReference(Type type) =>
                language = new Lazy<TLanguage>(() => (TLanguage)Activator.CreateInstance(type, nonPublic: true)!);
            internal static LanguageReference<TLanguage> Create(Type type) => new(type);
            internal static LanguageReference<T> Create<T>(Type type) where T : class => new(type);
            internal TLanguage Get(Node? node) => language.Value;
        }

        public sealed class ContextReference<TContext> where TContext : class
        {
            private readonly Type languageType;
            private ContextReference(Type languageType) => this.languageType = languageType;
            internal static ContextReference<TContext> Create(Type languageType) => new(languageType);
            internal static ContextReference<T> Create<TLanguage, T>(Type languageType) where T : class => new(languageType);
            internal TContext Get(Node? node)
            {
                if (Contexts.Value is not { } contexts ||
                    !contexts.TryGetValue(languageType, out var stack) ||
                    stack.Count == 0)
                    throw new InvalidOperationException("Pkl context has not been installed for this execution.");
                var installed = stack[^1];
                ObjectDisposedException.ThrowIf(installed.Owner.IsClosed, installed.Owner);
                return (TContext)installed.Value;
            }
        }
    }

    internal static class TruffleStackTrace
    {
        internal static IReadOnlyList<TruffleStackTraceElement> GetStackTrace(Exception exception) =>
            exception is AbstractTruffleException truffleException
                ? truffleException.GetTruffleStackTrace()
                : Array.Empty<TruffleStackTraceElement>();
    }

    internal sealed class TruffleStackTraceElement
    {
        private readonly Node? location;
        private readonly CallTarget target;

        internal TruffleStackTraceElement(Node? location, CallTarget target)
        {
            this.location = location;
            this.target = target;
        }

        internal Node? GetLocation() => location;
        internal CallTarget GetTarget() => target;
    }
}

// SnakeYAML Engine contracts reached by the evaluator's YAML parser closure.
// The generated Pkl classes retain ownership of Pkl conversion and value-model
// behavior; these types provide the external library surface resolved by Spoon.
namespace DripSharp.Brine.Runtime.SnakeYaml.api
{
    using DripSharp.Brine.Runtime.SnakeYaml.constructor;
    using DripSharp.Brine.Runtime.SnakeYaml.nodes;

    internal interface ConstructNode
    {
        object? Construct(Node node);
        void ConstructRecursive(Node node, object? data) { }
    }

    internal sealed class LoadSettings
    {
        internal LoadSettings() { }
        public static LoadSettingsBuilder Builder() => new();
    }

    internal sealed class LoadSettingsBuilder
    {
        public LoadSettingsBuilder SetAllowNonScalarKeys(bool value) => this;
        public LoadSettingsBuilder SetLabel(string value) => this;
        public LoadSettingsBuilder SetMaxAliasesForCollections(int value) => this;
        public LoadSettingsBuilder SetSchema(schema.Schema value) => this;
        public LoadSettings Build() => new();
    }

    internal sealed class Load
    {
        private readonly BaseConstructor constructor;
        public Load(LoadSettings settings, BaseConstructor constructor) => this.constructor = constructor;
        public object? LoadFromString(string text) =>
            throw new exceptions.YamlEngineException("SnakeYAML parsing substrate is not implemented yet.");
        public IEnumerable<object?> LoadAllFromString(string text) =>
            throw new exceptions.YamlEngineException("SnakeYAML parsing substrate is not implemented yet.");
    }
}

namespace DripSharp.Brine.Runtime.SnakeYaml.constructor
{
    using DripSharp.Brine.Runtime.SnakeYaml.api;
    using DripSharp.Brine.Runtime.SnakeYaml.nodes;

    internal class BaseConstructor
    {
        protected readonly Dictionary<Tag, ConstructNode> TagConstructors = new();
        protected Dictionary<Tag, ConstructNode> tagConstructors => TagConstructors;
        public virtual object? ConstructObject(Node node) => null;
        protected void FlattenMapping(MappingNode node) { }
    }

    internal class StandardConstructor : BaseConstructor
    {
        public StandardConstructor(api.LoadSettings settings) { }
    }
}

namespace DripSharp.Brine.Runtime.SnakeYaml.exceptions
{
    internal sealed class Mark { }

    internal class YamlEngineException : Exception
    {
        public YamlEngineException(string message) : base(message) { }
    }
}

namespace DripSharp.Brine.Runtime.SnakeYaml.nodes
{
    using DripSharp.Brine.Runtime.SnakeYaml.exceptions;

    internal class Tag : IEquatable<Tag>
    {
        public const string PREFIX = "tag:yaml.org,2002:";
        public static readonly Tag BINARY = new(PREFIX + "binary");
        public static readonly Tag BOOL = new(PREFIX + "bool");
        public static readonly Tag FLOAT = new(PREFIX + "float");
        public static readonly Tag INT = new(PREFIX + "int");
        public static readonly Tag MAP = new(PREFIX + "map");
        public static readonly Tag NULL = new(PREFIX + "null");
        public static readonly Tag SEQ = new(PREFIX + "seq");
        public static readonly Tag SET = new(PREFIX + "set");
        public static readonly Tag STR = new(PREFIX + "str");

        private readonly string value;
        public Tag(string value) => this.value = value;
        public bool Equals(Tag? other) => other is not null && value == other.value;
        public override bool Equals(object? other) => Equals(other as Tag);
        public override int GetHashCode() => value.GetHashCode(StringComparison.Ordinal);
        public override string ToString() => value;
    }

    internal class Node
    {
        public virtual JavaOptional<Mark> GetStartMark() => JavaOptional<Mark>.Empty();
        public virtual JavaOptional<Mark> GetEndMark() => JavaOptional<Mark>.Empty();
        public virtual bool IsRecursive() => false;
    }

    internal sealed class ScalarNode : Node
    {
        private readonly string value;
        public ScalarNode(string value) => this.value = value;
        public string GetValue() => value;
    }

    internal sealed class SequenceNode : Node
    {
        private readonly IList<Node> value;
        public SequenceNode(IList<Node> value) => this.value = value;
        public IList<Node> GetValue() => value;
    }

    internal sealed class NodeTuple
    {
        private readonly Node keyNode;
        private readonly Node valueNode;
        public NodeTuple(Node keyNode, Node valueNode)
        {
            this.keyNode = keyNode;
            this.valueNode = valueNode;
        }
        public Node GetKeyNode() => keyNode;
        public Node GetValueNode() => valueNode;
    }

    internal sealed class MappingNode : Node
    {
        private readonly IList<NodeTuple> value;
        public MappingNode(IList<NodeTuple> value) => this.value = value;
        public IList<NodeTuple> GetValue() => value;
    }
}

namespace DripSharp.Brine.Runtime.SnakeYaml.resolver
{
    using DripSharp.Brine.Runtime.SnakeYaml.nodes;

    internal interface ScalarResolver
    {
        Tag Resolve(string value, bool implicitValue);
    }
}

namespace DripSharp.Brine.Runtime.SnakeYaml.schema
{
    using DripSharp.Brine.Runtime.SnakeYaml.api;
    using DripSharp.Brine.Runtime.SnakeYaml.nodes;
    using DripSharp.Brine.Runtime.SnakeYaml.resolver;

    internal abstract class Schema
    {
        public abstract ScalarResolver GetScalarResolver();
        public abstract IDictionary<Tag, ConstructNode> GetSchemaTagConstructors();
    }
}

namespace DripSharp.Brine.Util
{
    internal sealed partial class HttpUtils
    {
        public static void CheckHasStatusCode200<T>(global::DripSharp.Brine.Runtime.JavaHttpResponse<T> response)
        {
            if (response.StatusCode() == 200) return;
            if (response.Body() is IDisposable disposable) disposable.Dispose();
            throw new System.IO.IOException(ErrorMessages.Create("badHttpStatusCode", response.StatusCode(), response.Uri()));
        }
    }
}

namespace DripSharp.Brine
{
    public sealed partial class PClassInfo<T>
    {
        public PClassInfo<object> AsObject() =>
            this is PClassInfo<object> exact
                ? exact
                : new PClassInfo<object>(this.moduleName, this.className, this.javaClass, this.moduleUri);

        public PClassInfo<TValue> Retype<TValue>() =>
            this is PClassInfo<TValue> exact
                ? exact
                : new PClassInfo<TValue>(this.moduleName, this.className, this.javaClass, this.moduleUri);

        public static PClassInfo<TValue> ForValueCompat<TValue>(TValue value)
        {
            if (value is Value pklValue) return pklValue.GetClassInfo().Retype<TValue>();
            if (value is string) return PClassInfo<object>.String.Retype<TValue>();
            if (value is bool) return PClassInfo<object>.Boolean.Retype<TValue>();
            if (value is long) return PClassInfo<object>.Int.Retype<TValue>();
            if (value is double) return PClassInfo<object>.Float.Retype<TValue>();
            if (value is byte[] or sbyte[]) return PClassInfo<object>.Bytes.Retype<TValue>();
            if (value is System.Collections.IList) return PClassInfo<object>.List.Retype<TValue>();
            if (value is System.Collections.IDictionary) return PClassInfo<object>.Map.Retype<TValue>();
            if (value is System.Text.RegularExpressions.Regex) return PClassInfo<object>.Regex.Retype<TValue>();
            if (global::DripSharp.Runtime.JavaCompat.IsSet(value)) return PClassInfo<object>.Set.Retype<TValue>();
            throw new ArgumentException("Not a Pkl value: " + value);
        }
    }
}

namespace DripSharp.Brine.Util
{
    internal sealed partial class ByteArrayUtils
    {
        public static string Base64(byte[] input) =>
            global::System.Convert.ToBase64String(input);
    }
}

namespace DripSharp.Brine.Service
{
    // Product-owned .NET representation of the versioned executor SPI used by
    // the upstream service implementation. It avoids exposing ServiceLoader or
    // a JVM distribution contract while preserving the ordinary provider body.
    internal interface IExecutorSpi
    {
        string GetPklVersion();
        string EvaluatePath(string modulePath, ExecutorSpiOptions options);
    }

    internal sealed class ExecutorSpiException : Exception
    {
        public ExecutorSpiException(string? message, Exception? cause) : base(message, cause) { }
    }

    internal class ExecutorSpiOptions
    {
        private readonly IList<string> allowedModules;
        private readonly IList<string> allowedResources;
        private readonly IDictionary<string, string> environmentVariables;
        private readonly IDictionary<string, string> externalProperties;
        private readonly IList<string> modulePath;
        private readonly string? rootDir;
        private readonly TimeSpan? timeout;
        private readonly string? outputFormat;
        private readonly string? moduleCacheDir;
        private readonly string? projectDir;

        public ExecutorSpiOptions(
            IList<string> allowedModules,
            IList<string> allowedResources,
            IDictionary<string, string> environmentVariables,
            IDictionary<string, string> externalProperties,
            IList<string> modulePath,
            string? rootDir,
            TimeSpan? timeout,
            string? outputFormat,
            string? moduleCacheDir,
            string? projectDir)
        {
            this.allowedModules = allowedModules;
            this.allowedResources = allowedResources;
            this.environmentVariables = environmentVariables;
            this.externalProperties = externalProperties;
            this.modulePath = modulePath;
            this.rootDir = rootDir;
            this.timeout = timeout;
            this.outputFormat = outputFormat;
            this.moduleCacheDir = moduleCacheDir;
            this.projectDir = projectDir;
        }

        public IList<string> GetAllowedModules() => allowedModules;
        public IList<string> GetAllowedResources() => allowedResources;
        public IDictionary<string, string> GetEnvironmentVariables() => environmentVariables;
        public IDictionary<string, string> GetExternalProperties() => externalProperties;
        public IList<string> GetModulePath() => modulePath;
        public string? GetRootDir() => rootDir;
        public TimeSpan? GetTimeout() => timeout;
        public string? GetOutputFormat() => outputFormat;
        public string? GetModuleCacheDir() => moduleCacheDir;
        public string? GetProjectDir() => projectDir;
    }

    internal class ExecutorSpiOptions2 : ExecutorSpiOptions
    {
        private readonly IList<string> certificateFiles;
        private readonly IList<sbyte[]> certificateBytes;
        private readonly int testPort;

        public ExecutorSpiOptions2(
            IList<string> allowedModules,
            IList<string> allowedResources,
            IDictionary<string, string> environmentVariables,
            IDictionary<string, string> externalProperties,
            IList<string> modulePath,
            string? rootDir,
            TimeSpan? timeout,
            string? outputFormat,
            string? moduleCacheDir,
            string? projectDir,
            IList<string> certificateFiles,
            IList<sbyte[]> certificateBytes,
            int testPort)
            : base(allowedModules, allowedResources, environmentVariables, externalProperties,
                   modulePath, rootDir, timeout, outputFormat, moduleCacheDir, projectDir)
        {
            this.certificateFiles = certificateFiles;
            this.certificateBytes = certificateBytes;
            this.testPort = testPort;
        }

        public IList<string> GetCertificateFiles() => certificateFiles;
        public IList<sbyte[]> GetCertificateBytes() => certificateBytes;
        public int GetTestPort() => testPort;
    }

    internal class ExecutorSpiOptions3 : ExecutorSpiOptions2
    {
        private readonly IDictionary<Uri, Uri> httpRewrites;

        public ExecutorSpiOptions3(
            IList<string> allowedModules,
            IList<string> allowedResources,
            IDictionary<string, string> environmentVariables,
            IDictionary<string, string> externalProperties,
            IList<string> modulePath,
            string? rootDir,
            TimeSpan? timeout,
            string? outputFormat,
            string? moduleCacheDir,
            string? projectDir,
            IList<string> certificateFiles,
            IList<sbyte[]> certificateBytes,
            int testPort,
            IDictionary<Uri, Uri> httpRewrites)
            : base(allowedModules, allowedResources, environmentVariables, externalProperties,
                   modulePath, rootDir, timeout, outputFormat, moduleCacheDir, projectDir,
                   certificateFiles, certificateBytes, testPort) => this.httpRewrites = httpRewrites;

        public IDictionary<Uri, Uri> GetHttpRewrites() => httpRewrites;
    }

    internal class ExecutorSpiOptions4 : ExecutorSpiOptions3
    {
        private readonly IDictionary<string, IDictionary<string, IList<string>>> httpHeaders;

        public ExecutorSpiOptions4(
            IList<string> allowedModules,
            IList<string> allowedResources,
            IDictionary<string, string> environmentVariables,
            IDictionary<string, string> externalProperties,
            IList<string> modulePath,
            string? rootDir,
            TimeSpan? timeout,
            string? outputFormat,
            string? moduleCacheDir,
            string? projectDir,
            IList<string> certificateFiles,
            IList<sbyte[]> certificateBytes,
            int testPort,
            IDictionary<Uri, Uri> httpRewrites,
            IDictionary<string, IDictionary<string, IList<string>>> httpHeaders)
            : base(allowedModules, allowedResources, environmentVariables, externalProperties,
                   modulePath, rootDir, timeout, outputFormat, moduleCacheDir, projectDir,
                   certificateFiles, certificateBytes, testPort, httpRewrites) =>
            this.httpHeaders = httpHeaders;

        public IDictionary<string, IDictionary<string, IList<string>>> GetHttpHeaders() => httpHeaders;
    }
}

namespace DripSharp.Brine.Messaging
{
    internal partial interface MessageTransport
    {
        // Destination-only lifecycle hook. The upstream transport drops its
        // response-handler map on EOF/close; .NET external readers instead use
        // this hook to wake every caller before releasing the transport.
        internal void Fail(System.Exception error);
    }

    internal sealed partial class MessageTransports
    {
        internal abstract partial class AbstractMessageTransport
        {
            private readonly object destinationLifecycleLock = new();
            private System.Exception? destinationFailure;

            private sealed class TransportFailureResponse : Message.Response
            {
                internal TransportFailureResponse(long requestId) => RequestId = requestId;
                public long RequestId { get; }
                public Message.Type CreateType() => Message.Type.READ_MODULE_RESPONSE;
            }

            internal void SendRequestSafely(
                Message.Request message,
                MessageTransport.ResponseHandler responseHandler)
            {
                System.Exception? failure;
                lock (destinationLifecycleLock)
                {
                    failure = destinationFailure;
                    if (failure is null)
                    {
                        responseHandlers[message.RequestId] = responseHandler;
                        try
                        {
                            DoSend(message);
                            return;
                        }
                        catch (System.Exception error)
                        {
                            responseHandlers.Remove(message.RequestId);
                            destinationFailure = failure = NormalizeTransportFailure(error);
                        }
                    }
                }
                CompleteFailedResponse(message.RequestId, responseHandler, failure!);
            }

            internal MessageTransport.ResponseHandler? TakeResponseHandler(long requestId)
            {
                lock (destinationLifecycleLock)
                {
                    if (!responseHandlers.TryGetValue(requestId, out var handler)) return null;
                    responseHandlers.Remove(requestId);
                    return handler;
                }
            }

            public void Fail(System.Exception error)
            {
                KeyValuePair<long, MessageTransport.ResponseHandler>[] pending;
                System.Exception failure;
                lock (destinationLifecycleLock)
                {
                    destinationFailure ??= NormalizeTransportFailure(error);
                    failure = destinationFailure;
                    pending = responseHandlers.ToArray();
                    responseHandlers.Clear();
                }
                foreach (var entry in pending)
                    CompleteFailedResponse(entry.Key, entry.Value, failure);
            }

            internal void CloseSafely()
            {
                Fail(new System.IO.IOException("External reader transport was closed."));
                DoClose();
            }

            private static System.IO.IOException NormalizeTransportFailure(System.Exception error) =>
                error as System.IO.IOException ??
                new System.IO.IOException(
                    "External reader transport failed (" + error.GetType().Name + ").", error);

            private static void CompleteFailedResponse(
                long requestId,
                MessageTransport.ResponseHandler handler,
                System.Exception failure)
            {
                try
                {
                    // Existing generated response callbacks turn an unexpected
                    // response into a failed JavaFuture. MessageTransports then
                    // exposes that as the public deterministic IOException.
                    handler(new TransportFailureResponse(requestId));
                }
                catch (System.Exception callbackFailure)
                {
                    _ = new System.AggregateException(failure, callbackFailure);
                }
            }
        }
    }
}

namespace DripSharp.Brine.Externalreader
{
    internal sealed partial class ExternalReaderProcessImpl
    {
        private DripSharp.Brine.Runtime.JavaThread? destinationReaderThread;

        private void StartDestinationTransportThread(DripSharp.Brine.Messaging.MessageTransport selectedTransport)
        {
            var thread = new DripSharp.Brine.Runtime.JavaThread(
                () => RunTransport(selectedTransport),
                "ExternalReaderProcessImpl rxThread for " + spec);
            thread.SetDaemon(true);
            destinationReaderThread = thread;
            thread.Start();
        }

        private void FinishDestinationTransport(
            DripSharp.Brine.Messaging.MessageTransport selectedTransport,
            System.Exception failure)
        {
            selectedTransport.Fail(failure);
            global::DripSharp.Runtime.JavaProcess? ownedProcess;
            lock (@lock) ownedProcess = process;
            if (ownedProcess is null) return;
            ownedProcess.Terminate();
            ownedProcess.Dispose();
        }

        private void CloseDestinationProcess()
        {
            global::DripSharp.Runtime.JavaProcess? ownedProcess;
            DripSharp.Brine.Messaging.MessageTransport? ownedTransport;
            DripSharp.Brine.Runtime.JavaThread? readerThread;
            lock (@lock)
            {
                if (closed) return;
                closed = true;
                ownedProcess = process;
                ownedTransport = transport;
                readerThread = destinationReaderThread;
            }

            try
            {
                if (ownedTransport is not null && ownedProcess is not null && ownedProcess.IsAlive())
                {
                    ownedTransport.Send(new ExternalReaderMessages.CloseExternalProcess());
                    ownedProcess.WaitFor(
                        global::DripSharp.Runtime.JavaCompat.DurationToMillis(CLOSE_TIMEOUT),
                        global::DripSharp.Runtime.JavaTimeUnit.MILLISECONDS);
                }
            }
            catch (System.Exception) { }
            finally
            {
                ownedTransport?.Fail(new System.IO.IOException("External reader process was closed."));
                try { ownedTransport?.Close(); }
                catch (System.Exception) { }
                ownedProcess?.Terminate();
                if (readerThread is not null)
                    readerThread.Join(TimeSpan.FromSeconds(3));
                ownedProcess?.Dispose();
            }
        }
    }
}
