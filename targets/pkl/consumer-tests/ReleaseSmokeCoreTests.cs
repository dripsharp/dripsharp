using System.Text;
using System.Text.RegularExpressions;
using DripSharp.Brine;
using DripSharp.Brine.Module;
using DripSharp.Brine.Resource;
using Xunit;

namespace DripSharp.Brine.ReleaseSmoke;

public sealed class ReleaseSmokeCoreTests
{
    [Fact]
    public void CoreRuntimeAndEvaluatorExposeRepresentativePublicBehavior()
    {
        Duration duration = Duration.OfSeconds(90);
        Assert.Same(DurationUnit.SECONDS, duration.GetUnit());
        Assert.Equal(1.5, duration.InMinutes());
        Assert.Equal("PT1M30S", duration.ToIsoString());

        DataSize size = DataSize.OfKibibytes(2);
        Assert.Same(DataSizeUnit.KIBIBYTES, size.GetUnit());
        Assert.Equal(2048, size.InBytes());

        var pair = new Pair<string, long>("answer", 42);
        Assert.Equal("answer", pair.GetFirst());
        Assert.Equal(42, pair.GetSecond());
        Assert.Same(PNull.GetInstance(), PNull.GetInstance());

        ModuleSource source = ModuleSource.FromText(
            "name = \"Brine\"\nanswer = 40 + 2\nclass Bird { name: String }\n");
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(source);

        Assert.Equal("Brine", module.GetProperty("name"));
        Assert.Equal(42L, module.GetProperty("answer"));
        Assert.Equal(42L, evaluator.EvaluateExpression(source, "answer"));
        Assert.Contains("Bird", evaluator.EvaluateSchema(source).GetClasses().Keys);
    }

    [Fact]
    public void FileLoadingFactoriesAndSecurityPolicyRemainUsable()
    {
        string root = Path.Combine(
            Path.GetTempPath(), $"brine-release-smoke-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        try
        {
            string child = Path.Combine(root, "child.pkl");
            string entry = Path.Combine(root, "entry.pkl");
            File.WriteAllText(child, "value = 42\n", new UTF8Encoding(false));
            File.WriteAllText(
                entry,
                "import \"child.pkl\"\nloaded = child.value\n",
                new UTF8Encoding(false));

            using Evaluator evaluator = EvaluatorBuilder.Preconfigured()
                .SetRootDir(root)
                .Build();
            PModule module = evaluator.Evaluate(ModuleSource.FromPath(entry));
            Assert.Equal(42L, module.GetProperty("loaded"));

            ModuleKey? key = ModuleKeyFactories.FileFactory.TryCreate(new Uri(child));
            Assert.NotNull(key);
            Assert.Equal(new Uri(child), key.Uri);
            Assert.True(key.Local);

            using ResourceReader reader = ResourceReaders.CreateFile();
            Assert.Equal("file", reader.GetUriScheme());
            Assert.True(reader.HasHierarchicalUris());

            SecurityManager manager = SecurityManagers.CreateStandardBuilder()
                .SetAllowedModules(new[] { new Regex("^repl:") })
                .SetAllowedResources(Array.Empty<Regex>())
                .Build();
            manager.CheckResolveModule(new Uri("repl:release-smoke"));
            Assert.Throws<SecurityManagerException>(
                () => manager.CheckResolveModule(new Uri(child)));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
