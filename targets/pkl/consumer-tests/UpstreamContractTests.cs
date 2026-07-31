using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Xunit;

[assembly: CollectionBehavior(DisableTestParallelization = true)]

namespace DripSharp.Brine.Tests;

public sealed class UpstreamContractTests
{
    const string PinnedRevision = "f7cac257ade5775c1dfc255f4fda2eacc296e9d0";
    const string ApprovedLanguageExclusion = "outside-epic-approved-exclusion";
    const string CoreExecutionOwner = "complete-pkl-core-runner";

    static readonly string Root = FindProductRoot();
    static readonly Lazy<IReadOnlyDictionary<string, LanguageResult>> LanguageResults =
        new(RunLanguageCorpus);
    static readonly Lazy<IReadOnlyDictionary<string, CoreResult>> CoreResults =
        new(RunCoreCorpus);
    static readonly IReadOnlyDictionary<string, LanguageCase> LanguageContract =
        ReadLanguageContract();
    static readonly IReadOnlyDictionary<string, CoreCase> CoreContract =
        ReadCoreContract();
    static readonly IReadOnlyDictionary<string, ExpectedLanguageResult> LanguageExpected =
        ReadLanguageExpected();

    public static IEnumerable<object[]> LanguageCases() =>
        LanguageContract.Values
            .Where(row => row.ProductScope != ApprovedLanguageExclusion)
            .Select(row => new object[] { row.Id });

    public static IEnumerable<object[]> CoreCases() =>
        CoreContract.Values
            .Where(row => row.ExecutionOwner == CoreExecutionOwner)
            .Where(row => row.PlatformConditions != "os=windows" ||
                          OperatingSystem.IsWindows())
            .Select(row => new object[] { row.Id });

    [Theory]
    [MemberData(nameof(LanguageCases))]
    public void LanguageSnippetMatchesPinnedUpstreamContract(string caseId)
    {
        LanguageCase contract = LanguageContract[caseId];
        ExpectedLanguageResult expected = LanguageExpected[caseId];
        LanguageResult actual = LanguageResults.Value[caseId];
        Assert.True(
            LanguageMatches(contract, expected, actual),
            $"Upstream case {caseId} did not match. Expected {expected.Status} " +
            $"({Sha256(expected.PayloadBase64)}), observed {actual.Status} " +
            $"({Sha256(actual.PayloadBase64)}). Diagnostic: {Decode(actual.DiagnosticBase64)}");
    }

    [Theory]
    [MemberData(nameof(CoreCases))]
    public void PklCoreCasePassesPackageAdaptation(string caseId)
    {
        CoreResult actual = CoreResults.Value[caseId];
        Assert.Equal(PinnedRevision, actual.Revision);
        Assert.Equal("PASS", actual.Status);
    }

    [Fact]
    public void GeneratedCoverageAndProvenanceAreExact()
    {
        Assert.Equal(940, LanguageContract.Count);
        Assert.Equal(909, LanguageCases().Count());
        Assert.Equal(605, CoreContract.Count);
        Assert.Equal(524, CoreContract.Values.Count(row =>
            row.ExecutionOwner == CoreExecutionOwner));
        Assert.Equal(OperatingSystem.IsWindows() ? 524 : 523, CoreCases().Count());
        VerifyInventory();
        VerifyProvenance();
    }

    [Fact]
    public void DeliberateExpectationPerturbationIsDetected()
    {
        string caseId = LanguageCases().Select(row => (string)row[0]).First();
        LanguageCase contract = LanguageContract[caseId];
        LanguageResult actual = LanguageResults.Value[caseId];
        var perturbed = new ExpectedLanguageResult(
            actual.Status, Convert.ToBase64String("deliberately perturbed"u8));
        Assert.False(LanguageMatches(contract, perturbed, actual));
    }

    static bool LanguageMatches(
        LanguageCase contract,
        ExpectedLanguageResult expected,
        LanguageResult actual)
    {
        if (actual.Status == expected.Status &&
            actual.PayloadBase64 == expected.PayloadBase64)
            return true;
        if (contract.ProductScope != "in-scope-mixed-excluded-surface" ||
            !contract.ExecutionRequirements.Split(';').Contains(
                "messagepack-debug-decoding", StringComparer.Ordinal) ||
            actual.Status != "ERROR" ||
            actual.LoggerBase64.Length != 0 ||
            actual.DiagnosticBase64.Length != 0)
            return false;
        string payload = Decode(actual.PayloadBase64);
        const string boundary =
            "MessagePack is excluded from the DripSharp product target.";
        return Count(payload, boundary) == 4 &&
               payload.StartsWith(
                   "DripSharp.Brine.PklBugException: An unexpected error has occurred.",
                   StringComparison.Ordinal);
    }

    static IReadOnlyDictionary<string, LanguageCase> ReadLanguageContract()
    {
        string path = ProductPath("tests", "Contracts", "LanguageSnippetContract.tsv");
        string? revision = null;
        string[]? columns = null;
        var rows = new Dictionary<string, LanguageCase>(StringComparer.Ordinal);
        foreach (string line in File.ReadLines(path, Encoding.UTF8))
        {
            string[] fields = line.Split('\t');
            if (fields[0] == "meta" && fields.Length == 3 &&
                fields[1] == "source-revision")
                revision = fields[2];
            else if (fields[0] == "columns")
                columns = fields.Skip(1).ToArray();
            else if (fields[0] == "case")
            {
                Dictionary<string, string> row = Row(columns, fields, path);
                var value = new LanguageCase(
                    Required(row, "case-id"),
                    Required(row, "product-scope"),
                    Required(row, "execution-requirements"));
                AddUnique(rows, value.Id, value, path);
            }
        }
        Require(revision == PinnedRevision, "Language contract revision drifted.");
        Require(rows.Count == 940, "Language contract row count drifted.");
        Require(rows.Values.Count(row =>
            row.ProductScope != ApprovedLanguageExclusion) == 909,
            "Language in-scope row count drifted.");
        return rows;
    }

    static IReadOnlyDictionary<string, CoreCase> ReadCoreContract()
    {
        string path = ProductPath("tests", "Contracts", "PklCoreTestContract.tsv");
        string? revision = null;
        string[]? columns = null;
        var rows = new Dictionary<string, CoreCase>(StringComparer.Ordinal);
        foreach (string line in File.ReadLines(path, Encoding.UTF8))
        {
            string[] fields = line.Split('\t');
            if (fields[0] == "meta" && fields.Length == 3 &&
                fields[1] == "source-revision")
                revision = fields[2];
            else if (fields[0] == "case-columns")
                columns = fields.Skip(1).ToArray();
            else if (fields[0] == "case")
            {
                Dictionary<string, string> row = Row(columns, fields, path);
                var value = new CoreCase(
                    Required(row, "case-id"),
                    Required(row, "execution-owner"),
                    Required(row, "platform-conditions"));
                AddUnique(rows, value.Id, value, path);
            }
        }
        Require(revision == PinnedRevision, "Pkl.Core contract revision drifted.");
        Require(rows.Count == 605, "Pkl.Core contract row count drifted.");
        Require(rows.Values.Count(row => row.ExecutionOwner == CoreExecutionOwner) == 524,
            "Pkl.Core product matrix drifted.");
        return rows;
    }

    static IReadOnlyDictionary<string, ExpectedLanguageResult> ReadLanguageExpected()
    {
        string path = ProductPath("tests", "Contracts", "LanguageSnippetExpected.tsv");
        var rows = new Dictionary<string, ExpectedLanguageResult>(StringComparer.Ordinal);
        foreach (string line in File.ReadLines(path, Encoding.UTF8))
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == 3, $"Malformed language expectation in {path}.");
            _ = Convert.FromBase64String(fields[2]);
            AddUnique(rows, fields[0], new ExpectedLanguageResult(fields[1], fields[2]), path);
        }
        Require(rows.Keys.SequenceEqual(LanguageContract.Keys, StringComparer.Ordinal),
            "Language expectations do not cover the contract in order.");
        return rows;
    }

    static IReadOnlyDictionary<string, LanguageResult> RunLanguageCorpus()
    {
        string runner = Runner("DripSharp.Brine.LanguageSnippetRunner");
        string output = Temporary("language-results.tsv");
        string manifest = ProductPath("tests", "Contracts", "LanguageSnippetContract.tsv");
        string fixtureRoot = PrepareLanguageFixtures();
        string snippets = Path.Combine(
            fixtureRoot, "pkl-core", "src", "test", "files", "LanguageSnippetTests");
        string packageBuild = Path.Combine(fixtureRoot, "pkl-commons-test", "build");
        string assemblies = WriteAssemblyManifest(Path.GetDirectoryName(runner)!);
        Run(
            runner, manifest, output, snippets, packageBuild, assemblies,
            "15000", "30000", WorkerCount());
        string[] lines = File.ReadAllLines(output, Encoding.UTF8);
        Require(lines.Length >= 2 &&
                lines[0] == "DRIPSHARP_LANGUAGE_SNIPPET_PACKAGE_RESULTS_V1",
            "Language runner result schema drifted.");
        var rows = new Dictionary<string, LanguageResult>(StringComparer.Ordinal);
        foreach (string line in lines.Skip(2))
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == 6 && fields[0] == "case",
                "Malformed language result row.");
            AddUnique(
                rows, fields[1],
                new LanguageResult(fields[2], fields[3], fields[4], fields[5]), output);
        }
        Require(rows.Keys.SequenceEqual(LanguageContract.Keys, StringComparer.Ordinal),
            "Language results do not cover the contract in order.");
        return rows;
    }

    static IReadOnlyDictionary<string, CoreResult> RunCoreCorpus()
    {
        string runner = Runner("DripSharp.Brine.CoreTestRunner");
        string output = Temporary("core-results.tsv");
        string manifest = ProductPath("tests", "Contracts", "PklCoreTestContract.tsv");
        string assemblies = WriteAssemblyManifest(Path.GetDirectoryName(runner)!);
        string packages = Temporary("packages");
        Directory.CreateDirectory(packages);
        string loaded = Temporary("loaded-assemblies.tsv");
        Run(runner, manifest, output, assemblies, packages, loaded, "60000", WorkerCount());
        string[] lines = File.ReadAllLines(output, Encoding.UTF8);
        Require(lines.Length >= 2 &&
                lines[0] == "DRIPSHARP_PKL_CORE_CORPUS_RESULTS_V1",
            "Pkl.Core runner result schema drifted.");
        var rows = new Dictionary<string, CoreResult>(StringComparer.Ordinal);
        foreach (string line in lines.Skip(2))
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == 14 && fields[0] == "case",
                "Malformed Pkl.Core result row.");
            AddUnique(rows, fields[1], new CoreResult(fields[3], fields[11], fields[13]), output);
        }
        Require(rows.Keys.SequenceEqual(CoreContract.Keys, StringComparer.Ordinal),
            "Pkl.Core results do not cover the contract in order.");
        return rows;
    }

    static void Run(string runner, params string[] arguments)
    {
        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = "dotnet",
            WorkingDirectory = Root,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        process.StartInfo.ArgumentList.Add(runner);
        foreach (string argument in arguments) process.StartInfo.ArgumentList.Add(argument);
        process.Start();
        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
        Task<string> stderr = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit((int)TimeSpan.FromMinutes(60).TotalMilliseconds))
        {
            process.Kill(entireProcessTree: true);
            throw new TimeoutException($"Generated runner timed out: {runner}");
        }
        Task.WaitAll(stdout, stderr);
        Require(
            process.ExitCode == 0,
            $"Generated runner failed ({process.ExitCode}).\n{stdout.Result}\n{stderr.Result}");
    }

    static string Runner(string assembly)
    {
        string path = ProductPath(
            "tests", assembly, "bin", "Release", "net10.0", assembly + ".dll");
        Require(File.Exists(path), $"Generated runner is missing: {path}");
        return path;
    }

    static string WriteAssemblyManifest(string runnerDirectory)
    {
        string output = Temporary("assemblies.tsv");
        string[] names = { "DripSharp.Brine", "DripSharp.Brine.Parser" };
        File.WriteAllLines(
            output,
            names.Select(name => name + "\t" +
                Sha256File(Path.Combine(runnerDirectory, name + ".dll"))),
            new UTF8Encoding(false));
        return output;
    }

    static string PrepareLanguageFixtures()
    {
        string source = ProductPath("tests", "Fixtures", "pkl");
        string destination = Path.Combine(
            AppContext.BaseDirectory,
            ".language-fixtures-" + Guid.NewGuid().ToString("N"),
            "pkl");
        CopyDirectory(source, destination);
        string manifest = Path.Combine(destination, "SYMLINKS.tsv");
        string[] lines = File.ReadAllLines(manifest, Encoding.UTF8);
        Require(lines.Length == 2 &&
                lines[0] == "path\ttarget\tsource-path\tsource-sha256",
            "Fixture symlink manifest drifted.");
        string[] fields = lines[1].Split('\t');
        Require(fields.Length == 4 && fields[3] == Sha256(fields[1]),
            "Fixture symlink provenance drifted.");
        string link = Path.GetFullPath(Path.Combine(destination, fields[0]));
        string normalizedRoot = Path.GetFullPath(destination)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        Require(link.StartsWith(normalizedRoot, StringComparison.Ordinal) &&
                !Path.IsPathRooted(fields[1]) &&
                !fields[1].Split('/', '\\').Contains("..", StringComparer.Ordinal),
            "Fixture symlink escaped its temporary root.");
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        Directory.CreateSymbolicLink(link, fields[1]);
        return destination;
    }

    static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (string directory in Directory.EnumerateDirectories(
                     source, "*", SearchOption.AllDirectories))
            Directory.CreateDirectory(Path.Combine(
                destination, Path.GetRelativePath(source, directory)));
        foreach (string file in Directory.EnumerateFiles(
                     source, "*", SearchOption.AllDirectories))
        {
            string output = Path.Combine(destination, Path.GetRelativePath(source, file));
            Directory.CreateDirectory(Path.GetDirectoryName(output)!);
            File.Copy(file, output, overwrite: true);
        }
    }

    static void VerifyInventory()
    {
        string tests = ProductPath("tests");
        string inventory = Path.Combine(tests, "SHA256SUMS");
        foreach (string line in File.ReadLines(inventory, Encoding.UTF8))
        {
            int split = line.IndexOf("  ", StringComparison.Ordinal);
            Require(split == 64, "Malformed SHA256SUMS row.");
            string relative = line[(split + 2)..];
            Assert.Equal(line[..split], Sha256File(Path.Combine(tests, relative)));
        }
    }

    static void VerifyProvenance()
    {
        string tests = ProductPath("tests");
        string ledger = Path.Combine(tests, "TEST-PROVENANCE.tsv");
        string[] columns = File.ReadLines(ledger, Encoding.UTF8).First().Split('\t');
        Require(columns.SequenceEqual(new[]
        {
            "path", "class", "upstream-revision", "source-path", "source-sha256",
            "transformation", "emitted-sha256", "license", "notice",
            "durable-source", "authored-lines", "review-evidence", "line-budget"
        }), "Test provenance schema drifted.");
        var classified = new HashSet<string>(StringComparer.Ordinal);
        foreach (string line in File.ReadLines(ledger, Encoding.UTF8).Skip(1))
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == columns.Length, "Malformed test provenance row.");
            Dictionary<string, string> row = columns.Select((name, index) => (name, fields[index]))
                .ToDictionary(item => item.name, item => item.Item2, StringComparer.Ordinal);
            Require(classified.Add(row["path"]), "Duplicate test provenance path.");
            Require(row["class"] is "mechanically-upstream-derived" or
                    "vendored-third-party" or "dripsharp-authored-test-infrastructure" or
                    "deterministic-generated-wrapper",
                "Unknown test provenance class.");
            if (row["class"] is "mechanically-upstream-derived" or "vendored-third-party")
                Require(row["upstream-revision"] == PinnedRevision &&
                        row["durable-source"] == "-" && row["authored-lines"] == "-" &&
                        row["review-evidence"] == "-" && row["line-budget"] == "-",
                    "Upstream test material is not revision-pinned.");
            if (row["class"] == "dripsharp-authored-test-infrastructure")
                Require(row["durable-source"] != "-" && row["authored-lines"] != "-" &&
                        row["review-evidence"] != "-" && row["line-budget"] != "-",
                    "Authored test infrastructure is falsely mechanical or unbudgeted.");
            Assert.Equal(
                row["emitted-sha256"], Sha256File(Path.Combine(tests, row["path"])));
        }
        string[] expected = File.ReadLines(Path.Combine(tests, "SHA256SUMS"), Encoding.UTF8)
            .Select(line => line[(line.IndexOf("  ", StringComparison.Ordinal) + 2)..])
            .Where(path => path is not "TEST-PROVENANCE.tsv" and not "SHA256SUMS")
            .OrderBy(path => path, StringComparer.Ordinal)
            .ToArray();
        Assert.Equal(expected, classified.OrderBy(path => path, StringComparer.Ordinal));
    }

    static Dictionary<string, string> Row(string[]? columns, string[] fields, string path)
    {
        Require(columns is not null && fields.Length == columns.Length + 1,
            $"Malformed contract row in {path}.");
        string[] actualColumns = columns ??
            throw new InvalidDataException($"Contract columns are missing in {path}.");
        return actualColumns.Select((name, index) => (name, fields[index + 1]))
            .ToDictionary(item => item.name, item => item.Item2, StringComparer.Ordinal);
    }

    static string Required(IReadOnlyDictionary<string, string> row, string key) =>
        row.TryGetValue(key, out string? value) && value.Length > 0
            ? value
            : throw new InvalidDataException($"Contract row omits {key}.");

    static void AddUnique<T>(
        IDictionary<string, T> rows, string id, T row, string source)
    {
        if (!rows.TryAdd(id, row))
            throw new InvalidDataException($"Duplicate case {id} in {source}.");
    }

    static string FindProductRoot()
    {
        for (DirectoryInfo? current = new(AppContext.BaseDirectory);
             current is not null;
             current = current.Parent)
            if (File.Exists(Path.Combine(current.FullName, "tests", "SHA256SUMS")) &&
                Directory.Exists(Path.Combine(current.FullName, "src", "DripSharp.Brine")))
                return current.FullName;
        throw new DirectoryNotFoundException("Could not locate the generated Brine root.");
    }

    static string ProductPath(params string[] parts) =>
        parts.Aggregate(Root, Path.Combine);

    static string Temporary(string name)
    {
        string root = Path.Combine(
            Path.GetTempPath(), "brine-upstream-xunit",
            Environment.ProcessId.ToString(CultureInfo.InvariantCulture));
        Directory.CreateDirectory(root);
        return Path.Combine(root, name);
    }

    static string WorkerCount() =>
        Environment.GetEnvironmentVariable("DRIPSHARP_WORKERS") is string value &&
        int.TryParse(value, out int workers) && workers > 0
            ? workers.ToString(CultureInfo.InvariantCulture)
            : "22";

    static int Count(string value, string needle)
    {
        int count = 0;
        for (int start = 0;
             (start = value.IndexOf(needle, start, StringComparison.Ordinal)) >= 0;
             start += needle.Length)
            count++;
        return count;
    }

    static string Decode(string value) =>
        Encoding.UTF8.GetString(Convert.FromBase64String(value));

    static string Sha256(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();

    static string Sha256File(string path)
    {
        using FileStream stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidDataException(message);
    }

    sealed record LanguageCase(string Id, string ProductScope, string ExecutionRequirements);
    sealed record CoreCase(string Id, string ExecutionOwner, string PlatformConditions);
    sealed record ExpectedLanguageResult(string Status, string PayloadBase64);
    sealed record LanguageResult(
        string Status, string PayloadBase64, string LoggerBase64, string DiagnosticBase64);
    sealed record CoreResult(string Revision, string Status, string DiagnosticBase64);
}
