using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using DripSharp.Brine;
using DripSharp.Brine.EvaluatorSettings;
using DripSharp.Brine.Externalreader;
using DripSharp.Brine.Http;
using DripSharp.Brine.Module;
using DripSharp.Brine.Packages;
using DripSharp.Brine.Project;
using DripSharp.Brine.Resource;
using DripSharp.Brine.Settings;
using PklHttpClient = DripSharp.Brine.Http.HttpClient;
using StackFrame = DripSharp.Brine.StackFrame;

/** Package-only .NET probe for the loader, package, and policy contract. */
static class LoadingContractDotNetProbe
{
    public static void Main(string[] args)
    {
        if (Array.IndexOf(args, "--external-reader") >= 0)
        {
            ExternalReaderFixture.Run(Console.OpenStandardInput(), Console.OpenStandardOutput());
            return;
        }
        int blockingReader = Array.IndexOf(args, "--external-reader-block");
        if (blockingReader >= 0)
        {
            if (blockingReader + 1 >= args.Length)
                throw new ArgumentException("The blocking external reader requires a PID marker path.");
            File.WriteAllText(args[blockingReader + 1], Environment.ProcessId.ToString(CultureInfo.InvariantCulture));
            ExternalReaderFixture.Run(
                Console.OpenStandardInput(), Console.OpenStandardOutput(), blockResponses: true);
            return;
        }
        int faultReader = Array.IndexOf(args, "--external-reader-fault");
        if (faultReader >= 0)
        {
            if (faultReader + 2 >= args.Length)
                throw new ArgumentException("The faulting external reader requires a scenario and PID marker path.");
            ExternalReaderFixture.RunFault(
                Console.OpenStandardInput(), Console.OpenStandardOutput(),
                args[faultReader + 1], args[faultReader + 2]);
            return;
        }
        int failureParent = Array.IndexOf(args, "--external-reader-failure-parent");
        if (failureParent >= 0)
        {
            if (failureParent + 1 >= args.Length)
                throw new ArgumentException("The external-reader failure parent requires a work path.");
            RunExternalReaderFailureParent(Path.GetFullPath(args[failureParent + 1]));
            return;
        }
        if (args.Length != 5)
            throw new ArgumentException(
                "fixture, output, work, upstream package-build, and packed-assembly manifest paths are required");
        string fixtures = Path.GetFullPath(args[0]);
        string output = Path.GetFullPath(args[1]);
        string work = Path.GetFullPath(args[2]);
        string packageBuild = Path.GetFullPath(args[3]);
        string assemblyManifest = Path.GetFullPath(args[4]);
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        ResetDirectory(work);
        VerifyPackedAssemblies(assemblyManifest);

        using var writer = new StreamWriter(output, false, new UTF8Encoding(false));
        Write(writer, "module-source/forms", "API", ObserveModuleSourceForms(work));
        Write(writer, "local/import-resource", "LOADING", ObserveLocal(fixtures));
        Write(writer, "local/list-glob", "LOADING", ObserveLocalGlob(fixtures));
        Write(writer, "modulepath/directory-archive", "LOADING", ObserveModulePath(fixtures, work));
        Write(writer, "stdlib/import", "LOADING", ObserveStandardLibrary());
        Write(writer, "custom/module-resource-lifecycle", "LOADING", ObserveCustomReaders());
        Write(writer, "resources/environment-property", "LOADING", ObserveEnvironmentAndProperties());
        Write(writer, "evaluator/builder", "API", ObserveEvaluatorBuilder(work));
        Write(writer, "module-resource/public-api", "API", ObserveModuleAndResourceApi(work));
        Write(writer, "http/public-api", "HTTP", ObserveHttpApi());
        Write(writer, "package/public-api", "PACKAGE", ObservePackageApi());
        Write(writer, "project-settings/public-api", "SETTINGS", ObserveProjectAndSettingsApi(fixtures));
        Write(writer, "security-external/public-api", "POLICY", ObserveSecurityAndExternalApi());
        Write(writer, "analyzer/import-graph", "API", ObserveAnalyzerAndImportGraph(work));
        Write(writer, "logging/public-api", "LOGGING", ObserveLogging());
        Write(writer, "diagnostics/stack-transform", "DIAGNOSTIC", ObserveStackTransforms(fixtures));
        Write(writer, "diagnostics/exception-metadata", "DIAGNOSTIC", ObserveExceptions());
        Write(writer, "runtime/platform-release", "API", ObservePlatformAndRelease());
        Write(writer, "security/policy", "POLICY", ObserveSecurityPolicy(work));
        NetworkObservations network = ObserveNetworkAndPackages(packageBuild, work);
        Write(writer, "https/rewrite-redirect-headers", "HTTP", network.Http);
        Write(writer, "package/assets-cache-integrity", "PACKAGE", network.Packages);
        Write(writer, "uri/decoded-components-package-assets", "URI", network.UriComponents);
        Write(writer, "collections/map-entry-set", "COLLECTION", network.Collections);
        Write(writer, "project/projectpackage-dependencies", "PROJECT", network.ProjectPackage);
        Write(writer, "network/package-errors", "ERROR", network.Errors);
        Write(writer, "project/evaluator-user-settings", "SETTINGS", ObserveSettings(fixtures, work));
        Write(writer, "errors/missing-invalid-io-type", "ERROR", ObserveErrors(fixtures, work));
        Write(writer, "project/dependency-cycles", "ERROR", ObserveProjectCycles(fixtures));
        Write(writer, "lifecycle/close", "LIFECYCLE", ObserveLifecycle());
        TimeoutObservations timeouts = ObserveTimeoutCancellation(packageBuild, work);
        Write(writer, "evaluator/timeout", "TIMEOUT", timeouts.Shared);
        Write(writer, "assembly/module-loading", "DOTNET", ObserveAssemblyModules());
        Write(writer, "embedded/resource-loading", "DOTNET", ObserveEmbeddedResources());
        Write(writer, "platform/path-uri-policy", "DOTNET", ObservePlatformPolicy(work));
        Write(writer, "ownership/disposal", "DOTNET", ObserveOwnership(fixtures, work));
        Write(writer, "idiomatic/loading-api-shapes", "DOTNET", ObserveIdiomaticLoadingApiShapes());
        Write(writer, "external/configured-process-loading", "DOTNET",
            ObserveConfiguredExternalReader(work));
        Write(writer, "external/failure-lifecycle", "DOTNET",
            ObserveExternalReaderFailureLifecycle(work));
        Write(writer, "evaluator/timeout-cleanup", "DOTNET", timeouts.Cleanup);
        Console.WriteLine("Package-only loading, package, and policy validation passed.");
    }

    static string ObserveModuleAndResourceApi(string work)
    {
        string sourcePath = Path.Combine(work, "public-api-module.pkl");
        File.WriteAllText(sourcePath, "value = 42\n", new UTF8Encoding(false));
        Uri sourceUri = new Uri(sourcePath);
        ModuleKey? file = ModuleKeyFactories.FileFactory.TryCreate(sourceUri);
        ModuleKey? unsupported = ModuleKeyFactories.HttpFactory.TryCreate(sourceUri);
        ModuleKey synthetic = ModuleKeys.CreateSynthetic(new Uri("repl:public-api"), "value = 1\n");
        ResolvedModuleKey resolved = ResolvedModuleKeys.CreateVirtual(
            synthetic, new Uri("repl:resolved-public-api"), "value = 2\n", true);
        var resource = new Resource(new Uri("memory:payload"), new byte[] { 0, 127, 128, 255 });
        using ResourceReader reader = ResourceReaders.CreateEmbeddedResources(
            Assembly.GetExecutingAssembly(), "Contract.Resources", "api-resource");
        object? missing = reader.TryRead(new Uri("api-resource:/missing.txt"));
        string invalid = ExceptionName(() => ModuleKeys.CreatePackage(new Uri("https://example.test/pkg")));
        return $"factory={file?.Uri.Scheme}:{Lower(file?.Local == true)}:{Lower(unsupported is null)}" +
            $"|synthetic={synthetic.Uri}:{resolved.Original.Uri}:{resolved.Uri}:{Escape(resolved.Source)}" +
            $"|resource={resource.Uri}:{Convert.ToHexString(resource.Bytes).ToLowerInvariant()}:" +
            $"{resource.Base64}:{Lower(missing is null)}|invalid={Lower(invalid != "none")}";
    }

    static string ObserveHttpApi()
    {
        bool byteOverload = typeof(PklHttpClient.Builder).GetMethods()
            .Any(method => method.Name == "AddCertificates" &&
                method.GetParameters() is [{ ParameterType: var type }] && type == typeof(byte[]));
        string invalidCertificate = ExceptionName(() =>
        {
            using PklHttpClient client = PklHttpClient.CreateBuilder()
                .AddCertificate(new byte[] { 1, 2, 3, 4 })
                .Build();
        });
        using PklHttpClient dummy = PklHttpClient.DummyClient();
        string dummyFailure = ExceptionName(() => dummy.Send(
            new HttpRequestMessage(System.Net.Http.HttpMethod.Get, "https://example.test/"),
            _ => { }));
        dummy.Dispose();
        return $"bytes={Lower(byteOverload)}|invalid={Lower(invalidCertificate == nameof(HttpClientException))}" +
            $"|dummy={Lower(dummyFailure != "none")}|dispose=true";
    }

    static string ObservePackageApi()
    {
        var packageUri = new PackageUri("package://example.test/birds@1.2.3");
        var checksums = new Checksums("abc123");
        var dependency = new Dependency.RemoteDependency(packageUri, checksums);
        var dependencies = new Dictionary<string, Dependency.RemoteDependency>
        {
            ["birds"] = dependency
        };
        var metadata = new DependencyMetadata(
            "catalog", packageUri, DripSharp.Brine.Version.Parse("1.2.3"),
            new Uri("https://example.test/birds@1.2.3.zip"), checksums, dependencies,
            null, null, null, null, null, new List<string> { "Pkl" }, null, "birds",
            new List<PObject>());
        using var output = new MemoryStream();
        metadata.WriteTo(output);
        PackageAssetUri asset = packageUri.ToPackageAssetUri("/catalog/Bird.pkl")
            .Resolve("../Ostrich.pkl");
        string invalid = ExceptionName(() => _ = new PackageUri("https://example.test/birds@1.2.3"));
        bool byteFacade = typeof(PackageResolver).GetMethod("GetAssetBytes")?.ReturnType == typeof(byte[]);
        return $"uri={packageUri.Uri}:{packageUri.Version}:{packageUri.DisplayName}" +
            $"|asset={asset.PackageUri.Version}:{asset.AssetPath}" +
            $"|metadata={metadata.Name}:{metadata.PackageUri}:{metadata.PackageArchiveChecksums.Sha256}:" +
            $"{metadata.Dependencies.Count}:{metadata.Authors?.Count}:" +
            $"{Lower(Encoding.UTF8.GetString(output.ToArray()).Contains("packageUri", StringComparison.Ordinal))}" +
            $"|bytes={Lower(byteFacade)}|invalid={Lower(invalid != "none")}";
    }

    static string ObserveProjectAndSettingsApi(string fixtures)
    {
        Project project = Project.LoadFromPath(Path.Combine(fixtures, "project", "PklProject"));
        PklEvaluatorSettings settings = project.ResolvedEvaluatorConfiguration;
        var proxy = new PklEvaluatorSettings.Proxy(
            new Uri("http://localhost:8080"), new List<string> { "localhost" });
        var http = new PklEvaluatorSettings.Http(
            proxy,
            new Dictionary<Uri, Uri>
            {
                [new Uri("https://source.test/")] = new Uri("https://target.test/")
            },
            null);
        var external = new PklEvaluatorSettings.ExternalReader(
            "reader", new List<string> { "one", "two" }, "work");
        PklSettings user = PklSettings.Load(ModuleSource.FromPath(
            Path.Combine(fixtures, "project", "settings.pkl")));
        string invalid = ExceptionName(() => Project.Load(ModuleSource.FromText("value = 1\n")));
        return $"project={project.ProjectFileUri.Scheme}:{Lower(Path.IsPathFullyQualified(project.ProjectDirectory))}:" +
            $"{project.DeclaredDependencies.RemoteDependenciesReadOnly.Count}:" +
            $"{project.LocalProjectDependencies.Count}:{project.Tests.Count}" +
            $"|settings={settings.Environment?.Count}:{settings.ModulePaths?.Count}:" +
            $"{Lower(settings.HttpSettings is not null)}:{settings.ExternalModuleReadersReadOnly?.Count}" +
            $"|http={http.RewritesReadOnly?.Count}:{proxy.NoProxyReadOnly?.Count}:" +
            $"{external.ArgumentsReadOnly?.Count}|user={Lower(user.EditorSettings.UrlScheme == user.GetEditor().UrlScheme)}:" +
            $"{Lower(user.HttpSettings is not null)}|invalid={Lower(invalid != "none")}";
    }

    static string ObserveSecurityAndExternalApi()
    {
        SecurityManagers.StandardBuilder builder = SecurityManagers.CreateStandardBuilder()
            .AddAllowedModule(new Regex("^repl:"))
            .AddAllowedResource(new Regex("^env:"))
            .SetRootDir(null);
        SecurityManager manager = builder.Build();
        manager.CheckResolveModule(new Uri("repl:module"));
        string denied = ExceptionName(() => manager.CheckResolveModule(new Uri("file:///denied.pkl")));
        string empty = ExceptionName(() => SecurityManagers.CreateStandardBuilder().Build());
        var specification = new PklEvaluatorSettings.ExternalReader(
            "/definitely/missing/pkl-reader", new List<string>(), null);
        bool processFailure;
        using (ExternalReaderProcess process = ExternalReaderProcess.Start(specification))
        {
            processFailure = ExceptionName(() => process.GetModuleReaderSpec("missing")) != "none";
        }
        return $"defaults={SecurityManagers.DefaultAllowedModules.Count}:" +
            $"{SecurityManagers.DefaultAllowedResources.Count}:{Lower(SecurityManagers.DefaultManager is not null)}" +
            $"|builder={builder.AllowedModules.Count}:{builder.AllowedResources.Count}:" +
            $"{Lower(builder.RootDirectory is null)}|denied={Lower(denied == nameof(SecurityManagerException))}" +
            $"|empty={Lower(empty == nameof(InvalidOperationException))}|external={Lower(processFailure)}";
    }

    static string ObserveIdiomaticLoadingApiShapes()
    {
        bool readOnly = ExceptionName(() =>
            ((IList<Regex>)SecurityManagers.DefaultAllowedModules).Add(new Regex("never"))) ==
            nameof(NotSupportedException);
        bool nullable = ModuleKeyFactories.HttpFactory.TryCreate(new Uri("file:///not-http.pkl")) is null;
        bool disposables = typeof(IDisposable).IsAssignableFrom(typeof(ModuleKeyFactory)) &&
            typeof(IDisposable).IsAssignableFrom(typeof(ResourceReader)) &&
            typeof(IDisposable).IsAssignableFrom(typeof(PklHttpClient)) &&
            typeof(IDisposable).IsAssignableFrom(typeof(PackageResolver)) &&
            typeof(IDisposable).IsAssignableFrom(typeof(ExternalReaderProcess));
        return $"byte={Lower(typeof(Resource).GetProperty("Bytes")?.PropertyType == typeof(byte[]))}" +
            $"|uri={Lower(typeof(ModuleKey).GetProperty("Uri")?.PropertyType == typeof(Uri))}" +
            $"|stream={Lower(typeof(PklHttpClient).GetMethod("OpenRead")?.ReturnType == typeof(Stream))}" +
            $"|collection={Lower(readOnly)}|nullable={Lower(nullable)}" +
            $"|exceptions={Lower(typeof(Exception).IsAssignableFrom(typeof(HttpClientException)) && typeof(Exception).IsAssignableFrom(typeof(PackageLoadError)))}" +
            $"|disposable={Lower(disposables)}";
    }

    static string ObserveAnalyzerAndImportGraph(string work)
    {
        string source = Path.Combine(work, "analyzer-main.pkl");
        File.WriteAllText(source,
            "amends \"pkl:base\"\nimport \"pkl:json\"\nvalue = import(\"pkl:xml\")\n",
            new UTF8Encoding(false));
        var analyzer = new Analyzer(
            StackFrameTransformers.DefaultTransformer,
            false,
            SecurityManagers.DefaultManager,
            new List<ModuleKeyFactory> { ModuleKeyFactories.file, ModuleKeyFactories.standardLibrary },
            null,
            null,
            PklHttpClient.DummyClient(),
            TraceMode.COMPACT);
        ImportGraph analyzed = analyzer.ImportGraph(new Uri(source));
        string analyzedImports = string.Join(",", analyzed.Imports[new Uri(source)]
            .Select(item => item.Uri.OriginalString).OrderBy(value => value, StringComparer.Ordinal));

        string json = "{\"imports\":{" +
            "\"pkl:z\":[{\"uri\":\"pkl:xml\"},{\"uri\":\"pkl:base\"},{\"uri\":\"pkl:base\"}]," +
            "\"pkl:a\":[]}," +
            "\"resolvedImports\":{\"pkl:z\":\"file:/tmp/z.pkl\",\"pkl:a\":\"pkl:a\"}}";
        ImportGraph parsed = ImportGraph.ParseFromJson(json);
        ImportGraph equal = ImportGraph.ParseFromJson(json);
        string keys = string.Join(",", parsed.Imports.Keys.Select(uri => uri.OriginalString));
        string imports = string.Join(",", parsed.Imports[new Uri("pkl:z")]
            .Select(item => item.Uri.OriginalString));
        string invalidJson = ExceptionName(() => ImportGraph.ParseFromJson("{"));
        string invalidShape = ExceptionName(() => ImportGraph.ParseFromJson(
            "{\"imports\":{\"pkl:a\":1},\"resolvedImports\":{}}"));
        string invalidUri = ExceptionName(() => ImportGraph.ParseFromJson(
            "{\"imports\":{\"http://[\":[]},\"resolvedImports\":{}}"));
        return $"analyzed={analyzed.Imports.Count}:{analyzedImports}" +
            $"|parsed={keys}:{imports}:{parsed.ResolvedImports.Count}" +
            $"|equality={Lower(parsed.Equals(equal) && parsed.GetHashCode() == equal.GetHashCode())}" +
            $"|invalid={invalidJson}:{invalidShape}:{invalidUri}";
    }

    static string ObserveLogging()
    {
        var frame = new StackFrame("file:/module.pkl", "local#member",
            new List<string> { "value = 1" }, 1, 1, 1, 9);
        var sink = new StringWriter(CultureInfo.InvariantCulture);
        Logger logger = Loggers.Writer(sink);
        var buffered = new BufferedLogger(logger);
        buffered.Trace("trace", frame);
        buffered.Warn("warn\n", frame);
        string bufferedText = Escape(buffered.GetLogs());
        string writtenText = Escape(sink.ToString());
        buffered.Clear();
        Loggers.Noop().Trace("discarded", frame);
        var streamSink = new MemoryStream();
        Loggers.Stream(streamSink).Trace("stream", frame);
        string streamText = Escape(Encoding.UTF8.GetString(streamSink.ToArray()));
        var stderrSink = new StringWriter(CultureInfo.InvariantCulture);
        TextWriter originalError = Console.Error;
        try
        {
            Console.SetError(stderrSink);
            Loggers.StdErr().Warn("stderr", frame);
        }
        finally
        {
            Console.SetError(originalError);
        }
        string stderrText = Escape(stderrSink.ToString());
        return $"buffered={bufferedText}|cleared={Lower(buffered.GetLogs().Length == 0)}" +
            $"|writer={writtenText}|stream={streamText}|stderr={stderrText}" +
            $"|factories={Lower(Loggers.Noop() is not null && Loggers.StdErr() is not null)}";
    }

    static string ObserveStackTransforms(string fixtures)
    {
        var frame = new StackFrame("file:///tmp/project/main.pkl", "local#member",
            new List<string> { "value = 1" }, 2, 3, 4, 5);
        var same = new StackFrame("file:///tmp/project/main.pkl", "local#member",
            new List<string> { "value = 1" }, 2, 3, 4, 5);
        StackFrameTransformer composed =
            ((StackFrameTransformer)(value => value.WithModuleUri(value.GetModuleUri() + "|first")))
            .AndThen(value => value.WithModuleUri(value.GetModuleUri() + "|second"));
        string format = "editor://open?url=%{url}&path=%{path}&line=%{line}" +
            "&end=%{endLine}&column=%{column}&endColumn=%{endColumn}";
        StackFrame converted = StackFrameTransformers.ConvertFilePathToUriScheme(format)(frame);
        StackFrame relative = StackFrameTransformers.RelativizeModuleUri(
            new Uri("file:///tmp/project/"))(frame);
        StackFrame stdlib = StackFrameTransformers.ConvertStdLibUrlToExternalUrl(
            new StackFrame("pkl:base", null, new List<string>(), 7, 1, 7, 2));
        PklSettings settings = PklSettings.Load(ModuleSource.PathFromPath(
            Path.Combine(fixtures, "project", "settings.pkl")));
        StackFrame configured = StackFrameTransformers.CreateDefault(settings)(frame);
        return $"identity={Lower(ReferenceEquals(StackFrameTransformers.Empty(frame), frame))}:" +
            $"{Lower(ReferenceEquals(StackFrameTransformers.FromServiceProviders(frame), frame))}" +
            $"|composition={composed(frame).GetModuleUri()}" +
            $"|file={converted.GetModuleUri()}|relative={relative.GetModuleUri()}" +
            $"|stdlib={Lower(stdlib.GetModuleUri().Contains("stdlib/base.pkl#L7", StringComparison.Ordinal))}" +
            $"|configured={Lower(configured.GetModuleUri() != frame.GetModuleUri())}" +
            $"|equality={Lower(frame.Equals(same) && frame.GetHashCode() == same.GetHashCode() && !frame.Equals(frame.WithModuleUri("other:")))}";
    }

    static string ObserveExceptions()
    {
        var cause = new ArgumentException("cause");
        var pkl = new PklException("message", cause);
        var causeOnly = new PklException(cause);
        var bug = new PklBugException(cause);
        var missing = new NoSuchPropertyException("missing", "bird");
        var renderer = new RendererException("render");
        var security = new SecurityManagerException("denied");
        return $"types={pkl.GetType().Name}:{bug.GetType().Name}:{missing.GetType().Name}:" +
            $"{renderer.GetType().Name}:{security.GetType().Name}" +
            $"|metadata={pkl.Message}:{Lower(ReferenceEquals(pkl.InnerException, cause))}:" +
            $"{Lower(causeOnly.Message.Contains("cause", StringComparison.Ordinal))}:" +
            $"{Lower(bug.Message.StartsWith("An unexpected error", StringComparison.Ordinal))}:" +
            $"{missing.GetPropertyName()}:{PklBugException.UnreachableCode().Message}";
    }

    static string ObservePlatformAndRelease()
    {
        Platform platform = Platform.Current();
        var platformCopy = new Platform(platform.LanguageValue, platform.RuntimeValue,
            platform.VirtualMachineValue, platform.OperatingSystemValue, platform.ProcessorValue);
        Release release = Release.Current();
        var releaseCopy = new Release(release.Version, release.Os, release.Flavor,
            release.VersionInfo, release.CommitId, release.SourceCodeValue,
            release.DocumentationValue, release.StandardLibraryValue);
        return $"identity={Lower(ReferenceEquals(platform, Platform.Current()))}:" +
            $"{Lower(ReferenceEquals(release, Release.Current()))}" +
            $"|equality={Lower(platform.Equals(platformCopy) && platform.GetHashCode() == platformCopy.GetHashCode())}:" +
            $"{Lower(release.Equals(releaseCopy) && release.GetHashCode() == releaseCopy.GetHashCode())}" +
            $"|metadata={Lower(!string.IsNullOrWhiteSpace(platform.LanguageValue.Version))}:" +
            $"{Lower(!string.IsNullOrWhiteSpace(platform.RuntimeValue.Name))}:" +
            $"{Lower(!string.IsNullOrWhiteSpace(platform.OperatingSystemValue.Name))}:" +
            $"{Lower(release.VersionInfo.StartsWith("Pkl ", StringComparison.Ordinal))}:" +
            $"{Lower(release.StandardLibraryValue.Modules.Count > 0)}:" +
            $"{Lower(release.SourceCodeValue.SourceCodeUrlScheme().Contains("%{path}", StringComparison.Ordinal))}";
    }

    static string ExceptionName(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (Exception exception)
        {
            return exception.GetType().Name;
        }
    }

    static void VerifyPackedAssemblies(string manifest)
    {
        string baseDirectory = Path.GetFullPath(AppContext.BaseDirectory)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        string[] lines = File.ReadAllLines(manifest);
        Require(lines.Length == 2, "packed runtime assembly manifest must contain parser and core");
        foreach (string line in lines)
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == 2 && fields[1].Length == 64,
                "packed runtime assembly manifest row");
            Assembly assembly = Assembly.Load(new AssemblyName(fields[0]));
            string location = Path.GetFullPath(assembly.Location);
            Require(location.StartsWith(baseDirectory, StringComparison.Ordinal),
                $"runtime assembly escaped the isolated consumer output: {location}");
            using FileStream stream = File.OpenRead(location);
            string actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            Require(actual == fields[1],
                $"loaded {fields[0]} does not match its exact packed assembly");
        }
    }

    static string ObserveModuleSourceForms(string work)
    {
        string path = Path.Combine(work, "module-source.pkl");
        File.WriteAllText(path, "value = 1\n", new UTF8Encoding(false));
        ModuleSource text = ModuleSource.Text("value = 1");
        ModuleSource fromPath = ModuleSource.PathFromPath(path);
        ModuleSource fromFile = ModuleSource.FileFromFile(path);
        ModuleSource uri = ModuleSource.Uri("https://example.test/main.pkl");
        ModuleSource modulePath = ModuleSource.ModulePath("lib/main.pkl");
        return $"text={text.GetUri()}:{text.GetContents()}" +
            $"|path={fromPath.GetUri().Scheme}:{Lower(fromPath.GetContents() is null)}" +
            $"|file={fromFile.GetUri().Scheme}:{Lower(fromFile.GetContents() is null)}" +
            $"|uri={uri.GetUri()}:{Lower(uri.GetContents() is null)}" +
            $"|modulepath={modulePath.GetUri()}:{Lower(modulePath.GetContents() is null)}";
    }

    static string ObserveLocal(string fixtures)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(ModuleSource.PathFromPath(
            Path.Combine(fixtures, "local", "main.pkl")));
        return $"imported={module.GetProperty("imported")}" +
            $"|resource={Escape((string)module.GetProperty("resource"))}";
    }

    static string ObserveLocalGlob(string fixtures)
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(ModuleSource.PathFromPath(
            Path.Combine(fixtures, "local", "main.pkl")));
        return $"modules={Sorted(module.GetProperty("moduleKeys"))}" +
            $"|resources={Sorted(module.GetProperty("resourceKeys"))}";
    }

    static string ObserveModulePath(string fixtures, string work)
    {
        string source = Path.Combine(fixtures, "modulepath");
        string directoryRoot = Path.Combine(work, "modulepath-directory");
        CopyTree(Path.Combine(source, "directory"), Path.Combine(directoryRoot, "directory"));
        string archive = Path.Combine(work, "modulepath-contract.zip");
        ZipTree(Path.Combine(source, "archive"), archive, "archive");

        using var resolver = new ModulePathResolver(new[] { directoryRoot, archive });
        using ModuleKeyFactory factory = ModuleKeyFactories.CreateModulePath(resolver);
        using ResourceReader reader = ResourceReaders.ModulePath(resolver);
        ModuleKey directory = factory.Create(new Uri("modulepath:/directory/module.pkl"))
            ?? throw new InvalidOperationException("directory module path was not recognized");
        ModuleKey zipped = factory.Create(new Uri("modulepath:/archive/module.pkl"))
            ?? throw new InvalidOperationException("archive module path was not recognized");
        ResolvedModuleKey resolvedDirectory = directory.Resolve(SecurityManagers.DefaultManager);
        ResolvedModuleKey resolvedZip = zipped.Resolve(SecurityManagers.DefaultManager);
        var directoryResource = reader.Read(new Uri("modulepath:/directory/resource.txt")) as Resource
            ?? throw new InvalidOperationException("directory resource path was not recognized");
        var zipResource = reader.Read(new Uri("modulepath:/archive/resource.txt")) as Resource
            ?? throw new InvalidOperationException("archive resource path was not recognized");
        Require(resolvedDirectory.GetUri().IsFile, "directory module path must resolve to a file");
        Require(resolvedZip.GetUri().IsFile || resolvedZip.GetUri().Scheme == "jar",
            "archive module path must resolve to an extracted file or archive URI");
        // The .NET adapter owns a private extracted tree; normalize its file URI
        // to the shared archive identity after verifying the resolved content.
        return $"directory=file:{Compact(resolvedDirectory.LoadSource())}" +
            $":{Compact(directoryResource.GetText())}" +
            $"|archive=jar:{Compact(resolvedZip.LoadSource())}:{Compact(zipResource.GetText())}";
    }

    static string ObserveStandardLibrary()
    {
        using Evaluator evaluator = Evaluator.Preconfigured();
        object result = evaluator.EvaluateExpression(
            ModuleSource.Text(""), "import(\"pkl:math\").gcd(54, 24)");
        return $"gcd={result}";
    }

    static string ObserveCustomReaders()
    {
        var factory = new CountingModuleFactory();
        var reader = new CountingResourceReader();
        using (Evaluator evaluator = EvaluatorBuilder.Unconfigured()
            .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
            .SetAllowedModules(new List<Regex> { new("custom:"), new("pkl:") })
            .SetAllowedResources(new List<Regex> { new("contractres:") })
            .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .AddModuleKeyFactory(factory)
            .AddResourceReader(reader)
            .Build())
        {
            PModule module = evaluator.Evaluate(ModuleSource.Create(
                new Uri("custom:main"),
                "dependency = import(\"custom:dependency\").value\n" +
                "resource = read(\"contractres:item\")\n"));
            Require((long)module.GetProperty("dependency") == 42, "custom module result");
            Require((string)module.GetProperty("resource") == "resource-value", "custom resource result");
        }
        Require(factory.Closes == 0 && reader.Closes == 0,
            "evaluators must not dispose caller-owned readers");
        return $"dependency=42|resource=resource-value|creates={factory.Creates}" +
            $"|factory-closes={factory.Closes}|reader-closes={reader.Closes}";
    }

    static string ObserveEnvironmentAndProperties()
    {
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetEnvironmentVariables(new Dictionary<string, string>
                { ["CONTRACT_ENV"] = "environment-value" })
            .SetExternalProperties(new Dictionary<string, string>
                { ["contract.property"] = "property-value" })
            .Build();
        PModule module = evaluator.Evaluate(ModuleSource.Text(
            "environment = read(\"env:CONTRACT_ENV\")\n" +
            "property = read(\"prop:contract.property\")\n"));
        return $"environment={module.GetProperty("environment")}" +
            $"|property={module.GetProperty("property")}";
    }

    static string ObserveEvaluatorBuilder(string work)
    {
        var factory = new CountingModuleFactory();
        var reader = new CountingResourceReader();
        string cache = Path.Combine(work, "builder-cache");
        EvaluatorBuilder builder = EvaluatorBuilder.Unconfigured()
            .SetColor(true)
            .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
            .SetAllowedModules(new List<Regex> { new("file:") })
            .SetAllowedResources(new List<Regex> { new("env:") })
            .SetRootDir(work)
            .SetLogger(Loggers.Noop())
            .SetHttpClient(PklHttpClient.DummyClient())
            .SetModuleKeyFactories(new List<ModuleKeyFactory> { factory })
            .SetResourceReaders(new List<ResourceReader> { reader })
            .SetEnvironmentVariables(new Dictionary<string, string> { ["A"] = "1" })
            .SetExternalProperties(new Dictionary<string, string> { ["B"] = "2" })
            .SetTimeout(TimeSpan.FromSeconds(2))
            .SetModuleCacheDir(cache)
            .SetOutputFormat("json")
            .SetTraceMode(TraceMode.PRETTY)
            .SetPowerAssertionsEnabled(true);
        bool conflict;
        try
        {
            builder.SetSecurityManager(SecurityManagers.DefaultManager)
                .SetAllowedModules(Array.Empty<Regex>());
            conflict = false;
        }
        catch (InvalidOperationException)
        {
            conflict = true;
        }
        finally
        {
            builder.UnsetSecurityManager();
        }
        return $"color={Lower(builder.GetColor())}" +
            $"|stack={Lower(builder.GetStackFrameTransformer() is not null)}" +
            $"|allowed={builder.GetAllowedModules().Count}:{builder.GetAllowedResources().Count}" +
            $"|root={Lower(Path.GetFullPath(builder.GetRootDir()!) == Path.GetFullPath(work))}" +
            $"|logger={Lower(builder.GetLogger() is not null)}" +
            $"|http={Lower(builder.GetHttpClient() is not null)}" +
            $"|readers={builder.GetModuleKeyFactories().Count}:{builder.GetResourceReaders().Count}" +
            $"|values={builder.GetEnvironmentVariables()["A"]}:{builder.GetExternalProperties()["B"]}" +
            $"|timeout={builder.GetTimeout()!.Value.TotalSeconds:0}" +
            $"|cache={Lower(Path.GetFullPath(builder.GetModuleCacheDir()!) == Path.GetFullPath(cache))}" +
            $"|format={builder.GetOutputFormat()}" +
            $"|trace={builder.GetTraceMode().ToString()!.ToLowerInvariant()}" +
            $"|power={Lower(builder.GetPowerAssertionsEnabled())}" +
            $"|conflict={Lower(conflict)}";
    }

    static string ObserveSecurityPolicy(string work)
    {
        string root = Path.Combine(work, "security-root");
        string allowed = Path.Combine(root, "allowed.pkl");
        string outside = Path.Combine(work, "outside.pkl");
        Directory.CreateDirectory(root);
        File.WriteAllText(allowed, "value = 1\n");
        File.WriteAllText(outside, "value = 2\n");
        SecurityManager manager = SecurityManagers.CreateStandard(
            new List<Regex> { new("file:"), new("pkl:") },
            new List<Regex> { new("file:"), new("env:") },
            SecurityManagers.DefaultTrustLevels,
            root);
        Uri allowedUri = new(Path.GetFullPath(allowed));
        Uri outsideUri = new(Path.GetFullPath(outside));
        manager.CheckResolveModule(allowedUri);
        manager.CheckReadResource(allowedUri);
        bool moduleDenied = ThrowsSecurity(() => manager.CheckResolveModule(
            new Uri("https://example.test/main.pkl")));
        bool resourceDenied = ThrowsSecurity(() => manager.CheckReadResource(new Uri("prop:secret")));
        bool trustDenied = ThrowsSecurity(() => manager.CheckImportModule(
            new Uri("https://example.test/main.pkl"), allowedUri));
        bool rootDenied = ThrowsSecurity(() => manager.CheckResolveModule(outsideUri));
        bool missingRootDenied = ThrowsSecurity(() => manager.CheckResolveModule(
            new Uri(Path.Combine(work, "missing-outside.pkl"))));
        string escapeRoot = Path.Combine(work, "security-root-escape");
        Directory.CreateDirectory(escapeRoot);
        string escapeFile = Path.Combine(escapeRoot, "escape.pkl");
        File.WriteAllText(escapeFile, "value = 3\n");
        bool prefixRootDenied = ThrowsSecurity(() => manager.CheckResolveModule(new Uri(escapeFile)));
        string symlinkTarget = Path.Combine(work, "security-link-target");
        Directory.CreateDirectory(symlinkTarget);
        string symlinkTargetFile = Path.Combine(symlinkTarget, "target.pkl");
        File.WriteAllText(symlinkTargetFile, "value = 4\n");
        string symlink = Path.Combine(root, "link");
        Directory.CreateSymbolicLink(symlink, symlinkTarget);
        bool symlinkDenied = ThrowsSecurity(() => manager.CheckResolveModule(
            new Uri(Path.Combine(symlink, "target.pkl"))));
        bool encodedTraversal = ThrowsUri(() => _ = new PackageUri(
            "package://attacker.test/%2e%2e/legit@1.2.3"));
        bool literalTraversal = ThrowsUri(() => _ = new PackageUri(
            "package://attacker.test/../legit@1.2.3"));
        var deniedReader = new CountingResourceReader();
        bool readDenied;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetAllowedModules(new List<Regex> { new("repl:"), new("pkl:") })
                .SetAllowedResources(new List<Regex>())
                .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
                .AddResourceReader(deniedReader)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text("value = read(\"contractres:item\")\n"));
            readDenied = false;
        }
        catch (PklException)
        {
            readDenied = deniedReader.Reads == 0;
        }
        bool trustOrdered;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetSecurityManager(SecurityManagers.CreateStandard(
                    new List<Regex> { new("custom:"), new("file:"), new("pkl:") },
                    new List<Regex>(),
                    uri => uri.Scheme == "custom" ? 0 : 10,
                    null))
                .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
                .AddModuleKeyFactory(new CountingModuleFactory())
                .AddModuleKeyFactory(ModuleKeyFactories.file)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Create(new Uri("custom:trust-main"),
                $"value = import(\"{outsideUri}\").value\n"));
            trustOrdered = false;
        }
        catch (PklException error)
        {
            trustOrdered = error.Message.Contains("trust", StringComparison.OrdinalIgnoreCase);
        }
        deniedReader.Dispose();
        Require(missingRootDenied && prefixRootDenied && symlinkDenied && readDenied && trustOrdered,
            "canonical root, resolve/read, and import-trust ordering policy");
        return "allowed=true" +
            $"|module-denied={Lower(moduleDenied)}" +
            $"|resource-denied={Lower(resourceDenied)}" +
            $"|trust-denied={Lower(trustDenied)}" +
            $"|root-denied={Lower(rootDenied)}" +
            $"|encoded-traversal={Lower(encodedTraversal)}" +
            $"|literal-traversal={Lower(literalTraversal)}";
    }

    static NetworkObservations ObserveNetworkAndPackages(string packageBuild, string work)
    {
        PrepareEncodedAssetPackage(packageBuild);
        using var server = new ContractHttpServer(packageBuild);
        var httpModules = new List<Regex>(SecurityManagers.DefaultAllowedModules) { new("http:") };
        var httpResources = new List<Regex>(SecurityManagers.DefaultAllowedResources) { new("http:") };
        PModule httpModule;
        using (PklHttpClient client = PklHttpClient.CreateBuilder()
            .AddHeaders("**", new Dictionary<string, IReadOnlyList<string>>
                { ["X-Contract"] = new List<string> { "enabled" } })
            .Build())
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetAllowedModules(httpModules)
            .SetAllowedResources(httpResources)
            .SetHttpClient(client)
            .Build())
        {
            httpModule = evaluator.Evaluate(ModuleSource.Uri(server.PlainUri("/plain-main.pkl")));
        }

        PModule proxied;
        using (PklHttpClient proxyClient = PklHttpClient.CreateBuilder()
            .SetProxy(server.ProxyUri, Array.Empty<string>())
            .AddHeaders("**", new Dictionary<string, IReadOnlyList<string>>
                { ["X-Contract"] = new List<string> { "enabled" } })
            .Build())
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetAllowedModules(httpModules)
            .SetAllowedResources(httpResources)
            .SetHttpClient(proxyClient)
            .Build())
        {
            proxied = evaluator.Evaluate(ModuleSource.Uri("http://origin.test/proxy-main.pkl"));
        }

        using PklHttpClient directClient = server.NewTlsClient()
            .AddRewrite(new Uri("https://origin.test/"), server.TlsUri("/"))
            .AddHeaders("**", new Dictionary<string, IReadOnlyList<string>>
                { ["X-Contract"] = new List<string> { "enabled" } })
            .Build();
        var checkedUris = new List<Uri>();
        using HttpResponseMessage redirectResponse = directClient.Send(
            new HttpRequestMessage(HttpMethod.Get, new Uri("https://origin.test/redirect.pkl")),
            checkedUris.Add);
        PModule httpsModule;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(directClient)
            .Build())
        {
            httpsModule = evaluator.Evaluate(ModuleSource.Uri("https://origin.test/main.pkl"));
        }
        string redirectBody = redirectResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        string http = $"http={httpModule.GetProperty("value")}:" +
            Escape((string)httpModule.GetProperty("payload")) +
            $"|https={httpsModule.GetProperty("value")}:" +
            Escape((string)httpsModule.GetProperty("payload")) +
            $"|redirect={Compact(redirectBody)}|checked={checkedUris.Count}" +
            $"|headers={Lower(server.AllRequestsHadContractHeader)}" +
            $"|proxy={proxied.GetProperty("value")}:{Lower(server.ProxyRequestCount > 0)}";

        var orderedHeaderRules = new Dictionary<string, IReadOnlyDictionary<string, IReadOnlyList<string>>>
        {
            ["**"] = new Dictionary<string, IReadOnlyList<string>>
            {
                ["X-Contract"] = new List<string> { "enabled" },
                ["X-Rule-Order"] = new List<string> { "first" }
            },
            ["**/main.pkl"] = new Dictionary<string, IReadOnlyList<string>>
            {
                ["X-Rule-Order"] = new List<string> { "second" }
            }
        };
        using (PklHttpClient orderedHeaderClient = server.NewTlsClient()
            .AddRewrite(new Uri("https://origin.test/"), server.TlsUri("/"))
            .SetHeaders(orderedHeaderRules)
            .Build())
        {
            using HttpResponseMessage _ = orderedHeaderClient.Send(
                new HttpRequestMessage(HttpMethod.Get, new Uri("https://origin.test/main.pkl")),
                _ => { });
        }
        string collections = ObserveMapEntrySet(server.HeaderRuleOrder);

        string cache = Path.Combine(work, "package-cache");
        string packageSource =
            "bird = import(\"package://localhost:0/birds@0.5.0#/catalog/Swallow.pkl\").name\n" +
            "modules = import*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n" +
            "resources = read*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n";
        int beforePackages = server.RequestCount;
        PModule first;
        using (PklHttpClient packageClient = server.NewTlsClient().Build())
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(packageClient)
            .SetModuleCacheDir(cache)
            .Build())
        {
            first = evaluator.Evaluate(ModuleSource.Text(packageSource));
        }
        int downloads = server.RequestCount - beforePackages;

        string metadataPath = Path.Combine(
            packageBuild, "test-packages", "birds@0.5.0", "birds@0.5.0.json");
        string metadataShaPath = metadataPath + ".sha256";
        DependencyMetadata metadata = DependencyMetadata.Parse(File.ReadAllText(metadataPath));
        string metadataSha = File.ReadAllText(metadataShaPath).Trim();
        PackageAssetUri normalizedAsset = new PackageAssetUri(
            new Uri("package://localhost:0/birds@0.5.0#/foo/../Bird.pkl"))
            .Resolve("./catalog.pkl");

        bool checksumFailure;
        try
        {
            using PklHttpClient checksumClient = server.NewTlsClient().Build();
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetHttpClient(checksumClient)
                .SetModuleCacheDir(Path.Combine(work, "invalid-checksum-cache"))
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text(
                "bad = import(\"package://localhost:0/birds@0.5.0::sha256:" +
                "0000000000000000000000000000000000000000000000000000000000000000#/Bird.pkl\")\n"));
            checksumFailure = false;
        }
        catch (PklException error)
        {
            checksumFailure = error.Message.Contains("checksum", StringComparison.OrdinalIgnoreCase);
        }

        int beforeOffline = server.RequestCount;
        PModule offline;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(PklHttpClient.DummyClient())
            .SetModuleCacheDir(cache)
            .Build())
        {
            offline = evaluator.Evaluate(ModuleSource.Text(packageSource));
        }
        bool offlineNoNetwork = server.RequestCount == beforeOffline;
        string packages = $"bird={first.GetProperty("bird")}" +
            $"|modules={Sorted(first.GetProperty("modules"))}" +
            $"|resources={Sorted(first.GetProperty("resources"))}" +
            $"|offline={offline.GetProperty("bird")}:{Lower(offlineNoNetwork)}" +
            $"|downloads={Lower(downloads > 0)}" +
            $"|metadata={metadata.GetName()}:{metadata.GetDependencies().Count}" +
            $"|metadata-sha={Lower(metadataSha.Length == 64)}" +
            $"|asset={normalizedAsset.GetAssetPath()}" +
            $"|checksum-failure={Lower(checksumFailure)}";

        string uriComponents = ObserveUriComponentsAndEncodedAssets(server, work);
        string projectPackage = ObserveProjectPackage(packageBuild, work, cache);
        string errors = ObserveNetworkErrors(server, work, checksumFailure);
        return new NetworkObservations(
            http, packages, uriComponents, collections, projectPackage, errors);
    }

    static string ObserveMapEntrySet(string headerRuleOrder)
    {
        var lower = new Uri("https://example.test/a%2fb");
        var upper = new Uri("https://example.test/a%2Fb");
        var secondUri = new Uri("https://example.test/second");
        var thirdUri = new Uri("https://example.test/third");
        var source = new Dictionary<Uri, string>
        {
            [lower] = "one",
            [secondUri] = "two"
        };
        object view = InvokeJavaCompatGenericExact(
            "MapEntrySet",
            new[] { typeof(Uri), typeof(string) },
            new[] { typeof(IDictionary<Uri, string>) },
            source);
        source[thirdUri] = "three";
        bool live = (int)view.GetType().GetProperty("Count")!.GetValue(view)! == 3;

        IEnumerator iterator = ((IEnumerable)view).GetEnumerator();
        Type entryType = typeof(Evaluator).Assembly
            .GetType("DripSharp.Runtime.JavaMapEntry`2", throwOnError: true)!
            .MakeGenericType(typeof(Uri), typeof(string));
        object first = InvokeJavaCompatGeneric("IteratorNext", new[] { entryType }, iterator);
        string previous = (string)entryType.GetMethod("SetValue")!
            .Invoke(first, new object[] { "updated" })!;
        object second = InvokeJavaCompatGeneric("IteratorNext", new[] { entryType }, iterator);
        InvokeJavaCompat<object?>("IteratorRemove", iterator);
        bool removed = !source.ContainsKey(secondUri) &&
            Equals(entryType.GetProperty("Key")!.GetValue(second), secondUri);

        object detached = InvokeJavaCompatGeneric(
            "MapEntry", new[] { typeof(Uri), typeof(string) }, upper, "updated");
        bool equal = first.Equals(detached);
        bool entryHash = InvokeJavaCompat<int>("HashCode", first) ==
            InvokeJavaCompat<int>("HashCode", detached);
        var entries = ((IEnumerable)view).Cast<object>().ToList();
        int expectedSetHash = 0;
        foreach (object entry in entries)
            expectedSetHash = unchecked(expectedSetHash + InvokeJavaCompat<int>("HashCode", entry));
        bool setHash = InvokeJavaCompat<int>("HashCode", view) == expectedSetHash;
        string order = "[" + string.Join(", ", entries.Select(entry =>
            ((Uri)entryType.GetProperty("Key")!.GetValue(entry)!).OriginalString)) + "]";
        string current = (string)entryType.GetProperty("Value")!.GetValue(first)!;
        Require(live && previous == "one" && current == "updated" && removed && equal &&
            entryHash && setHash && headerRuleOrder == "first,second",
            "live Java map entry-set and configured header rule order");
        return $"live={Lower(live)}|set={previous}:{current}|removed={Lower(removed)}" +
            $"|order={order}|equals={Lower(equal)}|hash={Lower(entryHash && setHash)}" +
            $"|headers={headerRuleOrder}";
    }

    static string ObserveUriComponentsAndEncodedAssets(ContractHttpServer server, string work)
    {
        var components = new Uri(
            "package://user%20name@example.test/pkg%20name@1.0.0" +
            "?q%20x=%E2%98%83%26z#/hello%20world/%E2%98%83%2Ffile.pkl");
        var opaque = new Uri("env:snow%20man%2F%E2%98%83?literal%2Fquery#frag%2Fpart");
        var percentLower = new Uri("https://example.test/a%2fb?q%2fx#f%2fx");
        var percentUpper = new Uri("https://example.test/a%2Fb?q%2Fx#f%2Fx");
        var literalReserved = new Uri("https://example.test/a/b?q/x#f/x");
        var opaqueOther = new Uri("env:other");

        string cache = Path.Combine(work, "encoded-asset-cache");
        string source =
            "space = import(\"package://localhost:0/encoded-assets@1.0.0#/hello%20world.pkl\").name\n" +
            "unicode = import(\"package://localhost:0/encoded-assets@1.0.0#/%E9%9B%AA.pkl\").name\n" +
            "reserved = import(\"package://localhost:0/encoded-assets@1.0.0#/reserved%2Fslash.pkl\").name\n" +
            "punctuation = import(\"package://localhost:0/encoded-assets@1.0.0#/hash%23query%3F.pkl\").name\n" +
            "modules = import*(\"package://localhost:0/encoded-assets@1.0.0#/*.pkl\").keys\n";
        int beforeOnline = server.RequestCount;
        PModule online;
        using (PklHttpClient client = server.NewTlsClient().Build())
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(client)
            .SetModuleCacheDir(cache)
            .Build())
        {
            online = evaluator.Evaluate(ModuleSource.Text(source));
        }
        int onlineRequests = server.RequestCount - beforeOnline;
        int beforeOffline = server.RequestCount;
        PModule offline;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(PklHttpClient.DummyClient())
            .SetModuleCacheDir(cache)
            .Build())
        {
            offline = evaluator.Evaluate(ModuleSource.Text(source));
        }
        bool offlineNoNetwork = server.RequestCount == beforeOffline;

        string environment;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .AddEnvironmentVariable("snow man/☃?literal/query", "decoded-environment")
            .Build())
        {
            environment = (string)evaluator.Evaluate(ModuleSource.Text(
                "value = read(\"env:snow%20man%2F%E2%98%83%3Fliteral%2Fquery\")\n"))
                .GetProperty("value");
        }

        var decodedAsset = new PackageAssetUri(new Uri(
            "package://localhost:0/encoded-assets@1.0.0" +
            "#/hello%20world/%E9%9B%AA%2Fhash%23query%3F.pkl"));
        PackageAssetUri resolvedAsset = new PackageAssetUri(new Uri(
            "package://localhost:0/encoded-assets@1.0.0#/directory/base.pkl"))
            .Resolve("../next file/雪#?.pkl");

        bool percentCaseEqual = InvokeJavaCompat<bool>("Equals", percentLower, percentUpper);
        bool percentHashEqual =
            InvokeJavaCompat<int>("HashCode", percentLower) ==
            InvokeJavaCompat<int>("HashCode", percentUpper);
        bool escapedDistinct = !InvokeJavaCompat<bool>("Equals", percentUpper, literalReserved);
        bool opaqueDistinct = !InvokeJavaCompat<bool>("Equals", opaque, opaqueOther);

        return "ssp=" + UriComponent("UriSchemeSpecificPart", components)
            + "|raw-ssp=" + UriComponent("UriRawSchemeSpecificPart", components)
            + "|authority=" + UriComponent("UriAuthority", components)
            + "|raw-authority=" + UriComponent("UriRawAuthority", components)
            + "|user=" + UriComponent("UriUserInfo", components)
            + "|raw-user=" + UriComponent("UriRawUserInfo", components)
            + "|path=" + UriComponent("UriPath", components)
            + "|raw-path=" + UriComponent("UriRawPath", components)
            + "|query=" + UriComponent("UriQuery", components)
            + "|raw-query=" + UriComponent("UriRawQuery", components)
            + "|fragment=" + UriComponent("UriFragment", components)
            + "|raw-fragment=" + UriComponent("UriRawFragment", components)
            + "|opaque-ssp=" + UriComponent("UriSchemeSpecificPart", opaque)
            + "|opaque-raw-ssp=" + UriComponent("UriRawSchemeSpecificPart", opaque)
            + "|opaque-query=" + UriComponent("UriQuery", opaque)
            + "|opaque-raw-query=" + UriComponent("UriRawQuery", opaque)
            + "|percent-case=" + Lower(percentCaseEqual) + ":" + Lower(percentHashEqual)
            + "|escaped-distinct=" + Lower(escapedDistinct)
            + "|opaque-distinct=" + Lower(opaqueDistinct)
            + "|asset=" + decodedAsset.GetAssetPath()
            + "|resolved=" + resolvedAsset.GetAssetPath()
            + "|environment=" + environment
            + $"|loaded={online.GetProperty("space")}:{online.GetProperty("unicode")}" +
                $":{online.GetProperty("reserved")}:{online.GetProperty("punctuation")}" +
            "|listed=" + Sorted(online.GetProperty("modules"))
            + $"|offline={offline.GetProperty("space")}:{Lower(offlineNoNetwork)}"
            + "|downloaded=" + Lower(onlineRequests > 0);
    }

    static string UriComponent(string method, Uri uri) =>
        InvokeJavaCompat<string?>(method, uri) ?? "null";

    static TResult InvokeJavaCompat<TResult>(string method, params object?[] arguments)
    {
        Type compatibility = typeof(Evaluator).Assembly.GetType("DripSharp.Runtime.JavaCompat")
            ?? throw new InvalidOperationException("The packaged Java compatibility type is missing.");
        MethodInfo[] candidates = compatibility.GetMethods(
            BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.DeclaredOnly);
        MethodInfo selected = candidates.Single(candidate =>
            candidate.Name == method && candidate.GetParameters().Length == arguments.Length &&
            candidate.GetParameters().Select(parameter => parameter.ParameterType)
                .Zip(arguments, (type, argument) => argument is null || type.IsInstanceOfType(argument))
                .All(matches => matches));
        return (TResult)selected.Invoke(null, arguments)!;
    }

    static object InvokeJavaCompatGeneric(
        string method, Type[] typeArguments, params object?[] arguments)
    {
        Type compatibility = typeof(Evaluator).Assembly.GetType("DripSharp.Runtime.JavaCompat")
            ?? throw new InvalidOperationException("The packaged Java compatibility type is missing.");
        MethodInfo selected = compatibility.GetMethods(
                BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.DeclaredOnly)
            .Where(candidate => candidate.Name == method && candidate.IsGenericMethodDefinition &&
                candidate.GetGenericArguments().Length == typeArguments.Length &&
                candidate.GetParameters().Length == arguments.Length)
            .Select(candidate => candidate.MakeGenericMethod(typeArguments))
            .Single(candidate => candidate.GetParameters().Select(parameter => parameter.ParameterType)
                .Zip(arguments, (type, argument) => argument is null || type.IsInstanceOfType(argument))
                .All(matches => matches));
        return selected.Invoke(null, arguments)!;
    }

    static object InvokeJavaCompatGenericExact(
        string method,
        Type[] typeArguments,
        Type[] parameterTypes,
        params object?[] arguments)
    {
        Type compatibility = typeof(Evaluator).Assembly.GetType("DripSharp.Runtime.JavaCompat")
            ?? throw new InvalidOperationException("The packaged Java compatibility type is missing.");
        MethodInfo selected = compatibility.GetMethods(
                BindingFlags.Static | BindingFlags.NonPublic | BindingFlags.DeclaredOnly)
            .Where(candidate => candidate.Name == method && candidate.IsGenericMethodDefinition &&
                candidate.GetGenericArguments().Length == typeArguments.Length)
            .Select(candidate => candidate.MakeGenericMethod(typeArguments))
            .Single(candidate => candidate.GetParameters().Select(parameter => parameter.ParameterType)
                .SequenceEqual(parameterTypes));
        return selected.Invoke(null, arguments)!;
    }

    static void PrepareEncodedAssetPackage(string packageBuild)
    {
        const string identity = "encoded-assets@1.0.0";
        string packageDirectory = Path.Combine(packageBuild, "test-packages", identity);
        string source = Path.Combine(packageDirectory, "encoded-source");
        Directory.CreateDirectory(Path.Combine(source, "reserved"));
        File.WriteAllText(Path.Combine(source, "hello world.pkl"), "name = \"space\"\n");
        File.WriteAllText(Path.Combine(source, "雪.pkl"), "name = \"unicode\"\n");
        File.WriteAllText(Path.Combine(source, "reserved", "slash.pkl"), "name = \"reserved\"\n");
        File.WriteAllText(Path.Combine(source, "hash#query?.pkl"), "name = \"punctuation\"\n");
        string archive = Path.Combine(packageDirectory, identity + ".zip");
        ZipTree(source, archive, "");
        string checksum = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(archive)))
            .ToLowerInvariant();
        string metadata =
            "{\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"name\": \"encoded-assets\",\n" +
            "  \"packageUri\": \"package://localhost:0/encoded-assets@1.0.0\",\n" +
            "  \"packageZipUrl\": \"https://localhost:0/encoded-assets@1.0.0/encoded-assets@1.0.0.zip\",\n" +
            "  \"dependencies\": {},\n" +
            "  \"version\": \"1.0.0\",\n" +
            $"  \"packageZipChecksums\": {{\"sha256\": \"{checksum}\"}}\n" +
            "}\n";
        File.WriteAllText(Path.Combine(packageDirectory, identity + ".json"), metadata);
    }

    static string ObserveProjectPackage(string packageBuild, string work, string cache)
    {
        string projectDir = Path.Combine(work, "projectpackage");
        Directory.CreateDirectory(projectDir);
        string projectFile = Path.Combine(projectDir, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\n" +
            "dependencies { [\"birds\"] { uri = \"package://localhost:0/birds@0.5.0\" } }\n");
        string birdsSha = File.ReadAllText(Path.Combine(packageBuild, "test-packages",
            "birds@0.5.0", "birds@0.5.0.json.sha256")).Trim();
        string fruitSha = File.ReadAllText(Path.Combine(packageBuild, "test-packages",
            "fruit@1.0.5", "fruit@1.0.5.json.sha256")).Trim();
        File.WriteAllText(Path.Combine(projectDir, "PklProject.deps.json"),
            "{\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"resolvedDependencies\": {\n" +
            "    \"package://localhost:0/birds@0\": {\"type\": \"remote\", \"uri\": \"projectpackage://localhost:0/birds@0.5.0\", \"checksums\": {\"sha256\": \"" + birdsSha + "\"}},\n" +
            "    \"package://localhost:0/fruit@1\": {\"type\": \"remote\", \"uri\": \"projectpackage://localhost:0/fruit@1.0.5\", \"checksums\": {\"sha256\": \"" + fruitSha + "\"}}\n" +
            "  }\n" +
            "}\n");
        string main = Path.Combine(projectDir, "main.pkl");
        File.WriteAllText(main,
            "bird = import(\"@birds/catalog/Swallow.pkl\").name\n" +
            "resource = read(\"@birds/catalog/Ostrich.pkl\").text.contains(\"Ostrich\")\n");
        Project project = Project.LoadFromPath(projectFile);
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .ApplyFromProject(project)
            .SetModuleCacheDir(cache)
            .SetHttpClient(PklHttpClient.DummyClient())
            .Build();
        PModule module = evaluator.Evaluate(ModuleSource.PathFromPath(main));
        return $"dependencies={project.GetDependencies().RemoteDependencies.Count}" +
            $"|bird={module.GetProperty("bird")}" +
            $"|resource={Lower((bool)module.GetProperty("resource"))}";
    }

    static string ObserveNetworkErrors(ContractHttpServer server, string work, bool checksumFailure)
    {
        var httpModules = new List<Regex>(SecurityManagers.DefaultAllowedModules) { new("http:") };
        bool httpFailure;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetAllowedModules(httpModules)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Uri(server.PlainUri("/missing.pkl")));
            httpFailure = false;
        }
        catch (PklException error)
        {
            httpFailure = error.Message.Contains("404", StringComparison.OrdinalIgnoreCase) ||
                error.Message.Contains("status", StringComparison.OrdinalIgnoreCase);
        }

        bool missingAsset = ThrowsPkl(() =>
        {
            using PklHttpClient client = server.NewTlsClient().Build();
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetHttpClient(client)
                .SetModuleCacheDir(Path.Combine(work, "missing-asset-cache"))
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text(
                "value = import(\"package://localhost:0/birds@0.5.0#/missing.pkl\")\n"));
        });
        bool invalidMetadata = ThrowsPkl(() =>
        {
            using PklHttpClient client = server.NewTlsClient().Build();
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetHttpClient(client)
                .SetModuleCacheDir(Path.Combine(work, "invalid-metadata-cache"))
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text(
                "value = import(\"package://localhost:0/badMetadataJson@1.0.0#/main.pkl\")\n"));
        });
        bool invalidScheme = ThrowsUri(() => _ = new PackageUri("package:invalid"));
        int beforePolicy = server.RequestCount;
        bool policy = ThrowsPkl(() =>
        {
            using Evaluator evaluator = Evaluator.Preconfigured();
            _ = evaluator.Evaluate(ModuleSource.Uri(server.PlainUri("/plain-main.pkl")));
        }) && server.RequestCount == beforePolicy;
        bool coldCache = ThrowsPkl(() =>
        {
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetHttpClient(PklHttpClient.DummyClient())
                .SetModuleCacheDir(Path.Combine(work, "cold-offline-cache"))
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text(
                "value = import(\"package://localhost:0/birds@0.5.0#/Bird.pkl\")\n"));
        });
        Require(httpFailure && missingAsset && invalidMetadata && checksumFailure && invalidScheme &&
            policy && coldCache, "deterministic HTTP/package failure matrix");
        return $"http={Lower(httpFailure)}|missing={Lower(missingAsset)}" +
            $"|metadata={Lower(invalidMetadata)}|checksum={Lower(checksumFailure)}" +
            $"|scheme={Lower(invalidScheme)}|policy={Lower(policy)}" +
            $"|cold-cache={Lower(coldCache)}";
    }

    static string ObserveSettings(string fixtures, string work)
    {
        string projectDir = Path.Combine(work, "settings-project");
        CopyTree(Path.Combine(fixtures, "project"), projectDir);
        string projectFile = Path.Combine(projectDir, "PklProject");
        Project project = Project.LoadFromPath(projectFile);
        Project fromSource = Project.Load(ModuleSource.PathFromPath(projectFile));
        Require(project.Equals(fromSource), "path and ModuleSource project loading must agree");
        DripSharp.Brine.Project.Package package = project.GetPackage()!;
        Require(package.Name == "contract-project" && package.Version.ToString() == "1.2.3" &&
            package.Authors.Count == 1 && package.ApiTests.Count == 1 && package.Exclude.Count >= 3 &&
            package.Website is not null && package.Documentation is not null &&
            package.SourceCode is not null && package.IssueTracker is not null,
            "project package metadata must retain all configured fields");
        Require(project.GetAnnotations().Count == 3 && project.GetTests().Count == 1 &&
            project.GetDependencies().RemoteDependencies.Count == 1 &&
            project.GetDependencies().LocalDependencies.Count == 1 &&
            project.GetLocalProjectDependencies().Count == 1,
            "project annotations, tests, and local/remote dependencies must be parsed");
        PklEvaluatorSettings settings = project.GetResolvedEvaluatorSettings();
        PklSettings user = PklSettings.Load(ModuleSource.PathFromPath(
            Path.Combine(projectDir, "settings.pkl")));
        PklEvaluatorSettings.ExternalReader moduleReader =
            settings.ExternalModuleReaders!["contractmod"];
        PklEvaluatorSettings.ExternalReader resourceReader =
            settings.ExternalResourceReaders!["contractres"];
        EvaluatorBuilder applied = EvaluatorBuilder.Preconfigured().ApplyFromProject(project);
        Require(ReferenceEquals(applied.GetProjectDependencies(), project.GetDependencies()),
            "ApplyFromProject must retain declared dependencies");
        PModule localModule;
        using (Evaluator evaluator = applied.Build())
        {
            localModule = evaluator.Evaluate(ModuleSource.PathFromPath(
                Path.Combine(projectDir, "local-main.pkl")));
        }
        Require(Equals(localModule.GetProperty("localValue"), 42L) &&
            Equals(localModule.GetProperty("localResource"), true),
            "ApplyFromProject must resolve local dependency modules and resources");
        EvaluatorBuilder overridden = EvaluatorBuilder.Preconfigured()
            .ApplyFromProject(project)
            .SetColor(false)
            .AddEnvironmentVariable("CONTRACT_ENV", "caller-env");
        Require(!overridden.GetColor() &&
            overridden.GetEnvironmentVariables()["CONTRACT_ENV"] == "caller-env",
            "caller settings applied after a project must take precedence");
        var httpSettings = settings.HttpValue!;
        var proxyAddress = httpSettings.Proxy!.Address!;
        return $"env={settings.Env!["CONTRACT_ENV"]}" +
            $"|property={settings.ExternalProperties!["contract.property"]}" +
            $"|allowed={settings.AllowedModules!.Count}:{settings.AllowedResources!.Count}" +
            $"|paths={Lower(Path.GetFullPath(settings.RootDir!) == Path.GetFullPath(projectDir))}:" +
            $"{Lower(Path.GetFullPath(settings.ModuleCacheDir!) == Path.GetFullPath(Path.Combine(projectDir, "cache")))}:" +
            $"{Lower(Path.GetFullPath(settings.ModulePath![0]) == Path.GetFullPath(Path.Combine(projectDir, "modules")))}" +
            $"|timeout={settings.Timeout!.GetValue():0.0}" +
            $"|color={settings.Color!.ToString()!.ToLowerInvariant()}" +
            $"|trace={settings.TraceMode!.ToString()!.ToLowerInvariant()}" +
            $"|external={Lower(moduleReader.Executable.EndsWith(Path.Combine("tools", "module-reader"), StringComparison.Ordinal))}:" +
            $"{Lower(moduleReader.WorkingDir!.EndsWith("reader-work", StringComparison.Ordinal))}:" +
            $"{Lower(resourceReader.Executable == "contract-resource-reader")}" +
            $"|http={proxyAddress.OriginalString}:" +
            $"{httpSettings.Rewrites!.Count}:{httpSettings.Headers!.Count}" +
            $"|user={Lower(user.GetEditor().Equals(PklSettings.Editor.SUBLIME))}:" +
            $"{user.Http!.Headers!["https://mirror.test/**"]["X-Contract"].Count}" +
            $"|local={localModule.GetProperty("localValue")}:" +
            $"{Lower((bool)localModule.GetProperty("localResource"))}" +
            $"|applied={Lower(applied.GetColor())}:" +
            $"{applied.GetTraceMode().ToString()!.ToLowerInvariant()}:" +
            $"{applied.GetEnvironmentVariables()["CONTRACT_ENV"]}";
    }

    static string ObserveErrors(string fixtures, string work)
    {
        bool missing;
        using (Evaluator evaluator = Evaluator.Preconfigured())
        {
            try
            {
                _ = evaluator.Evaluate(ModuleSource.PathFromPath(Path.Combine(work, "does-not-exist.pkl")));
                missing = false;
            }
            catch (PklException error)
            {
                missing = error.Message.Contains("Cannot find module", StringComparison.OrdinalIgnoreCase);
            }
        }
        bool relative;
        using (Evaluator evaluator = Evaluator.Preconfigured())
        {
            try
            {
                _ = evaluator.Evaluate(ModuleSource.Create(
                    new Uri("relative.pkl", UriKind.Relative), "value = 1\n"));
                relative = false;
            }
            catch (PklException error)
            {
                relative = error.Message.Contains("relative module URI", StringComparison.OrdinalIgnoreCase);
            }
        }
        bool projectType;
        try
        {
            _ = Project.LoadFromPath(Path.Combine(fixtures, "project", "not-a-project.pkl"));
            projectType = false;
        }
        catch (PklException error)
        {
            projectType = error.Message.Contains("pkl.Project", StringComparison.Ordinal) &&
                error.Message.Contains("contract.NotAProject", StringComparison.Ordinal);
        }
        bool settingsType;
        try
        {
            _ = PklSettings.Load(ModuleSource.PathFromPath(
                Path.Combine(fixtures, "project", "not-settings.pkl")));
            settingsType = false;
        }
        catch (Exception error)
        {
            settingsType = error.Message.Contains("pkl.settings", StringComparison.OrdinalIgnoreCase);
        }
        bool malformedSettings = ThrowsPkl(() => _ = PklSettings.Load(ModuleSource.PathFromPath(
            Path.Combine(fixtures, "project", "malformed-settings.pkl"))));
        bool malformedProject = ThrowsPkl(() => _ = Project.LoadFromPath(
            Path.Combine(fixtures, "project", "malformed-settings.pkl")));
        bool invalidPackage = ThrowsUri(() => _ = new PackageUri("package:invalid"));
        bool ioFailure;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetAllowedModules(new List<Regex> { new("repl:"), new("iofail:") })
                .SetAllowedResources(Array.Empty<Regex>())
                .AddModuleKeyFactory(new FailingModuleFactory())
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text("value = import(\"iofail:module\")\n"));
            ioFailure = false;
        }
        catch (PklException error)
        {
            ioFailure = error.Message.Contains("I/O", StringComparison.OrdinalIgnoreCase) ||
                error.Message.Contains("contract I/O failure", StringComparison.OrdinalIgnoreCase);
        }
        Require(missing && relative && projectType && settingsType && malformedSettings && malformedProject &&
            invalidPackage && ioFailure, "deterministic missing, malformed, I/O, and output-type errors");
        return $"missing={Lower(missing)}|relative={Lower(relative)}" +
            $"|invalid-package={Lower(invalidPackage)}|io={Lower(ioFailure)}" +
            $"|project-type={Lower(projectType)}|settings-type={Lower(settingsType)}";
    }

    static string ObserveProjectCycles(string fixtures)
    {
        string root = Path.Combine(fixtures, "project");
        bool single;
        try
        {
            _ = Project.LoadFromPath(Path.Combine(root, "projectCycle1", "PklProject"));
            single = false;
        }
        catch (PklException error)
        {
            single = error.Message.Contains("circular", StringComparison.OrdinalIgnoreCase) &&
                error.Message.Contains("Cycle:", StringComparison.Ordinal);
        }
        bool multiple;
        try
        {
            _ = Project.LoadFromPath(Path.Combine(root, "projectCycle4", "PklProject"));
            multiple = false;
        }
        catch (PklException error)
        {
            multiple = error.Message.Contains("circular", StringComparison.OrdinalIgnoreCase) &&
                error.Message.Contains("Cycle 1:", StringComparison.Ordinal) &&
                error.Message.Contains("Cycle 2:", StringComparison.Ordinal);
        }
        Require(single && multiple, "single and multiple project cycles must be deterministic");
        return $"single={Lower(single)}|multiple={Lower(multiple)}";
    }

    static string ObserveLifecycle()
    {
        Evaluator evaluator = Evaluator.Preconfigured();
        evaluator.Dispose();
        evaluator.Dispose();
        bool evaluateAfterClose;
        try { _ = evaluator.Evaluate(ModuleSource.Text("value = 1\n")); evaluateAfterClose = false; }
        catch (Exception) { evaluateAfterClose = true; }

        PklHttpClient client = PklHttpClient.CreateBuilder().Build();
        client.Dispose();
        client.Dispose();
        bool httpAfterClose;
        try
        {
            using HttpResponseMessage _ = client.Send(
                new HttpRequestMessage(HttpMethod.Get, new Uri("https://example.test/")),
                _ => { });
            httpAfterClose = false;
        }
        catch (InvalidOperationException) { httpAfterClose = true; }
        Require(evaluateAfterClose && httpAfterClose, "evaluator and HTTP close boundaries");
        return $"evaluator-repeat=true|evaluator-after-close={Lower(evaluateAfterClose)}" +
            $"|http-repeat=true|http-after-close={Lower(httpAfterClose)}";
    }

    static string ObserveConfiguredExternalReader(string work)
    {
        string projectDir = Path.Combine(work, "external-reader-project");
        Directory.CreateDirectory(projectDir);
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("The package consumer process path is unavailable.");
        string entryAssembly = Assembly.GetEntryAssembly()?.Location
            ?? throw new InvalidOperationException("The package consumer entry assembly is unavailable.");
        string projectFile = Path.Combine(projectDir, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\n\n" +
            "evaluatorSettings {\n" +
            "  allowedModules { \"pkl:\"; \"file:\"; \"repl:\"; \"contractmod:\" }\n" +
            "  allowedResources { \"file:\"; \"contractres:\" }\n" +
            "  externalModuleReaders {\n" +
            "    [\"contractmod\"] {\n" +
            $"      executable = \"{PklString(executable)}\"\n" +
            $"      arguments {{ \"{PklString(entryAssembly)}\"; \"--external-reader\" }}\n" +
            "    }\n" +
            "  }\n" +
            "  externalResourceReaders {\n" +
            "    [\"contractres\"] {\n" +
            $"      executable = \"{PklString(executable)}\"\n" +
            $"      arguments {{ \"{PklString(entryAssembly)}\"; \"--external-reader\" }}\n" +
            "    }\n" +
            "  }\n" +
            "}\n",
            new UTF8Encoding(false));

        Project project = Project.LoadFromPath(projectFile);
        PModule module;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .ApplyFromProject(project)
            .Build())
        {
            module = evaluator.Evaluate(ModuleSource.Text(
                "value = import(\"contractmod:main\").value\n" +
                "resource = read(\"contractres:payload\").text\n"));
        }
        long value = (long)module.GetProperty("value");
        string resource = (string)module.GetProperty("resource");
        Require(value == 84 && resource == "external payload\n",
            $"configured external reader results: {value}, {Escape(resource)}");
        return $"value={value}|resource={Escape(resource)}";
    }

    static string ObserveExternalReaderFailureLifecycle(string work)
    {
        string parentWork = Path.Combine(work, "external-reader-failure-parent");
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("The package consumer process path is unavailable.");
        string entryAssembly = Assembly.GetEntryAssembly()?.Location
            ?? throw new InvalidOperationException("The package consumer entry assembly is unavailable.");
        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        start.ArgumentList.Add(entryAssembly);
        start.ArgumentList.Add("--external-reader-failure-parent");
        start.ArgumentList.Add(parentWork);
        using Process parent = Process.Start(start)
            ?? throw new InvalidOperationException("Could not start the external-reader failure parent.");
        Task<string> stdoutTask = parent.StandardOutput.ReadToEndAsync();
        Task<string> stderrTask = parent.StandardError.ReadToEndAsync();
        if (!parent.WaitForExit(30_000))
        {
            parent.Kill(entireProcessTree: true);
            parent.WaitForExit();
            throw new InvalidOperationException("The external-reader failure parent hung.");
        }
        string stdout = stdoutTask.GetAwaiter().GetResult();
        string stderr = stderrTask.GetAwaiter().GetResult();
        Require(parent.ExitCode == 0,
            $"external-reader failure parent crashed ({parent.ExitCode}): {stderr}");
        const string prefix = "external-reader-failures=";
        string? result = stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries)
            .Select(line => line.Trim())
            .SingleOrDefault(line => line.StartsWith(prefix, StringComparison.Ordinal));
        Require(result is not null,
            $"external-reader failure parent omitted its result: {stdout} {stderr}");
        return result![prefix.Length..];
    }

    static void RunExternalReaderFailureParent(string work)
    {
        ResetDirectory(work);
        int baselineHandles = CurrentHandleCount();
        IDictionary<string, string> first = RunExternalReaderFailurePass(
            Path.Combine(work, "pass-1"));
        CollectProcessFinalizers();
        int firstHandles = CurrentHandleCount();
        IDictionary<string, string> second = RunExternalReaderFailurePass(
            Path.Combine(work, "pass-2"));
        CollectProcessFinalizers();
        int secondHandles = CurrentHandleCount();

        Require(first.Count == second.Count &&
                first.All(entry => second.TryGetValue(entry.Key, out string? value) &&
                    value == entry.Value),
            "external-reader failures were not deterministic across isolated repetitions");
        Require(firstHandles <= baselineHandles + 12 && secondHandles <= firstHandles + 2,
            $"external-reader process handles leaked: {baselineHandles}/{firstHandles}/{secondHandles}");
        Console.WriteLine(
            "external-reader-failures=" +
            "exit-init=true|exit-read=true|malformed=true|truncated=true|protocol=true" +
            "|blocked=true|close-race=true|processes=true|handles=true");
    }

    static IDictionary<string, string> RunExternalReaderFailurePass(string work)
    {
        Directory.CreateDirectory(work);
        var results = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (string scenario in new[]
                 { "exit-init", "exit-read", "malformed-init", "truncated-read", "protocol-init", "blocked" })
            results[scenario] = RunExternalReaderFailure(scenario, work);
        results["close-race"] = RunExternalReaderCloseRace(work);
        return results;
    }

    static string RunExternalReaderFailure(string scenario, string work)
    {
        string marker = Path.Combine(work, scenario + ".pid");
        string projectFile = WriteExternalReaderFailureProject(work, scenario, marker,
            scenario == "blocked" ? "100.ms" : "5.s");
        Exception? failure = null;
        var watch = Stopwatch.StartNew();
        try
        {
            Project project = Project.LoadFromPath(projectFile);
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .ApplyFromProject(project)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Uri("faultmod:main"));
        }
        catch (Exception error)
        {
            failure = error;
        }
        watch.Stop();
        Require(failure is PklException && failure is not PklBugException,
            $"{scenario} did not surface a normal Pkl failure: {failure}");
        Require(watch.Elapsed < TimeSpan.FromSeconds(2),
            $"{scenario} did not fail within its bounded deadline: {watch.Elapsed}");
        int pid = ReadExternalReaderPid(marker,
            requireReadStage: scenario is "exit-read" or "truncated-read" or "blocked");
        Require(SpinWait.SpinUntil(() => !IsProcessAlive(pid), TimeSpan.FromSeconds(2)),
            $"{scenario} leaked external-reader process {pid}");
        return NormalizeExternalReaderFailure(failure!);
    }

    static string RunExternalReaderCloseRace(string work)
    {
        const string scenario = "close-race";
        string marker = Path.Combine(work, scenario + ".pid");
        string projectFile = WriteExternalReaderFailureProject(work, scenario, marker, null);
        Project project = Project.LoadFromPath(projectFile);
        Evaluator evaluator = EvaluatorBuilder.Preconfigured().ApplyFromProject(project).Build();
        Task<Exception?> evaluation = Task.Run(() =>
        {
            try
            {
                _ = evaluator.Evaluate(ModuleSource.Uri("faultmod:main"));
                return null;
            }
            catch (Exception error)
            {
                return error;
            }
        });
        int pid = ReadExternalReaderPid(marker, requireReadStage: true);
        var watch = Stopwatch.StartNew();
        evaluator.Dispose();
        watch.Stop();
        Require(watch.Elapsed < TimeSpan.FromSeconds(2),
            $"evaluator close blocked on external reader: {watch.Elapsed}");
        Require(evaluation.Wait(TimeSpan.FromSeconds(2)),
            "external-reader evaluation remained blocked after evaluator close");
        Exception? failure = evaluation.Result;
        Require(failure is PklException && failure is not PklBugException,
            $"close race did not surface a normal Pkl failure: {failure}");
        Require(SpinWait.SpinUntil(() => !IsProcessAlive(pid), TimeSpan.FromSeconds(2)),
            $"close race leaked external-reader process {pid}");
        return NormalizeExternalReaderFailure(failure!);
    }

    static string WriteExternalReaderFailureProject(
        string work, string scenario, string marker, string? timeout)
    {
        string projectDir = Path.Combine(work, scenario + "-project");
        Directory.CreateDirectory(projectDir);
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("The package consumer process path is unavailable.");
        string entryAssembly = Assembly.GetEntryAssembly()?.Location
            ?? throw new InvalidOperationException("The package consumer entry assembly is unavailable.");
        string projectFile = Path.Combine(projectDir, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\n\n" +
            "evaluatorSettings {\n" +
            (timeout is null ? "" : $"  timeout = {timeout}\n") +
            "  allowedModules { \"pkl:\"; \"file:\"; \"repl:\"; \"faultmod:\" }\n" +
            "  externalModuleReaders {\n" +
            "    [\"faultmod\"] {\n" +
            $"      executable = \"{PklString(executable)}\"\n" +
            $"      arguments {{ \"{PklString(entryAssembly)}\"; \"--external-reader-fault\"; \"{scenario}\"; \"{PklString(marker)}\" }}\n" +
            "    }\n" +
            "  }\n" +
            "}\n",
            new UTF8Encoding(false));
        return projectFile;
    }

    static int ReadExternalReaderPid(string marker, bool requireReadStage)
    {
        string? markerValue = null;
        Require(SpinWait.SpinUntil(
                () => TryReadExternalReaderMarker(marker, out markerValue) &&
                    (!requireReadStage || markerValue!.Contains(":read", StringComparison.Ordinal)),
                TimeSpan.FromSeconds(2)),
            $"external reader did not reach the expected stage: {marker}");
        string value = markerValue!.Split(':')[0];
        return int.Parse(value, CultureInfo.InvariantCulture);
    }

    static bool TryReadExternalReaderMarker(string marker, out string? value)
    {
        try
        {
            if (!File.Exists(marker))
            {
                value = null;
                return false;
            }
            value = File.ReadAllText(marker);
            return value.Length > 0;
        }
        catch (IOException)
        {
            value = null;
            return false;
        }
    }

    static string NormalizeExternalReaderFailure(Exception failure)
    {
        string firstLine = failure.Message.Split('\n', StringSplitOptions.None)[0].Trim();
        return failure.GetType().Name + ":" + firstLine;
    }

    static int CurrentHandleCount()
    {
        using Process current = Process.GetCurrentProcess();
        return current.HandleCount;
    }

    static void CollectProcessFinalizers()
    {
        GC.Collect();
        GC.WaitForPendingFinalizers();
        GC.Collect();
    }

    static TimeoutObservations ObserveTimeoutCancellation(string packageBuild, string work)
    {
        var elapsed = new List<TimeSpan>();
        TimeSpan shortTimeout = TimeSpan.FromMilliseconds(100);

        elapsed.Add(ExpectTimeout("cpu", shortTimeout,
            () => EvaluatorBuilder.Preconfigured().SetTimeout(shortTimeout).Build(),
            evaluator => _ = evaluator.Evaluate(ModuleSource.Text(
                "function fib(n) = if (n < 2) 0 else fib(n - 1) + fib(n - 2)\n" +
                "value = fib(100)\n"))));

        var blockingFactory = new BlockingModuleFactory();
        elapsed.Add(ExpectTimeout("module reader", shortTimeout,
            () => TimeoutBuilder(shortTimeout).AddModuleKeyFactory(blockingFactory).Build(),
            evaluator => _ = evaluator.Evaluate(ModuleSource.Uri("timeoutmod:main"))));
        Require(blockingFactory.Exited.Wait(TimeSpan.FromSeconds(1)),
            "blocking module reader did not unwind after timeout");

        var blockingResource = new BlockingResourceReader();
        elapsed.Add(ExpectTimeout("resource reader", shortTimeout,
            () => TimeoutBuilder(shortTimeout).AddResourceReader(blockingResource).Build(),
            evaluator => _ = evaluator.Evaluate(ModuleSource.Text(
                "value = read(\"timeoutres:item\")\n"))));
        Require(blockingResource.Exited.Wait(TimeSpan.FromSeconds(1)),
            "blocking resource reader did not unwind after timeout");

        using (var server = new BlockingTlsServer(packageBuild))
        using (PklHttpClient client = server.NewClient())
        {
            elapsed.Add(ExpectTimeout("HTTP module", shortTimeout,
                () => EvaluatorBuilder.Preconfigured()
                    .SetHttpClient(client).SetTimeout(shortTimeout).Build(),
                evaluator => _ = evaluator.Evaluate(
                    ModuleSource.Uri("https://localhost:0/timeout.pkl"))));
            Require(server.RequestReceived, "HTTP timeout probe did not reach the server");
        }

        string timeoutCache = Path.Combine(work, "timeout-package-cache");
        ResetDirectory(timeoutCache);
        using (var server = new BlockingTlsServer(packageBuild))
        using (PklHttpClient client = server.NewClient())
        {
            elapsed.Add(ExpectTimeout("package", shortTimeout,
                () => EvaluatorBuilder.Preconfigured()
                    .SetHttpClient(client)
                    .SetModuleCacheDir(timeoutCache)
                    .SetTimeout(shortTimeout)
                    .Build(),
                evaluator => _ = evaluator.Evaluate(ModuleSource.Text(
                    "value = import(\"package://localhost:0/timeout@1.0.0#/main.pkl\")\n"))));
            Require(server.RequestReceived, "package timeout probe did not reach the server");
        }
        Directory.Delete(timeoutCache, recursive: true);
        bool cacheClean = !Directory.Exists(timeoutCache);

        string slowProjectDir = Path.Combine(work, "timeout-project-load");
        Directory.CreateDirectory(slowProjectDir);
        string slowProject = Path.Combine(slowProjectDir, "PklProject");
        File.WriteAllText(slowProject,
            "amends \"pkl:Project\"\n" +
            "local function fib(n) = if (n < 2) 0 else fib(n - 1) + fib(n - 2)\n" +
            "evaluatorSettings { timeout = fib(100).s }\n",
            new UTF8Encoding(false));
        elapsed.Add(ExpectPklTimeout("project load", shortTimeout,
            () => _ = Project.LoadFromPath(
                slowProject, SecurityManagers.DefaultManager, shortTimeout)));

        bool processClean = ObserveProjectSettingsExternalTimeout(work, elapsed);

        bool successClean;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetTimeout(TimeSpan.FromMilliseconds(250)).Build())
        {
            _ = evaluator.Evaluate(ModuleSource.Text("value = 1\n"));
            Thread.Sleep(350);
            successClean = (long)evaluator.Evaluate(ModuleSource.Text("value = 2\n"))
                .GetProperty("value") == 2;
        }

        bool contextClean;
        using (Evaluator evaluator = Evaluator.Preconfigured())
            contextClean = (long)evaluator.Evaluate(ModuleSource.Text("value = 3\n"))
                .GetProperty("value") == 3;

        bool deadline = elapsed.All(duration => duration < TimeSpan.FromSeconds(2));
        Require(deadline && successClean && contextClean && processClean && cacheClean,
            "timeout deadline or cleanup contract");
        return new TimeoutObservations(
            "cpu=true|project-load=true|project-settings=true|diagnostic=true|deadline=true",
            "module=true|resource=true|http=true|package=true|external=true" +
            "|success-clean=true|context-clean=true|process-clean=true|cache-clean=true");
    }

    static EvaluatorBuilder TimeoutBuilder(TimeSpan timeout) =>
        EvaluatorBuilder.Unconfigured()
            .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
            .SetAllowedModules(new List<Regex>
                { new("timeoutmod:"), new("repl:"), new("pkl:") })
            .SetAllowedResources(new List<Regex> { new("timeoutres:") })
            .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .SetTimeout(timeout);

    static TimeSpan ExpectTimeout(
        string name,
        TimeSpan timeout,
        Func<Evaluator> create,
        Action<Evaluator> operation)
    {
        using Evaluator evaluator = create();
        return ExpectPklTimeout(name, timeout, () => operation(evaluator));
    }

    static TimeSpan ExpectPklTimeout(string name, TimeSpan timeout, Action operation)
    {
        var watch = Stopwatch.StartNew();
        try
        {
            operation();
            throw new InvalidOperationException($"{name} did not time out");
        }
        catch (PklException error)
        {
            watch.Stop();
            string seconds = timeout.TotalSeconds.ToString("0.##", CultureInfo.CurrentCulture);
            Require(error.Message == $"Evaluation timed out after {seconds} second(s).",
                $"{name} timeout diagnostic: {error.Message}");
            return watch.Elapsed;
        }
    }

    static bool ObserveProjectSettingsExternalTimeout(string work, IList<TimeSpan> elapsed)
    {
        string projectDir = Path.Combine(work, "timeout-external-reader-project");
        Directory.CreateDirectory(projectDir);
        string marker = Path.Combine(projectDir, "reader.pid");
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("The package consumer process path is unavailable.");
        string entryAssembly = Assembly.GetEntryAssembly()?.Location
            ?? throw new InvalidOperationException("The package consumer entry assembly is unavailable.");
        string projectFile = Path.Combine(projectDir, "PklProject");
        File.WriteAllText(projectFile,
            "amends \"pkl:Project\"\n\n" +
            "evaluatorSettings {\n" +
            "  timeout = 1.s\n" +
            "  allowedModules { \"pkl:\"; \"file:\"; \"repl:\"; \"timeoutreader:\" }\n" +
            "  externalModuleReaders {\n" +
            "    [\"timeoutreader\"] {\n" +
            $"      executable = \"{PklString(executable)}\"\n" +
            $"      arguments {{ \"{PklString(entryAssembly)}\"; \"--external-reader-block\"; \"{PklString(marker)}\" }}\n" +
            "    }\n" +
            "  }\n" +
            "}\n",
            new UTF8Encoding(false));

        Project project = Project.LoadFromPath(projectFile);
        TimeSpan configured = TimeSpan.FromSeconds(
            project.GetResolvedEvaluatorSettings().Timeout!.InSeconds());
        elapsed.Add(ExpectTimeout("project external reader", configured,
            () => EvaluatorBuilder.Preconfigured().ApplyFromProject(project).Build(),
            evaluator => _ = evaluator.Evaluate(ModuleSource.Uri("timeoutreader:main"))));

        Require(SpinWait.SpinUntil(() => File.Exists(marker), TimeSpan.FromSeconds(1)),
            "blocking external reader did not record its PID");
        int pid = int.Parse(File.ReadAllText(marker), CultureInfo.InvariantCulture);
        return SpinWait.SpinUntil(() => !IsProcessAlive(pid), TimeSpan.FromSeconds(1));
    }

    static bool IsProcessAlive(int pid)
    {
        try
        {
            using Process process = Process.GetProcessById(pid);
            return !process.HasExited;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    static string PklString(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("\"", "\\\"", StringComparison.Ordinal);

    static string ObserveAssemblyModules()
    {
        ModuleKeyFactory factory = ModuleKeyFactories.CreateAssembly(
            Assembly.GetExecutingAssembly(), "Contract.Modules");
        using (Evaluator evaluator = EvaluatorBuilder.Unconfigured()
            .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
            .SetAllowedModules(new List<Regex> { new("assembly:"), new("pkl:") })
            .SetAllowedResources(new List<Regex>())
            .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .AddModuleKeyFactory(factory)
            .Build())
        {
            PModule dependency = evaluator.Evaluate(ModuleSource.Uri("assembly:/dependency.pkl"));
            Require((long)dependency.GetProperty("value") == 42, "assembly direct module result");
            PModule module = evaluator.Evaluate(ModuleSource.Uri("assembly:/main.pkl"));
            Require((long)module.GetProperty("value") == 84, "assembly relative import result");
            string siblings = Sorted(module.GetProperty("siblings"));
            Require(siblings == "[./dependency.pkl, ./main.pkl]" ||
                    siblings == "[dependency.pkl, main.pkl]",
                "assembly module glob: " + siblings);
        }
        ModuleKey missing = factory.Create(new Uri("assembly:/missing.pkl"))
            ?? throw new InvalidOperationException("assembly module URI was not recognized");
        bool missingDiagnostic;
        try
        {
            _ = missing.Resolve(SecurityManagers.CreateStandard(
                new List<Regex> { new("assembly:") }, new List<Regex>(), _ => 0, null));
            missingDiagnostic = false;
        }
        catch (FileNotFoundException error)
        {
            missingDiagnostic = error.Message.Contains("assembly module", StringComparison.Ordinal);
        }
        Require(missingDiagnostic, "assembly missing-module diagnostic");
        factory.Dispose();
        factory.Dispose();
        bool disposed;
        try { _ = factory.Create(new Uri("assembly:/main.pkl")); disposed = false; }
        catch (ObjectDisposedException) { disposed = true; }
        Require(disposed, "assembly factory disposal boundary");
        return "module=42|relative=84|list=[dependency.pkl, main.pkl]" +
            "|missing=assembly:module-not-found";
    }

    static string ObserveEmbeddedResources()
    {
        ResourceReader reader = ResourceReaders.CreateEmbeddedResources(
            Assembly.GetExecutingAssembly(), "Contract.Resources");
        SecurityManager manager = SecurityManagers.CreateStandard(
            new List<Regex>(), new List<Regex> { new("embedded:") }, _ => 0, null);
        var payload = reader.Read(new Uri("embedded:/payload.txt")) as Resource
            ?? throw new InvalidOperationException("embedded resource URI was not recognized");
        string hex = string.Concat(payload.Bytes.Select(value =>
            unchecked((byte)value).ToString("x2", CultureInfo.InvariantCulture)));
        string list = "[" + string.Join(", ", reader.ListElements(manager, new Uri("embedded:/"))
            .Select(element => element.GetName()).OrderBy(name => name, StringComparer.Ordinal)) + "]";
        bool denied;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetAllowedModules(new List<Regex> { new("repl:"), new("pkl:") })
                .SetAllowedResources(new List<Regex>())
                .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
                .AddResourceReader(reader)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text("value = read(\"embedded:/payload.txt\")\n"));
            denied = false;
        }
        catch (PklException error)
        {
            denied = error.Message.Contains("resource allowlist", StringComparison.OrdinalIgnoreCase);
        }
        Require(denied, "embedded resource policy must precede reading");
        bool missing;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
                .SetAllowedModules(new List<Regex> { new("repl:"), new("pkl:") })
                .SetAllowedResources(new List<Regex> { new("embedded:") })
                .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
                .AddResourceReader(reader)
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text("value = read(\"embedded:/missing.txt\")\n"));
            missing = false;
        }
        catch (PklException error)
        {
            missing = error.Message.Contains("Cannot find resource", StringComparison.OrdinalIgnoreCase);
        }
        Require(missing, "embedded missing-resource diagnostic");
        reader.Dispose();
        reader.Dispose();
        bool disposed;
        try { _ = reader.Read(new Uri("embedded:/payload.txt")); disposed = false; }
        catch (ObjectDisposedException) { disposed = true; }
        Require(disposed, "embedded reader disposal boundary");
        return $"text={Escape(payload.GetText())}|bytes={hex}|list={list}" +
            "|denied=security:resource-not-allowed";
    }

    static string ObservePlatformPolicy(string work)
    {
        bool posix = ModuleSource.PathFromString(Path.Combine(work, "platform.pkl"))
            .GetUri().IsFile;
        var windows = new Uri("file:///C:/contract/main.pkl");
        bool windowsDrive = windows.Scheme == Uri.UriSchemeFile &&
            windows.AbsoluteUri.Contains("/C:/contract/main.pkl", StringComparison.Ordinal);
        var unc = new Uri("file://contract-server/share/main.pkl");
        bool uncPath = unc.IsFile && unc.Host == "contract-server";
        string root = Path.Combine(work, "platform-root");
        string outside = Path.Combine(work, "platform-outside.pkl");
        Directory.CreateDirectory(root);
        File.WriteAllText(outside, "value = 1\n");
        SecurityManager manager = SecurityManagers.CreateStandard(
            new List<Regex> { new("file:") }, new List<Regex>(), _ => 0, root);
        bool rootDenied = ThrowsSecurity(() => manager.CheckResolveModule(new Uri(outside)));
        bool encodedTraversal = ThrowsUri(() => _ = new PackageUri(
            "package://attacker.test/%2e%2e/legit@1.2.3"));
        Require(posix && windowsDrive && uncPath && rootDenied && encodedTraversal,
            $"platform URI and root policy adaptation: posix={posix}, " +
            $"windows={windowsDrive}, unc={uncPath}, root={rootDenied}, traversal={encodedTraversal}");
        return "posix=true|windows-drive=true|unc=true|root-denied=true|encoded-traversal=true";
    }

    static string ObserveOwnership(string fixtures, string work)
    {
        var factory = new CountingModuleFactory();
        var reader = new CountingResourceReader();
        using (Evaluator evaluator = EvaluatorBuilder.Unconfigured()
            .SetStackFrameTransformer(StackFrameTransformers.DefaultTransformer)
            .SetAllowedModules(new List<Regex> { new("repl:"), new("pkl:") })
            .SetAllowedResources(new List<Regex>())
            .AddModuleKeyFactory(ModuleKeyFactories.standardLibrary)
            .AddModuleKeyFactory(factory)
            .AddResourceReader(reader)
            .Build())
        {
            _ = evaluator.Evaluate(ModuleSource.Text("value = 1\n"));
        }
        bool callerOwned = factory.Closes == 0 && reader.Closes == 0;
        factory.Dispose();
        factory.Dispose();
        reader.Dispose();
        reader.Dispose();
        Require(factory.Closes == 1 && reader.Closes == 1, "repeat custom disposal");

        string archive = Path.Combine(work, "owned-archive.zip");
        ZipTree(Path.Combine(fixtures, "modulepath", "archive"), archive, "archive");
        var resolver = new ModulePathResolver(new[] { archive });
        _ = resolver.Resolve(new Uri("modulepath:/archive/module.pkl"));
        resolver.Dispose();
        resolver.Dispose();
        bool archiveDisposed;
        try
        {
            _ = resolver.Resolve(new Uri("modulepath:/archive/module.pkl"));
            archiveDisposed = false;
        }
        catch (InvalidOperationException)
        {
            archiveDisposed = true;
        }
        Require(callerOwned && archiveDisposed, "reader and archive ownership");

        string unsafeArchive = Path.Combine(work, "unsafe-archive.zip");
        using (ZipArchive zip = ZipFile.Open(unsafeArchive, ZipArchiveMode.Create))
        {
            using StreamWriter entry = new(zip.CreateEntry("../escape.pkl").Open());
            entry.Write("value = 1\n");
        }
        bool unsafeRejected;
        using (var unsafeResolver = new ModulePathResolver(new[] { unsafeArchive }))
        {
            try
            {
                _ = unsafeResolver.Resolve(new Uri("modulepath:/escape.pkl"));
                unsafeRejected = false;
            }
            catch (IOException)
            {
                unsafeRejected = true;
            }
        }
        Require(unsafeRejected && !File.Exists(Path.Combine(work, "escape.pkl")),
            "archive traversal must be rejected before extraction");

        DripSharp.Brine.Http.HttpClient http = DripSharp.Brine.Http.HttpClient.CreateBuilder().Build();
        http.Dispose();
        http.Dispose();
        bool httpDisposed = true;

        var externalSpec = new PklEvaluatorSettings.ExternalReader(
            Path.Combine(work, "not-started-reader"), null, null);
        ExternalReaderProcess process = ExternalReaderProcess.Of(externalSpec);
        using ModuleKeyFactory externalFactory =
            ModuleKeyFactories.CreateExternalProcess("contractexternal", process);
        process.Dispose();
        process.Dispose();
        bool externalDisposed;
        try
        {
            _ = externalFactory.Create(new Uri("contractexternal:item"));
            externalDisposed = false;
        }
        catch (Exception error)
        {
            externalDisposed = error.ToString().Contains("closed", StringComparison.OrdinalIgnoreCase);
        }
        ExternalReaderProcess resourceProcess = ExternalReaderProcess.Of(externalSpec);
        using ResourceReader externalReader = ResourceReaders.CreateExternalProcess(
            "contractexternalres", resourceProcess);
        resourceProcess.Dispose();
        resourceProcess.Dispose();
        bool externalResourceDisposed;
        try
        {
            _ = externalReader.Read(new Uri("contractexternalres:item"));
            externalResourceDisposed = false;
        }
        catch (Exception error)
        {
            externalResourceDisposed = error.ToString().Contains("closed", StringComparison.OrdinalIgnoreCase);
        }
        Require(httpDisposed && externalDisposed && externalResourceDisposed,
            "HTTP and external-process disposal");
        return "custom-owned-by-caller=true|owned-http-disposed=true|owned-archive-disposed=true" +
            "|owned-external-process-disposed=true|repeat=true";
    }

    sealed class FailingModuleFactory : ModuleKeyFactory
    {
        public ModuleKey? Create(Uri uri) => uri.Scheme == "iofail"
            ? new FailingModuleKey(uri)
            : null;
        public void Close() { }
        public void Dispose() { }
    }

    sealed class FailingModuleKey(Uri uri) : ModuleKey
    {
        public Uri GetUri() => uri;
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public ResolvedModuleKey Resolve(SecurityManager securityManager)
        {
            securityManager.CheckResolveModule(uri);
            return new FailingResolvedModuleKey(this, uri);
        }
    }

    sealed class FailingResolvedModuleKey(ModuleKey original, Uri uri) : ResolvedModuleKey
    {
        public ModuleKey GetOriginal() => original;
        public Uri GetUri() => uri;
        public string LoadSource() => throw new IOException("contract I/O failure");
    }

    sealed class CountingModuleFactory : ModuleKeyFactory
    {
        public int Creates { get; private set; }
        public int Closes { get; private set; }
        bool disposed;

        public ModuleKey? Create(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (uri.Scheme != "custom") return null;
            Creates++;
            return new CountingModuleKey(uri);
        }

        public void Close()
        {
            if (disposed) return;
            disposed = true;
            Closes++;
        }

        public void Dispose() => Close();
    }

    sealed class BlockingModuleFactory : ModuleKeyFactory
    {
        internal ManualResetEventSlim Exited { get; } = new(false);
        public ModuleKey? Create(Uri uri)
        {
            if (uri.Scheme != "timeoutmod") return null;
            try { Thread.Sleep(TimeSpan.FromSeconds(5)); }
            finally { Exited.Set(); }
            return null;
        }
        public void Close() { }
        public void Dispose() { }
    }

    sealed class BlockingResourceReader : ResourceReader
    {
        internal ManualResetEventSlim Exited { get; } = new(false);
        public string GetUriScheme() => "timeoutres";
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public object? Read(Uri uri)
        {
            try { Thread.Sleep(TimeSpan.FromSeconds(5)); }
            finally { Exited.Set(); }
            return null;
        }
        public void Close() { }
        public void Dispose() { }
    }

    sealed class CountingModuleKey(Uri uri) : ModuleKey
    {
        public Uri GetUri() => uri;
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public ResolvedModuleKey Resolve(SecurityManager securityManager)
        {
            securityManager.CheckResolveModule(uri);
            string source = uri.ToString() == "custom:dependency" ? "value = 42\n" : "value = 0\n";
            return new CountingResolvedModuleKey(this, uri, source);
        }
    }

    sealed class CountingResolvedModuleKey(ModuleKey original, Uri uri, string source)
        : ResolvedModuleKey
    {
        public ModuleKey GetOriginal() => original;
        public Uri GetUri() => uri;
        public string LoadSource() => source;
    }

    sealed class CountingResourceReader : ResourceReader
    {
        public int Closes { get; private set; }
        public int Reads { get; private set; }
        bool disposed;
        public string GetUriScheme() => "contractres";
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public object? Read(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            Reads++;
            return uri.ToString() == "contractres:item"
                ? "resource-value"
                : null;
        }
        public void Close()
        {
            if (disposed) return;
            disposed = true;
            Closes++;
        }
        public void Dispose() => Close();
    }

    static void CopyTree(string source, string destination)
    {
        foreach (string directory in Directory.GetDirectories(source, "*", SearchOption.AllDirectories))
            Directory.CreateDirectory(Path.Combine(destination, Path.GetRelativePath(source, directory)));
        Directory.CreateDirectory(destination);
        foreach (string file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            string target = Path.Combine(destination, Path.GetRelativePath(source, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target, true);
        }
    }

    static void ZipTree(string source, string archive, string prefix)
    {
        if (File.Exists(archive)) File.Delete(archive);
        using ZipArchive zip = ZipFile.Open(archive, ZipArchiveMode.Create);
        foreach (string file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            string relative = Path.GetRelativePath(source, file).Replace('\\', '/');
            string entry = string.IsNullOrEmpty(prefix)
                ? relative
                : prefix.Trim('/') + "/" + relative;
            zip.CreateEntryFromFile(file, entry);
        }
    }

    static void ResetDirectory(string path)
    {
        if (Directory.Exists(path)) Directory.Delete(path, true);
        Directory.CreateDirectory(path);
    }

    static bool ThrowsSecurity(Action action)
    {
        try { action(); return false; }
        catch (SecurityManagerException) { return true; }
    }

    static bool ThrowsUri(Action action)
    {
        try { action(); return false; }
        catch (Exception error) when (error is ArgumentException or UriFormatException) { return true; }
    }

    static bool ThrowsPkl(Action action)
    {
        try { action(); return false; }
        catch (PklException) { return true; }
    }

    static string Sorted(object value)
    {
        var values = new List<string>();
        foreach (object item in (IEnumerable)value) values.Add(item.ToString()!);
        values.Sort(StringComparer.Ordinal);
        return "[" + string.Join(", ", values) + "]";
    }

    static string Compact(string value) => value.Trim().Replace("\r", "").Replace("\n", " ");
    static string Escape(string value) => value.Replace("\\", "\\\\").Replace("\n", "\\n");
    static string Lower(bool value) => value ? "true" : "false";
    static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
    static string Encode(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
    static void Write(StreamWriter writer, string id, string kind, string observation) =>
        writer.WriteLine($"{id}\t{kind}\t{Encode(observation)}");

    sealed record NetworkObservations(
        string Http,
        string Packages,
        string UriComponents,
        string Collections,
        string ProjectPackage,
        string Errors);

    sealed record TimeoutObservations(string Shared, string Cleanup);

    sealed class ContractHttpServer : IDisposable
    {
        readonly string packageBuild;
        readonly TcpListener plain = new(IPAddress.Loopback, 0);
        readonly TcpListener tls = new(IPAddress.Loopback, 0);
        readonly CancellationTokenSource cancellation = new();
        readonly X509Certificate2 certificate;
        readonly Task plainLoop;
        readonly Task tlsLoop;
        int requestCount;
        int proxyRequestCount;
        int disposed;
        int allRequestsHadContractHeader = 1;
        readonly object headerRuleOrderLock = new();
        string headerRuleOrder = string.Empty;

        internal ContractHttpServer(string packageBuild)
        {
            this.packageBuild = packageBuild;
            string pfx = Path.Combine(packageBuild, "keystore", "localhost.p12");
            if (!File.Exists(pfx)) throw new FileNotFoundException("TLS fixture is missing.", pfx);
#pragma warning disable SYSLIB0057 // net8-compatible certificate construction
            certificate = new X509Certificate2(
                pfx,
                "password",
                X509KeyStorageFlags.Exportable);
#pragma warning restore SYSLIB0057
            plain.Start();
            tls.Start();
            plainLoop = Task.Run(() => AcceptLoop(plain, secure: false));
            tlsLoop = Task.Run(() => AcceptLoop(tls, secure: true));
        }

        internal int RequestCount => Volatile.Read(ref requestCount);
        internal int ProxyRequestCount => Volatile.Read(ref proxyRequestCount);
        internal bool AllRequestsHadContractHeader =>
            Volatile.Read(ref allRequestsHadContractHeader) != 0;
        internal string HeaderRuleOrder
        {
            get { lock (headerRuleOrderLock) return headerRuleOrder; }
        }
        internal Uri ProxyUri => new($"http://127.0.0.1:{((IPEndPoint)plain.LocalEndpoint).Port}");
        internal Uri PlainUri(string path) =>
            new($"http://localhost:{((IPEndPoint)plain.LocalEndpoint).Port}{path}");
        internal Uri TlsUri(string path) =>
            new($"https://localhost:{((IPEndPoint)tls.LocalEndpoint).Port}{path}");
        internal PklHttpClient.Builder NewTlsClient() => PklHttpClient.CreateBuilder()
            .AddCertificates(Path.Combine(packageBuild, "keystore", "localhost.pem"))
            .AddRewrite(new Uri("https://localhost:0/"), TlsUri("/"));

        void AcceptLoop(TcpListener listener, bool secure)
        {
            while (!cancellation.IsCancellationRequested)
            {
                TcpClient? client = null;
                try
                {
                    client = listener.AcceptTcpClient();
                    Handle(client, secure);
                }
                catch (Exception error) when (
                    cancellation.IsCancellationRequested &&
                    error is SocketException or ObjectDisposedException or IOException)
                {
                    client?.Dispose();
                    return;
                }
                catch
                {
                    client?.Dispose();
                    throw;
                }
            }
        }

        void Handle(TcpClient client, bool secure)
        {
            using (client)
            using (Stream network = client.GetStream())
            {
                Stream transport = network;
                SslStream? ssl = null;
                if (secure)
                {
                    ssl = new SslStream(network, leaveInnerStreamOpen: true);
                    ssl.AuthenticateAsServer(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                    });
                    transport = ssl;
                }
                try
                {
                    using var reader = new StreamReader(
                        transport, Encoding.ASCII, false, 4096, leaveOpen: true);
                    string requestLine = reader.ReadLine() ?? throw new IOException("Missing request line.");
                    string[] requestParts = requestLine.Split(' ', 3);
                    if (requestParts.Length < 2) throw new IOException("Malformed request line.");
                    string target = requestParts[1];
                    bool contractHeader = false;
                    var ruleOrder = new List<string>();
                    for (string? line = reader.ReadLine(); !string.IsNullOrEmpty(line); line = reader.ReadLine())
                    {
                        int colon = line!.IndexOf(':');
                        if (colon > 0 && line[..colon].Equals("X-Contract", StringComparison.OrdinalIgnoreCase) &&
                            line[(colon + 1)..].Trim().Equals("enabled", StringComparison.Ordinal))
                            contractHeader = true;
                        if (colon > 0 && line[..colon].Equals("X-Rule-Order", StringComparison.OrdinalIgnoreCase))
                            ruleOrder.AddRange(line[(colon + 1)..].Split(',')
                                .Select(value => value.Trim()).Where(value => value.Length > 0));
                    }
                    bool proxied = Uri.TryCreate(target, UriKind.Absolute, out Uri? absoluteTarget);
                    string path = proxied ? absoluteTarget!.AbsolutePath : target.Split('?', 2)[0];
                    if (path == "/main.pkl" && ruleOrder.Count > 0)
                    {
                        lock (headerRuleOrderLock)
                            headerRuleOrder = string.Join(",", ruleOrder);
                    }
                    if (proxied) Interlocked.Increment(ref proxyRequestCount);
                    if (!contractHeader && !path.Contains('@') && path != "/missing.pkl")
                        Interlocked.Exchange(ref allRequestsHadContractHeader, 0);
                    Interlocked.Increment(ref requestCount);
                    Respond(transport, path);
                }
                finally
                {
                    ssl?.Dispose();
                }
            }
        }

        void Respond(Stream stream, string path)
        {
            if (path == "/redirect.pkl")
            {
                Send(stream, 302, Array.Empty<byte>(), "Location: https://origin.test/main.pkl\r\n");
                return;
            }
            if (path == "/main.pkl")
            {
                Send(stream, 200, Encoding.UTF8.GetBytes(
                    "value = 42\npayload = read(\"https://origin.test/data.txt\").text\n"));
                return;
            }
            if (path == "/data.txt")
            {
                Send(stream, 200, Encoding.UTF8.GetBytes("secure payload\n"));
                return;
            }
            if (path == "/plain-main.pkl")
            {
                Send(stream, 200, Encoding.UTF8.GetBytes(
                    $"value = 42\npayload = read(\"{PlainUri("/plain-data.txt")}\").text\n"));
                return;
            }
            if (path == "/plain-data.txt")
            {
                Send(stream, 200, Encoding.UTF8.GetBytes("plain payload\n"));
                return;
            }
            if (path == "/proxy-main.pkl")
            {
                Send(stream, 200, Encoding.UTF8.GetBytes("value = 17\n"));
                return;
            }

            string relative = Uri.UnescapeDataString(path.TrimStart('/'));
            string? packageFile = null;
            if (relative.EndsWith(".zip", StringComparison.Ordinal))
            {
                string packageDirectory = relative[..relative.IndexOf('/')];
                packageFile = Path.Combine(
                    packageBuild,
                    "test-packages",
                    packageDirectory,
                    Path.GetFileName(relative));
            }
            else if (relative.Contains('@') && !relative.Contains('/'))
            {
                packageFile = Path.Combine(
                    packageBuild,
                    "test-packages",
                    relative,
                    relative + ".json");
            }
            if (packageFile is null || !File.Exists(packageFile))
            {
                Send(stream, 404, Array.Empty<byte>());
                return;
            }
            Send(stream, 200, File.ReadAllBytes(packageFile));
        }

        static void Send(Stream stream, int status, byte[] body, string extraHeaders = "")
        {
            string reason = status switch { 200 => "OK", 302 => "Found", 404 => "Not Found", _ => "Error" };
            byte[] headers = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {status} {reason}\r\n" + extraHeaders +
                $"Content-Length: {body.Length}\r\nConnection: close\r\n\r\n");
            stream.Write(headers);
            stream.Write(body);
            stream.Flush();
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            cancellation.Cancel();
            plain.Stop();
            tls.Stop();
            try { Task.WaitAll(new[] { plainLoop, tlsLoop }, TimeSpan.FromSeconds(5)); }
            catch (AggregateException error) when (error.InnerExceptions.All(
                inner => inner is SocketException or ObjectDisposedException or IOException)) { }
            cancellation.Dispose();
            certificate.Dispose();
        }
    }

    sealed class BlockingTlsServer : IDisposable
    {
        readonly TcpListener listener = new(IPAddress.Loopback, 0);
        readonly CancellationTokenSource cancellation = new();
        readonly X509Certificate2 certificate;
        readonly string certificatePath;
        readonly Task serverTask;
        int requestReceived;

        internal BlockingTlsServer(string packageBuild)
        {
            string pfx = Path.Combine(packageBuild, "keystore", "localhost.p12");
            certificatePath = Path.Combine(packageBuild, "keystore", "localhost.pem");
#pragma warning disable SYSLIB0057 // net8-compatible certificate construction
            certificate = new X509Certificate2(pfx, "password", X509KeyStorageFlags.Exportable);
#pragma warning restore SYSLIB0057
            listener.Start();
            serverTask = Task.Run(Serve);
        }

        internal bool RequestReceived => Volatile.Read(ref requestReceived) != 0;

        internal PklHttpClient NewClient() => PklHttpClient.CreateBuilder()
            .AddCertificates(certificatePath)
            .AddRewrite(
                new Uri("https://localhost:0/"),
                new Uri($"https://localhost:{((IPEndPoint)listener.LocalEndpoint).Port}/"))
            .Build();

        void Serve()
        {
            try
            {
                using TcpClient client = listener.AcceptTcpClientAsync(cancellation.Token)
                    .AsTask().GetAwaiter().GetResult();
                using var ssl = new SslStream(client.GetStream());
                ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = certificate,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                }, cancellation.Token).GetAwaiter().GetResult();
                using var reader = new StreamReader(ssl, Encoding.ASCII, false, 4096, leaveOpen: true);
                _ = reader.ReadLine();
                for (string? line = reader.ReadLine(); !string.IsNullOrEmpty(line); line = reader.ReadLine()) { }
                Interlocked.Exchange(ref requestReceived, 1);
                Task.Delay(TimeSpan.FromSeconds(5), cancellation.Token).GetAwaiter().GetResult();
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            catch (Exception error) when (cancellation.IsCancellationRequested &&
                error is SocketException or ObjectDisposedException or IOException) { }
        }

        public void Dispose()
        {
            cancellation.Cancel();
            listener.Stop();
            try { serverTask.GetAwaiter().GetResult(); }
            catch (Exception error) when (error is SocketException or ObjectDisposedException or IOException) { }
            cancellation.Dispose();
            certificate.Dispose();
        }
    }
}

/** Minimal validation-only external-reader protocol fixture. */
static class ExternalReaderFixture
{
    const int ReadResourceRequest = 38;
    const int ReadResourceResponse = 39;
    const int ReadModuleRequest = 40;
    const int ReadModuleResponse = 41;
    const int ListResourcesRequest = 42;
    const int ListResourcesResponse = 43;
    const int ListModulesRequest = 44;
    const int ListModulesResponse = 45;
    const int InitializeModuleReaderRequest = 46;
    const int InitializeModuleReaderResponse = 47;
    const int InitializeResourceReaderRequest = 48;
    const int InitializeResourceReaderResponse = 49;
    const int CloseExternalProcess = 50;

    public static void Run(Stream input, Stream output, bool blockResponses = false)
    {
        var reader = new MessagePackReader(input);
        var writer = new MessagePackWriter(output);
        while (reader.TryRead(out object? raw))
        {
            var message = RequireList(raw);
            if (message.Count != 2)
                throw new InvalidDataException("External-reader message must contain type and body.");
            int type = checked((int)RequireLong(message[0]));
            var body = RequireMap(message[1]);
            if (type == CloseExternalProcess) return;
            if (blockResponses) Thread.Sleep(TimeSpan.FromSeconds(5));
            long requestId = RequireLong(body["requestId"]);
            switch (type)
            {
                case InitializeModuleReaderRequest:
                    RequireString(body["scheme"], "contractmod");
                    writer.WriteMessage(InitializeModuleReaderResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["spec"] = new Dictionary<string, object?>
                        {
                            ["scheme"] = "contractmod",
                            ["hasHierarchicalUris"] = false,
                            ["isLocal"] = false,
                            ["isGlobbable"] = false
                        }
                    });
                    break;
                case InitializeResourceReaderRequest:
                    RequireString(body["scheme"], "contractres");
                    writer.WriteMessage(InitializeResourceReaderResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["spec"] = new Dictionary<string, object?>
                        {
                            ["scheme"] = "contractres",
                            ["hasHierarchicalUris"] = false,
                            ["isGlobbable"] = false
                        }
                    });
                    break;
                case ReadModuleRequest:
                    writer.WriteMessage(ReadModuleResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["evaluatorId"] = RequireLong(body["evaluatorId"]),
                        ["contents"] = Module(RequireString(body["uri"]))
                    });
                    break;
                case ReadResourceRequest:
                    writer.WriteMessage(ReadResourceResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["evaluatorId"] = RequireLong(body["evaluatorId"]),
                        ["contents"] = Resource(RequireString(body["uri"]))
                    });
                    break;
                case ListModulesRequest:
                    writer.WriteMessage(ListModulesResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["evaluatorId"] = RequireLong(body["evaluatorId"]),
                        ["pathElements"] = Elements("dependency.pkl", "main.pkl", "second.pkl")
                    });
                    break;
                case ListResourcesRequest:
                    writer.WriteMessage(ListResourcesResponse, new()
                    {
                        ["requestId"] = requestId,
                        ["evaluatorId"] = RequireLong(body["evaluatorId"]),
                        ["pathElements"] = Elements("payload.txt", "second.txt")
                    });
                    break;
                default:
                    throw new InvalidDataException($"Unexpected external-reader message type: {type}");
            }
        }
    }

    public static void RunFault(Stream input, Stream output, string scenario, string marker)
    {
        File.WriteAllText(marker,
            Environment.ProcessId.ToString(CultureInfo.InvariantCulture),
            new UTF8Encoding(false));
        if (scenario == "exit-init") return;

        var reader = new MessagePackReader(input);
        var writer = new MessagePackWriter(output);
        if (!reader.TryRead(out object? rawInitialize)) return;
        var initialize = RequireList(rawInitialize);
        if (initialize.Count != 2)
            throw new InvalidDataException("External-reader initialize message must contain type and body.");
        int initializeType = checked((int)RequireLong(initialize[0]));
        if (initializeType != InitializeModuleReaderRequest)
            throw new InvalidDataException($"Expected module-reader initialization, actual {initializeType}.");
        long initializeRequestId = RequireLong(RequireMap(initialize[1])["requestId"]);

        if (scenario == "malformed-init")
        {
            output.WriteByte(0xc1);
            output.Flush();
            return;
        }
        if (scenario == "protocol-init")
        {
            writer.WriteMessage(InitializeModuleReaderResponse, new()
            {
                ["requestId"] = initializeRequestId + 1,
                ["spec"] = new Dictionary<string, object?>
                {
                    ["scheme"] = "faultmod",
                    ["hasHierarchicalUris"] = false,
                    ["isLocal"] = false,
                    ["isGlobbable"] = false
                }
            });
            return;
        }

        writer.WriteMessage(InitializeModuleReaderResponse, new()
        {
            ["requestId"] = initializeRequestId,
            ["spec"] = new Dictionary<string, object?>
            {
                ["scheme"] = "faultmod",
                ["hasHierarchicalUris"] = false,
                ["isLocal"] = false,
                ["isGlobbable"] = false
            }
        });
        if (!reader.TryRead(out object? rawRead)) return;
        var read = RequireList(rawRead);
        if (read.Count != 2 || checked((int)RequireLong(read[0])) != ReadModuleRequest)
            throw new InvalidDataException("Expected a module read after initialization.");
        File.WriteAllText(marker,
            Environment.ProcessId.ToString(CultureInfo.InvariantCulture) + ":read",
            new UTF8Encoding(false));

        switch (scenario)
        {
            case "exit-read":
                return;
            case "truncated-read":
                output.WriteByte(0x92); // response envelope array
                output.WriteByte(ReadModuleResponse);
                output.WriteByte(0x81); // map with a missing key/value payload
                output.WriteByte(0xa9);
                output.Write(Encoding.ASCII.GetBytes("requestId"));
                output.Flush();
                return;
            case "blocked":
            case "close-race":
                Thread.Sleep(TimeSpan.FromSeconds(30));
                return;
            default:
                throw new InvalidDataException($"Unknown external-reader fault scenario: {scenario}");
        }
    }

    static string Module(string uri)
    {
        if (uri == "contractmod:main")
            return "value = import(\"contractmod:dependency\").value * 2\n";
        if (uri == "contractmod:dependency") return "value = 42\n";
        throw new FileNotFoundException("External module is missing.", uri);
    }

    static byte[] Resource(string uri)
    {
        if (uri == "contractres:payload")
            return Encoding.UTF8.GetBytes("external payload\n");
        throw new FileNotFoundException("External resource is missing.", uri);
    }

    static List<object?> Elements(params string[] names) => names
        .Select(name => (object?)new Dictionary<string, object?>
        {
            ["name"] = name,
            ["isDirectory"] = false
        })
        .ToList();

    static List<object?> RequireList(object? value) => value as List<object?>
        ?? throw new InvalidDataException("Expected a MessagePack array.");

    static Dictionary<string, object?> RequireMap(object? value) =>
        value as Dictionary<string, object?>
        ?? throw new InvalidDataException("Expected a MessagePack map.");

    static long RequireLong(object? value) => value is long number
        ? number
        : throw new InvalidDataException("Expected a MessagePack integer.");

    static string RequireString(object? value) => value as string
        ?? throw new InvalidDataException("Expected a MessagePack string.");

    static void RequireString(object? value, string expected)
    {
        string actual = RequireString(value);
        if (actual != expected)
            throw new InvalidDataException($"Expected scheme {expected}, actual {actual}.");
    }

    sealed class MessagePackReader(Stream input)
    {
        public bool TryRead(out object? value)
        {
            int prefix = input.ReadByte();
            if (prefix < 0)
            {
                value = null;
                return false;
            }
            value = ReadValue((byte)prefix);
            return true;
        }

        object? Read()
        {
            int prefix = input.ReadByte();
            if (prefix < 0) throw new EndOfStreamException();
            return ReadValue((byte)prefix);
        }

        object? ReadValue(byte prefix)
        {
            if (prefix <= 0x7f) return (long)prefix;
            if (prefix >= 0xe0) return (long)unchecked((sbyte)prefix);
            if ((prefix & 0xf0) == 0x80) return ReadMap(prefix & 0x0f);
            if ((prefix & 0xf0) == 0x90) return ReadArray(prefix & 0x0f);
            if ((prefix & 0xe0) == 0xa0) return ReadString(prefix & 0x1f);
            return prefix switch
            {
                0xc0 => null,
                0xc2 => false,
                0xc3 => true,
                0xc4 => ReadBinary(checked((int)ReadUnsigned(1))),
                0xc5 => ReadBinary(checked((int)ReadUnsigned(2))),
                0xc6 => ReadBinary(checked((int)ReadUnsigned(4))),
                0xcc => checked((long)ReadUnsigned(1)),
                0xcd => checked((long)ReadUnsigned(2)),
                0xce => checked((long)ReadUnsigned(4)),
                0xcf => unchecked((long)ReadUnsigned(8)),
                0xd0 => (long)unchecked((sbyte)ReadUnsigned(1)),
                0xd1 => (long)unchecked((short)ReadUnsigned(2)),
                0xd2 => (long)unchecked((int)ReadUnsigned(4)),
                0xd3 => unchecked((long)ReadUnsigned(8)),
                0xd9 => ReadString(checked((int)ReadUnsigned(1))),
                0xda => ReadString(checked((int)ReadUnsigned(2))),
                0xdb => ReadString(checked((int)ReadUnsigned(4))),
                0xdc => ReadArray(checked((int)ReadUnsigned(2))),
                0xdd => ReadArray(checked((int)ReadUnsigned(4))),
                0xde => ReadMap(checked((int)ReadUnsigned(2))),
                0xdf => ReadMap(checked((int)ReadUnsigned(4))),
                _ => throw new InvalidDataException($"Unsupported MessagePack prefix: 0x{prefix:x2}")
            };
        }

        ulong ReadUnsigned(int count)
        {
            ulong result = 0;
            for (int index = 0; index < count; index++)
            {
                int value = input.ReadByte();
                if (value < 0) throw new EndOfStreamException();
                result = (result << 8) | (byte)value;
            }
            return result;
        }

        byte[] ReadBinary(int length)
        {
            byte[] bytes = new byte[length];
            input.ReadExactly(bytes);
            return bytes;
        }

        string ReadString(int length) => Encoding.UTF8.GetString(ReadBinary(length));

        List<object?> ReadArray(int count)
        {
            var result = new List<object?>(count);
            for (int index = 0; index < count; index++) result.Add(Read());
            return result;
        }

        Dictionary<string, object?> ReadMap(int count)
        {
            var result = new Dictionary<string, object?>(count, StringComparer.Ordinal);
            for (int index = 0; index < count; index++)
                result.Add(RequireString(Read()), Read());
            return result;
        }
    }

    sealed class MessagePackWriter(Stream output)
    {
        public void WriteMessage(int type, Dictionary<string, object?> body)
        {
            WriteArrayHeader(2);
            WriteLong(type);
            WriteMap(body);
            output.Flush();
        }

        void Write(object? value)
        {
            switch (value)
            {
                case null:
                    output.WriteByte(0xc0);
                    break;
                case bool boolean:
                    output.WriteByte(boolean ? (byte)0xc3 : (byte)0xc2);
                    break;
                case int integer:
                    WriteLong(integer);
                    break;
                case long integer:
                    WriteLong(integer);
                    break;
                case string text:
                    WriteString(text);
                    break;
                case byte[] bytes:
                    output.WriteByte(0xc6);
                    WriteUnsigned((uint)bytes.Length, 4);
                    output.Write(bytes);
                    break;
                case Dictionary<string, object?> map:
                    WriteMap(map);
                    break;
                case List<object?> array:
                    WriteArrayHeader(array.Count);
                    foreach (object? item in array) Write(item);
                    break;
                default:
                    throw new InvalidDataException($"Unsupported MessagePack value: {value.GetType()}");
            }
        }

        void WriteMap(Dictionary<string, object?> map)
        {
            if (map.Count < 16) output.WriteByte((byte)(0x80 | map.Count));
            else
            {
                output.WriteByte(0xdf);
                WriteUnsigned((uint)map.Count, 4);
            }
            foreach ((string key, object? value) in map)
            {
                WriteString(key);
                Write(value);
            }
        }

        void WriteArrayHeader(int count)
        {
            if (count < 16) output.WriteByte((byte)(0x90 | count));
            else
            {
                output.WriteByte(0xdd);
                WriteUnsigned((uint)count, 4);
            }
        }

        void WriteString(string value)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(value);
            if (bytes.Length < 32) output.WriteByte((byte)(0xa0 | bytes.Length));
            else
            {
                output.WriteByte(0xdb);
                WriteUnsigned((uint)bytes.Length, 4);
            }
            output.Write(bytes);
        }

        void WriteLong(long value)
        {
            if (value >= 0 && value <= 0x7f)
            {
                output.WriteByte((byte)value);
                return;
            }
            if (value >= -32 && value < 0)
            {
                output.WriteByte(unchecked((byte)value));
                return;
            }
            output.WriteByte(0xd3);
            WriteUnsigned(unchecked((ulong)value), 8);
        }

        void WriteUnsigned(ulong value, int count)
        {
            for (int shift = (count - 1) * 8; shift >= 0; shift -= 8)
                output.WriteByte((byte)(value >> shift));
        }
    }
}
