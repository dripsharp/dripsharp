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

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Lang

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaCloneable
{
}

internal sealed class JavaAssertionError : Exception
{
    internal JavaAssertionError() { }
    internal JavaAssertionError(object? detail)
        : base(JavaCompat.StringValueOf(detail), detail as Exception)
    {
    }
    internal JavaAssertionError(string? message, Exception? cause)
        : base(message, cause)
    {
    }
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

[Serializable]
internal sealed class JavaNumberFormatException : ArgumentException
{
    internal JavaNumberFormatException(string message) : base(message) { }
    internal JavaNumberFormatException(string message, Exception cause)
        : base(message, cause) { }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaReference<T> where T : class
{
    public abstract T? Get();
    public abstract void Clear();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSoftReference<T> : JavaReference<T> where T : class
{
    private WeakReference<T>? reference;

    public JavaSoftReference(T? value)
    {
        if (value is not null)
            reference = new WeakReference<T>(value);
    }

    public override T? Get()
    {
        var current = reference;
        return current is not null && current.TryGetTarget(out var value) ? value : null;
    }

    public override void Clear() => reference = null;
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaWeakReference<T> : JavaReference<T> where T : class
{
    private WeakReference<T>? reference;

    public JavaWeakReference(T? value)
    {
        if (value is not null)
            reference = new WeakReference<T>(value);
    }

    public override T? Get()
    {
        var current = reference;
        return current is not null && current.TryGetTarget(out var value) ? value : null;
    }

    public override void Clear() => reference = null;
}

[AttributeUsage(AttributeTargets.Field, AllowMultiple = false, Inherited = false)]
internal sealed class JavaEnumNameAttribute(string name) : Attribute
{
    internal string Name { get; } = name;
}

[AttributeUsage(AttributeTargets.Field, AllowMultiple = false, Inherited = false)]
internal sealed class JavaEnumOrdinalAttribute(int ordinal) : Attribute
{
    internal int Ordinal { get; } = ordinal;
}

internal sealed class JavaMethodType : IEquatable<JavaMethodType>
{
    internal Type ReturnType { get; }
    internal IReadOnlyList<Type> ParameterTypes { get; }
    internal JavaMethodType(Type returnType, params Type[] parameterTypes)
    {
        ReturnType = returnType;
        ParameterTypes = parameterTypes;
    }
    internal static JavaMethodType methodType(Type returnType) => new(returnType);
    internal static JavaMethodType methodType(Type returnType, Type parameterType) =>
        new(returnType, parameterType);
    internal Type returnType() => ReturnType;
    public bool Equals(JavaMethodType? other) =>
        other is not null && ReturnType == other.ReturnType &&
        ParameterTypes.SequenceEqual(other.ParameterTypes);
    public override bool Equals(object? obj) => Equals(obj as JavaMethodType);
    public override int GetHashCode() =>
        ParameterTypes.Aggregate(ReturnType.GetHashCode(),
            (hash, parameter) => HashCode.Combine(hash, parameter));
}

internal sealed class JavaMethodHandle
{
    private readonly Func<object?[], object?> invocation;
    private readonly JavaMethodType methodType;
    internal JavaMethodHandle(Func<object?[], object?> invoke, JavaMethodType methodType)
    {
        invocation = invoke;
        this.methodType = methodType;
    }
    internal JavaMethodHandle asType(JavaMethodType _) => this;
    internal JavaMethodHandle bindTo(object? target) =>
        new(arguments => invocation(new[] { target }.Concat(arguments).ToArray()),
            new JavaMethodType(methodType.ReturnType,
                methodType.ParameterTypes.Skip(1).ToArray()));
    internal object? invoke(params object?[] arguments) => invocation(arguments);
    internal object? invokeExact(params object?[] arguments) => invocation(arguments);
    internal JavaMethodType type() => methodType;
}

internal sealed class JavaMethodHandlesLookup
{
    internal JavaMethodHandle findStatic(Type owner, string name, JavaMethodType methodType) =>
        FromMethod(owner.GetMethod(name, BindingFlags.Static | BindingFlags.Public |
            BindingFlags.NonPublic) ?? throw new MissingMethodException(owner.FullName, name),
            methodType);
    internal JavaMethodHandle findVirtual(Type owner, string name, JavaMethodType methodType) =>
        FromMethod(owner.GetMethod(name, BindingFlags.Instance | BindingFlags.Public |
            BindingFlags.NonPublic) ?? throw new MissingMethodException(owner.FullName, name),
            methodType);
    internal JavaMethodHandle unreflect(MethodInfo method) =>
        FromMethod(method, new JavaMethodType(method.ReturnType,
            (method.IsStatic ? Array.Empty<Type>() : new[] { method.DeclaringType! })
                .Concat(method.GetParameters().Select(parameter => parameter.ParameterType))
                .ToArray()));
    private static JavaMethodHandle FromMethod(MethodInfo method, JavaMethodType methodType) =>
        new(arguments =>
        {
            var target = method.IsStatic ? null : arguments[0];
            var parameters = method.IsStatic ? arguments : arguments.Skip(1).ToArray();
            return method.Invoke(target, parameters);
        }, methodType);
}

internal static class JavaMethodHandles
{
    internal static JavaMethodHandlesLookup lookup() => new();
    internal static JavaMethodHandlesLookup publicLookup() => lookup();
    internal static JavaMethodHandle empty(JavaMethodType methodType) =>
        new(_ => null, methodType);
    internal static JavaMethodHandle constant(Type type, object? value) =>
        new(_ => value, new JavaMethodType(type));
    internal static JavaMethodHandle dropArguments(
        JavaMethodHandle target, int _, params Type[] parameterTypes) =>
        new(arguments => target.invokeExact(arguments.Skip(parameterTypes.Length).ToArray()),
            new JavaMethodType(target.type().ReturnType,
                parameterTypes.Concat(target.type().ParameterTypes).ToArray()));
    internal static JavaMethodHandle filterReturnValue(
        JavaMethodHandle target, JavaMethodHandle filter) =>
        new(arguments => filter.invokeExact(target.invokeExact(arguments)),
            new JavaMethodType(filter.type().ReturnType, target.type().ParameterTypes.ToArray()));
    internal static JavaMethodHandle guardWithTest(
        JavaMethodHandle test, JavaMethodHandle target, JavaMethodHandle fallback) =>
        new(arguments => (bool)test.invokeExact(arguments)!
                ? target.invokeExact(arguments)
                : fallback.invokeExact(arguments),
            target.type());
}

internal sealed class JavaUnsafe
{
    internal static readonly JavaUnsafe theUnsafe = new();
    internal void invokeCleaner(JavaByteBuffer buffer) => buffer.Dispose();
}

internal sealed class JavaRuntime
{
    private static readonly JavaRuntime Instance = new();
    internal static JavaRuntime getRuntime() => Instance;
    internal void addShutdownHook(JavaThread thread) =>
        AppDomain.CurrentDomain.ProcessExit += (_, _) => thread.Start();
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

    internal JavaProcessBuilder Directory(FileInfo directory) =>
        Directory(directory.FullName);

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


internal static partial class JavaCompat
{
    private static readonly bool AssertionsEnabled =
        string.Equals(Environment.GetEnvironmentVariable("DRIPSHARP_JAVA_ASSERTIONS"),
            "true", StringComparison.OrdinalIgnoreCase);
    internal static void Assert(Func<bool> condition, Func<object?>? message = null)
    {
        if (AssertionsEnabled && !condition())
            throw new JavaAssertionError(message?.Invoke()?.ToString());
    }

    // Java compound assignment includes the narrowing conversion back to the
    // left-hand type. A ref helper also preserves Java's single evaluation of
    // array indexes and other assignable expressions.
    internal static sbyte AddAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target + value));
    internal static sbyte SubtractAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target - value));
    internal static sbyte MultiplyAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target * value));
    internal static sbyte DivideAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target / value));
    internal static sbyte RemainderAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target % value));
    internal static sbyte AndAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target & value));
    internal static sbyte OrAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)((byte)target | value));
    internal static sbyte XorAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target ^ value));
    internal static sbyte ShiftLeftAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target << (value & 0x1f)));
    internal static sbyte ShiftRightAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)(target >> (value & 0x1f)));
    internal static sbyte UnsignedShiftRightAssign(ref sbyte target, int value) =>
        target = unchecked((sbyte)((uint)target >> (value & 0x1f)));

    internal static int AddAssign(ref int target, long value) =>
        target = unchecked((int)(target + value));
    internal static int AddAssign(ref int target, float value) =>
        target = NarrowToInt(target + value);
    internal static int AddAssign(ref int target, double value) =>
        target = NarrowToInt(target + value);
    internal static int SubtractAssign(ref int target, long value) =>
        target = unchecked((int)(target - value));
    internal static int SubtractAssign(ref int target, float value) =>
        target = NarrowToInt(target - value);
    internal static int SubtractAssign(ref int target, double value) =>
        target = NarrowToInt(target - value);
    internal static int MultiplyAssign(ref int target, long value) =>
        target = unchecked((int)(target * value));
    internal static int MultiplyAssign(ref int target, float value) =>
        target = NarrowToInt(target * value);
    internal static int MultiplyAssign(ref int target, double value) =>
        target = NarrowToInt(target * value);
    internal static int DivideAssign(ref int target, long value) =>
        target = unchecked((int)(target / value));
    internal static int DivideAssign(ref int target, float value) =>
        target = NarrowToInt(target / value);
    internal static int DivideAssign(ref int target, double value) =>
        target = NarrowToInt(target / value);
    internal static int RemainderAssign(ref int target, long value) =>
        target = unchecked((int)(target % value));
    internal static int RemainderAssign(ref int target, float value) =>
        target = NarrowToInt(target % value);
    internal static int RemainderAssign(ref int target, double value) =>
        target = NarrowToInt(target % value);
    internal static int AndAssign(ref int target, long value) =>
        target = unchecked((int)((uint)target & (ulong)value));
    internal static int OrAssign(ref int target, long value) =>
        target = unchecked((int)((uint)target | (ulong)value));
    internal static int XorAssign(ref int target, long value) =>
        target = unchecked((int)((uint)target ^ (ulong)value));
    internal static int ShiftLeftAssign(ref int target, long value) =>
        target <<= unchecked((int)value) & 0x1f;
    internal static int ShiftRightAssign(ref int target, long value) =>
        target >>= unchecked((int)value) & 0x1f;
    internal static int UnsignedShiftRightAssign(ref int target, long value) =>
        target = unchecked((int)((uint)target >> (unchecked((int)value) & 0x1f)));

    private static int NarrowToInt(float value) =>
        float.IsNaN(value) ? 0 :
        value >= int.MaxValue ? int.MaxValue :
        value <= int.MinValue ? int.MinValue :
        (int)value;

    private static int NarrowToInt(double value) =>
        double.IsNaN(value) ? 0 :
        value >= int.MaxValue ? int.MaxValue :
        value <= int.MinValue ? int.MinValue :
        (int)value;

    internal static readonly TextWriter @out = Console.Out;
    internal static readonly TextWriter err = Console.Error;
    private static readonly Dictionary<string, string> SystemProperties = new(StringComparer.Ordinal)
    {
        ["os.name"] = OperatingSystem.IsMacOS() ? "Mac OS X"
            : OperatingSystem.IsWindows() ? "Windows"
            : OperatingSystem.IsLinux() ? "Linux"
            : Environment.OSVersion.Platform.ToString(),
        ["os.version"] = Environment.OSVersion.VersionString,
        ["os.arch"] = RuntimeInformation.OSArchitecture switch
        {
            Architecture.X64 => "amd64",
            Architecture.Arm64 => "aarch64",
            Architecture.X86 => "x86",
            Architecture.Arm => "arm",
            var architecture => architecture.ToString().ToLowerInvariant()
        },
        ["java.version"] = Environment.Version.ToString(),
        ["user.home"] = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ["user.dir"] = Environment.CurrentDirectory,
        ["java.io.tmpdir"] = Path.GetTempPath(),
        ["file.separator"] = Path.DirectorySeparatorChar.ToString(),
        ["path.separator"] = Path.PathSeparator.ToString(),
        ["line.separator"] = Environment.NewLine
    };

    internal static T RequireNonNull<T>(T? value, string? message = null) =>
        value is null ? throw new NullReferenceException(message) : value;
    internal static bool nonNull(object? value) => value is not null;
    internal static T doPrivileged<T>(Func<T> action) => action();
    internal static Type ClassForName(string name) => name switch
    {
        "sun.misc.Unsafe" => typeof(JavaUnsafe),
        "java.nio.DirectByteBuffer" => typeof(JavaByteBuffer),
        _ => Type.GetType(name, throwOnError: true)!
    };
    internal static FieldInfo GetDeclaredField(Type type, string name) =>
        type.GetField(name, BindingFlags.Instance | BindingFlags.Static |
            BindingFlags.Public | BindingFlags.NonPublic) ??
        throw new MissingFieldException(type.FullName, name);
    internal static MethodInfo GetMethod(Type type, string name, params Type[] parameterTypes) =>
        type.GetMethod(name, BindingFlags.Instance | BindingFlags.Static |
            BindingFlags.Public | BindingFlags.NonPublic, parameterTypes) ??
        throw new MissingMethodException(type.FullName, name);
    internal static void SetAccessible(MemberInfo _, bool __) { }
    internal static T RequireNonNullElseGet<T>(T? value, Func<T> supplier) =>
        value is null ? RequireNonNull(supplier()) : value;
    internal static string? Getenv(string name) => Environment.GetEnvironmentVariable(name);
    internal static string ExceptionMessage(Exception? exception)
    {
        if (exception is null) return null!;
        var method = exception.GetType().GetMethod("GetMessage", Type.EmptyTypes);
        return method is null ? exception.Message : (method.Invoke(exception, null) as string)!;
    }
    internal static string ExceptionToString(Exception exception)
    {
        var typeName = exception.GetType().FullName ?? exception.GetType().Name;
        var message = ExceptionMessage(exception);
        return string.IsNullOrEmpty(message) ? typeName : typeName + ": " + message;
    }

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
    internal static int CharacterDigit(char value, int radix)
    {
        var digit = value is >= '0' and <= '9' ? value - '0'
            : value is >= 'a' and <= 'z' ? value - 'a' + 10
            : value is >= 'A' and <= 'Z' ? value - 'A' + 10
            : (int)char.GetNumericValue(value);
        return digit >= 0 && digit < radix ? digit : -1;
    }
    internal static bool IsWhitespace(char value) => char.IsWhiteSpace(value);
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
    internal static bool StringStartsWith(string value, string prefix, int offset) =>
        offset >= 0 &&
        offset <= value.Length &&
        value.AsSpan(offset).StartsWith(prefix.AsSpan(), StringComparison.Ordinal);
    internal static bool StringEndsWith(string value, string suffix) =>
        value.EndsWith(suffix, StringComparison.Ordinal);
    internal static string StringSubstring(string value, int beginIndex, int endIndex) =>
        value.Substring(beginIndex, endIndex - beginIndex);
    internal static int StringIndexOf(string value, int character) =>
        value.IndexOf((char)character);
    internal static int StringIndexOf(string value, int character, int fromIndex) =>
        value.IndexOf((char)character, fromIndex);
    internal static int StringLastIndexOf(string value, int character) =>
        value.LastIndexOf((char)character);
    internal static int StringLastIndexOf(string value, int character, int fromIndex) =>
        value.Length == 0 || fromIndex < 0
            ? -1
            : value.LastIndexOf((char)character, Math.Min(fromIndex, value.Length - 1));
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

    internal static int LongHashCode(long value) =>
        unchecked((int)(value ^ (long)((ulong)value >> 32)));

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
    internal static string StringJoin(string delimiter, IEnumerable<string> values) =>
        string.Join(delimiter, values);
    internal static string StringValueOf(float value) => JavaFloatingString(value);
    internal static string StringValueOf(double value) => JavaFloatingString(value);
    internal static string ObjectsToString(object? value, string? nullDefault) =>
        value is null ? nullDefault! : StringValueOf(value);
    internal static string Normalize(string value, NormalizationForm form) =>
        value.Normalize(form);
    internal static StringBuilder AppendValue(StringBuilder builder, object? value)
    {
        builder.Append(StringValueOf(value));
        return builder;
    }
    internal static IEnumerable<string> StringLines(string value) => value.Replace("\r\n", "\n").Split('\n');
    internal static sbyte[] StringGetBytes(string value, Encoding encoding)
    {
        if (ReferenceEquals(encoding, JavaStandardCharsets.UTF16))
        {
            return new byte[] { 0xfe, 0xff }
                .Concat(Encoding.BigEndianUnicode.GetBytes(value))
                .Select(item => unchecked((sbyte)item))
                .ToArray();
        }
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
        var field = value.GetType().GetFields(flags)
            .FirstOrDefault(candidate => ReferenceEquals(candidate.GetValue(null), value));
        return field?.GetCustomAttribute<JavaEnumNameAttribute>()?.Name
               ?? field?.Name
               ?? value.ToString()
               ?? string.Empty;
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
        var field = constants.FirstOrDefault(
            candidate => ReferenceEquals(candidate.GetValue(null), value));
        if (field is null)
            throw new ArgumentException("Value is not a declared enum constant", nameof(value));
        return field.GetCustomAttribute<JavaEnumOrdinalAttribute>()?.Ordinal
               ?? Array.IndexOf(constants, field);
    }

    internal static T EnumValueOf<T>(string name)
    {
        const BindingFlags flags = BindingFlags.Public | BindingFlags.Static;
        var field = typeof(T).GetFields(flags)
            .Where(candidate => typeof(T).IsAssignableFrom(candidate.FieldType))
            .FirstOrDefault(candidate =>
                string.Equals(
                    candidate.GetCustomAttribute<JavaEnumNameAttribute>()?.Name
                    ?? candidate.Name,
                    name,
                    StringComparison.Ordinal));
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

    internal static int ReflectionFieldModifiers(FieldInfo field)
    {
        ArgumentNullException.ThrowIfNull(field);
        var modifiers = 0;
        if (field.IsPublic) modifiers |= 0x0001;
        if (field.IsPrivate) modifiers |= 0x0002;
        if (field.IsFamily) modifiers |= 0x0004;
        if (field.IsStatic) modifiers |= 0x0008;
        if (field.IsInitOnly || field.IsLiteral) modifiers |= 0x0010;
        return modifiers;
    }

    internal static bool ReflectionModifierIsFinal(int modifiers) =>
        (modifiers & 0x0010) != 0;

    internal static int ParseInt(string value)
    {
        try
        {
            return int.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }

    internal static int ParseInt(string value, int radix) =>
        checked((int)ParseSignedRadix(value, radix, int.MinValue, int.MaxValue));
    internal static bool ParseBoolean(string value) =>
        string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);

    internal static long ParseLong(string value)
    {
        try
        {
            return long.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static long ParseLong(string value, int radix) =>
        ParseSignedRadix(value, radix, long.MinValue, long.MaxValue);
    internal static long ParseLong(string value, int beginIndex, int endIndex, int radix) =>
        ParseLong(value.Substring(beginIndex, endIndex - beginIndex), radix);
    internal static sbyte ParseByte(string value, int radix)
    {
        try
        {
            return checked((sbyte)ParseSignedRadix(value, radix, sbyte.MinValue, sbyte.MaxValue));
        }
        catch (OverflowException error)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }

    private static long ParseSignedRadix(string value, int radix, long minimum, long maximum)
    {
        if (radix is < 2 or > 36) throw new ArgumentException($"Invalid radix {radix}.");
        if (string.IsNullOrEmpty(value))
            throw new JavaNumberFormatException("Input string was empty.");
        var index = 0;
        var negative = false;
        if (value[0] is '+' or '-')
        {
            negative = value[0] == '-';
            index++;
            if (index == value.Length)
                throw new JavaNumberFormatException($"Invalid number `{value}`.");
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
                throw new JavaNumberFormatException($"Invalid number `{value}` for radix {radix}.");
            if (magnitude > (limit - (uint)digit) / (uint)radix)
                throw new JavaNumberFormatException($"Number `{value}` is out of range.");
            magnitude = magnitude * (uint)radix + (uint)digit;
        }
        if (!negative) return (long)magnitude;
        return magnitude == negativeLimit ? minimum : -(long)magnitude;
    }
    internal static double ParseDouble(string value)
    {
        try
        {
            return double.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static float ParseFloat(string value)
    {
        try
        {
            return float.Parse(value, CultureInfo.InvariantCulture);
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            throw new JavaNumberFormatException(error.Message, error);
        }
    }
    internal static int CompareLong(long left, long right) => left.CompareTo(right);
    internal static int CompareInt(int left, int right) => left.CompareTo(right);
    internal static int SumInt(int left, int right) => unchecked(left + right);
    internal static int CompareFloat(float left, float right) => left.CompareTo(right);
    internal static int CompareDouble(double left, double right)
    {
        if (left < right) return -1;
        if (left > right) return 1;
        if (double.IsNaN(left)) return double.IsNaN(right) ? 0 : 1;
        if (double.IsNaN(right)) return -1;
        var leftBits = BitConverter.DoubleToInt64Bits(left);
        var rightBits = BitConverter.DoubleToInt64Bits(right);
        return leftBits == rightBits ? 0 : leftBits < rightBits ? -1 : 1;
    }
    internal static int StringCompareTo(string left, string right) =>
        string.Compare(left, right, StringComparison.Ordinal);
    internal static int LongLeadingZeros(long value) => BitOperations.LeadingZeroCount(unchecked((ulong)value));
    internal static int LongTrailingZeros(long value) => BitOperations.TrailingZeroCount(unchecked((ulong)value));
    internal static int IntLeadingZeros(int value) => BitOperations.LeadingZeroCount(unchecked((uint)value));
    internal static int HighestOneBit(int value) =>
        value == 0 ? 0 : 1 << (31 - BitOperations.LeadingZeroCount(unchecked((uint)value)));
    internal static int FloatToIntBits(float value) =>
        float.IsNaN(value) ? 0x7fc00000 : BitConverter.SingleToInt32Bits(value);
    internal static int DoubleHashCode(double value)
    {
        var bits = double.IsNaN(value)
            ? 0x7ff8000000000000L
            : BitConverter.DoubleToInt64Bits(value);
        return unchecked((int)(bits ^ (long)((ulong)bits >> 32)));
    }
    internal static int Signum(long value) => Math.Sign(value);
    internal static string ToHexString(int value) =>
        unchecked((uint)value).ToString("x", CultureInfo.InvariantCulture);
    internal static string ToHexString(long value) =>
        unchecked((ulong)value).ToString("x", CultureInfo.InvariantCulture);
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
    internal static string ToStringRadix(BigInteger value, int radix)
    {
        const string digits = "0123456789abcdefghijklmnopqrstuvwxyz";
        if (radix is < 2 or > 36) radix = 10;
        if (value.IsZero) return "0";
        var negative = value.Sign < 0;
        var remaining = BigInteger.Abs(value);
        var result = new StringBuilder();
        while (!remaining.IsZero)
        {
            remaining = BigInteger.DivRem(remaining, radix, out var remainder);
            result.Append(digits[(int)remainder]);
        }
        if (negative) result.Append('-');
        var characters = result.ToString().ToCharArray();
        Array.Reverse(characters);
        return new string(characters);
    }
    internal static int ToUnsignedInt(sbyte value) => unchecked((byte)value);

    internal static int CharacterCharCount(int codePoint) => codePoint >= 0x10000 ? 2 : 1;

    internal static string CharacterName(int codePoint)
    {
        if (!IsValidCodePoint(codePoint))
            throw new ArgumentException("Invalid Unicode code point.", nameof(codePoint));
        if (codePoint is >= 'A' and <= 'Z')
            return $"LATIN CAPITAL LETTER {(char)codePoint}";
        if (codePoint is >= 'a' and <= 'z')
            return $"LATIN SMALL LETTER {char.ToUpperInvariant((char)codePoint)}";
        if (codePoint is >= '0' and <= '9')
        {
            string[] digitNames =
                ["ZERO", "ONE", "TWO", "THREE", "FOUR",
                 "FIVE", "SIX", "SEVEN", "EIGHT", "NINE"];
            return $"DIGIT {digitNames[codePoint - '0']}";
        }
        if (codePoint == ' ') return "SPACE";
        return $"U+{codePoint:X4}";
    }

    internal static bool CharacterIsDefined(int codePoint)
    {
        if (codePoint is >= 0xd800 and <= 0xdfff) return true;
        return Rune.IsValid(codePoint) &&
            Rune.GetUnicodeCategory(new Rune(codePoint)) !=
                UnicodeCategory.OtherNotAssigned;
    }
    internal static bool IsBmpCodePoint(int codePoint) => (uint)codePoint <= 0xffff;
    internal static bool IsValidCodePoint(int codePoint) => (uint)codePoint <= 0x10ffff;

    internal static IEnumerable<int> StringCodePoints(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        for (var index = 0; index < value.Length; index++)
        {
            var first = value[index];
            if (char.IsHighSurrogate(first) &&
                index + 1 < value.Length &&
                char.IsLowSurrogate(value[index + 1]))
            {
                yield return char.ConvertToUtf32(first, value[++index]);
            }
            else
            {
                yield return first;
            }
        }
    }

    internal static int StringCodePointCount(string value, int beginIndex, int endIndex)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (beginIndex < 0 || endIndex > value.Length || beginIndex > endIndex)
        {
            throw new IndexOutOfRangeException();
        }
        var count = 0;
        for (var index = beginIndex; index < endIndex; index++, count++)
        {
            if (char.IsHighSurrogate(value[index]) &&
                index + 1 < endIndex &&
                char.IsLowSurrogate(value[index + 1]))
            {
                index++;
            }
        }
        return count;
    }

    internal static T ClassCast<T>(Type type, object value) =>
        type.IsInstanceOfType(value) ? (T)value : throw new InvalidCastException();
    internal static Type ClassAsSubclass(Type type, Type parentType) =>
        parentType.IsAssignableFrom(type)
            ? type
            : throw new InvalidCastException(
                $"{type.FullName ?? type.Name} is not a subclass of {parentType.FullName ?? parentType.Name}.");
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
        type.GetCustomAttributes(true).FirstOrDefault(annotationType.IsInstanceOfType) as T;
    internal static ConstructorInfo ClassGetDeclaredConstructor(Type type, params Type[] parameterTypes) =>
        type.GetConstructor(
            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance,
            binder: null,
            types: parameterTypes,
            modifiers: null)
        ?? throw new MissingMethodException(type.FullName, ".ctor");
    internal static ConstructorInfo ClassGetConstructor(Type type, params Type[] parameterTypes) =>
        type.GetConstructor(
            BindingFlags.Public | BindingFlags.Instance,
            binder: null,
            types: parameterTypes,
            modifiers: null)
        ?? throw new MissingMethodException(type.FullName, ".ctor");
    internal static T ConstructorInvoke<T>(ConstructorInfo constructor, params object?[] arguments) =>
        (T)constructor.Invoke(arguments);
    internal static T? FieldGetAnnotation<T>(FieldInfo field, Type annotationType) where T : class =>
        field.GetCustomAttributes(true).FirstOrDefault(annotationType.IsInstanceOfType) as T;
    internal static bool MemberIsAnnotationPresent(MemberInfo member, Type annotationType) =>
        member.GetCustomAttributes(true).Any(annotationType.IsInstanceOfType);
    internal static Exception NewThrowable()
    {
        var throwable = new Exception();
        System.Runtime.ExceptionServices.ExceptionDispatchInfo.SetCurrentStackTrace(throwable);
        return throwable;
    }

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
    internal static IDictionary<string, string> GetEnvironment() =>
        Environment.GetEnvironmentVariables().Cast<System.Collections.DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string?)entry.Value ?? string.Empty,
                          StringComparer.Ordinal);

    internal static IDictionary<string, string> GetProperties() =>
        new Dictionary<string, string>(SystemProperties, StringComparer.Ordinal);

    internal static string? GetProperty(string name) =>
        SystemProperties.TryGetValue(name, out var value) ? value : null;

    internal static string GetProperty(string name, string fallback) =>
        GetProperty(name) ?? fallback;

    internal static T Clone<T>(T value) =>
        value is TimeZoneInfo or string
            ? value
            : value is ICloneable cloneable
                ? (T)cloneable.Clone()!
                : throw new NotSupportedException(
                    $"Java clone is not available for destination type {value!.GetType()}.");

    internal static string? SetProperty(string name, string value)
    {
        var previous = GetProperty(name);
        SystemProperties[name] = value;
        return previous;
    }

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
    private sealed class JavaCause
    {
        internal JavaCause(Exception? value) => Value = value;
        internal Exception? Value { get; }
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Exception, JavaCause>
        JavaCauses = new();
    private static readonly object JavaCausesLock = new();
    private static readonly System.Reflection.FieldInfo? ExceptionInnerExceptionField =
        typeof(Exception).GetField(
            "_innerException",
            System.Reflection.BindingFlags.Instance |
            System.Reflection.BindingFlags.NonPublic)
        ?? typeof(Exception).GetField(
            "m_innerException",
            System.Reflection.BindingFlags.Instance |
            System.Reflection.BindingFlags.NonPublic);

    internal static Exception InitCause(Exception exception, Exception? cause)
    {
        ArgumentNullException.ThrowIfNull(exception);
        if (ReferenceEquals(exception, cause))
            throw new ArgumentException("Self-causation is not permitted.", nameof(cause));
        lock (JavaCausesLock)
        {
            if (exception.InnerException is not null || JavaCauses.TryGetValue(exception, out _))
                throw new InvalidOperationException("Cause has already been initialized.");
            JavaCauses.Add(exception, new JavaCause(cause));
            if (cause is not null)
                ExceptionInnerExceptionField?.SetValue(exception, cause);
        }
        return exception;
    }
    internal static Exception? GetCause(Exception exception)
    {
        ArgumentNullException.ThrowIfNull(exception);
        return JavaCauses.TryGetValue(exception, out var cause)
            ? cause.Value
            : exception.InnerException;
    }
    internal static int IdentityHashCode(object value) =>
        System.Runtime.CompilerServices.RuntimeHelpers.GetHashCode(value);

    internal static long NanoTime() =>
        checked((long)(System.Diagnostics.Stopwatch.GetTimestamp() *
                       (1_000_000_000.0 / System.Diagnostics.Stopwatch.Frequency)));
    internal static object? ConsoleInstance() =>
        !Console.IsInputRedirected && !Console.IsOutputRedirected ? new object() : null;

    internal static string Format(JavaFormat format, object? value) => format.Format(value);

    internal static V Unbox<V>(V? value) where V : struct =>
        value ?? throw new NullReferenceException("Cannot unbox a null Java boxed value.");
    internal static V UnboxObject<V>(object? value) where V : struct =>
        value is V result
            ? result
            : value is null
                ? throw new NullReferenceException("Cannot unbox a null Java boxed value.")
                : throw new InvalidCastException(
                    $"Cannot unbox {value.GetType()} as {typeof(V)}.");
    internal static bool Unbox(bool value) => value;
    internal static sbyte Unbox(sbyte value) => value;
    internal static short Unbox(short value) => value;
    internal static int Unbox(int value) => value;
    internal static long Unbox(long value) => value;
    internal static float Unbox(float value) => value;
    internal static double Unbox(double value) => value;
    internal static char Unbox(char value) => value;

}
