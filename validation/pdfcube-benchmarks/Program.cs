using BenchmarkDotNet.Running;
using System.Runtime.CompilerServices;

namespace PdfCarton.Benchmarks;

internal static class Program
{
    public static void Main(string[] args)
    {
        if (!args.Any(argument => argument is "--artifacts" or "-a"))
            args = [.. args, "--artifacts", Path.GetFullPath(
                Path.Combine(SourceDirectory(), "../../validation-output/pdfcarton-benchmarks"))];
        // The assembly uses the product's test friend name. Limit BDN project
        // discovery to this directory, avoiding other test projects in the repo.
        for (var i = 0; i < args.Length - 1; i++)
            if (args[i] is "--artifacts" or "-a")
                args[i + 1] = Path.GetFullPath(args[i + 1]);
        Directory.SetCurrentDirectory(SourceDirectory());
        var summaries = BenchmarkSwitcher.FromAssembly(typeof(Program).Assembly).Run(args);
        if (summaries.Any(summary => summary.HasCriticalValidationErrors ||
            summary.Reports.Any(report => !report.Success)))
            Environment.ExitCode = 1;
    }

    private static string SourceDirectory([CallerFilePath] string path = "") =>
        Path.GetDirectoryName(path)!;
}
