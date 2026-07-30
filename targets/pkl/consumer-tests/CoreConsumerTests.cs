using DripSharp.Brine;
using DripSharp.Brine.Module;
using Xunit;

namespace DripSharp.Brine.Tests;

public sealed class CoreConsumerTests
{
    [Fact]
    public void PublicValuesAndEvaluatorConsumeAProductRepositoryFixture()
    {
        Duration duration = Duration.OfSeconds(90);
        Assert.Same(DurationUnit.SECONDS, duration.GetUnit());
        Assert.Equal(1.5, duration.InMinutes());
        Assert.Equal("PT1M30S", duration.ToIsoString());

        DataSize dataSize = DataSize.OfKibibytes(2);
        Assert.Same(DataSizeUnit.KIBIBYTES, dataSize.GetUnit());
        Assert.Equal(2048, dataSize.InBytes());

        var pair = new Pair<string, long>("answer", 42);
        Assert.Equal("answer", pair.GetFirst());
        Assert.Equal(42, pair.GetSecond());
        Assert.Same(PNull.GetInstance(), PNull.GetInstance());

        string fixture = Path.Combine(
            AppContext.BaseDirectory, "Fixtures", "sample.pkl");
        using Evaluator evaluator = Evaluator.Preconfigured();
        PModule module = evaluator.Evaluate(ModuleSource.PathFromPath(fixture));
        Assert.Equal("hello from Brine", module.GetProperty("greeting"));
        Assert.Equal(42L, module.GetProperty("answer"));
    }
}
