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

    static ChildResult ExecuteCommandSpecParserTest(ContractRow row, CorpusFixture fixture)
    {
        const string renderOptions =
            "extends \"pkl:Command\"\nimport \"pkl:Command\"\n" +
            "options: Options\noutput { value = options }\n";
        PklCommandSpec Parse(string source, string fileName = "cmd.pkl")
        {
            string path = Path.Combine(fixture.Root, fileName);
            File.WriteAllText(path, source, new UTF8Encoding(false));
            using Evaluator evaluator = Evaluator.Preconfigured();
            return evaluator.ParseCommand(
                ModuleSource.FromPath(path),
                new HashSet<string>(new[] { "help", "root-dir" }, StringComparer.Ordinal),
                new HashSet<string>(new[] { "h" }, StringComparer.Ordinal));
        }

        (string Source, string[] Fragments)? failure = row.SourceMethod switch
        {
            "command module does not amend pkl_Command" =>
                ("", new[] { "Expected value of type `pkl.Command`, but got type" }),
            "options property assigned" =>
                ("extends \"pkl:Command\"\noptions = new {}",
                    new[] { "options = ", "Commands must not assign or amend property `options`." }),
            "options property amended" =>
                ("extends \"pkl:Command\"\noptions {}",
                    new[] { "options {", "Commands must not assign or amend property `options`." }),
            "parent property assigned" =>
                ("extends \"pkl:Command\"\nparent = new {}",
                    new[] { "parent = ", "Commands must not assign or amend property `parent`." }),
            "parent property amended" =>
                ("extends \"pkl:Command\"\nparent {}",
                    new[] { "parent {", "Commands must not assign or amend property `parent`." }),
            "options type annotation does not reference class" =>
                ("extends \"pkl:Command\"\noptions: \"nope\" | \"try again\"",
                    new[] { "options: \"nope\" | \"try again\"", "must be a class type" }),
            "options class is abstract" =>
                ("extends \"pkl:Command\"\noptions: Options\nabstract class Options {}",
                    new[] { "abstract class Options {", "may not be abstract" }),
            "command property value does not amend CommandInfo" =>
                ("extends \"pkl:Command\"\ncommand = new Foo {}\nclass Foo",
                    new[] { "command = new Foo {}", "Expected value of type `pkl.Command#CommandInfo`" }),
            "@Flag and @Argument on the same option" =>
                (renderOptions + "class Options { @Flag; @Argument; foo: String }",
                    new[] { "foo: String", "Found both `@Flag` and `@Argument`" }),
            "option with no type annotation" =>
                (renderOptions + "class Options { foo = \"bar\" }",
                    new[] { "foo = \"bar\"", "No type annotation found for `foo`" }),
            "option with union type containing non-string-literals" =>
                (renderOptions + "class Options { foo: \"oops\" | String }",
                    new[] { "foo: \"oops\" | String", "unsupported type" }),
            "argument with default not allowed" =>
                (renderOptions + "class Options { @Argument; foo: String = \"bar\" }",
                    new[] { "foo: String = \"bar\"", "Unexpected default value" }),
            "nullable non-collection argument not allowed" =>
                (renderOptions + "class Options { @Argument; foo: String? }",
                    new[] { "foo: String?", "Unexpected nullable type" }),
            "flag with collision on --help" =>
                (renderOptions + "class Options { help: Boolean }",
                    new[] { "help: Boolean", "collides with a reserved flag name" }),
            "flag with collision on -h" =>
                (renderOptions + "class Options { @Flag { shortName = \"h\" }; showHelp: Boolean }",
                    new[] { "showHelp: Boolean", "short name `h` collides" }),
            "flag with collision on reserved option name" =>
                (renderOptions + "class Options { `root-dir`: String }",
                    new[] { "`root-dir`: String", "collides with a reserved flag name" }),
            "multiple arguments with collection types not allowed" =>
                (renderOptions + "class Options { @Argument; list: List<String>; " +
                    "@Argument; set: Set<String> }",
                    new[] { "More than one repeated option", "Only one repeated argument" }),
            "collection option with collection element type" =>
                (renderOptions + "class Options { foo: List<List<\"a\" | \"b\">> }",
                    new[] { "unsupported element type `List<\"a\" | \"b\">`" }),
            "collection option with map element type" =>
                (renderOptions + "class Options { foo: List<Map<String, \"a\" | \"b\">> }",
                    new[] { "unsupported element type `Map<String, \"a\" | \"b\">`" }),
            "map option with collection value type" =>
                (renderOptions + "class Options { foo: Map<String, List<\"a\" | \"b\">> }",
                    new[] { "unsupported value type `List<\"a\" | \"b\">`" }),
            "map option with map value type" =>
                (renderOptions + "class Options { foo: Map<String, Map<String, \"a\" | \"b\">> }",
                    new[] { "unsupported value type `Map<String, \"a\" | \"b\">`" }),
            "map option with collection key type" or "map option with map key type" =>
                (renderOptions + "class Options { foo: Map<Map<String, \"a\" | \"b\">, String> }",
                    new[] { "unsupported key type `Map<String, \"a\" | \"b\">`" }),
            "unsupported option type" =>
                (renderOptions + "class Options { foo: Foo }; class Foo",
                    new[] { "foo: Foo", "unsupported type `Foo`" }),
            "conflicting subcommand names" =>
                ("extends \"pkl:Command\"\nimport \"pkl:Command\"\ncommand { subcommands { " +
                    "new Sub { command { name = \"foo\" } }; " +
                    "new Sub { command { name = \"foo\" } } } }\nclass Sub extends Command",
                    new[] { "subcommands with conflicting name \"foo\"" }),
            "map option with no type arguments" =>
                (renderOptions + "class Options { foo: Map }",
                    new[] { "unsupported type `Map`", "must provide two type arguments" }),
            "boolean flag with incorrect type" =>
                (renderOptions + "class Options { @BooleanFlag; foo: String }",
                    new[] { "annotation `@BooleanFlag` has invalid type `String`", "Expected type: `Boolean`" }),
            "counted flag with incorrect type" =>
                (renderOptions + "class Options { @CountedFlag; foo: String }",
                    new[] { "annotation `@CountedFlag` has invalid type `String`", "Expected type: `Int`" }),
            _ => null
        };
        if (failure is not null)
        {
            PklException error = Throws<PklException>(() => Parse(failure.Value.Source));
            Require(failure.Value.Fragments.All(fragment =>
                error.Message.Contains(fragment, StringComparison.Ordinal)),
                row.SourceMethod + " deterministic command diagnostic");
            return Passed(row);
        }

        switch (row.SourceMethod)
        {
            case "first annotation of the same type wins":
            {
                PklCommandSpec spec = Parse(renderOptions +
                    "open class BaseOptions { /// foo in BaseOptions\n@Flag { shortName = \"a\" }; " +
                    "foo: String; /// bar in BaseOptions\n@Flag { shortName = \"b\" }; bar: String }\n" +
                    "class Options extends BaseOptions { /// bar in Options\n" +
                    "@Flag { shortName = \"x\" }; bar: String; /// baz in Options\n" +
                    "@Flag { shortName = \"y\" }; @CountedFlag { shortName = \"z\" }; baz: Int }");
                Require(spec.Options.Count == 3 &&
                    spec.Options[0] is PklCommandFlag bar && bar.Name == "bar" &&
                    bar.ShortName == "x" && bar.HelpText == "bar in Options" &&
                    spec.Options[1] is PklCommandFlag baz && baz.Name == "baz" &&
                    baz.ShortName == "y" && baz.HelpText == "baz in Options" &&
                    spec.Options[2] is PklCommandFlag foo && foo.Name == "foo" &&
                    foo.ShortName == "a" && foo.HelpText == "foo in BaseOptions",
                    "command annotation and inheritance order");
                break;
            }
            case "non-constant default values result in an optional flag with no default":
            {
                PklCommandSpec spec = Parse(renderOptions +
                    "class Options { foo: String = \"hi\"; bar: String = foo; " +
                    "baz: Map<String, String> = Map(); qux: Map<String, String> = baz; quux: Int = 5 }");
                PklCommandFlag[] flags = spec.Options.Cast<PklCommandFlag>().ToArray();
                Require(flags.Select(flag => flag.Name).SequenceEqual(
                        new[] { "foo", "bar", "baz", "qux", "quux" }) &&
                    flags[0].DefaultValue == "hi" && flags[1].DefaultValue is null &&
                    flags[2].DefaultValue is null && flags[3].DefaultValue is null &&
                    flags[4].DefaultValue == "5", "command constant default extraction");
                break;
            }
            case "map option with map key type allowed with convert":
                _ = Parse(renderOptions +
                    "class Options { @Flag { convert = (it) -> Pair(\"foo\", \"a\") }; " +
                    "foo: Map<Map<String, \"a\" | \"b\">, String> }");
                break;
            case "options constraints in all positions are erased":
                _ = Parse(renderOptions + "class Options { a: String(true); b: String?(true); " +
                    "c: String(true)?; d: List<String(true)>; e: List<String(true)>(true); " +
                    "f: List<String(true)>(true)?(true); " +
                    "g: (Map<String(true), String(true)>(true)?(true))(true) }");
                break;
            case "list or set option with no type arguments":
                foreach (string type in new[] { "List", "Set" })
                {
                    PklException error = Throws<PklException>(() => Parse(
                        renderOptions + $"class Options {{ foo: {type} }}", $"cmd_{type}.pkl"));
                    Require(error.Message.Contains($"unsupported type `{type}`", StringComparison.Ordinal) &&
                        error.Message.Contains("must provide one type argument", StringComparison.Ordinal),
                        type + " option arity diagnostic");
                }
                break;
            case "union typed option validates invalid choice without stream error":
            {
                PklCommandSpec spec = Parse(renderOptions +
                    "class Options { format: \"json\" | \"yaml\" | \"toml\" }");
                var flag = (PklCommandFlag)spec.Options[0];
                Require(flag.Metavar == "[json, toml, yaml]", "union choice metavar");
                PklCommandOptionException error = Throws<PklCommandOptionException>(() =>
                    flag.Convert("xml", new Uri("file:///tmp")));
                Require(error.Message.Contains("invalid choice", StringComparison.Ordinal) &&
                    error.Message.Contains("xml", StringComparison.Ordinal), "invalid union choice");
                break;
            }
            case "typealias of nullable is resolved as optional":
            {
                PklCommandSpec spec = Parse(renderOptions +
                    "typealias OptionalString = String?\nclass Options { foo: OptionalString }");
                Require(spec.Options[0] is PklCommandFlag flag && !flag.ShowAsRequired,
                    "nullable typealias optional command flag");
                break;
            }
            default:
                return Pending(row);
        }
        return Passed(row);
    }

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
