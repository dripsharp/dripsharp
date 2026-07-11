import java.io.BufferedWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pkl.core.DataSize;
import org.pkl.core.Duration;
import org.pkl.core.Evaluator;
import org.pkl.core.ModuleSource;
import org.pkl.core.PModule;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.Pair;
import org.pkl.core.PklException;

/** Independent JVM behavior oracle for the public upstream pkl-core API. */
public final class CoreUpstreamOracle {
  private static final Base64.Encoder BASE64 = Base64.getEncoder();
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private CoreUpstreamOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("manifest and output paths are required");
    var manifest = Path.of(args[0]);
    var output = Path.of(args[1]);
    var work = output.toAbsolutePath().getParent().resolve("upstream-work");
    Files.createDirectories(work);

    try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
        Evaluator evaluator = Evaluator.preconfigured()) {
      writeValueModelObservations(writer);
      for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
        if (line.isEmpty()) continue;
        String[] fields = line.split("\\t", -1);
        if (fields.length != 4) throw new IllegalArgumentException("invalid core manifest line");
        String id = fields[0];
        String operation = fields[1];
        String source = decode(fields[2]);
        String argument = decode(fields[3]);
        Path module = work.resolve(safeName(id) + ".pkl");
        Files.writeString(module, source, StandardCharsets.UTF_8);
        write(writer, id, operation, observe(evaluator, operation, module, argument));
      }
    }
  }

  private static void writeValueModelObservations(BufferedWriter writer) throws Exception {
    var duration = Duration.ofSeconds(90);
    write(
        writer,
        "@duration",
        "VALUE",
        normalize(duration)
            + "|minutes="
            + doubleBits(duration.inMinutes())
            + "|iso="
            + encode(duration.toIsoString())
            + "|equal="
            + duration.equals(Duration.ofMinutes(1.5)));

    var dataSize = DataSize.ofKibibytes(2);
    write(
        writer,
        "@data-size",
        "VALUE",
        normalize(dataSize)
            + "|bytes="
            + doubleBits(dataSize.inBytes())
            + "|equal="
            + dataSize.equals(DataSize.ofBytes(2048)));

    var pair = new Pair<Object, Object>("answer", 42L);
    write(writer, "@pair", "VALUE", normalize(pair) + "|render=" + encode(pair.toString()));
    write(
        writer,
        "@null",
        "VALUE",
        normalize(PNull.getInstance())
            + "|singleton="
            + (PNull.getInstance() == PNull.getInstance())
            + "|render="
            + encode(PNull.getInstance().toString()));

    var uri = ModuleSource.uri(URI.create("file:///independent-core-oracle.pkl"));
    write(
        writer,
        "@module-source",
        "RUNTIME",
        "uri="
            + encode(uri.getUri().toString())
            + ",contents-null="
            + (uri.getContents() == null));
  }

  private static String observe(Evaluator evaluator, String operation, Path module, String argument) {
    try {
      ModuleSource source = ModuleSource.uri(module.toUri());
      Object result =
          switch (operation) {
            case "EVALUATE" -> evaluator.evaluate(source);
            case "EXPRESSION" -> evaluator.evaluateExpression(source, argument);
            case "OUTPUT_VALUE" -> evaluator.evaluateOutputValue(source);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
          };
      return "OK|" + normalize(result);
    } catch (PklException error) {
      if (System.getenv("VIBEFORMER_DIFFERENTIAL_DEBUG") != null) error.printStackTrace(System.err);
      return "ERROR|" + normalizeError(error.getMessage());
    }
  }

  private static String normalize(Object value) {
    if (value == null || value == PNull.getInstance()) return "null";
    if (value instanceof String string) return "string:" + encode(string);
    if (value instanceof Boolean bool) return "bool:" + bool;
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
      return "int:" + ((Number) value).longValue();
    }
    if (value instanceof Float || value instanceof Double) {
      return "float:" + doubleBits(((Number) value).doubleValue());
    }
    if (value instanceof Duration duration) {
      return "duration:" + doubleBits(duration.getValue()) + "@" + duration.getUnit().getSymbol();
    }
    if (value instanceof DataSize size) {
      return "data-size:" + doubleBits(size.getValue()) + "@" + size.getUnit().getSymbol();
    }
    if (value instanceof Pair<?, ?> pair) {
      return "pair(" + normalize(pair.getFirst()) + "," + normalize(pair.getSecond()) + ")";
    }
    if (value instanceof PModule module) {
      return "module:" + encode(module.getModuleName()) + normalizeProperties(module.getProperties());
    }
    if (value instanceof PObject object) {
      return "object:" + encode(object.getClassInfo().getQualifiedName()) + normalizeProperties(object.getProperties());
    }
    if (value instanceof Map<?, ?> map) return normalizeMap(map);
    if (value instanceof Set<?> set) {
      var values = new ArrayList<String>();
      for (Object item : set) values.add(normalize(item));
      values.sort(Comparator.naturalOrder());
      return "set[" + String.join(",", values) + "]";
    }
    if (value instanceof Iterable<?> iterable) {
      var values = new ArrayList<String>();
      for (Object item : iterable) values.add(normalize(item));
      return "list[" + String.join(",", values) + "]";
    }
    throw new IllegalArgumentException("unsupported exported value: " + value.getClass().getName());
  }

  private static String normalizeProperties(Map<String, Object> properties) {
    var entries = new ArrayList<String>();
    properties.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> entries.add(encode(entry.getKey()) + "=" + normalize(entry.getValue())));
    return "{" + String.join(",", entries) + "}";
  }

  private static String normalizeMap(Map<?, ?> map) {
    var entries = new ArrayList<String>();
    for (var entry : map.entrySet()) {
      entries.add(normalize(entry.getKey()) + "=" + normalize(entry.getValue()));
    }
    entries.sort(Comparator.naturalOrder());
    return "map{" + String.join(",", entries) + "}";
  }

  private static String normalizeError(String message) {
    if (message.contains("Unexpected token")) return "syntax:unexpected-token";
    if (message.contains("Expected value of type `String`, but got type `Int`")) {
      return "type:expected-string-got-int";
    }
    if (message.contains("Cannot find property `missing`")) return "evaluation:missing-property";
    return "other:" + encode(message.lines().findFirst().orElse(""));
  }

  private static String doubleBits(double value) {
    return String.format("%016x", Double.doubleToRawLongBits(value));
  }

  private static String safeName(String id) {
    return id.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  private static String decode(String value) {
    return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
  }

  private static String encode(String value) {
    return BASE64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void write(BufferedWriter writer, String id, String kind, String observation)
      throws Exception {
    writer.write(id);
    writer.write('\t');
    writer.write(kind);
    writer.write('\t');
    writer.write(encode(observation));
    writer.newLine();
  }
}
