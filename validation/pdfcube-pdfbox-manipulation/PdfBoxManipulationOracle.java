import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.Overlay;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.PageExtractor;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDParentTreeValue;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

public final class PdfBoxManipulationOracle {
  private static final COSName PROBE = COSName.getPDFName("Probe");
  private static final COSName LEFT = COSName.getPDFName("Left");
  private static final COSName RIGHT = COSName.getPDFName("Right");
  private static final COSName PAYLOAD = COSName.getPDFName("Payload");
  private static final COSName SHARED_STATE = COSName.getPDFName("SharedState");
  private static final List<String> OBSERVATIONS = new ArrayList<>();

  private static Path exchange;
  private static Path fixtures;

  private PdfBoxManipulationOracle() {}

  public static void main(String[] args) {
    try {
      if (args.length != 3) {
        throw new IllegalArgumentException(
            "Expected output trace, exchange directory, and fixture directory.");
      }
      Path output = Paths.get(args[0]);
      exchange = Paths.get(args[1]);
      fixtures = Paths.get(args[2]);
      Files.createDirectories(exchange);

      writeCrossRuntimeDocument(exchange.resolve("java-manipulation.pdf"));
      observeCloneUtility();
      observeImportAndResourceCollisions();
      observeSplittingAndExtraction();
      observeStructuredSplitFixture();
      observeAppendMerge();
      observeFileMergeAndRepeatedSources();
      observeOverlay();
      observeLayerUtility();
      observeFailures();
      observeCrossRuntimeReopen(exchange.resolve("dotnet-manipulation.pdf"));

      Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void observeCloneUtility() throws Exception {
    try (PDDocument source = new PDDocument();
        PDDocument destination = new PDDocument()) {
      COSDictionary shared = new COSDictionary();
      shared.setString(PROBE, "shared");

      COSStream stream = source.getDocument().createCOSStream();
      try (OutputStream output = stream.createRawOutputStream()) {
        output.write("clone-stream-payload".getBytes(StandardCharsets.US_ASCII));
      }
      stream.setItem(PROBE, shared);
      stream.setInt(COSName.LENGTH1, 20);

      COSArray array = new COSArray();
      array.add(shared);
      array.add(shared);
      COSDictionary root = new COSDictionary();
      root.setItem(LEFT, shared);
      root.setItem(RIGHT, shared);
      root.setItem(PAYLOAD, stream);
      root.setItem(COSName.KIDS, array);

      ExposedCloner cloner = new ExposedCloner(destination);
      COSDictionary cloned = cloner.cloneForNewDocument(root);
      COSDictionary clonedAgain = cloner.cloneForNewDocument(root);
      COSDictionary left = cloned.getCOSDictionary(LEFT);
      COSDictionary right = cloned.getCOSDictionary(RIGHT);
      COSStream clonedStream = cloned.getCOSStream(PAYLOAD);
      COSArray clonedArray = cloned.getCOSArray(COSName.KIDS);

      require(cloned != root, "Clone root must be distinct");
      require(cloned == clonedAgain, "Repeated clone must be deduplicated");
      require(left == right, "Shared dictionary identity must be preserved");
      require(left == clonedArray.getObject(0), "Array reference must reuse clone");
      require(left == clonedArray.getObject(1), "Repeated array reference must reuse clone");
      require(clonedStream.getDictionaryObject(PROBE) == left,
          "Stream metadata reference must reuse clone");

      observe(
          "clone-identity",
          "deduplication",
          cloned != root,
          cloned == clonedAgain,
          left == right,
          left == clonedArray.getObject(0),
          left == clonedArray.getObject(1));
      observe(
          "clone-reference",
          "source-destination-separation",
          left != shared,
          clonedStream != stream,
          clonedStream.getDictionaryObject(PROBE) == left,
          left.getString(PROBE));
      observe(
          "clone-stream",
          "raw-bytes-and-metadata",
          readAscii(clonedStream.createRawInputStream()),
          clonedStream.getInt(COSName.LENGTH1),
          clonedStream.getCOSObject() == clonedStream);
    }
  }

  private static void observeImportAndResourceCollisions() throws Exception {
    Path importedPath = exchange.resolve("java-imported-pages.pdf");
    try (PDDocument source = new PDDocument();
        PDDocument destination = new PDDocument()) {
      PDPage sourcePage = new PDPage(new PDRectangle(10, 20, 210, 320));
      sourcePage.setCropBox(new PDRectangle(20, 30, 180, 250));
      sourcePage.setRotation(90);
      sourcePage.getCOSObject().setString(PROBE, "import-source");
      sourcePage.setResources(resourcesWithAlpha(0.25f));
      source.addPage(sourcePage);
      writePageContent(source, sourcePage, "%import-source\n0 0 m 10 10 l S\n");

      PDAnnotationText note = new PDAnnotationText();
      note.setContents("imported note");
      note.setRectangle(new PDRectangle(25, 35, 20, 20));
      sourcePage.getAnnotations().add(note);

      PDPage first = destination.importPage(sourcePage);
      PDPage second = destination.importPage(sourcePage);
      require(first.getCOSObject() != sourcePage.getCOSObject(),
          "Imported page must be a new object");
      require(first.getCOSObject() != second.getCOSObject(),
          "Repeated imports must create separate pages");
      require(first.getContents() != sourcePage.getContents(),
          "Imported content stream must be owned by destination");

      observe(
          "import-page",
          "geometry-annotations-and-stream",
          pageGeometry(first),
          first.getRotation(),
          first.getAnnotations().size(),
          readPageContent(first).contains("%import-source"),
          first.getResources().getExtGState(SHARED_STATE)
              .getNonStrokingAlphaConstant());
      observe(
          "repeated-import",
          "identity-and-order",
          destination.getNumberOfPages(),
          first.getCOSObject() != second.getCOSObject(),
          first.getContents() != second.getContents(),
          pageOrder(destination),
          readPageContent(second).contains("%import-source"));

      destination.save(importedPath.toFile(), CompressParameters.NO_COMPRESSION);
    }
    try (PDDocument reopened = Loader.loadPDF(importedPath.toFile())) {
      observe(
          "import-page",
          "destination-lifecycle-reopen",
          reopened.getNumberOfPages(),
          pageOrder(reopened),
          pageGeometry(reopened.getPage(0)),
          reopened.getPage(0).getAnnotations().size(),
          readPageContent(reopened.getPage(1)).contains("%import-source"));
    }

    try (PDDocument firstSource = onePageDocument("collision-a", 220, 310, 0.25f);
        PDDocument secondSource = onePageDocument("collision-b", 420, 210, 0.75f);
        PDDocument destination = new PDDocument()) {
      PDFMergerUtility merger = new PDFMergerUtility();
      merger.appendDocument(destination, firstSource);
      merger.appendDocument(destination, secondSource);
      float firstAlpha = destination.getPage(0).getResources()
          .getExtGState(SHARED_STATE).getNonStrokingAlphaConstant();
      float secondAlpha = destination.getPage(1).getResources()
          .getExtGState(SHARED_STATE).getNonStrokingAlphaConstant();
      require(firstAlpha == 0.25f && secondAlpha == 0.75f,
          "Page-local resources must survive name collisions");
      observe(
          "resource-collision",
          "page-local-same-name",
          pageOrder(destination),
          firstAlpha,
          secondAlpha,
          resourceNames(destination.getPage(0)),
          resourceNames(destination.getPage(1)));
    }
  }

  private static void observeSplittingAndExtraction() throws Exception {
    Path splitOnePath = exchange.resolve("java-split-one.pdf");
    Path splitTwoPath = exchange.resolve("java-split-two.pdf");
    try (PDDocument source = new PDDocument()) {
      source.setVersion(1.7f);
      source.getDocumentInformation().setTitle("split-source");
      source.getDocumentCatalog().setLanguage("en-GB");
      for (int index = 1; index <= 5; index++) {
        PDPage page = new PDPage(new PDRectangle(100 + index * 10, 200 + index * 5));
        page.getCOSObject().setString(PROBE, "p" + index);
        source.addPage(page);
        writePageContent(source, page, "%p" + index + "\n");
      }

      Splitter splitter = new Splitter();
      splitter.setStartPage(2);
      splitter.setEndPage(5);
      splitter.setSplitAtPage(2);
      List<PDDocument> splits = splitter.split(source);
      require(splits.size() == 2, "Expected two split documents");
      require("p2,p3|p4,p5".equals(
          pageOrder(splits.get(0)) + "|" + pageOrder(splits.get(1))),
          "Split page order differs");
      observe(
          "split-order",
          "range-and-chunks",
          splits.size(),
          splitSizes(splits),
          pageOrder(splits.get(0)) + "|" + pageOrder(splits.get(1)),
          splits.get(0).getVersion(),
          splits.get(0).getDocumentInformation().getTitle(),
          splits.get(1).getDocumentCatalog().getLanguage());

      splits.get(0).save(splitOnePath.toFile(), CompressParameters.NO_COMPRESSION);
      splits.get(1).save(splitTwoPath.toFile(), CompressParameters.NO_COMPRESSION);
      for (PDDocument split : splits) {
        split.close();
      }

      try (PDDocument extracted = new PageExtractor(source, 2, 4).extract();
          PDDocument empty = new PageExtractor(source, 4, 3).extract()) {
        require("p2,p3,p4".equals(pageOrder(extracted)),
            "PageExtractor must retain inclusive ordering");
        require(empty.getNumberOfPages() == 0,
            "Reversed extraction range must be empty");
        observe(
            "page-extractor",
            "inclusive-clamped-and-empty",
            extracted.getNumberOfPages(),
            pageOrder(extracted),
            pageGeometry(extracted.getPage(0)),
            empty.getNumberOfPages());
      }
    }

    try (PDDocument first = Loader.loadPDF(splitOnePath.toFile());
        PDDocument second = Loader.loadPDF(splitTwoPath.toFile())) {
      observe(
          "split-lifecycle",
          "saved-before-source-close",
          first.getNumberOfPages(),
          second.getNumberOfPages(),
          pageOrder(first) + "|" + pageOrder(second),
          readPageContent(first.getPage(0)).contains("%p2"),
          readPageContent(second.getPage(1)).contains("%p5"));
    }
  }

  private static void observeStructuredSplitFixture() throws Exception {
    Path fixture = fixture("input/merge/PDFBOX-5762-722238.pdf");
    try (PDDocument source = Loader.loadPDF(fixture.toFile())) {
      Splitter splitter = new Splitter();
      splitter.setStartPage(1);
      splitter.setEndPage(2);
      splitter.setSplitAtPage(2);
      List<PDDocument> splits = splitter.split(source);
      require(splits.size() == 1, "Structured fixture should produce one split");
      try (PDDocument split = splits.get(0)) {
        PDStructureTreeRoot root =
            split.getDocumentCatalog().getStructureTreeRoot();
        List<PDAnnotation> annotations = split.getPage(0).getAnnotations();
        List<Integer> destinations = new ArrayList<>();
        for (PDAnnotation annotation : annotations) {
          if (annotation instanceof PDAnnotationLink) {
            PDAnnotationLink link = (PDAnnotationLink) annotation;
            COSBase action = link.getCOSObject().getDictionaryObject(COSName.A);
            PDPageDestination destination = null;
            if (action instanceof COSDictionary) {
              org.apache.pdfbox.pdmodel.interactive.action.PDAction parsed =
                  org.apache.pdfbox.pdmodel.interactive.action.PDActionFactory
                      .createAction((COSDictionary) action);
              if (parsed instanceof
                  org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo) {
                org.apache.pdfbox.pdmodel.interactive.documentnavigation
                    .destination.PDDestination value =
                    ((org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo)
                        parsed).getDestination();
                if (value instanceof PDPageDestination) {
                  destination = (PDPageDestination) value;
                }
              }
            }
            destinations.add(destination == null || destination.getPage() == null
                ? -1
                : split.getPages().indexOf(destination.getPage()));
          }
        }
        require(root != null, "Structured split must retain structure root");
        require(annotations.size() == 5,
            "Structured split must retain fixture annotations");
        observe(
            "split-structure",
            "structure-annotations-and-destinations",
            split.getNumberOfPages(),
            root != null,
            parentTreeEntryCount(root.getParentTree()),
            root.getRoleMap().size(),
            annotations.size(),
            destinations.stream().map(String::valueOf)
                .collect(Collectors.joining(",")));
      }
    }
  }

  private static void observeAppendMerge() throws Exception {
    Path mergedPath = exchange.resolve("java-rich-merge.pdf");
    try (PDDocument first = featureDocument("alpha", 0.25f, 1.4f, 210, 310);
        PDDocument second = featureDocument("beta", 0.75f, 1.7f, 410, 210);
        PDDocument destination = new PDDocument()) {
      PDFMergerUtility merger = new PDFMergerUtility();
      merger.appendDocument(destination, first);
      merger.appendDocument(destination, second);
      require("alpha-1,beta-1".equals(pageOrder(destination)),
          "Append merge order differs");
      require(formNames(destination).equals("alpha-field,beta-field"),
          "Append merge must retain form fields");
      require(outlineTitles(destination).equals("alpha-outline,beta-outline"),
          "Append merge must retain outline ordering");
      require(optionalGroupNames(destination).equals("alpha-layer,beta-layer"),
          "Append merge must retain optional-content groups");
      require(destination.getDocumentCatalog().getStructureTreeRoot() != null,
          "Append merge must retain logical structure");

      observe(
          "merge-model",
          "rich-document-structures",
          pageOrder(destination),
          destination.getVersion(),
          destination.getDocumentInformation().getTitle(),
          metadataText(destination),
          formNames(destination),
          outlineTitles(destination),
          optionalGroupNames(destination),
          structureKidCount(destination),
          annotationCounts(destination));
      observe(
          "resource-collision",
          "append-merge-page-local",
          destination.getPage(0).getResources().getExtGState(SHARED_STATE)
              .getNonStrokingAlphaConstant(),
          destination.getPage(1).getResources().getExtGState(SHARED_STATE)
              .getNonStrokingAlphaConstant(),
          pageGeometry(destination.getPage(0)),
          pageGeometry(destination.getPage(1)));

      destination.save(mergedPath.toFile(), CompressParameters.NO_COMPRESSION);
    }
    try (PDDocument reopened = Loader.loadPDF(mergedPath.toFile())) {
      observe(
          "merge-reopen",
          "rich-document-structures",
          pageOrder(reopened),
          reopened.getVersion(),
          reopened.getDocumentInformation().getTitle(),
          metadataText(reopened),
          formNames(reopened),
          outlineTitles(reopened),
          optionalGroupNames(reopened),
          structureKidCount(reopened),
          annotationCounts(reopened));
    }
  }

  private static void observeFileMergeAndRepeatedSources() throws Exception {
    Path firstPath = exchange.resolve("java-file-source-a.pdf");
    Path secondPath = exchange.resolve("java-file-source-b.pdf");
    Path mergedPath = exchange.resolve("java-file-merged.pdf");
    try (PDDocument first = onePageDocument("file-a", 200, 300, 0.2f);
        PDDocument second = onePageDocument("file-b", 500, 240, 0.8f)) {
      first.save(firstPath.toFile(), CompressParameters.NO_COMPRESSION);
      second.save(secondPath.toFile(), CompressParameters.NO_COMPRESSION);
    }

    PDFMergerUtility merger = new PDFMergerUtility();
    merger.addSource(firstPath.toFile());
    merger.addSource(secondPath.toFile());
    merger.addSource(firstPath.toFile());
    merger.setDestinationFileName(mergedPath.toString());
    merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache(),
        CompressParameters.NO_COMPRESSION);

    try (PDDocument merged = Loader.loadPDF(mergedPath.toFile())) {
      require("file-a,file-b,file-a".equals(pageOrder(merged)),
          "File merge must preserve source order and repeated imports");
      observe(
          "repeated-import",
          "file-merge-source-list",
          merged.getNumberOfPages(),
          pageOrder(merged),
          pageGeometry(merged.getPage(0)),
          pageGeometry(merged.getPage(1)),
          pageGeometry(merged.getPage(2)));
      observe(
          "merge-reopen",
          "file-api-output",
          Files.size(mergedPath) > 0,
          merged.getNumberOfPages(),
          pageOrder(merged),
          readPageContent(merged.getPage(0)).contains("%file-a"),
          readPageContent(merged.getPage(1)).contains("%file-b"));
    }
  }

  private static void observeOverlay() throws Exception {
    try (PDDocument input = mixedPageDocument("overlay-base");
        PDDocument overlayDocument =
            onePageDocument("overlay-mark", 120, 80, 0.6f);
        Overlay overlay = new Overlay()) {
      overlay.setInputPDF(input);
      overlay.setDefaultOverlayPDF(overlayDocument);
      overlay.setOverlayPosition(Overlay.Position.FOREGROUND);
      overlay.setAdjustRotation(true);
      PDDocument result = overlay.overlayDocuments(Collections.emptyMap());
      require(result == input, "Overlay must mutate and return the input document");
      observe(
          "overlay-order",
          "foreground-save-restore",
          streamKinds(result.getPage(0)),
          streamKinds(result.getPage(1)),
          streamKinds(result.getPage(2)),
          result == input);
      observe(
          "overlay-geometry",
          "mixed-page-size-and-rotation",
          pageGeometry(result.getPage(0)),
          result.getPage(0).getRotation(),
          pageGeometry(result.getPage(1)),
          result.getPage(1).getRotation(),
          pageGeometry(result.getPage(2)),
          result.getPage(2).getRotation(),
          xObjectCounts(result));
    }

    try (PDDocument input = mixedPageDocument("specific-base");
        PDDocument firstOverlay =
            onePageDocument("specific-one", 90, 60, 0.3f);
        PDDocument secondOverlay =
            onePageDocument("specific-two", 140, 100, 0.7f);
        Overlay overlay = new Overlay()) {
      overlay.setInputPDF(input);
      overlay.setOverlayPosition(Overlay.Position.BACKGROUND);
      Map<Integer, PDDocument> specifics = new TreeMap<>();
      specifics.put(1, firstOverlay);
      specifics.put(3, secondOverlay);
      PDDocument result = overlay.overlayDocuments(specifics);
      observe(
          "overlay-order",
          "specific-background-pages",
          streamKinds(result.getPage(0)),
          streamKinds(result.getPage(1)),
          streamKinds(result.getPage(2)),
          xObjectCounts(result));
    }
  }

  private static void observeLayerUtility() throws Exception {
    Path layerPath = exchange.resolve("java-layered-import.pdf");
    try (PDDocument source = new PDDocument();
        PDDocument target = new PDDocument()) {
      PDPage sourcePage = new PDPage(new PDRectangle(0, 0, 300, 200));
      sourcePage.setCropBox(new PDRectangle(10, 20, 210, 170));
      sourcePage.setRotation(90);
      sourcePage.setResources(resourcesWithAlpha(0.4f));
      source.addPage(sourcePage);
      writePageContent(source, sourcePage, "%layer-source\n0 0 m 30 30 l S\n");
      PDMetadata pageMetadata = new PDMetadata(
          source,
          new ByteArrayInputStream(
              "<page-meta>layer</page-meta>".getBytes(StandardCharsets.UTF_8)));
      sourcePage.setMetadata(pageMetadata);
      PDOptionalContentProperties sourceProperties =
          new PDOptionalContentProperties();
      sourceProperties.addGroup(new PDOptionalContentGroup("source-oc"));
      source.getDocumentCatalog().setOCProperties(sourceProperties);

      PDPage targetPage = new PDPage(new PDRectangle(400, 300));
      target.addPage(targetPage);
      writePageContent(target, targetPage, "%layer-target\n");

      LayerUtility utility = new LayerUtility(target);
      utility.wrapInSaveRestore(targetPage);
      PDFormXObject form = utility.importPageAsForm(source, sourcePage);
      PDOptionalContentGroup layer = utility.appendFormAsLayer(
          targetPage,
          form,
          java.awt.geom.AffineTransform.getTranslateInstance(12, 18),
          "imported-layer");
      require("source-oc,imported-layer".equals(optionalGroupNames(target)),
          "Layer import must preserve source and appended optional content");

      observe(
          "layer-import",
          "form-geometry-resources-and-metadata",
          form.getBBox().getLowerLeftX(),
          form.getBBox().getLowerLeftY(),
          form.getBBox().getWidth(),
          form.getBBox().getHeight(),
          form.getMatrix().getScaleX(),
          form.getMatrix().getScaleY(),
          form.getResources().getExtGStateNames().iterator().hasNext(),
          form.getCOSObject().containsKey(COSName.METADATA));
      observe(
          "layer-optional",
          "wrap-append-and-group-order",
          utility.getDocument() == target,
          layer.getName(),
          optionalGroupNames(target),
          streamKinds(targetPage),
          fails(IllegalArgumentException.class,
              () -> utility.appendFormAsLayer(
                  targetPage,
                  form,
                  new java.awt.geom.AffineTransform(),
                  "imported-layer")));

      target.save(layerPath.toFile(), CompressParameters.NO_COMPRESSION);
    }
    try (PDDocument reopened = Loader.loadPDF(layerPath.toFile())) {
      observe(
          "layer-optional",
          "serialization-reopen",
          reopened.getNumberOfPages(),
          optionalGroupNames(reopened),
          streamKinds(reopened.getPage(0)),
          xObjectCount(reopened.getPage(0)));
    }
  }

  private static void observeFailures() throws Exception {
    Splitter splitter = new Splitter();
    boolean invalidSplit = fails(
        IllegalArgumentException.class, () -> splitter.setSplitAtPage(0));
    boolean invalidStart = fails(
        IllegalArgumentException.class, () -> splitter.setStartPage(0));
    splitter.setStartPage(3);
    boolean invalidEnd = fails(
        IllegalArgumentException.class, () -> splitter.setEndPage(2));
    observe(
        "malformed-input",
        "split-configuration",
        invalidSplit,
        invalidStart,
        invalidEnd);

    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      page.getCOSObject().setItem(COSName.CONTENTS, COSInteger.ONE);
      LayerUtility utility = new LayerUtility(document);
      observe(
          "layer-failure",
          "unknown-content-object",
          fails(IOException.class, () -> utility.wrapInSaveRestore(page)));
    }

    try (Overlay overlay = new Overlay()) {
      observe(
          "overlay-failure",
          "missing-input-document",
          fails(IllegalArgumentException.class,
              () -> overlay.overlay(Collections.emptyMap())));
    }

    Path malformed = exchange.resolve("malformed-manipulation.pdf");
    Files.write(malformed, "%PDF-1.7\n1 0 obj\n<< /Type /Catalog".getBytes(
        StandardCharsets.US_ASCII));
    Path malformedOutput = exchange.resolve("malformed-merge-output.pdf");
    PDFMergerUtility malformedMerger = new PDFMergerUtility();
    malformedMerger.addSource(malformed.toFile());
    malformedMerger.setDestinationFileName(malformedOutput.toString());
    observe(
        "malformed-input",
        "merge-truncated-source",
        fails(IOException.class,
            () -> malformedMerger.mergeDocuments(
                IOUtils.createMemoryOnlyStreamCache())));

    Path encrypted =
        fixture("org/apache/pdfbox/encryption/PasswordSample-40bit.pdf");
    Path encryptedOutput = exchange.resolve("encrypted-merge-output.pdf");
    PDFMergerUtility encryptedMerger = new PDFMergerUtility();
    encryptedMerger.addSource(encrypted.toFile());
    encryptedMerger.setDestinationFileName(encryptedOutput.toString());
    observe(
        "encrypted-input",
        "merge-without-credential",
        fails(IOException.class,
            () -> encryptedMerger.mergeDocuments(
                IOUtils.createMemoryOnlyStreamCache())));

    PDDocument closedSource = onePageDocument("closed-source", 100, 100, 0.5f);
    PDDocument openDestination = new PDDocument();
    closedSource.close();
    boolean closedSourceFailure = fails(
        IOException.class,
        () -> new PDFMergerUtility().appendDocument(
            openDestination, closedSource));
    openDestination.close();
    PDDocument openSource = onePageDocument("open-source", 100, 100, 0.5f);
    boolean closedDestinationFailure = fails(
        IOException.class,
        () -> new PDFMergerUtility().appendDocument(
            openDestination, openSource));
    openSource.close();
    observe(
        "merge-lifecycle",
        "closed-source-and-destination",
        closedSourceFailure,
        closedDestinationFailure);
  }

  private static void writeCrossRuntimeDocument(Path path) throws Exception {
    try (PDDocument first = onePageDocument("cross-a", 200, 300, 0.25f);
        PDDocument second = onePageDocument("cross-b", 420, 220, 0.75f);
        PDDocument overlayDocument =
            onePageDocument("cross-overlay", 80, 60, 0.5f);
        PDDocument destination = new PDDocument()) {
      PDFMergerUtility merger = new PDFMergerUtility();
      merger.appendDocument(destination, first);
      merger.appendDocument(destination, second);

      try (Overlay overlay = new Overlay()) {
        overlay.setInputPDF(destination);
        overlay.setDefaultOverlayPDF(overlayDocument);
        overlay.setOverlayPosition(Overlay.Position.FOREGROUND);
        overlay.overlayDocuments(Collections.emptyMap());
      }

      LayerUtility layerUtility = new LayerUtility(destination);
      PDFormXObject form =
          layerUtility.importPageAsForm(overlayDocument, 0);
      layerUtility.appendFormAsLayer(
          destination.getPage(0),
          form,
          java.awt.geom.AffineTransform.getTranslateInstance(5, 7),
          "cross-layer");
      destination.save(path.toFile(), CompressParameters.NO_COMPRESSION);
    }
  }

  private static void observeCrossRuntimeReopen(Path path) throws Exception {
    try (PDDocument document = Loader.loadPDF(path.toFile())) {
      require(document.getNumberOfPages() == 2,
          "Foreign manipulation output must contain two pages");
      require("cross-a,cross-b".equals(pageOrder(document)),
          "Foreign manipulation output page order differs");
      observe(
          "cross-reopen",
          "foreign-merge-overlay-layer",
          document.getNumberOfPages(),
          pageOrder(document),
          pageGeometry(document.getPage(0)),
          pageGeometry(document.getPage(1)),
          streamKinds(document.getPage(0)),
          streamKinds(document.getPage(1)),
          optionalGroupNames(document),
          xObjectCounts(document));
    }
  }

  private static PDDocument onePageDocument(
      String id, float width, float height, float alpha) throws Exception {
    PDDocument document = new PDDocument();
    PDPage page = new PDPage(new PDRectangle(width, height));
    page.getCOSObject().setString(PROBE, id);
    page.setResources(resourcesWithAlpha(alpha));
    document.addPage(page);
    writePageContent(document, page, "%" + id + "\n/SharedState gs\n");
    return document;
  }

  private static PDDocument mixedPageDocument(String prefix) throws Exception {
    PDDocument document = new PDDocument();
    float[][] geometry = {{200, 300}, {400, 200}, {260, 360}};
    int[] rotations = {0, 90, 270};
    for (int index = 0; index < geometry.length; index++) {
      PDPage page =
          new PDPage(new PDRectangle(geometry[index][0], geometry[index][1]));
      page.setRotation(rotations[index]);
      page.getCOSObject().setString(PROBE, prefix + "-" + (index + 1));
      page.setResources(resourcesWithAlpha(0.2f + index * 0.2f));
      document.addPage(page);
      writePageContent(document, page, "%" + prefix + "-" + (index + 1) + "\n");
    }
    return document;
  }

  private static PDDocument featureDocument(
      String prefix,
      float alpha,
      float version,
      float width,
      float height) throws Exception {
    PDDocument document = onePageDocument(prefix + "-1", width, height, alpha);
    document.setVersion(version);
    document.getDocumentInformation().setTitle(prefix + "-title");
    document.getDocumentCatalog().setLanguage("en-US");
    document.getDocumentCatalog().setMetadata(
        new PDMetadata(
            document,
            new ByteArrayInputStream(
                ("<metadata>" + prefix + "</metadata>")
                    .getBytes(StandardCharsets.UTF_8))));

    PDPage page = document.getPage(0);
    PDAnnotationText note = new PDAnnotationText();
    note.setContents(prefix + "-annotation");
    note.setRectangle(new PDRectangle(10, 10, 20, 20));
    page.getAnnotations().add(note);

    PDAcroForm form = new PDAcroForm(document);
    form.setNeedAppearances(true);
    form.setDefaultResources(new PDResources());
    document.getDocumentCatalog().setAcroForm(form);
    form = document.getDocumentCatalog().getAcroForm();
    PDTextField field = new PDTextField(form);
    field.setPartialName(prefix + "-field");
    field.setValue(prefix + "-value");
    form.setFields(new ArrayList<>(Collections.singletonList(field)));

    PDDocumentOutline outline = new PDDocumentOutline();
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(prefix + "-outline");
    item.setDestination(page);
    outline.addLast(item);
    document.getDocumentCatalog().setDocumentOutline(outline);

    PDOptionalContentProperties properties = new PDOptionalContentProperties();
    properties.addGroup(new PDOptionalContentGroup(prefix + "-layer"));
    document.getDocumentCatalog().setOCProperties(properties);

    PDStructureTreeRoot root = new PDStructureTreeRoot();
    PDStructureElement element = new PDStructureElement("Sect", root);
    element.setTitle(prefix + "-structure");
    element.setPage(page);
    root.appendKid(element);
    page.setStructParents(0);
    COSArray parentValues = new COSArray();
    parentValues.add(element);
    PDNumberTreeNode parentTree =
        new PDNumberTreeNode(PDParentTreeValue.class);
    Map<Integer, COSObjectable> parentNumbers = new LinkedHashMap<>();
    parentNumbers.put(0, new PDParentTreeValue(parentValues));
    parentTree.setNumbers(parentNumbers);
    root.setParentTree(parentTree);
    root.setParentTreeNextKey(1);
    document.getDocumentCatalog().setStructureTreeRoot(root);
    return document;
  }

  private static PDResources resourcesWithAlpha(float alpha) {
    PDResources resources = new PDResources();
    PDExtendedGraphicsState state = new PDExtendedGraphicsState();
    state.setNonStrokingAlphaConstant(alpha);
    resources.put(SHARED_STATE, state);
    return resources;
  }

  private static void writePageContent(
      PDDocument document, PDPage page, String content) throws Exception {
    try (PDPageContentStream stream =
        new PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.OVERWRITE,
            false)) {
      stream.appendRawCommands(content);
    }
  }

  private static String readPageContent(PDPage page) throws Exception {
    InputStream input = page.getContents();
    return input == null ? "" : readAscii(input);
  }

  private static String readAscii(InputStream input) throws Exception {
    try (InputStream owned = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      IOUtils.copy(owned, output);
      return new String(output.toByteArray(), StandardCharsets.ISO_8859_1);
    }
  }

  private static String pageOrder(PDDocument document) {
    List<String> values = new ArrayList<>();
    for (PDPage page : document.getPages()) {
      values.add(page.getCOSObject().getString(PROBE, "-"));
    }
    return String.join(",", values);
  }

  private static String pageGeometry(PDPage page) {
    PDRectangle media = page.getMediaBox();
    PDRectangle crop = page.getCropBox();
    return numbers(
        media.getLowerLeftX(),
        media.getLowerLeftY(),
        media.getWidth(),
        media.getHeight())
        + "/"
        + numbers(
            crop.getLowerLeftX(),
            crop.getLowerLeftY(),
            crop.getWidth(),
            crop.getHeight());
  }

  private static String splitSizes(List<PDDocument> documents) {
    return documents.stream()
        .map(document -> String.valueOf(document.getNumberOfPages()))
        .collect(Collectors.joining(","));
  }

  private static String resourceNames(PDPage page) {
    List<String> names = new ArrayList<>();
    for (COSName name : page.getResources().getExtGStateNames()) {
      names.add(name.getName());
    }
    Collections.sort(names);
    return String.join(",", names);
  }

  private static String formNames(PDDocument document) throws Exception {
    PDAcroForm form = document.getDocumentCatalog().getAcroForm();
    if (form == null) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (PDField field : form.getFieldTree()) {
      names.add(field.getFullyQualifiedName());
    }
    Collections.sort(names);
    return String.join(",", names);
  }

  private static String outlineTitles(PDDocument document) {
    PDDocumentOutline outline =
        document.getDocumentCatalog().getDocumentOutline();
    if (outline == null) {
      return "";
    }
    List<String> titles = new ArrayList<>();
    for (PDOutlineItem item : outline.children()) {
      titles.add(item.getTitle());
    }
    return String.join(",", titles);
  }

  private static String optionalGroupNames(PDDocument document) {
    PDOptionalContentProperties properties =
        document.getDocumentCatalog().getOCProperties();
    if (properties == null) {
      return "";
    }
    return String.join(",", properties.getGroupNames());
  }

  private static int structureKidCount(PDDocument document) {
    PDStructureTreeRoot root =
        document.getDocumentCatalog().getStructureTreeRoot();
    return root == null ? 0 : root.getKids().size();
  }

  private static String annotationCounts(PDDocument document)
      throws Exception {
    List<String> counts = new ArrayList<>();
    for (PDPage page : document.getPages()) {
      counts.add(String.valueOf(page.getAnnotations().size()));
    }
    return String.join(",", counts);
  }

  private static String metadataText(PDDocument document) throws Exception {
    PDMetadata metadata = document.getDocumentCatalog().getMetadata();
    return metadata == null ? "" : readAscii(metadata.exportXMPMetadata());
  }

  private static String streamKinds(PDPage page) throws Exception {
    List<String> kinds = new ArrayList<>();
    java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> streams =
        page.getContentStreams();
    while (streams.hasNext()) {
      String value = readAscii(streams.next().createInputStream()).trim();
      if ("q".equals(value)) {
        kinds.add("q");
      } else if ("Q".equals(value)) {
        kinds.add("Q");
      } else if (value.contains(" Do")) {
        kinds.add("form");
      } else if (value.contains("%")) {
        int start = value.indexOf('%') + 1;
        int end = value.indexOf('\n', start);
        kinds.add(end < 0 ? value.substring(start) : value.substring(start, end));
      } else {
        kinds.add("content");
      }
    }
    return String.join(",", kinds);
  }

  private static String xObjectCounts(PDDocument document) {
    List<String> counts = new ArrayList<>();
    for (PDPage page : document.getPages()) {
      counts.add(String.valueOf(xObjectCount(page)));
    }
    return String.join(",", counts);
  }

  private static int xObjectCount(PDPage page) {
    int count = 0;
    for (COSName ignored : page.getResources().getXObjectNames()) {
      count++;
    }
    return count;
  }

  private static int parentTreeEntryCount(PDNumberTreeNode tree)
      throws Exception {
    if (tree == null) {
      return 0;
    }
    Map<Integer, COSObjectable> numbers = tree.getNumbers();
    int count = numbers == null ? 0 : numbers.size();
    List<PDNumberTreeNode> kids = tree.getKids();
    if (kids != null) {
      for (PDNumberTreeNode kid : kids) {
        count += parentTreeEntryCount(kid);
      }
    }
    return count;
  }

  private static Path fixture(String relative) {
    return fixtures.resolve(relative);
  }

  private static String numbers(float... values) {
    List<String> rendered = new ArrayList<>();
    for (float value : values) {
      rendered.add(render(value));
    }
    return String.join("x", rendered);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static boolean fails(
      Class<? extends Throwable> expected, ThrowingRunnable operation) {
    try {
      operation.run();
      return false;
    } catch (Throwable error) {
      return expected.isInstance(error);
    }
  }

  private static void observe(String family, String id, Object... values) {
    String value = Arrays.stream(values)
        .map(PdfBoxManipulationOracle::render)
        .collect(Collectors.joining("|"));
    OBSERVATIONS.add(family + "\t" + id + "\t" + value);
  }

  private static String render(Object value) {
    if (value == null) {
      return "-";
    }
    if (value instanceof Boolean) {
      return Boolean.TRUE.equals(value) ? "true" : "false";
    }
    if (value instanceof Float || value instanceof Double) {
      double number = ((Number) value).doubleValue();
      if (Math.abs(number) < 0.0005d) {
        number = 0d;
      }
      return String.format(Locale.ROOT, "%.3f", number)
          .replaceAll("0+$", "")
          .replaceAll("\\.$", "");
    }
    return String.valueOf(value)
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ');
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static final class ExposedCloner extends PDFCloneUtility {
    private ExposedCloner(PDDocument destination) {
      super(destination);
    }
  }
}
