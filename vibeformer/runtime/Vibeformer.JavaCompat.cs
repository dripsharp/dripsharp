// Ordinary generated-product support for Java contracts with no direct .NET API.
// This file is copied unchanged into disposable projects; it is not a second AST
// and contains no Pkl parser behavior.
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
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;

namespace Vibeformer.Runtime;

internal static class JavaStandardCharsets
{
    internal static readonly Encoding UTF8 = new UTF8Encoding(false);
    internal static readonly Encoding USASCII = Encoding.ASCII;
    internal static readonly Encoding ISO88591 = Encoding.Latin1;
}

internal delegate TResult JavaIntFunction<out TResult>(int value);
internal delegate int JavaToIntFunction<in TValue>(TValue value);
internal delegate long JavaToLongFunction<in TValue>(TValue value);
internal delegate bool JavaBiPredicate<in TLeft, in TRight>(TLeft left, TRight right);

internal interface IJavaEconomicMapCursor<out K, out V>
{
    bool Advance();
    K GetKey();
    V GetValue();
}

internal interface IJavaEconomicMap<K, out V> where K : notnull
{
    V? Get(K key);
    bool ContainsKey(K key);
    int Size();
    IJavaEconomicMapCursor<K, V> GetEntries();
}

internal
enum JavaTimeUnit { NANOSECONDS, MICROSECONDS, MILLISECONDS, SECONDS, MINUTES, HOURS, DAYS }
internal static class JavaTimeUnits
{
    internal static TimeSpan ToTimeSpan(long value, JavaTimeUnit unit) => unit switch
    {
        JavaTimeUnit.NANOSECONDS => TimeSpan.FromTicks(value / 100),
        JavaTimeUnit.MICROSECONDS => TimeSpan.FromTicks(checked(value * 10)),
        JavaTimeUnit.MILLISECONDS => TimeSpan.FromMilliseconds(value),
        JavaTimeUnit.SECONDS => TimeSpan.FromSeconds(value),
        JavaTimeUnit.MINUTES => TimeSpan.FromMinutes(value),
        JavaTimeUnit.HOURS => TimeSpan.FromHours(value),
        JavaTimeUnit.DAYS => TimeSpan.FromDays(value),
        _ => throw new ArgumentOutOfRangeException(nameof(unit))
    };
}
internal enum JavaProcessRedirect { INHERIT }

// C# forbids goto from a finally clause, while Java permits a labeled break or
// continue to complete a finally clause abruptly. The recursive translator
// catches this internal signal at the nearest translated try boundary before
// ordinary Java exception handlers can observe it.
internal sealed class JavaLabeledControlFlowException(int branchId) : Exception
{
    internal int BranchId { get; } = branchId;
}

// Carries cancellation through ordinary translated Java blocking primitives.
// Product runtimes install a token at their evaluation boundary; the generic
// compatibility layer only observes that token and does not define product
// timeout policy.
internal sealed class JavaCancellationException : OperationCanceledException
{
    internal JavaCancellationException(CancellationToken token)
        : base("The translated Java operation was cancelled.", token) { }
}

internal sealed class JavaAssertionError(string? message) : Exception(message);

internal sealed class JavaUriSyntaxException : UriFormatException
{
    internal string InputText { get; }
    internal string Reason { get; }

    internal JavaUriSyntaxException(string input, string reason, int index = -1)
        : base(index < 0 ? $"{reason}: {input}" : $"{reason} at index {index}: {input}")
    {
        InputText = input;
        Reason = reason;
    }
}

// java.nio.file.NoSuchFileException carries the missing path as its message.
// System.IO.FileNotFoundException instead decorates Message and therefore
// changes the evaluator diagnostic even when given the same path.
internal sealed class NoSuchFileException : IOException
{
    internal NoSuchFileException(string path) : base(path) { }
    internal NoSuchFileException(string path, Exception cause) : base(path, cause) { }
}

internal sealed class JavaFileNotFoundException : FileNotFoundException
{
    // Java's no-argument FileNotFoundException has a null message. The CLR
    // supplies a generic fallback message, which would become a spurious Pkl
    // cause line unless the Java contract is retained explicitly.
    public override string Message => null!;
}

internal static class JavaCancellation
{
    private sealed record Binding(object Owner, CancellationToken Token);
    private static readonly AsyncLocal<IReadOnlyList<Binding>?> Bindings = new();

    internal static CancellationToken CurrentToken =>
        Bindings.Value is { Count: > 0 } bindings
            ? bindings[^1].Token
            : CancellationToken.None;

    internal static void Push(object owner, CancellationToken token)
    {
        var bindings = Bindings.Value is { } current
            ? current.ToList()
            : new List<Binding>();
        bindings.Add(new Binding(owner, token));
        Bindings.Value = bindings;
    }

    internal static void Pop(object owner)
    {
        if (Bindings.Value is not { Count: > 0 } current ||
            !ReferenceEquals(current[^1].Owner, owner))
            throw new InvalidOperationException("Java cancellation scopes must be left in enter order.");
        Bindings.Value = current.Count == 1 ? null : current.Take(current.Count - 1).ToList();
    }

    internal static void ThrowIfCancellationRequested()
    {
        var token = CurrentToken;
        if (token.IsCancellationRequested) throw new JavaCancellationException(token);
    }
}

// Java's Future and CompletableFuture share one reference in APIs that cache
// an asynchronously completed result. TaskCompletionSource is the matching
// .NET primitive, while this small facade preserves Java's blocking get() and
// ExecutionException wrapping for translated callers.
internal sealed class JavaFuture<T>
{
    private readonly TaskCompletionSource<T>? completion;
    private readonly Task<T> task;

    internal JavaFuture()
    {
        completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        task = completion.Task;
    }

    private JavaFuture(Task<T> task) => this.task = task;
    internal Task CompletionTask => task;

    internal static JavaFuture<T> Run(Func<T> callable, CancellationToken cancellation) =>
        new(Task.Run(callable, cancellation));

    internal static JavaFuture<T> Run(Func<T> callable, CancellationToken cancellation,
        TaskScheduler scheduler) =>
        new(Task.Factory.StartNew(callable, cancellation,
            TaskCreationOptions.DenyChildAttach, scheduler));

    internal bool Complete(T value) => completion?.TrySetResult(value) ?? false;
    internal bool CompleteExceptionally(Exception error) =>
        completion?.TrySetException(error) ?? false;

    internal T Get()
    {
        var cancellation = JavaCancellation.CurrentToken;
        try
        {
            return cancellation.CanBeCanceled
                ? task.WaitAsync(cancellation).GetAwaiter().GetResult()
                : task.GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            throw new JavaCancellationException(cancellation);
        }
        catch (Exception error)
        {
            throw new AggregateException(error);
        }
    }

    internal T Get(long timeout, JavaTimeUnit unit)
    {
        try
        {
            return task.WaitAsync(JavaTimeUnits.ToTimeSpan(timeout, unit),
                    JavaCancellation.CurrentToken)
                .GetAwaiter().GetResult();
        }
        catch (OperationCanceledException) when (JavaCancellation.CurrentToken.IsCancellationRequested)
        {
            throw new JavaCancellationException(JavaCancellation.CurrentToken);
        }
        catch (TimeoutException)
        {
            throw;
        }
        catch (Exception error)
        {
            throw new AggregateException(error);
        }
    }
}

internal sealed class JavaExecutorService
{
    private readonly object sync = new();
    private readonly List<Task> tasks = new();
    private readonly CancellationTokenSource cancellation = new();
    private readonly JavaFixedThreadTaskScheduler? scheduler;
    private bool shutdown;

    internal JavaExecutorService() { }

    internal JavaExecutorService(int workerCount) :
        this(workerCount, runnable => new JavaThread(runnable)) { }

    internal JavaExecutorService(int workerCount, JavaThreadFactory threadFactory) =>
        scheduler = new JavaFixedThreadTaskScheduler(workerCount, threadFactory);

    internal JavaFuture<T> Submit<T>(Func<T> callable) =>
        Track(scheduler is null
            ? JavaFuture<T>.Run(callable, Token())
            : JavaFuture<T>.Run(callable, Token(), scheduler));

    internal JavaFuture<object> Submit(Action runnable) =>
        Submit<object>(() =>
        {
            runnable();
            return null!;
        });

    internal void Shutdown()
    {
        lock (sync) shutdown = true;
        scheduler?.Complete();
    }

    internal IList<Action> ShutdownNow()
    {
        lock (sync) shutdown = true;
        cancellation.Cancel();
        scheduler?.Complete();
        return new List<Action>();
    }

    internal bool AwaitTermination(long timeout, JavaTimeUnit unit)
    {
        Task[] pending;
        lock (sync) pending = tasks.ToArray();
        var duration = JavaTimeUnits.ToTimeSpan(timeout, unit);
        var started = Stopwatch.StartNew();
        if (!Task.WhenAll(pending).Wait(duration)) return false;
        return scheduler?.AwaitTermination(duration - started.Elapsed) ?? true;
    }

    private CancellationToken Token()
    {
        lock (sync)
        {
            if (shutdown) throw new InvalidOperationException("Executor service is shut down.");
            return cancellation.Token;
        }
    }

    private JavaFuture<T> Track<T>(JavaFuture<T> future)
    {
        var marker = future.CompletionTask;
        lock (sync) tasks.Add(marker);
        _ = marker.ContinueWith(completed =>
        {
            lock (sync) tasks.Remove(completed);
        }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously,
            TaskScheduler.Default);
        return future;
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

internal class JavaFilterOutputStream : Stream
{
    protected readonly Stream @out;

    internal JavaFilterOutputStream(Stream output) => @out = output;
    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => @out.CanWrite;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush() => @out.Flush();
    public override void Write(byte[] buffer, int offset, int count) =>
        @out.Write(buffer, offset, count);
    public override void WriteByte(byte value) => @out.WriteByte(value);
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing) @out.Dispose();
        base.Dispose(disposing);
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

internal delegate JavaThread JavaThreadFactory(Action runnable);

internal sealed class JavaThread
{
    private readonly Thread thread;

    internal JavaThread(Action runnable) => thread = new Thread(() => runnable());
    internal JavaThread(Action runnable, string name) : this(runnable) => SetName(name);
    private JavaThread(Thread thread) => this.thread = thread;
    internal static JavaThread CurrentThread() => new(Thread.CurrentThread);
    internal static void Sleep(long milliseconds) => Thread.Sleep(checked((int)milliseconds));
    internal void SetDaemon(bool daemon) => thread.IsBackground = daemon;
    internal void SetName(string name) => thread.Name = name;
    internal void Start() => thread.Start();
    internal void Interrupt() => thread.Interrupt();
    internal bool Join(TimeSpan timeout) => thread.Join(timeout);
}

internal sealed class JavaFixedThreadTaskScheduler : TaskScheduler
{
    private readonly BlockingCollection<Task> tasks = new();
    private readonly IReadOnlyList<JavaThread> workers;

    internal JavaFixedThreadTaskScheduler(int workerCount, JavaThreadFactory threadFactory)
    {
        if (workerCount <= 0) throw new ArgumentOutOfRangeException(nameof(workerCount));
        ArgumentNullException.ThrowIfNull(threadFactory);
        var created = new List<JavaThread>(workerCount);
        for (var index = 0; index < workerCount; index++)
        {
            var worker = threadFactory(Consume);
            if (worker is null)
                throw new InvalidOperationException("A Java thread factory returned null.");
            created.Add(worker);
        }
        workers = created;
        foreach (var worker in workers) worker.Start();
    }

    internal void Complete() => tasks.CompleteAdding();

    internal bool AwaitTermination(TimeSpan timeout)
    {
        if (timeout <= TimeSpan.Zero) return workers.All(worker => worker.Join(TimeSpan.Zero));
        var started = Stopwatch.StartNew();
        foreach (var worker in workers)
        {
            var remaining = timeout - started.Elapsed;
            if (remaining <= TimeSpan.Zero || !worker.Join(remaining)) return false;
        }
        return true;
    }

    protected override IEnumerable<Task>? GetScheduledTasks() => tasks.ToArray();
    protected override void QueueTask(Task task) => tasks.Add(task);
    protected override bool TryExecuteTaskInline(Task task, bool taskWasPreviouslyQueued) => false;

    private void Consume()
    {
        foreach (var task in tasks.GetConsumingEnumerable()) TryExecuteTask(task);
    }
}

internal sealed class JavaAtomicBoolean
{
    private int value;

    internal JavaAtomicBoolean(bool value = false) => this.value = value ? 1 : 0;
    internal bool Get() => Volatile.Read(ref value) != 0;
    internal void Set(bool replacement) => Volatile.Write(ref value, replacement ? 1 : 0);
    internal bool CompareAndSet(bool expected, bool replacement) =>
        Interlocked.CompareExchange(ref value, replacement ? 1 : 0, expected ? 1 : 0) ==
        (expected ? 1 : 0);
}

internal sealed class JavaAtomicInteger
{
    private int value;

    internal JavaAtomicInteger(int value = 0) => this.value = value;
    internal int IncrementAndGet() => Interlocked.Increment(ref value);
}

internal sealed class JavaAtomicReference<T> where T : class
{
    private T? value;

    internal JavaAtomicReference(T? value = null) => this.value = value;
    internal T Get() => Volatile.Read(ref value)!;
    internal void Set(T? replacement) => Volatile.Write(ref value, replacement);
    internal T GetAndSet(T? replacement) => Interlocked.Exchange(ref value, replacement)!;
}

internal sealed class JavaSocketFactory
{
    internal static readonly JavaSocketFactory Plain = new(false);
    internal static readonly JavaSocketFactory Default = new(true);
    private readonly bool tls;

    private JavaSocketFactory(bool tls) => this.tls = tls;

    internal System.Net.Sockets.Socket CreateSocket(string host, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(host, port);
            Stream stream = new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
            if (tls)
            {
                var secure = new System.Net.Security.SslStream(stream, leaveInnerStreamOpen: false);
                secure.AuthenticateAsClient(host);
                stream = secure;
            }
            JavaCompat.RegisterSocketStream(socket, stream);
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal System.Net.Sockets.Socket CreateSocket(System.Net.IPAddress address, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            address.AddressFamily,
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(address, port);
            JavaCompat.RegisterSocketStream(
                socket, new System.Net.Sockets.NetworkStream(socket, ownsSocket: false));
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}

internal sealed class JavaServerSocket : IDisposable
{
    private readonly System.Net.Sockets.TcpListener listener;
    private int closed;

    internal JavaServerSocket(int port)
    {
        listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Any, port);
        listener.Start();
    }

    internal System.Net.Sockets.Socket Accept()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref closed) != 0, this);
        return listener.AcceptSocket();
    }

    internal bool IsClosed() => Volatile.Read(ref closed) != 0;

    internal void Close()
    {
        if (Interlocked.Exchange(ref closed, 1) == 0) listener.Stop();
    }

    public void Dispose() => Close();
}

internal sealed class JavaRandom
{
    private readonly Random random = new();
    private readonly object sync = new();

    internal long NextLong()
    {
        Span<byte> bytes = stackalloc byte[sizeof(long)];
        lock (sync) random.NextBytes(bytes);
        return BitConverter.ToInt64(bytes);
    }
}

internal sealed class JavaProcessBuilder
{
    private readonly ProcessStartInfo startInfo = new()
    {
        UseShellExecute = false,
        RedirectStandardInput = true,
        RedirectStandardOutput = true
    };

    internal JavaProcessBuilder(IEnumerable<string> command)
    {
        using var parts = command.GetEnumerator();
        if (!parts.MoveNext()) throw new ArgumentException("Process command must not be empty.", nameof(command));
        startInfo.FileName = parts.Current;
        while (parts.MoveNext()) startInfo.ArgumentList.Add(parts.Current);
    }

    internal JavaProcessBuilder Directory(string directory)
    {
        startInfo.WorkingDirectory = directory;
        return this;
    }

    internal JavaProcessBuilder RedirectError(JavaProcessRedirect redirect)
    {
        startInfo.RedirectStandardError = redirect != JavaProcessRedirect.INHERIT;
        return this;
    }

    internal JavaProcess Start()
    {
        try
        {
            return new JavaProcess(Process.Start(startInfo) ??
                throw new IOException($"Could not start process `{startInfo.FileName}`."));
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            throw new IOException($"Could not start process `{startInfo.FileName}`.", error);
        }
    }
}

internal sealed class JavaProcess : IDisposable
{
    private readonly Process process;
    private readonly Stream inputStream;
    private readonly Stream outputStream;
    private readonly CancellationTokenRegistration cancellationRegistration;
    private int disposeStarted;

    internal JavaProcess(Process process)
    {
        this.process = process;
        inputStream = process.StandardOutput.BaseStream;
        outputStream = process.StandardInput.BaseStream;
        var cancellation = JavaCancellation.CurrentToken;
        if (cancellation.CanBeCanceled)
            cancellationRegistration = cancellation.Register(
                static state => ((JavaProcess)state!).CancelForEvaluation(), this);
    }

    internal bool IsAlive()
    {
        try { return Volatile.Read(ref disposeStarted) == 0 && !process.HasExited; }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException)
        {
            return false;
        }
    }

    internal Stream GetInputStream()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposeStarted) != 0, this);
        return inputStream;
    }

    internal Stream GetOutputStream()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref disposeStarted) != 0, this);
        return outputStream;
    }

    internal bool WaitFor(long timeout, JavaTimeUnit unit)
    {
        try
        {
            return process.WaitForExit(checked((int)Math.Min(timeout, int.MaxValue)));
        }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException)
        {
            return true;
        }
    }

    internal JavaProcess DestroyForcibly()
    {
        try
        {
            if (IsAlive()) process.Kill(entireProcessTree: true);
        }
        catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException or ThreadInterruptedException) { }
        return this;
    }

    private void CancelForEvaluation()
    {
        // CancellationToken callbacks run synchronously on the thread closing
        // the evaluation context. Never let process teardown replace the
        // product's stable timeout/cancellation diagnostic.
        try { Terminate(); }
        catch (Exception error) when (error is not StackOverflowException and not OutOfMemoryException) { }
    }

    // Killing the owned process tree and closing both redirected pipes are
    // separate operations on .NET. Do both so a reader blocked in a pipe read
    // is released even when process-exit notification races disposal.
    internal JavaProcess Terminate()
    {
        DestroyForcibly();
        ClosePipes();
        return this;
    }

    internal void ClosePipes()
    {
        DisposePipe(outputStream);
        DisposePipe(inputStream);
    }

    private static void DisposePipe(Stream stream)
    {
        while (true)
        {
            try
            {
                stream.Dispose();
                return;
            }
            // Thread.Interrupt may race the SafePipeHandle spin wait used by
            // Stream.Dispose. The exception clears the interrupt; retry so the
            // redirected pipe is still deterministically released.
            catch (ThreadInterruptedException) { }
            catch (Exception error) when (error is IOException or ObjectDisposedException) { return; }
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposeStarted, 1) != 0) return;
        try
        {
            try
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
            }
            catch (Exception error) when (error is InvalidOperationException or ObjectDisposedException or ThreadInterruptedException) { }
            ClosePipes();
        }
        finally
        {
            cancellationRegistration.Dispose();
            process.Dispose();
        }
    }
}

internal sealed class JavaProperties
{
    private readonly Dictionary<string, string> values = new(StringComparer.Ordinal);

    internal void Load(Stream stream)
    {
        using var reader = new StreamReader(stream, Encoding.Latin1, false, 1024, leaveOpen: true);
        string? pending = null;
        while (reader.ReadLine() is { } physicalLine)
        {
            var line = pending is null ? physicalLine : pending + physicalLine.TrimStart();
            var trailingSlashes = line.Reverse().TakeWhile(character => character == '\\').Count();
            if ((trailingSlashes & 1) == 1)
            {
                pending = line[..^1];
                continue;
            }
            pending = null;
            var trimmed = line.TrimStart();
            if (trimmed.Length == 0 || trimmed[0] is '#' or '!') continue;
            var separator = -1;
            var escaped = false;
            for (var index = 0; index < trimmed.Length; index++)
            {
                var character = trimmed[index];
                if (!escaped && (character is '=' or ':' || char.IsWhiteSpace(character)))
                {
                    separator = index;
                    break;
                }
                escaped = !escaped && character == '\\';
                if (character != '\\') escaped = false;
            }
            var key = separator < 0 ? trimmed : trimmed[..separator];
            var valueStart = separator < 0 ? trimmed.Length : separator;
            while (valueStart < trimmed.Length && char.IsWhiteSpace(trimmed[valueStart])) valueStart++;
            if (valueStart < trimmed.Length && trimmed[valueStart] is '=' or ':') valueStart++;
            while (valueStart < trimmed.Length && char.IsWhiteSpace(trimmed[valueStart])) valueStart++;
            values[Unescape(key)] = Unescape(trimmed[valueStart..]);
        }
    }

    internal string? GetProperty(string key) => values.TryGetValue(key, out var value) ? value : null;

    private static string Unescape(string value) => Regex.Replace(
        value,
        @"\\(u[0-9A-Fa-f]{4}|.)",
        match => match.Groups[1].Value switch
        {
            "t" => "\t",
            "n" => "\n",
            "r" => "\r",
            "f" => "\f",
            var escaped when escaped.StartsWith('u') =>
                ((char)Convert.ToInt32(escaped[1..], 16)).ToString(),
            var escaped => escaped
        });
}

internal interface IJavaOptional
{
    bool HasValue { get; }
    object? BoxedValue { get; }
}

internal sealed class JavaOptional<T> : IJavaOptional
{
    private readonly T? value;
    private readonly bool present;

    private JavaOptional(T? value, bool present)
    {
        this.value = value;
        this.present = present;
    }

    internal static JavaOptional<T> Empty() => new(default, false);
    internal static JavaOptional<T> Of(T value) => new(JavaCompat.RequireNonNull(value), true);
    internal static JavaOptional<T> OfNullable(T? value) => new(value, value is not null);
    internal bool IsPresent() => present;
    internal bool IsEmpty() => !present;
    internal T Get() => present ? value! : throw new InvalidOperationException("Optional is empty");
    internal T OrElse(T fallback) => present ? value! : fallback;
    internal T OrElseGet(Func<T> supplier) => present ? value! : supplier();
    internal void IfPresent(Action<T> action) { if (present) action(value!); }
    internal void IfPresentOrElse(Action<T> action, Action emptyAction) { if (present) action(value!); else emptyAction(); }
    internal T OrElseThrow() => Get();
    internal T OrElseThrow(Func<Exception> exceptionSupplier) =>
        present ? value! : throw exceptionSupplier();
    internal JavaOptional<R> Map<R>(Func<T, R> mapper) => present ? JavaOptional<R>.OfNullable(mapper(value!)) : JavaOptional<R>.Empty();
    internal R Match<R>(Func<T, R> presentCase, Func<R> emptyCase) =>
        present ? presentCase(value!) : emptyCase();
    bool IJavaOptional.HasValue => present;
    object? IJavaOptional.BoxedValue => value;
    public override bool Equals(object? other) =>
        other is IJavaOptional optional && present == optional.HasValue &&
        (!present || JavaCompat.Equals(value, optional.BoxedValue));
    public override int GetHashCode() => present ? JavaCompat.HashCode(value) : 0;
}

// A Java Map.Entry is a reference object whose value can remain backed by the
// source map. KeyValuePair cannot model setValue(), so translated declarations
// use this reusable compatibility type instead of taking an entry snapshot.
internal sealed class JavaMapEntry<K, V> where K : notnull
{
    private readonly IDictionary<K, V>? source;
    private readonly K key;
    private V value;

    internal JavaMapEntry(IDictionary<K, V> source, K key)
    {
        this.source = source;
        this.key = key;
        value = source.TryGetValue(key, out var current) ? current : default!;
    }

    internal JavaMapEntry(K key, V value)
    {
        this.key = key;
        this.value = value;
    }

    public K Key => key;
    public V Value => source is not null && source.TryGetValue(key, out var current)
        ? current
        : value;

    public V SetValue(V replacement)
    {
        if (source is null)
            throw new NotSupportedException("This Java map entry is immutable.");
        var previous = Value;
        if (source is JavaLinkedHashMap<K, V> linked)
            linked.ReplaceValueWithoutAccess(key, replacement);
        else
            source[key] = replacement;
        value = replacement;
        return previous;
    }

    public override bool Equals(object? other)
    {
        if (other is null) return false;
        var type = other.GetType();
        if (!type.IsGenericType || type.GetGenericTypeDefinition() != typeof(JavaMapEntry<,>))
            return false;
        return JavaCompat.Equals(Key, type.GetProperty(nameof(Key))!.GetValue(other)) &&
               JavaCompat.Equals(Value, type.GetProperty(nameof(Value))!.GetValue(other));
    }

    public override int GetHashCode() =>
        JavaCompat.HashCode(Key) ^ JavaCompat.HashCode(Value);

    public override string ToString() => $"{Key}={Value}";
}

internal interface JavaRemovableIterator
{
    void MarkReturned();
    void Remove();
}

public interface JavaIterator<out T>
{
    bool HasNext();
    T Next();
    void Remove() => throw new NotSupportedException(
        "This Java iterator does not expose mutable removal semantics.");
}

internal sealed class JavaListIterator<T>(IEnumerable<T> values) : JavaIterator<T>
{
    private readonly IEnumerator<T> iterator = values.GetEnumerator();
    private bool prepared;
    private bool hasNext;

    public bool HasNext()
    {
        if (!prepared)
        {
            hasNext = iterator.MoveNext();
            prepared = true;
        }
        return hasNext;
    }

    public T Next()
    {
        if (!HasNext()) throw new InvalidOperationException("Iterator has no next element.");
        prepared = false;
        return iterator.Current;
    }

    public void Remove() => throw new NotSupportedException(
        "This Java iterator does not expose mutable removal semantics.");
}

internal interface JavaReadOnlyAdapter
{
    object MutableSource { get; }
}

internal sealed class JavaReadOnlyList<T> : IReadOnlyList<T>, JavaReadOnlyAdapter
{
    private readonly IList<T> values;

    public JavaReadOnlyList(IList<T> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public T this[int index] => values[index];
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaReadOnlyDictionary<K, V> : IReadOnlyDictionary<K, V>, JavaReadOnlyAdapter
{
    private readonly IDictionary<K, V> values;

    public JavaReadOnlyDictionary(IDictionary<K, V> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public IEnumerable<K> Keys => values.Keys;
    public IEnumerable<V> Values => values.Values;
    public V this[K key] => values[key];
    public bool ContainsKey(K key) => values.ContainsKey(key);
    public bool TryGetValue(K key, out V value) => values.TryGetValue(key, out value!);
    public IEnumerator<KeyValuePair<K, V>> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaReadOnlySet<T> : IReadOnlySet<T>, JavaReadOnlyAdapter
{
    private readonly ISet<T> values;

    public JavaReadOnlySet(ISet<T> values) => this.values = values;

    object JavaReadOnlyAdapter.MutableSource => values;
    public int Count => values.Count;
    public bool Contains(T item) => values.Contains(item);
    public bool IsProperSubsetOf(IEnumerable<T> other) => values.IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<T> other) => values.IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<T> other) => values.IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<T> other) => values.IsSupersetOf(other);
    public bool Overlaps(IEnumerable<T> other) => values.Overlaps(other);
    public bool SetEquals(IEnumerable<T> other) => values.SetEquals(other);
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal sealed class JavaMapEntrySet<K, V> : ISet<JavaMapEntry<K, V>> where K : notnull
{
    private readonly IDictionary<K, V> source;

    internal JavaMapEntrySet(IDictionary<K, V> source) => this.source = source;

    public int Count => source.Count;
    public bool IsReadOnly => false;

    bool ISet<JavaMapEntry<K, V>>.Add(JavaMapEntry<K, V> item) =>
        throw new NotSupportedException("Java Map.entrySet does not support add().");

    void ICollection<JavaMapEntry<K, V>>.Add(JavaMapEntry<K, V> item) =>
        throw new NotSupportedException("Java Map.entrySet does not support add().");

    public void Clear() => source.Clear();

    public bool Contains(JavaMapEntry<K, V> item) =>
        source.TryGetValue(item.Key, out var value) && JavaCompat.Equals(value, item.Value);

    public void CopyTo(JavaMapEntry<K, V>[] array, int arrayIndex)
    {
        foreach (var entry in this) array[arrayIndex++] = entry;
    }

    public bool Remove(JavaMapEntry<K, V> item)
    {
        if (!Contains(item)) return false;
        return source.Remove(item.Key);
    }

    public IEnumerator<JavaMapEntry<K, V>> GetEnumerator() => new Enumerator(source);
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    public void ExceptWith(IEnumerable<JavaMapEntry<K, V>> other)
    {
        var removed = other.ToList();
        foreach (var entry in removed) Remove(entry);
    }

    public void IntersectWith(IEnumerable<JavaMapEntry<K, V>> other)
    {
        var retained = new HashSet<JavaMapEntry<K, V>>(other);
        foreach (var entry in this.Where(entry => !retained.Contains(entry)).ToList()) Remove(entry);
    }

    public bool IsProperSubsetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsProperSubsetOf(other);

    public bool IsProperSupersetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsProperSupersetOf(other);

    public bool IsSubsetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsSubsetOf(other);

    public bool IsSupersetOf(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().IsSupersetOf(other);

    public bool Overlaps(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().Overlaps(other);

    public bool SetEquals(IEnumerable<JavaMapEntry<K, V>> other) =>
        Snapshot().SetEquals(other);

    public void SymmetricExceptWith(IEnumerable<JavaMapEntry<K, V>> other) =>
        throw new NotSupportedException("Java Map.entrySet does not support adding entries.");

    public void UnionWith(IEnumerable<JavaMapEntry<K, V>> other) =>
        throw new NotSupportedException("Java Map.entrySet does not support adding entries.");

    private HashSet<JavaMapEntry<K, V>> Snapshot() => new(this);

    private sealed class Enumerator : IEnumerator<JavaMapEntry<K, V>>, JavaRemovableIterator
    {
        private readonly IDictionary<K, V> source;
        private readonly IList<K> keys;
        private int index = -1;
        private K? preparedKey;
        private K? returnedKey;
        private bool hasPreparedKey;
        private bool canRemove;

        internal Enumerator(IDictionary<K, V> source)
        {
            this.source = source;
            keys = source.Keys.ToList();
        }

        public JavaMapEntry<K, V> Current { get; private set; } = default!;
        object IEnumerator.Current => Current;

        public bool MoveNext()
        {
            while (++index < keys.Count)
            {
                var key = keys[index];
                if (!source.ContainsKey(key)) continue;
                preparedKey = key;
                hasPreparedKey = true;
                Current = new JavaMapEntry<K, V>(source, key);
                return true;
            }
            preparedKey = default;
            hasPreparedKey = false;
            Current = default!;
            return false;
        }

        public void MarkReturned()
        {
            if (!hasPreparedKey)
                throw new InvalidOperationException("Iterator has no current map entry.");
            returnedKey = preparedKey;
            canRemove = true;
        }

        public void Remove()
        {
            if (!canRemove)
                throw new InvalidOperationException("Iterator.remove() requires one preceding next().");
            source.Remove(returnedKey!);
            canRemove = false;
        }

        public void Reset() => throw new NotSupportedException();
        public void Dispose() { }
    }
}

internal sealed class JavaMapKeySet<K, V> : ISet<K> where K : notnull
{
    private readonly IDictionary<K, V> source;

    private sealed class KeyComparer : IEqualityComparer<K>
    {
        public bool Equals(K? left, K? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(K value) => JavaCompat.HashCode(value);
    }

    internal JavaMapKeySet(IDictionary<K, V> source) => this.source = source;

    private HashSet<K> Snapshot() => new(source.Keys, new KeyComparer());
    public int Count => source.Count;
    public bool IsReadOnly => false;
    bool ISet<K>.Add(K item) =>
        throw new NotSupportedException("Java Map.keySet does not support add().");
    void ICollection<K>.Add(K item) =>
        throw new NotSupportedException("Java Map.keySet does not support add().");
    public void Clear() => source.Clear();
    public bool Contains(K item) => source.ContainsKey(item);
    public void CopyTo(K[] array, int arrayIndex)
    {
        foreach (var item in source.Keys) array[arrayIndex++] = item;
    }
    public bool Remove(K item) => source.Remove(item);
    public IEnumerator<K> GetEnumerator() => source.Keys.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void ExceptWith(IEnumerable<K> other)
    {
        foreach (var item in other.ToList()) source.Remove(item);
    }
    public void IntersectWith(IEnumerable<K> other)
    {
        var retained = new HashSet<K>(other, new KeyComparer());
        foreach (var item in source.Keys.Where(item => !retained.Contains(item)).ToList())
            source.Remove(item);
    }
    public bool IsProperSubsetOf(IEnumerable<K> other) => Snapshot().IsProperSubsetOf(other);
    public bool IsProperSupersetOf(IEnumerable<K> other) => Snapshot().IsProperSupersetOf(other);
    public bool IsSubsetOf(IEnumerable<K> other) => Snapshot().IsSubsetOf(other);
    public bool IsSupersetOf(IEnumerable<K> other) => Snapshot().IsSupersetOf(other);
    public bool Overlaps(IEnumerable<K> other) => Snapshot().Overlaps(other);
    public bool SetEquals(IEnumerable<K> other) => Snapshot().SetEquals(other);
    public void SymmetricExceptWith(IEnumerable<K> other) =>
        throw new NotSupportedException("Java Map.keySet does not support adding keys.");
    public void UnionWith(IEnumerable<K> other) =>
        throw new NotSupportedException("Java Map.keySet does not support adding keys.");
    public override string ToString() =>
        "[" + string.Join(", ", source.Keys.Select(item => JavaCompat.StringValueOf(item))) + "]";
}

internal static class JavaCompat
{
    private static readonly bool AssertionsEnabled =
        string.Equals(Environment.GetEnvironmentVariable("VIBEFORMER_JAVA_ASSERTIONS"),
            "true", StringComparison.OrdinalIgnoreCase);
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Net.Sockets.Socket, Stream> SocketStreams = new();
    private sealed class ReadOnlyAdapterCache
    {
        internal Dictionary<Type, object> Values { get; } = new();
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<object, ReadOnlyAdapterCache>
        ReadOnlyAdapters = new();

    internal static void Assert(Func<bool> condition, Func<object?>? message = null)
    {
        if (AssertionsEnabled && !condition())
            throw new JavaAssertionError(message?.Invoke()?.ToString());
    }

    internal static JavaIterator<T> Iterator<T>(IEnumerable<T> values) =>
        new JavaListIterator<T>(values);

    private static object ReadOnlyAdapter(Type targetType, object source, Func<object> create)
    {
        var cache = ReadOnlyAdapters.GetOrCreateValue(source);
        lock (cache.Values)
        {
            if (cache.Values.TryGetValue(targetType, out var existing)) return existing;
            var adapter = create();
            cache.Values.Add(targetType, adapter);
            return adapter;
        }
    }

    private sealed class JavaRegex(
        string originalPattern,
        string translatedPattern,
        RegexOptions options,
        int flags,
        string[] groupNames,
        IReadOnlyDictionary<string, string> namedGroups)
        : Regex(translatedPattern, options)
    {
        internal int Flags { get; } = flags;
        internal string[] GroupNames { get; } = groupNames;
        internal IReadOnlyDictionary<string, string> NamedGroups { get; } = namedGroups;
        public override string ToString() => originalPattern;
    }

    private sealed class JavaUriText(string value)
    {
        internal string Value { get; } = value;
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, object>
        SingleSlashFileUris = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, JavaUriText>
        OriginalUriTexts = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Regex, JavaUriText>
        OriginalRegexPatterns = new();
    internal static readonly TextWriter @out = Console.Out;
    internal static readonly TextWriter err = Console.Error;
    private static readonly Dictionary<string, string> SystemProperties = new(StringComparer.Ordinal)
    {
        ["os.name"] = OperatingSystem.IsMacOS() ? "Mac OS X"
            : OperatingSystem.IsWindows() ? "Windows"
            : OperatingSystem.IsLinux() ? "Linux"
            : Environment.OSVersion.Platform.ToString(),
        ["os.version"] = Environment.OSVersion.VersionString,
        ["java.version"] = Environment.Version.ToString(),
        ["user.home"] = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ["user.dir"] = Environment.CurrentDirectory,
        ["java.io.tmpdir"] = Path.GetTempPath(),
        ["file.separator"] = Path.DirectorySeparatorChar.ToString(),
        ["path.separator"] = Path.PathSeparator.ToString(),
        ["line.separator"] = Environment.NewLine
    };
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<IEnumerator, IteratorState>
        IteratorStates = new();

    private sealed class IteratorState
    {
        internal bool Prepared;
        internal bool Exhausted;
    }

    internal static T RequireNonNull<T>(T? value, string? message = null) =>
        value is null ? throw new NullReferenceException(message) : value;
    internal static T RequireNonNullElseGet<T>(T? value, Func<T> supplier) =>
        value is null ? RequireNonNull(supplier()) : value;
    internal static string? Getenv(string name) => Environment.GetEnvironmentVariable(name);
    internal static string? ExceptionMessage(Exception? exception)
    {
        if (exception is null) return null;
        var method = exception.GetType().GetMethod("GetMessage", Type.EmptyTypes);
        return method is null ? exception.Message : method.Invoke(exception, null) as string;
    }

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

    internal static bool IsRegularFile(FileSystemInfo attributes) => attributes is FileInfo;
    internal static bool IsRegularFile(string path) => File.Exists(path);

    internal static string Concat(object? left, object? right) =>
        JavaString(left) + JavaString(right);

    private static string JavaString(object? value) => StringValueOf(value);

    internal static bool IsDigit(int codePoint) => Rune.GetUnicodeCategory(new Rune(codePoint)) == UnicodeCategory.DecimalDigitNumber;

    internal static bool IsLetterOrDigit(int codePoint) =>
        Rune.IsLetter(new Rune(codePoint)) || IsDigit(codePoint);

    internal static bool IsUnicodeIdentifierStart(int codePoint)
    {
        var category = Rune.GetUnicodeCategory(new Rune(codePoint));
        return Rune.IsLetter(new Rune(codePoint)) ||
               category is UnicodeCategory.LetterNumber or UnicodeCategory.CurrencySymbol or UnicodeCategory.ConnectorPunctuation;
    }

    internal static bool IsUnicodeIdentifierPart(int codePoint)
    {
        var category = Rune.GetUnicodeCategory(new Rune(codePoint));
        return IsUnicodeIdentifierStart(codePoint) ||
               category is UnicodeCategory.DecimalDigitNumber or UnicodeCategory.NonSpacingMark or
                   UnicodeCategory.SpacingCombiningMark or UnicodeCategory.Format;
    }

    internal static string CodePointToString(int codePoint)
    {
        // Character.toString(int) preserves every BMP char value, including an
        // unpaired surrogate. ConvertFromUtf32 is stricter and rejects those.
        if (codePoint is >= char.MinValue and <= char.MaxValue) return ((char)codePoint).ToString();
        return char.ConvertFromUtf32(codePoint);
    }

    internal static int CodePointAt(string value, int index) => char.ConvertToUtf32(value, index);
    internal static int CharacterType(int codePoint) => Rune.GetUnicodeCategory(new Rune(codePoint)) switch
    {
        UnicodeCategory.UppercaseLetter => 1,
        UnicodeCategory.LowercaseLetter => 2,
        UnicodeCategory.TitlecaseLetter => 3,
        UnicodeCategory.ModifierLetter => 4,
        UnicodeCategory.OtherLetter => 5,
        UnicodeCategory.NonSpacingMark => 6,
        UnicodeCategory.EnclosingMark => 7,
        UnicodeCategory.SpacingCombiningMark => 8,
        UnicodeCategory.DecimalDigitNumber => 9,
        UnicodeCategory.LetterNumber => 10,
        UnicodeCategory.OtherNumber => 11,
        UnicodeCategory.SpaceSeparator => 12,
        UnicodeCategory.LineSeparator => 13,
        UnicodeCategory.ParagraphSeparator => 14,
        UnicodeCategory.Control => 15,
        UnicodeCategory.Format => 16,
        UnicodeCategory.PrivateUse => 18,
        UnicodeCategory.Surrogate => 19,
        UnicodeCategory.DashPunctuation => 20,
        UnicodeCategory.OpenPunctuation => 21,
        UnicodeCategory.ClosePunctuation => 22,
        UnicodeCategory.ConnectorPunctuation => 23,
        UnicodeCategory.OtherPunctuation => 24,
        UnicodeCategory.MathSymbol => 25,
        UnicodeCategory.CurrencySymbol => 26,
        UnicodeCategory.ModifierSymbol => 27,
        UnicodeCategory.OtherSymbol => 28,
        UnicodeCategory.InitialQuotePunctuation => 29,
        UnicodeCategory.FinalQuotePunctuation => 30,
        _ => 0
    };
    internal static int ToUpperCase(int codePoint) => Rune.ToUpperInvariant(new Rune(codePoint)).Value;
    internal static StringBuilder AppendCodePoint(StringBuilder builder, int codePoint) =>
        builder.Append(CodePointToString(codePoint));

    internal static int CodePointCount(string value, int beginIndex, int endIndex)
    {
        var count = 0;
        for (var index = beginIndex; index < endIndex; count++)
            index += char.IsSurrogatePair(value, index) ? 2 : 1;
        return count;
    }

    internal static bool EqualsIgnoreCase(string value, string? other) =>
        string.Equals(value, other, StringComparison.OrdinalIgnoreCase);
    internal static bool StringStartsWith(string value, string prefix) =>
        value.StartsWith(prefix, StringComparison.Ordinal);
    internal static bool StringEndsWith(string value, string suffix) =>
        value.EndsWith(suffix, StringComparison.Ordinal);
    internal static string StringSubstring(string value, int beginIndex, int endIndex) =>
        value.Substring(beginIndex, endIndex - beginIndex);
    internal static int StringIndexOf(string value, int character) =>
        value.IndexOf((char)character);
    internal static int StringIndexOf(string value, int character, int fromIndex) =>
        value.IndexOf((char)character, fromIndex);
    internal static bool StringContains(string value, string part) =>
        value.Contains(part, StringComparison.Ordinal);
    internal static string StringTrim(string value)
    {
        var start = 0;
        while (start < value.Length && value[start] <= '\u0020') start++;
        var end = value.Length;
        while (end > start && value[end - 1] <= '\u0020') end--;
        return value.Substring(start, end - start);
    }

    internal static int StringHashCode(string value)
    {
        var result = 0;
        foreach (var character in value) result = unchecked(31 * result + character);
        return result;
    }

    internal static string StringValueOf(object? value) => value switch
    {
        null => "null",
        bool boolean => boolean ? "true" : "false",
        double number => JavaFloatingString(number),
        float number => JavaFloatingString(number),
        Uri uri => UriToString(uri),
        Regex regex => JavaCompat.RegexPattern(regex),
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString() ?? "null"
    };
    internal static string StringValueOf(char value) => value.ToString();
    internal static string StringValueOf(char[] value) => new(value);
    internal static string StringValueOf(bool value) => value ? "true" : "false";
    internal static string StringValueOf(int value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(long value) => value.ToString(CultureInfo.InvariantCulture);
    internal static string StringValueOf(float value) => JavaFloatingString(value);
    internal static string StringValueOf(double value) => JavaFloatingString(value);
    internal static StringBuilder AppendValue(StringBuilder builder, object? value)
    {
        builder.Append(StringValueOf(value));
        return builder;
    }
    internal static IEnumerable<string> StringLines(string value) => value.Replace("\r\n", "\n").Split('\n');
    internal static int ReaderRead(TextReader reader, char[] buffer, int index, int count)
    {
        var read = reader.Read(buffer, index, count);
        return read == 0 && count != 0 ? -1 : read;
    }
    internal static string[] StringSplit(string value, string pattern, int limit)
        => RegexSplit(CompileRegex(pattern), value, limit);
    internal static bool StringMatches(string value, string pattern) => RegexMatcher(CompileRegex(pattern), value).Matches();
    internal static string StringReplaceAll(string value, string pattern, string replacement) =>
        RegexMatcher(CompileRegex(pattern), value).ReplaceAll(replacement);
    internal static sbyte[] StringGetBytes(string value, Encoding encoding)
    {
        if (encoding.CodePage == Encoding.UTF8.CodePage)
        {
            encoding = (Encoding)new UTF8Encoding(false, false).Clone();
            encoding.EncoderFallback = new EncoderReplacementFallback("?");
        }
        return encoding.GetBytes(value).Select(item => unchecked((sbyte)item)).ToArray();
    }
    internal static sbyte[] StringGetBytes(string value, string encoding)
    {
        if (encoding.Equals("UTF-16", StringComparison.OrdinalIgnoreCase))
        {
            var payload = Encoding.BigEndianUnicode.GetBytes(value);
            return new byte[] { 0xfe, 0xff }.Concat(payload)
                .Select(item => unchecked((sbyte)item)).ToArray();
        }
        if (encoding.Equals("UTF-16BE", StringComparison.OrdinalIgnoreCase))
            return Encoding.BigEndianUnicode.GetBytes(value)
                .Select(item => unchecked((sbyte)item)).ToArray();
        if (encoding.Equals("UTF-16LE", StringComparison.OrdinalIgnoreCase))
            return Encoding.Unicode.GetBytes(value)
                .Select(item => unchecked((sbyte)item)).ToArray();
        if (encoding.Equals("ISO-8859-1", StringComparison.OrdinalIgnoreCase))
        {
            var bytes = new List<sbyte>();
            foreach (var rune in value.EnumerateRunes())
                bytes.Add(unchecked((sbyte)(rune.Value <= 0xff ? rune.Value : '?')));
            return bytes.ToArray();
        }
        return StringGetBytes(value, Encoding.GetEncoding(encoding));
    }

    private static string JavaFloatingString(double value)
    {
        if (double.IsNaN(value)) return "NaN";
        if (double.IsPositiveInfinity(value)) return "Infinity";
        if (double.IsNegativeInfinity(value)) return "-Infinity";
        if (value == 0) return BitConverter.DoubleToInt64Bits(value) < 0 ? "-0.0" : "0.0";
        // Double.ToString("R") uses "5E-324" for the minimum subnormal,
        // whereas Java's canonical Double.toString representation is
        // "4.9E-324". Keep the exact Java spelling at this boundary.
        if (BitConverter.DoubleToInt64Bits(value) == 1) return "4.9E-324";
        if (BitConverter.DoubleToInt64Bits(value) == unchecked((long)0x8000000000000001UL))
            return "-4.9E-324";
        return JavaFiniteFloatingString(value.ToString("R", CultureInfo.InvariantCulture), Math.Abs(value));
    }

    private static string JavaFloatingString(float value)
    {
        if (float.IsNaN(value)) return "NaN";
        if (float.IsPositiveInfinity(value)) return "Infinity";
        if (float.IsNegativeInfinity(value)) return "-Infinity";
        if (value == 0) return BitConverter.SingleToInt32Bits(value) < 0 ? "-0.0" : "0.0";
        return JavaFiniteFloatingString(value.ToString("R", CultureInfo.InvariantCulture), Math.Abs((double)value));
    }

    private static string JavaFiniteFloatingString(string text, double magnitude)
    {
        var negative = text[0] == '-';
        if (negative) text = text[1..];
        var exponentIndex = text.IndexOfAny(new[] { 'E', 'e' });
        var exponent = 0;
        if (exponentIndex >= 0)
        {
            exponent = int.Parse(text[(exponentIndex + 1)..], CultureInfo.InvariantCulture);
            text = text[..exponentIndex];
        }
        var decimalIndex = text.IndexOf('.');
        var decimalPosition = (decimalIndex < 0 ? text.Length : decimalIndex) + exponent;
        var digits = text.Replace(".", "", StringComparison.Ordinal);
        while (digits.Length > 1 && digits[0] == '0')
        {
            digits = digits[1..];
            decimalPosition--;
        }
        while (digits.Length > 1 && digits[^1] == '0') digits = digits[..^1];

        string result;
        if (magnitude >= 1e7 || magnitude < 1e-3)
        {
            result = digits.Length == 1 ? digits + ".0" : digits[0] + "." + digits[1..];
            result += "E" + (decimalPosition - 1).ToString(CultureInfo.InvariantCulture);
        }
        else if (decimalPosition <= 0)
        {
            result = "0." + new string('0', -decimalPosition) + digits;
        }
        else if (decimalPosition >= digits.Length)
        {
            result = digits + new string('0', decimalPosition - digits.Length) + ".0";
        }
        else
        {
            result = digits.Insert(decimalPosition, ".");
        }
        return negative ? "-" + result : result;
    }

    // Port of java.lang.FdLibm.Pow from OpenJDK 21. StrictMath.pow is defined
    // in terms of this fdlibm implementation, and System.Math.Pow can differ
    // by one ulp for ordinary Pkl expressions.
    internal static double StrictPow(double x, double y)
    {
        double z, r, s, t, u, v, w;
        int i, j, k, n;

        if (y == 0.0) return 1.0;
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;

        var yAbs = Math.Abs(y);
        var xAbs = Math.Abs(x);
        if (y == 2.0) return x * x;
        if (y == 0.5)
        {
            if (x >= -double.MaxValue) return Math.Sqrt(x + 0.0);
        }
        else if (yAbs == 1.0)
        {
            return y == 1.0 ? x : 1.0 / x;
        }
        else if (double.IsPositiveInfinity(yAbs))
        {
            if (xAbs == 1.0) return y - y;
            if (xAbs > 1.0) return y >= 0 ? y : 0.0;
            return y < 0 ? -y : 0.0;
        }

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var yIsInt = 0;
        if (hx < 0)
        {
            if (yAbs >= 9007199254740992.0)
            {
                yIsInt = 2;
            }
            else if (yAbs >= 1.0)
            {
                var yAsLong = (long)yAbs;
                if ((double)yAsLong == yAbs) yIsInt = 2 - (int)(yAsLong & 1L);
            }
        }

        if (xAbs == 0.0 || double.IsPositiveInfinity(xAbs) || xAbs == 1.0)
        {
            z = xAbs;
            if (y < 0.0) z = 1.0 / z;
            if (hx < 0)
            {
                if (((ix - 0x3ff00000) | yIsInt) == 0) z = (z - z) / (z - z);
                else if (yIsInt == 1) z = -z;
            }
            return z;
        }

        n = (hx >> 31) + 1;
        if ((n | yIsInt) == 0) return (x - x) / (x - x);
        s = (n | (yIsInt - 1)) == 0 ? -1.0 : 1.0;

        double pH, pL, t1, t2;
        if (yAbs > 2147483903.9999998)
        {
            const double invLn2 = 1.44269504088896338700;
            const double invLn2H = 1.44269502162933349609;
            const double invLn2L = 1.92596299112661746887e-08;
            if (xAbs < 0.9999995231628418) return y < 0.0 ? s * double.PositiveInfinity : s * 0.0;
            if (xAbs > 1.0000009536743162) return y > 0.0 ? s * double.PositiveInfinity : s * 0.0;
            t = xAbs - 1.0;
            w = t * t * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
            u = invLn2H * t;
            v = t * invLn2L - w * invLn2;
            t1 = ClearLowWord(u + v);
            t2 = v - (t1 - u);
        }
        else
        {
            const double cp = 9.61796693925975554329e-01;
            const double cpH = 9.61796700954437255859e-01;
            const double cpL = -7.02846165095275826516e-09;
            ReadOnlySpan<double> bp = [1.0, 1.5];
            ReadOnlySpan<double> dpH = [0.0, 5.84962487220764160156e-01];
            ReadOnlySpan<double> dpL = [0.0, 1.35003920212974897128e-08];
            const double l1 = 5.99999999999994648725e-01;
            const double l2 = 4.28571428578550184252e-01;
            const double l3 = 3.33333329818377432918e-01;
            const double l4 = 2.72728123808534006489e-01;
            const double l5 = 2.30660745775561754067e-01;
            const double l6 = 2.06975017800338417784e-01;

            n = 0;
            if (ix < 0x00100000)
            {
                xAbs *= 9007199254740992.0;
                n -= 53;
                ix = HighWord(xAbs);
            }
            n += (ix >> 20) - 0x3ff;
            j = ix & 0x000fffff;
            ix = j | 0x3ff00000;
            if (j <= 0x3988E) k = 0;
            else if (j < 0xBB67A) k = 1;
            else
            {
                k = 0;
                n++;
                ix -= 0x00100000;
            }
            xAbs = WithHighWord(xAbs, ix);
            u = xAbs - bp[k];
            v = 1.0 / (xAbs + bp[k]);
            var ss = u * v;
            var sH = ClearLowWord(ss);
            var tH = WithHighWord(0.0, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
            var tL = xAbs - (tH - bp[k]);
            var sL = v * ((u - sH * tH) - sH * tL);
            var s2 = ss * ss;
            r = s2 * s2 * (l1 + s2 * (l2 + s2 * (l3 + s2 * (l4 + s2 * (l5 + s2 * l6)))));
            r += sL * (sH + ss);
            s2 = sH * sH;
            tH = ClearLowWord(3.0 + s2 + r);
            tL = r - ((tH - 3.0) - s2);
            u = sH * tH;
            v = sL * tH + tL * ss;
            pH = ClearLowWord(u + v);
            pL = v - (pH - u);
            var zH = cpH * pH;
            var zL = cpL * pH + pL * cp + dpL[k];
            t = n;
            t1 = ClearLowWord(((zH + zL) + dpH[k]) + t);
            t2 = zL - (((t1 - t) - dpH[k]) - zH);
        }

        var y1 = ClearLowWord(y);
        pL = (y - y1) * t1 + y * t2;
        pH = y1 * t1;
        z = pL + pH;
        j = HighWord(z);
        i = LowWord(z);
        if (j >= 0x40900000)
        {
            if (((j - 0x40900000) | i) != 0) return s * double.PositiveInfinity;
            const double ovt = 8.0085662595372944372e-17;
            if (pL + ovt > z - pH) return s * double.PositiveInfinity;
        }
        else if ((j & 0x7fffffff) >= 0x4090cc00)
        {
            if (((j - unchecked((int)0xc090cc00)) | i) != 0) return s * 0.0;
            if (pL <= z - pH) return s * 0.0;
        }

        const double p1 = 1.66666666666666019037e-01;
        const double p2 = -2.77777777770155933842e-03;
        const double p3 = 6.61375632143793436117e-05;
        const double p4 = -1.65339022054652515390e-06;
        const double p5 = 4.13813679705723846039e-08;
        const double lg2 = 6.93147180559945286227e-01;
        const double lg2H = 6.93147182464599609375e-01;
        const double lg2L = -1.90465429995776804525e-09;
        i = j & 0x7fffffff;
        k = (i >> 20) - 0x3ff;
        n = 0;
        if (i > 0x3fe00000)
        {
            n = j + (0x00100000 >> (k + 1));
            k = ((n & 0x7fffffff) >> 20) - 0x3ff;
            t = WithHighWord(0.0, n & ~(0x000fffff >> k));
            n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
            if (j < 0) n = -n;
            pH -= t;
        }
        t = ClearLowWord(pL + pH);
        u = t * lg2H;
        v = (pL - (t - pH)) * lg2 + t * lg2L;
        z = u + v;
        w = v - (z - u);
        t = z * z;
        t1 = z - t * (p1 + t * (p2 + t * (p3 + t * (p4 + t * p5))));
        r = z * t1 / (t1 - 2.0) - (w + z * w);
        z = 1.0 - (r - z);
        j = HighWord(z) + (n << 20);
        z = (j >> 20) <= 0 ? Math.ScaleB(z, n) : WithHighWord(z, j);
        return s * z;
    }

    // Ports of the corresponding OpenJDK fdlibm routines. StrictMath is
    // specified in terms of these algorithms; platform libm functions can
    // differ by one ulp, which is observable in rendered Pkl values.
    internal static double StrictLog(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ln2Hi = 6.93147180369123816490e-01;
        const double ln2Lo = 1.90821492927058770002e-10;
        const double lg1 = 6.666666666666735130e-01;
        const double lg2 = 3.999999999940941908e-01;
        const double lg3 = 2.857142874366239149e-01;
        const double lg4 = 2.222219843214978396e-01;
        const double lg5 = 1.818357216161805012e-01;
        const double lg6 = 1.531383769920937332e-01;
        const double lg7 = 1.479819860511658591e-01;

        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        hx &= 0x000fffff;
        var i = (hx + 0x95f64) & 0x100000;
        x = WithHighWord(x, hx | (i ^ 0x3ff00000));
        k += i >> 20;
        var f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3)
        {
            if (f == 0.0)
                return k == 0 ? 0.0 : k * ln2Hi + k * ln2Lo;
            var smallR = f * f * (0.5 - 0.33333333333333333 * f);
            return k == 0
                ? f - smallR
                : k * ln2Hi - ((smallR - k * ln2Lo) - f);
        }
        var s = f / (2.0 + f);
        var dk = (double)k;
        var z = s * s;
        i = hx - 0x6147a;
        var w = z * z;
        var j = 0x6b851 - hx;
        var t1 = w * (lg2 + w * (lg4 + w * lg6));
        var t2 = z * (lg1 + w * (lg3 + w * (lg5 + w * lg7)));
        i |= j;
        var r = t2 + t1;
        if (i > 0)
        {
            var hfsq = 0.5 * f * f;
            return k == 0
                ? f - (hfsq - s * (hfsq + r))
                : dk * ln2Hi - ((hfsq - (s * (hfsq + r) + dk * ln2Lo)) - f);
        }
        return k == 0
            ? f - s * (f - r)
            : dk * ln2Hi - ((s * (f - r) - dk * ln2Lo) - f);
    }

    internal static double StrictLog10(double x)
    {
        const double two54 = 1.80143985094819840000e+16;
        const double ivln10 = 4.34294481903251816668e-01;
        const double log10_2hi = 3.01029995663611771306e-01;
        const double log10_2lo = 3.69423907715893078616e-13;
        var hx = HighWord(x);
        var lx = LowWord(x);
        var k = 0;
        if (hx < 0x00100000)
        {
            if (((hx & 0x7fffffff) | lx) == 0) return double.NegativeInfinity;
            if (hx < 0) return double.NaN;
            k -= 54;
            x *= two54;
            hx = HighWord(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >> 20) - 1023;
        var i = (int)((uint)k >> 31);
        hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
        var y = (double)(k + i);
        x = WithHighWord(x, hx);
        var z = y * log10_2lo + ivln10 * StrictLog(x);
        return z + y * log10_2hi;
    }

    private static int StrictRemPio2(double x, Span<double> y)
    {
        const double invpio2 = 6.36619772367581382433e-01;
        const double pio2_1 = 1.57079632673412561417e+00;
        const double pio2_1t = 6.07710050650619224932e-11;
        const double pio2_2 = 6.07710050630396597660e-11;
        const double pio2_2t = 2.02226624879595063154e-21;
        const double pio2_3 = 2.02226624871116645580e-21;
        const double pio2_3t = 8.47842766036889956997e-32;
        ReadOnlySpan<int> npio2Hw =
        [
            0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
            0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
            0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
            0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
            0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
            0x404858EB, 0x404921FB
        ];

        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb)
        {
            y[0] = x;
            y[1] = 0.0;
            return 0;
        }
        if (ix < 0x4002d97c)
        {
            if (hx > 0)
            {
                var z = x - pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z - pio2_1t;
                    y[1] = (z - y[0]) - pio2_1t;
                }
                else
                {
                    z -= pio2_2;
                    y[0] = z - pio2_2t;
                    y[1] = (z - y[0]) - pio2_2t;
                }
                return 1;
            }
            else
            {
                var z = x + pio2_1;
                if (ix != 0x3ff921fb)
                {
                    y[0] = z + pio2_1t;
                    y[1] = (z - y[0]) + pio2_1t;
                }
                else
                {
                    z += pio2_2;
                    y[0] = z + pio2_2t;
                    y[1] = (z - y[0]) + pio2_2t;
                }
                return -1;
            }
        }
        var t = Math.Abs(x);
        var n = (int)(t * invpio2 + 0.5);
        var fn = (double)n;
        var r = t - fn * pio2_1;
        var w = fn * pio2_1t;
        if (n < 32 && ix != npio2Hw[n - 1])
        {
            y[0] = r - w;
        }
        else
        {
            var j = ix >> 20;
            y[0] = r - w;
            var i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
            if (i > 16)
            {
                t = r;
                w = fn * pio2_2;
                r = t - w;
                w = fn * pio2_2t - ((t - r) - w);
                y[0] = r - w;
                i = j - ((HighWord(y[0]) >> 20) & 0x7ff);
                if (i > 49)
                {
                    t = r;
                    w = fn * pio2_3;
                    r = t - w;
                    w = fn * pio2_3t - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx >= 0) return n;
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }

    private static double StrictKernelSin(double x, double y, int iy)
    {
        const double s1 = -1.66666666666666324348e-01;
        const double s2 = 8.33333333332248946124e-03;
        const double s3 = -1.98412698298579493134e-04;
        const double s4 = 2.75573137070700676789e-06;
        const double s5 = -2.50507602534068634195e-08;
        const double s6 = 1.58969099521155010221e-10;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return x;
        var z = x * x;
        var v = z * x;
        var r = s2 + z * (s3 + z * (s4 + z * (s5 + z * s6)));
        return iy == 0
            ? x + v * (s1 + z * r)
            : x - ((z * (0.5 * y - v * r) - y) - v * s1);
    }

    private static double StrictKernelCos(double x, double y)
    {
        const double c1 = 4.16666666666666019037e-02;
        const double c2 = -1.38888888888741095749e-03;
        const double c3 = 2.48015872894767294178e-05;
        const double c4 = -2.75573143513906633035e-07;
        const double c5 = 2.08757232129817482790e-09;
        const double c6 = -1.13596475577881948265e-11;
        var ix = HighWord(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int)x == 0) return 1.0;
        var z = x * x;
        var r = z * (c1 + z * (c2 + z * (c3 + z * (c4 + z * (c5 + z * c6)))));
        if (ix < 0x3fd33333) return 1.0 - (0.5 * z - (z * r - x * y));
        var qx = ix > 0x3fe90000
            ? 0.28125
            : BitConverter.Int64BitsToDouble((long)(ix - 0x00200000) << 32);
        var hz = 0.5 * z - qx;
        var a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    internal static double StrictSin(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelSin(x, 0.0, 0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Sin(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelSin(y[0], y[1], 1),
            1 => StrictKernelCos(y[0], y[1]),
            2 => -StrictKernelSin(y[0], y[1], 1),
            _ => -StrictKernelCos(y[0], y[1])
        };
    }

    internal static double StrictCos(double x)
    {
        var ix = HighWord(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return StrictKernelCos(x, 0.0);
        if (ix >= 0x7ff00000) return x - x;
        if (ix > 0x413921fb) return Math.Cos(x);
        Span<double> y = stackalloc double[2];
        var n = StrictRemPio2(x, y);
        return (n & 3) switch
        {
            0 => StrictKernelCos(y[0], y[1]),
            1 => -StrictKernelSin(y[0], y[1], 1),
            2 => -StrictKernelCos(y[0], y[1]),
            _ => StrictKernelSin(y[0], y[1], 1)
        };
    }

    internal static double StrictAsin(double x)
    {
        const double pio2Hi = 1.57079632679489655800e+00;
        const double pio2Lo = 6.12323399573676603587e-17;
        const double pio4Hi = 7.85398163397448278999e-01;
        const double pS0 = 1.66666666666666657415e-01;
        const double pS1 = -3.25565818622400915405e-01;
        const double pS2 = 2.01212532134862925881e-01;
        const double pS3 = -4.00555345006794114027e-02;
        const double pS4 = 7.91534994289814532176e-04;
        const double pS5 = 3.47933107596021167570e-05;
        const double qS1 = -2.40339491173441421878e+00;
        const double qS2 = 2.02094576023350569471e+00;
        const double qS3 = -6.88283971605453293030e-01;
        const double qS4 = 7.70381505559019352791e-02;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000)
        {
            if (((ix - 0x3ff00000) | LowWord(x)) == 0) return x * pio2Hi + x * pio2Lo;
            return double.NaN;
        }
        double t = 0.0;
        if (ix < 0x3fe00000)
        {
            if (ix < 0x3e400000) return x;
            t = x * x;
            var p = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
            var q = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
            return x + x * (p / q);
        }
        var w = 1.0 - Math.Abs(x);
        t = w * 0.5;
        var pn = t * (pS0 + t * (pS1 + t * (pS2 + t * (pS3 + t * (pS4 + t * pS5)))));
        var qn = 1.0 + t * (qS1 + t * (qS2 + t * (qS3 + t * qS4)));
        var s = Math.Sqrt(t);
        if (ix >= 0x3fef3333)
        {
            w = pn / qn;
            t = pio2Hi - (2.0 * (s + s * w) - pio2Lo);
        }
        else
        {
            w = ClearLowWord(s);
            var c = (t - w * w) / (s + w);
            var r = pn / qn;
            var p = 2.0 * s * r - (pio2Lo - 2.0 * c);
            var q = pio4Hi - 2.0 * w;
            t = pio4Hi - (p - q);
        }
        return hx > 0 ? t : -t;
    }

    internal static double StrictAtan(double x)
    {
        ReadOnlySpan<double> atanHi =
        [4.63647609000806093515e-01, 7.85398163397448278999e-01,
         9.82793723247329054082e-01, 1.57079632679489655800e+00];
        ReadOnlySpan<double> atanLo =
        [2.26987774529616870924e-17, 3.06161699786838301793e-17,
         1.39033110312319984516e-17, 6.12323399573676603587e-17];
        ReadOnlySpan<double> aT =
        [
            3.33333333333329318027e-01, -1.99999999998764832476e-01,
            1.42857142725034663711e-01, -1.11111104054623557880e-01,
            9.09088713343650656196e-02, -7.69187620504482999495e-02,
            6.66107313738753120669e-02, -5.83357013379057348645e-02,
            4.97687799461593236017e-02, -3.65315727442169155270e-02,
            1.62858201153657823623e-02
        ];
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        int id;
        if (ix >= 0x44100000)
        {
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && LowWord(x) != 0)) return x + x;
            return hx > 0 ? atanHi[3] + atanLo[3] : -atanHi[3] - atanLo[3];
        }
        if (ix < 0x3fdc0000)
        {
            if (ix < 0x3e200000) return x;
            id = -1;
        }
        else
        {
            x = Math.Abs(x);
            if (ix < 0x3ff30000)
            {
                if (ix < 0x3fe60000)
                {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                }
                else
                {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            }
            else if (ix < 0x40038000)
            {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            }
            else
            {
                id = 3;
                x = -1.0 / x;
            }
        }
        var z = x * x;
        var w = z * z;
        var s1 = z * (aT[0] + w * (aT[2] + w * (aT[4] + w * (aT[6] + w * (aT[8] + w * aT[10])))));
        var s2 = w * (aT[1] + w * (aT[3] + w * (aT[5] + w * (aT[7] + w * aT[9]))));
        if (id < 0) return x - x * (s1 + s2);
        z = atanHi[id] - ((x * (s1 + s2) - atanLo[id]) - x);
        return hx < 0 ? -z : z;
    }

    internal static double StrictAtan2(double y, double x)
    {
        const double tiny = 1.0e-300;
        const double piOver4 = 7.8539816339744827900e-01;
        const double piOver2 = 1.5707963267948965580e+00;
        const double piLo = 1.2246467991473531772e-16;
        var hx = HighWord(x);
        var ix = hx & 0x7fffffff;
        var lx = LowWord(x);
        var hy = HighWord(y);
        var iy = hy & 0x7fffffff;
        var ly = LowWord(y);
        if (double.IsNaN(x) || double.IsNaN(y)) return x + y;
        if (((hx - 0x3ff00000) | lx) == 0) return StrictAtan(y);
        var m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
        if ((iy | ly) == 0)
            return m switch { 0 or 1 => y, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        if ((ix | lx) == 0) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        if (ix == 0x7ff00000)
        {
            if (iy == 0x7ff00000)
                return m switch
                {
                    0 => piOver4 + tiny,
                    1 => -piOver4 - tiny,
                    2 => 3.0 * piOver4 + tiny,
                    _ => -3.0 * piOver4 - tiny
                };
            return m switch { 0 => 0.0, 1 => -0.0, 2 => Math.PI + tiny, _ => -Math.PI - tiny };
        }
        if (iy == 0x7ff00000) return hy < 0 ? -piOver2 - tiny : piOver2 + tiny;
        var k = (iy - ix) >> 20;
        double z;
        if (k > 60) z = piOver2 + 0.5 * piLo;
        else if (hx < 0 && k < -60) z = 0.0;
        else z = StrictAtan(Math.Abs(y / x));
        return m switch
        {
            0 => z,
            1 => -z,
            2 => Math.PI - (z - piLo),
            _ => (z - piLo) - Math.PI
        };
    }

    private static int HighWord(double value) =>
        unchecked((int)(BitConverter.DoubleToInt64Bits(value) >> 32));

    private static int LowWord(double value) =>
        unchecked((int)BitConverter.DoubleToInt64Bits(value));

    private static double ClearLowWord(double value) =>
        BitConverter.Int64BitsToDouble(BitConverter.DoubleToInt64Bits(value) & unchecked((long)0xffffffff00000000UL));

    private static double WithHighWord(double value, int highWord) =>
        BitConverter.Int64BitsToDouble(
            (BitConverter.DoubleToInt64Bits(value) & 0x00000000ffffffffL) |
            ((long)highWord << 32));
    internal static StringBuilder StringBuilderDelete(StringBuilder value, int start, int end) =>
        value.Remove(start, end - start);

    internal static IEnumerable<int> CodePoints(string value)
    {
        for (var index = 0; index < value.Length;)
        {
            var codePoint = char.ConvertToUtf32(value, index);
            yield return codePoint;
            index += char.IsSurrogatePair(value, index) ? 2 : 1;
        }
    }

    internal static string EnumName(object value)
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        return value.GetType().GetFields(flags).FirstOrDefault(field => ReferenceEquals(field.GetValue(null), value))?.Name
               ?? value.ToString() ?? string.Empty;
    }

    internal static int EnumOrdinal(object value)
    {
        var type = value.GetType();
        if (type.IsEnum) return Convert.ToInt32(value, CultureInfo.InvariantCulture);
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        var constants = type.GetFields(flags)
            .Where(field => type.IsAssignableFrom(field.FieldType))
            .OrderBy(field => field.MetadataToken)
            .ToArray();
        var ordinal = Array.FindIndex(constants, field => ReferenceEquals(field.GetValue(null), value));
        return ordinal >= 0 ? ordinal : throw new ArgumentException("Value is not a declared enum constant", nameof(value));
    }

    internal static T EnumValueOf<T>(string name)
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        var field = typeof(T).GetField(name, flags);
        return field?.GetValue(null) is T value
            ? value
            : throw new ArgumentException($"No enum constant {typeof(T).FullName}.{name}", nameof(name));
    }

    internal static T[] EnumValues<T>()
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        return typeof(T).GetFields(flags)
            .Where(field => typeof(T).IsAssignableFrom(field.FieldType))
            .OrderBy(field => field.MetadataToken)
            .Select(field => (T)field.GetValue(null)!)
            .ToArray();
    }

    internal static int ParseInt(string value)
    {
        try
        {
            return int.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (OverflowException error)
        {
            throw new FormatException(error.Message, error);
        }
    }

    internal static int ParseInt(string value, int radix) =>
        checked((int)ParseSignedRadix(value, radix, int.MinValue, int.MaxValue));

    internal static long ParseLong(string value)
    {
        try
        {
            return long.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (OverflowException error)
        {
            throw new FormatException(error.Message, error);
        }
    }
    internal static long ParseLong(string value, int radix) =>
        ParseSignedRadix(value, radix, long.MinValue, long.MaxValue);
    internal static long ParseLong(string value, int beginIndex, int endIndex, int radix) =>
        ParseLong(value.Substring(beginIndex, endIndex - beginIndex), radix);
    internal static sbyte ParseByte(string value, int radix)
    {
        return checked((sbyte)ParseSignedRadix(value, radix, sbyte.MinValue, sbyte.MaxValue));
    }

    private static long ParseSignedRadix(string value, int radix, long minimum, long maximum)
    {
        if (radix is < 2 or > 36) throw new ArgumentException($"Invalid radix {radix}.");
        if (string.IsNullOrEmpty(value)) throw new FormatException("Input string was empty.");
        var index = 0;
        var negative = false;
        if (value[0] is '+' or '-')
        {
            negative = value[0] == '-';
            index++;
            if (index == value.Length) throw new FormatException($"Invalid number `{value}`.");
        }
        ulong magnitude = 0;
        var negativeLimit = unchecked((ulong)(-(minimum + 1))) + 1UL;
        var limit = negative ? negativeLimit : (ulong)maximum;
        for (; index < value.Length; index++)
        {
            var character = value[index];
            var digit = character is >= '0' and <= '9' ? character - '0'
                : character is >= 'a' and <= 'z' ? character - 'a' + 10
                : character is >= 'A' and <= 'Z' ? character - 'A' + 10
                : -1;
            if (digit < 0 || digit >= radix)
                throw new FormatException($"Invalid number `{value}` for radix {radix}.");
            if (magnitude > (limit - (uint)digit) / (uint)radix)
                throw new FormatException($"Number `{value}` is out of range.");
            magnitude = magnitude * (uint)radix + (uint)digit;
        }
        if (!negative) return (long)magnitude;
        return magnitude == negativeLimit ? minimum : -(long)magnitude;
    }
    internal static double ParseDouble(string value) => double.Parse(value, CultureInfo.InvariantCulture);
    internal static int CompareLong(long left, long right) => left.CompareTo(right);
    internal static int CompareInt(int left, int right) => left.CompareTo(right);
    internal static int CompareDouble(double left, double right) => left.CompareTo(right);
    internal static int StringCompareTo(string left, string right) =>
        string.Compare(left, right, StringComparison.Ordinal);
    internal static int LongLeadingZeros(long value) => BitOperations.LeadingZeroCount(unchecked((ulong)value));
    internal static int LongTrailingZeros(long value) => BitOperations.TrailingZeroCount(unchecked((ulong)value));
    internal static int IntLeadingZeros(int value) => BitOperations.LeadingZeroCount(unchecked((uint)value));
    internal static int Signum(long value) => Math.Sign(value);
    internal static string ToHexString(long value) => unchecked((ulong)value).ToString("x", CultureInfo.InvariantCulture);
    internal static string ToUnsignedString(long value, int radix)
    {
        const string digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        if (radix is < 2 or > 36) radix = 10;
        var remaining = unchecked((ulong)value);
        if (remaining == 0) return "0";
        var buffer = new char[64];
        var index = buffer.Length;
        while (remaining != 0)
        {
            buffer[--index] = digits[(int)(remaining % (uint)radix)];
            remaining /= (uint)radix;
        }
        return new string(buffer, index, buffer.Length - index);
    }
    internal static string ToStringRadix(long value, int radix)
    {
        if (value >= 0) return ToUnsignedString(value, radix);
        return "-" + ToUnsignedString(unchecked(-value), radix);
    }
    internal static string ToStringRadix(int value, int radix) => ToStringRadix((long)value, radix);
    internal static int ToUnsignedInt(sbyte value) => unchecked((byte)value);
    internal static int ToUnsignedInt(byte value) => value;
    internal static long ToUnsignedLong(sbyte value) => unchecked((byte)value);
    internal static bool IsBmpCodePoint(int value) => value is >= char.MinValue and <= char.MaxValue;
    internal static bool IsValidCodePoint(int value) => Rune.IsValid(value);
    internal static bool IsUpperCase(int value) => Rune.IsUpper(new Rune(value));
    internal static bool IsUpperCase(char value) => char.IsUpper(value);
    internal static int ToTitleCase(int value) => value is >= char.MinValue and <= char.MaxValue
        ? char.ToUpperInvariant((char)value)
        : value;
    internal static long MathRound(double value) => double.IsNaN(value) ? 0
        : value >= long.MaxValue ? long.MaxValue
        : value <= long.MinValue ? long.MinValue
        : (long)Math.Floor(value + 0.5d);
    internal static int MathRoundFloat(float value) => float.IsNaN(value) ? 0
        : value >= int.MaxValue ? int.MaxValue
        : value <= int.MinValue ? int.MinValue
        : (int)Math.Floor(value + 0.5f);
    internal static long AddExact(long left, long right) => checked(left + right);
    internal static long MultiplyExact(long left, long right) => checked(left * right);
    internal static int MultiplyExactInt(int left, int right) => checked(left * right);
    internal static double SignumDouble(double value) => double.IsNaN(value) || value == 0.0
        ? value
        : value > 0.0 ? 1.0 : -1.0;
    internal static long SubtractExact(long left, long right) => checked(left - right);
    internal static long NegateExact(long value) => checked(-value);
    internal static int ToIntExact(long value) => checked((int)value);
    internal static int AddExactInt(int left, int right) => checked(left + right);
    internal static int GetExponent(double value) => Math.ILogB(value);
    internal static BigInteger NewBigInteger(int signum, sbyte[] magnitude) =>
        new BigInteger(magnitude.Select(value => unchecked((byte)value)).ToArray(), true, true) * Math.Sign(signum);
    internal static BigInteger NewBigInteger(int signum, byte[] magnitude) =>
        new BigInteger(magnitude, true, true) * Math.Sign(signum);
    internal static TimeSpan DurationOfSeconds(long seconds) => TimeSpan.FromSeconds(seconds);
    internal static TimeSpan DurationOfSeconds(long seconds, long nanos) =>
        TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    internal static T ClassCast<T>(Type type, object value) =>
        type.IsInstanceOfType(value) ? (T)value : throw new InvalidCastException();
    private static string? ClassResourceName(Assembly assembly, Type? type, string name)
    {
        var absolute = name.TrimStart('/').Replace('/', '.');
        var relative = name.StartsWith('/') || type?.Namespace is null
            ? absolute
            : type.Namespace + "." + absolute;
        if (assembly.GetManifestResourceInfo(relative) is not null) return relative;
        if (assembly.GetManifestResourceInfo(absolute) is not null) return absolute;
        var suffix = "." + absolute;
        var matches = assembly.GetManifestResourceNames()
            .Where(candidate => candidate.EndsWith(suffix, StringComparison.Ordinal))
            .Take(2)
            .ToArray();
        return matches.Length == 1 ? matches[0] : null;
    }
    internal static Uri? ClassGetResource(Type type, string name) =>
        ClassResourceName(type.Assembly, type, name) is { } resource ? new Uri("resource:///" + resource) : null;
    internal static Uri? ClassGetResource(Assembly assembly, string name) =>
        assembly.GetManifestResourceInfo(name.TrimStart('/')) is null ? null : new Uri("resource:///" + name.TrimStart('/'));
    internal static Stream? ClassGetResourceAsStream(Type type, string name) =>
        ClassResourceName(type.Assembly, type, name) is { } resource
            ? type.Assembly.GetManifestResourceStream(resource)
            : null;
    internal static Stream? ClassGetResourceAsStream(Assembly assembly, string name) =>
        assembly.GetManifestResourceStream(name.TrimStart('/'));
    internal static T? ClassGetAnnotation<T>(Type type, Type annotationType) where T : class =>
        type.GetCustomAttributes(annotationType, true).FirstOrDefault() as T;
    internal static System.Diagnostics.StackFrame[] GetStackTrace(Exception exception) =>
        new System.Diagnostics.StackTrace(exception, true).GetFrames();
    internal static void SetStackTrace(Exception exception, object? stackTrace)
    {
        // System.Exception has no writable stack trace. Keep the Java call as
        // an explicit compatibility boundary while retaining the exception.
        _ = exception;
        _ = stackTrace;
    }
    internal static void PrintStackTrace(Exception exception) => Console.Error.WriteLine(exception);
    internal static void PrintStackTrace(Exception exception, object writer)
    {
        if (writer is TextWriter textWriter) textWriter.WriteLine(exception);
        else Console.Error.WriteLine(exception);
    }

    internal static string Formatted(string format, params object?[] arguments) =>
        JavaStringFormat(CultureInfo.CurrentCulture, format, arguments);

    internal static string JavaStringFormat(string format, params object?[] arguments) =>
        JavaStringFormat(CultureInfo.CurrentCulture, format, arguments);

    internal static string JavaStringFormat(CultureInfo locale, string format,
        params object?[] arguments)
    {
        var result = new StringBuilder();
        var nextArgument = 0;
        for (var index = 0; index < format.Length; index++)
        {
            if (format[index] != '%' || index + 1 >= format.Length)
            {
                result.Append(format[index]);
                continue;
            }

            var cursor = index + 1;
            if (format[cursor] == '%')
            {
                result.Append('%');
                index = cursor;
                continue;
            }
            if (format[cursor] == 'n')
            {
                result.Append(Environment.NewLine);
                index = cursor;
                continue;
            }

            int? explicitArgument = null;
            var digitsStart = cursor;
            while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
            if (cursor < format.Length && cursor > digitsStart && format[cursor] == '$')
            {
                explicitArgument = int.Parse(format[digitsStart..cursor],
                    CultureInfo.InvariantCulture) - 1;
                cursor++;
            }
            else
            {
                cursor = digitsStart;
            }

            var flagsStart = cursor;
            while (cursor < format.Length && "-#+ 0,(<".Contains(format[cursor])) cursor++;
            var flags = format[flagsStart..cursor];
            var widthStart = cursor;
            while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
            var width = cursor > widthStart
                ? int.Parse(format[widthStart..cursor], CultureInfo.InvariantCulture)
                : 0;
            int? precision = null;
            if (cursor < format.Length && format[cursor] == '.')
            {
                cursor++;
                var precisionStart = cursor;
                while (cursor < format.Length && char.IsDigit(format[cursor])) cursor++;
                if (precisionStart == cursor) throw new FormatException("Invalid Java format precision");
                precision = int.Parse(format[precisionStart..cursor], CultureInfo.InvariantCulture);
            }
            if (cursor < format.Length && format[cursor] is 't' or 'T') cursor++;
            if (cursor >= format.Length) throw new FormatException("Invalid Java format conversion");
            var conversion = format[cursor];

            var argumentIndex = explicitArgument ?? nextArgument++;
            if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                throw new FormatException("Missing Java format argument");
            var rendered = FormatJavaArgument(arguments[argumentIndex], conversion, precision, locale);
            if (conversion is >= 'A' and <= 'Z') rendered = rendered.ToUpper(locale);
            if (flags.Contains('+') && rendered.Length > 0 && rendered[0] != '-') rendered = "+" + rendered;
            if (width > rendered.Length)
            {
                var padding = new string(flags.Contains('0') && !flags.Contains('-') ? '0' : ' ',
                    width - rendered.Length);
                rendered = flags.Contains('-') ? rendered + padding : padding + rendered;
            }
            result.Append(rendered);
            index = cursor;
        }
        return result.ToString();
    }

    private static string FormatJavaArgument(object? value, char conversion, int? precision,
        CultureInfo locale)
    {
        switch (char.ToLowerInvariant(conversion))
        {
            case 's':
            {
                var rendered = StringValueOf(value);
                return precision is { } limit && rendered.Length > limit ? rendered[..limit] : rendered;
            }
            case 'b': return value is null ? "false" : value is bool boolean ? StringValueOf(boolean) : "true";
            case 'c': return value is char character
                ? character.ToString()
                : char.ConvertFromUtf32(Convert.ToInt32(value, CultureInfo.InvariantCulture));
            case 'd': return Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString(locale);
            case 'o': return Convert.ToString(Convert.ToInt64(value, CultureInfo.InvariantCulture), 8)!;
            case 'x': return Convert.ToInt64(value, CultureInfo.InvariantCulture).ToString("x", locale);
            case 'f': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("F" + (precision ?? 6), locale);
            case 'e': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("E" + (precision ?? 6), locale);
            case 'g': return Convert.ToDouble(value, CultureInfo.InvariantCulture)
                .ToString("G" + (precision ?? 6), locale);
            case 'h': return JavaHashCode(value).ToString("x", CultureInfo.InvariantCulture);
            default: return StringValueOf(value);
        }
    }

    internal static int IndexOfCodePoint(string value, int codePoint, int fromIndex) =>
        value.IndexOf(char.ConvertFromUtf32(codePoint), Math.Max(0, fromIndex), StringComparison.Ordinal);

    internal static string Repeat(string value, int count)
    {
        if (count < 0) throw new ArgumentException("count is negative", nameof(count));
        return string.Concat(Enumerable.Repeat(value, count));
    }

    internal static bool StartsWith(string value, string prefix) => value.StartsWith(prefix, StringComparison.Ordinal);
    internal static bool RegionMatches(string value, bool ignoreCase, int thisOffset, string other, int otherOffset, int length) =>
        string.Compare(value, thisOffset, other, otherOffset, length,
            ignoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal) == 0;

    internal static string Substring(string value, int begin, int end) => value.Substring(begin, end - begin);

    internal static StringBuilder AppendRange(StringBuilder builder, string value, int start, int end) =>
        builder.Append(value, start, end - start);

    internal static StringBuilder Reverse(StringBuilder builder)
    {
        var runes = builder.ToString().EnumerateRunes().ToArray();
        Array.Reverse(runes);
        builder.Clear();
        foreach (var rune in runes) builder.Append(rune.ToString());
        return builder;
    }
    internal static void Reverse<T>(IList<T> values)
    {
        for (var left = 0; left < values.Count / 2; left++)
        {
            var right = values.Count - left - 1;
            (values[left], values[right]) = (values[right], values[left]);
        }
    }

    internal static void ArrayCopy(object source, int sourceIndex, object destination, int destinationIndex, int length) =>
        Array.Copy((Array)source, sourceIndex, (Array)destination, destinationIndex, length);

    internal static T[] ArrayCopy<T>(T[] source, int length, Type? _) => CopyOf(source, length);

    internal static IDictionary<string, string> GetEnvironment() =>
        Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? string.Empty,
                          StringComparer.Ordinal);

    internal static IDictionary<string, string> GetProperties() =>
        new Dictionary<string, string>(SystemProperties, StringComparer.Ordinal);

    internal static string? GetProperty(string name) =>
        SystemProperties.TryGetValue(name, out var value) ? value : null;

    internal static string? SetProperty(string name, string value)
    {
        var previous = GetProperty(name);
        SystemProperties[name] = value;
        return previous;
    }

    private static void ValidateJavaUriText(string value)
    {
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (current <= 0x20 || current == 0x7f || current == '^')
                throw new JavaUriSyntaxException(value, "Illegal character in path", index);
            if (current != '%') continue;
            if (index + 2 >= value.Length ||
                !Uri.IsHexDigit(value[index + 1]) || !Uri.IsHexDigit(value[index + 2]))
                throw new JavaUriSyntaxException(value, "Malformed escape pair", index);
            index += 2;
        }
    }

    internal static Uri CreateUri(string value)
    {
        ValidateJavaUriText(value);
        if (Regex.IsMatch(value, @"(?i)^file:[^/]"))
        {
            // System.Uri rejects Java's opaque `file:path` form before the
            // translated file reader can apply Pkl's purpose-built diagnostic.
            // Keep a valid CLR carrier while retaining the Java URI spelling
            // and opaque semantics through the compatibility accessors.
            var opaqueFile = new Uri("file:///" + value["file:".Length..], UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(opaqueFile, _ => new JavaUriText(value));
            return opaqueFile;
        }
        if (Regex.IsMatch(value, @"(?i)^file:/[^/]"))
        {
            var singleSlash = new Uri("file:///" + value["file:/".Length..], UriKind.Absolute);
            _ = SingleSlashFileUris.GetValue(singleSlash, _ => new object());
            return singleSlash;
        }
        if (Regex.IsMatch(value, @"(?i)^file:///[a-z]:$"))
        {
            var driveOnly = new Uri(value + "/", UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(driveOnly, _ => new JavaUriText(value));
            return driveOnly;
        }
        if (Regex.IsMatch(value, @"(?i)(?:^|/)%2e(?:%2e)?(?:/|$)"))
        {
            var options = new UriCreationOptions
            {
                DangerousDisablePathAndQueryCanonicalization = true
            };
            return new Uri(value, in options);
        }
        return new Uri(value, UriKind.RelativeOrAbsolute);
    }
    internal static string NewString(char[] value) => new(value);
    internal static string NewString(char[] value, int offset, int count) => new(value, offset, count);
    internal static string NewString(int[] codePoints, int offset, int count) =>
        string.Concat(codePoints.Skip(offset).Take(count).Select(CodePointToString));
    internal static string NewString(sbyte[] value, Encoding encoding) =>
        encoding.GetString(value.Select(item => unchecked((byte)item)).ToArray());
    internal static string NewString(sbyte[] value, int offset, int count, Encoding encoding) =>
        encoding.GetString(value.Skip(offset).Take(count).Select(item => unchecked((byte)item)).ToArray());
    internal static string NewString(byte[] value, Encoding encoding) => encoding.GetString(value);
    internal static string NewString(object value) => StringValueOf(value);
    internal static Uri NewUri(string value) => CreateUri(value);
    internal static string UriToString(Uri value) =>
        OriginalUriTexts.TryGetValue(value, out var original)
            ? original.Value
            : SingleSlashFileUris.TryGetValue(value, out _) && value.IsAbsoluteUri && value.IsFile
            ? "file:" + value.AbsolutePath + value.Query + value.Fragment
            : value.IsAbsoluteUri && value.IsFile &&
              !value.OriginalString.StartsWith("file:", StringComparison.OrdinalIgnoreCase)
            // Idiomatic .NET callers commonly construct a file URI directly
            // from an absolute path. System.Uri keeps that bare path as its
            // OriginalString even though the URI's scheme is `file`; Java's
            // URI.toString() carrier must expose the scheme for allowlist and
            // other URI-pattern behavior. Explicit Java URI spellings were
            // handled by the preserved-text branches above.
            ? value.AbsoluteUri
            : value.OriginalString;

    internal static bool UriUsesSingleSlashFileSyntax(Uri value) =>
        SingleSlashFileUris.TryGetValue(value, out _);

    private static bool IsUriUnreserved(char value) =>
        value is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or
            '-' or '.' or '_' or '~';

    private static string QuoteUriComponent(string value, string allowedPunctuation)
    {
        StringBuilder? result = null;
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (IsUriUnreserved(current) || allowedPunctuation.Contains(current) ||
                (current > 0x7f && !char.IsControl(current) && !char.IsWhiteSpace(current)))
            {
                result?.Append(current);
                continue;
            }

            result ??= new StringBuilder(value.Length + 8).Append(value, 0, index);
            foreach (var octet in Encoding.UTF8.GetBytes(new[] { current }))
                result.Append('%').Append(octet.ToString("X2", CultureInfo.InvariantCulture));
        }
        return result?.ToString() ?? value;
    }

    internal static Uri NewUri(string? scheme, string? schemeSpecificPart, string? fragment)
    {
        var text = (scheme is null ? string.Empty : scheme + ":") +
                   QuoteUriComponent(schemeSpecificPart ?? string.Empty, ":/?[]@!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }
    internal static UriFormatException NewUriSyntaxException(string input, string reason) =>
        new JavaUriSyntaxException(input, reason);
    internal static UriFormatException NewUriSyntaxException(string input, string reason, int index) =>
        new JavaUriSyntaxException(input, reason, index);
    internal static string UriSyntaxReason(UriFormatException error) =>
        error is JavaUriSyntaxException syntax ? syntax.Reason : error.Message;
    internal static string UriSyntaxInput(UriFormatException error)
    {
        if (error is JavaUriSyntaxException syntax) return syntax.InputText;
        var separator = error.Message.LastIndexOf(": ", StringComparison.Ordinal);
        return separator >= 0 ? error.Message[(separator + 2)..] : error.Message;
    }
    internal static IOException NewIOException() => new();
    internal static IOException NewIOException(string? message) => new(message);
    internal static IOException NewIOException(Exception cause) => new(cause.Message, cause);
    internal static IOException NewIOException(string? message, Exception? cause) => new(message, cause);
    internal static FileNotFoundException NewFileNotFoundException() => new JavaFileNotFoundException();
    internal static Exception NewException() => new();
    internal static Exception NewException(string? message) => new(message);
    internal static Exception NewException(Exception cause) => new(cause.Message, cause);
    internal static Exception NewException(string? message, Exception? cause) => new(message, cause);
    internal static ArgumentException NewArgumentException() => new();
    internal static ArgumentException NewArgumentException(string? message) => new(message);
    internal static ArgumentException NewArgumentException(Exception cause) => new(cause.Message, cause);
    internal static ArgumentException NewArgumentException(string? message, Exception? cause) => new(message, cause);
    internal static InvalidOperationException NewInvalidOperationException() => new();
    internal static InvalidOperationException NewInvalidOperationException(string? message) => new(message);
    internal static InvalidOperationException NewInvalidOperationException(Exception cause) => new(cause.Message, cause);
    internal static InvalidOperationException NewInvalidOperationException(string? message, Exception? cause) => new(message, cause);
    internal static TypeInitializationException NewTypeInitializationException(Exception cause) =>
        new(cause.GetType().FullName, cause);
    internal static Uri NewUri(string? scheme, string? host, string? path, string? fragment)
        => NewUri(scheme, null, host, -1, path, null, fragment);

    internal static Uri NewUri(string? scheme, string? userInfo, string? host, int port,
        string? path, string? query, string? fragment)
    {
        if (host is null && (userInfo is not null || port != -1))
            throw new UriFormatException("User info and port require a URI host.");
        var text = scheme is null ? string.Empty : scheme + ":";
        if (host is not null)
        {
            text += "//";
            if (userInfo is not null)
                text += QuoteUriComponent(userInfo, ":!$&'()*+,;=") + "@";
            text += host;
            if (port != -1) text += ":" + port.ToString(CultureInfo.InvariantCulture);
        }
        text += QuoteUriComponent(path ?? string.Empty, ":@/!$&'()*+,;=");
        if (query is not null)
            text += "?" + QuoteUriComponent(query, ":@/?!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }

    private static string UriTextBeforeFragment(Uri uri)
    {
        var text = OriginalUriTexts.TryGetValue(uri, out var original)
            ? original.Value
            : uri.OriginalString;
        var fragment = text.IndexOf('#');
        return fragment < 0 ? text : text[..fragment];
    }

    internal static string? UriScheme(Uri uri) => uri.IsAbsoluteUri ? uri.Scheme : null;

    private static string? DecodeUriComponent(string? value) =>
        value is null ? null : Uri.UnescapeDataString(value);

    internal static string? UriRawSchemeSpecificPart(Uri uri)
    {
        var text = UriTextBeforeFragment(uri);
        var colon = text.IndexOf(':');
        return colon < 0 ? text : text[(colon + 1)..];
    }

    internal static string? UriSchemeSpecificPart(Uri uri) =>
        DecodeUriComponent(UriRawSchemeSpecificPart(uri));

    internal static string? UriRawFragment(Uri uri)
    {
        var text = uri.OriginalString;
        var marker = text.IndexOf('#');
        return marker < 0 ? null : text[(marker + 1)..];
    }

    internal static string? UriFragment(Uri uri) => DecodeUriComponent(UriRawFragment(uri));

    internal static string? UriRawQuery(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var marker = schemeSpecificPart.IndexOf('?');
        return marker < 0 ? null : schemeSpecificPart[(marker + 1)..];
    }

    internal static string? UriQuery(Uri uri) => DecodeUriComponent(UriRawQuery(uri));

    internal static string? UriRawAuthority(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        if (!schemeSpecificPart.StartsWith("//", StringComparison.Ordinal)) return null;
        var end = schemeSpecificPart.Length;
        var slash = schemeSpecificPart.IndexOf('/', 2);
        var query = schemeSpecificPart.IndexOf('?', 2);
        if (slash >= 0) end = Math.Min(end, slash);
        if (query >= 0) end = Math.Min(end, query);
        return end == 2 ? null : schemeSpecificPart[2..end];
    }

    internal static string? UriAuthority(Uri uri) => DecodeUriComponent(UriRawAuthority(uri));

    internal static string? UriHost(Uri uri) => uri.IsAbsoluteUri && !string.IsNullOrEmpty(uri.Host) ? uri.Host : null;

    internal static string? UriRawUserInfo(Uri uri)
    {
        var authority = UriRawAuthority(uri);
        if (authority is null) return null;
        var marker = authority.LastIndexOf('@');
        return marker < 0 ? null : authority[..marker];
    }

    internal static string? UriUserInfo(Uri uri) => DecodeUriComponent(UriRawUserInfo(uri));

    internal static int UriPort(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return -1;
        var authority = UriRawAuthority(uri);
        if (authority is null) return -1;
        var userInfo = authority.LastIndexOf('@');
        if (userInfo >= 0) authority = authority[(userInfo + 1)..];
        var closeBracket = authority.LastIndexOf(']');
        var colon = authority.LastIndexOf(':');
        return colon > closeBracket && int.TryParse(authority[(colon + 1)..], out var port) ? port : -1;
    }

    internal static string? UriRawPath(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var query = schemeSpecificPart.IndexOf('?');
        var pathEnd = query < 0 ? schemeSpecificPart.Length : query;
        if (schemeSpecificPart.StartsWith("//", StringComparison.Ordinal))
        {
            var pathStart = schemeSpecificPart.IndexOf('/', 2);
            return pathStart < 0 || pathStart >= pathEnd
                ? string.Empty
                : schemeSpecificPart[pathStart..pathEnd];
        }
        return schemeSpecificPart[..pathEnd];
    }

    internal static string? UriPath(Uri uri) => DecodeUriComponent(UriRawPath(uri));

    internal static Uri ResolveUri(Uri basis, string value) => ResolveUri(basis, CreateUri(value));
    internal static Uri ResolveUri(Uri basis, Uri value)
    {
        if (value.IsAbsoluteUri) return value;
        // java.net.URI.resolve("") resolves to the base URI's containing
        // directory; System.Uri otherwise preserves the base file itself.
        if (value.OriginalString.Length == 0) value = CreateUri(".");
        if (OriginalUriTexts.TryGetValue(basis, out var originalBasis) &&
            Regex.IsMatch(originalBasis.Value, @"(?i)^file:///[a-z]:$") &&
            value.OriginalString == ".")
            return new Uri("file:///", UriKind.Absolute);
        // java.net.URI.resolve leaves a relative reference relative when the
        // base URI is opaque. System.Uri instead interprets it as a new opaque
        // scheme-specific part (for example, `repl:foo.pkl`).
        if (basis.IsAbsoluteUri && UriIsOpaque(basis)) return value;
        if (basis.IsAbsoluteUri) return new Uri(basis, value);
        var basisText = basis.OriginalString;
        var rooted = basisText.StartsWith("/", StringComparison.Ordinal);
        var dummyBasis = new Uri("https://vibeformer.invalid/" + basisText.TrimStart('/'));
        var resolved = new Uri(dummyBasis, value);
        var text = resolved.PathAndQuery + resolved.Fragment;
        if (!rooted) text = text.TrimStart('/');
        return new Uri(text, UriKind.Relative);
    }
    internal static Uri ResolveLocalDependencyUri(Uri basis, Uri value)
    {
        var resolved = ResolveUri(basis, value);
        if (resolved.IsAbsoluteUri && resolved.IsFile)
            _ = SingleSlashFileUris.GetValue(resolved, _ => new object());
        return resolved;
    }
    internal static Uri NormalizeUri(Uri uri) => uri;
    internal static Uri RelativizeUri(Uri basis, Uri value)
    {
        if (!basis.IsAbsoluteUri || !value.IsAbsoluteUri ||
            !string.Equals(UriScheme(basis), UriScheme(value), StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(UriRawAuthority(basis), UriRawAuthority(value), StringComparison.Ordinal))
            return value;
        var basePath = UriRawPath(basis) ?? string.Empty;
        var valuePath = UriRawPath(value) ?? string.Empty;
        if (!valuePath.StartsWith(basePath, StringComparison.Ordinal)) return value;
        var relative = valuePath[basePath.Length..];
        var query = UriRawQuery(value);
        var fragment = UriRawFragment(value);
        if (query is not null) relative += "?" + query;
        if (fragment is not null) relative += "#" + fragment;
        return CreateUri(relative);
    }
    internal static bool UriIsOpaque(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return false;
        var original = OriginalUriTexts.TryGetValue(uri, out var preserved)
            ? preserved.Value
            : uri.OriginalString;
        var colon = original.IndexOf(':');
        return colon >= 0 && (colon + 1 == original.Length || original[colon + 1] != '/');
    }
    internal static Exception InitCause(Exception exception, Exception cause) =>
        new Exception(exception.Message, cause);
    internal static int IdentityHashCode(object value) =>
        System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);

    internal static long NanoTime() =>
        checked((long)(System.Diagnostics.Stopwatch.GetTimestamp() *
                       (1_000_000_000.0 / System.Diagnostics.Stopwatch.Frequency)));

    internal static string Format(JavaFormat format, object? value) => format.Format(value);

    internal static bool Add<T>(ICollection<T> collection, T value)
    {
        collection.Add(value);
        return true;
    }
    internal static bool Add<T>(ICollection<T> collection, object? value)
    {
        collection.Add((T)value!);
        return true;
    }

    internal static bool AddAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var changed = false;
        foreach (var value in values)
        {
            collection.Add(value);
            changed = true;
        }
        return changed;
    }
    internal static bool RemoveIf<T>(ICollection<T> collection, Func<T, bool> predicate)
    {
        var removed = false;
        foreach (var value in collection.Where(predicate).ToList())
            removed |= collection.Remove(value);
        return removed;
    }

    internal static int CollectionCount<T>(IEnumerable<T> collection) => collection.Count();
    internal static bool CollectionIsEmpty<T>(IEnumerable<T> collection) => !collection.Any();
    internal static int CollectionCount(IEnumerable collection) => collection.Cast<object?>().Count();
    internal static bool CollectionIsEmpty(IEnumerable collection) => !collection.Cast<object?>().Any();
    internal static bool CollectionContains<T>(IEnumerable<T> collection, object? value) =>
        value is T typed && collection.Contains(typed);
    internal static bool CollectionRemove<T>(ICollection<T> collection, object? value) =>
        value is T typed && collection.Remove(typed);
    internal static bool ContainsAll<T>(IEnumerable<T> collection, IEnumerable<T> values)
    {
        var set = new HashSet<T>(collection);
        return values.All(set.Contains);
    }
    internal static bool RemoveAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var changed = false;
        foreach (var value in values.ToArray()) changed |= collection.Remove(value);
        return changed;
    }
    internal static bool RetainAll<T>(ICollection<T> collection, IEnumerable<T> values)
    {
        var retained = new HashSet<T>(values);
        var changed = false;
        foreach (var value in collection.Where(value => !retained.Contains(value)).ToArray())
            changed |= collection.Remove(value);
        return changed;
    }

    internal static IList<T> Mutable<T>(IList<T> values) => new List<T>(values);
    internal static ISet<T> Mutable<T>(ISet<T> values) =>
        new HashSet<T>(values, new JavaEqualityComparer<T>());
    internal static IDictionary<K, V> Mutable<K, V>(IDictionary<K, V> values) where K : notnull =>
        new Dictionary<K, V>(values);
    internal static IList<T> Assoc<T>(IList<T> values, int index, T value)
    {
        var result = new List<T>(values);
        result[index] = value;
        return result;
    }
    internal static IDictionary<K, V> Assoc<K, V>(IDictionary<K, V> values, K key, V value) where K : notnull
    {
        var result = new Dictionary<K, V>(values) { [key] = value };
        return result;
    }
    internal static IDictionary<K, V> Without<K, V>(IDictionary<K, V> values, K key) where K : notnull
    {
        var result = new Dictionary<K, V>(values);
        result.Remove(key);
        return result;
    }
    internal static ISet<T> Without<T>(ISet<T> values, T value)
    {
        var result = new HashSet<T>(values, new JavaEqualityComparer<T>());
        result.Remove(value);
        return result;
    }

    internal static bool MapContainsKey<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.ContainsKey(typed);
    internal static bool MapContainsKey<K, V>(IReadOnlyDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.ContainsKey(typed);

    internal static ISet<JavaMapEntry<K, V>> MapEntrySet<K, V>(IDictionary<K, V> map) where K : notnull =>
        new JavaMapEntrySet<K, V>(map);
    internal static IEnumerable<KeyValuePair<K, V>> MapEntrySet<K, V>(IReadOnlyDictionary<K, V> map)
        where K : notnull => map;

    internal static bool MapIsEmpty<K, V>(IDictionary<K, V> map) where K : notnull => map.Count == 0;
    internal static bool MapIsEmpty<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull => map.Count == 0;
    internal static ISet<K> MapKeySet<K, V>(IDictionary<K, V> map) where K : notnull =>
        map is JavaLinkedHashMap<K, V> linked
            ? linked.KeySet()
            : new JavaMapKeySet<K, V>(map);
    internal static ISet<K> MapKeySet<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull =>
        new HashSet<K>(map.Keys, new JavaEqualityComparer<K>());
    internal static int MapCount<K, V>(IDictionary<K, V> map) where K : notnull => map.Count;
    internal static int MapCount<K, V>(IReadOnlyDictionary<K, V> map) where K : notnull => map.Count;
    internal static bool MapContainsValue<K, V>(IDictionary<K, V> map, object? value) where K : notnull =>
        value is V typed && map.Values.Contains(typed);
    internal static bool MapContainsValue<K, V>(IReadOnlyDictionary<K, V> map, object? value) where K : notnull =>
        value is V typed && map.Values.Contains(typed);
    internal static V MapRemove<K, V>(IDictionary<K, V> map, object? key) where K : notnull
    {
        if (key is K typed && map.Remove(typed, out var value)) return value;
        return default!;
    }
    internal static V ComputeIfAbsent<K, V>(IDictionary<K, V> map, K key, Func<K, V> factory) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked) return linked.ComputeIfAbsent(key, factory);
        if (map.TryGetValue(key, out var value) && value is not null) return value;
        value = factory(key);
        if (value is null) return default!;
        map[key] = value;
        return value;
    }
    internal static V MapGetOrDefault<K, V>(IDictionary<K, V> map, K key, V fallback) where K : notnull =>
        map is JavaLinkedHashMap<K, V> linked
            ? linked.GetOrDefault(key, fallback)
            : map.TryGetValue(key, out var value) ? value : fallback;
    internal static V MapGetOrDefault<K, V>(IReadOnlyDictionary<K, V> map, K key, V fallback) where K : notnull =>
        map.TryGetValue(key, out var value) ? value : fallback;
    internal static V MapPutIfAbsent<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked) return linked.PutIfAbsent(key, value);
        if (map.TryGetValue(key, out var previous) && previous is not null) return previous;
        return MapPut(map, key, value);
    }

    internal static void MapPutAll<K, V>(IDictionary<K, V> map, IEnumerable<KeyValuePair<K, V>> values) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked)
        {
            linked.PutAll(values);
            return;
        }
        foreach (var (key, value) in values) map[key] = value;
    }

    internal static V MapGet<K, V>(IDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed
            ? map is JavaLinkedHashMap<K, V> linked
                ? linked.Get(typed)
                : map.TryGetValue(typed, out var value) ? value : default!
            : default!;
    internal static V MapGet<K, V>(IReadOnlyDictionary<K, V> map, object? key) where K : notnull =>
        key is K typed && map.TryGetValue(typed, out var value) ? value : default!;

    internal static V MapPut<K, V>(IDictionary<K, V> map, K key, V value) where K : notnull
    {
        if (map is JavaLinkedHashMap<K, V> linked) return linked.Put(key, value);
        var previous = map.TryGetValue(key, out var oldValue) ? oldValue : default!;
        map[key] = value;
        return previous;
    }

    internal static JavaMapEntry<K, V> MapEntry<K, V>(K key, V value) where K : notnull => new(key, value);

    internal static IDictionary<K, V> MapOfEntries<K, V>(params JavaMapEntry<K, V>[] entries) where K : notnull =>
        entries.ToDictionary(entry => entry.Key, entry => entry.Value);
    internal static IDictionary<K, V> MapOf<K, V>(params object[] values) where K : notnull
    {
        var result = new Dictionary<K, V>();
        for (var index = 0; index < values.Length; index += 2)
            result[(K)values[index]] = (V)values[index + 1];
        return result;
    }

    private const int JavaRegexUnixLines = 0x01;
    private const int JavaRegexCaseInsensitive = 0x02;
    private const int JavaRegexComments = 0x04;
    private const int JavaRegexMultiline = 0x08;
    private const int JavaRegexLiteral = 0x10;
    private const int JavaRegexDotAll = 0x20;
    private const int JavaRegexUnicodeCase = 0x40;
    private const int JavaRegexCanonEq = 0x80;
    private const int JavaRegexUnicodeCharacterClass = 0x100;
    private const int JavaRegexAllFlags = 0x1ff;

    private sealed class JavaCodePointSet
    {
        private readonly List<(int Start, int End)> ranges;

        private JavaCodePointSet(IEnumerable<(int Start, int End)> source)
        {
            ranges = new List<(int Start, int End)>();
            foreach (var range in source.OrderBy(value => value.Start).ThenBy(value => value.End))
            {
                if (range.End < range.Start) continue;
                var start = Math.Max(0, range.Start);
                var end = Math.Min(0x10ffff, range.End);
                if (start > end) continue;
                if (ranges.Count != 0 && start <= ranges[^1].End + 1)
                {
                    var previous = ranges[^1];
                    ranges[^1] = (previous.Start, Math.Max(previous.End, end));
                }
                else
                {
                    ranges.Add((start, end));
                }
            }
        }

        internal static JavaCodePointSet Empty { get; } = new(Array.Empty<(int, int)>());
        internal static JavaCodePointSet All { get; } = new(new[] { (0, 0x10ffff) });
        internal static JavaCodePointSet Range(int start, int end) => new(new[] { (start, end) });

        internal static JavaCodePointSet FromPredicate(Func<int, bool> predicate)
        {
            var result = new List<(int Start, int End)>();
            var start = -1;
            for (var codePoint = 0; codePoint <= 0x10ffff; codePoint++)
            {
                var included = codePoint is < 0xd800 or > 0xdfff && predicate(codePoint);
                if (included && start < 0) start = codePoint;
                if (!included && start >= 0)
                {
                    result.Add((start, codePoint - 1));
                    start = -1;
                }
            }
            if (start >= 0) result.Add((start, 0x10ffff));
            return new JavaCodePointSet(result);
        }

        internal bool TrySingle(out int codePoint)
        {
            if (ranges.Count == 1 && ranges[0].Start == ranges[0].End)
            {
                codePoint = ranges[0].Start;
                return true;
            }
            codePoint = -1;
            return false;
        }

        internal bool Contains(int codePoint)
        {
            var lower = 0;
            var upper = ranges.Count - 1;
            while (lower <= upper)
            {
                var middle = lower + (upper - lower) / 2;
                if (codePoint < ranges[middle].Start) upper = middle - 1;
                else if (codePoint > ranges[middle].End) lower = middle + 1;
                else return true;
            }
            return false;
        }

        internal static JavaCodePointSet Parse(string value)
        {
            if (value.Length == 0) return Empty;
            return new JavaCodePointSet(value.Split(',').Select(encoded =>
            {
                var separator = encoded.IndexOf('-');
                var start = int.Parse(separator < 0 ? encoded : encoded[..separator],
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
                var end = separator < 0 ? start : int.Parse(encoded[(separator + 1)..],
                    NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
                return (start, end);
            }));
        }

        internal JavaCodePointSet Union(JavaCodePointSet other) => new(ranges.Concat(other.ranges));

        internal JavaCodePointSet CaseFold(bool unicode)
        {
            var result = this;
            if (!unicode)
            {
                for (var lower = 'a'; lower <= 'z'; lower++)
                {
                    var upper = char.ToUpperInvariant(lower);
                    if (Contains(lower) || Contains(upper))
                        result = result.Union(Range(lower, lower)).Union(Range(upper, upper));
                }
                return result;
            }
            foreach (var mapping in JavaRegexUnicode.Value.CaseFolds)
            {
                if (Contains(mapping.Upper) || Contains(mapping.Folded))
                    result = result.Union(Range(mapping.Candidate, mapping.Candidate));
            }
            return result;
        }

        internal JavaCodePointSet Intersect(JavaCodePointSet other)
        {
            var result = new List<(int, int)>();
            var left = 0;
            var right = 0;
            while (left < ranges.Count && right < other.ranges.Count)
            {
                var start = Math.Max(ranges[left].Start, other.ranges[right].Start);
                var end = Math.Min(ranges[left].End, other.ranges[right].End);
                if (start <= end) result.Add((start, end));
                if (ranges[left].End < other.ranges[right].End) left++;
                else right++;
            }
            return new JavaCodePointSet(result);
        }

        internal JavaCodePointSet Except(JavaCodePointSet other)
        {
            var result = new List<(int, int)>();
            foreach (var source in ranges)
            {
                var cursor = source.Start;
                foreach (var removed in other.ranges)
                {
                    if (removed.End < cursor) continue;
                    if (removed.Start > source.End) break;
                    if (removed.Start > cursor) result.Add((cursor, removed.Start - 1));
                    cursor = Math.Max(cursor, removed.End + 1);
                    if (cursor > source.End) break;
                }
                if (cursor <= source.End) result.Add((cursor, source.End));
            }
            return new JavaCodePointSet(result);
        }

        internal JavaCodePointSet Complement() => All.Except(this);

        private static string Unit(int value) => $"\\u{value:X4}";
        private static string UnitRange(int start, int end) =>
            start == end ? Unit(start) : "[" + Unit(start) + "-" + Unit(end) + "]";

        internal string ToRegex()
        {
            if (ranges.Count == 0) return "(?!)";
            var bmp = new List<(int Start, int End)>();
            var astral = new List<string>();
            foreach (var range in ranges)
            {
                if (range.Start <= 0xffff)
                {
                    var bmpEnd = Math.Min(range.End, 0xffff);
                    if (range.Start < 0xd800)
                        bmp.Add((range.Start, Math.Min(bmpEnd, 0xd7ff)));
                    if (bmpEnd >= 0xd800 && range.Start <= 0xdbff)
                        astral.Add(UnitRange(Math.Max(range.Start, 0xd800), Math.Min(bmpEnd, 0xdbff)) +
                                    "(?![\\uDC00-\\uDFFF])");
                    if (bmpEnd >= 0xdc00 && range.Start <= 0xdfff)
                        astral.Add("(?<![\\uD800-\\uDBFF])" +
                                    UnitRange(Math.Max(range.Start, 0xdc00), Math.Min(bmpEnd, 0xdfff)));
                    if (bmpEnd >= 0xe000)
                        bmp.Add((Math.Max(range.Start, 0xe000), bmpEnd));
                }
                if (range.End <= 0xffff) continue;
                var start = Math.Max(range.Start, 0x10000);
                var startHigh = char.ConvertFromUtf32(start)[0];
                var startLow = char.ConvertFromUtf32(start)[1];
                var endHigh = char.ConvertFromUtf32(range.End)[0];
                var endLow = char.ConvertFromUtf32(range.End)[1];
                if (startHigh == endHigh)
                {
                    astral.Add(Unit(startHigh) + "[" + Unit(startLow) + "-" + Unit(endLow) + "]");
                    continue;
                }
                astral.Add(Unit(startHigh) + "[" + Unit(startLow) + "-\\uDFFF]");
                if (startHigh + 1 <= endHigh - 1)
                    astral.Add("[" + Unit(startHigh + 1) + "-" + Unit(endHigh - 1) + "][\\uDC00-\\uDFFF]");
                astral.Add(Unit(endHigh) + "[\\uDC00-" + Unit(endLow) + "]");
            }

            var alternatives = new List<string>(astral);
            if (bmp.Count != 0)
            {
                var builder = new StringBuilder("[");
                foreach (var range in bmp)
                {
                    builder.Append(Unit(range.Start));
                    if (range.End != range.Start) builder.Append('-').Append(Unit(range.End));
                }
                alternatives.Add(builder.Append(']').ToString());
            }
            return alternatives.Count == 1
                ? alternatives[0]
                : "(?:" + string.Join("|", alternatives) + ")";
        }
    }

    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, JavaCodePointSet>
        JavaRegexPropertySets = new(StringComparer.Ordinal);

    private sealed class JavaRegexUnicodeDatabase
    {
        internal Dictionary<string, JavaCodePointSet> Blocks { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> AlgorithmicNameBlocks { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> Scripts { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, JavaCodePointSet> Properties { get; } = new(StringComparer.Ordinal);
        internal Dictionary<string, int> Names { get; } = new(StringComparer.Ordinal);
        internal List<(int Candidate, int Upper, int Folded)> CaseFolds { get; } = new();
        internal JavaCodePointSet[] GraphemeTypes { get; } =
            Enumerable.Repeat(JavaCodePointSet.Empty, 15).ToArray();
        internal Dictionary<string, JavaCodePointSet> IndicConjunct { get; } = new(StringComparer.Ordinal);

        internal JavaRegexUnicodeDatabase()
        {
            var compressed = Convert.FromBase64String(JavaRegexUnicodeData.GzipBase64);
            using var input = new MemoryStream(compressed, writable: false);
            using var gzip = new GZipStream(input, CompressionMode.Decompress);
            using var reader = new StreamReader(gzip, Encoding.UTF8, false, 65536);
            while (reader.ReadLine() is { } line)
            {
                var fields = line.Split('\t');
                if (fields.Length != 3) continue;
                switch (fields[0])
                {
                    case "B": Blocks[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "A": AlgorithmicNameBlocks[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "S": Scripts[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "P": Properties[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "N": Names[fields[1]] = int.Parse(fields[2], NumberStyles.AllowHexSpecifier,
                        CultureInfo.InvariantCulture); break;
                    case "G": GraphemeTypes[int.Parse(fields[1], CultureInfo.InvariantCulture)] =
                        JavaCodePointSet.Parse(fields[2]); break;
                    case "I": IndicConjunct[fields[1]] = JavaCodePointSet.Parse(fields[2]); break;
                    case "F": {
                        var values = fields[2].Split(',');
                        CaseFolds.Add((
                            int.Parse(fields[1], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture),
                            int.Parse(values[0], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture),
                            int.Parse(values[1], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture)));
                        break;
                    }
                }
            }
        }
    }

    private static readonly Lazy<JavaRegexUnicodeDatabase> JavaRegexUnicode =
        new(() => new JavaRegexUnicodeDatabase());

    private static string NormalizeJavaRegexPropertyName(string value) =>
        new(value.Where(char.IsLetterOrDigit).Select(char.ToLowerInvariant).ToArray());

    private static int JavaRegexNamedCodePoint(string rawName)
    {
        var name = rawName.Trim().ToUpperInvariant();
        var database = JavaRegexUnicode.Value;
        if (database.Names.TryGetValue(name, out var named)) return named;

        var separator = Math.Max(name.LastIndexOf(' '), name.LastIndexOf('-'));
        if (separator > 0 && int.TryParse(name[(separator + 1)..], NumberStyles.AllowHexSpecifier,
                CultureInfo.InvariantCulture, out var codePoint) && codePoint <= 0x10ffff)
        {
            var prefix = NormalizeJavaRegexPropertyName(name[..separator]);
            if (database.AlgorithmicNameBlocks.TryGetValue(prefix, out var block) && block.Contains(codePoint))
                return codePoint;
        }
        throw new ArgumentException("Unknown character name [" + rawName + "]");
    }

    private static string JavaGraphemeClusterPattern()
    {
        var data = JavaRegexUnicode.Value;
        string Type(int index) => data.GraphemeTypes[index].ToRegex();
        string Either(params string[] values) => "(?:" + string.Join("|", values) + ")";

        var cr = Type(1);
        var lf = Type(2);
        var control = Either(cr, lf, Type(3));
        var extend = Type(4);
        var zwj = Type(5);
        var regionalIndicator = Type(6);
        var prepend = Type(7);
        var spacingMark = Type(8);
        var l = Type(9);
        var v = Type(10);
        var t = Type(11);
        var lv = Type(12);
        var lvt = Type(13);
        var pictographic = Type(14);
        var noBreak = Either(extend, zwj, spacingMark);
        var hangul = Either(
            l + "+(?:(?:" + v + "|" + lv + ")" + v + "*" + t + "*|" + lvt + t + "*)?",
            "(?:" + lv + v + "*|" + v + "+)" + t + "*",
            "(?:" + lvt + "|" + t + ")" + t + "*");
        var emojiMarks = Either(extend, spacingMark);
        var emoji = pictographic + emojiMarks + "*(?:" + zwj + "+" + pictographic +
            emojiMarks + "*)*";
        var indicConsonant = data.IndicConjunct["consonant"].ToRegex();
        var indicExtend = data.IndicConjunct["extend"].ToRegex();
        var indicLinker = data.IndicConjunct["linker"].ToRegex();
        var indicPart = Either(indicExtend, indicLinker);
        var indic = indicConsonant + "(?:" + indicPart + "*" + indicLinker + indicPart + "*" +
            indicConsonant + ")+";
        var ordinary = Either(Type(0), extend, zwj, spacingMark);
        var core = Either(indic, emoji, regionalIndicator + regionalIndicator + "?", hangul, ordinary);
        return Either(cr + lf, control, prepend + "*" + core + noBreak + "*", prepend + "+");
    }

    private static UnicodeCategory UnicodeCategoryOf(int codePoint) =>
        Rune.GetUnicodeCategory(new Rune(codePoint));

    private static bool IsUnicodeWhitespace(int codePoint) =>
        codePoint is >= 0x0009 and <= 0x000d or 0x0020 or 0x0085 or 0x00a0 or 0x1680 or
            >= 0x2000 and <= 0x200a or 0x2028 or 0x2029 or 0x202f or 0x205f or 0x3000;

    private static bool IsJavaWhitespace(int codePoint)
    {
        if (codePoint is >= 0x0009 and <= 0x000d or >= 0x001c and <= 0x001f) return true;
        if (codePoint is 0x00a0 or 0x2007 or 0x202f) return false;
        return UnicodeCategoryOf(codePoint) is UnicodeCategory.SpaceSeparator or
            UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator;
    }

    private static bool IsUnicodeWord(int codePoint) =>
        codePoint is 0x200c or 0x200d || UnicodeCategoryOf(codePoint) is
            UnicodeCategory.UppercaseLetter or UnicodeCategory.LowercaseLetter or
            UnicodeCategory.TitlecaseLetter or UnicodeCategory.ModifierLetter or
            UnicodeCategory.OtherLetter or UnicodeCategory.NonSpacingMark or
            UnicodeCategory.SpacingCombiningMark or UnicodeCategory.EnclosingMark or
            UnicodeCategory.DecimalDigitNumber or UnicodeCategory.LetterNumber or
            UnicodeCategory.ConnectorPunctuation;

    private static JavaCodePointSet JavaRegexPropertySet(
        string rawName, bool unicodeClasses, bool caseInsensitive)
    {
        var cacheKey = (unicodeClasses ? "U:" : "A:") + (caseInsensitive ? "I:" : "S:") + rawName;
        return JavaRegexPropertySets.GetOrAdd(cacheKey, _ =>
        {
            var database = JavaRegexUnicode.Value;
            var equals = rawName.IndexOf('=');
            if (equals >= 0)
            {
                var property = NormalizeJavaRegexPropertyName(rawName[..equals]);
                var value = NormalizeJavaRegexPropertyName(rawName[(equals + 1)..]);
                var selected = property switch
                {
                    "sc" or "script" => database.Scripts.GetValueOrDefault(value),
                    "blk" or "block" => database.Blocks.GetValueOrDefault(value),
                    "gc" or "generalcategory" => RegexUnicodeProperty(value),
                    _ => null
                };
                return selected ?? throw new ArgumentException(
                    "Unknown Unicode property {name=<" + rawName[..equals] + ">, value=<" +
                    rawName[(equals + 1)..] + ">}");
            }

            if (rawName.StartsWith("In", StringComparison.Ordinal))
            {
                var block = NormalizeJavaRegexPropertyName(rawName[2..]);
                return database.Blocks.GetValueOrDefault(block) ?? throw new ArgumentException(
                    "Unknown character property name {" + rawName + "}");
            }
            if (rawName.StartsWith("Is", StringComparison.Ordinal))
            {
                var value = NormalizeJavaRegexPropertyName(rawName[2..]);
                return RegexUnicodeProperty(value) ?? database.Scripts.GetValueOrDefault(value) ??
                    throw new ArgumentException("Unknown character property name {" + rawName + "}");
            }

            var normalized = NormalizeJavaRegexPropertyName(rawName);
            return RegexUnicodeProperty(normalized) ?? throw new ArgumentException(
                "Unknown character property name {" + rawName + "}");

            JavaCodePointSet? RegexUnicodeProperty(string value)
            {
                var key = (unicodeClasses ? "u" : "a") + (caseInsensitive ? "i" : "s") + value;
                return database.Properties.GetValueOrDefault(key);
            }
        });
    }

    private sealed class JavaRegexTranslator
    {
        private readonly string pattern;
        private int index;
        private readonly List<string> groupNames = new() { string.Empty };
        private readonly Dictionary<string, string> namedGroups = new(StringComparer.Ordinal);

        internal JavaRegexTranslator(string pattern) => this.pattern = pattern;
        internal string[] GroupNames => groupNames.ToArray();
        internal IReadOnlyDictionary<string, string> NamedGroups => namedGroups;

        internal string Translate(int flags)
        {
            var mode = EffectiveFlags(flags);
            var result = TranslateSequence(ref mode, false);
            if (index != pattern.Length) throw new ArgumentException("Unexpected ')' near index " + index);
            return result;
        }

        private static int EffectiveFlags(int flags) =>
            (flags & JavaRegexUnicodeCharacterClass) != 0 ? flags | JavaRegexUnicodeCase : flags;

        private void SkipIgnored(int mode)
        {
            if ((mode & JavaRegexComments) == 0) return;
            while (index < pattern.Length)
            {
                if (char.IsWhiteSpace(pattern[index]))
                {
                    index++;
                    continue;
                }
                if (pattern[index] != '#') break;
                while (index < pattern.Length && pattern[index] is not '\n' and not '\r') index++;
            }
        }

        private string TranslateSequence(ref int mode, bool closesAtParenthesis)
        {
            var result = new StringBuilder();
            while (true)
            {
                SkipIgnored(mode);
                if (index == pattern.Length)
                {
                    if (closesAtParenthesis) throw new ArgumentException("Unclosed group near index " + index);
                    break;
                }
                if (pattern[index] == ')')
                {
                    if (!closesAtParenthesis) break;
                    index++;
                    break;
                }
                if (pattern[index] == '|')
                {
                    result.Append('|');
                    index++;
                    continue;
                }

                var atom = pattern[index] == '(' ? TranslateGroup(ref mode) : TranslateAtom(mode);
                if (atom is null) continue;
                result.Append(TranslateQuantifier(atom, mode));
            }
            return result.ToString();
        }

        private string? TranslateGroup(ref int mode)
        {
            index++;
            if (index >= pattern.Length || pattern[index] != '?')
            {
                var groupName = "j" + groupNames.Count.ToString(CultureInfo.InvariantCulture);
                groupNames.Add(groupName);
                var nestedMode = mode;
                return "(?<" + groupName + ">" + TranslateSequence(ref nestedMode, true) + ")";
            }

            index++;
            if (index >= pattern.Length) throw new ArgumentException("Unknown inline modifier near index " + index);
            if (pattern[index] == '<')
            {
                if (index + 1 < pattern.Length && pattern[index + 1] is '=' or '!')
                {
                    var prefix = pattern[index + 1] == '=' ? "(?<=" : "(?<!";
                    index += 2;
                    var lookbehindMode = mode;
                    return prefix + TranslateSequence(ref lookbehindMode, true) + ")";
                }
                var end = pattern.IndexOf('>', index + 1);
                if (end < 0) throw new ArgumentException("named capturing group is missing trailing '>'");
                var javaName = pattern[(index + 1)..end];
                if (javaName.Length == 0 || !char.IsAsciiLetter(javaName[0]) ||
                    javaName.Skip(1).Any(character => !char.IsAsciiLetterOrDigit(character)))
                    throw new ArgumentException("capturing group name does not start with a Latin letter");
                if (namedGroups.ContainsKey(javaName))
                    throw new ArgumentException("Named capturing group <" + javaName + "> is already defined");
                index = end + 1;
                var groupName = "j" + groupNames.Count.ToString(CultureInfo.InvariantCulture);
                groupNames.Add(groupName);
                namedGroups.Add(javaName, groupName);
                var namedGroupMode = mode;
                return "(?<" + groupName + ">" + TranslateSequence(ref namedGroupMode, true) + ")";
            }
            if (pattern[index] is ':' or '=' or '!' or '>')
            {
                var marker = pattern[index++];
                var prefix = marker switch
                {
                    ':' => "(?:",
                    '=' => "(?=",
                    '!' => "(?!",
                    _ => "(?>"
                };
                var nestedMode = mode;
                return prefix + TranslateSequence(ref nestedMode, true) + ")";
            }

            var changedMode = mode;
            var enable = true;
            var sawFlag = false;
            while (index < pattern.Length)
            {
                if (pattern[index] == '-')
                {
                    enable = false;
                    index++;
                    continue;
                }
                var flag = pattern[index] switch
                {
                    'd' => JavaRegexUnixLines,
                    'i' => JavaRegexCaseInsensitive,
                    'm' => JavaRegexMultiline,
                    's' => JavaRegexDotAll,
                    'u' => JavaRegexUnicodeCase,
                    'x' => JavaRegexComments,
                    'U' => JavaRegexUnicodeCharacterClass,
                    _ => 0
                };
                if (flag == 0) break;
                sawFlag = true;
                index++;
                if (enable) changedMode |= flag;
                else changedMode &= ~flag;
                changedMode = EffectiveFlags(changedMode);
            }
            if (!sawFlag || index >= pattern.Length || pattern[index] is not ')' and not ':')
                throw new ArgumentException("Unknown inline modifier near index " + index);
            if (pattern[index++] == ')')
            {
                mode = changedMode;
                return null;
            }
            return "(?:" + TranslateSequence(ref changedMode, true) + ")";
        }

        private string TranslateAtom(int mode)
        {
            var current = pattern[index++];
            return current switch
            {
                '[' => ParseClass(mode).ToRegex(),
                '.' => Dot(mode),
                '^' => StartAnchor(mode),
                '$' => EndAnchor(mode),
                '\\' => TranslateEscape(mode),
                '*' or '+' or '?' => throw new ArgumentException("Dangling meta character '" + current + "' near index " + (index - 1)),
                _ => Literal(ReadCodePoint(current), mode)
            };
        }

        private int ReadCodePoint(char first)
        {
            if (char.IsHighSurrogate(first) && index < pattern.Length && char.IsLowSurrogate(pattern[index]))
                return char.ConvertToUtf32(first, pattern[index++]);
            return first;
        }

        private string TranslateQuantifier(string atom, int mode)
        {
            SkipIgnored(mode);
            if (index >= pattern.Length) return atom;
            string? quantifier = null;
            if (pattern[index] is '?' or '*' or '+')
            {
                quantifier = pattern[index++].ToString();
            }
            else if (pattern[index] == '{')
            {
                var match = Regex.Match(pattern[index..], @"^\{\d+(?:,\d*)?\}");
                if (match.Success)
                {
                    quantifier = match.Value;
                    index += match.Length;
                }
            }
            if (quantifier is null) return atom;
            // A Java regex atom can translate to more than one .NET regex atom.
            // Supplementary code points are the important example: .NET regexes
            // operate on UTF-16 units, so their translated surrogate pair must
            // remain one unit when Java applies a quantifier.
            var quantified = "(?:" + atom + ")" + quantifier;
            SkipIgnored(mode);
            if (index < pattern.Length && pattern[index] == '?')
            {
                index++;
                return quantified + "?";
            }
            if (index < pattern.Length && pattern[index] == '+')
            {
                index++;
                return "(?>" + quantified + ")";
            }
            return quantified;
        }

        private string TranslateEscape(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Unexpected internal error near index " + index);
            var escaped = pattern[index++];
            return escaped switch
            {
                'Q' => Quoted(mode),
                'E' => throw new ArgumentException("Illegal/unsupported escape sequence near index " + (index - 1)),
                'd' => PredefinedClass('d', mode).ToRegex(),
                'D' => PredefinedClass('d', mode).Complement().ToRegex(),
                's' => PredefinedClass('s', mode).ToRegex(),
                'S' => PredefinedClass('s', mode).Complement().ToRegex(),
                'w' => PredefinedClass('w', mode).ToRegex(),
                'W' => PredefinedClass('w', mode).Complement().ToRegex(),
                'h' => PredefinedClass('h', mode).ToRegex(),
                'H' => PredefinedClass('h', mode).Complement().ToRegex(),
                'v' => PredefinedClass('v', mode).ToRegex(),
                'V' => PredefinedClass('v', mode).Complement().ToRegex(),
                'p' => ParseProperty(mode, false).ToRegex(),
                'P' => ParseProperty(mode, true).ToRegex(),
                'A' => "\\A",
                'G' => "\\G",
                'Z' => FinalTerminatorAnchor(mode),
                'z' => "\\z",
                'R' => "(?:\\r\\n|[\\n\\u000B\\f\\r\\u0085\\u2028\\u2029])",
                'X' => JavaGraphemeClusterPattern(),
                'b' when index + 2 < pattern.Length && pattern.Substring(index, 3) == "{g}" => GraphemeBoundary(),
                'b' => WordBoundary(mode, false),
                'B' => WordBoundary(mode, true),
                'k' => NamedBackReference(mode),
                '0' => Octal(mode),
                'x' => HexEscape(mode),
                'u' => FixedHexEscape(4, mode),
                'N' => NamedCharacter(mode),
                't' => Literal('\t', mode),
                'n' => Literal('\n', mode),
                'r' => Literal('\r', mode),
                'f' => Literal('\f', mode),
                'a' => Literal('\a', mode),
                'e' => Literal(0x1b, mode),
                'c' => ControlEscape(mode),
                >= '1' and <= '9' => NumericBackReference(escaped, mode),
                _ when char.IsAsciiLetter(escaped) => throw new ArgumentException(
                    "Illegal/unsupported escape sequence near index " + (index - 1)),
                _ => Literal(escaped, mode)
            };
        }

        private string Quoted(int mode)
        {
            var end = pattern.IndexOf("\\E", index, StringComparison.Ordinal);
            if (end < 0) end = pattern.Length;
            var value = pattern[index..end];
            index = end == pattern.Length ? end : end + 2;
            var result = new StringBuilder();
            foreach (var rune in value.EnumerateRunes()) result.Append(Literal(rune.Value, mode));
            return "(?:" + result + ")";
        }

        private string NamedBackReference(int mode)
        {
            if (index >= pattern.Length || pattern[index++] != '<')
                throw new ArgumentException("named capturing group is missing trailing '>'");
            var end = pattern.IndexOf('>', index);
            if (end < 0) throw new ArgumentException("named capturing group is missing trailing '>'");
            var name = pattern[index..end];
            index = end + 1;
            if (!namedGroups.TryGetValue(name, out var translated))
                throw new ArgumentException("named capturing group <" + name + "> does not exist");
            return CaseScope("\\k<" + translated + ">", mode);
        }

        private string NumericBackReference(char first, int mode)
        {
            var number = first - '0';
            while (index < pattern.Length && char.IsAsciiDigit(pattern[index]))
            {
                var candidate = checked(number * 10 + pattern[index] - '0');
                if (candidate >= groupNames.Count) break;
                number = candidate;
                index++;
            }
            if (number >= groupNames.Count) return "(?!)";
            return CaseScope("\\k<" + groupNames[number] + ">", mode);
        }

        private string Octal(int mode)
        {
            var value = 0;
            var digits = 0;
            while (digits < 3 && index < pattern.Length && pattern[index] is >= '0' and <= '7' &&
                   (digits < 2 || value <= 0x1f))
            {
                value = value * 8 + pattern[index++] - '0';
                digits++;
            }
            if (digits == 0) throw new ArgumentException("Illegal octal escape sequence near index " + index);
            return Literal(value, mode);
        }

        private string HexEscape(int mode)
        {
            if (index < pattern.Length && pattern[index] == '{')
            {
                var end = pattern.IndexOf('}', ++index);
                if (end < 0) throw new ArgumentException("Unclosed hexadecimal escape sequence near index " + index);
                var digits = pattern[index..end];
                index = end + 1;
                if (!int.TryParse(digits, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var codePoint) ||
                    codePoint > 0x10ffff)
                    throw new ArgumentException("Hexadecimal codepoint is too big near index " + index);
                return Literal(codePoint, mode);
            }
            return FixedHexEscape(2, mode);
        }

        private string FixedHexEscape(int digits, int mode)
        {
            if (index + digits > pattern.Length ||
                !int.TryParse(pattern.Substring(index, digits), NumberStyles.AllowHexSpecifier,
                              CultureInfo.InvariantCulture, out var value))
                throw new ArgumentException("Illegal hexadecimal escape sequence near index " + index);
            index += digits;
            return Literal(value, mode);
        }

        private string NamedCharacter(int mode)
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Illegal character name escape sequence near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character name escape sequence near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            return Literal(JavaRegexNamedCodePoint(name), mode);
        }

        private string ControlEscape(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Illegal control escape sequence near index " + index);
            return Literal(pattern[index++] ^ 64, mode);
        }

        private JavaCodePointSet ParseClass(int mode)
        {
            var negate = index < pattern.Length && pattern[index] == '^';
            if (negate) index++;
            var result = ParseClassUnion(mode, true);
            while (index + 1 < pattern.Length && pattern[index] == '&' && pattern[index + 1] == '&')
            {
                index += 2;
                result = result.Intersect(ParseClassUnion(mode, false));
            }
            if (index >= pattern.Length || pattern[index++] != ']')
                throw new ArgumentException("Unclosed character class near index " + index);
            return negate ? result.Complement() : result;
        }

        private JavaCodePointSet ParseClassUnion(int mode, bool first)
        {
            var result = JavaCodePointSet.Empty;
            var items = 0;
            while (index < pattern.Length)
            {
                SkipIgnored(mode);
                if (index >= pattern.Length) break;
                if (pattern[index] == ']' && !(first && items == 0)) break;
                if (index + 1 < pattern.Length && pattern[index] == '&' && pattern[index + 1] == '&') break;
                var item = ParseClassAtom(mode);
                if (index < pattern.Length && pattern[index] == '-' &&
                    index + 1 < pattern.Length && pattern[index + 1] != ']')
                {
                    index++;
                    var end = ParseClassAtom(mode);
                    if (!item.TrySingle(out var start) || !end.TrySingle(out var finish) || finish < start)
                        throw new ArgumentException("Illegal character range near index " + index);
                    item = JavaCodePointSet.Range(start, finish);
                }
                if ((mode & JavaRegexCaseInsensitive) != 0)
                    item = item.CaseFold((mode & JavaRegexUnicodeCase) != 0);
                result = result.Union(item);
                items++;
                first = false;
            }
            return result;
        }

        private JavaCodePointSet ParseClassAtom(int mode)
        {
            if (index >= pattern.Length) throw new ArgumentException("Unclosed character class near index " + index);
            var current = pattern[index++];
            if (current == '[') return ParseClass(mode);
            if (current != '\\')
            {
                var codePoint = ReadCodePoint(current);
                return JavaCodePointSet.Range(codePoint, codePoint);
            }
            if (index >= pattern.Length) throw new ArgumentException("Unclosed character class near index " + index);
            var escaped = pattern[index++];
            return escaped switch
            {
                'd' => PredefinedClass('d', mode),
                'D' => PredefinedClass('d', mode).Complement(),
                's' => PredefinedClass('s', mode),
                'S' => PredefinedClass('s', mode).Complement(),
                'w' => PredefinedClass('w', mode),
                'W' => PredefinedClass('w', mode).Complement(),
                'h' => PredefinedClass('h', mode),
                'H' => PredefinedClass('h', mode).Complement(),
                'v' => PredefinedClass('v', mode),
                'V' => PredefinedClass('v', mode).Complement(),
                'p' => ParseProperty(mode, false),
                'P' => ParseProperty(mode, true),
                'b' => JavaCodePointSet.Range('\b', '\b'),
                't' => JavaCodePointSet.Range('\t', '\t'),
                'n' => JavaCodePointSet.Range('\n', '\n'),
                'r' => JavaCodePointSet.Range('\r', '\r'),
                'f' => JavaCodePointSet.Range('\f', '\f'),
                'a' => JavaCodePointSet.Range('\a', '\a'),
                'e' => JavaCodePointSet.Range(0x1b, 0x1b),
                '0' => ParseClassOctalSet(),
                'x' => ParseClassHex(),
                'u' => ParseClassFixedHex(4),
                'N' => ParseClassNamedCharacter(),
                'Q' => ParseClassQuoted(),
                'c' => ParseClassControl(),
                _ when char.IsAsciiLetter(escaped) => throw new ArgumentException(
                    "Illegal/unsupported escape sequence near index " + (index - 1)),
                _ => JavaCodePointSet.Range(escaped, escaped)
            };
        }

        private int ParseClassOctal()
        {
            var value = 0;
            var digits = 0;
            while (digits < 3 && index < pattern.Length && pattern[index] is >= '0' and <= '7' &&
                   (digits < 2 || value <= 0x1f))
            {
                value = value * 8 + pattern[index++] - '0';
                digits++;
            }
            if (digits == 0) throw new ArgumentException("Illegal octal escape sequence near index " + index);
            return value;
        }

        private JavaCodePointSet ParseClassOctalSet()
        {
            var value = ParseClassOctal();
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassHex()
        {
            if (index < pattern.Length && pattern[index] == '{')
            {
                var end = pattern.IndexOf('}', ++index);
                if (end < 0) throw new ArgumentException("Unclosed hexadecimal escape sequence near index " + index);
                var digits = pattern[index..end];
                index = end + 1;
                if (!int.TryParse(digits, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var codePoint) ||
                    codePoint > 0x10ffff)
                    throw new ArgumentException("Hexadecimal codepoint is too big near index " + index);
                return JavaCodePointSet.Range(codePoint, codePoint);
            }
            return ParseClassFixedHex(2);
        }

        private JavaCodePointSet ParseClassFixedHex(int digits)
        {
            if (index + digits > pattern.Length ||
                !int.TryParse(pattern.Substring(index, digits), NumberStyles.AllowHexSpecifier,
                              CultureInfo.InvariantCulture, out var value))
                throw new ArgumentException("Illegal hexadecimal escape sequence near index " + index);
            index += digits;
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassControl()
        {
            if (index >= pattern.Length) throw new ArgumentException("Illegal control escape sequence near index " + index);
            var value = pattern[index++] ^ 64;
            return JavaCodePointSet.Range(value, value);
        }

        private JavaCodePointSet ParseClassNamedCharacter()
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Illegal character name escape sequence near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character name escape sequence near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            var codePoint = JavaRegexNamedCodePoint(name);
            return JavaCodePointSet.Range(codePoint, codePoint);
        }

        private JavaCodePointSet ParseClassQuoted()
        {
            var end = pattern.IndexOf("\\E", index, StringComparison.Ordinal);
            if (end < 0) end = pattern.Length;
            var result = JavaCodePointSet.Empty;
            while (index < end)
            {
                var codePoint = ReadCodePoint(pattern[index++]);
                result = result.Union(JavaCodePointSet.Range(codePoint, codePoint));
            }
            index = end == pattern.Length ? end : end + 2;
            return result;
        }

        private JavaCodePointSet ParseProperty(int mode, bool negate)
        {
            if (index >= pattern.Length || pattern[index++] != '{')
                throw new ArgumentException("Unknown character property name near index " + index);
            var end = pattern.IndexOf('}', index);
            if (end < 0) throw new ArgumentException("Unclosed character family near index " + index);
            var name = pattern[index..end];
            index = end + 1;
            var set = JavaRegexPropertySet(
                name,
                (mode & JavaRegexUnicodeCharacterClass) != 0,
                (mode & JavaRegexCaseInsensitive) != 0);
            return negate ? set.Complement() : set;
        }

        private static JavaCodePointSet PredefinedClass(char kind, int mode)
        {
            var unicode = (mode & JavaRegexUnicodeCharacterClass) != 0;
            return kind switch
            {
                'd' when unicode => JavaRegexPropertySet("Digit", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                'd' => JavaCodePointSet.Range('0', '9'),
                's' when unicode => JavaRegexPropertySet("Space", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                's' => JavaCodePointSet.Range('\t', '\r').Union(JavaCodePointSet.Range(' ', ' ')),
                'w' when unicode => JavaRegexPropertySet("Word", true,
                    (mode & JavaRegexCaseInsensitive) != 0),
                'w' => JavaCodePointSet.Range('0', '9').Union(JavaCodePointSet.Range('A', 'Z'))
                    .Union(JavaCodePointSet.Range('_', '_')).Union(JavaCodePointSet.Range('a', 'z')),
                'h' => JavaRegexPropertySets.GetOrAdd("fixed:h", _ => JavaCodePointSet.FromPredicate(codePoint =>
                    codePoint is '\t' or 0x20 or 0xa0 or 0x1680 or 0x180e or
                        >= 0x2000 and <= 0x200a or 0x202f or 0x205f or 0x3000)),
                'v' => JavaCodePointSet.Range('\n', '\r').Union(JavaCodePointSet.Range(0x85, 0x85))
                    .Union(JavaCodePointSet.Range(0x2028, 0x2029)),
                _ => throw new ArgumentException("Unknown predefined character class")
            };
        }

        internal static string Literal(int codePoint, int mode)
        {
            if ((mode & JavaRegexCanonEq) != 0 && codePoint is < 0xd800 or > 0xdfff)
            {
                var source = char.ConvertFromUtf32(codePoint);
                var normalized = source.Normalize(NormalizationForm.FormD);
                if (!string.Equals(source, normalized, StringComparison.Ordinal))
                {
                    var result = new StringBuilder();
                    foreach (var rune in normalized.EnumerateRunes())
                        result.Append(Literal(rune.Value, mode & ~JavaRegexCanonEq));
                    return "(?:" + result + ")";
                }
            }
            var literal = JavaCodePointSet.Range(codePoint, codePoint);
            if ((mode & JavaRegexCaseInsensitive) != 0)
                literal = literal.CaseFold((mode & JavaRegexUnicodeCase) != 0);
            return literal.ToRegex();
        }

        private static string CaseScope(string value, int mode) =>
            (mode & (JavaRegexCaseInsensitive | JavaRegexUnicodeCase)) ==
                (JavaRegexCaseInsensitive | JavaRegexUnicodeCase)
                ? "(?i:" + value + ")"
                : value;

        private static string Dot(int mode)
        {
            var excluded = (mode & JavaRegexDotAll) != 0
                ? JavaCodePointSet.Empty
                : (mode & JavaRegexUnixLines) != 0
                    ? JavaCodePointSet.Range('\n', '\n')
                    : JavaCodePointSet.Range('\n', '\r').Union(JavaCodePointSet.Range(0x85, 0x85))
                        .Union(JavaCodePointSet.Range(0x2028, 0x2029));
            return excluded.Complement().ToRegex();
        }

        private static string StartAnchor(int mode)
        {
            if ((mode & JavaRegexMultiline) == 0) return "\\A";
            return (mode & JavaRegexUnixLines) != 0
                ? "(?:\\A|(?<=\\n)(?!\\z))"
                : "(?:\\A|(?<=\\n)(?!\\z)|(?<=\\r)(?!\\n)(?!\\z)|(?<=[\\u0085\\u2028\\u2029])(?!\\z))";
        }

        private static string EndAnchor(int mode)
        {
            if ((mode & JavaRegexMultiline) == 0) return FinalTerminatorAnchor(mode);
            return (mode & JavaRegexUnixLines) != 0
                ? "(?=\\n|\\z)"
                : "(?=\\r\\n|\\r(?!\\n)|(?<!\\r)\\n|[\\u0085\\u2028\\u2029]|\\z)";
        }

        private static string FinalTerminatorAnchor(int mode) =>
            (mode & JavaRegexUnixLines) != 0
                ? "(?=\\n?\\z)"
                : "(?=\\r\\n\\z|[\\n\\r\\u0085\\u2028\\u2029]?\\z)";

        private static string WordBoundary(int mode, bool negate)
        {
            var word = PredefinedClass('w', mode).ToRegex();
            var boundary = "(?:(?<=" + word + ")(?!(?:" + word + "))|(?<!" + word + ")(?=" + word + "))";
            return negate ? "(?!(?:" + boundary + "))" : boundary;
        }

        private string GraphemeBoundary()
        {
            index += 3;
            return "(?:(?=\\A)|(?=\\z)|(?<![\\p{M}\\u200D])(?=[^\\p{M}]))";
        }
    }

    private static string TranslateJavaRegex(string pattern)
    {
        var translator = new JavaRegexTranslator(pattern);
        return translator.Translate(0);
    }

    private static string JavaRegexSyntaxMessage(string pattern, ArgumentException error)
    {
        if (pattern.StartsWith('*') || pattern.StartsWith('+') || pattern.StartsWith('?'))
            return $"Dangling meta character '{pattern[0]}' near index 0\n{pattern}\n^";

        var depth = 0;
        var escaped = false;
        foreach (var current in pattern)
        {
            if (escaped)
            {
                escaped = false;
                continue;
            }
            if (current == '\\')
            {
                escaped = true;
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')' && depth > 0) depth--;
        }
        if (depth > 0) return $"Unclosed group near index {pattern.Length}\n{pattern}";
        return error.Message;
    }

    private static Regex CompileRegexCore(string pattern, int flags)
    {
        if ((flags & ~JavaRegexAllFlags) != 0)
            throw new ArgumentException("Unknown flag 0x" + (flags & ~JavaRegexAllFlags).ToString("x", CultureInfo.InvariantCulture));
        try
        {
            var effectiveFlags = (flags & JavaRegexUnicodeCharacterClass) != 0
                ? flags | JavaRegexUnicodeCase
                : flags;
            string translated;
            string[] groupNames;
            IReadOnlyDictionary<string, string> namedGroups;
            if ((flags & JavaRegexLiteral) != 0)
            {
                var literal = new StringBuilder(pattern.Length);
                for (var index = 0; index < pattern.Length; index++)
                {
                    var codePoint = char.IsHighSurrogate(pattern[index]) && index + 1 < pattern.Length &&
                        char.IsLowSurrogate(pattern[index + 1])
                        ? char.ConvertToUtf32(pattern[index], pattern[++index])
                        : pattern[index];
                    literal.Append(JavaRegexTranslator.Literal(codePoint, effectiveFlags));
                }
                translated = literal.ToString();
                groupNames = new[] { string.Empty };
                namedGroups = new Dictionary<string, string>();
            }
            else
            {
                var translator = new JavaRegexTranslator(pattern);
                translated = translator.Translate(effectiveFlags);
                groupNames = translator.GroupNames;
                namedGroups = translator.NamedGroups;
            }
            var options = RegexOptions.CultureInvariant;
            var result = new JavaRegex(pattern, translated, options, effectiveFlags, groupNames, namedGroups);
            _ = OriginalRegexPatterns.GetValue(result, _ => new JavaUriText(pattern));
            return result;
        }
        catch (ArgumentException error)
        {
            throw new ArgumentException(JavaRegexSyntaxMessage(pattern, error), error);
        }
    }

    internal static Regex CompileRegex(string pattern) => CompileRegexCore(pattern, 0);
    internal static Regex CompileRegex(string pattern, int flags) => CompileRegexCore(pattern, flags);
    internal static Regex CompileLiteralRegex(string pattern) =>
        CompileRegexCore(pattern, JavaRegexLiteral | JavaRegexUnicodeCase);
    internal static string RegexPattern(Regex pattern) =>
        OriginalRegexPatterns.TryGetValue(pattern, out var original)
            ? original.Value
            : pattern.ToString();
    internal static int RegexFlags(Regex pattern) => pattern is JavaRegex javaRegex ? javaRegex.Flags : 0;
    internal static int RegexGroupCount(Regex pattern) => pattern is JavaRegex javaRegex
        ? javaRegex.GroupNames.Length - 1
        : pattern.GetGroupNumbers().Length - 1;
    internal static string RegexGroupName(Regex pattern, int group)
    {
        if (pattern is not JavaRegex javaRegex) return group.ToString(CultureInfo.InvariantCulture);
        if (group < 0 || group >= javaRegex.GroupNames.Length)
            throw new ArgumentOutOfRangeException(nameof(group), "No group " + group);
        return group == 0 ? "0" : javaRegex.GroupNames[group];
    }
    internal static string RegexGroupName(Regex pattern, string group)
    {
        if (pattern is not JavaRegex javaRegex) return group;
        return javaRegex.NamedGroups.TryGetValue(group, out var translated)
            ? translated
            : throw new ArgumentException("No group with name <" + group + ">");
    }
    internal static string QuoteRegex(string value) => "\\Q" + value.Replace("\\E", "\\E\\\\E\\Q", StringComparison.Ordinal) + "\\E";
    internal static JavaRegexMatcher RegexMatcher(Regex pattern, string input) => new(pattern, input);
    internal static string[] RegexSplit(Regex pattern, string input, int limit)
    {
        var result = new List<string>();
        var matcher = new JavaRegexMatcher(pattern, input);
        var start = 0;
        var matched = false;
        while ((limit <= 0 || result.Count < limit - 1) && matcher.Find())
        {
            var matchStart = matcher.Start();
            var matchEnd = matcher.End();
            if (matchStart == 0 && matchEnd == 0) continue;
            matched = true;
            result.Add(input.Substring(start, matchStart - start));
            start = matchEnd;
        }
        if (!matched) return new[] { input };
        result.Add(input.Substring(start));
        if (limit == 0)
        {
            while (result.Count != 0 && result[^1].Length == 0) result.RemoveAt(result.Count - 1);
        }
        return result.ToArray();
    }
    internal static string QuoteReplacement(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("$", "\\$", StringComparison.Ordinal);
    internal static string Encode(string value, Encoding encoding) => Uri.EscapeDataString(value);

    internal static ReadOnlyCollection<T> ListOf<T>(params T[] values) => new(values);

    internal static IList<T> AsList<T>(params T[] values) => new JavaArrayList<T>(values);

    internal static HashSet<T> SetOf<T>(params T[] values) =>
        new(values, new JavaEqualityComparer<T>());
    internal static HashSet<T> SetOfValues<T>(IEnumerable<T> values) =>
        new(values, new JavaEqualityComparer<T>());
    internal static ISet<T> EnumSetNoneOf<T>(Type _) => new HashSet<T>();
    internal static ISet<T> EnumSetAllOf<T>(Type type) =>
        new HashSet<T>(type.GetFields(BindingFlags.Public | BindingFlags.Static)
            .Where(field => type.IsAssignableFrom(field.FieldType))
            .Select(field => (T)field.GetValue(null)!));
    internal static ISet<T> EnumSetOf<T>(T value) => new HashSet<T> { value };
    internal static ISet<T> EnumSetCopyOf<T>(IEnumerable<T> values) => new HashSet<T>(values);

    internal static ReadOnlyCollection<T> UnmodifiableList<T>(IEnumerable<T> values) =>
        new(values is IList<T> list ? list : values.ToList());

    internal static IDictionary<K, V> UnmodifiableMap<K, V>(IDictionary<K, V> values)
        where K : notnull => new ReadOnlyDictionary<K, V>(values);
    internal static IDictionary<K, V> EmptyMap<K, V>() where K : notnull =>
        new ReadOnlyDictionary<K, V>(new Dictionary<K, V>());

    internal static IList<T> SubList<T>(IEnumerable<T> values, int fromIndex, int toIndex) =>
        new JavaSubList<T>(values is IList<T> list ? list : values.ToList(), fromIndex, toIndex);
    // Java generic casts may legally carry null even when the declaration is
    // not annotated. Keep that runtime behavior while presenting the helper's
    // declared result as the Java target type; generated nullable APIs still
    // surface their own explicit `?` contract.
    internal static IList<T> CastList<T>(object? values) => values is null
        ? null!
        : ((IEnumerable)values).Cast<object?>().Select(value => (T)value!).ToList();
    internal static IDictionary<TKey, TValue> CastDictionary<TKey, TValue>(object? values)
        where TKey : notnull
    {
        if (values is null) return null!;
        if (values is IDictionary<TKey, TValue> typed) return typed;
        var result = new Dictionary<TKey, TValue>();
        foreach (var entry in (IEnumerable)values)
        {
            var type = entry!.GetType();
            var key = type.GetProperty("Key")!.GetValue(entry);
            var value = type.GetProperty("Value")!.GetValue(entry);
            result.Add((TKey)ConvertCastValue(typeof(TKey), key)!,
                (TValue)ConvertCastValue(typeof(TValue), value)!);
        }
        return result;
    }
    private static object? ConvertCastValue(Type targetType, object? value)
    {
        if (value is null || targetType.IsInstanceOfType(value)) return value;
        if (!targetType.IsGenericType) return value;
        var definition = targetType.GetGenericTypeDefinition();
        var methodName = definition == typeof(IDictionary<,>)
            ? nameof(CastDictionary)
            : definition == typeof(IList<>) ? nameof(CastList) : null;
        if (methodName is null) return value;
        var method = typeof(JavaCompat).GetMethods(BindingFlags.Static | BindingFlags.NonPublic)
            .Single(candidate => candidate.Name == methodName && candidate.IsGenericMethodDefinition);
        return method.MakeGenericMethod(targetType.GetGenericArguments()).Invoke(null, new[] { value });
    }
    internal static IDictionary<TKey, TValue> NewJavaDictionary<TKey, TValue>(params object?[] arguments)
        where TKey : notnull
    {
        var comparer = new JavaEqualityComparer<TKey>();
        if (arguments.Length == 0) return new Dictionary<TKey, TValue>(comparer);
        if (arguments.Length == 1 && arguments[0] is int capacity)
            return new Dictionary<TKey, TValue>(capacity, comparer);
        if (arguments.Length == 1 && arguments[0] is IEnumerable<KeyValuePair<TKey, TValue>> values)
            return new Dictionary<TKey, TValue>(values, comparer);
        throw new ArgumentException("Unsupported Java HashMap constructor arguments.");
    }

    internal static SortedDictionary<TKey, TValue> NewSortedDictionary<TKey, TValue>()
        where TKey : notnull
    {
        return new SortedDictionary<TKey, TValue>(Comparer<TKey>.Create(JavaCompare));
    }

    internal static SortedSet<T> NewSortedSet<T>() =>
        new(Comparer<T>.Create(JavaCompare));

    private static int JavaCompare<T>(T? left, T? right)
    {
        if (ReferenceEquals(left, right)) return 0;
        if (left is null) return -1;
        if (right is null) return 1;
        if (left is Uri leftUri && right is Uri rightUri)
            return string.Compare(leftUri.OriginalString, rightUri.OriginalString,
                StringComparison.Ordinal);
        if (left is string leftString && right is string rightString)
            return string.Compare(leftString, rightString, StringComparison.Ordinal);
        if (left is IComparable<T> generic) return generic.CompareTo(right);
        if (left is IComparable comparable) return comparable.CompareTo(right);
        var method = left.GetType().GetMethod("CompareTo", new[] { right.GetType() });
        if (method is not null) return (int)method.Invoke(left, new object?[] { right })!;
        throw new ArgumentException($"{left.GetType()} does not implement Java Comparable semantics.");
    }

    private sealed class JavaEqualityComparer<T> : IEqualityComparer<T>
    {
        public bool Equals(T? left, T? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(T value) => JavaHashCode(value);
    }

    internal static T[] CopyOf<T>(T[] source, int length)
    {
        var result = new T[length];
        Array.Copy(source, result, Math.Min(source.Length, length));
        return result;
    }
    internal static T[] CopyOfRange<T>(T[] source, int fromIndex, int toIndex) => source[fromIndex..toIndex];
    internal static void Fill<T>(T[] values, T value) => Array.Fill(values, value);
    internal static void Fill<T>(T[] values, int fromIndex, int toIndex, T value) =>
        Array.Fill(values, value, fromIndex, toIndex - fromIndex);
    internal static T[] EmptyArray<T>() => Array.Empty<T>();
    internal static IEnumerator<T> EmptyIterator<T>() => Enumerable.Empty<T>().GetEnumerator();
    internal static string ArrayString(Array value) => string.Join(", ", value.Cast<object?>().Select(StringValueOf));
    internal static string ArrayString<T>(T[] value) => ArrayString((Array)value);
    internal static string IndentSpace(int count) => new(' ', Math.Max(0, count));
    internal static T[] InsertIntoArrayAt<T>(T value, T[] source, int index, Type? _) =>
        SpliceIntoArrayAt(value, source, index, null);
    internal static T[] SpliceIntoArrayAt<T>(T value, T[] source, int index, Type? _)
    {
        var result = new T[source.Length + 1];
        Array.Copy(source, 0, result, 0, index);
        result[index] = value;
        Array.Copy(source, index, result, index + 1, source.Length - index);
        return result;
    }
    internal static T[] SpliceIntoArrayAt<T>(T[] values, T[] source, int index, Type? _)
    {
        var result = new T[source.Length + values.Length];
        Array.Copy(source, 0, result, 0, index);
        Array.Copy(values, 0, result, index, values.Length);
        Array.Copy(source, index, result, index + values.Length, source.Length - index);
        return result;
    }
    internal static T[] ReplaceInArrayAt<T>(T value, T[] source, int index, Type? _)
    {
        var result = (T[])source.Clone();
        result[index] = value;
        return result;
    }

    internal static int ListCount<T>(IEnumerable<T> values) => values.Count();

    internal static bool ListIsEmpty<T>(IEnumerable<T> values) => !values.Any();

    internal static T ListGet<T>(IEnumerable<T> values, int index) =>
        values is IList<T> list ? list[index] : values.ElementAt(index);

    internal static bool ListAddAll<T>(IList<T> values, int index, IEnumerable<T> added)
    {
        var changed = false;
        foreach (var value in added) { values.Insert(index++, value); changed = true; }
        return changed;
    }

    internal static T ListSet<T>(IList<T> values, int index, T value)
    {
        var previous = values[index];
        values[index] = value;
        return previous;
    }

    internal static void ListAdd<T>(IList<T> values, int index, T value) => values.Insert(index, value);
    internal static T ListRemove<T>(IList<T> values, int index)
    {
        var previous = values[index];
        values.RemoveAt(index);
        return previous;
    }
    internal static int ListLastIndexOf<T>(IList<T> values, object? value)
    {
        for (var index = values.Count - 1; index >= 0; index--)
            if (Equals(values[index], value)) return index;
        return -1;
    }
    internal static IEnumerator<T> ReverseIterator<T>(IEnumerable<T> values, int index) =>
        values.Take(index).Reverse().GetEnumerator();

    internal static bool IteratorHasNext(IEnumerator iterator)
    {
        var state = IteratorStates.GetValue(iterator, _ => new IteratorState());
        if (!state.Prepared && !state.Exhausted)
        {
            state.Prepared = iterator.MoveNext();
            state.Exhausted = !state.Prepared;
        }
        return state.Prepared;
    }

    internal static T IteratorNext<T>(IEnumerator<T> iterator)
    {
        var state = IteratorStates.GetValue(iterator, _ => new IteratorState());
        if (!state.Prepared)
        {
            if (state.Exhausted || !iterator.MoveNext())
            {
                state.Exhausted = true;
                throw new InvalidOperationException("Iterator is exhausted");
            }
        }
        state.Prepared = false;
        if (iterator is JavaRemovableIterator removable) removable.MarkReturned();
        return iterator.Current;
    }

    internal static long IteratorNextLong(IEnumerator<long> iterator) => IteratorNext(iterator);
    internal static void IteratorRemove(IEnumerator iterator)
    {
        if (iterator is not JavaRemovableIterator removable)
            throw new NotSupportedException("This translated Java iterator does not support remove().");
        removable.Remove();
    }

    internal static T DequeGetFirst<T>(JavaDeque<T> deque) => deque.GetFirst();
    internal static T? DequePeek<T>(JavaDeque<T> deque) => deque.Peek();
    internal static T DequePop<T>(JavaDeque<T> deque) => deque.Pop();
    internal static void DequePush<T>(JavaDeque<T> deque, T value) => deque.Push(value);

    internal new static bool Equals(object? left, object? right)
    {
        if (left is JavaReadOnlyAdapter leftAdapter) left = leftAdapter.MutableSource;
        if (right is JavaReadOnlyAdapter rightAdapter) right = rightAdapter.MutableSource;
        if (ReferenceEquals(left, right)) return true;
        if (left is null || right is null) return false;
        if (left is Uri leftUri)
            return right is Uri rightUri && UriEquals(leftUri, rightUri);
        if (IsJavaList(left)) return IsJavaList(right) && ListsEqual((IEnumerable)left, (IEnumerable)right);
        if (IsJavaSet(left)) return IsJavaSet(right) && SetsEqual((IEnumerable)left, (IEnumerable)right);
        if (left is IDictionary leftMap)
            return right is IDictionary rightMap && MapsEqual(leftMap, rightMap);
        return left.Equals(right);
    }

    internal static bool DeepEquals(object? left, object? right)
    {
        if (ReferenceEquals(left, right)) return true;
        if (left is not Array leftArray || right is not Array rightArray)
            return left is not Array && right is not Array && Equals(left, right);
        if (leftArray.Rank != 1 || rightArray.Rank != 1) return false;
        if (leftArray.Length != rightArray.Length) return false;
        var leftElement = leftArray.GetType().GetElementType()!;
        var rightElement = rightArray.GetType().GetElementType()!;
        var primitiveElements = leftElement.IsValueType || rightElement.IsValueType;
        if (primitiveElements && leftElement != rightElement) return false;
        for (var index = 0; index < leftArray.Length; index++)
        {
            var equal = primitiveElements
                ? object.Equals(leftArray.GetValue(index), rightArray.GetValue(index))
                : DeepEquals(leftArray.GetValue(index), rightArray.GetValue(index));
            if (!equal) return false;
        }
        return true;
    }

    internal static int Hash(params object?[] values)
    {
        unchecked
        {
            var result = 1;
            foreach (var value in values) result = 31 * result + JavaHashCode(value);
            return result;
        }
    }

    internal static int ArrayHash(Array values)
    {
        unchecked
        {
            var result = 1;
            foreach (var value in values) result = 31 * result + JavaHashCode(value);
            return result;
        }
    }

    private static bool IsJavaList(object value) =>
        value is not Array && value is IEnumerable &&
        (value is IList || value.GetType().GetInterfaces().Any(type =>
            type.IsGenericType &&
            (type.GetGenericTypeDefinition() == typeof(IList<>) ||
             type.GetGenericTypeDefinition() == typeof(IReadOnlyList<>))));

    private static bool IsJavaSet(object value) =>
        value is IEnumerable && value.GetType().GetInterfaces().Any(type =>
            type.IsGenericType &&
            (type.GetGenericTypeDefinition() == typeof(ISet<>) ||
             type.GetGenericTypeDefinition() == typeof(IReadOnlySet<>)));

    internal static bool IsSet(object? value) => value is not null && IsJavaSet(value);

    private static bool ListsEqual(IEnumerable left, IEnumerable right)
    {
        var leftEnumerator = left.GetEnumerator();
        var rightEnumerator = right.GetEnumerator();
        try
        {
            while (true)
            {
                var leftHasValue = leftEnumerator.MoveNext();
                var rightHasValue = rightEnumerator.MoveNext();
                if (leftHasValue != rightHasValue) return false;
                if (!leftHasValue) return true;
                if (!Equals(leftEnumerator.Current, rightEnumerator.Current)) return false;
            }
        }
        finally
        {
            (leftEnumerator as IDisposable)?.Dispose();
            (rightEnumerator as IDisposable)?.Dispose();
        }
    }

    private static bool SetsEqual(IEnumerable left, IEnumerable right)
    {
        var leftValues = left.Cast<object?>().ToList();
        var rightValues = right.Cast<object?>().ToList();
        return leftValues.Count == rightValues.Count &&
               leftValues.All(leftValue => rightValues.Any(rightValue => Equals(leftValue, rightValue)));
    }

    private static bool MapsEqual(IDictionary left, IDictionary right)
    {
        if (left.Count != right.Count) return false;
        foreach (DictionaryEntry entry in left)
            if (!right.Contains(entry.Key) || !Equals(entry.Value, right[entry.Key])) return false;
        return true;
    }

    private static int JavaHashCode(object? value)
    {
        if (value is JavaReadOnlyAdapter adapter) value = adapter.MutableSource;
        if (value is null) return 0;
        if (value is Uri uri)
        {
            var schemeHash = StringComparer.OrdinalIgnoreCase.GetHashCode(UriScheme(uri) ?? "");
            var fragmentHash = UriEscapedHashCode(UriRawFragment(uri));
            if (UriIsOpaque(uri))
                return System.HashCode.Combine(
                    schemeHash,
                    UriEscapedHashCode(UriRawSchemeSpecificPart(uri)),
                    fragmentHash);
            var host = UriHost(uri);
            var authorityHash = host is null
                ? UriEscapedHashCode(UriRawAuthority(uri))
                : System.HashCode.Combine(
                    UriEscapedHashCode(UriRawUserInfo(uri)),
                    StringComparer.OrdinalIgnoreCase.GetHashCode(host),
                    UriPort(uri));
            return System.HashCode.Combine(
                schemeHash,
                authorityHash,
                UriEscapedHashCode(UriRawPath(uri)),
                UriEscapedHashCode(UriRawQuery(uri)),
                fragmentHash);
        }
        if (value is IDictionary map)
        {
            var result = 0;
            foreach (DictionaryEntry entry in map)
                result += JavaHashCode(entry.Key) ^ JavaHashCode(entry.Value);
            return result;
        }
        if (IsJavaSet(value))
        {
            unchecked
            {
                var result = 0;
                foreach (var element in (IEnumerable)value) result += JavaHashCode(element);
                return result;
            }
        }
        if (!IsJavaList(value)) return value.GetHashCode();
        unchecked
        {
            var result = 1;
            foreach (var element in (IEnumerable)value) result = 31 * result + JavaHashCode(element);
            return result;
        }
    }

    private static string? NormalizeUriEscapes(string? value)
    {
        if (value is null) return null;
        StringBuilder? normalized = null;
        for (var index = 0; index + 2 < value.Length; index++)
        {
            if (value[index] != '%' || !Uri.IsHexDigit(value[index + 1]) ||
                !Uri.IsHexDigit(value[index + 2]))
                continue;
            var upperFirst = char.ToUpperInvariant(value[index + 1]);
            var upperSecond = char.ToUpperInvariant(value[index + 2]);
            if (upperFirst == value[index + 1] && upperSecond == value[index + 2])
            {
                index += 2;
                continue;
            }
            normalized ??= new StringBuilder(value);
            normalized[index + 1] = upperFirst;
            normalized[index + 2] = upperSecond;
            index += 2;
        }
        return normalized?.ToString() ?? value;
    }

    private static bool UriEscapedEquals(string? left, string? right) =>
        string.Equals(NormalizeUriEscapes(left), NormalizeUriEscapes(right),
                      StringComparison.Ordinal);

    private static int UriEscapedHashCode(string? value) =>
        StringComparer.Ordinal.GetHashCode(NormalizeUriEscapes(value) ?? "");

    private static bool UriEquals(Uri left, Uri right)
    {
        if (!string.Equals(UriScheme(left), UriScheme(right), StringComparison.OrdinalIgnoreCase) ||
            !UriEscapedEquals(UriRawFragment(left), UriRawFragment(right)))
            return false;
        var leftOpaque = UriIsOpaque(left);
        var rightOpaque = UriIsOpaque(right);
        if (leftOpaque || rightOpaque)
            return leftOpaque && rightOpaque &&
                   UriEscapedEquals(UriRawSchemeSpecificPart(left),
                                    UriRawSchemeSpecificPart(right));
        if (!UriEscapedEquals(UriRawPath(left), UriRawPath(right)) ||
            !UriEscapedEquals(UriRawQuery(left), UriRawQuery(right)))
            return false;
        var leftHost = UriHost(left);
        var rightHost = UriHost(right);
        if (leftHost is null || rightHost is null)
            return leftHost is null && rightHost is null &&
                   UriEscapedEquals(UriRawAuthority(left), UriRawAuthority(right));
        return string.Equals(leftHost, rightHost, StringComparison.OrdinalIgnoreCase) &&
               UriPort(left) == UriPort(right) &&
               UriEscapedEquals(UriRawUserInfo(left), UriRawUserInfo(right));
    }

    internal static JavaResourceBundle GetResourceBundle(string baseName, CultureInfo locale) => new(baseName, locale);

    internal static string GetResourceString(JavaResourceBundle bundle, string name) => bundle.GetString(name);

    internal static JavaCollector Joining(string delimiter) =>
        new(values => string.Join(delimiter, values.Select(JavaString)));

    internal static bool All(IEnumerable<int> values, Predicate<int> predicate) => values.All(value => predicate(value));

    internal static IEnumerable<T> Skip<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));

    internal static dynamic Collect<T>(IEnumerable<T> values, JavaCollector collector) => collector.Collect(values.Cast<object?>());

    internal static JavaCollector ToMap<T, K, V>(Func<T, K> keySelector, Func<T, V> valueSelector)
        where K : notnull =>
        new(values => values.Cast<T>().ToDictionary(keySelector, valueSelector));

    internal static IEnumerable<TResult> Map<T, TResult>(IEnumerable<T> values, Func<T, TResult> mapper) => values.Select(mapper);
    internal static IEnumerable<long> MapToLong<T>(IEnumerable<T> values, JavaToLongFunction<T> mapper) =>
        values.Select(value => mapper(value));
    internal static long Sum(IEnumerable<long> values) => values.Sum();
    internal static decimal DecimalDivide(decimal left, decimal right, int scale, object rounding)
    {
        var rounded = decimal.Round(
            left / right,
            scale,
            string.Equals(rounding.ToString(), "DOWN", StringComparison.Ordinal)
                ? MidpointRounding.ToZero
                : MidpointRounding.ToEven);
        // BigDecimal.toString() retains the requested division scale. Reparse a fixed-point
        // representation so System.Decimal carries the same scale in its value bits.
        return decimal.Parse(
            rounded.ToString("F" + scale, System.Globalization.CultureInfo.InvariantCulture),
            System.Globalization.NumberStyles.Number,
            System.Globalization.CultureInfo.InvariantCulture);
    }
    internal static IEnumerable<T> Filter<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Where(predicate);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values) =>
        values.OrderBy(value => value, Comparer<T>.Create(JavaCompare));
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, IComparer<T> comparer) => values.OrderBy(value => value, comparer);
    internal static IEnumerable<T> Sorted<T>(IEnumerable<T> values, Comparison<T> comparison) =>
        values.OrderBy(value => value, Comparer<T>.Create(comparison));
    internal static Comparison<T> NaturalOrder<T>() => JavaCompare;
    internal static Comparison<T> ComparingInt<T>(Func<T, int> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> Comparing<T>(Func<T, IComparable> selector) =>
        (left, right) => selector(left).CompareTo(selector(right));
    internal static Comparison<T> ThenComparing<T>(Comparison<T> first, Comparison<T> second) =>
        (left, right) => { var result = first(left, right); return result != 0 ? result : second(left, right); };
    internal static Comparison<T> ReverseComparison<T>(Comparison<T> comparison) =>
        (left, right) => comparison(right, left);
    internal static T[] ToArray<T>(IEnumerable<T> values) => values.ToArray();
    internal static T[] ToArrayLoose<T>(System.Collections.IEnumerable values) =>
        values.Cast<object?>().Select(value => (T)value!).ToArray();
    internal static IList<T> ToListValues<T>(IEnumerable<T> values) => values.ToList();
    internal static IReadOnlyList<T> ToReadOnlyList<T>(IEnumerable<T> values) =>
        values as IReadOnlyList<T> ?? values.ToList();
    internal static IReadOnlyCollection<T> ToReadOnlyCollection<T>(IEnumerable<T> values) =>
        values as IReadOnlyCollection<T> ?? values.ToList();
    internal static IReadOnlySet<T> ToReadOnlySet<T>(IEnumerable<T> values) =>
        values as IReadOnlySet<T> ?? values.ToHashSet();
    internal static IReadOnlyDictionary<K, V> ToReadOnlyDictionary<K, V>(
        IEnumerable<KeyValuePair<K, V>> values) where K : notnull =>
        values as IReadOnlyDictionary<K, V> ??
        values.ToDictionary(entry => entry.Key, entry => entry.Value);
    internal static T ToReadOnly<T>(object? value) =>
        (T)ToReadOnlyValue(typeof(T), value)!;
    private static object? ToReadOnlyValue(Type targetType, object? value)
    {
        if (value is null || !targetType.IsGenericType) return value;
        if (value is JavaReadOnlyAdapter && targetType.IsInstanceOfType(value)) return value;

        var definition = targetType.GetGenericTypeDefinition();
        var arguments = targetType.GetGenericArguments();
        if (definition == typeof(IReadOnlyList<>) ||
            definition == typeof(IReadOnlyCollection<>))
        {
            var mutableType = typeof(IList<>).MakeGenericType(arguments[0]);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                var transformed = (IList)Activator.CreateInstance(
                    typeof(List<>).MakeGenericType(arguments[0]))!;
                foreach (var item in (IEnumerable)value)
                    transformed.Add(ToReadOnlyValue(arguments[0], item));
                result = transformed;
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlyList<>).MakeGenericType(arguments[0]), result)!);
        }

        if (definition == typeof(IReadOnlySet<>))
        {
            var mutableType = typeof(ISet<>).MakeGenericType(arguments[0]);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                result = Activator.CreateInstance(typeof(HashSet<>).MakeGenericType(arguments[0]))!;
                var add = result.GetType().GetMethod("Add", arguments)!;
                foreach (var item in (IEnumerable)value)
                    add.Invoke(result, new[] { ToReadOnlyValue(arguments[0], item) });
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlySet<>).MakeGenericType(arguments[0]), result)!);
        }

        if (definition == typeof(IReadOnlyDictionary<,>))
        {
            var mutableType = typeof(IDictionary<,>).MakeGenericType(arguments);
            object result = value;
            if (!mutableType.IsInstanceOfType(value))
            {
                result = Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(arguments))!;
                var add = result.GetType().GetMethod("Add", arguments)!;
                foreach (var entry in (IEnumerable)value)
                {
                    var entryType = entry.GetType();
                    var key = entryType.GetProperty("Key")!.GetValue(entry);
                    var item = entryType.GetProperty("Value")!.GetValue(entry);
                    add.Invoke(result, new[]
                    {
                        ToReadOnlyValue(arguments[0], key),
                        ToReadOnlyValue(arguments[1], item)
                    });
                }
            }
            return ReadOnlyAdapter(targetType, value, () => Activator.CreateInstance(
                typeof(JavaReadOnlyDictionary<,>).MakeGenericType(arguments), result)!);
        }

        return value;
    }
    internal static T ToMutable<T>(object? value) =>
        (T)ToMutableValue(typeof(T), value)!;
    private static object? ToMutableValue(Type targetType, object? value)
    {
        if (value is JavaReadOnlyAdapter adapter &&
            targetType.IsInstanceOfType(adapter.MutableSource))
            return adapter.MutableSource;
        // Arrays satisfy IList<T> in .NET but Java arrays are not Lists: retaining one here
        // breaks Java List structural equality and hashing for public read-only inputs.
        if (value is null || (value is not Array && targetType.IsInstanceOfType(value))) return value;
        if (!targetType.IsGenericType) return value;

        var definition = targetType.GetGenericTypeDefinition();
        var arguments = targetType.GetGenericArguments();
        if (definition == typeof(IList<>) || definition == typeof(ICollection<>))
        {
            var result = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(arguments[0]))!;
            foreach (var item in (IEnumerable)value)
                result.Add(ToMutableValue(arguments[0], item));
            return result;
        }

        if (definition == typeof(ISet<>))
        {
            var result = Activator.CreateInstance(typeof(HashSet<>).MakeGenericType(arguments[0]))!;
            var add = result.GetType().GetMethod("Add", arguments)!;
            foreach (var item in (IEnumerable)value)
                add.Invoke(result, new[] { ToMutableValue(arguments[0], item) });
            return result;
        }

        if (definition == typeof(IDictionary<,>))
        {
            var result = Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(arguments))!;
            var add = result.GetType().GetMethod("Add", arguments)!;
            foreach (var entry in (IEnumerable)value)
            {
                var entryType = entry.GetType();
                var key = entryType.GetProperty("Key")!.GetValue(entry);
                var item = entryType.GetProperty("Value")!.GetValue(entry);
                add.Invoke(result, new[]
                {
                    ToMutableValue(arguments[0], key),
                    ToMutableValue(arguments[1], item)
                });
            }
            return result;
        }

        return value;
    }
    internal static IDictionary<K, V> ToDictionaryValues<K, V>(
        IEnumerable<KeyValuePair<K, V>> values) where K : notnull =>
        values.ToDictionary(entry => entry.Key, entry => entry.Value);

    internal static byte[] ToUnsignedBytes(sbyte[] values) =>
        values.Select(value => unchecked((byte)value)).ToArray();
    internal static byte[] ToUnsignedBytes(byte[] values) => values;
    internal static sbyte[] ToSignedBytes(byte[] values) =>
        values.Select(value => unchecked((sbyte)value)).ToArray();
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
    internal static void RegisterSocketStream(System.Net.Sockets.Socket socket, Stream stream) =>
        SocketStreams.Add(socket, stream);
    internal static System.Net.IPAddress InetSocketAddressAddress(
        System.Net.IPEndPoint endpoint) => endpoint.Address;
    internal static Stream SocketStream(System.Net.Sockets.Socket socket) =>
        SocketStreams.TryGetValue(socket, out var stream)
            ? stream
            : new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
    internal static bool SocketIsClosed(System.Net.Sockets.Socket socket) =>
        socket.SafeHandle.IsClosed;
    internal static bool SocketIsConnected(System.Net.Sockets.Socket socket) => socket.Connected;
    internal static void SocketSetSoTimeout(System.Net.Sockets.Socket socket, int timeout) =>
        socket.ReceiveTimeout = timeout;
    internal static void ForEach<T>(IEnumerable<T> values, Action<T> action)
    {
        foreach (var value in values) action(value);
    }
    internal static IEnumerable<T> StreamOf<T>(params T[] values) => values;
    internal static IEnumerable<R> FlatMap<T, R>(IEnumerable<T> values,
        Func<T, IEnumerable<R>> mapper) => values.SelectMany(mapper);
    internal static void ForEach<K, V>(IDictionary<K, V> values, Action<K, V> action) where K : notnull
    {
        foreach (var entry in values) action(entry.Key, entry.Value);
    }
    internal static void ForEach<K, V>(IReadOnlyDictionary<K, V> values, Action<K, V> action) where K : notnull
    {
        foreach (var entry in values) action(entry.Key, entry.Value);
    }
    internal static T? FirstOrDefault<T>(IEnumerable<T> values) => values.FirstOrDefault();
    internal static bool Any<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.Any(predicate);
    internal static bool AllValues<T>(IEnumerable<T> values, Func<T, bool> predicate) => values.All(predicate);
    internal static IEnumerable<T> ConcatValues<T>(IEnumerable<T> left, IEnumerable<T> right) => left.Concat(right);
    internal static IEnumerable<T> TakeValues<T>(IEnumerable<T> values, long count) => values.Take(checked((int)count));
    internal static IEnumerable<T> DropValues<T>(IEnumerable<T> values, long count) => values.Skip(checked((int)count));
    internal static JavaOptional<int> MaxOptional(IEnumerable<int> values) =>
        values.Any() ? JavaOptional<int>.Of(values.Max()) : JavaOptional<int>.Empty();
    internal static JavaOptional<T> ReduceOptional<T>(IEnumerable<T> values, Func<T, T, T> reducer)
    {
        using var iterator = values.GetEnumerator();
        if (!iterator.MoveNext()) return JavaOptional<T>.Empty();
        var result = iterator.Current;
        while (iterator.MoveNext()) result = reducer(result, iterator.Current);
        return JavaOptional<T>.Of(result);
    }
    internal static Func<T, T> AndThen<T>(Func<T, T> first, Func<T, T> second) => value => second(first(value));
    internal static void ForEachRemaining<T>(IEnumerator<T> iterator, Action<T> action)
    {
        while (iterator.MoveNext()) action(iterator.Current);
    }

    internal static IEnumerable<T> LoadServices<T>(Type serviceType, params object?[] ignored) =>
        AppDomain.CurrentDomain.GetAssemblies()
            .SelectMany(assembly =>
            {
                try { return assembly.GetTypes(); }
                catch (ReflectionTypeLoadException error) { return error.Types.Where(type => type is not null)!; }
            })
            .Where(type => type is not null && !type.IsAbstract && serviceType.IsAssignableFrom(type)
                           && type.GetConstructor(Type.EmptyTypes) is not null)
            .Select(type => (T)Activator.CreateInstance(type!)!);

    internal static int HashCode(object? value) => JavaHashCode(value);
    internal static IEnumerable<T> Stream<T>(IEnumerable<T> values) => values;
    internal static IEnumerable<object> BoxValues<T>(IEnumerable<T> values) => values.Cast<object>();
    internal static JavaCollector ToList<T>() => new(values => values.Cast<T>().ToList());
    internal static JavaCollector ToSet<T>() => new(values => new HashSet<T>(values.Cast<T>()));
    internal static JavaCollector ToCollection<C>(Func<C> supplier)
    {
        return new JavaCollector(values =>
        {
            object collection = supplier()!;
            var collectionInterface = collection.GetType().GetInterfaces().FirstOrDefault(type =>
                type.IsGenericType && type.GetGenericTypeDefinition() == typeof(ICollection<>)) ??
                throw new InvalidOperationException(
                    $"Collector target `{collection.GetType()}` is not a Java collection.");
            var add = collectionInterface.GetMethod(nameof(ICollection<object>.Add))!;
            foreach (var value in values) add.Invoke(collection, new[] { value });
            return collection;
        });
    }

    internal static bool Exists(string path) => File.Exists(path) || Directory.Exists(path);
    internal static bool IsDirectory(string path) => Directory.Exists(path);
    internal static void DeleteIfExists(string path)
    {
        if (File.Exists(path)) File.Delete(path);
        else if (Directory.Exists(path)) Directory.Delete(path);
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
    internal static int PathNameCount(string path) => path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
        .Count(segment => !string.IsNullOrEmpty(segment));
    internal static string PathName(string path, int index) =>
        path.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            .Where(segment => !string.IsNullOrEmpty(segment)).ElementAt(index);
    internal static int CompareUri(Uri left, Uri right) => string.CompareOrdinal(left.OriginalString, right.OriginalString);
    internal static Stream OpenStream(Uri uri) =>
        uri.IsFile
            ? OpenFileRead(PathOfUri(uri))
            : new System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
    internal static Stream OpenInputStream(string path) => OpenFileRead(path);
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
    internal static JavaStream<string> Walk(string path, params object?[] _) =>
        new(Directory.EnumerateFileSystemEntries(path, "*", SearchOption.AllDirectories).Prepend(path));
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
    internal static void WriterAppend(TextWriter writer, object? value, int start, int end) =>
        writer.Write(StringValueOf(value).AsSpan(start, end - start));
    internal static void SetPosixFilePermissions(string path, ISet<UnixFileMode> permissions)
    {
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, permissions.Aggregate((UnixFileMode)0, (mode, permission) => mode | permission));
    }
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
    internal static System.Net.IPAddress GetByName(string name) => System.Net.Dns.GetHostAddresses(name)[0];
    internal static bool GetBoolean(string name) => bool.TryParse(GetProperty(name), out var value) && value;
    internal static IList<T> NCopies<T>(int count, T value) => Enumerable.Repeat(value, count).ToList();
    internal static T Min<T>(T left, T right) where T : IComparable<T> => left.CompareTo(right) <= 0 ? left : right;
    internal static T Min<T>(IEnumerable<T> values) => values.Min(Comparer<T>.Default)!;

    internal static long DurationToMillis(TimeSpan value) => checked((long)value.TotalMilliseconds);
    internal static long DurationGetSeconds(TimeSpan value) => checked((long)value.TotalSeconds);
    internal static int DurationGetNano(TimeSpan value) => checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
    internal static bool EconomicMapEquals<K, V>(
        IJavaEconomicMap<K, V> left,
        IJavaEconomicMap<K, V> right)
        where K : notnull
    {
        if (ReferenceEquals(left, right)) return true;
        if (left.Size() != right.Size()) return false;
        var cursor = left.GetEntries();
        while (cursor.Advance())
        {
            K key = cursor.GetKey();
            object? leftValue = cursor.GetValue();
            object? rightValue = right.Get(key);
            if (rightValue is null)
            {
                if (leftValue is not null || !right.ContainsKey(key)) return false;
            }
            else if (!Equals(rightValue, leftValue)) return false;
        }
        return true;
    }
    internal static global::System.Net.IPEndPoint NewIpEndPoint(string host, int port) =>
        new(global::System.Net.Dns.GetHostAddresses(host)[0], port);
    internal static V OrganicGet<K, V>(IDictionary<K, V> values, K key) where K : notnull => MapGet(values, key);
    internal static T OrganicGet<T>(IList<T> values, int index) => values[index];
    internal static V OrganicPut<K, V>(IDictionary<K, V> values, K key, V value) where K : notnull => MapPut(values, key, value);
    internal static ISet<T> OrganicPut<T>(ISet<T> values, T value) { values.Add(value); return values; }
    internal static ISet<T> Assoc<T>(ISet<T> values, T value)
    {
        var result = new HashSet<T>(values, new JavaEqualityComparer<T>());
        result.Add(value);
        return result;
    }
    internal static Uri PathToUri(string path)
    {
        var pathUri = new Uri(Path.GetFullPath(path));
        return new Uri(pathUri.AbsoluteUri);
    }
}

internal
sealed class JavaRegexMatcher
{
    private readonly Regex regex;
    private readonly string input;
    private int regionStart;
    private int regionEnd;
    private int nextIndex;
    private int appendIndex;
    private Match? current;
    private int currentOffset;
    private int[]? currentBoundaryMap;

    internal JavaRegexMatcher(Regex regex, string input)
    {
        this.regex = regex;
        this.input = input;
        regionEnd = input.Length;
    }

    private Match Current() => current ?? throw new InvalidOperationException("No successful match is available");

    private bool Accept(Match match, int offset)
    {
        if (!match.Success)
        {
            current = null;
            return false;
        }
        current = match;
        currentOffset = offset;
        var end = offset + OriginalBoundary(match.Index + match.Length);
        nextIndex = match.Length == 0 ? AdvanceCodePoint(end) : end;
        return true;
    }

    private int AdvanceCodePoint(int position)
    {
        if (position >= regionEnd) return regionEnd + 1;
        // Matcher.find() advances one UTF-16 code unit after an empty match,
        // including into a surrogate pair. Pattern.split therefore exposes
        // the same Java String boundaries for an empty delimiter.
        return position + 1;
    }

    private Match MatchRegion(int absoluteStart)
    {
        var region = input.Substring(regionStart, regionEnd - regionStart);
        var startWithinRegion = Math.Max(0, absoluteStart - regionStart);
        currentBoundaryMap = null;
        if ((JavaCompat.RegexFlags(regex) & 0x80) != 0)
        {
            var normalized = new StringBuilder(region.Length);
            var boundaries = new List<int> { 0 };
            for (var sourceIndex = 0; sourceIndex < region.Length;)
            {
                var sourceLength = char.IsSurrogatePair(region, sourceIndex) ? 2 : 1;
                var unit = region.Substring(sourceIndex, sourceLength).Normalize(NormalizationForm.FormD);
                normalized.Append(unit);
                for (var unitIndex = 1; unitIndex <= unit.Length; unitIndex++)
                    boundaries.Add(unitIndex == unit.Length ? sourceIndex + sourceLength : sourceIndex);
                sourceIndex += sourceLength;
            }
            region = normalized.ToString();
            currentBoundaryMap = boundaries.ToArray();
            startWithinRegion = Array.FindIndex(currentBoundaryMap,
                boundary => boundary >= startWithinRegion);
            if (startWithinRegion < 0) startWithinRegion = region.Length;
        }
        return regex.Match(region, startWithinRegion);
    }

    internal bool Find() => nextIndex <= regionEnd && Accept(MatchRegion(Math.Max(regionStart, nextIndex)), regionStart);
    internal bool Matches()
    {
        var match = MatchRegion(regionStart);
        if (!match.Success || match.Index != 0 ||
            OriginalBoundary(match.Index + match.Length) != regionEnd - regionStart)
        {
            current = null;
            return false;
        }
        return Accept(match, regionStart);
    }
    internal bool LookingAt()
    {
        var match = MatchRegion(regionStart);
        if (!match.Success || match.Index != 0)
        {
            current = null;
            return false;
        }
        return Accept(match, regionStart);
    }
    internal JavaRegexMatcher Region(int start, int end)
    {
        if (start < 0 || start > input.Length) throw new ArgumentOutOfRangeException(nameof(start));
        if (end < 0 || end > input.Length) throw new ArgumentOutOfRangeException(nameof(end));
        if (start > end) throw new ArgumentOutOfRangeException(nameof(start), "start > end");
        regionStart = start;
        regionEnd = end;
        nextIndex = start;
        current = null;
        return this;
    }
    private Group CurrentGroup(int index) => Current().Groups[JavaCompat.RegexGroupName(regex, index)];
    private Group CurrentGroup(string name) => Current().Groups[JavaCompat.RegexGroupName(regex, name)];
    private int OriginalBoundary(int normalizedBoundary) => currentBoundaryMap is null
        ? normalizedBoundary
        : currentBoundaryMap[Math.Min(normalizedBoundary, currentBoundaryMap.Length - 1)];
    private int AbsoluteStart(Group group) => currentOffset + OriginalBoundary(group.Index);
    private int AbsoluteEnd(Group group) => currentOffset + OriginalBoundary(group.Index + group.Length);
    private string GroupValue(Group group) => input.Substring(AbsoluteStart(group), AbsoluteEnd(group) - AbsoluteStart(group));
    internal string Group() => GroupValue(Current());
    internal string Group(int index) => CurrentGroup(index).Success ? GroupValue(CurrentGroup(index)) : null!;
    internal string Group(string name) => CurrentGroup(name).Success ? GroupValue(CurrentGroup(name)) : null!;
    internal int GroupCount() => JavaCompat.RegexGroupCount(regex);
    internal int Start() => AbsoluteStart(Current());
    internal int Start(int index) => CurrentGroup(index).Success ? AbsoluteStart(CurrentGroup(index)) : -1;
    internal int End() => AbsoluteEnd(Current());
    internal int End(int index) => CurrentGroup(index).Success
        ? AbsoluteEnd(CurrentGroup(index))
        : -1;
    internal JavaRegexMatcher ToMatchResult()
    {
        var result = new JavaRegexMatcher(regex, input)
        {
            regionStart = regionStart,
            regionEnd = regionEnd,
            nextIndex = nextIndex,
            appendIndex = appendIndex,
            current = Current(),
            currentOffset = currentOffset,
            currentBoundaryMap = currentBoundaryMap
        };
        return result;
    }
    private string ExpandReplacement(string replacement)
    {
        var result = new StringBuilder(replacement.Length);
        var groupCount = GroupCount();
        for (var index = 0; index < replacement.Length; index++)
        {
            var current = replacement[index];
            if (current == '\\')
            {
                if (++index == replacement.Length)
                    throw new ArgumentException("character to be escaped is missing");
                result.Append(replacement[index]);
                continue;
            }
            if (current != '$')
            {
                result.Append(current);
                continue;
            }
            if (++index == replacement.Length)
                throw new ArgumentException("Illegal group reference: group index is missing");
            if (replacement[index] == '{')
            {
                var end = replacement.IndexOf('}', index + 1);
                if (end < 0) throw new ArgumentException("named capturing group is missing trailing '}'");
                var name = replacement[(index + 1)..end];
                if (name.Length == 0) throw new ArgumentException("named capturing group has 0 length name");
                if (!char.IsAsciiLetter(name[0]) || name.Skip(1).Any(character => !char.IsAsciiLetterOrDigit(character)))
                    throw new ArgumentException("named capturing group has invalid name");
                result.Append(Group(name));
                index = end;
                continue;
            }
            if (!char.IsAsciiDigit(replacement[index]))
                throw new ArgumentException("Illegal group reference");
            var group = replacement[index] - '0';
            if (group > groupCount) throw new ArgumentOutOfRangeException(null, "No group " + group);
            while (index + 1 < replacement.Length && char.IsAsciiDigit(replacement[index + 1]))
            {
                var candidate = checked(group * 10 + replacement[index + 1] - '0');
                if (candidate > groupCount) break;
                group = candidate;
                index++;
            }
            result.Append(Group(group));
        }
        return result.ToString();
    }
    private string Replace(string replacement, bool firstOnly)
    {
        var result = new StringBuilder(input.Length);
        while (Find())
        {
            AppendReplacement(result, replacement);
            if (firstOnly) break;
        }
        AppendTail(result);
        return result.ToString();
    }
    internal string ReplaceAll(string replacement) => Replace(replacement, false);
    internal string ReplaceFirst(string replacement) => Replace(replacement, true);
    internal JavaRegexMatcher AppendReplacement(StringBuilder buffer, string replacement)
    {
        var matchIndex = Start();
        buffer.Append(input, appendIndex, matchIndex - appendIndex);
        buffer.Append(ExpandReplacement(replacement));
        appendIndex = End();
        return this;
    }
    internal StringBuilder AppendTail(StringBuilder buffer)
    {
        buffer.Append(input, appendIndex, input.Length - appendIndex);
        appendIndex = input.Length;
        return buffer;
    }
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

internal sealed class JavaDeque<T> : ICollection<T>
{
    private readonly LinkedList<T> values = new();
    internal T GetFirst() => values.First is { } first
        ? first.Value
        : throw new InvalidOperationException("Deque is empty");
    internal T? Peek() => values.First is null ? default : values.First.Value;
    internal T Pop()
    {
        var value = GetFirst();
        values.RemoveFirst();
        return value;
    }
    internal void Push(T value) => values.AddFirst(value);
    internal void AddFirst(T value) => values.AddFirst(value);
    internal bool IsEmpty() => values.Count == 0;
    internal IEnumerator<T> DescendingIterator() => values.Reverse().GetEnumerator();
    public int Count => values.Count;
    public bool IsReadOnly => false;
    public void Add(T item) => values.AddLast(item);
    public void Clear() => values.Clear();
    public bool Contains(T item) => values.Contains(item);
    public void CopyTo(T[] array, int arrayIndex) => values.CopyTo(array, arrayIndex);
    public bool Remove(T item) => values.Remove(item);
    public IEnumerator<T> GetEnumerator() => values.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
}

internal
class JavaLinkedHashMap<K, V> : IDictionary<K, V>, IDictionary where K : notnull
{
    private sealed class Entry(K key, V value)
    {
        internal K Key { get; } = key;
        internal V Value { get; set; } = value;
    }

    private sealed class KeyComparer : IEqualityComparer<K>
    {
        public bool Equals(K? left, K? right) => JavaCompat.Equals(left, right);
        public int GetHashCode(K value) => JavaCompat.HashCode(value);
    }

    private readonly Dictionary<K, LinkedListNode<Entry>> entries;
    private readonly LinkedList<Entry> order = new();
    private readonly bool accessOrder;

    public JavaLinkedHashMap() : this(0, 0.75f, false) { }
    public JavaLinkedHashMap(int initialCapacity) : this(initialCapacity, 0.75f, false) { }
    public JavaLinkedHashMap(int initialCapacity, float loadFactor)
        : this(initialCapacity, loadFactor, false) { }
    public JavaLinkedHashMap(int initialCapacity, float loadFactor, bool accessOrder)
    {
        if (initialCapacity < 0) throw new ArgumentOutOfRangeException(nameof(initialCapacity));
        if (!(loadFactor > 0) || float.IsNaN(loadFactor))
            throw new ArgumentOutOfRangeException(nameof(loadFactor));
        entries = new Dictionary<K, LinkedListNode<Entry>>(initialCapacity, new KeyComparer());
        this.accessOrder = accessOrder;
    }
    public JavaLinkedHashMap(IEnumerable<KeyValuePair<K, V>> values) : this()
    {
        PutAll(values);
    }

    protected internal virtual bool RemoveEldestEntry(JavaMapEntry<K, V> eldest) => false;

    public int Count => entries.Count;
    public bool IsReadOnly => false;
    bool IDictionary.IsFixedSize => false;
    bool IDictionary.IsReadOnly => false;
    bool ICollection.IsSynchronized => false;
    object ICollection.SyncRoot => this;
    public ICollection<K> Keys => new KeyCollection(this);
    public ICollection<V> Values => new ValueCollection(this);
    ICollection IDictionary.Keys => Keys.ToList();
    ICollection IDictionary.Values => Values.ToList();
    public V this[K key]
    {
        get
        {
            if (!entries.TryGetValue(key, out var node)) throw new KeyNotFoundException();
            RecordAccess(node);
            return node.Value.Value;
        }
        set => Put(key, value);
    }
    object? IDictionary.this[object key]
    {
        get => key is K typed && TryGetValue(typed, out var value) ? value : null;
        set => Put(RequireKey(key), RequireValue(value));
    }

    public int Size() => Count;

    internal V Get(K key)
    {
        if (!entries.TryGetValue(key, out var node)) return default!;
        RecordAccess(node);
        return node.Value.Value;
    }

    internal V GetOrDefault(K key, V fallback)
    {
        if (!entries.TryGetValue(key, out var node)) return fallback;
        RecordAccess(node);
        return node.Value.Value;
    }

    internal V PutIfAbsent(K key, V value)
    {
        if (entries.TryGetValue(key, out var node))
        {
            var previous = node.Value.Value;
            RecordAccess(node);
            if (previous is not null) return previous;
        }
        return Put(key, value);
    }

    internal V ComputeIfAbsent(K key, Func<K, V> factory)
    {
        var present = entries.TryGetValue(key, out var node);
        if (present)
        {
            var current = node!.Value.Value;
            RecordAccess(node);
            if (current is not null) return current;
        }
        var value = factory(key);
        if (value is null) return default!;
        Put(key, value);
        return value;
    }

    internal V Put(K key, V value)
    {
        if (entries.TryGetValue(key, out var existing))
        {
            var previous = existing.Value.Value;
            existing.Value.Value = value;
            RecordAccess(existing);
            return previous;
        }

        var node = order.AddLast(new Entry(key, value));
        entries.Add(key, node);
        var eldest = order.First!;
        if (RemoveEldestEntry(new JavaMapEntry<K, V>(this, eldest.Value.Key)))
            Remove(eldest.Value.Key);
        return default!;
    }

    internal void PutAll(IEnumerable<KeyValuePair<K, V>> values)
    {
        foreach (var (key, value) in values) Put(key, value);
    }

    internal void ReplaceValueWithoutAccess(K key, V value)
    {
        if (!entries.TryGetValue(key, out var node)) throw new KeyNotFoundException();
        node.Value.Value = value;
    }

    internal ISet<K> KeySet() => new KeySetView(this);

    private void RecordAccess(LinkedListNode<Entry> node)
    {
        if (!accessOrder || ReferenceEquals(order.Last, node)) return;
        order.Remove(node);
        order.AddLast(node);
    }

    public void Add(K key, V value)
    {
        if (entries.ContainsKey(key)) throw new ArgumentException("An item with the same key has already been added.");
        Put(key, value);
    }

    public bool ContainsKey(K key) => entries.ContainsKey(key);

    public bool Remove(K key)
    {
        if (!entries.Remove(key, out var node)) return false;
        order.Remove(node);
        return true;
    }

    public bool Remove(K key, out V value)
    {
        if (!entries.TryGetValue(key, out var node))
        {
            value = default!;
            return false;
        }
        value = node.Value.Value;
        entries.Remove(key);
        order.Remove(node);
        return true;
    }

    public bool TryGetValue(K key, out V value)
    {
        if (entries.TryGetValue(key, out var node))
        {
            value = node.Value.Value;
            return true;
        }
        value = default!;
        return false;
    }

    public void Add(KeyValuePair<K, V> item) => Add(item.Key, item.Value);
    void IDictionary.Add(object key, object? value) => Add(RequireKey(key), RequireValue(value));
    public void Clear()
    {
        entries.Clear();
        order.Clear();
    }

    public bool Contains(KeyValuePair<K, V> item) =>
        entries.TryGetValue(item.Key, out var node) && JavaCompat.Equals(node.Value.Value, item.Value);
    bool IDictionary.Contains(object key) => key is K typed && ContainsKey(typed);

    public void CopyTo(KeyValuePair<K, V>[] array, int arrayIndex)
    {
        foreach (var item in this) array[arrayIndex++] = item;
    }
    void ICollection.CopyTo(Array array, int index)
    {
        foreach (var item in this)
            array.SetValue(new DictionaryEntry(item.Key!, item.Value), index++);
    }

    public bool Remove(KeyValuePair<K, V> item) => Contains(item) && Remove(item.Key);
    void IDictionary.Remove(object key)
    {
        if (key is K typed) Remove(typed);
    }

    public IEnumerator<KeyValuePair<K, V>> GetEnumerator()
    {
        foreach (var entry in order)
            yield return new KeyValuePair<K, V>(entry.Key, entry.Value);
    }

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    IDictionaryEnumerator IDictionary.GetEnumerator() => new DictionaryEnumerator(this);

    private static K RequireKey(object key) => key is K typed
        ? typed
        : throw new ArgumentException($"Key must be assignable to {typeof(K)}.", nameof(key));

    private static V RequireValue(object? value)
    {
        if (value is V typed) return typed;
        if (value is null && default(V) is null) return default!;
        throw new ArgumentException($"Value must be assignable to {typeof(V)}.", nameof(value));
    }

    private sealed class DictionaryEnumerator(JavaLinkedHashMap<K, V> source) : IDictionaryEnumerator
    {
        private readonly IEnumerator<KeyValuePair<K, V>> inner = source.GetEnumerator();
        public DictionaryEntry Entry => new(Key!, Value);
        public object Key => inner.Current.Key;
        public object? Value => inner.Current.Value;
        public object Current => Entry;
        public bool MoveNext() => inner.MoveNext();
        public void Reset() => inner.Reset();
    }

    private sealed class KeyCollection(JavaLinkedHashMap<K, V> source) : ICollection<K>
    {
        public int Count => source.Count;
        public bool IsReadOnly => false;
        public void Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(K item) => source.ContainsKey(item);
        public void CopyTo(K[] array, int arrayIndex)
        {
            foreach (var item in this) array[arrayIndex++] = item;
        }
        public bool Remove(K item) => source.Remove(item);
        public IEnumerator<K> GetEnumerator()
        {
            foreach (var entry in source.order) yield return entry.Key;
        }
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class ValueCollection(JavaLinkedHashMap<K, V> source) : ICollection<V>
    {
        public int Count => source.Count;
        public bool IsReadOnly => false;
        public void Add(V item) => throw new NotSupportedException("Java Map.values does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(V item) => source.order.Any(entry => JavaCompat.Equals(entry.Value, item));
        public void CopyTo(V[] array, int arrayIndex)
        {
            foreach (var item in this) array[arrayIndex++] = item;
        }
        public bool Remove(V item)
        {
            var node = source.order.First;
            while (node is not null)
            {
                if (JavaCompat.Equals(node.Value.Value, item)) return source.Remove(node.Value.Key);
                node = node.Next;
            }
            return false;
        }
        public IEnumerator<V> GetEnumerator()
        {
            foreach (var entry in source.order) yield return entry.Value;
        }
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class KeySetView(JavaLinkedHashMap<K, V> source) : ISet<K>
    {
        private HashSet<K> Snapshot() => new(source.Keys, new KeyComparer());
        public int Count => source.Count;
        public bool IsReadOnly => false;
        bool ISet<K>.Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        void ICollection<K>.Add(K item) => throw new NotSupportedException("Java Map.keySet does not support add().");
        public void Clear() => source.Clear();
        public bool Contains(K item) => source.ContainsKey(item);
        public void CopyTo(K[] array, int arrayIndex) => source.Keys.CopyTo(array, arrayIndex);
        public void ExceptWith(IEnumerable<K> other)
        {
            foreach (var item in other.ToList()) source.Remove(item);
        }
        public void IntersectWith(IEnumerable<K> other)
        {
            var retained = new HashSet<K>(other, new KeyComparer());
            foreach (var item in source.Keys.Where(item => !retained.Contains(item)).ToList()) source.Remove(item);
        }
        public bool IsProperSubsetOf(IEnumerable<K> other) => Snapshot().IsProperSubsetOf(other);
        public bool IsProperSupersetOf(IEnumerable<K> other) => Snapshot().IsProperSupersetOf(other);
        public bool IsSubsetOf(IEnumerable<K> other) => Snapshot().IsSubsetOf(other);
        public bool IsSupersetOf(IEnumerable<K> other) => Snapshot().IsSupersetOf(other);
        public bool Overlaps(IEnumerable<K> other) => Snapshot().Overlaps(other);
        public bool SetEquals(IEnumerable<K> other) => Snapshot().SetEquals(other);
        public void SymmetricExceptWith(IEnumerable<K> other) =>
            throw new NotSupportedException("Java Map.keySet does not support adding keys.");
        public void UnionWith(IEnumerable<K> other) =>
            throw new NotSupportedException("Java Map.keySet does not support adding keys.");
        public bool Remove(K item) => source.Remove(item);
        public IEnumerator<K> GetEnumerator() => source.Keys.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        public override string ToString() =>
            "[" + string.Join(", ", source.Keys.Select(item => JavaCompat.StringValueOf(item))) + "]";
    }
}

internal
sealed class JavaLinkedList<T> : IList<T>
{
    private readonly List<T> values = new();

    public T this[int index] { get => values[index]; set => values[index] = value; }
    public int Count => values.Count;
    public bool IsReadOnly => false;
    public void Add(T item) => values.Add(item);
    public void AddFirst(T item) => values.Insert(0, item);
    public void Clear() => values.Clear();
    public bool Contains(T item) => values.Contains(item);
    public void CopyTo(T[] array, int arrayIndex) => values.CopyTo(array, arrayIndex);
    public int IndexOf(T item) => values.IndexOf(item);
    public void Insert(int index, T item) => values.Insert(index, item);
    public bool Remove(T item) => values.Remove(item);
    public void RemoveAt(int index) => values.RemoveAt(index);
    public IEnumerator<T> GetEnumerator() => new Enumerator(values);
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    private sealed class Enumerator : IEnumerator<T>, JavaRemovableIterator
    {
        private readonly List<T> values;
        private int index = -1;
        private bool canRemove;

        internal Enumerator(List<T> values) => this.values = values;
        public T Current { get; private set; } = default!;
        object IEnumerator.Current => Current!;
        public bool MoveNext()
        {
            if (++index >= values.Count)
            {
                Current = default!;
                return false;
            }
            Current = values[index];
            return true;
        }
        public void MarkReturned() => canRemove = true;
        public void Remove()
        {
            if (!canRemove) throw new InvalidOperationException(
                "Iterator.remove() requires one preceding next().");
            values.RemoveAt(index--);
            canRemove = false;
        }
        public void Reset() => throw new NotSupportedException();
        public void Dispose() { }
    }
}

internal sealed class JavaResourceBundle
{
    private readonly IReadOnlyDictionary<string, string> resources;

    internal JavaResourceBundle(string baseName, CultureInfo locale)
    {
        _ = locale;
        var resourceName = baseName + ".properties";
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName)
            ?? throw new MissingManifestResourceException(resourceName);
        resources = ReadProperties(stream);
    }

    internal string GetString(string name) =>
        resources.TryGetValue(name, out var value) ? value : throw new MissingManifestResourceException(name);

    private static IReadOnlyDictionary<string, string> ReadProperties(Stream stream)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        using var reader = new StreamReader(stream, Encoding.Latin1, false, 1024, leaveOpen: true);
        var logicalLine = new StringBuilder();
        var continued = false;

        while (reader.ReadLine() is { } physicalLine)
        {
            if (continued) physicalLine = physicalLine.TrimStart(' ', '\t', '\f');
            logicalLine.Append(physicalLine);
            var slashCount = 0;
            for (var index = logicalLine.Length - 1; index >= 0 && logicalLine[index] == '\\'; index--) slashCount++;
            if (slashCount % 2 == 1)
            {
                logicalLine.Length--;
                continued = true;
                continue;
            }

            AddProperty(result, logicalLine.ToString());
            logicalLine.Clear();
            continued = false;
        }

        if (logicalLine.Length > 0) AddProperty(result, logicalLine.ToString());
        return result;
    }

    private static void AddProperty(IDictionary<string, string> properties, string line)
    {
        var start = 0;
        while (start < line.Length && char.IsWhiteSpace(line[start])) start++;
        if (start == line.Length || line[start] is '#' or '!') return;

        var escaped = false;
        var keyEnd = start;
        while (keyEnd < line.Length)
        {
            var current = line[keyEnd];
            if (!escaped && (current is '=' or ':' || char.IsWhiteSpace(current))) break;
            if (current == '\\' && !escaped) escaped = true;
            else escaped = false;
            keyEnd++;
        }

        var valueStart = keyEnd;
        while (valueStart < line.Length && char.IsWhiteSpace(line[valueStart])) valueStart++;
        if (valueStart < line.Length && line[valueStart] is '=' or ':') valueStart++;
        while (valueStart < line.Length && char.IsWhiteSpace(line[valueStart])) valueStart++;

        properties[Unescape(line[start..keyEnd])] = Unescape(line[valueStart..]);
    }

    private static string Unescape(string value)
    {
        var result = new StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (value[index] != '\\' || index + 1 == value.Length)
            {
                result.Append(value[index]);
                continue;
            }

            var escaped = value[++index];
            switch (escaped)
            {
                case 't': result.Append('\t'); break;
                case 'n': result.Append('\n'); break;
                case 'r': result.Append('\r'); break;
                case 'f': result.Append('\f'); break;
                case 'u':
                    if (index + 4 >= value.Length)
                        throw new FormatException("Incomplete Unicode escape in Java properties resource");
                    result.Append((char)Convert.ToInt32(value.Substring(index + 1, 4), 16));
                    index += 4;
                    break;
                default: result.Append(escaped); break;
            }
        }
        return result.ToString();
    }
}

internal sealed class JavaPrintWriter
{
    private readonly TextWriter writer;
    public JavaPrintWriter(TextWriter writer) => this.writer = writer;
    public void Print(object? value) => writer.Write(value);
    public void Println(object? value = null) => writer.WriteLine(value);
    public void Flush() => writer.Flush();
}

internal class JavaFormat
{
    internal virtual string Format(object? value) => value?.ToString() ?? "null";
}

internal sealed class JavaMessageFormat : JavaFormat
{
    private readonly string pattern;
    private readonly CultureInfo locale;
    internal JavaMessageFormat(string pattern) : this(pattern, CultureInfo.CurrentCulture) { }
    internal JavaMessageFormat(string pattern, CultureInfo locale)
    {
        this.pattern = pattern;
        this.locale = locale;
    }
    internal override string Format(object? value)
    {
        var arguments = value as object?[] ?? new[] { value };
        var result = new StringBuilder(pattern.Length);
        var quoted = false;
        for (var index = 0; index < pattern.Length; index++)
        {
            var current = pattern[index];
            if (current == '\'')
            {
                if (index + 1 < pattern.Length && pattern[index + 1] == '\'')
                {
                    result.Append('\'');
                    index++;
                }
                else
                {
                    quoted = !quoted;
                }
                continue;
            }
            if (!quoted && current == '{')
            {
                var close = pattern.IndexOf('}', index + 1);
                if (close < 0)
                    throw new FormatException("Invalid Java MessageFormat placeholder");
                var placeholder = pattern.Substring(index + 1, close - index - 1);
                var fields = placeholder.Split(',', 3, StringSplitOptions.TrimEntries);
                if (fields.Length == 0 ||
                    !int.TryParse(fields[0], NumberStyles.None, CultureInfo.InvariantCulture,
                                  out var argumentIndex))
                    throw new FormatException("Invalid Java MessageFormat placeholder");
                if (argumentIndex < 0 || argumentIndex >= arguments.Length)
                    throw new FormatException("Java MessageFormat argument index is out of range");
                result.Append(FormatArgument(arguments[argumentIndex], fields));
                index = close;
                continue;
            }
            result.Append(current);
        }
        return result.ToString();
    }

    private string FormatArgument(object? argument, string[] fields)
    {
        if (fields.Length == 1) return FormatDefault(argument);
        if (!string.Equals(fields[1], "number", StringComparison.OrdinalIgnoreCase))
            throw new FormatException($"Unsupported Java MessageFormat type `{fields[1]}`");
        if (argument is null) return "null";
        if (fields.Length == 2 || string.IsNullOrEmpty(fields[2]))
            return FormatDefault(argument);

        var style = fields[2];
        var decimalIndex = style.IndexOf('.');
        var integerPattern = decimalIndex < 0 ? style : style[..decimalIndex];
        var fractionPattern = decimalIndex < 0 ? string.Empty : style[(decimalIndex + 1)..];
        if (integerPattern.Any(character => character is not '#' and not '0' and not ',') ||
            fractionPattern.Any(character => character is not '#' and not '0'))
            throw new FormatException($"Unsupported Java DecimalFormat pattern `{style}`");

        var minimumIntegerDigits = Math.Max(1, integerPattern.Count(character => character == '0'));
        var minimumFractionDigits = fractionPattern.Count(character => character == '0');
        var maximumFractionDigits = fractionPattern.Length;
        var grouping = integerPattern.Contains(',');
        var custom = (grouping ? "#,##" : string.Empty) + new string('0', minimumIntegerDigits);
        if (maximumFractionDigits > 0)
            custom += "." + new string('0', minimumFractionDigits) +
                      new string('#', maximumFractionDigits - minimumFractionDigits);
        return argument is IFormattable formattable
            ? formattable.ToString(custom, locale)
            : Convert.ToString(argument, locale) ?? "null";
    }

    private string FormatDefault(object? argument) => argument switch
    {
        null => "null",
        sbyte or byte or short or ushort or int or uint or long or ulong =>
            ((IFormattable)argument).ToString("N0", locale),
        Uri uri => JavaCompat.UriToString(uri),
        Regex regex => JavaCompat.RegexPattern(regex),
        _ => Convert.ToString(argument, locale) ?? "null"
    };
}

internal sealed class JavaCollector
{
    private readonly Func<IEnumerable<object?>, object> collector;
    internal JavaCollector(Func<IEnumerable<object?>, object> collector) => this.collector = collector;
    internal object Collect(IEnumerable<object?> values) => collector(values);
}

internal sealed class JavaArrayList<T> : Collection<T>
{
    internal JavaArrayList(IList<T> values) : base(values) { }
}

internal sealed class JavaStream<T> : IEnumerable<T>, IDisposable
{
    private readonly IEnumerable<T> source;
    internal JavaStream(IEnumerable<T> source) => this.source = source;
    public IEnumerator<T> GetEnumerator() => source.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    public void Dispose() { }
}

internal sealed class JavaSubList<T> : IList<T>
{
    private readonly IList<T> source;
    private int offset;
    private int count;
    internal JavaSubList(IList<T> source, int fromIndex, int toIndex)
    {
        if (fromIndex < 0 || toIndex > source.Count || fromIndex > toIndex) throw new ArgumentOutOfRangeException();
        this.source = source;
        offset = fromIndex;
        count = toIndex - fromIndex;
    }
    public T this[int index] { get => source[Checked(index)]; set => source[Checked(index)] = value; }
    public int Count => count;
    public bool IsReadOnly => source.IsReadOnly;
    public void Add(T item) => Insert(count, item);
    public void Clear() { for (var index = count - 1; index >= 0; index--) RemoveAt(index); }
    public bool Contains(T item) => this.Any(value => EqualityComparer<T>.Default.Equals(value, item));
    public void CopyTo(T[] array, int arrayIndex) { foreach (var item in this) array[arrayIndex++] = item; }
    public IEnumerator<T> GetEnumerator() { for (var index = 0; index < count; index++) yield return source[offset + index]; }
    public int IndexOf(T item) { var index = 0; foreach (var value in this) { if (EqualityComparer<T>.Default.Equals(value, item)) return index; index++; } return -1; }
    public void Insert(int index, T item) { if (index < 0 || index > count) throw new ArgumentOutOfRangeException(nameof(index)); source.Insert(offset + index, item); count++; }
    public bool Remove(T item) { var index = IndexOf(item); if (index < 0) return false; RemoveAt(index); return true; }
    public void RemoveAt(int index) { source.RemoveAt(Checked(index)); count--; }
    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    private int Checked(int index) => index >= 0 && index < count ? offset + index : throw new ArgumentOutOfRangeException(nameof(index));
}
