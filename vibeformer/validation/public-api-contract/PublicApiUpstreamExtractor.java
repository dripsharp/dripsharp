/*
 * Standalone upstream public-declaration extractor used by the executable
 * Vibeformer public API contract. It deliberately uses the JDK compiler tree
 * API rather than Vibeformer's Spoon model or translation pipeline.
 */

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class PublicApiUpstreamExtractor {
  private static final String MAGIC = "VIBEFORMER_UPSTREAM_PUBLIC_API_V1";

  private static final List<String> COLUMNS =
      List.of(
          "source-module",
          "package",
          "owner",
          "kind",
          "name",
          "parameter-count",
          "signature",
          "generic-constraints",
          "nullability",
          "exceptions",
          "delegate",
          "lifecycle",
          "upstream-provenance",
          "javadoc",
          "invocation-evidence");

  private record SourceModule(String name, Path root) {}

  private record EvidenceIndex(Map<String, String> tokens, Map<String, String> members) {}

  private record Owner(
      String qualifiedName,
      String simpleName,
      Tree.Kind kind,
      boolean interfaceLike,
      boolean nullMarked,
      String provenance,
      boolean lifecycle,
      boolean functionalInterface) {}

  private record Row(
      String sourceModule,
      String packageName,
      String owner,
      String kind,
      String name,
      int parameterCount,
      String signature,
      String genericConstraints,
      String nullability,
      String exceptions,
      String delegate,
      String lifecycle,
      String upstreamProvenance,
      String javadoc,
      String invocationEvidence) {
    String key() {
      return String.join("\u0000", owner, kind, name, signature, upstreamProvenance);
    }

    String toTsv() {
      return String.join(
          "\t",
          clean(sourceModule),
          clean(packageName),
          clean(owner),
          clean(kind),
          clean(name),
          Integer.toString(parameterCount),
          clean(signature),
          clean(genericConstraints),
          clean(nullability),
          clean(exceptions),
          clean(delegate),
          clean(lifecycle),
          clean(upstreamProvenance),
          clean(javadoc),
          clean(invocationEvidence));
    }
  }

  private static final class Scanner extends TreePathScanner<Void, Void> {
    private final String sourceModule;
    private final Path workspace;
    private final Path source;
    private final String sourceText;
    private final CompilationUnitTree unit;
    private final DocTrees docs;
    private final SourcePositions positions;
    private final Map<String, Boolean> nullMarkedPackages;
    private final EvidenceIndex evidence;
    private final List<Row> rows;
    private final Deque<Owner> owners = new ArrayDeque<>();
    private final String packageName;

    Scanner(
        String sourceModule,
        Path workspace,
        Path source,
        CompilationUnitTree unit,
        DocTrees docs,
        Map<String, Boolean> nullMarkedPackages,
        EvidenceIndex evidence,
        List<Row> rows) {
      this.sourceModule = sourceModule;
      this.workspace = workspace;
      this.source = source;
      try {
        this.sourceText = Files.readString(source, StandardCharsets.UTF_8);
      } catch (IOException error) {
        throw new IllegalStateException("Could not read upstream source " + source, error);
      }
      this.unit = unit;
      this.docs = docs;
      this.positions = docs.getSourcePositions();
      this.nullMarkedPackages = nullMarkedPackages;
      this.evidence = evidence;
      this.rows = rows;
      this.packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
    }

    @Override
    public Void visitClass(ClassTree tree, Void unused) {
      var parent = owners.peek();
      boolean implicitPublic = parent != null && parent.interfaceLike();
      boolean isPublic = has(tree.getModifiers(), Modifier.PUBLIC) || implicitPublic;
      if (!isPublic) return null;

      String simpleName = tree.getSimpleName().toString();
      if (simpleName.isBlank()) return null;
      String ownerName =
          parent == null
              ? packageName + "." + simpleName
              : parent.qualifiedName() + "$" + simpleName;
      boolean nullMarked =
          nullMarkedPackages.getOrDefault(packageName, false)
              && !hasAnnotation(tree.getModifiers(), "NullUnmarked");
      boolean lifecycle =
          containsLifecycleType(tree.getExtendsClause())
              || tree.getImplementsClause().stream().anyMatch(PublicApiUpstreamExtractor::containsLifecycleType);
      boolean functional = hasAnnotation(tree.getModifiers(), "FunctionalInterface");
      String provenance = provenance(tree);
      Owner owner =
          new Owner(
              ownerName,
              simpleName,
              tree.getKind(),
              tree.getKind() == Tree.Kind.INTERFACE || tree.getKind() == Tree.Kind.ANNOTATION_TYPE,
              nullMarked,
              provenance,
              lifecycle,
              functional);

      String typeKind =
          switch (tree.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "annotation";
            default -> "class";
          };
      rows.add(
          row(
              owner,
              "type",
              simpleName,
              0,
              typeSignature(tree),
              constraints(tree.getTypeParameters()),
              nullMarked ? "type=non-null-default" : "type=unspecified",
              "-",
              functional ? "functional-interface" : (owner.interfaceLike() ? "interface" : "-"),
              lifecycle ? "closeable" : "-",
              provenance,
              docState(),
              invocation(ownerName, simpleName, null)));

      if (tree.getKind() == Tree.Kind.RECORD) addRecordSurface(owner, tree);

      owners.push(owner);
      super.visitClass(tree, unused);
      owners.pop();
      return null;
    }

    private void addRecordSurface(Owner owner, ClassTree tree) {
      List<String[]> components = recordComponents(tree, owner.simpleName());
      if (components.isEmpty()) return;
      String provenance = owner.provenance();
      for (int index = 0; index < components.size(); index++) {
        String type = components.get(index)[0];
        String name = components.get(index)[1];
        rows.add(
            row(
                owner,
                "property",
                name,
                0,
                normalize(type + " " + name),
                "-",
                nullableState(type, tree.getModifiers(), owner.nullMarked(), "value"),
                "-",
                "-",
                "-",
                provenance,
                "record-component",
                invocation(owner.qualifiedName(), owner.simpleName(), name)));
      }
      boolean declaredCanonical =
          tree.getMembers().stream()
              .filter(member -> member instanceof MethodTree)
              .map(member -> (MethodTree) member)
              .anyMatch(
                  method ->
                      method.getReturnType() == null
                          && method.getParameters().size() == components.size());
      if (!declaredCanonical) {
        String parameters =
            String.join(
                ",",
                components.stream().map(component -> component[0] + " " + component[1]).toList());
        String nullability =
            String.join(
                ";",
                java.util.stream.IntStream.range(0, components.size())
                    .mapToObj(
                        index ->
                            nullableState(
                                components.get(index)[0],
                                tree.getModifiers(),
                                owner.nullMarked(),
                                "param" + index))
                    .toList());
        rows.add(
            row(
                owner,
                "constructor",
                ".ctor",
                components.size(),
                normalize(owner.simpleName() + " .ctor(" + parameters + ")"),
                "-",
                nullability,
                "-",
                "-",
                "construct",
                provenance,
                "implicit-record-canonical",
                invocation(owner.qualifiedName(), owner.simpleName(), null)));
      }
    }

    private List<String[]> recordComponents(ClassTree tree, String simpleName) {
      long startPosition = positions.getStartPosition(unit, tree);
      if (startPosition < 0 || startPosition >= sourceText.length()) return List.of();
      int start = (int) startPosition;
      int record = sourceText.indexOf("record", start);
      int name = record < 0 ? -1 : sourceText.indexOf(simpleName, record + "record".length());
      int open = name < 0 ? -1 : sourceText.indexOf('(', name + simpleName.length());
      if (open < 0) return List.of();
      int close = matchingDelimiter(sourceText, open, '(', ')');
      if (close < 0) return List.of();
      String text = sourceText.substring(open + 1, close);
      List<String[]> result = new ArrayList<>();
      for (String component : splitTopLevel(text)) {
        String normalized = normalize(component.replaceAll("/\\*.*?\\*/", " "));
        if (normalized.isBlank()) continue;
        var matcher = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)$").matcher(normalized);
        if (!matcher.find()) throw new IllegalStateException("Cannot parse record component: " + normalized);
        String componentName = matcher.group(1);
        String componentType = normalize(normalized.substring(0, matcher.start()));
        result.add(new String[] {componentType, componentName});
      }
      return result;
    }

    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
      Owner owner = owners.peek();
      if (owner == null) return null;
      boolean constructor = tree.getReturnType() == null;
      boolean implicitPublic = owner.interfaceLike() && !has(tree.getModifiers(), Modifier.PRIVATE);
      boolean isPublic = has(tree.getModifiers(), Modifier.PUBLIC) || implicitPublic;
      if (!isPublic) return null;

      String name = constructor ? ".ctor" : tree.getName().toString();
      String returnType = constructor ? owner.simpleName() : typeText(tree.getReturnType());
      String signature = methodSignature(tree, name, returnType);
      String lifecycle =
          (!constructor && tree.getName().contentEquals("close") && tree.getParameters().isEmpty())
              ? "close"
              : (constructor ? "construct" : (owner.lifecycle() ? "closeable-member" : "-"));
      String delegate =
          owner.functionalInterface() && !constructor && isAbstractInterfaceMethod(tree)
              ? "invoke"
              : "-";
      String exceptions =
          tree.getThrows().isEmpty()
              ? "-"
              : String.join(",", tree.getThrows().stream().map(Object::toString).sorted().toList());
      String nullability = methodNullability(owner, tree, constructor);
      String provenance = provenance(tree);
      rows.add(
          row(
              owner,
              constructor ? "constructor" : "method",
              name,
              tree.getParameters().size(),
              signature,
              constraints(tree.getTypeParameters()),
              nullability,
              exceptions,
              delegate,
              lifecycle,
              provenance,
              docState(),
              invocation(owner.qualifiedName(), owner.simpleName(), constructor ? null : tree.getName().toString())));
      return null;
    }

    @Override
    public Void visitVariable(VariableTree tree, Void unused) {
      Owner owner = owners.peek();
      if (owner == null) return null;
      boolean enumConstant =
          owner.kind() == Tree.Kind.ENUM
              && (tree.getType() == null
                  || (tree.getInitializer() != null
                      && tree.getInitializer().getKind() == Tree.Kind.NEW_CLASS));
      boolean implicitPublic = owner.interfaceLike() || enumConstant;
      boolean isPublic = has(tree.getModifiers(), Modifier.PUBLIC) || implicitPublic;
      if (!isPublic) return null;

      String kind = enumConstant ? "enum-value" : "field";
      String type = enumConstant ? owner.simpleName() : typeText(tree.getType());
      String signature = normalize(type + " " + tree.getName());
      String nullability =
          nullableState(type, tree.getModifiers(), owner.nullMarked(), "value");
      String provenance = provenance(tree);
      rows.add(
          row(
              owner,
              kind,
              tree.getName().toString(),
              0,
              signature,
              "-",
              nullability,
              "-",
              "-",
              "-",
              provenance,
              docState(),
              invocation(owner.qualifiedName(), owner.simpleName(), tree.getName().toString())));
      return null;
    }

    private Row row(
        Owner owner,
        String kind,
        String name,
        int parameterCount,
        String signature,
        String genericConstraints,
        String nullability,
        String exceptions,
        String delegate,
        String lifecycle,
        String provenance,
        String javadoc,
        String invocationEvidence) {
      return new Row(
          sourceModule,
          packageName,
          owner.qualifiedName(),
          kind,
          name,
          parameterCount,
          signature,
          empty(genericConstraints),
          empty(nullability),
          empty(exceptions),
          empty(delegate),
          empty(lifecycle),
          provenance,
          javadoc,
          empty(invocationEvidence));
    }

    private String typeSignature(ClassTree tree) {
      var result = new StringBuilder();
      result.append(tree.getKind().name().toLowerCase(Locale.ROOT)).append(' ');
      result.append(tree.getSimpleName());
      if (!tree.getTypeParameters().isEmpty()) {
        result.append('<');
        result.append(String.join(",", tree.getTypeParameters().stream().map(Object::toString).toList()));
        result.append('>');
      }
      if (tree.getExtendsClause() != null) result.append(" extends ").append(tree.getExtendsClause());
      if (!tree.getImplementsClause().isEmpty()) {
        result.append(" implements ");
        result.append(String.join(",", tree.getImplementsClause().stream().map(Object::toString).toList()));
      }
      return normalize(result.toString());
    }

    private String methodSignature(MethodTree tree, String name, String returnType) {
      var result = new StringBuilder();
      if (!tree.getTypeParameters().isEmpty()) {
        result.append('<');
        result.append(String.join(",", tree.getTypeParameters().stream().map(Object::toString).toList()));
        result.append("> ");
      }
      result.append(returnType).append(' ').append(name).append('(');
      result.append(
          String.join(
              ",",
              tree.getParameters().stream()
                  .map(parameter -> normalize(typeText(parameter.getType()) + " " + parameter.getName()))
                  .toList()));
      result.append(')');
      if (!tree.getThrows().isEmpty()) {
        result.append(" throws ");
        result.append(String.join(",", tree.getThrows().stream().map(Object::toString).toList()));
      }
      return normalize(result.toString());
    }

    private String methodNullability(Owner owner, MethodTree tree, boolean constructor) {
      var parts = new ArrayList<String>();
      if (!constructor) {
        parts.add(nullableState(typeText(tree.getReturnType()), tree.getModifiers(), owner.nullMarked(), "return"));
      }
      for (int index = 0; index < tree.getParameters().size(); index++) {
        VariableTree parameter = tree.getParameters().get(index);
        parts.add(
            nullableState(
                typeText(parameter.getType()),
                parameter.getModifiers(),
                owner.nullMarked(),
                "param" + index));
      }
      return parts.isEmpty() ? "-" : String.join(";", parts);
    }

    private String provenance(Tree tree) {
      long start = positions.getStartPosition(unit, tree);
      long line = start < 0 ? -1 : unit.getLineMap().getLineNumber(start);
      String relative = slash(workspace.relativize(source));
      return relative + (line < 0 ? "" : ":" + line);
    }

    private String docState() {
      TreePath path = getCurrentPath();
      DocCommentTree comment = path == null ? null : docs.getDocCommentTree(path);
      return comment == null ? "absent" : "present";
    }

    private String invocation(String ownerName, String simpleName, String memberName) {
      if (memberName != null) {
        String hit = evidence.members().get(memberName);
        if (hit == null) hit = evidence.members().get(pascal(memberName));
        if (hit != null) return hit;
      }
      String hit = evidence.tokens().get(ownerName);
      if (hit == null) hit = evidence.tokens().get(simpleName);
      return hit == null ? "-" : hit;
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !args[0].equals("--workspace")) {
      throw new IllegalArgumentException("Usage: PublicApiUpstreamExtractor --workspace <checkout-root>");
    }
    Path workspace = Paths.get(args[1]).toAbsolutePath().normalize();
    List<SourceModule> modules =
        List.of(
            new SourceModule(
                "pkl-parser", workspace.resolve("research/pkl/pkl-parser/src/main/java")),
            new SourceModule(
                "pkl-core", workspace.resolve("research/pkl/pkl-core/src/main/java")),
            new SourceModule(
                "pkl-config-java",
                workspace.resolve("research/pkl/pkl-config-java/src/main/java")));
    for (SourceModule module : modules) {
      if (!Files.isDirectory(module.root())) {
        throw new IllegalStateException("Missing upstream production source root: " + module.root());
      }
    }

    EvidenceIndex evidence = loadEvidence(workspace);
    Map<String, Boolean> nullMarkedPackages = discoverNullMarkedPackages(modules);
    List<Row> rows = new ArrayList<>();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("A full JDK is required");

    for (SourceModule module : modules) {
      List<Path> sources = javaFiles(module.root());
      try (StandardJavaFileManager fileManager =
          compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
        Iterable<? extends JavaFileObject> files =
            fileManager.getJavaFileObjectsFromPaths(sources);
        JavacTask task =
            (JavacTask)
                compiler.getTask(
                    null,
                    fileManager,
                    diagnostic -> {},
                    List.of("-proc:none", "-XDshould-stop.at=PARSE"),
                    null,
                    files);
        Iterable<? extends CompilationUnitTree> units = task.parse();
        DocTrees docs = DocTrees.instance(task);
        Map<String, Path> byUri = new HashMap<>();
        for (Path source : sources) byUri.put(source.toUri().normalize().toString(), source);
        for (CompilationUnitTree unit : units) {
          Path source = byUri.get(unit.getSourceFile().toUri().normalize().toString());
          if (source == null) source = Paths.get(unit.getSourceFile().toUri());
          new Scanner(
                  module.name(),
                  workspace,
                  source,
                  unit,
                  docs,
                  nullMarkedPackages,
                  evidence,
                  rows)
              .scan(unit, null);
        }
      }
    }

    rows.sort(Comparator.comparing(Row::key));
    Set<String> keys = new HashSet<>();
    for (Row row : rows) {
      if (!keys.add(row.key())) throw new IllegalStateException("Duplicate extracted row: " + row.key());
    }
    System.out.println("# " + MAGIC);
    System.out.println(String.join("\t", COLUMNS));
    for (Row row : rows) System.out.println(row.toTsv());
  }

  private static EvidenceIndex loadEvidence(Path workspace) throws IOException {
    List<Path> roots =
        List.of(
            workspace.resolve("research/pkl/pkl-parser/src/test"),
            workspace.resolve("research/pkl/pkl-core/src/test"),
            workspace.resolve("research/pkl/pkl-config-java/src/test"),
            workspace.resolve("research/pkl/pkl-config-kotlin/src/test"),
            workspace.resolve("research/pkl/pkl-codegen-java/src/test"),
            workspace.resolve("research/pkl/pkl-codegen-kotlin/src/test"),
            workspace.resolve("research/pkl/docs/modules/java-binding"),
            workspace.resolve("vibeformer/validation"));
    Predicate<Path> evidenceFile =
        path -> {
          String name = path.getFileName().toString(), portable = slash(path);
          return isPklEvidence(workspace, path) && !portable.contains("/public-contract-compiler/")
              && !portable.contains("/pkl-core-test-contract/")
              && (name.endsWith(".java") || name.endsWith(".kt")
                  || name.endsWith(".cs") || name.endsWith(".adoc"));
        };
    Map<String, String> tokens = new HashMap<>();
    Map<String, String> members = new HashMap<>();
    Pattern tokenPattern = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$]*");
    Pattern memberPattern = Pattern.compile("(?:[.]|::)([A-Za-z_$][A-Za-z0-9_$]*)");
    for (Path root : roots) {
      if (!Files.isDirectory(root)) continue;
      for (Path file : files(root, evidenceFile)) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relative = slash(workspace.relativize(file));
        for (int index = 0; index < lines.size(); index++) {
          String text = lines.get(index).trim();
          if (text.isBlank()) continue;
          String provenance = relative + ":" + (index + 1);
          var tokenMatcher = tokenPattern.matcher(text);
          while (tokenMatcher.find()) tokens.putIfAbsent(tokenMatcher.group(), provenance);
          var memberMatcher = memberPattern.matcher(text);
          while (memberMatcher.find()) members.putIfAbsent(memberMatcher.group(1), provenance);
        }
      }
    }
    return new EvidenceIndex(tokens, members);
  }

  private static Map<String, Boolean> discoverNullMarkedPackages(List<SourceModule> modules)
      throws IOException {
    Map<String, Boolean> result = new LinkedHashMap<>();
    Pattern packagePattern = Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*;");
    for (SourceModule module : modules) {
      for (Path file : javaFiles(module.root())) {
        if (!file.getFileName().toString().equals("package-info.java")) continue;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        var matcher = packagePattern.matcher(text);
        if (matcher.find()) result.put(matcher.group(1), text.contains("@NullMarked"));
      }
    }
    return result;
  }

  private static List<Path> javaFiles(Path root) throws IOException {
    return files(root, path -> path.getFileName().toString().endsWith(".java"));
  }

  private static List<Path> files(Path root, Predicate<Path> predicate) throws IOException {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream.filter(Files::isRegularFile).filter(predicate).sorted().toList();
    }
  }

  private static boolean has(ModifiersTree modifiers, Modifier modifier) {
    return modifiers.getFlags().contains(modifier);
  }

  private static boolean hasAnnotation(ModifiersTree modifiers, String simpleName) {
    for (AnnotationTree annotation : modifiers.getAnnotations()) {
      String name = annotation.getAnnotationType().toString();
      if (name.equals(simpleName) || name.endsWith("." + simpleName)) return true;
    }
    return false;
  }

  private static boolean isAbstractInterfaceMethod(MethodTree tree) {
    return tree.getBody() == null
        && !has(tree.getModifiers(), Modifier.STATIC)
        && !has(tree.getModifiers(), Modifier.DEFAULT)
        && !has(tree.getModifiers(), Modifier.PRIVATE);
  }

  private static boolean containsLifecycleType(Tree tree) {
    if (tree == null) return false;
    String text = tree.toString();
    return text.endsWith("AutoCloseable")
        || text.endsWith("Closeable")
        || text.endsWith("Disposable");
  }

  private static String typeText(Tree tree) {
    return tree == null ? "void" : normalize(tree.toString());
  }

  private static String constraints(List<? extends TypeParameterTree> parameters) {
    if (parameters.isEmpty()) return "-";
    return String.join(";", parameters.stream().map(Object::toString).map(PublicApiUpstreamExtractor::normalize).toList());
  }

  private static String nullableState(
      String type, ModifiersTree modifiers, boolean nullMarked, String subject) {
    boolean nullable = type.contains("@Nullable") || hasAnnotation(modifiers, "Nullable");
    boolean unspecified = type.contains("@NullnessUnspecified") || hasAnnotation(modifiers, "NullnessUnspecified");
    String state = nullable ? "nullable" : (unspecified ? "unspecified" : (nullMarked ? "non-null" : "unspecified"));
    return subject + "=" + state;
  }

  private static String pascal(String value) {
    if (value == null || value.isBlank()) return value;
    if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) return value;
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  private static int matchingDelimiter(String text, int open, char opening, char closing) {
    int depth = 0;
    for (int index = open; index < text.length(); index++) {
      char current = text.charAt(index);
      if (current == opening) depth++;
      else if (current == closing && --depth == 0) return index;
    }
    return -1;
  }

  private static List<String> splitTopLevel(String text) {
    var result = new ArrayList<String>();
    int start = 0;
    int angle = 0;
    int round = 0;
    int square = 0;
    for (int index = 0; index < text.length(); index++) {
      switch (text.charAt(index)) {
        case '<' -> angle++;
        case '>' -> angle = Math.max(0, angle - 1);
        case '(' -> round++;
        case ')' -> round = Math.max(0, round - 1);
        case '[' -> square++;
        case ']' -> square = Math.max(0, square - 1);
        case ',' -> {
          if (angle == 0 && round == 0 && square == 0) {
            result.add(text.substring(start, index));
            start = index + 1;
          }
        }
        default -> {}
      }
    }
    result.add(text.substring(start));
    return result;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").trim();
  }

  private static String empty(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static String clean(String value) {
    return empty(value).replace("\t", "\\t").replace("\r", " ").replace("\n", " ");
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static boolean isPklEvidence(Path workspace, Path path) {
    Path validationRoot = workspace.resolve("vibeformer/validation");
    if (!path.startsWith(validationRoot)) return true;
    Path relative = validationRoot.relativize(path);
    if (relative.getNameCount() <= 1 || path.getFileName().toString().startsWith("RawHttp.")) {
      return false;
    }
    return switch (relative.getName(0).toString()) {
      case "differential",
          "language-snippet-contract",
          "language-snippet-runner",
          "loading-contract",
          "package-consumer",
          "package-inspector",
          "pkl-core-corpus",
          "public-api-contract",
          "regex-compat",
          "schema-codegen" -> true;
      default -> false;
    };
  }
}
