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

// JDK compatibility area: Java.Util.Concurrent

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

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaExecutorService
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
    internal long getId() => thread.ManagedThreadId;
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
    internal bool GetAndSet(bool replacement) =>
        Interlocked.Exchange(ref value, replacement ? 1 : 0) != 0;
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

internal sealed class JavaThreadLocal<T> : IDisposable
{
    private readonly ThreadLocal<T> value;

    private JavaThreadLocal(Func<T> supplier) => value = new ThreadLocal<T>(supplier);

    internal static JavaThreadLocal<T> WithInitial(Func<T> supplier)
    {
        ArgumentNullException.ThrowIfNull(supplier);
        return new JavaThreadLocal<T>(supplier);
    }

    internal T Get() => value.Value!;
    internal void Set(T replacement) => value.Value = replacement;
    public void Dispose() => value.Dispose();
}

