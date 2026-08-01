// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Idiomatic .NET value-model adapters for Brine's Pkl surface.
#nullable enable
using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace DripSharp.Brine;

public sealed partial class Pair<F, S> { }

internal static class PairEquality
{
    internal static bool EqualsPair(object? first, object? second, object? candidate)
    {
        if (candidate is null) return false;
        Type type = candidate.GetType();
        if (!type.IsGenericType || type.GetGenericTypeDefinition() != typeof(Pair<,>))
            return false;
        object? otherFirst = type.GetMethod("GetFirst", Type.EmptyTypes)!.Invoke(candidate, null);
        object? otherSecond = type.GetMethod("GetSecond", Type.EmptyTypes)!.Invoke(candidate, null);
        return DripSharp.Runtime.JavaCompat.Equals(first, otherFirst) &&
            DripSharp.Runtime.JavaCompat.Equals(second, otherSecond);
    }
}

internal static class DotNetCollections
{
    internal static IReadOnlyList<T> ReadOnly<T>(IList<T> values) =>
        new ReadOnlyCollection<T>(values);

    internal static IReadOnlyList<T> ReadOnly<T>(IReadOnlyList<T> values) =>
        values is IList<T> mutable ? new ReadOnlyCollection<T>(mutable) : values;

    internal static IReadOnlyDictionary<TKey, TValue> ReadOnly<TKey, TValue>(
        IDictionary<TKey, TValue> values) where TKey : notnull =>
        new ReadOnlyDictionary<TKey, TValue>(values);

    internal static IReadOnlyDictionary<TKey, TValue> ReadOnly<TKey, TValue>(
        IReadOnlyDictionary<TKey, TValue> values) where TKey : notnull =>
        values is IDictionary<TKey, TValue> mutable
            ? new ReadOnlyDictionary<TKey, TValue>(mutable)
            : values;

    internal static IReadOnlySet<T> ReadOnly<T>(IReadOnlySet<T> values) =>
        values is ISet<T> mutable ? new ReadOnlySet<T>(mutable) : values;

    internal static IReadOnlySet<T> ReadOnly<T>(ISet<T> values) =>
        new ReadOnlySet<T>(values);

    private sealed class ReadOnlySet<T> : IReadOnlySet<T>
    {
        private readonly HashSet<T> values;

        internal ReadOnlySet(IEnumerable<T> values) => this.values = new HashSet<T>(values);

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
}

public sealed partial class ModuleSource
{
    public static ModuleSource FromPath(string path) => PathFromString(path);
    public static ModuleSource FromFile(string path) => FileFromString(path);
    public static ModuleSource FromText(string text) => Text(text);
    public static ModuleSource FromUri(string uri) => Uri(uri);
    public static ModuleSource FromUri(Uri uri) => Uri(uri);
    public static ModuleSource FromModulePath(string path) => ModulePath(path);

    public Uri SourceUri => GetUri();
    public string? Contents => GetContents();
}

public partial interface FileOutput
{
    public string Text => GetText();
    public byte[] Bytes => GetBytes();
}

public partial interface Evaluator
{
    public IReadOnlyDictionary<string, FileOutput> EvaluateOutputFilesReadOnly(
        ModuleSource moduleSource) => DotNetCollections.ReadOnly(EvaluateOutputFiles(moduleSource));

    public TestResults EvaluateTests(ModuleSource moduleSource, bool overwriteExpected = false) =>
        EvaluateTest(moduleSource, overwriteExpected);
}

internal static class PklPath
{
    public static string ResolvePosix(Uri baseUri, string path)
    {
        ArgumentNullException.ThrowIfNull(baseUri);
        ArgumentNullException.ThrowIfNull(path);
        return Util.PathResolvers.ForPosix().ResolvePath(baseUri, path);
    }

    public static string ResolveWindows(Uri baseUri, string path)
    {
        ArgumentNullException.ThrowIfNull(baseUri);
        ArgumentNullException.ThrowIfNull(path);
        return Util.PathResolvers.ForWindows().ResolvePath(baseUri, path);
    }
}

internal static class PklGlob
{
    public static System.Text.RegularExpressions.Regex Compile(string pattern)
    {
        ArgumentNullException.ThrowIfNull(pattern);
        try
        {
            return Util.GlobResolver.ToRegexPattern(pattern);
        }
        catch (Util.GlobResolver.InvalidGlobPatternException error)
        {
            throw new ArgumentException(error.Message, nameof(pattern), error);
        }
    }
}

internal static class PklUris
{
    public static Uri EnsurePathEndsWithSlash(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri) return EnsureRelativePathEndsWithSlash(uri);
        string schemePrefix = uri.Scheme + ":";
        string afterScheme = uri.OriginalString[schemePrefix.Length..];
        if (!afterScheme.StartsWith("/", StringComparison.Ordinal)) return uri;
        if (!uri.AbsolutePath.StartsWith("/", StringComparison.Ordinal)) return uri;
        if (uri.AbsolutePath.EndsWith("/", StringComparison.Ordinal)) return uri;
        var builder = new UriBuilder(uri) { Path = uri.AbsolutePath + "/" };
        return builder.Uri;
    }

    public static Uri Resolve(Uri baseUri, Uri newUri)
    {
        ArgumentNullException.ThrowIfNull(baseUri);
        ArgumentNullException.ThrowIfNull(newUri);
        if (newUri.IsAbsoluteUri) return newUri;
        string baseText = DripSharp.Runtime.JavaCompat.UriToString(baseUri);
        if (DripSharp.Runtime.JavaCompat.UriUsesSingleSlashFileSyntax(baseUri) ||
            (baseText.StartsWith("file:/", StringComparison.OrdinalIgnoreCase) &&
                !baseText.StartsWith("file://", StringComparison.OrdinalIgnoreCase)))
        {
            string basePath = baseText["file:".Length..];
            var carrier = new Uri("https://dripsharp.invalid" + basePath);
            Uri resolved = new(carrier, newUri);
            return DripSharp.Runtime.JavaCompat.CreateUri(
                "file:" + resolved.AbsolutePath + resolved.Query + resolved.Fragment);
        }
        return Util.IoUtils.Resolve(baseUri, newUri);
    }

    public static string Format(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        return DripSharp.Runtime.JavaCompat.UriToString(uri);
    }

    public static string Relativize(string path, string basePath)
    {
        ArgumentNullException.ThrowIfNull(path);
        ArgumentNullException.ThrowIfNull(basePath);
        return Util.IoUtils.Relativize(path, basePath);
    }

    public static Uri Relativize(Uri uri, Uri baseUri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        ArgumentNullException.ThrowIfNull(baseUri);
        if (!uri.IsAbsoluteUri || !baseUri.IsAbsoluteUri ||
            DripSharp.Runtime.JavaCompat.UriIsOpaque(uri) ||
            DripSharp.Runtime.JavaCompat.UriIsOpaque(baseUri) ||
            !string.Equals(uri.Scheme, baseUri.Scheme, StringComparison.Ordinal) ||
            !string.Equals(
                DripSharp.Runtime.JavaCompat.UriRawAuthority(uri),
                DripSharp.Runtime.JavaCompat.UriRawAuthority(baseUri),
                StringComparison.Ordinal))
            return uri;

        string uriPath = DripSharp.Runtime.JavaCompat.UriPath(uri) ?? "";
        string basePath = DripSharp.Runtime.JavaCompat.UriPath(baseUri) ?? "";
        if (basePath.Length == 0) return uri;
        var uriParts = uriPath.Split('/', StringSplitOptions.RemoveEmptyEntries).ToList();
        var baseParts = basePath.Split('/', StringSplitOptions.RemoveEmptyEntries).ToList();
        if (!basePath.EndsWith("/", StringComparison.Ordinal) && baseParts.Count > 0)
            baseParts.RemoveAt(baseParts.Count - 1);
        int common = 0;
        while (common < uriParts.Count && common < baseParts.Count &&
            uriParts[common] == baseParts[common]) common++;
        string relative = string.Concat(Enumerable.Repeat("../", baseParts.Count - common)) +
            string.Join("/", uriParts.Skip(common));
        return new Uri(relative + uri.Query + uri.Fragment, UriKind.Relative);
    }

    public static Uri Parse(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return Util.IoUtils.ToUri(value);
    }

    public static string? ToFilePath(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri)
            throw new ArgumentException("Only absolute URIs can be converted to paths.", nameof(uri));
        return uri.IsFile ? uri.LocalPath : null;
    }

    public static bool IsWhitespace(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        return value.All(char.IsWhiteSpace);
    }

    public static string Capitalize(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (value.Length == 0 || !char.IsLower(value[0])) return value;
        return char.ToUpperInvariant(value[0]) + value[1..];
    }

    public static int GetMaxLineLength(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        int maximum = 0;
        int current = 0;
        foreach (char character in value)
        {
            if (character == '\n')
            {
                maximum = Math.Max(maximum, current);
                current = 0;
            }
            else if (character != '\r')
            {
                current++;
            }
        }
        return Math.Max(maximum, current);
    }

    public static string EncodePath(string path)
    {
        ArgumentNullException.ThrowIfNull(path);
        return Util.IoUtils.EncodePath(path);
    }

    public static string InferModuleName(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        string value = uri.OriginalString;
        int fragment = value.LastIndexOf("#/", StringComparison.Ordinal);
        string path = fragment >= 0 ? value[(fragment + 2)..] :
            uri.IsAbsoluteUri && uri.AbsolutePath.Length > 0 ? uri.AbsolutePath :
            value[(value.LastIndexOf(':') + 1)..];
        path = path.TrimEnd('/');
        int slash = path.LastIndexOf('/');
        string name = slash < 0 ? path : path[(slash + 1)..];
        if (uri.IsAbsoluteUri && uri.Scheme.Equals("pkl", StringComparison.OrdinalIgnoreCase))
        {
            int separator = name.LastIndexOf('.');
            return separator < 0 ? name : name[(separator + 1)..];
        }
        int dot = name.LastIndexOf('.');
        return dot <= 0 ? name : name[..dot];
    }

    public static byte[] ReadBytes(Uri uri)
    {
        string? path = ToFilePath(uri);
        if (path is null)
            throw new ArgumentException("Only file URIs can be read directly.", nameof(uri));
        return File.ReadAllBytes(path);
    }

    public static string ReadText(Uri uri) => Encoding.UTF8.GetString(ReadBytes(uri));

    public static Uri ResolveTripleDotFile(Uri moduleUri, Uri importUri)
    {
        ArgumentNullException.ThrowIfNull(moduleUri);
        ArgumentNullException.ThrowIfNull(importUri);
        string? modulePath = ToFilePath(moduleUri);
        if (modulePath is null)
            throw new ArgumentException("The module URI must use the file scheme.", nameof(moduleUri));
        string target = ParseTripleDot(importUri, Path.GetFileName(modulePath));
        string fullModulePath = Path.GetFullPath(modulePath);
        DirectoryInfo? directory = Directory.GetParent(modulePath)?.Parent;
        while (directory is not null)
        {
            string candidate = Path.GetFullPath(Path.Combine(directory.FullName,
                target.Replace('/', Path.DirectorySeparatorChar)));
            if (!string.Equals(candidate, fullModulePath, StringComparison.Ordinal) &&
                File.Exists(candidate)) return new Uri(candidate);
            directory = directory.Parent;
        }
        throw new FileNotFoundException(
            $"Could not resolve triple-dot import `{importUri.OriginalString}`.");
    }

    public static Uri ResolveTripleDotModulePath(
        Uri moduleUri,
        Uri importUri,
        IReadOnlySet<Uri> availableModules)
    {
        ArgumentNullException.ThrowIfNull(moduleUri);
        ArgumentNullException.ThrowIfNull(importUri);
        ArgumentNullException.ThrowIfNull(availableModules);
        string target = ParseTripleDot(importUri,
            moduleUri.AbsolutePath[(moduleUri.AbsolutePath.LastIndexOf('/') + 1)..]);
        string current = moduleUri.AbsolutePath;
        int slash = current.LastIndexOf('/');
        current = slash <= 0 ? "/" : current[..slash];
        while (true)
        {
            string candidatePath = (current.TrimEnd('/') + "/" + target).Replace("//", "/");
            var candidate = new Uri($"{moduleUri.Scheme}:{candidatePath}");
            if (availableModules.Contains(candidate)) return candidate;
            if (current == "/") break;
            slash = current.LastIndexOf('/');
            current = slash <= 0 ? "/" : current[..slash];
        }
        throw new FileNotFoundException(
            $"Could not resolve triple-dot import `{importUri.OriginalString}`.");
    }

    private static string ParseTripleDot(Uri importUri, string currentFileName)
    {
        if (importUri.IsAbsoluteUri)
            throw new ArgumentException("A triple-dot import must be relative.", nameof(importUri));
        string value = importUri.OriginalString;
        if (value == "...") return currentFileName;
        if (!value.StartsWith(".../", StringComparison.Ordinal) || value.Length == 4)
            throw new UriFormatException($"Invalid triple-dot import `{value}`.");
        return value[4..];
    }

    private static Uri EnsureRelativePathEndsWithSlash(Uri uri)
    {
        string value = uri.OriginalString;
        int suffix = value.IndexOfAny(new[] { '?', '#' });
        string path = suffix < 0 ? value : value[..suffix];
        if (path.EndsWith("/", StringComparison.Ordinal)) return uri;
        string tail = suffix < 0 ? "" : value[suffix..];
        return new Uri(path + "/" + tail, UriKind.Relative);
    }
}

internal enum PklAnsiCode
{
    Bold,
    Red
}

internal static class PklValueRenderer
{
    public static string RenderNull(int lengthLimit = 80) =>
        Runtime.VmValueRenderer.SingleLine(lengthLimit).Render(Runtime.VmNull.WithoutDefault());

    public static string RenderBytes(byte[] value, int lengthLimit = 80)
    {
        ArgumentNullException.ThrowIfNull(value);
        return Runtime.VmValueRenderer.SingleLine(lengthLimit).Render(new Runtime.VmBytes(value));
    }
}

internal static class PklExceptions
{
    public static Exception RootCause(Exception value)
    {
        ArgumentNullException.ThrowIfNull(value);
        while (value.InnerException is not null) value = value.InnerException;
        return value;
    }

    public static string RootReason(Exception value)
    {
        Exception root = RootCause(value);
        var messageField = typeof(Exception).GetField(
            "_message", System.Reflection.BindingFlags.Instance |
                System.Reflection.BindingFlags.NonPublic);
        string? message = messageField is null
            ? root.Message
            : messageField.GetValue(root) as string;
        return string.IsNullOrEmpty(message) ? "(unknown reason)" : message;
    }
}

internal sealed class PklAnsiBuilder
{
    private readonly bool enabled;
    private readonly StringBuilder builder = new();
    private readonly HashSet<PklAnsiCode> active = new();

    public PklAnsiBuilder(bool enabled) => this.enabled = enabled;

    public PklAnsiBuilder Append(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        Reset();
        builder.Append(text);
        return this;
    }

    public PklAnsiBuilder Append(PklAnsiCode code, string text) =>
        Append(new[] { code }, text);

    public PklAnsiBuilder Append(IEnumerable<PklAnsiCode> codes, string text)
    {
        ArgumentNullException.ThrowIfNull(codes);
        ArgumentNullException.ThrowIfNull(text);
        if (!enabled)
        {
            builder.Append(text);
            return this;
        }
        var requested = new HashSet<PklAnsiCode>(codes);
        if (!active.IsSubsetOf(requested)) Reset();
        var additions = requested.Where(code => !active.Contains(code)).ToList();
        if (additions.Count > 0)
        {
            string sequence = string.Join(";", additions.OrderBy(CodeNumber).Select(CodeNumber));
            builder.Append("\u001b[").Append(sequence).Append('m');
            active.UnionWith(additions);
        }
        builder.Append(text);
        return this;
    }

    public override string ToString()
    {
        Reset();
        return builder.ToString();
    }

    private static int CodeNumber(PklAnsiCode code) => code switch
    {
        PklAnsiCode.Bold => 1,
        PklAnsiCode.Red => 31,
        _ => throw new ArgumentOutOfRangeException(nameof(code))
    };

    private void Reset()
    {
        if (enabled && active.Count > 0) builder.Append("\u001b[0m");
        active.Clear();
    }
}

internal sealed class PklTextEscaper
{
    private readonly IReadOnlyDictionary<char, string> escapes;

    private PklTextEscaper(IReadOnlyDictionary<char, string> escapes) =>
        this.escapes = escapes;

    public static Builder CreateBuilder() => new();

    public string Escape(string value)
    {
        ArgumentNullException.ThrowIfNull(value);
        int first = -1;
        for (int index = 0; index < value.Length; index++)
            if (escapes.ContainsKey(value[index])) { first = index; break; }
        if (first < 0) return value;
        var result = new StringBuilder(value.Length + 8).Append(value, 0, first);
        for (int index = first; index < value.Length; index++)
            result.Append(escapes.TryGetValue(value[index], out string? replacement)
                ? replacement : value[index]);
        return result.ToString();
    }

    public sealed class Builder
    {
        private readonly Dictionary<char, string> escapes = new();

        public Builder WithEscape(char character, string replacement)
        {
            ArgumentNullException.ThrowIfNull(replacement);
            if (character > byte.MaxValue)
                throw new InvalidOperationException(
                    "Array-backed character escapers only support characters through U+00FF.");
            escapes[character] = replacement;
            return this;
        }

        public PklTextEscaper Build() => new(
            new ReadOnlyDictionary<char, string>(new Dictionary<char, string>(escapes)));
    }
}

internal static class PklHttp
{
    public static bool IsHttpUrl(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        return Util.HttpUtils.IsHttpUrlFromURI(uri);
    }

    public static Uri WithPort(Uri uri, int port)
    {
        ArgumentNullException.ThrowIfNull(uri);
        return Util.HttpUtils.SetPort(uri, port);
    }

    public static void RequireSuccessStatusCode(int statusCode)
    {
        if (statusCode != 200)
            throw new IOException($"Unexpected HTTP response status code `{statusCode}`.");
    }
}

internal static class PklStrings
{
    public static int CodePointOffsetToUtf16Offset(
        string value,
        int codePointOffset,
        int startIndex = 0)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (codePointOffset < 0 || startIndex < 0 || startIndex > value.Length) return -1;
        int index = startIndex;
        for (int remaining = codePointOffset; remaining > 0; remaining--)
        {
            if (index >= value.Length) return -1;
            index += char.IsHighSurrogate(value[index]) && index + 1 < value.Length &&
                char.IsLowSurrogate(value[index + 1]) ? 2 : 1;
        }
        return index;
    }

    public static int CodePointOffsetFromEndToUtf16Offset(string value, int codePointOffset)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (codePointOffset < 0) return -1;
        int index = value.Length;
        for (int remaining = codePointOffset; remaining > 0; remaining--)
        {
            if (index <= 0) return -1;
            index -= char.IsLowSurrogate(value[index - 1]) && index > 1 &&
                char.IsHighSurrogate(value[index - 2]) ? 2 : 1;
        }
        return index;
    }
}

internal static class PklClassInfos
{
    public static bool IsExactTypeOf(PClassInfo<object> classInfo, object value)
    {
        ArgumentNullException.ThrowIfNull(classInfo);
        ArgumentNullException.ThrowIfNull(value);
        if (classInfo.Equals(PClassInfo<object>.Any.AsObject())) return false;
        if (classInfo.Equals(PClassInfo<object>.Typed.AsObject()) && value is not PObject)
            return false;
        return classInfo.IsExactClassOf(value);
    }
}

internal static class PklParserUtilities
{
    public static IReadOnlyList<string> FindImportsAndReads(string source)
    {
        ArgumentNullException.ThrowIfNull(source);
        Module.ModuleKey key = Module.ModuleKeys.CreateSynthetic(new Uri("repl:text"), source);
        return Ast.Builder.ImportsAndReadsParser.Parse(
                key, key.Resolve(SecurityManagers.defaultManager))
            .Select(entry => entry.StringValue)
            .ToList().AsReadOnly();
    }
}

internal static class PklImportGraphs
{
    public static IReadOnlyList<IReadOnlyList<Uri>> FindCycles(ImportGraph graph)
    {
        ArgumentNullException.ThrowIfNull(graph);
        return Util.ImportGraphUtils.FindImportCycles(graph)
            .Select(cycle => (IReadOnlyList<Uri>)cycle.ToList().AsReadOnly())
            .ToList().AsReadOnly();
    }
}

internal enum PklValuePathPartKind
{
    Property,
    Element,
    WildcardProperty,
    WildcardElement,
    TopLevel
}

internal sealed record PklValuePathPart(PklValuePathPartKind Kind, object? Value = null)
{
    public static PklValuePathPart Property(string name) =>
        new(PklValuePathPartKind.Property, name);

    public static PklValuePathPart Element(object key) =>
        new(PklValuePathPartKind.Element, key);

    public static PklValuePathPart WildcardProperty { get; } =
        new(PklValuePathPartKind.WildcardProperty);

    public static PklValuePathPart WildcardElement { get; } =
        new(PklValuePathPartKind.WildcardElement);

    public static PklValuePathPart TopLevel { get; } =
        new(PklValuePathPartKind.TopLevel);
}

internal static class PklValuePaths
{
    public static IReadOnlyList<PklValuePathPart> Parse(string pathSpec)
    {
        ArgumentNullException.ThrowIfNull(pathSpec);
        try
        {
            return new Stdlib.PathSpecParser().Parse(pathSpec)
                .Select(FromInternal)
                .ToList().AsReadOnly();
        }
        catch (Exception error) when (error.GetType().Name.StartsWith("Vm", StringComparison.Ordinal))
        {
            throw new ArgumentException($"Invalid Pkl value path `{pathSpec}`.", nameof(pathSpec), error);
        }
    }

    public static bool Matches(
        IReadOnlyList<PklValuePathPart> pathSpec,
        IReadOnlyList<PklValuePathPart> path)
    {
        ArgumentNullException.ThrowIfNull(pathSpec);
        ArgumentNullException.ThrowIfNull(path);
        return Stdlib.PathConverterSupport.PathMatches(
            pathSpec.Select(ToInternal), path.Select(ToInternal));
    }

    private static PklValuePathPart FromInternal(object value)
    {
        if (ReferenceEquals(value, Runtime.VmValueConverter<object>.WILDCARD_PROPERTY))
            return PklValuePathPart.WildcardProperty;
        if (ReferenceEquals(value, Runtime.VmValueConverter<object>.WILDCARD_ELEMENT))
            return PklValuePathPart.WildcardElement;
        if (ReferenceEquals(value, Runtime.VmValueConverter<object>.TOP_LEVEL_VALUE))
            return PklValuePathPart.TopLevel;
        if (value is Runtime.Identifier identifier)
            return PklValuePathPart.Property(identifier.ToString());
        return PklValuePathPart.Element(value);
    }

    private static object ToInternal(PklValuePathPart value) => value.Kind switch
    {
        PklValuePathPartKind.Property => Runtime.Identifier.Get((string)value.Value!),
        PklValuePathPartKind.Element => value.Value!,
        PklValuePathPartKind.WildcardProperty =>
            Runtime.VmValueConverter<object>.WILDCARD_PROPERTY,
        PklValuePathPartKind.WildcardElement =>
            Runtime.VmValueConverter<object>.WILDCARD_ELEMENT,
        PklValuePathPartKind.TopLevel => Runtime.VmValueConverter<object>.TOP_LEVEL_VALUE,
        _ => throw new ArgumentOutOfRangeException(nameof(value))
    };
}

public partial class PObject
{
    public PClassInfo<object> ClassInfo => GetClassInfo();
    public IReadOnlyDictionary<string, object> Properties =>
        DotNetCollections.ReadOnly(GetProperties());
}

public sealed partial class PModule
{
    public Uri ModuleUri => GetModuleUri();
    public string ModuleName => GetModuleName();
}

public sealed partial class PNull
{
    public static PNull Instance => GetInstance();
}

public sealed partial class PklInfo
{
    static PklInfo() => CURRENT = new PklInfo();

    public PklInfo() =>
        packageIndex = new PackageIndex("https://pkl-lang.org/package-docs/");
}

public sealed partial class PClassInfo<T>
{
    public string ModuleName => GetModuleName();
    public string SimpleName => GetSimpleName();
    public string QualifiedName => GetQualifiedName();
    public string DisplayName => GetDisplayName();
    public Type ValueType => javaClass;
    public Uri ModuleUri => GetModuleUri();
    public bool IsModule => IsModuleClass();
    public bool IsExternal => IsExternalClass();
    public bool IsStandardLibrary => IsStandardLibraryClass();
    public bool IsConcreteCollection => IsConcreteCollectionClass();
}

public sealed partial class ModuleSchema
{
    public Uri ModuleUri => GetModuleUri();
    public string ModuleName => GetModuleName();
    public string ShortModuleName => GetShortModuleName();
    public bool AmendsModule => IsAmend();
    public bool ExtendsModule => IsExtend();
    public ModuleSchema? Supermodule => GetSupermodule();
    public PClass ModuleClass => GetModuleClass();
    public string? DocComment => GetDocComment();
    public IReadOnlyList<PObject> Annotations => DotNetCollections.ReadOnly(GetAnnotations());
    public IReadOnlyDictionary<string, Uri> Imports => DotNetCollections.ReadOnly(GetImports());
    public IReadOnlyDictionary<string, PClass> Classes => DotNetCollections.ReadOnly(GetClasses());
    public IReadOnlyDictionary<string, PClass> AllClasses => DotNetCollections.ReadOnly(GetAllClasses());
    public IReadOnlyDictionary<string, TypeAlias> TypeAliases => DotNetCollections.ReadOnly(GetTypeAliases());
    public IReadOnlyDictionary<string, TypeAlias> AllTypeAliases => DotNetCollections.ReadOnly(GetAllTypeAliases());
}

public abstract partial class Member
{
    public string ModuleName => GetModuleName();
    public string? DocComment => GetDocComment();
    public SourceLocation Location => GetSourceLocation();
    public IReadOnlySet<Modifier> Modifiers => DotNetCollections.ReadOnly(GetModifiers());
    public IReadOnlyList<PObject> Annotations => DotNetCollections.ReadOnly(GetAnnotations());
    public string SimpleName => GetSimpleName();
    public bool IsExternalMember => IsExternal();
    public bool IsAbstractMember => IsAbstract();
    public bool IsHiddenMember => IsHidden();
    public bool IsOpenMember => IsOpen();
    public bool IsStandardLibrary => IsStandardLibraryMember();
}

public sealed partial class PClass
{
    public string QualifiedName => GetQualifiedName();
    public string DisplayName => GetDisplayName();
    public PClassInfo<object> Info => GetInfo();
    public bool IsModule => IsModuleClass();
    public IReadOnlyList<TypeParameter> TypeParameters => DotNetCollections.ReadOnly(GetTypeParameters());
    public PType? Supertype => GetSupertype();
    public PClass? Superclass => GetSuperclass();
    public IReadOnlyDictionary<string, Property> Properties => DotNetCollections.ReadOnly(GetProperties());
    public IReadOnlyDictionary<string, Method> Methods => DotNetCollections.ReadOnly(GetMethods());
    public IReadOnlyDictionary<string, Property> AllProperties => DotNetCollections.ReadOnly(GetAllProperties());
    public IReadOnlyDictionary<string, Method> AllMethods => DotNetCollections.ReadOnly(GetAllMethods());
    public PClass ModuleClass => GetModuleClass();

    public abstract partial class ClassMember
    {
        public PClass Owner => GetOwner();
        public string? InheritedDocComment => GetInheritedDocComment();
    }

    public sealed partial class Property
    {
        public PType ValueType => GetType();
    }

    public sealed partial class Method
    {
        public IReadOnlyList<TypeParameter> TypeParameters =>
            DotNetCollections.ReadOnly(GetTypeParameters());
        public IReadOnlyDictionary<string, PType> Parameters =>
            DotNetCollections.ReadOnly(GetParameters());
        public PType ReturnType => GetReturnType();
    }
}

public sealed partial class TypeAlias
{
    public string QualifiedName => GetQualifiedName();
    public string DisplayName => GetDisplayName();
    public IReadOnlyList<TypeParameter> TypeParameters =>
        DotNetCollections.ReadOnly(GetTypeParameters());
    public PClass ModuleClass => GetModuleClass();
    public PType AliasedType => GetAliasedType();
}

public sealed partial class TypeParameter
{
    public Member Owner => GetOwner();
    public Variance VarianceValue => GetVariance();
    public string Name => GetName();
    public int Index => GetIndex();
}

public abstract partial class PType
{
    public IReadOnlyList<PType> TypeArguments => DotNetCollections.ReadOnly(GetTypeArguments());

    public sealed partial class StringLiteral
    {
        public string Literal => GetLiteral();
    }

    public sealed partial class Class
    {
        public PClass SchemaClass => GetPClass();
    }

    public sealed partial class Nullable
    {
        public PType BaseType => GetBaseType();
    }

    public sealed partial class Constrained
    {
        public PType BaseType => GetBaseType();
        public IReadOnlyList<string> Constraints => DotNetCollections.ReadOnly(GetConstraints());
    }

    public sealed partial class Alias
    {
        public TypeAlias TypeAliasValue => GetTypeAlias();
        public PType AliasedType => GetAliasedType();
    }

    public sealed partial class Function
    {
        public IReadOnlyList<PType> ParameterTypes => DotNetCollections.ReadOnly(GetParameterTypes());
        public PType ReturnType => GetReturnType();
    }

    public sealed partial class Union
    {
        public IReadOnlyList<PType> ElementTypes => DotNetCollections.ReadOnly(GetElementTypes());
    }

    public sealed partial class TypeVariable
    {
        public string Name => GetName();
        public TypeParameter TypeParameter => GetTypeParameter();
    }
}

public sealed partial class Duration
{
    public double Value => GetValue();
    public DurationUnit Unit => GetUnit();

    public TimeSpan ToTimeSpan()
    {
        double ticks = InNanos() / 100.0;
        if (!double.IsFinite(ticks) || ticks > TimeSpan.MaxValue.Ticks ||
            ticks < TimeSpan.MinValue.Ticks)
            throw new OverflowException("The Pkl duration cannot be represented as a TimeSpan.");
        return TimeSpan.FromTicks(checked((long)Math.Round(ticks, MidpointRounding.AwayFromZero)));
    }
}

public sealed partial class DataSize
{
    public double Value => GetValue();
    public DataSizeUnit Unit => GetUnit();
}

public sealed partial class Pair<F, S>
{
    public F First => GetFirst();
    public S Second => GetSecond();
}

public partial class Reference
{
    public Composite Domain => GetDomain();
    public object Data => GetData();
    public IReadOnlyList<Composite> Path => DotNetCollections.ReadOnly(GetPath());
    public PType ReferentType => GetReferentType();
}

public sealed partial class ValueFormatter
{
    public void FormatStringValue(string value, string lineIndent, System.IO.TextWriter writer)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.Write(FormatStringValue(value, lineIndent));
    }
}
