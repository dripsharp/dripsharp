using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Security.Cryptography;
using System.Text;

internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.Length < 2)
        {
            throw new ArgumentException("package path, assembly entry, and expected resources are required");
        }

        using var package = ZipFile.OpenRead(args[0]);
        var assemblyEntry = package.GetEntry(args[1])
            ?? throw new InvalidDataException($"Package assembly entry is missing: {args[1]}");
        using var packageAssembly = assemblyEntry.Open();
        using var assembly = new MemoryStream();
        packageAssembly.CopyTo(assembly);
        assembly.Position = 0;
        using var portableExecutable = new PEReader(assembly);
        var metadata = portableExecutable.GetMetadataReader();
        var actual = metadata.ManifestResources
            .Select(handle => metadata.GetString(metadata.GetManifestResource(handle).Name))
            .OrderBy(name => name, StringComparer.Ordinal)
            .ToArray();
        var expected = args.Skip(2).OrderBy(name => name, StringComparer.Ordinal).ToArray();

        if (!actual.SequenceEqual(expected, StringComparer.Ordinal))
        {
            throw new InvalidDataException(
                $"Embedded resources differ. Expected [{string.Join(", ", expected)}], " +
                $"actual [{string.Join(", ", actual)}].");
        }

        Console.WriteLine($"Embedded resource inspection passed: {actual.Length}");
        var surface = PublicSurface(metadata);
        if (surface.Types == 0 || surface.Members == 0)
        {
            throw new InvalidDataException(
                $"Package assembly has an empty public surface: {surface.Types} types, " +
                $"{surface.Members} members.");
        }

        Console.WriteLine(
            $"Public surface inspection passed: {surface.Types} types, " +
            $"{surface.Members} members, SHA-256 {surface.Fingerprint}");
        return 0;
    }

    private static (int Types, int Members, string Fingerprint) PublicSurface(
        MetadataReader metadata)
    {
        var entries = new List<string>();
        var typeCount = 0;
        var memberCount = 0;

        foreach (var handle in metadata.TypeDefinitions)
        {
            var type = metadata.GetTypeDefinition(handle);
            if (!IsExternallyVisible(metadata, type))
            {
                continue;
            }

            typeCount++;
            var typeName = QualifiedName(metadata, type);
            entries.Add($"type|{typeName}|{(int)type.Attributes}");

            foreach (var fieldHandle in type.GetFields())
            {
                var field = metadata.GetFieldDefinition(fieldHandle);
                if (!IsExternallyAccessible(field.Attributes))
                {
                    continue;
                }

                memberCount++;
                entries.Add(
                    $"field|{typeName}|{metadata.GetString(field.Name)}|" +
                    $"{Signature(metadata, field.Signature)}|{(int)field.Attributes}");
            }

            foreach (var methodHandle in type.GetMethods())
            {
                var method = metadata.GetMethodDefinition(methodHandle);
                if (!IsExternallyAccessible(method.Attributes))
                {
                    continue;
                }

                memberCount++;
                entries.Add(
                    $"method|{typeName}|{metadata.GetString(method.Name)}|" +
                    $"{Signature(metadata, method.Signature)}|{(int)method.Attributes}");
            }

            foreach (var propertyHandle in type.GetProperties())
            {
                var property = metadata.GetPropertyDefinition(propertyHandle);
                var accessors = property.GetAccessors();
                if (!IsExternallyAccessible(metadata, accessors.Getter) &&
                    !IsExternallyAccessible(metadata, accessors.Setter))
                {
                    continue;
                }

                memberCount++;
                entries.Add(
                    $"property|{typeName}|{metadata.GetString(property.Name)}|" +
                    $"{Signature(metadata, property.Signature)}|{(int)property.Attributes}");
            }

            foreach (var eventHandle in type.GetEvents())
            {
                var eventDefinition = metadata.GetEventDefinition(eventHandle);
                var accessors = eventDefinition.GetAccessors();
                if (!IsExternallyAccessible(metadata, accessors.Adder) &&
                    !IsExternallyAccessible(metadata, accessors.Remover) &&
                    !IsExternallyAccessible(metadata, accessors.Raiser))
                {
                    continue;
                }

                memberCount++;
                entries.Add(
                    $"event|{typeName}|{metadata.GetString(eventDefinition.Name)}|" +
                    $"{(int)eventDefinition.Attributes}");
            }
        }

        entries.Sort(StringComparer.Ordinal);
        var bytes = Encoding.UTF8.GetBytes(string.Join("\n", entries));
        var fingerprint = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        return (typeCount, memberCount, fingerprint);
    }

    private static bool IsExternallyVisible(MetadataReader metadata, TypeDefinition type)
    {
        var visibility = type.Attributes & TypeAttributes.VisibilityMask;
        if (visibility == TypeAttributes.Public)
        {
            return true;
        }

        if (visibility != TypeAttributes.NestedPublic &&
            visibility != TypeAttributes.NestedFamily &&
            visibility != TypeAttributes.NestedFamORAssem)
        {
            return false;
        }

        var declaringType = type.GetDeclaringType();
        return !declaringType.IsNil &&
            IsExternallyVisible(metadata, metadata.GetTypeDefinition(declaringType));
    }

    private static bool IsExternallyAccessible(FieldAttributes attributes)
    {
        var access = attributes & FieldAttributes.FieldAccessMask;
        return access == FieldAttributes.Public ||
            access == FieldAttributes.Family ||
            access == FieldAttributes.FamORAssem;
    }

    private static bool IsExternallyAccessible(MethodAttributes attributes)
    {
        var access = attributes & MethodAttributes.MemberAccessMask;
        return access == MethodAttributes.Public ||
            access == MethodAttributes.Family ||
            access == MethodAttributes.FamORAssem;
    }

    private static bool IsExternallyAccessible(
        MetadataReader metadata,
        MethodDefinitionHandle handle)
    {
        if (handle.IsNil)
        {
            return false;
        }

        var method = metadata.GetMethodDefinition(handle);
        return IsExternallyAccessible(method.Attributes);
    }

    private static string QualifiedName(MetadataReader metadata, TypeDefinition type)
    {
        var name = metadata.GetString(type.Name);
        var declaringType = type.GetDeclaringType();
        if (!declaringType.IsNil)
        {
            return $"{QualifiedName(metadata, metadata.GetTypeDefinition(declaringType))}+{name}";
        }

        var namespaceName = metadata.GetString(type.Namespace);
        return string.IsNullOrEmpty(namespaceName) ? name : $"{namespaceName}.{name}";
    }

    private static string Signature(MetadataReader metadata, BlobHandle signature)
    {
        return Convert.ToHexString(metadata.GetBlobBytes(signature)).ToLowerInvariant();
    }
}
