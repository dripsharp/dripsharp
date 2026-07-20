import java.io.BufferedWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.pkl.core.DataSize;
import org.pkl.core.Duration;
import org.pkl.core.Evaluator;
import org.pkl.core.EvaluatorBuilder;
import org.pkl.core.FileOutput;
import org.pkl.core.ModuleSource;
import org.pkl.core.PClassInfo;
import org.pkl.core.PModule;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.Pair;
import org.pkl.core.PklException;
import org.pkl.core.RendererException;
import org.pkl.core.ValueConverter;
import org.pkl.core.ValueFormatter;
import org.pkl.core.ValueRenderer;
import org.pkl.core.ValueRenderers;
import org.pkl.core.ValueVisitor;

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

    try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      writeValueModelObservations(writer);
      for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
        if (line.isEmpty()) continue;
        String[] fields = line.split("\\t", -1);
        if (fields.length != 4) throw new IllegalArgumentException("invalid core manifest line");
        String id = fields[0];
        String operation = fields[1];
        String source = decode(fields[2]);
        String argument = decode(fields[3]);
        prepareFixtures(work, operation, argument);
        Path module = work.resolve(safeName(id) + ".pkl");
        Files.writeString(module, source, StandardCharsets.UTF_8);
        try (Evaluator evaluator = Evaluator.preconfigured()) {
          write(writer, id, operation, observe(evaluator, operation, module, argument));
        }
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

    var classInfo = PClassInfo.get("pkl.base", "String", URI.create("pkl:base"));
    write(
        writer,
        "@class-info",
        "VALUE",
        "qualified="
            + encode(classInfo.getQualifiedName())
            + ",display="
            + encode(classInfo.getDisplayName())
            + ",equal="
            + PClassInfo.String.equals(classInfo));

    write(writer, "@value-visitation", "VALUE", observeValueVisitation());
    write(writer, "@value-conversion", "VALUE", observeValueConversion());
    write(writer, "@value-equality-order", "VALUE", observeValueEqualityAndOrder());
    write(writer, "@value-formatter", "FORMAT", observeValueFormatter());
    write(writer, "@value-renderers", "RENDER", observeValueRenderers());
    write(writer, "@idiomatic-data-api", "DOTNET", "bytes=true|facades=true|nullable=true");
  }

  private static String observeValueVisitation() {
    var properties = new LinkedHashMap<String, Object>();
    properties.put("name", "Pigeon");
    var object = new PObject(PClassInfo.Object, properties);
    var module =
        new PModule(
            URI.create("repl:visitor"),
            "visitor.module",
            PClassInfo.forModuleClass("visitor.module", URI.create("repl:visitor")),
            properties);
    var set = new LinkedHashSet<Object>();
    set.add("set");
    var map = new LinkedHashMap<Object, Object>();
    map.put("key", 1L);
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      var schema =
          evaluator.evaluateSchema(
              ModuleSource.text("class Bird { name: String }\ntypealias Name = String\n"));
      var reference =
          new org.pkl.core.Reference(object, "data", List.of(object), org.pkl.core.PType.UNKNOWN);
      List<Object> values =
          List.of(
              PNull.getInstance(),
              "text",
              true,
              1L,
              1.5d,
              Duration.ofSeconds(2),
              DataSize.ofBytes(3),
              new byte[] {0, 127, (byte) 128, (byte) 255},
              new Pair<>("first", 2L),
              List.of("list"),
              set,
              map,
              object,
              module,
              schema.getClasses().get("Bird"),
              schema.getTypeAliases().get("Name"),
              Pattern.compile("a.+b"),
              reference);
      var observed = new ArrayList<String>();
      for (Object value : values) {
        var visitor = new RecordingVisitor();
        visitor.visit(value);
        observed.add(visitor.observation);
      }
      var direct = new RecordingVisitor();
      module.accept(direct);
      boolean invalid = throwsIllegalArgument(() -> new RecordingVisitor().visit(new Object()));
      return String.join(",", observed) + "|direct=" + direct.observation + "|invalid=" + invalid;
    }
  }

  private static String observeValueConversion() {
    var converter = new RecordingConverter();
    var properties = new LinkedHashMap<String, Object>();
    properties.put("name", "Pigeon");
    var object = new PObject(PClassInfo.Object, properties);
    var module =
        new PModule(
            URI.create("repl:converter"),
            "converter.module",
            PClassInfo.forModuleClass("converter.module", URI.create("repl:converter")),
            properties);
    var set = new LinkedHashSet<Object>();
    set.add("set");
    var map = new LinkedHashMap<Object, Object>();
    map.put("key", 1L);
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      var schema =
          evaluator.evaluateSchema(
              ModuleSource.text("class Bird { name: String }\ntypealias Name = String\n"));
      var reference =
          new org.pkl.core.Reference(object, "data", List.of(object), org.pkl.core.PType.UNKNOWN);
      List<Object> values =
          List.of(
              PNull.getInstance(),
              "text",
              true,
              1L,
              1.5d,
              Duration.ofSeconds(2),
              DataSize.ofBytes(3),
              new Pair<>("first", 2L),
              List.of("list"),
              set,
              map,
              object,
              module,
              schema.getClasses().get("Bird"),
              schema.getTypeAliases().get("Name"),
              Pattern.compile("a.+b"),
              reference);
      var observed = new ArrayList<String>();
      for (Object value : values) observed.add(converter.convert(value));
      boolean bytesRejected = throwsIllegalArgument(() -> converter.convert(new byte[] {1}));
      boolean invalid = throwsIllegalArgument(() -> converter.convert(new Object()));
      return String.join(",", observed)
          + "|direct-bytes="
          + converter.convertBytes(new byte[] {1})
          + "|bytes-rejected="
          + bytesRejected
          + "|invalid="
          + invalid;
    }
  }

  private static String observeValueEqualityAndOrder() {
    var leftProperties = new LinkedHashMap<String, Object>();
    leftProperties.put("name", "Pigeon");
    leftProperties.put("age", 42L);
    var rightProperties = new LinkedHashMap<String, Object>();
    rightProperties.put("age", 42L);
    rightProperties.put("name", "Pigeon");
    var left = new PObject(PClassInfo.Object, leftProperties);
    var right = new PObject(PClassInfo.Object, rightProperties);
    var module =
        new PModule(
            URI.create("repl:equality"),
            "equality.module",
            PClassInfo.forModuleClass("equality.module", URI.create("repl:equality")),
            leftProperties);
    var moduleCopy =
        new PModule(
            URI.create("repl:equality"),
            "equality.module",
            PClassInfo.forModuleClass("equality.module", URI.create("repl:equality")),
            rightProperties);
    return "object="
        + (left.equals(right) && left.hashCode() == right.hashCode())
        + "|module="
        + (module.equals(moduleCopy) && module.hashCode() == moduleCopy.hashCode())
        + "|pair="
        + new Pair<>("a", 1L).equals(new Pair<>("a", 1L))
        + "|duration="
        + Duration.ofSeconds(90).equals(Duration.ofMinutes(1.5))
        + "|size="
        + DataSize.ofKibibytes(2).equals(DataSize.ofBytes(2048))
        + "|class="
        + PClassInfo.String.equals(PClassInfo.get("pkl.base", "String", URI.create("pkl:base")))
        + "|order="
        + String.join(",", left.getProperties().keySet())
        + "|identity="
        + (left.getProperties() == leftProperties)
        + "|render="
        + encode(module.toString());
  }

  private static String observeValueFormatter() {
    var basic = ValueFormatter.basic();
    var custom = ValueFormatter.withCustomStringDelimiters();
    var multiline = new ValueFormatter(true, true);
    var builder = new StringBuilder();
    custom.formatStringValue("\"\"start\\#\nnext\t\r", "  ", builder);
    return "basic="
        + encode(basic.formatStringValue("quote\"slash\\\n", ""))
        + "|custom-quote="
        + encode(custom.formatStringValue("\"", ""))
        + "|custom-prefix="
        + encode(custom.formatStringValue("\"\"start", ""))
        + "|multiline="
        + encode(multiline.formatStringValue("first\nsecond\"\"\"#\\##", "  "))
        + "|builder="
        + encode(builder.toString());
  }

  private static String observeValueRenderers() {
    try (Evaluator evaluator = Evaluator.preconfigured()) {
      PModule module =
          evaluator.evaluate(
              ModuleSource.text(
                  "name = \"Pigeon\"\nage = 3\nactive = true\nitems = List(\"a\", 2)\nnested { value = \"x\" }\nnullable = null\n"));
      var jsonWriter = new StringWriter();
      ValueRenderers.json(jsonWriter, "  ", true).renderDocument(module);
      String json = jsonWriter.toString();
      var pcfWriter = new StringWriter();
      ValueRenderers.pcf(pcfWriter, "  ", false, true).renderDocument(module);
      String pcf = pcfWriter.toString();
      var plistWriter = new StringWriter();
      ValueRenderers.plist(plistWriter, "  ").renderDocument(module);
      String plist = plistWriter.toString();
      var propertiesWriter = new StringWriter();
      PModule propertiesModule =
          evaluator.evaluate(
              ModuleSource.text(
                  "name = \"Pigeon\"\nage = 3\nactive = true\nnested { value = \"x\" }\nnullable = null\n"));
      ValueRenderers.properties(propertiesWriter, false, true).renderDocument(propertiesModule);
      String properties = propertiesWriter.toString();
      return "json="
          + encode(json)
          + "|pcf="
          + encode(pcf)
          + "|plist="
          + encode(plist)
          + "|properties="
          + encode(properties)
          + "|newline="
          + (json.endsWith("\n") && pcf.endsWith("\n") && plist.endsWith("\n") && properties.endsWith("\n"))
          + "|invalid="
          + invalidRenderer(ValueRenderers.json(new StringWriter(), "  ", false))
          + ":"
          + invalidRenderer(ValueRenderers.pcf(new StringWriter(), "  ", false, false))
          + ":"
          + invalidRenderer(ValueRenderers.plist(new StringWriter(), "  "))
          + ":"
          + invalidRenderer(ValueRenderers.properties(new StringWriter(), false, true));
    }
  }

  private static boolean invalidRenderer(ValueRenderer renderer) {
    try {
      renderer.renderDocument(new Object());
      return false;
    } catch (IllegalArgumentException | RendererException expected) {
      return true;
    }
  }

  private static boolean throwsIllegalArgument(Runnable action) {
    try {
      action.run();
      return false;
    } catch (IllegalArgumentException expected) {
      return true;
    }
  }

  private static final class RecordingVisitor implements ValueVisitor {
    private String observation = "none";

    @Override public void visitNull() { observation = "null"; }
    @Override public void visitString(String value) { observation = "string"; }
    @Override public void visitBoolean(Boolean value) { observation = "boolean"; }
    @Override public void visitInt(Long value) { observation = "int"; }
    @Override public void visitFloat(Double value) { observation = "float"; }
    @Override public void visitDuration(Duration value) { observation = "duration"; }
    @Override public void visitDataSize(DataSize value) { observation = "data-size"; }
    @Override public void visitBytes(byte[] value) { observation = "bytes"; }
    @Override public void visitPair(Pair<?, ?> value) { observation = "pair"; }
    @Override public void visitList(List<?> value) { observation = "list"; }
    @Override public void visitSet(Set<?> value) { observation = "set"; }
    @Override public void visitMap(Map<?, ?> value) { observation = "map"; }
    @Override public void visitObject(PObject value) { observation = "object"; }
    @Override public void visitModule(PModule value) { observation = "module"; }
    @Override public void visitClass(org.pkl.core.PClass value) { observation = "class"; }
    @Override public void visitTypeAlias(org.pkl.core.TypeAlias value) { observation = "alias"; }
    @Override public void visitRegex(Pattern value) { observation = "regex"; }
    @Override public void visitReference(org.pkl.core.Reference value) { observation = "reference"; }
  }

  private static final class RecordingConverter implements ValueConverter<String> {
    @Override public String convertNull() { return "null"; }
    @Override public String convertString(String value) { return "string"; }
    @Override public String convertBoolean(Boolean value) { return "boolean"; }
    @Override public String convertInt(Long value) { return "int"; }
    @Override public String convertFloat(Double value) { return "float"; }
    @Override public String convertDuration(Duration value) { return "duration"; }
    @Override public String convertDataSize(DataSize value) { return "data-size"; }
    @Override public String convertBytes(byte[] value) { return "bytes"; }
    @Override public String convertPair(Pair<?, ?> value) { return "pair"; }
    @Override public String convertList(List<?> value) { return "list"; }
    @Override public String convertSet(Set<?> value) { return "set"; }
    @Override public String convertMap(Map<?, ?> value) { return "map"; }
    @Override public String convertObject(PObject value) { return "object"; }
    @Override public String convertModule(PModule value) { return "module"; }
    @Override public String convertClass(org.pkl.core.PClass value) { return "class"; }
    @Override public String convertTypeAlias(org.pkl.core.TypeAlias value) { return "alias"; }
    @Override public String convertRegex(Pattern value) { return "regex"; }
    @Override public String convertReference(org.pkl.core.Reference value) { return "reference"; }
  }

  private static String observe(Evaluator evaluator, String operation, Path module, String argument) {
    try {
      ModuleSource source = ModuleSource.uri(module.toUri());
      if (operation.equals("OUTPUT_FILES")) {
        return "OK|" + normalizeFileOutputs(evaluator.evaluateOutputFiles(source));
      }
      Object result =
          switch (operation) {
            case "EVALUATE", "LOCAL_IMPORT", "FILE_RESOURCE" -> evaluator.evaluate(source);
            case "EXPRESSION" -> evaluator.evaluateExpression(source, argument);
            case "EXPRESSION_STRING" -> evaluator.evaluateExpressionString(source, argument);
            case "OUTPUT_TEXT" -> evaluator.evaluateOutputText(source);
            case "OUTPUT_BYTES" -> evaluator.evaluateOutputBytes(source);
            case "OUTPUT_VALUE" -> evaluator.evaluateOutputValue(source);
            case "OUTPUT_VALUE_AS_STRING" ->
                evaluator.evaluateOutputValueAs(source, PClassInfo.String);
            case "SECURITY_DENIED" -> evaluateWithDeniedModules(source);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
          };
      return "OK|" + normalize(result);
    } catch (PklException error) {
      if (System.getenv("VIBEFORMER_DIFFERENTIAL_DEBUG") != null) error.printStackTrace(System.err);
      return "ERROR|" + normalizeError(error.getMessage());
    }
  }

  private static Object evaluateWithDeniedModules(ModuleSource source) {
    try (Evaluator evaluator =
        EvaluatorBuilder.preconfigured().setAllowedModules(List.<Pattern>of()).build()) {
      return evaluator.evaluate(source);
    }
  }

  private static void prepareFixtures(Path work, String operation, String argument) throws Exception {
    switch (operation) {
      case "LOCAL_IMPORT" ->
          Files.writeString(work.resolve("dependency.pkl"), argument, StandardCharsets.UTF_8);
      case "FILE_RESOURCE" ->
          Files.writeString(work.resolve("resource.txt"), argument, StandardCharsets.UTF_8);
      default -> {
        // No auxiliary fixture is needed for this operation.
      }
    }
  }

  private static String normalizeFileOutputs(Map<String, FileOutput> outputs) {
    var entries = new ArrayList<String>();
    outputs.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                entries.add(
                    encode(entry.getKey())
                        + "=text:"
                        + encode(entry.getValue().getText())
                        + ","
                        + normalize(entry.getValue().getBytes())));
    return "files{" + String.join(",", entries) + "}";
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
    if (value instanceof byte[] bytes) return "bytes:" + hexBytes(bytes);
    if (value instanceof Pattern pattern) return "regex:" + encode(pattern.pattern());
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
    var missingMember = normalizeMissingMemberError(message);
    if (missingMember != null) return missingMember;
    if (message.contains("output.value") && message.contains("String") && message.contains("Int")) {
      return "output-value-type:expected-string-got-int";
    }
    if (message.contains("does not match any entry in the module allowlist")) {
      return "security:module-not-allowed";
    }
    return "other:" + encode(message.lines().findFirst().orElse(""));
  }

  private static String normalizeMissingMemberError(String message) {
    var lines = message.lines().toList();
    var diagnostic =
        lines.stream()
            .filter(
                line ->
                    line.startsWith("Cannot find property `")
                        || line.startsWith("Cannot find method `"))
            .findFirst()
            .orElse(null);
    if (diagnostic == null) return null;

    int header = lines.indexOf("Did you mean any of the following?");
    if (header < 0) return null;
    var suggestions =
        lines.subList(header + 1, lines.size()).stream().takeWhile(line -> !line.isEmpty()).toList();
    if (suggestions.isEmpty()) return null;
    return "missing-member:"
        + encode(diagnostic)
        + "|suggestions:"
        + encode(String.join("\n", suggestions));
  }

  private static String hexBytes(byte[] bytes) {
    var result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
    return result.toString();
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
