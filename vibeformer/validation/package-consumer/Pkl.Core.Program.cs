using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Pkl.Core;
using Pkl.Core.EvaluatorSettings;

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
        var headers = new Dictionary<string, IDictionary<string, IList<string>>>
        {
            ["**"] = new Dictionary<string, IList<string>>
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

        using Evaluator evaluator = Evaluator.Preconfigured();
        Func<ModuleSource, PModule> evaluate = evaluator.Evaluate;
        Func<ModuleSource, object> evaluateOutputValue = evaluator.EvaluateOutputValue;
        Func<ModuleSource, string, object> evaluateExpression = evaluator.EvaluateExpression;
        Check.That(evaluate.Target is EvaluatorImpl, "module evaluator entry target");
        Check.That(evaluateOutputValue.Target is EvaluatorImpl, "output-value evaluator entry target");
        Check.That(evaluateExpression.Target is EvaluatorImpl, "expression evaluator entry target");

        Console.WriteLine("Independent Pkl.Core package consumer passed.");
    }
}
