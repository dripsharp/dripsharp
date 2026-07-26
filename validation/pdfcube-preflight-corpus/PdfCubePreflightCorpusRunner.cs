using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using PdfCube.IO;
using PdfCube.Preflight;
using PdfCube.Preflight.Exception;
using PdfCube.Preflight.Parser;

internal static class Program
{
    private const string ManifestMagic =
        "DRIPSHARP_PDFCUBE_PREFLIGHT_CORPUS_MANIFEST_V1";
    private const string ResultMagic =
        "DRIPSHARP_PDFCUBE_PREFLIGHT_CORPUS_RESULTS_V1";
    private const string AssemblyMagic =
        "DRIPSHARP_PDFCUBE_PREFLIGHT_LOADED_ASSEMBLIES_V1";

    private static readonly string[] ResultColumns =
    {
        "case-id", "origin", "format", "expected-outcome", "input-sha256",
        "status", "valid", "error-count", "error-codes-base64",
        "warnings-base64", "pages-base64", "details-base64", "source-closed",
        "document-closed", "diagnostic-base64",
    };

    public static async Task<int> Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--child")
        {
            return RunChild(args);
        }
        if (args.Length != 7)
        {
            Console.Error.WriteLine(
                "Usage: runner <manifest> <corpus> <output> <assembly-manifest> " +
                "<packages-root> <loaded-assemblies-output> <case-timeout-ms>");
            return 2;
        }

        try
        {
            string manifestPath = Path.GetFullPath(args[0]);
            string corpusRoot = DirectoryPath(args[1]);
            string outputPath = Path.GetFullPath(args[2]);
            string assemblyManifest = Path.GetFullPath(args[3]);
            string packagesRoot = DirectoryPath(args[4]);
            string loadedAssemblies = Path.GetFullPath(args[5]);
            int timeoutMs = ParsePositive(args[6], "case-timeout-ms");
            CorpusManifest manifest = CorpusManifest.Read(manifestPath);
            VerifyLoadedAssemblies(
                assemblyManifest, packagesRoot, loadedAssemblies);
            await RunParent(
                manifestPath, corpusRoot, outputPath, manifest, timeoutMs);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static int RunChild(string[] args)
    {
        if (args.Length != 5)
        {
            Console.Error.WriteLine(
                "Usage: runner --child <manifest> <corpus> <case-index> <output>");
            return 2;
        }
        try
        {
            CorpusManifest manifest = CorpusManifest.Read(
                Path.GetFullPath(args[1]));
            string corpusRoot = DirectoryPath(args[2]);
            int index = int.Parse(args[3], CultureInfo.InvariantCulture);
            if (index < 0 || index >= manifest.Cases.Count)
            {
                throw new ArgumentOutOfRangeException(nameof(index));
            }
            CorpusResult result = RunCase(corpusRoot, manifest.Cases[index]);
            File.WriteAllText(
                Path.GetFullPath(args[4]),
                result.Render() + "\n",
                new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static async Task RunParent(
        string manifestPath,
        string corpusRoot,
        string outputPath,
        CorpusManifest manifest,
        int timeoutMs)
    {
        string scratch = Path.Combine(
            Environment.CurrentDirectory,
            ".pdfcube-preflight-corpus-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        try
        {
            var results = new List<CorpusResult>();
            for (int index = 0; index < manifest.Cases.Count; index++)
            {
                results.Add(await RunBoundedChild(
                    manifestPath,
                    corpusRoot,
                    manifest.Cases[index],
                    index,
                    scratch,
                    timeoutMs));
            }
            WriteResults(outputPath, results);
        }
        finally
        {
            Directory.Delete(scratch, recursive: true);
        }
    }

    private static async Task<CorpusResult> RunBoundedChild(
        string manifestPath,
        string corpusRoot,
        CorpusCase corpusCase,
        int index,
        string scratch,
        int timeoutMs)
    {
        string resultPath = Path.Combine(
            scratch, index.ToString("D4", CultureInfo.InvariantCulture) + ".tsv");
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException(
                "The package runner executable path is unavailable");
        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = Environment.CurrentDirectory,
            },
        };
        process.StartInfo.ArgumentList.Add("--child");
        process.StartInfo.ArgumentList.Add(manifestPath);
        process.StartInfo.ArgumentList.Add(corpusRoot);
        process.StartInfo.ArgumentList.Add(
            index.ToString(CultureInfo.InvariantCulture));
        process.StartInfo.ArgumentList.Add(resultPath);
        if (!process.Start())
        {
            return CorpusResult.Failure(
                corpusCase, "CRASH", "Child process did not start");
        }
        Task<string> standardOutput = process.StandardOutput.ReadToEndAsync();
        Task<string> standardError = process.StandardError.ReadToEndAsync();
        using var cancellation = new CancellationTokenSource(timeoutMs);
        try
        {
            await process.WaitForExitAsync(cancellation.Token);
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            await process.WaitForExitAsync();
            await Task.WhenAll(standardOutput, standardError);
            return CorpusResult.Failure(
                corpusCase,
                "TIMEOUT",
                "Case exceeded " + timeoutMs.ToString(CultureInfo.InvariantCulture) +
                " ms");
        }
        string childOutput = await standardOutput;
        string childError = await standardError;
        if (process.ExitCode != 0)
        {
            return CorpusResult.Failure(
                corpusCase,
                "CRASH",
                NormalizeDiagnostic(childError + "\n" + childOutput));
        }
        if (!File.Exists(resultPath))
        {
            return CorpusResult.Failure(
                corpusCase, "CRASH", "Child produced no result");
        }
        try
        {
            CorpusResult result = CorpusResult.Parse(
                File.ReadAllText(resultPath, Encoding.UTF8).TrimEnd('\r', '\n'));
            if (!string.Equals(
                    result.CorpusCase.Id, corpusCase.Id, StringComparison.Ordinal))
            {
                throw new InvalidDataException(
                    "Child returned the wrong corpus case");
            }
            return result;
        }
        catch (Exception error)
        {
            return CorpusResult.Failure(
                corpusCase, "CRASH", NormalizeDiagnostic(error.ToString()));
        }
    }

    private static CorpusResult RunCase(
        string corpusRoot,
        CorpusCase corpusCase)
    {
        string input = Path.GetFullPath(
            Path.Combine(corpusRoot, corpusCase.StagedFile));
        if (!input.StartsWith(corpusRoot, StringComparison.Ordinal))
        {
            return CorpusResult.Failure(
                corpusCase, "CRASH", "Corpus path escaped its staging root");
        }
        try
        {
            string actualHash = Sha256(input);
            if (!string.Equals(
                    corpusCase.InputSha256, actualHash, StringComparison.Ordinal))
            {
                return CorpusResult.Failure(
                    corpusCase,
                    "CRASH",
                    "Staged input checksum mismatch: " + actualHash);
            }
        }
        catch (Exception error)
        {
            return CorpusResult.Failure(
                corpusCase, "CRASH", NormalizeDiagnostic(error.ToString()));
        }

        RandomAccessReadBufferedFile? source = null;
        PreflightDocument? document = null;
        ValidationResult? validation = null;
        Exception? failure = null;
        try
        {
            source = new RandomAccessReadBufferedFile(new FileInfo(input));
            var parser = new PreflightParser(source);
            try
            {
                document = (PreflightDocument)parser.Parse(
                    ParseFormat(corpusCase.Format));
                validation = document.Validate();
            }
            catch (SyntaxValidationException syntax)
            {
                validation = syntax.GetResult();
            }
        }
        catch (Exception error)
        {
            failure = error;
        }
        finally
        {
            try
            {
                if (document is not null)
                {
                    document.Dispose();
                }
                else
                {
                    source?.Dispose();
                }
            }
            catch (Exception closeError)
            {
                failure ??= closeError;
            }
        }

        bool sourceClosed = source is null || source.IsClosed();
        string documentClosed = document is null
            ? "na"
            : RenderBoolean(document.GetDocument().IsClosed());
        if (failure is not null)
        {
            return CorpusResult.Failure(
                corpusCase,
                "CRASH",
                NormalizeDiagnostic(failure.GetType().FullName + ": " + failure.Message),
                sourceClosed,
                documentClosed);
        }
        if (validation is null)
        {
            return CorpusResult.Failure(
                corpusCase,
                "CRASH",
                "Validation completed without a result",
                sourceClosed,
                documentClosed);
        }
        if (!sourceClosed || documentClosed == "false")
        {
            return CorpusResult.Failure(
                corpusCase,
                "LEAK",
                "Preflight input or document remained open",
                sourceClosed,
                documentClosed);
        }

        IList<ValidationResult.ValidationError> errors =
            validation.GetErrorsList();
        return new CorpusResult(
            corpusCase,
            "PASS",
            RenderBoolean(validation.IsValid()),
            errors.Count.ToString(CultureInfo.InvariantCulture),
            string.Join(";", errors.Select(error => error.GetErrorCode())),
            string.Join(";", errors.Select(
                error => RenderBoolean(error.IsWarning()))),
            string.Join(";", errors.Select(
                error => error.GetPageNumber()?.ToString(
                    CultureInfo.InvariantCulture) ?? "null")),
            string.Join(";", errors.Select(
                error => Normalize(error.GetDetails()))),
            RenderBoolean(sourceClosed),
            documentClosed,
            "");
    }

    private static Format ParseFormat(string value) => value switch
    {
        "pdf-a1a" => Format.PdfA1a,
        "pdf-a1b" => Format.PdfA1b,
        _ => throw new ArgumentException("Unknown PDF/A format: " + value),
    };

    private static void VerifyLoadedAssemblies(
        string manifestPath,
        string packagesRoot,
        string outputPath)
    {
        if (!Directory.Exists(packagesRoot))
        {
            throw new InvalidOperationException(
                "Fresh isolated package cache is missing: " + packagesRoot);
        }
        Dictionary<string, string> expected = File.ReadAllLines(
                manifestPath, Encoding.UTF8)
            .Where(line => !string.IsNullOrWhiteSpace(line))
            .Select(line => line.Split('\t'))
            .ToDictionary(
                fields => fields[0],
                fields => fields[1],
                StringComparer.Ordinal);
        string baseDirectory = DirectoryPath(AppContext.BaseDirectory);
        var rows = new List<string>();
        foreach (KeyValuePair<string, string> entry in expected.OrderBy(
                     value => value.Key, StringComparer.Ordinal))
        {
            Assembly assembly = Assembly.Load(new AssemblyName(entry.Key));
            string location = Path.GetFullPath(assembly.Location);
            if (!location.StartsWith(baseDirectory, StringComparison.Ordinal))
            {
                throw new InvalidOperationException(
                    "Loaded assembly escaped isolated consumer output: " + location);
            }
            string actualHash = Sha256(location);
            if (!string.Equals(entry.Value, actualHash, StringComparison.Ordinal))
            {
                throw new InvalidOperationException(
                    "Loaded assembly hash differs from package proof: " + entry.Key);
            }
            rows.Add(string.Join(
                '\t', entry.Key, location, entry.Value, actualHash));
        }
        Directory.CreateDirectory(
            Path.GetDirectoryName(outputPath)
                ?? throw new InvalidOperationException(
                    "Loaded-assembly output has no parent"));
        File.WriteAllText(
            outputPath,
            AssemblyMagic + "\n" + string.Join("\n", rows) + "\n",
            new UTF8Encoding(false));
    }

    private static void WriteResults(
        string outputPath,
        IEnumerable<CorpusResult> results)
    {
        Directory.CreateDirectory(
            Path.GetDirectoryName(outputPath)
                ?? throw new InvalidOperationException(
                    "Corpus output has no parent"));
        var output = new StringBuilder();
        output.Append(ResultMagic).Append('\n');
        output.Append("columns\t").AppendJoin('\t', ResultColumns).Append('\n');
        foreach (CorpusResult result in results)
        {
            output.Append(result.Render()).Append('\n');
        }
        File.WriteAllText(outputPath, output.ToString(), new UTF8Encoding(false));
    }

    private static int ParsePositive(string value, string label)
    {
        int parsed = int.Parse(value, CultureInfo.InvariantCulture);
        if (parsed <= 0)
        {
            throw new ArgumentOutOfRangeException(label);
        }
        return parsed;
    }

    private static string DirectoryPath(string value) =>
        Path.GetFullPath(value).TrimEnd(
            Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
        + Path.DirectorySeparatorChar;

    private static string Sha256(string path) =>
        Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path)))
            .ToLowerInvariant();

    private static string Normalize(string? value) =>
        (value ?? "null").Replace("\r\n", "\n", StringComparison.Ordinal)
            .Replace('\r', '\n');

    private static string NormalizeDiagnostic(string value) =>
        Normalize(value).Trim();

    private static string RenderBoolean(bool value) => value ? "true" : "false";

    private static string Encode(string value) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(value));

    private static string Decode(string value) =>
        Encoding.UTF8.GetString(Convert.FromBase64String(value));

    private sealed record CorpusCase(
        string Id,
        string StagedFile,
        string InputSha256,
        string Format,
        string ExpectedOutcome);

    private sealed record CorpusManifest(IReadOnlyList<CorpusCase> Cases)
    {
        public static CorpusManifest Read(string path)
        {
            string[] lines = File.ReadAllLines(path, Encoding.UTF8);
            if (lines.Length < 2 ||
                !string.Equals(lines[0], ManifestMagic, StringComparison.Ordinal))
            {
                throw new InvalidDataException(
                    "Corpus manifest marker is invalid");
            }
            const string expectedColumns =
                "columns\tcase-id\tstaged-file\tinput-sha256\tformat\texpected-outcome";
            if (!string.Equals(
                    lines[1], expectedColumns, StringComparison.Ordinal))
            {
                throw new InvalidDataException(
                    "Corpus manifest columns are invalid");
            }
            var cases = new List<CorpusCase>();
            foreach (string line in lines.Skip(2))
            {
                string[] fields = line.Split('\t');
                if (fields.Length != 6 ||
                    !string.Equals(fields[0], "case", StringComparison.Ordinal))
                {
                    throw new InvalidDataException(
                        "Malformed corpus manifest row");
                }
                cases.Add(new CorpusCase(
                    fields[1], fields[2], fields[3], fields[4], fields[5]));
            }
            if (cases.Count == 0)
            {
                throw new InvalidDataException("Corpus manifest has no cases");
            }
            return new CorpusManifest(cases);
        }
    }

    private sealed record CorpusResult(
        CorpusCase CorpusCase,
        string Status,
        string Valid,
        string ErrorCount,
        string ErrorCodes,
        string Warnings,
        string Pages,
        string Details,
        string SourceClosed,
        string DocumentClosed,
        string Diagnostic)
    {
        public static CorpusResult Failure(
            CorpusCase corpusCase,
            string status,
            string diagnostic,
            bool sourceClosed = true,
            string documentClosed = "na") =>
            new(
                corpusCase,
                status,
                "",
                "",
                "",
                "",
                "",
                "",
                RenderBoolean(sourceClosed),
                documentClosed,
                diagnostic);

        public static CorpusResult Parse(string line)
        {
            string[] fields = line.Split('\t');
            if (fields.Length != 16 ||
                !string.Equals(fields[0], "case", StringComparison.Ordinal) ||
                !string.Equals(
                    fields[2], "package-dotnet", StringComparison.Ordinal))
            {
                throw new InvalidDataException("Malformed child corpus result");
            }
            var corpusCase = new CorpusCase(
                fields[1], "", fields[5], fields[3], fields[4]);
            return new CorpusResult(
                corpusCase,
                fields[6],
                fields[7],
                fields[8],
                Decode(fields[9]),
                Decode(fields[10]),
                Decode(fields[11]),
                Decode(fields[12]),
                fields[13],
                fields[14],
                Decode(fields[15]));
        }

        public string Render() => string.Join(
            '\t',
            "case",
            CorpusCase.Id,
            "package-dotnet",
            CorpusCase.Format,
            CorpusCase.ExpectedOutcome,
            CorpusCase.InputSha256,
            Status,
            Valid,
            ErrorCount,
            Encode(ErrorCodes),
            Encode(Warnings),
            Encode(Pages),
            Encode(Details),
            SourceClosed,
            DocumentClosed,
            Encode(Diagnostic));
    }
}
