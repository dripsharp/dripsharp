import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.preflight.Format;
import org.apache.pdfbox.preflight.PreflightConfiguration;
import org.apache.pdfbox.preflight.PreflightConstants;
import org.apache.pdfbox.preflight.PreflightContext;
import org.apache.pdfbox.preflight.PreflightDocument;
import org.apache.pdfbox.preflight.PreflightPath;
import org.apache.pdfbox.preflight.ValidationResult;
import org.apache.pdfbox.preflight.ValidationResult.ValidationError;
import org.apache.pdfbox.preflight.exception.MissingValidationProcessException;
import org.apache.pdfbox.preflight.exception.SyntaxValidationException;
import org.apache.pdfbox.preflight.exception.ValidationException;
import org.apache.pdfbox.preflight.parser.PreflightParser;
import org.apache.pdfbox.preflight.parser.XmlResultParser;
import org.apache.pdfbox.preflight.process.EmptyValidationProcess;
import org.apache.pdfbox.preflight.process.ValidationProcess;
import org.w3c.dom.Element;

public final class PreflightExecutionOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();
    private static final List<String> PROCESS_TRACE = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "Expected output trace, generated fixture directory, and repository root.");
        }

        Path output = Paths.get(args[0]);
        Path fixtureDirectory = Paths.get(args[1]);
        Path repositoryRoot = Paths.get(args[2]);
        Path upstreamFixtures = repositoryRoot.resolve(
                "research/pdfbox/preflight/src/test/resources");
        Files.createDirectories(fixtureDirectory);
        writeGeneratedFixtures(upstreamFixtures, fixtureDirectory);

        observeFormatAndConstants();
        observeConfiguration();
        observeResultAndErrors();
        observePath();
        observeParsers(upstreamFixtures, fixtureDirectory);
        observeDocumentAndProcesses(upstreamFixtures);
        observeContextAndLifetime(upstreamFixtures, fixtureDirectory);
        observeXml(upstreamFixtures);
        observeValidationProcesses(upstreamFixtures, fixtureDirectory);

        Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void writeGeneratedFixtures(
            Path upstreamFixtures, Path fixtureDirectory) throws Exception
    {
        Path encrypted = fixtureDirectory.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument())
        {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(encrypted.toFile());
        }

        Files.write(
                fixtureDirectory.resolve("malformed.pdf"),
                ("%PDF-1.4\n%âãÏÓ\n"
                        + "1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                        + "trailer\n<< /Root 1 0 R >>\n"
                        + "startxref\n999999\n%%EOF\n")
                        .getBytes(StandardCharsets.ISO_8859_1));

        byte[] valid = Files.readAllBytes(
                upstreamFixtures.resolve("pdfa-with-annotations-square.pdf"));
        Files.write(
                fixtureDirectory.resolve("truncated.pdf"),
                Arrays.copyOf(valid, valid.length / 2));
        Files.write(
                fixtureDirectory.resolve("unsupported.bin"),
                "not a PDF".getBytes(StandardCharsets.UTF_8));

        writeValidationFixtures(upstreamFixtures, fixtureDirectory);
    }

    private static void writeValidationFixtures(
            Path upstreamFixtures, Path fixtureDirectory) throws Exception
    {
        Path valid = upstreamFixtures.resolve("pdfa-with-annotations-square.pdf");

        mutatePdf(valid, fixtureDirectory.resolve("catalog-invalid.pdf"), document -> {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            catalog.setString(COSName.LANG, "not a valid RFC 1766 language!");
            catalog.setItem(COSName.getPDFName("OCProperties"), new COSDictionary());
        });

        String trailerPdf = new String(
                Files.readAllBytes(valid),
                StandardCharsets.ISO_8859_1);
        String withoutId = trailerPdf.replaceFirst(
                "/ID\\s*\\[\\s*<[^>]*>\\s*<[^>]*>\\s*\\]",
                "");
        if (withoutId.equals(trailerPdf))
        {
            throw new IllegalStateException("Unable to remove the validation fixture trailer ID");
        }
        Files.write(
                fixtureDirectory.resolve("trailer-missing-id.pdf"),
                withoutId.getBytes(StandardCharsets.ISO_8859_1));

        try (PDDocument document = Loader.loadPDF(valid.toFile()))
        {
            document.save(
                    fixtureDirectory.resolve("xref-stream.pdf").toFile(),
                    CompressParameters.DEFAULT_COMPRESSION);
        }

        mutatePdf(valid, fixtureDirectory.resolve("page-transparency.pdf"), document -> {
            COSDictionary group = new COSDictionary();
            group.setItem(COSName.TYPE, COSName.GROUP);
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            firstPage(document).getCOSObject().setItem(COSName.GROUP, group);
        });

        mutatePdf(valid, fixtureDirectory.resolve("content-stream.pdf"), document -> {
            replacePageContent(document, "/Bogus ri\n");
        });

        mutatePdf(valid, fixtureDirectory.resolve("graphics-state.pdf"), document -> {
            COSDictionary state = new COSDictionary();
            state.setItem(COSName.SMASK, COSName.getPDFName("Luminosity"));
            state.setItem(COSName.CA, new COSFloat(0.5f));
            state.setItem(COSName.getPDFName("ca"), new COSFloat(0.5f));
            state.setItem(COSName.BM, COSName.getPDFName("Multiply"));
            state.setItem(COSName.TR, COSName.getPDFName("Identity"));
            state.setItem(COSName.TR2, COSName.getPDFName("Identity"));
            COSDictionary states = new COSDictionary();
            states.setItem(COSName.getPDFName("GSbad"), state);
            resources(firstPage(document))
                    .getCOSObject()
                    .setItem(COSName.EXT_G_STATE, states);
        });

        mutatePdf(valid, fixtureDirectory.resolve("font-unembedded.pdf"), document -> {
            PDResources resources = resources(firstPage(document));
            resources.put(
                    COSName.getPDFName("Fbad"),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            replacePageContent(document, "BT /Fbad 12 Tf (A) Tj ET\n");
        });

        mutatePdf(valid, fixtureDirectory.resolve("image-xobject.pdf"), document -> {
            COSStream image = document.getDocument().createCOSStream();
            image.setItem(COSName.TYPE, COSName.XOBJECT);
            image.setItem(COSName.SUBTYPE, COSName.IMAGE);
            image.setItem(COSName.WIDTH, COSInteger.ONE);
            image.setItem(COSName.HEIGHT, COSInteger.ONE);
            image.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(16));
            image.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            image.setItem(COSName.INTERPOLATE, COSBoolean.TRUE);
            image.setItem(COSName.INTENT, COSName.getPDFName("Bogus"));
            image.setItem(COSName.SMASK, COSName.getPDFName("Luminosity"));
            try (OutputStream output = image.createRawOutputStream())
            {
                output.write(new byte[] { 0, 0, 0, 0, 0, 0 });
            }
            COSDictionary xobjects = new COSDictionary();
            xobjects.setItem(COSName.getPDFName("ImBad"), image);
            resources(firstPage(document))
                    .getCOSObject()
                    .setItem(COSName.XOBJECT, xobjects);
        });

        mutatePdf(valid, fixtureDirectory.resolve("annotation-invalid.pdf"), document -> {
            COSDictionary annotation = new COSDictionary();
            annotation.setItem(COSName.TYPE, COSName.ANNOT);
            annotation.setItem(COSName.SUBTYPE, COSName.getPDFName("Movie"));
            annotation.setItem(COSName.RECT, rectangle(10, 10, 30, 30));
            annotation.setInt(COSName.F, 0);
            COSArray annotations =
                    firstPage(document).getCOSObject().getCOSArray(COSName.ANNOTS);
            if (annotations == null)
            {
                annotations = new COSArray();
                firstPage(document).getCOSObject().setItem(COSName.ANNOTS, annotations);
            }
            annotations.add(annotation);
        });

        mutatePdf(valid, fixtureDirectory.resolve("action-forbidden.pdf"), document -> {
            COSDictionary action = new COSDictionary();
            action.setItem(COSName.TYPE, COSName.getPDFName("Action"));
            action.setItem(COSName.S, COSName.getPDFName("Launch"));
            document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, action);
        });

        mutatePdf(valid, fixtureDirectory.resolve("form-invalid.pdf"), document -> {
            COSDictionary form = new COSDictionary();
            form.setBoolean(COSName.NEED_APPEARANCES, true);
            form.setItem(COSName.FIELDS, new COSArray());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
        });

        mutatePdf(valid, fixtureDirectory.resolve("output-intent-invalid-icc.pdf"), document -> {
            COSArray outputIntents = document.getDocumentCatalog()
                    .getCOSObject()
                    .getCOSArray(COSName.OUTPUT_INTENTS);
            COSDictionary outputIntent = (COSDictionary) outputIntents.getObject(0);
            COSStream profile =
                    (COSStream) outputIntent.getDictionaryObject(COSName.DEST_OUTPUT_PROFILE);
            profile.removeItem(COSName.FILTER);
            try (OutputStream output = profile.createRawOutputStream())
            {
                output.write("not an ICC profile".getBytes(StandardCharsets.US_ASCII));
            }
        });

        mutatePdf(valid, fixtureDirectory.resolve("embedded-file.pdf"), document -> {
            COSDictionary embedded = new COSDictionary();
            embedded.setItem(COSName.TYPE, COSName.getPDFName("EmbeddedFile"));
            COSDictionary embeddedFiles = new COSDictionary();
            embeddedFiles.setItem(COSName.F, embedded);
            COSDictionary fileSpec = new COSDictionary();
            fileSpec.setItem(COSName.TYPE, COSName.FILESPEC);
            fileSpec.setString(COSName.F, "payload.bin");
            fileSpec.setItem(COSName.EF, embeddedFiles);
            COSDictionary nameTree = new COSDictionary();
            COSArray names = new COSArray();
            names.add(new COSString("payload.bin"));
            names.add(fileSpec);
            nameTree.setItem(COSName.NAMES, names);
            COSDictionary catalogNames = new COSDictionary();
            catalogNames.setItem(COSName.EMBEDDED_FILES, nameTree);
            document.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, catalogNames);
        });
    }

    private static void mutatePdf(
            Path source, Path destination, DocumentMutation mutation) throws Exception
    {
        try (PDDocument document = Loader.loadPDF(source.toFile()))
        {
            mutation.apply(document);
            document.save(destination.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }

    private static PDPage firstPage(PDDocument document)
    {
        return document.getPage(0);
    }

    private static PDResources resources(PDPage page)
    {
        PDResources resources = page.getResources();
        if (resources == null)
        {
            resources = new PDResources();
            page.setResources(resources);
        }
        return resources;
    }

    private static void replacePageContent(PDDocument document, String content)
            throws IOException
    {
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream output = stream.createRawOutputStream())
        {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
        }
        firstPage(document).getCOSObject().setItem(COSName.CONTENTS, stream);
    }

    private static COSArray rectangle(float lowerX, float lowerY, float upperX, float upperY)
    {
        return new PDRectangle(lowerX, lowerY, upperX - lowerX, upperY - lowerY)
                .getCOSArray();
    }

    private static void observeFormatAndConstants()
    {
        observe(
                "format",
                "values",
                Format.PDF_A1B.name(),
                Format.PDF_A1B.getFname(),
                Format.PDF_A1A.name(),
                Format.PDF_A1A.getFname(),
                Format.values().length,
                Format.PDF_A1B == Format.valueOf("PDF_A1B"));
        observe(
                "error-code",
                "representative-constants",
                PreflightConstants.ERROR_SYNTAX_COMMON,
                PreflightConstants.ERROR_SYNTAX_HEADER,
                PreflightConstants.ERROR_SYNTAX_CROSS_REF,
                PreflightConstants.ERROR_GRAPHIC_INVALID_COLOR_SPACE,
                PreflightConstants.ERROR_METADATA_MAIN,
                PreflightConstants.ERROR_PDF_PROCESSING_MISSING);
    }

    private static void observeConfiguration() throws Exception
    {
        PreflightConfiguration configuration =
                PreflightConfiguration.createPdfA1BConfiguration();
        observe(
                "configuration",
                "defaults-and-order",
                configuration.isErrorOnMissingProcess(),
                configuration.isLazyValidation(),
                configuration.getMaxErrors(),
                String.join(",", configuration.getProcessNames()),
                String.join(",", configuration.getPageValidationProcessNames()),
                configuration.getActionFact() != null,
                configuration.getAnnotFact() != null,
                configuration.getColorSpaceHelperFact() != null,
                configuration.getActionFact() == configuration.getActionFact(),
                configuration.getAnnotFact() == configuration.getAnnotFact(),
                configuration.getColorSpaceHelperFact()
                        == configuration.getColorSpaceHelperFact());

        MissingValidationProcessException missing =
                capture(
                        MissingValidationProcessException.class,
                        () -> configuration.getInstanceOfProcess("missing-process"));
        observe(
                "exception",
                "missing-process",
                missing != null,
                missing == null ? null : missing.getProcessName(),
                missing == null ? null : missing.getMessage());

        configuration.setErrorOnMissingProcess(false);
        observe(
                "configuration",
                "missing-process-policy",
                configuration.getInstanceOfProcess("missing-process")
                        instanceof EmptyValidationProcess);

        PreflightConfiguration custom = new PreflightConfiguration();
        custom.replaceProcess("first", FirstProcess.class);
        custom.replaceProcess("second", SecondProcess.class);
        custom.replaceProcess("first", ReplacementProcess.class);
        custom.replacePageProcess("inner", FirstProcess.class);
        observe(
                "configuration",
                "replace-remove-and-instantiation",
                String.join(",", custom.getProcessNames()),
                String.join(",", custom.getPageValidationProcessNames()),
                custom.getInstanceOfProcess("first") instanceof ReplacementProcess,
                custom.getInstanceOfProcess("second") instanceof SecondProcess,
                custom.getInstanceOfProcess("inner") instanceof FirstProcess);
        custom.removeProcess("first");
        custom.replaceProcess("first", FirstProcess.class);
        custom.removePageProcess("inner");
        observe(
                "configuration",
                "remove-and-readd-order",
                String.join(",", custom.getProcessNames()),
                custom.getPageValidationProcessNames().size());

        custom.setLazyValidation(true);
        custom.setMaxErrors(37);
        observe(
                "configuration",
                "mutable-options",
                custom.isLazyValidation(),
                custom.getMaxErrors());

        custom.replaceProcess("broken", NoDefaultConstructorProcess.class);
        ValidationException broken =
                capture(
                        ValidationException.class,
                        () -> custom.getInstanceOfProcess("broken"));
        observe(
                "exception",
                "process-construction",
                broken != null,
                broken == null ? null : broken.getMessage(),
                broken != null && broken.getCause() != null);
    }

    private static void observeResultAndErrors()
    {
        ValidationError warning = new ValidationError("9.9", "warning");
        warning.setWarning(true);
        warning.setPageNumber(2);
        ValidationError hard = new ValidationError(
                PreflightConstants.ERROR_SYNTAX_HEADER,
                "bad header",
                new IOException("cause"));
        hard.setPageNumber(4);

        ValidationResult result = new ValidationResult(true);
        result.addError(warning);
        boolean validAfterWarning = result.isValid();
        result.addErrors(Arrays.asList(hard));
        result.addError(null);
        observe(
                "result",
                "warning-hard-and-aggregation",
                validAfterWarning,
                result.isValid(),
                result.getErrorsList().size(),
                result.getErrorsList().stream()
                        .map(ValidationError::getErrorCode)
                        .collect(Collectors.joining(",")),
                result.getErrorsList().stream()
                        .map(error -> error.getPageNumber() == null
                                ? "null" : error.getPageNumber().toString())
                        .collect(Collectors.joining(",")));

        ValidationResult merge = new ValidationResult(true);
        ValidationError mergedWarning = new ValidationError("8.8", "merged");
        mergedWarning.setWarning(true);
        ValidationResult other = new ValidationResult(true);
        other.addError(mergedWarning);
        merge.mergeResult(other);
        merge.mergeResult(null);
        observe(
                "result",
                "merge",
                merge.isValid(),
                merge.getErrorsList().size(),
                mergedWarning == merge.getErrorsList().get(0));

        List<ValidationError> sourceList = new ArrayList<>();
        sourceList.add(warning);
        ValidationResult listResult = new ValidationResult(sourceList);
        sourceList.add(hard);
        observe(
                "result",
                "list-constructor-alias",
                listResult.isValid(),
                listResult.getErrorsList().size(),
                sourceList == listResult.getErrorsList());

        String longDetail = repeat('x', 500);
        ValidationError detailed = new ValidationError(
                PreflightConstants.ERROR_SYNTAX_HEADER,
                longDetail,
                new IOException("detail-cause"));
        detailed.setPageNumber(3);
        ValidationError equal = new ValidationError(
                PreflightConstants.ERROR_SYNTAX_HEADER,
                longDetail,
                new IOException("other-cause"));
        equal.setPageNumber(3);
        observe(
                "error",
                "details-cause-location-equality",
                detailed.getDetails().length(),
                detailed.getDetails().startsWith("Header Syntax error, "),
                detailed.getCause() == detailed.getCause(),
                detailed.getCause().getMessage(),
                detailed.getThrowable() != null,
                detailed.getThrowable().getStackTrace().length > 0,
                detailed.equals(equal),
                detailed.hashCode() == equal.hashCode(),
                detailed.getPageNumber());
        equal.setWarning(true);
        observe(
                "error",
                "warning-affects-equality",
                detailed.equals(equal),
                equal.isWarning());

        IOException validationCause = new IOException("validation-cause");
        ValidationException validationException = new ValidationException(
                "validation-message",
                validationCause,
                7);
        ValidationResult syntaxResult = new ValidationResult(false);
        SyntaxValidationException syntaxException = new SyntaxValidationException(
                "syntax-message",
                validationCause,
                syntaxResult);
        observe(
                "exception",
                "constructors",
                validationException.getMessage(),
                validationException.getPageNumber(),
                validationCause == validationException.getCause(),
                syntaxException.getMessage(),
                syntaxResult == syntaxException.getResult(),
                validationCause == syntaxException.getCause());
    }

    private static void observePath()
    {
        PreflightPath path = new PreflightPath();
        boolean initialEmpty = path.isEmpty();
        boolean pushedString = path.pushObject("a");
        boolean pushedNull = path.pushObject(null);
        path.pushObject(6);
        path.pushObject("b");
        observe(
                "path",
                "stack-and-types",
                initialEmpty,
                pushedString,
                pushedNull,
                path.size(),
                path.getClosestTypePosition(String.class),
                path.getClosestTypePosition(Integer.class),
                path.getPathElement(1, Integer.class),
                path.getClosestPathElement(String.class),
                path.isExpectedType(String.class),
                path.isExpectedType(Object.class),
                path.getPathElement(99, Object.class) == null,
                path.peek());
        Object popped = path.pop();
        path.clear();
        observe(
                "path",
                "pop-and-clear",
                popped,
                path.isEmpty(),
                path.size());
    }

    private static void observeParsers(
            Path upstreamFixtures, Path generatedFixtures) throws Exception
    {
        observeValidation(
                "parser-valid",
                "pdfa-with-annotations-square",
                upstreamFixtures.resolve("pdfa-with-annotations-square.pdf"),
                true);
        observeValidation(
                "parser-invalid",
                "pdfbox-3741",
                upstreamFixtures.resolve("PDFBOX-3741.pdf"),
                true);
        observeValidation(
                "parser-encrypted",
                "password-required",
                generatedFixtures.resolve("encrypted.pdf"),
                true);
        observeValidation(
                "parser-malformed",
                "bad-startxref",
                generatedFixtures.resolve("malformed.pdf"),
                true);
        observeValidation(
                "parser-truncated",
                "missing-eof",
                generatedFixtures.resolve("truncated.pdf"),
                true);
        observeValidation(
                "parser-unsupported",
                "plain-text",
                generatedFixtures.resolve("unsupported.bin"),
                false);
    }

    private static void observeDocumentAndProcesses(Path upstreamFixtures)
            throws Exception
    {
        File path = upstreamFixtures.resolve(
                "pdfa-with-annotations-square.pdf").toFile();

        PROCESS_TRACE.clear();
        PreflightConfiguration ordered = new PreflightConfiguration();
        ordered.replaceProcess("first", FirstProcess.class);
        ordered.replaceProcess("second", SecondProcess.class);
        try (RandomAccessReadBufferedFile parserSource =
                     new RandomAccessReadBufferedFile(path))
        {
            PreflightParser parser = new PreflightParser(parserSource);
            try (PreflightDocument document =
                         (PreflightDocument) parser.parse(Format.PDF_A1B, ordered))
            {
                ValidationResult result = document.validate();
                List<ValidationError> errors = document.getValidationErrors();
                UnsupportedOperationException unmodifiable =
                        capture(
                                UnsupportedOperationException.class,
                                () -> errors.add(new ValidationError("7.7")));
                observe(
                        "process",
                        "ordering-and-error-aggregation",
                        String.join(",", PROCESS_TRACE),
                        result.isValid(),
                        result.getErrorsList().size(),
                        result.getErrorsList().stream()
                                .map(ValidationError::getErrorCode)
                                .collect(Collectors.joining(",")),
                        result.getErrorsList().stream()
                                .map(error -> error.getPageNumber() == null
                                        ? "null" : error.getPageNumber().toString())
                                .collect(Collectors.joining(",")),
                        unmodifiable != null);
            }
        }

        observeLazyProcess(path, false);
        observeLazyProcess(path, true);

        PreflightConfiguration empty = new PreflightConfiguration();
        try (RandomAccessReadBufferedFile source =
                     new RandomAccessReadBufferedFile(path))
        {
            PreflightParser emptyParser = new PreflightParser(source);
            try (PreflightDocument emptyDocument =
                         (PreflightDocument) emptyParser.parse(
                                 Format.PDF_A1B, empty))
            {
                ValidationResult first = emptyDocument.validate();
                ValidationResult second = emptyDocument.validate();
                observe(
                        "document",
                        "specification-context-and-result-cache",
                        Format.PDF_A1B == emptyDocument.getSpecification(),
                        emptyDocument == emptyDocument.getContext().getDocument(),
                        first == second,
                        first.getErrorsList().size());
            }
        }
    }

    private static void observeLazyProcess(File path, boolean lazy)
            throws Exception
    {
        PreflightConfiguration configuration = new PreflightConfiguration();
        configuration.setLazyValidation(lazy);
        configuration.replaceProcess("lazy", LazyProcess.class);
        try (RandomAccessReadBufferedFile source =
                     new RandomAccessReadBufferedFile(path))
        {
            PreflightParser parser = new PreflightParser(source);
            try (PreflightDocument document =
                         (PreflightDocument) parser.parse(
                                 Format.PDF_A1B, configuration))
            {
                ValidationResult result = document.validate();
                observe(
                        "process",
                        lazy ? "lazy-validation" : "eager-validation",
                        result.isValid(),
                        result.getErrorsList().size(),
                        result.getErrorsList().get(0).isWarning(),
                        configuration == document.getContext().getConfig());
            }
        }
    }

    private static void observeContextAndLifetime(
            Path upstreamFixtures, Path generatedFixtures) throws Exception
    {
        File validPath = upstreamFixtures.resolve(
                "pdfa-with-annotations-square.pdf").toFile();
        RandomAccessReadBufferedFile validSource =
                new RandomAccessReadBufferedFile(validPath);
        PreflightParser parser = new PreflightParser(validSource);
        PreflightDocument document = (PreflightDocument) parser.parse();
        PreflightContext context = document.getContext();
        PDPage page = new PDPage();
        context.addToProcessedSet(page);
        context.setIccProfileAlreadySearched(true);
        context.setCurrentPageNumber(6);
        ValidationError paged = new ValidationError("7.1", "paged");
        context.addValidationError(paged);
        ValidationError unpaged = new ValidationError("7.2", "unpaged");
        context.addValidationErrors(Arrays.asList(unpaged));
        observe(
                "context",
                "state-caches-and-page-data",
                context.getXrefTrailerResolver() != null,
                context.getFileLen(),
                validPath.length(),
                context.isIccProfileAlreadySearched(),
                context.getCurrentPageNumber(),
                context.isInProcessedSet(page),
                !context.isInProcessedSet(new PDPage()),
                paged.getPageNumber(),
                unpaged.getPageNumber() == null,
                context.getValidationPath() == context.getValidationPath());

        boolean wasOpenBefore = !validSource.isClosed();
        document.close();
        boolean closedAfterDocument = validSource.isClosed();
        document.close();
        observe(
                "lifecycle",
                "document-owns-source",
                wasOpenBefore,
                closedAfterDocument,
                validSource.isClosed());

        RandomAccessReadBufferedFile contextSource =
                new RandomAccessReadBufferedFile(validPath);
        PreflightParser contextParser = new PreflightParser(contextSource);
        PreflightDocument contextDocument =
                (PreflightDocument) contextParser.parse();
        contextDocument.getContext().close();
        contextDocument.getContext().close();
        observe(
                "lifecycle",
                "context-closes-document",
                contextSource.isClosed(),
                contextDocument.getDocument().isClosed());

        RandomAccessReadBufferedFile invalidSource =
                new RandomAccessReadBufferedFile(
                        generatedFixtures.resolve("unsupported.bin").toFile());
        PreflightParser invalidParser = new PreflightParser(invalidSource);
        SyntaxValidationException syntax =
                capture(SyntaxValidationException.class, invalidParser::parse);
        observe(
                "lifecycle",
                "failed-parse-source-ownership",
                syntax != null,
                syntax == null ? null : syntax.getResult().isValid(),
                syntax == null ? null : syntax.getResult().getErrorsList().size(),
                invalidSource.isClosed());
        invalidSource.close();
    }

    private static void observeValidationProcesses(
            Path upstreamFixtures, Path generatedFixtures) throws Exception
    {
        Path valid = upstreamFixtures.resolve("pdfa-with-annotations-square.pdf");
        observeValidation(
                "rule-selection", "pdf-a1b", valid, Format.PDF_A1B, true);
        observeValidation(
                "rule-selection", "pdf-a1a", valid, Format.PDF_A1A, true);
        observeValidation(
                "catalog",
                "language-and-optional-content",
                generatedFixtures.resolve("catalog-invalid.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "file-structure",
                "bad-startxref",
                generatedFixtures.resolve("malformed.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "cross-reference",
                "xref-stream",
                generatedFixtures.resolve("xref-stream.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "trailer",
                "missing-id",
                generatedFixtures.resolve("trailer-missing-id.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "page",
                "transparency-group",
                generatedFixtures.resolve("page-transparency.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "content-stream",
                "invalid-rendering-intent",
                generatedFixtures.resolve("content-stream.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "graphics-state",
                "transparency-and-transfer",
                generatedFixtures.resolve("graphics-state.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "color",
                "device-gray-without-profile",
                upstreamFixtures.resolve("PDFBOX-3741.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "font",
                "unembedded-standard-font",
                generatedFixtures.resolve("font-unembedded.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "transparency",
                "page-group",
                generatedFixtures.resolve("page-transparency.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "image-xobject",
                "invalid-image-dictionary",
                generatedFixtures.resolve("image-xobject.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "annotation",
                "forbidden-subtype",
                generatedFixtures.resolve("annotation-invalid.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "action",
                "forbidden-launch",
                generatedFixtures.resolve("action-forbidden.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "form",
                "need-appearances",
                generatedFixtures.resolve("form-invalid.pdf"),
                Format.PDF_A1B,
                true);

        Path metadata = upstreamFixtures.resolve(
                "org/apache/pdfbox/preflight/metadata");
        observeValidation(
                "metadata",
                "trailing-nul-valid",
                metadata.resolve("PDFAMetaDataValidationTestTrailingNul.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "metadata",
                "trailing-spaces-invalid",
                metadata.resolve("PDFAMetaDataValidationTestTrailingSpaces.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "xmp",
                "middle-control-character",
                metadata.resolve("PDFAMetaDataValidationTestMiddleControlChar.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "xmp",
                "middle-nul",
                metadata.resolve("PDFAMetaDataValidationTestMiddleNul.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "xmp",
                "trailing-control-character",
                metadata.resolve("PDFAMetaDataValidationTestTrailingControlChar.pdf"),
                Format.PDF_A1B,
                true);

        observeValidation(
                "output-intent",
                "invalid-icc-profile",
                generatedFixtures.resolve("output-intent-invalid-icc.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "icc",
                "valid-srgb-profile",
                valid,
                Format.PDF_A1B,
                true);
        observeValidation(
                "icc",
                "invalid-profile",
                generatedFixtures.resolve("output-intent-invalid-icc.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "embedded-file",
                "catalog-name-tree-and-file-specification",
                generatedFixtures.resolve("embedded-file.pdf"),
                Format.PDF_A1B,
                true);
        observeValidation(
                "logical-structure",
                "upstream-pdf-a1a-selection",
                valid,
                Format.PDF_A1A,
                true);
    }

    private static void observeXml(Path upstreamFixtures) throws Exception
    {
        XmlResultParser parser = new XmlResultParser();
        Element valid = parser.validate(
                upstreamFixtures.resolve(
                        "pdfa-with-annotations-square.pdf").toFile());
        Element invalid = parser.validate(
                upstreamFixtures.resolve("PDFBOX-3741.pdf").toFile());
        observe(
                "xml",
                "valid-response",
                valid.getTagName(),
                valid.getAttribute("name"),
                text(valid, "isValid"),
                ((Element) valid.getElementsByTagName("isValid").item(0))
                        .getAttribute("type"),
                parseDuration(valid) >= 0,
                valid.getElementsByTagName("errors").getLength() == 0,
                valid.getElementsByTagName("exceptionThrown").getLength() == 0);
        Element errors = (Element) invalid.getElementsByTagName("errors").item(0);
        observe(
                "xml",
                "invalid-response",
                invalid.getTagName(),
                invalid.getAttribute("name"),
                text(invalid, "isValid"),
                ((Element) invalid.getElementsByTagName("isValid").item(0))
                        .getAttribute("type"),
                errors.getAttribute("count"),
                text(invalid, "code"),
                text(invalid, "details"),
                text(invalid, "page"),
                parseDuration(invalid) >= 0,
                invalid.getElementsByTagName("exceptionThrown").getLength() == 0);
    }

    private static long parseDuration(Element element)
    {
        return Long.parseLong(text(element, "executionTimeMS"));
    }

    private static String text(Element element, String tag)
    {
        return element.getElementsByTagName(tag).item(0).getTextContent();
    }

    private static void observeValidation(
            String family, String id, Path path, boolean includeDetails)
            throws Exception
    {
        ValidationResult result = PreflightParser.validate(path.toFile());
        observeValidationResult(family, id, result, includeDetails);
    }

    private static void observeValidation(
            String family,
            String id,
            Path path,
            Format format,
            boolean includeDetails) throws Exception
    {
        ValidationResult result;
        try (RandomAccessReadBufferedFile source =
                     new RandomAccessReadBufferedFile(path.toFile()))
        {
            PreflightParser parser = new PreflightParser(source);
            try (PreflightDocument document =
                         (PreflightDocument) parser.parse(format))
            {
                result = document.validate();
            }
            catch (SyntaxValidationException syntax)
            {
                result = syntax.getResult();
            }
        }
        observeValidationResult(family, id, result, includeDetails);
    }

    private static void observeValidationResult(
            String family,
            String id,
            ValidationResult result,
            boolean includeDetails)
    {
        List<ValidationError> errors = result.getErrorsList();
        observe(
                family,
                id,
                result.isValid(),
                errors.size(),
                errors.stream()
                        .map(ValidationError::getErrorCode)
                        .collect(Collectors.joining(",")),
                errors.stream()
                        .map(error -> Boolean.toString(error.isWarning()))
                        .collect(Collectors.joining(",")),
                errors.stream()
                        .map(error -> error.getPageNumber() == null
                                ? "null" : error.getPageNumber().toString())
                        .collect(Collectors.joining(",")),
                includeDetails
                        ? errors.stream()
                                .map(ValidationError::getDetails)
                                .collect(Collectors.joining(";"))
                        : errors.stream()
                                .map(error -> Boolean.toString(
                                                error.getDetails().toLowerCase(Locale.ROOT)
                                                        .contains("offset"))
                                        + ":"
                                        + Boolean.toString(error.getCause() != null))
                                .collect(Collectors.joining(",")));
    }

    private static <T extends Throwable> T capture(
            Class<T> type, ThrowingAction action)
    {
        try
        {
            action.run();
            return null;
        }
        catch (Throwable thrown)
        {
            if (type.isInstance(thrown))
            {
                return type.cast(thrown);
            }
            throw new RuntimeException(thrown);
        }
    }

    private static void observe(String family, String id, Object... values)
    {
        String rendered = Arrays.stream(values)
                .map(PreflightExecutionOracle::render)
                .collect(Collectors.joining("|"));
        OBSERVATIONS.add(family + "\t" + id + "\t" + rendered);
    }

    private static String render(Object value)
    {
        String text = value == null ? "null" : String.valueOf(value);
        return text.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String repeat(char value, int count)
    {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    @FunctionalInterface
    private interface DocumentMutation
    {
        void apply(PDDocument document) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }

    public static final class FirstProcess implements ValidationProcess
    {
        @Override
        public void validate(PreflightContext context)
        {
            PROCESS_TRACE.add("first");
            context.setCurrentPageNumber(4);
            ValidationError warning = new ValidationError("9.1", "first");
            warning.setWarning(true);
            context.addValidationError(warning);
        }
    }

    public static final class SecondProcess implements ValidationProcess
    {
        @Override
        public void validate(PreflightContext context)
        {
            PROCESS_TRACE.add("second");
            context.addValidationError(new ValidationError("9.2", "second"));
        }
    }

    public static final class ReplacementProcess implements ValidationProcess
    {
        @Override
        public void validate(PreflightContext context)
        {
            PROCESS_TRACE.add("replacement");
        }
    }

    public static final class LazyProcess implements ValidationProcess
    {
        @Override
        public void validate(PreflightContext context)
        {
            ValidationError error = new ValidationError("9.3", "lazy");
            error.setWarning(context.getConfig().isLazyValidation());
            context.addValidationError(error);
        }
    }

    public static final class NoDefaultConstructorProcess
            implements ValidationProcess
    {
        public NoDefaultConstructorProcess(String value)
        {
        }

        @Override
        public void validate(PreflightContext context)
        {
        }
    }
}
