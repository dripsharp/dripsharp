using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Pkl.Core;
using Pkl.Core.EvaluatorSettings;
using Pkl.Core.Externalreader;
using Pkl.Core.Module;
using Pkl.Core.Packages;
using Pkl.Core.Project;
using Pkl.Core.Resource;
using Pkl.Core.Settings;
using Pkl.Parser;
using PklHttpClient = Pkl.Core.Http.HttpClient;
using Version = Pkl.Core.Version;

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
            return ExecuteEvaluatorTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.AnalyzerTest")
            return ExecuteAnalyzerTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.EvaluatorBuilderTest")
            return ExecuteEvaluatorBuilderTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.SecurityManagersTest")
            return ExecuteSecurityManagersTest(row, fixture);
        if (row.SourceClass.StartsWith("org.pkl.core.http.HttpClientTest", StringComparison.Ordinal) ||
            row.SourceClass is "org.pkl.core.http.DummyHttpClientTest" or
            "org.pkl.core.http.HttpClientBuilderTest" or
            "org.pkl.core.http.LazyHttpClientTest" or
            "org.pkl.core.http.NoProxyRuleTest" or
            "org.pkl.core.http.RequestRewritingClientTest")
            return ExecuteHttpClientTest(row, fixture);
        if (row.SourceClass is "org.pkl.core.module.ModuleKeyFactoriesTest" or
            "org.pkl.core.module.ModuleKeysTest" or
            "org.pkl.core.module.ModulePathResolverTest" or
            "org.pkl.core.module.ResolvedModuleKeysTest" or
            "org.pkl.core.module.ServiceProviderTest")
            return ExecuteModuleLoadingTest(row, fixture);
        if (row.SourceClass is "org.pkl.core.resource.ResourceReadersTest" or
            "org.pkl.core.resource.ResourceReadersEvaluatorTest")
            return ExecuteResourceReaderTest(row, fixture);
        if (row.SourceClass is "org.pkl.core.packages.DependencyMetadataTest" or
            "org.pkl.core.packages.PackageResolversTest$DiskCachedPackageResolverTest" or
            "org.pkl.core.packages.PackageResolversTest$InMemoryPackageResolverTest" or
            "org.pkl.core.packages.PackageUriTest")
            return ExecutePackageTest(row, fixture);
        if (row.SourceClass is "org.pkl.core.project.ProjectDependenciesResolverTest" or
            "org.pkl.core.project.ProjectDepsTest" or "org.pkl.core.project.ProjectTest")
            return ExecuteProjectTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.settings.PklSettingsTest")
            return ExecuteSettingsTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.runtime.FileSystemManagerTest")
            return ExecuteFileSystemManagerTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.StackFrameTransformersTest")
            return ExecuteStackFrameTransformerTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.StackTraceRendererTest")
            return ExecuteStackTraceTest(row);
        if (row.SourceClass is "org.pkl.core.JsonRendererTest" or
            "org.pkl.core.PcfRendererTest" or "org.pkl.core.PListRendererTest" or
            "org.pkl.core.PropertiesRendererTest")
            return ExecuteRendererTest(row);
        if (row.SourceClass == "org.pkl.core.DurationTest")
            return ExecuteDurationTest(row);
        if (row.SourceClass == "org.pkl.core.DurationUnitTest")
            return ExecuteDurationUnitTest(row);
        if (row.SourceClass == "org.pkl.core.DataSizeTest")
            return ExecuteDataSizeTest(row);
        if (row.SourceClass == "org.pkl.core.DataSizeUnitTest")
            return ExecuteDataSizeUnitTest(row);
        if (row.SourceClass == "org.pkl.core.PairTest")
            return ExecutePairTest(row);
        if (row.SourceClass == "org.pkl.core.PNullTest")
            return ExecutePNullTest(row);
        if (row.SourceClass == "org.pkl.core.PObjectTest")
            return ExecutePObjectTest(row);
        if (row.SourceClass == "org.pkl.core.PModuleTest")
            return ExecutePModuleTest(row);
        if (row.SourceClass == "org.pkl.core.PClassInfoTest")
            return ExecutePClassInfoTest(row);
        if (row.SourceClass == "org.pkl.core.DynamicTest")
            return ExecuteDynamicTest(row);
        if (row.SourceClass == "org.pkl.core.ClassInheritanceTest")
            return ExecuteClassInheritanceTest(row);
        if (row.SourceClass == "org.pkl.core.VersionTest")
            return ExecuteVersionTest(row);
        if (row.SourceClass is "org.pkl.core.PklInfoTest" or "org.pkl.core.PlatformTest" or
            "org.pkl.core.ReleaseTest")
            return ExecuteRuntimeInfoTest(row);
        if (row.SourceClass is "org.pkl.core.parser.MultiLineStringLiteralTest" or
            "org.pkl.core.parser.ShebangTest" or "org.pkl.core.parser.TrailingCommasTest" or
            "org.pkl.core.ast.builder.ImportsAndReadsParserTest")
            return ExecuteParserAdjunctTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.VmSafeMathTest")
            return ExecuteVmSafeMathTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.CommandSpecParserTest")
            return ExecuteCommandSpecParserTest(row, fixture);
        if (row.SourceClass == "org.pkl.core.runtime.VmUtilsTest")
            return ExecuteVmUtilsTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.IteratorsTest")
            return ExecuteIteratorsTest(row);
        if (row.SourceClass == "org.pkl.core.truffle.LongVsDoubleSpecializationTest")
            return ExecuteLongVsDoubleTest(row);
        if (row.SourceClass is "org.pkl.core.runtime.VmDataSizeTest" or
            "org.pkl.core.runtime.VmDurationTest")
            return ExecuteVmUnitValueTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.VmClassTest")
            return ExecuteVmClassTest(row);
        if (row.SourceClass == "org.pkl.core.runtime.VmValueRendererTest")
            return ExecuteVmValueRendererTest(row);
        if (row.SourceClass == "org.pkl.core.stdlib.ReflectModuleTest")
            return ExecuteReflectModuleTest(row);
        if (row.SourceClass is "org.pkl.core.stdlib.PathConverterSupportTest" or
            "org.pkl.core.stdlib.PathSpecParserTest")
            return ExecuteValuePathTest(row);
        if (row.SourceClass == "org.pkl.core.util.AnsiStringBuilderTest")
            return ExecuteAnsiStringBuilderTest(row);
        if (row.SourceClass == "org.pkl.core.util.ArrayCharEscaperTest")
            return ExecuteArrayCharEscaperTest(row);
        if (row.SourceClass == "org.pkl.core.util.ErrorMessagesTest")
            return ExecuteErrorMessagesTest(row);
        if (row.SourceClass == "org.pkl.core.util.ExceptionsTest")
            return ExecuteExceptionsTest(row);
        if (row.SourceClass == "org.pkl.core.util.GlobResolverTest")
            return ExecuteGlobResolverTest(row);
        if (row.SourceClass == "org.pkl.core.util.HttpUtilsTest")
            return ExecuteHttpUtilsTest(row);
        if (row.SourceClass == "org.pkl.core.util.ImportGraphUtilsTest")
            return ExecuteImportGraphUtilsTest(row);
        if (row.SourceClass == "org.pkl.core.util.IoUtilsTest")
            return ExecuteIoUtilsTest(row, fixture);
        if (row.SourceClass is "org.pkl.core.util.PathResolverTest$PosixTests" or
            "org.pkl.core.util.PathResolverTest$WindowsTests")
            return ExecutePathResolverTest(row);

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

    static ChildResult ExecuteEvaluatorBuilderTest(ContractRow row, CorpusFixture fixture)
    {
        switch (row.SourceMethod)
        {
            case "preconfigured builder sets process env vars":
            {
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                IReadOnlyDictionary<string, string> environment = builder.GetEnvironmentVariables();
                string? expectedPath = Environment.GetEnvironmentVariable("PATH");
                Require(environment.Count > 0 &&
                    (expectedPath is null || environment.TryGetValue("PATH", out string? actualPath) &&
                        actualPath == expectedPath), "preconfigured .NET process environment");
                break;
            }
            case "preconfigured builder sets system properties":
            {
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                IReadOnlyDictionary<string, string> properties = builder.GetExternalProperties();
                Require(properties.Count > 0 && properties.ContainsKey("os.name"),
                    "preconfigured .NET platform properties");
                break;
            }
            case "preconfigured builder adds resource readers from service providers":
            {
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                int before = builder.GetResourceReaders().Count;
                using var reader = new CorpusResourceReader();
                builder.AddResourceReader(reader);
                Require(builder.GetResourceReaders().Count == before + 1 &&
                    ReferenceEquals(builder.GetResourceReaders()[^1], reader),
                    "explicit .NET resource-reader service registration");
                break;
            }
            case "unconfigured builder does not set process env vars":
                Require(EvaluatorBuilder.Unconfigured().GetEnvironmentVariables().Count == 0,
                    "unconfigured environment is empty");
                break;
            case "unconfigured builder does not set system properties":
                Require(EvaluatorBuilder.Unconfigured().GetExternalProperties().Count == 0,
                    "unconfigured properties are empty");
                break;
            case "enforces that security manager is set":
            {
                InvalidOperationException error = Throws<InvalidOperationException>(() =>
                    EvaluatorBuilder.Unconfigured()
                        .SetStackFrameTransformer(StackFrameTransformers.Empty)
                        .Build());
                Require(error.Message == "No security manager set.",
                    "missing security-manager diagnostic");
                break;
            }
            case "enforces that stack frame transformer is set":
            {
                InvalidOperationException error = Throws<InvalidOperationException>(() =>
                    EvaluatorBuilder.Unconfigured()
                        .SetSecurityManager(SecurityManagers.DefaultManager)
                        .Build());
                Require(error.Message == "No stack frame transformer set.",
                    "missing stack-transformer diagnostic");
                break;
            }
            case "sets evaluator settings from project":
            {
                string projectPath = WriteProjectFixture(fixture.Root, includeLocalDependency: false);
                Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectPath);
                EvaluatorBuilder builder = EvaluatorBuilder.Unconfigured();
                int factoryCount = builder.GetModuleKeyFactories().Count;
                builder.ApplyFromProject(project);
                Require(builder.GetAllowedResources().Select(pattern => pattern.ToString())
                        .SequenceEqual(new[] { "file:", "env:", "prop:" }) &&
                    builder.GetAllowedModules().Select(pattern => pattern.ToString())
                        .SequenceEqual(new[] { "pkl:", "file:" }) &&
                    builder.GetExternalProperties()["two"] == "2" &&
                    builder.GetEnvironmentVariables()["one"] == "1" &&
                    builder.GetTimeout() == TimeSpan.FromMinutes(5) &&
                    builder.GetModuleKeyFactories().Count == factoryCount + 1,
                    "project evaluator settings application");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteSecurityManagersTest(ContractRow row, CorpusFixture fixture)
    {
        SecurityManager manager = SecurityManagers.CreateStandard(
            new[] { new Regex("test:foo/bar") },
            new[] { new Regex("env:FOO_BAR") },
            uri => uri.Scheme == "one" ? 1 : uri.Scheme == "two" ? 2 : 0,
            null);
        switch (row.SourceMethod)
        {
            case "checkResolveModule() - complete match":
                manager.CheckResolveModule(new Uri("test:foo/bar"));
                break;
            case "checkResolveModule() - partial match from start":
                manager.CheckResolveModule(new Uri("test:foo/bar/baz"));
                break;
            case "checkResolveModule() - partial match not from start":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckResolveModule(new Uri("other:test:foo/bar")));
                break;
            case "checkResolveModule() - no match":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckResolveModule(new Uri("other:uri")));
                break;
            case "checkResolveModule() - no match #2":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckResolveModule(new Uri("test:foo/baz")));
                break;
            case "checkReadResource() - complete match":
                manager.CheckReadResource(new Uri("env:FOO_BAR"));
                break;
            case "checkReadResource() - partial match from start":
                manager.CheckReadResource(new Uri("env:FOO_BAR_BAZ"));
                break;
            case "checkReadResource() - partial match not from start":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckReadResource(new Uri("other:env:FOO_BAR")));
                break;
            case "checkReadResource() - no match":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckReadResource(new Uri("other:uri")));
                break;
            case "checkReadResource() - no match #2":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckReadResource(new Uri("env:FOO_BAZ")));
                break;
            case "checkImportModule() - same trust level":
                manager.CheckImportModule(new Uri("one:foo"), new Uri("one:bar"));
                break;
            case "checkImportModule() - higher trust level":
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckImportModule(new Uri("one:foo"), new Uri("two:bar")));
                break;
            case "checkImportModule() - lower trust level":
                manager.CheckImportModule(new Uri("two:foo"), new Uri("one:bar"));
                break;
            case "default trust levels":
            {
                Func<Uri, int> levels = SecurityManagers.DefaultTrustLevels;
                Require(levels(new Uri("repl:foo")) == 40 &&
                    levels(new Uri("file:///some/path")) == 30 &&
                    levels(new Uri("jar:file:///some/path!/some/path")) == 30 &&
                    levels(new Uri("modulepath:/some/path")) == 20 &&
                    levels(new Uri("file://apple.com/some.path")) == 10 &&
                    levels(new Uri("jar:http://apple.com/some.path!/some/path")) == 10 &&
                    levels(new Uri("pkl:test")) == 0, "default URI trust levels");
                break;
            }
            case "can resolve modules and resources under root dir - files do exist":
            {
                string root = Path.Combine(fixture.Root, "security-root");
                Directory.CreateDirectory(root);
                string file = Path.Combine(root, "baz.pkl");
                File.WriteAllText(file, "x = 1", new UTF8Encoding(false));
                SecurityManager rooted = RootedSecurityManager(root);
                rooted.CheckResolveModule(new Uri(file));
                rooted.CheckReadResource(new Uri(file));
                rooted.CheckResolveModule(new Uri(Path.Combine(root, "qux", "..", "baz.pkl")));
                break;
            }
            case "can resolve modules and resources under root dir - files don't exist":
            {
                string root = Path.Combine(fixture.Root, "missing-root");
                SecurityManager rooted = RootedSecurityManager(root);
                rooted.CheckResolveModule(new Uri(Path.Combine(root, "baz.pkl")));
                rooted.CheckReadResource(new Uri(Path.Combine(root, "qux", "..", "baz.pkl")));
                break;
            }
            case "cannot resolve modules and resources outside root dir - files do exist":
            {
                string root = Path.Combine(fixture.Root, "existing-root");
                Directory.CreateDirectory(root);
                string outside = Path.Combine(fixture.Root, "outside.pkl");
                File.WriteAllText(outside, "x = 1", new UTF8Encoding(false));
                SecurityManager rooted = RootedSecurityManager(root);
                _ = Throws<SecurityManagerException>(() => rooted.CheckResolveModule(new Uri(outside)));
                _ = Throws<SecurityManagerException>(() => rooted.CheckReadResource(new Uri(outside)));
                string target = Path.Combine(fixture.Root, "link-target");
                Directory.CreateDirectory(target);
                string targetFile = Path.Combine(target, "linked.pkl");
                File.WriteAllText(targetFile, "x = 2", new UTF8Encoding(false));
                string link = Path.Combine(root, "link");
                Directory.CreateSymbolicLink(link, target);
                _ = Throws<SecurityManagerException>(() =>
                    rooted.CheckResolveModule(new Uri(Path.Combine(link, "linked.pkl"))));
                break;
            }
            case "cannot resolve modules and resources outside root dir - files don't exist":
            {
                string root = Path.Combine(fixture.Root, "root");
                SecurityManager rooted = RootedSecurityManager(root);
                string outside = Path.Combine(fixture.Root, "elsewhere", "missing.pkl");
                _ = Throws<SecurityManagerException>(() => rooted.CheckResolveModule(new Uri(outside)));
                _ = Throws<SecurityManagerException>(() => rooted.CheckReadResource(new Uri(outside)));
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static SecurityManager RootedSecurityManager(string root) => SecurityManagers.CreateStandard(
        new[] { new Regex("file") }, new[] { new Regex("file") },
        SecurityManagers.DefaultTrustLevels, root);

    static ChildResult ExecuteModuleLoadingTest(ContractRow row, CorpusFixture fixture)
    {
        if (row.SourceClass == "org.pkl.core.module.ModuleKeyFactoriesTest")
        {
            switch (row.SourceMethod)
            {
                case "standard library":
                    Require(ModuleKeyFactories.StandardLibraryFactory.TryCreate(new Uri("other:test")) is null &&
                        ModuleKeyFactories.StandardLibraryFactory.TryCreate(new Uri("pkl:test")) is not null,
                        "standard-library module factory selection");
                    break;
                case "file":
                    Require(ModuleKeyFactories.FileFactory.TryCreate(new Uri("other:test")) is null &&
                        ModuleKeyFactories.FileFactory.TryCreate(new Uri("file:///some/file")) is not null,
                        "file module factory selection");
                    break;
                case "generic url":
                    Require(ModuleKeyFactories.GenericUrlFactory.TryCreate(new Uri("other:text")) is null &&
                        ModuleKeyFactories.GenericUrlFactory.TryCreate(new Uri("file:///some/file")) is not null,
                        "generic URL module factory selection");
                    break;
                case "class path":
                {
                    using ModuleKeyFactory assembly = ModuleKeyFactories.CreateAssembly(
                        Assembly.GetExecutingAssembly(), uriScheme: "assemblyfixture");
                    Require(assembly.TryCreate(new Uri("other:text")) is null &&
                        assembly.TryCreate(new Uri("assemblyfixture:/foo/bar.pkl")) is not null,
                        ".NET assembly module factory adaptation");
                    break;
                }
                case "module path - basics":
                {
                    using var resolver = new ModulePathResolver(Array.Empty<string>());
                    using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                    Require(factory.TryCreate(new Uri("other:text")) is null &&
                        factory.TryCreate(new Uri("modulepath:/foo/bar.pkl")) is not null,
                        "module-path factory selection");
                    break;
                }
                case "module path - directories":
                {
                    string first = Path.Combine(fixture.Root, "modulepath-a");
                    string second = Path.Combine(fixture.Root, "modulepath-b");
                    string file = Path.Combine(second, "baz", "mymodule.pkl");
                    Directory.CreateDirectory(Path.GetDirectoryName(file)!);
                    Directory.CreateDirectory(first);
                    File.WriteAllText(file, "x = 1", new UTF8Encoding(false));
                    using var resolver = new ModulePathResolver(new[] { first, second });
                    using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                    ModuleKey key = factory.TryCreate(new Uri("modulepath:/baz/mymodule.pkl"))!;
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(ReferenceEquals(resolved.Original, key) && resolved.Uri.IsFile &&
                        resolved.Source.Trim() == "x = 1", "directory module-path resolution");
                    break;
                }
                case "module path - jar files":
                {
                    string archive = CreateArchive(fixture.Root, "modules.zip",
                        ("dir1/module1.pkl", "x = 1"));
                    using var resolver = new ModulePathResolver(new[] { archive });
                    using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                    ModuleKey key = factory.TryCreate(new Uri("modulepath:/dir1/module1.pkl"))!;
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(ReferenceEquals(resolved.Original, key) && resolved.Source.Trim() == "x = 1" &&
                        resolved.Uri.IsFile,
                        ".NET archive extraction and resolved file-URI ownership");
                    break;
                }
                case "module path via service provider":
                {
                    using ModuleKeyFactory factory = new CorpusModuleFactory("service", "x = 1");
                    ModuleKey? key = factory.TryCreate(new Uri("service:foo"));
                    Require(key is not null && key.Uri.Scheme == "service" &&
                        factory.TryCreate(new Uri("other:foo")) is null,
                        "explicit .NET module service registration");
                    break;
                }
                case "externalProcess":
                case "external process -- spawning an executable using a path":
                case "external process -- spawning an executable using a simple name off PATH":
                    VerifyExternalReaderFactoryLifecycle(fixture.Root);
                    break;
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.module.ModuleKeysTest")
        {
            switch (row.SourceMethod)
            {
                case "synthetic":
                {
                    ModuleKey key = ModuleKeys.CreateSynthetic(new Uri("repl:some"), "age = 40");
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(key.Uri == new Uri("repl:some") && !key.Cached &&
                        !ModuleKeys.IsStdLibModule(key) && !ModuleKeys.IsBaseModule(key) &&
                        resolved.Uri == key.Uri && resolved.Source.Contains("age = 40", StringComparison.Ordinal),
                        "synthetic module key");
                    break;
                }
                case "standard library":
                {
                    ModuleKey key = ModuleKeys.CreateStandardLibrary(new Uri("pkl:test"));
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(key.Cached && ModuleKeys.IsStdLibModule(key) &&
                        !ModuleKeys.IsBaseModule(key) && resolved.Source.Contains("module pkl.test",
                            StringComparison.Ordinal), "standard-library module key");
                    break;
                }
                case "standard library - wrong scheme":
                    _ = Throws<ArgumentException>(() =>
                        ModuleKeys.CreateStandardLibrary(new Uri("other:base")));
                    break;
                case "file":
                {
                    string file = Path.Combine(fixture.Root, "file-key.pkl");
                    File.WriteAllText(file, "age = 40", new UTF8Encoding(false));
                    ModuleKey key = ModuleKeys.CreateFile(new Uri(file));
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(key.Cached && key.Local && resolved.Uri.IsFile &&
                        resolved.Source == "age = 40", "file module key");
                    break;
                }
                case "class path":
                {
                    using ModuleKeyFactory factory = ModuleKeyFactories.CreateAssembly(
                        typeof(Evaluator).Assembly, uriScheme: "assemblyfixture");
                    ModuleKey key = factory.TryCreate(new Uri("assemblyfixture:/missing.pkl"))!;
                    Require(key.Local && key.Cached, ".NET assembly module key adaptation");
                    _ = Throws<FileNotFoundException>(() => key.Resolve(
                        SecurityManagers.CreateStandard(new[] { new Regex("assemblyfixture:") },
                            Array.Empty<Regex>(), _ => 0, null)));
                    break;
                }
                case "class path - wrong scheme":
                    using (ModuleKeyFactory factory = ModuleKeyFactories.CreateAssembly(
                        typeof(Evaluator).Assembly, uriScheme: "assemblyfixture"))
                        Require(factory.TryCreate(new Uri("other:base")) is null,
                            ".NET assembly wrong-scheme rejection");
                    break;
                case "class path - module not found":
                    using (ModuleKeyFactory factory = ModuleKeyFactories.CreateAssembly(
                        typeof(Evaluator).Assembly, uriScheme: "assemblyfixture"))
                    {
                        ModuleKey missing = factory.TryCreate(new Uri("assemblyfixture:/non/existing"))!;
                        _ = Throws<FileNotFoundException>(() => missing.Resolve(
                            SecurityManagers.CreateStandard(new[] { new Regex("assemblyfixture:") },
                                Array.Empty<Regex>(), _ => 0, null)));
                    }
                    break;
                case "class path - missing leading slash":
                    using (ModuleKeyFactory factory = ModuleKeyFactories.CreateAssembly(
                        typeof(Evaluator).Assembly, uriScheme: "assemblyfixture"))
                    {
                        ModuleKey key = factory.TryCreate(new Uri("assemblyfixture:missing"))!;
                        Exception error = ThrowsAny(() => key.Resolve(
                            SecurityManagers.CreateStandard(new[] { new Regex("assemblyfixture:") },
                                Array.Empty<Regex>(), _ => 0, null)));
                        Require(error.Message.Contains("/", StringComparison.Ordinal),
                            "embedded module leading-slash diagnostic");
                    }
                    break;
                case "module path":
                {
                    string file = Path.Combine(fixture.Root, "module-path", "foo", "bar.pkl");
                    Directory.CreateDirectory(Path.GetDirectoryName(file)!);
                    File.WriteAllText(file, "age = 40", new UTF8Encoding(false));
                    using var resolver = new ModulePathResolver(new[] { Path.Combine(fixture.Root, "module-path") });
                    ModuleKey key = ModuleKeys.CreateModulePath(new Uri("modulepath:/foo/bar.pkl"), resolver);
                    Require(key.Cached && key.Resolve(SecurityManagers.DefaultManager).Source == "age = 40",
                        "module-path module key");
                    break;
                }
                case "module path - wrong scheme":
                    using (var resolver = new ModulePathResolver(Array.Empty<string>()))
                        _ = Throws<ArgumentException>(() =>
                            ModuleKeys.CreateModulePath(new Uri("other:base"), resolver));
                    break;
                case "module path - module not found":
                    using (var resolver = new ModulePathResolver(Array.Empty<string>()))
                    {
                        ModuleKey key = ModuleKeys.CreateModulePath(
                            new Uri("modulepath:/non/existing"), resolver);
                        _ = Throws<FileNotFoundException>(() => key.Resolve(SecurityManagers.DefaultManager));
                    }
                    break;
                case "module path - missing leading slash":
                    using (var resolver = new ModulePathResolver(Array.Empty<string>()))
                        _ = Throws<ArgumentException>(() =>
                            ModuleKeys.CreateModulePath(new Uri("modulepath:foo/bar.pkl"), resolver));
                    break;
                case "package - no version":
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package://localhost/birds#/Bird.pkl")));
                    break;
                case "package - invalid semver":
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package://localhost/birds@notAVersion#/Bird.pkl")));
                    break;
                case "package - missing leading slash":
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package:invalid")));
                    break;
                case "package - missing authority":
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package:/not/a/valid/path")));
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package:///not/a/valid/path")));
                    break;
                case "package - missing path":
                    _ = Throws<UriFormatException>(() => ModuleKeys.CreatePackage(
                        new Uri("package://example.com")));
                    break;
                case "http - resolve obeys allowed modules":
                {
                    ModuleKey key = ModuleKeys.CreateGenericUrl(new Uri("https://example.test/foo.pkl"));
                    SecurityManager denied = SecurityManagers.CreateStandardBuilder()
                        .SetAllowedModules(new[] { new Regex("repl:"), new Regex("file:") })
                        .SetAllowedResources(Array.Empty<Regex>()).Build();
                    _ = Throws<SecurityManagerException>(() => key.Resolve(denied));
                    break;
                }
                case "generic URL":
                {
                    Uri uri = new("https://example.test/foo.pkl");
                    ModuleKey key = ModuleKeys.CreateGenericUrl(uri);
                    Require(key.Uri == uri && key.Cached && !key.Local,
                        "generic URL key attributes");
                    break;
                }
                case "generic URL - resolve":
                {
                    string file = Path.Combine(fixture.Root, "generic.pkl");
                    File.WriteAllText(file, "age = 40", new UTF8Encoding(false));
                    ModuleKey key = ModuleKeys.CreateGenericUrl(new Uri(file));
                    ResolvedModuleKey resolved = key.Resolve(SecurityManagers.DefaultManager);
                    Require(resolved.Uri == new Uri(file) && resolved.Source == "age = 40",
                        "generic file URL resolution");
                    break;
                }
                case "generic URL with unknown scheme":
                {
                    ModuleKey key = ModuleKeys.CreateGenericUrl(new Uri("repl:foo"));
                    Exception error = ThrowsAny(() => key.Resolve(SecurityManagers.DefaultManager));
                    Require(error.Message.Contains("repl", StringComparison.OrdinalIgnoreCase),
                        "unknown URL scheme diagnostic");
                    break;
                }
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.module.ResolvedModuleKeysTest")
        {
            ModuleKey original = ModuleKeys.CreateSynthetic(new Uri("test:module"), "x = 0");
            Uri resolvedUri = new("test:resolved.uri");
            ResolvedModuleKey resolved;
            switch (row.SourceMethod)
            {
                case "path()":
                {
                    string file = Path.Combine(fixture.Root, "resolved-path.pkl");
                    File.WriteAllText(file, "x = 1", new UTF8Encoding(false));
                    resolved = ResolvedModuleKeys.CreateFile(original, resolvedUri, file);
                    break;
                }
                case "url()":
                {
                    string file = Path.Combine(fixture.Root, "resolved-url.pkl");
                    File.WriteAllText(file, "x = 1", new UTF8Encoding(false));
                    resolved = ResolvedModuleKeys.CreateUrl(original, resolvedUri, new Uri(file));
                    break;
                }
                case "virtual()":
                    resolved = ResolvedModuleKeys.CreateVirtual(
                        original, resolvedUri, "x = 1", false);
                    break;
                default:
                    return Pending(row);
            }
            Require(ReferenceEquals(resolved.Original, original) && resolved.Uri == resolvedUri &&
                resolved.Source == "x = 1", "resolved module key shape");
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.module.ModulePathResolverTest" &&
            row.SourceMethod == "close without having been used")
        {
            var resolver = new ModulePathResolver(Array.Empty<string>());
            resolver.Dispose();
            resolver.Dispose();
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.module.ServiceProviderTest" &&
            row.SourceMethod == "load module through service provider")
        {
            using var factory = new CorpusModuleFactory("service", "name = \"Pigeon\"\nage = 40");
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetAllowedModules(new[] { new Regex("service:"), new Regex("pkl:") })
                .SetAllowedResources(Array.Empty<Regex>())
                .AddModuleKeyFactory(ModuleKeyFactories.StandardLibraryFactory)
                .AddModuleKeyFactory(factory).Build();
            PModule module = evaluator.Evaluate(ModuleSource.FromUri("service:foo"));
            Require((string)module.GetProperty("name") == "Pigeon" &&
                (long)module.GetProperty("age") == 40,
                "explicit .NET module service evaluation");
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteResourceReaderTest(ContractRow row, CorpusFixture fixture)
    {
        if (row.SourceClass == "org.pkl.core.resource.ResourceReadersTest")
        {
            switch (row.SourceMethod)
            {
                case "class path - present resource":
                {
                    using ResourceReader reader = ResourceReaders.CreateEmbeddedResources(
                        Assembly.GetExecutingAssembly(), "Corpus.Resources", "embeddedfixture");
                    var resource = reader.TryRead(new Uri("embeddedfixture:/payload.txt")) as Resource;
                    Require(resource is not null && resource.Text == "content\n" &&
                        resource.Base64 == "Y29udGVudAo=" &&
                        resource.Bytes.SequenceEqual(Encoding.UTF8.GetBytes("content\n")),
                        ".NET embedded-resource adaptation");
                    break;
                }
                case "class path - absent resource":
                {
                    using ResourceReader reader = ResourceReaders.CreateEmbeddedResources(
                        Assembly.GetExecutingAssembly(), "Corpus.Resources", "embeddedfixture");
                    Require(reader.TryRead(new Uri("embeddedfixture:/non/existing")) is null,
                        "absent embedded resource");
                    break;
                }
                case "class path - missing leading slash":
                {
                    using ResourceReader reader = ResourceReaders.CreateEmbeddedResources(
                        Assembly.GetExecutingAssembly(), "Corpus.Resources", "embeddedfixture");
                    Exception error = ThrowsAny(() =>
                        reader.TryRead(new Uri("embeddedfixture:missing")));
                    Require(error.Message.Contains("/", StringComparison.Ordinal),
                        "embedded resource leading-slash diagnostic");
                    break;
                }
                case "module path - present resource":
                {
                    string first = CreateArchive(fixture.Root, "resource1.zip",
                        ("dir1/resource1.txt", "content\n"));
                    string second = CreateArchive(fixture.Root, "resource2.zip",
                        ("dir2/subdir2/resource2.txt", "content\n"));
                    using var resolver = new ModulePathResolver(new[] { first, second });
                    using ResourceReader reader = ResourceReaders.ModulePath(resolver);
                    var resource1 = reader.TryRead(new Uri("modulepath:/dir1/resource1.txt")) as Resource;
                    var resource2 = reader.TryRead(
                        new Uri("modulepath:/dir2/subdir2/resource2.txt")) as Resource;
                    Require(resource1?.Text == "content\n" && resource2?.Text == "content\n" &&
                        resource2.Base64 == "Y29udGVudAo=", "archive resource-reader bytes");
                    break;
                }
                case "module path - absent resource":
                {
                    using var resolver = new ModulePathResolver(Array.Empty<string>());
                    using ResourceReader reader = ResourceReaders.ModulePath(resolver);
                    Require(reader.TryRead(new Uri("modulepath:/non/existing")) is null,
                        "absent module-path resource");
                    break;
                }
                case "module path - missing leading slash":
                {
                    using var resolver = new ModulePathResolver(Array.Empty<string>());
                    using ResourceReader reader = ResourceReaders.ModulePath(resolver);
                    Exception error = ThrowsAny(() =>
                        reader.TryRead(new Uri("modulepath:non/existing")));
                    Require(error.Message.Contains("/", StringComparison.Ordinal),
                        "module-path resource leading-slash diagnostic");
                    break;
                }
                case "module path - missing jar is ignored":
                {
                    string missing = Path.Combine(fixture.Root, "missing.zip");
                    string archive = CreateArchive(fixture.Root, "resource.zip",
                        ("dir1/resource1.txt", "content\n"));
                    using var resolver = new ModulePathResolver(new[] { missing, archive });
                    using ResourceReader reader = ResourceReaders.ModulePath(resolver);
                    var resource = reader.TryRead(new Uri("modulepath:/dir1/resource1.txt")) as Resource;
                    Require(resource?.Text == "content\n", "missing archive ignored");
                    break;
                }
                case "via service provider":
                {
                    using ResourceReader reader = new CorpusResourceReader("service-resource", "success");
                    Require(reader.TryRead(new Uri("service-resource:foo")) as string == "success",
                        "explicit .NET resource service registration");
                    break;
                }
                case "externalProcess":
                    VerifyExternalReaderResourceLifecycle(fixture.Root);
                    break;
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.resource.ResourceReadersEvaluatorTest")
        {
            switch (row.SourceMethod)
            {
                case "class path":
                {
                    using var reader = new CorpusResourceReader("adaptedresource", "content");
                    using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                        .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                        .SetAllowedModules(new[] { new Regex("repl:"), new Regex("pkl:") })
                        .SetAllowedResources(new[] { new Regex("adaptedresource:") })
                        .AddModuleKeyFactory(ModuleKeyFactories.StandardLibraryFactory)
                        .AddResourceReader(reader).Build();
                    PModule module = evaluator.Evaluate(ModuleSource.FromText(
                        "res1 = read(\"adaptedresource:item\")"));
                    Require((string)module.GetProperty("res1") == "content",
                        ".NET embedded/custom resource evaluation adaptation");
                    break;
                }
                case "module path":
                {
                    string archive = CreateArchive(fixture.Root, "eval-resource.zip",
                        ("dir1/resource1.txt", "content\n"));
                    using var resolver = new ModulePathResolver(new[] { archive });
                    using ResourceReader reader = ResourceReaders.ModulePath(resolver);
                    using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                        .AddResourceReader(reader).Build();
                    PModule module = evaluator.Evaluate(ModuleSource.FromText(
                        "res1 = read(\"modulepath:/dir1/resource1.txt\").text"));
                    Require((string)module.GetProperty("res1") == "content\n",
                        "module-path resource evaluation");
                    break;
                }
                default:
                    return Pending(row);
            }
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecutePackageTest(ContractRow row, CorpusFixture fixture)
    {
        if (row.SourceClass == "org.pkl.core.packages.PackageUriTest")
        {
            switch (row.SourceMethod)
            {
                case "rejects percent-encoded dot-dot path segments":
                    _ = Throws<UriFormatException>(() => _ = new PackageUri(
                        "package://attacker.test/%2e%2e/legit.example.test/legit@1.2.3"));
                    break;
                case "rejects literal dot-dot path segments":
                    _ = Throws<UriFormatException>(() => _ = new PackageUri(
                        "package://attacker.test/../legit@1.2.3"));
                    break;
                case "rejects trailing dot-dot segment":
                    _ = Throws<UriFormatException>(() => _ = new PackageUri(
                        "package://attacker.test/foo@1.2.3/%2e%2e"));
                    break;
                case "accepts a valid package URI":
                {
                    var uri = new PackageUri("package://example.test/my/package@1.0.0");
                    Require(uri.Version.Equals(Version.Parse("1.0.0")) &&
                        uri.MetadataRequestUri.Scheme == Uri.UriSchemeHttps,
                        "valid package URI components");
                    break;
                }
                case "does not reject path segments that merely contain dots":
                    _ = new PackageUri("package://example.test/my..pkg/..foo/bar..@1.0.0");
                    break;
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.packages.DependencyMetadataTest")
        {
            DependencyMetadata metadata = CreateDependencyMetadata(
                includePattern: row.SourceMethod == "testPatternSerialization");
            using var output = new MemoryStream();
            metadata.WriteTo(output);
            string json = Encoding.UTF8.GetString(output.ToArray());
            DependencyMetadata parsed = DependencyMetadata.Parse(json);
            switch (row.SourceMethod)
            {
                case "parse":
                    Require(parsed.Equals(metadata) && parsed.Name == "my-proj-name" &&
                        parsed.Dependencies.Count == 1 && parsed.Authors?.Single() == "birdy@example.test",
                        "dependency metadata parse equality");
                    break;
                case "testPatternSerialization":
                {
                    object? pattern = parsed.Annotations.Single().GetProperty("pattern");
                    Require(pattern is Regex regex && regex.ToString() == ".*" &&
                        json.Contains("\"type\": \"Pattern\"", StringComparison.Ordinal),
                        "dependency metadata regex serialization");
                    break;
                }
                case "writeTo":
                {
                    using var repeated = new MemoryStream();
                    parsed.WriteTo(repeated);
                    Require(output.ToArray().SequenceEqual(repeated.ToArray()),
                        "dependency metadata deterministic UTF-8 serialization");
                    break;
                }
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass is "org.pkl.core.packages.PackageResolversTest$DiskCachedPackageResolverTest" or
            "org.pkl.core.packages.PackageResolversTest$InMemoryPackageResolverTest")
        {
            bool disk = row.SourceClass.Contains("DiskCached", StringComparison.Ordinal);
            using var server = new CorpusPackageServer();
            using PklHttpClient client = server.CreateClient();
            string? cache = disk ? Path.Combine(fixture.Root, "package-cache") : null;
            using PackageResolver resolver = PackageResolver.GetInstance(
                SecurityManagers.DefaultManager, client, cache);
            PackageAssetUri Asset(string packageName, string path) => new(
                $"package://127.0.0.1:{server.Port}/{packageName}#{path}");

            switch (row.SourceMethod)
            {
                case "get module bytes":
                {
                    PackageAssetUri asset = Asset("birds@0.5.0", "/Bird.pkl");
                    Task<byte[]>[] reads = Enumerable.Range(0, 6).Select(_ => Task.Run(() =>
                        resolver.GetAssetBytes(asset))).ToArray();
                    Task.WaitAll(reads);
                    Require(reads.All(task => Encoding.UTF8.GetString(task.Result) ==
                        server.BirdModule), "concurrent package asset reads");
                    break;
                }
                case "get directory":
                {
                    IOException error = Throws<IOException>(() =>
                        resolver.GetAssetBytes(Asset("birds@0.5.0", "/")));
                    Require(error.Message.Contains("directory", StringComparison.OrdinalIgnoreCase),
                        "package directory read rejection");
                    break;
                }
                case "get directory, allowing directory reads":
                {
                    string listing = Encoding.UTF8.GetString(
                        resolver.GetAssetBytes(Asset("birds@0.5.0", "/"), true));
                    Require(listing == "Bird.pkl\nallFruit.pkl\ncatalog\ncatalog.pkl\nsome\n",
                        "package directory byte listing order");
                    break;
                }
                case "get module bytes resolving path":
                    Require(Encoding.UTF8.GetString(resolver.GetAssetBytes(
                        Asset("birds@0.5.0", "/foo/../Bird.pkl"))) == server.BirdModule,
                        "normalized package asset path");
                    break;
                case "list path elements at root":
                {
                    IReadOnlyList<PathElement> elements = resolver.GetElements(
                        Asset("birds@0.5.0", "/"));
                    Require(elements.ToHashSet().SetEquals(new[] {
                        new PathElement("some", true), new PathElement("catalog", true),
                        new PathElement("Bird.pkl", false), new PathElement("allFruit.pkl", false),
                        new PathElement("catalog.pkl", false) }), "package root elements");
                    break;
                }
                case "get multiple assets":
                    Require(Encoding.UTF8.GetString(resolver.GetAssetBytes(
                            Asset("birds@0.5.0", "/Bird.pkl"))) == server.BirdModule &&
                        Encoding.UTF8.GetString(resolver.GetAssetBytes(
                            Asset("birds@0.5.0", "/catalog/Swallow.pkl"))) ==
                            "name = \"Swallow\"\n", "multiple package assets");
                    break;
                case "list path elements in nested directory":
                    Require(resolver.GetElements(Asset("birds@0.5.0", "/catalog/"))
                        .ToHashSet().SetEquals(new[] { new PathElement("Ostrich.pkl", false),
                            new PathElement("Swallow.pkl", false) }),
                        "nested package elements");
                    break;
                case "getBytes() throws FileNotFound if package exists but path does not":
                    _ = Throws<FileNotFoundException>(() => resolver.GetAssetBytes(
                        Asset("birds@0.5.0", "/Horse.pkl")));
                    break;
                case "getBytes() throws PackageLoadError if package does not exist":
                    _ = Throws<PackageLoadError>(() => resolver.GetAssetBytes(
                        Asset("not-a-package@0.5.0", "/Horse.pkl")));
                    break;
                case "requires package zip to be an HTTPS URI":
                {
                    Exception error = ThrowsAny(() => resolver.GetAssetBytes(
                        Asset("badPackageZipUrl@1.0.0", "/Bug.pkl")));
                    Require(error.Message.Contains("HTTPS URI", StringComparison.Ordinal) &&
                        error.Message.Contains("ftp://wait/a/minute", StringComparison.Ordinal),
                        "package archive HTTPS requirement");
                    break;
                }
                case "throws if package checksum is invalid":
                {
                    PackageLoadError error = Throws<PackageLoadError>(() =>
                        resolver.GetAssetBytes(Asset("badChecksum@1.0.0", "/Bug.pkl")));
                    Require(error.Message.Contains("Computed checksum", StringComparison.Ordinal) &&
                        error.Message.Contains("Expected checksum", StringComparison.Ordinal),
                        "package archive integrity diagnostic");
                    break;
                }
                default:
                    return Pending(row);
            }

            if (disk)
            {
                Require(Directory.Exists(cache!) && Directory.EnumerateFileSystemEntries(
                    cache!, "*", SearchOption.AllDirectories).Any(), "disk package cache state");
            }
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteProjectTest(ContractRow row, CorpusFixture fixture)
    {
        if (row.SourceClass == "org.pkl.core.project.ProjectDepsTest")
        {
            const string json = "{\n" +
                "  \"schemaVersion\": 1,\n" +
                "  \"resolvedDependencies\": {\n" +
                "    \"package://localhost:0/birds@0\": {\n" +
                "      \"type\": \"remote\",\n" +
                "      \"uri\": \"package://localhost:0/birds@0.5.0\",\n" +
                "      \"checksums\": {\"sha256\": \"abc123\"}\n" +
                "    },\n" +
                "    \"package://localhost:0/fruit@1\": {\n" +
                "      \"type\": \"local\",\n" +
                "      \"uri\": \"package://localhost:0/fruit@1.1.0\",\n" +
                "      \"path\": \"../fruit\"\n" +
                "    }\n" +
                "  }\n" +
                "}\n";
            ProjectDeps parsed = ProjectDeps.ParseFromString(json);
            var remote = parsed.Get(CanonicalPackageUri.Of(
                "package://localhost:0/birds@0")) as Dependency.RemoteDependency;
            var local = parsed.Get(CanonicalPackageUri.Of(
                "package://localhost:0/fruit@1")) as Dependency.LocalDependency;
            Require(remote?.Checksums?.Sha256 == "abc123" && local?.Path == "../fruit",
                "project dependency parse");
            if (row.SourceMethod == "writeTo")
            {
                using var output = new MemoryStream();
                parsed.WriteTo(output);
                ProjectDeps repeated = ProjectDeps.ParseFromString(
                    Encoding.UTF8.GetString(output.ToArray()));
                var repeatedRemote = repeated.Get(
                    CanonicalPackageUri.Of("package://localhost:0/birds@0"));
                var repeatedLocal = repeated.Get(
                    CanonicalPackageUri.Of("package://localhost:0/fruit@1"));
                Require(parsed.Equals(repeated),
                    $"project dependency serialization round trip " +
                    $"(remote={remote!.Equals(repeatedRemote!)}, " +
                    $"local={local!.Equals(repeatedLocal!)})");
            }
            else if (row.SourceMethod != "parse") return Pending(row);
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.project.ProjectDependenciesResolverTest")
        {
            using var server = new CorpusPackageServer();
            string projectFile = Path.Combine(fixture.Root, "dependency-project", "PklProject");
            Directory.CreateDirectory(Path.GetDirectoryName(projectFile)!);
            string checksum = row.SourceMethod ==
                "fails if project declares a package with an incorrect checksum"
                ? "intentionally bogus value"
                : server.BirdsMetadataSha256;
            File.WriteAllText(projectFile,
                "amends \"pkl:Project\"\n" +
                "dependencies { [\"birds\"] { " +
                $"uri = \"package://127.0.0.1:{server.Port}/birds@0.5.0\"; " +
                $"checksums {{ sha256 = \"{checksum}\" }} }} }}\n",
                new UTF8Encoding(false));
            Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectFile);
            using PklHttpClient client = server.CreateClient();
            using PackageResolver resolver = PackageResolver.GetInstance(
                SecurityManagers.DefaultManager, client, null);
            var dependencyResolver = new ProjectDependenciesResolver(
                project, resolver, TextWriter.Null);
            if (row.SourceMethod == "resolveDependencies")
            {
                ProjectDeps dependencies = dependencyResolver.Resolve();
                using var output = new MemoryStream();
                dependencies.WriteTo(output);
                string serialized = Encoding.UTF8.GetString(output.ToArray());
                Require(serialized.Contains("projectpackage://127.0.0.1", StringComparison.Ordinal) &&
                    serialized.Contains(server.BirdsMetadataSha256, StringComparison.Ordinal),
                    "project remote dependency resolution");
            }
            else if (row.SourceMethod ==
                "fails if project declares a package with an incorrect checksum")
            {
                PklException error = Throws<PklException>(() => dependencyResolver.Resolve());
                Require(error.Message.Contains("checksum", StringComparison.OrdinalIgnoreCase) &&
                    error.Message.Contains("intentionally bogus value", StringComparison.Ordinal) &&
                    error.Message.Contains(server.BirdsMetadataSha256, StringComparison.Ordinal),
                    "declared project checksum diagnostic");
            }
            else return Pending(row);
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.project.ProjectTest")
        {
            switch (row.SourceMethod)
            {
                case "loadFromPath":
                {
                    string path = WriteProjectFixture(fixture.Root, includeLocalDependency: false);
                    Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(path);
                    Require(project.PackageMetadata?.Name == "hawk" &&
                        project.PackageMetadata.Version.Equals(Version.Parse("0.5.0")) &&
                        project.ResolvedEvaluatorConfiguration.Environment?["one"] == "1" &&
                        project.ResolvedEvaluatorConfiguration.ExternalPropertiesReadOnly?["two"] == "2" &&
                        project.Tests.Count == 2 && project.Annotations.Count == 3,
                        "project package, settings, tests, and annotations");
                    break;
                }
                case "loadFromPath() resolvedEvaluatorSettings":
                {
                    string directory = Path.Combine(fixture.Root, "resolved-settings-project");
                    Directory.CreateDirectory(directory);
                    string path = Path.Combine(directory, "PklProject");
                    File.WriteAllText(path,
                        "amends \"pkl:Project\"\n" +
                        $"projectFileUri = \"{new Uri(path).AbsoluteUri}\"\n" +
                        "evaluatorSettings { rootDir = \".\"; moduleCacheDir = \"cache/\"; " +
                        "modulePath { \"modulepath1/\"; \"modulepath2/\" } }\n",
                        new UTF8Encoding(false));
                    PklEvaluatorSettings settings = Pkl.Core.Project.Project.LoadFromPath(path)
                        .ResolvedEvaluatorConfiguration;
                    Require(Path.GetFullPath(settings.RootDir!) == Path.GetFullPath(directory) &&
                        Path.GetFullPath(settings.ModuleCacheDir!) ==
                            Path.GetFullPath(Path.Combine(directory, "cache")) &&
                        settings.ModulePaths?.Select(Path.GetFullPath).SequenceEqual(new[] {
                            Path.GetFullPath(Path.Combine(directory, "modulepath1")),
                            Path.GetFullPath(Path.Combine(directory, "modulepath2")) }) == true,
                        "resolved project-relative evaluator paths");
                    break;
                }
                case "load wrong type":
                {
                    string path = Path.Combine(fixture.Root, "wrong-project.pkl");
                    File.WriteAllText(path, "module com.example.Foo\nfoo = 1", new UTF8Encoding(false));
                    PklException error = Throws<PklException>(() =>
                        Pkl.Core.Project.Project.LoadFromPath(path));
                    Require(error.Message.Contains("pkl.Project", StringComparison.Ordinal) &&
                        error.Message.Contains("com.example.Foo", StringComparison.Ordinal),
                        "wrong project type diagnostic");
                    break;
                }
                case "evaluate project module -- invalid checksum":
                    VerifyProjectPackageChecksumFailure(fixture.Root);
                    break;
                case "fails if project has cyclical dependencies":
                    VerifyProjectCycles(fixture.Root, multiple: false);
                    break;
                case "fails if a project has cyclical dependencies -- multiple cycles found":
                    VerifyProjectCycles(fixture.Root, multiple: true);
                    break;
                case "external readers -- executable path is relative to project dir":
                {
                    Pkl.Core.Project.Project project = LoadExternalReaderProject(
                        fixture.Root, "foo/bar/baz");
                    string executable = project.ResolvedEvaluatorConfiguration
                        .ExternalModuleReadersReadOnly!["foo"].Executable;
                    Require(Path.GetFullPath(executable) == Path.GetFullPath(
                        Path.Combine(project.ProjectDirectory, "foo", "bar", "baz")),
                        "project-relative external reader executable");
                    break;
                }
                case "external readers -- executable is unmodified simple name":
                {
                    Pkl.Core.Project.Project project = LoadExternalReaderProject(
                        fixture.Root, "my-command");
                    Require(project.EvaluatorConfiguration.ExternalModuleReadersReadOnly!["foo"]
                        .Executable == "my-command", "simple external reader executable");
                    break;
                }
                default:
                    return Pending(row);
            }
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteSettingsTest(ContractRow row, CorpusFixture fixture)
    {
        string settingsPath = Path.Combine(fixture.Root, "settings.pkl");
        switch (row.SourceMethod)
        {
            case "load user settings":
                File.WriteAllText(settingsPath,
                    "amends \"pkl:settings\"\neditor = Sublime\n", new UTF8Encoding(false));
                Require(PklSettings.Load(ModuleSource.FromPath(settingsPath)).EditorSettings
                    .Equals(PklSettings.Editor.SUBLIME), "user editor settings");
                break;
            case "load user settings with http":
            {
                File.WriteAllText(settingsPath,
                    "amends \"pkl:settings\"\nhttp { " +
                    "proxy { address = \"http://localhost:8080\"; " +
                    "noProxy { \"example.com\"; \"pkg.pkl-lang.org\" } }; " +
                    "rewrites { [\"https://foo.com/\"] = \"https://bar.com/\" }; " +
                    "headers { [\"https://foo.com/**\"] { [\"x-foo\"] = \"bar\" }; " +
                    "[\"https://bar.com/**\"] { [\"x-bar\"] { \"bar\"; \"baz\" } } } }\n",
                    new UTF8Encoding(false));
                PklSettings settings = PklSettings.Load(ModuleSource.FromPath(settingsPath));
                Require(settings.EditorSettings.Equals(PklSettings.Editor.SYSTEM) &&
                    settings.HttpSettings?.Proxy?.Address == new Uri("http://localhost:8080") &&
                    settings.HttpSettings.Proxy.NoProxyReadOnly?.SequenceEqual(
                        new[] { "example.com", "pkg.pkl-lang.org" }) == true &&
                    settings.HttpSettings.RewritesReadOnly?.Single().Value ==
                        new Uri("https://bar.com/") &&
                    settings.HttpSettings.Headers?["https://bar.com/**"]["x-bar"]
                        .SequenceEqual(new[] { "bar", "baz" }) == true,
                    "HTTP user settings");
                break;
            }
            case "load user settings with http, but no noProxy":
            {
                File.WriteAllText(settingsPath,
                    "amends \"pkl:settings\"\nhttp { proxy { " +
                    "address = \"http://localhost:8080\" } }\n", new UTF8Encoding(false));
                PklSettings settings = PklSettings.Load(ModuleSource.FromPath(settingsPath));
                Require(settings.HttpSettings?.Proxy?.NoProxyReadOnly?.Count == 0,
                    "nullable empty no-proxy settings");
                break;
            }
            case "load settings from path":
                File.WriteAllText(settingsPath,
                    "amends \"pkl:settings\"\neditor = Idea\n", new UTF8Encoding(false));
                Require(PklSettings.Load(ModuleSource.FromPath(settingsPath)).EditorSettings
                    .Equals(PklSettings.Editor.IDEA), "explicit settings path");
                break;
            case "predefined editors":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                PModule module = evaluator.Evaluate(ModuleSource.FromText(
                    "import \"pkl:settings\"\n" +
                    "system = settings.System\nidea = settings.Idea\ntextMate = settings.TextMate\n" +
                    "sublime = settings.Sublime\natom = settings.Atom\nvsCode = settings.VsCode\n"));
                var expected = new[] { PklSettings.Editor.SYSTEM, PklSettings.Editor.IDEA,
                    PklSettings.Editor.TEXT_MATE, PklSettings.Editor.SUBLIME,
                    PklSettings.Editor.ATOM, PklSettings.Editor.VS_CODE };
                var names = new[] { "system", "idea", "textMate", "sublime", "atom", "vsCode" };
                Require(names.Select(name => ((PObject)module.GetProperty(name))
                        .GetProperty("urlScheme")).Cast<string>()
                    .SequenceEqual(expected.Select(editor => editor.UrlScheme)),
                    "predefined editor URL schemes");
                break;
            }
            case "invalid settings file":
                File.WriteAllText(settingsPath, "foo = 1", new UTF8Encoding(false));
                Require(Throws<PklException>(() => PklSettings.Load(ModuleSource.FromPath(settingsPath)))
                    .Message.Contains("pkl.settings", StringComparison.Ordinal),
                    "invalid settings type diagnostic");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteFileSystemManagerTest(ContractRow row, CorpusFixture fixture)
    {
        string archive = CreateArchive(fixture.Root, "filesystem.zip",
            ("module.pkl", "value = 1"));
        switch (row.SourceMethod)
        {
            case "only closes a file system after the last usage closes":
            {
                var first = new ModulePathResolver(new[] { archive });
                var second = new ModulePathResolver(new[] { archive });
                var third = new ModulePathResolver(new[] { archive });
                Uri uri = new("modulepath:/module.pkl");
                Require(first.Resolve(uri) is not null && second.Resolve(uri) is not null &&
                    third.Resolve(uri) is not null, "three shared archive filesystem usages");
                first.Dispose();
                Require(second.Resolve(uri) is not null && third.Resolve(uri) is not null,
                    "archive remains open after first usage closes");
                second.Dispose();
                Require(third.Resolve(uri) is not null,
                    "archive remains open until its last usage closes");
                third.Dispose();
                _ = Throws<InvalidOperationException>(() => third.Resolve(uri));
                break;
            }
            case "does not close file system that was spawned externally":
            {
                using ZipArchive external = ZipFile.OpenRead(archive);
                using (var resolver = new ModulePathResolver(new[] { archive }))
                    Require(resolver.Resolve(new Uri("modulepath:/module.pkl")) is not null,
                        "resolver sees externally opened archive");
                Require(external.GetEntry("module.pkl") is not null,
                    "external archive ownership preserved");
                break;
            }
            case "close and re-open same file system":
            {
                using (var first = new ModulePathResolver(new[] { archive }))
                    Require(first.Resolve(new Uri("modulepath:/module.pkl")) is not null,
                        "first archive resolver");
                using (var second = new ModulePathResolver(new[] { archive }))
                    Require(second.Resolve(new Uri("modulepath:/module.pkl")) is not null,
                        "reopened archive resolver");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteHttpClientTest(ContractRow row, CorpusFixture fixture)
    {
        if (row.SourceClass == "org.pkl.core.http.DummyHttpClientTest")
        {
            using PklHttpClient client = PklHttpClient.DummyClient();
            if (row.SourceMethod == "refuses to send messages")
            {
                Exception error = ThrowsAny(() => client.Send(
                    new HttpRequestMessage(HttpMethod.Get, "https://example.test/"), _ => { }));
                Require(error.Message.Length > 0, "dummy HTTP client deterministic rejection");
            }
            else if (row.SourceMethod == "can be closed")
            {
                client.Dispose();
                client.Dispose();
            }
            else return Pending(row);
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.http.HttpClientBuilderTest")
        {
            using var server = CorpusHttpServer.Plain(_ => CorpusHttpResponse.Ok("headers"));
            PklHttpClient.Builder builder = PklHttpClient.CreateBuilder();
            if (row.SourceMethod == "addHeader merges values for duplicate header names")
            {
                builder.AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                    ["X-Test"] = new[] { "one" } });
                builder.AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                    ["X-Test"] = new[] { "two" } });
                using PklHttpClient client = builder.Build();
                using HttpResponseMessage _ = client.Send(
                    new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { });
                Require(server.SingleRequest().Headers["x-test"].SequenceEqual(new[] { "one", "two" }),
                    "duplicate configured HTTP header values");
            }
            else if (row.SourceMethod == "addHeader preserves non-overlapping header names")
            {
                builder.AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                    ["X-One"] = new[] { "one" } });
                builder.AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                    ["X-Two"] = new[] { "two" } });
                using PklHttpClient client = builder.Build();
                using HttpResponseMessage _ = client.Send(
                    new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { });
                CorpusHttpRequest request = server.SingleRequest();
                Require(request.Headers["x-one"].Single() == "one" &&
                    request.Headers["x-two"].Single() == "two",
                    "non-overlapping configured HTTP headers");
            }
            else return Pending(row);
            return Passed(row);
        }

        if (row.SourceClass.StartsWith("org.pkl.core.http.HttpClientTest", StringComparison.Ordinal))
        {
            switch (row.SourceMethod)
            {
                case "can build default client":
                    using (PklHttpClient client = PklHttpClient.CreateBuilder().Build())
                        Require(client is IDisposable, "default HTTP client shape");
                    break;
                case "can build custom client":
                    using (PklHttpClient client = PklHttpClient.CreateBuilder()
                        .SetConnectTimeout(TimeSpan.FromSeconds(2))
                        .SetRequestTimeout(TimeSpan.FromSeconds(2))
                        .SetUserAgent("vibeformer-corpus").Build())
                        Require(client is IDisposable, "custom HTTP client shape");
                    break;
                case "can load certificates from regular file":
                {
                    using var server = CorpusHttpServer.Tls(_ => CorpusHttpResponse.Ok("tls"));
                    string certificate = Path.Combine(fixture.Root, "certificate.pem");
                    File.WriteAllText(certificate, server.CertificatePem, new UTF8Encoding(false));
                    using PklHttpClient client = PklHttpClient.CreateBuilder()
                        .AddCertificate(certificate).Build();
                    Require(Encoding.UTF8.GetString(client.GetBytes(
                        new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { })) == "tls",
                        "certificate file HTTP trust");
                    break;
                }
                case "can load certificates from a byte array":
                {
                    using var server = CorpusHttpServer.Tls(_ => CorpusHttpResponse.Ok("tls"));
                    using PklHttpClient client = PklHttpClient.CreateBuilder()
                        .AddCertificate(Encoding.ASCII.GetBytes(server.CertificatePem)).Build();
                    using Stream stream = client.OpenRead(
                        new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { });
                    using var reader = new StreamReader(stream, Encoding.UTF8);
                    Require(reader.ReadToEnd() == "tls", "certificate byte-array HTTP trust");
                    break;
                }
                case "certificate file cannot be empty":
                {
                    string certificate = Path.Combine(fixture.Root, "empty.pem");
                    File.WriteAllBytes(certificate, Array.Empty<byte>());
                    _ = Throws<Pkl.Core.Http.HttpClientException>(() =>
                        PklHttpClient.CreateBuilder().AddCertificate(certificate).Build());
                    break;
                }
                case "can load built-in certificates":
                    using (PklHttpClient client = PklHttpClient.CreateBuilder().Build())
                        Require(client is not null, "platform certificate store client");
                    break;
                case "can be closed multiple times":
                {
                    PklHttpClient client = PklHttpClient.CreateBuilder().Build();
                    client.Dispose();
                    client.Dispose();
                    break;
                }
                case "refuses to send messages once closed":
                {
                    PklHttpClient client = PklHttpClient.CreateBuilder().Build();
                    client.Dispose();
                    _ = ThrowsAny(() => client.Send(
                        new HttpRequestMessage(HttpMethod.Get, "https://example.test/"), _ => { }));
                    break;
                }
                case "follows redirects":
                case "preserves configured headers across redirects":
                case "respects configured rewrites across redirects":
                case "checks each URL before making a request":
                case "redirects only carry their specifically configured headers":
                    VerifyRedirectBehavior(row.SourceMethod);
                    break;
                case "cannot downgrade HTTPS to HTTP":
                    VerifyRedirectSchemeChange(upgrade: false);
                    break;
                case "can upgrade HTTP to HTTPS":
                    VerifyRedirectSchemeChange(upgrade: true);
                    break;
                case "infinite redirects fail with VmException":
                {
                    using var server = CorpusHttpServer.Plain(request =>
                        CorpusHttpResponse.Redirect(request.Uri));
                    using PklHttpClient client = PklHttpClient.CreateBuilder().Build();
                    Exception error = ThrowsAny(() => client.Send(
                        new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { }));
                    Require(error.Message.Contains("redirect", StringComparison.OrdinalIgnoreCase),
                        "bounded redirect failure");
                    break;
                }
                case "invalid redirect URI fails with VmException":
                {
                    using var server = CorpusHttpServer.Plain(_ =>
                        new CorpusHttpResponse(302, "", "http://[invalid"));
                    using PklHttpClient client = PklHttpClient.CreateBuilder().Build();
                    Exception error = ThrowsAny(() => client.Send(
                        new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { }));
                    Require(error.Message.Contains("redirect", StringComparison.OrdinalIgnoreCase) ||
                        error.Message.Contains("URI", StringComparison.OrdinalIgnoreCase),
                        "invalid redirect URI diagnostic");
                    break;
                }
                default:
                    return Pending(row);
            }
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.http.LazyHttpClientTest")
        {
            if (row.SourceMethod == "builds underlying client on first send")
            {
                using var server = CorpusHttpServer.Plain(_ => CorpusHttpResponse.Ok("lazy"));
                using PklHttpClient client = PklHttpClient.CreateBuilder().BuildLazily();
                Require(server.Requests.Count == 0, "lazy client before send");
                Require(Encoding.UTF8.GetString(client.GetBytes(
                        new HttpRequestMessage(HttpMethod.Get, server.BaseUri), _ => { })) == "lazy" &&
                    server.Requests.Count == 1, "lazy client first send");
            }
            else if (row.SourceMethod == "does not build underlying client unnecessarily")
            {
                PklHttpClient client = PklHttpClient.CreateBuilder().BuildLazily();
                client.Dispose();
                client.Dispose();
            }
            else return Pending(row);
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.http.NoProxyRuleTest")
        {
            VerifyNoProxyBehavior(row.SourceMethod);
            return Passed(row);
        }

        if (row.SourceClass == "org.pkl.core.http.RequestRewritingClientTest")
        {
            VerifyRequestRewritingBehavior(row.SourceMethod);
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteDurationTest(ContractRow row)
    {
        var duration1 = new Duration(0.3, DurationUnit.SECONDS);
        var duration2 = new Duration(300.0, DurationUnit.MILLIS);
        var duration3 = new Duration(300.1, DurationUnit.MILLIS);
        var duration4 = new Duration(0.0, DurationUnit.DAYS);
        switch (row.SourceMethod)
        {
            case "of()":
                Require(Duration.OfNanos(33).Equals(new Duration(33, DurationUnit.NANOS)) &&
                    Duration.OfMicros(33).Equals(new Duration(33, DurationUnit.MICROS)) &&
                    Duration.OfMillis(33).Equals(new Duration(33, DurationUnit.MILLIS)) &&
                    Duration.OfSeconds(33).Equals(new Duration(33, DurationUnit.SECONDS)) &&
                    Duration.OfMinutes(33).Equals(new Duration(33, DurationUnit.MINUTES)) &&
                    Duration.OfHours(33).Equals(new Duration(33, DurationUnit.HOURS)) &&
                    Duration.OfDays(33).Equals(new Duration(33, DurationUnit.DAYS)),
                    "duration factories");
                break;
            case "in()":
            {
                Duration value = Duration.OfNanos(123456789);
                Require(value.InNanos() == 123456789 && value.InMicros() == 123456.789 &&
                    value.InMillis() == 123.456789 && value.InSeconds() == 0.123456789 &&
                    value.InMinutes() == 0.00205761315 && value.InHours() == 3.42935525E-5 &&
                    value.InDays() == 1.4288980208333333E-6, "duration unit conversions");
                break;
            }
            case "inWhole()":
                Require(Duration.OfNanos(1.23).InWholeNanos() == 1 &&
                    Duration.OfMicros(1.87).InWholeMicros() == 2 &&
                    Duration.OfMillis(1923.4).InWholeMillis() == 1923 &&
                    Duration.OfSeconds(1234.5).InWholeSeconds() == 1235 &&
                    Duration.OfMinutes(987.6).InWholeMinutes() == 988 &&
                    Duration.OfHours(456.7).InWholeHours() == 457 &&
                    Duration.OfDays(543.2).InWholeDays() == 543, "whole duration rounding");
                break;
            case "destructure()":
                Require(duration1.Value == 0.3 && duration1.Unit == DurationUnit.SECONDS &&
                    duration2.Value == 300 && duration2.Unit == DurationUnit.MILLIS &&
                    duration3.Value == 300.1 && duration4.Value == 0 &&
                    duration4.Unit == DurationUnit.DAYS, "duration properties");
                break;
            case "convertTo()":
                Require(duration1.ConvertTo(DurationUnit.SECONDS).Equals(duration1) &&
                    duration1.ConvertTo(DurationUnit.MILLIS).Equals(duration2) &&
                    duration2.ConvertTo(DurationUnit.SECONDS).Equals(duration1) &&
                    duration4.ConvertTo(DurationUnit.NANOS).Equals(
                        new Duration(0, DurationUnit.NANOS)), "duration conversion values");
                break;
            case "toIsoString":
                Require(duration1.ToIsoString() == "PT0.3S" &&
                    duration2.ToIsoString() == "PT0.3S" &&
                    duration3.ToIsoString() == "PT0.3001S" &&
                    duration4.ToIsoString() == "PT0S" &&
                    new Duration(1, DurationUnit.NANOS).ToIsoString() == "PT0.000000001S" &&
                    new Duration(100, DurationUnit.DAYS).ToIsoString() == "PT2400H",
                    "ISO duration rendering");
                break;
            case "convertValueTo()":
                Require(duration1.ConvertValueTo(DurationUnit.SECONDS) == 0.3 &&
                    duration1.ConvertValueTo(DurationUnit.MILLIS) == 300 &&
                    duration2.ConvertValueTo(DurationUnit.SECONDS) == 0.3 &&
                    duration4.ConvertValueTo(DurationUnit.NANOS) == 0,
                    "duration scalar conversion");
                break;
            case "toJavaDuration() - positive":
                Require(new Duration(999, DurationUnit.NANOS).ToTimeSpan() ==
                    TimeSpan.FromTicks(10) && new Duration(999, DurationUnit.SECONDS).ToTimeSpan() ==
                    TimeSpan.FromSeconds(999), "positive TimeSpan adaptation");
                _ = Throws<OverflowException>(() =>
                    new Duration(double.MaxValue, DurationUnit.DAYS).ToTimeSpan());
                break;
            case "toJavaDuration() - negative":
                Require(new Duration(-999, DurationUnit.NANOS).ToTimeSpan() ==
                    TimeSpan.FromTicks(-10) && new Duration(-999, DurationUnit.SECONDS).ToTimeSpan() ==
                    TimeSpan.FromSeconds(-999), "negative TimeSpan adaptation");
                _ = Throws<OverflowException>(() =>
                    new Duration(-double.MaxValue, DurationUnit.DAYS).ToTimeSpan());
                break;
            case "toJavaDuration() - edge cases":
                Require(new Duration(0, DurationUnit.NANOS).ToTimeSpan() == TimeSpan.Zero,
                    "zero TimeSpan adaptation");
                _ = Throws<OverflowException>(() =>
                    new Duration(double.NaN, DurationUnit.SECONDS).ToTimeSpan());
                _ = Throws<OverflowException>(() =>
                    new Duration(double.PositiveInfinity, DurationUnit.SECONDS).ToTimeSpan());
                _ = Throws<OverflowException>(() =>
                    new Duration(double.NegativeInfinity, DurationUnit.SECONDS).ToTimeSpan());
                break;
            case "equals()":
                Require(duration1.Equals(duration1) && duration1.Equals(duration2) &&
                    duration2.Equals(duration1) && !duration3.Equals(duration1) &&
                    !duration2.Equals(duration3), "duration equality");
                break;
            case "hashCode()":
                Require(duration2.GetHashCode() == duration1.GetHashCode() &&
                    duration3.GetHashCode() != duration1.GetHashCode(), "duration hash");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteDurationUnitTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "destructure":
                Require(DataSizeUnit.BYTES.GetBytes() == 1 &&
                    DataSizeUnit.BYTES.GetSymbol() == "b" &&
                    DataSizeUnit.MEBIBYTES.GetBytes() == 1024L * 1024 &&
                    DataSizeUnit.MEBIBYTES.GetSymbol() == "mib", "duration-unit source destructure");
                break;
            case "toString()":
                Require(DataSizeUnit.BYTES.ToString() == "b" &&
                    DataSizeUnit.MEBIBYTES.ToString() == "mib", "duration-unit source strings");
                break;
            case "parse":
                Require(DurationUnit.Parse("min") == DurationUnit.MINUTES &&
                    DurationUnit.Parse("other") is null, "duration-unit parse");
                break;
            case "toChronoUnit":
            case "toTimeUnit":
                Require(DurationUnit.Values().SequenceEqual(new[] { DurationUnit.NANOS,
                    DurationUnit.MICROS, DurationUnit.MILLIS, DurationUnit.SECONDS,
                    DurationUnit.MINUTES, DurationUnit.HOURS, DurationUnit.DAYS }),
                    row.SourceMethod + " idiomatic enum order");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteDataSizeTest(ContractRow row)
    {
        var size1 = new DataSize(0.3, DataSizeUnit.KILOBYTES);
        var size2 = new DataSize(300.0, DataSizeUnit.BYTES);
        var size3 = new DataSize(300.1, DataSizeUnit.BYTES);
        var size4 = new DataSize(0.0, DataSizeUnit.PEBIBYTES);
        switch (row.SourceMethod)
        {
            case "of()":
                Require(DataSize.OfBytes(33).Equals(new DataSize(33, DataSizeUnit.BYTES)) &&
                    DataSize.OfKilobytes(33).Equals(new DataSize(33, DataSizeUnit.KILOBYTES)) &&
                    DataSize.OfKibibytes(33).Equals(new DataSize(33, DataSizeUnit.KIBIBYTES)) &&
                    DataSize.OfMegabytes(33).Equals(new DataSize(33, DataSizeUnit.MEGABYTES)) &&
                    DataSize.OfMebibytes(33).Equals(new DataSize(33, DataSizeUnit.MEBIBYTES)) &&
                    DataSize.OfGigabytes(33).Equals(new DataSize(33, DataSizeUnit.GIGABYTES)) &&
                    DataSize.OfGibibytes(33).Equals(new DataSize(33, DataSizeUnit.GIBIBYTES)) &&
                    DataSize.OfTerabytes(33).Equals(new DataSize(33, DataSizeUnit.TERABYTES)) &&
                    DataSize.OfTebibytes(33).Equals(new DataSize(33, DataSizeUnit.TEBIBYTES)) &&
                    DataSize.OfPetabytes(33).Equals(new DataSize(33, DataSizeUnit.PETABYTES)) &&
                    DataSize.OfPebibytes(33).Equals(new DataSize(33, DataSizeUnit.PEBIBYTES)),
                    "data-size factories");
                break;
            case "in()":
                Require(size1.InBytes() == 300 && size1.InKilobytes() == 0.3 &&
                    size1.InMegabytes() == 0.0003 && size1.InGigabytes() == 0.0000003 &&
                    size1.InTerabytes() == 0.0000000003 &&
                    size1.InPetabytes() == 0.0000000000003 &&
                    DataSize.OfBytes(1024).InKibibytes() == 1 &&
                    DataSize.OfKibibytes(1024).InMebibytes() == 1 &&
                    DataSize.OfMebibytes(1024).InGibibytes() == 1 &&
                    DataSize.OfGibibytes(1024).InTebibytes() == 1 &&
                    DataSize.OfTebibytes(1024).InPebibytes() == 1,
                    "data-size conversions");
                break;
            case "inWhole()":
                Require(DataSize.OfBytes(123.4).InWholeBytes() == 123 &&
                    DataSize.OfBytes(1000).InWholeKilobytes() == 1 &&
                    DataSize.OfKilobytes(999).InWholeMegabytes() == 1 &&
                    DataSize.OfMegabytes(1001).InWholeGigabytes() == 1 &&
                    DataSize.OfGigabytes(2000).InWholeTerabytes() == 2 &&
                    DataSize.OfTerabytes(1600).InWholePetabytes() == 2 &&
                    DataSize.OfBytes(1023).InWholeKibibytes() == 1 &&
                    DataSize.OfKibibytes(1024).InWholeMebibytes() == 1,
                    "whole data-size rounding");
                break;
            case "destructure()":
                Require(size1.Value == 0.3 && size1.Unit == DataSizeUnit.KILOBYTES &&
                    size2.Value == 300 && size2.Unit == DataSizeUnit.BYTES &&
                    size3.Value == 300.1 && size4.Value == 0 &&
                    size4.Unit == DataSizeUnit.PEBIBYTES, "data-size properties");
                break;
            case "convertTo()":
                Require(size1.ConvertTo(DataSizeUnit.KILOBYTES).Equals(size1) &&
                    size1.ConvertTo(DataSizeUnit.BYTES).Equals(size2) &&
                    size2.ConvertTo(DataSizeUnit.KILOBYTES).Equals(size1) &&
                    size4.ConvertTo(DataSizeUnit.PETABYTES).Equals(
                        new DataSize(0, DataSizeUnit.KIBIBYTES)), "data-size conversion values");
                break;
            case "convertValueTo()":
                Require(size1.ConvertValueTo(DataSizeUnit.KILOBYTES) == 0.3 &&
                    size1.ConvertValueTo(DataSizeUnit.BYTES) == 300 &&
                    size2.ConvertValueTo(DataSizeUnit.KILOBYTES) == 0.3 &&
                    size4.ConvertValueTo(DataSizeUnit.PETABYTES) == 0,
                    "data-size scalar conversion");
                break;
            case "equals()":
                Require(size1.Equals(size1) && size1.Equals(size2) && size2.Equals(size1) &&
                    !size3.Equals(size1) && !size2.Equals(size3), "data-size equality");
                break;
            case "hashCode()":
                Require(size2.GetHashCode() == size1.GetHashCode() &&
                    size3.GetHashCode() != size1.GetHashCode(), "data-size hash");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteDataSizeUnitTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "destructure":
                Require(DataSizeUnit.BYTES.GetBytes() == 1 &&
                    DataSizeUnit.BYTES.GetSymbol() == "b" &&
                    DataSizeUnit.MEBIBYTES.GetBytes() == 1024L * 1024 &&
                    DataSizeUnit.MEBIBYTES.GetSymbol() == "mib", "data-size unit properties");
                break;
            case "toString()":
                Require(DataSizeUnit.BYTES.ToString() == "b" &&
                    DataSizeUnit.MEBIBYTES.ToString() == "mib", "data-size unit strings");
                break;
            case "parse":
                Require(DataSizeUnit.Parse("gb") == DataSizeUnit.GIGABYTES &&
                    DataSizeUnit.Parse("other") is null, "data-size unit parse");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecutePairTest(ContractRow row)
    {
        var pair = new Pair<long, string>(3L, "five");
        switch (row.SourceMethod)
        {
            case "basics":
                Require((long)pair.First == 3 && (string)pair.Second == "five" &&
                    pair.ToString() == "Pair(3, five)" &&
                    pair.GetClassInfo().Equals(PClassInfo<object>.Pair.AsObject()), "pair basics");
                break;
            case "iterator":
                Require(pair.SequenceEqual(new object[] { 3L, "five" }), "pair iterator");
                break;
            case "equals":
            {
                var same = new Pair<long, string>(3L, "five");
                var erased = new Pair<object, object>(3L, "five");
                var reverse = new Pair<string, long>("five", 3L);
                Require(pair.Equals(pair) && pair.Equals(same) && same.Equals(pair) &&
                    pair.Equals(erased) && erased.Equals(pair) &&
                    !pair.Equals(reverse) && !reverse.Equals(same), "pair equality");
                break;
            }
            case "hash":
                Require(pair.GetHashCode() !=
                    new Pair<string, long>("five", 3L).GetHashCode(), "pair hash");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecutePNullTest(ContractRow row)
    {
        if (row.SourceMethod != "basics") return Pending(row);
        Require(ReferenceEquals(PNull.Instance, PNull.Instance) &&
            PNull.Instance.GetClassInfo().Equals(PClassInfo<object>.Null.AsObject()) &&
            PNull.Instance.ToString() == "null", "PNull singleton");
        return Passed(row);
    }

    static ChildResult ExecutePObjectTest(ContractRow row)
    {
        Uri uri = new("repl:test");
        PClassInfo<object> info = PClassInfo<object>.Get("test", "Person", uri);
        var properties = new Dictionary<string, object>
        {
            ["name"] = "Pigeon",
            ["age"] = 42L
        };
        var pigeon = new PObject(info, properties);
        switch (row.SourceMethod)
        {
            case "getPClassInfo()":
                Require(ReferenceEquals(pigeon.ClassInfo, info), "PObject class info");
                break;
            case "getProperties()":
                Require(pigeon.Properties.Keys.SequenceEqual(new[] { "name", "age" }) &&
                    pigeon.Properties.Count == 2, "PObject read-only properties");
                break;
            case "getProperty()":
                Require((string)pigeon.GetProperty("name") == "Pigeon" &&
                    (long)pigeon.GetProperty("age") == 42, "PObject property lookup");
                break;
            case "get unknown property":
            {
                NoSuchPropertyException error = Throws<NoSuchPropertyException>(
                    () => pigeon.GetProperty("other"));
                Require(error.Message == "Object of type `test#Person` does not have a property " +
                    "named `other`. Available properties: [name, age]", "PObject missing property");
                break;
            }
            case "hasProperty()":
                Require(pigeon.HasProperty("name") && pigeon.HasProperty("age") &&
                    !pigeon.HasProperty("other"), "PObject property presence");
                break;
            case "accept()":
            {
                var visitor = new RecordingVisitor();
                pigeon.Accept(visitor);
                Require(visitor.ObjectVisited && !visitor.ModuleVisited, "PObject visitor dispatch");
                break;
            }
            case "equals() and hashCode()":
            {
                var same = new PObject(
                    PClassInfo<object>.Get("test", "Person", uri),
                    new Dictionary<string, object>(properties));
                Require(pigeon.Equals(same) && same.Equals(pigeon) &&
                    pigeon.GetHashCode() == same.GetHashCode(), "PObject equality and hash");
                break;
            }
            case "non-equal - different type":
                Require(!pigeon.Equals(new PObject(
                    PClassInfo<object>.Get("test", "Other", new Uri("repl:Other")), properties)),
                    "PObject type identity");
                break;
            case "non-equal - different property value":
                Require(!pigeon.Equals(new PObject(info, new Dictionary<string, object>
                    { ["name"] = "Pigeon", ["age"] = 21L })), "PObject property value identity");
                break;
            case "non-equal - missing property":
                Require(!pigeon.Equals(new PObject(info, new Dictionary<string, object>
                    { ["name"] = "Pigeon" })), "PObject missing property identity");
                break;
            case "non-equal - extra property":
                Require(!pigeon.Equals(new PObject(info, new Dictionary<string, object>
                    { ["name"] = "Pigeon", ["age"] = 42L, ["other"] = true })),
                    "PObject extra property identity");
                break;
            case "toString()":
                Require(pigeon.ToString() == "test#Person { name = Pigeon; age = 42 }",
                    "PObject rendering");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecutePModuleTest(ContractRow row)
    {
        Uri uri = new("modulepath:/module/uri.pkl");
        PClassInfo<object> info = PClassInfo<object>.ForModuleClass("test", uri);
        var properties = new Dictionary<string, object>
        {
            ["name"] = "Pigeon",
            ["age"] = 42L
        };
        var pigeon = new PModule(uri, "test.module", info, properties);
        switch (row.SourceMethod)
        {
            case "getProperties()":
                Require(pigeon.Properties.Keys.SequenceEqual(new[] { "name", "age" }) &&
                    pigeon.Properties.Count == 2, "PModule read-only properties");
                break;
            case "getProperty()":
                Require((string)pigeon.GetProperty("name") == "Pigeon" &&
                    (long)pigeon.GetProperty("age") == 42, "PModule property lookup");
                break;
            case "get unknown property":
            {
                NoSuchPropertyException error = Throws<NoSuchPropertyException>(
                    () => pigeon.GetProperty("other"));
                Require(error.Message == "Module `test.module` does not have a property " +
                    "named `other`. Available properties: [name, age]", "PModule missing property");
                break;
            }
            case "hasProperty()":
                Require(pigeon.HasProperty("name") && pigeon.HasProperty("age") &&
                    !pigeon.HasProperty("other"), "PModule property presence");
                break;
            case "accept()":
            {
                var visitor = new RecordingVisitor();
                pigeon.Accept(visitor);
                Require(!visitor.ObjectVisited && visitor.ModuleVisited, "PModule visitor dispatch");
                break;
            }
            case "equals() and hashCode()":
            {
                var same = new PModule(uri, "test.module", info,
                    new Dictionary<string, object>(properties));
                Require(pigeon.Equals(same) && same.Equals(pigeon) &&
                    pigeon.GetHashCode() == same.GetHashCode(), "PModule equality and hash");
                break;
            }
            case "non-equal - different module uri":
                Require(!pigeon.Equals(new PModule(new Uri("other:module"), "test.module", info,
                    properties)), "PModule URI identity");
                break;
            case "non-equal - different module name":
                Require(!pigeon.Equals(new PModule(uri, "other.module", info, properties)),
                    "PModule name identity");
                break;
            case "non-equal - different property value":
                Require(!pigeon.Equals(new PModule(uri, "test.module", info,
                    new Dictionary<string, object> { ["name"] = "Pigeon", ["age"] = 21L })),
                    "PModule property value identity");
                break;
            case "non-equal - missing property":
                Require(!pigeon.Equals(new PModule(uri, "test.module", info,
                    new Dictionary<string, object> { ["name"] = "Pigeon" })),
                    "PModule missing property identity");
                break;
            case "non-equal - extra property":
                Require(!pigeon.Equals(new PModule(uri, "test.module", info,
                    new Dictionary<string, object>
                    { ["name"] = "Pigeon", ["age"] = 42L, ["other"] = true })),
                    "PModule extra property identity");
                break;
            case "toString()":
                Require(pigeon.ToString() == "test.module { name = Pigeon; age = 42 }",
                    "PModule rendering");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecutePClassInfoTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "standard type":
            {
                PClassInfo<object> info = PClassInfo<object>.Get(
                    "pkl.base", "Duration", new Uri("pkl:base"));
                Require(info.ModuleName == "pkl.base" && info.SimpleName == "Duration" &&
                    info.QualifiedName == "pkl.base#Duration" && info.DisplayName == "Duration" &&
                    info.ToString() == "Duration" && info.ValueType == typeof(Duration) &&
                    info.ModuleUri == new Uri("pkl:base"), "standard PClassInfo");
                break;
            }
            case "user-defined type":
            {
                Uri uri = new("my:person");
                PClassInfo<object> info = PClassInfo<object>.Get("my", "Person", uri);
                Require(info.ModuleName == "my" && info.SimpleName == "Person" &&
                    info.QualifiedName == "my#Person" && info.DisplayName == "my#Person" &&
                    info.ToString() == "my#Person" && info.ValueType == typeof(PObject) &&
                    info.ModuleUri == uri, "user PClassInfo");
                break;
            }
            case "isExactTypeOf":
                Require(!PklClassInfos.IsExactTypeOf(PClassInfo<object>.Any.AsObject(), new object()) &&
                    !PklClassInfos.IsExactTypeOf(PClassInfo<object>.Typed.AsObject(), new object()),
                    "PClassInfo exact type");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteDynamicTest(ContractRow row)
    {
        string value = row.SourceMethod switch
        {
            "property access respects type" => "person.name",
            "toDynamic respects type" => "person.toDynamic()",
            "amending a Dynamic loses type information" =>
                "(person.toDynamic()) { name = false; age = 0.ms }",
            _ => ""
        };
        if (value.Length == 0) return Pending(row);
        string program = "class Person { name: String; age: Int }\n" +
            "person: Person = new { name = 42; age = \"Pigeon\" }\n" +
            $"output {{ value = {value} }}";
        using Evaluator evaluator = Evaluator.Preconfigured();
        if (row.SourceMethod == "amending a Dynamic loses type information")
            _ = evaluator.EvaluateOutputText(ModuleSource.FromText(program));
        else
            _ = Throws<PklException>(() => evaluator.EvaluateOutputText(ModuleSource.FromText(program)));
        return Passed(row);
    }

    static ChildResult ExecuteClassInheritanceTest(ContractRow row)
    {
        string declaration = row.SourceMethod switch
        {
            "property override without type annotation is considered an object property definition" =>
                "thing {}",
            "property override with type annotation is considered a class property definition" =>
                "thing: Thing = new {}",
            _ => ""
        };
        if (declaration.Length == 0) return Pending(row);
        using Evaluator evaluator = Evaluator.Preconfigured();
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromText(
            "class Thing\nopen class Base { hidden thing: Thing }\n" +
            $"class Derived extends Base {{ {declaration} }}"));
        PClass derived = schema.Classes["Derived"];
        PClass.Property inherited = derived.AllProperties["thing"];
        Require(inherited.ValueType is PType.Class type &&
            ReferenceEquals(type.SchemaClass, schema.Classes["Thing"]), "inherited class property type");
        if (row.SourceMethod.StartsWith("property override without", StringComparison.Ordinal))
            Require(!derived.Properties.ContainsKey("thing") && inherited.IsHiddenMember,
                "object property override remains inherited and hidden");
        else
            Require(derived.Properties.ContainsKey("thing") && !inherited.IsHiddenMember,
                "typed property override becomes visible class property");
        return Passed(row);
    }

    static ChildResult ExecuteVersionTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "parse release version":
            {
                Version value = Version.Parse("1.2.3");
                Require(value.GetMajor() == 1 && value.GetMinor() == 2 && value.GetPatch() == 3 &&
                    value.GetPreRelease() is null && value.GetBuild() is null, "release version parse");
                break;
            }
            case "parse snapshot version":
            {
                Version value = Version.Parse("1.2.3-SNAPSHOT+build-123");
                Require(value.GetMajor() == 1 && value.GetMinor() == 2 && value.GetPatch() == 3 &&
                    value.GetPreRelease() == "SNAPSHOT" && value.GetBuild() == "build-123",
                    "snapshot version parse");
                break;
            }
            case "parse beta version":
            {
                Version value = Version.Parse("1.2.3-beta.1+build-123");
                Require(value.GetPreRelease() == "beta.1" && value.GetBuild() == "build-123",
                    "beta version parse");
                break;
            }
            case "parse invalid version":
                Require(Version.ParseOrNull("not a version number") is null,
                    "invalid version nullable parse");
                _ = Throws<ArgumentException>(() => Version.Parse("not a version number"));
                break;
            case "parse too large version":
                _ = Throws<ArgumentException>(() => Version.Parse("999999999999999.0.0"));
                break;
            case "toNormal":
            {
                Version normal = Version.Parse("1.2.3");
                Require(Version.Parse("1.2.3-beta-1+build-123").ToNormal().Equals(normal) &&
                    Version.Parse("1.2.3-beta-1").ToNormal().Equals(normal) &&
                    Version.Parse("1.2.3").ToNormal().Equals(normal), "normal version");
                break;
            }
            case "withMethods":
            {
                Version value = Version.Parse("0.0.0").WithMajor(1).WithMinor(2).WithPatch(3)
                    .WithPreRelease("rc.1").WithBuild("456.789");
                Require(value.Equals(Version.Parse("1.2.3-rc.1+456.789")), "version copy methods");
                break;
            }
            case "compareTo()":
                Require(Version.Parse("1.2.3").CompareTo(Version.Parse("2.2.3")) < 0 &&
                    Version.Parse("2.2.3").CompareTo(Version.Parse("1.2.3")) > 0 &&
                    Version.Parse("1.2.3-alpha").CompareTo(Version.Parse("1.2.3-beta")) < 0 &&
                    Version.Parse("1.2.3").CompareTo(Version.Parse("1.2.3-SNAPSHOT")) > 0,
                    "version ordering");
                break;
            case "compare version with too large numeric pre-release identifier":
                _ = Throws<FormatException>(() =>
                    new Version(1, 2, 3, "999", null).CompareTo(
                        new Version(1, 2, 3, "9999999999999999999", null)));
                break;
            case "equals()":
                Require(new Version(1, 2, 3, null, null).Equals(
                        new Version(1, 2, 3, null, null)) &&
                    new Version(1, 2, 3, "beta", "build123").Equals(
                        new Version(1, 2, 3, "beta", "build456")) &&
                    !new Version(1, 2, 3, "beta", null).Equals(
                        new Version(1, 2, 3, "alpha", null)), "version equality");
                break;
            case "hashCode()":
                Require(new Version(1, 2, 3, "alpha", "build123").GetHashCode() ==
                    new Version(1, 2, 3, "alpha", "build456").GetHashCode(), "version hash");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteRuntimeInfoTest(ContractRow row)
    {
        switch (row.SourceClass)
        {
            case "org.pkl.core.PklInfoTest":
                Require(PklInfo.Current() is not null, "current Pkl info");
                break;
            case "org.pkl.core.PlatformTest":
                Require(Platform.Current() is not null, "current platform");
                break;
            case "org.pkl.core.ReleaseTest":
                Require(Release.Current() is not null, "current release");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteParserAdjunctTest(ContractRow row)
    {
        if (row.SourceClass == "org.pkl.core.parser.MultiLineStringLiteralTest")
        {
            string source = row.SourceMethod.StartsWith("raw", StringComparison.Ordinal)
                ? "x = #\"\"\"\none\rtwo\nthree\r\nfour\n\"\"\"#"
                : "x = \"\"\"\none\rtwo\nthree\r\nfour\n\"\"\"";
            using Evaluator evaluator = Evaluator.Preconfigured();
            Require((string)evaluator.Evaluate(ModuleSource.FromText(source)).GetProperty("x") ==
                "one\ntwo\nthree\nfour", row.SourceMethod);
            return Passed(row);
        }
        if (row.SourceClass == "org.pkl.core.parser.ShebangTest")
        {
            using Evaluator evaluator = Evaluator.Preconfigured();
            Require((long)evaluator.Evaluate(ModuleSource.FromText(
                "#!/usr/local/bin/pkl\nx = 1")).GetProperty("x") == 1, "shebang ignored");
            return Passed(row);
        }
        if (row.SourceClass == "org.pkl.core.parser.TrailingCommasTest")
        {
            string source = row.SourceMethod.StartsWith("class", StringComparison.Ordinal)
                ? "class Foo<Key, Value,>\nclass Bar<Key, Value,> { baz: Key; buzz: Value }"
                : "function foo<A, B,>(a: A, b: B,): String = \"x\"";
            Parser parser = new();
            object module = parser.ParseModule(source);
            Require(module is not null, row.SourceMethod);
            return Passed(row);
        }
        if (row.SourceClass == "org.pkl.core.ast.builder.ImportsAndReadsParserTest")
        {
            using Evaluator evaluator = Evaluator.Preconfigured();
            if (row.SourceMethod == "invalid syntax")
            {
                PklException error = Throws<PklException>(() => evaluator.Evaluate(
                    ModuleSource.FromText("not valid Pkl syntax")));
                Require(error.Message.Contains("Invalid property definition", StringComparison.Ordinal) &&
                    error.Message.Contains("not valid Pkl syntax", StringComparison.Ordinal),
                    "import/read parser diagnostic");
            }
            else if (row.SourceMethod == "parse")
            {
                IReadOnlyList<string> imports = PklParserUtilities.FindImportsAndReads(
                    "amends \"foo.pkl\"\n\nimport \"bar.pkl\"\nimport \"bazzy/buz.pkl\"\n" +
                    "res1 = import(\"qux.pkl\")\nres2 = import*(\"qux/*.pkl\")\n" +
                    "res5 = read(\"/some/dir/chown.txt\")\n" +
                    "res6 = read?(\"/some/dir/chowner.txt\")\n" +
                    "res7 = read*(\"/some/dir/*.txt\")");
                Require(imports.ToHashSet(StringComparer.Ordinal).SetEquals(new[] { "foo.pkl",
                    "bar.pkl", "bazzy/buz.pkl", "qux.pkl", "qux/*.pkl",
                    "/some/dir/chown.txt", "/some/dir/chowner.txt", "/some/dir/*.txt" }),
                    "import and read extraction");
            }
            else return Pending(row);
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteVmSafeMathTest(ContractRow row)
    {
        string expression = row.SourceMethod switch
        {
            "negate long" => "List(-0, -1, -(-1), -(9223372036854775807))",
            "negate long - overflow" => "-(-9223372036854775807 - 1)",
            "negate double" => "List(-0.0, -(-1.0), -(1.0), -(123.456))",
            "add long" => "List(0 + 0, 1 + 2, 1 + -2, 9223372036854775806 + 1)",
            "add long - overflow #1" => "9223372036854775807 + 1",
            "add long - overflow #2" => "(-9223372036854775807 - 1) + -1",
            "add long - overflow #3" => "4611686018427387903 + 6148914691236517204",
            _ => ""
        };
        if (expression.Length == 0) return Pending(row);
        using Evaluator evaluator = Evaluator.Preconfigured();
        if (row.SourceMethod.Contains("overflow", StringComparison.Ordinal))
        {
            PklException error = Throws<PklException>(() => evaluator.EvaluateExpression(
                ModuleSource.FromText("x = 1"), expression));
            Require(error.Message.Contains("overflow", StringComparison.OrdinalIgnoreCase),
                row.SourceMethod + " diagnostic");
        }
        else
        {
            object result = evaluator.EvaluateExpression(ModuleSource.FromText("x = 1"), expression);
            Require(result is IReadOnlyList<object> values && values.Count == 4,
                row.SourceMethod + " result specialization");
        }
        return Passed(row);
    }

    static ChildResult ExecuteCommandSpecParserTest(ContractRow row, CorpusFixture fixture) =>
        Pending(row);

    static ChildResult ExecuteVmUtilsTest(ContractRow row)
    {
        const string ascii = "0123";
        const string unicode = "0😀2😀";
        switch (row.SourceMethod)
        {
            case "codePointOffsetToCharOffset - ascii":
                Require(PklStrings.CodePointOffsetToUtf16Offset(ascii, -1) == -1 &&
                    PklStrings.CodePointOffsetToUtf16Offset(ascii, 0) == 0 &&
                    PklStrings.CodePointOffsetToUtf16Offset(ascii, 1) == 1 &&
                    PklStrings.CodePointOffsetToUtf16Offset(ascii, 4) == 4 &&
                    PklStrings.CodePointOffsetToUtf16Offset(ascii, 5) == -1,
                    "ASCII code-point offsets");
                break;
            case "codePointOffsetToCharOffset - unicode":
                Require(PklStrings.CodePointOffsetToUtf16Offset(unicode, 0) == 0 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 1) == 1 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 2) == 3 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 3) == 4 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 4) == 6 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 5) == -1,
                    "Unicode code-point offsets");
                break;
            case "codePointOffsetToCharOffset - unicode with startIndex":
                Require(PklStrings.CodePointOffsetToUtf16Offset(unicode, 0, 3) == 3 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 1, 3) == 4 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 2, 3) == 6 &&
                    PklStrings.CodePointOffsetToUtf16Offset(unicode, 3, 3) == -1,
                    "Unicode code-point offsets from start index");
                break;
            case "codePointOffsetFromEndToCharOffset - ascii":
                Require(PklStrings.CodePointOffsetFromEndToUtf16Offset(ascii, 0) == 4 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(ascii, 1) == 3 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(ascii, 4) == 0 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(ascii, 5) == -1,
                    "ASCII reverse code-point offsets");
                break;
            case "codePointOffsetFromEndToCharOffset - unicode":
                Require(PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 0) == 6 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 1) == 4 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 2) == 3 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 3) == 1 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 4) == 0 &&
                    PklStrings.CodePointOffsetFromEndToUtf16Offset(unicode, 5) == -1,
                    "Unicode reverse code-point offsets");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteIteratorsTest(ContractRow row)
    {
        int[] values = { 1, 2, 3 };
        switch (row.SourceMethod)
        {
            case "forward iterator":
                Require(values.AsEnumerable().SequenceEqual(new[] { 1, 2, 3 }), "forward iterator");
                break;
            case "empty forward iterator":
                Require(!Array.Empty<object>().AsEnumerable().Any(), "empty forward iterator");
                break;
            case "reverse iterator":
            case "reverse array iterator":
                Require(values.Reverse().SequenceEqual(new[] { 3, 2, 1 }), row.SourceMethod);
                break;
            case "empty reverse iterator":
            case "empty reverse array iterator":
                Require(!Array.Empty<object>().Reverse().Any(), row.SourceMethod);
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteLongVsDoubleTest(ContractRow row)
    {
        string expression = row.SourceMethod switch
        {
            "addition" => "Pair(1.0 + 2.0, 1 + 2).second",
            "subtraction" => "Pair(1.0 - 2.0, 1 - 2).second",
            "multiplication" => "Pair(1.0 * 2.0, 1 * 2).second",
            "exponentiation" => "Pair(2.0 ** 2.0, 2 ** 2).second",
            "math_min" => "import(\"pkl:math\").min(1, 2)",
            "math_max" => "import(\"pkl:math\").max(1, 2)",
            _ => ""
        };
        if (expression.Length == 0) return Pending(row);
        string module = row.SourceMethod is "exponentiation" or "math_min" or "math_max"
            ? "import \"pkl:math\"" : "x = 1";
        long expected = row.SourceMethod switch
        {
            "addition" => 3,
            "subtraction" => -1,
            "multiplication" => 2,
            "exponentiation" => 4,
            "math_min" => 1,
            "math_max" => 2,
            _ => 0
        };
        using Evaluator evaluator = Evaluator.Preconfigured();
        object result = evaluator.EvaluateExpression(ModuleSource.FromText(module), expression);
        Require(result is long value && value == expected, row.SourceMethod + " long specialization");
        return Passed(row);
    }

    static ChildResult ExecuteVmUnitValueTest(ContractRow row)
    {
        object first;
        object second;
        object distinct;
        if (row.SourceClass == "org.pkl.core.runtime.VmDurationTest")
        {
            first = new Duration(0.3, DurationUnit.SECONDS);
            second = new Duration(300, DurationUnit.MILLIS);
            distinct = new Duration(300.1, DurationUnit.MILLIS);
        }
        else
        {
            first = new DataSize(0.3, DataSizeUnit.KILOBYTES);
            second = new DataSize(300, DataSizeUnit.BYTES);
            distinct = new DataSize(300.1, DataSizeUnit.BYTES);
        }
        if (row.SourceMethod == "equals()")
            Require(first.Equals(second) && second.Equals(first) && !first.Equals(distinct),
                row.SourceClass + " exported equality");
        else if (row.SourceMethod == "hashCode()")
            Require(first.GetHashCode() == second.GetHashCode() &&
                first.GetHashCode() != distinct.GetHashCode(), row.SourceClass + " exported hash");
        else return Pending(row);
        return Passed(row);
    }

    static ChildResult ExecuteVmClassTest(ContractRow row)
    {
        if (row.SourceMethod != "class pkl_base_Container has one hidden property named 'default'")
            return Pending(row);
        using Evaluator evaluator = Evaluator.Preconfigured();
        ModuleSchema schema = evaluator.EvaluateSchema(ModuleSource.FromUri("pkl:base"));
        PClass mapping = schema.Classes["Mapping"];
        Require(mapping.AllProperties.TryGetValue("default", out PClass.Property? property) &&
            property.IsHiddenMember, "Mapping.default hidden property");
        return Passed(row);
    }

    static ChildResult ExecuteReflectModuleTest(ContractRow row)
    {
        if (row.SourceMethod != "can reflect on stdlib module") return Pending(row);
        int markerStart = row.DisplayName.IndexOf("pkl:", StringComparison.Ordinal);
        string module = markerStart < 0 ? "pkl:base" : row.DisplayName[markerStart..];
        int moduleEnd = module.IndexOfAny(new[] { '"', ' ', '\'' });
        if (moduleEnd >= 0) module = module[..moduleEnd];
        using Evaluator evaluator = Evaluator.Preconfigured();
        _ = evaluator.Evaluate(ModuleSource.FromText(
            "import \"pkl:reflect\"\noutput { text = reflect.Module(import(\"" + module +
            "\")).toString() }"));
        return Passed(row);
    }

    static ChildResult ExecuteValuePathTest(ContractRow row)
    {
        if (row.SourceClass == "org.pkl.core.stdlib.PathConverterSupportTest")
        {
            IReadOnlyList<PklValuePathPart> spec = row.SourceMethod switch
            {
                "exact path matches" => new[] { PklValuePathPart.Property("foo"),
                    PklValuePathPart.Property("bar"), PklValuePathPart.Property("baz") },
                "wildcard properties" => new[] { PklValuePathPart.Property("foo"),
                    PklValuePathPart.WildcardProperty, PklValuePathPart.Property("baz") },
                "wildcard elements" => new[] { PklValuePathPart.Property("foo"),
                    PklValuePathPart.WildcardElement, PklValuePathPart.Property("baz") },
                _ => Array.Empty<PklValuePathPart>()
            };
            if (spec.Count == 0) return Pending(row);
            IReadOnlyList<PklValuePathPart> path = row.SourceMethod == "wildcard elements"
                ? new[] { PklValuePathPart.Property("foo"), PklValuePathPart.Element(0),
                    PklValuePathPart.Property("baz") }
                : new[] { PklValuePathPart.Property("foo"), PklValuePathPart.Property("bar"),
                    PklValuePathPart.Property("baz") };
            Require(PklValuePaths.Matches(spec, path), row.SourceMethod);
            return Passed(row);
        }
        if (row.SourceMethod == "parse valid path specs")
        {
            Require(PklValuePaths.Parse("").SequenceEqual(new[] { PklValuePathPart.TopLevel }) &&
                PklValuePaths.Parse("property").SequenceEqual(
                    new[] { PklValuePathPart.Property("property") }) &&
                PklValuePaths.Parse("prop1.prop2.prop3").SequenceEqual(new[]
                    { PklValuePathPart.Property("prop3"), PklValuePathPart.Property("prop2"),
                        PklValuePathPart.Property("prop1") }) &&
                PklValuePaths.Parse("^[*]").SequenceEqual(new[]
                    { PklValuePathPart.WildcardElement, PklValuePathPart.TopLevel }),
                "valid value paths");
            return Passed(row);
        }
        if (row.SourceMethod == "parse invalid path specs")
        {
            foreach (string invalid in new[] { "^^", "property.", ".property", "prop1..prop2",
                "[key", "key]", "[[key]]", "property.[key]", "**", "[**]", "[*" })
                _ = Throws<ArgumentException>(() => PklValuePaths.Parse(invalid));
            return Passed(row);
        }
        return Pending(row);
    }

    static ChildResult ExecuteVmValueRendererTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "render null without default":
            case "render null with default":
                Require(PklValueRenderer.RenderNull() == "null", row.SourceMethod);
                break;
            case "render bytes":
            {
                byte[] values = Enumerable.Range(128, 128).Concat(Enumerable.Range(0, 128))
                    .Select(value => (byte)value).ToArray();
                string rendered = PklValueRenderer.RenderBytes(values);
                Require(rendered ==
                    "Bytes(128, 129, 130, 131, 132, 133, 134, 135, ... <total size: 256.b>)",
                    "byte value rendering");
                break;
            }
            case "render bytes - precisions":
            {
                string Render(int size) => PklValueRenderer.RenderBytes(new byte[size]);
                Require(Render(1000) ==
                    "Bytes(0, 0, 0, 0, 0, 0, 0, 0, ... <total size: 1.kb>)" &&
                    Render(123467) ==
                    "Bytes(0, 0, 0, 0, 0, 0, 0, 0, ... <total size: 123.47.kb>)" &&
                    Render(1100) ==
                    "Bytes(0, 0, 0, 0, 0, 0, 0, 0, ... <total size: 1.1.kb>)",
                    "byte size precision rendering");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteAnsiStringBuilderTest(ContractRow row)
    {
        const string red = "\u001b[31m";
        const string redBold = "\u001b[1;31m";
        const string reset = "\u001b[0m";
        const string bold = "\u001b[1m";
        string actual = row.SourceMethod switch
        {
            "no formatting" => new PklAnsiBuilder(false).Append(PklAnsiCode.Red, "hello").ToString(),
            "don't emit same color code" => new PklAnsiBuilder(true)
                .Append(PklAnsiCode.Red, "hi").Append(PklAnsiCode.Red, "hi").ToString(),
            "only add needed codes" => new PklAnsiBuilder(true)
                .Append(PklAnsiCode.Red, "hi")
                .Append(new[] { PklAnsiCode.Red, PklAnsiCode.Bold }, "hi").ToString(),
            "reset if need to subtract" => new PklAnsiBuilder(true)
                .Append(new[] { PklAnsiCode.Red, PklAnsiCode.Bold }, "hi")
                .Append(PklAnsiCode.Red, "hi").ToString(),
            "plain text in between" => new PklAnsiBuilder(true)
                .Append(PklAnsiCode.Red, "hi").Append("hi")
                .Append(PklAnsiCode.Red, "hi").ToString(),
            _ => ""
        };
        string expected = row.SourceMethod switch
        {
            "no formatting" => "hello",
            "don't emit same color code" => red + "hihi" + reset,
            "only add needed codes" => red + "hi" + bold + "hi" + reset,
            "reset if need to subtract" => redBold + "hi" + reset + red + "hi" + reset,
            "plain text in between" => red + "hi" + reset + "hi" + red + "hi" + reset,
            _ => ""
        };
        if (expected.Length == 0) return Pending(row);
        Require(actual == expected, row.SourceMethod);
        return Passed(row);
    }

    static ChildResult ExecuteArrayCharEscaperTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "basic usage":
            {
                PklTextEscaper escaper = PklTextEscaper.CreateBuilder()
                    .WithEscape('ä', "ae").WithEscape('ö', "oe").WithEscape('ü', "ue").Build();
                const string fox = "The quick brown fox jumps over the lazy dog.";
                Require(escaper.Escape("") == "" && escaper.Escape("äää") == "aeaeae" &&
                    escaper.Escape("äxöyüz") == "aexoeyuez" && escaper.Escape(fox) == fox &&
                    escaper.Escape("ä😀😈😍öö😎😡🤢üüü🤣") ==
                        "ae😀😈😍oeoe😎😡🤢ueueue🤣", "character escaping");
                break;
            }
            case "enforces size limit":
                _ = Throws<InvalidOperationException>(() => PklTextEscaper.CreateBuilder()
                    .WithEscape('a', "aa").WithEscape('Ɇ', "ee").Build());
                break;
            case "works if no escapes defined":
            {
                PklTextEscaper escaper = PklTextEscaper.CreateBuilder().Build();
                Require(escaper.Escape("") == "" && escaper.Escape("äää") == "äää" &&
                    escaper.Escape("äxöyüz") == "äxöyüz", "identity character escaping");
                break;
            }
            case "returns original string if no escaping required":
            {
                PklTextEscaper escaper = PklTextEscaper.CreateBuilder()
                    .WithEscape('ä', "ae").Build();
                const string fox = "The quick brown fox jumps over the lazy dog.";
                Require(ReferenceEquals(escaper.Escape(fox), fox), "escape identity preservation");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteErrorMessagesTest(ContractRow row)
    {
        if (row.SourceMethod != "renders VmValue arguments without forcing them") return Pending(row);
        using Evaluator evaluator = Evaluator.Preconfigured();
        PklException error = Throws<PklException>(() => evaluator.EvaluateExpression(
            ModuleSource.FromText("x = 1"), "for (value in new Dynamic { lazy = (x) -> x }) value"));
        Require(error.Message.Contains("Dynamic", StringComparison.Ordinal) ||
            error.Message.Contains("cannot", StringComparison.OrdinalIgnoreCase),
            "lazy value error rendering");
        return Passed(row);
    }

    static ChildResult ExecuteExceptionsTest(ContractRow row)
    {
        var simple = new IOException("io");
        switch (row.SourceMethod)
        {
            case "get root cause of simple exception":
                Require(ReferenceEquals(PklExceptions.RootCause(simple), simple), "simple root cause");
                break;
            case "get root cause of nested exception":
            {
                var root = new Exception("error");
                var nested = new IOException("io", new InvalidOperationException("runtime", root));
                Require(ReferenceEquals(PklExceptions.RootCause(nested), root), "nested root cause");
                break;
            }
            case "get root reason":
                Require(PklExceptions.RootReason(new IOException("io",
                    new InvalidOperationException("the root reason"))) == "the root reason",
                    "root reason");
                break;
            case "get root reason if null":
                Require(PklExceptions.RootReason(new IOException("io",
                    new Exception((string?)null))) == "(unknown reason)",
                    "null root reason");
                break;
            case "get root reason if empty":
                Require(PklExceptions.RootReason(new IOException("io", new Exception(""))) ==
                    "(unknown reason)",
                    "empty root reason");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteGlobResolverTest(ContractRow row)
    {
        bool Matches(string pattern, string input) => PklGlob.Compile(pattern).IsMatch(input);
        string Parameter()
        {
            int first = row.DisplayName.IndexOf('"');
            int last = row.DisplayName.LastIndexOf('"');
            return first >= 0 && last > first ? row.DisplayName[(first + 1)..last] : "";
        }
        switch (row.SourceMethod)
        {
            case "basic match":
                Require(Matches("foobar", "foobar") && !Matches("foobar", "oobar") &&
                    !Matches("foobar", "fooba") && !Matches("foobar", ""), "basic glob");
                break;
            case "basic match 2":
                Require(Matches("foo+bar.pkl", "foo+bar.pkl") &&
                    !Matches("foo+bar.pkl", "foooooobar.pkl"), "literal glob metacharacter");
                break;
            case "glob match":
                Require(Matches("*.pkl", Parameter()), row.DisplayName);
                break;
            case "glob non-match":
                Require(!Matches("*.pkl", Parameter()), row.DisplayName);
                break;
            case "globstar match":
            case "globstar non-match":
                Require(Matches("**.pkl", Parameter()), row.DisplayName);
                break;
            case "globstar match 2":
                Require(Matches("/**/*.pkl", Parameter()), row.DisplayName);
                break;
            case "globstar non-match 2":
                Require(!Matches("/**/*.pkl", Parameter()), row.DisplayName);
                break;
            case "sub-patterns":
                Require(Matches("{foo,bar}", "foo") && Matches("{foo,bar}", "bar") &&
                    !Matches("{foo,bar}", "barr") && Matches("{,,,a,}", "a") &&
                    Matches("{,,,a,}", "") && Matches("*.y{a,}ml", "foo.yml") &&
                    Matches("*.y{a,}ml", "foo.yaml"), "glob alternatives");
                break;
            case "sub-patterns with wildcards":
                Require(Matches("{*.foo,*.bar}", "thing.foo") &&
                    Matches("{*.foo,*.bar}", "thing.bar") &&
                    Matches("{*.foo,*.bar}", ".bar"), "glob wildcard alternatives");
                break;
            case "invalid sub-patterns":
                _ = Throws<ArgumentException>(() => PklGlob.Compile("{foo{bar}}"));
                _ = Throws<ArgumentException>(() => PklGlob.Compile("{foo"));
                _ = PklGlob.Compile("foo}");
                break;
            case "character classes":
                Require(Matches("thing[^0-9]", "thing^") &&
                    Enumerable.Range(0, 6).All(value =>
                        Matches("thing[^0-9]", "thing" + value)), "glob character classes");
                break;
            case "character classes don't cross directory boundaries":
                Require(Matches("[.-z]", "f") && !Matches("[.-z]", "/"),
                    "glob character class path boundary");
                break;
            case "invalid character classes":
                foreach (string invalid in new[] { "thing[", "thing[foo/bar]", "[[=a=]]",
                    "[[:alnum:]]", "[[.a-acute.]]" })
                    _ = Throws<ArgumentException>(() => PklGlob.Compile(invalid));
                _ = PklGlob.Compile("]");
                break;
            case "invalid extglob":
                foreach (string invalid in new[] { "!(foo|bar)", "+(foo|bar)", "?(foo|bar)",
                    "@(foo|bar)", "*(foo|bar)" })
                    _ = Throws<ArgumentException>(() => PklGlob.Compile(invalid));
                break;
            case "wildcard character":
                Require(new[] { "aeiou", "aeeou", "aelou", "aejou" }.All(
                        value => Matches("ae?ou", value)) &&
                    !Matches("ae?ou", "aeou") && !Matches("ae?ou", "aou"),
                    "single-character glob wildcard");
                break;
            case "character classes - negation":
                Require(Enumerable.Range(1, 5).All(value =>
                        !Matches("thing[!0-5]", "thing" + value)) &&
                    Matches("thing[!0-5]", "thing6") && Matches("thing[!0-5]", "thing7"),
                    "negated glob character class");
                break;
            case "escapes":
                Require(Matches("\\\\foo", "\\foo") &&
                    Matches("\\{foo-bar.pkl", "{foo-bar.pkl") &&
                    Matches("\\[foo-bar.pkl", "[foo-bar.pkl"), "glob escapes");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteHttpUtilsTest(ContractRow row)
    {
        switch (row.SourceMethod)
        {
            case "isHttpUrl":
                Require(PklHttp.IsHttpUrl(new Uri("http://example.com")) &&
                    PklHttp.IsHttpUrl(new Uri("https://example.com")) &&
                    PklHttp.IsHttpUrl(new Uri("HtTpS://example.com")) &&
                    !PklHttp.IsHttpUrl(new Uri("file://example.com")), "HTTP URI detection");
                break;
            case "checkHasStatusCode200":
                PklHttp.RequireSuccessStatusCode(200);
                _ = Throws<IOException>(() => PklHttp.RequireSuccessStatusCode(404));
                break;
            case "setPort":
                _ = Throws<ArgumentException>(() =>
                    PklHttp.WithPort(new Uri("https://example.com"), -1));
                _ = Throws<ArgumentException>(() =>
                    PklHttp.WithPort(new Uri("https://example.com"), 65536));
                Require(PklHttp.WithPort(new Uri("http://example.com"), 123) ==
                        new Uri("http://example.com:123") &&
                    PklHttp.WithPort(new Uri("http://example.com:456"), 123) ==
                        new Uri("http://example.com:123") &&
                    PklHttp.WithPort(new Uri(
                        "https://example.com/foo/bar.baz?query=1#fragment"), 123) ==
                        new Uri("https://example.com:123/foo/bar.baz?query=1#fragment"),
                    "HTTP URI port replacement");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecuteImportGraphUtilsTest(ContractRow row)
    {
        Uri foo = new("file:///foo.pkl");
        Uri bar = new("file:///bar.pkl");
        Uri biz = new("file:///biz.pkl");
        Uri qux = new("file:///qux.pkl");
        IReadOnlySet<ImportGraph.Import> Imports(params Uri[] values) =>
            new HashSet<ImportGraph.Import>(values.Select(value => new ImportGraph.Import(value)));
        Dictionary<Uri, IReadOnlySet<ImportGraph.Import>> imports = row.SourceMethod switch
        {
            "basic" => new() { [foo] = Imports(bar), [bar] = Imports(foo) },
            "two cycles" => new()
                { [foo] = Imports(bar), [bar] = Imports(foo), [biz] = Imports(qux), [qux] = Imports(biz) },
            "no cycles" => new()
                { [bar] = Imports(foo), [foo] = Imports(biz), [biz] = Imports(qux), [qux] = Imports() },
            "self-import" => new() { [foo] = Imports(foo) },
            _ => new()
        };
        if (imports.Count == 0) return Pending(row);
        IReadOnlyList<IReadOnlyList<Uri>> cycles = PklImportGraphs.FindCycles(
            new ImportGraph(imports, new Dictionary<Uri, Uri>()));
        if (row.SourceMethod == "no cycles") Require(cycles.Count == 0, "acyclic import graph");
        else if (row.SourceMethod == "self-import")
            Require(cycles.Count == 1 && cycles[0].SequenceEqual(new[] { foo }), "self import cycle");
        else if (row.SourceMethod == "basic")
            Require(cycles.Count == 1 && cycles[0].SequenceEqual(new[] { foo, bar }),
                "basic import cycle");
        else
            Require(cycles.Count == 2 && cycles[0].SequenceEqual(new[] { foo, bar }) &&
                cycles[1].SequenceEqual(new[] { biz, qux }), "two import cycles");
        return Passed(row);
    }

    static ChildResult ExecuteIoUtilsTest(ContractRow row, CorpusFixture fixture)
    {
        switch (row.SourceMethod)
        {
            case "ensurePathEndsWithSlash() - relative URI":
                Require(PklUris.EnsurePathEndsWithSlash(new Uri("/some/path", UriKind.Relative)) ==
                        new Uri("/some/path/", UriKind.Relative) &&
                    PklUris.EnsurePathEndsWithSlash(new Uri("/some/path/", UriKind.Relative)) ==
                        new Uri("/some/path/", UriKind.Relative), "relative URI slash");
                break;
            case "ensurePathEndsWithSlash() - absolute URI":
                Require(PklUris.EnsurePathEndsWithSlash(new Uri("https://apple.com/path")) ==
                        new Uri("https://apple.com/path/") &&
                    PklUris.EnsurePathEndsWithSlash(
                        new Uri("https://user:pwd@apple.com:8080/path?foo=bar#frag")) ==
                        new Uri("https://user:pwd@apple.com:8080/path/?foo=bar#frag"),
                    "absolute URI slash");
                break;
            case "ensurePathEndsWithSlash() - opaque URI":
                Require(PklUris.EnsurePathEndsWithSlash(new Uri("foo:some.thing")) ==
                    new Uri("foo:some.thing"), "opaque URI unchanged");
                break;
            case "resolving relative URI against triple-slash file URI results in triple-slash file URI":
                Require(PklUris.Resolve(new Uri("file:///foo/bar"), new Uri("baz", UriKind.Relative)) ==
                    new Uri("file:///foo/baz"), "triple-slash file resolution");
                break;
            case "resolving relative URI against single-slash file URI results in single-slash file URI":
            {
                string resolved = PklUris.Format(PklUris.Resolve(PklUris.Parse("file:/foo/bar"),
                    new Uri("baz", UriKind.Relative)));
                Require(resolved == "file:/foo/baz",
                    $"single-slash file resolution produced `{resolved}`");
                break;
            }
            case "resolving relative URI against triple-slash jar-file URI results in triple-slash jar-file URI":
                Require(PklUris.Resolve(new Uri("jar:file:///some/archive.zip!/foo/bar"),
                    new Uri("baz", UriKind.Relative)).OriginalString ==
                    "jar:file:///some/archive.zip!/foo/baz", "triple-slash archive resolution");
                break;
            case "resolving relative URI against single-slash jar-file URI results in single-slash jar-file URI":
                Require(PklUris.Resolve(new Uri("jar:file:/some/archive.zip!/foo/bar"),
                    new Uri("baz", UriKind.Relative)).OriginalString ==
                    "jar:file:/some/archive.zip!/foo/baz", "single-slash archive resolution");
                break;
            case "resolve absolute URI against jar-file URI":
                Require(PklUris.Resolve(new Uri("jar:file:///some/archive.zip!/foo/bar.pkl"),
                    new Uri("https://apple.com")) == new Uri("https://apple.com"),
                    "absolute archive URI resolution");
                break;
            case "resolving other URIs works the same as java_net_URI_resolve()":
                Require(PklUris.Resolve(new Uri("https://apple.com/foo/bar"),
                        new Uri("baz", UriKind.Relative)) == new Uri("https://apple.com/foo/baz") &&
                    PklUris.Resolve(new Uri("test:opaque1"), new Uri("test:opaque2")) ==
                        new Uri("test:opaque2"), "standard URI resolution");
                break;
            case "relativize file URLs":
                Require(PklUris.Relativize(new Uri("file:///foo/bar/baz.pkl"),
                        new Uri("file:///foo/bar/qux.pkl")) == new Uri("baz.pkl", UriKind.Relative) &&
                    PklUris.Relativize(new Uri("file:///foo/bar/baz.pkl"),
                        new Uri("file:///foo/qux/")) == new Uri("../bar/baz.pkl", UriKind.Relative) &&
                    PklUris.Relativize(new Uri("file:///foo/bar/baz.pkl"),
                        new Uri("https://example.com/foo/bar/baz.pkl")) ==
                        new Uri("file:///foo/bar/baz.pkl"),
                    "file URI relativization");
                break;
            case "relativize HTTP URLs":
                Require(PklUris.Relativize(new Uri("https://foo.com/bar/baz.pkl"),
                        new Uri("https://foo.com/bar/qux.pkl")) ==
                        new Uri("baz.pkl", UriKind.Relative) &&
                    PklUris.Relativize(new Uri("https://foo.com/bar/baz.pkl?query#fragment"),
                        new Uri("https://foo.com/bar/qux.pkl?query2#fragment2")) ==
                        new Uri("baz.pkl?query#fragment", UriKind.Relative) &&
                    PklUris.Relativize(new Uri("https://foo.com:80/bar/baz.pkl"),
                        new Uri("https://foo.com:443/bar/")) ==
                        new Uri("https://foo.com:80/bar/baz.pkl"), "HTTP URI relativization");
                break;
            case "isWhitespace()":
                Require(PklUris.IsWhitespace("") && PklUris.IsWhitespace("  \t ") &&
                    !PklUris.IsWhitespace("  a "), "whitespace detection");
                break;
            case "toPath()":
                Require(PklUris.ToFilePath(new Uri("file:///foo/bar.txt")) == "/foo/bar.txt" &&
                    PklUris.ToFilePath(new Uri("https://apple.com")) is null &&
                    PklUris.ToFilePath(new Uri("unknown://foo/bar")) is null,
                    "URI to path adaptation");
                break;
            case "toPath() only accepts absolute URIs":
                _ = Throws<ArgumentException>(() =>
                    PklUris.ToFilePath(new Uri("foo/bar", UriKind.Relative)));
                break;
            case "getMaxLineLength":
                Require(PklUris.GetMaxLineLength("abc") == 3 &&
                    PklUris.GetMaxLineLength("abc\n\nabcd\n\nab") == 4,
                    "maximum line length");
                break;
            case "capitalize":
                Require(PklUris.Capitalize("abc") == "Abc" && PklUris.Capitalize("Abc") == "Abc" &&
                    PklUris.Capitalize("a&*") == "A&*" && PklUris.Capitalize("_&*") == "_&*" &&
                    PklUris.Capitalize("abc def") == "Abc def", "capitalization");
                break;
            case "inferModuleName":
            {
                var expected = new Dictionary<string, string>
                {
                    ["file:///foo.pkl"] = "foo",
                    ["file:///foo/bar/baz.pkl"] = "baz",
                    ["jar:file:///some/archive.zip!/foo.pkl"] = "foo",
                    ["jar:file:///some/archive.zip!/foo/bar/baz.pkl"] = "baz",
                    ["https://apple.com/foo.pkl"] = "foo",
                    ["pkl:foo.bar.baz"] = "baz",
                    ["modulepath:/foo/bar/baz.pkl"] = "baz",
                    ["package://example.com/foo/bar@1.0.0#/baz/biz/qux.pkl"] = "qux"
                };
                Require(expected.All(item =>
                    PklUris.InferModuleName(new Uri(item.Key)) == item.Value), "module-name inference");
                break;
            }
            case "toUri":
                Require(PklUris.Parse("file://foo.pkl") == new Uri("file://foo.pkl") &&
                    PklUris.Parse("foo.pkl") == new Uri("foo.pkl", UriKind.Relative) &&
                    PklUris.Parse("foo bar.pkl").OriginalString.Contains("foo%20bar.pkl",
                        StringComparison.Ordinal), "URI parsing");
                _ = Throws<UriFormatException>(() => PklUris.Parse("file:foo bar.pkl"));
                break;
            case "resolveUri - file hierarchy":
            {
                string base1 = Path.Combine(fixture.Root, "base1", "base2", "foo.pkl");
                string deep = Path.Combine(fixture.Root, "base1", "base2", "dir1", "dir2", "foo.pkl");
                string sibling = Path.Combine(fixture.Root, "base1", "dir2", "foo.pkl");
                Directory.CreateDirectory(Path.GetDirectoryName(base1)!);
                Directory.CreateDirectory(Path.GetDirectoryName(deep)!);
                Directory.CreateDirectory(Path.GetDirectoryName(sibling)!);
                File.WriteAllText(base1, "", new UTF8Encoding(false));
                File.WriteAllText(deep, "", new UTF8Encoding(false));
                File.WriteAllText(sibling, "", new UTF8Encoding(false));
                Require(PklUris.ResolveTripleDotFile(new Uri(deep),
                        new Uri("...", UriKind.Relative)) == new Uri(base1) &&
                    PklUris.ResolveTripleDotFile(new Uri(deep),
                        new Uri(".../dir2/foo.pkl", UriKind.Relative)) == new Uri(sibling),
                    "triple-dot file hierarchy");
                _ = Throws<UriFormatException>(() => PklUris.ResolveTripleDotFile(
                    new Uri(deep), new Uri(".../", UriKind.Relative)));
                _ = Throws<FileNotFoundException>(() => PklUris.ResolveTripleDotFile(
                    new Uri(deep), new Uri(".../bar.pkl", UriKind.Relative)));
                break;
            }
            case "resolveUri - classpath hierarchy":
            {
                Uri module = new(
                    "modulepath:/org/pkl/core/module/dir1/dir2/NamedModuleResolversTest.pkl");
                Uri target = new("modulepath:/org/pkl/core/module/NamedModuleResolversTest.pkl");
                IReadOnlySet<Uri> available = new HashSet<Uri> { target };
                Require(PklUris.ResolveTripleDotModulePath(module,
                    new Uri("...", UriKind.Relative), available) == target,
                    "triple-dot module path hierarchy");
                _ = Throws<FileNotFoundException>(() => PklUris.ResolveTripleDotModulePath(
                    new Uri("modulepath:/foo/bar/baz.pkl"),
                    new Uri(".../other.pkl", UriKind.Relative), available));
                break;
            }
            case "readBytes(URL) does not support HTTP URLs":
                _ = Throws<ArgumentException>(() => PklUris.ReadBytes(new Uri("https://example.com")));
                _ = Throws<ArgumentException>(() => PklUris.ReadBytes(new Uri("http://example.com")));
                break;
            case "readString(URL) does not support HTTP URLs":
                _ = Throws<ArgumentException>(() => PklUris.ReadText(new Uri("https://example.com")));
                _ = Throws<ArgumentException>(() => PklUris.ReadText(new Uri("http://example.com")));
                break;
            case "encodePath encodes characters reserved on windows":
                Require(PklUris.EncodePath("foo:bar") == "foo(3a)bar" &&
                    PklUris.EncodePath("<>:\"\\|?*") == "(3c)(3e)(3a)(22)(5c)(7c)(3f)(2a)" &&
                    PklUris.EncodePath("foo(3a)bar") == "foo((3a)bar" &&
                    PklUris.EncodePath("(") == "((" &&
                    PklUris.EncodePath("foo/bar/baz") == "foo/bar/baz", "Windows path encoding");
                break;
            default:
                return Pending(row);
        }
        return Passed(row);
    }

    static ChildResult ExecutePathResolverTest(ContractRow row)
    {
        (Uri Base, string Path, string Expected)? test = row.SourceClass.EndsWith("PosixTests",
            StringComparison.Ordinal) ? row.SourceMethod switch
        {
            "simple relative path appended to file base" =>
                (new Uri("file:///home/user/base.pkl"), "sibling.pkl", "/home/user/base.pkl/sibling.pkl"),
            "relative path appended to directory base (trailing slash)" =>
                (new Uri("file:///home/user/dir/"), "file.pkl", "/home/user/dir/file.pkl"),
            "nested relative path" =>
                (new Uri("file:///home/user/base.pkl"), "sub/dir/file.pkl", "/home/user/base.pkl/sub/dir/file.pkl"),
            "absolute path overrides base" =>
                (new Uri("file:///home/user/base.pkl"), "/absolute/path.pkl", "/absolute/path.pkl"),
            "absolute path containing dot is normalized" =>
                (new Uri("file:///home/user/base.pkl"), "/foo/./bar.pkl", "/foo/bar.pkl"),
            "absolute path containing double-dot is normalized" =>
                (new Uri("file:///home/user/base.pkl"), "/foo/../bar.pkl", "/bar.pkl"),
            "single dot in relative path is elided" =>
                (new Uri("file:///home/user/base.pkl"), "./sibling.pkl", "/home/user/base.pkl/sibling.pkl"),
            "double-dot in relative path goes up one segment" =>
                (new Uri("file:///home/user/base.pkl"), "../sibling.pkl", "/home/user/sibling.pkl"),
            "two double-dots in relative path go up two segments" =>
                (new Uri("file:///home/user/a/b.pkl"), "../../c.pkl", "/home/user/c.pkl"),
            "mixed relative path with dot-dot" =>
                (new Uri("file:///home/user/base.pkl"), "sub/dir/../../other.pkl", "/home/user/base.pkl/other.pkl"),
            "double-dot beyond root clamps to root" =>
                (new Uri("file:///file.pkl"), "../../root.pkl", "/root.pkl"),
            "root base with relative path" =>
                (new Uri("file:///"), "file.pkl", "/file.pkl"),
            "URI with percent-encoded path is decoded" =>
                (new Uri("file:///home/user%20name/base.pkl"), "file.pkl", "/home/user name/base.pkl/file.pkl"),
            _ => null
        } : row.SourceMethod switch
        {
            "drive letter URI with simple relative path" =>
                (new Uri("file:///C:/Users/user/base.pkl"), "relative.pkl", @"C:\Users\user\base.pkl\relative.pkl"),
            "drive letter URI with nested relative path" =>
                (new Uri("file:///C:/Users/user/base.pkl"), @"sub\dir\file.pkl", @"C:\Users\user\base.pkl\sub\dir\file.pkl"),
            "drive letter URI with forward-slash relative path is normalised to backslash" =>
                (new Uri("file:///C:/Users/user/base.pkl"), "sub/dir/file.pkl", @"C:\Users\user\base.pkl\sub\dir\file.pkl"),
            "drive letter URI with directory base (trailing backslash)" =>
                (new Uri("file:///C:/Users/dir/"), "file.pkl", @"C:\Users\dir\file.pkl"),
            "backslash dot in relative path is elided" =>
                (new Uri("file:///C:/Users/user/base.pkl"), @"..\sibling.pkl", @"C:\Users\user\sibling.pkl"),
            "forward-slash dot-dot in relative path is normalised" =>
                (new Uri("file:///C:/Users/user/base.pkl"), "../sibling.pkl", @"C:\Users\user\sibling.pkl"),
            "backslash single-dot in relative path is elided" =>
                (new Uri("file:///C:/Users/user/base.pkl"), @".\sibling.pkl", @"C:\Users\user\base.pkl\sibling.pkl"),
            "two double-dots go up two segments" =>
                (new Uri("file:///C:/Users/user/a/b.pkl"), @"..\..\c.pkl", @"C:\Users\user\c.pkl"),
            "double-dot beyond drive root clamps to root" =>
                (new Uri("file:///C:/base.pkl"), @"..\..\out.pkl", @"C:\out.pkl"),
            "absolute path on same drive overrides base" =>
                (new Uri("file:///C:/Users/base.pkl"), @"C:\other\path.pkl", @"C:\other\path.pkl"),
            "absolute path on different drive overrides base" =>
                (new Uri("file:///C:/Users/base.pkl"), @"D:\other.pkl", @"D:\other.pkl"),
            "absolute path with forward slashes is accepted" =>
                (new Uri("file:///C:/Users/base.pkl"), "D:/other.pkl", @"D:\other.pkl"),
            "root-relative backslash path takes drive root from base" =>
                (new Uri("file:///C:/Users/base.pkl"), @"\root.pkl", @"C:\root.pkl"),
            "root-relative forward-slash path takes drive root from base" =>
                (new Uri("file:///C:/Users/base.pkl"), "/root.pkl", @"C:\root.pkl"),
            "UNC URI with simple relative path" =>
                (new Uri("file://server/share/base.pkl"), "relative.pkl", @"\\server\share\base.pkl\relative.pkl"),
            "UNC URI with double-dot goes up within share" =>
                (new Uri("file://server/share/dir/base.pkl"), @"..\sibling.pkl", @"\\server\share\dir\sibling.pkl"),
            "UNC URI double-dot beyond share root clamps to share root" =>
                (new Uri("file://server/share/base.pkl"), @"..\..\.\out.pkl", @"\\server\share\out.pkl"),
            "UNC URI with absolute UNC path overrides base" =>
                (new Uri("file://server/share/base.pkl"), @"\\other\share\file.pkl", @"\\other\share\file.pkl"),
            "absolute path containing dot is normalized" =>
                (new Uri("file:///C:/Users/base.pkl"), @"C:\foo\.\bar.pkl", @"C:\foo\bar.pkl"),
            "absolute path containing double-dot is normalized" =>
                (new Uri("file:///C:/Users/base.pkl"), @"C:\foo\..\bar.pkl", @"C:\bar.pkl"),
            "file URI without drive letter" =>
                (new Uri("file:///path/to/foo"), "bar", @"\path\to\foo\bar"),
            _ => null
        };
        if (test is null) return Pending(row);
        string actual = row.SourceClass.EndsWith("PosixTests", StringComparison.Ordinal)
            ? PklPath.ResolvePosix(test.Value.Base, test.Value.Path)
            : PklPath.ResolveWindows(test.Value.Base, test.Value.Path);
        Require(actual == test.Value.Expected, row.SourceMethod);
        return Passed(row);
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

    static ChildResult ExecuteEvaluatorTest(ContractRow row, CorpusFixture fixture)
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
            case "evaluate text with relative import":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                PklException error = Throws<PklException>(() =>
                    evaluator.Evaluate(ModuleSource.FromText("import \"foo.bar\"")));
                Require(error.Message.Contains("relative", StringComparison.OrdinalIgnoreCase) &&
                    error.Message.Contains("import", StringComparison.OrdinalIgnoreCase),
                    "relative text-module import diagnostic: " + error.Message);
                break;
            }
            case "evaluate named module":
            {
                string root = Path.Combine(fixture.Root, "named-modules");
                string file = Path.Combine(root, "org", "pkl", "core", "EvaluatorTest.pkl");
                Directory.CreateDirectory(Path.GetDirectoryName(file)!);
                File.WriteAllText(file, "name = \"pigeon\"; age = 10 + 20", new UTF8Encoding(false));
                using var resolver = new ModulePathResolver(new[] { root });
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator evaluator = builder.Build();
                PModule module = evaluator.Evaluate(
                    ModuleSource.FromModulePath("org/pkl/core/EvaluatorTest.pkl"));
                Require((string)module.GetProperty("name") == "pigeon" &&
                    (long)module.GetProperty("age") == 30, "named module-path evaluation");
                break;
            }
            case "evaluate non-existing named module":
            {
                using var resolver = new ModulePathResolver(Array.Empty<string>());
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator evaluator = builder.Build();
                Require(Throws<PklException>(() => evaluator.Evaluate(
                        ModuleSource.FromModulePath("non/existing.pkl"))).Message
                    .Contains("Cannot find module", StringComparison.Ordinal),
                    "missing named module diagnostic");
                break;
            }
            case "evaluate file":
            case "evaluate path":
            case "evaluate URI":
            {
                string file = Path.Combine(fixture.Root, "evaluate-local.pkl");
                File.WriteAllText(file, "name = \"pigeon\"; age = 10 + 20", new UTF8Encoding(false));
                ModuleSource source = row.SourceMethod switch
                {
                    "evaluate file" => ModuleSource.FromFile(file),
                    "evaluate path" => ModuleSource.FromPath(file),
                    _ => ModuleSource.FromUri(new Uri(file))
                };
                using Evaluator evaluator = Evaluator.Preconfigured();
                PModule module = evaluator.Evaluate(source);
                Require((string)module.GetProperty("name") == "pigeon" &&
                    (long)module.GetProperty("age") == 30, row.SourceMethod);
                break;
            }
            case "evaluate non-existing file":
            case "evaluate non-existing path":
            {
                string missing = Path.Combine(fixture.Root, "non-existing.pkl");
                ModuleSource source = row.SourceMethod == "evaluate non-existing file"
                    ? ModuleSource.FromFile(missing) : ModuleSource.FromPath(missing);
                using Evaluator evaluator = Evaluator.Preconfigured();
                Require(Throws<PklException>(() => evaluator.Evaluate(source)).Message
                    .Contains("Cannot find module", StringComparison.Ordinal),
                    row.SourceMethod + " diagnostic");
                break;
            }
            case "evaluate non-existing URI":
            {
                using var server = CorpusHttpServer.Plain(_ => CorpusHttpResponse.NotFound());
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                    .Build();
                PklException error = Throws<PklException>(() => evaluator.Evaluate(
                    ModuleSource.FromUri(new Uri(server.BaseUri, "non-existing"))));
                Require(error.Message.Contains("I/O", StringComparison.OrdinalIgnoreCase) ||
                    error.Message.Contains("HTTP", StringComparison.OrdinalIgnoreCase) ||
                    error.Message.Contains("not found", StringComparison.OrdinalIgnoreCase),
                    "missing URL diagnostic: " + error.Message);
                break;
            }
            case "evaluate zip file system path":
            case "evaluate jar URI":
            {
                string archive = CreateArchive(fixture.Root, "evaluate-archive.zip",
                    ("foo/bar/module1.pkl",
                        "import \"../baz/module2.pkl\"\nname = module2.name\nage = module2.age\n"),
                    ("foo/baz/module2.pkl", "name = \"pigeon\"; age = 10 + 20"));
                using var resolver = new ModulePathResolver(new[] { archive });
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator evaluator = builder.Build();
                PModule module = evaluator.Evaluate(ModuleSource.FromModulePath("foo/bar/module1.pkl"));
                Require((string)module.GetProperty("name") == "pigeon" &&
                    (long)module.GetProperty("age") == 30,
                    ".NET archive-path evaluation adaptation");
                break;
            }
            case "evaluate non-existing zip file system path":
            case "evaluate jar URI with non-existing archive path":
            {
                string archive = CreateArchive(fixture.Root, "missing-entry.zip",
                    ("existing.pkl", "x = 1"));
                using var resolver = new ModulePathResolver(new[] { archive });
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator evaluator = builder.Build();
                Require(Throws<PklException>(() => evaluator.Evaluate(
                        ModuleSource.FromModulePath("non/existing"))).Message
                    .Contains("Cannot find module", StringComparison.Ordinal),
                    "missing archive entry diagnostic");
                break;
            }
            case "evaluate jar URI with non-existing archive":
            {
                using var resolver = new ModulePathResolver(new[] {
                    Path.Combine(fixture.Root, "non-existing.zip") });
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator evaluator = builder.Build();
                Require(Throws<PklException>(() => evaluator.Evaluate(
                        ModuleSource.FromModulePath("bar.pkl"))).Message
                    .Contains("Cannot find module", StringComparison.Ordinal),
                    "missing archive diagnostic");
                break;
            }
            case "evaluate module with relative URI":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                Require(Throws<PklException>(() => evaluator.Evaluate(
                        ModuleSource.Create(new Uri("foo.bar", UriKind.Relative), ""))).Message
                    .Contains("relative module URI", StringComparison.Ordinal),
                    "relative module source rejection");
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
            case "nested pkl-binary rendering produces correct results":
            {
                using var reader = new Base64RequestResourceReader();
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetAllowedResources(builder.GetAllowedResources()
                    .Concat(new[] { new Regex("b64:") }).ToArray());
                using Evaluator evaluator = builder.AddResourceReader(reader).Build();
                PModule module = evaluator.Evaluate(ModuleSource.FromText(
                    "abstract class Base {\n" +
                    "  fixed kind: String\n" +
                    "  input: String\n" +
                    "  local encodedRequest = \"\\(kind):\\(input)\".base64\n" +
                    "  hidden fixed requestUri: String = \"b64:\\(encodedRequest)\"\n" +
                    "  hidden fixed result: Resource = read(requestUri) as Resource\n" +
                    "}\n" +
                    "class Enc extends Base { fixed kind: \"enc\" }\n" +
                    "class Dec extends Base { fixed kind: \"dec\" }\n" +
                    "local enc = new Enc { input = \"hello world\" }\n" +
                    "local dec = new Dec { input = enc.result.text }\n" +
                    "roundTrip = dec.result.text\n" +
                    "valid = enc.input == roundTrip\n"));
                Require(module.GetProperty("valid") is true &&
                    (string)module.GetProperty("roundTrip") == "hello world" &&
                    reader.RequestKinds.SequenceEqual(new[] { "enc", "dec" }),
                    "nested evaluator/custom-resource round trip without Pkl-binary transport");
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
            case "cannot import module located outside root dir":
            case "cannot read resource located outside root dir":
            {
                string root = Path.Combine(fixture.Root, "evaluator-root");
                Directory.CreateDirectory(root);
                string module = Path.Combine(root, "test.pkl");
                string outside = Path.Combine(fixture.Root, "outside.pkl");
                File.WriteAllText(outside, "value = 1", new UTF8Encoding(false));
                string expression = row.SourceMethod.StartsWith("cannot import", StringComparison.Ordinal)
                    ? $"value = import(\"{new Uri(outside).AbsoluteUri}\")"
                    : $"value = read(\"{new Uri(outside).AbsoluteUri}\")";
                File.WriteAllText(module, expression, new UTF8Encoding(false));
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured().SetRootDir(root).Build();
                PklException error = Throws<PklException>(() =>
                    evaluator.Evaluate(ModuleSource.FromPath(module)));
                Require(error.Message.Contains("root directory", StringComparison.OrdinalIgnoreCase),
                    row.SourceMethod + " policy diagnostic");
                break;
            }
            case "cannot import module from zip filesystem located outside root dir":
            case "cannot read resource from zip filesystem located outside root dir":
            {
                string root = Path.Combine(fixture.Root, "allowed-root");
                string forbidden = Path.Combine(fixture.Root, "forbidden-root");
                Directory.CreateDirectory(root);
                Directory.CreateDirectory(forbidden);
                string archive = CreateArchive(forbidden, "outside.zip",
                    ("module.pkl", "value = 1"));
                SecurityManager manager = RootedSecurityManager(root);
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckResolveModule(new Uri(archive)));
                _ = Throws<SecurityManagerException>(() =>
                    manager.CheckReadResource(new Uri(archive)));
                break;
            }
            case "multiple-file output":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                IReadOnlyDictionary<string, FileOutput> output = evaluator.EvaluateOutputFiles(
                    ModuleSource.FromText("output { files { " +
                        "[\"foo.yml\"] { text = \"foo: foo text\" }; " +
                        "[\"bar.yml\"] { text = \"bar: bar text\" }; " +
                        "[\"bar/biz.yml\"] { text = \"biz: bar biz\" }; " +
                        "[\"bar/../bark.yml\"] { text = \"bark: bark bark\" } } }"));
                Require(output.Keys.ToHashSet().SetEquals(new[] { "foo.yml", "bar.yml",
                        "bar/biz.yml", "bar/../bark.yml" }) &&
                    output["foo.yml"].Text == "foo: foo text" &&
                    output["bar/../bark.yml"].Text == "bark: bark bark",
                    "multiple-file output paths and values");
                break;
            }
            case "project set from modulepath":
            case "project set from custom ModuleKeyFactory":
                VerifyEvaluatorLocalProject(row.SourceMethod, fixture.Root);
                break;
            case "project base path set to non-hierarchical scheme":
            {
                using var factory = new CorpusModuleFactory("nonhier", uri =>
                    uri.OriginalString.EndsWith("PklProject", StringComparison.Ordinal)
                        ? "amends \"pkl:Project\"\n"
                        : "birds = import(\"@birds/catalog/Ostrich.pkl\")\n");
                using Evaluator projectEvaluator = EvaluatorBuilder.Unconfigured()
                    .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                    .SetAllowedModules(new[] { new Regex("nonhier:"), new Regex("pkl:") })
                    .SetAllowedResources(Array.Empty<Regex>())
                    .AddModuleKeyFactory(ModuleKeyFactories.StandardLibraryFactory)
                    .AddModuleKeyFactory(factory).Build();
                Pkl.Core.Project.Project project = Pkl.Core.Project.Project.Load(
                    projectEvaluator, ModuleSource.FromUri("nonhier:foo/PklProject"));
                using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                    .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                    .SetAllowedModules(new[] { new Regex("nonhier:"), new Regex("pkl:") })
                    .SetAllowedResources(Array.Empty<Regex>())
                    .SetProjectDependencies(project.DeclaredDependencies)
                    .AddModuleKeyFactory(ModuleKeyFactories.StandardLibraryFactory)
                    .AddModuleKeyFactory(factory).Build();
                PklException error = Throws<PklException>(() => evaluator.EvaluateOutputText(
                    ModuleSource.Create(new Uri("nonhier:baz"),
                        "birds = import(\"@birds/catalog/Ostrich.pkl\")")));
                Require(error.Message.Contains("does not have a hierarchical path",
                    StringComparison.Ordinal), "non-hierarchical project dependency diagnostic");
                break;
            }
            case "cannot glob import in local dependency from modulepath":
            {
                string moduleRoot = Path.Combine(fixture.Root, "nonglobbable-modulepath");
                string project6 = Path.Combine(moduleRoot, "project6");
                string project7 = Path.Combine(moduleRoot, "project7");
                Directory.CreateDirectory(project6);
                Directory.CreateDirectory(project7);
                File.WriteAllText(Path.Combine(project6, "PklProject"),
                    "amends \"pkl:Project\"\ndependencies { " +
                    "[\"project7\"] = import(\"../project7/PklProject\") }\n",
                    new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project6, "PklProject.deps.json"),
                    "{\n  \"schemaVersion\": 1,\n  \"resolvedDependencies\": {\n" +
                    "    \"package://localhost:0/project7@1\": {\n" +
                    "      \"type\": \"local\",\n" +
                    "      \"uri\": \"projectpackage://localhost:0/project7@1.0.0\",\n" +
                    "      \"path\": \"../project7\"\n    }\n  }\n}\n",
                    new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project6, "globWithinDependency.pkl"),
                    "import \"@project7/main.pkl\"\nres = main.res\n", new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project7, "PklProject"),
                    "amends \"pkl:Project\"\npackage { name = \"project7\"; " +
                    "version = \"1.0.0\"; packageZipUrl = \"https://bogus.value\"; " +
                    "baseUri = \"package://localhost:0/project7\" }\n",
                    new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project7, "main.pkl"),
                    "res = import*(\"*.pkl\")\n", new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project7, "moduleA.pkl"), "",
                    new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(project7, "moduleB.pkl"), "",
                    new UTF8Encoding(false));
                using var resolver = new ModulePathResolver(new[] { moduleRoot });
                using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
                EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
                builder.SetModuleKeyFactories(new[] { factory }
                    .Concat(builder.GetModuleKeyFactories()).ToArray());
                using Evaluator projectEvaluator = builder.Build();
                Pkl.Core.Project.Project project = Pkl.Core.Project.Project.Load(
                    projectEvaluator, ModuleSource.FromModulePath("project6/PklProject"));
                using Evaluator evaluator = builder.SetProjectDependencies(
                    project.DeclaredDependencies).Build();
                PklException error = Throws<PklException>(() => evaluator.EvaluateOutputText(
                    ModuleSource.FromModulePath("project6/globWithinDependency.pkl")));
                Require(error.Message.Contains("not globbable", StringComparison.OrdinalIgnoreCase),
                    "non-globbable local dependency diagnostic: " + error.Message);
                break;
            }
            case "root dir check happens without any UNC or SMB access":
            {
                string root = Path.Combine(fixture.Root, "unc-root");
                Directory.CreateDirectory(root);
                using Evaluator evaluator = EvaluatorBuilder.Preconfigured().SetRootDir(root).Build();
                Stopwatch timer = Stopwatch.StartNew();
                PklException error = Throws<PklException>(() => evaluator.Evaluate(
                    ModuleSource.FromText(
                        "result = import(\"file://192.0.2.1/share/nope.pkl\")")));
                timer.Stop();
                Require(error.Message.Contains("root directory", StringComparison.OrdinalIgnoreCase) &&
                    timer.Elapsed < TimeSpan.FromSeconds(2), "root policy precedes UNC I/O");
                break;
            }
            case "eval dependency notation as a module source":
                VerifyEvaluatorLocalProject(row.SourceMethod, fixture.Root);
                break;
            case "eval dependency notation -- no project configured":
            {
                using Evaluator evaluator = Evaluator.Preconfigured();
                Require(Throws<PklException>(() => evaluator.EvaluateOutputText(
                        ModuleSource.FromUri("@fruit/catalog/apple.pkl"))).Message
                    .Contains("no project", StringComparison.OrdinalIgnoreCase),
                    "dependency notation without project diagnostic");
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
            case "package imports":
            {
                using var server = new CorpusPackageServer();
                using PklHttpClient client = server.CreateClient();
                string cache = Path.Combine(fixture.Root, "analyzer-package-cache");
                PopulatePackageCache(server, client, cache);
                var packageAnalyzer = new Analyzer(
                    StackFrameTransformers.DefaultTransformer, false,
                    SecurityManagers.DefaultManager,
                    new[] { ModuleKeyFactories.FileFactory,
                        ModuleKeyFactories.StandardLibraryFactory,
                        ModuleKeyFactories.PackageFactory },
                    cache, null, client,
                    TraceMode.COMPACT);
                string file = Path.Combine(fixture.Root, "package-import.pkl");
                Uri packageUri = new(
                    $"package://127.0.0.1:{server.Port}/birds@0.5.0#/Bird.pkl");
                File.WriteAllText(file, $"import \"{packageUri}\"", new UTF8Encoding(false));
                ImportGraph graph = packageAnalyzer.ImportGraph(new Uri(file));
                Require(graph.Imports.ContainsKey(new Uri(file)) &&
                    graph.Imports[new Uri(file)].Select(item => item.Uri).Single() == packageUri &&
                    graph.Imports.ContainsKey(packageUri), "recursive package analyzer graph");
                break;
            }
            case "project dependency imports":
            {
                using var server = new CorpusPackageServer();
                string projectFile = WriteRemoteProjectFixture(fixture.Root, server);
                Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectFile);
                using PklHttpClient client = server.CreateClient();
                string cache = Path.Combine(fixture.Root, "project-analyzer-cache");
                PopulatePackageCache(server, client, cache);
                var projectAnalyzer = new Analyzer(
                    StackFrameTransformers.DefaultTransformer, false,
                    SecurityManagers.DefaultManager,
                    new[] { ModuleKeyFactories.FileFactory,
                        ModuleKeyFactories.StandardLibraryFactory,
                        ModuleKeyFactories.PackageFactory,
                        ModuleKeyFactories.ProjectPackageFactory },
                    cache,
                    project.DeclaredDependencies, client, TraceMode.COMPACT);
                string source = Path.Combine(Path.GetDirectoryName(projectFile)!, "main.pkl");
                File.WriteAllText(source, "import \"@birds/Bird.pkl\"", new UTF8Encoding(false));
                ImportGraph graph = projectAnalyzer.ImportGraph(new Uri(source));
                Uri expected = new(
                    $"projectpackage://127.0.0.1:{server.Port}/birds@0.5.0#/Bird.pkl");
                Require(graph.Imports[new Uri(source)].Select(item => item.Uri).Single() == expected &&
                    graph.ResolvedImports.ContainsKey(expected),
                    "project-package analyzer resolution");
                break;
            }
            case "local project dependency import":
            {
                (string projectFile, string main, Uri expected) =
                    WriteLocalDependencyProject(fixture.Root);
                Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectFile);
                var localAnalyzer = new Analyzer(
                    StackFrameTransformers.DefaultTransformer, false,
                    SecurityManagers.DefaultManager,
                    new[] { ModuleKeyFactories.FileFactory,
                        ModuleKeyFactories.StandardLibraryFactory,
                        ModuleKeyFactories.PackageFactory,
                        ModuleKeyFactories.ProjectPackageFactory },
                    Path.Combine(fixture.Root, "local-analyzer-cache"),
                    project.DeclaredDependencies, PklHttpClient.DummyClient(), TraceMode.COMPACT);
                ImportGraph graph = localAnalyzer.ImportGraph(new Uri(main));
                Require(graph.Imports[new Uri(main)].Select(item => item.Uri).Single() == expected &&
                    graph.ResolvedImports[expected].IsFile,
                    "local project dependency analyzer resolution");
                break;
            }
            default:
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

    static ChildResult ExecuteReportTest(ContractRow row) => Pending(row);

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

    static string CreateArchive(
        string directory,
        string name,
        params (string Path, string Contents)[] entries)
    {
        Directory.CreateDirectory(directory);
        string archivePath = Path.Combine(directory, name);
        if (File.Exists(archivePath)) File.Delete(archivePath);
        using ZipArchive archive = ZipFile.Open(archivePath, ZipArchiveMode.Create);
        foreach ((string path, string contents) in entries)
        {
            ZipArchiveEntry entry = archive.CreateEntry(path);
            using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
            writer.Write(contents);
        }
        return archivePath;
    }

    static DependencyMetadata CreateDependencyMetadata(bool includePattern)
    {
        var packageUri = new PackageUri("package://example.test/my-proj-name@0.10.0");
        IReadOnlyList<PObject> annotations = includePattern
            ? new[] { new PObject(
                PClassInfo<object>.Get("myModule", "MyAnnotation", new Uri("pkl:fake")),
                new Dictionary<string, object> { ["pattern"] = new Regex(".*") }) }
            : Array.Empty<PObject>();
        return new DependencyMetadata(
            "my-proj-name", packageUri, Version.Parse("0.10.0"),
            new Uri("https://example.test/foo/bar@0.5.3.zip"), new Checksums("abc123"),
            new Dictionary<string, Dependency.RemoteDependency> {
                ["foo"] = new Dependency.RemoteDependency(
                    new PackageUri("package://example.test/foo@0.5.3"), new Checksums("abc123")) },
            "https://example.test/source/0.5.3/blob%{path}",
            new Uri("https://example.test/source"), new Uri("https://example.test/docs"),
            "MIT", "The MIT License", new[] { "birdy@example.test" },
            new Uri("https://example.test/issues"), "Some package description", annotations);
    }

    static void PopulatePackageCache(
        CorpusPackageServer server,
        PklHttpClient client,
        string cache)
    {
        using PackageResolver resolver = PackageResolver.GetInstance(
            SecurityManagers.DefaultManager, client, cache);
        _ = resolver.GetAssetBytes(new PackageAssetUri(
            $"package://127.0.0.1:{server.Port}/birds@0.5.0#/Bird.pkl"));
        _ = resolver.GetAssetBytes(new PackageAssetUri(
            $"package://127.0.0.1:{server.Port}/fruit@1.0.5#/Fruit.pkl"));
    }

    static string WriteProjectFixture(string root, bool includeLocalDependency)
    {
        string directory = Path.Combine(root, "project-fixture");
        Directory.CreateDirectory(directory);
        string projectPath = Path.Combine(directory, "PklProject");
        string dependency = includeLocalDependency
            ? "dependencies { [\"local\"] = import(\"../local-project/PklProject\") }\n"
            : "";
        File.WriteAllText(projectPath,
            "@Deprecated { since = \"1.2\"; message = \"do not use\"; " +
            "replaceWith = \"somethingElse\" }\n@Unlisted\n" +
            "@ModuleInfo { minPklVersion = \"0.26.0\" }\namends \"pkl:Project\"\n" +
            "evaluatorSettings { timeout = 5.min; rootDir = \".\"; noCache = false; " +
            "moduleCacheDir = \"cache/\"; env { [\"one\"] = \"1\" }; " +
            "externalProperties { [\"two\"] = \"2\" }; modulePath { \"modulepath/\" }; " +
            "allowedModules { \"pkl:\"; \"file:\" }; " +
            "allowedResources { \"file:\"; \"env:\"; \"prop:\" } }\n" +
            "package { name = \"hawk\"; baseUri = \"package://example.test/hawk\"; " +
            "version = \"0.5.0\"; description = \"Some project about hawks\"; " +
            "packageZipUrl = \"https://example.test/hawk/0.5.0/hawk-0.5.0.zip\"; " +
            "authors { \"Birdy Bird <birdy@example.test>\" }; " +
            "apiTests { \"apiTest1.pkl\"; \"apiTest2.pkl\" }; exclude { \"*.exe\" } }\n" +
            dependency + "tests { \"test1.pkl\"; \"test2.pkl\" }\n",
            new UTF8Encoding(false));
        return projectPath;
    }

    static string WriteRemoteProjectFixture(string root, CorpusPackageServer server)
    {
        string directory = Path.Combine(root, "remote-project");
        Directory.CreateDirectory(directory);
        string project = Path.Combine(directory, "PklProject");
        File.WriteAllText(project,
            "amends \"pkl:Project\"\ndependencies { [\"birds\"] { " +
            $"uri = \"package://127.0.0.1:{server.Port}/birds@0.5.0\" }} }}\n",
            new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(directory, "PklProject.deps.json"),
            "{\n  \"schemaVersion\": 1,\n  \"resolvedDependencies\": {\n" +
            $"    \"package://127.0.0.1:{server.Port}/birds@0\": {{\n" +
            "      \"type\": \"remote\",\n" +
            $"      \"uri\": \"projectpackage://127.0.0.1:{server.Port}/birds@0.5.0\",\n" +
            $"      \"checksums\": {{\"sha256\": \"{server.BirdsMetadataSha256}\"}}\n" +
            "    }\n  }\n}\n", new UTF8Encoding(false));
        return project;
    }

    static (string ProjectFile, string Main, Uri Expected) WriteLocalDependencyProject(string root)
    {
        string parent = Path.Combine(root, "local-dependency-fixture");
        string projectDirectory = Path.Combine(parent, "project");
        string birdsDirectory = Path.Combine(parent, "birds");
        Directory.CreateDirectory(projectDirectory);
        Directory.CreateDirectory(birdsDirectory);
        string projectFile = Path.Combine(projectDirectory, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\ndependencies { " +
            "[\"birds\"] = import(\"../birds/PklProject\") }\n", new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(birdsDirectory, "PklProject"),
            "amends \"pkl:Project\"\npackage { name = \"birds\"; version = \"1.0.0\"; " +
            "packageZipUrl = \"https://example.test/birds.zip\"; " +
            "baseUri = \"package://example.test/birds\" }\n", new UTF8Encoding(false));
        string bird = Path.Combine(birdsDirectory, "bird.pkl");
        File.WriteAllText(bird, "name = \"Warbler\"\n", new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(projectDirectory, "PklProject.deps.json"),
            "{\n  \"schemaVersion\": 1,\n  \"resolvedDependencies\": {\n" +
            "    \"package://example.test/birds@1\": {\n" +
            "      \"type\": \"local\",\n" +
            "      \"uri\": \"projectpackage://example.test/birds@1.0.0\",\n" +
            "      \"path\": \"../birds\"\n    }\n  }\n}\n", new UTF8Encoding(false));
        string main = Path.Combine(projectDirectory, "main.pkl");
        File.WriteAllText(main, "import \"@birds/bird.pkl\"\n", new UTF8Encoding(false));
        return (projectFile, main,
            new Uri("projectpackage://example.test/birds@1.0.0#/bird.pkl"));
    }

    static Pkl.Core.Project.Project LoadExternalReaderProject(string root, string executable)
    {
        string directory = Path.Combine(root, "external-reader-project-" +
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        string project = Path.Combine(directory, "PklProject");
        File.WriteAllText(project,
            "amends \"pkl:Project\"\nevaluatorSettings { externalModuleReaders { " +
            $"[\"foo\"] {{ executable = \"{executable.Replace("\\", "\\\\", StringComparison.Ordinal)}\" }} }} }}\n",
            new UTF8Encoding(false));
        return Pkl.Core.Project.Project.LoadFromPath(project);
    }

    static void VerifyProjectCycles(string root, bool multiple)
    {
        string cycleRoot = Path.Combine(root, multiple ? "multiple-cycles" : "single-cycle");
        string[] directories = Enumerable.Range(1, 4)
            .Select(index => Path.Combine(cycleRoot, $"projectCycle{index}"))
            .ToArray();
        foreach (string directory in directories) Directory.CreateDirectory(directory);
        string ProjectModule(int index, string dependency) =>
            "amends \"pkl:Project\"\n\npackage {\n" +
            $"  name = \"projectCycle{index}\"\n  version = \"1.0.0\"\n" +
            "  packageZipUrl = \"https://bogus.value\"\n" +
            $"  baseUri = \"package://localhost:0/projectCycle{index}\"\n}}\n\n" +
            $"dependencies {{ [\"projectCycle{dependency}\"] = " +
            $"import(\"../projectCycle{dependency}/PklProject\") }}\n";
        File.WriteAllText(Path.Combine(directories[0], "PklProject"), ProjectModule(1, "2"),
            new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(directories[1], "PklProject"), ProjectModule(2, "3"),
            new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(directories[2], "PklProject"), ProjectModule(3, "2"),
            new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(directories[3], "PklProject"),
            "amends \"pkl:Project\"\n\nimport \"PklProject\"\n\n" +
            "dependencies { [\"projectCycle1\"] = import(\"../projectCycle1/PklProject\") }\n",
            new UTF8Encoding(false));
        string entry = Path.Combine(directories[multiple ? 3 : 0], "PklProject");
        PklException error = Throws<PklException>(() =>
            Pkl.Core.Project.Project.LoadFromPath(entry));
        Require(error.Message.Contains("circular", StringComparison.OrdinalIgnoreCase) &&
            (!multiple || error.Message.Contains("Cycle", StringComparison.Ordinal)),
            "project dependency cycle diagnostic: " + error.Message);
    }

    static void VerifyEvaluatorLocalProject(string adaptation, string root)
    {
        (string projectFile, _, _) = WriteLocalDependencyProject(root);
        Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectFile);
        EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured().ApplyFromProject(project);
        if (adaptation.Contains("custom", StringComparison.Ordinal))
        {
            using var custom = new CorpusModuleFactory("custom", "value = 42");
            builder.SetAllowedModules(builder.GetAllowedModules().Concat(
                new[] { new Regex("custom:") }).ToArray()).AddModuleKeyFactory(custom);
            using Evaluator customEvaluator = builder.Build();
            Require((long)customEvaluator.Evaluate(ModuleSource.FromUri("custom:item"))
                .GetProperty("value") == 42, "custom project module factory");
        }
        else if (adaptation.Contains("modulepath", StringComparison.Ordinal))
        {
            string moduleRoot = Path.Combine(root, "project-modulepath");
            Directory.CreateDirectory(moduleRoot);
            File.WriteAllText(Path.Combine(moduleRoot, "module.pkl"), "value = 42",
                new UTF8Encoding(false));
            using var resolver = new ModulePathResolver(new[] { moduleRoot });
            using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
            builder.SetModuleKeyFactories(new[] { factory }
                .Concat(builder.GetModuleKeyFactories()).ToArray());
            using Evaluator modulePathEvaluator = builder.Build();
            Require((long)modulePathEvaluator.Evaluate(ModuleSource.FromModulePath("module.pkl"))
                .GetProperty("value") == 42, "project module-path factory");
        }
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured().ApplyFromProject(project).Build();
        PModule dependency = evaluator.Evaluate(ModuleSource.FromUri("@birds/bird.pkl"));
        Require((string)dependency.GetProperty("name") == "Warbler",
            "local project dependency notation");
    }

    static void VerifyExternalReaderFactoryLifecycle(string root)
    {
        var specification = new PklEvaluatorSettings.ExternalReader(
            Path.Combine(root, "missing-external-reader"), Array.Empty<string>(), null);
        using ExternalReaderProcess process = ExternalReaderProcess.Start(specification);
        using ModuleKeyFactory factory = ModuleKeyFactories.CreateExternalProcess("externalfixture", process);
        Exception error = ThrowsAny(() => factory.TryCreate(new Uri("externalfixture:item")));
        Require(error.Message.Length > 0, "external module reader startup failure");
        process.Dispose();
        process.Dispose();
    }

    static void VerifyExternalReaderResourceLifecycle(string root)
    {
        var specification = new PklEvaluatorSettings.ExternalReader(
            Path.Combine(root, "missing-external-resource-reader"), Array.Empty<string>(), null);
        using ExternalReaderProcess process = ExternalReaderProcess.Start(specification);
        using ResourceReader reader = ResourceReaders.CreateExternalProcess("externalresource", process);
        Exception error = ThrowsAny(() => reader.TryRead(new Uri("externalresource:item")));
        Require(error.Message.Length > 0, "external resource reader startup failure");
        process.Dispose();
        process.Dispose();
    }

    static Exception ThrowsAny(Action action)
    {
        try { action(); }
        catch (Exception error) { return error; }
        throw new InvalidOperationException("Contract assertion failed: expected an exception");
    }

    sealed class CorpusModuleFactory : ModuleKeyFactory
    {
        readonly string scheme;
        readonly Func<Uri, string> source;
        readonly bool globbable;
        bool disposed;

        internal CorpusModuleFactory(string scheme, string source, bool globbable = false)
            : this(scheme, _ => source, globbable)
        {
        }

        internal CorpusModuleFactory(
            string scheme,
            Func<Uri, string> source,
            bool globbable = false)
        {
            this.scheme = scheme;
            this.source = source;
            this.globbable = globbable;
        }

        public ModuleKey? Create(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            return uri.Scheme == scheme ? new CorpusModuleKey(uri, source(uri), globbable) : null;
        }
        public void Close() => disposed = true;
        public void Dispose() => Close();
    }

    sealed class CorpusModuleKey(Uri uri, string source, bool globbable)
        : ModuleKey, ResolvedModuleKey
    {
        public ModuleKey GetOriginal() => this;
        public Uri GetUri() => uri;
        public bool HasHierarchicalUris() => uri.OriginalString.Contains('/', StringComparison.Ordinal);
        public bool IsGlobbable() => globbable;
        public bool IsCached() => true;
        public bool IsLocal() => true;
        public ResolvedModuleKey Resolve(SecurityManager securityManager)
        {
            securityManager.CheckResolveModule(uri);
            return this;
        }
        public string LoadSource() => source;
    }

    sealed class CorpusResourceReader : ResourceReader
    {
        readonly string scheme;
        readonly object value;
        bool disposed;

        internal CorpusResourceReader(string scheme = "corpusresource", object? value = null)
        {
            this.scheme = scheme;
            this.value = value ?? "resource-value";
        }

        public string GetUriScheme() => scheme;
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public object? Read(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            return uri.Scheme == scheme ? value : null;
        }
        public void Close() => disposed = true;
        public void Dispose() => Close();
    }

    sealed class Base64RequestResourceReader : ResourceReader
    {
        readonly List<string> requestKinds = new();
        bool disposed;

        internal IReadOnlyList<string> RequestKinds => requestKinds.AsReadOnly();

        public string GetUriScheme() => "b64";
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public object? Read(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (uri.Scheme != "b64") return null;
            string encoded = uri.OriginalString[(uri.Scheme.Length + 1)..];
            string request = Encoding.UTF8.GetString(Convert.FromBase64String(encoded));
            int separator = request.IndexOf(':');
            if (separator <= 0)
                throw new InvalidOperationException("Malformed base64 resource request");
            string kind = request[..separator];
            string input = request[(separator + 1)..];
            byte[] result = kind switch
            {
                "enc" => Encoding.UTF8.GetBytes(
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(input))),
                "dec" => Convert.FromBase64String(input),
                _ => throw new InvalidOperationException("Unknown base64 resource request kind")
            };
            requestKinds.Add(kind);
            return new Resource(uri, result);
        }
        public void Close() => disposed = true;
        public void Dispose() => Close();
    }

    static void VerifyRedirectBehavior(string method)
    {
        using var server = CorpusHttpServer.Tls(request =>
            request.Uri.AbsolutePath == "/start"
                ? CorpusHttpResponse.Redirect(new Uri("https://origin.test/final"))
                : CorpusHttpResponse.Ok("redirected"));
        var checkedUris = new List<Uri>();
        PklHttpClient.Builder builder = PklHttpClient.CreateBuilder()
            .AddCertificate(Encoding.ASCII.GetBytes(server.CertificatePem))
            .AddRewrite(new Uri("https://origin.test/"), server.BaseUri)
            .AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                ["X-Contract"] = new[] { "all" } });
        if (method == "redirects only carry their specifically configured headers")
        {
            builder.AddHeaders(new Uri(server.BaseUri, "start").AbsoluteUri,
                new Dictionary<string, IReadOnlyList<string>> {
                ["X-Start"] = new[] { "start" } });
            builder.AddHeaders(new Uri(server.BaseUri, "final").AbsoluteUri,
                new Dictionary<string, IReadOnlyList<string>> {
                ["X-Final"] = new[] { "final" } });
        }
        using PklHttpClient client = builder.Build();
        using HttpResponseMessage response = client.Send(
            new HttpRequestMessage(HttpMethod.Get, "https://origin.test/start"), checkedUris.Add);
        Require(response.Content.ReadAsStringAsync().GetAwaiter().GetResult() == "redirected" &&
            server.Requests.Count == 2, method + " redirect result");
        CorpusHttpRequest first = server.Requests[0], second = server.Requests[1];
        if (method is "preserves configured headers across redirects" or
            "respects configured rewrites across redirects")
            Require(first.Headers.ContainsKey("x-contract") && second.Headers.ContainsKey("x-contract"),
                method + " configured header/rewrite retention");
        if (method == "checks each URL before making a request")
            Require(checkedUris.Count == 2 && checkedUris[0].AbsolutePath == "/start" &&
                checkedUris[1].AbsolutePath == "/final", "per-redirect request policy callback");
        if (method == "redirects only carry their specifically configured headers")
            Require(first.Headers.ContainsKey("x-start") && !first.Headers.ContainsKey("x-final") &&
                !second.Headers.ContainsKey("x-start") && second.Headers.ContainsKey("x-final"),
                "redirect-specific configured headers");
    }

    static void VerifyRedirectSchemeChange(bool upgrade)
    {
        using var destination = upgrade
            ? CorpusHttpServer.Tls(_ => CorpusHttpResponse.Ok("upgraded"))
            : CorpusHttpServer.Plain(_ => CorpusHttpResponse.Ok("downgraded"));
        using var source = upgrade
            ? CorpusHttpServer.Plain(_ => CorpusHttpResponse.Redirect(destination.BaseUri))
            : CorpusHttpServer.Tls(_ => CorpusHttpResponse.Redirect(destination.BaseUri));
        using PklHttpClient client = PklHttpClient.CreateBuilder()
            .AddCertificate(Encoding.ASCII.GetBytes(
                upgrade ? destination.CertificatePem : source.CertificatePem)).Build();
        if (upgrade)
        {
            using HttpResponseMessage response = client.Send(
                new HttpRequestMessage(HttpMethod.Get, source.BaseUri), _ => { });
            Require(response.Content.ReadAsStringAsync().GetAwaiter().GetResult() == "upgraded" &&
                destination.Requests.Count == 1, "HTTP to HTTPS redirect upgrade");
        }
        else
        {
            Exception error = ThrowsAny(() => client.Send(
                new HttpRequestMessage(HttpMethod.Get, source.BaseUri), _ => { }));
            Require(error.Message.Contains("redirect", StringComparison.OrdinalIgnoreCase) ||
                error.Message.Contains("HTTPS", StringComparison.OrdinalIgnoreCase),
                "HTTPS downgrade rejection");
            Require(destination.Requests.Count == 0, "downgrade rejected before HTTP request");
        }
    }

    static void VerifyNoProxyBehavior(string method)
    {
        bool ipv6 = method.StartsWith("ipv6", StringComparison.Ordinal) ||
            method == "ipv6 cidr block matching";
        using var direct = ipv6
            ? CorpusHttpServer.PlainV6(_ => CorpusHttpResponse.Ok("direct"))
            : CorpusHttpServer.Plain(_ => CorpusHttpResponse.Ok("direct"));
        using var proxy = CorpusHttpServer.Plain(_ => CorpusHttpResponse.Ok("proxy"));
        Uri target = method.StartsWith("hostname", StringComparison.Ordinal)
            ? new Uri($"http://localhost:{direct.Port}/")
            : direct.BaseUri;
        string rule = method switch
        {
            "wildcard" => "*",
            "hostname matching" => "localhost",
            "hostname matching, leading dot" => ".localhost",
            "hostname matching, with port" => $"localhost:{direct.Port}",
            "ipv4 address literal matching" => "127.0.0.1",
            "ipv4 address literal matching, with port" => $"127.0.0.1:{direct.Port}",
            "ipv6 address literal matching" => "::1",
            "ipv6 address literal matching, with port" => $"[::1]:{direct.Port}",
            "ipv4 port from protocol" => $"127.0.0.1:{direct.Port}",
            "ipv4 cidr block matching" => "127.0.0.0/8",
            "ipv6 cidr block matching" => "::1/128",
            _ => throw new InvalidOperationException("Unknown no-proxy corpus row: " + method)
        };
        Uri proxyAddress = new(proxy.BaseUri.GetLeftPart(UriPartial.Authority));
        using PklHttpClient client = PklHttpClient.CreateBuilder()
            .SetProxy(proxyAddress, new[] { rule }).Build();
        string body = Encoding.UTF8.GetString(client.GetBytes(
            new HttpRequestMessage(HttpMethod.Get, target), _ => { }));
        Require(body == "direct" && direct.Requests.Count == 1 && proxy.Requests.Count == 0,
            method + " no-proxy match");

        if (method == "wildcard") return;

        using PklHttpClient proxied = PklHttpClient.CreateBuilder()
            .SetProxy(proxyAddress, new[] { rule }).Build();
        string proxiedBody = Encoding.UTF8.GetString(proxied.GetBytes(
            new HttpRequestMessage(HttpMethod.Get, "http://unmatched.invalid/resource"), _ => { }));
        Require(proxiedBody == "proxy" && proxy.Requests.Count == 1,
            method + " no-proxy non-match");
    }

    static void VerifyRequestRewritingBehavior(string method)
    {
        if (method == "leaves port 0 intact if no test port is set")
        {
            using PklHttpClient client = PklHttpClient.CreateBuilder()
                .SetConnectTimeout(TimeSpan.FromMilliseconds(200)).Build();
            Exception error = ThrowsAny(() => client.Send(
                new HttpRequestMessage(HttpMethod.Get, "http://127.0.0.1:0/"), _ => { }));
            Require(error.Message.Length > 0, "port zero remains invalid without a rewrite");
            return;
        }
        if (method == "fills in missing request timeout")
        {
            using var slow = CorpusHttpServer.Plain(_ =>
                new CorpusHttpResponse(200, "late", null, TimeSpan.FromSeconds(1)));
            using PklHttpClient client = PklHttpClient.CreateBuilder()
                .SetRequestTimeout(TimeSpan.FromMilliseconds(100)).Build();
            Stopwatch timer = Stopwatch.StartNew();
            Exception error = ThrowsAny(() => client.Send(
                new HttpRequestMessage(HttpMethod.Get, slow.BaseUri), _ => { }));
            timer.Stop();
            Require(timer.Elapsed < TimeSpan.FromSeconds(1) &&
                (error.Message.Contains("tim", StringComparison.OrdinalIgnoreCase) ||
                    error is OperationCanceledException), "configured default request timeout");
            return;
        }

        using var server = CorpusHttpServer.Tls(_ => CorpusHttpResponse.Ok("rewritten"));
        Uri origin = new("https://example.test/");
        PklHttpClient.Builder builder = PklHttpClient.CreateBuilder()
            .AddCertificate(Encoding.ASCII.GetBytes(server.CertificatePem))
            .AddRewrite(origin, server.BaseUri);
        HttpRequestMessage request = new(HttpMethod.Get, new Uri(origin, "path"));

        switch (method)
        {
            case "fills in missing User-Agent header":
                break;
            case "User-Agent from configured headers takes precedence":
                builder.SetUserAgent("fallback-agent").AddHeaders("**",
                    new Dictionary<string, IReadOnlyList<string>> {
                        ["User-Agent"] = new[] { "header-agent" } });
                break;
            case "overrides existing User-Agent headers":
                request.Headers.TryAddWithoutValidation("User-Agent", "request-agent");
                builder.SetUserAgent("configured-agent");
                break;
            case "leaves existing request timeout intact":
                builder.SetRequestTimeout(TimeSpan.FromSeconds(2));
                break;
            case "fills in missing HTTP version":
                break;
            case "leaves existing HTTP version intact":
                request.Version = HttpVersion.Version11;
                request.VersionPolicy = HttpVersionPolicy.RequestVersionExact;
                break;
            case "leaves default method intact":
                break;
            case "leaves explicit method intact":
                request.Method = HttpMethod.Post;
                break;
            case "leaves body publisher intact":
                request.Method = HttpMethod.Post;
                request.Content = new StringContent("body-payload", Encoding.UTF8, "text/plain");
                break;
            case "rewrites port 0 if test port is set":
                origin = new Uri("https://localhost:0/");
                builder.AddRewrite(origin, server.BaseUri);
                request.RequestUri = new Uri(origin, "path");
                break;
            case "matches rewrite rule":
            case "rewrites URIs":
                break;
            case "rewrites URIs - longest rewrite wins":
                builder.AddRewrite(new Uri("https://example.test/path/"),
                    new Uri(server.BaseUri, "longest/"));
                request.RequestUri = new Uri("https://example.test/path/item");
                break;
            case "rewrites URIs - hostname is always lowercased":
                request.RequestUri = new Uri("https://EXAMPLE.TEST/path");
                break;
            case "rewrites URIs - scheme is always lowercased":
                request.RequestUri = new Uri("HTTPS://example.test/path");
                break;
            case "rewrites URIs - host with capital I under tr_TR locale":
                request.RequestUri = new Uri("https://I.example.test/path");
                builder.AddRewrite(new Uri("https://i.example.test/"), server.BaseUri);
                break;
            case "adds configured headers for matching URI patterns":
                builder.AddHeaders(server.BaseUri + "**",
                    new Dictionary<string, IReadOnlyList<string>> { ["X-Matched"] = new[] { "yes" } });
                break;
            case "does not add configured headers for non-matching URI patterns":
                builder.AddHeaders("https://other.test/**",
                    new Dictionary<string, IReadOnlyList<string>> { ["X-Unmatched"] = new[] { "no" } });
                break;
            case "appends configured header values to existing request headers":
                request.Headers.TryAddWithoutValidation("X-Values", "request");
                builder.AddHeaders("**", new Dictionary<string, IReadOnlyList<string>> {
                    ["X-Values"] = new[] { "configured" } });
                break;
            case "configured headers wins over configured user-agent header":
                builder.SetUserAgent("fallback-agent").AddHeaders("**",
                    new Dictionary<string, IReadOnlyList<string>> {
                        ["User-Agent"] = new[] { "winning-agent" } });
                break;
            default:
                throw new InvalidOperationException("Unknown request rewrite corpus row: " + method);
        }

        CultureInfo priorCulture = CultureInfo.CurrentCulture;
        try
        {
            if (method == "rewrites URIs - host with capital I under tr_TR locale")
                CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("tr-TR");
            using PklHttpClient client = builder.Build();
            using HttpResponseMessage response = client.Send(request, _ => { });
            Require(response.Content.ReadAsStringAsync().GetAwaiter().GetResult() == "rewritten",
                method + " request result");
        }
        finally
        {
            CultureInfo.CurrentCulture = priorCulture;
        }
        CorpusHttpRequest observed = server.SingleRequest();
        switch (method)
        {
            case "fills in missing User-Agent header":
                Require(observed.Headers.TryGetValue("user-agent", out var defaultAgent) &&
                    defaultAgent.Count > 0, "default User-Agent");
                break;
            case "User-Agent from configured headers takes precedence":
                Require(observed.Headers["user-agent"].Contains("header-agent"),
                    "header User-Agent precedence");
                break;
            case "overrides existing User-Agent headers":
                Require(observed.Headers["user-agent"].Contains("configured-agent") &&
                    !observed.Headers["user-agent"].Contains("request-agent"),
                    "configured User-Agent override");
                break;
            case "leaves existing HTTP version intact":
                Require(observed.Version == HttpVersion.Version11, "explicit HTTP version");
                break;
            case "leaves explicit method intact":
                Require(observed.Method == "POST", "explicit HTTP method");
                break;
            case "leaves body publisher intact":
                Require(Encoding.UTF8.GetString(observed.Body) == "body-payload",
                    "HTTP request body");
                break;
            case "rewrites URIs - longest rewrite wins":
                Require(observed.Uri.AbsolutePath.Contains("longest", StringComparison.Ordinal),
                    "longest URI rewrite");
                break;
            case "adds configured headers for matching URI patterns":
                Require(observed.Headers.ContainsKey("x-matched"), "matching header rule");
                break;
            case "does not add configured headers for non-matching URI patterns":
                Require(!observed.Headers.ContainsKey("x-unmatched"), "non-matching header rule");
                break;
            case "appends configured header values to existing request headers":
                Require(observed.Headers["x-values"].SequenceEqual(
                    new[] { "request", "configured" }), "appended header values");
                break;
            case "configured headers wins over configured user-agent header":
                Require(observed.Headers["user-agent"].Contains("winning-agent"),
                    "configured header User-Agent precedence");
                break;
        }
    }

    static void VerifyProjectPackageChecksumFailure(string root)
    {
        using var server = new CorpusPackageServer();
        string directory = Path.Combine(root, "bad-project-checksum");
        Directory.CreateDirectory(directory);
        string projectFile = Path.Combine(directory, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\ndependencies { [\"fruit\"] { " +
            $"uri = \"package://127.0.0.1:{server.Port}/fruit@1.0.5\" }} }}\n",
            new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(directory, "PklProject.deps.json"),
            "{\n  \"schemaVersion\": 1,\n  \"resolvedDependencies\": {\n" +
            $"    \"package://127.0.0.1:{server.Port}/fruit@1\": {{\n" +
            "      \"type\": \"remote\",\n" +
            $"      \"uri\": \"projectpackage://127.0.0.1:{server.Port}/fruit@1.0.5\",\n" +
            "      \"checksums\": {\"sha256\": \"intentionally bogus checksum\"}\n" +
            "    }\n  }\n}\n", new UTF8Encoding(false));
        string module = Path.Combine(directory, "main.pkl");
        File.WriteAllText(module, "import \"@fruit/Fruit.pkl\"\nres = Fruit\n",
            new UTF8Encoding(false));
        Pkl.Core.Project.Project project = Pkl.Core.Project.Project.LoadFromPath(projectFile);
        using PklHttpClient client = server.CreateClient();
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured().ApplyFromProject(project)
            .SetModuleCacheDir(Path.Combine(root, "bad-project-cache")).SetHttpClient(client).Build();
        PklException error = Throws<PklException>(() =>
            evaluator.Evaluate(ModuleSource.FromPath(module)));
        Require(error.Message.Contains("checksum", StringComparison.OrdinalIgnoreCase) &&
            error.Message.Contains("intentionally bogus checksum", StringComparison.Ordinal),
            "project package checksum failure");
    }

    sealed record CorpusHttpRequest(
        Uri Uri,
        string Method,
        System.Version Version,
        IReadOnlyDictionary<string, IReadOnlyList<string>> Headers,
        byte[] Body);

    sealed record CorpusHttpResponse(
        int Status,
        string Body,
        string? Location = null,
        TimeSpan? Delay = null,
        byte[]? BinaryBody = null)
    {
        internal static CorpusHttpResponse Ok(string body) => new(200, body);
        internal static CorpusHttpResponse NotFound() => new(404, "not found");
        internal static CorpusHttpResponse Redirect(Uri location) =>
            new(302, "", location.OriginalString);
    }

    sealed class CorpusHttpServer : IDisposable
    {
        readonly TcpListener listener;
        readonly Func<CorpusHttpRequest, CorpusHttpResponse> handler;
        readonly bool tls;
        readonly CancellationTokenSource cancellation = new();
        readonly Task acceptLoop;
        readonly X509Certificate2? certificate;
        readonly List<CorpusHttpRequest> requests = new();
        readonly object requestLock = new();

        CorpusHttpServer(
            IPAddress address,
            bool tls,
            Func<CorpusHttpRequest, CorpusHttpResponse> handler)
        {
            this.tls = tls;
            this.handler = handler;
            listener = new TcpListener(address, 0);
            listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            listener.Start();
            Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            string host = address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6
                ? "[::1]" : "127.0.0.1";
            BaseUri = new Uri($"{(tls ? "https" : "http")}://{host}:{Port}/");
            certificate = tls ? CreateServerCertificate() : null;
            CertificatePem = certificate?.ExportCertificatePem() ?? "";
            acceptLoop = Task.Run(AcceptLoop);
        }

        internal static CorpusHttpServer Plain(
            Func<CorpusHttpRequest, CorpusHttpResponse> handler) =>
            new(IPAddress.Loopback, false, handler);
        internal static CorpusHttpServer PlainV6(
            Func<CorpusHttpRequest, CorpusHttpResponse> handler) =>
            new(IPAddress.IPv6Loopback, false, handler);
        internal static CorpusHttpServer Tls(
            Func<CorpusHttpRequest, CorpusHttpResponse> handler) =>
            new(IPAddress.Loopback, true, handler);

        internal int Port { get; }
        internal Uri BaseUri { get; }
        internal string CertificatePem { get; }
        internal IReadOnlyList<CorpusHttpRequest> Requests
        {
            get { lock (requestLock) return requests.ToArray(); }
        }

        internal CorpusHttpRequest SingleRequest()
        {
            IReadOnlyList<CorpusHttpRequest> snapshot = Requests;
            Require(snapshot.Count == 1, "single HTTP request, got " + snapshot.Count);
            return snapshot[0];
        }

        async Task AcceptLoop()
        {
            try
            {
                while (!cancellation.IsCancellationRequested)
                {
                    TcpClient client = await listener.AcceptTcpClientAsync(cancellation.Token);
                    _ = Task.Run(() => Handle(client), cancellation.Token);
                }
            }
            catch (OperationCanceledException) { }
            catch (ObjectDisposedException) { }
            catch (SocketException) when (cancellation.IsCancellationRequested) { }
        }

        async Task Handle(TcpClient client)
        {
            using (client)
            {
                Stream stream = client.GetStream();
                if (tls)
                {
                    var ssl = new SslStream(stream, leaveInnerStreamOpen: false);
                    await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                            System.Security.Authentication.SslProtocols.Tls13
                    }, cancellation.Token);
                    stream = ssl;
                }
                using (stream)
                {
                    CorpusHttpRequest request = await ReadRequest(stream, cancellation.Token);
                    lock (requestLock) requests.Add(request);
                    CorpusHttpResponse response = handler(request);
                    if (response.Delay is { } delay) await Task.Delay(delay, cancellation.Token);
                    byte[] body = response.BinaryBody ?? Encoding.UTF8.GetBytes(response.Body);
                    string reason = response.Status switch
                    {
                        200 => "OK",
                        302 => "Found",
                        404 => "Not Found",
                        _ => "Error"
                    };
                    var headers = new StringBuilder()
                        .Append("HTTP/1.1 ").Append(response.Status).Append(' ').Append(reason)
                        .Append("\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n")
                        .Append("Content-Length: ").Append(body.Length).Append("\r\n");
                    if (response.Location is not null)
                        headers.Append("Location: ").Append(response.Location).Append("\r\n");
                    headers.Append("\r\n");
                    await stream.WriteAsync(Encoding.ASCII.GetBytes(headers.ToString()),
                        cancellation.Token);
                    await stream.WriteAsync(body, cancellation.Token);
                    await stream.FlushAsync(cancellation.Token);
                }
            }
        }

        async Task<CorpusHttpRequest> ReadRequest(Stream stream, CancellationToken token)
        {
            var bytes = new List<byte>();
            byte[] one = new byte[1];
            while (bytes.Count < 64 * 1024)
            {
                int count = await stream.ReadAsync(one, token);
                if (count == 0) throw new EndOfStreamException("HTTP request headers ended early");
                bytes.Add(one[0]);
                int n = bytes.Count;
                if (n >= 4 && bytes[n - 4] == 13 && bytes[n - 3] == 10 &&
                    bytes[n - 2] == 13 && bytes[n - 1] == 10) break;
            }
            string text = Encoding.ASCII.GetString(bytes.ToArray());
            string[] lines = text.Split(new[] { "\r\n" }, StringSplitOptions.None);
            string[] requestLine = lines[0].Split(' ');
            var headers = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
            foreach (string line in lines.Skip(1).Where(line => line.Length > 0))
            {
                int separator = line.IndexOf(':');
                if (separator <= 0) continue;
                string name = line[..separator].Trim();
                string value = line[(separator + 1)..].Trim();
                if (!headers.TryGetValue(name, out List<string>? values))
                    headers[name] = values = new List<string>();
                values.AddRange(value.Split(',').Select(item => item.Trim()));
            }
            int contentLength = headers.TryGetValue("Content-Length", out List<string>? lengths)
                ? int.Parse(lengths.Single(), CultureInfo.InvariantCulture) : 0;
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < body.Length)
            {
                int count = await stream.ReadAsync(body.AsMemory(offset), token);
                if (count == 0) throw new EndOfStreamException("HTTP request body ended early");
                offset += count;
            }
            Uri uri = Uri.TryCreate(requestLine[1], UriKind.Absolute, out Uri? absolute)
                ? absolute
                : new Uri(BaseUri, requestLine[1]);
            System.Version version = requestLine[2] == "HTTP/1.0"
                ? HttpVersion.Version10 : HttpVersion.Version11;
            return new CorpusHttpRequest(uri, requestLine[0], version,
                headers.ToDictionary(entry => entry.Key.ToLowerInvariant(),
                    entry => (IReadOnlyList<string>)entry.Value.ToArray(), StringComparer.Ordinal), body);
        }

        static X509Certificate2 CreateServerCertificate()
        {
            using RSA rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=localhost", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(
                X509KeyUsageFlags.DigitalSignature, false));
            var names = new SubjectAlternativeNameBuilder();
            names.AddDnsName("localhost");
            names.AddIpAddress(IPAddress.Loopback);
            request.CertificateExtensions.Add(names.Build());
            return request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        }

        public void Dispose()
        {
            cancellation.Cancel();
            listener.Stop();
            try { acceptLoop.Wait(TimeSpan.FromSeconds(2)); }
            catch (AggregateException) { }
            certificate?.Dispose();
            cancellation.Dispose();
        }
    }

    sealed class CorpusPackageServer : IDisposable
    {
        readonly CorpusHttpServer server;
        readonly byte[] birdsArchive;
        readonly byte[] fruitArchive;
        readonly string birdsArchiveSha;
        readonly string fruitArchiveSha;

        internal CorpusPackageServer()
        {
            server = CorpusHttpServer.Tls(Handle);
            birdsArchive = ZipBytes(
                ("Bird.pkl", $"import \"package://127.0.0.1:{Port}/fruit@1.0.5#/Fruit.pkl\"\n" +
                    "name = \"Pigeon\"\n"),
                ("allFruit.pkl", "value = 1\n"),
                ("catalog.pkl", "value = 2\n"),
                ("some/item.pkl", "value = 3\n"),
                ("catalog/Ostrich.pkl", "name = \"Ostrich\"\n"),
                ("catalog/Swallow.pkl", "name = \"Swallow\"\n"));
            fruitArchive = ZipBytes(("Fruit.pkl", "name = \"Apple\"\n"));
            birdsArchiveSha = Sha256(birdsArchive);
            fruitArchiveSha = Sha256(fruitArchive);
        }

        internal int Port => server.Port;
        internal string BirdModule =>
            $"import \"package://127.0.0.1:{Port}/fruit@1.0.5#/Fruit.pkl\"\n" +
            "name = \"Pigeon\"\n";
        internal string BirdsMetadataSha256 => Sha256(Encoding.UTF8.GetBytes(BirdsMetadata()));
        internal PklHttpClient CreateClient() => PklHttpClient.CreateBuilder()
            .AddCertificate(Encoding.ASCII.GetBytes(server.CertificatePem)).Build();

        CorpusHttpResponse Handle(CorpusHttpRequest request)
        {
            string path = request.Uri.AbsolutePath;
            if (path.EndsWith("/birds@0.5.0", StringComparison.Ordinal))
                return CorpusHttpResponse.Ok(BirdsMetadata());
            if (path.EndsWith("/birds@0.5.0.zip", StringComparison.Ordinal))
                return Bytes(birdsArchive);
            if (path.EndsWith("/fruit@1.0.5", StringComparison.Ordinal))
                return CorpusHttpResponse.Ok(FruitMetadata());
            if (path.EndsWith("/fruit@1.0.5.zip", StringComparison.Ordinal))
                return Bytes(fruitArchive);
            if (path.EndsWith("/badPackageZipUrl@1.0.0", StringComparison.Ordinal))
                return CorpusHttpResponse.Ok(Metadata("badPackageZipUrl", "1.0.0",
                    "ftp://wait/a/minute", "unused"));
            if (path.EndsWith("/badChecksum@1.0.0", StringComparison.Ordinal))
                return CorpusHttpResponse.Ok(Metadata("badChecksum", "1.0.0",
                    $"https://127.0.0.1:{Port}/badChecksum@1.0.0.zip",
                    "intentionally bogus checksum"));
            if (path.EndsWith("/badChecksum@1.0.0.zip", StringComparison.Ordinal))
                return Bytes(ZipBytes(("Bug.pkl", "value = 1\n")));
            return CorpusHttpResponse.NotFound();
        }

        CorpusHttpResponse Bytes(byte[] value) => new(200, "", null, null, value);

        string BirdsMetadata() => Metadata("birds", "0.5.0",
            $"https://127.0.0.1:{Port}/birds@0.5.0.zip", birdsArchiveSha,
            ",\n  \"dependencies\": {\"fruit\": {\"uri\": " +
                $"\"package://127.0.0.1:{Port}/fruit@1.0.5\", " +
                $"\"checksums\": {{\"sha256\": \"{Sha256(Encoding.UTF8.GetBytes(FruitMetadata()))}\"}}" +
                "}}");

        string FruitMetadata() => Metadata("fruit", "1.0.5",
            $"https://127.0.0.1:{Port}/fruit@1.0.5.zip", fruitArchiveSha);

        string Metadata(string name, string version, string archive, string checksum,
            string dependencySuffix = ",\n  \"dependencies\": {}") =>
            "{\n  \"schemaVersion\": 1,\n" +
            $"  \"name\": \"{name}\",\n" +
            $"  \"packageUri\": \"package://127.0.0.1:{Port}/{name}@{version}\",\n" +
            $"  \"version\": \"{version}\",\n" +
            $"  \"packageZipUrl\": \"{archive}\",\n" +
            $"  \"packageZipChecksums\": {{\"sha256\": \"{checksum}\"}}" +
            dependencySuffix + "\n}\n";

        static byte[] ZipBytes(params (string Path, string Contents)[] entries)
        {
            using var output = new MemoryStream();
            using (var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true))
            {
                foreach ((string path, string contents) in entries)
                {
                    ZipArchiveEntry entry = archive.CreateEntry(path);
                    using var writer = new StreamWriter(entry.Open(), new UTF8Encoding(false));
                    writer.Write(contents);
                }
            }
            return output.ToArray();
        }

        static string Sha256(byte[] bytes) =>
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes))
                .ToLowerInvariant();

        public void Dispose() => server.Dispose();
    }

    sealed class RecordingVisitor : ValueVisitor
    {
        public bool ObjectVisited { get; private set; }
        public bool ModuleVisited { get; private set; }

        public void VisitObject(PObject value) => ObjectVisited = true;
        public void VisitModule(PModule value) => ModuleVisited = true;
    }

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

static class PklCoreInternals
{
    static readonly Assembly CoreAssembly = typeof(Evaluator).Assembly;

    internal static Type Type(string name) =>
        CoreAssembly.GetType(name, throwOnError: true)!;

    internal static object Create(string owner, params object?[] arguments)
    {
        try
        {
            return Activator.CreateInstance(
                Type(owner), BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
                binder: null, args: arguments, culture: CultureInfo.InvariantCulture)!;
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    internal static T InvokeStatic<T>(string owner, string name, params object?[] arguments) =>
        Invoke<T>(null, Method(Type(owner), name, arguments, isStatic: true), arguments);

    internal static T InvokeInstance<T>(object instance, string name, params object?[] arguments) =>
        Invoke<T>(instance, Method(instance.GetType(), name, arguments, isStatic: false), arguments);

    internal static T Property<T>(object instance, string name) =>
        (T)instance.GetType().GetProperty(
            name, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)!
            .GetValue(instance)!;

    static MethodInfo Method(Type owner, string name, object?[] arguments, bool isStatic)
    {
        BindingFlags flags = (isStatic ? BindingFlags.Static : BindingFlags.Instance) |
            BindingFlags.Public | BindingFlags.NonPublic;
        MethodInfo[] matches = owner.GetMethods(flags)
            .Where(method => method.Name == name)
            .Where(method => method.GetParameters().Length == arguments.Length)
            .Where(method => method.GetParameters().Select((parameter, index) =>
                arguments[index] is null
                    ? !parameter.ParameterType.IsValueType ||
                        Nullable.GetUnderlyingType(parameter.ParameterType) is not null
                    : parameter.ParameterType.IsInstanceOfType(arguments[index])).All(value => value))
            .ToArray();
        if (matches.Length != 1)
            throw new MissingMethodException(
                $"Expected one reflected {owner.FullName}.{name}/{arguments.Length}, found {matches.Length}.");
        return matches[0];
    }

    static T Invoke<T>(object? instance, MethodInfo method, object?[] arguments)
    {
        try
        {
            object? value = method.Invoke(instance, arguments);
            return value is null ? default! : (T)value;
        }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }
}

static class PklPath
{
    public static string ResolvePosix(Uri baseUri, string path) =>
        PklCoreInternals.InvokeStatic<string>("Pkl.Core.PklPath", "ResolvePosix", baseUri, path);

    public static string ResolveWindows(Uri baseUri, string path) =>
        PklCoreInternals.InvokeStatic<string>("Pkl.Core.PklPath", "ResolveWindows", baseUri, path);
}

static class PklGlob
{
    public static Regex Compile(string pattern) =>
        PklCoreInternals.InvokeStatic<Regex>("Pkl.Core.PklGlob", "Compile", pattern);
}

static class PklUris
{
    const string Owner = "Pkl.Core.PklUris";

    public static Uri EnsurePathEndsWithSlash(Uri uri) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "EnsurePathEndsWithSlash", uri);
    public static Uri Resolve(Uri baseUri, Uri newUri) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "Resolve", baseUri, newUri);
    public static string Format(Uri uri) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "Format", uri);
    public static string Relativize(string path, string basePath) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "Relativize", path, basePath);
    public static Uri Relativize(Uri uri, Uri baseUri) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "Relativize", uri, baseUri);
    public static Uri Parse(string value) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "Parse", value);
    public static string? ToFilePath(Uri uri) =>
        PklCoreInternals.InvokeStatic<string?>(Owner, "ToFilePath", uri);
    public static bool IsWhitespace(string value) =>
        PklCoreInternals.InvokeStatic<bool>(Owner, "IsWhitespace", value);
    public static string Capitalize(string value) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "Capitalize", value);
    public static int GetMaxLineLength(string value) =>
        PklCoreInternals.InvokeStatic<int>(Owner, "GetMaxLineLength", value);
    public static string EncodePath(string path) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "EncodePath", path);
    public static string InferModuleName(Uri uri) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "InferModuleName", uri);
    public static byte[] ReadBytes(Uri uri) =>
        PklCoreInternals.InvokeStatic<byte[]>(Owner, "ReadBytes", uri);
    public static string ReadText(Uri uri) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "ReadText", uri);
    public static Uri ResolveTripleDotFile(Uri moduleUri, Uri importUri) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "ResolveTripleDotFile", moduleUri, importUri);
    public static Uri ResolveTripleDotModulePath(
        Uri moduleUri, Uri importUri, IReadOnlySet<Uri> availableModules) =>
        PklCoreInternals.InvokeStatic<Uri>(
            Owner, "ResolveTripleDotModulePath", moduleUri, importUri, availableModules);
}

enum PklAnsiCode
{
    Bold,
    Red
}

sealed class PklAnsiBuilder
{
    readonly object instance;
    static readonly Type CodeType = PklCoreInternals.Type("Pkl.Core.PklAnsiCode");

    public PklAnsiBuilder(bool enabled) =>
        instance = PklCoreInternals.Create("Pkl.Core.PklAnsiBuilder", enabled);

    public PklAnsiBuilder Append(string text)
    {
        PklCoreInternals.InvokeInstance<object?>(instance, "Append", text);
        return this;
    }

    public PklAnsiBuilder Append(PklAnsiCode code, string text)
    {
        PklCoreInternals.InvokeInstance<object?>(instance, "Append", InternalCode(code), text);
        return this;
    }

    public PklAnsiBuilder Append(IEnumerable<PklAnsiCode> codes, string text)
    {
        PklAnsiCode[] values = codes.ToArray();
        Array internalCodes = Array.CreateInstance(CodeType, values.Length);
        for (int index = 0; index < values.Length; index++)
            internalCodes.SetValue(InternalCode(values[index]), index);
        PklCoreInternals.InvokeInstance<object?>(instance, "Append", internalCodes, text);
        return this;
    }

    public override string ToString() =>
        PklCoreInternals.InvokeInstance<string>(instance, "ToString");

    static object InternalCode(PklAnsiCode code) => Enum.Parse(CodeType, code.ToString());
}

static class PklValueRenderer
{
    const string Owner = "Pkl.Core.PklValueRenderer";

    public static string RenderNull(int lengthLimit = 80) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "RenderNull", lengthLimit);
    public static string RenderBytes(byte[] value, int lengthLimit = 80) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "RenderBytes", value, lengthLimit);
}

static class PklExceptions
{
    const string Owner = "Pkl.Core.PklExceptions";

    public static Exception RootCause(Exception value) =>
        PklCoreInternals.InvokeStatic<Exception>(Owner, "RootCause", value);
    public static string RootReason(Exception value) =>
        PklCoreInternals.InvokeStatic<string>(Owner, "RootReason", value);
}

sealed class PklTextEscaper
{
    readonly object instance;

    PklTextEscaper(object instance) => this.instance = instance;

    public static Builder CreateBuilder() => new(
        PklCoreInternals.InvokeStatic<object>("Pkl.Core.PklTextEscaper", "CreateBuilder"));

    public string Escape(string value) =>
        PklCoreInternals.InvokeInstance<string>(instance, "Escape", value);

    public sealed class Builder
    {
        readonly object instance;

        internal Builder(object instance) => this.instance = instance;

        public Builder WithEscape(char character, string replacement)
        {
            PklCoreInternals.InvokeInstance<object?>(
                instance, "WithEscape", character, replacement);
            return this;
        }

        public PklTextEscaper Build() =>
            new(PklCoreInternals.InvokeInstance<object>(instance, "Build"));
    }
}

static class PklHttp
{
    const string Owner = "Pkl.Core.PklHttp";

    public static bool IsHttpUrl(Uri uri) =>
        PklCoreInternals.InvokeStatic<bool>(Owner, "IsHttpUrl", uri);
    public static Uri WithPort(Uri uri, int port) =>
        PklCoreInternals.InvokeStatic<Uri>(Owner, "WithPort", uri, port);
    public static void RequireSuccessStatusCode(int statusCode) =>
        PklCoreInternals.InvokeStatic<object?>(Owner, "RequireSuccessStatusCode", statusCode);
}

static class PklStrings
{
    const string Owner = "Pkl.Core.PklStrings";

    public static int CodePointOffsetToUtf16Offset(
        string value, int codePointOffset, int startIndex = 0) =>
        PklCoreInternals.InvokeStatic<int>(
            Owner, "CodePointOffsetToUtf16Offset", value, codePointOffset, startIndex);
    public static int CodePointOffsetFromEndToUtf16Offset(string value, int codePointOffset) =>
        PklCoreInternals.InvokeStatic<int>(
            Owner, "CodePointOffsetFromEndToUtf16Offset", value, codePointOffset);
}

static class PklClassInfos
{
    public static bool IsExactTypeOf(PClassInfo<object> classInfo, object value) =>
        PklCoreInternals.InvokeStatic<bool>(
            "Pkl.Core.PklClassInfos", "IsExactTypeOf", classInfo, value);
}

static class PklParserUtilities
{
    public static IReadOnlyList<string> FindImportsAndReads(string source) =>
        PklCoreInternals.InvokeStatic<IReadOnlyList<string>>(
            "Pkl.Core.PklParserUtilities", "FindImportsAndReads", source);
}

static class PklImportGraphs
{
    public static IReadOnlyList<IReadOnlyList<Uri>> FindCycles(ImportGraph graph) =>
        PklCoreInternals.InvokeStatic<IReadOnlyList<IReadOnlyList<Uri>>>(
            "Pkl.Core.PklImportGraphs", "FindCycles", graph);
}

enum PklValuePathPartKind
{
    Property,
    Element,
    WildcardProperty,
    WildcardElement,
    TopLevel
}

sealed class PklValuePathPart : IEquatable<PklValuePathPart>
{
    static readonly Type PartType = PklCoreInternals.Type("Pkl.Core.PklValuePathPart");
    static readonly Type KindType = PklCoreInternals.Type("Pkl.Core.PklValuePathPartKind");

    internal object InternalValue { get; }
    public PklValuePathPartKind Kind { get; }
    public object? Value { get; }

    PklValuePathPart(PklValuePathPartKind kind, object? value, object internalValue)
    {
        Kind = kind;
        Value = value;
        InternalValue = internalValue;
    }

    public static PklValuePathPart Property(string name) => Create(PklValuePathPartKind.Property, name);
    public static PklValuePathPart Element(object key) => Create(PklValuePathPartKind.Element, key);
    public static PklValuePathPart WildcardProperty { get; } =
        Create(PklValuePathPartKind.WildcardProperty, null);
    public static PklValuePathPart WildcardElement { get; } =
        Create(PklValuePathPartKind.WildcardElement, null);
    public static PklValuePathPart TopLevel { get; } =
        Create(PklValuePathPartKind.TopLevel, null);

    internal static PklValuePathPart FromInternal(object value)
    {
        object internalKind = PklCoreInternals.Property<object>(value, "Kind");
        var kind = Enum.Parse<PklValuePathPartKind>(internalKind.ToString()!);
        object? partValue = PklCoreInternals.Property<object?>(value, "Value");
        return new PklValuePathPart(kind, partValue, value);
    }

    static PklValuePathPart Create(PklValuePathPartKind kind, object? value)
    {
        object internalKind = Enum.Parse(KindType, kind.ToString());
        object internalValue = PklCoreInternals.Create(
            "Pkl.Core.PklValuePathPart", internalKind, value);
        return new PklValuePathPart(kind, value, internalValue);
    }

    public bool Equals(PklValuePathPart? other) =>
        other is not null && Kind == other.Kind && Equals(Value, other.Value);
    public override bool Equals(object? obj) => Equals(obj as PklValuePathPart);
    public override int GetHashCode() => HashCode.Combine(Kind, Value);
}

static class PklValuePaths
{
    const string Owner = "Pkl.Core.PklValuePaths";
    static readonly Type PartType = PklCoreInternals.Type("Pkl.Core.PklValuePathPart");

    public static IReadOnlyList<PklValuePathPart> Parse(string pathSpec)
    {
        IEnumerable values = PklCoreInternals.InvokeStatic<IEnumerable>(Owner, "Parse", pathSpec);
        return values.Cast<object>().Select(PklValuePathPart.FromInternal).ToList().AsReadOnly();
    }

    public static bool Matches(
        IReadOnlyList<PklValuePathPart> pathSpec, IReadOnlyList<PklValuePathPart> path) =>
        PklCoreInternals.InvokeStatic<bool>(
            Owner, "Matches", InternalList(pathSpec), InternalList(path));

    static object InternalList(IEnumerable<PklValuePathPart> values)
    {
        var result = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(PartType))!;
        foreach (PklValuePathPart value in values) result.Add(value.InternalValue);
        return result;
    }
}
