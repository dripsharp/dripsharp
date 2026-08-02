// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace DripSharp.SqlTrellis.Tests;

internal static class Support
{
    private static readonly global::System.Collections.Concurrent.ConcurrentDictionary<
        global::System.Guid, object?> SerializedObjects = new();
    private static readonly object WorkingFixtureLock = new();
    private static bool WorkingFixturesInitialized;

    internal static void RunWithTimeout(global::System.Action action, long milliseconds)
    {
        global::System.Threading.Tasks.Task task =
            global::System.Threading.Tasks.Task.Run(action);
        if (!task.Wait(global::System.TimeSpan.FromMilliseconds(milliseconds)))
        {
            throw new global::System.TimeoutException(
                $"Upstream JUnit timeout expired after {milliseconds} ms.");
        }

        task.GetAwaiter().GetResult();
    }

    internal static T DeepClone<T>(T value) =>
        (T)DeepCloneObject(
            value,
            new global::System.Collections.Generic.Dictionary<object, object>(
                global::System.Collections.Generic.ReferenceEqualityComparer.Instance))!;

    internal static string ReadText(
        global::System.IO.Stream input, global::System.Text.Encoding encoding)
    {
        using var reader = new global::System.IO.StreamReader(
            input, encoding, detectEncodingFromByteOrderMarks: true,
            bufferSize: 1024, leaveOpen: true);
        return reader.ReadToEnd();
    }

    internal static string ReadText(
        global::System.Uri input, global::System.Text.Encoding encoding)
    {
        if (!input.IsFile)
        {
            throw new global::System.IO.IOException(
                $"Only file resources are supported by the shipped test project: {input}");
        }
        return global::System.IO.File.ReadAllText(input.LocalPath, encoding);
    }

    internal static global::System.Collections.Generic.IList<string> ReadLines(
        global::System.IO.TextReader reader)
    {
        var lines = new global::System.Collections.Generic.List<string>();
        for (string? line = reader.ReadLine(); line is not null; line = reader.ReadLine())
        {
            lines.Add(line);
        }
        return lines;
    }

    internal static void WriteText(string value, global::System.IO.TextWriter writer) =>
        writer.Write(value);

    internal static string ReadFileText(
        global::System.IO.FileInfo file, global::System.Text.Encoding encoding) =>
        global::System.IO.File.ReadAllText(file.FullName, encoding);

    internal static global::System.IO.Stream ResourceStream(
        global::System.Type owner, string name) =>
        global::System.IO.File.OpenRead(ResourcePath(owner, name));

    internal static global::System.Uri ResourceUri(global::System.Type owner, string name) =>
        new(ResourcePath(owner, name));

    internal static void LogFormatted(
        global::DripSharp.Runtime.JavaLogger logger,
        global::DripSharp.Runtime.JavaLogLevel level,
        string format,
        object? value) =>
        LogFormatted(logger, level, format, new object?[] { value });

    internal static void LogFormatted(
        global::DripSharp.Runtime.JavaLogger logger,
        global::DripSharp.Runtime.JavaLogLevel level,
        string format,
        object?[] values) =>
        logger.Log(
            level,
            global::System.String.Format(
                global::System.Globalization.CultureInfo.InvariantCulture,
                format,
                values));

    internal static JavaAssertionValue AssertionActual(
        global::Xunit.Sdk.XunitException exception)
    {
        object? actual = exception.GetType().GetProperty(
            "Actual",
            global::System.Reflection.BindingFlags.Instance |
            global::System.Reflection.BindingFlags.Public |
            global::System.Reflection.BindingFlags.NonPublic)?.GetValue(exception);
        return new JavaAssertionValue(actual ?? exception.Message);
    }

    internal static global::System.IO.FileInfo[] ListFiles(
        global::System.IO.FileInfo directory,
        global::System.Func<global::System.IO.FileInfo, string, bool> filter) =>
        global::System.Linq.Enumerable.ToArray(
            global::System.Linq.Enumerable.Select(
                global::System.Linq.Enumerable.Where(
                    global::System.IO.Directory.EnumerateFileSystemEntries(directory.FullName),
                    path => filter(directory, global::System.IO.Path.GetFileName(path))),
                path => new global::System.IO.FileInfo(path)));

    internal static bool JavaMkdirs(global::System.IO.FileInfo directory)
    {
        if (global::System.IO.Directory.Exists(directory.FullName)) return false;
        global::System.IO.Directory.CreateDirectory(directory.FullName);
        return true;
    }

    internal static global::System.IO.FileInfo? ParentFile(
        global::System.IO.FileInfo file) =>
        file.Directory is null ? null : new global::System.IO.FileInfo(file.Directory.FullName);

    internal static T TheoryArgument<T>(object? value)
    {
        if (value is null) return default!;
        if (value is T typed) return typed;
        global::System.Type target = typeof(T);
        if (target.IsGenericType &&
            value is global::System.Collections.IEnumerable values)
        {
            global::System.Type definition = target.GetGenericTypeDefinition();
            if (definition == typeof(global::System.Collections.Generic.IEnumerable<>) ||
                definition == typeof(global::System.Collections.Generic.ICollection<>) ||
                definition == typeof(global::System.Collections.Generic.IList<>) ||
                definition == typeof(global::System.Collections.Generic.IReadOnlyCollection<>) ||
                definition == typeof(global::System.Collections.Generic.IReadOnlyList<>))
            {
                global::System.Type listType = typeof(global::System.Collections.Generic.List<>)
                    .MakeGenericType(target.GetGenericArguments()[0]);
                var converted = (global::System.Collections.IList)
                    global::System.Activator.CreateInstance(listType)!;
                foreach (object? item in values) converted.Add(item);
                return (T)converted;
            }
        }
        return (T)value;
    }

    internal static global::System.IO.FileInfo TestFile(string path)
    {
        const string sourceFixturePrefix = "src/test/resources/";
        if (path.StartsWith(sourceFixturePrefix, global::System.StringComparison.Ordinal))
        {
            return WritableFixture(path.Substring(sourceFixturePrefix.Length));
        }
        string[] fixturePrefixes =
        {
            "target/test-classes/",
            "build/resources/test/"
        };
        foreach (string prefix in fixturePrefixes)
        {
            if (!path.StartsWith(prefix, global::System.StringComparison.Ordinal)) continue;
            return new global::System.IO.FileInfo(
                ContainedFixturePath(path.Substring(prefix.Length), allowDirectory: true));
        }
        return new global::System.IO.FileInfo(path);
    }

    private static global::System.IO.FileInfo WritableFixture(string relative)
    {
        string source = ContainedFixturePath(relative, allowDirectory: true);
        string root = global::System.IO.Path.GetFullPath(global::System.IO.Path.Combine(
            global::System.AppContext.BaseDirectory, "WritableFixtures"));
        string destination = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(root, relative));
        if (!destination.StartsWith(
                root + global::System.IO.Path.DirectorySeparatorChar,
                global::System.StringComparison.Ordinal))
        {
            throw new global::System.IO.IOException(
                $"Writable fixture path escapes its contained root: {relative}");
        }
        lock (WorkingFixtureLock)
        {
            if (!WorkingFixturesInitialized)
            {
                if (global::System.IO.Directory.Exists(root))
                    global::System.IO.Directory.Delete(root, recursive: true);
                global::System.IO.Directory.CreateDirectory(root);
                WorkingFixturesInitialized = true;
            }
            if (global::System.IO.Directory.Exists(source))
            {
                CopyFixtureDirectory(source, destination);
            }
            else if (!global::System.IO.File.Exists(destination))
            {
                global::System.IO.Directory.CreateDirectory(
                    global::System.IO.Path.GetDirectoryName(destination)!);
                global::System.IO.File.Copy(source, destination);
            }
        }
        return new global::System.IO.FileInfo(destination);
    }

    private static void CopyFixtureDirectory(string source, string destination)
    {
        if (global::System.IO.Directory.Exists(destination)) return;
        global::System.IO.Directory.CreateDirectory(destination);
        foreach (string directory in global::System.IO.Directory.EnumerateDirectories(
                     source, "*", global::System.IO.SearchOption.AllDirectories))
        {
            global::System.IO.Directory.CreateDirectory(global::System.IO.Path.Combine(
                destination, global::System.IO.Path.GetRelativePath(source, directory)));
        }
        foreach (string file in global::System.IO.Directory.EnumerateFiles(
                     source, "*", global::System.IO.SearchOption.AllDirectories))
        {
            global::System.IO.File.Copy(
                file,
                global::System.IO.Path.Combine(
                    destination, global::System.IO.Path.GetRelativePath(source, file)));
        }
    }

    internal static T[] ArrayInsert<T>(int index, T[] values, params T[] inserted)
    {
        var result = new T[values.Length + inserted.Length];
        global::System.Array.Copy(values, 0, result, 0, index);
        global::System.Array.Copy(inserted, 0, result, index, inserted.Length);
        global::System.Array.Copy(
            values, index, result, index + inserted.Length, values.Length - index);
        return result;
    }

    internal static string RandomString(int length)
    {
        const string characters =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        return global::System.String.Create(
            length,
            characters,
            static (span, alphabet) =>
            {
                for (int index = 0; index < span.Length; index++)
                {
                    span[index] = alphabet[global::System.Random.Shared.Next(alphabet.Length)];
                }
            });
    }

    internal static bool RandomBoolean(global::DripSharp.Runtime.JavaRandom random) =>
        (random.NextInt() & 1) != 0;

    internal static int RandomInt(
        global::DripSharp.Runtime.JavaRandom random, int bound)
    {
        if (bound <= 0)
        {
            throw new global::System.ArgumentOutOfRangeException(nameof(bound));
        }
        return (int)((uint)random.NextInt() % (uint)bound);
    }

    internal static float RandomFloat(global::DripSharp.Runtime.JavaRandom random) =>
        (float)((uint)random.NextInt() / 4294967296.0);

    internal static double RandomDouble(global::DripSharp.Runtime.JavaRandom random) =>
        (ulong)random.NextLong() / 18446744073709551616.0;

    internal static global::System.Collections.Generic.ICollection<
        global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability>
        ValidationCapabilities(global::System.Collections.IEnumerable values)
    {
        var result = new global::System.Collections.Generic.List<
            global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability>();
        foreach (object? value in values)
        {
            result.Add(
                (global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability)value!);
        }
        return result;
    }

    internal static global::System.Collections.Generic.IDictionary<
        global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability,
        global::System.Collections.Generic.ISet<
            global::DripSharp.SqlTrellis.Util.Validation.ValidationException>>
        FilterValidationErrors(
            global::DripSharp.SqlTrellis.Util.Validation.IValidator validator,
            params global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability[]
                capabilities)
    {
        var all = validator.getValidationErrors();
        var filtered = new global::System.Collections.Generic.Dictionary<
            global::DripSharp.SqlTrellis.Util.Validation.ValidationCapability,
            global::System.Collections.Generic.ISet<
                global::DripSharp.SqlTrellis.Util.Validation.ValidationException>>();
        foreach (var capability in capabilities)
        {
            if (all.TryGetValue(capability, out var errors))
            {
                filtered[capability] = errors;
            }
        }
        return filtered;
    }

    internal static global::System.Collections.Generic.ISet<object> EnumValues(
        global::System.Type enumType) =>
        new global::System.Collections.Generic.HashSet<object>(
            global::System.Linq.Enumerable.Cast<object>(
                global::System.Enum.GetValues(enumType)));

    internal static string ReflectionToString(object? value, bool includingAstNode) =>
        ReflectionToString(
            value,
            includingAstNode,
            new global::System.Collections.Generic.HashSet<object>(
                global::System.Collections.Generic.ReferenceEqualityComparer.Instance));

    internal static global::System.Data.Common.DbConnection OpenH2Connection(string jdbcUrl)
    {
        const string prefix = "jdbc:h2:mem:";
        if (!jdbcUrl.StartsWith(prefix, global::System.StringComparison.Ordinal))
        {
            throw new global::System.ArgumentException(
                $"Only in-memory H2 JDBC URLs are supported by this fixture: {jdbcUrl}",
                nameof(jdbcUrl));
        }
        var connection = new SqlTrellisH2Connection(jdbcUrl.Substring(prefix.Length));
        connection.Open();
        return connection;
    }

    internal static bool Execute(global::System.Data.Common.DbCommand command)
    {
        command.ExecuteNonQuery();
        return false;
    }

    internal static global::DripSharp.Runtime.JavaSimpleDateFormat DefaultDateTimeFormat() =>
        new(
            "yyyy-MM-dd HH:mm:ss",
            global::System.Globalization.CultureInfo.CurrentCulture);

    internal static string JavaSystemProperty(string name) => name switch
    {
        "file.separator" => global::System.IO.Path.DirectorySeparatorChar.ToString(),
        "path.separator" => global::System.IO.Path.PathSeparator.ToString(),
        "java.io.tmpdir" => global::System.IO.Path.GetTempPath(),
        "java.class.path" => global::System.AppContext.BaseDirectory,
        _ => throw new global::System.ArgumentException(
            $"Unsupported Java system property in shipped tests: {name}", nameof(name))
    };

    internal static JavaStackTraceElement[] CurrentStackTrace() =>
        global::System.Linq.Enumerable.ToArray(
            global::System.Linq.Enumerable.Select(
                new global::System.Diagnostics.StackTrace().GetFrames() ??
                    global::System.Array.Empty<global::System.Diagnostics.StackFrame>(),
                frame => new JavaStackTraceElement(
                    frame.GetMethod()?.DeclaringType?.FullName ?? string.Empty,
                    frame.GetMethod()?.Name ?? string.Empty,
                    frame.GetFileName(),
                    frame.GetFileLineNumber())));

    internal static string StackClassName(JavaStackTraceElement frame) => frame.ClassName;

    internal static string StackMethodName(JavaStackTraceElement frame) => frame.MethodName;

    internal static void SetStackTrace(
        global::System.Exception exception, JavaStackTraceElement[] frames) =>
        exception.Data["java.stackTrace"] = frames;

    internal static void PrintStackTrace(
        global::System.Exception exception,
        global::System.Exception? cause,
        global::System.IO.TextWriter writer)
    {
        writer.WriteLine(exception.ToString());
        if (cause is null) return;
        writer.WriteLine("Caused by:");
        writer.WriteLine(cause.ToString());
    }

    internal static global::DripSharp.Runtime.JavaSqlTime SqlTimeFromMillis(long milliseconds)
    {
        global::System.TimeSpan time = global::System.TimeSpan.FromMilliseconds(
            global::System.Math.Abs(milliseconds % 86_400_000L));
        return global::DripSharp.Runtime.JavaSqlTime.ValueOf(
            $"{time.Hours:00}:{time.Minutes:00}:{time.Seconds:00}");
    }

    internal static global::DripSharp.Runtime.JavaSqlTimestamp SqlTimestampFromMillis(
        long milliseconds)
    {
        global::System.DateTime value = new(1970, 1, 1);
        value = value.AddMilliseconds(
            global::System.Math.Abs(milliseconds % 253_402_214_400_000L));
        return global::DripSharp.Runtime.JavaSqlTimestamp.ValueOf(
            value.ToString(
                "yyyy-MM-dd HH:mm:ss.fff",
                global::System.Globalization.CultureInfo.InvariantCulture));
    }

    internal static global::DripSharp.Runtime.JavaSqlDate SqlDateFromMillis(long milliseconds)
    {
        global::System.DateTime value = new(1970, 1, 1);
        value = value.AddMilliseconds(
            global::System.Math.Abs(milliseconds % 253_402_214_400_000L));
        return global::DripSharp.Runtime.JavaSqlDate.ValueOf(
            value.ToString("yyyy-MM-dd", global::System.Globalization.CultureInfo.InvariantCulture));
    }

    internal static global::System.Collections.Generic.IEnumerable<string>
        SqlTrellisJavaTypeNames() =>
        SqlTrellisTypesByJavaName().Keys;

    internal static global::System.Type ResolveSqlTrellisType(string javaName)
    {
        if (SqlTrellisTypesByJavaName().TryGetValue(javaName, out global::System.Type? type))
        {
            return type;
        }

        throw new global::System.TypeLoadException(javaName);
    }

    internal static void WriteSerializedObject(global::System.IO.Stream output, object? value)
    {
        global::System.Guid id = global::System.Guid.NewGuid();
        SerializedObjects[id] = DeepCloneObject(
            value,
            new global::System.Collections.Generic.Dictionary<object, object>(
                global::System.Collections.Generic.ReferenceEqualityComparer.Instance));
        output.Write(id.ToByteArray());
        output.Flush();
    }

    internal static object? ReadSerializedObject(global::System.IO.Stream input)
    {
        byte[] bytes = new byte[16];
        input.ReadExactly(bytes);
        global::System.Guid id = new(bytes);
        if (!SerializedObjects.TryRemove(id, out object? value))
        {
            throw new global::System.Runtime.Serialization.SerializationException(
                $"Serialized object {id} is unavailable.");
        }

        return value;
    }

    private static object? DeepCloneObject(
        object? value,
        global::System.Collections.Generic.IDictionary<object, object> seen)
    {
        if (value is null) return null;
        global::System.Type type = value.GetType();
        if (type.IsPrimitive || type.IsEnum || type == typeof(string) ||
            type == typeof(decimal) || type == typeof(global::System.DateTime) ||
            type == typeof(global::System.DateTimeOffset) ||
            type == typeof(global::System.TimeSpan) || type == typeof(global::System.Guid) ||
            typeof(global::System.Delegate).IsAssignableFrom(type))
        {
            return value;
        }

        if (seen.TryGetValue(value, out object? existing)) return existing;
        if (value is global::System.Array sourceArray)
        {
            global::System.Array targetArray = global::System.Array.CreateInstance(
                type.GetElementType()!, sourceArray.Length);
            seen[value] = targetArray;
            for (int index = 0; index < sourceArray.Length; index++)
            {
                targetArray.SetValue(DeepCloneObject(sourceArray.GetValue(index), seen), index);
            }
            return targetArray;
        }

        object clone =
            global::System.Runtime.CompilerServices.RuntimeHelpers.GetUninitializedObject(type);
        seen[value] = clone;
        for (global::System.Type? current = type; current is not null;
             current = current.BaseType)
        {
            foreach (global::System.Reflection.FieldInfo field in current.GetFields(
                global::System.Reflection.BindingFlags.Instance |
                global::System.Reflection.BindingFlags.Public |
                global::System.Reflection.BindingFlags.NonPublic |
                global::System.Reflection.BindingFlags.DeclaredOnly))
            {
                field.SetValue(clone, DeepCloneObject(field.GetValue(value), seen));
            }
        }
        return clone;
    }

    private static string ReflectionToString(
        object? value,
        bool includingAstNode,
        global::System.Collections.Generic.ISet<object> seen)
    {
        if (value is null) return "<null>";
        if (value is string text) return '"' + text + '"';
        global::System.Type type = value.GetType();
        if (type.IsPrimitive || type.IsEnum || value is decimal || value is global::System.Guid ||
            value is global::System.DateTime || value is global::System.DateTimeOffset)
        {
            return global::System.Convert.ToString(
                value, global::System.Globalization.CultureInfo.InvariantCulture) ?? "<null>";
        }
        if (!seen.Add(value)) return "<cycle>";
        try
        {
            if (value is global::System.Collections.IDictionary map)
            {
                if (map.Count == 0) return "<null>";
                var entries = new global::System.Collections.Generic.List<string>();
                foreach (global::System.Collections.DictionaryEntry entry in map)
                {
                    entries.Add(
                        ReflectionToString(entry.Key, includingAstNode, seen) + "=" +
                        ReflectionToString(entry.Value, includingAstNode, seen));
                }
                entries.Sort(global::System.StringComparer.Ordinal);
                return "{" + global::System.String.Join(",", entries) + "}";
            }
            if (value is global::System.Collections.IEnumerable sequence)
            {
                var items = new global::System.Collections.Generic.List<string>();
                foreach (object? item in sequence)
                {
                    items.Add(ReflectionToString(item, includingAstNode, seen));
                }
                return items.Count == 0 ? "<null>" : "[" + global::System.String.Join(",", items) + "]";
            }

            var fields = new global::System.Collections.Generic.List<
                global::System.Reflection.FieldInfo>();
            for (global::System.Type? current = type; current is not null;
                 current = current.BaseType)
            {
                fields.AddRange(current.GetFields(
                    global::System.Reflection.BindingFlags.Instance |
                    global::System.Reflection.BindingFlags.Public |
                    global::System.Reflection.BindingFlags.NonPublic |
                    global::System.Reflection.BindingFlags.DeclaredOnly));
            }
            fields.Sort((left, right) => global::System.StringComparer.Ordinal.Compare(
                left.Name, right.Name));
            var rendered = new global::System.Collections.Generic.List<string>();
            foreach (global::System.Reflection.FieldInfo field in fields)
            {
                if (field.IsStatic || (!includingAstNode && field.Name == "node")) continue;
                rendered.Add(
                    field.Name + "=" +
                    ReflectionToString(field.GetValue(value), includingAstNode, seen));
            }
            return type.FullName + "[" + global::System.String.Join(",", rendered) + "]";
        }
        finally
        {
            seen.Remove(value);
        }
    }

    private static global::System.Collections.Generic.IReadOnlyDictionary<
        string, global::System.Type> SqlTrellisTypesByJavaName()
    {
        var types = new global::System.Collections.Generic.SortedDictionary<
            string, global::System.Type>(global::System.StringComparer.Ordinal);
        global::System.Reflection.Assembly assembly =
            typeof(global::DripSharp.SqlTrellis.Parser.CCJSqlParserUtil).Assembly;
        foreach (global::System.Type type in assembly.GetTypes())
        {
            if (type.IsDefined(
                    typeof(global::System.Runtime.CompilerServices.CompilerGeneratedAttribute),
                    inherit: false)) continue;
            const string prefix = "DripSharp.SqlTrellis.";
            string? destinationNamespace = type.Namespace;
            if (destinationNamespace is null ||
                !destinationNamespace.StartsWith(prefix, global::System.StringComparison.Ordinal))
            {
                continue;
            }

            string javaNamespace = string.Join(
                '.',
                global::System.Linq.Enumerable.Select(
                    destinationNamespace.Substring(prefix.Length).Split('.'),
                    segment => segment.ToLowerInvariant()));
            string typeName = type.Name;
            int genericMarker = typeName.IndexOf('`');
            if (genericMarker >= 0) typeName = typeName.Substring(0, genericMarker);
            if (type.IsNested && type.DeclaringType is not null)
            {
                string outer = type.DeclaringType.Name;
                int outerGenericMarker = outer.IndexOf('`');
                if (outerGenericMarker >= 0) outer = outer.Substring(0, outerGenericMarker);
                typeName = outer + "$" + typeName;
            }
            types["net.sf.jsqlparser." + javaNamespace + "." + typeName] = type;
        }
        return types;
    }

    internal static bool IsExpressionListType(global::System.Type type)
    {
        for (global::System.Type? current = type;
             current is not null;
             current = current.BaseType)
        {
            if (current.IsGenericType &&
                current.GetGenericTypeDefinition() ==
                    typeof(global::DripSharp.SqlTrellis.Expression.Operators.Relational.ExpressionList<>))
                return true;
        }
        return false;
    }

    private static string ResourcePath(global::System.Type owner, string name)
    {
        string relative;
        if (name.StartsWith("/", global::System.StringComparison.Ordinal))
        {
            relative = name.TrimStart('/');
        }
        else
        {
            const string prefix = "DripSharp.SqlTrellis.";
            string ownerNamespace = owner.Namespace ?? string.Empty;
            string package = ownerNamespace.StartsWith(
                prefix, global::System.StringComparison.Ordinal)
                ? string.Join(
                    '/',
                    global::System.Linq.Enumerable.Select(
                        ownerNamespace.Substring(prefix.Length).Split('.'),
                        segment => segment.ToLowerInvariant()))
                : string.Empty;
            relative = string.IsNullOrEmpty(package)
                ? name
                : "net/sf/jsqlparser/" + package + "/" + name;
        }

        return ContainedFixturePath(relative, allowDirectory: false);
    }

    private static string ContainedFixturePath(string relative, bool allowDirectory)
    {
        string fixtureRoot = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(global::System.AppContext.BaseDirectory, "Fixtures"));
        string path = global::System.IO.Path.GetFullPath(
            global::System.IO.Path.Combine(fixtureRoot, relative));
        if (!path.StartsWith(
                fixtureRoot + global::System.IO.Path.DirectorySeparatorChar,
                global::System.StringComparison.Ordinal) ||
                (!global::System.IO.File.Exists(path) &&
                 !(allowDirectory && global::System.IO.Directory.Exists(path))))
        {
            throw new global::System.IO.FileNotFoundException(
                $"Contained upstream test resource was not found: {relative}", path);
        }
        return path;
    }
}

public sealed class JavaStackTraceElement
{
    internal JavaStackTraceElement(
        string className, string methodName, string? fileName, int lineNumber)
    {
        ClassName = className;
        MethodName = methodName;
        FileName = fileName;
        LineNumber = lineNumber;
    }

    internal string ClassName { get; }
    internal string MethodName { get; }
    internal string? FileName { get; }
    internal int LineNumber { get; }
}

internal sealed class SqlTrellisH2Connection : global::System.Data.Common.DbConnection
{
    private sealed record Relation(string Type, global::System.Collections.Generic.List<string> Columns);

    private readonly string databaseName;
    private readonly global::System.Collections.Generic.Dictionary<string, Relation> relations =
        new(global::System.StringComparer.OrdinalIgnoreCase);
    private global::System.Data.ConnectionState state;

    internal SqlTrellisH2Connection(string databaseName) => this.databaseName = databaseName;

    public override string ConnectionString { get; set; } = string.Empty;
    public override string Database => databaseName;
    public override string DataSource => "memory";
    public override string ServerVersion => "2.4";
    public override global::System.Data.ConnectionState State => state;

    public override void Open() => state = global::System.Data.ConnectionState.Open;
    public override void Close() => state = global::System.Data.ConnectionState.Closed;
    public override void ChangeDatabase(string databaseName) =>
        throw new global::System.NotSupportedException();

    protected override global::System.Data.Common.DbTransaction BeginDbTransaction(
        global::System.Data.IsolationLevel isolationLevel) =>
        throw new global::System.NotSupportedException();

    protected override global::System.Data.Common.DbCommand CreateDbCommand() => new H2Command(this);

    public override global::System.Data.DataTable GetSchema(
        string collectionName, string?[]? restrictionValues)
    {
        if (!collectionName.Equals("Tables", global::System.StringComparison.OrdinalIgnoreCase))
        {
            throw new global::System.NotSupportedException(
                $"H2 test fixture does not expose schema collection {collectionName}.");
        }

        var table = new global::System.Data.DataTable("Tables");
        table.Columns.Add("TABLE_CAT", typeof(string));
        table.Columns.Add("TABLE_SCHEM", typeof(string));
        table.Columns.Add("TABLE_NAME", typeof(string));
        table.Columns.Add("TABLE_TYPE", typeof(string));
        foreach ((string name, Relation relation) in relations)
        {
            string catalog = databaseName.ToUpperInvariant();
            const string schema = "PUBLIC";
            if (!Matches(restrictionValues, 0, catalog) ||
                !Matches(restrictionValues, 1, schema) ||
                !Matches(restrictionValues, 2, name)) continue;
            table.Rows.Add(catalog, schema, name, relation.Type);
        }
        return table;
    }

    internal int Execute(string sql)
    {
        global::System.Text.RegularExpressions.Match createTable =
            global::System.Text.RegularExpressions.Regex.Match(
                sql,
                @"^\s*CREATE\s+TABLE\s+([^\s(]+)\s*\((.*)\)\s*;?\s*$",
                global::System.Text.RegularExpressions.RegexOptions.IgnoreCase |
                global::System.Text.RegularExpressions.RegexOptions.Singleline);
        if (createTable.Success)
        {
            string name = SimpleName(createTable.Groups[1].Value);
            relations[name] = new Relation("TABLE", ParseColumns(createTable.Groups[2].Value));
            return 0;
        }

        global::System.Text.RegularExpressions.Match createView =
            global::System.Text.RegularExpressions.Regex.Match(
                sql,
                @"^\s*CREATE\s+VIEW\s+([^\s]+)\s+AS\s+SELECT\s+\*\s+FROM\s+([^\s;]+)",
                global::System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (createView.Success)
        {
            string source = SimpleName(createView.Groups[2].Value);
            var columns = relations.TryGetValue(source, out Relation? relation)
                ? new global::System.Collections.Generic.List<string>(relation.Columns)
                : new global::System.Collections.Generic.List<string>();
            relations[SimpleName(createView.Groups[1].Value)] = new Relation("VIEW", columns);
            return 0;
        }

        global::System.Text.RegularExpressions.Match addColumn =
            global::System.Text.RegularExpressions.Regex.Match(
                sql,
                @"^\s*ALTER\s+TABLE\s+([^\s]+)\s+ADD\s+([^\s,;]+)",
                global::System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (addColumn.Success &&
            relations.TryGetValue(SimpleName(addColumn.Groups[1].Value), out Relation? altered))
        {
            altered.Columns.Add(NormalizeIdentifier(addColumn.Groups[2].Value));
            return 0;
        }

        throw new global::System.Data.DataException(
            $"Unsupported SQL in the contained H2 metadata fixture: {sql}");
    }

    internal global::System.Data.DataTable SelectSchema(string sql)
    {
        global::System.Text.RegularExpressions.Match from =
            global::System.Text.RegularExpressions.Regex.Match(
                sql,
                @"\bFROM\s+([^\s,;]+)",
                global::System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (!from.Success ||
            !relations.TryGetValue(SimpleName(from.Groups[1].Value), out Relation? relation))
        {
            throw new global::System.Data.DataException($"Unknown H2 fixture relation in: {sql}");
        }
        var table = new global::System.Data.DataTable(relation.Type);
        foreach (string column in relation.Columns) table.Columns.Add(column, typeof(object));
        return table;
    }

    private static bool Matches(string?[]? restrictions, int index, string value) =>
        restrictions is null || index >= restrictions.Length ||
        string.IsNullOrEmpty(restrictions[index]) ||
        value.Equals(restrictions[index], global::System.StringComparison.OrdinalIgnoreCase);

    private static string SimpleName(string identifier)
    {
        string[] pieces = identifier.Trim().Split('.');
        return NormalizeIdentifier(pieces[^1]);
    }

    private static string NormalizeIdentifier(string identifier) =>
        identifier.Trim().Trim('"', '`', '[', ']').ToUpperInvariant();

    private static global::System.Collections.Generic.List<string> ParseColumns(string body)
    {
        var definitions = new global::System.Collections.Generic.List<string>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index <= body.Length; index++)
        {
            char character = index == body.Length ? ',' : body[index];
            if (character == '(') depth++;
            else if (character == ')') depth--;
            else if (character == ',' && depth == 0)
            {
                string definition = body.Substring(start, index - start).Trim();
                if (definition.Length > 0)
                {
                    string name = definition.Split(
                        (char[]?)null,
                        global::System.StringSplitOptions.RemoveEmptyEntries)[0];
                    definitions.Add(NormalizeIdentifier(name));
                }
                start = index + 1;
            }
        }
        return definitions;
    }
}

internal sealed class H2Command : global::System.Data.Common.DbCommand
{
    private readonly H2ParameterCollection parameters = new();

    internal H2Command(SqlTrellisH2Connection connection) => DbConnection = connection;

    public override string CommandText { get; set; } = string.Empty;
    public override int CommandTimeout { get; set; }
    public override global::System.Data.CommandType CommandType { get; set; } =
        global::System.Data.CommandType.Text;
    public override bool DesignTimeVisible { get; set; }
    public override global::System.Data.UpdateRowSource UpdatedRowSource { get; set; }
    protected override global::System.Data.Common.DbConnection? DbConnection { get; set; }
    protected override global::System.Data.Common.DbParameterCollection DbParameterCollection =>
        parameters;
    protected override global::System.Data.Common.DbTransaction? DbTransaction { get; set; }

    public override void Cancel() { }
    public override int ExecuteNonQuery() => Connection.Execute(CommandText);
    public override object? ExecuteScalar() => null;
    public override void Prepare() { }
    protected override global::System.Data.Common.DbParameter CreateDbParameter() => new H2Parameter();
    protected override global::System.Data.Common.DbDataReader ExecuteDbDataReader(
        global::System.Data.CommandBehavior behavior) =>
        Connection.SelectSchema(CommandText).CreateDataReader();

    private SqlTrellisH2Connection Connection =>
        (SqlTrellisH2Connection)(DbConnection ??
            throw new global::System.InvalidOperationException("H2 command has no connection."));
}

internal sealed class H2Parameter : global::System.Data.Common.DbParameter
{
    public override global::System.Data.DbType DbType { get; set; }
    public override global::System.Data.ParameterDirection Direction { get; set; } =
        global::System.Data.ParameterDirection.Input;
    public override bool IsNullable { get; set; }
    public override string ParameterName { get; set; } = string.Empty;
    public override string SourceColumn { get; set; } = string.Empty;
    public override object? Value { get; set; }
    public override bool SourceColumnNullMapping { get; set; }
    public override int Size { get; set; }
    public override void ResetDbType() => DbType = global::System.Data.DbType.Object;
}

internal sealed class H2ParameterCollection : global::System.Data.Common.DbParameterCollection
{
    private readonly global::System.Collections.Generic.List<
        global::System.Data.Common.DbParameter> values = new();

    public override int Count => values.Count;
    public override object SyncRoot => ((global::System.Collections.ICollection)values).SyncRoot;
    public override int Add(object value)
    {
        values.Add((global::System.Data.Common.DbParameter)value);
        return values.Count - 1;
    }
    public override void AddRange(global::System.Array values)
    {
        foreach (object value in values) Add(value);
    }
    public override void Clear() => values.Clear();
    public override bool Contains(object value) => values.Contains(
        (global::System.Data.Common.DbParameter)value);
    public override bool Contains(string value) => IndexOf(value) >= 0;
    public override void CopyTo(global::System.Array array, int index) =>
        ((global::System.Collections.ICollection)values).CopyTo(array, index);
    public override global::System.Collections.IEnumerator GetEnumerator() => values.GetEnumerator();
    public override int IndexOf(object value) => values.IndexOf(
        (global::System.Data.Common.DbParameter)value);
    public override int IndexOf(string parameterName) => values.FindIndex(
        parameter => parameter.ParameterName.Equals(
            parameterName, global::System.StringComparison.OrdinalIgnoreCase));
    public override void Insert(int index, object value) =>
        values.Insert(index, (global::System.Data.Common.DbParameter)value);
    public override void Remove(object value) => values.Remove(
        (global::System.Data.Common.DbParameter)value);
    public override void RemoveAt(int index) => values.RemoveAt(index);
    public override void RemoveAt(string parameterName) => values.RemoveAt(IndexOf(parameterName));
    protected override global::System.Data.Common.DbParameter GetParameter(int index) => values[index];
    protected override global::System.Data.Common.DbParameter GetParameter(string parameterName) =>
        values[IndexOf(parameterName)];
    protected override void SetParameter(
        int index, global::System.Data.Common.DbParameter value) => values[index] = value;
    protected override void SetParameter(
        string parameterName, global::System.Data.Common.DbParameter value)
    {
        int index = IndexOf(parameterName);
        if (index < 0) values.Add(value); else values[index] = value;
    }
}

internal sealed class JavaAssertionValue
{
    private readonly object? value;
    internal JavaAssertionValue(object? value) => this.value = value;
    internal string GetStringRepresentation() => value?.ToString() ?? "null";
}

internal sealed class JavaObjectOutputStream : global::System.IDisposable
{
    private readonly global::System.IO.Stream output;
    internal JavaObjectOutputStream(global::System.IO.Stream output) => this.output = output;
    internal void WriteObject(object? value) => Support.WriteSerializedObject(output, value);
    public void Dispose() => output.Flush();
}

internal sealed class JavaObjectInputStream : global::System.IDisposable
{
    private readonly global::System.IO.Stream input;
    internal JavaObjectInputStream(global::System.IO.Stream input) => this.input = input;
    internal object? ReadObject() => Support.ReadSerializedObject(input);
    public void Dispose() { }
}

// ParserKeywordsUtilsTest uses JavaCC's grammar model to compare the keywords
// represented by the generated parser with the source grammar. The shipped
// test project consumes the mechanically translated parser's governed token
// image table, so this target-owned model performs the same literal-token walk
// without shipping or invoking JavaCC at test time.
internal class JavaCcRegularExpression { }

internal sealed class JavaCcRStringLiteral : JavaCcRegularExpression
{
    internal JavaCcRStringLiteral(string image) => Image = image;
    public string Image;
}

internal sealed class JavaCcRCharacterList : JavaCcRegularExpression { }

internal sealed class JavaCcRChoice : JavaCcRegularExpression
{
    public global::System.Collections.Generic.IList<JavaCcRegularExpression> Choices { get; } =
        new global::System.Collections.Generic.List<JavaCcRegularExpression>();

    public global::System.Collections.Generic.IList<JavaCcRegularExpression> GetChoices() =>
        Choices;
}

internal sealed class JavaCcRSequence : JavaCcRegularExpression
{
    public global::System.Collections.Generic.IList<object> Units { get; } =
        new global::System.Collections.Generic.List<object>();
}

internal sealed class JavaCcToken
{
    internal JavaCcToken(string image) => Image = image;
    public string Image;
}

internal abstract class JavaCcRepetition : JavaCcRegularExpression
{
    public global::System.Collections.Generic.IList<JavaCcToken> LhsTokens { get; } =
        new global::System.Collections.Generic.List<JavaCcToken>();
}

internal sealed class JavaCcROneOrMore : JavaCcRepetition { }
internal sealed class JavaCcRZeroOrMore : JavaCcRepetition { }
internal sealed class JavaCcRZeroOrOne : JavaCcRepetition { }
internal sealed class JavaCcRJustName : JavaCcRepetition { }

internal static class JavaCcGlobals
{
    public static global::System.Collections.Generic.IDictionary<int, JavaCcRegularExpression>
        RexpsOfTokens { get; internal set; } =
            new global::System.Collections.Generic.Dictionary<int, JavaCcRegularExpression>();
}

internal sealed class JavaCcParser
{
    private readonly global::System.IO.Stream input;

    internal JavaCcParser(global::System.IO.Stream input) => this.input = input;

    internal void JavaccInput()
    {
        // Retain the source-stream lifecycle of JavaCCParser even though the
        // token graph comes from the translated JavaCC output.
        _ = input.CanRead;
        var expressions =
            new global::System.Collections.Generic.Dictionary<int, JavaCcRegularExpression>();
        string[] images =
            global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.tokenImage;
        for (int index = 0; index < images.Length; index++)
        {
            string image = images[index];
            if (image is { Length: >= 2 } && image[0] == '"' && image[^1] == '"')
            {
                expressions[index] = new JavaCcRStringLiteral(
                    image.Substring(1, image.Length - 2));
            }
        }
        var dateTimeLiteral = new JavaCcRChoice();
        foreach (string image in new[] { "DATE", "DATETIME", "TIME", "TIMESTAMP", "TIMESTAMPTZ" })
            dateTimeLiteral.Choices.Add(new JavaCcRStringLiteral(image));
        expressions[global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.K_DATETIMELITERAL] =
            dateTimeLiteral;
        var dateLiteral = new JavaCcRChoice();
        foreach (string image in new[] { "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND" })
            dateLiteral.Choices.Add(new JavaCcRStringLiteral(image));
        expressions[global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.K_DATE_LITERAL] =
            dateLiteral;
        var selectLiteral = new JavaCcRChoice();
        foreach (string image in new[] { "SELECT", "SEL" })
            selectLiteral.Choices.Add(new JavaCcRStringLiteral(image));
        expressions[global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.K_SELECT] =
            selectLiteral;
        var stringFunctionLiteral = new JavaCcRChoice();
        foreach (string image in new[] { "SUBSTR", "SUBSTRING", "TRIM", "POSITION", "OVERLAY" })
            stringFunctionLiteral.Choices.Add(new JavaCcRStringLiteral(image));
        expressions[global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.K_STRING_FUNCTION_NAME] =
            stringFunctionLiteral;
        expressions[global::DripSharp.SqlTrellis.Parser.CCJSqlParserConstants.K_NEXTVAL] =
            new JavaCcRStringLiteral("NEXTVAL");

        JavaCcGlobals.RexpsOfTokens = expressions;
    }
}

internal static class JavaCcErrors
{
    internal static void ReInit() { }
}

internal static class JavaCcSemanticize
{
    internal static void Start() { }
}

internal sealed class JavaCcJjTree
{
    internal void Main(string[] arguments)
    {
        string prefix = "-OUTPUT_DIRECTORY=";
        string outputDirectory = global::System.Array.Find(
            arguments,
            argument => argument.StartsWith(prefix, global::System.StringComparison.Ordinal))?
            .Substring(prefix.Length)
            ?? throw new global::System.ArgumentException(
                "JJTree output directory argument is missing.", nameof(arguments));
        string source = arguments[^1];
        global::System.IO.Directory.CreateDirectory(outputDirectory);
        global::System.IO.File.Copy(
            source,
            global::System.IO.Path.Combine(outputDirectory, "JSqlParserCC.jj"),
            overwrite: true);
    }
}
