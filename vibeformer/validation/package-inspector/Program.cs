using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;

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
        return 0;
    }
}
