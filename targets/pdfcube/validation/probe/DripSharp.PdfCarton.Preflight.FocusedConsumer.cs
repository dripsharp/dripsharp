#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Preflight;
using DripSharp.PdfCarton.Preflight.Exception;
using DripSharp.PdfCarton.Preflight.Parser;
using DripSharp.PdfCarton.Preflight.Process;

internal static class Program
{
    private static int processRuns;

    private static void Main(string[] args)
    {
        if (args.Length is not 0 and not 2)
        {
            throw new ArgumentException(
                "Expected no arguments or the expected operating system and architecture.");
        }

        var root = Path.Combine(
            Path.GetTempPath(),
            "pdfcube-preflight-consumer-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(root);
            var pdf = Path.Combine(root, "package-consumer.pdf");
            using (var source = new PDDocument())
            {
                source.AddPage(new PDPage());
                source.Save(pdf);
            }

            VerifyConfigurationResultAndErrors();
            VerifyParserContextValidationAndLifecycle(pdf);
            VerifyMalformedInput(Path.Combine(root, "malformed.pdf"));

            if (args.Length == 2)
                VerifyHost(args[0], args[1]);

            Console.WriteLine("DripSharp.PdfCarton.Preflight focused behavior passed.");
        }
        finally
        {
            if (Directory.Exists(root))
                Directory.Delete(root, recursive: true);
        }
    }

    private static void VerifyConfigurationResultAndErrors()
    {
        var configuration = new PreflightConfiguration();
        configuration.SetLazyValidation(true);
        configuration.SetMaxErrors(17);
        configuration.ReplaceProcess(
            "package-consumer",
            typeof(PackageValidationProcess));

        Assert(configuration.IsLazyValidation(),
            "Configuration must retain lazy validation.");
        Assert(configuration.GetMaxErrors() == 17,
            "Configuration must retain the maximum error count.");
        Assert(
            configuration.GetProcessNames().SequenceEqual(["package-consumer"]),
            "Configuration must retain process order.");
        Assert(
            configuration.GetInstanceOfProcess("package-consumer")
                is PackageValidationProcess,
            "Configuration must instantiate public validation processes.");

        var warning = new ValidationResult.ValidationError("9.8", "warning");
        warning.SetWarning(true);
        warning.SetPageNumber(3);
        var hard = new ValidationResult.ValidationError(
            PreflightConstants.ErrorSyntaxHeader,
            "invalid header",
            new IOException("package cause"));
        hard.SetPageNumber(5);
        var result = new ValidationResult(true);
        result.AddError(warning);
        Assert(result.IsValid(),
            "Warnings must not invalidate a validation result.");
        result.AddErrors(
            new List<ValidationResult.ValidationError> { hard });
        Assert(!result.IsValid() && result.GetErrorsList().Count == 2,
            "Hard validation errors must invalidate and aggregate.");
        Assert(
            hard.GetErrorCode() == PreflightConstants.ErrorSyntaxHeader &&
            hard.GetDetails().Contains("invalid header", StringComparison.Ordinal) &&
            hard.GetPageNumber() == 5 &&
            hard.GetCause()?.Message == "package cause",
            "Validation errors must retain code, detail, page, and cause.");
    }

    private static void VerifyParserContextValidationAndLifecycle(string pdf)
    {
        processRuns = 0;
        var configuration = new PreflightConfiguration();
        configuration.ReplaceProcess(
            "package-consumer",
            typeof(PackageValidationProcess));
        var input = new RandomAccessReadBufferedFile(new FileInfo(pdf));
        var parser = new PreflightParser(input);
        var document =
            (PreflightDocument)parser.Parse(Format.PdfA1b, configuration);
        var context = document.GetContext();

        Assert(ReferenceEquals(document, context.GetDocument()),
            "Parser context must retain its Preflight document.");
        Assert(context.GetFileLen() == new FileInfo(pdf).Length,
            "Parser context must retain the source length.");
        context.SetIccProfileAlreadySearched(true);
        context.SetCurrentPageNumber(7);
        Assert(context.IsIccProfileAlreadySearched() &&
               context.GetCurrentPageNumber() == 7,
            "Parser context state must remain mutable.");

        var result = document.Validate();
        Assert(ReferenceEquals(configuration, context.GetConfig()),
            "Validation context must retain its configuration.");
        Assert(processRuns == 1,
            "Document validation must run the configured process exactly once.");
        Assert(result.IsValid() &&
               result.GetErrorsList().Count == 1 &&
               result.GetErrorsList()[0].IsWarning() &&
               result.GetErrorsList()[0].GetPageNumber() == 7,
            "Validation must retain warning and page observations.");
        Assert(ReferenceEquals(result, document.Validate()),
            "Repeated validation must return the cached result.");

        document.Dispose();
        document.Dispose();
        Assert(input.IsClosed(),
            "Disposing a Preflight document must close its parser source.");
    }

    private static void VerifyMalformedInput(string malformed)
    {
        File.WriteAllText(malformed, "%PDF-1.4\nbroken", System.Text.Encoding.ASCII);
        var input = new RandomAccessReadBufferedFile(new FileInfo(malformed));
        try
        {
            var parser = new PreflightParser(input);
            try
            {
                _ = parser.Parse();
                throw new InvalidOperationException(
                    "Malformed PDF input must fail Preflight parsing.");
            }
            catch (SyntaxValidationException error)
            {
                Assert(!error.GetResult().IsValid(),
                    "Malformed parsing must expose an invalid result.");
                Assert(error.GetResult().GetErrorsList().Count > 0,
                    "Malformed parsing must expose validation errors.");
            }
        }
        finally
        {
            input.Dispose();
        }
        Assert(input.IsClosed(),
            "Malformed parser sources must support deterministic cleanup.");
    }

    private static void VerifyHost(string expectedOs, string expectedArchitecture)
    {
        var actualOs =
            RuntimeInformation.IsOSPlatform(OSPlatform.Windows) ? "windows" :
            RuntimeInformation.IsOSPlatform(OSPlatform.Linux) ? "linux" :
            RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "macos" :
            RuntimeInformation.OSDescription;
        var actualArchitecture =
            RuntimeInformation.ProcessArchitecture switch
            {
                Architecture.X64 => "x64",
                Architecture.Arm64 => "arm64",
                _ => RuntimeInformation.ProcessArchitecture.ToString().ToLowerInvariant(),
            };
        Assert(
            string.Equals(expectedOs, actualOs, StringComparison.Ordinal) &&
            string.Equals(
                expectedArchitecture,
                actualArchitecture,
                StringComparison.Ordinal),
            $"Host mismatch: expected {expectedOs}/{expectedArchitecture}, " +
            $"actual {actualOs}/{actualArchitecture}.");
        Console.WriteLine($"DripSharp.PdfCarton.Preflight host: {actualOs}/{actualArchitecture}");
    }

    public sealed class PackageValidationProcess : ValidationProcess
    {
        public void Validate(PreflightContext context)
        {
            processRuns++;
            var warning =
                new ValidationResult.ValidationError("9.7", "package process");
            warning.SetWarning(true);
            context.AddValidationError(warning);
        }
    }

    private static void Assert(bool condition, string message)
    {
        if (!condition)
            throw new InvalidOperationException(message);
    }
}
