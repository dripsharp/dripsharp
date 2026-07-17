using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
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
using Pkl.Core;
using Pkl.Core.EvaluatorSettings;
using Pkl.Core.Externalreader;
using Pkl.Core.Http;
using Pkl.Core.Module;
using Pkl.Core.Packages;
using Pkl.Core.Project;
using Pkl.Core.Resource;
using Pkl.Core.Runtime;

/** Package-only .NET probe for the loader, package, and policy contract. */
static class LoadingContractDotNetProbe
{
    public static void Main(string[] args)
    {
        if (args.Length != 4)
            throw new ArgumentException(
                "fixture, output, work, and upstream package-build paths are required");
        string fixtures = Path.GetFullPath(args[0]);
        string output = Path.GetFullPath(args[1]);
        string work = Path.GetFullPath(args[2]);
        string packageBuild = Path.GetFullPath(args[3]);
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        ResetDirectory(work);

        using var writer = new StreamWriter(output, false, new UTF8Encoding(false));
        Write(writer, "module-source/forms", "API", ObserveModuleSourceForms(work));
        Write(writer, "local/import-resource", "LOADING", ObserveLocal(fixtures));
        Write(writer, "local/list-glob", "LOADING", ObserveLocalGlob(fixtures));
        Write(writer, "modulepath/directory-archive", "LOADING", ObserveModulePath(fixtures, work));
        Write(writer, "stdlib/import", "LOADING", ObserveStandardLibrary());
        Write(writer, "custom/module-resource-lifecycle", "LOADING", ObserveCustomReaders());
        Write(writer, "resources/environment-property", "LOADING", ObserveEnvironmentAndProperties());
        Write(writer, "security/policy", "POLICY", ObserveSecurityPolicy(work));
        NetworkObservations network = ObserveNetworkAndPackages(packageBuild, work);
        Write(writer, "https/rewrite-redirect-headers", "HTTP", network.Http);
        Write(writer, "package/assets-cache-integrity", "PACKAGE", network.Packages);
        Write(writer, "project/projectpackage-dependencies", "PROJECT", network.ProjectPackage);
        Write(writer, "network/package-errors", "ERROR", network.Errors);
        Write(writer, "lifecycle/close", "LIFECYCLE", ObserveLifecycle());
        Write(writer, "assembly/module-loading", "DOTNET", ObserveAssemblyModules());
        Write(writer, "embedded/resource-loading", "DOTNET", ObserveEmbeddedResources());
        Write(writer, "platform/path-uri-policy", "DOTNET", ObservePlatformPolicy(work));
        Write(writer, "ownership/disposal", "DOTNET", ObserveOwnership(fixtures, work));
        AssertMissingDiagnostics(work);
        Console.WriteLine("Package-only loading, package, and policy validation passed.");
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
        ModuleKey directory = factory.Create(new Uri("modulepath:/directory/module.pkl")).OrElseThrow();
        ModuleKey zipped = factory.Create(new Uri("modulepath:/archive/module.pkl")).OrElseThrow();
        ResolvedModuleKey resolvedDirectory = directory.Resolve(SecurityManagers.defaultManager);
        ResolvedModuleKey resolvedZip = zipped.Resolve(SecurityManagers.defaultManager);
        var directoryResource = (Resource)reader.Read(
            new Uri("modulepath:/directory/resource.txt")).OrElseThrow();
        var zipResource = (Resource)reader.Read(
            new Uri("modulepath:/archive/resource.txt")).OrElseThrow();
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
            .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
            SecurityManagers.defaultTrustLevels,
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
                .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
                .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
        using var server = new ContractHttpServer(packageBuild);
        var httpModules = new List<Regex>(SecurityManagers.defaultAllowedModules) { new("http:") };
        var httpResources = new List<Regex>(SecurityManagers.defaultAllowedResources) { new("http:") };
        PModule httpModule;
        using (HttpClient client = HttpClient.CreateBuilder()
            .AddHeaders("**", new Dictionary<string, IList<string>>
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
        using (HttpClient proxyClient = HttpClient.CreateBuilder()
            .SetProxy(server.ProxyUri, Array.Empty<string>())
            .AddHeaders("**", new Dictionary<string, IList<string>>
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

        using HttpClient directClient = server.NewTlsClient()
            .AddRewrite(new Uri("https://origin.test/"), new Uri("https://localhost:0/"))
            .AddHeaders("**", new Dictionary<string, IList<string>>
                { ["X-Contract"] = new List<string> { "enabled" } })
            .Build();
        var checkedUris = new List<Uri>();
        JavaHttpResponse<sbyte[]> redirectResponse = directClient.Send(
            JavaHttpRequest.NewBuilder(new Uri("https://origin.test/redirect.pkl")).Build(),
            JavaHttpBodyHandlers.OfByteArray(),
            checkedUris.Add);
        PModule httpsModule;
        using (Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetHttpClient(directClient)
            .Build())
        {
            httpsModule = evaluator.Evaluate(ModuleSource.Uri("https://origin.test/main.pkl"));
        }
        string redirectBody = Encoding.UTF8.GetString(
            redirectResponse.Body().Select(value => unchecked((byte)value)).ToArray());
        string http = $"http={httpModule.GetProperty("value")}:" +
            Escape((string)httpModule.GetProperty("payload")) +
            $"|https={httpsModule.GetProperty("value")}:" +
            Escape((string)httpsModule.GetProperty("payload")) +
            $"|redirect={Compact(redirectBody)}|checked={checkedUris.Count}" +
            $"|headers={Lower(server.AllRequestsHadContractHeader)}" +
            $"|proxy={proxied.GetProperty("value")}:{Lower(server.ProxyRequestCount > 0)}";

        string cache = Path.Combine(work, "package-cache");
        string packageSource =
            "bird = import(\"package://localhost:0/birds@0.5.0#/catalog/Swallow.pkl\").name\n" +
            "modules = import*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n" +
            "resources = read*(\"package://localhost:0/birds@0.5.0#/catalog/*.pkl\").keys\n";
        int beforePackages = server.RequestCount;
        PModule first;
        using (HttpClient packageClient = server.NewTlsClient().Build())
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
            using HttpClient checksumClient = server.NewTlsClient().Build();
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
            .SetHttpClient(HttpClient.DummyClient())
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

        string projectPackage = ObserveProjectPackage(packageBuild, work, cache);
        string errors = ObserveNetworkErrors(server, work, checksumFailure);
        return new NetworkObservations(http, packages, projectPackage, errors);
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
        var remote = new Dictionary<string, Dependency.RemoteDependency>
        {
            ["birds"] = new Dependency.RemoteDependency(
                new PackageUri("package://localhost:0/birds@0.5.0"), null)
        };
        var declared = new DeclaredDependencies(
            remote,
            new Dictionary<string, DeclaredDependencies>(),
            new Uri(projectFile),
            null);
        using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
            .SetProjectDependencies(declared)
            .SetModuleCacheDir(cache)
            .SetHttpClient(HttpClient.DummyClient())
            .Build();
        PModule module = evaluator.Evaluate(ModuleSource.PathFromPath(main));
        return $"dependencies={remote.Count}|bird={module.GetProperty("bird")}" +
            $"|resource={Lower((bool)module.GetProperty("resource"))}";
    }

    static string ObserveNetworkErrors(ContractHttpServer server, string work, bool checksumFailure)
    {
        var httpModules = new List<Regex>(SecurityManagers.defaultAllowedModules) { new("http:") };
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
            using HttpClient client = server.NewTlsClient().Build();
            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetHttpClient(client)
                .SetModuleCacheDir(Path.Combine(work, "missing-asset-cache"))
                .Build();
            _ = evaluator.Evaluate(ModuleSource.Text(
                "value = import(\"package://localhost:0/birds@0.5.0#/missing.pkl\")\n"));
        });
        bool invalidMetadata = ThrowsPkl(() =>
        {
            using HttpClient client = server.NewTlsClient().Build();
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
                .SetHttpClient(HttpClient.DummyClient())
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

    static string ObserveLifecycle()
    {
        Evaluator evaluator = Evaluator.Preconfigured();
        evaluator.Dispose();
        evaluator.Dispose();
        bool evaluateAfterClose;
        try { _ = evaluator.Evaluate(ModuleSource.Text("value = 1\n")); evaluateAfterClose = false; }
        catch (Exception) { evaluateAfterClose = true; }

        HttpClient client = HttpClient.CreateBuilder().Build();
        client.Dispose();
        client.Dispose();
        bool httpAfterClose;
        try
        {
            _ = client.Send(
                JavaHttpRequest.NewBuilder(new Uri("https://example.test/")).Build(),
                JavaHttpBodyHandlers.OfByteArray(),
                _ => { });
            httpAfterClose = false;
        }
        catch (InvalidOperationException) { httpAfterClose = true; }
        Require(evaluateAfterClose && httpAfterClose, "evaluator and HTTP close boundaries");
        return $"evaluator-repeat=true|evaluator-after-close={Lower(evaluateAfterClose)}" +
            $"|http-repeat=true|http-after-close={Lower(httpAfterClose)}";
    }

    static string ObserveAssemblyModules()
    {
        var factory = (AssemblyModuleKeyFactory)ModuleKeyFactories.CreateAssembly(
            Assembly.GetExecutingAssembly(), "Contract.Modules");
        using (Evaluator evaluator = EvaluatorBuilder.Unconfigured()
            .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
        ModuleKey missing = factory.Create(new Uri("assembly:/missing.pkl")).OrElseThrow();
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
        var reader = (EmbeddedResourceReader)ResourceReaders.CreateEmbeddedResources(
            Assembly.GetExecutingAssembly(), "Contract.Resources");
        SecurityManager manager = SecurityManagers.CreateStandard(
            new List<Regex>(), new List<Regex> { new("embedded:") }, _ => 0, null);
        var payload = (Resource)reader.Read(new Uri("embedded:/payload.txt")).OrElseThrow();
        string hex = string.Concat(payload.Bytes.Select(value =>
            unchecked((byte)value).ToString("x2", CultureInfo.InvariantCulture)));
        string list = "[" + string.Join(", ", reader.ListElements(manager, new Uri("embedded:/"))
            .Select(element => element.GetName()).OrderBy(name => name, StringComparer.Ordinal)) + "]";
        bool denied;
        try
        {
            using Evaluator evaluator = EvaluatorBuilder.Unconfigured()
                .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
                .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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
            .SetStackFrameTransformer(StackFrameTransformers.defaultTransformer)
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

        Pkl.Core.Http.HttpClient http = Pkl.Core.Http.HttpClient.CreateBuilder().Build();
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

    static void AssertMissingDiagnostics(string work)
    {
        bool missing;
        using (Evaluator evaluator = Evaluator.Preconfigured())
        {
            try
            {
                _ = evaluator.Evaluate(ModuleSource.PathFromPath(Path.Combine(work, "missing.pkl")));
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
        Require(missing && relative, "stable missing and invalid module diagnostics");
    }

    sealed class CountingModuleFactory : ModuleKeyFactory
    {
        public int Creates { get; private set; }
        public int Closes { get; private set; }
        bool disposed;

        public JavaOptional<ModuleKey> Create(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (uri.Scheme != "custom") return JavaOptional<ModuleKey>.Empty();
            Creates++;
            return JavaOptional<ModuleKey>.Of(new CountingModuleKey(uri));
        }

        public void Close()
        {
            if (disposed) return;
            disposed = true;
            Closes++;
        }

        public void Dispose() => Close();
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
        public JavaOptional<object> Read(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            Reads++;
            return uri.ToString() == "contractres:item"
                ? JavaOptional<object>.Of("resource-value")
                : JavaOptional<object>.Empty();
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
            zip.CreateEntryFromFile(file, prefix.Trim('/') + "/" + relative);
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
        string ProjectPackage,
        string Errors);

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
        internal Uri ProxyUri => new($"http://localhost:{((IPEndPoint)plain.LocalEndpoint).Port}");
        internal Uri PlainUri(string path) =>
            new($"http://localhost:{((IPEndPoint)plain.LocalEndpoint).Port}{path}");
        internal HttpClient.Builder NewTlsClient() => HttpClient.CreateBuilder()
            .AddCertificates(Path.Combine(packageBuild, "keystore", "localhost.pem"))
            .SetTestPort(((IPEndPoint)tls.LocalEndpoint).Port);

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
                    for (string? line = reader.ReadLine(); !string.IsNullOrEmpty(line); line = reader.ReadLine())
                    {
                        int colon = line!.IndexOf(':');
                        if (colon > 0 && line[..colon].Equals("X-Contract", StringComparison.OrdinalIgnoreCase) &&
                            line[(colon + 1)..].Trim().Equals("enabled", StringComparison.Ordinal))
                            contractHeader = true;
                    }
                    bool proxied = Uri.TryCreate(target, UriKind.Absolute, out Uri? absoluteTarget);
                    string path = proxied ? absoluteTarget!.AbsolutePath : target.Split('?', 2)[0];
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
}
