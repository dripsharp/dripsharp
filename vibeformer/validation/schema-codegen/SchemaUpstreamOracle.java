import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.pkl.core.DataSize;
import org.pkl.core.Duration;
import org.pkl.core.Evaluator;
import org.pkl.core.Member;
import org.pkl.core.ModuleSchema;
import org.pkl.core.ModuleSource;
import org.pkl.core.PClass;
import org.pkl.core.PModule;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.PType;
import org.pkl.core.Pair;
import org.pkl.core.TypeAlias;

/** Separately executed JVM oracle for the selected schema and binding contract. */
public final class SchemaUpstreamOracle {
  private static final Base64.Encoder BASE64 = Base64.getEncoder();

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("fixture directory and output are required");
    var fixtures = Path.of(args[0]).toAbsolutePath();
    try (var evaluator = Evaluator.preconfigured();
        var writer = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
      for (var file : List.of("ContractBase.pkl", "ContractImported.pkl", "ContractMain.pkl")) {
        var schema = evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve(file)));
        write(writer, "schema/" + file, "SCHEMA", schema(schema));
      }
      var module = evaluator.evaluate(ModuleSource.path(fixtures.resolve("ContractMain.pkl")));
      write(writer, "values/ContractMain.pkl", "VALUES", values(module));
    }
  }

  private static String schema(ModuleSchema schema) {
    var result = new StringBuilder();
    result.append("module(").append(q(schema.getModuleName()))
        .append(";file=").append(q(Path.of(schema.getModuleUri()).getFileName().toString()))
        .append(";amend=").append(schema.isAmend())
        .append(";extend=").append(schema.isExtend())
        .append(";super=").append(q(schema.getSupermodule() == null ? "" : schema.getSupermodule().getModuleName()))
        .append(";doc=").append(q(schema.getDocComment()))
        .append(";imports=[");
    for (var entry : schema.getImports().entrySet()) {
      result.append(q(entry.getKey())).append("=")
          .append(q(Path.of(entry.getValue()).getFileName().toString())).append(";");
    }
    result.append("];moduleClass=").append(pClass(schema.getModuleClass())).append(";classes=[");
    for (var entry : schema.getClasses().entrySet()) result.append(pClass(entry.getValue())).append(";");
    result.append("];aliases=[");
    for (var entry : schema.getTypeAliases().entrySet()) result.append(alias(entry.getValue())).append(";");
    return result.append("])").toString();
  }

  private static String pClass(PClass value) {
    var result = new StringBuilder("class(")
        .append(q(value.getQualifiedName()))
        .append(";moduleClass=").append(value.isModuleClass())
        .append(";abstract=").append(value.isAbstract())
        .append(";open=").append(value.isOpen())
        .append(";super=").append(type(value.getSupertype()))
        .append(";doc=").append(q(value.getDocComment()))
        .append(";line=").append(value.getSourceLocation().startLine()).append(":")
        .append(value.getSourceLocation().endLine()).append(";properties=[");
    for (var entry : value.getProperties().entrySet()) {
      var property = entry.getValue();
      result.append(q(property.getSimpleName())).append(":")
          .append(type(property.getType())).append(":doc=")
          .append(q(property.getInheritedDocComment())).append(":line=")
          .append(property.getSourceLocation().startLine()).append(":")
          .append(property.getSourceLocation().endLine()).append(":mods=")
          .append(modifiers(property)).append(";");
    }
    return result.append("])").toString();
  }

  private static String alias(TypeAlias value) {
    return "alias(" + q(value.getQualifiedName()) + ";doc=" + q(value.getDocComment()) +
        ";line=" + value.getSourceLocation().startLine() + ":" + value.getSourceLocation().endLine() +
        ";params=" + value.getTypeParameters().stream().map(item -> q(item.getName())).toList() +
        ";type=" + type(value.getAliasedType()) + ")";
  }

  private static String modifiers(Member value) {
    return value.getModifiers().stream().map(Object::toString).sorted().toList().toString();
  }

  private static String type(PType value) {
    if (value == null) return "none";
    if (value == PType.UNKNOWN) return "unknown";
    if (value == PType.NOTHING) return "nothing";
    if (value == PType.MODULE) return "module";
    if (value instanceof PType.StringLiteral literal) return "literal(" + q(literal.getLiteral()) + ")";
    if (value instanceof PType.Class clazz) {
      return "class(" + q(clazz.getPClass().getQualifiedName()) + typeArguments(clazz.getTypeArguments()) + ")";
    }
    if (value instanceof PType.Nullable nullable) return "nullable(" + type(nullable.getBaseType()) + ")";
    if (value instanceof PType.Constrained constrained) {
      return "constrained(" + type(constrained.getBaseType()) + ";" +
          constrained.getConstraints().stream().map(SchemaUpstreamOracle::q).toList() + ")";
    }
    if (value instanceof PType.Alias alias) {
      return "alias(" + q(alias.getTypeAlias().getQualifiedName()) + typeArguments(alias.getTypeArguments()) +
          "=" + type(alias.getAliasedType()) + ")";
    }
    if (value instanceof PType.Function function) {
      return "function(" + function.getParameterTypes().stream().map(SchemaUpstreamOracle::type).toList() +
          "->" + type(function.getReturnType()) + ")";
    }
    if (value instanceof PType.Union union) {
      return "union(" + union.getElementTypes().stream().map(SchemaUpstreamOracle::type).toList() + ")";
    }
    if (value instanceof PType.TypeVariable variable) return "variable(" + q(variable.getName()) + ")";
    throw new IllegalArgumentException("Unknown PType: " + value.getClass());
  }

  private static String typeArguments(List<PType> values) {
    return values.isEmpty() ? "" : "<" + values.stream().map(SchemaUpstreamOracle::type).toList() + ">";
  }

  private static String values(PModule module) {
    var service = (PObject) module.getProperty("service");
    var endpoint = (PObject) service.getProperty("endpoint");
    var pair = (Pair<?, ?>) service.getProperty("pair");
    var special = (PObject) module.getProperty("class");
    var duration = (Duration) service.getProperty("duration");
    var size = (DataSize) service.getProperty("size");
    return "values(base=" + q((String) module.getProperty("baseName")) +
        ";id=" + q((String) service.getProperty("id")) +
        ";name=" + q((String) service.getProperty("name")) +
        ";endpoint=" + q((String) endpoint.getProperty("host")) + ":" + endpoint.getProperty("port") +
        ";direction=" + q((String) service.getProperty("direction")) +
        ";email=" + q((String) service.getProperty("email")) +
        ";maybe=" + (service.getProperty("maybe") == PNull.getInstance() ? "null" : "unexpected") +
        ";constrained=" + service.getProperty("constrained") +
        ";choice=" + q((String) service.getProperty("choice")) +
        ";tags=" + sequence((Collection<?>) service.getProperty("tags"), false) +
        ";listing=" + sequence((Collection<?>) service.getProperty("listing"), false) +
        ";names=" + sequence((Collection<?>) service.getProperty("names"), true) +
        ";weights=" + mapping((Map<?, ?>) service.getProperty("weights")) +
        ";mapping=" + mapping((Map<?, ?>) service.getProperty("mapping")) +
        ";pair=" + q((String) pair.getFirst()) + ":" + pair.getSecond() +
        ";bytes=" + bytes((byte[]) service.getProperty("bytes")) +
        ";pattern=" + q(((Pattern) service.getProperty("pattern")).pattern()) +
        ";duration=" + bits(duration.getValue()) + "@" + duration.getUnit().getSymbol() +
        ";size=" + bits(size.getValue()) + "@" + size.getUnit().getSymbol() +
        ";bag=" + sequence((Collection<?>) module.getProperty("bag"), false) +
        ";quoted=" + q((String) special.getProperty("event")) + ":" +
        q((String) special.getProperty("first-name")) + ")";
  }

  private static String sequence(Collection<?> values, boolean sorted) {
    var items = values.stream().map(SchemaUpstreamOracle::scalar).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    if (sorted) items.sort(Comparator.naturalOrder());
    return items.toString();
  }

  private static String mapping(Map<?, ?> values) {
    return values.entrySet().stream().map(entry -> scalar(entry.getKey()) + "=" + scalar(entry.getValue()))
        .sorted().toList().toString();
  }

  private static String scalar(Object value) {
    return value instanceof String text ? q(text) : String.valueOf(value);
  }

  private static String bytes(byte[] values) {
    var result = new StringBuilder();
    for (var value : values) result.append(String.format("%02x", value & 0xff));
    return result.toString();
  }

  private static String bits(double value) {
    return String.format("%016x", Double.doubleToLongBits(value));
  }

  private static String q(String value) {
    return value == null ? "-" : BASE64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void write(BufferedWriter writer, String id, String kind, String value) throws Exception {
    writer.write(id + "\t" + kind + "\t" + q(value));
    writer.newLine();
  }
}
