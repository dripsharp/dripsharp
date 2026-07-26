import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDJavascriptNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.fdf.FDFDocument;
import org.apache.pdfbox.pdmodel.fdf.FDFField;
import org.apache.pdfbox.pdmodel.fdf.FDFJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionHide;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionNamed;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionResetForm;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionSubmitForm;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationCaret;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationInk;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPolygon;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPolyline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSound;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquiggly;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationStrikeout;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnderline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderEffectDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.pagenavigation.PDThread;
import org.apache.pdfbox.pdmodel.interactive.pagenavigation.PDThreadBead;

public final class PdfBoxInteractionOracle {
  private static final List<String> OBSERVATIONS = new ArrayList<>();
  private static Path exchange;
  private static Path fixtures;

  private PdfBoxInteractionOracle() {}

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

      writeRepresentative(exchange.resolve("java-interaction.pdf"));
      observeFormModel();
      observeFormImportExport();
      observeFlatten();
      observeMalformedForms();
      observeAnnotationFixture();
      observeAnnotationModel();
      observeActions();
      observeDestinationsAndOutlines();
      observePageLabels();
      observeAttachments();
      observeCrossRuntimeReopen(exchange.resolve("dotnet-interaction.pdf"));

      Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void writeRepresentative(Path path) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage first = new PDPage(PDRectangle.A4);
      PDPage second = new PDPage(new PDRectangle(400, 300));
      document.addPage(first);
      document.addPage(second);
      PDDocumentCatalog catalog = document.getDocumentCatalog();

      PDAcroForm form = new PDAcroForm(document);
      form.setNeedAppearances(true);
      form.setDefaultResources(new PDResources());
      form.setDefaultAppearance("/Helv 0 Tf 0 g");
      catalog.setAcroForm(form);
      form = catalog.getAcroForm();
      PDTextField text = new PDTextField(form);
      text.setPartialName("customer-name");
      text.setAlternateFieldName("Customer name");
      text.setRequired(true);
      addWidget(text, first, new PDRectangle(40, 700, 180, 24));
      text.setValue("Ada Lovelace");
      PDComboBox choice = new PDComboBox(form);
      choice.setPartialName("customer-plan");
      choice.setOptions(
          Arrays.asList("basic", "pro"), Arrays.asList("Basic", "Professional"));
      addWidget(choice, first, new PDRectangle(40, 660, 180, 24));
      choice.setValue("pro");
      form.setFields(new ArrayList<>(Arrays.asList(text, choice)));
      form.setCalcOrder(new ArrayList<>(Arrays.asList(text, choice)));

      PDAnnotationText note = new PDAnnotationText();
      note.setRectangle(new PDRectangle(30, 600, 32, 32));
      note.setContents("Review this page");
      note.setName(PDAnnotationText.NAME_COMMENT);
      note.setOpen(true);
      note.setPrinted(true);
      first.getAnnotations().add(note);

      PDActionURI uri = new PDActionURI();
      uri.setURI("https://pdfbox.apache.org/");
      uri.setTrackMousePosition(true);
      PDActionJavaScript chainedScript =
          new PDActionJavaScript("app.alert('stored, not executed');");
      uri.setNext(Collections.singletonList(chainedScript));
      PDAnnotationLink link = new PDAnnotationLink();
      link.setRectangle(new PDRectangle(40, 560, 220, 24));
      link.setContents("PDFBox");
      link.setHighlightMode(PDAnnotationLink.HIGHLIGHT_MODE_OUTLINE);
      PDBorderStyleDictionary linkBorder = new PDBorderStyleDictionary();
      linkBorder.setStyle(PDBorderStyleDictionary.STYLE_DASHED);
      linkBorder.setWidth(2);
      COSArray dash = new COSArray();
      dash.add(COSInteger.get(3));
      dash.add(COSInteger.get(2));
      linkBorder.setDashStyle(dash);
      link.setBorderStyle(linkBorder);
      link.setAction(uri);
      first.getAnnotations().add(link);

      PDAnnotationSquare square = new PDAnnotationSquare();
      square.setRectangle(new PDRectangle(300, 560, 80, 60));
      square.setContents("Manual appearance");
      PDBorderEffectDictionary effect = new PDBorderEffectDictionary();
      effect.setStyle(PDBorderEffectDictionary.STYLE_CLOUDY);
      effect.setIntensity(1.5f);
      square.setBorderEffect(effect);
      square.setRectDifferences(2, 3, 4, 5);
      PDAppearanceStream appearance = new PDAppearanceStream(document);
      appearance.setBBox(new PDRectangle(80, 60));
      appearance.setResources(new PDResources());
      try (java.io.OutputStream stream = appearance.getCOSObject().createOutputStream()) {
        stream.write("0 0 80 60 re S\n".getBytes(StandardCharsets.US_ASCII));
      }
      PDAppearanceDictionary appearanceDictionary = new PDAppearanceDictionary();
      appearanceDictionary.setNormalAppearance(appearance);
      square.setAppearance(appearanceDictionary);
      first.getAnnotations().add(square);

      PDPageXYZDestination xyz = new PDPageXYZDestination();
      xyz.setPage(second);
      xyz.setLeft(12);
      xyz.setTop(250);
      xyz.setZoom(1.25f);
      PDDestinationNameTreeNode destinations = new PDDestinationNameTreeNode();
      Map<String, PDPageDestination> destinationNames = new TreeMap<>();
      destinationNames.put("chapter.two", xyz);
      destinations.setNames(destinationNames);

      PDActionJavaScript namedScript =
          new PDActionJavaScript("this.print({bUI:false});");
      PDJavascriptNameTreeNode scripts = new PDJavascriptNameTreeNode();
      Map<String, PDActionJavaScript> scriptNames = new TreeMap<>();
      scriptNames.put("print.silent", namedScript);
      scripts.setNames(scriptNames);

      byte[] attachmentBytes =
          "PdfCube representative attachment\n".getBytes(StandardCharsets.UTF_8);
      PDEmbeddedFile embedded =
          new PDEmbeddedFile(document, new ByteArrayInputStream(attachmentBytes));
      embedded.setSubtype("text/plain");
      embedded.setSize(attachmentBytes.length);
      embedded.setCheckSum("0123456789abcdef");
      embedded.setCreationDate(fixedCalendar());
      embedded.setModDate(fixedCalendar());
      PDComplexFileSpecification specification = new PDComplexFileSpecification();
      specification.setFile("contract.txt");
      specification.setFileUnicode("contract.txt");
      specification.setFileDescription("Interaction contract");
      specification.setEmbeddedFile(embedded);
      specification.setEmbeddedFileUnicode(embedded);
      PDEmbeddedFilesNameTreeNode embeddedFiles = new PDEmbeddedFilesNameTreeNode();
      Map<String, PDComplexFileSpecification> attachmentNames = new TreeMap<>();
      attachmentNames.put("contract.txt", specification);
      embeddedFiles.setNames(attachmentNames);

      PDDocumentNameDictionary names = new PDDocumentNameDictionary(catalog);
      names.setDests(destinations);
      names.setJavascript(scripts);
      names.setEmbeddedFiles(embeddedFiles);
      catalog.setNames(names);

      PDDocumentOutline outline = new PDDocumentOutline();
      PDOutlineItem chapter = new PDOutlineItem();
      chapter.setTitle("Chapter Two");
      chapter.setBold(true);
      chapter.setItalic(true);
      chapter.setDestination(xyz);
      PDOutlineItem child = new PDOutlineItem();
      child.setTitle("Named Child");
      PDActionGoTo goTo = new PDActionGoTo();
      goTo.setDestination(new PDNamedDestination("chapter.two"));
      child.setAction(goTo);
      chapter.addLast(child);
      chapter.openNode();
      outline.addLast(chapter);
      outline.openNode();
      catalog.setDocumentOutline(outline);

      PDPageLabels labels = new PDPageLabels(document);
      PDPageLabelRange cover = new PDPageLabelRange();
      cover.setPrefix("Cover-");
      labels.setLabelItem(0, cover);
      PDPageLabelRange body = new PDPageLabelRange();
      body.setStyle(PDPageLabelRange.STYLE_ROMAN_LOWER);
      body.setPrefix("Body-");
      body.setStart(4);
      labels.setLabelItem(1, body);
      catalog.setPageLabels(labels);

      PDThread thread = new PDThread();
      PDDocumentInformation threadInfo = new PDDocumentInformation();
      threadInfo.setTitle("Review thread");
      thread.setThreadInfo(threadInfo);
      PDThreadBead firstBead = new PDThreadBead();
      firstBead.setPage(first);
      firstBead.setRectangle(new PDRectangle(20, 20, 100, 50));
      firstBead.setThread(thread);
      PDThreadBead secondBead = new PDThreadBead();
      secondBead.setPage(second);
      secondBead.setRectangle(new PDRectangle(30, 30, 120, 60));
      secondBead.setThread(thread);
      firstBead.appendBead(secondBead);
      thread.setFirstBead(firstBead);
      catalog.setThreads(Collections.singletonList(thread));

      catalog.setOpenAction(new PDActionJavaScript("console.println('model only');"));
      document.getDocumentInformation().setTitle("Interaction Contract");
      document.save(path.toFile(), CompressParameters.NO_COMPRESSION);
    }
  }

  private static void addWidget(
      PDField field, PDPage page, PDRectangle rectangle) throws IOException {
    PDAnnotationWidget widget = field.getWidgets().get(0);
    widget.setRectangle(rectangle);
    widget.setPage(page);
    page.getAnnotations().add(widget);
  }

  private static void observeFormModel() throws Exception {
    Path source = fixture(
        "org/apache/pdfbox/pdmodel/interactive/form/MultilineFields.pdf");
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDAcroForm form = document.getDocumentCatalog().getAcroForm();
      List<String> fields = new ArrayList<>();
      int widgets = 0;
      int widgetAppearances = 0;
      for (PDField field : form.getFieldTree()) {
        fields.add(
            render(field.getFullyQualifiedName()) + ":" + field.getClass().getSimpleName());
        widgets += field.getWidgets().size();
        for (PDAnnotationWidget widget : field.getWidgets()) {
          if (widget.getNormalAppearanceStream() != null) {
            widgetAppearances++;
          }
        }
      }
      Collections.sort(fields);
      observe(
          "form-model",
          "upstream-field-tree",
          form.getFields().size(),
          fields.size(),
          widgets,
          widgetAppearances,
          fields.get(0),
          fields.get(fields.size() - 1),
          form.getDefaultAppearance(),
          form.getNeedAppearances());
    }

    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      PDAcroForm form = new PDAcroForm(document);
      form.setNeedAppearances(true);
      form.setDefaultResources(new PDResources());
      document.getDocumentCatalog().setAcroForm(form);
      form = document.getDocumentCatalog().getAcroForm();
      PDTextField text = new PDTextField(form);
      text.setPartialName("invoice-number");
      text.setAlternateFieldName("Invoice number");
      text.setMappingName("invoice-id");
      text.setRequired(true);
      text.setMaxLen(12);
      text.setMultiline(false);
      text.setValue("INV-42");
      PDComboBox choice = new PDComboBox(form);
      choice.setPartialName("invoice-status");
      choice.setOptions(
          Arrays.asList("new", "paid"), Arrays.asList("New", "Paid"));
      choice.setValue("paid");
      form.setFields(new ArrayList<>(Arrays.asList(text, choice)));
      form.setCalcOrder(new ArrayList<>(Arrays.asList(choice, text)));
      form.setCacheFields(true);
      observe(
          "form-model",
          "mutation-and-calculation-order",
          form.getFields().size(),
          form.getField("invoice-number").getValueAsString(),
          form.getField("invoice-status").getValueAsString(),
          text.getAlternateFieldName(),
          text.getMappingName(),
          text.isRequired(),
          text.getMaxLen(),
          choice.hasSeparateExportAndDisplayValues(),
          joinFields(form.getCalcOrder()),
          form.isCachingFields());
    }
  }

  private static void observeFormImportExport() throws Exception {
    Path fdfPath = exchange.resolve("java-export.fdf");
    Path xfdfPath = exchange.resolve("java-export.xfdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      PDAcroForm form = new PDAcroForm(document);
      form.setNeedAppearances(true);
      form.setDefaultResources(new PDResources());
      document.getDocumentCatalog().setAcroForm(form);
      form = document.getDocumentCatalog().getAcroForm();
      PDTextField text = new PDTextField(form);
      text.setPartialName("account-owner");
      text.setValue("Before");
      form.setFields(new ArrayList<>(Collections.singletonList(text)));

      try (FDFDocument exported = form.exportFDF()) {
        List<FDFField> fields = exported.getCatalog().getFDF().getFields();
        fields.get(0).setValue("After");
        form.importFDF(exported);
        exported.save(fdfPath.toFile());
        exported.saveXFDF(xfdfPath.toFile());
        observe(
            "form-import-export",
            "export-mutate-import",
            fields.size(),
            fields.get(0).getPartialFieldName(),
            fields.get(0).getValue(),
            text.getValue(),
            Files.size(fdfPath) > 0,
            new String(Files.readAllBytes(xfdfPath), StandardCharsets.UTF_8)
                .contains("account-owner"));
      }
    }
    try (FDFDocument loadedFdf = Loader.loadFDF(fdfPath.toFile());
        FDFDocument loadedXfdf = Loader.loadXFDF(xfdfPath.toFile())) {
      observe(
          "form-import-export",
          "fdf-xfdf-reopen",
          loadedFdf.getCatalog().getFDF().getFields().size(),
          loadedFdf.getCatalog().getFDF().getFields().get(0).getValue(),
          loadedXfdf.getCatalog().getFDF().getFields().size(),
          loadedXfdf.getCatalog().getFDF().getFields().get(0).getValue());
    }
  }

  private static void observeFlatten() throws Exception {
    Path source = fixture(
        "org/apache/pdfbox/pdmodel/interactive/form/MultilineFields.pdf");
    Path flattened = exchange.resolve("java-flattened.pdf");
    int beforeFields;
    int beforeAnnotations;
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDAcroForm form = document.getDocumentCatalog().getAcroForm();
      beforeFields = form.getFields().size();
      beforeAnnotations = document.getPage(0).getAnnotations().size();
      PDField field = form.getField("AlignLeft-Filled");
      form.flatten(Collections.singletonList(field), false);
      document.save(flattened.toFile(), CompressParameters.NO_COMPRESSION);
      observe(
          "form-flatten",
          "focused-upstream-field",
          beforeFields,
          form.getFields().size(),
          form.getField("AlignLeft-Filled") == null,
          beforeAnnotations,
          document.getPage(0).getAnnotations().size());
    }
    try (PDDocument reopened = Loader.loadPDF(flattened.toFile())) {
      observe(
          "form-flatten",
          "serialization-reopen",
          reopened.getDocumentCatalog().getAcroForm().getFields().size(),
          reopened.getDocumentCatalog().getAcroForm().getField("AlignLeft-Filled") == null,
          reopened.getPage(0).getAnnotations().size(),
          countContentStreams(reopened.getPage(0)));
    }
  }

  private static void observeMalformedForms() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDAcroForm form = new PDAcroForm(document);
      document.getDocumentCatalog().setAcroForm(form);
      form.getCOSObject().removeItem(COSName.FIELDS);
      observe(
          "form-malformed",
          "missing-required-fields",
          form.getFields() != null,
          form.getFields().isEmpty(),
          form.getField("missing") == null);
    }

    String invalidType;
    try (PDDocument document = new PDDocument()) {
      COSDictionary dictionary = new COSDictionary();
      COSArray fields = new COSArray();
      COSDictionary field = new COSDictionary();
      field.setName(COSName.FT, "UnknownField");
      field.setString(COSName.T, "broken");
      fields.add(field);
      dictionary.setItem(COSName.FIELDS, fields);
      PDAcroForm form = new PDAcroForm(document, dictionary);
      invalidType = failureCategory(() -> form.getFields());
    }

    String invalidAppearance;
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDAcroForm form = new PDAcroForm(document);
      document.getDocumentCatalog().setAcroForm(form);
      form.setDefaultResources(new PDResources());
      PDTextField text = new PDTextField(form);
      text.setPartialName("SampleField");
      text.setDefaultAppearance("/Helv 0 tf 0 g");
      form.getFields().add(text);
      addWidget(text, page, new PDRectangle(50, 750, 200, 20));
      invalidAppearance = failureCategory(() -> text.setValue("value"));
    }
    observe(
        "form-malformed",
        "unknown-type-and-bad-appearance",
        invalidType,
        invalidAppearance);
  }

  private static void observeAnnotationFixture() throws Exception {
    Path source = fixture(
        "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf");
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      Map<String, Integer> subtypes = new TreeMap<>();
      int appearances = 0;
      int borderStyles = 0;
      for (PDPage page : document.getPages()) {
        for (PDAnnotation annotation : page.getAnnotations()) {
          subtypes.merge(render(annotation.getSubtype()), 1, Integer::sum);
          if (annotation.getNormalAppearanceStream() != null) {
            appearances++;
          }
          if (annotation.getCOSObject().containsKey(COSName.BS)) {
            borderStyles++;
          }
        }
      }
      observe(
          "annotation-fixture",
          "upstream-types-and-appearances",
          document.getNumberOfPages(),
          subtypes.values().stream().mapToInt(Integer::intValue).sum(),
          joinCounts(subtypes),
          appearances,
          borderStyles);
    }
  }

  private static void observeAnnotationModel() throws Exception {
    PDAnnotationText text = new PDAnnotationText();
    text.setContents("A note");
    text.setName(PDAnnotationText.NAME_KEY);
    text.setOpen(true);
    text.setHidden(true);
    text.setPrinted(true);
    text.setNoZoom(true);
    text.setAnnotationName("note-1");
    text.setModifiedDate("D:20200102030405Z");
    text.setRectangle(new PDRectangle(1, 2, 30, 40));

    PDAnnotationLink link = new PDAnnotationLink();
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://example.test/π");
    link.setAction(uri);
    link.setHighlightMode(PDAnnotationLink.HIGHLIGHT_MODE_PUSH);
    link.setQuadPoints(new float[] {1, 2, 3, 4, 5, 6, 7, 8});
    PDBorderStyleDictionary border = new PDBorderStyleDictionary();
    border.setStyle(PDBorderStyleDictionary.STYLE_UNDERLINE);
    border.setWidth(3.5f);
    link.setBorderStyle(border);

    observe(
        "annotation-model",
        "flags-border-link-and-factory",
        text.getSubtype(),
        text.getContents(),
        text.getName(),
        text.getOpen(),
        text.isHidden(),
        text.isPrinted(),
        text.isNoZoom(),
        text.getAnnotationName(),
        text.getRectangle().getWidth(),
        text.getRectangle().getHeight(),
        link.getHighlightMode(),
        link.getBorderStyle().getStyle(),
        link.getBorderStyle().getWidth(),
        link.getQuadPoints().length,
        ((PDActionURI) link.getAction()).getURI(),
        PDAnnotation.createAnnotation(text.getCOSObject()).getClass().getSimpleName(),
        PDAnnotation.createAnnotation(link.getCOSObject()).getClass().getSimpleName());

    List<PDAnnotation> supported =
        Arrays.asList(
            new PDAnnotationFileAttachment(),
            new PDAnnotationLine(),
            new PDAnnotationLink(),
            new PDAnnotationPopup(),
            new PDAnnotationRubberStamp(),
            new PDAnnotationSquare(),
            new PDAnnotationCircle(),
            new PDAnnotationPolygon(),
            new PDAnnotationPolyline(),
            new PDAnnotationInk(),
            new PDAnnotationText(),
            new PDAnnotationHighlight(),
            new PDAnnotationUnderline(),
            new PDAnnotationStrikeout(),
            new PDAnnotationSquiggly(),
            new PDAnnotationWidget(),
            new PDAnnotationFreeText(),
            new PDAnnotationCaret(),
            new PDAnnotationSound());
    List<String> factoryTypes = new ArrayList<>();
    for (PDAnnotation annotation : supported) {
      factoryTypes.add(
          annotation.getSubtype()
              + ":"
              + PDAnnotation.createAnnotation(annotation.getCOSObject())
                  .getClass()
                  .getSimpleName());
    }
    COSDictionary unsupported = new COSDictionary();
    unsupported.setName(COSName.SUBTYPE, "3D");
    observe(
        "annotation-model",
        "supported-and-unknown-subtypes",
        supported.size(),
        String.join(",", factoryTypes),
        PDAnnotation.createAnnotation(unsupported).getClass().getSimpleName(),
        failureCategory(() -> PDAnnotation.createAnnotation(new COSString("bad"))));

    try (PDDocument document = new PDDocument()) {
      PDAppearanceStream stream = new PDAppearanceStream(document);
      stream.setBBox(new PDRectangle(10, 20));
      stream.setResources(new PDResources());
      PDAppearanceDictionary dictionary = new PDAppearanceDictionary();
      dictionary.setNormalAppearance(stream);
      PDAnnotationSquare square = new PDAnnotationSquare();
      square.setAppearance(dictionary);
      PDAppearanceEntry entry = square.getAppearance().getNormalAppearance();
      observe(
          "annotation-appearance",
          "stream-dictionary",
          entry.isStream(),
          entry.isSubDictionary(),
          entry.getAppearanceStream().getBBox().getWidth(),
          entry.getAppearanceStream().getBBox().getHeight(),
          square.getNormalAppearanceStream() != null);
    }
  }

  private static void observeActions() throws Exception {
    PDPageFitDestination fit = new PDPageFitDestination();
    fit.setPageNumber(2);
    fit.setFitBoundingBox(true);
    PDActionGoTo goTo = new PDActionGoTo();
    goTo.setDestination(new PDNamedDestination("page.two"));
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://pdfbox.apache.org/");
    uri.setTrackMousePosition(true);
    PDActionJavaScript javascript = new PDActionJavaScript("var answer = 42;");
    PDActionNamed named = new PDActionNamed();
    named.setN("NextPage");
    PDActionLaunch launch = new PDActionLaunch();
    launch.setF("readme.txt");
    launch.setD("/tmp");
    launch.setO("open");
    launch.setP("--safe");
    PDActionResetForm reset = new PDActionResetForm();
    reset.setFlags(1);
    COSArray resetFields = new COSArray();
    resetFields.add(new COSString("invoice-number"));
    reset.setFields(resetFields);
    PDActionSubmitForm submit = new PDActionSubmitForm();
    submit.setFlags(4);
    PDActionHide hide = new PDActionHide();
    hide.setT(new COSString("invoice-number"));
    hide.setH(true);
    uri.setNext(Arrays.asList(javascript, named));

    List<PDAction> actions =
        Arrays.asList(goTo, uri, javascript, named, launch, reset, submit, hide);
    List<String> reconstructed = new ArrayList<>();
    for (PDAction action : actions) {
      reconstructed.add(
          PDActionFactory.createAction(action.getCOSObject()).getClass().getSimpleName()
              + ":"
              + action.getSubType());
    }
    observe(
        "action",
        "factory-and-properties",
        String.join(",", reconstructed),
        fit.getPageNumber(),
        fit.fitBoundingBox(),
        uri.getURI(),
        uri.shouldTrackMousePosition(),
        uri.getNext().size(),
        javascript.getAction(),
        named.getN(),
        launch.getF(),
        launch.getD(),
        launch.getO(),
        launch.getP(),
        reset.getFlags(),
        reset.getFields().size(),
        submit.getFlags(),
        hide.getH());

    FDFJavaScript fdfJavaScript = new FDFJavaScript();
    fdfJavaScript.setBefore("beforeImport();");
    fdfJavaScript.setAfter("afterImport();");
    Map<String, PDActionJavaScript> documentScripts = new LinkedHashMap<>();
    documentScripts.put("validate", new PDActionJavaScript("validate();"));
    fdfJavaScript.setDoc(documentScripts);
    observe(
        "javascript",
        "fdf-model",
        fdfJavaScript.getBefore(),
        fdfJavaScript.getAfter(),
        fdfJavaScript.getDoc().size(),
        fdfJavaScript.getDoc().get("validate") == null);
  }

  private static void observeDestinationsAndOutlines() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage first = new PDPage();
      PDPage second = new PDPage();
      document.addPage(first);
      document.addPage(second);
      PDPageXYZDestination xyz = new PDPageXYZDestination();
      xyz.setPage(second);
      xyz.setLeft(25);
      xyz.setTop(700);
      xyz.setZoom(1.5f);
      PDDestinationNameTreeNode tree = new PDDestinationNameTreeNode();
      tree.setNames(Collections.singletonMap("target", xyz));
      PDDocumentNameDictionary names =
          new PDDocumentNameDictionary(document.getDocumentCatalog());
      names.setDests(tree);
      document.getDocumentCatalog().setNames(names);

      PDDocumentOutline outline = new PDDocumentOutline();
      PDOutlineItem root = new PDOutlineItem();
      root.setTitle("Root");
      root.setDestination(xyz);
      root.setBold(true);
      PDOutlineItem child = new PDOutlineItem();
      child.setTitle("Child");
      child.setDestination(new PDNamedDestination("target"));
      root.addLast(child);
      root.openNode();
      outline.addLast(root);
      outline.openNode();
      document.getDocumentCatalog().setDocumentOutline(outline);

      observe(
          "destination",
          "xyz-and-named",
          xyz.retrievePageNumber(),
          xyz.getLeft(),
          xyz.getTop(),
          xyz.getZoom(),
          tree.getValue("target").retrievePageNumber(),
          document.getDocumentCatalog()
              .findNamedDestinationPage(new PDNamedDestination("target"))
              .retrievePageNumber());
      observe(
          "named-destination",
          "name-tree-limits-and-lookup",
          tree.getNames().size(),
          tree.getLowerLimit(),
          tree.getUpperLimit(),
          tree.getValue("missing") == null);
      observe(
          "outline",
          "mutation-and-resolution",
          outline.getOpenCount(),
          root.getOpenCount(),
          root.isNodeOpen(),
          root.getTitle(),
          root.isBold(),
          root.getFirstChild().getTitle(),
          document.getPages().indexOf(root.findDestinationPage(document)),
          document.getPages().indexOf(child.findDestinationPage(document)));
    }

    Path source = fixture("org/apache/pdfbox/pdmodel/with_outline.pdf");
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
      List<String> titles = new ArrayList<>();
      collectOutlineTitles(outline.getFirstChild(), titles);
      observe(
          "outline",
          "upstream-fixture",
          titles.size(),
          String.join(",", titles),
          outline.getFirstChild().findDestinationPage(document) != null);
    }
  }

  private static void observePageLabels() throws Exception {
    try (PDDocument document = new PDDocument()) {
      for (int i = 0; i < 5; i++) {
        document.addPage(new PDPage());
      }
      PDPageLabels labels = new PDPageLabels(document);
      PDPageLabelRange front = new PDPageLabelRange();
      front.setStyle(PDPageLabelRange.STYLE_ROMAN_LOWER);
      front.setPrefix("Front-");
      labels.setLabelItem(0, front);
      PDPageLabelRange body = new PDPageLabelRange();
      body.setStyle(PDPageLabelRange.STYLE_DECIMAL);
      body.setPrefix("P-");
      body.setStart(10);
      labels.setLabelItem(2, body);
      document.getDocumentCatalog().setPageLabels(labels);
      observe(
          "page-label",
          "mutation",
          labels.getPageRangeCount(),
          join(labels.getLabelsByPageIndices()),
          labels.getPageIndices().toString(),
          labels.getPageIndicesByLabels().get("P-10"));
    }

    Path source = fixture("org/apache/pdfbox/pdmodel/test_pagelabels.pdf");
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDPageLabels labels = document.getDocumentCatalog().getPageLabels();
      String[] rendered = labels.getLabelsByPageIndices();
      observe(
          "page-label",
          "upstream-fixture",
          document.getNumberOfPages(),
          labels.getPageRangeCount(),
          rendered.length,
          rendered[0],
          rendered[rendered.length - 1],
          labels.getPageIndices().toString());
    }
  }

  private static void observeAttachments() throws Exception {
    Path source = fixture(
        "org/apache/pdfbox/pdmodel/common/testPDF_multiFormatEmbFiles.pdf");
    try (PDDocument document = Loader.loadPDF(source.toFile())) {
      PDEmbeddedFilesNameTreeNode tree =
          document.getDocumentCatalog().getNames().getEmbeddedFiles();
      int kids = tree.getKids() == null ? 0 : tree.getKids().size();
      List<String> names = new ArrayList<>();
      int variants = 0;
      if (tree.getNames() != null) {
        names.addAll(tree.getNames().keySet());
      }
      if (tree.getKids() != null) {
        for (PDNameTreeNode<PDComplexFileSpecification> kid : tree.getKids()) {
          if (kid.getNames() == null) {
            continue;
          }
          names.addAll(kid.getNames().keySet());
          for (PDComplexFileSpecification specification : kid.getNames().values()) {
            variants += specification.getEmbeddedFile() != null ? 1 : 0;
            variants += specification.getEmbeddedFileMac() != null ? 1 : 0;
            variants += specification.getEmbeddedFileDos() != null ? 1 : 0;
            variants += specification.getEmbeddedFileUnix() != null ? 1 : 0;
          }
        }
      }
      Collections.sort(names);
      observe(
          "attachment",
          "upstream-fixture",
          kids,
          names.size(),
          String.join(",", names),
          variants);
    }

    try (PDDocument document = new PDDocument()) {
      byte[] bytes = "attachment-data".getBytes(StandardCharsets.UTF_8);
      PDEmbeddedFile embedded =
          new PDEmbeddedFile(document, new ByteArrayInputStream(bytes));
      embedded.setSubtype("text/plain");
      embedded.setSize(bytes.length);
      embedded.setCheckSum("checksum");
      PDComplexFileSpecification specification = new PDComplexFileSpecification();
      specification.setFile("data.txt");
      specification.setFileUnicode("δ-data.txt");
      specification.setFileDescription("Data");
      specification.setVolatile(true);
      specification.setEmbeddedFile(embedded);
      specification.setEmbeddedFileUnicode(embedded);
      observe(
          "attachment",
          "mutation",
          specification.getFilename(),
          specification.getFile(),
          specification.getFileUnicode(),
          specification.getFileDescription(),
          specification.isVolatile(),
          specification.getEmbeddedFile().getSubtype(),
          specification.getEmbeddedFile().getSize(),
          specification.getEmbeddedFile().getCheckSum(),
          new String(specification.getEmbeddedFile().toByteArray(), StandardCharsets.UTF_8));
    }
  }

  private static void observeCrossRuntimeReopen(Path path) throws Exception {
    try (PDDocument document = Loader.loadPDF(path.toFile())) {
      PDDocumentCatalog catalog = document.getDocumentCatalog();
      PDAcroForm form = catalog.getAcroForm();
      PDActionJavaScript openAction = (PDActionJavaScript) catalog.getOpenAction();
      PDDocumentNameDictionary names = catalog.getNames();
      PDPageDestination destination = names.getDests().getValue("chapter.two");
      PDActionJavaScript namedScript = names.getJavaScript().getValue("print.silent");
      PDComplexFileSpecification specification =
          names.getEmbeddedFiles().getValue("contract.txt");
      PDDocumentOutline outline = catalog.getDocumentOutline();
      PDOutlineItem chapter = outline.getFirstChild();
      PDThread thread = catalog.getThreads().get(0);
      PDThreadBead firstBead = thread.getFirstBead();
      List<PDAnnotation> annotations = document.getPage(0).getAnnotations();
      Map<String, Integer> subtypes = new TreeMap<>();
      for (PDAnnotation annotation : annotations) {
        subtypes.merge(annotation.getSubtype(), 1, Integer::sum);
      }
      int formAppearances = 0;
      for (PDField field : form.getFieldTree()) {
        for (PDAnnotationWidget widget : field.getWidgets()) {
          if (widget.getNormalAppearanceStream() != null) {
            formAppearances++;
          }
        }
      }

      observe(
          "representative-reopen",
          "cross-runtime-model",
          document.getNumberOfPages(),
          document.getDocumentInformation().getTitle(),
          form.getFields().size(),
          form.getField("customer-name").getValueAsString(),
          form.getField("customer-plan").getValueAsString(),
          formAppearances,
          joinFields(form.getCalcOrder()),
          joinCounts(subtypes),
          chapter.getTitle(),
          chapter.isBold(),
          chapter.isItalic(),
          document.getPages().indexOf(chapter.findDestinationPage(document)),
          destination.retrievePageNumber(),
          join(catalog.getPageLabels().getLabelsByPageIndices()),
          thread.getThreadInfo().getTitle(),
          firstBead.getNextBead() != null,
          firstBead.getNextBead().getPreviousBead() != null,
          specification.getFileUnicode(),
          specification.getEmbeddedFile().getSize(),
          new String(specification.getEmbeddedFile().toByteArray(), StandardCharsets.UTF_8)
              .trim());
      observe(
          "javascript",
          "stored-without-execution",
          openAction.getAction(),
          names.getJavaScript().getNames().size(),
          namedScript.getAction(),
          ((PDActionJavaScript)
                  ((PDActionURI)
                          ((PDAnnotationLink)
                                  annotations.stream()
                                      .filter(a -> a instanceof PDAnnotationLink)
                                      .findFirst()
                                      .orElseThrow(
                                          () -> new IllegalStateException("link missing")))
                              .getAction())
                      .getNext()
                      .get(0))
              .getAction());
      PDAnnotationSquare square =
          (PDAnnotationSquare)
              annotations.stream()
                  .filter(a -> a instanceof PDAnnotationSquare)
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalStateException("square missing"));
      observe(
          "annotation-appearance",
          "serialization-reopen",
          square.getNormalAppearanceStream() != null,
          square.getNormalAppearanceStream().getBBox().getWidth(),
          square.getNormalAppearanceStream().getBBox().getHeight(),
          square.getBorderEffect().getStyle(),
          square.getBorderEffect().getIntensity(),
          join(square.getRectDifferences()));
      observe(
          "thread",
          "serialization-reopen",
          catalog.getThreads().size(),
          thread.getThreadInfo().getTitle(),
          document.getPages().indexOf(firstBead.getPage()),
          document.getPages().indexOf(firstBead.getNextBead().getPage()),
          firstBead.getRectangle().getWidth(),
          firstBead.getNextBead().getRectangle().getHeight());
      observe(
          "named-destination",
          "serialization-reopen",
          names.getDests().getNames().size(),
          names.getDests().getLowerLimit(),
          names.getDests().getUpperLimit(),
          destination.retrievePageNumber());
    }
  }

  private static void collectOutlineTitles(PDOutlineItem item, List<String> titles) {
    for (PDOutlineItem current = item; current != null; current = current.getNextSibling()) {
      titles.add(current.getTitle());
      if (current.getFirstChild() != null) {
        collectOutlineTitles(current.getFirstChild(), titles);
      }
    }
  }

  private static int countContentStreams(PDPage page) {
    int count = 0;
    java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> iterator =
        page.getContentStreams();
    while (iterator.hasNext()) {
      iterator.next();
      count++;
    }
    return count;
  }

  private static Calendar fixedCalendar() {
    Calendar calendar =
        new GregorianCalendar(TimeZone.getTimeZone("GMT-07:00"), Locale.ROOT);
    calendar.clear();
    calendar.set(2020, Calendar.MAY, 4, 3, 2, 1);
    return calendar;
  }

  private static Path fixture(String relative) {
    return fixtures.resolve(relative);
  }

  private static String joinFields(List<PDField> fields) {
    return fields.stream().map(PDField::getFullyQualifiedName).collect(Collectors.joining(","));
  }

  private static String joinCounts(Map<String, Integer> counts) {
    return counts.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(","));
  }

  private static String join(Object[] values) {
    return Arrays.stream(values).map(PdfBoxInteractionOracle::render)
        .collect(Collectors.joining(","));
  }

  private static String join(float[] values) {
    List<String> rendered = new ArrayList<>();
    for (float value : values) {
      rendered.add(render(value));
    }
    return String.join(",", rendered);
  }

  private static String failureCategory(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (IllegalArgumentException error) {
      return "argument";
    } catch (UnsupportedOperationException error) {
      return "unsupported";
    } catch (IOException error) {
      return "io";
    } catch (NullPointerException error) {
      return "null";
    } catch (Throwable error) {
      return "other";
    }
  }

  private static void observe(String family, String id, Object... values) {
    List<String> rendered = new ArrayList<>(values.length);
    for (Object value : values) {
      rendered.add(render(value));
    }
    OBSERVATIONS.add(family + "\t" + id + "\t" + String.join("|", rendered));
  }

  private static String render(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Float || value instanceof Double) {
      return String.format(Locale.ROOT, "%.5f", ((Number) value).doubleValue())
          .replaceAll("0+$", "")
          .replaceAll("\\.$", ".0");
    }
    return String.valueOf(value).replace('\t', ' ').replace('\n', ' ');
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
