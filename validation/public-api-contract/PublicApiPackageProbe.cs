// Independent reflection probe for assemblies extracted from the produced NuGet packages.
using System.Reflection;
using System.Runtime.CompilerServices;

internal static class PublicApiPackageProbe
{
    private const string Magic = "DRIPSHARP_DOTNET_PUBLIC_API_V1";
    private static readonly NullabilityInfoContext Nullability = new();

    private sealed record Row(
        string Assembly,
        string Owner,
        string Kind,
        string Name,
        int ParameterCount,
        string Signature,
        string GenericConstraints,
        string Nullability,
        string Exceptions,
        string Delegate,
        string Lifecycle)
    {
        internal string Key => string.Join('\0', Owner, Kind, Name, Signature);
        internal string Tsv => string.Join('\t', new[]
        {
            Clean(Assembly), Clean(Owner), Clean(Kind), Clean(Name), ParameterCount.ToString(),
            Clean(Signature), Clean(GenericConstraints), Clean(Nullability), Clean(Exceptions),
            Clean(Delegate), Clean(Lifecycle)
        });
    }

    public static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("Usage: PublicApiPackageProbe <assembly> [<assembly> ...]");
            return 2;
        }

        var assemblyPaths = args.Select(Path.GetFullPath).Order(StringComparer.Ordinal).ToArray();
        foreach (var path in assemblyPaths)
            if (!File.Exists(path)) throw new FileNotFoundException("Package assembly is missing", path);

        var directories = assemblyPaths.Select(Path.GetDirectoryName).OfType<string>().Distinct(StringComparer.Ordinal).ToArray();
        AppDomain.CurrentDomain.AssemblyResolve += (_, eventArgs) =>
        {
            var name = new AssemblyName(eventArgs.Name).Name + ".dll";
            var match = directories.Select(directory => Path.Combine(directory, name))
                .FirstOrDefault(File.Exists);
            return match is null ? null : Assembly.LoadFrom(match);
        };

        var rows = new List<Row>();
        foreach (var path in assemblyPaths) Extract(Assembly.LoadFrom(path), rows);
        rows.Sort((left, right) => StringComparer.Ordinal.Compare(left.Key, right.Key));
        var duplicate = rows.GroupBy(row => row.Key, StringComparer.Ordinal).FirstOrDefault(group => group.Count() > 1);
        if (duplicate is not null) throw new InvalidOperationException("Duplicate reflected row: " + duplicate.Key);

        Console.WriteLine("# " + Magic);
        Console.WriteLine("assembly\towner\tkind\tname\tparameter-count\tsignature\tgeneric-constraints\tnullability\texceptions\tdelegate\tlifecycle");
        foreach (var row in rows) Console.WriteLine(row.Tsv);
        return 0;
    }

    private static void Extract(Assembly assembly, ICollection<Row> rows)
    {
        foreach (var type in ExportedTypes(assembly).OrderBy(TypeName, StringComparer.Ordinal))
        {
            var owner = TypeName(type);
            var assemblyName = assembly.GetName().Name ?? "-";
            var delegateType = typeof(MulticastDelegate).IsAssignableFrom(type.BaseType);
            rows.Add(new Row(
                assemblyName,
                owner,
                "type",
                SimpleTypeName(type),
                0,
                TypeSignature(type),
                GenericConstraints(type.GetGenericArguments().Where(argument => argument.IsGenericParameter)),
                "type=" + TypeNullability(type),
                "-",
                delegateType ? "delegate" : (type.IsInterface ? "interface" : "-"),
                typeof(IDisposable).IsAssignableFrom(type) ? "disposable" : "-"));

            const BindingFlags declaredPublic = BindingFlags.Public | BindingFlags.Instance |
                BindingFlags.Static | BindingFlags.DeclaredOnly;

            foreach (var constructor in type.GetConstructors(declaredPublic))
                rows.Add(ExecutableRow(assemblyName, owner, constructor, ".ctor", "constructor", delegateType));

            foreach (var property in type.GetProperties(declaredPublic))
            {
                var accessors = property.GetAccessors(nonPublic: false);
                if (accessors.Length == 0) continue;
                var indexParameters = property.GetIndexParameters();
                rows.Add(new Row(
                    assemblyName,
                    owner,
                    "property",
                    property.Name,
                    indexParameters.Length,
                    PropertySignature(property),
                    "-",
                    PropertyNullability(property),
                    "-",
                    "-",
                    "-"));
            }

            foreach (var field in type.GetFields(declaredPublic))
            {
                rows.Add(new Row(
                    assemblyName,
                    owner,
                    field.IsLiteral && type.IsEnum ? "enum-value" : "field",
                    field.Name,
                    0,
                    TypeDisplay(field.FieldType) + " " + field.Name,
                    "-",
                    "value=" + NullabilityState(() => Nullability.Create(field)),
                    "-",
                    "-",
                    "-"));
            }

            foreach (var method in type.GetMethods(declaredPublic).Where(method => !method.IsSpecialName))
                rows.Add(ExecutableRow(assemblyName, owner, method, method.Name, "method", delegateType));
        }
    }

    private static Row ExecutableRow(
        string assembly,
        string owner,
        MethodBase method,
        string name,
        string kind,
        bool delegateType)
    {
        var parameters = method.GetParameters();
        var signature = new List<string>();
        if (method is MethodInfo info)
        {
            if (info.IsGenericMethodDefinition)
                signature.Add("<" + string.Join(',', info.GetGenericArguments().Select(argument => argument.Name)) + ">");
            signature.Add(TypeDisplay(info.ReturnType));
        }
        else signature.Add(SimpleOwnerName(owner));
        signature.Add(name + "(" + string.Join(',', parameters.Select(ParameterDisplay)) + ")");
        var nullability = new List<string>();
        if (method is MethodInfo returnMethod)
            nullability.Add("return=" + NullabilityState(() => Nullability.Create(returnMethod.ReturnParameter)));
        for (var index = 0; index < parameters.Length; index++)
        {
            var parameter = parameters[index];
            nullability.Add("param" + index + "=" + NullabilityState(() => Nullability.Create(parameter)));
        }
        var lifecycle = name == "Dispose" && parameters.Length == 0 ? "dispose" :
            kind == "constructor" ? "construct" : "-";
        return new Row(
            assembly,
            owner,
            kind,
            name,
            parameters.Length,
            string.Join(' ', signature),
            GenericConstraints(method.IsGenericMethod ? method.GetGenericArguments() : Array.Empty<Type>()),
            nullability.Count == 0 ? "-" : string.Join(';', nullability),
            "not-representable-in-cli-metadata",
            delegateType && name == "Invoke" ? "invoke" : "-",
            lifecycle);
    }

    private static string TypeSignature(Type type)
    {
        var kind = type.IsEnum ? "enum" : type.IsInterface ? "interface" :
            type.IsAbstract && type.IsSealed ? "static-class" :
            typeof(MulticastDelegate).IsAssignableFrom(type.BaseType) ? "delegate" :
            type.IsValueType ? "struct" : "class";
        var bases = new List<string>();
        if (type.BaseType is not null && type.BaseType != typeof(object) && type.BaseType != typeof(ValueType) &&
            type.BaseType != typeof(Enum) && type.BaseType != typeof(MulticastDelegate))
            bases.Add(TypeDisplay(type.BaseType));
        bases.AddRange(type.GetInterfaces().Select(TypeDisplay).Order(StringComparer.Ordinal));
        return kind + " " + SimpleTypeName(type) + (bases.Count == 0 ? "" : " : " + string.Join(',', bases));
    }

    private static string PropertySignature(PropertyInfo property)
    {
        var access = new List<string>();
        if (property.GetMethod?.IsPublic == true) access.Add("get");
        if (property.SetMethod?.IsPublic == true) access.Add("set");
        var indexes = property.GetIndexParameters();
        return TypeDisplay(property.PropertyType) + " " + property.Name +
            (indexes.Length == 0 ? "" : "[" + string.Join(',', indexes.Select(ParameterDisplay)) + "]") +
            " {" + string.Join(';', access) + "}";
    }

    private static string PropertyNullability(PropertyInfo property)
    {
        string value = NullabilityState(() => Nullability.Create(property));
        var indexes = property.GetIndexParameters();
        var result = new List<string> { "value=" + value };
        for (var index = 0; index < indexes.Length; index++)
            result.Add("param" + index + "=" + NullabilityState(() => Nullability.Create(indexes[index])));
        return string.Join(';', result);
    }

    private static string ParameterDisplay(ParameterInfo parameter)
    {
        var prefix = parameter.IsOut ? "out " : parameter.ParameterType.IsByRef ? "ref " : "";
        var type = parameter.ParameterType.IsByRef ? parameter.ParameterType.GetElementType()! : parameter.ParameterType;
        return prefix + TypeDisplay(type) + " " + parameter.Name;
    }

    private static string GenericConstraints(IEnumerable<Type> arguments)
    {
        var constraints = new List<string>();
        foreach (var argument in arguments.Where(argument => argument.IsGenericParameter))
        {
            var values = new List<string>();
            var attributes = argument.GenericParameterAttributes;
            if ((attributes & GenericParameterAttributes.ReferenceTypeConstraint) != 0) values.Add("class");
            if ((attributes & GenericParameterAttributes.NotNullableValueTypeConstraint) != 0) values.Add("struct");
            if ((attributes & GenericParameterAttributes.DefaultConstructorConstraint) != 0) values.Add("new()");
            values.AddRange(argument.GetGenericParameterConstraints().Select(TypeDisplay).Order(StringComparer.Ordinal));
            constraints.Add(argument.Name + (values.Count == 0 ? "" : ":" + string.Join('&', values)));
        }
        return constraints.Count == 0 ? "-" : string.Join(';', constraints);
    }

    private static IEnumerable<Type> ExportedTypes(Assembly assembly)
    {
        try { return assembly.GetExportedTypes(); }
        catch (ReflectionTypeLoadException error)
        {
            var loaderErrors = string.Join(" | ", error.LoaderExceptions.Where(item => item is not null)
                .Select(item => item!.GetType().Name + ": " + item.Message));
            throw new InvalidOperationException("Could not load every public package type: " + loaderErrors, error);
        }
    }

    private static string TypeName(Type type) =>
        (type.FullName ?? type.Name).Replace('+', '$');

    private static string SimpleTypeName(Type type)
    {
        var name = type.Name;
        var tick = name.IndexOf('`', StringComparison.Ordinal);
        return tick < 0 ? name : name[..tick];
    }

    private static string SimpleOwnerName(string owner)
    {
        var index = Math.Max(owner.LastIndexOf('.'), owner.LastIndexOf('$'));
        var name = index < 0 ? owner : owner[(index + 1)..];
        var tick = name.IndexOf('`', StringComparison.Ordinal);
        return tick < 0 ? name : name[..tick];
    }

    private static string TypeDisplay(Type type)
    {
        if (type.IsGenericParameter) return type.Name;
        if (type.IsArray) return TypeDisplay(type.GetElementType()!) + "[]";
        if (type.IsPointer) return TypeDisplay(type.GetElementType()!) + "*";
        if (type.IsByRef) return TypeDisplay(type.GetElementType()!) + "&";
        if (!type.IsGenericType) return TypeName(type);
        var definition = type.GetGenericTypeDefinition();
        var name = TypeName(definition);
        var tick = name.IndexOf('`', StringComparison.Ordinal);
        if (tick >= 0) name = name[..tick];
        return name + "<" + string.Join(',', type.GetGenericArguments().Select(TypeDisplay)) + ">";
    }

    private static string TypeNullability(Type type) => type.IsValueType ? "not-null" : "oblivious";

    private static string NullabilityState(Func<NullabilityInfo> read)
    {
        try
        {
            return read().ReadState switch
            {
                System.Reflection.NullabilityState.NotNull => "non-null",
                System.Reflection.NullabilityState.Nullable => "nullable",
                _ => "unspecified"
            };
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or NotSupportedException)
        {
            return "unspecified";
        }
    }

    private static string Clean(string? value) => string.IsNullOrWhiteSpace(value) ? "-" :
        value.Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", " ", StringComparison.Ordinal)
            .Replace("\n", " ", StringComparison.Ordinal);
}
