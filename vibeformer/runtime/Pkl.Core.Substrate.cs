// Focused .NET substrate for the Truffle and Graal collection contracts used
// by the generated Pkl.Core product. This is product runtime code, not a
// reconstructed frontend model.
#nullable enable

using System;
using System.Collections.Generic;
using System.Linq;

namespace Pkl.Core.Runtime
{
    public delegate T JavaBinaryOperator<T>(T left, T right);
    public delegate T JavaLongFunction<T>(long value);
    public sealed partial class VmList
    {
        internal static VmList Create(IEnumerable<object> values) => CreateFromUnmodIterable(values);
    }
    public sealed partial class VmSet
    {
        internal static VmSet Create(ISet<object> values, global::Pkl.Core.Util.Paguro.RrbTree<object> order) =>
            Create(values, (global::Pkl.Core.Util.Paguro.RrbTree<object>.ImRrbt<object>)(object)order);
    }
    public static class ExcludedMessagePack
    {
        public static ExcludedMessagePackPacker NewDefaultBufferPacker() => new();
        public static ExcludedMessagePackUnpacker NewDefaultUnpacker(byte[] bytes) => new();
    }
    public sealed class ExcludedMessagePackPacker : IDisposable
    {
        private static NotSupportedException Excluded() => new("MessagePack is excluded from the Vibeformer product target.");
        public void Clear() { }
        public sbyte[] ToByteArray() => throw Excluded();
        public ExcludedMessagePackPacker PackNil() => throw Excluded();
        public ExcludedMessagePackPacker PackBoolean(bool value) => throw Excluded();
        public ExcludedMessagePackPacker PackLong(long value) => throw Excluded();
        public ExcludedMessagePackPacker PackByte(sbyte value) => throw Excluded();
        public ExcludedMessagePackPacker PackBinaryHeader(int length) => throw Excluded();
        public ExcludedMessagePackPacker WritePayload(sbyte[] value) => throw Excluded();
        public ExcludedMessagePackPacker AddPayload(sbyte[] value) => throw Excluded();
        public ExcludedMessagePackPacker PackDouble(double value) => throw Excluded();
        public ExcludedMessagePackPacker PackString(string value) => throw Excluded();
        public ExcludedMessagePackPacker PackArrayHeader(int size) => throw Excluded();
        public ExcludedMessagePackPacker PackMapHeader(int size) => throw Excluded();
        public void Close() { }
        public void Dispose() { }
    }
    public sealed class ExcludedMessagePackUnpacker : IDisposable
    {
        private static NotSupportedException Excluded() => new("MessagePack is excluded from the Vibeformer product target.");
        public bool HasNext() => throw Excluded();
        public object UnpackValue() => throw Excluded();
        public void Close() { }
        public void Dispose() { }
    }
    public class JavaTuple2<A, B>
    {
        public readonly A _1; public readonly B _2;
        public JavaTuple2(A first, B second) { _1 = first; _2 = second; }
        public static JavaTuple2<X, Y> Of<X, Y>(X first, Y second) => new(first, second);
    }
    public class JavaTuple4<A, B, C, D>
    {
        public readonly A _1; public readonly B _2; public readonly C _3; public readonly D _4;
        public JavaTuple4(A first, B second, C third, D fourth) { _1 = first; _2 = second; _3 = third; _4 = fourth; }
    }
    public sealed class JavaIdentityDictionary<K, V> : Dictionary<K, V> where K : notnull
    {
        private sealed class IdentityComparer : IEqualityComparer<K>
        {
            public bool Equals(K? left, K? right) => ReferenceEquals(left, right);
            public int GetHashCode(K value) => System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);
        }
        public JavaIdentityDictionary() : base(new IdentityComparer()) { }
    }
    public sealed class JavaDecimalFormat
    {
        private readonly string pattern; private readonly System.Globalization.NumberFormatInfo format;
        public JavaDecimalFormat() : this(string.Empty, System.Globalization.CultureInfo.InvariantCulture.NumberFormat) { }
        public JavaDecimalFormat(string pattern, System.Globalization.NumberFormatInfo format) { this.pattern = pattern; this.format = format; }
        public string Format(object value) => string.Format(format, "{0}", value);
        public void SetGroupingUsed(bool value) { }
        public void SetMinimumFractionDigits(int value) => format.NumberDecimalDigits = Math.Max(format.NumberDecimalDigits, value);
        public void SetMaximumFractionDigits(int value) => format.NumberDecimalDigits = value;
        public void SetDecimalFormatSymbols(System.Globalization.NumberFormatInfo value) { }
    }
    public static class JavaBase64
    {
        public static JavaBase64Encoder GetEncoder() => new(false);
        public static JavaBase64Encoder GetUrlEncoder() => new(true);
        public static JavaBase64Decoder GetDecoder() => new(false);
        public static JavaBase64Decoder GetUrlDecoder() => new(true);
    }
    public sealed class JavaBase64Encoder
    {
        private readonly bool url; public JavaBase64Encoder(bool url) => this.url = url;
        public string EncodeToString(byte[] bytes)
        { var value = Convert.ToBase64String(bytes); return url ? value.TrimEnd('=').Replace('+', '-').Replace('/', '_') : value; }
        public string EncodeToString(sbyte[] bytes) => EncodeToString(bytes.Select(value => unchecked((byte)value)).ToArray());
        public JavaBase64Encoder WithoutPadding() => this;
    }
    public sealed class JavaBase64Decoder
    {
        private readonly bool url; public JavaBase64Decoder(bool url) => this.url = url;
        public sbyte[] Decode(string value)
        { if (url) value = value.Replace('-', '+').Replace('_', '/'); value = value.PadRight((value.Length + 3) / 4 * 4, '='); return Convert.FromBase64String(value).Select(item => unchecked((sbyte)item)).ToArray(); }
    }

    public sealed class JavaOptional<T>
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

    public sealed class JavaPrintWriter
    {
        private readonly System.IO.TextWriter writer;
        public JavaPrintWriter(System.IO.TextWriter writer) => this.writer = writer;
        public void Print(object? value) => writer.Write(value);
        public void Println(object? value = null) => writer.WriteLine(value);
        public void Flush() => writer.Flush();
    }

    public enum JavaTemporalUnit { NANOS, MICROS, MILLIS, SECONDS, MINUTES, HOURS, DAYS }
    public enum JavaRoundingMode { UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN, UNNECESSARY }

    public sealed class JavaScheduledExecutor
    {
        public System.Threading.Tasks.Task Schedule(Action action, long delay, object unit) =>
            System.Threading.Tasks.Task.Run(async () =>
            {
                await System.Threading.Tasks.Task.Delay(TimeSpan.FromMilliseconds(delay));
                action();
            });
        public System.Threading.Tasks.Task Schedule(object runnable, long delay, object unit) =>
            System.Threading.Tasks.Task.Run(async () =>
            {
                await System.Threading.Tasks.Task.Delay(TimeSpan.FromMilliseconds(delay));
                runnable.GetType().GetMethod("Run", System.Reflection.BindingFlags.Instance |
                                                   System.Reflection.BindingFlags.Public |
                                                   System.Reflection.BindingFlags.NonPublic)!
                        .Invoke(runnable, null);
            });
        public void Shutdown() { }
    }

    public sealed class JavaThread
    {
        private readonly System.Threading.Thread thread;
        public JavaThread(Action action, string name)
        {
            thread = new System.Threading.Thread(() => action()) { Name = name };
        }
        private JavaThread(System.Threading.Thread thread) => this.thread = thread;
        public static JavaThread CurrentThread() => new(System.Threading.Thread.CurrentThread);
        public void Interrupt() => thread.Interrupt();
        public void SetDaemon(bool daemon) => thread.IsBackground = daemon;
        public void Start() => thread.Start();
    }

    public static class JavaConcurrency
    {
        public static JavaScheduledExecutor NewSingleThreadScheduledExecutor(
            Func<Action, JavaThread> threadFactory) => new();
    }

    public sealed class JavaAtomicBoolean
    {
        private int value;
        public JavaAtomicBoolean(bool value = false) => this.value = value ? 1 : 0;
        public bool Get() => System.Threading.Volatile.Read(ref value) != 0;
        public void Set(bool next) => System.Threading.Volatile.Write(ref value, next ? 1 : 0);
        public bool GetAndSet(bool next) => System.Threading.Interlocked.Exchange(ref value, next ? 1 : 0) != 0;
        public bool CompareAndSet(bool expected, bool next) =>
            System.Threading.Interlocked.CompareExchange(ref value, next ? 1 : 0, expected ? 1 : 0) == (expected ? 1 : 0);
    }
    public sealed class JavaAtomicLong
    {
        private long value;
        public JavaAtomicLong(long value = 0) => this.value = value;
        public long Get() => System.Threading.Interlocked.Read(ref value);
        public void Set(long next) => System.Threading.Interlocked.Exchange(ref value, next);
        public long IncrementAndGet() => System.Threading.Interlocked.Increment(ref value);
    }
    public sealed class JavaAtomicReference<T> where T : class
    {
        private T? value;
        public JavaAtomicReference(T? value = null) => this.value = value;
        public T? Get() => System.Threading.Volatile.Read(ref value);
        public void Set(T? next) => System.Threading.Volatile.Write(ref value, next);
        public bool CompareAndSet(T? expected, T? next) =>
            ReferenceEquals(System.Threading.Interlocked.CompareExchange(ref value, next, expected), expected);
    }

    public sealed class JavaByteBuffer
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

    public class JavaUrlConnection
    {
        private readonly Uri? uri;
        public JavaUrlConnection() { }
        public JavaUrlConnection(Uri uri) => this.uri = uri;
        public virtual void Connect() { }
        public virtual Uri GetURL() => uri ?? throw new InvalidOperationException("URL is unavailable");
        public virtual System.IO.Stream GetInputStream() =>
            uri is null ? throw new NotSupportedException() :
            new System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
        public virtual void SetUseCaches(bool value) { }
    }
    public sealed class JavaJarConnection : JavaUrlConnection
    {
        public override System.IO.Stream GetInputStream() => throw new NotSupportedException("JAR URL streams require a resolved module path.");
    }

    public sealed class JavaBufferedWriter : System.IO.TextWriter
    {
        private readonly System.IO.TextWriter writer;
        public JavaBufferedWriter(System.IO.TextWriter writer) => this.writer = writer;
        public override System.Text.Encoding Encoding => writer.Encoding;
        public override void Write(char value) => writer.Write(value);
        public override void Write(string? value) => writer.Write(value);
        public override void Flush() => writer.Flush();
        protected override void Dispose(bool disposing) { if (disposing) writer.Dispose(); base.Dispose(disposing); }
    }
    public sealed class JavaCharsetDecoder
    {
        private readonly System.Text.Encoding encoding;
        public JavaCharsetDecoder(System.Text.Encoding encoding) => this.encoding = encoding;
        public string Decode(JavaByteBuffer buffer) => encoding.GetString(buffer.UnsignedArray());
    }
    public sealed class JavaCharsetEncoder
    {
        private readonly System.Text.Encoding encoding;
        public JavaCharsetEncoder(System.Text.Encoding encoding) => this.encoding = encoding;
        public JavaByteBuffer Encode(string value) => JavaByteBuffer.Wrap(encoding.GetBytes(value));
    }

    public enum JavaFileVisitResult { CONTINUE, TERMINATE, SKIP_SUBTREE, SKIP_SIBLINGS }
    public enum JavaCopyOption { REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE }
    public sealed class JavaWatchService : IDisposable { public void Close() { } public void Dispose() { } }
    public class JavaSimpleFileVisitor<T>
    {
        public virtual JavaFileVisitResult VisitFile(T file, System.IO.FileSystemInfo attributes) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult PreVisitDirectory(T directory, System.IO.FileSystemInfo attributes) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult PostVisitDirectory(T directory, System.IO.IOException? error) => JavaFileVisitResult.CONTINUE;
        public virtual JavaFileVisitResult VisitFileFailed(T file, System.IO.IOException error) => JavaFileVisitResult.CONTINUE;
    }
    public class JavaFileSystem : IDisposable
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
        public void Dispose() { }
    }

    public class JavaProxySelector
    {
        public static JavaProxySelector GetDefault() => new();
        public virtual IList<System.Net.WebProxy> Select(Uri uri) => new[] { new System.Net.WebProxy() };
        public virtual void ConnectFailed(Uri uri, System.Net.EndPoint address, System.IO.IOException error) { }
    }
    public static class JavaFileSystems
    {
        public static JavaFileSystem GetDefault() => new();
        public static JavaFileSystem GetFileSystem(Uri uri) => new();
        public static JavaFileSystem NewFileSystem(Uri uri, IDictionary<string, object> environment) => new();
    }
    public sealed class JavaFileSystemProvider
    {
        public static IEnumerable<JavaFileSystemProvider> InstalledProviders() => new[] { new JavaFileSystemProvider() };
        public string GetScheme() => "file";
    }
    public abstract class JavaFileTypeDetector
    {
        public abstract string? ProbeContentType(string path);
    }
    public sealed class JavaZipEntry
    {
        private readonly string name;
        public JavaZipEntry(string name) => this.name = name;
        public JavaZipEntry(System.IO.Compression.ZipArchiveEntry entry) => name = entry.FullName;
        public string GetName() => name;
        public bool IsDirectory() => name.EndsWith("/", StringComparison.Ordinal);
    }
    public sealed class JavaZipInputStream : IDisposable
    {
        private readonly System.IO.Compression.ZipArchive archive; private int index = -1; private System.IO.Stream? current;
        public JavaZipInputStream(System.IO.Stream stream) => archive = new(stream, System.IO.Compression.ZipArchiveMode.Read);
        public JavaZipEntry? GetNextEntry()
        { current?.Dispose(); index++; if (index >= archive.Entries.Count) return null; var entry = archive.Entries[index]; current = entry.Open(); return new(entry); }
        public byte[] ReadAllBytes() { using var memory = new System.IO.MemoryStream(); current!.CopyTo(memory); return memory.ToArray(); }
        public void CloseEntry() { current?.Dispose(); current = null; }
        public void Dispose() => archive.Dispose();
    }
    public sealed class JavaZipOutputStream : IDisposable
    {
        private readonly System.IO.Compression.ZipArchive archive; private System.IO.Stream? current;
        public JavaZipOutputStream(System.IO.Stream stream) => archive = new(stream, System.IO.Compression.ZipArchiveMode.Create, true);
        public void PutNextEntry(JavaZipEntry entry) { current?.Dispose(); current = archive.CreateEntry(entry.GetName()).Open(); }
        public void Write(byte[] bytes) => current!.Write(bytes);
        public void CloseEntry() { current?.Dispose(); current = null; }
        public void Finish() => Dispose();
        public void Dispose() => archive.Dispose();
    }

    public sealed class JavaMessageDigest
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

    public sealed class JavaDigestInputStream : System.IO.Stream
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
    }

    public sealed class JavaDigestOutputStream : System.IO.Stream
    {
        private readonly System.IO.Stream stream; private readonly JavaMessageDigest digest;
        public JavaDigestOutputStream(System.IO.Stream stream, JavaMessageDigest digest) { this.stream = stream; this.digest = digest; }
        public override void Write(byte[] buffer, int offset, int count) { stream.Write(buffer, offset, count); digest.Update(buffer[offset..(offset + count)]); }
        public override bool CanRead => false; public override bool CanSeek => false; public override bool CanWrite => stream.CanWrite;
        public override long Length => stream.Length; public override long Position { get => stream.Position; set => throw new NotSupportedException(); }
        public override void Flush() => stream.Flush(); public override int Read(byte[] b, int o, int c) => throw new NotSupportedException();
        public override long Seek(long o, System.IO.SeekOrigin so) => throw new NotSupportedException(); public override void SetLength(long v) => stream.SetLength(v);
    }

    public sealed class JavaSecureRandom { }
    public sealed class JavaKeyStore
    {
        public static string GetDefaultType() => "X509";
        public static JavaKeyStore GetInstance(string type) => new();
        public void Load(object? parameter) { }
        public void SetCertificateEntry(string alias, System.Security.Cryptography.X509Certificates.X509Certificate2 certificate) { }
    }
    public sealed class JavaCertificateFactory
    {
        public static JavaCertificateFactory GetInstance(string type) => new();
        public System.Security.Cryptography.X509Certificates.X509Certificate2 GenerateCertificate(System.IO.Stream stream)
        {
            using var bytes = new System.IO.MemoryStream();
            stream.CopyTo(bytes);
#pragma warning disable SYSLIB0057 // net8-compatible certificate construction
            return new System.Security.Cryptography.X509Certificates.X509Certificate2(bytes.ToArray());
#pragma warning restore SYSLIB0057
        }
        public ICollection<System.Security.Cryptography.X509Certificates.X509Certificate2> GenerateCertificates(System.IO.Stream stream) =>
            new[] { GenerateCertificate(stream) };
    }
    public sealed class JavaSslContext
    {
        public static JavaSslContext GetDefault() => new();
        public static JavaSslContext GetInstance(string protocol) => new();
        public void Init(object? keyManagers, object? trustManagers, JavaSecureRandom random) { }
    }
    public sealed class JavaTrustManagerFactory
    {
        public static JavaTrustManagerFactory GetInstance(string algorithm) => new();
        public void Init(JavaKeyStore keyStore) { }
        public object[] GetTrustManagers() => Array.Empty<object>();
    }

    public enum JavaHttpRedirect { NEVER, NORMAL, ALWAYS }
    public enum JavaProxyType { DIRECT, HTTP, SOCKS }
    public enum JavaHttpVersion { HTTP_1_1, HTTP_2 }
    public delegate T JavaHttpBodyHandler<T>(System.Net.Http.HttpResponseMessage response);

    public sealed class JavaHttpResponse<T>
    {
        private readonly System.Net.Http.HttpResponseMessage response;
        private readonly T body;
        public JavaHttpResponse(System.Net.Http.HttpResponseMessage response, T body)
        { this.response = response; this.body = body; }
        public int StatusCode() => (int)response.StatusCode;
        public T Body() => body;
        public JavaHttpRequest Request() => new(response.RequestMessage!);
        public JavaOptional<JavaHttpResponse<T>> PreviousResponse() => JavaOptional<JavaHttpResponse<T>>.Empty();
        public Uri Uri() => response.RequestMessage?.RequestUri ?? new Uri("about:blank");
        public JavaHttpHeaders Headers() => new(response.Headers.Concat(response.Content.Headers)
            .ToDictionary(header => header.Key,
                          header => (IReadOnlyList<string>)header.Value.ToList(),
                          StringComparer.OrdinalIgnoreCase));
        public JavaHttpVersion Version() => response.Version.Major >= 2 ? JavaHttpVersion.HTTP_2 : JavaHttpVersion.HTTP_1_1;
    }

    public sealed class JavaHttpRequest
    {
        internal System.Net.Http.HttpRequestMessage Message { get; }
        public JavaHttpRequest(System.Net.Http.HttpRequestMessage message) => Message = message;
        public static Builder NewBuilder() => new();
        public static Builder NewBuilder(Uri uri) => new Builder().Uri(uri);
        public Uri Uri() => Message.RequestUri!;
        public JavaHttpHeaders Headers() => new(Message.Headers.Concat(Message.Content?.Headers ?? Enumerable.Empty<KeyValuePair<string, IEnumerable<string>>>())
            .ToDictionary(header => header.Key,
                          header => (IReadOnlyList<string>)header.Value.ToList(),
                          StringComparer.OrdinalIgnoreCase));
        public bool ExpectContinue() => Message.Headers.ExpectContinue ?? false;
        public string Method() => Message.Method.Method;
        public JavaOptional<TimeSpan> Timeout() => JavaOptional<TimeSpan>.Empty();
        public JavaOptional<JavaHttpVersion> Version() =>
            JavaOptional<JavaHttpVersion>.Of(Message.Version.Major >= 2 ? JavaHttpVersion.HTTP_2 : JavaHttpVersion.HTTP_1_1);
        public JavaOptional<object> BodyPublisher() => JavaOptional<object>.OfNullable(Message.Content);
        public sealed class Builder
        {
            private readonly System.Net.Http.HttpRequestMessage message = new();
            public Builder Uri(Uri uri) { message.RequestUri = uri; return this; }
            public Builder Timeout(TimeSpan timeout) { return this; }
            public Builder Version(JavaHttpVersion version) { return this; }
            public Builder Header(string name, string value) { message.Headers.TryAddWithoutValidation(name, value); return this; }
            public Builder SetHeader(string name, string value) { message.Headers.Remove(name); return Header(name, value); }
            public Builder ExpectContinue(bool value) { message.Headers.ExpectContinue = value; return this; }
            public Builder Method(string method, object? body) { message.Method = new System.Net.Http.HttpMethod(method); return this; }
            public Builder GET() { message.Method = System.Net.Http.HttpMethod.Get; return this; }
            public Builder DELETE() { message.Method = System.Net.Http.HttpMethod.Delete; return this; }
            public JavaHttpRequest Build() => new(message);
        }
    }

    public static class JavaHttpBodyPublishers
    {
        public static object NoBody() => new object();
    }

    public sealed class JavaHttpHeaders
    {
        private readonly IReadOnlyDictionary<string, IReadOnlyList<string>> values;
        public JavaHttpHeaders(IReadOnlyDictionary<string, IReadOnlyList<string>> values) => this.values = values;
        public JavaOptional<string> FirstValue(string name) =>
            values.TryGetValue(name, out var entries) && entries.Count > 0
                ? JavaOptional<string>.Of(entries[0]) : JavaOptional<string>.Empty();
        public IReadOnlyDictionary<string, IReadOnlyList<string>> Map() => values;
    }

    public static class JavaHttpBodyHandlers
    {
        public static JavaHttpBodyHandler<System.IO.Stream> OfInputStream() =>
            response => response.Content.ReadAsStream();
        public static JavaHttpBodyHandler<sbyte[]> OfByteArray() =>
            response => response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                .Select(value => unchecked((sbyte)value)).ToArray();
    }

    public sealed class JavaHttpClient : IDisposable
    {
        private readonly System.Net.Http.HttpClient client = new();
        public static Builder NewBuilder() => new();
        public JavaHttpResponse<T> Send<T>(JavaHttpRequest request, JavaHttpBodyHandler<T> handler)
        {
            var response = client.Send(request.Message);
            return new JavaHttpResponse<T>(response, handler(response));
        }
        public void Close() => Dispose();
        public void Dispose() => client.Dispose();
        public sealed class Builder
        {
            public Builder ConnectTimeout(TimeSpan timeout) => this;
            public Builder FollowRedirects(JavaHttpRedirect redirect) => this;
            public Builder Version(JavaHttpVersion version) => this;
            public Builder SslContext(object context) => this;
            public Builder Proxy(object proxySelector) => this;
            public JavaHttpClient Build() => new();
        }
    }
}

namespace Pkl.Core.Runtime.Polyglot
{
    public sealed class PolyglotException : Exception
    {
        public PolyglotException(string? message = null, Exception? cause = null)
            : base(message, cause) { }

        public bool IsCancelled() => false;
    }

    public sealed class Engine
    {
        public static Builder NewBuilder(params string[] languages) => new();

        public sealed class Builder
        {
            public Builder Option(string key, string value) => this;
            public Engine Build() => new();
        }
    }

    public sealed class Context : IDisposable
    {
        public static Builder NewBuilder(params string[] languages) => new();
        public void Initialize(string language) { }
        public void Enter() { }
        public void Leave() { }
        public void Close(bool cancelIfExecuting) { }
        public void Dispose() => Close(false);

        public sealed class Builder
        {
            public Builder Engine(Engine engine) => this;
            public Context Build() => new();
        }
    }
}

namespace Pkl.Core.Runtime.GraalCollections
{
    public class UnmodifiableEconomicMap<K, V> where K : notnull
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
    }

    public sealed class EconomicMap<K, V> : UnmodifiableEconomicMap<K, V> where K : notnull
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

    public static class EconomicMap
    {
        internal static EconomicMap<K, V> Create<K, V>() where K : notnull => new();
        internal static EconomicMap<K, V> Create<K, V>(int capacity) where K : notnull => new(capacity);
    }

    public class UnmodifiableEconomicSet<T> : IEnumerable<T> where T : notnull
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

    public sealed class EconomicSet<T> : UnmodifiableEconomicSet<T> where T : notnull
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

    public static class EconomicSet
    {
        internal static EconomicSet<T> Create<T>() where T : notnull => new();
        internal static EconomicSet<T> Create<T>(int capacity) where T : notnull => new(capacity);
    }

    public sealed class UnmodifiableMapCursor<K, V> where K : notnull
    {
        private readonly IEnumerator<KeyValuePair<K, V>> entries;
        internal UnmodifiableMapCursor(IEnumerator<KeyValuePair<K, V>> entries) => this.entries = entries;
        internal bool Advance() => entries.MoveNext();
        internal K GetKey() => entries.Current.Key;
        internal V GetValue() => entries.Current.Value;
        internal IEnumerable<KeyValuePair<K, V>> Entries()
        {
            while (entries.MoveNext()) yield return entries.Current;
        }
    }
}

namespace Pkl.Core.Runtime.Truffle.api.source
{
    public sealed class Source
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
        internal SourceSection CreateSection(int start) => CreateSection(start, characters.Length - start);
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

    public sealed class SourceSection
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

namespace Pkl.Core.Runtime.Truffle.api.frame
{
    public enum FrameSlotKind { Illegal, Object, Long, Double, Boolean }

    public sealed class FrameSlotTypeException : Exception { }

    public class Frame
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

    public class VirtualFrame : Frame
    {
        internal VirtualFrame(object?[]? arguments = null) : base(arguments) { }
        protected VirtualFrame(object?[]? arguments, FrameDescriptor? descriptor) : base(arguments, descriptor) { }
        internal MaterializedFrame Materialize()
        {
            var result = new MaterializedFrame(Arguments, GetFrameDescriptor());
            CopyStateTo(result);
            return result;
        }
    }

    public sealed class MaterializedFrame : VirtualFrame
    {
        internal MaterializedFrame(object?[]? arguments = null, FrameDescriptor? descriptor = null) :
            base(arguments, descriptor) { }
    }

    public sealed class FrameDescriptor
    {
        private readonly Dictionary<int, FrameSlotKind> slotKinds = new();
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
        internal object? GetSlotName(int slot) => auxiliarySlots.FirstOrDefault(entry => entry.Value == slot).Key;
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
            internal Builder(int capacity) => slots = 0;
            internal int AddSlot(FrameSlotKind kind, object identifier, object? info) => slots++;
            internal FrameDescriptor Build() => new() { slots = slots };
        }
    }
}

namespace Pkl.Core.Runtime.Truffle.api.nodes
{
    using Pkl.Core.Runtime.Truffle.api.frame;
    using Pkl.Core.Runtime.Truffle.api.source;

    public class Node
    {
        private Node? parent;
        public virtual SourceSection? GetSourceSection() => null;
        internal Node? GetParent() => parent;
        internal T Insert<T>(T child) where T : Node { child.parent = this; return child; }
        internal RootNode? GetRootNode()
        {
            Node? current = this;
            while (current is not null && current is not RootNode) current = current.parent;
            return current as RootNode;
        }
        internal void AdoptChildren() { }
        internal Node DeepCopy() => (Node)MemberwiseClone();
        internal T Replace<T>(T replacement) where T : Node
        {
            replacement.parent = parent;
            return replacement;
        }
        internal bool Accept(Func<Node, bool> visitor) => visitor(this);
    }

    // Truffle uses this exception family for non-error interpreter control
    // flow.  Keeping it distinct from ordinary failures preserves the
    // generated evaluator's catch behavior.
    public class ControlFlowException : Exception { }

    public sealed class UnexpectedResultException : Exception
    {
        private readonly object result;

        public UnexpectedResultException(object result) => this.result = result;

        public object GetResult() => result;
    }

    public static class LoopNode
    {
        // Truffle consumes this count as a compilation profile hint. The CLR
        // has no corresponding interpreter notification, so evaluation keeps
        // the observable loop behavior while deliberately ignoring the hint.
        public static void ReportLoopCount(Node node, long count) { }
    }

    [AttributeUsage(AttributeTargets.Class, Inherited = true)]
    public sealed class NodeInfo : Attribute
    {
        private readonly string shortName;
        public NodeInfo(string shortName = "") => this.shortName = shortName;
        public string ShortName() => shortName;
    }

    public abstract class RootNode : Node
    {
        private readonly FrameDescriptor descriptor;
        protected RootNode(object? language, FrameDescriptor descriptor) => this.descriptor = descriptor;
        public virtual object? Execute(VirtualFrame frame) => null;
        public virtual string GetName() => GetType().Name;
        public virtual bool IsInternal() => false;
        internal FrameDescriptor GetFrameDescriptor() => descriptor;
        internal Pkl.Core.Runtime.Truffle.api.RootCallTarget GetCallTarget() => new(this);
    }

    public sealed class DirectCallNode : Node
    {
        private readonly Pkl.Core.Runtime.Truffle.api.CallTarget target;
        private DirectCallNode(Pkl.Core.Runtime.Truffle.api.CallTarget target) => this.target = target;
        internal static DirectCallNode Create(Pkl.Core.Runtime.Truffle.api.CallTarget target) => new(target);
        internal object? Call(params object?[] arguments) => target.Call(arguments);
    }

    public sealed class IndirectCallNode : Node
    {
        internal static IndirectCallNode Create() => new();
        internal static IndirectCallNode GetUncached() => new();
        internal object? Call(Pkl.Core.Runtime.Truffle.api.CallTarget target, params object?[] arguments) =>
            target.Call(arguments);
    }
}

namespace Pkl.Core.Runtime.Truffle.api.instrumentation
{
    using Pkl.Core.Runtime.Truffle.api.frame;
    using Pkl.Core.Runtime.Truffle.api.nodes;

    public interface InstrumentableNode
    {
        public interface WrapperNode
        {
            Node GetDelegateNode();
            ProbeNode GetProbeNode();
        }
    }

    public class ProbeNode : Node
    {
        public static readonly object UNWIND_ACTION_REENTER = new();

        public virtual void OnEnter(VirtualFrame frame) { }
        public virtual void OnReturnValue(VirtualFrame frame, object? value) { }
        public virtual object? OnReturnExceptionalOrUnwind(
            VirtualFrame frame,
            Exception exception,
            bool wasOnReturnExecuted) => null;
    }

    public sealed class EventContext
    {
        private readonly Node instrumentedNode;
        internal EventContext(Node instrumentedNode) => this.instrumentedNode = instrumentedNode;
        public Node GetInstrumentedNode() => instrumentedNode;
    }

    public abstract class ExecutionEventNode : Node
    {
        protected internal virtual void OnReturnValue(VirtualFrame frame, object? result) { }
    }

    public delegate ExecutionEventNode ExecutionEventNodeFactory(EventContext context);

    public sealed class EventBinding<T> : IDisposable
    {
        public void Dispose() { }
    }

    public sealed class Instrumenter
    {
        public EventBinding<ExecutionEventNodeFactory> AttachExecutionEventFactory(
            SourceSectionFilter filter,
            ExecutionEventNodeFactory factory) => new();
        public EventBinding<T> AttachExecutionEventFactory<T>(SourceSectionFilter filter, T factory) where T : Delegate => new();
    }

    public sealed class SourceSectionFilter
    {
        public static Builder NewBuilder() => new();

        public sealed class Builder
        {
            public Builder TagIs(params Type[] tags) => this;
            public SourceSectionFilter Build() => new();
        }
    }

    public class Tag { }
}

namespace Pkl.Core.Runtime.Truffle.api.dsl
{
    using Pkl.Core.Runtime.Truffle.api.nodes;

    public static class DSLSupport
    {
        public static bool AssertIdempotence(bool value) => value;
    }

    public sealed class UnsupportedSpecializationException : Exception
    {
        public UnsupportedSpecializationException(
            Node node,
            Node[] suppliedNodes,
            params object?[] suppliedValues)
            : base("No generated Truffle specialization accepts the supplied values.") { }
    }

    public static class InlineSupport
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

namespace Pkl.Core.Runtime.Truffle.api.exception
{
    using Pkl.Core.Runtime.Truffle.api.nodes;

    public class AbstractTruffleException : Exception
    {
        internal const int UNLIMITED_STACK_TRACE = -1;
        protected AbstractTruffleException(string? message, Exception? cause, int stackTraceLimit, Node? location)
            : base(message, cause) { }
    }
}

namespace Pkl.Core.Runtime.Truffle.api
{
    using Pkl.Core.Runtime.Truffle.api.frame;
    using Pkl.Core.Runtime.Truffle.api.nodes;
    using Pkl.Core.Runtime.Truffle.api.source;

    public class CallTarget
    {
        private readonly RootNode? root;
        internal CallTarget(RootNode? root = null) => this.root = root;
        internal virtual object? Call(params object?[] arguments) =>
            root?.Execute(new VirtualFrame(arguments));
        internal RootNode? GetRootNode() => root;
    }

    public sealed class RootCallTarget : CallTarget
    {
        internal RootCallTarget(RootNode root) : base(root) { }
    }

    public static class CompilerDirectives
    {
        internal static void TransferToInterpreter() { }
        internal static void TransferToInterpreterAndInvalidate() { }
        internal static void Blackhole(object? value) { }
    }

    public static class CompilerAsserts
    {
        internal static void NeverPartOfCompilation() { }
    }

    public static class TruffleOptions
    {
        internal const bool AOT = false;
    }

    public static class Truffle
    {
        private static readonly TruffleRuntime Runtime = new();
        internal static TruffleRuntime GetRuntime() => Runtime;
    }

    public sealed class TruffleRuntime
    {
        internal VirtualFrame CreateVirtualFrame(object?[] arguments, FrameDescriptor descriptor) => new(arguments);
        internal MaterializedFrame CreateMaterializedFrame(object?[] arguments) => new(arguments);
        internal IndirectCallNode CreateIndirectCallNode() => IndirectCallNode.GetUncached();
        internal RootCallTarget CreateCallTarget(RootNode root) => new(root);
    }

    public sealed class ContextThreadLocal<T> where T : class
    {
        private readonly System.Threading.ThreadLocal<T> local;
        internal ContextThreadLocal(Func<object?, System.Threading.Thread, T> factory) =>
            local = new System.Threading.ThreadLocal<T>(
                () => factory(null, System.Threading.Thread.CurrentThread));
        public T Get() => local.Value!;
    }

    public sealed class ContextLocalSupport
    {
        public ContextThreadLocal<T> CreateContextThreadLocal<T>(
            Func<object?, System.Threading.Thread, T> factory) where T : class => new(factory);
    }

    public abstract class TruffleLanguage<TContext> where TContext : class
    {
        protected readonly ContextLocalSupport locals = new();
        protected internal abstract TContext CreateContext(TruffleLanguage.Env env);
        protected internal abstract CallTarget Parse(TruffleLanguage.ParsingRequest request);
    }

    public static class TruffleLanguage
    {
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
            private ContextReference() { }
            internal static ContextReference<TContext> Create(Type languageType) => new();
            internal static ContextReference<T> Create<TLanguage, T>(Type languageType) where T : class => new();
            internal TContext Get(Node? node) =>
                throw new InvalidOperationException("Pkl context has not been installed for this execution.");
        }
    }

    public static class TruffleStackTrace
    {
        internal static IReadOnlyList<TruffleStackTraceElement> GetStackTrace(Exception exception) =>
            Array.Empty<TruffleStackTraceElement>();
    }

    public sealed class TruffleStackTraceElement
    {
        internal Node? GetLocation() => null;
        internal CallTarget? GetTarget() => null;
    }
}

// SnakeYAML Engine contracts reached by the evaluator's YAML parser closure.
// The generated Pkl classes retain ownership of Pkl conversion and value-model
// behavior; these types provide the external library surface resolved by Spoon.
namespace Pkl.Core.Runtime.SnakeYaml.api
{
    using Pkl.Core.Runtime.SnakeYaml.constructor;
    using Pkl.Core.Runtime.SnakeYaml.nodes;

    public interface ConstructNode
    {
        object? Construct(Node node);
        void ConstructRecursive(Node node, object? data) { }
    }

    public sealed class LoadSettings
    {
        internal LoadSettings() { }
        public static LoadSettingsBuilder Builder() => new();
    }

    public sealed class LoadSettingsBuilder
    {
        public LoadSettingsBuilder SetAllowNonScalarKeys(bool value) => this;
        public LoadSettingsBuilder SetLabel(string value) => this;
        public LoadSettingsBuilder SetMaxAliasesForCollections(int value) => this;
        public LoadSettingsBuilder SetSchema(schema.Schema value) => this;
        public LoadSettings Build() => new();
    }

    public sealed class Load
    {
        private readonly BaseConstructor constructor;
        public Load(LoadSettings settings, BaseConstructor constructor) => this.constructor = constructor;
        public object? LoadFromString(string text) =>
            throw new exceptions.YamlEngineException("SnakeYAML parsing substrate is not implemented yet.");
        public IEnumerable<object?> LoadAllFromString(string text) =>
            throw new exceptions.YamlEngineException("SnakeYAML parsing substrate is not implemented yet.");
    }
}

namespace Pkl.Core.Runtime.SnakeYaml.constructor
{
    using Pkl.Core.Runtime.SnakeYaml.api;
    using Pkl.Core.Runtime.SnakeYaml.nodes;

    public class BaseConstructor
    {
        protected readonly Dictionary<Tag, ConstructNode> TagConstructors = new();
        protected Dictionary<Tag, ConstructNode> tagConstructors => TagConstructors;
        public virtual object? ConstructObject(Node node) => null;
        protected void FlattenMapping(MappingNode node) { }
    }

    public class StandardConstructor : BaseConstructor
    {
        public StandardConstructor(api.LoadSettings settings) { }
    }
}

namespace Pkl.Core.Runtime.SnakeYaml.exceptions
{
    public sealed class Mark { }

    public class YamlEngineException : Exception
    {
        public YamlEngineException(string message) : base(message) { }
    }
}

namespace Pkl.Core.Runtime.SnakeYaml.nodes
{
    using Pkl.Core.Runtime.SnakeYaml.exceptions;

    public class Tag : IEquatable<Tag>
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

    public class Node
    {
        public virtual JavaOptional<Mark> GetStartMark() => JavaOptional<Mark>.Empty();
        public virtual JavaOptional<Mark> GetEndMark() => JavaOptional<Mark>.Empty();
        public virtual bool IsRecursive() => false;
    }

    public sealed class ScalarNode : Node
    {
        private readonly string value;
        public ScalarNode(string value) => this.value = value;
        public string GetValue() => value;
    }

    public sealed class SequenceNode : Node
    {
        private readonly IList<Node> value;
        public SequenceNode(IList<Node> value) => this.value = value;
        public IList<Node> GetValue() => value;
    }

    public sealed class NodeTuple
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

    public sealed class MappingNode : Node
    {
        private readonly IList<NodeTuple> value;
        public MappingNode(IList<NodeTuple> value) => this.value = value;
        public IList<NodeTuple> GetValue() => value;
    }
}

namespace Pkl.Core.Runtime.SnakeYaml.resolver
{
    using Pkl.Core.Runtime.SnakeYaml.nodes;

    public interface ScalarResolver
    {
        Tag Resolve(string value, bool implicitValue);
    }
}

namespace Pkl.Core.Runtime.SnakeYaml.schema
{
    using Pkl.Core.Runtime.SnakeYaml.api;
    using Pkl.Core.Runtime.SnakeYaml.nodes;
    using Pkl.Core.Runtime.SnakeYaml.resolver;

    public abstract class Schema
    {
        public abstract ScalarResolver GetScalarResolver();
        public abstract IDictionary<Tag, ConstructNode> GetSchemaTagConstructors();
    }
}

namespace Pkl.Core.Util
{
    public sealed partial class HttpUtils
    {
        public static void CheckHasStatusCode200<T>(global::Pkl.Core.Runtime.JavaHttpResponse<T> response)
        {
            if (response.StatusCode() == 200) return;
            if (response.Body() is IDisposable disposable) disposable.Dispose();
            throw new System.IO.IOException(ErrorMessages.Create("badHttpStatusCode", response.StatusCode(), response.Uri()));
        }
    }
}

namespace Pkl.Core
{
    public sealed partial class PClassInfo<T>
    {
        public PClassInfo<object> AsObject() =>
            this is PClassInfo<object> exact
                ? exact
                : new PClassInfo<object>(this.moduleName, this.className, this.javaClass, this.moduleUri);
    }
}
