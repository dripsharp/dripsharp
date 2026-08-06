using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

internal static class Program
{
    private static readonly Guid SourceLinkKind =
        new("CC110556-A091-4D38-9FEC-25AB9A351A6A");

    private static readonly Guid Sha256DocumentHash =
        new("8829D00F-11B8-4213-878B-770E8597AC16");

    private static int Main(string[] args)
    {
        if (args.Length != 7)
        {
            throw new ArgumentException(
                "package path, symbol package path, assembly entry, PDB entry, " +
                "project root, Source Link document pattern, and Source Link URL are required");
        }

        var projectRoot = Path.GetFullPath(args[4]);
        if (!Directory.Exists(projectRoot))
        {
            throw new DirectoryNotFoundException(
                $"Generated project root is missing: {projectRoot}");
        }

        if (!args[5].EndsWith('*') || !args[6].EndsWith('*'))
        {
            throw new ArgumentException("Source Link patterns must end in one wildcard.");
        }

        var assemblyBytes = ReadExactEntry(args[0], args[2]);
        var pdbBytes = ReadExactEntry(args[1], args[3]);

        using var assemblyStream = new MemoryStream(assemblyBytes, writable: false);
        using var portableExecutable = new PEReader(assemblyStream);
        using var pdbStream = new MemoryStream(pdbBytes, writable: false);
        using var provider = MetadataReaderProvider.FromPortablePdbStream(pdbStream);
        var metadata = provider.GetMetadataReader();

        InspectDllPdbPairing(portableExecutable, metadata, args[3]);
        var sourceLink = ReadSourceLink(metadata);
        InspectSourceLink(sourceLink, args[5], args[6]);
        var documents = InspectDocuments(metadata, projectRoot, args[5]);

        Console.WriteLine(
            $"Portable DLL/PDB pairing inspection passed: {documents.Length} documents");
        Console.WriteLine($"Source Link inspection passed: {args[5]} -> {args[6]}");
        return 0;
    }

    private static byte[] ReadExactEntry(string archivePath, string entryName)
    {
        using var archive = ZipFile.OpenRead(archivePath);
        var matches = archive.Entries
            .Where(entry => string.Equals(entry.FullName, entryName, StringComparison.Ordinal))
            .ToArray();
        if (matches.Length != 1)
        {
            throw new InvalidDataException(
                $"Archive must contain exactly one {entryName} entry; found {matches.Length}.");
        }

        using var input = matches[0].Open();
        using var output = new MemoryStream();
        input.CopyTo(output);
        return output.ToArray();
    }

    private static void InspectDllPdbPairing(
        PEReader portableExecutable,
        MetadataReader pdb,
        string pdbEntry)
    {
        var codeViewEntries = portableExecutable.ReadDebugDirectory()
            .Where(entry => entry.Type == DebugDirectoryEntryType.CodeView)
            .ToArray();
        if (codeViewEntries.Length != 1)
        {
            throw new InvalidDataException(
                $"Assembly must contain exactly one CodeView record; found {codeViewEntries.Length}.");
        }

        var entry = codeViewEntries[0];
        var codeView = portableExecutable.ReadCodeViewDebugDirectoryData(entry);
        var pdbHeader = pdb.DebugMetadataHeader
            ?? throw new InvalidDataException("Portable PDB debug metadata header is missing.");
        var pdbId = new BlobContentId(pdbHeader.Id);
        if (codeView.Guid != pdbId.Guid || entry.Stamp != pdbId.Stamp || codeView.Age != 1)
        {
            throw new InvalidDataException(
                "Assembly CodeView identity does not match the portable PDB. " +
                $"DLL {codeView.Guid}/{entry.Stamp}/{codeView.Age}; " +
                $"PDB {pdbId.Guid}/{pdbId.Stamp}/1.");
        }

        var expectedName = Path.GetFileName(pdbEntry);
        if (!string.Equals(Path.GetFileName(codeView.Path), expectedName,
                           StringComparison.Ordinal))
        {
            throw new InvalidDataException(
                $"Assembly CodeView path differs. Expected {expectedName}, actual {codeView.Path}.");
        }
    }

    private static string ReadSourceLink(MetadataReader metadata)
    {
        var module = MetadataTokens.EntityHandle(0x00000001);
        var records = metadata.GetCustomDebugInformation(module)
            .Select(handle => metadata.GetCustomDebugInformation(handle))
            .Where(record => metadata.GetGuid(record.Kind) == SourceLinkKind)
            .ToArray();
        if (records.Length != 1)
        {
            throw new InvalidDataException(
                $"Portable PDB must contain exactly one Source Link record; found {records.Length}.");
        }

        return Encoding.UTF8.GetString(metadata.GetBlobBytes(records[0].Value));
    }

    private static void InspectSourceLink(
        string sourceLink,
        string expectedDocumentPattern,
        string expectedUrl)
    {
        using var document = JsonDocument.Parse(sourceLink);
        var rootProperties = document.RootElement.EnumerateObject().ToArray();
        if (rootProperties.Length != 1 || rootProperties[0].Name != "documents" ||
            rootProperties[0].Value.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidDataException(
                "Source Link payload must contain exactly one documents object.");
        }

        var mappings = rootProperties[0].Value.EnumerateObject().ToArray();
        if (mappings.Length != 1 ||
            !string.Equals(mappings[0].Name, expectedDocumentPattern,
                           StringComparison.Ordinal) ||
            mappings[0].Value.ValueKind != JsonValueKind.String ||
            !string.Equals(mappings[0].Value.GetString(), expectedUrl,
                           StringComparison.Ordinal))
        {
            throw new InvalidDataException(
                "Source Link mapping differs. " +
                $"Expected {expectedDocumentPattern} -> {expectedUrl}; actual {sourceLink}.");
        }
    }

    private static string[] InspectDocuments(
        MetadataReader metadata,
        string projectRoot,
        string documentPattern)
    {
        var prefix = documentPattern[..^1];
        var documents = metadata.Documents
            .Select(handle => metadata.GetDocument(handle))
            .Select(document => new
            {
                Name = metadata.GetString(document.Name),
                HashAlgorithm = metadata.GetGuid(document.HashAlgorithm),
                Hash = metadata.GetBlobBytes(document.Hash)
            })
            .OrderBy(document => document.Name, StringComparer.Ordinal)
            .ToArray();

        if (documents.Length == 0 ||
            documents.Select(document => document.Name)
                .Distinct(StringComparer.Ordinal).Count() != documents.Length)
        {
            throw new InvalidDataException(
                "Portable PDB document inventory must be nonempty and unique.");
        }

        var rootPrefix = projectRoot.EndsWith(Path.DirectorySeparatorChar)
            ? projectRoot
            : projectRoot + Path.DirectorySeparatorChar;
        foreach (var document in documents)
        {
            if (!document.Name.StartsWith(prefix, StringComparison.Ordinal) ||
                !document.Name.EndsWith(".cs", StringComparison.Ordinal) ||
                document.Name.Contains("source-map", StringComparison.OrdinalIgnoreCase) ||
                document.Name.Contains("translator", StringComparison.OrdinalIgnoreCase) ||
                document.HashAlgorithm != Sha256DocumentHash)
            {
                throw new InvalidDataException(
                    $"Portable PDB exposes a non-product source document: {document.Name}.");
            }

            var relative = document.Name[prefix.Length..];
            if (relative.Length == 0 || relative.Split('/').Contains("..", StringComparer.Ordinal))
            {
                throw new InvalidDataException(
                    $"Portable PDB contains an unsafe source document: {document.Name}.");
            }

            var local = Path.GetFullPath(
                Path.Combine(projectRoot, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!local.StartsWith(rootPrefix, StringComparison.Ordinal) || !File.Exists(local))
            {
                throw new InvalidDataException(
                    $"Portable PDB document is outside generated product source: {document.Name}.");
            }

            var actualHash = SHA256.HashData(File.ReadAllBytes(local));
            if (!actualHash.AsSpan().SequenceEqual(document.Hash))
            {
                throw new InvalidDataException(
                    $"Portable PDB checksum differs from generated source: {document.Name}.");
            }
        }

        return documents.Select(document => document.Name).ToArray();
    }
}
