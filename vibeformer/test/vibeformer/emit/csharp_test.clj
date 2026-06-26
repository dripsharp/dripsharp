(ns vibeformer.emit.csharp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.kotlin-psi :as kotlin-psi]
            [vibeformer.ingest.source :as source]
            [vibeformer.transform.rules :as rules])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)
           (java.util UUID)))

(def java-fixture
  "package com.example.tools;

public final class Counter {
  private static final String EMPTY = \"\";
  private int ignored;

  private Counter() {
  }

  public static void main(String[] args) {
  }

  static int countWords(String text) {
    return 0;
  }
}
")

(def unsupported-call-fixture
  "package com.example.tools;

public final class UnsupportedCall {
  static void call(String text) {
    text.substring(1);
  }
}
")

(def integer-to-string-fixture
  "package com.example.tools;

public final class IntegerDisplay {
  static String show(int value) {
    return Integer.toString(value);
  }
}
")

(def integer-to-string-overload-fixture
  "package com.example.tools;

public final class IntegerDisplay {
  static String show(int value) {
    return Integer.toString(value, 16);
  }
}
")

(def nullable-types-fixture
  "package com.example.nullable;

import org.jspecify.annotations.Nullable;

public final class NullableApi {
  private final @Nullable String label;

  public NullableApi(@Nullable String label) {
    this.label = label;
  }

  public @Nullable String getLabel() {
    return label;
  }

  public static void demo() {
    new NullableApi(null).getLabel();
  }
}
")

(def stream-pipeline-fixture
  "package com.example.stream;

import java.util.List;
import java.util.stream.Collectors;

public final class StreamPipeline {
  public List<String> normalize(List<String> names) {
    return names.stream()
        .filter(it -> !it.isEmpty())
        .map(it -> it.trim())
        .collect(Collectors.toList());
  }
}
")

(def stream-operations-fixture
  "package com.example.stream;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class StreamOperations {
  public static void main(String[] args) {
  }

  public List<String> sortedUnique(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .distinct()
        .sorted()
        .toList();
  }

  public HashSet<String> uniqueSet(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .collect(Collectors.toSet());
  }

  public Map<String, String> keyed(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .collect(Collectors.toMap(it -> it, it -> it.trim()));
  }

  public LinkedList<String> linked(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  public LinkedHashSet<String> orderedSet(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public String joined(List<String> names) {
    return names.stream()
        .filter(it -> it != null)
        .map(it -> it.trim())
        .collect(Collectors.joining(\",\"));
  }

  public boolean hasEmpty(List<String> names) {
    return names.stream().anyMatch(it -> it.isEmpty());
  }

  public boolean allHaveText(List<String> names) {
    return names.stream().allMatch(it -> !it.isEmpty());
  }

  public boolean noneEmpty(List<String> names) {
    return names.stream().noneMatch(it -> it.isEmpty());
  }

  public Object[] flatten(List<String> names) {
    return names.stream()
        .flatMap(it -> names.stream())
        .toArray();
  }

  public long fixedTotal(List<String> names) {
    return names.stream()
        .mapToLong(it -> 1)
        .sum();
  }

  public Object[] asArray(List<String> names) {
    return names.stream().toArray();
  }
}
")

(def reflection-api-fixture
  "package com.example.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public final class ReflectionApi {
  public static void main(String[] args) {
  }

  public static boolean canInstantiate(Class<?> requestedType, Class<?> implementationType) {
    return requestedType.isAssignableFrom(implementationType)
        && !Modifier.isAbstract(implementationType.getModifiers())
        && !implementationType.isArray()
        && !implementationType.isPrimitive();
  }

  public static String typeLabel() {
    return String.class.getTypeName() + \":\" + String.class.getSimpleName();
  }

  public static Type parentType(Class<?> type) {
    return type.getGenericSuperclass();
  }

  public static Type[] typeParameters(Class<?> type) {
    return type.getTypeParameters();
  }

  public static Type componentType(Class<?> type) {
    return type.getComponentType();
  }

  public static boolean isEnumType(Class<?> type) {
    return type.isEnum();
  }

  public static ClassLoader classLoader(Class<?> type) {
    return type.getClassLoader();
  }

  public static ClassLoader localLoader() {
    return ReflectionApi.class.getClassLoader();
  }

  public static boolean sameLoader(Class<?> type) {
    return same(type.getClassLoader(), ReflectionApi.class.getClassLoader());
  }

  private static boolean same(ClassLoader left, ClassLoader right) {
    return left == right;
  }

  public static String reflectedTypeName(Type type) {
    return type.getTypeName();
  }

  public static Type[] actualArgs(ParameterizedType type) {
    return type.getActualTypeArguments();
  }

  public static Type rawType(ParameterizedType type) {
    return type.getRawType();
  }

  public static Type ownerType(ParameterizedType type) {
    return type.getOwnerType();
  }

  public static Parameter[] parameters(Constructor<?> constructor) {
    return constructor.getParameters();
  }

  public static String parameterName(Parameter parameter) {
    return parameter.isNamePresent() ? parameter.getName() : \"\";
  }
}
")

(def reflection-unsupported-api-fixture
  "package com.example.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.WildcardType;

public final class ReflectionUnsupportedApi {
  public static Method method(Class<?> type) throws Exception {
    return type.getDeclaredMethod(\"run\");
  }

  public static Method publicMethod(Class<?> type) throws Exception {
    return type.getMethod(\"run\");
  }

  public static Method[] methods(Class<?> type) {
    return type.getDeclaredMethods();
  }

  public static Constructor<?>[] constructors(Class<?> type) {
    return type.getDeclaredConstructors();
  }

  public static ClassLoader classLoader(Class<?> type) {
    return type.getClassLoader();
  }

  public static Annotation classAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
    return type.getAnnotation(annotationType);
  }

  public static Object call(Method method, Object target) throws Exception {
    return method.invoke(target);
  }

  public static Object construct(Constructor<?> constructor) throws Exception {
    return constructor.newInstance();
  }

  public static Annotation constructorAnnotation(Constructor<?> constructor, Class<? extends Annotation> annotationType) {
    return constructor.getAnnotation(annotationType);
  }

  public static Annotation parameterAnnotation(Parameter parameter, Class<? extends Annotation> annotationType) {
    return parameter.getAnnotation(annotationType);
  }

  public static java.lang.reflect.Type[] lowerBounds(WildcardType wildcardType) {
    return wildcardType.getLowerBounds();
  }

  public static java.lang.reflect.Type[] upperBounds(WildcardType wildcardType) {
    return wildcardType.getUpperBounds();
  }
}
")

(def synchronized-fixture
  "package com.example.sync;

public final class SynchronizedCase {
  private int value;

  public static void main(String[] args) {
  }

  public synchronized int increment() {
    value = value + 1;
    return value;
  }

  public int add(int amount) {
    synchronized (this) {
      value = value + amount;
      return value;
    }
  }

  public static synchronized int zero() {
    return 0;
  }
}
")

(def enum-fixture
  "package com.example.lookup;

public enum MemberLookupMode {
  IMPLICIT_LOCAL,
  IMPLICIT_LEXICAL,
  IMPLICIT_BASE,
  IMPLICIT_THIS,
  EXPLICIT_RECEIVER
}
")

(def enum-switch-fixture
  "package com.example.token;

import java.util.Locale;

public enum Token {
  EXTERNAL,
  ABSTRACT,
  OPEN,
  LOCAL,
  HIDDEN,
  FIXED,
  CONST,
  WHEN,
  SWITCH,
  LINE_COMMENT,
  BLOCK_COMMENT,
  SEMICOLON,
  UNDERSCORE,
  IDENTIFIER;

  public boolean isModifier() {
    return switch (this) {
      case EXTERNAL, ABSTRACT, OPEN, LOCAL, HIDDEN, FIXED, CONST -> true;
      default -> false;
    };
  }

  public boolean isKeyword() {
    return switch (this) {
      case ABSTRACT,
          CONST,
          EXTERNAL,
          FIXED,
          HIDDEN,
          LOCAL,
          OPEN,
          WHEN,
          SWITCH ->
          true;
      default -> false;
    };
  }

  public boolean isAffix() {
    return switch (this) {
      case LINE_COMMENT, BLOCK_COMMENT, SEMICOLON -> true;
      default -> false;
    };
  }

  public String text() {
    if (this == UNDERSCORE) {
      return \"_\";
    }
    return name().toLowerCase(Locale.ROOT);
  }
}
")

(def enum-call-demo-fixture
  "package com.example.token;

public final class TokenDemo {
  private TokenDemo() {
  }

  public static void main(String[] args) {
    Token.ABSTRACT.isModifier();
    Token.WHEN.isKeyword();
    Token.SEMICOLON.isAffix();
    Token.UNDERSCORE.text();
  }
}
")

(def enum-unsupported-method-fixture
  "package com.example.token;

public enum Token {
  IDENTIFIER;

  public String text() {
    return System.getProperty(\"pkl.token\");
  }
}
")

(def operator-enum-fixture
  "package com.example.operator;

public enum Operator {
  NULL_COALESCE(1, false),
  PIPE(2, true);

  private final int prec;
  private final boolean leftAssoc;

  Operator(int prec, boolean leftAssoc) {
    this.prec = prec;
    this.leftAssoc = leftAssoc;
  }

  public int getPrec() {
    return prec;
  }

  public boolean isLeftAssoc() {
    return leftAssoc;
  }

  public static Operator byName(String name) {
    return switch (name) {
      case \"??\" -> NULL_COALESCE;
      case \"|>\" -> PIPE;
      default -> throw new RuntimeException(\"Unknown operator: \" + name);
    };
  }
}
")

(def record-fixture
  "package com.example.parser;

public record Span(int charIndex, int length) {
  public Span move(int amount) {
    return new Span(charIndex + amount, length);
  }

  public boolean contains(Span other) {
    return charIndex <= other.charIndex && other.charIndex + other.length <= charIndex + length;
  }
}
")

(def default-interface-fixture
  "package com.example.value;

public interface Value {
  <T> T accept(ValueConverter<T> converter);
}

public interface ValueConverter<T> {
  T convertString(StringValue value);

  default T convert(Object value) {
    if (value instanceof Value v) {
      return v.accept(this);
    }

    throw new IllegalArgumentException(\"Unsupported value: \" + value);
  }
}

public final class StringValue implements Value {
  public <T> T accept(ValueConverter<T> converter) {
    return converter.convertString(this);
  }
}
")

(def interface-generic-fixture
  "package com.example.value;

public interface Value {
  <T> T accept(ValueConverter<T> converter);
}

public interface ValueConverter<T> {
  T convert(Value value);
}

public final class DisplayValueConverter implements ValueConverter<String> {
  public String convert(Value value) {
    return \"\";
  }
}
")

(def nested-generics-fixture
  "package com.example.generics;

import java.util.List;

public final class NestedGenerics {
  public List<List<String>> echo(List<List<String>> groups) {
    return groups;
  }
}
")

(def kotlin-basic-fixture
  "package com.example.kotlin

object BasicDeclarations {
  val count: Int = 1

  fun describe(name: String?): String {
    return \"\"
  }
}
")

(def kotlin-api-call-fixture
  "package com.example.kotlin

import java.net.URI
import java.nio.file.Path

object KotlinApiCalls {
  fun message(name: String?): String {
    val raw = \"\"\"
      hello
    \"\"\".trimIndent()
    return name?.let { raw + it } ?: raw
  }

  fun values(root: Path): List<URI> {
    return listOf(root.resolve(\"child\").toUri(), URI(\"https://example.com\"))
  }
}
")

(def kotlin-object-overrides-fixture
  "package com.example.kotlin

import java.net.URI

interface ModuleReader {
  val isLocal: Boolean
  val scheme: String

  fun read(uri: URI): String

  fun listElements(uri: URI): List<String>
}

object FixtureModuleReader : ModuleReader {
  override val isLocal: Boolean = true

  override val scheme: String = \"foo\"

  override fun read(uri: URI): String = \"hello\"

  override fun listElements(uri: URI): List<String> {
    throw NotImplementedError()
  }
}
")

(def assignment-fixture
  "package com.example.tools;

public final class Holder {
  private String value;

  public Holder(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
")

(def object-creation-fixture
  "package com.example.tools;

public final class Factory {
  public static Holder make(String value) {
    Holder holder = new Holder(value);
    return holder;
  }
}
")

(def object-holder-fixture
  "package com.example.tools;

public final class Holder {
  Holder(String value) {
  }
}
")

(def numeric-constructor-fixture
  "package com.example.tools;

public final class NumericFactory {
  public DataSize make() {
    return new DataSize(12);
  }
}

final class DataSize {
  DataSize(double value) {
  }
}
")

(def null-literal-fixture
  "package com.example.tools;

public final class NullFactory {
  public Holder make() {
    return new Holder(null);
  }
}
")

(def static-import-enum-constant-fixture
  "package com.example.units;

import static com.example.units.DataSizeUnit.*;

public final class DataSize {
  private final double value;
  private final DataSizeUnit unit;

  public DataSize(double value, DataSizeUnit unit) {
    this.value = value;
    this.unit = unit;
  }

  public static DataSize ofBytes(double value) {
    return new DataSize(value, BYTES);
  }
}

enum DataSizeUnit {
  BYTES
}
")

(def require-non-null-fixture
  "package com.example.values;

import java.util.Objects;

public final class DataSize {
  private final Unit unit;

  public DataSize(Unit unit) {
    this.unit = Objects.requireNonNull(unit, \"unit\");
  }
}

final class Unit {
}
")

(def objects-equals-fixture
  "package com.example.values;

import java.util.Objects;

public final class Version {
  private final String preRelease;

  public Version(String preRelease) {
    this.preRelease = preRelease;
  }

  public boolean same(Version other) {
    return Objects.equals(preRelease, other.preRelease);
  }
}
")

(def objects-hash-fixture
  "package com.example.values;

import java.util.Objects;

public final class Version {
  private final int major;
  private final int minor;
  private final int patch;
  private final String preRelease;

  public Version(int major, int minor, int patch, String preRelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.preRelease = preRelease;
  }

  public int hashCode() {
    return Objects.hash(major, minor, patch, preRelease);
  }
}
")

(def math-round-fixture
  "package com.example.values;

public final class DataSize {
  private final double value;

  public DataSize(double value) {
    this.value = value;
  }

  public long inWholeBytes() {
    return Math.round(value);
  }
}
")

(def math-min-fixture
  "package com.example.values;

public final class Version {
  private final int major;

  public Version(int major) {
    this.major = major;
  }

  public int smallerMajor(Version other) {
    return Math.min(major, other.major);
  }
}
")

(def math-max-fixture
  "package com.example.values;

public final class Version {
  private final int major;

  public Version(int major) {
    this.major = major;
  }

  public int largerMajor(Version other) {
    return Math.max(major, other.major);
  }
}
")

(def double-hash-code-fixture
  "package com.example.values;

public final class DataSize {
  private final double value;

  public DataSize(double value) {
    this.value = value;
  }

  public int hashCode() {
    return Double.hashCode(value);
  }
}
")

(def pattern-fixture
  "package com.example.patterns;

public final class PatternDemo {
  public static Object show(Object value) {
    if (value instanceof Name n) {
      return n;
    }

    return \"\";
  }
}
")

(def pattern-name-fixture
  "package com.example.patterns;

public final class Name {
}
")

(def negated-pattern-fixture
  "package com.example.patterns;

public final class PatternDemo {
  public static boolean same(Object value) {
    if (!(value instanceof Name n)) {
      return false;
    }

    return true;
  }
}
")

(def conditional-expression-fixture
  "package com.example.values;

public final class DataSize {
  private final double value;

  public DataSize(double value) {
    this.value = value;
  }

  public String label() {
    return value == 1 ? \"byte\" : \"bytes\";
  }
}
")

(def type-cast-fixture
  "package com.example.values;

public final class DataSize {
  private final double value;

  public DataSize(double value) {
    this.value = value;
  }

  public String label() {
    return value == 1 ? (long) value + \" byte\" : value + \" bytes\";
  }
}
")

(def throw-fixture
  "package com.example.errors;

public final class Thrower {
  public static void fail(Object value) {
    throw new IllegalArgumentException(\"Unsupported value: \" + value);
  }
}
")

(def local-call-formatter-fixture
  "package com.example.localcalls;

public interface Formatter<T> {
  T convert(Name value);
}
")

(def local-call-display-fixture
  "package com.example.localcalls;

public final class DisplayFormatter implements Formatter<String> {
  public String convert(Name value) {
    return value.text();
  }
}
")

(def local-call-name-fixture
  "package com.example.localcalls;

public final class Name {
  public String text() {
    return \"pkl\";
  }
}
")

(def local-call-demo-fixture
  "package com.example.localcalls;

public final class Demo {
  public static String run(Formatter<String> formatter, Name name) {
    return formatter.convert(name);
  }
}
")

(def chained-call-fixture
  "package com.example.chain;

public final class Chain {
  public Chain move(int amount) {
    return this;
  }

  public Chain grow(int amount) {
    return this;
  }

  public static Chain run(Chain chain) {
    return chain.move(1).grow(2);
  }
}
")

(defn- sample-word-counter-source []
  (slurp (str (Paths/get "sample-projects/java-word-count/source/src/main/java/com/example/wordcount/WordCounter.java"
                         (make-array String 0)))))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-csharp-emit-test-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (is (true? (d/create-database client {:db-name db-name})))
      (reset! created? true)
      (f (d/connect client {:db-name db-name}))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-csharp-emit-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(def ambiguous-length-facts
  [{:db/id "project"
    :project/id "fixture"
    :project/name "Fixture"
    :project/root "/workspace/fixture"}
   {:db/id "file"
    :file/id "fixture:src/LengthLike.java"
    :file/path "src/LengthLike.java"
    :file/lang :lang/java
    :file/hash "sha256:file"
    :file/project "project"
    :file/package "com.example.tools"}
   {:db/id "class-node"
    :node/id "fixture:src/LengthLike.java:class:LengthLike"
    :node/lang :lang/java
    :node/kind :java.node/class
    :node/name "LengthLike"
    :node/file "file"
    :node/ordinal 0
    :node/start-line 3
    :node/start-column 1
    :node/end-line 7
    :node/end-column 2}
   {:db/id "class-decl"
    :decl/id "java:com.example.tools.LengthLike"
    :decl/lang :lang/java
    :decl/kind :decl.kind/class
    :decl/name "LengthLike"
    :decl/qualified-name "com.example.tools.LengthLike"
    :decl/source-node "class-node"
    :decl/modifiers #{:public :final}}
   {:db/id "int-type"
    :type/id "int"
    :type/lang :lang/java
    :type/name "int"
    :type/nullable? false}
   {:db/id "method-node"
    :node/id "fixture:src/LengthLike.java:method:LengthLike#measure"
    :node/lang :lang/java
    :node/kind :java.node/method
    :node/name "measure"
    :node/file "file"
    :node/parent "class-node"
    :node/ordinal 0
    :node/start-line 4
    :node/start-column 3
    :node/end-line 6
    :node/end-column 4}
   {:db/id "method-decl"
    :decl/id "java:com.example.tools.LengthLike#measure()"
    :decl/lang :lang/java
    :decl/kind :decl.kind/method
    :decl/name "measure"
    :decl/qualified-name "com.example.tools.LengthLike.measure"
    :decl/source-node "method-node"
    :decl/return-type "int-type"
    :decl/modifiers #{:static}}
   {:db/id "return-node"
    :node/id "fixture:src/LengthLike.java:method:LengthLike#measure:body:0"
    :node/lang :lang/java
    :node/kind :java.node/return-statement
    :node/name "return"
    :node/file "file"
    :node/parent "method-node"
    :node/role :body
    :node/ordinal 0
    :node/start-line 5
    :node/start-column 5
    :node/end-line 5
    :node/end-column 18}
   {:db/id "field-node"
    :node/id "fixture:src/LengthLike.java:method:LengthLike#measure:body:0:return:length"
    :node/lang :lang/java
    :node/kind :java.node/field-read
    :node/name "length"
    :node/file "file"
    :node/parent "return-node"
    :node/role :return-expression
    :node/ordinal 0
    :node/start-line 5
    :node/start-column 12
    :node/end-line 5
    :node/end-column 18}
   {:db/id "target-node"
    :node/id "fixture:src/LengthLike.java:method:LengthLike#measure:body:0:return:length:target"
    :node/lang :lang/java
    :node/kind :java.node/variable-read
    :node/name "value"
    :node/file "file"
    :node/parent "field-node"
    :node/role :target
    :node/ordinal 0
    :node/start-line 5
    :node/start-column 12
    :node/end-line 5
    :node/end-column 17}])

(deftest emits-java-declarations-as-csharp-skeletons
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/Counter.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path java-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/tools/Counter.cs")
              content (slurp (str generated))]
          (is (= 1 (:csharp/files-written result)))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (testing "namespace, class, static final field, constructor, and methods are emitted"
            (doseq [snippet ["namespace com.example.tools"
                             "public sealed class Counter"
                             "private static readonly string EMPTY = \"\";"
                             "private int ignored;"
                             "private Counter()"
                             "public static void Main(string[] args)"
                             "internal static int countWords(string text)"
                             "return 0;"
                             "throw new System.NotImplementedException();"]]
              (is (str/includes? content snippet))))
          (testing "emission returns provenance-friendly rule applications"
            (is (seq (:csharp/rule-applications result)))
            (is (seq (:csharp/provenance result)))
            (is (empty? (:csharp/diagnostics result))))
          (testing "instance field declarations keep field provenance"
            (let [field-entry (some #(when (and (= :java.field-node/to-csharp-field
                                                  (get-in % [:rule :rule/id]))
                                             (= "ignored" (:source/name %)))
                                      %)
                                    (:csharp/provenance result))]
              (is (some? field-entry))
              (is (= :rule.status/implemented
                     (get-in field-entry [:rule :rule/status]))))))))))

(deftest emits-java-interfaces-generic-signatures-and-implements-clauses
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/value/Value.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path interface-generic-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              value-file (.resolve target "com/example/value/Value.cs")
              converter-file (.resolve target "com/example/value/ValueConverter.cs")
              display-file (.resolve target "com/example/value/DisplayValueConverter.cs")
              value-content (slurp (str value-file))
              converter-content (slurp (str converter-file))
              display-content (slurp (str display-file))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (= 3 (:csharp/files-written result)))
          (doseq [file [value-file converter-file display-file]]
            (is (Files/isRegularFile file (make-array java.nio.file.LinkOption 0))))
          (is (str/includes? value-content "public interface Value"))
          (is (str/includes? value-content "T accept<T>(ValueConverter<T> converter);"))
          (is (str/includes? converter-content "public interface ValueConverter<T>"))
          (is (str/includes? converter-content "T convert(Value value);"))
          (is (str/includes? display-content "public sealed class DisplayValueConverter : ValueConverter<string>"))
          (is (str/includes? display-content "public string convert(Value value)"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.interface-node/to-csharp-interface
                       :java.method-node/to-csharp-method
                       :java.class-node/to-csharp-class]))
          (testing "interface provenance has registered rule metadata"
            (let [interface-entry (some #(when (= :java.interface-node/to-csharp-interface
                                                  (get-in % [:rule :rule/id]))
                                           %)
                                        (:csharp/provenance result))]
              (is (some? interface-entry))
              (is (= :rule.status/implemented
                     (get-in interface-entry [:rule :rule/status])))
              (is (= :java.node/interface (:source/kind interface-entry))))))))))

(deftest emits-nested-java-generic-type-arguments
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/generics/NestedGenerics.java"
            opts {:source/root source-root
                  :project/id "nested-generics"
                  :project/name "Nested Generics"}]
        (write-file! source-root file-path nested-generics-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "nested-generics"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/generics/NestedGenerics.cs")
              content (slurp (str generated))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "using System.Collections.Generic;"))
          (is (str/includes? content "public List<List<string>> echo(List<List<string>> groups)"))
          (is (str/includes? content "return groups;"))
          (is (empty? (:csharp/diagnostics result))))))))

(deftest emits-kotlin-object-declarations-with-default-bodies
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/kotlin/com/example/kotlin/BasicDeclarations.kt"
            opts {:source/root source-root
                  :project/id "kotlin-basic"
                  :project/name "Kotlin Basic"}]
        (write-file! source-root file-path kotlin-basic-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "kotlin-basic"})
        (kotlin-psi/enrich! conn {:project/id "kotlin-basic"})
        (rules/register! conn rules/initial-kotlin-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/kotlin/BasicDeclarations.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "#nullable enable"))
          (is (str/includes? content "namespace com.example.kotlin"))
          (is (str/includes? content "public static class BasicDeclarations"))
          (is (str/includes? content "public static int count { get; } = default!;"))
          (is (str/includes? content "public static string describe(string? name)"))
          (is (str/includes? content "return default!;"))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:kotlin.object-node/to-csharp-stub
                        :kotlin.property-node/to-csharp-stub
                        :kotlin.function-node/to-csharp-stub]]
            (is (contains? applied-rules rule))))))))

(deftest emits-focused-kotlin-api-call-bodies
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/kotlin/com/example/kotlin/ApiCalls.kt"
            opts {:source/root source-root
                  :project/id "kotlin-api"
                  :project/name "Kotlin API"}]
        (write-file! source-root file-path kotlin-api-call-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "kotlin-api"})
        (kotlin-psi/enrich! conn {:project/id "kotlin-api"})
        (rules/register! conn rules/initial-kotlin-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/kotlin/KotlinApiCalls.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "using System;"))
          (is (str/includes? content "using System.Collections.Generic;"))
          (is (str/includes? content "public static string message(string? name)"))
          (is (str/includes? content "var raw = \"hello\";"))
          (is (str/includes? content "return name is not null ? raw + name : raw;"))
          (is (str/includes? content "public static List<Uri> values(string root)"))
          (is (str/includes? content "return new List<Uri> { new Uri(System.IO.Path.Combine(root, \"child\"), UriKind.RelativeOrAbsolute), new Uri(\"https://example.com\") };"))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:kotlin.object-node/to-csharp-stub
                        :kotlin.function-node/to-csharp-stub
                        :kotlin.local-property-node/to-csharp-local
                        :kotlin.return-node/to-csharp-return]]
            (is (contains? applied-rules rule))))))))

(deftest emits-java-nullable-type-use-as-csharp-nullable
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/nullable/NullableApi.java"
            opts {:source/root source-root
                  :project/id "nullable"
                  :project/name "Nullable"}]
        (write-file! source-root file-path nullable-types-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "nullable"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/nullable/NullableApi.cs")
              content (slurp (str generated))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "#nullable enable"))
          (is (str/includes? content "private readonly string? label;"))
          (is (str/includes? content "public NullableApi(string? label)"))
          (is (str/includes? content "public string? getLabel()"))
          (is (str/includes? content "new NullableApi(null).getLabel();"))
          (is (empty? (:csharp/diagnostics result))))))))

(deftest emits-kotlin-object-interface-overrides
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/kotlin/com/example/kotlin/ObjectOverrides.kt"
            opts {:source/root source-root
                  :project/id "kotlin-overrides"
                  :project/name "Kotlin Overrides"}]
        (write-file! source-root file-path kotlin-object-overrides-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "kotlin-overrides"})
        (kotlin-psi/enrich! conn {:project/id "kotlin-overrides"})
        (rules/register! conn rules/initial-kotlin-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              interface-file (.resolve target "com/example/kotlin/ModuleReader.cs")
              object-file (.resolve target "com/example/kotlin/FixtureModuleReader.cs")
              interface-content (slurp (str interface-file))
              object-content (slurp (str object-file))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile interface-file (make-array java.nio.file.LinkOption 0)))
          (is (Files/isRegularFile object-file (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? interface-content "using System;"))
          (is (str/includes? interface-content "using System.Collections.Generic;"))
          (is (str/includes? interface-content "public interface ModuleReader"))
          (is (str/includes? interface-content "bool isLocal { get; }"))
          (is (str/includes? interface-content "string scheme { get; }"))
          (is (str/includes? interface-content "string read(Uri uri);"))
          (is (str/includes? interface-content "List<string> listElements(Uri uri);"))
          (is (str/includes? object-content "using System;"))
          (is (str/includes? object-content "using System.Collections.Generic;"))
          (is (str/includes? object-content "public sealed class FixtureModuleReader : ModuleReader"))
          (is (str/includes? object-content "public static readonly FixtureModuleReader Instance = new FixtureModuleReader();"))
          (is (str/includes? object-content "private FixtureModuleReader()"))
          (is (str/includes? object-content "public bool isLocal { get; } = true;"))
          (is (str/includes? object-content "public string scheme { get; } = \"foo\";"))
          (is (str/includes? object-content "public string read(Uri uri)"))
          (is (str/includes? object-content "return \"hello\";"))
          (is (str/includes? object-content "public List<string> listElements(Uri uri)"))
          (is (str/includes? object-content "throw new NotImplementedException();"))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:kotlin.class-node/to-csharp-stub
                        :kotlin.object-node/to-csharp-stub
                        :kotlin.property-node/to-csharp-stub
                        :kotlin.function-node/to-csharp-stub
                        :kotlin.return-node/to-csharp-return
                        :kotlin.throw-node/to-csharp-throw]]
            (is (contains? applied-rules rule))))))))

(deftest emits-simple-java-enums
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/lookup/MemberLookupMode.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path enum-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/lookup/MemberLookupMode.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))
              enum-entry (some #(when (= :java.enum-node/to-csharp-enum
                                         (get-in % [:rule :rule/id]))
                                  %)
                               (:csharp/provenance result))
              constant-entry (some #(when (and (= :java.field-node/to-csharp-field
                                                (get-in % [:rule :rule/id]))
                                             (= "IMPLICIT_LOCAL" (:source/name %)))
                                      %)
                                    (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["namespace com.example.lookup"
                           "public enum MemberLookupMode"
                           "IMPLICIT_LOCAL,"
                           "IMPLICIT_LEXICAL,"
                           "IMPLICIT_BASE,"
                           "IMPLICIT_THIS,"
                           "EXPLICIT_RECEIVER"]]
            (is (str/includes? content snippet)))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.enum-node/to-csharp-enum
                       :java.field-node/to-csharp-field]))
          (is (some? enum-entry))
          (is (= :rule.status/implemented
                 (get-in enum-entry [:rule :rule/status])))
          (is (= :java.node/enum (:source/kind enum-entry)))
          (is (some? constant-entry))
          (is (= :java.node/field (:source/kind constant-entry))))))))

(deftest emits-java-records-from-components
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/parser/Span.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path record-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/parser/Span.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "public sealed record Span(int charIndex, int length)"))
          (is (not (str/includes? content "charIndex()")))
          (is (not (str/includes? content "length()")))
          (is (str/includes? content "public Span move(int amount)"))
          (is (str/includes? content "return new Span(this.charIndex + amount, this.length);"))
          (is (str/includes? content "return this.charIndex <= other.charIndex && other.charIndex + other.length <= this.charIndex + this.length;"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.record-node/to-csharp-record
                       :java.record-component-node/to-csharp-parameter
                       :java.method-node/to-csharp-method
                       :java.object-creation-node/to-csharp-new
                       :java.field-read-node/to-csharp-member])))))))

(deftest emits-token-style-enum-switch-methods
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/token/Token.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path enum-switch-fixture)
        (write-file! source-root "src/main/java/com/example/token/TokenDemo.java" enum-call-demo-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/token/Token.cs")
              demo-generated (.resolve target "com/example/token/TokenDemo.cs")
              content (slurp (str generated))
              demo-content (slurp (str demo-generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))
              switch-entry (some #(when (= :java.node/switch-expression (:source/kind %)) %)
                                 (:csharp/provenance result))
              case-entries (filter #(= :java.node/switch-case (:source/kind %))
                                   (:csharp/provenance result))
              method-call-entries (filter #(= :java.node/method-call (:source/kind %))
                                          (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "public enum Token"))
          (is (str/includes? content "ABSTRACT,"))
          (is (str/includes? content "public static class TokenExtensions"))
          (is (str/includes? content "public static bool isModifier(this Token value)"))
          (is (str/includes? content "public static bool isKeyword(this Token value)"))
          (is (str/includes? content "public static bool isAffix(this Token value)"))
          (is (str/includes? content "public static string text(this Token value)"))
          (is (str/includes? content "return value switch"))
          (is (str/includes? content "Token.EXTERNAL or Token.ABSTRACT or Token.OPEN or Token.LOCAL or Token.HIDDEN or Token.FIXED or Token.CONST => true,"))
          (is (str/includes? content "Token.ABSTRACT or Token.CONST or Token.EXTERNAL or Token.FIXED or Token.HIDDEN or Token.LOCAL or Token.OPEN or Token.WHEN or Token.SWITCH => true,"))
          (is (str/includes? content "Token.LINE_COMMENT or Token.BLOCK_COMMENT or Token.SEMICOLON => true,"))
          (is (str/includes? content "_ => false,"))
          (is (str/includes? content "if (value == Token.UNDERSCORE)"))
          (is (str/includes? content "return \"_\";"))
          (is (str/includes? content "return value.ToString().ToLowerInvariant();"))
          (is (str/includes? demo-content "Token.ABSTRACT.isModifier();"))
          (is (str/includes? demo-content "Token.WHEN.isKeyword();"))
          (is (str/includes? demo-content "Token.SEMICOLON.isAffix();"))
          (is (str/includes? demo-content "Token.UNDERSCORE.text();"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.method-node/to-csharp-method
                       :java.switch-expression-node/to-csharp-switch
                       :java.switch-case-node/to-csharp-switch-arm]))
          (is (some? switch-entry))
          (is (= 6 (count case-entries)))
          (is (some #(= "toLowerCase" (:source/name %)) method-call-entries))
          (is (some #(= "name" (:source/name %)) method-call-entries)))))))

(deftest unsupported-enum-methods-produce-structured-diagnostics
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/token/Token.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path enum-unsupported-method-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/token/Token.cs")
              content (slurp (str generated))
              diagnostic (first (:csharp/diagnostics result))
              failed-app (some #(when (= :rule-app.status/failed (:rule-app/status %)) %)
                               (:csharp/rule-applications result))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "public enum Token"))
          (is (not (str/includes? content "text()")))
          (is (= :java.method-node/to-csharp-method (:rule/id diagnostic)))
          (is (= :emit.reason/unsupported-enum-method
                 (get-in diagnostic [:rule/context :reason])))
          (is (= file-path (:source/file diagnostic)))
          (is (= 6 (get-in diagnostic [:source/span :start-line])))
          (is (= :java.method-node/to-csharp-method
                 (second (:rule-app/rule failed-app)))))))))

(deftest emits-stateful-enum-members-and-static-by-name-switch
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/operator/Operator.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path operator-enum-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/operator/Operator.cs")
              content (slurp (str generated))
              reasons (frequencies (map #(get-in % [:rule/context :reason])
                                        (:csharp/diagnostics result)))
              failed-rules (frequencies (map (comp second :rule-app/rule)
                                             (filter #(= :rule-app.status/failed (:rule-app/status %))
                                                     (:csharp/rule-applications result))))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "using System;"))
          (is (not (str/includes? content "using com.example.operator;")))
          (is (str/includes? content "namespace com.example.@operator"))
          (is (str/includes? content "public sealed class Operator"))
          (is (str/includes? content "public static readonly Operator NULL_COALESCE = new Operator(1, false);"))
          (is (str/includes? content "public static readonly Operator PIPE = new Operator(2, true);"))
          (is (str/includes? content "private readonly int prec;"))
          (is (str/includes? content "private readonly bool leftAssoc;"))
          (is (str/includes? content "private Operator(int prec, bool leftAssoc)"))
          (is (str/includes? content "this.prec = prec;"))
          (is (str/includes? content "this.leftAssoc = leftAssoc;"))
          (is (str/includes? content "public int getPrec()"))
          (is (str/includes? content "return this.prec;"))
          (is (str/includes? content "public bool isLeftAssoc()"))
          (is (str/includes? content "return this.leftAssoc;"))
          (is (str/includes? content "public static Operator byName(string name)"))
          (is (str/includes? content "return name switch"))
          (is (str/includes? content "\"??\" => Operator.NULL_COALESCE,"))
          (is (str/includes? content "\"|>\" => Operator.PIPE,"))
          (is (str/includes? content "_ => throw new Exception(\"Unknown operator: \" + name),"))
          (is (= {} reasons))
          (is (= {} failed-rules))
          (is (every? rule-ids
                      [:java.field-node/to-csharp-field
                       :java.constructor-node/to-csharp-constructor
                       :java.method-node/to-csharp-method
                       :java.switch-expression-node/to-csharp-switch
                       :java.switch-case-node/to-csharp-switch-arm
                       :java.throw-statement-node/to-csharp-throw
                       :java.object-creation-node/to-csharp-new])))))))

(deftest emits-default-interface-method-bodies
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/value/Value.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path default-interface-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              converter-file (.resolve target "com/example/value/ValueConverter.cs")
              converter-content (slurp (str converter-file))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))
              method-entry (some #(when (and (= :java.method-node/to-csharp-method
                                                (get-in % [:rule :rule/id]))
                                             (= "convert" (:source/name %)))
                                    %)
                                  (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile converter-file (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["T convert(object value)"
                           "if (value is Value v)"
                           "return v.accept(this);"
                           "throw new ArgumentException(\"Unsupported value: \" + value);"]]
            (is (str/includes? converter-content snippet)))
          (is (not (str/includes? converter-content "Default Java interface method body is not emitted yet")))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.method-node/to-csharp-method
                       :java.if-statement-node/to-csharp-if
                       :java.type-pattern-node/to-csharp-pattern
                       :java.return-statement-node/to-csharp-return
                       :java.method-call-node/to-csharp-invocation
                       :java.throw-statement-node/to-csharp-throw
                       :java.object-creation-node/to-csharp-new]))
          (is (some? method-entry))
          (is (= :rule.status/implemented
                 (get-in method-entry [:rule :rule/status])))
          (is (= :java.node/method (:source/kind method-entry))))))))

(deftest emits-this-and-field-assignment-statements
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/Holder.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path assignment-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/tools/Holder.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "private string value;"))
          (is (str/includes? content "this.value = value;"))
          (is (str/includes? content "return this.value;"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.field-node/to-csharp-field
                       :java.assignment-node/to-csharp-assignment
                       :java.field-write-node/to-csharp-member
                       :java.field-read-node/to-csharp-member
                       :java.this-node/to-csharp-this]))
          (testing "assignment provenance has registered rule metadata"
            (let [assignment-entry (some #(when (= :java.assignment-node/to-csharp-assignment
                                                   (get-in % [:rule :rule/id]))
                                            %)
                                         (:csharp/provenance result))]
              (is (some? assignment-entry))
              (is (= :rule.status/implemented
                     (get-in assignment-entry [:rule :rule/status])))
              (is (= :java.node/assignment (:source/kind assignment-entry))))))))))

(deftest emits-project-local-object-creation
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/Factory.java"
            holder-path "src/main/java/com/example/tools/Holder.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path object-creation-fixture)
        (write-file! source-root holder-path object-holder-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/tools/Factory.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "Holder holder = new Holder(value);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.object-creation-node/to-csharp-new))
          (testing "object creation provenance has registered rule metadata"
            (let [entry (some #(when (= :java.object-creation-node/to-csharp-new
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/object-creation (:source/kind entry))))))))))

(deftest emits-project-local-object-creation-with-coerced-literal-signature
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/NumericFactory.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path numeric-constructor-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/tools/NumericFactory.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return new DataSize(12);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.object-creation-node/to-csharp-new))
          (testing "object creation provenance remains tied to the registered rule"
            (let [entry (some #(when (= :java.object-creation-node/to-csharp-new
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/object-creation (:source/kind entry))))))))))

(deftest emits-null-literals
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/NullFactory.java"
            holder-path "src/main/java/com/example/tools/Holder.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path null-literal-fixture)
        (write-file! source-root holder-path object-holder-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/tools/NullFactory.cs")
              content (slurp (str generated))
              entry (some #(when (= :java.literal-node/to-csharp-literal
                                    (get-in % [:rule :rule/id]))
                             %)
                          (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return new Holder(null);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (some? entry))
          (is (= :rule.status/implemented
                 (get-in entry [:rule :rule/status])))
          (is (= :java.node/literal (:source/kind entry))))))))

(deftest emits-static-imported-enum-constants
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/units/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path static-import-enum-constant-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/units/DataSize.cs")
              enum-generated (.resolve target "com/example/units/DataSizeUnit.cs")
              content (slurp (str generated))
              enum-content (slurp (str enum-generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (Files/isRegularFile enum-generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? enum-content "internal enum DataSizeUnit"))
          (is (str/includes? content "return new DataSize(value, DataSizeUnit.BYTES);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.field-read-node/to-csharp-member))
          (testing "field-read provenance has registered rule metadata"
            (let [entry (some #(when (and (= :java.field-read-node/to-csharp-member
                                            (get-in % [:rule :rule/id]))
                                         (= "BYTES" (:source/name %)))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/field-read (:source/kind entry))))))))))

(deftest emits-objects-require-non-null-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path require-non-null-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/DataSize.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "this.unit = (unit ?? throw new System.ArgumentNullException(\"unit\"));"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.objects-require-non-null/to-csharp-null-check))
          (testing "requireNonNull provenance has registered rule metadata"
            (let [entry (some #(when (= :java.objects-require-non-null/to-csharp-null-check
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-objects-equals-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/Version.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path objects-equals-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/Version.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return object.Equals(this.preRelease, other.preRelease);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.objects-equals/to-csharp-object-equals))
          (testing "Objects.equals provenance has registered rule metadata"
            (let [entry (some #(when (= :java.objects-equals/to-csharp-object-equals
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-objects-hash-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/Version.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path objects-hash-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/Version.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return System.HashCode.Combine(this.major, this.minor, this.patch, this.preRelease);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.objects-hash/to-csharp-hash-code-combine))
          (testing "Objects.hash provenance has registered rule metadata"
            (let [entry (some #(when (= :java.objects-hash/to-csharp-hash-code-combine
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-math-round-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path math-round-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/DataSize.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return (long)System.Math.Floor(this.value + 0.5);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.math-round/to-csharp-java-round))
          (testing "Math.round provenance has registered rule metadata"
            (let [entry (some #(when (= :java.math-round/to-csharp-java-round
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-math-min-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/Version.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path math-min-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/Version.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return System.Math.Min(this.major, other.major);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.math-min/to-csharp-math-min))
          (testing "Math.min provenance has registered rule metadata"
            (let [entry (some #(when (= :java.math-min/to-csharp-math-min
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-math-max-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/Version.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path math-max-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/Version.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return System.Math.Max(this.major, other.major);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.math-max/to-csharp-math-max))
          (testing "Math.max provenance has registered rule metadata"
            (let [entry (some #(when (= :java.math-max/to-csharp-math-max
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-double-hash-code-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path double-hash-code-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/DataSize.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return (this.value).GetHashCode();"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.double-hash-code/to-csharp-get-hash-code))
          (testing "Double.hashCode provenance has registered rule metadata"
            (let [entry (some #(when (= :java.double-hash-code/to-csharp-get-hash-code
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/method-call (:source/kind entry))))))))))

(deftest emits-instanceof-type-patterns
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/patterns/PatternDemo.java"
            name-path "src/main/java/com/example/patterns/Name.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path pattern-fixture)
        (write-file! source-root name-path pattern-name-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/patterns/PatternDemo.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "if (value is Name n)"))
          (is (str/includes? content "return n;"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.type-pattern-node/to-csharp-pattern))
          (testing "type-pattern provenance has registered rule metadata"
            (let [entry (some #(when (= :java.type-pattern-node/to-csharp-pattern
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/type-pattern (:source/kind entry))))))))))

(deftest emits-logical-not-unary-expressions
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/patterns/PatternDemo.java"
            name-path "src/main/java/com/example/patterns/Name.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path negated-pattern-fixture)
        (write-file! source-root name-path pattern-name-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/patterns/PatternDemo.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "if (!(value is Name n))"))
          (is (str/includes? content "return false;"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.unary-operator-node/to-csharp-unary))
          (testing "logical-not provenance has registered rule metadata"
            (let [entry (some #(when (= :java.unary-operator-node/to-csharp-unary
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/unary-operator (:source/kind entry))))))))))

(deftest emits-conditional-expressions
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path conditional-expression-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/DataSize.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return this.value == 1 ? \"byte\" : \"bytes\";"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.conditional-expression-node/to-csharp-conditional))
          (testing "conditional expression provenance has registered rule metadata"
            (let [entry (some #(when (= :java.conditional-expression-node/to-csharp-conditional
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/conditional-expression (:source/kind entry))))))))))

(deftest emits-type-cast-expressions
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/values/DataSize.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path type-cast-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/values/DataSize.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return this.value == 1 ? (long)(this.value) + \" byte\" : this.value + \" bytes\";"))
          (is (empty? (:csharp/diagnostics result)))
          (is (contains? rule-ids :java.type-cast-node/to-csharp-cast))
          (testing "type-cast provenance has registered rule metadata"
            (let [entry (some #(when (= :java.type-cast-node/to-csharp-cast
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/type-cast (:source/kind entry))))))))))

(deftest emits-throw-statements-and-known-java-exceptions
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/errors/Thrower.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path throw-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/errors/Thrower.cs")
              content (slurp (str generated))
              rule-ids (set (map (comp second :rule-app/rule)
                                 (:csharp/rule-applications result)))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "using System;"))
          (is (str/includes? content "throw new ArgumentException(\"Unsupported value: \" + value);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.throw-statement-node/to-csharp-throw
                       :java.object-creation-node/to-csharp-new]))
          (testing "throw provenance has registered rule metadata"
            (let [entry (some #(when (= :java.throw-statement-node/to-csharp-throw
                                        (get-in % [:rule :rule/id]))
                                 %)
                              (:csharp/provenance result))]
              (is (some? entry))
              (is (= :rule.status/implemented
                     (get-in entry [:rule :rule/status])))
              (is (= :java.node/throw-statement (:source/kind entry))))))))))

(deftest emits-project-local-method-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root "src/main/java/com/example/localcalls/Formatter.java" local-call-formatter-fixture)
        (write-file! source-root "src/main/java/com/example/localcalls/DisplayFormatter.java" local-call-display-fixture)
        (write-file! source-root "src/main/java/com/example/localcalls/Name.java" local-call-name-fixture)
        (write-file! source-root "src/main/java/com/example/localcalls/Demo.java" local-call-demo-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              display-content (slurp (str (.resolve target "com/example/localcalls/DisplayFormatter.cs")))
              demo-content (slurp (str (.resolve target "com/example/localcalls/Demo.cs")))
              call-provenance (filter #(= :java.method-call-node/to-csharp-invocation
                                          (get-in % [:rule :rule/id]))
                                      (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (str/includes? display-content "return value.text();"))
          (is (str/includes? demo-content "return formatter.convert(name);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (seq call-provenance))
          (is (every? #(= :rule.status/implemented
                          (get-in % [:rule :rule/status]))
                      call-provenance)))))))

(deftest emits-chained-project-local-method-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/chain/Chain.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path chained-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/chain/Chain.cs")
              content (slurp (str generated))
              call-provenance (filter #(= :java.method-call-node/to-csharp-invocation
                                          (get-in % [:rule :rule/id]))
                                      (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return chain.move(1).grow(2);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (= #{"move" "grow"}
                 (set (map :source/name call-provenance))))
          (is (every? #(= :rule.status/implemented
                          (get-in % [:rule :rule/status]))
                      call-provenance)))))))

(deftest emits-integer-to-string-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/IntegerDisplay.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path integer-to-string-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/tools/IntegerDisplay.cs")
              content (slurp (str generated))
              call-ref (ffirst
                        (d/q '[:find (pull ?ref [:ref/name
                                                 :ref/resolved?
                                                 {:ref/owner-type [:type/name]}])
                               :where
                               [?node :node/name "toString"]
                               [?node :node/kind :java.node/method-call]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/method-call]]
                             db))
              entry (some #(when (= :java.integer-to-string/to-csharp-convert-to-string
                                    (get-in % [:rule :rule/id]))
                             %)
                          (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (= {:ref/name "toString"
                  :ref/resolved? true
                  :ref/owner-type {:type/name "java.lang.Integer"}}
                 call-ref))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "return System.Convert.ToString(value);"))
          (is (empty? (:csharp/diagnostics result)))
          (is (some? entry))
          (is (= :rule.status/implemented
                 (get-in entry [:rule :rule/status])))
          (is (= :java.api/integer-to-string
                 (get-in entry [:rule :rule/input-feature]))))))))

(deftest unsupported-integer-to-string-overloads-produce-diagnostics
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/IntegerDisplay.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path integer-to-string-overload-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/tools/IntegerDisplay.cs")
              content (slurp (str generated))
              diagnostic (first (:csharp/diagnostics result))
              failed-app (some #(when (= :rule-app.status/failed (:rule-app/status %)) %)
                               (:csharp/rule-applications result))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "Unsupported Java node method-call"))
          (is (= :java.integer-to-string/to-csharp-convert-to-string (:rule/id diagnostic)))
          (is (= :emit.reason/unsupported-overload
                 (get-in diagnostic [:rule/context :reason])))
          (is (= 2 (get-in diagnostic [:rule/context :arity])))
            (is (= :java.integer-to-string/to-csharp-convert-to-string
                   (second (:rule-app/rule failed-app)))))))))

(deftest emits-supported-stream-pipeline-as-linq
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/stream/StreamPipeline.java"
            opts {:source/root source-root
                  :project/id "stream-pipeline"
                  :project/name "Stream Pipeline"}]
        (write-file! source-root file-path stream-pipeline-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "stream-pipeline"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/stream/StreamPipeline.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["using System.Collections.Generic;"
                           "using System.Linq;"
                           "public List<string> normalize(List<string> names)"
                           "return names.Where(it => !(string.IsNullOrEmpty(it))).Select(it => it.Trim()).ToList();"]]
            (is (str/includes? content snippet)))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:java.lambda-node/to-csharp-lambda
                        :java.stream-source/to-csharp-enumerable
                        :java.stream-filter/to-csharp-where
                        :java.stream-map/to-csharp-select
                          :java.stream-collect-to-list/to-csharp-to-list
                          :java.stream-collector-to-list/to-csharp-to-list]]
              (is (contains? applied-rules rule))))))))

(deftest emits-supported-stream-operations-as-linq
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/stream/StreamOperations.java"
            opts {:source/root source-root
                  :project/id "stream-operations"
                  :project/name "Stream Operations"}]
        (write-file! source-root file-path stream-operations-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "stream-operations"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/stream/StreamOperations.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["using System.Linq;"
                           "return names.Where(it => it != null).Distinct().OrderBy(it => it).ToList();"
                           "return names.Where(it => it != null).ToHashSet();"
                           "return names.Where(it => it != null).ToDictionary(it => it, it => it.Trim());"
                           "return new LinkedList<string>(names.Where(it => it != null));"
                           "return new HashSet<string>(names.Where(it => it != null));"
                           "return string.Join(\",\", names.Where(it => it != null).Select(it => it.Trim()));"
                           "return names.Any(it => string.IsNullOrEmpty(it));"
                           "return names.All(it => !(string.IsNullOrEmpty(it)));"
                           "return !(names.Any(it => string.IsNullOrEmpty(it)));"
                           "return names.SelectMany(it => names).ToArray();"
                           "return names.Select(it => 1).Sum();"
                           "return names.ToArray();"]]
            (is (str/includes? content snippet)))
          (is (= {:ok? true :failures []} coverage))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:java.stream-filter/to-csharp-where
                        :java.stream-distinct/to-csharp-distinct
                        :java.stream-sorted/to-csharp-order-by
                        :java.stream-collect-to-set/to-csharp-to-hash-set
                        :java.stream-collect-to-map/to-csharp-to-dictionary
                        :java.stream-collector-to-map/to-csharp-to-dictionary
                        :java.stream-collect-to-collection/to-csharp-collection-constructor
                        :java.stream-collector-to-collection/to-csharp-collection-constructor
                        :java.stream-collect-joining/to-csharp-string-join
                        :java.stream-any-match/to-csharp-any
                        :java.stream-all-match/to-csharp-all
                        :java.stream-none-match/to-csharp-not-any
                        :java.stream-flat-map/to-csharp-select-many
                        :java.stream-map-to-long/to-csharp-select
                        :java.stream-sum/to-csharp-sum
                        :java.stream-to-array/to-csharp-to-array]]
            (is (contains? applied-rules rule))))))))

(deftest emits-supported-reflection-api-as-type-operations
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/reflect/ReflectionApi.java"
            opts {:source/root source-root
                  :project/id "reflection-api"
                  :project/name "Reflection API"}]
        (write-file! source-root file-path reflection-api-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "reflection-api"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/reflect/ReflectionApi.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["using System;"
                           "using System.Reflection;"
                           "public static bool canInstantiate(Type requestedType, Type implementationType)"
                           "requestedType.IsAssignableFrom(implementationType)"
                           "(System.Reflection.TypeAttributes.Abstract & (System.Reflection.TypeAttributes)((int)implementationType.Attributes)) != 0"
                           "!(implementationType.IsArray)"
                           "!(implementationType.IsPrimitive)"
                           "return (typeof(string).FullName ?? typeof(string).Name) + \":\" + typeof(string).Name;"
                           "public static Type? parentType(Type type)"
                           "return type.BaseType;"
                           "public static Type[] typeParameters(Type type)"
                           "return type.GetGenericArguments();"
                           "public static Type? componentType(Type type)"
                           "return type.GetElementType();"
                           "public static bool isEnumType(Type type)"
                           "return type.IsEnum;"
                           "public static Assembly classLoader(Type type)"
                           "return type.Assembly;"
                           "public static Assembly localLoader()"
                           "return typeof(ReflectionApi).Assembly;"
                           "public static bool sameLoader(Type type)"
                           "return ReflectionApi.same(type.Assembly, typeof(ReflectionApi).Assembly);"
                           "private static bool same(Assembly left, Assembly right)"
                           "public static string reflectedTypeName(Type type)"
                           "return (type.FullName ?? type.Name);"
                           "public static Type[] actualArgs(Type type)"
                           "public static Type rawType(Type type)"
                           "return type;"
                           "public static Type? ownerType(Type type)"
                           "return type.DeclaringType;"
                           "public static ParameterInfo[] parameters(ConstructorInfo constructor)"
                           "return constructor.GetParameters();"
                           "public static string parameterName(ParameterInfo parameter)"
                           "return !string.IsNullOrEmpty(parameter.Name) ? parameter.Name : \"\";"]]
            (is (str/includes? content snippet)))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:java.class-type-literal/to-csharp-typeof
                        :java.class-get-type-name/to-csharp-full-name
                        :java.class-get-simple-name/to-csharp-name
                        :java.class-get-modifiers/to-csharp-attributes
                        :java.class-is-assignable-from/to-csharp-is-assignable-from
                        :java.class-is-array/to-csharp-is-array
                        :java.class-is-primitive/to-csharp-is-primitive
                        :java.class-get-generic-superclass/to-csharp-base-type
                        :java.class-get-type-parameters/to-csharp-generic-arguments
                        :java.class-get-component-type/to-csharp-element-type
                        :java.class-is-enum/to-csharp-is-enum
                        :java.class-get-class-loader/to-csharp-assembly
                        :java.type-get-type-name/to-csharp-full-name
                        :java.parameterized-type-get-actual-type-arguments/to-csharp-generic-arguments
                        :java.parameterized-type-get-raw-type/to-csharp-type
                        :java.parameterized-type-get-owner-type/to-csharp-declaring-type
                        :java.reflection-executable-get-parameters/to-csharp-get-parameters
                        :java.reflection-parameter-is-name-present/to-csharp-name-check
                        :java.reflection-parameter-get-name/to-csharp-name
                        :java.modifier-is-abstract/to-csharp-type-attributes]]
            (is (contains? applied-rules rule))))))))

(deftest unsupported-reflection-api-produces-structured-diagnostics
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/reflect/ReflectionUnsupportedApi.java"
            opts {:source/root source-root
                  :project/id "reflection-unsupported-api"
                  :project/name "Reflection Unsupported API"}]
        (write-file! source-root file-path reflection-unsupported-api-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "reflection-unsupported-api"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/reflect/ReflectionUnsupportedApi.cs")
              content (slurp (str generated))
              diagnostic-rules (set (map :rule/id (:csharp/diagnostics result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "Unsupported Java node method-call"))
          (is (= #{:java.class-get-declared-method/unsupported
                   :java.class-get-method/unsupported
                   :java.class-get-declared-methods/unsupported
                   :java.class-get-declared-constructors/unsupported
                   :java.class-get-annotation/unsupported
                   :java.reflection-method-invoke/unsupported
                   :java.reflection-constructor-new-instance/unsupported
                   :java.reflection-constructor-get-annotation/unsupported
                   :java.reflection-parameter-get-annotation/unsupported
                   :java.reflection-wildcard-type-get-lower-bounds/unsupported
                   :java.reflection-wildcard-type-get-upper-bounds/unsupported}
                 diagnostic-rules)))))))

(deftest emits-supported-synchronized-constructs-as-locks
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/sync/SynchronizedCase.java"
            opts {:source/root source-root
                  :project/id "synchronized"
                  :project/name "Synchronized"}]
        (write-file! source-root file-path synchronized-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "synchronized"})
        (rules/register! conn rules/initial-java-rules)
        (let [db (d/db conn)
              coverage (rules/coverage-report db)
              result (csharp/emit! db target)
              generated (.resolve target "com/example/sync/SynchronizedCase.cs")
              content (slurp (str generated))
              applied-rules (set (map (comp second :rule-app/rule)
                                      (:csharp/rule-applications result)))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
            (doseq [snippet ["public int increment()"
                             "lock (this)"
                             "public int add(int amount)"
                             "public static int zero()"
                             "lock (typeof(SynchronizedCase))"
                             "this.value = this.value + amount;"]]
            (is (str/includes? content snippet)))
          (is (= {:ok? true :failures []} coverage))
          (is (empty? (:csharp/diagnostics result)))
          (doseq [rule [:java.synchronized-block-node/to-csharp-lock
                        :java.synchronized-method/to-csharp-lock]]
            (is (contains? applied-rules rule))))))))

(deftest emits-word-counter-statement-and-expression-subset
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/wordcount/WordCounter.java"
            opts {:source/root source-root
                  :project/id "word-count"
                  :project/name "Word Count"}]
        (write-file! source-root file-path (sample-word-counter-source))
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "word-count"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/wordcount/WordCounter.cs")
              content (slurp (str generated))
              rules (set (map (comp second :rule-app/rule)
                              (:csharp/rule-applications result)))
              emitter-source (slurp "src/vibeformer/emit/csharp.clj")]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["using System.Text.RegularExpressions;"
                           "private static readonly Regex WHITESPACE = new Regex(\"\\\\s+\");"
                           "if (args.Length != 1)"
                           "System.Console.Error.WriteLine(\"Usage: WordCounter <file>\");"
                           "System.Environment.Exit(1);"
                           "string input = args[0];"
                           "string text = System.IO.File.ReadAllText(input);"
                           "int words = WordCounter.countWords(text);"
                           "System.Console.WriteLine(words);"
                           "string trimmed = text.Trim();"
                           "if (string.IsNullOrEmpty(trimmed))"
                           "return 0;"
                           "return WordCounter.WHITESPACE.Split(trimmed).Length;"]]
            (is (str/includes? content snippet)))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rules
                      [:java.regex-pattern-compile/to-csharp-regex
                       :java.string-trim/to-csharp-trim
                       :java.string-is-empty/to-csharp-is-null-or-empty
                       :java.regex-split/to-csharp-regex-split
                       :java.printstream-println/to-csharp-console
                       :java.system-exit/to-csharp-environment-exit
                       :java.path-of/to-csharp-string-path
                       :java.files-read-string/to-csharp-file-read-all-text]))
          (testing "provenance is deterministic structural emitter output"
            (let [provenance (:csharp/provenance result)
                  second-result (csharp/emit! (d/db conn) target)
                  word-counter (some #(when (= :java.class-node/to-csharp-class
                                               (get-in % [:rule :rule/id]))
                                        %)
                                     provenance)
                  count-words (some #(when (and (= :java.method-node/to-csharp-method
                                                  (get-in % [:rule :rule/id]))
                                               (= "countWords" (:source/name %)))
                                       %)
                                    provenance)]
              (is (= provenance (:csharp/provenance second-result)))
              (is (= "src/main/java/com/example/wordcount/WordCounter.java"
                     (:source/file word-counter)))
              (is (= :java.node/class (:source/kind word-counter)))
              (is (= 1 (get-in word-counter [:rule :rule/version])))
              (is (some? (:emit/dest-span word-counter)))
              (is (some? count-words))
              (is (= :java.node/method (:source/kind count-words)))
              (is (some? (:emit/dest-span count-words)))
              (is (every? #(and (:emit/source-node %)
                                (:emit/rule %)
                                (:rule %))
                          provenance))
              (doseq [rule-id [:java.regex-pattern-compile/to-csharp-regex
                               :java.string-trim/to-csharp-trim
                               :java.string-is-empty/to-csharp-is-null-or-empty
                               :java.regex-split/to-csharp-regex-split
                               :java.printstream-println/to-csharp-console
                               :java.system-exit/to-csharp-environment-exit
                               :java.path-of/to-csharp-string-path
                               :java.files-read-string/to-csharp-file-read-all-text]]
                (let [entry (some #(when (= rule-id (get-in % [:rule :rule/id])) %)
                                  provenance)]
                  (is (some? entry))
                  (is (= 1 (get-in entry [:rule :rule/version])))
                  (is (= :rule.status/implemented (get-in entry [:rule :rule/status])))
                  (is (= :lang/java (get-in entry [:rule :rule/source-lang])))
                  (is (some? (get-in entry [:rule :rule/input-feature])))))
              (is (not-any? #(contains? % :source/text) provenance))))
          (is (not (str/includes? emitter-source "source-text"))))))))

(deftest unsupported-external-method-calls-produce-diagnostics
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/UnsupportedCall.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path unsupported-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/tools/UnsupportedCall.cs")
              content (slurp (str generated))
              diagnostic (first (:csharp/diagnostics result))
              failed-app (some #(when (= :rule-app.status/failed (:rule-app/status %)) %)
                               (:csharp/rule-applications result))]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "Unsupported Java node method-call"))
          (is (= :java.method-call-node/to-csharp-invocation (:rule/id diagnostic)))
          (is (= :emit.reason/unsupported-external-method
                 (get-in diagnostic [:rule/context :reason])))
          (is (= :java.method-call-node/to-csharp-invocation
                 (second (:rule-app/rule failed-app)))))))))

(deftest ambiguous-length-field-access-produces-diagnostics
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn {:tx-data ambiguous-length-facts})
      (rules/register! conn rules/initial-java-rules)
      (let [target (.resolve (temp-root) "target/csharp")
            result (csharp/emit! (d/db conn) target)
            generated (.resolve target "com/example/tools/LengthLike.cs")
            content (slurp (str generated))
            diagnostic (first (:csharp/diagnostics result))
            failed-app (some #(when (= :rule-app.status/failed (:rule-app/status %)) %)
                             (:csharp/rule-applications result))]
        (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
        (is (str/includes? content "Unsupported Java node field-read"))
        (is (= :java.field-read-node/to-csharp-member (:rule/id diagnostic)))
        (is (= :emit.reason/unsupported-length-target
               (get-in diagnostic [:rule/context :reason])))
        (is (= :java.field-read-node/to-csharp-member
               (second (:rule-app/rule failed-app))))))))
