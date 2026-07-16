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
import java.util.regex.Pattern;
import org.pkl.config.java.mapper.ConversionException;
import org.pkl.config.java.mapper.Types;
import org.pkl.config.java.mapper.ValueMapper;
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
import org.pkl.core.TypeParameter;

/** Separately executed JVM oracle for the selected schema and binding contract. */
public final class SchemaUpstreamOracle {
  private static final Base64.Encoder BASE64 = Base64.getEncoder();

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("fixture directory and output are required");
    var fixtures = Path.of(args[0]).toAbsolutePath();
    try (var evaluator = Evaluator.preconfigured();
        var writer = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
      var schemas = new ArrayList<ModuleSchema>();
      for (var file : List.of("ContractBase.pkl", "ContractImported.pkl", "ContractMain.pkl")) {
        var schema = evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve(file)));
        schemas.add(schema);
        if (file.equals("ContractMain.pkl")) checkRepresentativeContract(schema);
        write(writer, "schema/" + file, "SCHEMA", schema(schema));
      }
      writeCodegenFailures(writer, evaluator, fixtures);
      write(writer, "generated/contract", "GENERATED_CONTRACT", generatedContract(schemas));
      var module = evaluator.evaluate(ModuleSource.path(fixtures.resolve("ContractMain.pkl")));
      write(writer, "values/ContractMain.pkl", "VALUES", values(module));
      writeBindingFailures(writer);
    }
  }

  private static void writeCodegenFailures(
      BufferedWriter writer, Evaluator evaluator, Path fixtures) throws Exception {
    var collision = evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve("Collision.pkl")));
    var collisionGroups = new java.util.TreeMap<String, List<String>>();
    for (var value : collision.getClasses().values()) {
      collisionGroups.computeIfAbsent(symbol(value.getSimpleName()), ignored -> new ArrayList<>())
          .add("class:" + value.getQualifiedName());
    }
    var collisionDiagnostics = new ArrayList<String>();
    for (var entry : collisionGroups.entrySet()) {
      if (entry.getValue().size() < 2) continue;
      entry.getValue().sort(String::compareTo);
      collisionDiagnostics.add("symbol collision `" + entry.getKey() + "`: " +
          String.join(", ", entry.getValue()));
    }
    writeDiagnostics(writer, "Collision.pkl", collisionDiagnostics);

    var generated = evaluator.evaluateSchema(
        ModuleSource.path(fixtures.resolve("GeneratedMemberCollision.pkl")));
    var generatedMembers = Map.of(
        "FromPkl", "generated method `FromPkl`",
        "GeneratedLoader", "generated nested type `GeneratedLoader`",
        "Load", "generated method `Load`",
        "PklLoader", "generated property `PklLoader`");
    var generatedDiagnostics = new ArrayList<String>();
    generated.getModuleClass().getProperties().values().stream()
        .sorted(Comparator.comparing(property -> symbol(property.getSimpleName())))
        .forEach(property -> {
          var mapped = symbol(property.getSimpleName());
          var generatedMember = generatedMembers.get(mapped);
          if (generatedMember != null) {
            generatedDiagnostics.add("member collision in `" +
                generated.getModuleClass().getQualifiedName() + "` for `" + mapped + "`: " +
                generatedMember + ", property `" + property.getSimpleName() + "`");
          }
        });
    writeDiagnostics(writer, "GeneratedMemberCollision.pkl", generatedDiagnostics);

    var inherited = evaluator.evaluateSchema(
        ModuleSource.path(fixtures.resolve("InheritedMemberCollision.pkl")));
    var inheritedDiagnostics = new ArrayList<String>();
    for (var value : inherited.getClasses().values()) {
      if (!(value.getSupertype() instanceof PType.Class supertype)) continue;
      for (var property : value.getProperties().values()) {
        for (var inheritedProperty : supertype.getPClass().getProperties().values()) {
          var mapped = symbol(property.getSimpleName());
          if (mapped.equals(symbol(inheritedProperty.getSimpleName()))) {
            inheritedDiagnostics.add("member collision in `" + value.getQualifiedName() +
                "` for `" + mapped + "`: inherited property `" +
                inheritedProperty.getSimpleName() + "` from `" +
                supertype.getPClass().getQualifiedName() + "`, property `" +
                property.getSimpleName() + "`");
          }
        }
      }
    }
    inheritedDiagnostics.sort(String::compareTo);
    writeDiagnostics(writer, "InheritedMemberCollision.pkl", inheritedDiagnostics);
  }

  private static void writeDiagnostics(
      BufferedWriter writer, String file, List<String> diagnostics) throws Exception {
    check(!diagnostics.isEmpty(), file + " produced no independent codegen failure contract");
    for (var index = 0; index < diagnostics.size(); index++) {
      write(writer, "codegen/" + file + "/" + index, "CODEGEN_FAILURE", diagnostics.get(index));
    }
  }

  private static String generatedContract(List<ModuleSchema> schemas) {
    var types = new ArrayList<String>();
    for (var schema : schemas) {
      for (var alias : schema.getTypeAliases().values()) types.add(generatedAlias(alias));
      types.add(generatedClass(schema.getModuleClass(), schema.getModuleName()));
      for (var value : schema.getClasses().values()) {
        types.add(generatedClass(value, value.getSimpleName()));
      }
    }
    types.sort(Comparator.comparing(SchemaUpstreamOracle::contractClrName));
    return "types=[" + String.join(";", types) + "]";
  }

  private static String contractClrName(String contract) {
    var start = "type(clr=".length();
    var end = contract.indexOf(';', start);
    return contract.substring(start, end);
  }

  private static String generatedAlias(TypeAlias alias) {
    var clrName = clrAliasName(alias);
    var literals = stringLiterals(alias.getAliasedType());
    if (literals != null) {
      var enumValues = literals.stream()
          .map(value -> symbol(value) + "=" + value)
          .toList();
      return generatedType(clrName, alias.getSimpleName(), "enum", false, true, false,
          "System.Object", List.of(), List.of(), enumValues, false, false, false);
    }
    return generatedType(clrName, alias.getSimpleName(), "alias", true, true, false,
        "System.Object", alias.getTypeParameters().stream().map(TypeParameter::getName).toList(),
        List.of("Value:" + clrType(alias.getAliasedType())), List.of(), true, true, false);
  }

  private static String generatedClass(PClass value, String pklName) {
    var properties = value.getProperties().values().stream()
        .sorted(Comparator.comparing(Member::getSimpleName))
        .map(property -> property.getSimpleName() + "=" + symbol(property.getSimpleName()) + ":" +
            clrType(property.getType()) + ":required=true")
        .toList();
    var supertype = value.getSupertype();
    var base = supertype instanceof PType.Class clazz &&
        !clazz.getPClass().getQualifiedName().startsWith("pkl.base#")
        ? clrClassName(clazz.getPClass()) : "System.Object";
    return generatedType(clrClassName(value), pklName, "class", false,
        !value.isOpen() && !value.isAbstract(), value.isAbstract(), base,
        value.getTypeParameters().stream().map(TypeParameter::getName).toList(),
        properties, List.of(), true, true, value.isModuleClass());
  }

  private static String generatedType(
      String clrName, String pklName, String kind, boolean value, boolean sealed,
      boolean abstractType, String base, List<String> parameters, List<String> properties,
      List<String> enumValues, boolean loader, boolean fromPkl, boolean load) {
    return "type(clr=" + clrName + ";pkl=" + pklName + ";kind=" + kind +
        ";value=" + value + ";sealed=" + sealed + ";abstract=" + abstractType +
        ";base=" + base + ";parameters=[" + String.join(",", parameters) +
        "];properties=[" + String.join(",", properties) +
        "];enum=[" + String.join(",", enumValues) +
        "];loader=" + loader + ";fromPkl=" + fromPkl + ";load=" + load + ")";
  }

  private static List<String> stringLiterals(PType value) {
    if (!(value instanceof PType.Union union)) return null;
    var result = new ArrayList<String>();
    for (var element : union.getElementTypes()) {
      if (!(element instanceof PType.StringLiteral literal)) return null;
      result.add(literal.getLiteral());
    }
    return result;
  }

  private static String clrType(PType value) {
    if (value == null || value == PType.UNKNOWN || value == PType.NOTHING || value == PType.MODULE) {
      return "System.Object";
    }
    if (value instanceof PType.StringLiteral) return "System.String";
    if (value instanceof PType.Nullable nullable) return clrType(nullable.getBaseType()) + "?";
    if (value instanceof PType.Constrained constrained) return clrType(constrained.getBaseType());
    if (value instanceof PType.Alias alias) return clrAliasBaseName(alias.getTypeAlias()) +
        clrTypeArguments(alias.getTypeArguments());
    if (value instanceof PType.Function) return "System.Delegate";
    if (value instanceof PType.Union) return "System.Object";
    if (value instanceof PType.TypeVariable variable) return variable.getName();
    if (!(value instanceof PType.Class clazz)) {
      throw new IllegalArgumentException("Unsupported generated type contract: " + value.getClass());
    }
    var qualifiedName = clazz.getPClass().getQualifiedName();
    var arguments = clazz.getTypeArguments();
    return switch (qualifiedName) {
      case "pkl.base#String" -> "System.String";
      case "pkl.base#Int" -> "System.Int64";
      case "pkl.base#Float" -> "System.Double";
      case "pkl.base#Boolean" -> "System.Boolean";
      case "pkl.base#List", "pkl.base#Listing" ->
          "System.Collections.Generic.IReadOnlyList" + clrTypeArguments(arguments);
      case "pkl.base#Set" ->
          "System.Collections.Generic.IReadOnlySet" + clrTypeArguments(arguments);
      case "pkl.base#Map", "pkl.base#Mapping" ->
          "System.Collections.Generic.IReadOnlyDictionary" + clrTypeArguments(arguments);
      case "pkl.base#Pair" -> "Pkl.Core.Pair" + clrTypeArguments(arguments);
      case "pkl.base#Bytes" -> "System.Byte[]";
      case "pkl.base#Regex" -> "System.Text.RegularExpressions.Regex";
      case "pkl.base#Duration" -> "Pkl.Core.Duration";
      case "pkl.base#DataSize" -> "Pkl.Core.DataSize";
      default -> qualifiedName.startsWith("pkl.base#")
          ? "System.Object" : clrClassName(clazz.getPClass()) + clrTypeArguments(arguments);
    };
  }

  private static String clrTypeArguments(List<PType> arguments) {
    return arguments.isEmpty() ? "" : "<" +
        String.join(",", arguments.stream().map(SchemaUpstreamOracle::clrType).toList()) + ">";
  }

  private static String clrAliasName(TypeAlias alias) {
    return clrAliasBaseName(alias) +
        (alias.getTypeParameters().isEmpty() ? "" : "<" +
            String.join(",", alias.getTypeParameters().stream().map(TypeParameter::getName).toList()) + ">");
  }

  private static String clrAliasBaseName(TypeAlias alias) {
    return clrQualifiedName(alias.getModuleName(), alias.getSimpleName());
  }

  private static String clrClassName(PClass value) {
    var simpleName = value.isModuleClass()
        ? value.getModuleName().substring(value.getModuleName().lastIndexOf('.') + 1)
        : value.getSimpleName();
    return clrQualifiedName(value.getModuleName(), simpleName) +
        (value.getTypeParameters().isEmpty() ? "" : "<" +
            String.join(",", value.getTypeParameters().stream().map(TypeParameter::getName).toList()) + ">");
  }

  private static String clrQualifiedName(String moduleName, String simpleName) {
    return java.util.Arrays.stream(moduleName.split("\\."))
        .map(SchemaUpstreamOracle::symbol)
        .collect(java.util.stream.Collectors.joining(".")) + "." + symbol(simpleName);
  }

  private static String symbol(String value) {
    var result = new StringBuilder();
    var capitalize = true;
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (!Character.isLetterOrDigit(character)) {
        capitalize = true;
      } else if (capitalize) {
        result.append(Character.toUpperCase(character));
        capitalize = false;
      } else {
        result.append(character);
      }
    }
    return result.toString();
  }

  private static void writeBindingFailures(BufferedWriter writer) throws Exception {
    var mapper = ValueMapper.preconfigured();
    observeBindingFailure(writer, "binding/incompatible-scalar", "String->Int32",
        () -> mapper.map("bad", Integer.class));
    observeBindingFailure(writer, "binding/integer-overflow", "Int->Int32",
        () -> mapper.map(Long.MAX_VALUE, Integer.class));
    observeBindingFailure(writer, "binding/non-nullable", "Null->Int32",
        () -> mapper.map(PNull.getInstance(), int.class));
    observeBindingFailure(writer, "binding/nested-list", "List<Int>->List<String>",
        () -> mapper.map(List.of(1L), Types.listOf(String.class)));
    observeBindingFailure(writer, "binding/nested-map", "Map<String,Int>->Map<String,String>",
        () -> mapper.map(Map.of("bad", 1L), Types.mapOf(String.class, String.class)));
    observeBindingFailure(writer, "binding/nested-pair", "Pair<String,Int>->Pair<String,String>",
        () -> mapper.map(new Pair<>("left", 1L), Types.pairOf(String.class, String.class)));
  }

  private static void observeBindingFailure(
      BufferedWriter writer, String id, String contract, Runnable action) throws Exception {
    try {
      action.run();
      throw new IllegalStateException(id + " unexpectedly mapped successfully");
    } catch (ConversionException expected) {
      write(writer, id, "BINDING_FAILURE", "conversion-failed(" + contract + ")");
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
        .append(";annotations=").append(annotations(schema.getAnnotations()))
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
        .append(";params=").append(typeParameters(value.getTypeParameters()))
        .append(";super=").append(type(value.getSupertype()))
        .append(";doc=").append(q(value.getDocComment()))
        .append(";annotations=").append(annotations(value.getAnnotations()))
        .append(";line=").append(value.getSourceLocation().startLine()).append(":")
        .append(value.getSourceLocation().endLine()).append(";properties=[");
    for (var entry : value.getProperties().entrySet()) {
      var property = entry.getValue();
      result.append(q(property.getSimpleName())).append(":")
          .append(type(property.getType())).append(":doc=")
          .append(q(property.getInheritedDocComment())).append(":line=")
          .append(property.getSourceLocation().startLine()).append(":")
          .append(property.getSourceLocation().endLine()).append(":mods=")
          .append(modifiers(property)).append(":annotations=")
          .append(annotations(property.getAnnotations())).append(";");
    }
    return result.append("])").toString();
  }

  private static String alias(TypeAlias value) {
    return "alias(" + q(value.getQualifiedName()) + ";doc=" + q(value.getDocComment()) +
        ";annotations=" + annotations(value.getAnnotations()) +
        ";line=" + value.getSourceLocation().startLine() + ":" + value.getSourceLocation().endLine() +
        ";params=" + typeParameters(value.getTypeParameters()) +
        ";type=" + type(value.getAliasedType()) + ")";
  }

  private static String typeParameters(List<TypeParameter> values) {
    return values.stream().map(value ->
        "parameter(" + q(value.getName()) + ";variance=" + value.getVariance().name() +
            ";index=" + value.getIndex() + ";owner=" +
            q(value.getOwner().getModuleName() + "#" + value.getOwner().getSimpleName()) + ")")
        .toList().toString();
  }

  private static String annotations(List<PObject> values) {
    return values.stream().map(SchemaUpstreamOracle::annotation).toList().toString();
  }

  private static String annotation(PObject value) {
    var properties = value.getProperties().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> q(entry.getKey()) + "=" + annotationValue(entry.getValue()))
        .toList();
    return "annotation(" + q(value.getClassInfo().getQualifiedName()) + ";" + properties + ")";
  }

  private static String annotationValue(Object value) {
    if (value == PNull.getInstance()) return "null";
    if (value instanceof String text) return "string(" + q(text) + ")";
    if (value instanceof Boolean bool) return "boolean(" + bool + ")";
    if (value instanceof Long integer) return "int(" + integer + ")";
    if (value instanceof Double floating) return "float(" + bits(floating) + ")";
    if (value instanceof PObject object) return annotation(object);
    if (value instanceof Collection<?> collection) {
      return "collection(" + collection.stream().map(SchemaUpstreamOracle::annotationValue).toList() + ")";
    }
    if (value instanceof Map<?, ?> map) {
      return "mapping(" + map.entrySet().stream()
          .map(entry -> annotationValue(entry.getKey()) + "=" + annotationValue(entry.getValue()))
          .sorted().toList() + ")";
    }
    throw new IllegalArgumentException("Unsupported annotation value: " + value.getClass());
  }

  private static void checkRepresentativeContract(ModuleSchema schema) {
    check(schema.getAnnotations().size() == 1, "module annotation contract");
    var direction = schema.getTypeAliases().get("Direction");
    check(direction != null && direction.getAnnotations().size() == 1, "type-alias annotation contract");
    var transform = schema.getTypeAliases().get("Transform");
    check(transform != null && transform.getTypeParameters().size() == 2, "generic alias contract");
    check(transform.getTypeParameters().get(0).getVariance() == TypeParameter.Variance.CONTRAVARIANT,
        "contravariant type parameter contract");
    check(transform.getTypeParameters().get(1).getVariance() == TypeParameter.Variance.COVARIANT,
        "covariant type parameter contract");
    check(transform.getTypeParameters().stream().allMatch(item -> item.getOwner() == transform),
        "type parameter owner contract");
    check(transform.getAliasedType() instanceof PType.Function, "function type contract");
    var service = schema.getClasses().get("Service");
    check(service != null && service.getAnnotations().size() == 1, "class annotation contract");
    check(service.getProperties().get("name").getAnnotations().size() == 1,
        "property annotation contract");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
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
