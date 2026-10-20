// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Buffers;
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

#if DRIPSHARP_SHARED_JAVA_FILE
using JavaUriMetadata = global::DripSharp.Runtime.JavaUriMetadata;
#endif

namespace DripSharp.Runtime;

// JDK compatibility area: Java.IO

#if !DRIPSHARP_SHARED_JAVA_FILE
// A Java File is a pathname, not a validated CLR FileInfo. In particular the
// empty pathname and embedded NUL survive construction on Java 17. Keep the
// lexical value until the requested operation decides how to handle it.
internal sealed class JavaFile : IEquatable<JavaFile>, IComparable<JavaFile>
{
    internal string Pathname { get; }
    private readonly FileInfo? imported;
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<FileInfo, Tuple<JavaFile, string>> Imports = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<JavaFile, FileInfo> Exports = new();
    private static bool Windows => Path.DirectorySeparatorChar == '\\';

    internal JavaFile(string pathname)
    {
        if (pathname is null) throw new NullReferenceException();
        Pathname = Normalize(pathname);
    }

    private JavaFile(FileInfo file) : this(file.FullName) { imported = file; }

    internal JavaFile(string? parent, string child)
    {
        if (child is null) throw new NullReferenceException();
        child = Normalize(child);
        if (parent is null) Pathname = child;
        else
        {
            parent = Normalize(parent);
            if (parent.Length == 0) parent = Windows ? "\\" : "/";
            bool driveRelative = Windows && parent.Length == 2 && IsDrive(parent);
            if (Windows && child.StartsWith("\\\\", StringComparison.Ordinal)) child = child.Substring(2);
            else if (!driveRelative) child = child.TrimStart(Path.DirectorySeparatorChar);
            Pathname = child.Length == 0 ? parent
                : Normalize(driveRelative ? parent + child
                    : parent.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar + child);
        }
    }

    internal JavaFile(JavaFile? parent, string child) : this(parent?.Pathname, child) { }

    internal JavaFile(Uri uri) : this(uri, uri is null ? "" : JavaCompat.UriToString(uri)) { }

    internal JavaFile(Uri uri, string original)
    {
        if (uri is null) throw new NullReferenceException();
        int colon = original.IndexOf(':');
        string path = colon < 0 ? "" : original.Substring(colon + 1);
        if (colon < 0 || !original.Substring(0, colon).Equals("file", StringComparison.OrdinalIgnoreCase) ||
            !path.StartsWith("/", StringComparison.Ordinal) ||
            path.IndexOf('?') >= 0 || path.IndexOf('#') >= 0 ||
            (path.StartsWith("//", StringComparison.Ordinal) && !path.StartsWith("///", StringComparison.Ordinal)))
            throw new ArgumentException("File URI must be absolute, hierarchical, and have no authority, query or fragment.", nameof(uri));
        if (path.StartsWith("//", StringComparison.Ordinal)) path = path.Substring(2);
        path = Uri.UnescapeDataString(path);
        if (Windows && path.Length >= 3 && path[0] == '/' && path[2] == ':') path = path.Substring(1);
        Pathname = Normalize(path);
    }

    private static string Normalize(string value)
    {
        if (Windows)
        {
            value = value.Replace('/', '\\');
            string withoutRoot = value.TrimStart('\\');
            if (IsDrive(withoutRoot)) value = withoutRoot;
        }
        char separator = Path.DirectorySeparatorChar;
        var result = new StringBuilder(value.Length);
        for (int index = 0; index < value.Length; index++)
        {
            char c = value[index];
            if (c != separator || result.Length == 0 || result[result.Length - 1] != separator ||
                (Windows && index == 1 && value[0] == separator)) result.Append(c);
        }
        int rootLength = Windows && result.Length >= 3 && result[1] == ':' && result[2] == separator
            ? 3 : Windows && value.StartsWith("\\\\", StringComparison.Ordinal) ? 2 : 1;
        if (result.Length > rootLength && result[result.Length - 1] == separator) result.Length--;
        return result.ToString();
    }

    private static bool IsDrive(string path) => path.Length >= 2 && path[1] == ':' &&
        (path[0] is >= 'A' and <= 'Z' or >= 'a' and <= 'z');
    private int PrefixLength => Windows
        ? Pathname.StartsWith("\\\\", StringComparison.Ordinal) ? 2
        : Pathname.StartsWith("\\", StringComparison.Ordinal) ? 1
        : IsDrive(Pathname) ? Pathname.Length > 2 && Pathname[2] == '\\' ? 3 : 2 : 0
        : Pathname.StartsWith("/", StringComparison.Ordinal) ? 1 : 0;
    internal bool Invalid => Pathname.IndexOf('\0') >= 0;
    internal bool Queryable => Pathname.Length != 0 && !Invalid;
    internal string Name
    {
        get
        {
            int prefix = PrefixLength;
            int last = Pathname.LastIndexOf(Path.DirectorySeparatorChar);
            return Pathname.Substring(Math.Max(prefix, last + 1));
        }
    }
    internal string? Parent
    {
        get
        {
            int last = Pathname.LastIndexOf(Path.DirectorySeparatorChar);
            int prefix = PrefixLength;
            if (last < prefix) return prefix > 0 && Pathname.Length > prefix ? Pathname.Substring(0, prefix) : null;
            return Pathname.Substring(0, last);
        }
    }
    internal bool IsAbsolute => Windows
        ? Pathname.StartsWith("\\\\", StringComparison.Ordinal) ||
            (Pathname.Length >= 3 && Pathname[1] == ':' && Pathname[2] == '\\')
        : Pathname.StartsWith("/", StringComparison.Ordinal);
    internal string AbsolutePath
    {
        get
        {
            if (IsAbsolute) return Pathname;
            string current = Environment.CurrentDirectory;
            if (Pathname.Length == 0) return current;
            if (Windows && PrefixLength == 1) return current.Substring(0, 2) + Pathname;
            if (Windows && PrefixLength == 2)
            {
                string drive = Pathname.Substring(0, 2);
                string directory = current.StartsWith(drive, StringComparison.OrdinalIgnoreCase)
                    ? current : Path.GetFullPath(drive + ".");
                return Normalize(directory + "\\" + Pathname.Substring(2));
            }
            return Normalize(current + Path.DirectorySeparatorChar + Pathname);
        }
    }
    internal string FullName => AbsolutePath;
    internal string? DirectoryName => Path.GetDirectoryName(AbsolutePath);

    // Explicit .NET API boundary: never turn an unrepresentable Java path into
    // an unrelated sentinel or current-directory FileInfo.
    internal FileInfo ToFileInfo()
    {
        if (!Queryable) throw new ArgumentException("The Java pathname cannot be represented by System.IO.FileInfo.", "path");
        try
        {
            string absolute = Path.GetFullPath(Pathname);
            lock (Imports)
            {
                FileInfo native = imported is not null && imported.FullName == absolute
                    ? imported : Exports.GetValue(this, file => new FileInfo(file.Pathname));
                if (native.FullName != absolute)
                {
                    Exports.Remove(this);
                    native = Exports.GetValue(this, file => new FileInfo(file.Pathname));
                }
                _ = Imports.GetValue(native, file => Tuple.Create(this, file.FullName));
                return native;
            }
        }
        catch (NotSupportedException error) { throw new ArgumentException("The Java pathname cannot be represented by System.IO.FileInfo.", "path", error); }
        catch (PathTooLongException error) { throw new ArgumentException("The Java pathname cannot be represented by System.IO.FileInfo.", "path", error); }
    }
    internal static JavaFile? FromFileInfo(FileInfo? file)
    {
        if (file is null) return null;
        lock (Imports)
        {
            var value = Imports.GetValue(file, f => Tuple.Create(new JavaFile(f), f.FullName));
            if (value.Item2 == file.FullName) return value.Item1;
            Imports.Remove(file);
            return Imports.GetValue(file, f => Tuple.Create(new JavaFile(f), f.FullName)).Item1;
        }
    }
    internal FileInfo OpenFileInfo()
    {
        try { return new FileInfo(OpenPath()); }
        catch (global::System.ArgumentException error) { throw new FileNotFoundException(error.Message, Pathname, error); }
        catch (NotSupportedException error) { throw new FileNotFoundException(error.Message, Pathname, error); }
    }
    internal bool Exists => Queryable && (File.Exists(Pathname) || Directory.Exists(Pathname));
    internal bool IsDirectory => Queryable && Directory.Exists(Pathname);
    internal bool IsFile => Queryable && File.Exists(Pathname);
    internal long Length
    {
        get
        {
            if (!IsFile) return 0;
            try { return new FileInfo(Pathname).Length; }
            catch (IOException) { return 0; }
            catch (UnauthorizedAccessException) { return 0; }
        }
    }
    internal JavaFile[]? ListFiles()
    {
        if (!IsDirectory) return null;
        try { return Directory.EnumerateFileSystemEntries(Pathname).Select(p => new JavaFile(p)).ToArray(); }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }
    internal string OpenPath()
    {
        if (!Queryable) throw new FileNotFoundException("Invalid or empty Java pathname.", Pathname);
        return Pathname;
    }
    public override string ToString() => Pathname;
    public bool Equals(JavaFile? other) => other is not null &&
        string.Equals(Pathname, other.Pathname, Windows ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
    public override bool Equals(object? other) => other is JavaFile file && Equals(file);
    public override int GetHashCode()
    {
        int hash = 0;
        foreach (char c in Windows ? Pathname.ToLowerInvariant() : Pathname)
            hash = unchecked(31 * hash + c);
        return hash ^ 1234321;
    }
    public int CompareTo(JavaFile? other)
    {
        if (other is null) throw new NullReferenceException();
        string left = Windows ? Pathname.ToLowerInvariant() : Pathname;
        string right = Windows ? other.Pathname.ToLowerInvariant() : other.Pathname;
        for (int i = 0; i < Math.Min(left.Length, right.Length); i++)
            if (left[i] != right[i]) return left[i] - right[i];
        return left.Length - right.Length;
    }
}

[AttributeUsage(AttributeTargets.Method, Inherited = false)]
internal sealed class JavaFileBoundaryAttribute : Attribute { }

// Public APIs retain FileInfo while calls between translated methods carry
// JavaFile values. Dispatch consults the public override first, so an external
// subclass or interface implementation remains an ordinary .NET extension.
internal static class JavaFileBridge
{
    internal static T Import<T>(object? value) => (T)ConvertValue(value, typeof(T))!;
    internal static T Export<T>(object? value) => (T)ConvertValue(value, typeof(T))!;

    private interface IProjection { object Source { get; } }
    private sealed class ListProjection<TSource, TDestination> : IList<TDestination>, IProjection
    {
        private readonly IList<TSource> source;
        public ListProjection(object source) { this.source = (IList<TSource>)source; }
        public object Source => source;
        public TDestination this[int index]
        {
            get => Import<TDestination>(source[index]);
            set => source[index] = Import<TSource>(value);
        }
        public int Count => source.Count;
        public bool IsReadOnly => source.IsReadOnly;
        public void Add(TDestination item) => source.Add(Import<TSource>(item));
        public void Clear() => source.Clear();
        public bool Contains(TDestination item) => IndexOf(item) >= 0;
        public void CopyTo(TDestination[] array, int index)
        {
            for (int i = 0; i < Count; i++) array[index + i] = this[i];
        }
        public IEnumerator<TDestination> GetEnumerator()
        {
            foreach (TSource item in source) yield return Import<TDestination>(item);
        }
        public int IndexOf(TDestination item)
        {
            var comparer = EqualityComparer<TDestination>.Default;
            for (int i = 0; i < Count; i++) if (comparer.Equals(this[i], item)) return i;
            return -1;
        }
        public void Insert(int index, TDestination item) => source.Insert(index, Import<TSource>(item));
        public bool Remove(TDestination item)
        {
            int index = IndexOf(item);
            if (index < 0) return false;
            RemoveAt(index);
            return true;
        }
        public void RemoveAt(int index) => source.RemoveAt(index);
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class EnumerableProjection<TSource, TDestination> : IEnumerable<TDestination>, IProjection
    {
        private readonly IEnumerable<TSource> source;
        public EnumerableProjection(object source) { this.source = (IEnumerable<TSource>)source; }
        public object Source => source;
        public IEnumerator<TDestination> GetEnumerator() => source.Select(value => Import<TDestination>(value)).GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }
    private sealed class CollectionProjection<TSource, TDestination> : ICollection<TDestination>, IProjection
    {
        private readonly ICollection<TSource> source;
        public CollectionProjection(object source) { this.source = (ICollection<TSource>)source; }
        public object Source => source;
        public int Count => source.Count;
        public bool IsReadOnly => source.IsReadOnly;
        public void Add(TDestination item) => source.Add(Import<TSource>(item));
        public void Clear() => source.Clear();
        public bool Contains(TDestination item) => source.Contains(Import<TSource>(item));
        public bool Remove(TDestination item) => source.Remove(Import<TSource>(item));
        public void CopyTo(TDestination[] array, int index) { foreach (var item in this) array[index++] = item; }
        public IEnumerator<TDestination> GetEnumerator() => source.Select(value => Import<TDestination>(value)).GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class SetProjection<TSource, TDestination> : ISet<TDestination>, IProjection
    {
        private readonly ISet<TSource> source;
        public SetProjection(object source) { this.source = (ISet<TSource>)source; }
        public object Source => source;
        public int Count => source.Count;
        public bool IsReadOnly => source.IsReadOnly;
        public bool Add(TDestination item) => source.Add(Import<TSource>(item));
        void ICollection<TDestination>.Add(TDestination item) => Add(item);
        public void Clear() => source.Clear();
        public bool Contains(TDestination item) => source.Contains(Import<TSource>(item));
        public bool Remove(TDestination item) => source.Remove(Import<TSource>(item));
        public void CopyTo(TDestination[] array, int index) { foreach (var item in this) array[index++] = item; }
        public IEnumerator<TDestination> GetEnumerator() => source.Select(value => Import<TDestination>(value)).GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        private static IEnumerable<TSource> Adapt(IEnumerable<TDestination> values) => values.Select(value => Import<TSource>(value));
        public void ExceptWith(IEnumerable<TDestination> other) => source.ExceptWith(Adapt(other));
        public void IntersectWith(IEnumerable<TDestination> other) => source.IntersectWith(Adapt(other));
        public void SymmetricExceptWith(IEnumerable<TDestination> other) => source.SymmetricExceptWith(Adapt(other));
        public void UnionWith(IEnumerable<TDestination> other) => source.UnionWith(Adapt(other));
        public bool IsProperSubsetOf(IEnumerable<TDestination> other) => source.IsProperSubsetOf(Adapt(other));
        public bool IsProperSupersetOf(IEnumerable<TDestination> other) => source.IsProperSupersetOf(Adapt(other));
        public bool IsSubsetOf(IEnumerable<TDestination> other) => source.IsSubsetOf(Adapt(other));
        public bool IsSupersetOf(IEnumerable<TDestination> other) => source.IsSupersetOf(Adapt(other));
        public bool Overlaps(IEnumerable<TDestination> other) => source.Overlaps(Adapt(other));
        public bool SetEquals(IEnumerable<TDestination> other) => source.SetEquals(Adapt(other));
    }

    private sealed class DictionaryProjection<TSK, TSV, TDK, TDV> : IDictionary<TDK, TDV>, IProjection
        where TSK : notnull where TDK : notnull
    {
        private readonly IDictionary<TSK, TSV> source;
        public DictionaryProjection(object source) { this.source = (IDictionary<TSK, TSV>)source; }
        public object Source => source;
        public TDV this[TDK key] { get => Import<TDV>(source[Import<TSK>(key)]); set => source[Import<TSK>(key)] = Import<TSV>(value); }
        public ICollection<TDK> Keys => new CollectionProjection<TSK, TDK>(source.Keys);
        public ICollection<TDV> Values => new CollectionProjection<TSV, TDV>(source.Values);
        public int Count => source.Count;
        public bool IsReadOnly => source.IsReadOnly;
        public void Add(TDK key, TDV value) => source.Add(Import<TSK>(key), Import<TSV>(value));
        public bool ContainsKey(TDK key) => source.ContainsKey(Import<TSK>(key));
        public bool Remove(TDK key) => source.Remove(Import<TSK>(key));
        public bool TryGetValue(TDK key, out TDV value)
        {
            bool found = source.TryGetValue(Import<TSK>(key), out var item);
            value = found ? Import<TDV>(item) : default!;
            return found;
        }
        public void Add(KeyValuePair<TDK, TDV> item) => Add(item.Key, item.Value);
        public void Clear() => source.Clear();
        public bool Contains(KeyValuePair<TDK, TDV> item) => TryGetValue(item.Key, out var value) && EqualityComparer<TDV>.Default.Equals(value, item.Value);
        public bool Remove(KeyValuePair<TDK, TDV> item) => Contains(item) && Remove(item.Key);
        public void CopyTo(KeyValuePair<TDK, TDV>[] array, int index) { foreach (var item in this) array[index++] = item; }
        public IEnumerator<KeyValuePair<TDK, TDV>> GetEnumerator() => source.Select(p => new KeyValuePair<TDK, TDV>(Import<TDK>(p.Key), Import<TDV>(p.Value))).GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
    }

    private sealed class ArrayLink
    {
        internal readonly Dictionary<Type, Array> Arrays = new();
        internal readonly Dictionary<Type, object?[]> Observed = new();
        internal void Add(Array array)
        {
            Arrays.Add(array.GetType(), array);
            Observed.Add(array.GetType(), array.Cast<object?>().ToArray());
            ArrayLinks.Add(array, this);
        }
        internal void Synchronize(Array preferred)
        {
            foreach (Array source in new[] { preferred }.Concat(Arrays.Values.Where(a => !ReferenceEquals(a, preferred))))
            {
                object?[] observed = Observed[source.GetType()];
                for (int i = 0; i < source.Length; i++)
                {
                    object? value = source.GetValue(i);
                    if (ReferenceEquals(value, observed[i])) continue;
                    foreach (Array destination in Arrays.Values)
                    {
                        object? adapted = ConvertValue(value, destination.GetType().GetElementType()!);
                        destination.SetValue(adapted, i);
                        Observed[destination.GetType()][i] = adapted;
                    }
                }
            }
        }
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Array, ArrayLink> ArrayLinks = new();
    private static readonly object ArrayLock = new();
    internal readonly struct ArrayView<T>
    {
        private readonly T[] source;
        internal ArrayView(T[] source) { this.source = source; }
        internal T this[int index]
        {
            get { SynchronizeArray(source); return source[index]; }
            set { source[index] = value; SynchronizeArray(source); }
        }
    }
    internal static ArrayView<T> AdaptArray<T>(T[] source) => new(source ?? throw new NullReferenceException());
    internal static void SynchronizeArray(Array source)
    {
        lock (ArrayLock) if (ArrayLinks.TryGetValue(source, out var link)) link.Synchronize(source);
    }

    private static object? ConvertValue(object? value, Type destination)
    {
        if (value is null) return null;
        if (destination.IsInstanceOfType(value)) return value;
        if (value is IProjection projection && destination.IsInstanceOfType(projection.Source)) return projection.Source;
        if (destination == typeof(JavaFile) && value is FileInfo native) return JavaFile.FromFileInfo(native);
        if (destination == typeof(FileInfo) && value is JavaFile file) return file.ToFileInfo();
        if (destination.IsArray && value is Array array)
        {
            lock (ArrayLock)
            {
                if (!ArrayLinks.TryGetValue(array, out var link))
                {
                    link = new ArrayLink();
                    link.Add(array);
                }
                link.Synchronize(array);
                if (link.Arrays.TryGetValue(destination, out var existing)) return existing;
                Type element = destination.GetElementType()!;
                var result = Array.CreateInstance(element, array.Length);
                for (int i = 0; i < array.Length; i++) result.SetValue(ConvertValue(array.GetValue(i), element), i);
                link.Add(result);
                return result;
            }
        }
        if (typeof(Delegate).IsAssignableFrom(destination) && value is Delegate callback)
        {
            var signature = destination.GetMethod("Invoke")!;
            var sourceSignature = callback.GetType().GetMethod("Invoke")!;
            var sourceParameters = sourceSignature.GetParameters();
            var parameters = signature.GetParameters().Select(p =>
                System.Linq.Expressions.Expression.Parameter(p.ParameterType, p.Name)).ToArray();
            var converter = typeof(JavaFileBridge).GetMethod(nameof(ConvertValue), BindingFlags.Static | BindingFlags.NonPublic)!;
            var arguments = parameters.Select((p, i) =>
                System.Linq.Expressions.Expression.Convert(
                    System.Linq.Expressions.Expression.Call(converter,
                        System.Linq.Expressions.Expression.Convert(p, typeof(object)),
                        System.Linq.Expressions.Expression.Constant(sourceParameters[i].ParameterType)),
                    sourceParameters[i].ParameterType));
            System.Linq.Expressions.Expression body = System.Linq.Expressions.Expression.Invoke(
                System.Linq.Expressions.Expression.Constant(callback), arguments);
            if (signature.ReturnType != typeof(void))
                body = System.Linq.Expressions.Expression.Convert(
                    System.Linq.Expressions.Expression.Call(converter,
                        System.Linq.Expressions.Expression.Convert(body, typeof(object)),
                        System.Linq.Expressions.Expression.Constant(signature.ReturnType)), signature.ReturnType);
            return System.Linq.Expressions.Expression.Lambda(destination, body, parameters).Compile();
        }
        if (destination.IsGenericType)
        {
            Type definition = destination.GetGenericTypeDefinition();
            Type? Find(Type definitionToFind) => value.GetType().GetInterfaces().FirstOrDefault(t => t.IsGenericType && t.GetGenericTypeDefinition() == definitionToFind);
            Type? sourceType;
            if (definition == typeof(IDictionary<,>) && (sourceType = Find(typeof(IDictionary<,>))) is not null)
                return Activator.CreateInstance(typeof(DictionaryProjection<,,,>).MakeGenericType(
                    sourceType.GetGenericArguments().Concat(destination.GetGenericArguments()).ToArray()), value);
            if ((definition == typeof(ISet<>) || definition == typeof(ICollection<>) || definition == typeof(IEnumerable<>)) &&
                (sourceType = Find(typeof(ISet<>))) is not null)
                return Activator.CreateInstance(typeof(SetProjection<,>).MakeGenericType(
                    sourceType.GetGenericArguments()[0], destination.GetGenericArguments()[0]), value);
            if (definition == typeof(IList<>) || definition == typeof(ICollection<>) || definition == typeof(IEnumerable<>))
            {
                Type? list = value.GetType().GetInterfaces().FirstOrDefault(t => t.IsGenericType && t.GetGenericTypeDefinition() == typeof(IList<>));
                if (list is not null)
                    return Activator.CreateInstance(typeof(ListProjection<,>).MakeGenericType(
                        list.GetGenericArguments()[0], destination.GetGenericArguments()[0]), value);
                if ((sourceType = Find(typeof(ICollection<>))) is not null)
                    return Activator.CreateInstance(typeof(CollectionProjection<,>).MakeGenericType(
                        sourceType.GetGenericArguments()[0], destination.GetGenericArguments()[0]), value);
                if (definition == typeof(IEnumerable<>) && (sourceType = Find(typeof(IEnumerable<>))) is not null)
                    return Activator.CreateInstance(typeof(EnumerableProjection<,>).MakeGenericType(
                        sourceType.GetGenericArguments()[0], destination.GetGenericArguments()[0]), value);
            }
        }
        throw new InvalidCastException($"Cannot adapt Java File boundary from {value.GetType()} to {destination}.");
    }

    internal static void Call(object target, string name, Type[] parameters, object?[] arguments) =>
        Invoke(target, name, parameters, arguments, typeof(void));
    internal static T Call<T>(object target, string name, Type[] parameters, object?[] arguments) =>
        Import<T>(Invoke(target, name, parameters, arguments, PublicType(typeof(T))));

    private static object? Invoke(object target, string name, Type[] parameters, object?[] arguments, Type result)
    {
        if (target is null) throw new NullReferenceException();
        Type type = target as Type ?? target.GetType();
        const BindingFlags flags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
        MethodInfo? selected = type.GetMethod(name, flags, null, parameters, null)
            ?? GenericMethod(type.GetMethods(flags), name, parameters, result);
        if (selected is null && !type.IsInterface)
        {
            foreach (Type contract in type.GetInterfaces())
            {
                MethodInfo? member = contract.GetMethod(name, parameters)
                    ?? GenericMethod(contract.GetMethods(), name, parameters, result);
                if (member is null) continue;
                var map = type.GetInterfaceMap(contract);
                MethodInfo definition = member.IsGenericMethod ? member.GetGenericMethodDefinition() : member;
                selected = map.TargetMethods[Array.IndexOf(map.InterfaceMethods, definition)];
                if (selected.IsGenericMethodDefinition) selected = selected.MakeGenericMethod(member.GetGenericArguments());
                break;
            }
        }
        MethodInfo method = selected ?? throw new MissingMethodException(type.FullName, name);
        if (method.IsDefined(typeof(JavaFileBoundaryAttribute), false))
        {
            Type[] genericArguments = method.IsGenericMethod ? method.GetGenericArguments() : Type.EmptyTypes;
            method = method.DeclaringType!.GetMethods(flags | BindingFlags.DeclaredOnly)
                .Where(m => m.Name == "__JavaFile_" + name && m.GetParameters().Length == parameters.Length)
                .Select(m => m.IsGenericMethodDefinition ? m.MakeGenericMethod(genericArguments) : m)
                .Single(m => m.GetParameters().Select(p => PublicType(p.ParameterType)).SequenceEqual(parameters));
        }
        var formal = method.GetParameters();
        var adapted = arguments.Select((value, index) => ConvertValue(value, formal[index].ParameterType)).ToArray();
        try { return method.Invoke(method.IsStatic ? null : target, adapted); }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    private static MethodInfo? GenericMethod(IEnumerable<MethodInfo> methods, string name, Type[] parameters, Type result)
    {
        foreach (MethodInfo method in methods.Where(m => m.Name == name && m.IsGenericMethodDefinition))
        {
            var formal = method.GetParameters();
            if (formal.Length != parameters.Length) continue;
            var inferred = new Dictionary<Type, Type>();
            if (!formal.Select((p, index) => MatchType(p.ParameterType, parameters[index], inferred)).All(v => v)) continue;
            if (method.GetGenericArguments().Any(t => !inferred.ContainsKey(t)))
                _ = MatchType(method.ReturnType, result, inferred);
            if (method.GetGenericArguments().All(inferred.ContainsKey))
                return method.MakeGenericMethod(method.GetGenericArguments().Select(t => inferred[t]).ToArray());
        }
        return null;
    }
    private static bool MatchType(Type formal, Type actual, IDictionary<Type, Type> inferred)
    {
        if (formal.IsGenericParameter)
        {
            if (inferred.TryGetValue(formal, out Type? previous)) return previous == actual;
            inferred[formal] = actual;
            return true;
        }
        if (formal.IsArray) return actual.IsArray && MatchType(formal.GetElementType()!, actual.GetElementType()!, inferred);
        if (formal.IsGenericType)
            return actual.IsGenericType && formal.GetGenericTypeDefinition() == actual.GetGenericTypeDefinition() &&
                formal.GetGenericArguments().Zip(actual.GetGenericArguments(), (f, a) => MatchType(f, a, inferred)).All(v => v);
        return formal == actual;
    }

    private static Type PublicType(Type type)
    {
        if (type == typeof(JavaFile)) return typeof(FileInfo);
        if (type.IsArray) return PublicType(type.GetElementType()!).MakeArrayType();
        if (type.IsGenericType) return type.GetGenericTypeDefinition().MakeGenericType(type.GetGenericArguments().Select(PublicType).ToArray());
        return type;
    }
}
#endif


internal sealed class JavaFileNotFoundException : FileNotFoundException
{
    // Java's no-argument FileNotFoundException has a null message. The CLR
    // supplies a generic fallback message, which would become a spurious
    // destination diagnostic unless the Java contract is retained explicitly.
    public override string Message => null!;
}

internal sealed class JavaRandomAccessFile : IDisposable
{
    private readonly FileStream stream;
    private bool disposed;

    internal JavaRandomAccessFile(JavaFile file, string mode) : this(RandomAccessPath(file, mode), mode) { }

    private static FileInfo RandomAccessPath(JavaFile file, string mode)
    {
        if (mode != "r" && mode != "rw") throw new ArgumentException($"Unsupported random-access mode `{mode}`.", nameof(mode));
        return file.OpenFileInfo();
    }

    internal JavaRandomAccessFile(FileInfo file, string mode)
    {
        ArgumentNullException.ThrowIfNull(file);
        stream = mode switch
        {
            "r" => new FileStream(file.FullName, FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete),
            "rw" => new FileStream(file.FullName, FileMode.OpenOrCreate, FileAccess.ReadWrite,
                FileShare.Read),
            _ => throw new ArgumentException($"Unsupported random-access mode `{mode}`.", nameof(mode))
        };
    }
    internal long length()
    {
        ThrowIfDisposed();
        return stream.Length;
    }
    internal long getFilePointer()
    {
        ThrowIfDisposed();
        return stream.Position;
    }
    internal int read()
    {
        ThrowIfDisposed();
        return stream.ReadByte();
    }
    internal int read(sbyte[] destination) =>
        read(destination, 0, destination.Length);
    internal int read(sbyte[] destination, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(destination);
        if (offset < 0 || length < 0 || offset > destination.Length - length)
            throw new IndexOutOfRangeException();
        if (length == 0) return 0;
        ThrowIfDisposed();
        var count = stream.Read(JavaCompat.AsUnsignedBytes(destination), offset, length);
        return count == 0 ? -1 : count;
    }
    internal void readFully(sbyte[] destination)
    {
        ArgumentNullException.ThrowIfNull(destination);
        if (destination.Length == 0) return;
        ThrowIfDisposed();
        var total = 0;
        while (total < destination.Length)
        {
            var count = read(destination, total, destination.Length - total);
            if (count < 0) throw new EndOfStreamException();
            total += count;
        }
    }
    internal void seek(long position)
    {
        ThrowIfDisposed();
        if (position < 0) throw new IOException("Negative seek offset");
        stream.Position = position;
    }
    internal void setLength(long length)
    {
        ThrowIfDisposed();
        if (length < 0) throw new IOException("Negative file length");
        try { stream.SetLength(length); }
        catch (NotSupportedException error) { throw new IOException(error.Message, error); }
        if (stream.Position > length) stream.Position = length;
    }
    internal int skipBytes(int count)
    {
        ThrowIfDisposed();
        if (count <= 0) return 0;
        var skipped = checked((int)Math.Min(count, Math.Max(0, stream.Length - stream.Position)));
        stream.Position += skipped;
        return skipped;
    }
    internal void write(sbyte[] source)
    {
        ThrowIfDisposed();
        ArgumentNullException.ThrowIfNull(source);
        try
        {
            stream.Write(JavaCompat.AsUnsignedBytes(source), 0, source.Length);
            stream.Flush();
        }
        catch (NotSupportedException error) { throw new IOException(error.Message, error); }
    }
    internal void close() => Dispose();
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stream.Dispose();
    }
    private void ThrowIfDisposed()
    {
        if (disposed) throw new IOException("Random access file is closed.");
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaInputStream : Stream, IDisposable
{
    private bool disposeDispatching;

    public abstract int Read();

    public virtual int Read(sbyte[] buffer) => Read(buffer, 0, buffer.Length);

    public virtual int Read(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        if (offset < 0 || count < 0 || offset > buffer.Length - count)
            throw new IndexOutOfRangeException();
        if (count == 0) return 0;
        var first = Read();
        if (first < 0) return -1;
        buffer[offset] = unchecked((sbyte)first);
        var copied = 1;
        while (copied < count)
        {
            int next;
            // InputStream's default bulk read treats an IOException after the
            // first byte as a short read; failure on the first byte propagates.
            try { next = Read(); }
            catch (IOException) { break; }
            if (next < 0) break;
            buffer[offset + copied++] = unchecked((sbyte)next);
        }
        return copied;
    }

    public virtual int Available() => 0;
    public virtual long Skip(long count)
    {
        if (count <= 0) return 0;
        var skipped = 0L;
        while (skipped < count && Read() >= 0) skipped++;
        return skipped;
    }
    public virtual void Mark(int readLimit) => _ = readLimit;
    public virtual void Reset() =>
        throw new IOException("mark/reset is not supported by this input stream.");
    public virtual bool MarkSupported() => false;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        JavaCompat.CheckStreamRange(buffer, offset, count);
        if (count == 0) return Math.Max(0, Read(Array.Empty<sbyte>(), 0, 0));
        // Java overrides may System.arraycopy into the array, which needs a
        // real sbyte[]; one pooled chunk bounds the copy by what is delivered.
        var length = Math.Min(count, JavaCompat.StreamBoundaryChunk);
        var signed = ArrayPool<sbyte>.Shared.Rent(length);
        try
        {
            var readCount = Read(signed, 0, length);
            if (readCount > 0) Buffer.BlockCopy(signed, 0, buffer, offset, readCount);
            return Math.Max(0, readCount);
        }
        finally
        {
            ArrayPool<sbyte>.Shared.Return(signed);
        }
    }

    public override int ReadByte() => Read();
    public override void Flush() { }
    public new virtual void Dispose()
    {
        if (disposeDispatching)
        {
            base.Dispose();
            return;
        }
        disposeDispatching = true;
        try
        {
            base.Dispose();
        }
        finally
        {
            disposeDispatching = false;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !disposeDispatching)
        {
            disposeDispatching = true;
            try
            {
                Dispose();
            }
            finally
            {
                disposeDispatching = false;
            }
        }
        base.Dispose(disposing);
    }

    void IDisposable.Dispose() => Dispose();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterInputStream : JavaInputStream
{
    protected readonly Stream @in;

    protected JavaFilterInputStream(Stream input) =>
        @in = input ?? throw new ArgumentNullException(nameof(input));

    public override int Read() => @in.ReadByte();

    public override int Read(sbyte[] buffer, int offset, int count) =>
        JavaCompat.InputStreamRead(@in, buffer, offset, count);

    public override int Available() =>
        @in.CanSeek ? checked((int)Math.Min(int.MaxValue, @in.Length - @in.Position)) : 0;

    public override long Skip(long count)
    {
        if (count <= 0) return 0;
        if (@in.CanSeek)
        {
            var original = @in.Position;
            @in.Position = Math.Min(@in.Length, original + count);
            return @in.Position - original;
        }
        return base.Skip(count);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) ((IDisposable)@in).Dispose();
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
abstract class JavaOutputStream : Stream, IDisposable
{
    private bool disposeDispatching;

    public abstract void Write(int value);

    public virtual void Write(sbyte[] buffer) => Write(buffer, 0, buffer.Length);

    public virtual void Write(sbyte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        if (offset < 0 || count < 0 || offset > buffer.Length - count)
            throw new IndexOutOfRangeException();
        for (var index = 0; index < count; index++) Write(buffer[offset + index]);
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Write(byte[] buffer, int offset, int count)
    {
        JavaCompat.CheckStreamRange(buffer, offset, count);
        if (count == 0)
        {
            Write(Array.Empty<sbyte>(), 0, 0);
            return;
        }
        // Java overrides receive a real sbyte[] (see Read); a pooled chunk
        // keeps the copy bounded however much the .NET caller writes.
        var signed = ArrayPool<sbyte>.Shared.Rent(Math.Min(count, JavaCompat.StreamBoundaryChunk));
        try
        {
            for (var written = 0; written < count;)
            {
                var length = Math.Min(count - written, JavaCompat.StreamBoundaryChunk);
                Buffer.BlockCopy(buffer, offset + written, signed, 0, length);
                Write(signed, 0, length);
                written += length;
            }
        }
        finally
        {
            ArrayPool<sbyte>.Shared.Return(signed);
        }
    }

    public override void WriteByte(byte value) => Write(value);
    public override void Flush() { }
    public new virtual void Dispose()
    {
        if (disposeDispatching)
        {
            base.Dispose();
            return;
        }
        disposeDispatching = true;
        try
        {
            base.Dispose();
        }
        finally
        {
            disposeDispatching = false;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && !disposeDispatching)
        {
            disposeDispatching = true;
            try
            {
                Dispose();
            }
            finally
            {
                disposeDispatching = false;
            }
        }
        base.Dispose(disposing);
    }

    void IDisposable.Dispose() => Dispose();
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaByteArrayOutputStream : MemoryStream, IDisposable
{
    private bool disposeDispatching;

    public JavaByteArrayOutputStream()
    {
    }

    public JavaByteArrayOutputStream(int capacity)
        : base(capacity)
    {
    }

    public new virtual void Dispose()
    {
        // java.io.ByteArrayOutputStream.close() has no effect. Keep the
        // public virtual surface so translated close() overrides dispatch.
    }

    void IDisposable.Dispose() => Dispose();

    protected override void Dispose(bool disposing)
    {
        if (disposing && !disposeDispatching)
        {
            disposeDispatching = true;
            try
            {
                Dispose();
            }
            finally
            {
                disposeDispatching = false;
            }
        }

        // Its content, size, reset, and write operations remain available
        // after close, so intentionally do not call MemoryStream.Dispose.
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

internal sealed class JavaPushbackInputStream : Stream
{
    private readonly Stream source;
    private readonly byte[] pushback;
    private int position;

    internal JavaPushbackInputStream(Stream source) =>
        (this.source, pushback, position) =
            (JavaCompat.RequireNonNull(source), new byte[1], 1);

    internal JavaPushbackInputStream(Stream source, int size)
    {
        if (size <= 0) throw new ArgumentOutOfRangeException(nameof(size));
        this.source = JavaCompat.RequireNonNull(source);
        pushback = new byte[size];
        position = size;
    }

    internal void Unread(int value)
    {
        if (position == 0) throw new IOException("Push back buffer is full");
        pushback[--position] = unchecked((byte)value);
    }

    internal void Unread(sbyte[] values, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || length < 0 || offset > values.Length - length)
            throw new IndexOutOfRangeException();
        if (length > position) throw new IOException("Push back buffer is full");
        position -= length;
        for (var index = 0; index < length; index++)
            pushback[position + index] = unchecked((byte)values[offset + index]);
    }

    public override int ReadByte()
    {
        return position < pushback.Length
            ? pushback[position++]
            : source.ReadByte();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (count == 0) return 0;
        var copied = 0;
        while (count > 0 && position < pushback.Length)
        {
            buffer[offset++] = pushback[position++];
            count--;
            copied++;
        }
        if (count == 0) return copied;
        return copied + source.Read(buffer, offset, count);
    }

    public override bool CanRead => source.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    protected override void Dispose(bool disposing)
    {
        if (disposing) source.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaSequenceInputStream : Stream
{
    private readonly Stream first;
    private readonly Stream second;
    private bool readingFirst = true;

    internal JavaSequenceInputStream(Stream first, Stream second)
    {
        this.first = first ?? throw new ArgumentNullException(nameof(first));
        this.second = second ?? throw new ArgumentNullException(nameof(second));
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        if (readingFirst)
        {
            var read = first.Read(buffer, offset, count);
            if (read != 0) return read;
            readingFirst = false;
        }
        return second.Read(buffer, offset, count);
    }

    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try
            {
                first.Dispose();
            }
            finally
            {
                second.Dispose();
            }
        }
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
class JavaFilterOutputStream : JavaOutputStream
{
    protected readonly Stream @out;
    private bool closed;

    public JavaFilterOutputStream(Stream output) => @out = output;
    public override bool CanWrite => @out.CanWrite;
    public override void Write(int value) => @out.WriteByte(unchecked((byte)value));
    public override void Write(sbyte[] buffer, int offset, int count) =>
        base.Write(buffer, offset, count);
    public override void Flush() => @out.Flush();

    public override void Dispose()
    {
        if (closed) return;
        closed = true;
        Exception? flushFailure = null;
        try
        {
            Flush();
        }
        catch (Exception failure)
        {
            flushFailure = failure;
            throw;
        }
        finally
        {
            try { ((IDisposable)@out).Dispose(); }
            catch (Exception closeFailure)
            {
                // FilterOutputStream gives the close failure precedence over
                // a flush failure, retaining the latter as suppressed.
                if (flushFailure is not null && !ReferenceEquals(closeFailure, flushFailure))
                    JavaCompat.AddSuppressed(closeFailure, flushFailure);
                throw;
            }
            base.Dispose();
        }
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

internal sealed class JavaDataInputStream : IDisposable
{
    private readonly Stream input;

    internal JavaDataInputStream(Stream input) =>
        this.input = input ?? throw new ArgumentNullException(nameof(input));

    internal sbyte readByte() => unchecked((sbyte)readUnsignedByte());

    internal int readUnsignedByte()
    {
        var value = input.ReadByte();
        if (value < 0) throw new EndOfStreamException();
        return value;
    }

    internal short readShort() => unchecked((short)readUnsignedShort());

    internal int readUnsignedShort() => (readUnsignedByte() << 8) | readUnsignedByte();

    internal int readInt() => unchecked(
        (readUnsignedByte() << 24) |
        (readUnsignedByte() << 16) |
        (readUnsignedByte() << 8) |
        readUnsignedByte());

    internal long readLong() => unchecked(
        ((long)readUnsignedByte() << 56) |
        ((long)readUnsignedByte() << 48) |
        ((long)readUnsignedByte() << 40) |
        ((long)readUnsignedByte() << 32) |
        ((long)readUnsignedByte() << 24) |
        ((long)readUnsignedByte() << 16) |
        ((long)readUnsignedByte() << 8) |
        (long)readUnsignedByte());

    internal void readFully(sbyte[] destination) =>
        readFully(destination, 0, destination.Length);

    internal void readFully(sbyte[] destination, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(destination);
        if (offset < 0 || length < 0 || offset > destination.Length - length)
            throw new ArgumentOutOfRangeException();
        var total = 0;
        while (total < length)
        {
            var count = JavaCompat.InputStreamRead(input, destination, offset + total, length - total);
            if (count <= 0) throw new EndOfStreamException();
            total += count;
        }
    }

    internal int available() => input.CanSeek
        ? checked((int)Math.Min(int.MaxValue, Math.Max(0, input.Length - input.Position)))
        : 0;

    internal void close() => input.Dispose();
    public void Dispose() => input.Dispose();
}

internal sealed class JavaDataOutputStream : Stream
{
    private readonly Stream output;

    internal JavaDataOutputStream(Stream output) =>
        this.output = output ?? throw new ArgumentNullException(nameof(output));

    internal void write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void Write(sbyte[] values) =>
        JavaCompat.OutputStreamWrite(output, values);

    internal void Write(sbyte[] values, int offset, int count) =>
        JavaCompat.OutputStreamWrite(output, values, offset, count);

    internal void writeByte(int value) => output.WriteByte(unchecked((byte)value));

    internal void writeShort(int value)
    {
        var bytes = new byte[2];
        System.Buffers.Binary.BinaryPrimitives.WriteInt16BigEndian(bytes, unchecked((short)value));
        output.Write(bytes, 0, bytes.Length);
    }

    internal void writeInt(int value)
    {
        var bytes = new byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(bytes, value);
        output.Write(bytes, 0, bytes.Length);
    }

    internal void writeLong(long value)
    {
        var bytes = new byte[8];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(bytes, value);
        output.Write(bytes, 0, bytes.Length);
    }

    internal void flush() => output.Flush();
    public override bool CanRead => false;
    public override bool CanSeek => output.CanSeek;
    public override bool CanWrite => output.CanWrite;
    public override long Length => output.Length;
    public override long Position
    {
        get => output.Position;
        set => output.Position = value;
    }
    public override void Flush() => output.Flush();
    public override int Read(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => output.Seek(offset, origin);
    public override void SetLength(long value) => output.SetLength(value);
    public override void Write(byte[] buffer, int offset, int count) =>
        output.Write(buffer, offset, count);
    public override void WriteByte(byte value) => output.WriteByte(value);
    protected override void Dispose(bool disposing)
    {
        if (disposing) output.Dispose();
        base.Dispose(disposing);
    }
}

internal sealed class JavaLineNumberReader : IDisposable
{
    private readonly TextReader reader;

    internal JavaLineNumberReader(TextReader reader) =>
        this.reader = reader ?? throw new ArgumentNullException(nameof(reader));

    internal string? ReadLine() => reader.ReadLine();
    public void Dispose() => reader.Dispose();
}

internal sealed class JavaPrintWriter
{
    private readonly TextWriter writer;
    public JavaPrintWriter(TextWriter writer) => this.writer = writer;
    public void Print(object? value) => writer.Write(value);
    public void Println(object? value = null) => writer.WriteLine(value);
    public void Flush() => writer.Flush();
}


internal static partial class JavaCompat
{
    internal static JavaFile NewJavaFile(string path) => new(path);
    internal static JavaFile NewJavaFile(string? parent, string child) => new(parent, child);
    internal static JavaFile NewJavaFile(JavaFile? parent, string child) => new(parent, child);
    internal static JavaFile NewJavaFile(Uri uri) => new(uri, uri is null ? "" : UriToString(uri));
    internal static bool FileExists(JavaFile file) => file.Exists;
    internal static bool FileIsFile(JavaFile file) => file.IsFile;
    internal static bool FileIsDirectory(JavaFile file) => file.IsDirectory;
    internal static string FileGetPath(JavaFile file) => file.Pathname;
    internal static string FileGetName(JavaFile file) => file.Name;
    internal static string FileGetAbsolutePath(JavaFile file) => file.AbsolutePath;
    internal static long FileLength(JavaFile file) => file.Length;
    internal static bool FileEquals(JavaFile file, object? other) => file.Equals(other);
    internal static JavaFile[]? FileListFiles(JavaFile file) => file.ListFiles();
    internal static bool FileCanRead(JavaFile file) => FileQuery(file, FileCanRead);
    internal static bool FileCanWrite(JavaFile file) => FileQuery(file, FileCanWrite);
    internal static bool FileIsHidden(JavaFile file) => file.Queryable &&
        (IsWindows() ? FileQuery(file, FileIsHidden) : file.Name.StartsWith(".", StringComparison.Ordinal));
    private static bool FileQuery(JavaFile file, Func<FileInfo, bool> query)
    {
        if (!file.Queryable) return false;
        try { return query(file.ToFileInfo()); }
        catch (global::System.ArgumentException) { return false; }
        catch (NotSupportedException) { return false; }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }
    internal static long FileLastModified(JavaFile file)
    {
        if (!file.Exists) return 0;
        try { return new DateTimeOffset(File.GetLastWriteTimeUtc(file.Pathname)).ToUnixTimeMilliseconds(); }
        catch (IOException) { return 0; }
        catch (UnauthorizedAccessException) { return 0; }
    }
    internal static bool FileDelete(JavaFile file) => file.Exists && FileQuery(file, FileDelete);
    internal static bool FileCreateNewFile(JavaFile file)
    {
        if (file.Invalid || file.Pathname.Length == 0) throw new IOException("Invalid or empty Java pathname.");
        if (file.Exists) return false;
        try { return FileCreateNewFile(file.ToFileInfo()); }
        catch (global::System.ArgumentException error) { throw new IOException(error.Message, error); }
        catch (NotSupportedException error) { throw new IOException(error.Message, error); }
        catch (UnauthorizedAccessException error) { throw new IOException(error.Message, error); }
    }
    internal static Stream OpenFileInput(JavaFile file) => OpenJavaFile(file, FileMode.Open, FileAccess.Read);
    internal static Stream OpenFileOutput(JavaFile file) => OpenJavaFile(file, FileMode.Create, FileAccess.Write);
    private static Stream OpenJavaFile(JavaFile file, FileMode mode, FileAccess access)
    {
        try { return new FileStream(file.OpenPath(), mode, access, FileShare.ReadWrite); }
        catch (global::System.ArgumentException error) { throw new FileNotFoundException(error.Message, file.Pathname, error); }
        catch (NotSupportedException error) { throw new FileNotFoundException(error.Message, file.Pathname, error); }
        catch (UnauthorizedAccessException error) { throw new FileNotFoundException(error.Message, file.Pathname, error); }
        catch (DirectoryNotFoundException error) { throw new FileNotFoundException(error.Message, file.Pathname, error); }
    }
    internal static TextReader OpenFileReader(JavaFile file) => new StreamReader(OpenFileInput(file));
    internal static StreamWriter NewFileWriter(JavaFile file) => new(OpenFileOutput(file));
    internal static StreamWriter NewFileWriter(JavaFile file, Encoding encoding) => new(OpenFileOutput(file), encoding);
    internal static JavaPath FileToPath(JavaFile file)
    {
        if (file.Invalid) throw new ArgumentException("NUL character in Java pathname.", "path");
        return new JavaPath(file.Pathname);
    }
    internal static Uri FileToUri(JavaFile file)
    {
        string path = file.AbsolutePath;
        if (file.IsDirectory && !path.EndsWith(Path.DirectorySeparatorChar.ToString(), StringComparison.Ordinal))
            path += Path.DirectorySeparatorChar;
        Uri carrier = new UriBuilder("file", "") { Path = path }.Uri;
        if (IsWindows()) path = path.Replace('\\', '/');
        if (!path.StartsWith("/", StringComparison.Ordinal)) path = "/" + path;
        if (path.StartsWith("//", StringComparison.Ordinal)) path = "//" + path;
        string original = "file:" + QuoteUriComponent(path, ":@/!$&'()*+,;=");
        _ = JavaUriMetadata.OriginalUriTexts.GetValue(carrier, _ => new JavaUriMetadata.Text(original));
        return carrier;
    }
    internal static bool SetFileReadable(JavaFile file, bool readable, bool ownerOnly) =>
        FileQuery(file, native => native.Exists || Directory.Exists(native.FullName)) && FileQuery(file, native => SetFileReadable(native, readable, ownerOnly));
    internal static bool SetFileWritable(JavaFile file, bool writable, bool ownerOnly) =>
        FileQuery(file, native => native.Exists || Directory.Exists(native.FullName)) && FileQuery(file, native => SetFileWritable(native, writable, ownerOnly));
    internal static bool SetFileExecutable(JavaFile file, bool executable, bool ownerOnly) =>
        FileQuery(file, native => native.Exists || Directory.Exists(native.FullName)) && FileQuery(file, native => SetFileExecutable(native, executable, ownerOnly));
    private sealed class StreamMark
    {
        internal long Position;
    }
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Stream, StreamMark>
        StreamMarks = new();
    internal static int ReaderRead(TextReader reader, char[] buffer, int index, int count)
    {
        try { var read = reader.Read(buffer, index, count); return read == 0 && count != 0 ? -1 : read; }
        catch (global::System.ObjectDisposedException error) { throw new IOException(error.Message, error); }
    }
    internal static bool ReaderReady(TextReader reader)
    {
        ArgumentNullException.ThrowIfNull(reader);
        return reader.Peek() >= 0;
    }

    internal static void ResetMemoryStream(MemoryStream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        stream.SetLength(0);
        stream.Position = 0;
    }

    internal static string MemoryStreamToString(MemoryStream stream, string encodingName)
    {
        ArgumentNullException.ThrowIfNull(stream);
        return CharsetForName(encodingName).GetString(stream.ToArray());
    }

    internal static Stream OpenFileInput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileInput(file.FullName);
    }

    internal static Stream OpenFileInput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
    }

    internal static TextReader OpenFileReader(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return new StreamReader(file.FullName);
    }

    internal static StreamReader NewInputStreamReader(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        return new StreamReader(stream);
    }

    internal static StreamReader NewInputStreamReader(Stream stream, string charsetName)
    {
        ArgumentNullException.ThrowIfNull(stream);
        ArgumentNullException.ThrowIfNull(charsetName);
        return new StreamReader(stream, CharsetForName(charsetName));
    }

    internal static Stream OpenFileOutput(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        return OpenFileOutput(file.FullName);
    }

    internal static Stream OpenFileOutput(string path)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        return new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read);
    }

    internal static long FileLastModified(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        file.Refresh();
        return file.Exists
            ? new DateTimeOffset(file.LastWriteTimeUtc).ToUnixTimeMilliseconds()
            : 0;
    }

    internal static FileInfo NewFileInfo(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        if (!uri.IsAbsoluteUri || !uri.IsFile)
            throw new ArgumentException("File URI must be absolute and use the file scheme.", nameof(uri));
        return new FileInfo(uri.LocalPath);
    }

    internal static FileInfo NewFileInfo(string parent, string child)
    {
        ArgumentNullException.ThrowIfNull(parent);
        ArgumentNullException.ThrowIfNull(child);
        return new FileInfo(Path.Combine(parent, child));
    }

    internal static bool FileCanWrite(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        try
        {
            if (Directory.Exists(file.FullName))
            {
                var probe = Path.Combine(file.FullName, $".dripsharp-write-{Guid.NewGuid():N}.tmp");
                using (new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
                File.Delete(probe);
                return true;
            }
            if (!file.Exists) return false;
            using var stream = new FileStream(
                file.FullName, FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    internal static void WriterWriteCharCode(TextWriter writer, int value)
    {
        ArgumentNullException.ThrowIfNull(writer);
        writer.Write(unchecked((char)value));
    }

    internal static bool FileEquals(FileInfo file, object? other)
    {
        ArgumentNullException.ThrowIfNull(file);
        return other is FileInfo candidate &&
            string.Equals(
                file.ToString(),
                candidate.ToString(),
                IsWindows()
                    ? StringComparison.OrdinalIgnoreCase
                    : StringComparison.Ordinal);
    }

    internal static IOException NewIOException() => new();
    internal static IOException NewIOException(string? message) => new(message);
    internal static IOException NewIOException(Exception cause) => new(cause.Message, cause);
    internal static IOException NewIOException(string? message, Exception? cause) => new(message, cause);
    internal static FileNotFoundException NewFileNotFoundException() => new JavaFileNotFoundException();
    // Largest temporary chunk used when bytes cross between Java's sbyte[]
    // and .NET's byte[]; below the large-object-heap threshold.
    internal const int StreamBoundaryChunk = 64 * 1024;

    // Reinterprets a Java byte array for a .NET byte[] API. Pass it only to
    // code that copies with Buffer.BlockCopy or spans (the exact BCL FileStream
    // and MemoryStream): Array.Copy between it and a real byte[] or sbyte[]
    // throws ArrayTypeMismatchException.
    internal static byte[] AsUnsignedBytes(sbyte[] values) =>
        System.Runtime.CompilerServices.Unsafe.As<byte[]>(values);

    private static bool AcceptsReinterpretedBytes(Stream stream)
    {
        var type = stream.GetType();
        return type == typeof(FileStream) || type == typeof(MemoryStream) ||
               type == typeof(JavaByteArrayInputStream) ||
               type == typeof(JavaByteArrayOutputStream);
    }

    internal static void CheckStreamRange(byte[] buffer, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        if (count < 0) throw new ArgumentOutOfRangeException(nameof(count));
        if (offset > buffer.Length - count)
            throw new ArgumentException("Offset and count exceed the buffer length.");
    }

    internal static void OutputStreamWrite(Stream stream, sbyte[] values)
    {
        if (stream is JavaOutputStream java) java.Write(values);
        else OutputStreamWrite(stream, values, 0, values.Length);
    }
    internal static void OutputStreamWrite(Stream stream, sbyte[] values, int offset, int count)
    {
        // Java overrides receive the caller's array, as java.io dispatch does.
        if (stream is JavaOutputStream java)
        {
            java.Write(values, offset, count);
            return;
        }
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || count < 0 || offset > values.Length - count)
            throw new IndexOutOfRangeException();
        if (AcceptsReinterpretedBytes(stream))
        {
            stream.Write(AsUnsignedBytes(values), offset, count);
            return;
        }
        var buffer = ArrayPool<byte>.Shared.Rent(Math.Min(count, StreamBoundaryChunk));
        try
        {
            var written = 0;
            do
            {
                var length = Math.Min(count - written, StreamBoundaryChunk);
                Buffer.BlockCopy(values, offset + written, buffer, 0, length);
                stream.Write(buffer, 0, length);
                written += length;
            } while (written < count);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }
    internal static void OutputStreamWrite(Stream stream, int value) =>
        stream.WriteByte(unchecked((byte)value));
    internal static void OutputStreamWrite(JavaDataOutputStream stream, sbyte[] values) =>
        stream.write(values);
    internal static void OutputStreamWrite(
        JavaDataOutputStream stream,
        sbyte[] values,
        int offset,
        int count) =>
        stream.write(values, offset, count);
    internal static bool InputStreamMarkSupported(Stream stream) => stream.CanSeek;
    internal static void InputStreamMark(Stream stream, int _)
    {
        if (stream.CanSeek) StreamMarks.GetOrCreateValue(stream).Position = stream.Position;
    }
    internal static void InputStreamReset(Stream stream)
    {
        if (!stream.CanSeek || !StreamMarks.TryGetValue(stream, out var mark))
            throw new IOException("Stream mark is not available.");
        stream.Position = mark.Position;
    }
    internal static long InputStreamSkip(Stream stream, long count)
    {
        if (count <= 0) return 0;
        if (stream.CanSeek)
        {
            var available = Math.Max(0, stream.Length - stream.Position);
            var skipped = Math.Min(available, count);
            stream.Position += skipped;
            return skipped;
        }
        var buffer = new byte[8192];
        long total = 0;
        while (total < count)
        {
            var read = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, count - total));
            if (read == 0) break;
            total += read;
        }
        return total;
    }
    internal static int InputStreamRead(Stream stream) => stream.ReadByte();
    internal static int InputStreamRead(Stream stream, sbyte[] values) =>
        stream is JavaInputStream java ? java.Read(values) : InputStreamRead(stream, values, 0, values.Length);
    internal static int InputStreamRead(Stream stream, sbyte[] values, int offset, int count)
    {
        ArgumentNullException.ThrowIfNull(stream);
        // Preserve virtual Java overloads and caller-buffer mutations, including
        // mutations performed before an override throws. Native Streams still
        // cross the unsigned-byte boundary below.
        if (stream is JavaInputStream java) return java.Read(values, offset, count);
        ArgumentNullException.ThrowIfNull(values);
        if (offset < 0 || count < 0 || offset > values.Length - count)
            throw new IndexOutOfRangeException();
        if (count == 0)
            return stream is JavaByteArrayInputStream byteArrayAtBoundary &&
                   byteArrayAtBoundary.Position >= byteArrayAtBoundary.Length
                ? -1
                : 0;
        if (AcceptsReinterpretedBytes(stream))
        {
            var direct = stream is JavaByteArrayInputStream byteArray
                ? byteArray.ReadJava(AsUnsignedBytes(values), offset, count)
                : stream.Read(AsUnsignedBytes(values), offset, count);
            return direct == 0 ? -1 : direct;
        }
        var buffer = ArrayPool<byte>.Shared.Rent(Math.Min(count, StreamBoundaryChunk));
        try
        {
            var total = 0;
            while (true)
            {
                var length = Math.Min(count - total, StreamBoundaryChunk);
                int read;
                // After the first chunk a failure is a short read, as in
                // Java's InputStream.read: the bytes delivered are kept.
                try { read = stream.Read(buffer, 0, length); }
                catch (IOException) when (total > 0) { return total; }
                if (read <= 0) return total > 0 ? total : -1;
                Buffer.BlockCopy(buffer, 0, values, offset + total, read);
                total += read;
                // Keep filling only seekable (file or memory) streams, whose
                // reads do not block; a pipe or socket returns what it has.
                if (total == count || read < length || !stream.CanSeek) return total;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }
    internal static void MemoryStreamWriteTo(MemoryStream source, Stream destination)
    {
        if (!source.TryGetBuffer(out var contents))
            contents = new ArraySegment<byte>(source.ToArray());
        destination.Write(contents.Array!, contents.Offset, checked((int)source.Length));
    }
}
