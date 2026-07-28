/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.preflight.Format;
import org.apache.pdfbox.preflight.PreflightDocument;
import org.apache.pdfbox.preflight.ValidationResult;
import org.apache.pdfbox.preflight.ValidationResult.ValidationError;
import org.apache.pdfbox.preflight.exception.SyntaxValidationException;
import org.apache.pdfbox.preflight.parser.PreflightParser;

/**
 * Synchronized reviewed PDFBox baseline oracle for the checksum-pinned Preflight corpus.
 */
public final class PreflightCorpusOracle
{
    private static final String MANIFEST_MAGIC =
            "DRIPSHARP_PDFCUBE_PREFLIGHT_CORPUS_MANIFEST_V1";
    private static final String RESULT_MAGIC =
            "DRIPSHARP_PDFCUBE_PREFLIGHT_CORPUS_RESULTS_V1";
    private static final String[] RESULT_COLUMNS = {
        "case-id", "origin", "format", "expected-outcome", "input-sha256",
        "status", "valid", "error-count", "error-codes-base64",
        "warnings-base64", "pages-base64", "details-base64", "source-closed",
        "document-closed", "diagnostic-base64"
    };

    private PreflightCorpusOracle()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "Expected execution manifest, staged corpus, and output.");
        }
        Path manifest = Paths.get(args[0]).toAbsolutePath().normalize();
        Path corpus = Paths.get(args[1]).toAbsolutePath().normalize();
        Path output = Paths.get(args[2]).toAbsolutePath().normalize();
        List<CorpusCase> cases = readManifest(manifest);
        List<String> lines = new ArrayList<>();
        lines.add(RESULT_MAGIC);
        lines.add("columns\t" + String.join("\t", RESULT_COLUMNS));
        for (CorpusCase corpusCase : cases)
        {
            lines.add(runCase(corpus, corpusCase).render());
        }
        Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static List<CorpusCase> readManifest(Path manifest) throws Exception
    {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !MANIFEST_MAGIC.equals(lines.get(0)))
        {
            throw new IllegalArgumentException("Corpus manifest marker is invalid");
        }
        String expectedColumns =
                "columns\tcase-id\tstaged-file\tinput-sha256\tformat\texpected-outcome";
        if (!expectedColumns.equals(lines.get(1)))
        {
            throw new IllegalArgumentException("Corpus manifest columns are invalid");
        }
        List<CorpusCase> result = new ArrayList<>();
        for (int index = 2; index < lines.size(); index++)
        {
            String[] fields = lines.get(index).split("\t", -1);
            if (fields.length != 6 || !"case".equals(fields[0]))
            {
                throw new IllegalArgumentException(
                        "Malformed corpus manifest row " + (index + 1));
            }
            result.add(new CorpusCase(
                    fields[1], fields[2], fields[3], fields[4], fields[5]));
        }
        if (result.isEmpty())
        {
            throw new IllegalArgumentException("Corpus manifest has no cases");
        }
        return result;
    }

    private static CorpusResult runCase(Path corpus, CorpusCase corpusCase)
    {
        Path input = corpus.resolve(corpusCase.stagedFile()).normalize();
        if (!input.startsWith(corpus))
        {
            return CorpusResult.failure(
                    corpusCase, "CRASH", "Corpus path escaped its staging root");
        }
        try
        {
            String actualHash = sha256(input);
            if (!corpusCase.inputSha256().equals(actualHash))
            {
                return CorpusResult.failure(
                        corpusCase,
                        "CRASH",
                        "Staged input checksum mismatch: " + actualHash);
            }
        }
        catch (Exception error)
        {
            return CorpusResult.failure(
                    corpusCase, "CRASH", normalizeDiagnostic(error));
        }

        RandomAccessReadBufferedFile source = null;
        PreflightDocument document = null;
        ValidationResult validation = null;
        Throwable failure = null;
        try
        {
            source = new RandomAccessReadBufferedFile(new File(input.toString()));
            PreflightParser parser = new PreflightParser(source);
            try
            {
                document = (PreflightDocument) parser.parse(
                        parseFormat(corpusCase.format()));
                validation = document.validate();
            }
            catch (SyntaxValidationException syntax)
            {
                validation = syntax.getResult();
            }
        }
        catch (Throwable error)
        {
            failure = error;
        }
        finally
        {
            try
            {
                if (document != null)
                {
                    document.close();
                }
                else if (source != null)
                {
                    source.close();
                }
            }
            catch (Throwable closeError)
            {
                if (failure == null)
                {
                    failure = closeError;
                }
            }
        }

        boolean sourceClosed = source == null || source.isClosed();
        String documentClosed =
                document == null
                        ? "na"
                        : Boolean.toString(document.getDocument().isClosed());
        if (failure != null)
        {
            return CorpusResult.failure(
                    corpusCase,
                    "CRASH",
                    normalizeDiagnostic(failure),
                    sourceClosed,
                    documentClosed);
        }
        if (validation == null)
        {
            return CorpusResult.failure(
                    corpusCase,
                    "CRASH",
                    "Validation completed without a result",
                    sourceClosed,
                    documentClosed);
        }
        if (!sourceClosed || "false".equals(documentClosed))
        {
            return CorpusResult.failure(
                    corpusCase,
                    "LEAK",
                    "Preflight input or document remained open",
                    sourceClosed,
                    documentClosed);
        }

        List<ValidationError> errors = validation.getErrorsList();
        return new CorpusResult(
                corpusCase,
                "PASS",
                Boolean.toString(validation.isValid()),
                Integer.toString(errors.size()),
                join(errors, ValidationError::getErrorCode),
                join(errors, error -> Boolean.toString(error.isWarning())),
                join(errors, error -> error.getPageNumber() == null
                        ? "null" : error.getPageNumber().toString()),
                join(errors, error -> normalize(error.getDetails())),
                Boolean.toString(sourceClosed),
                documentClosed,
                "");
    }

    private static Format parseFormat(String value)
    {
        if ("pdf-a1a".equals(value))
        {
            return Format.PDF_A1A;
        }
        if ("pdf-a1b".equals(value))
        {
            return Format.PDF_A1B;
        }
        throw new IllegalArgumentException("Unknown PDF/A format: " + value);
    }

    private static String join(
            List<ValidationError> errors,
            java.util.function.Function<ValidationError, String> projection)
    {
        return errors.stream().map(projection).collect(Collectors.joining(";"));
    }

    private static String normalize(String value)
    {
        if (value == null)
        {
            return "null";
        }
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalizeDiagnostic(Throwable error)
    {
        String message = error.getMessage();
        return error.getClass().getName() + ": "
                + (message == null ? "" : normalize(message));
    }

    private static String sha256(Path input) throws Exception
    {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(input));
        StringBuilder result = new StringBuilder();
        for (byte value : digest)
        {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String encode(String value)
    {
        return Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CorpusCase
    {
        private final String id;
        private final String stagedFile;
        private final String inputSha256;
        private final String format;
        private final String expectedOutcome;

        CorpusCase(
                String id,
                String stagedFile,
                String inputSha256,
                String format,
                String expectedOutcome)
        {
            this.id = id;
            this.stagedFile = stagedFile;
            this.inputSha256 = inputSha256;
            this.format = format;
            this.expectedOutcome = expectedOutcome;
        }

        String id()
        {
            return id;
        }

        String stagedFile()
        {
            return stagedFile;
        }

        String inputSha256()
        {
            return inputSha256;
        }

        String format()
        {
            return format;
        }

        String expectedOutcome()
        {
            return expectedOutcome;
        }
    }

    private static final class CorpusResult
    {
        private final CorpusCase corpusCase;
        private final String status;
        private final String valid;
        private final String errorCount;
        private final String errorCodes;
        private final String warnings;
        private final String pages;
        private final String details;
        private final String sourceClosed;
        private final String documentClosed;
        private final String diagnostic;

        CorpusResult(
                CorpusCase corpusCase,
                String status,
                String valid,
                String errorCount,
                String errorCodes,
                String warnings,
                String pages,
                String details,
                String sourceClosed,
                String documentClosed,
                String diagnostic)
        {
            this.corpusCase = corpusCase;
            this.status = status;
            this.valid = valid;
            this.errorCount = errorCount;
            this.errorCodes = errorCodes;
            this.warnings = warnings;
            this.pages = pages;
            this.details = details;
            this.sourceClosed = sourceClosed;
            this.documentClosed = documentClosed;
            this.diagnostic = diagnostic;
        }

        static CorpusResult failure(
                CorpusCase corpusCase,
                String status,
                String diagnostic)
        {
            return failure(corpusCase, status, diagnostic, true, "na");
        }

        static CorpusResult failure(
                CorpusCase corpusCase,
                String status,
                String diagnostic,
                boolean sourceClosed,
                String documentClosed)
        {
            return new CorpusResult(
                    corpusCase,
                    status,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    Boolean.toString(sourceClosed),
                    documentClosed,
                    diagnostic);
        }

        String render()
        {
            return String.join(
                    "\t",
                    "case",
                    corpusCase.id(),
                    "upstream-java",
                    corpusCase.format(),
                    corpusCase.expectedOutcome(),
                    corpusCase.inputSha256(),
                    status,
                    valid,
                    errorCount,
                    encode(errorCodes),
                    encode(warnings),
                    encode(pages),
                    encode(details),
                    sourceClosed,
                    documentClosed,
                    encode(diagnostic));
        }
    }
}
