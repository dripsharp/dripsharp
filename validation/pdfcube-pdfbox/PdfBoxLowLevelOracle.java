import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSDocumentState;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.cos.COSUpdateInfo;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public final class PdfBoxLowLevelOracle {
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final List<String> observations = new ArrayList<String>();
  private static Path resources;
  private static Path exchange;

  private PdfBoxLowLevelOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected output trace, PdfBox test resources, and exchange directory.");
    }
    Path output = new File(args[0]).toPath();
    resources = new File(args[1]).toPath();
    exchange = new File(args[2]).toPath();
    Files.createDirectories(exchange);

    observeCOS();
    observeFilters();
    observePredictors();
    observeObjectStreams();
    Path full = writeFull("java");
    writeIncremental("java", full);
    observeParsing(full);
    observeFixtures();
    observeCrossRuntime("dotnet");

    Files.write(output, observations, StandardCharsets.UTF_8);
    System.out.println(
        "Pinned reviewed PDFBox baseline low-level oracle passed: "
            + observations.size()
            + " observations.");
  }

  private static void observeCOS() throws Exception {
    observe(
        "cos-identity",
        "singletons-and-caches",
        join(
            COSBoolean.getBoolean(true) == COSBoolean.TRUE,
            COSBoolean.getBoolean(false) == COSBoolean.FALSE,
            COSInteger.get(42) == COSInteger.get(42),
            COSName.getPDFName("Custom") == COSName.getPDFName("Custom"),
            COSNull.NULL == COSNull.NULL));

    COSDocumentState state = new COSDocumentState();
    state.setParsing(false);
    COSArray array = new COSArray();
    array.getUpdateState().setOriginDocumentState(state);
    COSDictionary indirect = new COSDictionary();
    COSObjectKey indirectKey = new COSObjectKey(7, 2);
    indirect.setDirect(false);
    indirect.setKey(indirectKey);
    array.add(indirect);
    observe(
        "cos-mutation",
        "array-wrap-dereference-update",
        join(
            array.isDirect(),
            array.size(),
            array.get(0) instanceof COSObject,
            array.getObject(0) == indirect,
            array.indexOfObject(indirect),
            array.isNeedToBeUpdated()));

    COSDictionary dictionary = new COSDictionary();
    dictionary.getUpdateState().setOriginDocumentState(state);
    dictionary.setInt(COSName.COUNT, 3);
    dictionary.setString(COSName.TITLE, "low-level");
    dictionary.setItem(COSName.A, array);
    dictionary.removeItem(COSName.COUNT);
    observe(
        "cos-mutation",
        "dictionary-set-remove-path",
        join(
            dictionary.containsKey(COSName.TITLE),
            dictionary.containsKey(COSName.COUNT),
            ((COSDictionary) dictionary.getObjectFromPath("A/\\[0\\]")) == indirect,
            dictionary.getString(COSName.TITLE),
            dictionary.isNeedToBeUpdated()));

    COSObjectKey key = new COSObjectKey(12, 4);
    COSObjectKey same = new COSObjectKey(12, 4, 8);
    COSDictionary first = new COSDictionary();
    COSDictionary second = new COSDictionary();
    first.setItem(COSName.BE, COSName.BE);
    second.setItem(COSName.BE, COSName.BE);
    COSStream stream = new COSStream();
    stream.setItem(COSName.BE, COSName.BE);
    observe(
        "cos-equality",
        "values-keys-and-stream-distinction",
        join(
            new COSString("same").equals(new COSString("same")),
            new COSFloat(1.25f).equals(new COSFloat(1.25f)),
            key.equals(same),
            key.hashCode() == same.hashCode(),
            key.compareTo(new COSObjectKey(13, 0)) < 0,
            first.equals(second),
            !first.equals(stream),
            !stream.equals(first)));
    stream.close();

    COSDictionary root = new COSDictionary();
    COSDictionary child = new COSDictionary();
    COSObjectKey rootKey = new COSObjectKey(1, 0);
    COSObjectKey childKey = new COSObjectKey(2, 0);
    COSObjectKey leafKey = new COSObjectKey(3, 0);
    root.setKey(rootKey);
    child.setKey(childKey);
    COSArray children = new COSArray();
    children.add(new COSObject(child, childKey));
    children.add(new COSObject(COSInteger.ONE, leafKey));
    root.setItem(COSName.KIDS, children);
    List<COSObjectKey> traversed = new ArrayList<COSObjectKey>();
    root.getIndirectObjectKeys(traversed);
    Collections.sort(traversed);
    observe("cos-traversal", "indirect-object-keys", joinObjectKeys(traversed));

    COSDocument document = new COSDocument();
    COSObject pooled1 = document.getObjectFromPool(new COSObjectKey(31, 0));
    COSObject pooled2 = document.getObjectFromPool(new COSObjectKey(31, 0, 9));
    COSStream owned = document.createCOSStream();
    try (OutputStream output = owned.createOutputStream()) {
      output.write(bytes(1, 2, 3, 4));
    }
    observe(
        "cos-identity",
        "document-object-pool",
        join(
            pooled1 == pooled2,
            pooled1.getObjectNumber(),
            pooled1.getGenerationNumber(),
            document.getXrefTable().size()));
    document.close();
    document.close();
    observe(
        "cos-lifecycle",
        "document-and-owned-stream-close",
        join(document.isClosed(), failureKind(() -> owned.createInputStream())));

    COSDocument regression = new COSDocument();
    Map<COSObjectKey, Long> malformedXref = new HashMap<COSObjectKey, Long>();
    malformedXref.put(null, 10L);
    regression.addXRefTable(malformedXref);
    observe(
        "cos-traversal",
        "null-xref-regression",
        join(
            regression.getObjectsByType(COSName.T).size(),
            regression.getLinearizedDictionary() == null));
    regression.close();
  }

  private static void observeFilters() throws Exception {
    String[] names = {
      "FlateDecode",
      "Fl",
      "DCTDecode",
      "DCT",
      "CCITTFaxDecode",
      "CCF",
      "LZWDecode",
      "LZW",
      "ASCIIHexDecode",
      "AHx",
      "ASCII85Decode",
      "A85",
      "RunLengthDecode",
      "RL",
      "Crypt",
      "JPXDecode",
      "JBIG2Decode"
    };
    List<String> types = new ArrayList<String>();
    for (String name : names) {
      types.add(FilterFactory.INSTANCE.getFilter(name).getClass().getSimpleName());
    }
    observe("filter-factory", "production-names-and-aliases", join(types));

    byte[] sample = filterSample();
    COSName[] roundTripNames = {
      COSName.ASCII85_DECODE,
      COSName.ASCII_HEX_DECODE,
      COSName.FLATE_DECODE,
      COSName.LZW_DECODE,
      COSName.RUN_LENGTH_DECODE,
      COSName.CRYPT
    };
    for (COSName name : roundTripNames) {
      Filter filter = FilterFactory.INSTANCE.getFilter(name);
      COSDictionary parameters = new COSDictionary();
      if (COSName.CRYPT.equals(name)) {
        parameters.setItem(COSName.NAME, COSName.IDENTITY);
      }
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      filter.encode(new ByteArrayInputStream(sample), encoded, parameters, 0);
      ByteArrayOutputStream decoded = new ByteArrayOutputStream();
      filter.decode(
          new ByteArrayInputStream(encoded.toByteArray()), decoded, parameters, 0);
      observe(
          "filter-roundtrip",
          name.getName(),
          COSName.FLATE_DECODE.equals(name)
              ? join(
                  "runtime-zlib",
                  encoded.size() > 2,
                  Arrays.equals(sample, decoded.toByteArray()),
                  sha256(decoded.toByteArray()))
              : join(
                  encoded.size(),
                  sha256(encoded.toByteArray()),
                  Arrays.equals(sample, decoded.toByteArray()),
                  sha256(decoded.toByteArray())));
    }

    Filter ccitt = FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE);
    byte[] bitmap =
        bytes(
            0x00, 0xff, 0x55, 0xaa, 0x0f, 0xf0, 0x33, 0xcc,
            0x81, 0x7e, 0x18, 0xe7, 0x42, 0xbd, 0x24, 0xdb);
    COSDictionary ccittParameters = new COSDictionary();
    ccittParameters.setInt(COSName.COLUMNS, 16);
    ccittParameters.setInt(COSName.ROWS, 8);
    ccittParameters.setItem(COSName.FILTER, COSName.CCITTFAX_DECODE);
    COSDictionary ccittDecode = new COSDictionary();
    ccittDecode.setInt(COSName.COLUMNS, 16);
    ccittDecode.setInt(COSName.ROWS, 8);
    ccittDecode.setInt(COSName.K, -1);
    ccittDecode.setBoolean(COSName.BLACK_IS_1, true);
    ccittParameters.setItem(COSName.DECODE_PARMS, ccittDecode);
    ByteArrayOutputStream ccittEncoded = new ByteArrayOutputStream();
    ccitt.encode(
        new ByteArrayInputStream(bitmap), ccittEncoded, ccittParameters, 0);
    ByteArrayOutputStream ccittDecoded = new ByteArrayOutputStream();
    ccitt.decode(
        new ByteArrayInputStream(ccittEncoded.toByteArray()),
        ccittDecoded,
        ccittParameters,
        0);
    observe(
        "filter-roundtrip",
        "CCITTFaxDecode",
        join(
            ccittEncoded.size(),
            sha256(ccittEncoded.toByteArray()),
            Arrays.equals(bitmap, ccittDecoded.toByteArray()),
            sha256(ccittDecoded.toByteArray())));

    Path jpeg =
        resources.resolve("org/apache/pdfbox/pdmodel/graphics/image/jpeg.jpg");
    byte[] jpegBytes = Files.readAllBytes(jpeg);
    ByteArrayOutputStream dctDecoded = new ByteArrayOutputStream();
    FilterFactory.INSTANCE
        .getFilter(COSName.DCT_DECODE)
        .decode(
            new ByteArrayInputStream(jpegBytes),
            dctDecoded,
            new COSDictionary(),
            0,
            DecodeOptions.DEFAULT);
    observe(
        "filter-roundtrip",
        "DCTDecode-fixture",
        join(jpegBytes.length, dctDecoded.size(), sha256(dctDecoded.toByteArray())));

    observe(
        "filter-error",
        "invalid-and-provider-dependent",
        join(
            failureKind(() -> FilterFactory.INSTANCE.getFilter("NoSuchFilter")),
            failureKind(
                () ->
                    FilterFactory.INSTANCE
                        .getFilter(COSName.JPX_DECODE)
                        .decode(
                            new ByteArrayInputStream(new byte[0]),
                            new ByteArrayOutputStream(),
                            new COSDictionary(),
                            0)),
            failureKind(
                () ->
                    FilterFactory.INSTANCE
                        .getFilter(COSName.JBIG2_DECODE)
                        .decode(
                            new ByteArrayInputStream(new byte[0]),
                            new ByteArrayOutputStream(),
                            new COSDictionary(),
                            0))));
  }

  private static void observePredictors() throws Exception {
    observePredictor(2, 1, 8, bytes(0x5d), bytes(0x69));
    observePredictor(2, 2, 4, bytes(0x1b), bytes(0x1e));
    observePredictor(2, 4, 2, bytes(0x13), bytes(0x14));
    observePredictor(2, 8, 5, bytes(1, 1, 1, 1, 1), bytes(1, 2, 3, 4, 5));
    observePredictor(
        2,
        16,
        3,
        bytes(0, 1, 0, 2, 0, 3),
        bytes(0, 1, 0, 3, 0, 6));

    byte[] first = bytes(3, 5, 8, 13, 21);
    byte[] second = bytes(34, 55, 89, 144, 233);
    for (int png = 0; png <= 4; png++) {
      byte[] encoded =
          concat(
              new byte[] {(byte) png},
              encodePngRow(first, null, png),
              new byte[] {(byte) png},
              encodePngRow(second, first, png));
      observePredictor(10 + png, 8, 5, encoded, concat(first, second));
    }
    byte[] adaptive =
        concat(
            new byte[] {0},
            encodePngRow(first, null, 0),
            new byte[] {4},
            encodePngRow(second, first, 4));
    observePredictor(15, 8, 5, adaptive, concat(first, second));
  }

  private static void observePredictor(
      int predictor, int bits, int columns, byte[] encodedRows, byte[] expected)
      throws Exception {
    Filter flate = FilterFactory.INSTANCE.getFilter(COSName.FLATE_DECODE);
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    flate.encode(
        new ByteArrayInputStream(encodedRows),
        compressed,
        new COSDictionary(),
        0);
    COSDictionary parameters = new COSDictionary();
    parameters.setItem(COSName.FILTER, COSName.FLATE_DECODE);
    COSDictionary decode = new COSDictionary();
    decode.setInt(COSName.PREDICTOR, predictor);
    decode.setInt(COSName.COLORS, 1);
    decode.setInt(COSName.BITS_PER_COMPONENT, bits);
    decode.setInt(COSName.COLUMNS, columns);
    parameters.setItem(COSName.DECODE_PARMS, decode);
    ByteArrayOutputStream actual = new ByteArrayOutputStream();
    flate.decode(
        new ByteArrayInputStream(compressed.toByteArray()), actual, parameters, 0);
    observe(
        "filter-predictor",
        predictor + "-bpc-" + bits,
        join(hex(expected), hex(actual.toByteArray()), Arrays.equals(expected, actual.toByteArray())));
  }

  private static void observeObjectStreams() throws Exception {
    COSStream stream = new COSStream();
    stream.setItem(COSName.N, COSInteger.TWO);
    stream.setItem(COSName.FIRST, COSInteger.get(8));
    try (OutputStream output = stream.createOutputStream()) {
      output.write("4 0 6 5 true false".getBytes(StandardCharsets.US_ASCII));
    }
    PDFObjectStreamParser parser = new PDFObjectStreamParser(stream, null);
    Map<Long, Integer> offsets = parser.readObjectNumbers();
    Map<COSObjectKey, COSBase> objects =
        new PDFObjectStreamParser(stream, null).parseAllObjects();
    observe(
        "parser-object-stream",
        "offsets-and-values",
        join(
            offsets.size(),
            offsets.get(4L),
            offsets.get(6L),
            objects.size(),
            objects.get(new COSObjectKey(4, 0)) == COSBoolean.TRUE,
            objects.get(new COSObjectKey(6, 0)) == COSBoolean.FALSE));
    stream.close();
  }

  private static Path writeFull(String implementation) throws Exception {
    Path full = exchange.resolve(implementation + "-full.pdf");
    Path compressed = exchange.resolve(implementation + "-compressed.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.getDocumentInformation().setTitle("baseline");
      document.getDocumentInformation().setCustomMetadataValue("Probe", "low-level");
      document.save(full.toFile(), CompressParameters.NO_COMPRESSION);
    }
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.getDocumentInformation().setTitle("compressed");
      document.save(compressed.toFile(), CompressParameters.DEFAULT_COMPRESSION);
    }
    observe("writer-full", "uncompressed", inspectPdf(full));
    observe("writer-xref", "compressed-object-stream", inspectPdf(compressed));
    observe(
        "byte-invariant",
        "full-save-markers",
        inspectMarkers(Files.readAllBytes(full), false));
    observe(
        "byte-invariant",
        "compressed-save-markers",
        inspectMarkers(Files.readAllBytes(compressed), false));
    return full;
  }

  private static void writeIncremental(String implementation, Path full)
      throws Exception {
    Path incremental = exchange.resolve(implementation + "-incremental.pdf");
    try (PDDocument document = Loader.loadPDF(full.toFile())) {
      document.getDocumentInformation().setTitle("incremental");
      ((COSUpdateInfo) document.getDocumentInformation().getCOSObject())
          .setNeedToBeUpdated(true);
      try (OutputStream output = Files.newOutputStream(incremental)) {
        document.saveIncremental(output);
      }
    }
    observe(
        "writer-incremental",
        "append-and-reopen",
        join(
            Files.size(incremental) > Files.size(full),
            inspectPdf(incremental),
            inspectMarkers(Files.readAllBytes(incremental), true)));
  }

  private static void observeParsing(Path full) throws Exception {
    byte[] valid = Files.readAllBytes(full);
    byte[] damagedHeader = valid.clone();
    damagedHeader[1] = (byte) 'X';
    observe(
        "parser-strict",
        "damaged-header",
        failureKind(() -> parse(damagedHeader, false)));
    observe(
        "parser-lenient",
        "damaged-header",
        inspectDocument(parse(damagedHeader, true)));

    byte[] damagedXref = damageStartXref(valid);
    observe(
        "parser-strict",
        "damaged-startxref",
        failureKind(() -> parse(damagedXref, false)));
    observe(
        "parser-recovery",
        "damaged-startxref",
        inspectDocument(parse(damagedXref, true)));
  }

  private static void observeFixtures() throws Exception {
    Path parserResources = resources.resolve("org/apache/pdfbox/pdfparser");
    observe(
        "parser-fixture",
        "simple-form",
        inspectPdf(parserResources.resolve("SimpleForm2Fields.pdf")));
    observe(
        "parser-fixture",
        "compressed-acroform",
        inspectPdf(resources.resolve("input/compression/acroform.pdf")));
    observe(
        "parser-recovery",
        "missing-catalog",
        failureKind(
            () -> Loader.loadPDF(parserResources.resolve("MissingCatalog.pdf").toFile())));

    Path encrypted =
        resources.resolve(
            "org/apache/pdfbox/encryption/PasswordSample-128bit.pdf");
    try (PDDocument user = Loader.loadPDF(encrypted.toFile(), "user");
        PDDocument owner = Loader.loadPDF(encrypted.toFile(), "owner")) {
      observe(
          "parser-encryption",
          "password-and-permissions",
          join(
              user.getNumberOfPages(),
              user.isEncrypted(),
              user.getCurrentAccessPermission().isOwnerPermission(),
              owner.getCurrentAccessPermission().isOwnerPermission(),
              failureKind(() -> Loader.loadPDF(encrypted.toFile(), "wrong"))));
    }
  }

  private static void observeCrossRuntime(String implementation) throws Exception {
    Path full = exchange.resolve(implementation + "-full.pdf");
    Path compressed = exchange.resolve(implementation + "-compressed.pdf");
    Path incremental = exchange.resolve(implementation + "-incremental.pdf");
    if (!Files.isRegularFile(full)
        || !Files.isRegularFile(compressed)
        || !Files.isRegularFile(incremental)) {
      throw new IOException("Missing cross-runtime PdfCarton output");
    }
    observe("cross-reopen", "other-full", inspectPdf(full));
    observe("cross-reopen", "other-compressed", inspectPdf(compressed));
    observe("cross-reopen", "other-incremental", inspectPdf(incremental));
  }

  private static PDDocument parse(byte[] bytes, boolean lenient) throws Exception {
    PDFParser parser = new PDFParser(new RandomAccessReadBuffer(bytes));
    return parser.parse(lenient);
  }

  private static String inspectPdf(Path path) throws Exception {
    try (PDDocument document = Loader.loadPDF(path.toFile())) {
      return inspectDocument(document);
    }
  }

  private static String inspectDocument(PDDocument document) throws Exception {
    try (PDDocument closeable = document) {
      COSDocument cos = document.getDocument();
      long maximum = 0;
      int objectStreamEntries = 0;
      for (Map.Entry<COSObjectKey, Long> entry : cos.getXrefTable().entrySet()) {
        if (entry.getKey() != null) {
          maximum = Math.max(maximum, entry.getKey().getNumber());
        }
        if (entry.getValue() != null && entry.getValue() < 0) {
          objectStreamEntries++;
        }
      }
      return join(
          document.getNumberOfPages(),
          document.getDocumentInformation().getTitle(),
          cos.isXRefStream(),
          cos.getXrefTable().size(),
          cos.getTrailer().getLong(COSName.SIZE),
          maximum + 1 == cos.getTrailer().getLong(COSName.SIZE),
          objectStreamEntries);
    }
  }

  private static String inspectMarkers(byte[] bytes, boolean incremental) {
    String text = new String(bytes, StandardCharsets.ISO_8859_1);
    return join(
        text.startsWith("%PDF-"),
        count(text, "startxref"),
        count(text, "%%EOF"),
        incremental ? count(text, "%%EOF") >= 2 : count(text, "%%EOF") == 1,
        text.lastIndexOf("startxref") < text.lastIndexOf("%%EOF"));
  }

  private static byte[] damageStartXref(byte[] source) {
    byte[] damaged = source.clone();
    byte[] marker = "startxref\n".getBytes(StandardCharsets.US_ASCII);
    int start = lastIndexOf(damaged, marker) + marker.length;
    if (start < marker.length) {
      throw new IllegalArgumentException("PDF has no startxref marker");
    }
    while (start < damaged.length && damaged[start] >= '0' && damaged[start] <= '9') {
      damaged[start++] = '9';
    }
    return damaged;
  }

  private static int lastIndexOf(byte[] source, byte[] marker) {
    for (int i = source.length - marker.length; i >= 0; i--) {
      boolean found = true;
      for (int j = 0; j < marker.length; j++) {
        if (source[i + j] != marker[j]) {
          found = false;
          break;
        }
      }
      if (found) {
        return i;
      }
    }
    return -1;
  }

  private static byte[] encodePngRow(byte[] row, byte[] prior, int filter) {
    byte[] encoded = new byte[row.length];
    for (int i = 0; i < row.length; i++) {
      int left = i == 0 ? 0 : row[i - 1] & 0xff;
      int up = prior == null ? 0 : prior[i] & 0xff;
      int upLeft = prior == null || i == 0 ? 0 : prior[i - 1] & 0xff;
      int prediction;
      switch (filter) {
        case 0:
          prediction = 0;
          break;
        case 1:
          prediction = left;
          break;
        case 2:
          prediction = up;
          break;
        case 3:
          prediction = (left + up) / 2;
          break;
        case 4:
          prediction = paeth(left, up, upLeft);
          break;
        default:
          throw new IllegalArgumentException("Unknown PNG predictor");
      }
      encoded[i] = (byte) ((row[i] & 0xff) - prediction);
    }
    return encoded;
  }

  private static int paeth(int left, int up, int upLeft) {
    int value = left + up - upLeft;
    int leftDistance = Math.abs(value - left);
    int upDistance = Math.abs(value - up);
    int upLeftDistance = Math.abs(value - upLeft);
    if (leftDistance <= upDistance && leftDistance <= upLeftDistance) {
      return left;
    }
    return upDistance <= upLeftDistance ? up : upLeft;
  }

  private static byte[] filterSample() {
    byte[] sample = new byte[1024];
    for (int i = 0; i < sample.length; i++) {
      sample[i] = (byte) ((i % 17 < 6) ? i % 4 : (i * 37 + 11));
    }
    return sample;
  }

  private static String failureKind(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (InvalidPasswordException error) {
      return "invalid-password";
    } catch (UnsupportedOperationException error) {
      return "unsupported";
    } catch (IllegalArgumentException error) {
      return "invalid-argument";
    } catch (IllegalStateException error) {
      return "invalid-operation";
    } catch (java.io.EOFException error) {
      return "eof";
    } catch (IOException error) {
      return "io";
    } catch (Exception error) {
      return error.getClass().getSimpleName();
    }
  }

  private static void observe(String family, String id, Object value) {
    observations.add(family + "\t" + id + "\t" + value(value));
  }

  private static String join(Object... values) {
    List<String> parts = new ArrayList<String>();
    for (Object value : values) {
      parts.add(value(value));
    }
    return String.join("|", parts);
  }

  private static String joinObjectKeys(List<COSObjectKey> values) {
    List<String> parts = new ArrayList<String>();
    for (COSObjectKey value : values) {
      parts.add(value.getNumber() + ":" + value.getGeneration());
    }
    return String.join(",", parts);
  }

  private static String value(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean) {
      return ((Boolean) value).booleanValue() ? "true" : "false";
    }
    if (value instanceof Iterable<?>) {
      List<String> parts = new ArrayList<String>();
      for (Object item : (Iterable<?>) value) {
        parts.add(value(item));
      }
      return String.join(",", parts);
    }
    return String.valueOf(value).replace('\t', ' ').replace('\n', ' ');
  }

  private static String sha256(byte[] bytes) throws Exception {
    return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte item : bytes) {
      value.append(String.format("%02x", item & 0xff));
    }
    return value.toString();
  }

  private static int count(String value, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }

  private static byte[] concat(byte[]... values) {
    int length = 0;
    for (byte[] value : values) {
      length += value.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] value : values) {
      System.arraycopy(value, 0, result, offset, value.length);
      offset += value.length;
    }
    return result;
  }
}
