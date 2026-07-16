import java.io.BufferedWriter;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.pkl.config.java.mapper.Conversion;
import org.pkl.config.java.mapper.ConversionException;
import org.pkl.config.java.mapper.Types;
import org.pkl.config.java.mapper.ValueMapper;
import org.pkl.config.java.mapper.ValueMapperBuilder;
import org.pkl.core.DataSize;
import org.pkl.core.DataSizeUnit;
import org.pkl.core.Duration;
import org.pkl.core.DurationUnit;
import org.pkl.core.Evaluator;
import org.pkl.core.Member;
import org.pkl.core.ModuleSchema;
import org.pkl.core.ModuleSource;
import org.pkl.core.PClass;
import org.pkl.core.PClassInfo;
import org.pkl.core.PModule;
import org.pkl.core.PNull;
import org.pkl.core.PObject;
import org.pkl.core.PklException;
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
      for (var file : List.of(
          "ContractBase.pkl", "ContractImported.pkl", "ContractMain.pkl",
          "PolymorphicLib.pkl", "PolymorphicModuleTest.pkl", "OverriddenProperty.pkl",
          "SchemaMethods.pkl")) {
        var schema = evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve(file)));
        schemas.add(schema);
        if (file.equals("ContractMain.pkl")) checkRepresentativeContract(schema);
        if (file.equals("SchemaMethods.pkl")) checkMethodsContract(schema);
        write(writer, "schema/" + file, "SCHEMA", schema(schema));
      }
      var amendBase = evaluator.evaluateSchema(
          ModuleSource.path(fixtures.resolve("SchemaAmendBase.pkl")));
      var amend = evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve("SchemaAmend.pkl")));
      write(writer, "schema/SchemaAmendBase.pkl", "SCHEMA", schema(amendBase));
      write(writer, "schema/SchemaAmend.pkl", "SCHEMA", schema(amend));
      write(writer, "schema/pkl:base/generics", "SCHEMA_GENERICS",
          genericContract(evaluator.evaluateSchema(ModuleSource.uri(URI.create("pkl:base")))));
      write(writer, "schema/relationships", "SCHEMA_RELATIONSHIPS",
          relationshipsContract(schemas.get(2), amendBase, amend));
      writeSchemaFailures(writer, evaluator, fixtures);
      writeCodegenFailures(writer, evaluator, fixtures);
      write(writer, "generated/contract", "GENERATED_CONTRACT", generatedContract(schemas));
      var module = evaluator.evaluate(ModuleSource.path(fixtures.resolve("ContractMain.pkl")));
      write(writer, "values/ContractMain.pkl", "VALUES", values(module));
      var polymorphic = evaluator.evaluate(
          ModuleSource.path(fixtures.resolve("PolymorphicModuleTest.pkl")));
      write(writer, "values/PolymorphicModuleTest.pkl", "VALUES", polymorphicValues(polymorphic));
      var overridden = evaluator.evaluate(ModuleSource.path(fixtures.resolve("OverriddenProperty.pkl")));
      write(writer, "values/OverriddenProperty.pkl", "VALUES", overriddenValues(overridden));
      write(writer, "binding/conversion-matrix", "BINDING", conversionMatrix(evaluator, fixtures));
      write(writer, "binding/collection-matrix", "BINDING", collectionMatrix());
      writeBindingFailures(writer, evaluator, fixtures);
    }
  }

  private static void writeSchemaFailures(
      BufferedWriter writer, Evaluator evaluator, Path fixtures) throws Exception {
    write(writer, "schema/failure/UserDefinedGenericClass.pkl", "SCHEMA_FAILURE",
        stableSchemaFailure(evaluator, fixtures, "UserDefinedGenericClass.pkl",
            "Only standard library members can have type parameters.", List.of()));
    write(writer, "schema/failure/UserDefinedGenericMethod.pkl", "SCHEMA_FAILURE",
        stableSchemaFailure(evaluator, fixtures, "UserDefinedGenericMethod.pkl",
            "Only standard library members can have type parameters.", List.of("DoIt")));
    write(writer, "schema/recursive/CyclicTypeAlias2.pkl", "SCHEMA_RECURSIVE_ALIAS",
        recursiveAliasContract(evaluator, fixtures));
  }

  private static String recursiveAliasContract(Evaluator evaluator, Path fixtures) {
    var first = schemaFailure(evaluator, fixtures, "CyclicTypeAlias2.pkl",
        "Type alias definitions must not be cyclic.", List.of("Baz", "Bar", "Foo"));
    var source = ModuleSource.path(fixtures.resolve("CyclicTypeAlias2.pkl"));
    var second = evaluator.evaluateSchema(source);
    var third = evaluator.evaluateSchema(source);
    check(second == third && second.getModuleClass() == third.getModuleClass(),
        "recursive alias schema cache identity contract");
    check(second.getTypeAliases().keySet().equals(new java.util.LinkedHashSet<>(
        List.of("Foo", "Bar", "Baz"))), "recursive alias declaration order contract");
    for (var name : List.of("Foo", "Bar", "Baz")) {
      check(second.getTypeAliases().get(name) == third.getTypeAliases().get(name),
          "recursive alias identity contract for " + name);
      check(second.getTypeAliases().get(name).getAliasedType() == PType.UNKNOWN,
          "recursive alias fallback type contract for " + name);
    }
    return "recursive(first=" + first +
        ";cachedSchema=true;aliases=[Foo:unknown, Bar:unknown, Baz:unknown])";
  }

  private static String stableSchemaFailure(
      Evaluator evaluator, Path fixtures, String file, String expectedMessage, List<String> chain) {
    var first = schemaFailure(evaluator, fixtures, file, expectedMessage, chain);
    var second = schemaFailure(evaluator, fixtures, file, expectedMessage, chain);
    check(first.equals(second), file + " schema failure changed across repeated evaluation");
    return first;
  }

  private static String schemaFailure(
      Evaluator evaluator, Path fixtures, String file, String expectedMessage, List<String> chain) {
    try {
      evaluator.evaluateSchema(ModuleSource.path(fixtures.resolve(file)));
      throw new IllegalStateException(file + " unexpectedly exported a schema");
    } catch (PklException expected) {
      var message = expected.getMessage();
      check(message.contains(expectedMessage), file + " produced an unexpected schema failure");
      var previous = -1;
      for (var member : chain) {
        var index = message.indexOf("#" + member, previous + 1);
        check(index > previous, file + " omitted or reordered cycle member " + member);
        previous = index;
      }
      return "rejected(message=" + q(expectedMessage) + ";chain=" + chain + ")";
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
          alias.getQualifiedName(), "System.Object", List.of(), List.of(), enumValues,
          false, false, false);
    }
    return generatedType(clrName, alias.getSimpleName(), "alias", true, true, false,
        alias.getQualifiedName(), "System.Object",
        alias.getTypeParameters().stream().map(TypeParameter::getName).toList(),
        List.of("Value:" + clrType(alias.getAliasedType())), List.of(), true, true, false);
  }

  private static String generatedClass(PClass value, String pklName) {
    var properties = value.getProperties().values().stream()
        .sorted(Comparator.comparing(Member::getSimpleName))
        .map(property -> property.getSimpleName() + "=" + symbol(property.getSimpleName()) + ":" +
            clrType(property.getType()) + ":required=true:override=" +
            hasInheritedProperty(value, property.getSimpleName()))
        .toList();
    var supertype = value.getSupertype();
    var base = supertype instanceof PType.Class clazz &&
        !clazz.getPClass().getQualifiedName().startsWith("pkl.base#")
        ? clrClassName(clazz.getPClass()) : "System.Object";
    return generatedType(clrClassName(value), pklName, "class", false,
        !value.isOpen() && !value.isAbstract(), value.isAbstract(), value.getQualifiedName(), base,
        value.getTypeParameters().stream().map(TypeParameter::getName).toList(),
        properties, List.of(), true, true, value.isModuleClass());
  }

  private static String generatedType(
      String clrName, String pklName, String kind, boolean value, boolean sealed,
      boolean abstractType, String qualifiedName, String base, List<String> parameters,
      List<String> properties,
      List<String> enumValues, boolean loader, boolean fromPkl, boolean load) {
    return "type(clr=" + clrName + ";pkl=" + pklName + ";qualified=" + qualifiedName +
        ";kind=" + kind +
        ";value=" + value + ";sealed=" + sealed + ";abstract=" + abstractType +
        ";base=" + base + ";parameters=[" + String.join(",", parameters) +
        "];properties=[" + String.join(",", properties) +
        "];enum=[" + String.join(",", enumValues) +
        "];loader=" + loader + ";fromPkl=" + fromPkl + ";load=" + load + ")";
  }

  private static boolean hasInheritedProperty(PClass value, String pklName) {
    for (var superclass = value.getSuperclass();
        superclass != null && !superclass.getInfo().isStandardLibraryClass();
        superclass = superclass.getSuperclass()) {
      if (superclass.getProperties().values().stream()
          .anyMatch(property -> property.getSimpleName().equals(pklName))) return true;
    }
    return false;
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

  private static String polymorphicValues(PModule module) {
    var desserts = (Collection<?>) module.getProperty("desserts");
    var planes = (Collection<?>) module.getProperty("planes");
    return "polymorphic(desserts=[" + String.join(",", desserts.stream().map(item -> {
      var value = (PObject) item;
      var detail = value.getClassInfo().getQualifiedName().endsWith("#Strudel")
          ? value.getProperty("numberOfRolls").toString()
          : value.getProperty("isOfferedToEdmund").toString();
      return value.getClassInfo().getQualifiedName() + ":" + detail;
    }).toList()) + "];planes=[" + String.join(",", planes.stream().map(item -> {
      var value = (PObject) item;
      var detail = value.getClassInfo().getQualifiedName().endsWith("#Jet")
          ? value.getProperty("isSuperSonic").toString()
          : value.getProperty("isTurboprop").toString();
      return value.getClassInfo().getQualifiedName() + ":" + value.getProperty("name") + ":" +
          value.getProperty("numSeats") + ":" + detail;
    }).toList()) + "])";
  }

  private static String overriddenValues(PModule module) {
    var theClass = (PObject) module.getProperty("theClass");
    var bar = (Collection<?>) theClass.getProperty("bar");
    var derived = String.join(",", bar.stream().map(item -> {
          var value = (PObject) item;
          return value.getClassInfo().getQualifiedName() + ":" + value.getProperty("prop1") + ":" +
              value.getProperty("prop2");
        }).toList());
    var base = String.join(",", bar.stream().map(item -> {
          var value = (PObject) item;
          return value.getClassInfo().getQualifiedName() + ":" + value.getProperty("prop1");
        }).toList());
    return "override(type=" + theClass.getClassInfo().getQualifiedName() + ";bar=[" + derived +
        "];base=[" + base + "])";
  }

  private static String conversionMatrix(Evaluator evaluator, Path fixtures) {
    var mapper = ValueMapper.preconfigured();
    var versions = evaluator.evaluate(ModuleSource.path(fixtures.resolve("BindingConversions.pkl")));
    var semanticVersion = versions.getProperty("semanticVersion");
    var duration = new Duration(100, DurationUnit.MINUTES);
    var regex = Pattern.compile("(?i)\\w*");
    var bytes = new byte[] {0, 1, 127, (byte) 128, (byte) 255};
    var javaDuration = mapper.map(duration, java.time.Duration.class);
    return "identity[pnull=" + (mapper.map(PNull.getInstance(), PNull.class) == PNull.getInstance()) +
        ";bool=" + mapper.map(true, boolean.class) +
        ";string=" + mapper.map("value", String.class) +
        ";int=" + mapper.map(42L, long.class) +
        ";float=" + doubleBits(mapper.map(3.25d, double.class)) +
        ";duration=" + mapper.map(duration, Duration.class).getUnit().getSymbol() +
        ";bytes=" + bytes(mapper.map(bytes, byte[].class)) + "]" +
        ";numeric[int8=" + mapper.map(42L, byte.class) +
        ",int16=" + mapper.map(42L, short.class) +
        ",int32=" + mapper.map(42L, int.class) +
        ",int64=" + mapper.map(42L, long.class) +
        ",float32=" + floatBits(mapper.map(42L, float.class)) +
        ",float64=" + doubleBits(mapper.map(42L, double.class)) +
        ",bigint=" + mapper.map(42L, BigInteger.class) +
        ",decimal=" + mapper.map(42L, BigDecimal.class).toPlainString() +
        ",from-float32=" + floatBits(mapper.map(3.25d, float.class)) +
        ",from-float-decimal=" + mapper.map(3.25d, BigDecimal.class).toPlainString() + "]" +
        ";misc[char=" + mapper.map("x", Character.class) +
        ",uri=" + mapper.map("relative/path", URI.class) +
        ",url=" + mapper.map("https://example.test/path", URL.class) +
        ",file=" + mapper.map("relative/path", File.class) +
        ",path=" + mapper.map("relative/path", Path.class) +
        ",regex=" + mapper.map("(?i)\\w*", Pattern.class).pattern() +
        ",regex-string=" + mapper.map(regex, String.class) +
        ",duration=" + javaDuration.getSeconds() + ":" + javaDuration.getNano() +
        ",version=" + mapper.map(semanticVersion, org.pkl.core.Version.class) +
        ",version-string=" + mapper.map(semanticVersion, String.class) +
        ",parsed-version=" + mapper.map("2.3.4-beta+5", org.pkl.core.Version.class) +
        ",duration-unit=" + mapper.map("min", DurationUnit.class).getSymbol() +
        ",data-size-unit=" + mapper.map("gb", DataSizeUnit.class).getSymbol() + "]";
  }

  private static String collectionMatrix() {
    var mapper = ValueMapper.preconfigured();
    int[] array = mapper.map(List.of(1L, 2L, 3L), int[].class);
    List<Float> list = mapper.map(List.of(1.0d, 2.0d, 3.25d), Types.listOf(Float.class));
    java.util.Set<String> set = mapper.map(List.of("beta", "alpha", "beta"), Types.setOf(String.class));
    var sourceMap = new java.util.LinkedHashMap<Long, Object>();
    sourceMap.put(1L, 2L);
    sourceMap.put(2L, 4.5d);
    Map<Integer, Double> map = mapper.map(sourceMap, Types.mapOf(Integer.class, Double.class));
    Pair<Integer, Duration> pair = mapper.map(
        new Pair<>(1L, new Duration(3, DurationUnit.SECONDS)),
        Types.pairOf(Integer.class, Duration.class));
    Map<String, List<Integer>> nested = mapper.map(
        Map.of("items", List.of(4L, 5L)),
        Types.mapOf(String.class, Types.listOf(Integer.class)));
    List<String> nullable = mapper.map(
        List.of("value", PNull.getInstance()), Types.listOf(String.class));
    var customMapper = ValueMapperBuilder.preconfigured()
        .addConversion(Conversion.of(PClassInfo.Int, String.class,
            (value, ignored) -> value.toString()))
        .build();
    Map<String, String> custom = customMapper.map(
        Map.of("answer", 42L), Types.mapOf(String.class, String.class));
    return "collections[array=" + java.util.Arrays.stream(array).mapToObj(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",")) +
        ";list=" + list.stream().map(SchemaUpstreamOracle::floatBits)
            .collect(java.util.stream.Collectors.joining(",")) +
        ";set=" + set.stream().sorted().collect(java.util.stream.Collectors.joining(",")) +
        ";map=" + map.entrySet().stream().map(entry -> entry.getKey() + "=" + doubleBits(entry.getValue()))
            .sorted().collect(java.util.stream.Collectors.joining(",")) +
        ";pair=" + pair.getFirst() + ":" + pair.getSecond().getValue() + "@" +
            pair.getSecond().getUnit().getSymbol() +
        ";nested=" + nested.get("items").stream().map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(",")) +
        ";nullable=" + nullable.get(0) + ":" + (nullable.get(1) == null ? "null" : "unexpected") +
        ";custom=" + custom.get("answer") + "]";
  }

  private static String floatBits(float value) {
    return String.format("%08x", Float.floatToIntBits(value));
  }

  private static String doubleBits(double value) {
    return String.format("%016x", Double.doubleToLongBits(value));
  }

  private static void writeBindingFailures(
      BufferedWriter writer, Evaluator evaluator, Path fixtures) throws Exception {
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
    observeBindingFailure(writer, "binding/invalid-character", "String->Char",
        () -> mapper.map("too long", Character.class));
    observeBindingFailure(writer, "binding/invalid-uri", "String->Uri",
        () -> mapper.map("http://[invalid", URI.class));
    observeBindingFailure(writer, "binding/invalid-regex", "String->Regex",
        () -> mapper.map("[", Pattern.class));
    observeBindingFailure(writer, "binding/duration-overflow", "Duration->TimeSpan",
        () -> mapper.map(new Duration(Double.POSITIVE_INFINITY, DurationUnit.SECONDS),
            java.time.Duration.class));
    var versions = evaluator.evaluate(ModuleSource.path(fixtures.resolve("BindingConversions.pkl")));
    observeBindingFailure(writer, "binding/version-overflow", "VersionObject->Version",
        () -> mapper.map(versions.getProperty("oversizedSemanticVersion"), org.pkl.core.Version.class));
    observeBindingFailure(writer, "binding/invalid-version", "String->Version",
        () -> mapper.map("not-a-version", org.pkl.core.Version.class));
    observeBindingFailure(writer, "binding/invalid-unit", "String->DurationUnit",
        () -> mapper.map("fortnight", DurationUnit.class));
    var custom = ValueMapperBuilder.preconfigured()
        .addConversion(Conversion.of(PClassInfo.Int, String.class, (value, ignored) -> {
          throw new ConversionException("deliberate custom conversion failure");
        })).build();
    observeBindingFailure(writer, "binding/custom-conversion", "Int->CustomString",
        () -> custom.map(42L, String.class));
  }

  private static void observeBindingFailure(
      BufferedWriter writer, String id, String contract, Runnable action) throws Exception {
    var failed = false;
    try {
      action.run();
    } catch (RuntimeException expected) {
      failed = true;
    }
    if (!failed) throw new IllegalStateException(id + " unexpectedly mapped successfully");
    write(writer, id, "BINDING_FAILURE", "conversion-failed(" + contract + ")");
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
    result.append("];methods=[");
    for (var entry : value.getMethods().entrySet()) {
      result.append(method(entry.getValue())).append(";");
    }
    return result.append("])").toString();
  }

  private static String method(PClass.Method value) {
    var result = new StringBuilder("method(")
        .append(q(value.getSimpleName()))
        .append(";params=").append(typeParameters(value.getTypeParameters()))
        .append(";arguments=[");
    for (var entry : value.getParameters().entrySet()) {
      result.append(q(entry.getKey())).append(":").append(type(entry.getValue())).append(";");
    }
    return result.append("];return=").append(type(value.getReturnType()))
        .append(";doc=").append(q(value.getInheritedDocComment()))
        .append(";line=").append(value.getSourceLocation().startLine()).append(":")
        .append(value.getSourceLocation().endLine())
        .append(";mods=").append(modifiers(value))
        .append(";annotations=").append(annotations(value.getAnnotations()))
        .append(")").toString();
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

  private static void checkMethodsContract(ModuleSchema schema) {
    var methods = schema.getModuleClass().getMethods();
    check(methods.keySet().equals(new java.util.LinkedHashSet<>(
        List.of("methodb1", "methodb2", "methodb3"))), "module method declaration order");
    check(methods.get("methodb1").getReturnType() == PType.UNKNOWN,
        "untyped method return contract");
    var methodb2 = methods.get("methodb2");
    check(methodb2.getParameters().get("str") instanceof PType.Constrained,
        "constrained method parameter contract");
    check(methodb2.getReturnType() instanceof PType.Constrained,
        "constrained method return contract");
    check(methods.get("methodb3").getParameters().keySet().equals(new java.util.LinkedHashSet<>(
        List.of("x", "_#1", "i", "_#3"))), "unnamed method parameter contract");
    var record = schema.getClasses().get("Record");
    var display = record.getMethods().get("display");
    check(display != null && display.getOwner() == record, "class method owner identity contract");
  }

  private static String genericContract(ModuleSchema schema) {
    var list = schema.getClasses().get("List");
    var map = schema.getClasses().get("Map");
    check(list != null && map != null, "pkl:base generic class contract");
    check(list.getTypeParameters().stream().allMatch(parameter -> parameter.getOwner() == list),
        "List type parameter owner identity contract");
    check(map.getTypeParameters().stream().allMatch(parameter -> parameter.getOwner() == map),
        "Map type parameter owner identity contract");
    var method = list.getMethods().get("map");
    check(method != null && method.getTypeParameters().size() == 1,
        "List.map generic method contract");
    var resultParameter = method.getTypeParameters().get(0);
    check(resultParameter.getOwner() == method, "List.map type parameter owner identity contract");
    var transform = (PType.Function) method.getParameters().get("transform");
    var element = (PType.TypeVariable) transform.getParameterTypes().get(0);
    var result = (PType.TypeVariable) transform.getReturnType();
    var returnType = (PType.Class) method.getReturnType();
    var returnElement = (PType.TypeVariable) returnType.getTypeArguments().get(0);
    check(element.getTypeParameter() == list.getTypeParameters().get(0),
        "List.map parameter retains class type-parameter identity");
    check(result.getTypeParameter() == resultParameter &&
        returnElement.getTypeParameter() == resultParameter,
        "List.map result retains method type-parameter identity");
    return "generics(list=" + typeParameters(list.getTypeParameters()) +
        ";map=" + typeParameters(map.getTypeParameters()) +
        ";method=" + method(method) + ")";
  }

  private static String relationshipsContract(
      ModuleSchema extension, ModuleSchema amendBase, ModuleSchema amend) {
    check(extension.isExtend() && !extension.isAmend(), "extended module relation contract");
    check(extension.getSupermodule() != null &&
        extension.getModuleClass().getSuperclass() == extension.getSupermodule().getModuleClass(),
        "extended module class identity contract");
    check(amend.isAmend() && !amend.isExtend() && amend.getSupermodule() != null,
        "amended module relation contract");
    check(amend.getModuleClass() == amend.getSupermodule().getModuleClass(),
        "amended module class identity contract");
    check(amend.getSupermodule().getModuleClass() == amendBase.getModuleClass(),
        "amended module base identity contract");
    check(amend.getAllClasses() == amend.getAllClasses() &&
        amend.getAllTypeAliases() == amend.getAllTypeAliases(),
        "amended aggregate maps are stable");
    check(amend.getAllClasses().get("Item") == amendBase.getClasses().get("Item") &&
        amend.getAllTypeAliases().get("Name") == amendBase.getTypeAliases().get("Name"),
        "amended inherited declaration identity contract");
    return "relations(extend=" + q(extension.getModuleName()) + "->" +
        q(extension.getSupermodule().getModuleName()) +
        ";extendClassSuper=true;extendClasses=" + extension.getAllClasses().keySet() +
        ";extendAliases=" + extension.getAllTypeAliases().keySet() +
        ";amend=" + q(amend.getModuleName()) + "->" + q(amend.getSupermodule().getModuleName()) +
        ";sameModuleClass=true;amendClasses=" + amend.getAllClasses().keySet() +
        ";amendAliases=" + amend.getAllTypeAliases().keySet() + ")";
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
    if (value instanceof PType.TypeVariable variable) {
      var parameter = variable.getTypeParameter();
      return "variable(" + q(variable.getName()) + ";owner=" +
          q(parameter.getOwner().getModuleName() + "#" + parameter.getOwner().getSimpleName()) +
          ";index=" + parameter.getIndex() + ")";
    }
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
