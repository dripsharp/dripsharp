using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using DripSharp.Brine;
using DripSharp.Brine.EvaluatorSettings;
using DripSharp.Brine.Module;
using DripSharp.Brine.Packages;
using DripSharp.Brine.Project;
using DripSharp.Brine.Resource;
using DripSharp.Brine.Settings;
using PklHttpClient = DripSharp.Brine.Http.HttpClient;

static class Check
{
    public static void That(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    public static T Throws<T>(Action action, string message) where T : Exception
    {
        try { action(); }
        catch (T error) { return error; }
        throw new InvalidOperationException(message);
    }
}

static class PackageConsumer
{
    public static void Main()
    {
        var duration = Duration.OfSeconds(90);
        Check.That(duration.GetUnit() == DurationUnit.SECONDS, "duration unit");
        Check.That(duration.InMinutes() == 1.5, "duration conversion");
        Check.That(duration.Equals(Duration.OfMinutes(1.5)), "duration value equality");
        Check.That(duration.ToIsoString() == "PT1M30S", "duration ISO rendering");

        var dataSize = DataSize.OfKibibytes(2);
        Check.That(dataSize.GetUnit() == DataSizeUnit.KIBIBYTES, "data-size unit");
        Check.That(dataSize.InBytes() == 2048, "data-size conversion");
        Check.That(dataSize.Equals(DataSize.OfBytes(2048)), "data-size value equality");

        var pair = new Pair<string, long>("answer", 42);
        Check.That(pair.GetFirst() == "answer" && pair.GetSecond() == 42, "pair accessors");
        Check.That(pair.SequenceEqual(new object?[] { "answer", 42L }), "pair iteration");
        Check.That(ReferenceEquals(PNull.GetInstance(), PNull.GetInstance()), "null singleton");
        Check.That(PNull.GetInstance().ToString() == "null", "null rendering");

        var source = ModuleSource.Uri(new Uri("file:///isolated-consumer.pkl"));
        Check.That(source.GetUri().IsFile && source.GetContents() is null, "module URI source");
        Check.That(ModuleSource.Create(new Uri("repl:created"), "value = 1").GetContents() == "value = 1",
            "module create source");
        Check.That(ModuleSource.Text("value = 2").GetUri().Scheme == "repl", "module text source");
        Check.That(ModuleSource.PathFromString(".").GetUri().IsFile, "module string-path source");
        Check.That(ModuleSource.FileFromString(".").GetUri().IsFile, "module file source");
        Check.That(ModuleSource.Uri("https://example.test/main.pkl").GetUri().Scheme == "https",
            "module string-URI source");
        Check.That(ModuleSource.ModulePath("lib/main.pkl").GetUri().ToString() ==
            "modulepath:/lib/main.pkl", "module-path source");
        using ModuleKeyFactory customFactory = new TextModuleKeyFactory();
        ModuleKey? customKey = customFactory.TryCreate(new Uri("consumer:module"));
        Check.That(customKey is not null && customKey.GetUri().Scheme == "consumer" &&
            customKey.Resolve(SecurityManagers.DefaultManager).LoadSource() == "value = 42\n",
            "idiomatic nullable module factory and resolved module contracts");

        var modulePatterns = new List<Regex> { new("repl:") };
        var resourcePatterns = new List<Regex> { new("env:"), new("prop:") };
        var defaults = EvaluatorBuilder.Preconfigured();
        var builder = EvaluatorBuilder.Unconfigured()
            .SetColor(true)
            .SetStackFrameTransformer(frame => frame)
            .SetAllowedModules(modulePatterns)
            .SetAllowedResources(resourcePatterns)
            .SetRootDir(null)
            .SetLogger(Loggers.Noop())
            .SetHttpClient(defaults.GetHttpClient())
            .SetModuleKeyFactories(defaults.GetModuleKeyFactories())
            .SetResourceReaders(defaults.GetResourceReaders())
            .SetEnvironmentVariables(new Dictionary<string, string> { ["CONTRACT_ENV"] = "before" })
            .AddEnvironmentVariable("SECOND_ENV", "second")
            .AddEnvironmentVariables(new Dictionary<string, string> { ["THIRD_ENV"] = "third" })
            .SetExternalProperties(new Dictionary<string, string> { ["contract.property"] = "property" })
            .AddExternalProperty("second.property", "second")
            .AddExternalProperties(new Dictionary<string, string> { ["third.property"] = "third" })
            .SetTimeout(TimeSpan.FromSeconds(9))
            .SetModuleCacheDir(null)
            .SetOutputFormat("pcf")
            .SetTraceMode(TraceMode.COMPACT)
            .SetPowerAssertionsEnabled(true);
        Check.That(builder.GetColor() && builder.GetStackFrameTransformer() is not null,
            "builder color and transformer getters");
        Check.That(builder.GetSecurityManager() is null && builder.GetRootDir() is null,
            "builder standard security getters");
        Check.That(builder.GetAllowedModules().Single().ToString() == "repl:" &&
            builder.GetAllowedResources().Count == 2, "builder allowlist getters");
        Check.That(builder.GetLogger() is not null, "builder logger getter");
        Check.That(ReferenceEquals(builder.GetHttpClient(), defaults.GetHttpClient()), "builder HTTP getter");
        Check.That(builder.GetModuleKeyFactories().Count == defaults.GetModuleKeyFactories().Count &&
            builder.GetResourceReaders().Count == defaults.GetResourceReaders().Count,
            "builder loader getters");
        var additiveLoaders = EvaluatorBuilder.Unconfigured()
            .AddModuleKeyFactory(defaults.GetModuleKeyFactories().First())
            .AddModuleKeyFactories(defaults.GetModuleKeyFactories().Take(0).ToList())
            .AddResourceReader(defaults.GetResourceReaders().First())
            .AddResourceReaders(defaults.GetResourceReaders().Take(0).ToList());
        Check.That(additiveLoaders.GetModuleKeyFactories().Count == 1 &&
            additiveLoaders.GetResourceReaders().Count == 1,
            "builder additive loader mutations");
        Check.That(builder.GetEnvironmentVariables().Count == 3 &&
            builder.GetExternalProperties().Count == 3, "builder map getters");
        Check.That(builder.GetTimeout() == TimeSpan.FromSeconds(9) &&
            builder.GetModuleCacheDir() is null && builder.GetOutputFormat() == "pcf",
            "builder timeout cache and output getters");
        Check.That(builder.SetOutputFormat(OutputFormat.JSON).GetOutputFormat() == "json",
            "builder typed output-format mutation");
        builder.SetOutputFormat("pcf");
        Check.That(builder.GetProjectDependencies() is null && builder.GetTraceMode() == TraceMode.COMPACT &&
            builder.GetPowerAssertionsEnabled(), "builder project trace and assertion getters");

        var customManager = SecurityManagers.CreateStandardBuilder()
            .SetAllowedModules(modulePatterns)
            .SetAllowedResources(resourcePatterns)
            .SetRootDir(null)
            .Build();
        customManager.CheckResolveModule(new Uri("repl:text"));
        Check.Throws<SecurityManagerException>(
            () => customManager.CheckResolveModule(new Uri("https://denied.test/main.pkl")),
            "standard manager must enforce its module allowlist");
        Check.That(customManager.ResolveSecurePath(new Uri("file:///tmp/contract.pkl"), false) is null,
            "standard manager without a root has no secure-path rewrite");
        Check.That(SecurityManagers.CreateStandardBuilder().AddAllowedModule(new Regex("repl:"))
            .AddAllowedResources(resourcePatterns).GetAllowedResources().Count == 2,
            "standard security builder mutation");
        Check.Throws<InvalidOperationException>(() => SecurityManagers.CreateStandardBuilder().Build(),
            "empty standard security builder must fail");
        var conflict = EvaluatorBuilder.Unconfigured().SetSecurityManager(customManager);
        Check.Throws<InvalidOperationException>(() => conflict.SetAllowedModules(modulePatterns),
            "custom manager and module allowlist must conflict");
        Check.Throws<InvalidOperationException>(() => conflict.SetAllowedResources(resourcePatterns),
            "custom manager and resource allowlist must conflict");
        Check.That(ReferenceEquals(conflict.UnsetSecurityManager().GetSecurityManager(), null),
            "security manager unset");

        var proxy = new PklEvaluatorSettings.Proxy(new Uri("http://localhost:8080"),
            new List<string> { "example.test" });
        var rewrites = new Dictionary<Uri, Uri>
        {
            [new Uri("https://origin.test/")] = new Uri("https://mirror.test/")
        };
        var headers = new Dictionary<string, IReadOnlyDictionary<string, IReadOnlyList<string>>>
        {
            ["**"] = new Dictionary<string, IReadOnlyList<string>>
            {
                ["X-Contract"] = new List<string> { "one", "two" }
            }
        };
        var http = new PklEvaluatorSettings.Http(proxy, rewrites, headers);
        var externalReader = new PklEvaluatorSettings.ExternalReader("reader-tool",
            new List<string> { "--mode", "module" }, "reader-work");
        var externalReaders = new Dictionary<string, PklEvaluatorSettings.ExternalReader>
        {
            ["contract"] = externalReader
        };
        var settings = new PklEvaluatorSettings(
            new Dictionary<string, string> { ["property"] = "value" },
            new Dictionary<string, string> { ["ENV"] = "value" },
            modulePatterns, resourcePatterns, Color.NEVER, null, null,
            new List<string> { "modules" }, Duration.OfSeconds(5), null, http,
            externalReaders, externalReaders, TraceMode.COMPACT);
        var equalSettings = new PklEvaluatorSettings(
            new Dictionary<string, string> { ["property"] = "value" },
            new Dictionary<string, string> { ["ENV"] = "value" },
            modulePatterns, resourcePatterns,
            Color.NEVER, null, null, new List<string> { "modules" },
            Duration.OfSeconds(5), null, http, externalReaders, externalReaders, TraceMode.COMPACT);
        Check.That(settings.Equals(equalSettings) && settings.GetHashCode() == equalSettings.GetHashCode(),
            "nullable evaluator-settings value equality");
        Check.That(settings.HttpValue?.Proxy?.NoProxy?.Single() == "example.test" &&
            settings.HttpValue.Headers?["**"]["X-Contract"].SequenceEqual(new[] { "one", "two" }) == true &&
            settings.ExternalModuleReaders?["contract"].WorkingDir == "reader-work",
            "HTTP proxy rewrite header and external-reader settings");
        Check.Throws<PklException>(() => PklEvaluatorSettings.Proxy.Create("http://[", null),
            "invalid proxy URI must fail deterministically");

        using (Evaluator snapshot = builder.Build())
        {
            builder.SetEnvironmentVariables(new Dictionary<string, string> { ["CONTRACT_ENV"] = "after" });
            var module = snapshot.Evaluate(ModuleSource.Text("value = read(\"env:CONTRACT_ENV\")"));
            Check.That((string)module.GetProperty("value")! == "before", "builder build snapshot ownership");
        }
        var disposable = Evaluator.Preconfigured();
        disposable.Dispose();
        disposable.Dispose();
        Check.Throws<InvalidOperationException>(
            () => disposable.Evaluate(ModuleSource.Text("value = 1")),
            "evaluation after disposal must fail deterministically");

        VerifyEvaluatorContextIsolation();

        using Evaluator evaluator = Evaluator.Preconfigured();
        Func<ModuleSource, PModule> evaluate = evaluator.Evaluate;
        Func<ModuleSource, object> evaluateOutputValue = evaluator.EvaluateOutputValue;
        Func<ModuleSource, string, object> evaluateExpression = evaluator.EvaluateExpression;
        Check.That(evaluate.Target is not null, "module evaluator entry target");
        Check.That(evaluateOutputValue.Target is not null, "output-value evaluator entry target");
        Check.That(evaluateExpression.Target is not null, "expression evaluator entry target");

        Console.WriteLine("Independent Brine Pkl package consumer passed.");
    }

    static void VerifyEvaluatorContextIsolation()
    {
        string work = Path.Combine(Path.GetTempPath(), "dripsharp-context-" + Guid.NewGuid());
        string firstRoot = Path.Combine(work, "first");
        string secondRoot = Path.Combine(work, "second");
        string outerRoot = Path.Combine(work, "outer");
        string innerRoot = Path.Combine(work, "inner");
        Directory.CreateDirectory(firstRoot);
        Directory.CreateDirectory(secondRoot);
        Directory.CreateDirectory(outerRoot);
        Directory.CreateDirectory(innerRoot);
        var firstHttp = new FixedHttpClient("first");
        var secondHttp = new FixedHttpClient("second");
        var outerHttp = new FixedHttpClient("outer");
        var innerHttp = new FixedHttpClient("inner");
        var firstReader = new ContextResourceReader("first");
        var secondReader = new ContextResourceReader("second");
        var outerReader = new ContextResourceReader("outer");
        var innerReader = new ContextResourceReader("inner");
        Evaluator? first = null;
        Evaluator? second = null;
        Evaluator? outer = null;
        Evaluator? inner = null;
        try
        {
            first = BuildContextEvaluator("first", firstRoot, firstHttp, firstReader);
            second = BuildContextEvaluator("second", secondRoot, secondHttp, secondReader);

            // Construction order must not choose the evaluator used by a later operation.
            ObserveContextEvaluator(first, "first", firstRoot);
            ObserveContextEvaluator(second, "second", secondRoot);
            ObserveContextEvaluator(first, "first", firstRoot);

            // Each evaluator runs on an independent flowed execution context at the same time.
            Task firstTask = Task.Run(() =>
            {
                for (int i = 0; i < 4; i++) ObserveContextEvaluator(first, "first", firstRoot);
            });
            Task secondTask = Task.Run(() =>
            {
                for (int i = 0; i < 4; i++) ObserveContextEvaluator(second, "second", secondRoot);
            });
            Task.WaitAll(firstTask, secondTask);

            // Closing one live evaluator must neither pop nor invalidate the other evaluator.
            second.Dispose();
            Check.Throws<InvalidOperationException>(
                () => second.Evaluate(ModuleSource.Text("value = 1")),
                "closed evaluator must reject later use");
            ObserveContextEvaluator(first, "first", firstRoot);

            inner = BuildContextEvaluator("inner", innerRoot, innerHttp, innerReader);
            outer = BuildContextEvaluator("outer", outerRoot, outerHttp, outerReader);
            bool nestedRan = false;
            outerReader.NestedAction = () =>
            {
                Check.That(!nestedRan, "nested context callback must run exactly once");
                nestedRan = true;
                string settingsFile = Path.Combine(innerRoot, "settings.pkl");
                File.WriteAllText(settingsFile,
                    "amends \"pkl:settings\"\neditor = Sublime\n",
                    new UTF8Encoding(false));
                Project project = Project.LoadFromPath(Path.Combine(innerRoot, "PklProject"));
                PklSettings settings = PklSettings.Load(ModuleSource.PathFromPath(settingsFile));
                Check.That(project.GetDependencies().RemoteDependencies.Count == 0,
                    "nested project load");
                Check.That(settings.GetEditor().Equals(PklSettings.Editor.SUBLIME),
                    "nested settings load");
                ObserveContextEvaluator(inner, "inner", innerRoot);
                inner.Dispose();
                Check.Throws<InvalidOperationException>(
                    () => inner.Evaluate(ModuleSource.Text("value = 1")),
                    "nested evaluator must reject use after close");
            };
            ObserveContextEvaluator(outer, "outer", outerRoot);
            Check.That(nestedRan, "nested project/settings and evaluator operations were not exercised");
        }
        finally
        {
            inner?.Dispose();
            outer?.Dispose();
            second?.Dispose();
            first?.Dispose();
            innerHttp.Dispose();
            outerHttp.Dispose();
            secondHttp.Dispose();
            firstHttp.Dispose();
            if (Directory.Exists(work)) Directory.Delete(work, true);
        }
    }

    static Evaluator BuildContextEvaluator(
        string identity,
        string root,
        FixedHttpClient httpClient,
        ContextResourceReader reader)
    {
        string projectFile = Path.Combine(root, "PklProject");
        File.WriteAllText(projectFile, "amends \"pkl:Project\"\n", new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(root, "value.pkl"),
            $"value = \"{identity}\"\n", new UTF8Encoding(false));
        var dependencies = new DeclaredDependencies(
            new Dictionary<string, Dependency.RemoteDependency>(),
            new Dictionary<string, DeclaredDependencies>(),
            new Uri(projectFile),
            null);
        EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured();
        var modules = new List<Regex>(builder.GetAllowedModules())
        {
            new("^http://127[.]0[.]0[.]1:")
        };
        var resources = new List<Regex>(builder.GetAllowedResources()) { new("context:") };
        Evaluator evaluator = builder
            .SetAllowedModules(modules)
            .SetAllowedResources(resources)
            .SetRootDir(root)
            .SetEnvironmentVariables(new Dictionary<string, string> { ["CONTEXT_ID"] = identity })
            .SetExternalProperties(new Dictionary<string, string> { ["context.id"] = identity })
            .SetHttpClient(httpClient.Client)
            .AddResourceReader(reader)
            .SetProjectDependencies(dependencies)
            .Build();
        return evaluator;
    }

    static void ObserveContextEvaluator(Evaluator evaluator, string identity, string root)
    {
        PModule configured = evaluator.Evaluate(ModuleSource.Text(
            "reader = read(\"context:value\")\n" +
            "environment = read(\"env:CONTEXT_ID\")\n" +
            "property = read(\"prop:context.id\")\n"));
        Check.That((string)configured.GetProperty("reader")! == identity,
            $"{identity} reader context");
        Check.That((string)configured.GetProperty("environment")! == identity,
            $"{identity} environment context");
        Check.That((string)configured.GetProperty("property")! == identity,
            $"{identity} property context");

        PModule local = evaluator.Evaluate(ModuleSource.PathFromPath(Path.Combine(root, "value.pkl")));
        Check.That((string)local.GetProperty("value")! == identity,
            $"{identity} root/security context");

        PModule remote = evaluator.Evaluate(ModuleSource.Uri(
            new Uri($"https://context.test/{identity}.pkl")));
        Check.That((string)remote.GetProperty("value")! == identity,
            $"{identity} HTTP context");
    }

    sealed class ContextResourceReader(string identity) : ResourceReader
    {
        public Action? NestedAction { get; set; }

        public override string GetUriScheme() => "context";
        public override bool HasHierarchicalUris() => false;
        public override bool IsGlobbable() => false;

        public override object? Read(Uri uri)
        {
            NestedAction?.Invoke();
            return identity;
        }

        public override void Close() { }
    }

    sealed class FixedHttpClient : IDisposable
    {
        readonly System.Threading.CancellationTokenSource cancellation = new();
        readonly TcpListener listener = new(IPAddress.Loopback, 0);
        readonly Task serverTask;
        bool disposed;

        public FixedHttpClient(string identity)
        {
            listener.Start();
            int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            Client = PklHttpClient.CreateBuilder()
                .AddRewrite(new Uri("https://context.test/"),
                    new Uri($"http://127.0.0.1:{port}/"))
                .Build();
            serverTask = Task.Run(() => Serve(identity));
        }

        public PklHttpClient Client { get; }

        async Task Serve(string identity)
        {
            try
            {
                while (!cancellation.IsCancellationRequested)
                {
                    using TcpClient connection = await listener.AcceptTcpClientAsync(cancellation.Token);
                    using NetworkStream stream = connection.GetStream();
                    byte[] request = new byte[4096];
                    int length = 0;
                    while (length < request.Length)
                    {
                        int read = await stream.ReadAsync(
                            request.AsMemory(length, request.Length - length), cancellation.Token);
                        if (read == 0) break;
                        length += read;
                        if (Encoding.ASCII.GetString(request, 0, length).Contains("\r\n\r\n",
                                StringComparison.Ordinal))
                            break;
                    }

                    byte[] body = Encoding.UTF8.GetBytes($"value = \"{identity}\"\n");
                    byte[] headers = Encoding.ASCII.GetBytes(
                        "HTTP/1.1 200 OK\r\n" +
                        $"Content-Length: {body.Length}\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Connection: close\r\n\r\n");
                    await stream.WriteAsync(headers, cancellation.Token);
                    await stream.WriteAsync(body, cancellation.Token);
                }
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            catch (SocketException) when (cancellation.IsCancellationRequested) { }
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            Client.Dispose();
            cancellation.Cancel();
            listener.Stop();
            serverTask.GetAwaiter().GetResult();
            cancellation.Dispose();
        }
    }

    sealed class TextModuleKeyFactory : ModuleKeyFactory
    {
        bool disposed;

        public override ModuleKey? Create(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            return uri.Scheme == "consumer" ? new TextModuleKey(uri) : null;
        }

        public override void Close() => disposed = true;
    }

    sealed class TextModuleKey(Uri uri) : ModuleKey
    {
        public Uri GetUri() => uri;
        public Uri Uri => GetUri();
        public bool Cached => IsCached();
        public bool Local => IsLocal();
        public string? FileCachePath => GetFileCacheLocation();
        public ResolvedModuleKey Resolve(DripSharp.Brine.SecurityManager securityManager) =>
            new TextResolvedModuleKey(this);
        public bool HasHierarchicalUris() => false;
        public bool IsGlobbable() => false;
        public bool IsCached() => false;
        public bool IsLocal() => false;
        public string? GetFileCacheLocation() => null;
        public bool HasElement(DripSharp.Brine.SecurityManager securityManager, Uri elementUri) => false;
        public IReadOnlyList<PathElement> ListElements(
            DripSharp.Brine.SecurityManager securityManager, Uri baseUri) => Array.Empty<PathElement>();
        public bool HasFragmentPaths() => false;
        public Uri ResolveUri(Uri value) => ResolveUri(uri, value);
        public Uri ResolveUri(Uri baseUri, Uri value) => value;
    }

    sealed class TextResolvedModuleKey(ModuleKey original) : ResolvedModuleKey
    {
        public ModuleKey GetOriginal() => original;
        public Uri GetUri() => original.GetUri();
        public ModuleKey Original => GetOriginal();
        public Uri Uri => GetUri();
        public string LoadSource() => "value = 42\n";
        public string Source => LoadSource();
    }
}
