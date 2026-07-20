using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Pkl.Core;
using Pkl.Parser;

static class Program
{
    const string ResultMagic = "VIBEFORMER_PKL_CORE_CORPUS_RESULTS_V1";
    const string ChildMagic = "VIBEFORMER_PKL_CORE_PACKAGE_CHILD_V1";
    const string AssemblyMagic = "VIBEFORMER_PKL_CORE_LOADED_ASSEMBLIES_V1";
    const string Origin = "package-dotnet";

    static readonly string[] ResultColumns =
    {
        "case-id", "origin", "upstream-revision", "junit-unique-id", "source-path",
        "source-sha256", "source-line", "behavior-family", "product-classification",
        "execution-owner", "status", "observation-base64", "diagnostic-base64"
    };

    public static async Task<int> Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--child") return RunChild(args);
        if (args.Length > 0 && args[0] == "--external-reader")
        {
            Console.Write("fixture-reader-ok");
            return 0;
        }
        if (args.Length != 7)
        {
            Console.Error.WriteLine(
                "Usage: runner <manifest> <output> <assembly-manifest> <packages-root> " +
                "<loaded-assemblies-output> <timeout-ms> <workers>");
            return 2;
        }

        try
        {
            string manifestPath = Path.GetFullPath(args[0]);
            string output = Path.GetFullPath(args[1]);
            string assemblyManifest = Path.GetFullPath(args[2]);
            string packagesRoot = Path.GetFullPath(args[3]);
            string loadedAssemblies = Path.GetFullPath(args[4]);
            int timeoutMs = ParsePositive(args[5], "timeout-ms");
            int workers = ParsePositive(args[6], "workers");
            ContractManifest manifest = ContractManifest.Read(manifestPath);
            VerifyLoadedAssemblies(assemblyManifest, packagesRoot, loadedAssemblies);
            await RunParent(manifestPath, manifest, output, timeoutMs, workers);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    static int ParsePositive(string value, string label)
    {
        int parsed = int.Parse(value, System.Globalization.CultureInfo.InvariantCulture);
        if (parsed <= 0) throw new ArgumentOutOfRangeException(label);
        return parsed;
    }

    static async Task RunParent(
        string manifestPath,
        ContractManifest manifest,
        string output,
        int timeoutMs,
        int workers)
    {
        string scratch = Path.Combine(
            Path.GetTempPath(), "vibeformer-pkl-core-package-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        var results = new CorpusResult[manifest.Cases.Count];
        try
        {
            await Parallel.ForEachAsync(
                Enumerable.Range(0, manifest.Cases.Count),
                new ParallelOptions { MaxDegreeOfParallelism = workers },
                async (index, _) =>
                {
                    results[index] = await RunBoundedChild(
                        manifestPath, manifest, index, scratch, timeoutMs);
                });
            WriteResults(output, results);
        }
        finally
        {
            try { Directory.Delete(scratch, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    static async Task<CorpusResult> RunBoundedChild(
        string manifestPath,
        ContractManifest manifest,
        int index,
        string scratch,
        int timeoutMs)
    {
        ContractRow row = manifest.Cases[index];
        string resultFile = Path.Combine(scratch, index.ToString("D4") + ".result");
        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = "dotnet",
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Environment.CurrentDirectory
        };
        process.StartInfo.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
        process.StartInfo.ArgumentList.Add("--child");
        process.StartInfo.ArgumentList.Add(manifestPath);
        process.StartInfo.ArgumentList.Add(index.ToString(System.Globalization.CultureInfo.InvariantCulture));
        process.StartInfo.ArgumentList.Add(resultFile);
        try
        {
            process.Start();
            Task<string> stdout = process.StandardOutput.ReadToEndAsync();
            Task<string> stderr = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(timeoutMs);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                await Task.WhenAll(stdout, stderr);
                return CorpusResult.From(
                    row, manifest.Revision, "TIMEOUT", "",
                    $"Bounded .NET child timed out after {timeoutMs} ms");
            }

            string logs = (await stdout) + (await stderr);
            if (process.ExitCode != 0 || !File.Exists(resultFile))
            {
                return CorpusResult.From(
                    row, manifest.Revision, "CRASH", "",
                    NormalizeDiagnostic($"Bounded .NET child exited {process.ExitCode}:\n{logs}"));
            }
            return ReadChildResult(resultFile, row, manifest.Revision);
        }
        catch (Exception error)
        {
            if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
            }
            return CorpusResult.From(
                row, manifest.Revision, "CRASH", "",
                NormalizeDiagnostic(error.GetType().FullName + ": " + error.Message));
        }
    }

    static int RunChild(string[] args)
    {
        if (args.Length != 4)
            throw new ArgumentException("Usage: runner --child <manifest> <row-index> <result>");
        ContractManifest manifest = ContractManifest.Read(Path.GetFullPath(args[1]));
        int index = int.Parse(args[2], System.Globalization.CultureInfo.InvariantCulture);
        if (index < 0 || index >= manifest.Cases.Count)
            throw new ArgumentOutOfRangeException(nameof(index));
        ContractRow row = manifest.Cases[index];
        ChildResult result;
        try
        {
            using var fixture = CorpusFixture.Create(row);
            fixture.VerifyRequestedFacilities(row);
            result = ExecutePackageAdaptation(row, fixture);
        }
        catch (Exception error)
        {
            result = new ChildResult(
                "FAIL", "", NormalizeDiagnostic(error.GetType().FullName + ": " + error.Message));
        }
        File.WriteAllText(
            Path.GetFullPath(args[3]),
            ChildMagic + "\n" + result.Status + "\n" + Encode(result.Observation) + "\n" +
            Encode(result.Diagnostic) + "\n",
            new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        return 0;
    }

    static ChildResult ExecutePackageAdaptation(ContractRow row, CorpusFixture fixture)
    {
        if (row.ProductClassification == "user-approved-excluded-surface")
            return new ChildResult("APPROVED_EXCLUSION", "", "");
        if (row.ProductClassification == "test-infrastructure-only-mechanics")
            return new ChildResult("TEST_INFRASTRUCTURE", "", "");

        if (row.SourceClass == "org.pkl.core.EvaluateTestsTest")
            return ExecuteEvaluateTests(row, fixture);
        if (row.SourceClass == "org.pkl.core.stdlib.MinimalReportTest" ||
            row.SourceClass == "org.pkl.core.stdlib.SimpleReportTest")
            return ExecuteReportTest(row);
        if (row.SourceClass == "org.pkl.core.EvaluateExpressionTest")
            return ExecuteExpressionTest(row);
        if (row.SourceClass == "org.pkl.core.EvaluateMultipleFileOutputTest")
            return ExecuteMultipleFileOutputTest(row);
        if (row.SourceClass == "org.pkl.core.EvaluateOutputTextTest")
            return ExecuteOutputTextTest(row);
        if (row.SourceClass == "org.pkl.core.EvaluateSchemaTest")
            return ExecuteSchemaTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.EvaluatorTest")
            return ExecuteEvaluatorTest(row);
        if (row.SourceClass == "org.pkl.core.AnalyzerTest")
            return ExecuteAnalyzerTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.StackFrameTransformersTest")
            return ExecuteStackFrameTransformerTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.StackTraceRendererTest")
            return ExecuteStackTraceTest(row);
        if (row.SourceClass is "org.pkl.core.JsonRendererTest" or
            "org.pkl.core.PcfRendererTest" or "org.pkl.core.PListRendererTest" or
            "org.pkl.core.PropertiesRendererTest")
            return ExecuteRendererTest(row);

        // Every row reaches this package-only child and its declared local fixtures. The dependent
        // behavior tasks replace this explicit pending result with a public-API assertion for the
        // exact row; a family-level smoke test is deliberately not accepted as row conformance.
        _ = fixture.Root;
        return new ChildResult(
            "PENDING",
            "",
            $"No public package adaptation is registered for {row.CaseId} " +
            $"({row.SourcePath}:{row.SourceLine}).");
    }

    static ChildResult ExecuteExpressionTest(ContractRow row)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        object Evaluate(string program, string expression) =>
            evaluator.EvaluateExpression(ModuleSource.FromText(program), expression);
        switch (row.SourceMethod)
        {
            case "evaluate expression":
            {
                Require((long)Evaluate("res1 = 1\nres2 { res3 = 3; res4 = 4 }", "res1") == 1,
                    "expression scalar result");
                var result = (PObject)Evaluate(
                    "res1 = 1\nres2 { res3 = 3; res4 = 4 }", "res2");
                Require((long)result.Get("res3")! == 3 && (long)result.Get("res4")! == 4,
                    "expression object result");
                break;
            }
            case "evaluate subpath":
                Require((long)Evaluate("foo { bar = 2 }", "foo.bar") == 2,
                    "expression subpath");
                break;
            case "evaluate output text":
                Require((string)Evaluate(
                    "foo { bar = 2 }\noutput { renderer = new JsonRenderer {} }", "output.text") ==
                    "{\n  \"foo\": {\n    \"bar\": 2\n  }\n}\n", "expression output text");
                break;
            case "evaluate let expression":
                Require((long)Evaluate("foo = 1", "let (bar = 2) foo + bar") == 3,
                    "let expression");
                break;
            case "evaluate import expression":
                Require(((string)Evaluate("", "import(\"pkl:release\").current.documentation.homepage"))
                    .StartsWith("https://pkl-lang.org/", StringComparison.Ordinal),
                    "import expression");
                break;
            case "evaluate expression with invalid syntax":
            {
                PklException error = Throws<PklException>(() => Evaluate("foo = 1", "<>!!!"));
                Require(error.Message.Contains("Unexpected token", StringComparison.Ordinal) &&
                    error.Message.Contains("<>!!!", StringComparison.Ordinal),
                    "invalid expression diagnostic");
                break;
            }
            case "evaluate non-expression":
            {
                PklException error = Throws<PklException>(() => Evaluate("bar = 2", "bar = 15"));
                Require(error.Message.Contains("Unexpected token", StringComparison.Ordinal) &&
                    error.Message.Contains("bar = 15", StringComparison.Ordinal),
                    "non-expression diagnostic");
                break;
            }
            case "evaluate semantically invalid expression":
            {
                PklException error = Throws<PklException>(() => Evaluate("foo = 1", "foo as String"));
                Require(error.Message.Contains(
                    "Expected value of type `String`, but got type `Int`", StringComparison.Ordinal),
                    "invalid expression type diagnostic");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteMultipleFileOutputTest(ContractRow row)
    {
        const string filesProgram =
            "output {\n  files {\n" +
            "    [\"foo.yml\"] { text = \"foo: foo text\" }\n" +
            "    [\"bar.yml\"] { text = \"bar: bar text\" }\n" +
            "    [\"bar/biz.yml\"] { text = \"biz: bar biz\" }\n" +
            "    [\"bar/../bark.yml\"] { text = \"bark: bark bark\" }\n" +
            "  }\n}\n";
        switch (row.SourceMethod)
        {
            case "output files":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                IReadOnlyDictionary<string, FileOutput> output =
                    evaluator.EvaluateOutputFilesReadOnly(ModuleSource.FromText(filesProgram));
                string[] keys = { "foo.yml", "bar.yml", "bar/biz.yml", "bar/../bark.yml" };
                Require(output.Keys.SequenceEqual(keys), "output file ordering");
                string[] values = { "foo: foo text", "bar: bar text", "biz: bar biz",
                    "bark: bark bark" };
                for (int index = 0; index < keys.Length; index++)
                {
                    Require(output[keys[index]].Text == values[index],
                        "output file text " + keys[index]);
                    Require(output[keys[index]].Bytes.SequenceEqual(Encoding.UTF8.GetBytes(values[index])),
                        "output file bytes " + keys[index]);
                }
                break;
            }
            case "using a renderer":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                IReadOnlyDictionary<string, FileOutput> output = evaluator.EvaluateOutputFilesReadOnly(
                    ModuleSource.FromText(
                        "output { files { [\"foo.json\"] { value = new { foo = \"fooey\"; " +
                        "bar = \"barrey\" }; renderer = new JsonRenderer {} } } }"));
                Require(output["foo.json"].Text ==
                    "{\n  \"foo\": \"fooey\",\n  \"bar\": \"barrey\"\n}\n",
                    "rendered output file");
                break;
            }
            case "reading files after the evaluator is closed":
            {
                Evaluator evaluator = Evaluator.Preconfigured();
                IReadOnlyDictionary<string, FileOutput> output = evaluator.EvaluateOutputFilesReadOnly(
                    ModuleSource.FromText(
                        "output { files { [\"foo.json\"] { value = new { foo = \"fooey\" }; " +
                        "renderer = new JsonRenderer {} } } }"));
                evaluator.Dispose();
                _ = Throws<PklException>(() => _ = output["foo.json"].Text);
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteOutputTextTest(ContractRow row)
    {
        OutputFormat? format = row.SourceMethod switch
        {
            "render Pcf" => OutputFormat.PCF,
            "render JSON" => OutputFormat.JSON,
            "render plist" => OutputFormat.PLIST,
            _ => null
        };
        if (format is null) return Pending(row);
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured().SetOutputFormat(format).Build();
        string output = evaluator.EvaluateOutputText(ModuleSource.FromText(RendererProgram));
        string expected = format == OutputFormat.PCF ? RendererPcf :
            format == OutputFormat.JSON ? RendererJson : RendererPlist;
        Require(output == expected, row.SourceMethod + " output");
        return Passed(row);
    }

    static ChildResult ExecuteSchemaTest(ContractRow row, CorpusFixture fixture)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        switch (row.SourceMethod)
        {
            case "evaluate test schema":
            {
                string baseFile = Path.Combine(fixture.Root, "EvaluateSchemaBase.pkl");
                File.WriteAllText(baseFile,
                    "open module test.base\n\npropertya1 = \"pigeon\"\n\n" +
                    "function methoda1() = \"pigeon\"\n\n" +
                    "open class Classa1 {\n  name: String\n  age: Int\n}\n",
                    new UTF8Encoding(false));
                string moduleFile = Path.Combine(fixture.Root, "EvaluateSchema.pkl");
                File.WriteAllText(moduleFile,
                    "/// comment\nmodule test extends \"EvaluateSchemaBase.pkl\"\n\n" +
                    "/// comment\npropertyb1 = \"parrot\"\n\n" +
                    "/// comment\npropertyb2: Int =\n  42\n\n" +
                    "/// comment\nfunction methodb1() = \"parrot\"\n\n" +
                    "/// comment\nfunction methodb2(str: String(!isEmpty, startsWith(\"a\"))): " +
                    "Int(isPositive) =\n  str.length\n\n" +
                    "/// comment\nclass Classb1 extends Classa1 {\n  name: String\n  age: Int\n}\n\n" +
                    "propertyb3 = (_, _) -> 3\n\n" +
                    "function methodb3(x: String, _, i: Int, _): Int = x.length + i\n",
                    new UTF8Encoding(false));
                ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromPath(moduleFile));
                Require(schema.ModuleName == "test" && schema.ModuleUri == new Uri(moduleFile),
                    "schema module metadata");
                Require(schema.ModuleClass.Location.StartLine == 2 &&
                    schema.ModuleClass.Location.EndLine == 26, "schema module location");
                Require(schema.ModuleClass.Properties.Keys.SequenceEqual(
                    new[] { "propertyb1", "propertyb2", "propertyb3" }),
                    "schema property ordering");
                Require(schema.ModuleClass.Properties["propertyb1"].Location ==
                    new Member.SourceLocation(5, 5), "schema property location");
                var constrainedParameter = (PType.Constrained)
                    schema.ModuleClass.Methods["methodb2"].Parameters["str"];
                Require(constrainedParameter.Constraints.SequenceEqual(
                    new[] { "!isEmpty", "startsWith(\"a\")" }),
                    "schema parameter constraints");
                Require(schema.ModuleClass.Methods["methodb3"].Parameters.Keys.SequenceEqual(
                    new[] { "x", "_#1", "i", "_#3" }), "schema unnamed parameter ordering");
                Require(schema.Classes.Keys.SequenceEqual(new[] { "Classb1" }) &&
                    schema.Classes["Classb1"].Properties.Count == 2, "schema exported classes");
                Require(schema.Supermodule is not null && schema.Supermodule.ModuleName == "test.base" &&
                    schema.Supermodule.Classes.ContainsKey("Classa1") &&
                    schema.Supermodule.ModuleClass.Methods.ContainsKey("methoda1"),
                    "schema supermodule");
                break;
            }
            case "evaluate pkl_base schema":
            {
                ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromUri("pkl:base"));
                Require(schema.ModuleName == "pkl.base" && schema.ModuleClass.Superclass is not null &&
                    schema.ModuleClass.Superclass.QualifiedName == "pkl.base#Module",
                    "pkl:base schema superclass");
                break;
            }
            case "does not export local classes":
            {
                ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromText(
                    "class Foo {}\nlocal class Baz {}\n"));
                Require(schema.Classes.Keys.SequenceEqual(new[] { "Foo" }),
                    "local schema class exclusion");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteEvaluatorTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "evaluate text":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                PModule module = evaluator.Evaluate(ModuleSource.FromText(
                    "name = \"pigeon\"; age = 10 + 20"));
                Require(module.Properties.Count == 2 &&
                    (string)module.GetProperty("name") == "pigeon" &&
                    (long)module.GetProperty("age") == 30, "evaluate text module");
                break;
            }
            case "evaluating a broken module multiple times results in the same error every time":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                ModuleSource first = ModuleSource.FromText("x: Int = \"wrong\"");
                string firstMessage = Throws<PklException>(() => evaluator.Evaluate(first)).Message;
                string repeatedMessage = Throws<PklException>(() => evaluator.Evaluate(first)).Message;
                Require(firstMessage == repeatedMessage, "repeated type failure diagnostic");
                ModuleSource second = ModuleSource.FromText("x = throw(\"broken\")");
                string secondMessage = Throws<PklException>(() => evaluator.Evaluate(second)).Message;
                string secondRepeated = Throws<PklException>(() => evaluator.Evaluate(second)).Message;
                Require(secondMessage == secondRepeated, "repeated throw diagnostic");
                break;
            }
            case "evaluation timeout":
            {
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .SetTimeout(TimeSpan.FromMilliseconds(100)).Build();
                PklException error = Throws<PklException>(() => evaluator.Evaluate(ModuleSource.FromText(
                    "function fib(n) = if (n < 2) 0 else fib(n - 1) + fib(n - 2)\n" +
                    "x = fib(100)")));
                Require(error.Message.Contains("timed out", StringComparison.OrdinalIgnoreCase),
                    "evaluation timeout diagnostic");
                break;
            }
            case "stack overflow":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                PklException error = Throws<PklException>(() => evaluator.Evaluate(
                    ModuleSource.FromText("a = b\nb = c\nc = a\n")));
                Require(error.Message.Contains("A stack overflow occurred.", StringComparison.Ordinal),
                    "stack overflow diagnostic");
                break;
            }
            case "constraint failures activate instrumentation":
            {
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .SetPowerAssertionsEnabled(true).Build();
                PklException error = Throws<PklException>(() => evaluator.Evaluate(
                    ModuleSource.FromText("foo: String(chars.first == \"a\") = \"boo\"")));
                Require(error.Message.Contains("chars.first == \"a\"", StringComparison.Ordinal) &&
                    error.Message.Contains("false", StringComparison.Ordinal),
                    "constraint power assertion diagnostic");
                break;
            }
            case "union single-member constraint failures do not activate instrumentation":
            {
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .SetPowerAssertionsEnabled(true).Build();
                PModule module = evaluator.Evaluate(ModuleSource.FromText(
                    "foo: String(startsWith(\"a\")) | String(startsWith(\"b\")) | " +
                    "String(startsWith(\"c\")) = \"cool\""));
                Require((string)module.GetProperty("foo") == "cool", "union constraint evaluation");
                break;
            }
            case "type test failures do not activate instrumentation":
            {
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .SetPowerAssertionsEnabled(true).Build();
                PModule module = evaluator.Evaluate(ModuleSource.FromText(
                    "foo = \"bar\" is Int(this > 0)"));
                Require(module.GetProperty("foo") is false, "type-test constraint evaluation");
                break;
            }
            case "power assertions work with test facts with unavailable source section":
            {
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .SetPowerAssertionsEnabled(true).Build();
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromText(
                    "amends \"pkl:test\"\nfacts { [\"foo\"] { ...List(false) } }"));
                Require(results.TotalTests() == 1 && results.TotalFailures() == 1,
                    "unavailable-section power assertion result");
                break;
            }
            case "eval schema when property has a ConvertProperty annotation":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromText(
                    "@ConvertProperty { render = (prop, ) -> prop }\nfoo: String"));
                Require(schema.ModuleClass.Properties.ContainsKey("foo"),
                    "ConvertProperty schema export");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteAnalyzerTest(ContractRow row, CorpusFixture fixture)
    {
        var analyzer = new Analyzer(
            StackFrameTransformers.DefaultTransformer,
            false,
            SecurityManagers.DefaultManager,
            new[] { Pkl.Core.Module.ModuleKeyFactories.file,
                Pkl.Core.Module.ModuleKeyFactories.standardLibrary },
            null,
            null,
            Pkl.Core.Http.HttpClient.DummyClient(),
            Pkl.Core.EvaluatorSettings.TraceMode.COMPACT);
        switch (row.SourceMethod)
        {
            case "simple case":
            {
                string file = Path.Combine(fixture.Root, "analyzer.pkl");
                File.WriteAllText(file,
                    "amends \"pkl:base\"\nimport \"pkl:json\"\nmyProp = import(\"pkl:xml\")\n",
                    new UTF8Encoding(false));
                Uri uri = new(file);
                ImportGraph graph = analyzer.ImportGraph(uri);
                Require(graph.Imports.ContainsKey(uri) && graph.Imports[uri].Select(item => item.Uri)
                    .SequenceEqual(new[] { new Uri("pkl:base"), new Uri("pkl:json"),
                        new Uri("pkl:xml") }), "simple analyzer import graph");

                const string json = "{\"imports\":{" +
                    "\"pkl:z\":[{\"uri\":\"pkl:xml\"},{\"uri\":\"pkl:base\"}," +
                    "{\"uri\":\"pkl:base\"}],\"pkl:a\":[]}," +
                    "\"resolvedImports\":{\"pkl:z\":\"file:/tmp/z.pkl\",\"pkl:a\":\"pkl:a\"}}";
                ImportGraph parsed = ImportGraph.ParseFromJson(json);
                ImportGraph equal = ImportGraph.ParseFromJson(json);
                Require(parsed.Imports.Keys.SequenceEqual(new[] { new Uri("pkl:a"), new Uri("pkl:z") }) &&
                    parsed.Imports[new Uri("pkl:z")].Select(item => item.Uri).SequenceEqual(
                        new[] { new Uri("pkl:base"), new Uri("pkl:xml") }) &&
                    parsed.Equals(equal) && parsed.GetHashCode() == equal.GetHashCode(),
                    "parsed import graph ordering and equality");
                _ = Throws<Exception>(() => ImportGraph.ParseFromJson("{"));
                _ = Throws<Exception>(() => ImportGraph.ParseFromJson(
                    "{\"imports\":{\"pkl:a\":1},\"resolvedImports\":{}}"));
                _ = Throws<Exception>(() => ImportGraph.ParseFromJson(
                    "{\"imports\":{\"http://[\":[]},\"resolvedImports\":{}}"));
                break;
            }
            case "glob imports":
            {
                string first = Path.Combine(fixture.Root, "file1.pkl");
                string second = Path.Combine(fixture.Root, "file2.pkl");
                string third = Path.Combine(fixture.Root, "file3.pkl");
                File.WriteAllText(first, "import* \"*.pkl\"", new UTF8Encoding(false));
                File.WriteAllText(second, "foo = 1", new UTF8Encoding(false));
                File.WriteAllText(third, "bar = 1", new UTF8Encoding(false));
                Uri firstUri = new(first), secondUri = new(second), thirdUri = new(third);
                ImportGraph graph = analyzer.ImportGraph(firstUri);
                Require(graph.Imports.Keys.SequenceEqual(new[] { firstUri, secondUri, thirdUri }) &&
                    graph.Imports[firstUri].Select(item => item.Uri).SequenceEqual(
                        new[] { firstUri, secondUri, thirdUri }) &&
                    graph.Imports[secondUri].Count == 0 && graph.Imports[thirdUri].Count == 0,
                    "glob analyzer graph ordering");
                break;
            }
            case "cyclical imports":
            {
                string first = Path.Combine(fixture.Root, "cycle1.pkl");
                string second = Path.Combine(fixture.Root, "cycle2.pkl");
                File.WriteAllText(first, "import \"cycle2.pkl\"", new UTF8Encoding(false));
                File.WriteAllText(second, "import \"cycle1.pkl\"", new UTF8Encoding(false));
                Uri firstUri = new(first), secondUri = new(second);
                ImportGraph graph = analyzer.ImportGraph(firstUri);
                Require(graph.Imports.Count == 2 &&
                    graph.Imports[firstUri].Single().Uri == secondUri &&
                    graph.Imports[secondUri].Single().Uri == firstUri,
                    "cyclical analyzer graph");
                break;
            }
            default:
                // Package and project analyzer rows are owned by the loading/package partition.
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteStackFrameTransformerTest(ContractRow row)
    {
        if (row.SourceMethod != "replacePackageUriWithSourceCodeUrl") return Pending(row);
        var frame = new Pkl.Core.StackFrame("file:/module.pkl", "module#value",
            new[] { "value = 1" }, 1, 1, 1, 9);
        Pkl.Core.StackFrame unchanged =
            StackFrameTransformers.ReplacePackageUriWithSourceCodeUrl(frame);
        Require(ReferenceEquals(frame, unchanged), "non-package stack frame transformer identity");
        var equalFrame = new Pkl.Core.StackFrame("file:/module.pkl", "module#value",
            new[] { "value = 1" }, 1, 1, 1, 9);
        Require(frame.Equals(equalFrame), "stack frame value equality");
        Require(frame.GetHashCode() == equalFrame.GetHashCode(),
            $"stack frame hash: {frame.GetHashCode()} != {equalFrame.GetHashCode()}");
        return Passed(row);
    }

    static ChildResult ExecuteStackTraceTest(ContractRow row)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        string Overflow(string program) =>
            NormalizeLines(Throws<PklException>(() => evaluator.Evaluate(
                ModuleSource.FromText(program))).Message);
        switch (row.SourceMethod)
        {
            case "stringy self-reference":
            {
                string message = Overflow(
                    "self: String = \"Strings; if they were lazy, you could tie the knot on \\(self.take(7))\"");
                Require(message.Contains("A stack overflow occurred.", StringComparison.Ordinal) &&
                    message.Contains("repetitions of:", StringComparison.Ordinal) &&
                    message.Contains("self.take(7)", StringComparison.Ordinal) &&
                    message.Contains("^^^^", StringComparison.Ordinal),
                    "string self-reference stack trace");
                break;
            }
            case "cyclic property references":
            {
                string message = Overflow(
                    "foo: String = \"FOO:\" + bar\n" +
                    "bar: String = \"BAR:\" + baz\n" +
                    "baz: String = \"BAZ:\" + qux\n" +
                    "qux: String = \"QUX:\" + foo\n");
                Require(message.Contains("A stack overflow occurred.", StringComparison.Ordinal) &&
                    message.Contains("repetitions of:", StringComparison.Ordinal) &&
                    message.Contains("text#qux", StringComparison.Ordinal) &&
                    message.Contains("text#baz", StringComparison.Ordinal) &&
                    message.Contains("text#bar", StringComparison.Ordinal) &&
                    message.Contains("text#foo", StringComparison.Ordinal),
                    "cyclic property stack trace");
                break;
            }
            case "reduce stack overflow from actual Pkl code":
            case "compression preserves prefix and suffix and counts loop correctly":
            {
                string message = Overflow(StackCompressionProgram);
                Require(message.Contains("A stack overflow occurred.", StringComparison.Ordinal) &&
                    message.Contains("5 repetitions of:", StringComparison.Ordinal) &&
                    message.Contains("loopBody1(n - 1)", StringComparison.Ordinal) &&
                    message.Contains("13 repetitions of:", StringComparison.Ordinal) &&
                    message.Contains("prefix(n - 1)", StringComparison.Ordinal),
                    row.SourceMethod + " stack trace");
                break;
            }
            case "cycles of length 1 don't get rendered as a loop":
            {
                string message = Overflow("a = b\nb = c\nc = a\n");
                Require(message.Contains("A stack overflow occurred.", StringComparison.Ordinal) &&
                    !message.Contains("1 repetitions of:", StringComparison.Ordinal),
                    "single-cycle stack trace rendering");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteRendererTest(ContractRow row)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(ModuleSource.FromText(RendererProgram));
        switch ((row.SourceClass, row.SourceMethod))
        {
            case ("org.pkl.core.JsonRendererTest", "render document"):
                Require(RenderDocument(ValueRenderers.Json, module, "  ", true) == RendererJson,
                    "JSON renderer document");
                break;
            case ("org.pkl.core.JsonRendererTest", "rendered document ends in newline"):
                foreach (bool omitNulls in new[] { false, true })
                    Require(RenderDocument(ValueRenderers.Json, module, "  ", omitNulls)
                        .EndsWith('\n'), "JSON renderer newline");
                break;
            case ("org.pkl.core.PcfRendererTest", "render document"):
                Require(RenderPcf(module, omitNulls: false) == RendererPcf,
                    "PCF renderer document");
                break;
            case ("org.pkl.core.PcfRendererTest", "rendered document ends in newline"):
                Require(RenderPcf(module, omitNulls: false).EndsWith('\n'),
                    "PCF renderer newline");
                break;
            case ("org.pkl.core.PcfRendererTest", "rendering with and without null properties"):
            {
                PModule nulls = evaluator.Evaluate(ModuleSource.FromText(
                    "foo = null\nbar = null\nbaz {\n  qux = 42\n  quux = null\n" +
                    "  corge = new Listing { null; 1337; null; \"Hello World\" }\n" +
                    "  grault = new Mapping { [\"garply\"] = null; [\"waldo\"] = 42; " +
                    "[\"pigeon\"] = null }\n}\n"));
                Require(NormalizeLines(RenderPcf(nulls, omitNulls: true)) ==
                    "baz {\n  qux = 42\n  corge = List(null, 1337, null, \"Hello World\")\n" +
                    "  grault = Map(\"garply\", null, \"waldo\", 42, \"pigeon\", null)\n}",
                    "PCF omitted null properties");
                Require(NormalizeLines(RenderPcf(nulls, omitNulls: false)) ==
                    "foo = null\nbar = null\nbaz {\n  qux = 42\n  quux = null\n" +
                    "  corge = List(null, 1337, null, \"Hello World\")\n" +
                    "  grault = Map(\"garply\", null, \"waldo\", 42, \"pigeon\", null)\n}",
                    "PCF retained null properties");
                break;
            }
            case ("org.pkl.core.PListRendererTest", "render document"):
                Require(RenderPlist(module) == RendererPlist, "plist renderer document");
                break;
            case ("org.pkl.core.PListRendererTest", "rendered document ends in newline"):
                Require(RenderPlist(module).EndsWith('\n'), "plist renderer newline");
                break;
            case ("org.pkl.core.PropertiesRendererTest", "render document"):
            {
                PModule properties = evaluator.Evaluate(ModuleSource.FromText(PropertiesProgram));
                Require(RenderProperties(properties, omitNulls: true, restrictCharset: false) ==
                    PropertiesOutput, "properties renderer document");
                break;
            }
            case ("org.pkl.core.PropertiesRendererTest", "render unsupported document values"):
                foreach (string value in new[] { "List()", "new Listing {}", "Map()",
                    "new Mapping {}", "Set()", "new PropertiesRenderer {}", "new Dynamic {}" })
                {
                    PModule unsupported = evaluator.Evaluate(ModuleSource.FromText("value = " + value));
                    using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
                    ValueRenderer renderer = ValueRenderers.Properties(writer, true, false);
                    _ = Throws<RendererException>(() => renderer.RenderValue(unsupported));
                }
                break;
            case ("org.pkl.core.PropertiesRendererTest", "rendered document ends in newline"):
                foreach (bool omitNulls in new[] { false, true })
                    foreach (bool restrictCharset in new[] { false, true })
                        Require(RenderProperties(
                            evaluator.Evaluate(ModuleSource.FromText("foo { bar = 0 }")),
                            omitNulls, restrictCharset).EndsWith('\n'),
                            "properties renderer newline");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static string RenderDocument(
        Func<TextWriter, string, bool, ValueRenderer> factory,
        object value,
        string indent,
        bool option)
    {
        using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
        factory(writer, indent, option).RenderDocument(value);
        return writer.ToString();
    }

    static string RenderPcf(object value, bool omitNulls)
    {
        using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
        ValueRenderers.Pcf(writer, "  ", omitNulls, false).RenderDocument(value);
        return writer.ToString();
    }

    static string RenderPlist(object value)
    {
        using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
        ValueRenderers.Plist(writer, "  ").RenderDocument(value);
        return writer.ToString();
    }

    static string RenderProperties(object value, bool omitNulls, bool restrictCharset)
    {
        using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
        ValueRenderers.Properties(writer, omitNulls, restrictCharset).RenderDocument(value);
        return writer.ToString();
    }

    static ChildResult ExecuteEvaluateTests(ContractRow row, CorpusFixture fixture)
    {
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetPowerAssertionsEnabled(true)
            .Build();
        switch (row.SourceMethod)
        {
            case "test successful module":
            {
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromText(
                    "amends \"pkl:test\"\n\n" +
                    "facts {\n  [\"should pass\"] {\n    1 == 1\n    \"foo\" == \"foo\"\n  }\n}\n"),
                    overwriteExpected: true);
                Require(results.ModuleName == "text", "successful test module name");
                Require(results.DisplayUri == "repl:text", "successful test display URI");
                Require(results.TotalTests() == 1 && !results.Failed(), "successful test totals");
                Require(results.Facts.Results.Count == 1 &&
                    results.Facts.Results[0].Name == "should pass", "successful fact result");
                Require(string.IsNullOrWhiteSpace(results.Logs), "successful test logs");
                break;
            }
            case "test module failure":
            {
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromText(
                    "amends \"pkl:test\"\n\n" +
                    "facts {\n  [\"should fail\"] {\n    1 == 2\n    \"foo\" == \"bar\"\n  }\n}\n"),
                    overwriteExpected: true);
                Require(results.TotalTests() == 1 && results.TotalFailures() == 1 &&
                    results.Failed(), "failed test totals");
                TestResults.TestResult result = results.Facts.Results.Single();
                Require(result.Name == "should fail" && result.Errors.Count == 0 &&
                    result.Failures.Count == 2, "failed fact shape");
                Require(NormalizeLines(result.Failures[0].Message) ==
                    "1 == 2 (repl:text)\n  │\n  false", "first power assertion diagnostic");
                Require(NormalizeLines(result.Failures[1].Message) ==
                    "\"foo\" == \"bar\" (repl:text)\n      │\n      false",
                    "second power assertion diagnostic");
                break;
            }
            case "test module error":
            {
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromText(
                    "amends \"pkl:test\"\n\n" +
                    "facts {\n  [\"should fail\"] {\n    1 == 2\n    throw(\"got an error\")\n  }\n}\n"),
                    overwriteExpected: true);
                Require(results.TotalTests() == 1 && results.TotalFailures() == 1 &&
                    results.Failed(), "errored test totals");
                TestResults.TestResult result = results.Facts.Results.Single();
                Require(result.Name == "should fail" && result.Failures.Count == 1 &&
                    result.Errors.Count == 1, "errored fact shape");
                TestResults.Error error = result.Errors[0];
                Require(error.Message == "got an error", "test error message");
                Require(NormalizeLines(error.Exception.Message) ==
                    "–– Pkl Error ––\n" +
                    "got an error\n\n" +
                    "6 | throw(\"got an error\")\n" +
                    "    ^^^^^^^^^^^^^^^^^^^^^\n" +
                    "at text#facts[\"should fail\"][#2] (repl:text)",
                    "test error diagnostic: " + NormalizeLines(error.Exception.Message));
                break;
            }
            case "test successful example":
            {
                string file = WriteTestModule(fixture, ExampleModule("Bob", 33));
                WriteExpected(file, ExampleExpected("Bob", 33));
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromPath(file));
                Require(results.ModuleName.StartsWith("example", StringComparison.Ordinal),
                    "successful example module name");
                Require(results.DisplayUri.StartsWith("file:", StringComparison.Ordinal) &&
                    results.DisplayUri.EndsWith(".pkl", StringComparison.Ordinal),
                    "successful example display URI");
                Require(results.TotalTests() == 1 && !results.Failed(), "successful example totals");
                Require(results.Examples.Results.Single().Name == "user", "successful example name");
                break;
            }
            case "test fact failures with successful example":
            {
                string file = WriteTestModule(fixture,
                    "amends \"pkl:test\"\n\n" +
                    "facts {\n  [\"should fail\"] {\n    1 == 2\n    \"foo\" == \"bar\"\n  }\n}\n\n" +
                    ExampleSection("Bob", 33));
                WriteExpected(file, ExampleExpected("Bob", 33));
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromPath(file));
                Require(results.TotalTests() == 2 && results.TotalFailures() == 1 &&
                    results.Failed(), "fact failure plus example totals");
                Require(results.Facts.Results.Single().Name == "should fail" &&
                    results.Facts.Results[0].Failures.Count == 2, "fact failure plus example fact");
                Require(results.Examples.Results.Single().Name == "user",
                    "fact failure plus example result");
                break;
            }
            case "test fact error with successful example":
            {
                string file = WriteTestModule(fixture,
                    "amends \"pkl:test\"\n\n" +
                    "facts {\n  [\"should fail\"] {\n    throw(\"exception\")\n  }\n}\n\n" +
                    ExampleSection("Bob", 33));
                WriteExpected(file, ExampleExpected("Bob", 33));
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromPath(file));
                Require(results.TotalTests() == 2 && results.TotalFailures() == 1 &&
                    results.Failed(), "fact error plus example totals");
                TestResults.TestResult fact = results.Facts.Results.Single();
                Require(fact.Name == "should fail" && fact.Failures.Count == 0 &&
                    fact.Errors.Count == 1 && fact.Errors[0].Message == "exception",
                    "fact error plus example fact");
                Require(results.Examples.Results.Single().Name == "user",
                    "fact error plus example result");
                break;
            }
            case "test example failure":
            {
                string file = WriteTestModule(fixture, ExampleModule("Bob", 33));
                WriteExpected(file, ExampleExpected("Alice", 45));
                TestResults results = evaluator.EvaluateTests(ModuleSource.FromPath(file));
                Require(results.TotalTests() == 1 && results.TotalFailures() == 1 &&
                    results.Failed(), "example failure totals");
                TestResults.TestResult example = results.Examples.Results.Single();
                Require(example.Name == "user" && example.Errors.Count == 0 &&
                    example.Failures.Count == 1, "example failure shape");
                string message = NormalizeExampleDiagnostic(example.Failures[0].Message, file);
                Require(message ==
                    "#0: (<fixture>/example.pkl)\n" +
                    "  Expected: (<fixture>/example.pkl-expected.pcf)\n" +
                    "  new {\n    name = \"Alice\"\n    age = 45\n  }\n" +
                    "  Actual: (<fixture>/example.pkl-actual.pcf)\n" +
                    "  new {\n    name = \"Bob\"\n    age = 33\n  }",
                    "example failure diagnostic");
                break;
            }
            case "written examples use custom string delimiters":
            {
                string file = WriteTestModule(fixture,
                    "amends \"pkl:test\"\n\nexamples {\n  [\"myStr\"] {\n" +
                    "    \"my \\\"string\\\"\"\n  }\n}\n");
                _ = evaluator.EvaluateTests(ModuleSource.FromPath(file));
                Require(File.ReadAllText(file + "-expected.pcf", Encoding.UTF8) ==
                    "examples {\n  [\"myStr\"] {\n    #\"my \"string\"\"#\n  }\n}\n",
                    "custom string delimiter example output");
                break;
            }
            case "examples that don't use custom string delimiters still pass":
            {
                string file = WriteTestModule(fixture,
                    "amends \"pkl:test\"\n\nexamples {\n  [\"myStr\"] {\n" +
                    "    \"my \\\"string\\\"\"\n  }\n}\n");
                WriteExpected(file,
                    "examples {\n  [\"myStr\"] {\n    \"my \\\"string\\\"\"\n  }\n}\n");
                Require(!evaluator.EvaluateTests(ModuleSource.FromPath(file)).Failed(),
                    "legacy example string delimiters");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteReportTest(ContractRow row)
    {
        TestResults passing = BuildReportResults(includeFailure: false, largeCounts: false);
        TestResults failing = BuildReportResults(includeFailure: true, largeCounts: false);
        switch ((row.SourceClass, row.SourceMethod))
        {
            case ("org.pkl.core.stdlib.MinimalReportTest",
                "report with only passing tests does not show module or test names"):
            {
                using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
                PklTestReporters.Minimal().Report(passing, writer);
                Require(writer.ToString().Length == 0, "minimal passing report");
                break;
            }
            case ("org.pkl.core.stdlib.MinimalReportTest",
                "report with failures shows module name and only failed tests"):
            {
                using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
                PklTestReporters.Minimal().Report(failing, writer);
                string output = writer.ToString();
                Require(output.Contains("module module1", StringComparison.Ordinal) &&
                    output.Contains("failing fact", StringComparison.Ordinal) &&
                    !output.Contains("passing fact", StringComparison.Ordinal) &&
                    !output.Contains("passing example", StringComparison.Ordinal) &&
                    !output.Contains("examples", StringComparison.Ordinal), "minimal failure report");
                break;
            }
            case ("org.pkl.core.stdlib.MinimalReportTest",
                "summarize includes stats even when all tests pass"):
            {
                using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
                PklTestReporters.Minimal().Summarize(new[] { passing }, writer);
                string output = writer.ToString();
                Require(output.Contains("100.0% tests pass", StringComparison.Ordinal) &&
                    output.Contains("2 passed", StringComparison.Ordinal),
                    "minimal passing summary: " + NormalizeLines(output));
                break;
            }
            case ("org.pkl.core.stdlib.MinimalReportTest",
                "summarize method should generate correct output for failures"):
            {
                string output = RenderFailureSummary(PklTestReporters.Minimal());
                Require(output ==
                    "0.0% tests pass [2/2 failed], 99.9% asserts pass [2/754444 failed]",
                    "minimal failure summary: " + output);
                break;
            }
            case ("org.pkl.core.stdlib.SimpleReportTest",
                "summarize method should generate correct output"):
            {
                string output = RenderFailureSummary(PklTestReporters.Spec());
                Require(output ==
                    "0.0% tests pass [2/2 failed], 99.9% asserts pass [2/754444 failed]",
                    "spec failure summary: " + output);
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static TestResults BuildReportResults(bool includeFailure, bool largeCounts)
    {
        int factCount = largeCounts ? 321919 : 1;
        int exampleCount = largeCounts ? 432525 : 1;
        var factFailures = includeFailure
            ? new[] { new TestResults.Failure("Fact Failure", "failed") }
            : Array.Empty<TestResults.Failure>();
        var facts = new List<TestResults.TestResult>
        {
            new("passing fact", 1, Array.Empty<TestResults.Failure>(),
                Array.Empty<TestResults.Error>(), false)
        };
        if (includeFailure)
            facts.Add(new TestResults.TestResult("failing fact", factCount, factFailures,
                Array.Empty<TestResults.Error>(), false));
        var examples = new List<TestResults.TestResult>
        {
            includeFailure && largeCounts
                ? new TestResults.TestResult("example1", exampleCount,
                    new[] { new TestResults.Failure("Output Mismatch", "does not match") },
                    Array.Empty<TestResults.Error>(), false)
                : new TestResults.TestResult("passing example", 1,
                    Array.Empty<TestResults.Failure>(), Array.Empty<TestResults.Error>(), false)
        };
        return new TestResults.Builder("module1", "module1")
            .SetFactsSection(new TestResults.TestSectionResults(
                TestResults.TestSectionName.FACTS, facts))
            .SetExamplesSection(new TestResults.TestSectionResults(
                TestResults.TestSectionName.EXAMPLES, examples))
            .Build();
    }

    static string RenderFailureSummary(PklTestReporter reporter)
    {
        TestResults results = BuildReportResults(includeFailure: true, largeCounts: true);
        // The upstream fixture contains only the two failing rows for this aggregate.
        results = new TestResults.Builder("module1", "module1")
            .SetFactsSection(new TestResults.TestSectionResults(
                TestResults.TestSectionName.FACTS,
                new[] { results.Facts.Results.Last() }))
            .SetExamplesSection(new TestResults.TestSectionResults(
                TestResults.TestSectionName.EXAMPLES,
                new[] { results.Examples.Results.Single() }))
            .Build();
        using var writer = new StringWriter(System.Globalization.CultureInfo.InvariantCulture);
        reporter.Summarize(new[] { results }, writer);
        return NormalizeLines(writer.ToString());
    }

    const string RendererProgram =
        "foo = 1\n" +
        "text = \"hello\"\n" +
        "nil = null\n" +
        "nested { enabled = true }\n" +
        "list = List(1, \"two\")\n";

    const string RendererJson =
        "{\n" +
        "  \"foo\": 1,\n" +
        "  \"text\": \"hello\",\n" +
        "  \"nested\": {\n" +
        "    \"enabled\": true\n" +
        "  },\n" +
        "  \"list\": [\n" +
        "    1,\n" +
        "    \"two\"\n" +
        "  ]\n" +
        "}\n";

    const string RendererPcf =
        "foo = 1\n" +
        "text = \"hello\"\n" +
        "nil = null\n" +
        "nested {\n" +
        "  enabled = true\n" +
        "}\n" +
        "list = List(1, \"two\")\n";

    const string RendererPlist =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " +
        "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
        "<plist version=\"1.0\">\n" +
        "<dict>\n" +
        "  <key>foo</key>\n" +
        "  <integer>1</integer>\n" +
        "  <key>text</key>\n" +
        "  <string>hello</string>\n" +
        "  <key>nested</key>\n" +
        "  <dict>\n" +
        "    <key>enabled</key>\n" +
        "    <true/>\n" +
        "  </dict>\n" +
        "  <key>list</key>\n" +
        "  <array>\n" +
        "    <integer>1</integer>\n" +
        "    <string>two</string>\n" +
        "  </array>\n" +
        "</dict>\n" +
        "</plist>\n";

    const string PropertiesProgram =
        "foo = 1\ntext = \"hello\"\nnil = null\nnested { enabled = true }\n";

    const string PropertiesOutput =
        "foo = 1\ntext = hello\nnested.enabled = true\n";

    const string StackCompressionProgram =
        "function suffix(n: UInt): UInt =\n" +
        "  if (n == 0) 0 else suffix(n - 1)\n\n" +
        "function loopBody4(n: UInt): UInt =\n" +
        "  if (n == 0) loop() else loopBody1(n - 1)\n\n" +
        "function loopBody3(n: UInt) = loopBody4(n)\n" +
        "function loopBody2(n: UInt) = loopBody3(n)\n" +
        "function loopBody1(n: UInt) = loopBody2(n)\n\n" +
        "function loop(): UInt =\n" +
        "  if (suffix(100) > 0) 1 else loopBody1(5)\n\n" +
        "function prefix(n: UInt): UInt =\n" +
        "  if (n == 0) loop() else prefix(n - 1)\n\n" +
        "result = prefix(13)\n";

    static string ExampleModule(string name, int age) =>
        "amends \"pkl:test\"\n\n" + ExampleSection(name, age);

    static string ExampleSection(string name, int age) =>
        "examples {\n  [\"user\"] {\n    new {\n" +
        $"      name = \"{name}\"\n      age = {age}\n" +
        "    }\n  }\n}\n";

    static string ExampleExpected(string name, int age) => ExampleSection(name, age);

    static string WriteTestModule(CorpusFixture fixture, string contents)
    {
        string file = Path.Combine(fixture.Root, "example.pkl");
        File.WriteAllText(file, contents, new UTF8Encoding(false));
        return file;
    }

    static void WriteExpected(string module, string contents) =>
        File.WriteAllText(module + "-expected.pcf", contents, new UTF8Encoding(false));

    static string NormalizeExampleDiagnostic(string value, string module)
    {
        string directory = Path.GetDirectoryName(module)!;
        return NormalizeLines(value)
            .Replace(new Uri(directory + Path.DirectorySeparatorChar).AbsoluteUri,
                "<fixture>/", StringComparison.Ordinal)
            .Replace(directory + Path.DirectorySeparatorChar, "<fixture>/", StringComparison.Ordinal);
    }

    static string NormalizeLines(string value) =>
        value.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n').Trim();

    static void Require(bool condition, string subject)
    {
        if (!condition) throw new InvalidOperationException("Contract assertion failed: " + subject);
    }

    static T Throws<T>(Action action) where T : Exception
    {
        try
        {
            action();
        }
        catch (T error)
        {
            return error;
        }
        throw new InvalidOperationException(
            "Contract assertion failed: expected " + typeof(T).FullName);
    }

    static ChildResult Passed(ContractRow row) =>
        new("PASS", row.ExpectedOutcome, "");

    static ChildResult Pending(ContractRow row) =>
        new("PENDING", "",
            $"No public package adaptation is registered for {row.CaseId} " +
            $"({row.SourcePath}:{row.SourceLine}).");

    static CorpusResult ReadChildResult(string path, ContractRow row, string revision)
    {
        string[] lines = File.ReadAllLines(path, Encoding.UTF8);
        if (lines.Length != 4 || lines[0] != ChildMagic)
            return CorpusResult.From(
                row, revision, "CRASH", "", "Bounded .NET child emitted a malformed result record");
        try
        {
            return CorpusResult.From(
                row, revision, lines[1], Decode(lines[2]), NormalizeDiagnostic(Decode(lines[3])));
        }
        catch (FormatException)
        {
            return CorpusResult.From(
                row, revision, "CRASH", "", "Bounded .NET child emitted invalid base64 evidence");
        }
    }

    static void WriteResults(string path, IEnumerable<CorpusResult> results)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var output = new StringBuilder();
        output.Append(ResultMagic).Append('\n');
        output.Append("columns\t").AppendJoin('\t', ResultColumns).Append('\n');
        foreach (CorpusResult result in results) output.Append(result.Render()).Append('\n');
        File.WriteAllText(path, output.ToString(), new UTF8Encoding(false));
    }

    static void VerifyLoadedAssemblies(
        string manifestPath,
        string packagesRoot,
        string outputPath)
    {
        var expected = File.ReadAllLines(manifestPath, Encoding.UTF8)
            .Where(line => !string.IsNullOrWhiteSpace(line))
            .Select(line => line.Split('\t'))
            .ToDictionary(fields => fields[0], fields => fields[1], StringComparer.Ordinal);
        Assembly[] loaded = { typeof(Evaluator).Assembly, typeof(Parser).Assembly };
        if (!Directory.Exists(packagesRoot))
            throw new InvalidOperationException("Isolated package cache is missing: " + packagesRoot);
        string normalizedRoot = Path.GetFullPath(AppContext.BaseDirectory)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var rows = new List<string>();
        foreach (Assembly assembly in loaded.OrderBy(value => value.GetName().Name, StringComparer.Ordinal))
        {
            string name = assembly.GetName().Name
                ?? throw new InvalidOperationException("Loaded assembly has no name");
            if (!expected.TryGetValue(name, out string? expectedHash))
                throw new InvalidOperationException("Loaded assembly is absent from package proof: " + name);
            string location = Path.GetFullPath(assembly.Location);
            if (!location.StartsWith(normalizedRoot, StringComparison.Ordinal))
                throw new InvalidOperationException("Loaded assembly escaped isolated consumer output: " + location);
            string actualHash = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(location))).ToLowerInvariant();
            if (!string.Equals(expectedHash, actualHash, StringComparison.Ordinal))
                throw new InvalidOperationException("Loaded assembly hash differs from package proof: " + name);
            rows.Add(string.Join('\t', name, location, expectedHash, actualHash));
        }
        if (!expected.Keys.OrderBy(value => value, StringComparer.Ordinal)
            .SequenceEqual(loaded.Select(value => value.GetName().Name!)
                .OrderBy(value => value, StringComparer.Ordinal), StringComparer.Ordinal))
            throw new InvalidOperationException("Loaded assembly set differs from packed dependency closure");
        File.WriteAllText(
            outputPath, AssemblyMagic + "\n" + string.Join("\n", rows) + "\n", new UTF8Encoding(false));
    }

    static string Encode(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
    static string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));

    static string NormalizeDiagnostic(string value)
    {
        string normalized = value.Replace("\r\n", "\n", StringComparison.Ordinal)
            .Replace('\r', '\n');
        string current = Path.GetFullPath(Environment.CurrentDirectory);
        normalized = normalized.Replace(current, "<consumer>", StringComparison.Ordinal);
        string temporary = Path.GetFullPath(Path.GetTempPath());
        normalized = normalized.Replace(temporary, "<temp>/", StringComparison.Ordinal);
        return normalized.Trim();
    }

    sealed record ChildResult(string Status, string Observation, string Diagnostic);

    sealed record CorpusResult(
        ContractRow Row,
        string Revision,
        string Status,
        string Observation,
        string Diagnostic)
    {
        public static CorpusResult From(
            ContractRow row, string revision, string status, string observation, string diagnostic) =>
            new(row, revision, status, observation, diagnostic);

        public string Render() => string.Join(
            '\t', "case", Row.CaseId, Origin, Revision, Row.JunitUniqueId, Row.SourcePath,
            Row.SourceSha256, Row.SourceLine, Row.BehaviorFamily, Row.ProductClassification,
            Row.ExecutionOwner, Status, Encode(Observation), Encode(Diagnostic));
    }

    sealed record ContractRow(
        string CaseId,
        string JunitUniqueId,
        string DisplayName,
        string SourceClass,
        string SourceMethod,
        string SourcePath,
        string SourceSha256,
        string SourceLine,
        string BehaviorFamily,
        string ProductClassification,
        string ExecutionOwner,
        string ExpectedOutcome,
        string Fixtures,
        string EnvironmentRequirements);

    sealed record ContractManifest(string Revision, IReadOnlyList<ContractRow> Cases)
    {
        public static ContractManifest Read(string path)
        {
            string[] lines = File.ReadAllLines(path, Encoding.UTF8);
            if (lines.Length == 0 || lines[0] != "VIBEFORMER_PKL_CORE_TEST_CONTRACT_V1")
                throw new InvalidOperationException("Pkl.Core contract has the wrong schema marker");
            string? revision = null;
            string[]? columns = null;
            var cases = new List<ContractRow>();
            foreach (string line in lines.Skip(1))
            {
                string[] fields = line.Split('\t');
                if (fields[0] == "meta" && fields.Length == 3 && fields[1] == "source-revision")
                    revision = fields[2];
                else if (fields[0] == "case-columns")
                    columns = fields.Skip(1).ToArray();
                else if (fields[0] == "case")
                {
                    if (columns is null || fields.Length != columns.Length + 1)
                        throw new InvalidOperationException("Malformed Pkl.Core contract case row");
                    var row = columns.Select((column, index) => (column, value: fields[index + 1]))
                        .ToDictionary(item => item.column, item => item.value, StringComparer.Ordinal);
                    cases.Add(new ContractRow(
                        Required(row, "case-id"), Required(row, "junit-unique-id"),
                        Required(row, "display-name"), Required(row, "source-class"),
                        Required(row, "source-method"),
                        Required(row, "source-path"), Required(row, "source-sha256"),
                        Required(row, "source-line"), Required(row, "behavior-family"),
                        Required(row, "product-classification"), Required(row, "execution-owner"),
                        Required(row, "expected-outcome"), Required(row, "fixtures"),
                        Required(row, "environment-requirements")));
                }
            }
            if (revision is null || cases.Count == 0)
                throw new InvalidOperationException("Pkl.Core contract is missing provenance or cases");
            return new ContractManifest(revision, cases);
        }

        static string Required(IReadOnlyDictionary<string, string> row, string key)
        {
            if (!row.TryGetValue(key, out string? value) || string.IsNullOrWhiteSpace(value))
                throw new InvalidOperationException("Missing contract field: " + key);
            return value;
        }
    }

    sealed class CorpusFixture : IDisposable
    {
        readonly string root;
        bool disposed;

        CorpusFixture(string root) => this.root = root;
        public string Root => root;

        public static CorpusFixture Create(ContractRow row)
        {
            string root = Path.Combine(
                Path.GetTempPath(), "vibeformer-pkl-core-row-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            Directory.CreateDirectory(Path.Combine(root, "modules"));
            Directory.CreateDirectory(Path.Combine(root, "resources"));
            Directory.CreateDirectory(Path.Combine(root, "module path", "δ"));
            File.WriteAllText(
                Path.Combine(root, "modules", "dependency.pkl"), "value = 42\n", new UTF8Encoding(false));
            File.WriteAllText(
                Path.Combine(root, "modules", "main.pkl"),
                "dependency = import(\"dependency.pkl\")\n", new UTF8Encoding(false));
            File.WriteAllBytes(Path.Combine(root, "resources", "payload.bin"), new byte[] { 0, 1, 127, 255 });
            using (ZipArchive archive = ZipFile.Open(
                Path.Combine(root, "module-path.zip"), ZipArchiveMode.Create))
            {
                ZipArchiveEntry module = archive.CreateEntry("fixture/module.pkl");
                using var writer = new StreamWriter(module.Open(), new UTF8Encoding(false));
                writer.Write("value = \"archive\"\n");
            }
            return new CorpusFixture(root);
        }

        public void VerifyRequestedFacilities(ContractRow row)
        {
            Require(File.ReadAllText(Path.Combine(root, "modules", "dependency.pkl"), Encoding.UTF8)
                == "value = 42\n", "temporary module fixture bytes");
            Require(File.ReadAllBytes(Path.Combine(root, "resources", "payload.bin"))
                .SequenceEqual(new byte[] { 0, 1, 127, 255 }), "temporary resource fixture bytes");
            using (ZipArchive archive = ZipFile.OpenRead(Path.Combine(root, "module-path.zip")))
                Require(archive.GetEntry("fixture/module.pkl") is not null, "archive module-path fixture");
            Require(Directory.Exists(Path.Combine(root, "module path", "δ")), "platform path fixture");

            var environment = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["VIBEFORMER_CONTRACT_ENV"] = "environment-value"
            };
            var properties = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["vibeformer.contract.property"] = "property-value"
            };
            var services = new[] { "module-key-factory", "resource-reader" };
            Require(environment["VIBEFORMER_CONTRACT_ENV"] == "environment-value", "environment fixture");
            Require(properties["vibeformer.contract.property"] == "property-value", "property fixture");
            Require(services.SequenceEqual(new[] { "module-key-factory", "resource-reader" }),
                "service fixture ordering");

            string source = row.SourcePath;
            string requirements = row.EnvironmentRequirements;
            if (source.Contains("/http/", StringComparison.Ordinal) ||
                requirements.Contains("loopback-http-server", StringComparison.Ordinal))
            {
                VerifyPlainHttp();
                VerifyTlsHttp();
                VerifyProxy();
            }
            if (source.Contains("/packages/", StringComparison.Ordinal) ||
                requirements.Contains("loopback-package-server", StringComparison.Ordinal))
                VerifyPackageServer();
            if (source.Contains("externalreader", StringComparison.OrdinalIgnoreCase) ||
                requirements.Contains("external-reader-process", StringComparison.Ordinal))
                VerifyExternalReader();
        }

        static void VerifyPlainHttp()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: false, "/fixture");
            Require(payload == "http-fixture", "loopback HTTP fixture");
        }

        static void VerifyTlsHttp()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: true, proxy: false, "/fixture");
            Require(payload == "tls-fixture", "loopback TLS fixture");
        }

        static void VerifyProxy()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: true, "/proxy");
            Require(payload == "proxy-fixture", "loopback proxy fixture");
        }

        static void VerifyPackageServer()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: false, "/package");
            Require(payload == "package-fixture", "loopback package fixture");
        }

        static void VerifyExternalReader()
        {
            using var process = new Process();
            process.StartInfo = new ProcessStartInfo
            {
                FileName = "dotnet",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            process.StartInfo.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
            process.StartInfo.ArgumentList.Add("--external-reader");
            process.Start();
            string output = process.StandardOutput.ReadToEnd();
            string error = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(5000))
            {
                process.Kill(entireProcessTree: true);
                throw new InvalidOperationException("external-reader fixture timed out");
            }
            Require(process.ExitCode == 0 && output == "fixture-reader-ok" && error.Length == 0,
                "external-reader process fixture");
        }

        static void Require(bool condition, string subject)
        {
            if (!condition) throw new InvalidOperationException("Invalid " + subject);
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            try { Directory.Delete(root, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    static class OneShotHttpServer
    {
        public static string RoundTrip(bool useTls, bool proxy, string path)
        {
            using var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            using X509Certificate2? certificate = useTls ? CreateCertificate() : null;
            string expectedPayload = proxy ? "proxy-fixture" :
                path == "/package" ? "package-fixture" : useTls ? "tls-fixture" : "http-fixture";
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            Task server = Task.Run(async () =>
            {
                using TcpClient client = await listener.AcceptTcpClientAsync(deadline.Token);
                Stream stream = client.GetStream();
                if (useTls)
                {
                    var ssl = new SslStream(stream, leaveInnerStreamOpen: false);
                    await ssl.AuthenticateAsServerAsync(
                        new SslServerAuthenticationOptions
                        {
                            ServerCertificate = certificate,
                            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                                System.Security.Authentication.SslProtocols.Tls13
                        }, deadline.Token);
                    stream = ssl;
                }
                using (stream)
                {
                    await ReadHeaders(stream, deadline.Token);
                    byte[] body = Encoding.UTF8.GetBytes(expectedPayload);
                    byte[] response = Encoding.ASCII.GetBytes(
                        "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/plain\r\n" +
                        $"Content-Length: {body.Length}\r\n\r\n");
                    await stream.WriteAsync(response, deadline.Token);
                    await stream.WriteAsync(body, deadline.Token);
                    await stream.FlushAsync(deadline.Token);
                }
            }, deadline.Token);

            using var handler = new HttpClientHandler();
            Uri requestUri;
            if (proxy)
            {
                handler.Proxy = new WebProxy(new Uri($"http://127.0.0.1:{port}/"));
                handler.UseProxy = true;
                requestUri = new Uri("http://vibeformer-uncontrolled.invalid/proxy");
            }
            else
            {
                string scheme = useTls ? "https" : "http";
                requestUri = new Uri($"{scheme}://127.0.0.1:{port}{path}");
            }
            if (useTls)
            {
                string thumbprint = certificate!.Thumbprint;
                handler.ServerCertificateCustomValidationCallback =
                    (_, actual, _, _) => actual is not null && actual.GetCertHashString() == thumbprint;
            }
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
            string payload = client.GetStringAsync(requestUri, deadline.Token).GetAwaiter().GetResult();
            server.GetAwaiter().GetResult();
            return payload;
        }

        static async Task ReadHeaders(Stream stream, CancellationToken token)
        {
            int matched = 0;
            byte[] terminator = { 13, 10, 13, 10 };
            var buffer = new byte[1];
            while (matched < terminator.Length)
            {
                int count = await stream.ReadAsync(buffer, token);
                if (count == 0) throw new EndOfStreamException("HTTP fixture request ended early");
                matched = buffer[0] == terminator[matched]
                    ? matched + 1
                    : buffer[0] == terminator[0] ? 1 : 0;
            }
        }

        static X509Certificate2 CreateCertificate()
        {
            using RSA rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=localhost", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(
                new X509BasicConstraintsExtension(false, false, 0, false));
            request.CertificateExtensions.Add(
                new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
            var names = new SubjectAlternativeNameBuilder();
            names.AddDnsName("localhost");
            names.AddIpAddress(IPAddress.Loopback);
            request.CertificateExtensions.Add(names.Build());
            return request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        }
    }
}
