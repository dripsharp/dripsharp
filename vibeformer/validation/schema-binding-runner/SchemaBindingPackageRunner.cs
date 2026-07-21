using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
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
        string bindingContractObservation = VerifyConfigurationBindingApi();

        var fixtureOutcomes = ExecuteFixtureMatrix(
            fixtures, fixtureRoot, generatedRoot, nugetConfig, packageCache,
            packageId, packageVersion, targetFramework, timeoutMs, bindingContractObservation);
        var rows = inventory.Select(row => ExecuteRow(
            row, revision, fixtureOutcomes, bindingContractObservation)).ToArray();
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
        int timeoutMs,
        string bindingContractObservation)
    {
        var outcomes = new Dictionary<string, FixtureOutcome>(StringComparer.Ordinal);
        using var metadataEvaluator = Evaluator.Preconfigured();
        bool hasDeprecation = VerifyDeprecationGeneration(metadataEvaluator);
        for (int index = 0; index < fixtures.Count; index++)
        {
            Fixture fixture = fixtures[index];
            string source = ConfinedPath(fixtureRoot, fixture.RelativePath);
            if (!File.Exists(source) || Sha256(source) != fixture.SourceSha256)
                throw new InvalidDataException("Staged fixture hash differs from inventory: " + fixture.RowId);
            string output = Path.Combine(generatedRoot, index.ToString("D3", CultureInfo.InvariantCulture));
            Directory.CreateDirectory(output);
            var outcome = ExecuteFixture(
                fixture, source, output, nugetConfig, packageCache,
                packageId, packageVersion, targetFramework, timeoutMs,
                bindingContractObservation);
            outcomes.Add(fixture.RowId, outcome with
            {
                HasDeprecation = outcome.HasDeprecation || hasDeprecation
            });
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
        int timeoutMs,
        string bindingContractObservation)
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
            string? auditFailure = AuditGeneratedSource(combined);
            if (auditFailure is not null)
                return FailedFixture(fixture.RowId, "GENERATION", auditFailure, generated: true);
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
                bindingContractObservation + ";" +
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

    static string? AuditGeneratedSource(string source)
    {
        foreach (var forbidden in new[]
        {
            "default!", "return default", "return null!",
            "NotImplementedException", "NotSupportedException(\"TODO"
        })
            if (source.Contains(forbidden, StringComparison.Ordinal))
                return "generated public source contains forbidden placeholder " + forbidden;
        return null;
    }

    static bool VerifyDeprecationGeneration(Evaluator evaluator)
    {
        const string source = """
            @Deprecated { message = "module deprecation" }
            module regression.Deprecation

            @Deprecated { message = "class deprecation" }
            class OldClass {
              value: String
            }

            @Deprecated { message = "property deprecation" }
            oldProperty: String = "old"
            """;
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.Text(source));
        string generated = new CSharpGenerator().Generate(schema);
        string withoutDocs = new CSharpGenerator(new CSharpGeneratorOptions
        {
            EmitDocComments = false
        }).Generate(schema);
        foreach (string expected in new[]
        {
            "[global::System.Obsolete(\"module deprecation\")]",
            "[global::System.Obsolete(\"class deprecation\")]",
            "[global::System.Obsolete(\"property deprecation\")]"
        })
            if (!generated.Contains(expected, StringComparison.Ordinal) ||
                !withoutDocs.Contains(expected, StringComparison.Ordinal))
                throw new InvalidOperationException(
                    "Generated deprecation metadata is incomplete: " + expected);
        return true;
    }

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
        Row row, string revision, IReadOnlyDictionary<string, FixtureOutcome> fixtureOutcomes,
        string bindingContractObservation)
    {
        string classification = row["product-classification"];
        if (classification == "non-shipping-test-infrastructure")
            return (row, "TEST_INFRASTRUCTURE", "source-sha256=" + row["source-sha256"], "");
        if (classification == "user-approved-excluded-surface")
            return (row, "APPROVED_EXCLUSION", "", "");

        if (row["artifact-kind"] == "fixture")
        {
            FixtureOutcome outcome = fixtureOutcomes[row["row-id"]];
            string[] fixtureObservations = row["observation-kinds"].Split(';');
            bool passed = outcome.Generated && outcome.Compiled && outcome.Constructed &&
                (!fixtureObservations.Contains("binding-and-conversion") || outcome.Bound && outcome.Loaded) &&
                (!fixtureObservations.Contains("generated-loaders") || outcome.Loaded) &&
                (!fixtureObservations.Contains("equality-hash-string-behavior") ||
                    outcome.Loaded && outcome.EqualValues) &&
                (!fixtureObservations.Contains("diagnostics") || outcome.Diagnostics) &&
                (!fixtureObservations.Contains("lifecycle") || outcome.Lifecycle);
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
        if (observations.Contains("equality-hash-string-behavior"))
        {
            FixtureOutcome[] loaded = matrix.Where(item => item.Loaded).ToArray();
            if (loaded.Length == 0 || loaded.Any(item => !item.EqualValues))
                failures.Add("independently loaded generated values lack required equality behavior");
        }
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
            ";observations=" + string.Join(",", observations.OrderBy(value => value, StringComparer.Ordinal)) +
            ";" + bindingContractObservation;
        return executionFailure is not null
            ? (row, executionFailure, observation,
                "complete fixture matrix contains " + executionFailure.ToLowerInvariant())
            : failures.Count == 0
            ? (row, "PASS", observation, "")
            : (row, "FAIL", observation, string.Join("; ", failures.OrderBy(value => value, StringComparer.Ordinal)));
    }

    static string VerifyConfigurationBindingApi()
    {
        const string sourceText = """
            module qfk.Config

            import "pkl:semver"

            abstract class Animal {
              name: String
            }

            class Dog extends Animal {}

            pigeon {
              name = "Pigeon"
              age = 42
              hobbies = Set("swimming", "reading")
              address { street = "Fuzzy St." }
            }
            numbers = List(1, 2, 3)
            mapping = Map("one", 1, "two", 2)
            nullValue = null
            animal: Animal = new Dog { name = "Rex" }
            bytes = Bytes(0, 127, 255)
            pair = Pair(1, "two")
            duration = 3.s
            size = 2.mb
            version = semver.Version("1.2.3-rc.1+build.5")
            pattern = Regex("(?i)ab+")
            relativePath = "relative/path"
            uri = "https://example.invalid/a"
            """;

        Config retained;
        using (var evaluator = ConfigEvaluator.Preconfigured())
        {
            retained = evaluator.Evaluate(ModuleSource.Text(sourceText));
            Require(retained.QualifiedName == "", "configuration root qualified name");
            Require(retained["pigeon"].QualifiedName == "pigeon", "qualified child path");
            Require(retained.GetPath("pigeon", "address", "street").QualifiedName ==
                "pigeon.address.street", "qualified nested path");
            Require(retained.GetPath("pigeon", "address", "street").As<string>() == "Fuzzy St.",
                "nested navigation value");
            Require(retained["numbers"][1].QualifiedName == "numbers[1]" &&
                retained["numbers"][1].As<int>() == 2, "indexed navigation");
            Require(retained["mapping"].Get("two").As<int>() == 2, "map-key navigation");
            Require(retained["pigeon"].TryGet("name", out Config? name) &&
                name!.As<string>() == "Pigeon", "try navigation");
            Require(!retained["pigeon"].TryGet("missing", out _), "try missing navigation");
            var missing = Expect<NoSuchChildException>(
                () => retained["pigeon"].Get("missing"), "missing child");
            Require(missing.QualifiedName == "pigeon" && missing.ChildName == "missing" &&
                missing.Message.Contains("pigeon", StringComparison.Ordinal),
                "missing child diagnostics");

            PersonModel pigeon = retained["pigeon"].As<PersonModel>();
            Require(pigeon.Name == "Pigeon" && pigeon.Age == 42 &&
                pigeon.Hobbies.SetEquals(new[] { Hobby.Swimming, Hobby.Reading }) &&
                pigeon.Address.Street == "Fuzzy St.", "constructor and enum binding");
            Require(retained["pigeon"].As(typeof(PersonModel)) is PersonModel,
                "Type-based config binding");
            Require(retained["pigeon"].As<Dictionary<string, object?>>()["name"] is string,
                "Pkl object to map binding");
            Require(retained["nullValue"].AsNullable<string>() is null,
                "nullable config binding");
            var nullFailure = Expect<PklBindException>(
                () => retained["nullValue"].As<string>(), "non-null config binding");
            Require(nullFailure.PklPath == "$.nullValue" && nullFailure.TargetType == typeof(string),
                "nullable config diagnostics");

            Require(evaluator.EvaluateExpression<PersonModel>(
                    ModuleSource.Text(sourceText), "pigeon").Age == 42,
                "typed expression evaluation");
            Require(evaluator.EvaluateExpression(
                    ModuleSource.Text(sourceText), "pigeon", typeof(PersonModel)) is PersonModel,
                "Type-based expression evaluation");
            Config output = evaluator.EvaluateOutputValue(ModuleSource.Text(
                "output { value = new Dynamic { answer = 42 } }"));
            Require(output["answer"].As<int>() == 42, "output value evaluation");
        }
        Require(retained["pigeon"]["age"].As<int>() == 42,
            "configuration remains valid after evaluator disposal");

        VerifyBuilderAndLifecycle();
        VerifyBinderConversions(retained);
        VerifyBindingFailures();
        VerifyPolymorphismAndGeneratedLoaders(retained);

        return "binding=true;builder=true;conversions=true;diagnostics=true;" +
            "lifecycle=true;navigation=true;polymorphism=true;reflection-nullability=true";
    }

    static void VerifyBuilderAndLifecycle()
    {
        ConfigEvaluatorBuilder empty = ConfigEvaluatorBuilder.Unconfigured();
        Require(empty.EnvironmentVariables.Count == 0 && empty.ExternalProperties.Count == 0,
            "unconfigured evaluator builder");

        ConfigEvaluatorBuilder configured = ConfigEvaluatorBuilder.Preconfigured()
            .SetEnvironmentVariables(new Dictionary<string, string> { ["QFK_ENV"] = "environment" })
            .SetExternalProperties(new Dictionary<string, string> { ["qfk.property"] = "external" })
            .SetAllowedModules(new[] { new Regex(".*", RegexOptions.CultureInvariant) })
            .SetAllowedResources(new[] { new Regex(".*", RegexOptions.CultureInvariant) })
            .SetTimeout(TimeSpan.FromSeconds(30))
            .ConfigureBinder(options => options.PropertyNamesCaseInsensitive = false);
        Require(configured.EnvironmentVariables.SequenceEqual(
                new[] { new KeyValuePair<string, string>("QFK_ENV", "environment") }) &&
            configured.ExternalProperties.SequenceEqual(
                new[] { new KeyValuePair<string, string>("qfk.property", "external") }) &&
            configured.EvaluatorBuilder.GetAllowedModules().Count == 1 &&
            configured.EvaluatorBuilder.GetAllowedResources().Count == 1 &&
            configured.EvaluatorBuilder.GetTimeout() == TimeSpan.FromSeconds(30),
            "builder policy propagation");
        using (ConfigEvaluator evaluator = configured.Build())
        {
            Config config = evaluator.Evaluate(ModuleSource.Text(
                "environment = read(\"env:QFK_ENV\")\nexternalValue = read(\"prop:qfk.property\")"));
            Require(config["environment"].As<string>() == "environment" &&
                config["externalValue"].As<string>() == "external" && evaluator.OwnsEvaluator,
                "builder evaluator settings");
        }

        using var borrowedEvaluator = Evaluator.Preconfigured();
        var borrowed = new ConfigEvaluator(borrowedEvaluator);
        Require(!borrowed.OwnsEvaluator, "borrowed evaluator ownership");
        borrowed.Dispose();
        borrowed.Dispose();
        Require(borrowed.IsDisposed, "idempotent config evaluator disposal");
        Expect<ObjectDisposedException>(() => _ = borrowed.Binder, "disposed binder access");
        Expect<ObjectDisposedException>(() => _ = borrowed.Evaluator, "disposed evaluator access");
        Expect<ObjectDisposedException>(
            () => borrowed.Evaluate(ModuleSource.Text("value = 1")), "disposed evaluation");
        Require((long)borrowedEvaluator.EvaluateExpression(
                ModuleSource.Text("value = 1"), "value") == 1L,
            "borrowed evaluator survives wrapper disposal");

        var ownedEvaluator = Evaluator.Preconfigured();
        var owned = new ConfigEvaluator(ownedEvaluator, null, ownsEvaluator: true);
        Require(owned.OwnsEvaluator, "owned evaluator ownership");
        owned.Dispose();
        Expect<Exception>(() => ownedEvaluator.Evaluate(ModuleSource.Text("value = 1")),
            "owned evaluator disposal propagation");
    }

    static void VerifyBinderConversions(Config config)
    {
        var binder = new ConfigBinder();
        Require(binder.Bind<int[]>(new object[] { 1L, 2L, 3L }).SequenceEqual(new[] { 1, 2, 3 }),
            "array binding");
        Require(binder.Bind<IReadOnlySet<string>>(new object[] { "a", "b", "a" })
                .SetEquals(new[] { "a", "b" }), "set binding");
        var nestedSource = new Dictionary<string, object?>
        {
            ["values"] = new object?[] { 1L, null, 3L }
        };
        Dictionary<string, List<int?>> nested =
            binder.Bind<Dictionary<string, List<int?>>>(nestedSource);
        Require(nested["values"].SequenceEqual(new int?[] { 1, null, 3 }),
            "nested generic collection binding");
        var numericMap = binder.Bind<Dictionary<int, string>>(
            new Dictionary<object, object?> { [1L] = "one", [2L] = "two" });
        Require(numericMap.Count == 2 && numericMap[2] == "two", "map key/value binding");

        var pair = new Pair<object, object>(1L, "two");
        (int Number, string Text) tuple = binder.Bind<(int, string)>(pair);
        KeyValuePair<int, string> keyValue = binder.Bind<KeyValuePair<int, string>>(pair);
        Require(tuple == (1, "two") && keyValue.Key == 1 && keyValue.Value == "two",
            "pair equivalents");

        Require(binder.BindNullable<string>(PNull.Instance) is null,
            "explicit nullable root binding");
        var requiredNull = Expect<PklBindException>(
            () => binder.BindRequired<string>(PNull.Instance), "required null root");
        Require(requiredNull.PklPath == "$" && requiredNull.TargetType == typeof(string),
            "required null diagnostics");
        Require(binder.Bind<Hobby>("swimming") == Hobby.Swimming, "enum binding");

        byte[] bytes = binder.Bind<byte[]>(new sbyte[] { 0, 127, -1 });
        ReadOnlyMemory<byte> memory = binder.Bind<ReadOnlyMemory<byte>>(new sbyte[] { 1, -1 });
        Require(bytes.SequenceEqual(new byte[] { 0, 127, 255 }) &&
            memory.ToArray().SequenceEqual(new byte[] { 1, 255 }), "byte binding");
        Require(config["bytes"].As<byte[]>().SequenceEqual(new byte[] { 0, 127, 255 }),
            "evaluated Pkl Bytes binding");

        Require(binder.Bind<FileInfo>("relative/path").Name == "path" &&
            binder.Bind<DirectoryInfo>("relative/path").Name == "path" &&
            binder.Bind<Uri>("https://example.invalid/a").AbsolutePath == "/a" &&
            binder.Bind<Regex>("(?i)ab+").IsMatch("ABBB"), "path URI and regex binding");
        Require(config["pattern"].As<Regex>().IsMatch("ABBB"), "evaluated Regex binding");

        var duration = new Duration(3, DurationUnit.SECONDS);
        var dataSize = new DataSize(2, DataSizeUnit.MEGABYTES);
        Require(binder.Bind<TimeSpan>(duration) == TimeSpan.FromSeconds(3) &&
            ReferenceEquals(binder.Bind<DataSize>(dataSize), dataSize) &&
            config["duration"].As<TimeSpan>() == TimeSpan.FromSeconds(3) &&
            config["size"].As<DataSize>().Unit == DataSizeUnit.MEGABYTES,
            "duration and data-size binding");
        Require(binder.Bind<Pkl.Core.Version>("1.2.3-rc.1").ToString() == "1.2.3-rc.1" &&
            config["version"].As<Pkl.Core.Version>().ToString() == "1.2.3-rc.1+build.5",
            "semantic version binding");

        Require(binder.Bind<UserId>(12L).Value == 12L, "type alias binding");
        Require(binder.Bind<RecordModel>(new Dictionary<string, object?>
            { ["name"] = "record", ["age"] = 7L }) == new RecordModel("record", 7),
            "record binding");
        var init = binder.Bind<InitModel>(new Dictionary<string, object?>
            { ["name"] = "init", ["optional"] = null });
        Require(init.Name == "init" && init.Optional is null, "init and settable member binding");
        var derived = binder.Bind<DerivedModel>(new Dictionary<string, object?> { ["value"] = "derived" });
        Require(derived.Value == "derived" && derived.SetCount == 1,
            "most-derived override binding");
    }

    static void VerifyBindingFailures()
    {
        var binder = new ConfigBinder();
        var missing = Expect<PklBindException>(
            () => binder.Bind<RequiredModel>(new Dictionary<string, object?>()),
            "missing required member");
        Require(missing.PklPath == "$.value" && missing.TargetType == typeof(string),
            "missing member diagnostics");

        var unknown = Expect<PklBindException>(
            () => binder.Bind<RecordModel>(new Dictionary<string, object?>
                { ["name"] = "x", ["age"] = 1L, ["extra"] = true }),
            "unknown member");
        Require(unknown.PklPath == "$" && unknown.Reason!.Contains("extra", StringComparison.Ordinal),
            "unknown member diagnostics");
        var lenient = new ConfigBinder(new ConfigBinderOptions { IgnoreUnknownProperties = true });
        Require(lenient.Bind<RecordModel>(new Dictionary<string, object?>
            { ["name"] = "x", ["age"] = 1L, ["extra"] = true }).age == 1,
            "unknown member option");

        var incompatible = Expect<PklBindException>(
            () => binder.Bind<AgeModel>(new Dictionary<string, object?> { ["age"] = "old" }),
            "incompatible member");
        Require(incompatible.PklPath == "$.age" && incompatible.TargetType == typeof(int) &&
            incompatible.SourceType == typeof(string), "incompatible type diagnostics");
        Expect<PklBindException>(() => binder.Bind<byte>(256L), "numeric overflow");
        Expect<PklBindException>(() => binder.Bind<double>(9_007_199_254_740_993L),
            "lossy numeric conversion");
        var lossy = new ConfigBinder(new ConfigBinderOptions { AllowLossyNumericConversions = true });
        Require(lossy.Bind<double>(9_007_199_254_740_993L) == 9_007_199_254_740_992d,
            "lossy numeric option");

        Expect<PklBindException>(() => binder.Bind<AmbiguousModel>(
            new Dictionary<string, object?> { ["value"] = "x" }), "constructor ambiguity");
        var caseInsensitive = new ConfigBinder(new ConfigBinderOptions
            { PropertyNamesCaseInsensitive = true });
        Expect<PklBindException>(() => caseInsensitive.Bind<InitModel>(
            new Dictionary<string, object?> { ["name"] = "a", ["Name"] = "b" }),
            "case-insensitive ambiguity");

        var converters = new ConfigBinderOptions()
            .AddConversion<IComparable, string>((value, _) => value.ToString()!)
            .AddConversion<IFormattable, string>((value, _) => value.ToString(null, CultureInfo.InvariantCulture));
        Expect<PklBindException>(() => new ConfigBinder(converters).Bind<string>(1L),
            "custom conversion ambiguity");
        var failingConverter = new ConfigBinderOptions()
            .AddConversion<long, ConvertedModel>((_, _) =>
                throw new InvalidOperationException("deliberate converter failure"));
        var converterFailure = Expect<PklBindException>(
            () => new ConfigBinder(failingConverter).Bind<ConvertedModel>(1L),
            "custom converter failure");
        Require(converterFailure.InnerException is InvalidOperationException &&
            converterFailure.Reason == "the custom conversion failed",
            "custom converter diagnostics");

        var cycle = new Dictionary<string, object?>();
        cycle["child"] = cycle;
        var cycleFailure = Expect<PklBindException>(
            () => binder.Bind<CycleModel>(cycle), "binding cycle");
        Require(cycleFailure.PklPath == "$.child" &&
            cycleFailure.Reason!.Contains("cyclic", StringComparison.Ordinal),
            "cycle diagnostics");

        var nonNullable = Expect<PklBindException>(() => binder.Bind<NonNullableModel>(
            new Dictionary<string, object?> { ["value"] = null }), "non-nullable metadata");
        Require(nonNullable.PklPath == "$.value", "non-nullable member path");
        Require(binder.Bind<NullableModel>(new Dictionary<string, object?> { ["value"] = null }).Value is null,
            "nullable member metadata");
        Expect<PklBindException>(() => binder.Bind<RequiredListModel>(
            new Dictionary<string, object?> { ["values"] = new object?[] { "ok", null } }),
            "nested non-nullable metadata");
        Require(binder.Bind<NullableListModel>(new Dictionary<string, object?>
            { ["values"] = new object?[] { "ok", null } }).Values[1] is null,
            "nested nullable metadata");
    }

    static void VerifyPolymorphismAndGeneratedLoaders(Config config)
    {
        var polymorphic = new ConfigBinder(new ConfigBinderOptions()
            .AddTypeMapping<AnimalModel, DogModel>("qfk.Config#Dog"));
        AnimalModel animal = polymorphic.Bind<AnimalModel>(config["animal"].RawValue);
        Require(animal is DogModel { Name: "Rex" }, "configured polymorphic binding");

        GeneratedTargetLoader.Count = 0;
        var generated = new ConfigBinder().Bind<GeneratedTarget>(
            new Dictionary<string, object?> { ["value"] = 9L });
        Require(generated.Value == 9 && GeneratedTargetLoader.Count == 1,
            "generated loader binding");
        var withoutGenerated = new ConfigBinder(new ConfigBinderOptions { UseGeneratedLoaders = false })
            .Bind<GeneratedTarget>(new Dictionary<string, object?> { ["value"] = 10L });
        Require(withoutGenerated.Value == 10 && GeneratedTargetLoader.Count == 1,
            "generated loader option");
    }

    static void Require(bool condition, string behavior)
    {
        if (!condition) throw new InvalidOperationException(
            "Configuration/binding package assertion failed: " + behavior + ".");
    }

    static TException Expect<TException>(Action action, string behavior)
        where TException : Exception
    {
        try { action(); }
        catch (TException error) { return error; }
        throw new InvalidOperationException(
            "Configuration/binding package assertion did not fail: " + behavior + ".");
    }

    public enum Hobby
    {
        [PklName("swimming")] Swimming,
        [PklName("reading")] Reading,
        [PklName("surfing")] Surfing
    }

    public sealed class AddressModel
    {
        public AddressModel(string street) => Street = street;
        public string Street { get; }
    }

    public sealed class PersonModel
    {
        public PersonModel(string name, int age, IReadOnlySet<Hobby> hobbies, AddressModel address)
            => (Name, Age, Hobbies, Address) = (name, age, hobbies, address);
        public string Name { get; }
        public int Age { get; }
        public IReadOnlySet<Hobby> Hobbies { get; }
        public AddressModel Address { get; }
    }

    public sealed record RecordModel(string name, int age);

    [PklTypeAlias]
    public readonly record struct UserId(long Value);

    public sealed class InitModel
    {
        [PklName("name")] public string Name { get; init; } = "";
        [PklName("optional")] public string? Optional { get; set; }
    }

    public class BaseModel
    {
        [PklName("value")] public virtual string Value { get; set; } = "";
    }

    public sealed class DerivedModel : BaseModel
    {
        private string value = "";
        public int SetCount { get; private set; }
        [PklName("value")]
        public override string Value
        {
            get => value;
            set { this.value = value; SetCount++; }
        }
    }

    public sealed class RequiredModel
    {
        [PklName("value"), PklRequired] public string Value { get; init; } = "";
    }

    public sealed class AgeModel
    {
        public AgeModel(int age) => Age = age;
        public int Age { get; }
    }

    public sealed class AmbiguousModel
    {
        public AmbiguousModel(string value) { }
        public AmbiguousModel(Uri value) { }
    }

    public sealed class ConvertedModel { }

    public sealed class CycleModel
    {
        [PklName("child")] public CycleModel? Child { get; set; }
    }

    public sealed class NonNullableModel
    {
        public NonNullableModel(string value) => Value = value;
        public string Value { get; }
    }

    public sealed class NullableModel
    {
        public NullableModel(string? value) => Value = value;
        public string? Value { get; }
    }

    public sealed class RequiredListModel
    {
        public RequiredListModel(List<string> values) => Values = values;
        public List<string> Values { get; }
    }

    public sealed class NullableListModel
    {
        public NullableListModel(List<string?> values) => Values = values;
        public List<string?> Values { get; }
    }

    public abstract class AnimalModel
    {
        protected AnimalModel(string name) => Name = name;
        public string Name { get; }
    }

    [PklQualifiedName("qfk.Config#Dog")]
    public sealed class DogModel : AnimalModel
    {
        public DogModel(string name) : base(name) { }
    }

    public sealed class GeneratedTarget
    {
        public GeneratedTarget(int value) => Value = value;
        public int Value { get; }
        public static IPklGeneratedLoader<GeneratedTarget> PklLoader { get; } =
            new GeneratedTargetLoader();
    }

    public sealed class GeneratedTargetLoader : IPklGeneratedLoader<GeneratedTarget>
    {
        public static int Count { get; set; }
        public GeneratedTarget Load(object? value, ConfigBinder binder)
        {
            Count++;
            return binder.BindGenerated<GeneratedTarget>(value);
        }
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
                    .All(type => type.GetConstructors(BindingFlags.Public | BindingFlags.Instance).Length != 0);
                using var evaluator = Evaluator.Preconfigured();
                object? first = null;
                object? second = null;
                bool unexportableFunction = false;
                try
                {
                    first = module.GetMethod("Load", BindingFlags.Public | BindingFlags.Static)!
                        .Invoke(null, new object?[] { evaluator, ModuleSource.PathFromPath(args[0]), null });
                    second = module.GetMethod("Load", BindingFlags.Public | BindingFlags.Static)!
                        .Invoke(null, new object?[] { evaluator, ModuleSource.PathFromPath(args[0]), null });
                }
                catch (TargetInvocationException error) when (
                    error.InnerException is PklException pklError &&
                    pklError.Message.Contains("cannot be exported", StringComparison.Ordinal))
                {
                    unexportableFunction = true;
                }
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
                    "loaded=" + Lower(loaded) + "\t" +
                    "unexportable-function=" + Lower(unexportableFunction));
            }

            static string Lower(bool value) => value ? "true" : "false";
        }
        """;
}
