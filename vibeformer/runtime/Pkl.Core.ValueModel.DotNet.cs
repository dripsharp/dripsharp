#nullable enable
using System;
using System.Collections;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace Pkl.Core;

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

public interface PklTestReporter
{
    void Report(TestResults results, TextWriter writer);
    void Summarize(IReadOnlyList<TestResults> results, TextWriter writer);
    string Report(TestResults results);
    string Summarize(IReadOnlyList<TestResults> results);
    void ReportToFile(TestResults results, string path);
    void SummarizeToFile(IReadOnlyList<TestResults> results, string path);
}

public static class PklTestReporters
{
    public static PklTestReporter Minimal(bool useColor = false) =>
        new PklTestReporterAdapter(new Stdlib.Test.Report.MinimalReporter(useColor));

    public static PklTestReporter Spec(bool useColor = false) =>
        new PklTestReporterAdapter(new Stdlib.Test.Report.SpecReporter(useColor));

    public static PklTestReporter JUnit(string aggregateSuiteName = "") =>
        new PklTestReporterAdapter(new Stdlib.Test.Report.JUnitReporter(aggregateSuiteName));

    private sealed class PklTestReporterAdapter : PklTestReporter
    {
        private readonly Stdlib.Test.Report.TestReporter reporter;

        internal PklTestReporterAdapter(Stdlib.Test.Report.TestReporter reporter) =>
            this.reporter = reporter;

        public void Report(TestResults results, TextWriter writer)
        {
            ArgumentNullException.ThrowIfNull(results);
            ArgumentNullException.ThrowIfNull(writer);
            reporter.Report(results, writer);
        }

        public void Summarize(IReadOnlyList<TestResults> results, TextWriter writer)
        {
            ArgumentNullException.ThrowIfNull(results);
            ArgumentNullException.ThrowIfNull(writer);
            reporter.Summarize(results as IList<TestResults> ?? results.ToList(), writer);
        }

        public string Report(TestResults results)
        {
            using var writer = new StringWriter(CultureInfo.InvariantCulture);
            Report(results, writer);
            return writer.ToString();
        }

        public string Summarize(IReadOnlyList<TestResults> results)
        {
            using var writer = new StringWriter(CultureInfo.InvariantCulture);
            Summarize(results, writer);
            return writer.ToString();
        }

        public void ReportToFile(TestResults results, string path)
        {
            ArgumentException.ThrowIfNullOrEmpty(path);
            using var writer = new StreamWriter(path, append: false, new UTF8Encoding(false));
            Report(results, writer);
        }

        public void SummarizeToFile(IReadOnlyList<TestResults> results, string path)
        {
            ArgumentException.ThrowIfNullOrEmpty(path);
            using var writer = new StreamWriter(path, append: false, new UTF8Encoding(false));
            Summarize(results, writer);
        }
    }
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
