// Product-neutral exact reflection probe for a compiled .NET assembly.
using System.Reflection;
using System.Globalization;
using System.Runtime.Loader;
using System.Text;

internal static class Program
{
    private const string Magic = "DRIPSHARP_DOTNET_ACCESSIBLE_SURFACE_V1";
    private const BindingFlags DeclaredMembers = BindingFlags.Public | BindingFlags.NonPublic |
        BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly;
    private static readonly NullabilityInfoContext Nullability = new();

    private sealed record Row(
        string Assembly,
        string Owner,
        string Kind,
        string Name,
        int ParameterCount,
        string Visibility,
        string MetadataFlags,
        string Signature,
        string GenericConstraints,
        string NullabilityMetadata)
    {
        internal string Identity => string.Join('\0', Assembly, Owner, Kind, Name, Signature);
        internal string Tsv => string.Join('\t', new[]
        {
            Clean(Assembly), Clean(Owner), Clean(Kind), Clean(Name), ParameterCount.ToString(),
            Clean(Visibility), Clean(MetadataFlags), Clean(Signature), Clean(GenericConstraints),
            Clean(NullabilityMetadata)
        });
    }

    private sealed class ComponentLoadContext(string assemblyPath) : AssemblyLoadContext
    {
        private readonly AssemblyDependencyResolver _resolver = new(assemblyPath);
        private readonly string _directory = Path.GetDirectoryName(assemblyPath)!;

        protected override Assembly? Load(AssemblyName assemblyName)
        {
            var resolved = _resolver.ResolveAssemblyToPath(assemblyName);
            if (resolved is not null)
                return LoadFromAssemblyPath(resolved);
            var candidate = Path.Combine(_directory, assemblyName.Name + ".dll");
            return File.Exists(candidate) ? LoadFromAssemblyPath(candidate) : null;
        }
    }

    private static int Main(string[] args)
    {
        if (args.Length != 2)
        {
            Console.Error.WriteLine("Usage: DotNetSurfaceProbe <output.tsv> <assembly>");
            return 2;
        }

        var output = Path.GetFullPath(args[0]);
        var assemblyPath = Path.GetFullPath(args[1]);
        if (!File.Exists(assemblyPath))
            throw new FileNotFoundException("Surface assembly is missing", assemblyPath);

        var loadContext = new ComponentLoadContext(assemblyPath);
        var rows = Extract(loadContext.LoadFromAssemblyPath(assemblyPath));
        rows.Sort((left, right) => StringComparer.Ordinal.Compare(left.Tsv, right.Tsv));
        var duplicate = rows.GroupBy(row => row.Identity, StringComparer.Ordinal)
            .FirstOrDefault(group => group.Count() > 1);
        if (duplicate is not null)
            throw new InvalidOperationException("Duplicate reflected row: " + duplicate.Key);

        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        var text = new StringBuilder("# ").Append(Magic).Append('\n');
        text.AppendLine(
            "assembly\towner\tkind\tname\tparameter-count\tvisibility\tmetadata-flags\t" +
            "signature\tgeneric-constraints\tnullability");
        foreach (var row in rows) text.AppendLine(row.Tsv);
        File.WriteAllText(output, text.ToString(), new UTF8Encoding(false));

        var types = rows.Count(row => row.Kind == "type");
        Console.WriteLine(
            $"Exact accessible surface reflection passed: {types} types, " +
            $"{rows.Count - types} members.");
        return 0;
    }

    private static List<Row> Extract(Assembly assembly)
    {
        var rows = new List<Row>();
        foreach (var type in AllTypes(assembly).Where(IsExternallyVisible)
                     .OrderBy(TypeName, StringComparer.Ordinal))
        {
            var assemblyName = assembly.GetName().Name ?? "-";
            var owner = TypeName(type);
            rows.Add(new Row(
                assemblyName, owner, "type", SimpleTypeName(type), 0, Visibility(type),
                Flags(type.Attributes), TypeSignature(type),
                GenericConstraints(OwnGenericArguments(type)),
                "type=" + (type.IsValueType ? "not-null" : "oblivious")));

            foreach (var constructor in type.GetConstructors(DeclaredMembers)
                         .Where(IsExternallyAccessible))
                rows.Add(ExecutableRow(assemblyName, owner, constructor, ".ctor", "constructor"));

            foreach (var field in type.GetFields(DeclaredMembers)
                         .Where(IsExternallyAccessible))
                rows.Add(new Row(
                    assemblyName, owner, "field", field.Name, 0, Visibility(field),
                    Flags(field.Attributes), FieldSignature(field), "-",
                    "value=" + NullabilityDisplay(() => Nullability.Create(field))));

            foreach (var method in type.GetMethods(DeclaredMembers)
                         .Where(IsExternallyAccessible))
                rows.Add(ExecutableRow(assemblyName, owner, method, method.Name, "method"));

            foreach (var property in type.GetProperties(DeclaredMembers))
            {
                var accessors = property.GetAccessors(nonPublic: true)
                    .Where(IsExternallyAccessible).ToArray();
                if (accessors.Length == 0) continue;
                var indexes = property.GetIndexParameters();
                rows.Add(new Row(
                    assemblyName, owner, "property", property.Name, indexes.Length,
                    AccessorVisibility(property.GetMethod, property.SetMethod),
                    Flags(property.Attributes), PropertySignature(property), "-",
                    PropertyNullability(property)));
            }

            foreach (var eventInfo in type.GetEvents(DeclaredMembers))
            {
                var accessors = new[]
                    { eventInfo.AddMethod, eventInfo.RemoveMethod, eventInfo.RaiseMethod }
                    .OfType<MethodInfo>().Where(IsExternallyAccessible).ToArray();
                if (accessors.Length == 0) continue;
                rows.Add(new Row(
                    assemblyName, owner, "event", eventInfo.Name, 0,
                    AccessorVisibility(eventInfo.AddMethod, eventInfo.RemoveMethod,
                                       eventInfo.RaiseMethod),
                    Flags(eventInfo.Attributes),
                    TypeDisplay(eventInfo.EventHandlerType!) + " " + eventInfo.Name,
                    "-", "value=" + NullabilityDisplay(() => Nullability.Create(eventInfo))));
            }
        }
        return rows;
    }

    private static Row ExecutableRow(
        string assembly,
        string owner,
        MethodBase method,
        string name,
        string kind)
    {
        var parameters = method.GetParameters();
        var nullability = new List<string>();
        if (method is MethodInfo info)
            nullability.Add("return=" +
                NullabilityDisplay(() => Nullability.Create(info.ReturnParameter)));
        nullability.AddRange(parameters.Select((parameter, index) =>
            "param" + index + "=" + NullabilityDisplay(() => Nullability.Create(parameter))));
        return new Row(
            assembly, owner, kind, name, parameters.Length, Visibility(method),
            Flags(method.Attributes), MethodSignature(method),
            GenericConstraints(method.IsGenericMethod ? method.GetGenericArguments() : []),
            nullability.Count == 0 ? "-" : string.Join(';', nullability));
    }

    private static string TypeSignature(Type type)
    {
        var kind = type.IsEnum ? "enum" : type.IsInterface ? "interface" :
            type.IsAbstract && type.IsSealed ? "static-class" :
            typeof(MulticastDelegate).IsAssignableFrom(type.BaseType) ? "delegate" :
            type.IsValueType ? "struct" : "class";
        var bases = new List<string>();
        if (type.BaseType is not null && type.BaseType != typeof(object) &&
            type.BaseType != typeof(ValueType) && type.BaseType != typeof(Enum) &&
            type.BaseType != typeof(MulticastDelegate))
            bases.Add(TypeDisplay(type.BaseType));
        bases.AddRange(type.GetInterfaces().Select(TypeDisplay).Order(StringComparer.Ordinal));
        return kind + " " + SimpleTypeName(type) +
            (bases.Count == 0 ? "" : " : " + string.Join(',', bases));
    }

    private static string MethodSignature(MethodBase method)
    {
        var text = new StringBuilder();
        if (method is MethodInfo info)
        {
            if (info.IsGenericMethodDefinition)
                text.Append('<').Append(string.Join(',', info.GetGenericArguments()
                    .Select(argument => argument.Name))).Append("> ");
            text.Append(TypeDisplay(info.ReturnType))
                .Append(CustomModifiers(info.ReturnParameter)).Append(' ');
        }
        else text.Append(SimpleTypeName(method.DeclaringType!)).Append(' ');
        text.Append(method is ConstructorInfo ? ".ctor" : method.Name).Append('(')
            .Append(string.Join(',', method.GetParameters().Select(ParameterDisplay)))
            .Append(')');
        return text.ToString();
    }

    private static string PropertySignature(PropertyInfo property)
    {
        var indexes = property.GetIndexParameters();
        return TypeDisplay(property.PropertyType) + CustomModifiers(property) + " " +
            property.Name +
            (indexes.Length == 0 ? "" :
                "[" + string.Join(',', indexes.Select(ParameterDisplay)) + "]") +
            " {" + string.Join(';', new[]
            {
                IsExternallyAccessible(property.GetMethod) ? "get" : null,
                IsExternallyAccessible(property.SetMethod) ? "set" : null
            }.OfType<string>()) + "}";
    }

    private static string PropertyNullability(PropertyInfo property)
    {
        var result = new List<string>
            { "value=" + NullabilityDisplay(() => Nullability.Create(property)) };
        result.AddRange(property.GetIndexParameters().Select((parameter, index) =>
            "param" + index + "=" + NullabilityDisplay(() => Nullability.Create(parameter))));
        return string.Join(';', result);
    }

    private static string ParameterDisplay(ParameterInfo parameter)
    {
        var prefix = parameter.IsOut ? "out " :
            parameter.ParameterType.IsByRef && parameter.IsIn ? "in " :
            parameter.ParameterType.IsByRef ? "ref " : "";
        var type = parameter.ParameterType.IsByRef
            ? parameter.ParameterType.GetElementType()!
            : parameter.ParameterType;
        var defaultValue = parameter.HasDefaultValue
            ? " = " + ConstantDisplay(parameter.RawDefaultValue)
            : "";
        return prefix + TypeDisplay(type) + CustomModifiers(parameter) + " " +
            parameter.Name + defaultValue;
    }

    private static string GenericConstraints(IEnumerable<Type> arguments)
    {
        var constraints = new List<string>();
        foreach (var argument in arguments.Where(argument => argument.IsGenericParameter))
        {
            var values = new List<string>();
            var attributes = argument.GenericParameterAttributes;
            var variance = attributes & GenericParameterAttributes.VarianceMask;
            if (variance == GenericParameterAttributes.Covariant) values.Add("out");
            if (variance == GenericParameterAttributes.Contravariant) values.Add("in");
            if ((attributes & GenericParameterAttributes.ReferenceTypeConstraint) != 0)
                values.Add("class");
            if ((attributes & GenericParameterAttributes.NotNullableValueTypeConstraint) != 0)
                values.Add("struct");
            if ((attributes & GenericParameterAttributes.DefaultConstructorConstraint) != 0)
                values.Add("new()");
            values.AddRange(argument.GetGenericParameterConstraints()
                .Select(TypeDisplay).Order(StringComparer.Ordinal));
            values.AddRange(argument.GetCustomAttributesData()
                .Select(AttributeDisplay).Order(StringComparer.Ordinal));
            constraints.Add(argument.Name +
                (values.Count == 0 ? "" : ":" + string.Join('&', values)));
        }
        return constraints.Count == 0 ? "-" : string.Join(';', constraints);
    }

    private static IEnumerable<Type> OwnGenericArguments(Type type)
    {
        var inherited = type.DeclaringType?.GetGenericArguments().Length ?? 0;
        return type.GetGenericArguments().Skip(inherited);
    }

    private static bool IsExternallyVisible(Type type) =>
        (type.IsPublic || type.IsNestedPublic || type.IsNestedFamily ||
         type.IsNestedFamORAssem) &&
        (type.DeclaringType is null || IsExternallyVisible(type.DeclaringType));

    private static bool IsExternallyAccessible(MethodBase? method) =>
        method is not null && (method.IsPublic || method.IsFamily || method.IsFamilyOrAssembly);

    private static bool IsExternallyAccessible(FieldInfo field) =>
        field.IsPublic || field.IsFamily || field.IsFamilyOrAssembly;

    private static string Visibility(Type type) => type.IsPublic || type.IsNestedPublic
        ? "public" : type.IsNestedFamily ? "protected" : "protected-internal";

    private static string Visibility(MethodBase method) => method.IsPublic
        ? "public" : method.IsFamily ? "protected" : "protected-internal";

    private static string Visibility(FieldInfo field) => field.IsPublic
        ? "public" : field.IsFamily ? "protected" : "protected-internal";

    private static string AccessorVisibility(params MethodInfo?[] accessors) =>
        string.Join(';', accessors.OfType<MethodInfo>().Where(IsExternallyAccessible)
            .Select(method => method.Name.Split('_', 2)[0] + "=" + Visibility(method)));

    private static IEnumerable<Type> AllTypes(Assembly assembly)
    {
        try { return assembly.GetTypes(); }
        catch (ReflectionTypeLoadException error)
        {
            var loaderErrors = string.Join(" | ", error.LoaderExceptions
                .Where(item => item is not null)
                .Select(item => item!.GetType().Name + ": " + item.Message));
            throw new InvalidOperationException(
                "Could not load every assembly type: " + loaderErrors, error);
        }
    }

    private static string TypeName(Type type) => (type.FullName ?? type.Name).Replace('+', '$');

    private static string SimpleTypeName(Type type)
    {
        var name = type.Name;
        var tick = name.IndexOf('`', StringComparison.Ordinal);
        return tick < 0 ? name : name[..tick];
    }

    private static string TypeDisplay(Type type)
    {
        if (type.IsGenericParameter) return type.Name;
        if (type.IsArray) return TypeDisplay(type.GetElementType()!) + "[" +
            new string(',', type.GetArrayRank() - 1) + "]";
        if (type.IsPointer) return TypeDisplay(type.GetElementType()!) + "*";
        if (type.IsByRef) return TypeDisplay(type.GetElementType()!) + "&";
        if (!type.IsGenericType) return TypeName(type);
        var definition = type.GetGenericTypeDefinition();
        var name = TypeName(definition);
        var tick = name.IndexOf('`', StringComparison.Ordinal);
        if (tick >= 0) name = name[..tick];
        return name + "<" + string.Join(',', type.GetGenericArguments().Select(TypeDisplay)) + ">";
    }

    private static string FieldSignature(FieldInfo field) =>
        TypeDisplay(field.FieldType) + CustomModifiers(field) + " " + field.Name +
        (field.IsLiteral ? " = " + ConstantDisplay(field.GetRawConstantValue()) : "");

    private static string CustomModifiers(ParameterInfo parameter) =>
        CustomModifiers(parameter.GetRequiredCustomModifiers(),
                        parameter.GetOptionalCustomModifiers());

    private static string CustomModifiers(FieldInfo field) =>
        CustomModifiers(field.GetRequiredCustomModifiers(), field.GetOptionalCustomModifiers());

    private static string CustomModifiers(PropertyInfo property) =>
        CustomModifiers(property.GetRequiredCustomModifiers(), property.GetOptionalCustomModifiers());

    private static string CustomModifiers(Type[] required, Type[] optional)
    {
        var values = required.Select(type => "modreq(" + TypeDisplay(type) + ")")
            .Concat(optional.Select(type => "modopt(" + TypeDisplay(type) + ")"));
        var text = string.Join(',', values);
        return text.Length == 0 ? "" : " " + text;
    }

    private static string ConstantDisplay(object? value) => value switch
    {
        null => "null",
        DBNull => "dbnull",
        Missing => "missing",
        string text => "\"" + text.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal)
            .Replace("\"", "\\\"", StringComparison.Ordinal) + "\"",
        char character => "U+" + ((int)character).ToString("X4", CultureInfo.InvariantCulture),
        float number => number.ToString("R", CultureInfo.InvariantCulture),
        double number => number.ToString("R", CultureInfo.InvariantCulture),
        IFormattable formattable => formattable.ToString(null, CultureInfo.InvariantCulture),
        _ => value.ToString() ?? "null"
    };

    private static string AttributeDisplay(CustomAttributeData attribute)
    {
        var arguments = attribute.ConstructorArguments.Select(AttributeArgument)
            .Concat(attribute.NamedArguments.Select(argument =>
                argument.MemberName + "=" + AttributeArgument(argument.TypedValue)));
        return "attribute(" + TypeDisplay(attribute.AttributeType) + "(" +
            string.Join(',', arguments) + "))";
    }

    private static string AttributeArgument(CustomAttributeTypedArgument argument)
    {
        if (argument.Value is IEnumerable<CustomAttributeTypedArgument> values)
            return "[" + string.Join(',', values.Select(AttributeArgument)) + "]";
        return ConstantDisplay(argument.Value);
    }

    private static string Flags<T>(T value) where T : struct, Enum =>
        "0x" + Convert.ToUInt64(value, CultureInfo.InvariantCulture)
            .ToString("X", CultureInfo.InvariantCulture);

    private static string NullabilityDisplay(Func<NullabilityInfo> read)
    {
        try
        {
            return NullabilityDisplay(read());
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException or
                                      NotSupportedException)
        {
            return "unspecified";
        }
    }

    private static string NullabilityDisplay(NullabilityInfo info)
    {
        var text = NullabilityState(info.ReadState);
        if (info.WriteState != info.ReadState)
            text += ",write=" + NullabilityState(info.WriteState);
        if (info.ElementType is not null)
            text += "[element=" + NullabilityDisplay(info.ElementType) + "]";
        if (info.GenericTypeArguments.Length != 0)
            text += "<" + string.Join(',', info.GenericTypeArguments.Select((argument, index) =>
                "arg" + index + "=" + NullabilityDisplay(argument))) + ">";
        return text;
    }

    private static string NullabilityState(System.Reflection.NullabilityState state) => state switch
    {
        System.Reflection.NullabilityState.NotNull => "non-null",
        System.Reflection.NullabilityState.Nullable => "nullable",
        _ => "unspecified"
    };

    private static string Clean(string? value) => string.IsNullOrWhiteSpace(value) ? "-" :
        value.Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", " ", StringComparison.Ordinal)
            .Replace("\n", " ", StringComparison.Ordinal);
}
