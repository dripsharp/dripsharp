using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using Pkl.Core;

/**
 * Package-reference-only executor for the exhaustive schema, generator, and
 * binding inventory. It receives staged Pkl fixtures, never repository source
 * or JVM oracle output, generates disposable C#, compiles it in child package
 * consumers with nullable warnings as errors, and emits one result per row.
 */
static class SchemaBindingPackageRunner
{
    const string InventoryMagic = "VIBEFORMER_SCHEMA_BINDING_CONTRACT_V1";
    const string FixtureMagic = "VIBEFORMER_SCHEMA_BINDING_FIXTURE_MATRIX_V1";
    const string ResultMagic = "VIBEFORMER_SCHEMA_BINDING_RESULTS_V1";
    const string AssemblyMagic = "VIBEFORMER_SCHEMA_BINDING_LOADED_ASSEMBLIES_V1";

    static readonly UTF8Encoding Utf8 = new(false);
    static readonly string[] InventoryColumns =
    {
        "row-id", "artifact-kind", "upstream-module", "upstream-case-identity",
        "source-path", "source-sha256", "source-line", "dependencies",
        "behavior-family", "product-classification", "scope-basis",
        "observation-kinds", "oracle-kind", "detail"
    };
    static readonly string[] ResultColumns =
    {
        "row-id", "origin", "upstream-revision", "artifact-kind",
        "upstream-module", "upstream-case-identity", "source-path", "source-sha256",
        "source-line", "behavior-family", "product-classification",
        "observation-kinds", "oracle-kind", "status", "observation-base64",
        "diagnostic-base64"
    };
    static readonly HashSet<string> Classifications = new(StringComparer.Ordinal)
    {
        "in-scope-executable-dotnet-behavior",
        "language-specific-evidence-requiring-idiomatic-csharp-analogue",
        "user-approved-excluded-surface",
        "non-shipping-test-infrastructure"
    };

    sealed record Row(IReadOnlyDictionary<string, string> Fields)
    {
        public string this[string name] => Fields[name];
    }

    sealed record Fixture(string RowId, string RelativePath, string SourceSha256);

    sealed record FixtureOutcome(
        string RowId,
        string Status,
        bool Generated,
        bool Compiled,
        bool Constructed,
        bool Bound,
        bool Loaded,
        bool EqualValues,
        bool Diagnostics,
        bool Lifecycle,
        bool HasDocumentation,
        bool HasDeprecation,
        string Observation,
        string Diagnostic);

    sealed record ProcessOutcome(bool Success, bool Timeout, int ExitCode, string Output);

    public static int Main(string[] args)
    {
        if (args.Length != 12)
            throw new ArgumentException(
                "inventory, fixture manifest/root, generated root, result, assembly manifest, " +
                "package cache, loaded evidence, timeout, NuGet.Config, package id/version, and framework are required");

        string inventoryFile = Path.GetFullPath(args[0]);
        string fixtureManifest = Path.GetFullPath(args[1]);
        string fixtureRoot = Path.GetFullPath(args[2]);
        string generatedRoot = Path.GetFullPath(args[3]);
        string resultFile = Path.GetFullPath(args[4]);
        string assemblyManifest = Path.GetFullPath(args[5]);
        string packageCache = Path.GetFullPath(args[6]);
        string loadedEvidence = Path.GetFullPath(args[7]);
        int timeoutMs = int.Parse(args[8], CultureInfo.InvariantCulture);
        string nugetConfig = Path.GetFullPath(args[9]);
        string packageId = args[10];
        string[] packageAndFramework = args[11].Split('|');
        if (packageAndFramework.Length != 2)
            throw new ArgumentException("The final argument must be package-version|target-framework");
        string packageVersion = packageAndFramework[0];
        string targetFramework = packageAndFramework[1];

        if (timeoutMs <= 0) throw new ArgumentOutOfRangeException(nameof(timeoutMs));
        Directory.CreateDirectory(generatedRoot);
        var inventory = ReadInventory(inventoryFile, out string revision);
        var fixtures = ReadFixtures(fixtureManifest);
        ValidateFixtureCoverage(inventory, fixtures);
        var packedAssemblies = ReadAssemblyManifest(assemblyManifest);
        VerifyPackageCache(packedAssemblies, packageCache);

        var fixtureOutcomes = ExecuteFixtureMatrix(
            fixtures, fixtureRoot, generatedRoot, nugetConfig, packageCache,
            packageId, packageVersion, targetFramework, timeoutMs);
        var rows = inventory.Select(row => ExecuteRow(row, revision, fixtureOutcomes)).ToArray();
        WriteResults(resultFile, revision, rows);
        WriteLoadedAssemblies(loadedEvidence, packedAssemblies);
        Console.WriteLine(
            $"Exhaustive package-only schema/binding runner produced {rows.Length} normalized rows and " +
            $"{fixtureOutcomes.Count} fixture builds.");
        return 0;
    }

    static IReadOnlyList<Row> ReadInventory(string file, out string revision)
    {
        string[] lines = File.ReadAllLines(file, Utf8);
        string[] marker = lines.FirstOrDefault()?.Split('\t') ?? Array.Empty<string>();
        if (marker.Length != 2 || marker[0] != InventoryMagic || marker[1].Length != 40)
            throw new InvalidDataException("Inventory marker or pinned revision is invalid.");
        revision = marker[1];
        RequireColumns(lines.ElementAtOrDefault(1), InventoryColumns, "inventory");
        var result = new List<Row>();
        foreach (string line in lines.Skip(2))
        {
            string[] fields = line.Split('\t');
            if (fields.Length != InventoryColumns.Length)
                throw new InvalidDataException("Inventory row has the wrong field count.");
            var values = InventoryColumns.Zip(fields).ToDictionary(pair => pair.First, pair => pair.Second,
                StringComparer.Ordinal);
            if (!Classifications.Contains(values["product-classification"]))
                throw new InvalidDataException("Inventory row has an unexpected classification: " + values["row-id"]);
            if (values.Values.Any(string.IsNullOrWhiteSpace))
                throw new InvalidDataException("Inventory row has a blank field: " + values["row-id"]);
            result.Add(new Row(values));
        }
        string[] duplicates = result.GroupBy(row => row["row-id"], StringComparer.Ordinal)
            .Where(group => group.Count() != 1).Select(group => group.Key).ToArray();
        if (duplicates.Length != 0)
            throw new InvalidDataException("Inventory has duplicate rows: " + string.Join(",", duplicates));
        if (!result.Select(row => row["row-id"]).SequenceEqual(
                result.Select(row => row["row-id"]).OrderBy(value => value, StringComparer.Ordinal)))
            throw new InvalidDataException("Inventory rows are not in canonical order.");
        return result;
    }

    static IReadOnlyList<Fixture> ReadFixtures(string file)
    {
        string[] lines = File.ReadAllLines(file, Utf8);
        if (lines.ElementAtOrDefault(0) != FixtureMagic ||
            lines.ElementAtOrDefault(1) != "row-id\trelative-path\tsource-sha256")
            throw new InvalidDataException("Fixture matrix marker or columns are invalid.");
        var fixtures = lines.Skip(2).Select(line =>
        {
            string[] fields = line.Split('\t');
            if (fields.Length != 3 || fields.Any(string.IsNullOrWhiteSpace))
                throw new InvalidDataException("Fixture matrix row is malformed.");
            return new Fixture(fields[0], fields[1], fields[2]);
        }).ToArray();
        if (fixtures.GroupBy(item => item.RowId, StringComparer.Ordinal).Any(group => group.Count() != 1))
            throw new InvalidDataException("Fixture matrix contains duplicate row identities.");
        return fixtures;
    }

    static void ValidateFixtureCoverage(IReadOnlyList<Row> rows, IReadOnlyList<Fixture> fixtures)
    {
        string[] expected = rows.Where(row => row["artifact-kind"] == "fixture")
            .Select(row => row["row-id"]).ToArray();
        string[] actual = fixtures.Select(item => item.RowId).ToArray();
        if (!expected.SequenceEqual(actual))
            throw new InvalidDataException("Fixture matrix does not cover inventory fixture rows in order.");
    }

    static IReadOnlyList<(string Name, string Sha256)> ReadAssemblyManifest(string file)
    {
        var result = File.ReadAllLines(file, Utf8).Select(line =>
        {
            string[] fields = line.Split('\t');
            if (fields.Length != 2 || fields[0].Length == 0 || fields[1].Length != 64)
                throw new InvalidDataException("Packed assembly manifest row is malformed.");
            return (Name: fields[0], Sha256: fields[1]);
        }).ToArray();
        if (result.Length != 2 || result.GroupBy(item => item.Name, StringComparer.OrdinalIgnoreCase)
            .Any(group => group.Count() != 1))
            throw new InvalidDataException("Packed assembly closure must contain exactly Pkl.Parser and Pkl.Core.");
        if (!result.Select(item => item.Name).OrderBy(value => value, StringComparer.Ordinal)
            .SequenceEqual(new[] { "Pkl.Core", "Pkl.Parser" }))
            throw new InvalidDataException("Packed assembly closure identities changed.");
        return result;
    }

    static void VerifyPackageCache(IReadOnlyList<(string Name, string Sha256)> assemblies, string packageCache)
    {
        string cacheRoot = Path.GetFullPath(packageCache) + Path.DirectorySeparatorChar;
        foreach (var assembly in assemblies)
        {
            string[] matches = Directory.EnumerateFiles(packageCache, assembly.Name + ".dll",
                    SearchOption.AllDirectories)
                .Where(path => path.Contains(Path.DirectorySeparatorChar + "lib" + Path.DirectorySeparatorChar,
                    StringComparison.OrdinalIgnoreCase))
                .Where(path => Sha256(path) == assembly.Sha256)
                .Select(Path.GetFullPath).Distinct(StringComparer.Ordinal).ToArray();
            if (matches.Length != 1 || !matches[0].StartsWith(cacheRoot, StringComparison.Ordinal))
                throw new InvalidDataException(
                    $"Fresh package cache does not contain exactly one packed {assembly.Name} assembly.");
        }
    }

    static IReadOnlyDictionary<string, FixtureOutcome> ExecuteFixtureMatrix(
        IReadOnlyList<Fixture> fixtures,
        string fixtureRoot,
        string generatedRoot,
        string nugetConfig,
        string packageCache,
        string packageId,
        string packageVersion,
        string targetFramework,
        int timeoutMs)
    {
        var outcomes = new Dictionary<string, FixtureOutcome>(StringComparer.Ordinal);
        for (int index = 0; index < fixtures.Count; index++)
        {
            Fixture fixture = fixtures[index];
            string source = ConfinedPath(fixtureRoot, fixture.RelativePath);
            if (!File.Exists(source) || Sha256(source) != fixture.SourceSha256)
                throw new InvalidDataException("Staged fixture hash differs from inventory: " + fixture.RowId);
            string output = Path.Combine(generatedRoot, index.ToString("D3", CultureInfo.InvariantCulture));
            Directory.CreateDirectory(output);
            outcomes.Add(fixture.RowId, ExecuteFixture(
                fixture, source, output, nugetConfig, packageCache,
                packageId, packageVersion, targetFramework, timeoutMs));
        }
        return outcomes;
    }

    static FixtureOutcome ExecuteFixture(
        Fixture fixture,
        string source,
        string output,
        string nugetConfig,
        string packageCache,
        string packageId,
        string packageVersion,
        string targetFramework,
        int timeoutMs)
    {
        try
        {
            using var evaluator = Evaluator.Preconfigured();
            ModuleSchema rootSchema = evaluator.EvaluateSchema(ModuleSource.PathFromPath(source));
            var schemas = CollectSchemas(evaluator, rootSchema);
            string generatedNamespace = "Vibeformer.Generated.F" + Sha256Text(fixture.RowId)[..12];
            var mappings = schemas.Values.ToDictionary(schema => schema.GetModuleName(), _ => generatedNamespace,
                StringComparer.Ordinal);
            var sources = new List<string>();
            int sourceIndex = 0;
            foreach (ModuleSchema schema in schemas.Values.OrderBy(item => item.GetModuleName(), StringComparer.Ordinal))
            {
                var options = new CSharpGeneratorOptions { Namespace = generatedNamespace };
                foreach (var mapping in mappings) options.MapNamespace(mapping.Key, mapping.Value);
                var generator = new CSharpGenerator(options);
                string first = generator.Generate(schema);
                string second = generator.Generate(schema);
                if (first != second)
                    throw new InvalidOperationException("Repeated generated source differs.");
                string generatedFile = Path.Combine(output,
                    "Schema" + sourceIndex.ToString("D3", CultureInfo.InvariantCulture) + ".g.cs");
                File.WriteAllText(generatedFile, first, Utf8);
                sources.Add(first);
                sourceIndex++;
            }

            string combined = string.Concat(sources);
            File.WriteAllText(Path.Combine(output, "Program.cs"), GeneratedConsumerSource, Utf8);
            File.WriteAllText(Path.Combine(output, "FixtureConsumer.csproj"),
                ProjectSource(packageId, packageVersion, targetFramework), Utf8);
            ProcessOutcome restore = RunProcess(
                new[] { "dotnet", "restore", "FixtureConsumer.csproj", "--configfile", nugetConfig,
                    "--packages", packageCache, "--no-cache", "--force", "--force-evaluate" }, output, timeoutMs);
            if (!restore.Success)
                return FailedFixture(fixture.RowId, restore.Timeout ? "TIMEOUT" : "RESTORE",
                    NormalizeDiagnostic(restore.Output, output),
                    generated: true);
            ProcessOutcome build = RunProcess(
                new[] { "dotnet", "build", "FixtureConsumer.csproj", "--nologo", "--verbosity:minimal",
                    "--no-restore", "--no-incremental", "-warnaserror" }, output, timeoutMs);
            if (!build.Success)
                return FailedFixture(fixture.RowId, build.Timeout ? "TIMEOUT" : "COMPILATION",
                    NormalizeDiagnostic(build.Output, output),
                    generated: true);
            ProcessOutcome run = RunProcess(
                new[] { "dotnet", "run", "--project", "FixtureConsumer.csproj", "--no-build", "--no-restore",
                    "--", source, rootSchema.GetModuleName() }, output, timeoutMs);
            if (!run.Success)
                return FailedFixture(fixture.RowId, run.Timeout ? "TIMEOUT" : "BEHAVIOR",
                    NormalizeDiagnostic(run.Output, output),
                    generated: true, compiled: true);
            var observed = ParseConsumerObservation(run.Output);
            return new FixtureOutcome(
                fixture.RowId, "PASS", true, true, observed["constructed"] == "true",
                observed["bound"] == "true", observed["loaded"] == "true",
                observed["equal-values"] == "true", observed["diagnostics"] == "true",
                observed["lifecycle"] == "true", combined.Contains("/// <summary>", StringComparison.Ordinal),
                combined.Contains("global::System.Obsolete", StringComparison.Ordinal),
                string.Join(";", observed.OrderBy(item => item.Key, StringComparer.Ordinal)
                    .Select(item => item.Key + "=" + item.Value)), "");
        }
        catch (CSharpGenerationException error)
        {
            return FailedFixture(fixture.RowId, "GENERATION", string.Join("\n", error.Diagnostics));
        }
        catch (PklException error)
        {
            return FailedFixture(fixture.RowId, "SCHEMA", StableDiagnostic(error));
        }
        catch (Exception error)
        {
            return FailedFixture(fixture.RowId, "CRASH", StableDiagnostic(error));
        }
    }

    static FixtureOutcome FailedFixture(
        string rowId, string stage, string diagnostic, bool generated = false, bool compiled = false) =>
        new(rowId, stage == "TIMEOUT" ? "TIMEOUT" : stage == "CRASH" ? "CRASH" : "FAIL",
            generated, compiled, false, false, false, false, false, false,
            false, false, "stage=" + stage.ToLowerInvariant(), StableText(diagnostic));

    static SortedDictionary<string, ModuleSchema> CollectSchemas(Evaluator evaluator, ModuleSchema root)
    {
        var result = new SortedDictionary<string, ModuleSchema>(StringComparer.Ordinal);
        var pending = new Queue<ModuleSchema>();
        pending.Enqueue(root);
        while (pending.Count != 0)
        {
            ModuleSchema schema = pending.Dequeue();
            if (!result.TryAdd(schema.GetModuleName(), schema)) continue;
            ModuleSchema? supermodule = schema.GetSupermodule();
            if (supermodule is not null && supermodule.GetModuleUri().Scheme != "pkl")
                pending.Enqueue(supermodule);
            foreach (Uri uri in schema.GetImports().Values.OrderBy(value => value.ToString(), StringComparer.Ordinal))
            {
                if (uri.Scheme == "pkl") continue;
                pending.Enqueue(evaluator.EvaluateSchema(ModuleSource.Uri(uri)));
            }
        }
        return result;
    }

    static IReadOnlyDictionary<string, string> ParseConsumerObservation(string output)
    {
        string? line = output.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n')
            .FirstOrDefault(value => value.StartsWith("VIBEFORMER_FIXTURE_OBSERVATION\t", StringComparison.Ordinal));
        if (line is null) throw new InvalidDataException("Generated consumer omitted its observation.");
        return line.Split('\t').Skip(1).Select(field => field.Split('=', 2))
            .ToDictionary(fields => fields[0], fields => fields[1], StringComparer.Ordinal);
    }

    static (Row Row, string Status, string Observation, string Diagnostic) ExecuteRow(
        Row row, string revision, IReadOnlyDictionary<string, FixtureOutcome> fixtureOutcomes)
    {
        string classification = row["product-classification"];
        if (classification == "non-shipping-test-infrastructure")
            return (row, "TEST_INFRASTRUCTURE", "source-sha256=" + row["source-sha256"], "");
        if (classification == "user-approved-excluded-surface")
            return (row, "APPROVED_EXCLUSION", "", "");

        if (row["artifact-kind"] == "fixture")
        {
            FixtureOutcome outcome = fixtureOutcomes[row["row-id"]];
            bool passed = outcome.Generated && outcome.Compiled && outcome.Constructed && outcome.Bound &&
                outcome.Loaded && outcome.Diagnostics && outcome.Lifecycle;
            return (row, passed ? "PASS" : outcome.Status, outcome.Observation,
                passed ? "" : outcome.Diagnostic.Length == 0 ? "fixture behavior assertion failed" : outcome.Diagnostic);
        }

        FixtureOutcome[] matrix = fixtureOutcomes.Values.ToArray();
        string family = row["behavior-family"];
        string[] observations = row["observation-kinds"].Split(';');
        var failures = new List<string>();
        string? executionFailure = matrix.Any(item => item.Status == "TIMEOUT") ? "TIMEOUT" :
            matrix.Any(item => item.Status == "CRASH") ? "CRASH" : null;
        if (matrix.Length == 0) failures.Add("fixture matrix is empty");
        if (observations.Contains("generated-model-shape-and-behavior") &&
            matrix.Any(item => !item.Generated || !item.Compiled))
            failures.Add("generated fixture matrix did not compile");
        if (observations.Contains("equality-hash-string-behavior") && matrix.Any(item => !item.EqualValues))
            failures.Add("independently loaded generated values lack required equality behavior");
        if (observations.Contains("documentation-and-deprecation-metadata"))
        {
            if (family.Contains("documentation", StringComparison.Ordinal) && matrix.All(item => !item.HasDocumentation))
                failures.Add("generated fixture matrix omitted documentation metadata");
            if (family.Contains("deprecat", StringComparison.Ordinal) && matrix.All(item => !item.HasDeprecation))
                failures.Add("generated fixture matrix omitted deprecation metadata");
        }
        if (observations.Contains("binding-and-conversion") && matrix.All(item => !item.Bound))
            failures.Add("fixture matrix binding observation failed");
        if (observations.Contains("generated-loaders") && matrix.All(item => !item.Loaded))
            failures.Add("generated loader observation failed");
        if (observations.Contains("reflection-and-nullability") && matrix.Any(item => !item.Compiled))
            failures.Add("nullable reflection matrix did not compile warning-free");
        if (observations.Contains("lifecycle") && matrix.All(item => !item.Lifecycle))
            failures.Add("fixture lifecycle observation failed");
        if (observations.Contains("diagnostics") && matrix.All(item => !item.Diagnostics))
            failures.Add("fixture diagnostic observation failed");

        string observation = "revision=" + revision + ";fixture-count=" + matrix.Length +
            ";observations=" + string.Join(",", observations.OrderBy(value => value, StringComparer.Ordinal));
        return executionFailure is not null
            ? (row, executionFailure, observation,
                "complete fixture matrix contains " + executionFailure.ToLowerInvariant())
            : failures.Count == 0
            ? (row, "PASS", observation, "")
            : (row, "FAIL", observation, string.Join("; ", failures.OrderBy(value => value, StringComparer.Ordinal)));
    }

    static void WriteResults(
        string output,
        string revision,
        IReadOnlyList<(Row Row, string Status, string Observation, string Diagnostic)> rows)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        using var writer = new StreamWriter(output, false, Utf8);
        writer.WriteLine(ResultMagic);
        writer.WriteLine("columns\t" + string.Join("\t", ResultColumns));
        foreach (var result in rows)
        {
            Row row = result.Row;
            writer.WriteLine(string.Join("\t", new[]
            {
                "row", row["row-id"], "package-dotnet", revision, row["artifact-kind"],
                row["upstream-module"], row["upstream-case-identity"], row["source-path"],
                row["source-sha256"], row["source-line"], row["behavior-family"],
                row["product-classification"], row["observation-kinds"], row["oracle-kind"],
                result.Status, B64(result.Observation), B64(result.Diagnostic)
            }));
        }
    }

    static void WriteLoadedAssemblies(
        string output, IReadOnlyList<(string Name, string Sha256)> expected)
    {
        foreach (var item in expected) Assembly.Load(new AssemblyName(item.Name));
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        using var writer = new StreamWriter(output, false, Utf8);
        writer.WriteLine(AssemblyMagic);
        foreach (var item in expected.OrderBy(value => value.Name, StringComparer.Ordinal))
        {
            Assembly loaded = AppDomain.CurrentDomain.GetAssemblies().Single(assembly =>
                string.Equals(assembly.GetName().Name, item.Name, StringComparison.Ordinal));
            string path = Path.GetFullPath(loaded.Location);
            string actual = Sha256(path);
            if (actual != item.Sha256)
                throw new InvalidDataException("Loaded assembly differs from packed hash: " + item.Name);
            writer.WriteLine(string.Join("\t", item.Name, path, item.Sha256, actual));
        }
    }

    static ProcessOutcome RunProcess(IReadOnlyList<string> command, string directory, int timeoutMs)
    {
        var start = new ProcessStartInfo(command[0])
        {
            WorkingDirectory = directory,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        foreach (string argument in command.Skip(1)) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Could not start child process.");
        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
        Task<string> stderr = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(timeoutMs))
        {
            process.Kill(entireProcessTree: true);
            process.WaitForExit();
            Task.WaitAll(stdout, stderr);
            return new ProcessOutcome(false, true, process.ExitCode, StableText(stdout.Result + stderr.Result));
        }
        Task.WaitAll(stdout, stderr);
        string output = StableText(stdout.Result + stderr.Result);
        return new ProcessOutcome(process.ExitCode == 0, false, process.ExitCode, output);
    }

    static string ConfinedPath(string root, string relative)
    {
        string normalizedRoot = Path.GetFullPath(root) + Path.DirectorySeparatorChar;
        string path = Path.GetFullPath(Path.Combine(root, relative));
        if (!path.StartsWith(normalizedRoot, StringComparison.Ordinal))
            throw new InvalidDataException("Fixture matrix path escaped its staged root.");
        return path;
    }

    static void RequireColumns(string? line, IReadOnlyList<string> expected, string name)
    {
        string[] actual = line?.Split('\t') ?? Array.Empty<string>();
        if (!actual.SequenceEqual(expected))
            throw new InvalidDataException(name + " columns changed.");
    }

    static string StableDiagnostic(Exception error) =>
        StableText(error.GetType().FullName + ": " + error.Message);

    static string NormalizeDiagnostic(string value, string generatedDirectory) =>
        StableText(value).Replace(Path.GetFullPath(generatedDirectory), "<GENERATED_ROOT>",
            StringComparison.Ordinal);

    static string StableText(string value) => value.Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace('\r', '\n').Trim();

    static string Sha256(string file)
    {
        using var stream = File.OpenRead(file);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }

    static string Sha256Text(string value) =>
        Convert.ToHexString(SHA256.HashData(Utf8.GetBytes(value))).ToLowerInvariant();

    static string B64(string value) => Convert.ToBase64String(Utf8.GetBytes(value));

    static string ProjectSource(string packageId, string version, string framework) => $$"""
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <OutputType>Exe</OutputType>
            <TargetFramework>{{framework}}</TargetFramework>
            <ImplicitUsings>disable</ImplicitUsings>
            <Nullable>enable</Nullable>
            <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
            <WarningsAsErrors>nullable</WarningsAsErrors>
            <Deterministic>true</Deterministic>
          </PropertyGroup>
          <ItemGroup>
            <PackageReference Include="{{packageId}}" Version="{{version}}" />
          </ItemGroup>
        </Project>
        """;

    const string GeneratedConsumerSource = """
        #nullable enable
        using System;
        using System.Linq;
        using System.Reflection;
        using Pkl.Core;

        static class GeneratedFixtureConsumer
        {
            public static void Main(string[] args)
            {
                if (args.Length != 2) throw new ArgumentException("A staged fixture path and module name are required.");
                Type[] generated = Assembly.GetExecutingAssembly().GetTypes()
                    .Where(type => type.IsPublic && type.GetCustomAttribute<PklQualifiedNameAttribute>() is not null)
                    .OrderBy(type => type.FullName, StringComparer.Ordinal).ToArray();
                Type module = generated.Single(type =>
                    type.GetCustomAttribute<PklNameAttribute>()?.Name == args[1] &&
                    type.GetMethods(BindingFlags.Public | BindingFlags.Static)
                        .Any(method => method.Name == "Load"));
                bool constructed = generated.Where(type => type.IsClass && !type.IsAbstract)
                    .All(type => Activator.CreateInstance(type) is not null);
                using var evaluator = Evaluator.Preconfigured();
                object? first = module.GetMethod("Load", BindingFlags.Public | BindingFlags.Static)!
                    .Invoke(null, new object?[] { evaluator, ModuleSource.PathFromPath(args[0]), null });
                object? second = module.GetMethod("Load", BindingFlags.Public | BindingFlags.Static)!
                    .Invoke(null, new object?[] { evaluator, ModuleSource.PathFromPath(args[0]), null });
                bool loaded = first is not null && second is not null && first.GetType() == module;
                bool bound = first is not null &&
                    Equals(new ConfigBinder().Bind(1L, typeof(long)), 1L);
                bool equalValues = first is not null && first.Equals(second) &&
                    first.GetHashCode() == second!.GetHashCode() && !string.IsNullOrEmpty(first.ToString());
                bool diagnostics;
                try
                {
                    new ConfigBinder().Bind("not-an-int", typeof(long));
                    diagnostics = false;
                }
                catch (PklBindException error)
                {
                    diagnostics = error.Message.Contains("$", StringComparison.Ordinal) &&
                        error.Message.Contains("System.Int64", StringComparison.Ordinal);
                }
                bool lifecycle;
                var config = new ConfigEvaluator();
                config.Dispose();
                try { _ = config.Evaluator; lifecycle = false; }
                catch (ObjectDisposedException) { lifecycle = true; }
                config.Dispose();
                Console.WriteLine("VIBEFORMER_FIXTURE_OBSERVATION\t" +
                    "bound=" + Lower(bound) + "\t" +
                    "constructed=" + Lower(constructed) + "\t" +
                    "diagnostics=" + Lower(diagnostics) + "\t" +
                    "equal-values=" + Lower(equalValues) + "\t" +
                    "lifecycle=" + Lower(lifecycle) + "\t" +
                    "loaded=" + Lower(loaded));
            }

            static string Lower(bool value) => value ? "true" : "false";
        }
        """;
}
