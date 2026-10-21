import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.xml.sax.InputSource;

public final class JavaCompatOracle {
  private static final String HEADER =
      "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1";

  private record Provenance(
      String compatType,
      String jdkContract,
      String targets,
      String[] proofRows) {}

  private record Observation(String family, String id, String value) {}

  private static final class ControlledInputStream extends InputStream {
    private final byte[] data;
    private final int maxChunk;
    private final int failurePosition;
    private int position;
    private boolean closed;

    ControlledInputStream(byte[] data, int maxChunk, int failurePosition) {
      if (maxChunk <= 0 || failurePosition < -1) throw new IllegalArgumentException();
      this.data = data.clone();
      this.maxChunk = maxChunk;
      this.failurePosition = failurePosition;
    }
    @Override public int read() throws IOException {
      var one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
      checkOpen();
      if (length == 0) return 0;
      if (failurePosition >= 0 && position >= failurePosition)
        throw new IOException("Injected read failure");
      if (position >= data.length) return -1;
      int untilFailure = failurePosition < 0 ? Integer.MAX_VALUE : failurePosition - position;
      int actual = Math.min(length, Math.min(maxChunk, Math.min(data.length - position, untilFailure)));
      if (actual <= 0) throw new IOException("Injected read failure");
      System.arraycopy(data, position, buffer, offset, actual);
      position += actual;
      return actual;
    }
    @Override public long skip(long count) throws IOException {
      checkOpen();
      if (count <= 0) return 0;
      int skipped = (int)Math.min(count, data.length - position);
      position += skipped;
      return skipped;
    }
    @Override public int available() throws IOException { checkOpen(); return data.length - position; }
    @Override public void close() { closed = true; }
    int cursor() { return position; }
    int remaining() { return Math.max(0, data.length - position); }
    boolean closed() { return closed; }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Controlled input is closed"); }
  }

  private static final class ProbeFilterInputStream extends FilterInputStream {
    ProbeFilterInputStream(InputStream input) { super(input); }
  }

  private static final class ControlledOutputStream extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int failurePosition;
    private boolean closed;
    private int flushes;
    ControlledOutputStream(int failurePosition) {
      if (failurePosition < -1) throw new IllegalArgumentException();
      this.failurePosition = failurePosition;
    }
    @Override public void write(int value) throws IOException {
      checkOpen();
      if (failurePosition >= 0 && bytes.size() >= failurePosition)
        throw new IOException("Injected write failure");
      bytes.write(value);
    }
    @Override public void flush() throws IOException { checkOpen(); flushes++; }
    @Override public void close() { closed = true; }
    byte[] bytes() { return bytes.toByteArray(); }
    boolean closed() { return closed; }
    int flushes() { return flushes; }
    private void checkOpen() throws IOException { if (closed) throw new IOException("Controlled output is closed"); }
  }

  private static final class ProbeFilterOutputStream extends FilterOutputStream {
    ProbeFilterOutputStream(OutputStream output) { super(output); }
  }

  private static final class InputCase {
    final InputStream stream;
    final ControlledInputStream controlled;
    final int viewLength;
    byte[] lastBuffer;
    boolean closeInvoked;
    InputCase(InputStream stream, ControlledInputStream controlled, int viewLength) {
      this.stream = stream; this.controlled = controlled; this.viewLength = viewLength;
    }
  }

  private static final class OutputCase {
    final OutputStream stream;
    final ControlledOutputStream controlled;
    boolean closeInvoked;
    OutputCase(OutputStream stream, ControlledOutputStream controlled) {
      this.stream = stream; this.controlled = controlled;
    }
  }

  private static final class RandomCase {
    final RandomAccessFile file;
    final Path path;
    byte[] lastBuffer;
    boolean closed;
    RandomCase(RandomAccessFile file, Path path) { this.file = file; this.path = path; }
  }

  private static final class BufferCase {
    final byte[] bytes;
    final Map<String, ByteBuffer> views = new java.util.TreeMap<>();
    String activeName = "root";
    BufferCase(byte[] bytes) { this.bytes = bytes; views.put("root", ByteBuffer.wrap(bytes)); }
    ByteBuffer active() { return views.get(activeName); }
  }

  private static final class DataCase {
    final byte[] inputBytes;
    final ByteArrayInputStream inputStream;
    final DataInputStream input;
    final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    final DataOutputStream output = new DataOutputStream(outputStream);
    byte[] lastBuffer;
    DataCase(byte[] bytes) {
      inputBytes = bytes;
      inputStream = new ByteArrayInputStream(bytes);
      input = new DataInputStream(inputStream);
    }
  }

  private static final class CompressionCase {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final List<Integer> outputAfterWrites = new ArrayList<Integer>();
    String stage = "created";
    boolean compareTiming = true;
  }

  // Inputs are ASCII typed tokens, never JSON numbers or Unicode scalars.
  private static Object caseValue(String token) {
    if (token.equals("n:")) return null;
    if (token.matches("b:(?:[0-9a-f]{2})*"))
      return HexFormat.of().parseHex(token.substring(2));
    if (token.matches("i:(?:0|-?[1-9][0-9]*)"))
      return new java.math.BigInteger(token.substring(2));
    if (token.matches("u:(?:[0-9a-f]{4})*")) {
      var value = new StringBuilder();
      for (int i = 2; i < token.length(); i += 4)
        value.append((char)Integer.parseInt(token.substring(i, i + 4), 16));
      return value.toString();
    }
    throw new IllegalArgumentException("Invalid case value: " + token);
  }

  private static String stringToken(String value) {
    if (value == null) return "n:";
    var token = new StringBuilder("u:");
    for (int i = 0; i < value.length(); i++)
      token.append(String.format(Locale.ROOT, "%04x", (int)value.charAt(i)));
    return token.toString();
  }

  private static int intValue(Object value) { return ((java.math.BigInteger)value).intValueExact(); }
  private static long longValue(Object value) { return ((java.math.BigInteger)value).longValueExact(); }
  private static String bufferResult(int count, byte[] buffer) {
    return stringToken(Integer.toString(count) + "|" + HexFormat.of().formatHex(buffer));
  }
  private static String inputState(InputCase input) throws IOException {
    if (input == null) return "n:";
    var buffer = input.lastBuffer == null ? "-" : HexFormat.of().formatHex(input.lastBuffer);
    if (input.controlled != null)
      return stringToken(String.join("|", Integer.toString(input.controlled.cursor()),
          Integer.toString(input.controlled.remaining()), Boolean.toString(input.controlled.closed()), buffer));
    int available = input.stream.available();
    return stringToken(String.join("|", Integer.toString(input.viewLength - available),
        Integer.toString(available), Boolean.toString(input.closeInvoked), buffer));
  }
  private static String outputState(OutputCase output) {
    if (output == null) return "n:";
    if (output.controlled != null) {
      byte[] bytes = output.controlled.bytes();
      return stringToken(String.join("|", Integer.toString(bytes.length),
          HexFormat.of().formatHex(bytes), Boolean.toString(output.controlled.closed()),
          Integer.toString(output.controlled.flushes())));
    }
    byte[] bytes = ((ByteArrayOutputStream)output.stream).toByteArray();
    return stringToken(String.join("|", Integer.toString(bytes.length),
        HexFormat.of().formatHex(bytes), Boolean.toString(output.closeInvoked)));
  }
  private static String randomState(RandomCase random) throws IOException {
    if (random == null) return "n:";
    String pointer = random.closed ? "closed" : Long.toString(random.file.getFilePointer());
    byte[] bytes = Files.readAllBytes(random.path);
    String buffer = random.lastBuffer == null ? "-" : HexFormat.of().formatHex(random.lastBuffer);
    return stringToken(String.join("|", pointer, Integer.toString(bytes.length),
        HexFormat.of().formatHex(bytes), buffer));
  }

  private static String bufferState(BufferCase buffer) {
    if (buffer == null) return "n:";
    var views = new ArrayList<String>();
    for (var entry : buffer.views.entrySet()) {
      var view = entry.getValue();
      views.add(String.join(",", entry.getKey(), Integer.toString(view.position()),
          Integer.toString(view.limit()), Integer.toString(view.capacity()),
          Integer.toString(view.remaining()),
          view.order() == ByteOrder.BIG_ENDIAN ? "big" : "little",
          Boolean.toString(view.isReadOnly())));
    }
    return stringToken(String.join("|", HexFormat.of().formatHex(buffer.bytes),
        buffer.activeName, String.join(";", views)));
  }

  private static String dataState(DataCase data) {
    if (data == null) return "n:";
    return stringToken(String.join("|",
        Integer.toString(data.inputBytes.length - data.inputStream.available()),
        Integer.toString(data.inputBytes.length),
        HexFormat.of().formatHex(data.outputStream.toByteArray()),
        data.lastBuffer == null ? "-" : HexFormat.of().formatHex(data.lastBuffer)));
  }

  private static String compressionState(CompressionCase compression) {
    if (compression == null) return "n:";
    byte[] bytes = compression.output.toByteArray();
    var adler = new Adler32();
    adler.update(bytes);
    var crc = new CRC32();
    crc.update(bytes);
    var values = new ArrayList<String>();
    values.add(HexFormat.of().formatHex(bytes));
    values.add(Long.toString(adler.getValue()));
    values.add(Long.toString(crc.getValue()));
    if (compression.compareTiming) {
      values.add(compression.stage);
      values.add(compression.outputAfterWrites.stream()
          .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
    }
    return stringToken(String.join("|", values));
  }

  private static byte[] runCompressionRoundTrip(
      byte[] payload, int encodeChunk, int flushEvery, int decodeChunk,
      String sequence, CompressionCase observation) throws IOException {
    if (payload.length > 8192 || encodeChunk <= 0 || decodeChunk <= 0
        || flushEvery < 0 || !(sequence.equals("close") || sequence.equals("flush-close")))
      throw new IllegalArgumentException("Invalid bounded compression case");
    observation.compareTiming = false;

    var encoded = new ByteArrayOutputStream();
    try (var compressor = new DeflaterOutputStream(encoded)) {
      int writes = 0;
      for (int offset = 0; offset < payload.length; offset += encodeChunk) {
        int count = Math.min(encodeChunk, payload.length - offset);
        compressor.write(payload, offset, count);
        writes++;
        if (flushEvery > 0 && writes % flushEvery == 0) compressor.flush();
      }
    }
    return runCompressionDecode(
        encoded.toByteArray(), decodeChunk, sequence, observation);
  }

  private static byte[] runCompressionDecode(
      byte[] encoded, int chunk, String sequence, CompressionCase observation)
      throws IOException {
    if (encoded.length > 16384 || chunk <= 0
        || !(sequence.equals("close") || sequence.equals("flush-close")
            || sequence.equals("write-byte-close")))
      throw new IllegalArgumentException("Invalid bounded compression case");

    var inflater = new InflaterOutputStream(observation.output);
    if (sequence.equals("write-byte-close")) {
      for (int offset = 0; offset < encoded.length; offset++) {
        observation.stage = "write-" + offset;
        inflater.write(encoded[offset]);
        observation.outputAfterWrites.add(observation.output.size());
      }
    }
    for (int offset = 0; !sequence.equals("write-byte-close") && offset < encoded.length;
         offset += chunk) {
      observation.stage = "write-" + offset;
      int count = Math.min(chunk, encoded.length - offset);
      inflater.write(encoded, offset, count);
      observation.outputAfterWrites.add(observation.output.size());
      if (sequence.equals("flush-close")) {
        observation.stage = "flush-" + offset;
        inflater.flush();
      }
    }
    observation.stage = "close";
    inflater.close();
    observation.stage = "closed";
    return observation.output.toByteArray();
  }

  // Drives Inflater the way PDFBox's FlateFilterDecoderStream does: new input
  // only when needsInput(), a zero-filled output buffer per call. The trace
  // records each call's count and finished/needsInput state; a
  // DataFormatException records the salvageable non-zero prefix and the CRC of
  // the bytes written into the output buffer before the failure.
  private static String runInflate(
      byte[] encoded, int feed, int outputSize, String mode, CompressionCase observation) {
    if (encoded.length > 65536 || feed <= 0 || outputSize <= 0 || outputSize > 65536
        || !(mode.equals("raw") || mode.equals("zlib")))
      throw new IllegalArgumentException("Invalid bounded inflate case");
    observation.compareTiming = false;
    var inflater = new Inflater(mode.equals("raw"));
    var trace = new ArrayList<String>();
    var output = new byte[outputSize];
    int offset = 0;
    try {
      for (int step = 0; step < 100000; step++) {
        if (inflater.finished()) {
          trace.add("F");
          break;
        }
        if (inflater.needsInput()) {
          if (offset >= encoded.length) {
            trace.add("I");
            break;
          }
          int count = Math.min(feed, encoded.length - offset);
          inflater.setInput(encoded, offset, count);
          offset += count;
        }
        java.util.Arrays.fill(output, (byte)0);
        int count;
        try {
          count = inflater.inflate(output);
        } catch (DataFormatException error) {
          int prefix = output.length;
          while (prefix > 0 && output[prefix - 1] == 0) prefix--;
          var crc = new CRC32();
          crc.update(output, 0, output.length);
          trace.add("E" + prefix + ":" + crc.getValue());
          break;
        }
        observation.output.write(output, 0, count);
        if (count == 0 && !inflater.needsInput() && !inflater.finished()) {
          trace.add("S");
          break;
        }
        trace.add(count + (inflater.finished() ? "f" : "") + (inflater.needsInput() ? "n" : ""));
      }
    } finally {
      inflater.end();
    }
    return offset + "|" + String.join(",", trace);
  }

  private static String fileInspection(File file) {
    return String.join("\u0001",
        file.getPath(), file.getName(), file.getParent() == null ? "\u0002" : file.getParent(),
        Boolean.toString(file.isAbsolute()), Boolean.toString(file.exists()),
        Boolean.toString(file.isFile()), Boolean.toString(file.isDirectory()),
        Boolean.toString(file.canRead()), Boolean.toString(file.canWrite()),
        Boolean.toString(file.isHidden()), file.isFile() ? Long.toString(file.length()) : "\u0002",
        Integer.toString(file.hashCode()));
  }

  private static Path fileCaseRoot(Path input, String caseId) throws IOException {
    var root = input.toAbsolutePath().getParent().resolve("file-sandboxes").resolve(caseId);
    Files.createDirectories(root.resolve("dir"));
    Files.createDirectories(root.resolve("empty-dir"));
    Files.createDirectories(root.resolve("unicode-\u00e9"));
    Files.createDirectories(root.resolve(".hidden"));
    Files.writeString(root.resolve("file.txt"), "abc", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("unicode-\u00e9").resolve("\u96ea.txt"), "snow", StandardCharsets.UTF_8);
    return root;
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList())
        Files.delete(path);
    }
  }

  private static void runCases(Path input, Path output) throws Exception {
    var lines = Files.readAllLines(input, StandardCharsets.UTF_8);
    if (lines.size() < 2 || !lines.get(0).equals("DRIPSHARP_DIFFERENTIAL_CASES_V1"))
      throw new IllegalArgumentException("Invalid cases header or empty input");
    var results = new ArrayList<String>();
    results.add("DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1");
    var buffers = new java.util.HashMap<String, BufferCase>();
    var dataCases = new java.util.HashMap<String, DataCase>();
    var inputs = new java.util.HashMap<String, InputCase>();
    var outputs = new java.util.HashMap<String, OutputCase>();
    var randoms = new java.util.HashMap<String, RandomCase>();
    var identities = new java.util.HashSet<String>();
    var sandboxParent = input.toAbsolutePath().getParent().resolve("file-sandboxes");
    deleteTree(sandboxParent);
    try {
      for (var line : lines.subList(1, lines.size())) {
      var fields = line.split("\t", -1);
      if (fields.length < 5) throw new IllegalArgumentException("Malformed case");
      for (int i = 0; i < 5; i++)
        if (!fields[i].matches("[A-Za-z0-9_.-]+"))
          throw new IllegalArgumentException("Invalid case identity");
      var key = fields[0] + "\t" + fields[1];
      if (!identities.add(key + "\t" + fields[2]))
        throw new IllegalArgumentException("Duplicate case step");
      var operation = fields[0] + "/" + fields[3];
      int arity = switch (operation) {
        case "floating/run" -> 3;
        case "collections/run" -> 2;
        case "charset/decode", "charset/stream", "charset/string-ops" -> 3;
        case "numeric/int-div", "numeric/int-rem", "numeric/long-div", "numeric/long-rem" -> 2;
        case "byte-buffer/wrap", "string/hash", "long/hash" -> 1;
        case "byte-buffer/capacity", "byte-buffer/position", "byte-buffer/limit",
             "byte-buffer/remaining", "byte-buffer/is-read-only", "byte-buffer/mark",
             "byte-buffer/reset", "byte-buffer/clear", "byte-buffer/flip",
             "byte-buffer/rewind", "byte-buffer/duplicate", "byte-buffer/slice",
             "byte-buffer/read-only", "byte-buffer/get", "byte-buffer/get-short",
             "byte-buffer/get-int", "byte-buffer/get-long" -> 0;
        case "byte-buffer/set-position", "byte-buffer/set-limit", "byte-buffer/select",
             "byte-buffer/order", "byte-buffer/get-at", "byte-buffer/get-short-at",
             "byte-buffer/get-int-at", "byte-buffer/get-long-at", "byte-buffer/put",
             "byte-buffer/put-short", "byte-buffer/put-int", "byte-buffer/put-long" -> 1;
        case "byte-buffer/put-at", "byte-buffer/put-short-at",
             "byte-buffer/put-int-at", "byte-buffer/put-long-at" -> 2;
        case "binary-data/open" -> 1;
        case "binary-data/read-byte", "binary-data/read-unsigned-byte",
             "binary-data/read-short", "binary-data/read-unsigned-short",
             "binary-data/read-int", "binary-data/read-long", "binary-data/flush" -> 0;
        case "binary-data/write-byte", "binary-data/write-short",
             "binary-data/write-int", "binary-data/write-long" -> 1;
        case "binary-data/read-fully", "binary-data/write-buffer" -> 3;
        case "compression/roundtrip" -> 5;
        case "compression/decode" -> 3;
        case "compression/inflate" -> 4;
        case "byte-array-input/open", "filter-input/open",
             "byte-array-input/read-buffer", "filter-input/read-buffer",
             "byte-array-output/write-buffer", "filter-output/write-buffer",
             "random-access/read-buffer" -> 3;
        case "byte-array-input/skip", "filter-input/skip",
             "byte-array-input/mark", "filter-input/mark",
             "byte-array-output/open", "filter-output/open",
             "byte-array-output/write-one", "filter-output/write-one",
             "random-access/read-fully", "random-access/write-buffer",
             "random-access/seek", "random-access/set-length", "random-access/skip" -> 1;
        case "byte-array-input/read-one", "filter-input/read-one",
             "byte-array-input/available", "filter-input/available",
             "byte-array-input/mark-supported", "filter-input/mark-supported",
             "byte-array-input/reset", "filter-input/reset",
             "byte-array-input/close", "filter-input/close",
             "byte-array-output/size", "byte-array-output/reset", "byte-array-output/close",
             "filter-output/flush", "filter-output/close",
             "random-access/read-one", "random-access/length",
             "random-access/position", "random-access/close" -> 0;
        case "random-access/open" -> 2;
        case "file/inspect", "file/absolute", "file/uri", "file/to-path",
             "file/create", "file/delete", "file/open", "file/list-count" -> 1;
        case "file/parent-child", "file/file-parent-child", "file/equals" -> 2;
        default -> throw new IllegalArgumentException("Unknown operation: " + operation);
      };
      if (fields.length != 5 + arity)
        throw new IllegalArgumentException("Wrong argument count");
      var values = new Object[arity];
      for (int i = 0; i < arity; i++) values[i] = caseValue(fields[5 + i]);
      // Domain validation occurs outside the observed operation's exception boundary.
      if (operation.equals("byte-buffer/wrap") && values[0] != null && !(values[0] instanceof byte[])
          || operation.equals("binary-data/open") && !(values[0] instanceof byte[])
          || operation.startsWith("compression/")
              && (!(values[0] instanceof byte[]) || !(values[values.length - 1] instanceof String))
          || operation.equals("string/hash") && values[0] != null && !(values[0] instanceof String)
          || operation.equals("long/hash") && !(values[0] instanceof java.math.BigInteger)
          || (operation.equals("byte-array-input/open") || operation.equals("filter-input/open"))
              && !(values[0] instanceof byte[])
          || operation.startsWith("file/") && java.util.Arrays.stream(values)
              .anyMatch(value -> value != null && !(value instanceof String)))
        throw new IllegalArgumentException("Wrong argument type");
      long number = operation.equals("long/hash")
          ? ((java.math.BigInteger)values[0]).longValueExact() : 0;
      long numericLeft = operation.startsWith("numeric/") ? ((java.math.BigInteger)values[0]).longValueExact() : 0;
      long numericRight = operation.startsWith("numeric/") ? ((java.math.BigInteger)values[1]).longValueExact() : 0;
      Path caseRoot = operation.startsWith("file/") ? fileCaseRoot(input, fields[1]) : null;
      for (int i = 0; i < values.length; i++)
        if (values[i] instanceof String text)
          values[i] = text.replace("$ROOT", caseRoot == null ? "$ROOT" : caseRoot.toString());
      var result = "n:";
      var exception = "none";
      CompressionCase compressionCase = null;
      try {
        switch (operation) {
          case "floating/run" -> {
            Locale.setDefault(values[0].equals("root") ? Locale.ROOT : Locale.forLanguageTag((String)values[0]));
            result = stringToken(micro.FloatingCases.run((String)values[0], (String)values[1], (String)values[2]));
          }
          case "collections/run" -> result = stringToken(micro.CollectionMachine.run(intValue(values[0]), (String) values[1]));
          case "charset/decode" -> result = stringToken(micro.CharsetMachine.decode(
              (String) values[0], intValue(values[1]), (String) values[2]));
          case "charset/stream" -> result = stringToken(micro.CharsetMachine.stream(
              (String) values[0], (String) values[1], (String) values[2]));
          case "charset/string-ops" -> result = stringToken(micro.CharsetMachine.stringOps(
              (String) values[0], intValue(values[1]), intValue(values[2])));
          case "numeric/int-div" -> result = "i:" + ((int)numericLeft / (int)numericRight);
          case "numeric/int-rem" -> result = "i:" + ((int)numericLeft % (int)numericRight);
          case "numeric/long-div" -> result = "i:" + (numericLeft / numericRight);
          case "numeric/long-rem" -> result = "i:" + (numericLeft % numericRight);
          case "byte-buffer/wrap" -> buffers.put(key, new BufferCase((byte[])values[0]));
          case "byte-buffer/capacity" -> result = "i:" + buffers.get(key).active().capacity();
          case "byte-buffer/position" -> result = "i:" + buffers.get(key).active().position();
          case "byte-buffer/limit" -> result = "i:" + buffers.get(key).active().limit();
          case "byte-buffer/remaining" -> result = "i:" + buffers.get(key).active().remaining();
          case "byte-buffer/is-read-only" -> result = "i:" + (buffers.get(key).active().isReadOnly() ? 1 : 0);
          case "byte-buffer/set-position" -> { buffers.get(key).active().position(intValue(values[0])); result = "i:1"; }
          case "byte-buffer/set-limit" -> { buffers.get(key).active().limit(intValue(values[0])); result = "i:1"; }
          case "byte-buffer/mark" -> { buffers.get(key).active().mark(); result = "i:1"; }
          case "byte-buffer/reset" -> { buffers.get(key).active().reset(); result = "i:1"; }
          case "byte-buffer/clear" -> { buffers.get(key).active().clear(); result = "i:1"; }
          case "byte-buffer/flip" -> { buffers.get(key).active().flip(); result = "i:1"; }
          case "byte-buffer/rewind" -> { buffers.get(key).active().rewind(); result = "i:1"; }
          case "byte-buffer/duplicate" -> { var b = buffers.get(key); b.views.put("view", b.active().duplicate()); b.activeName = "view"; result = "i:1"; }
          case "byte-buffer/slice" -> { var b = buffers.get(key); b.views.put("view", b.active().slice()); b.activeName = "view"; result = "i:1"; }
          case "byte-buffer/read-only" -> { var b = buffers.get(key); b.views.put("view", b.active().asReadOnlyBuffer()); b.activeName = "view"; result = "i:1"; }
          case "byte-buffer/select" -> { var b = buffers.get(key); b.activeName = (String)values[0]; if (b.active() == null) throw new IllegalArgumentException(); }
          case "byte-buffer/order" -> { buffers.get(key).active().order(values[0].equals("big") ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN); result = "i:1"; }
          case "byte-buffer/get" -> result = "i:" + buffers.get(key).active().get();
          case "byte-buffer/get-at" -> result = "i:" + buffers.get(key).active().get(intValue(values[0]));
          case "byte-buffer/get-short" -> result = "i:" + buffers.get(key).active().getShort();
          case "byte-buffer/get-short-at" -> result = "i:" + buffers.get(key).active().getShort(intValue(values[0]));
          case "byte-buffer/get-int" -> result = "i:" + buffers.get(key).active().getInt();
          case "byte-buffer/get-int-at" -> result = "i:" + buffers.get(key).active().getInt(intValue(values[0]));
          case "byte-buffer/get-long" -> result = "i:" + buffers.get(key).active().getLong();
          case "byte-buffer/get-long-at" -> result = "i:" + buffers.get(key).active().getLong(intValue(values[0]));
          case "byte-buffer/put" -> { buffers.get(key).active().put((byte)intValue(values[0])); result = "i:1"; }
          case "byte-buffer/put-at" -> { buffers.get(key).active().put(intValue(values[0]), (byte)intValue(values[1])); result = "i:1"; }
          case "byte-buffer/put-short" -> { buffers.get(key).active().putShort((short)intValue(values[0])); result = "i:1"; }
          case "byte-buffer/put-short-at" -> { buffers.get(key).active().putShort(intValue(values[0]), (short)intValue(values[1])); result = "i:1"; }
          case "byte-buffer/put-int" -> { buffers.get(key).active().putInt(intValue(values[0])); result = "i:1"; }
          case "byte-buffer/put-int-at" -> { buffers.get(key).active().putInt(intValue(values[0]), intValue(values[1])); result = "i:1"; }
          case "byte-buffer/put-long" -> { buffers.get(key).active().putLong(longValue(values[0])); result = "i:1"; }
          case "byte-buffer/put-long-at" -> { buffers.get(key).active().putLong(intValue(values[0]), longValue(values[1])); result = "i:1"; }
          case "binary-data/open" -> dataCases.put(key, new DataCase((byte[])values[0]));
          case "binary-data/read-byte" -> result = "i:" + dataCases.get(key).input.readByte();
          case "binary-data/read-unsigned-byte" -> result = "i:" + dataCases.get(key).input.readUnsignedByte();
          case "binary-data/read-short" -> result = "i:" + dataCases.get(key).input.readShort();
          case "binary-data/read-unsigned-short" -> result = "i:" + dataCases.get(key).input.readUnsignedShort();
          case "binary-data/read-int" -> result = "i:" + dataCases.get(key).input.readInt();
          case "binary-data/read-long" -> result = "i:" + dataCases.get(key).input.readLong();
          case "binary-data/read-fully" -> {
            var data = dataCases.get(key);
            data.lastBuffer = ((byte[])values[0]).clone();
            data.input.readFully(data.lastBuffer, intValue(values[1]), intValue(values[2]));
            result = stringToken(HexFormat.of().formatHex(data.lastBuffer));
          }
          case "binary-data/write-byte" -> dataCases.get(key).output.writeByte(intValue(values[0]));
          case "binary-data/write-short" -> dataCases.get(key).output.writeShort(intValue(values[0]));
          case "binary-data/write-int" -> dataCases.get(key).output.writeInt(intValue(values[0]));
          case "binary-data/write-long" -> dataCases.get(key).output.writeLong(longValue(values[0]));
          case "binary-data/write-buffer" -> dataCases.get(key).output.write((byte[])values[0], intValue(values[1]), intValue(values[2]));
          case "binary-data/flush" -> dataCases.get(key).output.flush();
          case "compression/roundtrip" -> {
            compressionCase = new CompressionCase();
            result = "b:" + HexFormat.of().formatHex(runCompressionRoundTrip(
                (byte[])values[0], intValue(values[1]), intValue(values[2]),
                intValue(values[3]), (String)values[4], compressionCase));
          }
          case "compression/decode" -> {
            compressionCase = new CompressionCase();
            result = "b:" + HexFormat.of().formatHex(runCompressionDecode(
                (byte[])values[0], intValue(values[1]), (String)values[2],
                compressionCase));
          }
          case "compression/inflate" -> {
            compressionCase = new CompressionCase();
            result = stringToken(runInflate(
                (byte[])values[0], intValue(values[1]), intValue(values[2]),
                (String)values[3], compressionCase));
          }
          case "byte-array-input/open" -> {
            if (inputs.get(key) != null) inputs.get(key).stream.close();
            int offset = intValue(values[1]);
            int length = intValue(values[2]);
            inputs.put(key, new InputCase(new ByteArrayInputStream((byte[])values[0], offset, length),
                null, Math.min(length, ((byte[])values[0]).length - offset)));
          }
          case "filter-input/open" -> {
            if (inputs.get(key) != null) inputs.get(key).stream.close();
            var controlled = new ControlledInputStream((byte[])values[0], intValue(values[1]), intValue(values[2]));
            inputs.put(key, new InputCase(new ProbeFilterInputStream(controlled), controlled,
                ((byte[])values[0]).length));
          }
          case "byte-array-input/read-one", "filter-input/read-one" ->
              result = "i:" + inputs.get(key).stream.read();
          case "byte-array-input/read-buffer", "filter-input/read-buffer" -> {
            var stream = inputs.get(key);
            stream.lastBuffer = ((byte[])values[0]).clone();
            int read = stream.stream.read(stream.lastBuffer, intValue(values[1]), intValue(values[2]));
            result = bufferResult(read, stream.lastBuffer);
          }
          case "byte-array-input/skip", "filter-input/skip" ->
              result = "i:" + inputs.get(key).stream.skip(longValue(values[0]));
          case "byte-array-input/available", "filter-input/available" ->
              result = "i:" + inputs.get(key).stream.available();
          case "byte-array-input/mark", "filter-input/mark" ->
              inputs.get(key).stream.mark(intValue(values[0]));
          case "byte-array-input/reset", "filter-input/reset" -> inputs.get(key).stream.reset();
          case "byte-array-input/mark-supported", "filter-input/mark-supported" ->
              result = "i:" + (inputs.get(key).stream.markSupported() ? 1 : 0);
          case "byte-array-input/close", "filter-input/close" -> {
            inputs.get(key).closeInvoked = true;
            inputs.get(key).stream.close();
          }
          case "byte-array-output/open" -> {
            if (outputs.get(key) != null) outputs.get(key).stream.close();
            outputs.put(key, new OutputCase(new ByteArrayOutputStream(intValue(values[0])), null));
          }
          case "filter-output/open" -> {
            if (outputs.get(key) != null) outputs.get(key).stream.close();
            var controlled = new ControlledOutputStream(intValue(values[0]));
            outputs.put(key, new OutputCase(new ProbeFilterOutputStream(controlled), controlled));
          }
          case "byte-array-output/write-one", "filter-output/write-one" ->
              outputs.get(key).stream.write(intValue(values[0]));
          case "byte-array-output/write-buffer", "filter-output/write-buffer" ->
              outputs.get(key).stream.write((byte[])values[0], intValue(values[1]), intValue(values[2]));
          case "byte-array-output/size" ->
              result = "i:" + ((ByteArrayOutputStream)outputs.get(key).stream).size();
          case "byte-array-output/reset" -> ((ByteArrayOutputStream)outputs.get(key).stream).reset();
          case "filter-output/flush" -> outputs.get(key).stream.flush();
          case "byte-array-output/close", "filter-output/close" -> {
            outputs.get(key).closeInvoked = true;
            outputs.get(key).stream.close();
          }
          case "random-access/open" -> {
            if (randoms.get(key) != null) randoms.get(key).file.close();
            Path path = fileCaseRoot(input, fields[1]).resolve("random.bin");
            Files.write(path, (byte[])values[0]);
            randoms.put(key, new RandomCase(new RandomAccessFile(path.toFile(), (String)values[1]), path));
          }
          case "random-access/read-one" -> result = "i:" + randoms.get(key).file.read();
          case "random-access/read-buffer" -> {
            var random = randoms.get(key);
            random.lastBuffer = ((byte[])values[0]).clone();
            int read = random.file.read(random.lastBuffer, intValue(values[1]), intValue(values[2]));
            result = bufferResult(read, random.lastBuffer);
          }
          case "random-access/read-fully" -> {
            var random = randoms.get(key);
            random.lastBuffer = ((byte[])values[0]).clone();
            random.file.readFully(random.lastBuffer);
            result = stringToken(HexFormat.of().formatHex(random.lastBuffer));
          }
          case "random-access/write-buffer" -> randoms.get(key).file.write((byte[])values[0]);
          case "random-access/seek" -> randoms.get(key).file.seek(longValue(values[0]));
          case "random-access/skip" -> result = "i:" + randoms.get(key).file.skipBytes(intValue(values[0]));
          case "random-access/length" -> result = "i:" + randoms.get(key).file.length();
          case "random-access/position" -> result = "i:" + randoms.get(key).file.getFilePointer();
          case "random-access/set-length" -> randoms.get(key).file.setLength(longValue(values[0]));
          case "random-access/close" -> {
            randoms.get(key).closed = true;
            randoms.get(key).file.close();
          }
          case "string/hash" -> result = "i:" + ((String)values[0]).hashCode();
          case "long/hash" -> result = "i:" + Long.hashCode(number);
          case "file/inspect" -> result = stringToken(fileInspection(new File((String)values[0])));
          case "file/absolute" -> result = stringToken(new File((String)values[0]).getAbsolutePath());
          case "file/parent-child" -> result = stringToken(new File((String)values[0], (String)values[1]).getPath());
          case "file/file-parent-child" -> result = stringToken(new File(
              values[0] == null ? null : new File((String)values[0]), (String)values[1]).getPath());
          case "file/uri" -> result = stringToken(new File(new URI((String)values[0])).getPath());
          case "file/equals" -> result = "i:" + (new File((String)values[0]).equals(new File((String)values[1])) ? 1 : 0);
          case "file/to-path" -> result = stringToken(new File((String)values[0]).toPath().toString());
          case "file/create" -> result = "i:" + (new File((String)values[0]).createNewFile() ? 1 : 0);
          case "file/delete" -> result = "i:" + (new File((String)values[0]).delete() ? 1 : 0);
          case "file/open" -> {
            try (var stream = new FileInputStream(new File((String)values[0]))) {
              result = "i:" + stream.read();
            }
          }
          case "file/list-count" -> {
            var children = new File((String)values[0]).listFiles();
            result = children == null ? "n:" : "i:" + children.length;
          }
          default -> throw new AssertionError(operation);
        }
      } catch (NullPointerException error) {
        exception = "null-pointer";
      } catch (java.nio.BufferUnderflowException error) {
        exception = "buffer-underflow";
      } catch (java.nio.BufferOverflowException error) {
        exception = "buffer-overflow";
      } catch (java.nio.ReadOnlyBufferException error) {
        exception = "read-only";
      } catch (java.nio.InvalidMarkException error) {
        exception = "invalid-mark";
      } catch (EOFException error) {
        exception = "eof";
      } catch (InvalidPathException error) {
        exception = "invalid-path";
      } catch (FileNotFoundException error) {
        exception = "file-not-found";
      } catch (IndexOutOfBoundsException error) {
        exception = "index-out-of-bounds";
      } catch (ArithmeticException error) {
        exception = "arithmetic";
      } catch (IllegalArgumentException error) {
        exception = "illegal-argument";
      } catch (IOException error) {
        exception = "io";
      }
      var state = switch (fields[0]) {
        case "byte-array-input", "filter-input" -> inputState(inputs.get(key));
        case "byte-array-output", "filter-output" -> outputState(outputs.get(key));
        case "random-access" -> randomState(randoms.get(key));
        case "byte-buffer" -> bufferState(buffers.get(key));
        case "binary-data" -> dataState(dataCases.get(key));
        case "compression" -> compressionState(compressionCase);
        default -> "n:";
      };
      results.add(String.join("\t", fields[0], fields[1], fields[2], fields[3],
          exception, result, state));
      }
    } finally {
      for (var stream : inputs.values()) stream.stream.close();
      for (var stream : outputs.values()) stream.stream.close();
      for (var random : randoms.values()) random.file.close();
      for (var data : dataCases.values()) { data.input.close(); data.output.close(); }
      deleteTree(sandboxParent);
    }
    Files.writeString(output, String.join("\n", results) + "\n", StandardCharsets.UTF_8);
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length == 1 && arguments[0].equals("--runtime-version")) {
      System.out.println(System.getProperty("java.runtime.version"));
      return;
    }
    if (arguments.length == 3 && arguments[0].equals("--cases")) {
      runCases(Path.of(arguments[1]), Path.of(arguments[2]));
      return;
    }
    if (arguments.length != 2) {
      throw new IllegalArgumentException(
          "Usage: JavaCompatOracle <output.tsv> <TypeProvenance.tsv>");
    }

    var provenance = readProvenance(Path.of(arguments[1]));
    var observations = new ArrayList<Observation>();
    for (var row : provenance) {
      Class.forName(row.jdkContract());
      observations.add(
          new Observation("type-contract", row.compatType(), "available"));
    }
    observations.addAll(behaviorObservations());
    observations.sort(
        Comparator.comparing(Observation::family)
            .thenComparing(Observation::id));
    var lines = new ArrayList<String>();
    lines.add(HEADER);
    for (var row : observations) {
      lines.add(row.family() + "\t" + row.id() + "\t" + row.value());
    }
    Files.writeString(
        Path.of(arguments[0]),
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8);
  }

  private static List<Provenance> readProvenance(Path file)
      throws Exception {
    var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    if (lines.isEmpty()
        || !lines.get(0).equals(
            "compat-type\tjdk-contract\ttargets\tproof-rows")) {
      throw new IllegalArgumentException(
          "JavaCompat provenance has the wrong header.");
    }
    var rows = new ArrayList<Provenance>();
    for (var index = 1; index < lines.size(); index++) {
      var fields = lines.get(index).split("\t", -1);
      if (fields.length != 4) {
        throw new IllegalArgumentException(
            "Malformed JavaCompat provenance row " + (index + 1));
      }
      rows.add(
          new Provenance(
              fields[0], fields[1], fields[2], fields[3].split(",")));
    }
    return rows;
  }

  private static List<Observation> behaviorObservations()
      throws Exception {
    return List.of(
        observe("atomic-primitives", atomicPrimitives()),
        observe("base64", base64Contract()),
        observe("bit-set", bitSetContract()),
        observe("boxed-wrappers", boxedWrappersContract()),
        observe("byte-buffer", byteBufferContract()),
        observe("charset-malformed", charsetMalformedContract()),
        observe("collections", collectionsContract()),
        observe("compression", compressionContract()),
        observe("crc32", crc32Contract()),
        observe("data-output", dataOutputContract()),
        observe("file-paths", filePathsContract()),
        observe("decimal-format", decimalFormatContract()),
        observe("message-digest", messageDigestContract()),
        observe("message-format", messageFormatContract()),
        observe("optional", optionalContract()),
        observe("regex", regexContract()),
        observe("signed-byte-or", signedByteOrContract()),
        observe("strict-math", strictMathContract()),
        observe("string-identity", stringIdentityContract()),
        observe("string-tools", stringToolsContract()),
        observe("time-format", timeFormatContract()),
        observe("uri", uriContract()),
        observe("xpath", xpathContract()));
  }

  private static Observation observe(String id, String value) {
    return new Observation("behavior", id, value);
  }

  private static String filePathsContract() throws Exception {
    var values = new ArrayList<String>();
    for (var path : new String[] { "", "missing-file-path", "a//b/", "a/../b", "\0", " " }) {
      var file = new java.io.File(path);
      values.add(Base64.getEncoder().encodeToString(file.getPath().getBytes(StandardCharsets.UTF_8)));
      values.add(Base64.getEncoder().encodeToString(file.getName().getBytes(StandardCharsets.UTF_8)));
      values.add(Boolean.toString(file.exists()));
      values.add(Boolean.toString(file.isDirectory()));
      values.add(Integer.toString(file.hashCode()));
    }
    for (var path : new String[] { "/a/../b", "/a b", "/a#b", "/a%z", "/aé", "/a\0b" })
      values.add(new java.io.File(path).toURI().toString());
    for (var parent : new String[] { null, "", "a", "/" })
      values.add(new java.io.File(parent, "/b").getPath());
    try { new java.io.File((String)null); values.add("accepted-null"); }
    catch (NullPointerException e) { values.add("null-rejected"); }
    for (var path : new String[] { "", "\0" }) {
      try (var stream = new java.io.FileInputStream(new java.io.File(path))) { values.add("opened"); }
      catch (java.io.FileNotFoundException e) { values.add("open-rejected"); }
    }
    for (var uri : new String[] { "file:/a/../b", "file:///a%20b", "file:/a%00b", "file:/", "file://host/a", "file:/a?x", "file:/a#x", "file:a", "/a" }) {
      try { values.add(new java.io.File(new java.net.URI(uri)).getPath()); }
      catch (IllegalArgumentException e) { values.add("uri-rejected"); }
    }
    for (var parent : new String[] { null, "", "a", "/" }) {
      values.add(new java.io.File(parent == null ? null : new java.io.File(parent), "b").getPath());
      try { new java.io.File(parent, (String)null); values.add("accepted-child"); }
      catch (NullPointerException e) { values.add("child-rejected"); }
    }
    var empty = new java.io.File("");
    values.add(empty.canRead() + ":" + empty.canWrite() + ":" + empty.length() + ":" + empty.lastModified() + ":" + (empty.listFiles() == null));
    values.add(empty.toPath().toString());
    try { new java.io.File("\0").toPath(); values.add("path-accepted"); }
    catch (java.nio.file.InvalidPathException e) { values.add("path-rejected"); }
    try { new java.io.File((java.net.URI)null); values.add("accepted-null-uri"); }
    catch (NullPointerException e) { values.add("null-uri-rejected"); }
    try (var file = new java.io.RandomAccessFile(new java.io.File(""), "bad")) { values.add("mode-accepted"); }
    catch (IllegalArgumentException e) { values.add("mode-rejected"); }
    var command = System.getProperty("os.name").startsWith("Windows")
        ? new String[] { "cmd.exe", "/c", "exit" } : new String[] { "/bin/true" };
    try { new ProcessBuilder(command).directory(new java.io.File("")).start().waitFor(); values.add("process-started"); }
    catch (java.io.IOException e) { values.add("process-dir-rejected"); }
    var root = Files.createTempDirectory("dripsharp-file-");
    try {
      var spaced = Files.createDirectory(root.resolve(" "));
      var ordinary = Files.writeString(root.resolve("ordinary"), "abc");
      values.add(Boolean.toString(spaced.toFile().isDirectory()));
      values.add(Boolean.toString(ordinary.toFile().isFile()));
      values.add(Long.toString(ordinary.toFile().length()));
      values.add(Integer.toString(root.toFile().listFiles().length));
    } finally {
      Files.deleteIfExists(root.resolve("ordinary"));
      Files.deleteIfExists(root.resolve(" "));
      Files.delete(root);
    }
    return String.join("|", values);
  }

  private static String atomicPrimitives() {
    var bool = new AtomicBoolean();
    var integer = new AtomicInteger(40);
    var first = new Object();
    var second = new Object();
    var reference = new AtomicReference<>(first);
    var local = ThreadLocal.withInitial(() -> "initial");
    var previous = bool.getAndSet(true);
    var swapped = bool.compareAndSet(true, false);
    local.set("thread");
    return previous
        + "|"
        + swapped
        + "|"
        + bool.get()
        + "|"
        + integer.incrementAndGet()
        + "|"
        + (reference.getAndSet(second) == first)
        + "|"
        + local.get();
  }

  private static String base64Contract() {
    return Base64.getEncoder()
        .encodeToString("compat".getBytes(StandardCharsets.UTF_8));
  }

  private static String bitSetContract() {
    var bits = new BitSet();
    bits.set(2, 5);
    bits.clear(3);
    var trace = new StringBuilder();
    trace.append(bits.get(2)).append('|').append(bits.get(3)).append('|').append(bits.nextSetBit(3));
    // Word boundaries, ranges inside one word and across words, empty ranges, growth.
    bits.set(63);
    bits.set(64);
    bits.set(127, 130);
    bits.set(200, 200);
    bits.set(1000);
    bits.set(320, 384);
    trace.append('|').append(bitSetRuns(bits));
    for (int from : new int[] {0, 5, 63, 64, 65, 130, 384, 1000, 1001, 5000})
      trace.append(',').append(bits.nextSetBit(from));
    trace.append(',').append(bits.get(129)).append(',').append(bits.get(130))
        .append(',').append(bits.get(5000)).append(',').append(bits.get(383));
    // Clearing the highest bit shrinks the logical size; clearing past it is a no-op.
    bits.clear(1000);
    bits.clear(4000);
    bits.clear(64);
    bits.clear(383);
    trace.append('|').append(bitSetRuns(bits)).append(',').append(bits.nextSetBit(383))
        .append(',').append(bits.nextSetBit(400));
    bits.set(1, 191);
    trace.append('|').append(bitSetRuns(bits));
    bits.clear();
    trace.append('|').append(bits.nextSetBit(0)).append(',').append(bits.get(2));
    bits.set(70, 71);
    bits.set(0, 64);
    bits.set(65, 70);
    trace.append('|').append(bitSetRuns(bits));
    // ScratchFile: free pages set in bulk, taken with nextSetBit(0)/clear, freed and enlarged.
    bits.clear();
    bits.set(0, 100_000);
    long taken = 0;
    for (int i = 0; i < 25_600; i++) {
      int index = bits.nextSetBit(0);
      bits.clear(index);
      taken += index;
    }
    bits.set(7);
    bits.set(12_345);
    trace.append('|').append(taken).append(',').append(bits.nextSetBit(0))
        .append(',').append(bits.nextSetBit(8)).append(',').append(bits.nextSetBit(12_346));
    bits.clear(7);
    bits.clear(12_345);
    bits.set(25_600, 100_000);
    for (int i = 0; i < 74_400; i++) bits.clear(bits.nextSetBit(0));
    trace.append(',').append(bits.nextSetBit(0));
    bits.set(100_000, 100_016);
    trace.append(',').append(bitSetRuns(bits));
    // Negative indexes and reversed ranges.
    trace.append('|').append(bitSetFailure(() -> bits.get(-1)))
        .append(',').append(bitSetFailure(() -> { bits.set(-2); return null; }))
        .append(',').append(bitSetFailure(() -> { bits.clear(-3); return null; }))
        .append(',').append(bitSetFailure(() -> bits.nextSetBit(-4)))
        .append(',').append(bitSetFailure(() -> { bits.set(-5, 2); return null; }))
        .append(',').append(bitSetFailure(() -> { bits.set(2, -6); return null; }))
        .append(',').append(bitSetFailure(() -> { bits.set(9, 8); return null; }))
        .append(',').append(bitSetRuns(bits));
    return trace.toString();
  }

  private static String bitSetRuns(BitSet bits) {
    var runs = new StringBuilder("[");
    for (int index = bits.nextSetBit(0); index >= 0; index = bits.nextSetBit(index + 1)) {
      int start = index;
      while (bits.get(index + 1)) index++;
      runs.append(start).append('-').append(index).append(';');
    }
    return runs.append(']').toString();
  }

  private static String bitSetFailure(java.util.function.Supplier<Object> operation) {
    try {
      return "ok:" + operation.get();
    } catch (IndexOutOfBoundsException error) {
      return "index-out-of-bounds:" + error.getMessage();
    }
  }

  private static String byteBufferContract() {
    var buffer = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
    var integer = buffer.getInt();
    return integer + "|" + buffer.remaining() + "|" + buffer.get();
  }

  private static String charsetMalformedContract() {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(new byte[] {(byte) 0xc3, 0x28}));
      return "accepted";
    } catch (CharacterCodingException expected) {
      return "rejected";
    }
  }

  private static String collectionsContract() {
    var linked = new LinkedHashMap<String, String>();
    linked.put("first", "before");
    linked.put("second", "two");
    var entry = linked.entrySet().iterator().next();
    var prior = entry.setValue("after");
    var deque = new ArrayDeque<String>();
    deque.add("tail");
    deque.push("head");
    return prior
        + "|"
        + entry.getValue()
        + "|"
        + String.join(",", linked.keySet())
        + "|"
        + deque.pop()
        + ","
        + deque.pop();
  }

  private static String compressionContract() throws Exception {
    var compressed = new ByteArrayOutputStream();
    try (var compressor = new DeflaterOutputStream(compressed)) {
      compressor.write("deflate-body".getBytes(StandardCharsets.UTF_8));
    }
    var decoded = new ByteArrayOutputStream();
    try (var inflater = new InflaterOutputStream(decoded)) {
      inflater.write(compressed.toByteArray());
      inflater.flush();
    }
    return decoded.toString(StandardCharsets.UTF_8);
  }

  private static String crc32Contract() {
    var crc = new CRC32();
    var value = "123456789".getBytes(StandardCharsets.US_ASCII);
    crc.update(value, 0, value.length);
    return Long.toString(crc.getValue());
  }

  private static String dataOutputContract() throws Exception {
    var output = new ByteArrayOutputStream();
    try (var data = new DataOutputStream(output)) {
      data.writeByte(0x7f);
      data.writeShort(0x1234);
      data.writeInt(0x89abcdef);
    }
    return HexFormat.of().formatHex(output.toByteArray());
  }

  private static String decimalFormatContract() {
    var format =
        new DecimalFormat(
            "0.0000000", DecimalFormatSymbols.getInstance(Locale.ROOT));
    format.setRoundingMode(RoundingMode.HALF_EVEN);
    return format.format(123456789.123456789d)
        + "|"
        + format.format(-0.0d);
  }

  private static String messageDigestContract() throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest("abc".getBytes(StandardCharsets.US_ASCII)));
  }

  private static String messageFormatContract() {
    var format =
        new MessageFormat("{0}={1,number,000.00}", Locale.US);
    return format.format(new Object[] {"value", 12.5d});
  }

  private static String optionalContract() {
    var present = Optional.of("x").map(value -> value + "y")
        .orElse("missing");
    var empty = Optional.<String>empty().orElse("fallback");
    return present + "|" + empty;
  }

  private static String boxedWrappersContract() {
    float floatNaNPayload = Float.intBitsToFloat(0x7fc00001);
    double doubleNaNPayload = Double.longBitsToDouble(0x7ff8000000000001L);
    Object[] values = {
      'A', '\uffff', true, false,
      (byte) -1, (byte) -128, (short) -1, (short) -32768,
      0.0f, -0.0f, Float.NaN, floatNaNPayload,
      Float.intBitsToFloat(0xffc00000),
      Float.POSITIVE_INFINITY, Float.MIN_VALUE, 1.5f,
      0.0d, -0.0d, Double.NaN, doubleNaNPayload,
      Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Math.PI
    };
    var hashes = new StringJoiner(",");
    for (Object value : values) {
      hashes.add(Integer.toString(value.hashCode()));
    }
    int combined = Objects.hash('A', true, -0.0d, 1.5f);
    var equality =
        String.join(
            ",",
            Boolean.toString(Objects.equals(0.0d, -0.0d)),
            Boolean.toString(Objects.equals(Double.NaN, doubleNaNPayload)),
            Boolean.toString(Objects.equals(0.0f, -0.0f)),
            Boolean.toString(Objects.equals(Float.NaN, floatNaNPayload)),
            Boolean.toString(Objects.equals(1.5d, 1.5f)));
    var keys = new LinkedHashMap<Object, String>();
    for (Object key :
        new Object[] {0.0d, -0.0d, Double.NaN, doubleNaNPayload, 0.0f, -0.0f}) {
      keys.put(key, "x");
    }
    return hashes + "|" + combined + "|" + equality + "|" + keys.size();
  }

  private static String regexContract() {
    Matcher matcher =
        Pattern.compile("(?<word>\\p{L}+)").matcher("é42");
    if (!matcher.find()) {
      throw new IllegalStateException("Regex did not match.");
    }
    return matcher.group("word")
        + "|"
        + matcher.start()
        + "|"
        + matcher.end();
  }

  private static String signedByteOrContract() {
    byte value = -128;
    value |= 1;
    var first = value;
    value = 1;
    value |= 0x180;
    return first + "|" + value;
  }

  private static String strictMathContract() {
    return Double.toString(StrictMath.sin(2.34d))
        + "|"
        + Double.toString(StrictMath.cos(2.34d))
        + "|"
        + Double.toString(StrictMath.log10(2.34d))
        + "|"
        + Double.toString(StrictMath.pow(2.3d, -4.0d));
  }

  private static String stringIdentityContract() {
    return "DripSharp".hashCode()
        + "|"
        + Integer.toHexString(-65536)
        + "|"
        + Long.toHexString(-65536L)
        + "|"
        + String.valueOf((Object) null);
  }

  private static String stringToolsContract() {
    var joiner = new StringJoiner(",", "[", "]").add("a").add("b");
    var tokenizer = new StringTokenizer("one two\tthree");
    var tokens = new ArrayList<String>();
    while (tokenizer.hasMoreTokens()) {
      tokens.add(tokenizer.nextToken());
    }
    return joiner + "|" + String.join(",", tokens);
  }

  private static String timeFormatContract() {
    var instant =
        ZonedDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(instant)
        + "|"
        + TimeUnit.MILLISECONDS.toMillis(1500);
  }

  private static String uriContract() throws Exception {
    var hostless = new URI("http:///submit");
    var basis = URI.create("file:///tmp/PklProject");
    var resolved = basis.resolve(".");
    return (hostless.getHost() == null ? "null" : hostless.getHost())
        + "|"
        + hostless.getRawPath()
        + "|"
        + (resolved.getAuthority() == null
            ? "null"
            : resolved.getAuthority())
        + "|"
        + resolved.getRawPath();
  }

  private static String xpathContract() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    var document =
        factory
            .newDocumentBuilder()
            .parse(
                new InputSource(
                    new StringReader(
                        "<root><item>value</item></root>")));
    return XPathFactory.newInstance()
        .newXPath()
        .evaluate("/root/item", document);
  }
}
