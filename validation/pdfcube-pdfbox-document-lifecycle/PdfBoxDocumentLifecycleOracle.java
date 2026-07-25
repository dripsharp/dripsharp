import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

public final class PdfBoxDocumentLifecycleOracle {
  private static final List<String> OBSERVATIONS = new ArrayList<>();
  private static Path exchange;

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected output trace and exchange directory.");
    }
    Path output = Paths.get(args[0]);
    exchange = Paths.get(args[1]);
    Files.createDirectories(exchange);
    writeRepresentative(exchange.resolve("java-lifecycle.pdf"));

    observeCatalogAndInformation();
    observePageInheritance();
    observePageTreeMutation();
    observeMalformedPageTrees();
    observeResources();
    observeContentStreams();
    observeImport();
    observeScratchAndStreamLifetime();
    observeLifecycleFailures();
    observeCrossRuntimeReopen(exchange.resolve("dotnet-lifecycle.pdf"));

    Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
  }

  private static void writeRepresentative(Path path) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDDocumentInformation info = document.getDocumentInformation();
      info.setTitle("Lifecycle Contract");
      info.setAuthor("Apache PDFBox");
      info.setCustomMetadataValue("Probe", "document-model");
      info.setCreationDate(fixedCalendar(-7 * 60, 2020, 5, 4, 3, 2, 1));
      info.setTrapped("Unknown");
      document.getDocumentCatalog().setLanguage("en-US");
      document.setVersion(1.5f);

      PDPage first = new PDPage(PDRectangle.A4);
      first.setRotation(90);
      document.addPage(first);
      try (PDPageContentStream content =
          new PDPageContentStream(
              document, first, PDPageContentStream.AppendMode.OVERWRITE, false)) {
        content.appendRawCommands("0 0 m\n10 10 l\nS\n");
      }

      PDPage second = new PDPage(new PDRectangle(320, 240));
      second.setResources(new PDResources());
      document.addPage(second);
      document.save(path.toFile(), CompressParameters.NO_COMPRESSION);
    }
  }

  private static void observeCatalogAndInformation() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDDocumentCatalog catalog = document.getDocumentCatalog();
      PDDocumentCatalog catalogAgain = document.getDocumentCatalog();
      PDPageTree pages = catalog.getPages();
      observe(
          "catalog",
          "default-and-caching",
          catalog == catalogAgain,
          catalog.getCOSObject().getCOSName(COSName.TYPE).getName(),
          pages.getCOSObject().getCOSName(COSName.TYPE).getName(),
          pages.getCount(),
          document.getNumberOfPages());

      catalog.setLanguage("fr-CA");
      observe(
          "catalog",
          "mutation",
          catalog.getLanguage(),
          pages.getCOSObject() == document.getPages().getCOSObject());

      PDDocumentInformation info = document.getDocumentInformation();
      info.setTitle("Title");
      info.setAuthor("Author");
      info.setSubject("Subject");
      info.setKeywords("one two");
      info.setCreator("Creator");
      info.setProducer("Producer");
      info.setCustomMetadataValue("Custom", "Value");
      info.setTrapped("True");
      Calendar date = fixedCalendar(330, 2021, 2, 3, 4, 5, 6);
      info.setCreationDate(date);
      Calendar modification = (Calendar) date.clone();
      modification.add(Calendar.MINUTE, 7);
      info.setModificationDate(modification);
      List<String> keys = new ArrayList<>(info.getMetadataKeys());
      Collections.sort(keys);
      observe(
          "document-info",
          "roundtrip",
          info == document.getDocumentInformation(),
          info.getTitle(),
          info.getAuthor(),
          info.getSubject(),
          info.getKeywords(),
          info.getCreator(),
          info.getProducer(),
          info.getCustomMetadataValue("Custom"),
          info.getTrapped(),
          info.getCreationDate().getTimeInMillis(),
          info.getCreationDate().getTimeZone().getRawOffset() / 60000.0,
          info.getModificationDate().getTimeInMillis(),
          info.getModificationDate().getTimeZone().getRawOffset() / 60000.0,
          String.join(",", keys));

      info.setTitle(null);
      info.setCustomMetadataValue("Custom", null);
      info.setTrapped(null);
      observe(
          "document-info",
          "null-and-validation",
          info.getTitle() == null,
          info.getCustomMetadataValue("Custom") == null,
          info.getTrapped() == null,
          fails(IllegalArgumentException.class, () -> info.setTrapped("Maybe")));

      observe(
          "document-version",
          "upgrade-and-downgrade",
          document.getVersion(),
          document.getDocument().getVersion(),
          catalog.getVersion());
      document.setVersion(1.3f);
      float afterDowngrade = document.getVersion();
      document.setVersion(1.7f);
      observe(
          "document-version",
          "upgrade-and-downgrade-result",
          afterDowngrade,
          document.getVersion(),
          document.getDocument().getVersion(),
          catalog.getVersion());
    }
  }

  private static void observePageInheritance() throws Exception {
    COSDictionary parent = new COSDictionary();
    parent.setItem(COSName.TYPE, COSName.PAGES);
    parent.setItem(COSName.MEDIA_BOX, new PDRectangle(10, 20, 300, 400));
    parent.setItem(COSName.CROP_BOX, new PDRectangle(20, 30, 250, 350));
    parent.setInt(COSName.ROTATE, -90);
    COSDictionary resourceDictionary = new COSDictionary();
    parent.setItem(COSName.RESOURCES, resourceDictionary);

    COSDictionary pageDictionary = new COSDictionary();
    pageDictionary.setItem(COSName.TYPE, COSName.PAGE);
    pageDictionary.setItem(COSName.PARENT, parent);
    PDPage page = new PDPage(pageDictionary);
    PDRectangle media = page.getMediaBox();
    PDRectangle crop = page.getCropBox();
    observe(
        "page-inheritance",
        "boxes-rotation-resources",
        media.getLowerLeftX(),
        media.getLowerLeftY(),
        media.getWidth(),
        media.getHeight(),
        crop.getLowerLeftX(),
        crop.getLowerLeftY(),
        crop.getWidth(),
        crop.getHeight(),
        page.getRotation(),
        resourceDictionary == page.getResources().getCOSObject());

    COSDictionary cyclePage = new COSDictionary();
    cyclePage.setItem(COSName.TYPE, COSName.PAGE);
    COSDictionary cycleParent = new COSDictionary();
    cycleParent.setItem(COSName.TYPE, COSName.PAGES);
    cyclePage.setItem(COSName.PARENT, cycleParent);
    cycleParent.setItem(COSName.PARENT, cyclePage);
    PDPage loopPage = new PDPage(cyclePage);
    observe(
        "page-inheritance",
        "cycle-and-default",
        loopPage.getResources() == null,
        loopPage.getMediaBox().getWidth(),
        loopPage.getMediaBox().getHeight());
  }

  private static void observePageTreeMutation() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage one = namedPage("one");
      PDPage two = namedPage("two");
      PDPage three = namedPage("three");
      PDPage four = namedPage("four");
      document.addPage(one);
      document.addPage(two);
      document.getPages().insertBefore(three, two);
      document.getPages().insertAfter(four, two);

      observe(
          "page-tree-mutation",
          "insert-and-iterate",
          document.getPages().getCount(),
          pageOrder(document.getPages()),
          document.getPages().indexOf(one),
          document.getPages().indexOf(three),
          document.getPages().indexOf(two),
          document.getPages().indexOf(four),
          document.getPages().indexOf(new PDPage()));

      document.removePage(three);
      document.removePage(0);
      observe(
          "page-tree-mutation",
          "remove",
          document.getNumberOfPages(),
          pageOrder(document.getPages()),
          document.getPage(0).getCOSObject().getString(COSName.getPDFName("Probe")));
    }
  }

  private static void observeMalformedPageTrees() throws Exception {
    COSDictionary root = pageTreeRoot(3);
    COSArray kids = root.getCOSArray(COSName.KIDS);
    kids.add((COSBase) null);
    kids.add(COSInteger.get(7));
    COSDictionary invalid = new COSDictionary();
    invalid.setItem(COSName.TYPE, COSName.XOBJECT);
    kids.add(invalid);
    PDPageTree malformed = new PDPageTree(root);
    observe(
        "page-tree-malformed",
        "null-nondictionary-invalid-type",
        pageOrder(malformed),
        kids.getObject(0) instanceof COSDictionary,
        malformed.get(0).getCOSObject().getCOSName(COSName.TYPE).getName());

    COSDictionary cycleRoot = pageTreeRoot(1);
    COSDictionary child = pageTreeRoot(1);
    cycleRoot.getCOSArray(COSName.KIDS).add(child);
    child.getCOSArray(COSName.KIDS).add(cycleRoot);
    PDPageTree cyclic = new PDPageTree(cycleRoot);
    observe(
        "page-tree-malformed",
        "cycle",
        pageOrder(cyclic),
        fails(IllegalStateException.class, () -> cyclic.get(0)));

    PDPageTree empty = new PDPageTree();
    Iterator<PDPage> iterator = empty.iterator();
    observe(
        "page-tree-error",
        "bounds-iterator-constructor",
        fails(IndexOutOfBoundsException.class, () -> empty.get(-1)),
        fails(IndexOutOfBoundsException.class, () -> empty.get(0)),
        fails(java.util.NoSuchElementException.class, iterator::next),
        fails(UnsupportedOperationException.class, iterator::remove),
        fails(IllegalArgumentException.class, () -> new PDPageTree(null)));
  }

  private static void observeResources() throws Exception {
    PDResources resources = new PDResources();
    PDExtendedGraphicsState firstState = new PDExtendedGraphicsState();
    firstState.setLineWidth(2.5f);
    COSName firstName = resources.add(firstState);
    COSName duplicateName = resources.add(firstState);
    COSName secondName = resources.add(new PDExtendedGraphicsState());
    List<String> names = new ArrayList<>();
    for (COSName name : resources.getExtGStateNames()) {
      names.add(name.getName());
    }
    Collections.sort(names);
    observe(
        "resource-ownership",
        "names-and-dictionary",
        firstName.getName(),
        duplicateName.getName(),
        secondName.getName(),
        String.join(",", names));

    COSDictionary indirectDictionary = new COSDictionary();
    indirectDictionary.setFloat(COSName.LW, 4.5f);
    COSObject indirect = new COSObject(indirectDictionary);
    COSDictionary kindDictionary = new COSDictionary();
    COSName name = COSName.getPDFName("gs9");
    kindDictionary.setItem(name, indirect);
    COSDictionary resourceDictionary = new COSDictionary();
    resourceDictionary.setItem(COSName.EXT_G_STATE, kindDictionary);
    DefaultResourceCache cache = new DefaultResourceCache();
    PDResources cachedResources = new PDResources(resourceDictionary, cache);
    PDExtendedGraphicsState loadedFirst = cachedResources.getExtGState(name);
    PDExtendedGraphicsState loadedSecond = cachedResources.getExtGState(name);
    PDExtendedGraphicsState removed = cache.removeExtState(indirect);
    PDExtendedGraphicsState loadedThird = cachedResources.getExtGState(name);
    observe(
        "resource-cache",
        "indirect-identity-removal",
        loadedFirst == loadedSecond,
        loadedFirst == removed,
        loadedFirst != loadedThird,
        loadedThird.getLineWidth());

    try (PDDocument document = new PDDocument()) {
      DefaultResourceCache customCache = new DefaultResourceCache(false);
      document.setResourceCache(customCache);
      PDPage page = new PDPage();
      document.addPage(page);
      observe(
          "resource-ownership",
          "document-page-cache",
          customCache == document.getResourceCache(),
          page.getResourceCache() == null,
          customCache == document.getPage(0).getResourceCache());
    }
  }

  private static void observeContentStreams() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      PDPageContentStream overwrite =
          new PDPageContentStream(
              document, page, PDPageContentStream.AppendMode.OVERWRITE, false);
      overwrite.appendRawCommands("0 0 m\n");
      overwrite.close();
      overwrite.close();
      try (PDPageContentStream append =
          new PDPageContentStream(
              document, page, PDPageContentStream.AppendMode.APPEND, false)) {
        append.appendRawCommands("1 1 l\n");
      }
      try (PDPageContentStream prepend =
          new PDPageContentStream(
              document, page, PDPageContentStream.AppendMode.PREPEND, false)) {
        prepend.appendRawCommands("q\n");
      }
      observe(
          "content-stream",
          "overwrite-append-prepend",
          page.hasContents(),
          countContentStreams(page),
          collapseWhitespace(readAll(page.getContents())),
          page.getResources() != null);

      try (PDPageContentStream invalidContent =
          new PDPageContentStream(
              document,
              new PDPage(),
              PDPageContentStream.AppendMode.OVERWRITE,
              false)) {
        boolean invalidColor =
            fails(
                IllegalArgumentException.class,
                () -> invalidContent.setNonStrokingColor(1.1f, 0, 0));
        invalidContent.beginText();
        boolean invalidPath =
            fails(IllegalStateException.class, () -> invalidContent.moveTo(0, 0));
        boolean nestedText =
            fails(IllegalStateException.class, invalidContent::beginText);
        invalidContent.endText();
        observe(
            "content-error",
            "validation-and-text-mode",
            invalidColor,
            invalidPath,
            nestedText);
      }

      PDPage emptyPage = new PDPage();
      try (InputStream emptyContents = emptyPage.getContents()) {
        observe(
            "content-stream",
            "missing",
            emptyPage.hasContents(),
            readAll(emptyContents).length);
      }
    }
  }

  private static void observeImport() throws Exception {
    try (PDDocument source = new PDDocument();
        PDDocument destination = new PDDocument()) {
      PDPage sourcePage = new PDPage(new PDRectangle(210, 310));
      sourcePage.setCropBox(new PDRectangle(10, 20, 180, 260));
      sourcePage.setRotation(270);
      source.addPage(sourcePage);
      try (PDPageContentStream content =
          new PDPageContentStream(
              source,
              sourcePage,
              PDPageContentStream.AppendMode.OVERWRITE,
              false)) {
        content.appendRawCommands("2 3 m\n");
      }
      PDResources inheritedResources = new PDResources();
      inheritedResources.add(new PDExtendedGraphicsState());
      source.getPages().getCOSObject().setItem(COSName.RESOURCES, inheritedResources);
      PDPage inheritedPage = new PDPage();
      source.addPage(inheritedPage);

      PDPage imported = destination.importPage(sourcePage);
      observe(
          "import",
          "page-content-attributes",
          destination.getNumberOfPages(),
          imported.getMediaBox().getWidth(),
          imported.getMediaBox().getHeight(),
          imported.getCropBox().getLowerLeftX(),
          imported.getCropBox().getLowerLeftY(),
          imported.getRotation(),
          collapseWhitespace(readAll(imported.getContents())),
          imported.getCOSObject().containsKey(COSName.PARENT),
          destination.getResourceCache() == imported.getResourceCache());
      observe(
          "import",
          "inherited-resource-policy",
          inheritedPage.getResources() != null,
          !inheritedPage.getCOSObject().containsKey(COSName.RESOURCES),
          destination.importPage(inheritedPage).getResources() == null,
          destination.getNumberOfPages());
    }
  }

  private static void observeScratchAndStreamLifetime() throws Exception {
    byte[] payload = new byte[131072];
    for (int index = 0; index < payload.length; index++) {
      payload[index] = (byte) (index * 31);
    }
    TrackingInputStream input = new TrackingInputStream(payload);
    byte[] saved;
    PDDocument scratch = new PDDocument(IOUtils.createTempFileOnlyStreamCache());
    PDPage page = new PDPage();
    page.setContents(new PDStream(scratch, input));
    scratch.addPage(page);
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      scratch.save(output, CompressParameters.NO_COMPRESSION);
      saved = output.toByteArray();
    }
    boolean scratchClosedBefore = scratch.getDocument().isClosed();
    scratch.close();
    observe(
        "scratch-storage",
        "temp-file-cache",
        input.closed,
        saved.length > payload.length,
        scratchClosedBefore,
        scratch.getDocument().isClosed());

    try (PDDocument reopened = Loader.loadPDF(saved);
        InputStream contents = reopened.getPage(0).getContents()) {
      observe(
          "scratch-storage",
          "reopen",
          reopened.getNumberOfPages(),
          readAll(contents).length);
    }

    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      TrackingOutputStream saveOutput = new TrackingOutputStream();
      document.save(saveOutput, CompressParameters.NO_COMPRESSION);
      boolean openAfterSave = !saveOutput.closed;
      saveOutput.close();
      observe(
          "stream-lifetime",
          "input-output-ownership",
          input.closed,
          openAfterSave);
    }
  }

  private static void observeLifecycleFailures() throws Exception {
    PDDocument document = new PDDocument();
    document.addPage(new PDPage());
    boolean incrementalFailure =
        fails(
            IllegalStateException.class,
            () -> document.saveIncremental(new ByteArrayOutputStream()));
    document.close();
    document.close();
    boolean closed = document.getDocument().isClosed();
    boolean saveFailure =
        fails(IOException.class, () -> document.save(new ByteArrayOutputStream()));
    observe(
        "document-lifecycle",
        "close-and-save-failures",
        closed,
        incrementalFailure,
        saveFailure);

    byte[] invalidBytes = "<script language='JavaScript'>".getBytes(StandardCharsets.UTF_8);
    boolean byteFailure = fails(IOException.class, () -> Loader.loadPDF(invalidBytes));
    Path invalidPath = exchange.resolve("invalid-java.pdf");
    Files.write(invalidPath, invalidBytes);
    boolean fileFailure =
        fails(IOException.class, () -> Loader.loadPDF(invalidPath.toFile()));
    Files.delete(invalidPath);
    observe(
        "loader-error",
        "invalid-byte-and-file",
        byteFailure,
        fileFailure,
        !Files.exists(invalidPath));
  }

  private static void observeCrossRuntimeReopen(Path path) throws Exception {
    try (PDDocument loaded = Loader.loadPDF(path.toFile())) {
      String firstContent =
          collapseWhitespace(readAll(loaded.getPage(0).getContents()));
      Calendar creationDate = loaded.getDocumentInformation().getCreationDate();
      String original =
          String.join(
              "|",
              Integer.toString(loaded.getNumberOfPages()),
              loaded.getDocumentInformation().getTitle(),
              loaded.getDocumentInformation().getAuthor(),
              loaded.getDocumentCatalog().getLanguage(),
              Float.toString(loaded.getVersion()),
              Long.toString(creationDate.getTimeInMillis()),
              Integer.toString(creationDate.getTimeZone().getRawOffset() / 60000),
              Integer.toString(loaded.getPage(0).getRotation()),
              firstContent);

      loaded.getDocumentInformation().setTitle("Mutated");
      loaded.addPage(new PDPage(new PDRectangle(100, 200)));
      loaded.removePage(0);
      try (ByteArrayOutputStream mutatedBytes = new ByteArrayOutputStream()) {
        loaded.save(mutatedBytes, CompressParameters.NO_COMPRESSION);
        try (PDDocument mutated = Loader.loadPDF(mutatedBytes.toByteArray())) {
          observe(
              "package-reopen",
              "cross-runtime-create-load-mutate",
              original,
              mutated.getNumberOfPages(),
              mutated.getDocumentInformation().getTitle(),
              mutated.getPage(1).getMediaBox().getWidth(),
              mutated.getPage(1).getMediaBox().getHeight());
        }
      }
    }

    Path copy = exchange.resolve("delete-after-close-java.pdf");
    Files.copy(path, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    PDDocument deletionDocument = Loader.loadPDF(copy.toFile());
    deletionDocument.close();
    Files.delete(copy);
    observe(
        "document-lifecycle",
        "loaded-file-release",
        deletionDocument.getDocument().isClosed(),
        !Files.exists(copy));
  }

  private static Calendar fixedCalendar(
      int offsetMinutes, int year, int month, int day, int hour, int minute, int second) {
    int absoluteMinutes = Math.abs(offsetMinutes);
    String id =
        String.format(
            Locale.ROOT,
            "GMT%s%02d:%02d",
            offsetMinutes < 0 ? "-" : "+",
            absoluteMinutes / 60,
            absoluteMinutes % 60);
    Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone(id), Locale.ROOT);
    calendar.clear();
    calendar.set(year, month - 1, day, hour, minute, second);
    calendar.set(Calendar.MILLISECOND, 0);
    return calendar;
  }

  private static PDPage namedPage(String name) {
    PDPage page = new PDPage();
    page.getCOSObject().setString(COSName.getPDFName("Probe"), name);
    return page;
  }

  private static COSDictionary pageTreeRoot(int count) {
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.TYPE, COSName.PAGES);
    root.setItem(COSName.KIDS, new COSArray());
    root.setInt(COSName.COUNT, count);
    return root;
  }

  private static String pageOrder(PDPageTree tree) {
    List<String> values = new ArrayList<>();
    for (PDPage page : tree) {
      String value =
          page.getCOSObject().getString(COSName.getPDFName("Probe"));
      if (value == null) {
        COSName type = page.getCOSObject().getCOSName(COSName.TYPE);
        value = type == null ? "null" : type.getName();
      }
      values.add(value);
    }
    return String.join(",", values);
  }

  private static int countContentStreams(PDPage page) {
    int count = 0;
    Iterator<PDStream> iterator = page.getContentStreams();
    while (iterator.hasNext()) {
      iterator.next();
      count++;
    }
    return count;
  }

  private static byte[] readAll(InputStream input) throws IOException {
    try (InputStream stream = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) != -1) {
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String collapseWhitespace(byte[] bytes) {
    String value = new String(bytes, StandardCharsets.US_ASCII).trim();
    return value.isEmpty() ? "" : String.join(" ", value.split("\\s+"));
  }

  private static boolean fails(
      Class<? extends Throwable> expected, ThrowingAction action) {
    try {
      action.run();
      return false;
    } catch (Throwable error) {
      return expected.isInstance(error);
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
    return String.valueOf(value).replace('\t', ' ').replace('\n', ' ');
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private TrackingInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }

  private static final class TrackingOutputStream extends ByteArrayOutputStream {
    private boolean closed;

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
