import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.Optional;
import java.util.StringJoiner;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
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

  public static void main(String[] arguments) throws Exception {
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
        observe("byte-buffer", byteBufferContract()),
        observe("charset-malformed", charsetMalformedContract()),
        observe("collections", collectionsContract()),
        observe("compression", compressionContract()),
        observe("crc32", crc32Contract()),
        observe("data-output", dataOutputContract()),
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
    return bits.get(2) + "|" + bits.get(3) + "|" + bits.nextSetBit(3);
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
