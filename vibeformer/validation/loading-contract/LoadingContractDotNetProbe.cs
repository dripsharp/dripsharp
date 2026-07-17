using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using Pkl.Core;
using Pkl.Core.EvaluatorSettings;
using Pkl.Core.Externalreader;
using Pkl.Core.Module;
using Pkl.Core.Packages;
using Pkl.Core.Resource;
using Pkl.Core.Runtime;

/** Package-only .NET probe for the non-network loader and policy contract. */
static class LoadingContractDotNetProbe
{
    public static void Main(string[] args)
    {
        if (args.Length != 3)
            throw new ArgumentException("fixture, output, and work paths are required");
        string fixtures = Path.GetFullPath(args[0]);
        string output = Path.GetFullPath(args[1]);
        string work = Path.GetFullPath(args[2]);
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
        Write(writer, "assembly/module-loading", "DOTNET", ObserveAssemblyModules());
        Write(writer, "embedded/resource-loading", "DOTNET", ObserveEmbeddedResources());
        Write(writer, "platform/path-uri-policy", "DOTNET", ObservePlatformPolicy(work));
        Write(writer, "ownership/disposal", "DOTNET", ObserveOwnership(fixtures, work));
        AssertMissingDiagnostics(work);
        Console.WriteLine("Package-only non-network loading and policy validation passed.");
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
}
