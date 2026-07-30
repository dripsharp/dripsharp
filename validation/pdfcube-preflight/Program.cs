using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Xml;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Preflight;
using DripSharp.PdfCarton.Preflight.Exception;
using DripSharp.PdfCarton.Preflight.Parser;
using DripSharp.PdfCarton.Preflight.Process;

internal static class Program
{
    private static readonly List<string> Observations = new();
    private static readonly List<string> ProcessTrace = new();

    private static int Main(string[] args)
    {
        if (args.Length != 3)
        {
            throw new ArgumentException(
                "Expected output trace, generated fixture directory, and repository root.");
        }

        var output = args[0];
        var fixtureDirectory = args[1];
        var repositoryRoot = args[2];
        var upstreamFixtures = Path.Combine(
            repositoryRoot,
            "research",
            "pdfbox",
            "preflight",
            "src",
            "test",
            "resources");

        ObserveFormatAndConstants();
        ObserveConfiguration();
        ObserveResultAndErrors();
        ObservePath();
        ObserveParsers(upstreamFixtures, fixtureDirectory);
        ObserveDocumentAndProcesses(upstreamFixtures);
        ObserveContextAndLifetime(upstreamFixtures, fixtureDirectory);
        ObserveXml(upstreamFixtures);
        ObserveValidationProcesses(upstreamFixtures, fixtureDirectory);

        File.WriteAllLines(output, Observations, new UTF8Encoding(false));
        return 0;
    }

    private static void ObserveFormatAndConstants()
    {
        Observe(
            "format",
            "values",
            Format.PdfA1b.ToString(),
            Format.PdfA1b.GetFname(),
            Format.PdfA1a.ToString(),
            Format.PdfA1a.GetFname(),
            Format.values().Length,
            ReferenceEquals(Format.PdfA1b, Format.valueOf("PDF_A1B")));
        Observe(
            "error-code",
            "representative-constants",
            PreflightConstants.ErrorSyntaxCommon,
            PreflightConstants.ErrorSyntaxHeader,
            PreflightConstants.ErrorSyntaxCrossRef,
            PreflightConstants.ErrorGraphicInvalidColorSpace,
            PreflightConstants.ErrorMetadataMain,
            PreflightConstants.ErrorPdfProcessingMissing);
    }

    private static void ObserveConfiguration()
    {
        var configuration = PreflightConfiguration.CreatePdfA1BConfiguration();
        Observe(
            "configuration",
            "defaults-and-order",
            configuration.IsErrorOnMissingProcess(),
            configuration.IsLazyValidation(),
            configuration.GetMaxErrors(),
            string.Join(",", configuration.GetProcessNames()),
            string.Join(",", configuration.GetPageValidationProcessNames()),
            configuration.GetActionFact() is not null,
            configuration.GetAnnotFact() is not null,
            configuration.GetColorSpaceHelperFact() is not null,
            ReferenceEquals(configuration.GetActionFact(), configuration.GetActionFact()),
            ReferenceEquals(configuration.GetAnnotFact(), configuration.GetAnnotFact()),
            ReferenceEquals(
                configuration.GetColorSpaceHelperFact(),
                configuration.GetColorSpaceHelperFact()));

        var missing = Capture<MissingValidationProcessException>(
            () => configuration.GetInstanceOfProcess("missing-process"));
        Observe(
            "exception",
            "missing-process",
            missing is not null,
            missing?.GetProcessName(),
            missing?.Message);

        configuration.SetErrorOnMissingProcess(false);
        Observe(
            "configuration",
            "missing-process-policy",
            configuration.GetInstanceOfProcess("missing-process")
                is EmptyValidationProcess);

        var custom = new PreflightConfiguration();
        custom.ReplaceProcess("first", typeof(FirstProcess));
        custom.ReplaceProcess("second", typeof(SecondProcess));
        custom.ReplaceProcess("first", typeof(ReplacementProcess));
        custom.ReplacePageProcess("inner", typeof(FirstProcess));
        Observe(
            "configuration",
            "replace-remove-and-instantiation",
            string.Join(",", custom.GetProcessNames()),
            string.Join(",", custom.GetPageValidationProcessNames()),
            custom.GetInstanceOfProcess("first") is ReplacementProcess,
            custom.GetInstanceOfProcess("second") is SecondProcess,
            custom.GetInstanceOfProcess("inner") is FirstProcess);
        custom.RemoveProcess("first");
        custom.ReplaceProcess("first", typeof(FirstProcess));
        custom.RemovePageProcess("inner");
        Observe(
            "configuration",
            "remove-and-readd-order",
            string.Join(",", custom.GetProcessNames()),
            custom.GetPageValidationProcessNames().Count);

        custom.SetLazyValidation(true);
        custom.SetMaxErrors(37);
        Observe(
            "configuration",
            "mutable-options",
            custom.IsLazyValidation(),
            custom.GetMaxErrors());

        custom.ReplaceProcess("broken", typeof(NoDefaultConstructorProcess));
        var broken = Capture<ValidationException>(
            () => custom.GetInstanceOfProcess("broken"));
        Observe(
            "exception",
            "process-construction",
            broken is not null,
            broken?.Message,
            broken?.InnerException is not null);
    }

    private static void ObserveResultAndErrors()
    {
        var warning = new ValidationResult.ValidationError("9.9", "warning");
        warning.SetWarning(true);
        warning.SetPageNumber(2);
        var hard = new ValidationResult.ValidationError(
            PreflightConstants.ErrorSyntaxHeader,
            "bad header",
            new IOException("cause"));
        hard.SetPageNumber(4);

        var result = new ValidationResult(true);
        result.AddError(warning);
        var validAfterWarning = result.IsValid();
        result.AddErrors(new List<ValidationResult.ValidationError> { hard });
        result.AddError(null!);
        Observe(
            "result",
            "warning-hard-and-aggregation",
            validAfterWarning,
            result.IsValid(),
            result.GetErrorsList().Count,
            string.Join(",", result.GetErrorsList().Select(error => error.GetErrorCode())),
            string.Join(
                ",",
                result.GetErrorsList()
                    .Select(error => error.GetPageNumber()?.ToString(
                        CultureInfo.InvariantCulture) ?? "null")));

        var merge = new ValidationResult(true);
        var mergedWarning = new ValidationResult.ValidationError("8.8", "merged");
        mergedWarning.SetWarning(true);
        var other = new ValidationResult(true);
        other.AddError(mergedWarning);
        merge.MergeResult(other);
        merge.MergeResult(null!);
        Observe(
            "result",
            "merge",
            merge.IsValid(),
            merge.GetErrorsList().Count,
            ReferenceEquals(mergedWarning, merge.GetErrorsList()[0]));

        var sourceList = new List<ValidationResult.ValidationError> { warning };
        var listResult = new ValidationResult(sourceList);
        sourceList.Add(hard);
        Observe(
            "result",
            "list-constructor-alias",
            listResult.IsValid(),
            listResult.GetErrorsList().Count,
            ReferenceEquals(sourceList, listResult.GetErrorsList()));

        var longDetail = new string('x', 500);
        var detailed = new ValidationResult.ValidationError(
            PreflightConstants.ErrorSyntaxHeader,
            longDetail,
            new IOException("detail-cause"));
        detailed.SetPageNumber(3);
        var equal = new ValidationResult.ValidationError(
            PreflightConstants.ErrorSyntaxHeader,
            longDetail,
            new IOException("other-cause"));
        equal.SetPageNumber(3);
        Observe(
            "error",
            "details-cause-location-equality",
            detailed.GetDetails().Length,
            detailed.GetDetails().StartsWith(
                "Header Syntax error, ",
                StringComparison.Ordinal),
            ReferenceEquals(detailed.GetCause(), detailed.GetCause()),
            detailed.GetCause()?.Message,
            detailed.GetThrowable() is not null,
            !string.IsNullOrEmpty(detailed.GetThrowable()?.StackTrace),
            detailed.Equals(equal),
            detailed.GetHashCode() == equal.GetHashCode(),
            detailed.GetPageNumber());
        equal.SetWarning(true);
        Observe(
            "error",
            "warning-affects-equality",
            detailed.Equals(equal),
            equal.IsWarning());

        var validationCause = new IOException("validation-cause");
        var validationException = new ValidationException(
            "validation-message",
            validationCause,
            7);
        var syntaxResult = new ValidationResult(false);
        var syntaxException = new SyntaxValidationException(
            "syntax-message",
            validationCause,
            syntaxResult);
        Observe(
            "exception",
            "constructors",
            validationException.Message,
            validationException.GetPageNumber(),
            ReferenceEquals(validationCause, validationException.InnerException),
            syntaxException.Message,
            ReferenceEquals(syntaxResult, syntaxException.GetResult()),
            ReferenceEquals(validationCause, syntaxException.InnerException));
    }

    private static void ObservePath()
    {
        var path = new PreflightPath();
        var initialEmpty = path.IsEmpty();
        var pushedString = path.PushObject("a");
        var pushedNull = path.PushObject(null!);
        path.PushObject(6);
        path.PushObject("b");
        Observe(
            "path",
            "stack-and-types",
            initialEmpty,
            pushedString,
            pushedNull,
            path.Size(),
            path.GetClosestTypePosition(typeof(string)),
            path.GetClosestTypePosition(typeof(int)),
            path.GetPathElement<int>(1, typeof(int)),
            path.GetClosestPathElement<string>(typeof(string)),
            path.IsExpectedType(typeof(string)),
            path.IsExpectedType(typeof(object)),
            path.GetPathElement<object>(99, typeof(object)) is null,
            path.Peek());
        var popped = path.Pop();
        path.Clear();
        Observe(
            "path",
            "pop-and-clear",
            popped,
            path.IsEmpty(),
            path.Size());
    }

    private static void ObserveParsers(
        string upstreamFixtures,
        string generatedFixtures)
    {
        ObserveValidation(
            "parser-valid",
            "pdfa-with-annotations-square",
            Path.Combine(upstreamFixtures, "pdfa-with-annotations-square.pdf"),
            includeDetails: true);
        ObserveValidation(
            "parser-invalid",
            "pdfbox-3741",
            Path.Combine(upstreamFixtures, "PDFBOX-3741.pdf"),
            includeDetails: true);
        ObserveValidation(
            "parser-encrypted",
            "password-required",
            Path.Combine(generatedFixtures, "encrypted.pdf"),
            includeDetails: true);
        ObserveValidation(
            "parser-malformed",
            "bad-startxref",
            Path.Combine(generatedFixtures, "malformed.pdf"),
            includeDetails: true);
        ObserveValidation(
            "parser-truncated",
            "missing-eof",
            Path.Combine(generatedFixtures, "truncated.pdf"),
            includeDetails: true);
        ObserveValidation(
            "parser-unsupported",
            "plain-text",
            Path.Combine(generatedFixtures, "unsupported.bin"),
            includeDetails: false);
    }

    private static void ObserveDocumentAndProcesses(string upstreamFixtures)
    {
        var path = Path.Combine(upstreamFixtures, "pdfa-with-annotations-square.pdf");

        ProcessTrace.Clear();
        var ordered = new PreflightConfiguration();
        ordered.ReplaceProcess("first", typeof(FirstProcess));
        ordered.ReplaceProcess("second", typeof(SecondProcess));
        using (var parserSource = new RandomAccessReadBufferedFile(new FileInfo(path)))
        {
            var parser = new PreflightParser(parserSource);
            using var document =
                (PreflightDocument)parser.Parse(Format.PdfA1b, ordered);
            var result = document.Validate();
            var errors = document.GetValidationErrors();
            var unmodifiable = Capture<NotSupportedException>(
                () => errors.Add(new ValidationResult.ValidationError("7.7")));
            Observe(
                "process",
                "ordering-and-error-aggregation",
                string.Join(",", ProcessTrace),
                result.IsValid(),
                result.GetErrorsList().Count,
                string.Join(",", result.GetErrorsList().Select(error => error.GetErrorCode())),
                string.Join(
                    ",",
                    result.GetErrorsList()
                        .Select(error => error.GetPageNumber()?.ToString(
                            CultureInfo.InvariantCulture) ?? "null")),
                unmodifiable is not null);
        }

        ObserveLazyProcess(path, lazy: false);
        ObserveLazyProcess(path, lazy: true);

        var empty = new PreflightConfiguration();
        using var source = new RandomAccessReadBufferedFile(new FileInfo(path));
        var emptyParser = new PreflightParser(source);
        using var emptyDocument =
            (PreflightDocument)emptyParser.Parse(Format.PdfA1b, empty);
        var first = emptyDocument.Validate();
        var second = emptyDocument.Validate();
        Observe(
            "document",
            "specification-context-and-result-cache",
            ReferenceEquals(Format.PdfA1b, emptyDocument.GetSpecification()),
            ReferenceEquals(emptyDocument, emptyDocument.GetContext().GetDocument()),
            ReferenceEquals(first, second),
            first.GetErrorsList().Count);
    }

    private static void ObserveLazyProcess(string path, bool lazy)
    {
        var configuration = new PreflightConfiguration();
        configuration.SetLazyValidation(lazy);
        configuration.ReplaceProcess("lazy", typeof(LazyProcess));
        using var source = new RandomAccessReadBufferedFile(new FileInfo(path));
        var parser = new PreflightParser(source);
        using var document =
            (PreflightDocument)parser.Parse(Format.PdfA1b, configuration);
        var result = document.Validate();
        Observe(
            "process",
            lazy ? "lazy-validation" : "eager-validation",
            result.IsValid(),
            result.GetErrorsList().Count,
            result.GetErrorsList()[0].IsWarning(),
            ReferenceEquals(configuration, document.GetContext().GetConfig()));
    }

    private static void ObserveContextAndLifetime(
        string upstreamFixtures,
        string generatedFixtures)
    {
        var validPath =
            Path.Combine(upstreamFixtures, "pdfa-with-annotations-square.pdf");
        var validSource =
            new RandomAccessReadBufferedFile(new FileInfo(validPath));
        var parser = new PreflightParser(validSource);
        var document = (PreflightDocument)parser.Parse();
        var context = document.GetContext();
        var page = new PDPage();
        context.AddToProcessedSet(page);
        context.SetIccProfileAlreadySearched(true);
        context.SetCurrentPageNumber(6);
        var paged = new ValidationResult.ValidationError("7.1", "paged");
        context.AddValidationError(paged);
        var unpaged = new ValidationResult.ValidationError("7.2", "unpaged");
        context.AddValidationErrors(
            new List<ValidationResult.ValidationError> { unpaged });
        Observe(
            "context",
            "state-caches-and-page-data",
            context.GetXrefTrailerResolver() is not null,
            context.GetFileLen(),
            new FileInfo(validPath).Length,
            context.IsIccProfileAlreadySearched(),
            context.GetCurrentPageNumber(),
            context.IsInProcessedSet(page),
            !context.IsInProcessedSet(new PDPage()),
            paged.GetPageNumber(),
            unpaged.GetPageNumber() is null,
            ReferenceEquals(context.GetValidationPath(), context.GetValidationPath()));

        var wasOpenBefore = !validSource.IsClosed();
        document.Dispose();
        var closedAfterDocument = validSource.IsClosed();
        document.Dispose();
        Observe(
            "lifecycle",
            "document-owns-source",
            wasOpenBefore,
            closedAfterDocument,
            validSource.IsClosed());

        var contextSource =
            new RandomAccessReadBufferedFile(new FileInfo(validPath));
        var contextParser = new PreflightParser(contextSource);
        var contextDocument = (PreflightDocument)contextParser.Parse();
        contextDocument.GetContext().Dispose();
        contextDocument.GetContext().Dispose();
        Observe(
            "lifecycle",
            "context-closes-document",
            contextSource.IsClosed(),
            contextDocument.GetDocument().IsClosed());

        var invalidSource = new RandomAccessReadBufferedFile(
            new FileInfo(Path.Combine(generatedFixtures, "unsupported.bin")));
        var invalidParser = new PreflightParser(invalidSource);
        var syntax = Capture<SyntaxValidationException>(() => invalidParser.Parse());
        Observe(
            "lifecycle",
            "failed-parse-source-ownership",
            syntax is not null,
            syntax?.GetResult().IsValid(),
            syntax?.GetResult().GetErrorsList().Count,
            invalidSource.IsClosed());
        invalidSource.Dispose();
    }

    private static void ObserveXml(string upstreamFixtures)
    {
        var parser = new XmlResultParser();
        var valid = parser.Validate(
            new FileInfo(
                Path.Combine(upstreamFixtures, "pdfa-with-annotations-square.pdf")));
        var invalid = parser.Validate(
            new FileInfo(Path.Combine(upstreamFixtures, "PDFBOX-3741.pdf")));
        Observe(
            "xml",
            "valid-response",
            valid.Name,
            valid.GetAttribute("name"),
            valid.SelectSingleNode("isValid")?.InnerText,
            (valid.SelectSingleNode("isValid") as XmlElement)?.GetAttribute("type"),
            ParseDuration(valid) >= 0,
            valid.SelectSingleNode("errors") is null,
            valid.SelectSingleNode("exceptionThrown") is null);
        Observe(
            "xml",
            "invalid-response",
            invalid.Name,
            invalid.GetAttribute("name"),
            invalid.SelectSingleNode("isValid")?.InnerText,
            (invalid.SelectSingleNode("isValid") as XmlElement)?.GetAttribute("type"),
            (invalid.SelectSingleNode("errors") as XmlElement)?.GetAttribute("count"),
            invalid.SelectSingleNode("errors/error/code")?.InnerText,
            invalid.SelectSingleNode("errors/error/details")?.InnerText,
            invalid.SelectSingleNode("errors/error/page")?.InnerText,
            ParseDuration(invalid) >= 0,
            invalid.SelectSingleNode("exceptionThrown") is null);
    }

    private static void ObserveValidationProcesses(
        string upstreamFixtures,
        string generatedFixtures)
    {
        var valid = Path.Combine(upstreamFixtures, "pdfa-with-annotations-square.pdf");
        ObserveValidation(
            "rule-selection", "pdf-a1b", valid, Format.PdfA1b, includeDetails: true);
        ObserveValidation(
            "rule-selection", "pdf-a1a", valid, Format.PdfA1a, includeDetails: true);
        ObserveValidation(
            "catalog",
            "language-and-optional-content",
            Path.Combine(generatedFixtures, "catalog-invalid.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "file-structure",
            "bad-startxref",
            Path.Combine(generatedFixtures, "malformed.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "cross-reference",
            "xref-stream",
            Path.Combine(generatedFixtures, "xref-stream.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "trailer",
            "missing-id",
            Path.Combine(generatedFixtures, "trailer-missing-id.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "page",
            "transparency-group",
            Path.Combine(generatedFixtures, "page-transparency.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "content-stream",
            "invalid-rendering-intent",
            Path.Combine(generatedFixtures, "content-stream.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "graphics-state",
            "transparency-and-transfer",
            Path.Combine(generatedFixtures, "graphics-state.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "color",
            "device-gray-without-profile",
            Path.Combine(upstreamFixtures, "PDFBOX-3741.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "font",
            "unembedded-standard-font",
            Path.Combine(generatedFixtures, "font-unembedded.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "transparency",
            "page-group",
            Path.Combine(generatedFixtures, "page-transparency.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "image-xobject",
            "invalid-image-dictionary",
            Path.Combine(generatedFixtures, "image-xobject.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "annotation",
            "forbidden-subtype",
            Path.Combine(generatedFixtures, "annotation-invalid.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "action",
            "forbidden-launch",
            Path.Combine(generatedFixtures, "action-forbidden.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "form",
            "need-appearances",
            Path.Combine(generatedFixtures, "form-invalid.pdf"),
            Format.PdfA1b,
            includeDetails: true);

        var metadata = Path.Combine(
            upstreamFixtures,
            "org",
            "apache",
            "pdfbox",
            "preflight",
            "metadata");
        ObserveValidation(
            "metadata",
            "trailing-nul-valid",
            Path.Combine(metadata, "PDFAMetaDataValidationTestTrailingNul.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "metadata",
            "trailing-spaces-invalid",
            Path.Combine(metadata, "PDFAMetaDataValidationTestTrailingSpaces.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "xmp",
            "middle-control-character",
            Path.Combine(metadata, "PDFAMetaDataValidationTestMiddleControlChar.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "xmp",
            "middle-nul",
            Path.Combine(metadata, "PDFAMetaDataValidationTestMiddleNul.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "xmp",
            "trailing-control-character",
            Path.Combine(metadata, "PDFAMetaDataValidationTestTrailingControlChar.pdf"),
            Format.PdfA1b,
            includeDetails: true);

        ObserveValidation(
            "output-intent",
            "invalid-icc-profile",
            Path.Combine(generatedFixtures, "output-intent-invalid-icc.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "icc",
            "valid-srgb-profile",
            valid,
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "icc",
            "invalid-profile",
            Path.Combine(generatedFixtures, "output-intent-invalid-icc.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "embedded-file",
            "catalog-name-tree-and-file-specification",
            Path.Combine(generatedFixtures, "embedded-file.pdf"),
            Format.PdfA1b,
            includeDetails: true);
        ObserveValidation(
            "logical-structure",
            "upstream-pdf-a1a-selection",
            valid,
            Format.PdfA1a,
            includeDetails: true);
    }

    private static long ParseDuration(XmlElement element) =>
        long.Parse(
            element.SelectSingleNode("executionTimeMS")?.InnerText
                ?? throw new InvalidDataException("Missing executionTimeMS"),
            CultureInfo.InvariantCulture);

    private static void ObserveValidation(
        string family,
        string id,
        string path,
        bool includeDetails)
    {
        var result = PreflightParser.Validate(new FileInfo(path));
        ObserveValidationResult(family, id, result, includeDetails);
    }

    private static void ObserveValidation(
        string family,
        string id,
        string path,
        Format format,
        bool includeDetails)
    {
        ValidationResult result;
        using (var source = new RandomAccessReadBufferedFile(new FileInfo(path)))
        {
            var parser = new PreflightParser(source);
            try
            {
                using var document = (PreflightDocument)parser.Parse(format);
                result = document.Validate();
            }
            catch (SyntaxValidationException syntax)
            {
                result = syntax.GetResult();
            }
        }
        ObserveValidationResult(family, id, result, includeDetails);
    }

    private static void ObserveValidationResult(
        string family,
        string id,
        ValidationResult result,
        bool includeDetails)
    {
        var errors = result.GetErrorsList();
        Observe(
            family,
            id,
            result.IsValid(),
            errors.Count,
            string.Join(",", errors.Select(error => error.GetErrorCode())),
            string.Join(
                ",",
                errors.Select(error => error.IsWarning().ToString().ToLowerInvariant())),
            string.Join(
                ",",
                errors.Select(error => error.GetPageNumber()?.ToString(
                    CultureInfo.InvariantCulture) ?? "null")),
            includeDetails
                ? string.Join(";", errors.Select(error => error.GetDetails()))
                : string.Join(
                    ",",
                    errors.Select(
                        error =>
                            $"{error.GetDetails().Contains("offset", StringComparison.OrdinalIgnoreCase).ToString().ToLowerInvariant()}:{(error.GetCause() is not null).ToString().ToLowerInvariant()}")));
    }

    private static TException? Capture<TException>(Action action)
        where TException : System.Exception
    {
        try
        {
            action();
            return null;
        }
        catch (TException exception)
        {
            return exception;
        }
    }

    private static void Observe(string family, string id, params object?[] values)
    {
        var rendered = string.Join("|", values.Select(Render));
        Observations.Add($"{family}\t{id}\t{rendered}");
    }

    private static string Render(object? value)
    {
        var text = value switch
        {
            null => "null",
            bool boolean => boolean ? "true" : "false",
            IFormattable formattable => formattable.ToString(
                null,
                CultureInfo.InvariantCulture),
            _ => value.ToString(),
        };
        return (text ?? "null")
            .Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal);
    }

    public sealed class FirstProcess : ValidationProcess
    {
        public void Validate(PreflightContext context)
        {
            ProcessTrace.Add("first");
            context.SetCurrentPageNumber(4);
            var warning = new ValidationResult.ValidationError("9.1", "first");
            warning.SetWarning(true);
            context.AddValidationError(warning);
        }
    }

    public sealed class SecondProcess : ValidationProcess
    {
        public void Validate(PreflightContext context)
        {
            ProcessTrace.Add("second");
            context.AddValidationError(
                new ValidationResult.ValidationError("9.2", "second"));
        }
    }

    public sealed class ReplacementProcess : ValidationProcess
    {
        public void Validate(PreflightContext context)
        {
            ProcessTrace.Add("replacement");
        }
    }

    public sealed class LazyProcess : ValidationProcess
    {
        public void Validate(PreflightContext context)
        {
            var error = new ValidationResult.ValidationError("9.3", "lazy");
            error.SetWarning(context.GetConfig().IsLazyValidation());
            context.AddValidationError(error);
        }
    }

    public sealed class NoDefaultConstructorProcess : ValidationProcess
    {
        public NoDefaultConstructorProcess(string value)
        {
            _ = value;
        }

        public void Validate(PreflightContext context)
        {
            _ = context;
        }
    }
}
