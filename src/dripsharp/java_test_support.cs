// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Generated suites copy this authored support source verbatim.
#nullable enable
using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Runtime.CompilerServices;
using System.Runtime.ExceptionServices;
using System.Threading;
using Castle.DynamicProxy;
using Xunit;
using Xunit.Sdk;

namespace DripSharp.Testing;

public delegate T JavaAnswer<out T>(object? invocation);

public static class JavaAssertions
{
    public static void AssumeTrue(bool condition, object? message)
    {
        if (!condition)
            throw SkipException.ForSkip(Message(message, "JUnit assumption failed"));
    }

    internal static string Message(object? message, string fallback)
    {
        if (message is null) return fallback;
        if (message is Func<string> supplier) return supplier();
        if (message is Delegate callback)
        {
            try
            {
                object? supplied = callback.DynamicInvoke();
                return supplied?.ToString() ?? fallback;
            }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            {
                ExceptionDispatchInfo.Capture(error.InnerException).Throw();
                throw new InvalidOperationException("unreachable");
            }
        }
        return message.ToString() ?? fallback;
    }

    internal static bool DeepEqual(object? expected, object? actual)
    {
        if (ReferenceEquals(expected, actual)) return true;
        if (expected is null || actual is null) return false;
        if (expected is string || actual is string) return expected.Equals(actual);
        if (TryEntry(expected, out object? expectedKey, out object? expectedValue) &&
            TryEntry(actual, out object? actualKey, out object? actualValue))
        {
            return DeepEqual(expectedKey, actualKey) && DeepEqual(expectedValue, actualValue);
        }
        if (expected is IDictionary expectedMap && actual is IDictionary actualMap)
        {
            if (expectedMap.Count != actualMap.Count) return false;
            foreach (DictionaryEntry pair in expectedMap)
            {
                if (!actualMap.Contains(pair.Key) ||
                    !DeepEqual(pair.Value, actualMap[pair.Key])) return false;
            }
            return true;
        }
        if (IsSet(expected) && IsSet(actual) &&
            expected is IEnumerable expectedSet && actual is IEnumerable actualSet)
        {
            List<object?> expectedItems = expectedSet.Cast<object?>().ToList();
            List<object?> remaining = actualSet.Cast<object?>().ToList();
            if (expectedItems.Count != remaining.Count) return false;
            foreach (object? item in expectedItems)
            {
                int index = remaining.FindIndex(value => DeepEqual(item, value));
                if (index < 0) return false;
                remaining.RemoveAt(index);
            }
            return true;
        }
        if (expected is IEnumerable expectedValues && actual is IEnumerable actualValues)
        {
            return expectedValues.Cast<object?>().SequenceEqual(
                actualValues.Cast<object?>(), DeepComparer.Instance);
        }
        return expected.GetType() == actual.GetType() && expected.Equals(actual);
    }

    private static bool IsSet(object value) => value.GetType().GetInterfaces().Any(
        type => type.IsGenericType && type.GetGenericTypeDefinition() == typeof(ISet<>));

    private static bool TryEntry(object value, out object? key, out object? entryValue)
    {
        if (value is DictionaryEntry entry)
        {
            key = entry.Key;
            entryValue = entry.Value;
            return true;
        }
        Type type = value.GetType();
        if (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(KeyValuePair<,>))
        {
            key = type.GetProperty("Key")!.GetValue(value);
            entryValue = type.GetProperty("Value")!.GetValue(value);
            return true;
        }
        key = null;
        entryValue = null;
        return false;
    }

    private sealed class DeepComparer : IEqualityComparer<object?>
    {
        internal static readonly DeepComparer Instance = new();
        public new bool Equals(object? left, object? right) => DeepEqual(left, right);
        public int GetHashCode(object? value) => value?.GetHashCode() ?? 0;
    }

    private static double Number(object value) => Convert.ToDouble(value);

    private static bool DeltaEqual(object expected, object actual, double delta)
    {
        if (expected is IEnumerable expectedValues && expected is not string &&
            actual is IEnumerable actualValues && actual is not string)
        {
            object?[] expectedItems = expectedValues.Cast<object?>().ToArray();
            object?[] actualItems = actualValues.Cast<object?>().ToArray();
            return expectedItems.Length == actualItems.Length &&
                expectedItems.Select((item, index) =>
                    item is not null && actualItems[index] is not null &&
                    DeltaEqual(item, actualItems[index]!, delta)).All(value => value);
        }
        double expectedNumber = Number(expected);
        double actualNumber = Number(actual);
        if (expectedNumber.Equals(actualNumber)) return true;
        if (double.IsNaN(expectedNumber) || double.IsNaN(actualNumber))
            return double.IsNaN(expectedNumber) && double.IsNaN(actualNumber);
        return Math.Abs(expectedNumber - actualNumber) <= delta;
    }

    public static void Equal(object? expected, object? actual, object? message)
    {
        if (!DeepEqual(expected, actual))
        {
            Assert.Fail(Message(message, $"Expected <{expected}> but was <{actual}>."));
        }
    }

    public static void Equal(object expected, object actual, object? message, double delta)
    {
        if (!DeltaEqual(expected, actual, delta))
        {
            Assert.Fail(Message(message,
                $"Expected <{expected}> +/- <{delta}> but was <{actual}>."));
        }
    }

    public static void NotEqual(object? unexpected, object? actual, object? message)
    {
        if (DeepEqual(unexpected, actual))
        {
            Assert.Fail(Message(message, $"Did not expect <{actual}>."));
        }
    }

    public static void NotEqual(object unexpected, object actual, object? message, double delta)
    {
        if (DeltaEqual(unexpected, actual, delta))
        {
            Assert.Fail(Message(message,
                $"Did not expect <{actual}> within <{delta}> of <{unexpected}>."));
        }
    }

    public static void True(bool condition, object? message)
    {
        if (!condition) Assert.Fail(Message(message, "Expected condition to be true."));
    }

    public static void False(bool condition, object? message)
    {
        if (condition) Assert.Fail(Message(message, "Expected condition to be false."));
    }

    public static void Null(object? value, object? message)
    {
        if (value is not null) Assert.Fail(Message(message, $"Expected null but was <{value}>."));
    }

    public static void NotNull(object? value, object? message)
    {
        if (value is null) Assert.Fail(Message(message, "Expected a non-null value."));
    }

    public static void Same(object? expected, object? actual, object? message)
    {
        if (ReferenceEquals(expected, actual)) return;
        if (expected is not null && actual is not null &&
            expected.GetType().IsValueType && actual.GetType().IsValueType &&
            expected.Equals(actual)) return;
        Assert.Fail(Message(message, "Expected the same object instance."));
    }

    public static void NotSame(object? unexpected, object? actual, object? message)
    {
        if (ReferenceEquals(unexpected, actual))
            Assert.Fail(Message(message, "Expected different object instances."));
    }

    public static T InstanceOf<T>(object? actual, object? message)
    {
        if (actual is T typed) return typed;
        if (actual is not null &&
            typeof(T).Name == "JavaNumberFormatException" &&
            actual.GetType().Name == "JavaNumberFormatException")
        {
            // Generated product assemblies each own an internal compatibility
            // exception type for this one Java class.
            return default!;
        }
        Assert.Fail(Message(message,
            $"Expected instance of <{typeof(T).FullName}> but was <{actual?.GetType().FullName ?? "null"}>."));
        throw new InvalidOperationException("unreachable");
    }

    public static T Throws<T>(Action action, object? message) where T : Exception
    {
        try
        {
            action();
        }
        catch (Exception error)
        {
            if (error is T expected) return expected;
            Assert.Fail(Message(message,
                $"Expected <{typeof(T).FullName}> but caught <{error.GetType().FullName}>."));
        }
        Assert.Fail(Message(message, $"Expected <{typeof(T).FullName}> to be thrown."));
        throw new InvalidOperationException("unreachable");
    }

    public static T ThrowsExactly<T>(Action action, object? message) where T : Exception
    {
        try
        {
            action();
        }
        catch (Exception error)
        {
            if (error.GetType() == typeof(T)) return (T)error;
            Assert.Fail(Message(message,
                $"Expected exactly <{typeof(T).FullName}> but caught <{error.GetType().FullName}>."));
        }
        Assert.Fail(Message(message, $"Expected exactly <{typeof(T).FullName}> to be thrown."));
        throw new InvalidOperationException("unreachable");
    }

    public static void DoesNotThrow(Action action, object? message)
    {
        try { action(); }
        catch (Exception error)
        {
            Assert.Fail(Message(message,
                $"Expected no exception but caught <{error.GetType().FullName}>: {error.Message}"));
        }
    }

    public static T DoesNotThrow<T>(Func<T> action, object? message)
    {
        try { return action(); }
        catch (Exception error)
        {
            Assert.Fail(Message(message,
                $"Expected no exception but caught <{error.GetType().FullName}>: {error.Message}"));
            throw new InvalidOperationException("unreachable");
        }
    }

    public static void All(params object?[] arguments)
    {
        object? message = arguments.FirstOrDefault(value => value is string);
        var failures = new List<Exception>();
        IEnumerable<Delegate> Executables(object? value)
        {
            if (value is Delegate executable) yield return executable;
            else if (value is IEnumerable values && value is not string)
            {
                foreach (object? item in values)
                    foreach (Delegate nested in Executables(item)) yield return nested;
            }
        }
        foreach (Delegate executable in arguments.SelectMany(Executables))
        {
            try { executable.DynamicInvoke(); }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            { failures.Add(error.InnerException); }
            catch (Exception error) { failures.Add(error); }
        }
        if (failures.Count != 0)
        {
            Assert.Fail(Message(message,
                string.Join(Environment.NewLine, failures.Select(error => error.Message))));
        }
    }

    public static void Fail(object? message) =>
        Assert.Fail(Message(message, "Assertion failed."));
}

public static class JavaAssertJ
{
    public static JavaAssertJAssertion That(object? actual) => new(actual);
    public static JavaAssertJAssertion ThrownBy(Action action) => new(CatchThrowable(action));
    public static JavaAssertJAssertion ExceptionOfType(Type type) => new(null, type);
    public static Exception? CatchThrowable(Action action)
    {
        try { action(); return null; }
        catch (Exception error) { return error; }
    }
    public static KeyValuePair<object?, object?> Entry(object? key, object? value) => new(key, value);
}

public sealed class JavaAssertJAssertion
{
    private readonly object? actual;
    private readonly Type? expectedExceptionType;
    private string? description;
    private string? failMessage;

    internal JavaAssertJAssertion(object? actual, Type? expectedExceptionType = null)
    {
        this.actual = actual;
        this.expectedExceptionType = expectedExceptionType;
    }

    private string Prefix(string fallback) =>
        failMessage ?? (description is null ? fallback : $"[{description}] {fallback}");

    private JavaAssertJAssertion Check(bool condition, string failure)
    {
        if (!condition) Assert.Fail(Prefix(failure));
        return this;
    }

    private static List<object?> Values(object? value)
    {
        if (value is null || value is string || value is not IEnumerable enumerable)
            return new List<object?>();
        return enumerable.Cast<object?>().ToList();
    }

    private static string Format(string template, object?[] arguments)
    {
        string result = template;
        foreach (object? argument in arguments)
            result = result.Replace("%s", argument?.ToString() ?? "null", StringComparison.Ordinal);
        return result;
    }

    public JavaAssertJAssertion As(string text, params object?[] arguments)
    { description = Format(text, arguments); return this; }
    public JavaAssertJAssertion DescribedAs(string text, params object?[] arguments) => As(text, arguments);
    public JavaAssertJAssertion WithFailMessage(string text, params object?[] arguments)
    { failMessage = Format(text, arguments); return this; }
    public JavaAssertJAssertion IsEqualTo(object? expected) =>
        Check(JavaAssertions.DeepEqual(expected, actual), $"Expected <{expected}> but was <{actual}>.");
    public JavaAssertJAssertion IsNotEqualTo(object? unexpected) =>
        Check(!JavaAssertions.DeepEqual(unexpected, actual), $"Did not expect <{actual}>.");
    public JavaAssertJAssertion IsSameAs(object? expected) =>
        Check(ReferenceEquals(expected, actual), "Expected the same object instance.");
    public JavaAssertJAssertion IsNull() => Check(actual is null, $"Expected null but was <{actual}>.");
    public JavaAssertJAssertion IsNotNull() => Check(actual is not null, "Expected a non-null value.");
    public JavaAssertJAssertion IsTrue() => Check(actual is true, "Expected true.");
    public JavaAssertJAssertion IsFalse() => Check(actual is false, "Expected false.");
    public JavaAssertJAssertion IsInstanceOf(Type type) =>
        Check(actual is not null && type.IsInstanceOfType(actual),
              $"Expected instance of <{type.FullName}> but was <{actual?.GetType().FullName ?? "null"}>.");
    public JavaAssertJAssertion IsEmpty() => Check(Size(actual) == 0, "Expected an empty value.");
    public JavaAssertJAssertion IsNotEmpty() => Check(Size(actual) > 0, "Expected a non-empty value.");
    public JavaAssertJAssertion HasSize(int expected) =>
        Check(Size(actual) == expected, $"Expected size <{expected}> but was <{Size(actual)}>.");
    public JavaAssertJAssertion StartsWith(string prefix) =>
        Check(actual is string text && text.StartsWith(prefix, StringComparison.Ordinal),
              $"Expected <{actual}> to start with <{prefix}>.");
    public JavaAssertJAssertion EndsWith(string suffix) =>
        Check(actual is string text && text.EndsWith(suffix, StringComparison.Ordinal),
              $"Expected <{actual}> to end with <{suffix}>.");

    private static int Size(object? value) => value switch
    {
        string text => text.Length,
        ICollection collection => collection.Count,
        IEnumerable enumerable => enumerable.Cast<object?>().Count(),
        _ => -1
    };

    public JavaAssertJAssertion Contains(params object?[] expected)
    {
        if (actual is string text && expected.Length == 1 && expected[0] is string fragment)
            return Check(text.Contains(fragment, StringComparison.Ordinal),
                         $"Expected <{text}> to contain <{fragment}>.");
        List<object?> values = Values(actual);
        return Check(expected.All(item => values.Any(value => JavaAssertions.DeepEqual(item, value))),
                     "Expected collection to contain every requested value.");
    }

    public JavaAssertJAssertion DoesNotContain(params object?[] unexpected)
    {
        if (actual is string text && unexpected.Length == 1 && unexpected[0] is string fragment)
            return Check(!text.Contains(fragment, StringComparison.Ordinal),
                         $"Expected <{text}> not to contain <{fragment}>.");
        List<object?> values = Values(actual);
        return Check(unexpected.All(item => values.All(value => !JavaAssertions.DeepEqual(item, value))),
                     "Expected collection not to contain the requested values.");
    }

    public JavaAssertJAssertion ContainsExactly(params object?[] expected) =>
        Check(JavaAssertions.DeepEqual(expected, Values(actual).ToArray()),
              "Collection contents or order differed.");

    public JavaAssertJAssertion ContainsExactlyInAnyOrder(params object?[] expected)
    {
        List<object?> remaining = Values(actual);
        foreach (object? item in expected)
        {
            int index = remaining.FindIndex(value => JavaAssertions.DeepEqual(item, value));
            if (index < 0) return Check(false, "Collection contents differed.");
            remaining.RemoveAt(index);
        }
        return Check(remaining.Count == 0, "Collection contained unexpected values.");
    }

    public JavaAssertJAssertion ContainsOnly(params object?[] expected)
    {
        List<object?> values = Values(actual);
        return Check(
            expected.All(item => values.Any(value => JavaAssertions.DeepEqual(item, value))) &&
            values.All(value => expected.Any(item => JavaAssertions.DeepEqual(item, value))),
            "Collection contents differed.");
    }

    public JavaAssertJAssertion ContainsEntry(object? key, object? value)
    {
        if (actual is not IDictionary map)
            return Check(false, "Expected a map value.");
        return Check(map.Contains(key!) && JavaAssertions.DeepEqual(value, map[key!]),
                     $"Expected map entry <{key}={value}>.");
    }

    public JavaAssertJAssertion ContainsAllEntriesOf(IDictionary expected)
    {
        foreach (DictionaryEntry pair in expected) ContainsEntry(pair.Key, pair.Value);
        return this;
    }

    public JavaAssertJAssertion Extracting(Delegate extractor)
    {
        if (actual is IEnumerable && actual is not string)
        {
            object?[] extracted = Values(actual)
                .Select(value => extractor.DynamicInvoke(value)).ToArray();
            return new JavaAssertJAssertion(extracted);
        }
        return new JavaAssertJAssertion(extractor.DynamicInvoke(actual));
    }

    public JavaAssertJAssertion AllSatisfy(Delegate consumer)
    {
        var failures = new List<Exception>();
        foreach (object? value in Values(actual))
        {
            try { consumer.DynamicInvoke(value); }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            { failures.Add(error.InnerException); }
            catch (Exception error) { failures.Add(error); }
        }
        return Check(failures.Count == 0,
            string.Join(Environment.NewLine, failures.Select(error => error.Message)));
    }

    public JavaAssertJAssertion AsString() =>
        new(actual?.ToString() ?? "null");

    public JavaAssertJAssertion HasFieldOrPropertyWithValue(string name, object? expected)
    {
        if (actual is null) return Check(false, "Expected a non-null object.");
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public |
                                   BindingFlags.NonPublic;
        Type type = actual.GetType();
        object? value;
        PropertyInfo? property = type.GetProperty(name, flags);
        if (property is not null) value = property.GetValue(actual);
        else
        {
            FieldInfo? field = type.GetField(name, flags);
            if (field is null)
                return Check(false, $"Expected field or property <{name}>.");
            value = field.GetValue(actual);
        }
        return Check(JavaAssertions.DeepEqual(expected, value),
                     $"Expected <{name}> to be <{expected}> but was <{value}>.");
    }

    private Exception Exception() => actual as Exception ??
        throw new Xunit.Sdk.XunitException(Prefix("Expected an exception to be captured."));

    public JavaAssertJAssertion HasMessage(string expected) =>
        Check(Exception().Message == expected,
              $"Expected exception message <{expected}> but was <{Exception().Message}>.");
    public JavaAssertJAssertion HasMessageContaining(string expected) =>
        Check(Exception().Message.Contains(expected, StringComparison.Ordinal),
              $"Expected exception message to contain <{expected}>.");
    public JavaAssertJAssertion HasMessageStartingWith(string expected) =>
        Check(Exception().Message.StartsWith(expected, StringComparison.Ordinal),
              $"Expected exception message to start with <{expected}>.");
    public JavaAssertJAssertion HasCauseInstanceOf(Type type) =>
        Check(Exception().InnerException is not null &&
              type.IsInstanceOfType(Exception().InnerException),
              $"Expected exception cause of type <{type.FullName}>.");
    private Exception Root()
    {
        Exception root = Exception();
        while (root.InnerException is not null) root = root.InnerException;
        return root;
    }
    public JavaAssertJAssertion HasRootCauseInstanceOf(Type type) =>
        Check(type.IsInstanceOfType(Root()),
              $"Expected root cause of type <{type.FullName}>.");
    public JavaAssertJAssertion RootCause() => new(Root());
    public JavaAssertJAssertion IsThrownBy(Action action)
    {
        Exception? error = JavaAssertJ.CatchThrowable(action);
        return Check(error is not null &&
                     (expectedExceptionType is null || expectedExceptionType.IsInstanceOfType(error)),
                     $"Expected <{expectedExceptionType?.FullName ?? "an exception"}> to be thrown.");
    }
}

public sealed class JavaMatcher
{
    private readonly Func<object?, bool> predicate;
    internal JavaMatcher(Func<object?, bool> predicate, string description)
    { this.predicate = predicate; Description = description; }
    public string Description { get; }
    public bool Matches(object? actual) => predicate(actual);
}

public static class JavaHamcrest
{
    public static void AssertThat(params object?[] arguments)
    {
        string? reason = arguments.Length == 3 ? arguments[0]?.ToString() : null;
        object? actual = arguments[^2];
        if (arguments[^1] is not JavaMatcher matcher)
        {
            Assert.Fail("Resolved Hamcrest assertion did not receive a matcher.");
            throw new InvalidOperationException("unreachable");
        }
        if (!matcher.Matches(actual))
            Assert.Fail(reason ?? $"Expected {matcher.Description} but was <{actual}>.");
    }

    private static List<object?> Values(object? value) =>
        value is IEnumerable enumerable && value is not string
            ? enumerable.Cast<object?>().ToList() : new List<object?>();
    public static JavaMatcher Anything() => new(_ => true, "anything");
    public static JavaMatcher Anything(string description) => new(_ => true, description);
    public static JavaMatcher EqualTo(object? expected) =>
        new(actual => JavaAssertions.DeepEqual(expected, actual), $"equal to <{expected}>");
    public static JavaMatcher Is(JavaMatcher matcher) => matcher;
    public static JavaMatcher Is(Type type) => InstanceOf(type);
    public static JavaMatcher Is(object? expected) => EqualTo(expected);
    public static JavaMatcher Not(JavaMatcher matcher) =>
        new(actual => !matcher.Matches(actual), $"not ({matcher.Description})");
    public static JavaMatcher Not(object? expected) => Not(EqualTo(expected));
    public static JavaMatcher NullValue() => new(actual => actual is null, "null");
    public static JavaMatcher NotNullValue() => new(actual => actual is not null, "not null");
    public static JavaMatcher SameInstance(object? expected) =>
        new(actual => ReferenceEquals(expected, actual), "the same instance");
    public static JavaMatcher InstanceOf(Type type) =>
        new(actual => actual is not null && type.IsInstanceOfType(actual), $"instance of {type.FullName}");
    public static JavaMatcher IsA(Type type) => InstanceOf(type);
    public static JavaMatcher ContainsString(string value) =>
        new(actual => actual is string text && text.Contains(value, StringComparison.Ordinal), $"containing <{value}>");
    public static JavaMatcher StartsWith(string value) =>
        new(actual => actual is string text && text.StartsWith(value, StringComparison.Ordinal), $"starting with <{value}>");
    public static JavaMatcher EndsWith(string value) =>
        new(actual => actual is string text && text.EndsWith(value, StringComparison.Ordinal), $"ending with <{value}>");
    public static JavaMatcher AllOf(params JavaMatcher[] matchers) =>
        new(actual => matchers.All(matcher => matcher.Matches(actual)), "all matchers");
    public static JavaMatcher AnyOf(params JavaMatcher[] matchers) =>
        new(actual => matchers.Any(matcher => matcher.Matches(actual)), "any matcher");
    private static bool ExpectedMatches(object? expected, object? actual) =>
        expected is JavaMatcher matcher ? matcher.Matches(actual) :
        JavaAssertions.DeepEqual(expected, actual);
    public static JavaMatcher HasItem(object? expected) =>
        new(actual => Values(actual).Any(value => ExpectedMatches(expected, value)), $"has item <{expected}>");
    public static JavaMatcher HasItems(params object?[] expected) =>
        new(actual => expected.All(item => Values(actual).Any(value => ExpectedMatches(item, value))), "has items");
    public static JavaMatcher Contains(params object?[] expected) =>
        new(actual =>
        {
            List<object?> values = Values(actual);
            return values.Count == expected.Length &&
                expected.Select((item, index) => ExpectedMatches(item, values[index])).All(value => value);
        }, "contains in order");
    public static JavaMatcher ContainsInAnyOrder(params object?[] expected) =>
        new(actual => MultisetEqual(expected, Values(actual)), "contains in any order");
    public static JavaMatcher Empty() => new(actual => Values(actual).Count == 0, "empty");
    public static JavaMatcher EmptyIterable() => Empty();
    private static bool MultisetEqual(IEnumerable<object?> expected, List<object?> actual)
    {
        var remaining = new List<object?>(actual);
        foreach (object? item in expected)
        {
            int index = remaining.FindIndex(value => ExpectedMatches(item, value));
            if (index < 0) return false;
            remaining.RemoveAt(index);
        }
        return remaining.Count == 0;
    }
    private static int Compare(object? left, object? right) =>
        left is IComparable comparable ? comparable.CompareTo(right) : throw new InvalidOperationException("Value is not comparable.");
    public static JavaMatcher GreaterThan(object? expected) => new(actual => Compare(actual, expected) > 0, $"> {expected}");
    public static JavaMatcher GreaterThanOrEqualTo(object? expected) => new(actual => Compare(actual, expected) >= 0, $">= {expected}");
    public static JavaMatcher LessThan(object? expected) => new(actual => Compare(actual, expected) < 0, $"< {expected}");
    public static JavaMatcher LessThanOrEqualTo(object? expected) => new(actual => Compare(actual, expected) <= 0, $"<= {expected}");
    public static JavaMatcher CloseTo(double expected, double delta) =>
        new(actual => actual is not null && Math.Abs(Convert.ToDouble(actual) - expected) <= delta,
            $"within {delta} of {expected}");
}

public readonly record struct JavaVerificationMode(int Minimum, int Maximum, int DelayMilliseconds, bool Poll, bool Only);

internal sealed class JavaArgumentMatcher
{
    internal JavaArgumentMatcher(Func<object?, bool> predicate, string description)
    { Predicate = predicate; Description = description; }
    internal Func<object?, bool> Predicate { get; }
    internal string Description { get; }
}

internal sealed class JavaCapturedInvocation
{
    internal JavaCapturedInvocation(JavaMockInterceptor interceptor, MethodInfo method,
                                    object?[] arguments, JavaArgumentMatcher[] matchers)
    { Interceptor = interceptor; Method = method; Arguments = arguments; Matchers = matchers; }
    internal JavaMockInterceptor Interceptor { get; }
    internal MethodInfo Method { get; }
    internal object?[] Arguments { get; }
    internal JavaArgumentMatcher[] Matchers { get; }
    internal bool Verified { get; set; }
    internal bool Matches(MethodInfo method, object?[] arguments) =>
        Method == method && arguments.Length == Matchers.Length &&
        arguments.Select((argument, index) => Matchers[index].Predicate(argument)).All(value => value);
}

internal sealed class JavaStub
{
    private readonly Queue<object?> values = new();
    private object? lastValue;
    internal JavaStub(JavaCapturedInvocation invocation) { Invocation = invocation; }
    internal JavaCapturedInvocation Invocation { get; }
    internal Exception? Exception { get; set; }
    internal object? Answer { get; set; }
    internal void Returns(IEnumerable<object?> results)
    {
        foreach (object? result in results) { values.Enqueue(result); lastValue = result; }
    }
    internal object? NextValue(MethodInfo method, object?[] arguments)
    {
        if (Exception is not null) throw Exception;
        if (Answer is not null)
        {
            var invocation = new JavaInvocation(method, arguments);
            if (Answer is Delegate callback)
            {
                try { return callback.DynamicInvoke(invocation); }
                catch (TargetInvocationException error) when (error.InnerException is not null)
                {
                    ExceptionDispatchInfo.Capture(error.InnerException).Throw();
                    throw new InvalidOperationException("unreachable");
                }
            }
            MethodInfo? answerMethod = Answer.GetType().GetMethods(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                .FirstOrDefault(candidate =>
                    string.Equals(candidate.Name, "answer", StringComparison.OrdinalIgnoreCase) &&
                    candidate.GetParameters().Length == 1);
            if (answerMethod is null)
                throw new Xunit.Sdk.XunitException("Mockito Answer has no callable answer method.");
            try { return answerMethod.Invoke(Answer, new object?[] { invocation }); }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            {
                ExceptionDispatchInfo.Capture(error.InnerException).Throw();
                throw new InvalidOperationException("unreachable");
            }
        }
        return values.Count > 1 ? values.Dequeue() : (values.Count == 1 ? (lastValue = values.Dequeue()) : lastValue);
    }
}

public sealed class JavaInvocation
{
    internal JavaInvocation(MethodInfo method, object?[] arguments)
    { Method = method; Arguments = arguments; }
    public MethodInfo Method { get; }
    public object?[] Arguments { get; }
    public object? GetArgument(int index) => Arguments[index];
}

internal sealed class JavaMockInterceptor : IInterceptor
{
    private readonly List<JavaCapturedInvocation> invocations = new();
    private readonly List<JavaStub> stubs = new();
    private readonly object? target;
    private JavaVerificationMode? verification;
    private object? pendingAnswer;

    internal JavaMockInterceptor(object? target = null) { this.target = target; }

    public void Intercept(IInvocation invocation)
    {
        invocation.ReturnValue = Dispatch(invocation.Proxy, invocation.Method, invocation.Arguments);
    }

    internal object? Dispatch(object self, MethodInfo method, object?[] arguments)
    {
        if (method.DeclaringType == typeof(object))
        {
            if (method.Name == nameof(object.Equals) && arguments.Length == 1)
                return ReferenceEquals(self, arguments[0]);
            if (method.Name == nameof(object.GetHashCode) && arguments.Length == 0)
                return RuntimeHelpers.GetHashCode(self);
            if (method.Name == nameof(object.ToString) && arguments.Length == 0)
                return $"Mock<{self.GetType().BaseType?.FullName ?? self.GetType().FullName}>";
        }
        JavaArgumentMatcher[] matchers = JavaMockito.ConsumeMatchers(arguments);
        var captured = new JavaCapturedInvocation(this, method,
            arguments.ToArray(), matchers);
        if (pendingAnswer is not null)
        {
            object answer = pendingAnswer;
            pendingAnswer = null;
            JavaStub pending = BeginStubbing(captured);
            pending.Answer = answer;
            return JavaMockito.Default(method.ReturnType);
        }
        if (verification is JavaVerificationMode mode)
        {
            verification = null;
            Verify(captured, mode);
            return JavaMockito.Default(method.ReturnType);
        }
        invocations.Add(captured);
        JavaMockito.LastInvocation.Value = captured;
        JavaStub? stub = stubs.LastOrDefault(candidate =>
            candidate.Invocation.Matches(method, arguments));
        if (stub is not null) return stub.NextValue(method, arguments);
        if (target is null) return JavaMockito.Default(method.ReturnType);
        try { return method.Invoke(target, arguments); }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw new InvalidOperationException("unreachable");
        }
    }

    internal JavaStub BeginStubbing(JavaCapturedInvocation captured)
    {
        invocations.Remove(captured);
        var stub = new JavaStub(captured);
        stubs.Add(stub);
        return stub;
    }

    internal void BeginVerify(JavaVerificationMode mode) => verification = mode;
    internal void BeginWill(object answer) => pendingAnswer = answer;
    private void Verify(JavaCapturedInvocation wanted, JavaVerificationMode mode)
    {
        if (mode.DelayMilliseconds > 0 && !mode.Poll) Thread.Sleep(mode.DelayMilliseconds);
        DateTime deadline = DateTime.UtcNow.AddMilliseconds(mode.DelayMilliseconds);
        int count;
        do
        {
            count = invocations.Count(candidate => wanted.Matches(candidate.Method, candidate.Arguments));
            if (count >= mode.Minimum && count <= mode.Maximum) break;
            if (!mode.Poll || DateTime.UtcNow >= deadline) break;
            Thread.Sleep(10);
        } while (true);
        if (count < mode.Minimum || count > mode.Maximum ||
            (mode.Only && invocations.Count != count))
            Assert.Fail($"Mockito verification expected {mode.Minimum}..{mode.Maximum} matching calls but observed {count}.");
        foreach (JavaCapturedInvocation candidate in invocations.Where(candidate =>
                     wanted.Matches(candidate.Method, candidate.Arguments))) candidate.Verified = true;
    }
    internal void Clear() => invocations.Clear();
    internal void Reset()
    { invocations.Clear(); stubs.Clear(); verification = null; pendingAnswer = null; }
    internal void VerifyNoInteractions()
    { if (invocations.Count != 0) Assert.Fail($"Expected no mock interactions but observed {invocations.Count}."); }
    internal void VerifyNoMoreInteractions()
    { if (invocations.Any(invocation => !invocation.Verified)) Assert.Fail("Expected no unverified mock interactions."); }
}

internal static class JavaUninitializedProxyFactory
{
    private static readonly AssemblyBuilder Assembly = AssemblyBuilder.DefineDynamicAssembly(
        new AssemblyName("DripSharp.JavaMockito.DynamicProxies"), AssemblyBuilderAccess.Run);
    private static readonly ModuleBuilder Module = Assembly.DefineDynamicModule("Proxies");
    private static readonly ConcurrentDictionary<Type, Type> Cache = new();
    private static int nextType;

    internal static object Create(Type baseType)
    {
        if (baseType.IsSealed)
            throw new Xunit.Sdk.XunitException($"Mockito cannot mock sealed class <{baseType.FullName}>.");
        Type proxyType = Cache.GetOrAdd(baseType, Build);
        return RuntimeHelpers.GetUninitializedObject(proxyType);
    }

    private static Type SubstituteGenericType(
        Type type, IReadOnlyDictionary<Type, Type> substitutions)
    {
        if (substitutions.TryGetValue(type, out Type? replacement)) return replacement;
        if (type.IsByRef) return SubstituteGenericType(type.GetElementType()!, substitutions).MakeByRefType();
        if (type.IsPointer) return SubstituteGenericType(type.GetElementType()!, substitutions).MakePointerType();
        if (type.IsArray)
        {
            Type element = SubstituteGenericType(type.GetElementType()!, substitutions);
            return type.GetArrayRank() == 1 ? element.MakeArrayType() : element.MakeArrayType(type.GetArrayRank());
        }
        if (type.IsGenericType)
        {
            return type.GetGenericTypeDefinition().MakeGenericType(
                type.GetGenericArguments()
                    .Select(argument => SubstituteGenericType(argument, substitutions)).ToArray());
        }
        return type;
    }

    private static Type Build(Type baseType)
    {
        string name = $"JavaMockitoProxy_{Interlocked.Increment(ref nextType)}_{baseType.Name}";
        TypeBuilder builder = Module.DefineType(name,
            TypeAttributes.Public | TypeAttributes.Sealed | TypeAttributes.Class,
            baseType);
        ConstructorInfo baseConstructor = baseType.GetConstructors(
                BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
            .OrderBy(constructor => constructor.GetParameters().Length)
            .FirstOrDefault() ?? throw new Xunit.Sdk.XunitException(
                $"Mockito class <{baseType.FullName}> has no constructor metadata.");
        ConstructorBuilder constructor = builder.DefineConstructor(
            MethodAttributes.Public, CallingConventions.Standard, Type.EmptyTypes);
        ILGenerator constructorIl = constructor.GetILGenerator();
        constructorIl.Emit(OpCodes.Ldarg_0);
        foreach (ParameterInfo parameter in baseConstructor.GetParameters())
        {
            if (!parameter.ParameterType.IsValueType)
            {
                constructorIl.Emit(OpCodes.Ldnull);
            }
            else
            {
                LocalBuilder defaultValue = constructorIl.DeclareLocal(parameter.ParameterType);
                constructorIl.Emit(OpCodes.Ldloca, defaultValue);
                constructorIl.Emit(OpCodes.Initobj, parameter.ParameterType);
                constructorIl.Emit(OpCodes.Ldloc, defaultValue);
            }
        }
        constructorIl.Emit(OpCodes.Call, baseConstructor);
        constructorIl.Emit(OpCodes.Ret);
        MethodInfo[] methods = baseType.GetMethods(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic);
        foreach (MethodInfo method in methods)
        {
            if (!method.IsVirtual || method.IsFinal || method.IsStatic ||
                method.ReturnType.IsByRef ||
                method.GetParameters().Any(parameter => parameter.ParameterType.IsByRef) ||
                !(method.IsPublic || method.IsFamily || method.IsFamilyOrAssembly)) continue;

            Type[] sourceParameterTypes = method.GetParameters()
                .Select(parameter => parameter.ParameterType).ToArray();
            if (methods.Any(candidate => candidate != method &&
                    candidate.Name == method.Name &&
                    method.DeclaringType!.IsAssignableFrom(candidate.DeclaringType!) &&
                    candidate.GetParameters().Select(parameter => parameter.ParameterType)
                        .SequenceEqual(sourceParameterTypes))) continue;

            MethodAttributes visibility = method.IsPublic ? MethodAttributes.Public :
                (method.IsFamily ? MethodAttributes.Family : MethodAttributes.FamORAssem);
            MethodAttributes attributes = visibility | MethodAttributes.Virtual |
                MethodAttributes.HideBySig;
            if (method.IsSpecialName) attributes |= MethodAttributes.SpecialName;
            ParameterInfo[] parameters = method.GetParameters();
            MethodBuilder emitted = builder.DefineMethod(
                method.Name, attributes, method.CallingConvention);
            var substitutions = new Dictionary<Type, Type>();
            GenericTypeParameterBuilder[] emittedArguments = Array.Empty<GenericTypeParameterBuilder>();
            if (method.IsGenericMethodDefinition)
            {
                Type[] genericArguments = method.GetGenericArguments();
                emittedArguments = emitted.DefineGenericParameters(
                    genericArguments.Select(argument => argument.Name).ToArray());
                for (int index = 0; index < genericArguments.Length; index++)
                    substitutions[genericArguments[index]] = emittedArguments[index];
                for (int index = 0; index < genericArguments.Length; index++)
                {
                    Type argument = genericArguments[index];
                    GenericTypeParameterBuilder emittedArgument = emittedArguments[index];
                    emittedArgument.SetGenericParameterAttributes(argument.GenericParameterAttributes);
                    Type[] constraints = argument.GetGenericParameterConstraints()
                        .Select(constraint => SubstituteGenericType(constraint, substitutions)).ToArray();
                    Type? baseConstraint = constraints.FirstOrDefault(constraint => !constraint.IsInterface);
                    if (baseConstraint is not null) emittedArgument.SetBaseTypeConstraint(baseConstraint);
                    Type[] interfaces = constraints.Where(constraint => constraint.IsInterface).ToArray();
                    if (interfaces.Length > 0) emittedArgument.SetInterfaceConstraints(interfaces);
                }
            }
            Type returnType = SubstituteGenericType(method.ReturnType, substitutions);
            Type[] parameterTypes = sourceParameterTypes
                .Select(type => SubstituteGenericType(type, substitutions)).ToArray();
            emitted.SetSignature(
                returnType,
                method.ReturnParameter.GetRequiredCustomModifiers(),
                method.ReturnParameter.GetOptionalCustomModifiers(),
                parameterTypes,
                parameters.Select(parameter => parameter.GetRequiredCustomModifiers()).ToArray(),
                parameters.Select(parameter => parameter.GetOptionalCustomModifiers()).ToArray());
            for (int index = 0; index < parameters.Length; index++)
                emitted.DefineParameter(index + 1, parameters[index].Attributes, parameters[index].Name);

            ILGenerator il = emitted.GetILGenerator();
            il.Emit(OpCodes.Ldarg_0);
            il.Emit(OpCodes.Ldtoken, method);
            il.Emit(OpCodes.Ldtoken, method.DeclaringType!);
            il.Emit(OpCodes.Call, typeof(MethodBase).GetMethod(
                nameof(MethodBase.GetMethodFromHandle),
                new[] { typeof(RuntimeMethodHandle), typeof(RuntimeTypeHandle) })!);
            il.Emit(OpCodes.Castclass, typeof(MethodInfo));
            if (emittedArguments.Length > 0)
            {
                il.Emit(OpCodes.Callvirt, typeof(MethodInfo).GetMethod(
                    nameof(MethodInfo.GetGenericMethodDefinition), Type.EmptyTypes)!);
                il.Emit(OpCodes.Ldc_I4, emittedArguments.Length);
                il.Emit(OpCodes.Newarr, typeof(Type));
                for (int index = 0; index < emittedArguments.Length; index++)
                {
                    il.Emit(OpCodes.Dup);
                    il.Emit(OpCodes.Ldc_I4, index);
                    il.Emit(OpCodes.Ldtoken, emittedArguments[index]);
                    il.Emit(OpCodes.Call, typeof(Type).GetMethod(nameof(Type.GetTypeFromHandle))!);
                    il.Emit(OpCodes.Stelem_Ref);
                }
                il.Emit(OpCodes.Callvirt, typeof(MethodInfo).GetMethod(
                    nameof(MethodInfo.MakeGenericMethod), new[] { typeof(Type[]) })!);
            }
            il.Emit(OpCodes.Ldc_I4, parameterTypes.Length);
            il.Emit(OpCodes.Newarr, typeof(object));
            for (int index = 0; index < parameterTypes.Length; index++)
            {
                il.Emit(OpCodes.Dup);
                il.Emit(OpCodes.Ldc_I4, index);
                il.Emit(OpCodes.Ldarg, index + 1);
                if (parameterTypes[index].IsValueType || parameterTypes[index].IsGenericParameter)
                    il.Emit(OpCodes.Box, parameterTypes[index]);
                il.Emit(OpCodes.Stelem_Ref);
            }
            il.Emit(OpCodes.Call, typeof(JavaMockito).GetMethod(
                nameof(JavaMockito.Dispatch), BindingFlags.Public | BindingFlags.Static)!);
            if (returnType == typeof(void)) il.Emit(OpCodes.Pop);
            else if (returnType.IsValueType || returnType.IsGenericParameter)
                il.Emit(OpCodes.Unbox_Any, returnType);
            else il.Emit(OpCodes.Castclass, returnType);
            il.Emit(OpCodes.Ret);
            builder.DefineMethodOverride(emitted, method);
        }
        return builder.CreateType()!;
    }
}

public sealed class JavaStubbing<T>
{
    private readonly JavaStub stub;
    internal JavaStubbing(JavaStub stub) { this.stub = stub; }
    public JavaStubbing<T> ThenReturn(params T[] values) { stub.Returns(values.Cast<object?>()); return this; }
    public JavaStubbing<T> WillReturn(params T[] values) => ThenReturn(values);
    public JavaStubbing<T> ThenThrow(Exception error) { stub.Exception = error; return this; }
    public JavaStubbing<T> WillThrow(Exception error) => ThenThrow(error);
}

public sealed class JavaThen<T> where T : class
{
    private readonly T mock;
    internal JavaThen(T mock) { this.mock = mock; }
    public T Should() => JavaMockito.Verify(mock);
    public T Should(JavaVerificationMode mode) => JavaMockito.Verify(mock, mode);
}

public sealed class JavaWill
{
    private readonly object answer;
    internal JavaWill(object answer) { this.answer = answer; }
    public T Given<T>(T mock) where T : class
    {
        JavaMockito.PrepareWill(mock, answer);
        return mock;
    }
}

public static class JavaMockito
{
    private static readonly ProxyGenerator Generator = new();
    private static readonly ConditionalWeakTable<object, JavaMockInterceptor> Interceptors = new();
    private static readonly AsyncLocal<List<JavaArgumentMatcher>?> Matchers = new();
    internal static readonly AsyncLocal<JavaCapturedInvocation?> LastInvocation = new();

    public static T Mock<T>() where T : class
    {
        var interceptor = new JavaMockInterceptor();
        T proxy = typeof(T).IsInterface
            ? Generator.CreateInterfaceProxyWithoutTarget<T>(interceptor)
            : (T)JavaUninitializedProxyFactory.Create(typeof(T));
        Interceptors.Add(proxy, interceptor);
        return proxy;
    }

    public static T Spy<T>(T target) where T : class
    {
        var interceptor = new JavaMockInterceptor(target);
        T proxy = typeof(T).IsInterface
            ? Generator.CreateInterfaceProxyWithTarget<T>(target, interceptor)
            : (T)JavaUninitializedProxyFactory.Create(typeof(T));
        Interceptors.Add(proxy, interceptor);
        return proxy;
    }

    public static object? Dispatch(object proxy, MethodInfo method, object?[] arguments) =>
        Interceptor(proxy).Dispatch(proxy, method, arguments);

    public static JavaStubbing<T> Given<T>(T ignored)
    {
        JavaCapturedInvocation captured = LastInvocation.Value ??
            throw new Xunit.Sdk.XunitException("Mockito stubbing has no captured resolved invocation.");
        LastInvocation.Value = null;
        return new JavaStubbing<T>(captured.Interceptor.BeginStubbing(captured));
    }

    public static JavaThen<T> Then<T>(T mock) where T : class => new(mock);
    public static JavaWill Will(object answer) => new(answer);
    internal static void PrepareWill(object mock, object answer) =>
        Interceptor(mock).BeginWill(answer);

    public static T Verify<T>(T mock) where T : class => Verify(mock, Times(1));
    public static T Verify<T>(T mock, JavaVerificationMode mode) where T : class
    {
        Interceptor(mock).BeginVerify(mode);
        return mock;
    }
    public static JavaVerificationMode Times(int count) => new(count, count, 0, false, false);
    public static JavaVerificationMode Never() => Times(0);
    public static JavaVerificationMode Only() => new(1, 1, 0, false, true);
    public static JavaVerificationMode AtLeast(int count) => new(count, int.MaxValue, 0, false, false);
    public static JavaVerificationMode AtLeastOnce() => AtLeast(1);
    public static JavaVerificationMode AtMost(int count) => new(0, count, 0, false, false);
    public static JavaVerificationMode AtMostOnce() => AtMost(1);
    public static JavaVerificationMode After(long milliseconds) => new(1, 1, checked((int)milliseconds), false, false);
    public static JavaVerificationMode Timeout(long milliseconds) => new(1, 1, checked((int)milliseconds), true, false);

    private static JavaMockInterceptor Interceptor(object mock) =>
        Interceptors.TryGetValue(mock, out JavaMockInterceptor? interceptor) ? interceptor :
        throw new Xunit.Sdk.XunitException("Object was not created by the Java Mockito adapter.");
    public static void ClearInvocations(params object[] mocks)
    { foreach (object mock in mocks) Interceptor(mock).Clear(); }
    public static void Reset(params object[] mocks)
    { foreach (object mock in mocks) Interceptor(mock).Reset(); }
    public static void VerifyNoInteractions(params object[] mocks)
    { foreach (object mock in mocks) Interceptor(mock).VerifyNoInteractions(); }
    public static void VerifyNoMoreInteractions(params object[] mocks)
    { foreach (object mock in mocks) Interceptor(mock).VerifyNoMoreInteractions(); }

    private static T AddMatcher<T>(Func<object?, bool> predicate, string description)
    {
        (Matchers.Value ??= new List<JavaArgumentMatcher>()).Add(new(predicate, description));
        return default!;
    }
    public static T Any<T>() => AddMatcher<T>(_ => true, "any");
    public static T Any<T>(Type ignored) => Any<T>();
    public static T Eq<T>(T expected) => AddMatcher<T>(actual => JavaAssertions.DeepEqual(expected, actual), $"equal to {expected}");
    public static T Same<T>(T expected) => AddMatcher<T>(actual => ReferenceEquals(expected, actual), "same instance");
    public static T IsNull<T>() => AddMatcher<T>(actual => actual is null, "null");
    public static T NotNull<T>() => AddMatcher<T>(actual => actual is not null, "not null");
    public static T ArgThat<T>(Predicate<T> predicate) => AddMatcher<T>(actual => actual is T typed && predicate(typed), "predicate");
    public static bool AnyBoolean() => Any<bool>();
    public static byte AnyByte() => Any<byte>();
    public static char AnyChar() => Any<char>();
    public static double AnyDouble() => Any<double>();
    public static float AnyFloat() => Any<float>();
    public static int AnyInt() => Any<int>();
    public static long AnyLong() => Any<long>();
    public static short AnyShort() => Any<short>();
    public static string AnyString() => Any<string>();

    internal static JavaArgumentMatcher[] ConsumeMatchers(object?[] arguments)
    {
        List<JavaArgumentMatcher>? pending = Matchers.Value;
        Matchers.Value = null;
        if (pending is null || pending.Count == 0)
            return arguments.Select(argument => new JavaArgumentMatcher(
                actual => JavaAssertions.DeepEqual(argument, actual), $"equal to {argument}")).ToArray();
        if (pending.Count != arguments.Length)
            throw new Xunit.Sdk.XunitException("Mockito requires matchers for every argument when any matcher is used.");
        return pending.ToArray();
    }

    internal static object? Default(Type type) =>
        type == typeof(void) ? null : (type.IsValueType ? Activator.CreateInstance(type) : null);
}
