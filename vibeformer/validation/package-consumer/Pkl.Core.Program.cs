using System;
using System.Linq;
using Pkl.Core;

static class Check
{
    public static void That(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
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
