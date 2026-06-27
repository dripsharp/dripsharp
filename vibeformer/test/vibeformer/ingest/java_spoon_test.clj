(ns vibeformer.ingest.java-spoon-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.util UUID)))

(def java-fixture
  "package com.acme.parser;

import java.util.Locale;

class Base {}

interface GreeterApi {
  String greeting(Locale locale);
}

enum GreetingKind {
  FORMAL
}

public final class Greeter extends Base implements GreeterApi {
  private final String name;
  MissingType missing;

  public Greeter(String name) {
    this.name = name;
  }

  @Override
  public String greeting(Locale locale) {
    String normalized = name.toUpperCase(locale);
    missing.doWork();
    return normalized.trim();
  }
}
")

(def nullable-types-fixture
  "package com.acme.nullable;

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

(def canonical-method-call-fixture
  "package com.acme.calls;

public final class Calls {
  private String name;

  public void chain() {
    name.toUpperCase().trim();
  }

  public long streamCount() {
    return java.util.Arrays.asList(name.trim()).stream().count();
  }

  public Class<?> reflected() throws Exception {
    return Class.forName(name);
  }
}
")

(def dependency-backed-call-fixture
  "package com.acme.deps;

import org.example.Dependency;

public final class UsesDependency {
  public void run(Dependency dependency) {
    dependency.doWork();
  }
}
")

(def control-flow-fixture
  "package com.acme.statements;

import java.util.List;

public final class ControlFlow {
  public int sum(List<Integer> values) {
    int total = 0;
    try {
      for (Integer value : values) {
        total = total + value;
      }
    } catch (IllegalArgumentException ex) {
      total = 0;
    } finally {
      total = total;
    }
    return total;
  }
}
")

(def collection-map-fixture
  "package com.acme.collections;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CollectionMapApi {
  public int summarize(List<String> names) {
    Map<String, Integer> counts = new HashMap<String, Integer>();
    counts.put(\"fallback\", 1);
    for (String name : names) {
      if (counts.containsKey(name)) {
        counts.put(name, counts.get(name) + 1);
      } else {
        counts.put(name, counts.getOrDefault(name, 0) + 1);
      }
    }

    List<Integer> values = new ArrayList<Integer>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      values.add(entry.getValue());
      if (entry.getKey().isEmpty()) {
        values.add(0);
      }
    }
    for (String key : counts.keySet()) {
      values.add(counts.get(key));
    }
    for (Integer value : counts.values()) {
      values.add(value);
    }

    if (values.isEmpty()) {
      return counts.size();
    }
    if (values.contains(0)) {
      return values.get(0);
    }
    return values.size() + counts.get(\"fallback\");
  }
}
")

(def stream-pipeline-fixture
  "package com.acme.stream;

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
  "package com.acme.stream;

import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;

public final class StreamOperations {
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

  public int longest(List<String> names) {
    return names.stream()
        .mapToInt(it -> it.length())
        .max()
        .orElse(0);
  }

  public Optional<Version> compatible(List<Version> versions, Version requested) {
    return versions.stream()
        .filter(it -> it.compareTo(requested) >= 0)
        .min(Comparator.comparing(Version::getVersion));
  }

  public boolean identifierTail(String identifier) {
    return identifier.codePoints()
        .skip(1)
        .allMatch(cp -> cp == '$' || Character.isUnicodeIdentifierPart(cp));
  }

  public Object[] asArray(List<String> names) {
    return names.stream().toArray();
  }
}

final class Version {
  int compareTo(Version other) {
    return 0;
  }

  int getVersion() {
    return 0;
  }
}
")

(def stream-unsupported-collectors-fixture
  "package com.acme.stream;

import java.util.LinkedList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class StreamUnsupportedCollectors {
  public Map<String, String> keyed(List<String> names) {
    return names.stream().collect(Collectors.toMap(it -> it, it -> it));
  }

  public LinkedList<String> linked(List<String> names) {
    return names.stream().collect(Collectors.toCollection(LinkedList::new));
  }

  public LinkedHashSet<String> orderedSet(List<String> names) {
    return names.stream().collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
")

(def code-points-iterator-fixture
  "package com.acme.text;

public final class CodePointIterator {
  public int firstCodePoint(String value) {
    var iterator = value.codePoints().iterator();
    if (iterator.hasNext()) {
      return iterator.nextInt();
    }
    return 0;
  }
}
")

(def pseudo-type-fixture
  "package com.acme.pseudo;

public final class PseudoTypes {
  public void log(MissingDependency missing) {
    var value = missing.make();
  }
}
")

(def reflection-api-fixture
  "package com.acme.reflect;

import java.lang.reflect.Constructor;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Comparator;

public final class ReflectionApi {
  public boolean canInstantiate(Class<?> requestedType, Class<?> implementationType) {
    return requestedType.isAssignableFrom(implementationType)
        && !Modifier.isAbstract(implementationType.getModifiers())
        && !implementationType.isArray()
        && !implementationType.isPrimitive();
  }

  public String typeLabel() {
    return String.class.getTypeName() + \":\" + String.class.getSimpleName();
  }

  public String className(Class<?> type) {
    return type.getName();
  }

  public Type parentType(Class<?> type) {
    return type.getGenericSuperclass();
  }

  public Type[] typeParameters(Class<?> type) {
    return type.getTypeParameters();
  }

  public Type componentType(Class<?> type) {
    return type.getComponentType();
  }

  public boolean isEnumType(Class<?> type) {
    return type.isEnum();
  }

  public ClassLoader classLoader(Class<?> type) {
    return type.getClassLoader();
  }

  public ClassLoader localLoader() {
    return ReflectionApi.class.getClassLoader();
  }

  public String castString(Class<String> type, Object value) {
    return type.cast(value);
  }

  public InputStream resourceStream(Class<?> type, String path) {
    return type.getResourceAsStream(path);
  }

  public Method[] methods(Class<?> type) {
    return type.getDeclaredMethods();
  }

  public Constructor<?>[] constructors(Class<?> type) {
    return type.getDeclaredConstructors();
  }

  public String reflectedTypeName(Type type) {
    return type.getTypeName();
  }

  public Type[] actualArgs(ParameterizedType type) {
    return type.getActualTypeArguments();
  }

  public Type rawType(ParameterizedType type) {
    return type.getRawType();
  }

  public Type ownerType(ParameterizedType type) {
    return type.getOwnerType();
  }

  public Parameter[] parameters(Constructor<?> constructor) {
    return constructor.getParameters();
  }

  public int parameterCount(Constructor<?> constructor) {
    return constructor.getParameterCount();
  }

  public Object widestConstructor(Class<?> type) {
    return Arrays.stream(type.getDeclaredConstructors())
        .max(Comparator.comparingInt(Constructor::getParameterCount));
  }

  public String parameterName(Parameter parameter) {
    return parameter.isNamePresent() ? parameter.getName() : \"\";
  }

  public Type[] lowerBounds(WildcardType wildcardType) {
    return wildcardType.getLowerBounds();
  }

  public Type[] upperBounds(WildcardType wildcardType) {
    return wildcardType.getUpperBounds();
  }

  public Class<?> load(String name) throws Exception {
    return Class.forName(name);
  }
}
")

(def reflection-unsupported-api-fixture
  "package com.acme.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public final class ReflectionUnsupportedApi {
  public Method method(Class<?> type) throws Exception {
    return type.getDeclaredMethod(\"run\");
  }

  public Method publicMethod(Class<?> type) throws Exception {
    return type.getMethod(\"run\");
  }

  public Method[] methods(Class<?> type) {
    return type.getDeclaredMethods();
  }

  public Constructor<?>[] constructors(Class<?> type) {
    return type.getDeclaredConstructors();
  }

  public ClassLoader classLoader(Class<?> type) {
    return type.getClassLoader();
  }

  public Annotation classAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
    return type.getAnnotation(annotationType);
  }

  public Object call(Method method, Object target) throws Exception {
    return method.invoke(target);
  }

  public Object construct(Constructor<?> constructor) throws Exception {
    return constructor.newInstance();
  }

  public Annotation constructorAnnotation(Constructor<?> constructor, Class<? extends Annotation> annotationType) {
    return constructor.getAnnotation(annotationType);
  }

  public Annotation parameterAnnotation(Parameter parameter, Class<? extends Annotation> annotationType) {
    return parameter.getAnnotation(annotationType);
  }
}
")

(def parser-syntax-class-fixture
  "package com.acme.parser;

import org.pkl.parser.syntax.Class;

public final class ParserVisitor {
  public Object visitClass(Class clazz) {
    Object header = clazz.getHeaderSpan();
    Object typeParameters = clazz.getTypeParameterList();
    Object modifiers = clazz.getModifiers();
    Object name = clazz.getName();
    return clazz.getAnnotations();
  }
}
")

(def synchronized-fixture
  "package com.acme.sync;

public final class SynchronizedCase {
  private int value;

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

(def generic-interface-fixture
  "package com.acme.generic;

public interface Box<T> {
  <U> U map(Mapper<T, U> mapper);
}

interface Mapper<T, U> {
  U apply(T value);
}

final class StringBox implements Box<String> {
  public <U> U map(Mapper<String, U> mapper) {
    return mapper.apply(\"\");
  }
}
")

(def nested-generics-fixture
  "package com.acme.generics;

import java.util.ArrayList;
import java.util.List;

public final class NestedGenerics {
  public List<List<String>> emptyGroups() {
    return new ArrayList<List<String>>();
  }

  public List<List<String>> echo(List<List<String>> groups) {
    return groups;
  }
}
")

(def object-creation-fixture
  "package com.acme.objects;

public final class Factory {
  public Holder make(String value) {
    Holder holder = new Holder(value);
    return holder;
  }
}
")

(def object-holder-fixture
  "package com.acme.objects;

public final class Holder {
  Holder(String value) {
  }
}
")

(def numeric-constructor-fixture
  "package com.acme.objects;

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
  "package com.acme.objects;

public final class NullFactory {
  public Holder make() {
    return new Holder(null);
  }
}
")

(def static-import-enum-constant-fixture
  "package com.acme.units;

import static com.acme.units.DataSizeUnit.*;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.patterns;

public final class PatternDemo {
  public static String show(Object value) {
    if (value instanceof Name n) {
      return n.text();
    }

    return \"\";
  }
}

final class Name {
  public String text() {
    return \"\";
  }
}
")

(def negated-pattern-fixture
  "package com.acme.patterns;

public final class PatternDemo {
  public static boolean same(Object value) {
    if (!(value instanceof Name n)) {
      return false;
    }

    return true;
  }
}

final class Name {
}
")

(def conditional-expression-fixture
  "package com.acme.values;

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
  "package com.acme.values;

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
  "package com.acme.errors;

public final class Thrower {
  public static void fail(Object value) {
    throw new IllegalArgumentException(\"Unsupported value: \" + value);
  }
}
")

(def local-call-formatter-fixture
  "package com.acme.localcalls;

public interface Formatter<T> {
  T convert(Name value);
}
")

(def local-call-display-fixture
  "package com.acme.localcalls;

public final class DisplayFormatter implements Formatter<String> {
  public String convert(Name value) {
    return value.text();
  }
}
")

(def local-call-name-fixture
  "package com.acme.localcalls;

public final class Name {
  public String text() {
    return \"pkl\";
  }
}
")

(def local-call-demo-fixture
  "package com.acme.localcalls;

public final class Demo {
  public static String run(Formatter<String> formatter, Name name) {
    return formatter.convert(name);
  }
}
")

(def chained-call-fixture
  "package com.acme.chain;

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

(def switch-expression-fixture
  "package com.acme.tokens;

public enum Token {
  ABSTRACT,
  OPEN,
  LOCAL,
  IDENTIFIER;

  public boolean isModifier() {
    return switch (this) {
      case ABSTRACT, OPEN, LOCAL -> true;
      default -> false;
    };
  }
}
")

(def switch-expression-throw-fixture
  "package com.acme.operators;

public enum Operator {
  NULL_COALESCE,
  PIPE;

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
  "package com.acme.parser;

public record Span(int charIndex, int length) {
  public Span move(int amount) {
    return new Span(charIndex + amount, length);
  }
}
")

(def enum-call-demo-fixture
  "package com.acme.tokens;

public final class Demo {
  public static void main(String[] args) {
    Token.ABSTRACT.isModifier();
  }
}
")

(defn- with-empty-db [f]
  (let [system (str "vibeformer-java-spoon-test-" (UUID/randomUUID))
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
  (Files/createTempDirectory "vibeformer-java-spoon-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- entity-counts [db]
  {:nodes (ffirst (d/q '[:find (count ?node)
                         :where [?node :node/id]]
                       db))
   :decls (ffirst (d/q '[:find (count ?decl)
                         :where [?decl :decl/id]]
                       db))
   :types (ffirst (d/q '[:find (count ?type)
                         :where [?type :type/id]]
                       db))
   :refs (ffirst (d/q '[:find (count ?ref)
                        :where [?ref :ref/id]]
                      db))
   :features (ffirst (d/q '[:find (count ?feature)
                            :where [?feature :feature/id]]
                          db))})

(deftest extracts-normalized-java-facts-with-spoon
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/parser/Greeter.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path java-fixture)
        (source/ingest! conn opts)
        (let [first-run (java-spoon/ingest! conn {:project/id "fixture"})
              db (d/db conn)
              counts (entity-counts db)]
          (is (= {:project/id "fixture"
                  :java-files 1}
                 (select-keys first-run [:project/id :java-files])))
          (is (pos? (:transacted-facts first-run)))

          (testing "classes, interfaces, enums, fields, constructors, and methods are queryable"
            (is (set/subset?
                 #{[:java.node/class "Base" :decl.kind/class "com.acme.parser.Base"]
                   [:java.node/class "Greeter" :decl.kind/class "com.acme.parser.Greeter"]
                   [:java.node/interface "GreeterApi" :decl.kind/interface "com.acme.parser.GreeterApi"]
                   [:java.node/enum "GreetingKind" :decl.kind/enum "com.acme.parser.GreetingKind"]
                   [:java.node/field "name" :decl.kind/field "com.acme.parser.Greeter.name"]
                   [:java.node/field "missing" :decl.kind/field "com.acme.parser.Greeter.missing"]
                   [:java.node/constructor "<init>" :decl.kind/constructor "com.acme.parser.Greeter"]}
                 (set (d/q '[:find ?node-kind ?node-name ?decl-kind ?decl-qname
                             :where
                             [?node :node/file [:file/id "fixture:src/main/java/com/acme/parser/Greeter.java"]]
                             [?node :node/kind ?node-kind]
                             [?node :node/name ?node-name]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?decl :decl/qualified-name ?decl-qname]
                             [(contains? #{:java.node/class
                                           :java.node/interface
                                           :java.node/enum
                                           :java.node/field
                                           :java.node/constructor}
                                          ?node-kind)]]
                           db)))))

          (testing "modifiers, return types, and inheritance refs are queryable"
            (is (= #{[:public] [:final]}
                   (set (d/q '[:find ?modifier
                               :where
                               [?decl :decl/id "java:com.acme.parser.Greeter"]
                               [?decl :decl/modifiers ?modifier]]
                             db))))
            (is (= #{["greeting" "java.lang.String"]}
                   (set (d/q '[:find ?name ?type-id
                               :where
                               [?decl :decl/id "java:com.acme.parser.Greeter#greeting(java.util.Locale)"]
                               [?decl :decl/name ?name]
                               [?decl :decl/return-type ?type]
                               [?type :type/id ?type-id]]
                             db))))
            (is (= #{[:ref.kind/extends "com.acme.parser.Base" true]
                     [:ref.kind/implements "com.acme.parser.GreeterApi" true]}
                   (set (d/q '[:find ?kind ?type-id ?resolved?
                               :where
                               [?ref :ref/from-node ?node]
                               [?node :node/name "Greeter"]
                               [?ref :ref/kind ?kind]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [?ref :ref/resolved? ?resolved?]
                               [(contains? #{:ref.kind/extends :ref.kind/implements} ?kind)]]
                             db)))))

          (testing "parameter type refs retain roles and source names for emission"
            (is (= #{[:param-0 "name" "java.lang.String"]
                     [:param-0 "locale" "java.util.Locale"]}
                   (set (d/q '[:find ?role ?source-name ?type-id
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/role ?role]
                               [?ref :ref/source-name ?source-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [(contains? #{:param-0} ?role)]]
                             db)))))

          (testing "assignments, field writes, and this expressions are normalized"
            (is (= #{[:java.node/assignment "assignment" :body]
                     [:java.node/field-write "name" :left]
                     [:java.node/this "this" :target]}
                   (set (d/q '[:find ?kind ?name ?role
                               :where
                               [?node :node/file [:file/id "fixture:src/main/java/com/acme/parser/Greeter.java"]]
                               [?node :node/kind ?kind]
                               [?node :node/name ?name]
                               [?node :node/role ?role]
                               [(contains? #{:java.node/assignment
                                             :java.node/field-write
                                             :java.node/this}
                                            ?kind)]]
                             db)))))

          (testing "method calls and unresolved missing-classpath references are explicit"
            (is (= #{["toUpperCase" true]
                     ["trim" true]
                     ["doWork" false]}
                   (set (d/q '[:find ?name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/method-call]
                               [?ref :ref/name ?name]
                               [?ref :ref/resolved? ?resolved?]]
                             db))))
            (is (= #{["doWork" :resolve.reason/missing-classpath]
                     ["com.acme.parser.MissingType" :resolve.reason/missing-classpath]}
                   (set (d/q '[:find ?name ?reason
                               :where
                               [?ref :ref/resolved? false]
                               [?ref :ref/name ?name]
                               [?ref :ref/reason ?reason]]
                             db)))))

          (testing "feature facts cover the Java declarations"
            (is (= #{[:java.feature/class :feature.status/supported]
                     [:java.feature/interface :feature.status/supported]
                     [:java.feature/enum :feature.status/supported]
                     [:java.feature/field :feature.status/supported]
                     [:java.feature/package-private-member :feature.status/supported]}
                   (set (d/q '[:find ?kind ?status
                               :where
                               [?feature :feature/kind ?kind]
                               [?feature :feature/status ?status]]
                             db)))))

          (testing "unchanged reruns keep logical fact counts stable"
            (java-spoon/ingest! conn {:project/id "fixture"})
            (is (= counts (entity-counts (d/db conn))))))))))

(deftest java-classpath-package-roots-resolve-dependency-backed-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/deps/UsesDependency.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path dependency-backed-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"
                                  :java/classpath-package-roots #{"org.example"}})
        (let [db (d/db conn)
              reason (fn [ref-eid]
                       (:ref/reason (d/pull db [:ref/reason] ref-eid)))
              type-refs (->> (d/q '[:find ?ref ?type-name ?resolved
                                    :where
                                    [?ref :ref/kind :ref.kind/type-use]
                                    [?ref :ref/source-name "dependency"]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/name ?type-name]
                                    [?ref :ref/resolved? ?resolved]]
                                  db)
                               (map (fn [[ref-eid type-name resolved]]
                                      [type-name resolved (reason ref-eid)]))
                               set)
              method-refs (->> (d/q '[:find ?ref ?name ?owner-name ?resolved
                                      :where
                                      [?ref :ref/kind :ref.kind/method-call]
                                      [?ref :ref/name ?name]
                                      [?ref :ref/owner-type ?owner]
                                      [?owner :type/name ?owner-name]
                                      [?ref :ref/resolved? ?resolved]]
                                    db)
                                (map (fn [[ref-eid name owner-name resolved]]
                                       [name owner-name resolved (reason ref-eid)]))
                                set)
              method-decls (set (d/q '[:find ?decl-id ?name
                                       :where
                                       [?decl :decl/id ?decl-id]
                                       [?decl :decl/name ?name]
                                       [(= ?name "doWork")]]
                                     db))]
          (is (= #{["org.example.Dependency" true nil]} type-refs))
          (is (= #{["doWork" "org.example.Dependency" true nil]} method-refs))
          (is (= #{["java:org.example.Dependency#doWork()" "doWork"]} method-decls)))))))

(deftest extracts-java-nullable-type-use-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/nullable/NullableApi.java"
            opts {:source/root root
                  :project/id "nullable"
                  :project/name "Nullable"}]
        (write-file! root file-path nullable-types-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "nullable"})
        (let [db (d/db conn)]
          (testing "nullable type facts use distinct ids and retain the source type name"
            (is (= #{["java.lang.String?" "java.lang.String" true]}
                   (set (d/q '[:find ?type-id ?type-name ?nullable?
                               :where
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?type :type/nullable? ?nullable?]
                               [(= ?type-id "java.lang.String?")]]
                             db)))))
          (testing "field, parameter, and return refs point at nullable type facts"
            (is (= #{[:field-type :missing "java.lang.String?"]
                     [:param-0 "label" "java.lang.String?"]
                     [:return-type :missing "java.lang.String?"]}
                   (set (d/q '[:find ?role ?source-name ?type-id
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/role ?role]
                               [(get-else $ ?ref :ref/source-name :missing) ?source-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [(contains? #{:field-type :param-0 :return-type} ?role)]
                               [(= ?type-id "java.lang.String?")]]
                             db)))))
          (testing "declarations refer to nullable types where the source type is annotated"
            (is (= #{["label" "java.lang.String?"]}
                   (set (d/q '[:find ?name ?type-id
                               :where
                               [?decl :decl/name ?name]
                               [(= ?name "label")]
                               [?decl :decl/type ?type]
                               [?type :type/id ?type-id]]
                             db))))
            (is (= #{["getLabel" "java.lang.String?"]}
                   (set (d/q '[:find ?name ?type-id
                               :where
                               [?decl :decl/name ?name]
                               [(= ?name "getLabel")]
                               [?decl :decl/return-type ?type]
                               [?type :type/id ?type-id]]
                             db))))))))))

(deftest extracts-generic-interface-type-parameters
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/generic/Box.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path generic-interface-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (testing "declaration type parameters are ordered and queryable"
            (is (= #{["java:com.acme.generic.Box" 0 "T"]
                     ["java:com.acme.generic.Mapper" 0 "T"]
                     ["java:com.acme.generic.Mapper" 1 "U"]
                     ["java:com.acme.generic.Box#map(com.acme.generic.Mapper)" 0 "U"]
                     ["java:com.acme.generic.StringBox#map(com.acme.generic.Mapper)" 0 "U"]}
                   (set (d/q '[:find ?decl-id ?ordinal ?name
                               :where
                               [?decl :decl/type-params ?param]
                               [?decl :decl/id ?decl-id]
                               [?param :type-param/ordinal ?ordinal]
                               [?param :type-param/name ?name]]
                             db)))))
          (testing "parameterized source types retain ordered type arguments"
            (is (= #{["com.acme.generic.Box<java.lang.String>" "com.acme.generic.Box" 0 "java.lang.String"]
                     ["com.acme.generic.Mapper<T,U>" "com.acme.generic.Mapper" 0 "T"]
                     ["com.acme.generic.Mapper<T,U>" "com.acme.generic.Mapper" 1 "U"]
                     ["com.acme.generic.Mapper<java.lang.String,U>" "com.acme.generic.Mapper" 0 "java.lang.String"]
                     ["com.acme.generic.Mapper<java.lang.String,U>" "com.acme.generic.Mapper" 1 "U"]}
                   (set (d/q '[:find ?type-id ?type-name ?ordinal ?arg-name
                               :where
                               [?type :type/args ?arg]
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?arg :type.arg/ordinal ?ordinal]
                               [?arg :type.arg/type ?arg-type]
                               [?arg-type :type/name ?arg-name]]
                             db)))))
          (testing "implements refs point at the parameterized interface type"
            (is (= #{["com.acme.generic.Box<java.lang.String>" true]}
                   (set (d/q '[:find ?type-id ?resolved?
                               :where
                               [?node :node/name "StringBox"]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/implements]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [?ref :ref/resolved? ?resolved?]]
                             db))))))))))

(deftest extracts-object-creation-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/objects/Factory.java"
            holder-path "src/main/java/com/acme/objects/Holder.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path object-creation-fixture)
        (write-file! root holder-path object-holder-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["com.acme.objects.Holder" :java.node/object-creation :initializer]}
                 (set (d/q '[:find ?name ?kind ?role
                             :where
                             [?node :node/kind :java.node/object-creation]
                             [?node :node/name ?name]
                             [?node :node/kind ?kind]
                             [?node :node/role ?role]]
                           db))))
          (is (= #{["com.acme.objects.Holder" "com.acme.objects.Holder" true]}
                 (set (d/q '[:find ?name ?type-id ?resolved?
                             :where
                             [?node :node/kind :java.node/object-creation]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/constructor-call]
                             [?ref :ref/name ?name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?ref :ref/resolved? ?resolved?]]
                           db)))))))))

(deftest resolves-project-local-constructor-calls-by-owner-and-arity
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/objects/NumericFactory.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path numeric-constructor-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["com.acme.objects.DataSize"
                    "com.acme.objects.DataSize"
                    "java:com.acme.objects.DataSize(double)"
                    true]}
                 (set (d/q '[:find ?name ?type-id ?decl-id ?resolved?
                             :where
                             [?node :node/kind :java.node/object-creation]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/constructor-call]
                             [?ref :ref/name ?name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?ref :ref/to-decl ?decl]
                             [?decl :decl/id ?decl-id]
                             [?decl :decl/source-node]
                             [?ref :ref/resolved? ?resolved?]]
                           db)))))))))

(deftest extracts-null-literal-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/objects/NullFactory.java"
            holder-path "src/main/java/com/acme/objects/Holder.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path null-literal-fixture)
        (write-file! root holder-path object-holder-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["null" "nil" :argument]}
                 (set (d/q '[:find ?name ?value ?role
                             :where
                             [?node :node/kind :java.node/literal]
                             [?node :node/name ?name]
                             [?node :node/value ?value]
                             [?node :node/role ?role]]
                           db)))))))))

(deftest resolves-static-imported-enum-constant-field-refs
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/units/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path static-import-enum-constant-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["BYTES"
                    "java:com.acme.units.DataSizeUnit#field:BYTES"
                    "com.acme.units.DataSizeUnit"
                    "com.acme.units.DataSizeUnit"
                    true]}
                 (set (d/q '[:find ?name ?decl-id ?owner-name ?type-name ?resolved?
                             :where
                             [?node :node/kind :java.node/field-read]
                             [?node :node/name "BYTES"]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/field-access]
                             [?ref :ref/name ?name]
                             [?ref :ref/resolved? ?resolved?]
                             [?ref :ref/to-decl ?decl]
                             [?decl :decl/id ?decl-id]
                             [?decl :decl/source-node]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/name ?type-name]]
                           db)))))))))

(deftest extracts-objects-require-non-null-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path require-non-null-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["requireNonNull"
                    "java.util.Objects"
                    :java.api/objects-require-non-null
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "requireNonNull"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-objects-equals-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/Version.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path objects-equals-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["equals"
                    "java.util.Objects"
                    :java.api/objects-equals
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "equals"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-objects-hash-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/Version.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path objects-hash-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["hash"
                    "java.util.Objects"
                    :java.api/objects-hash
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "hash"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-math-round-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path math-round-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["round"
                    "java.lang.Math"
                    :java.api/math-round
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "round"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-math-min-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/Version.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path math-min-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["min"
                    "java.lang.Math"
                    :java.api/math-min
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "min"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-math-max-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/Version.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path math-max-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["max"
                    "java.lang.Math"
                    :java.api/math-max
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "max"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-double-hash-code-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path double-hash-code-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["hashCode"
                    "java.lang.Double"
                    :java.api/double-hash-code
                    :feature.status/supported]}
                 (set (d/q '[:find ?method-name ?owner-name ?feature-kind ?feature-status
                             :where
                             [?node :node/kind :java.node/method-call]
                             [?node :node/name "hashCode"]
                             [?node :node/name ?method-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]
                             [?feature :feature/status ?feature-status]]
                           db)))))))))

(deftest extracts-instanceof-type-pattern-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/patterns/PatternDemo.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path pattern-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["n" :java.node/type-pattern :right "com.acme.patterns.Name"]}
                 (set (d/q '[:find ?name ?kind ?role ?type-id
                             :where
                             [?node :node/kind :java.node/type-pattern]
                             [?node :node/name ?name]
                             [?node :node/kind ?kind]
                             [?node :node/role ?role]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/role :pattern-type]
                             [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]]
                             db)))))))))

(deftest extracts-nested-generic-type-arguments
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/generics/NestedGenerics.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path nested-generics-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["java.util.List<java.util.List<java.lang.String>>" "java.util.List" 0 "java.util.List<java.lang.String>"]
                   ["java.util.List<java.lang.String>" "java.util.List" 0 "java.lang.String"]
                   ["java.util.ArrayList<java.util.List<java.lang.String>>" "java.util.ArrayList" 0 "java.util.List<java.lang.String>"]}
                 (set (d/q '[:find ?type-id ?type-name ?ordinal ?arg-id
                             :where
                             [?type :type/args ?arg]
                             [?type :type/id ?type-id]
                             [?type :type/name ?type-name]
                             [?arg :type.arg/ordinal ?ordinal]
                             [?arg :type.arg/type ?arg-type]
                             [?arg-type :type/id ?arg-id]
                             [(contains? #{"java.util.List<java.util.List<java.lang.String>>"
                                           "java.util.List<java.lang.String>"
                                           "java.util.ArrayList<java.util.List<java.lang.String>>"}
                                          ?type-id)]]
                           db)))))))))

(deftest extracts-logical-not-unary-expression-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/patterns/PatternDemo.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path negated-pattern-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["not" "not" :java.node/unary-operator :condition
                    "instanceof" :java.node/binary-operator :operand
                    "n" :java.node/type-pattern :right
                    "com.acme.patterns.Name"]}
                 (set (d/q '[:find ?unary-name ?unary-value ?unary-kind ?unary-role
                                    ?operand-value ?operand-kind ?operand-role
                                    ?pattern-name ?pattern-kind ?pattern-role
                                    ?pattern-type
                             :where
                             [?unary :node/kind :java.node/unary-operator]
                             [?unary :node/name ?unary-name]
                             [?unary :node/value ?unary-value]
                             [?unary :node/kind ?unary-kind]
                             [?unary :node/role ?unary-role]
                             [?operand :node/parent ?unary]
                             [?operand :node/role ?operand-role]
                             [?operand :node/value ?operand-value]
                             [?operand :node/kind ?operand-kind]
                             [?pattern :node/parent ?operand]
                             [?pattern :node/kind :java.node/type-pattern]
                             [?pattern :node/name ?pattern-name]
                             [?pattern :node/kind ?pattern-kind]
                             [?pattern :node/role ?pattern-role]
                             [?ref :ref/from-node ?pattern]
                             [?ref :ref/role :pattern-type]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?pattern-type]]
                           db)))))))))

(deftest extracts-conditional-expression-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path conditional-expression-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["conditional" "?:" :java.node/conditional-expression :return-expression
                    "eq" :condition
                    "\"byte\"" :then-expression
                    "\"bytes\"" :else-expression]}
                 (set (d/q '[:find ?conditional-name ?conditional-value ?conditional-kind ?conditional-role
                                    ?condition-value ?condition-role
                                    ?then-value ?then-role
                                    ?else-value ?else-role
                             :where
                             [?conditional :node/kind :java.node/conditional-expression]
                             [?conditional :node/name ?conditional-name]
                             [?conditional :node/value ?conditional-value]
                             [?conditional :node/kind ?conditional-kind]
                             [?conditional :node/role ?conditional-role]
                             [?condition :node/parent ?conditional]
                             [?condition :node/role :condition]
                             [?condition :node/role ?condition-role]
                             [?condition :node/value ?condition-value]
                             [?then :node/parent ?conditional]
                             [?then :node/role :then-expression]
                             [?then :node/role ?then-role]
                             [?then :node/value ?then-value]
                             [?else :node/parent ?conditional]
                             [?else :node/role :else-expression]
                             [?else :node/role ?else-role]
                             [?else :node/value ?else-value]]
                           db)))))))))

(deftest extracts-type-cast-expression-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/values/DataSize.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path type-cast-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["cast" "long" :java.node/type-cast :left
                    "value" :java.node/field-read :operand
                    "long"]}
                 (set (d/q '[:find ?cast-name ?cast-value ?cast-kind ?cast-role
                                    ?operand-name ?operand-kind ?operand-role
                                    ?cast-type
                             :where
                             [?cast :node/kind :java.node/type-cast]
                             [?cast :node/name ?cast-name]
                             [?cast :node/value ?cast-value]
                             [?cast :node/kind ?cast-kind]
                             [?cast :node/role ?cast-role]
                             [?operand :node/parent ?cast]
                             [?operand :node/name ?operand-name]
                             [?operand :node/kind ?operand-kind]
                             [?operand :node/role ?operand-role]
                             [?ref :ref/from-node ?cast]
                             [?ref :ref/role :cast-type]
                             [?ref :ref/to-type ?type]
                             [?type :type/name ?cast-type]]
                           db)))))))))

(deftest extracts-throw-statement-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/errors/Thrower.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path throw-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{[:java.node/throw-statement "throw" :body
                   :java.node/object-creation :thrown-expression
                   "java.lang.IllegalArgumentException"]}
                 (set (d/q '[:find ?throw-kind ?throw-name ?throw-role
                                    ?expr-kind ?expr-role ?type-id
                             :where
                             [?throw :node/kind :java.node/throw-statement]
                             [?throw :node/kind ?throw-kind]
                             [?throw :node/name ?throw-name]
                             [?throw :node/role ?throw-role]
                             [?expr :node/parent ?throw]
                             [?expr :node/kind ?expr-kind]
                             [?expr :node/role ?expr-role]
                             [?ref :ref/from-node ?expr]
                             [?ref :ref/kind :ref.kind/constructor-call]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]]
                           db)))))))))

(deftest extracts-foreach-and-try-statement-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/statements/ControlFlow.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path control-flow-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{[:java.node/try-statement "try" :body]
                   [:java.node/foreach-statement "value" :body]
                   [:java.node/catch-clause "ex" :catch]}
                 (set (d/q '[:find ?kind ?name ?role
                             :where
                             [?node :node/kind ?kind]
                             [?node :node/name ?name]
                             [?node :node/role ?role]
                             [(contains? #{:java.node/try-statement
                                           :java.node/foreach-statement
                                           :java.node/catch-clause}
                                         ?kind)]]
                           db))))
          (is (= #{["value" "java.lang.Integer"]}
                 (set (d/q '[:find ?source-name ?type-name
                             :where
                             [?node :node/kind :java.node/foreach-statement]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/role :element-type]
                             [?ref :ref/source-name ?source-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/name ?type-name]]
                           db))))
          (is (= #{["ex" "java.lang.IllegalArgumentException"]}
                 (set (d/q '[:find ?source-name ?type-name
                             :where
                             [?node :node/kind :java.node/catch-clause]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/role :catch-type]
                             [?ref :ref/source-name ?source-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/name ?type-name]]
                           db))))
          (is (= #{[:java.node/variable-read :iterable]
                   [:java.node/assignment :body]
                   [:java.node/assignment :finally]}
                 (set (d/q '[:find ?kind ?role
                             :where
                             [?node :node/kind ?kind]
                             [?node :node/role ?role]
                             [(contains? #{:iterable :body :finally} ?role)]
                             [(contains? #{:java.node/variable-read
                                           :java.node/assignment}
                                         ?kind)]]
                           db)))))))))

(deftest extracts-collection-and-map-api-feature-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/collections/CollectionMapApi.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path collection-map-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              feature-kinds (set (map first
                                      (d/q '[:find ?kind
                                             :where
                                             [?feature :feature/kind ?kind]]
                                           db)))
              entry-types (set (d/q '[:find ?type-name
                                      :where
                                      [?ref :ref/role :element-type]
                                      [?ref :ref/to-type ?type]
                                      [?type :type/name ?type-name]]
                                    db))]
          (is (every? feature-kinds
                      [:java.collection/size
                       :java.collection/is-empty
                       :java.collection/contains
                       :java.collection/add
                       :java.list/get
                       :java.map/get
                       :java.map/put
                       :java.map/get-or-default
                       :java.map/contains-key
                       :java.map/entry-set
                       :java.map/key-set
                       :java.map/values
                       :java.map-entry/get-key
                       :java.map-entry/get-value]))
          (is (contains? entry-types ["java.util.Map$Entry"])))))))

(deftest resolves-project-local-method-call-refs
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root "src/main/java/com/acme/localcalls/Formatter.java" local-call-formatter-fixture)
        (write-file! root "src/main/java/com/acme/localcalls/DisplayFormatter.java" local-call-display-fixture)
        (write-file! root "src/main/java/com/acme/localcalls/Name.java" local-call-name-fixture)
        (write-file! root "src/main/java/com/acme/localcalls/Demo.java" local-call-demo-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["convert" "java:com.acme.localcalls.Formatter#convert(com.acme.localcalls.Name)" true]
                   ["text" "java:com.acme.localcalls.Name#text()" true]}
                 (set (d/q '[:find ?name ?decl-id ?resolved?
                             :where
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/name ?name]
                             [(contains? #{"convert" "text"} ?name)]
                             [?ref :ref/resolved? ?resolved?]
                             [?ref :ref/to-decl ?decl]
                             [?decl :decl/id ?decl-id]
                             [?decl :decl/source-node]]
                           db)))))))))

(deftest resolves-chained-project-local-method-call-refs
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/chain/Chain.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path chained-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["move" "java:com.acme.chain.Chain#move(int)" "com.acme.chain.Chain" true]
                   ["grow" "java:com.acme.chain.Chain#grow(int)" "com.acme.chain.Chain" true]}
                 (set (d/q '[:find ?name ?decl-id ?owner-name ?resolved?
                             :where
                             [?call :node/kind :java.node/method-call]
                             [?call :node/name ?name]
                             [(contains? #{"move" "grow"} ?name)]
                             [?ref :ref/from-node ?call]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/name ?name]
                             [?ref :ref/resolved? ?resolved?]
                             [?ref :ref/to-decl ?decl]
                             [?decl :decl/id ?decl-id]
                             [?decl :decl/source-node]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]]
                           db)))))))))

(deftest resolves-enum-constant-method-call-refs
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root "src/main/java/com/acme/tokens/Token.java" switch-expression-fixture)
        (write-file! root "src/main/java/com/acme/tokens/Demo.java" enum-call-demo-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["isModifier"
                    "java:com.acme.tokens.Token#isModifier()"
                    "com.acme.tokens.Token"
                    true]}
                 (set (d/q '[:find ?name ?decl-id ?owner-name ?resolved?
                             :where
                             [?call :node/kind :java.node/method-call]
                             [?call :node/name "isModifier"]
                             [?ref :ref/from-node ?call]
                             [?ref :ref/kind :ref.kind/method-call]
                             [?ref :ref/name ?name]
                             [?ref :ref/resolved? ?resolved?]
                             [?ref :ref/to-decl ?decl]
                             [?decl :decl/id ?decl-id]
                             [?decl :decl/source-node]
                             [?ref :ref/owner-type ?owner]
                             [?owner :type/name ?owner-name]]
                           db)))))))))

(deftest extracts-switch-expression-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/tokens/Token.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path switch-expression-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["switch" :return-expression]}
                 (set (d/q '[:find ?name ?role
                             :where
                             [?node :node/kind :java.node/switch-expression]
                             [?node :node/name ?name]
                             [?node :node/role ?role]]
                           db))))
          (is (= #{[0 "case" :case "arrow"]
                   [1 "default" :case "arrow"]}
                 (set (d/q '[:find ?ordinal ?name ?role ?value
                             :where
                             [?node :node/kind :java.node/switch-case]
                             [?node :node/ordinal ?ordinal]
                             [?node :node/name ?name]
                             [?node :node/role ?role]
                             [?node :node/value ?value]]
                           db))))
          (is (= #{["this" :selector]}
                 (set (d/q '[:find ?name ?role
                             :where
                             [?switch :node/kind :java.node/switch-expression]
                             [?child :node/parent ?switch]
                             [?child :node/name ?name]
                             [?child :node/role ?role]
                             [(= :selector ?role)]]
                           db))))
          (is (= #{[0 "ABSTRACT" :case-label]
                   [0 "OPEN" :case-label]
                   [0 "LOCAL" :case-label]
                   [0 "Boolean" :case-result]
                   [1 "Boolean" :case-result]}
                 (set (d/q '[:find ?case-ordinal ?name ?role
                             :where
                             [?switch :node/kind :java.node/switch-expression]
                             [?case :node/parent ?switch]
                             [?case :node/kind :java.node/switch-case]
                             [?case :node/ordinal ?case-ordinal]
                             [?child :node/parent ?case]
                             [?child :node/name ?name]
                             [?child :node/role ?role]]
                           db)))))))))

(deftest extracts-switch-default-throw-result-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/operators/Operator.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path switch-expression-throw-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{[2 "default" :case "arrow"
                    :java.node/throw-statement :case-result
                    :java.node/object-creation :thrown-expression
                    "java.lang.RuntimeException"]}
                 (set (d/q '[:find ?case-ordinal ?case-name ?case-role ?case-value
                                    ?throw-kind ?throw-role
                                    ?expr-kind ?expr-role ?type-id
                             :where
                             [?case :node/kind :java.node/switch-case]
                             [?case :node/ordinal ?case-ordinal]
                             [?case :node/name ?case-name]
                             [?case :node/role ?case-role]
                             [?case :node/value ?case-value]
                             [?throw :node/parent ?case]
                             [?throw :node/kind ?throw-kind]
                             [?throw :node/kind :java.node/throw-statement]
                             [?throw :node/role ?throw-role]
                             [?expr :node/parent ?throw]
                             [?expr :node/kind ?expr-kind]
                             [?expr :node/role ?expr-role]
                             [?ref :ref/from-node ?expr]
                             [?ref :ref/kind :ref.kind/constructor-call]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]]
                           db)))))))))

(deftest extracts-java-record-component-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/parser/Span.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path record-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (is (= #{["Span" :java.node/record :decl.kind/record :java.feature/record]}
                 (set (d/q '[:find ?name ?node-kind ?decl-kind ?feature-kind
                             :where
                             [?node :node/kind :java.node/record]
                             [?node :node/name ?name]
                             [?node :node/kind ?node-kind]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?feature :feature/node ?node]
                             [?feature :feature/kind ?feature-kind]]
                           db))))
          (is (= #{["charIndex" :java.node/record-component :decl.kind/record-component "int"]
                   ["length" :java.node/record-component :decl.kind/record-component "int"]}
                 (set (d/q '[:find ?name ?node-kind ?decl-kind ?type-id
                             :where
                             [?node :node/kind :java.node/record-component]
                             [?node :node/name ?name]
                             [?node :node/kind ?node-kind]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?decl :decl/type ?type]
                             [?type :type/id ?type-id]]
                           db))))
          (is (= #{["move"]}
                 (set (d/q '[:find ?name
                             :where
                             [?node :node/kind :java.node/method]
                             [?node :node/name ?name]]
                           db)))))))))

(deftest canonicalizes-method-call-source-nodes
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/calls/Calls.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path canonical-method-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              expected-call-counts #{["asList" 1]
                                     ["count" 1]
                                     ["forName" 1]
                                     ["stream" 1]
                                     ["toUpperCase" 1]
                                     ["trim" 2]}]
          (testing "each source invocation has one canonical method-call node"
            (is (= expected-call-counts
                   (set (d/q '[:find ?name (count ?node)
                               :where
                               [?node :node/kind :java.node/method-call]
                               [?node :node/name ?name]]
                             db))))
            (is (empty?
                 (d/q '[:find ?node-id ?name
                        :where
                        [?node :node/kind :java.node/method-call]
                        [?node :node/id ?node-id]
                        [?node :node/name ?name]
                        (not [?node :node/role])]
                      db))))

          (testing "method-call refs attach to the canonical structural nodes"
            (is (= expected-call-counts
                   (set (d/q '[:find ?name (count ?ref)
                               :where
                               [?node :node/kind :java.node/method-call]
                               [?node :node/name ?name]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/method-call]]
                             db)))))

          (testing "invocation feature facts attach to canonical method-call nodes"
            (is (set/subset?
                 #{[:java.reflection.class/for-name "forName" :java.node/method-call]
                   [:java.stream/source-to-enumerable "stream" :java.node/method-call]}
                                   (set (d/q '[:find ?kind ?node-name ?node-kind
                             :where
                             [?feature :feature/kind ?kind]
                             [(contains? #{:java.reflection.class/for-name
                                           :java.stream/source-to-enumerable}
                                          ?kind)]
                             [?feature :feature/node ?node]
                             [?node :node/name ?node-name]
                             [?node :node/kind ?node-kind]]
                           db))))))))))

(deftest classifies-supported-stream-pipeline-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/stream/StreamPipeline.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path stream-pipeline-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)]
          (testing "lambda expressions are normalized as source nodes with expression bodies"
            (let [lambda-parents (set (d/q '[:find ?lambda-value ?parent-name ?parent-kind
                                             :where
                                             [?lambda :node/kind :java.node/lambda]
                                             [?lambda :node/value ?lambda-value]
                                             [?lambda :node/parent ?parent]
                                             [?parent :node/name ?parent-name]
                                             [?parent :node/kind ?parent-kind]]
                                           db))
                  body-names (set (map first
                                       (d/q '[:find ?body-name
                                              :where
                                              [?lambda :node/kind :java.node/lambda]
                                              [?body :node/parent ?lambda]
                                              [?body :node/role :body-expression]
                                              [?body :node/name ?body-name]]
                                            db)))
                  nested-body-names (set (map first
                                              (d/q '[:find ?child-name
                                                     :where
                                                     [?lambda :node/kind :java.node/lambda]
                                                     [?body :node/parent ?lambda]
                                                     [?body :node/role :body-expression]
                                                     [?child :node/parent ?body]
                                                     [?child :node/name ?child-name]]
                                                   db)))]
              (is (= #{["it" "filter" :java.node/method-call]
                       ["it" "map" :java.node/method-call]}
                     lambda-parents))
              (is (= #{"not" "trim"} body-names))
              (is (= #{"it" "isEmpty"} nested-body-names))))
          (testing "stream API facts are split into supported precise features"
            (let [stream-features (set (d/q '[:find ?kind ?node-name ?status
                                              :where
                                              [?feature :feature/kind ?kind]
                                              [(contains? #{:java.stream/source-to-enumerable
                                                            :java.stream/filter
                                                            :java.stream/map
                                                            :java.stream/collect-to-list
                                                            :java.stream.collector/to-list
                                                            :java.feature/stream-api
                                                            :java.stream/collect}
                                                           ?kind)]
                                              [?feature :feature/status ?status]
                                              [?feature :feature/node ?node]
                                              [?node :node/name ?node-name]]
                                            db))
                  broad-unsupported (d/q '[:find ?kind
                                           :where
                                           [?feature :feature/kind ?kind]
                                           [(contains? #{:java.feature/stream-api
                                                         :java.stream/collect}
                                                        ?kind)]]
                                         db)]
                (is (= #{[:java.stream/source-to-enumerable "stream" :feature.status/supported]
                         [:java.stream/filter "filter" :feature.status/supported]
                         [:java.stream/map "map" :feature.status/supported]
                         [:java.stream/collect-to-list "collect" :feature.status/supported]
                         [:java.stream.collector/to-list "toList" :feature.status/supported]}
                        stream-features))
                (is (empty? broad-unsupported)))))))))

(deftest classifies-stream-operation-and-collector-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/stream/StreamOperations.java"
            unsupported-path "src/main/java/com/acme/stream/StreamUnsupportedCollectors.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path stream-operations-fixture)
        (write-file! root unsupported-path stream-unsupported-collectors-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              supported (set (d/q '[:find ?kind ?node-name ?status
                                     :where
                                     [?feature :feature/kind ?kind]
                                     [(contains? #{:java.stream/flat-map
                                                   :java.stream/map-to-int
                                                   :java.stream/map-to-long
                                                   :java.stream/to-array
                                                   :java.stream/sum
                                                   :java.stream/min
                                                   :java.stream/max
                                                   :java.stream/skip
                                                   :java.stream/any-match
                                                   :java.stream/all-match
                                                   :java.stream/none-match
                                                   :java.stream/distinct
                                                   :java.stream/sorted
                                                   :java.optional/or-else
                                                   :java.stream/collect-to-set
                                                   :java.stream/collect-joining
                                                   :java.stream/collect-to-map
                                                   :java.stream/collect-to-collection
                                                   :java.stream.collector/to-set
                                                   :java.stream.collector/joining
                                                   :java.stream.collector/to-map
                                                   :java.stream.collector/to-collection}
                                                  ?kind)]
                                     [?feature :feature/status ?status]
                                     [?feature :feature/node ?node]
                                     [?node :node/name ?node-name]]
                                   db))
              unsupported (set (d/q '[:find ?kind ?node-name ?status
                                       :where
                                       [?feature :feature/kind ?kind]
                                       [(contains? #{:java.feature/stream-api
                                                     :java.stream/collect}
                                                    ?kind)]
                                       [?feature :feature/status ?status]
                                       [?feature :feature/node ?node]
                                       [?node :node/name ?node-name]]
                                     db))
              constructor-refs (set (d/q '[:find ?name ?value ?role ?target-type
                                           :where
                                           [?node :node/kind :java.node/method-reference]
                                           [?node :node/name ?name]
                                           [?node :node/value ?value]
                                           [?node :node/role ?role]
                                           [?ref :ref/from-node ?node]
                                           [?ref :ref/kind :ref.kind/type-use]
                                           [?ref :ref/role :method-reference-target-type]
                                           [?ref :ref/to-type ?type]
                                           [?type :type/id ?target-type]]
                                         db))]
          (is (= #{[:java.stream/distinct "distinct" :feature.status/supported]
                   [:java.stream/sorted "sorted" :feature.status/supported]
                   [:java.stream/collect-to-set "collect" :feature.status/supported]
                   [:java.stream/collect-joining "collect" :feature.status/supported]
                   [:java.stream/collect-to-map "collect" :feature.status/supported]
                   [:java.stream/collect-to-collection "collect" :feature.status/supported]
                   [:java.stream.collector/to-set "toSet" :feature.status/supported]
                   [:java.stream.collector/joining "joining" :feature.status/supported]
                   [:java.stream.collector/to-map "toMap" :feature.status/supported]
                   [:java.stream.collector/to-collection "toCollection" :feature.status/supported]
                   [:java.stream/any-match "anyMatch" :feature.status/supported]
                   [:java.stream/all-match "allMatch" :feature.status/supported]
                   [:java.stream/none-match "noneMatch" :feature.status/supported]
                   [:java.stream/flat-map "flatMap" :feature.status/supported]
                   [:java.stream/map-to-int "mapToInt" :feature.status/supported]
                   [:java.stream/map-to-long "mapToLong" :feature.status/supported]
                   [:java.stream/sum "sum" :feature.status/supported]
                   [:java.stream/min "min" :feature.status/supported]
                   [:java.stream/max "max" :feature.status/supported]
                   [:java.stream/skip "skip" :feature.status/supported]
                   [:java.optional/or-else "orElse" :feature.status/supported]
                   [:java.stream/to-array "toArray" :feature.status/supported]}
                 supported))
          (is (empty? unsupported))
          (is (set/subset?
               #{["new" "java.util.LinkedList" :argument "java.util.LinkedList"]
                 ["new" "java.util.LinkedHashSet" :argument "java.util.LinkedHashSet"]}
               constructor-refs)))))))

(deftest classifies-code-points-iterator-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/text/CodePointIterator.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path code-points-iterator-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              supported (set (d/q '[:find ?kind ?node-name ?status
                                     :where
                                     [?feature :feature/kind ?kind]
                                     [(contains? #{:java.api/string-code-points
                                                   :java.stream/iterator
                                                   :java.iterator/has-next
                                                   :java.primitive-iterator/next-int}
                                                  ?kind)]
                                     [?feature :feature/status ?status]
                                     [?feature :feature/node ?node]
                                     [?node :node/name ?node-name]]
                                   db))
              unsupported (set (d/q '[:find ?kind ?node-name ?status
                                       :where
                                       [?feature :feature/kind ?kind]
                                       [(contains? #{:java.feature/stream-api}
                                                    ?kind)]
                                       [?feature :feature/status ?status]
                                       [?feature :feature/node ?node]
                                       [?node :node/name ?node-name]]
                                     db))]
          (is (= #{[:java.api/string-code-points "codePoints" :feature.status/supported]
                   [:java.stream/iterator "iterator" :feature.status/supported]
                   [:java.iterator/has-next "hasNext" :feature.status/supported]
                   [:java.primitive-iterator/next-int "nextInt" :feature.status/supported]}
                 supported))
          (is (empty? unsupported)))))))

(deftest java-pseudo-types-do-not-block-unresolved-reference-gate
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/pseudo/PseudoTypes.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path pseudo-type-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              type-refs (set (d/q '[:find ?role ?name ?type-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/type-use]
                                    [?ref :ref/role ?role]
                                    [?ref :ref/name ?name]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/resolved? ?resolved?]]
                                  db))
              unresolved-details (:unresolved-ref-detail-rankings (inventory/summary db))]
          (testing "Spoon var fallback and void return types are modeled as resolved pseudo/built-in refs"
            (is (contains? type-refs [:local-type "var" "var" true]))
            (is (contains? type-refs [:return-type "void" "void" true])))
          (testing "missing classpath refs still surface without pseudo-type noise"
            (is (= [{:lang :lang/java
                     :kind :ref.kind/method-call
                     :name "make"
                     :owner "com.acme.pseudo.MissingDependency"
                     :reason :resolve.reason/missing-classpath
                     :count 1
                     :file-count 1}
                    {:lang :lang/java
                     :kind :ref.kind/type-use
                     :name "com.acme.pseudo.MissingDependency"
                     :owner ""
                     :reason :resolve.reason/missing-classpath
                     :count 1
                     :file-count 1}]
                   unresolved-details))
            (is (not-any? #(contains? #{"var" "void"} (:name %))
                          unresolved-details))))))))

(deftest classifies-supported-reflection-api-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/reflect/ReflectionApi.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path reflection-api-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              reflection-features
              (set (d/q '[:find ?kind ?node-name ?status
                          :where
                          [?feature :feature/kind ?kind]
                          [(contains? #{:java.reflection.class/type-literal
                                        :java.reflection.class/get-type-name
                                        :java.reflection.class/get-name
                                        :java.reflection.class/get-simple-name
                                        :java.reflection.class/get-modifiers
                                        :java.reflection.class/is-assignable-from
                                        :java.reflection.class/is-array
                                        :java.reflection.class/is-primitive
                                        :java.reflection.class/get-generic-superclass
                                        :java.reflection.class/get-type-parameters
                                        :java.reflection.class/get-component-type
                                        :java.reflection.class/is-enum
                                        :java.reflection.class/get-class-loader
                                        :java.reflection.class/cast
                                        :java.reflection.class/get-resource-as-stream
                                        :java.reflection.class/get-declared-methods
                                        :java.reflection.class/get-declared-constructors
                                        :java.reflection.type/get-type-name
                                        :java.reflection.parameterized-type/get-actual-type-arguments
                                        :java.reflection.parameterized-type/get-raw-type
                                        :java.reflection.parameterized-type/get-owner-type
                                        :java.reflection.wildcard-type/get-lower-bounds
                                        :java.reflection.wildcard-type/get-upper-bounds
                                        :java.reflection.constructor/get-parameter-count
                                        :java.reflection.executable/get-parameters
                                        :java.reflection.parameter/is-name-present
                                        :java.reflection.parameter/get-name
                                        :java.reflection.modifier/is-abstract
                                        :java.reflection.class/for-name
                                        :java.feature/reflection}
                                       ?kind)]
                          [?feature :feature/status ?status]
                          [?feature :feature/node ?node]
                          [?node :node/name ?node-name]]
                        db))]
          (is (= #{[:java.reflection.class/type-literal "class" :feature.status/supported]
                   [:java.reflection.class/get-type-name "getTypeName" :feature.status/supported]
                   [:java.reflection.class/get-name "getName" :feature.status/supported]
                   [:java.reflection.class/get-simple-name "getSimpleName" :feature.status/supported]
                   [:java.reflection.class/get-modifiers "getModifiers" :feature.status/supported]
                   [:java.reflection.class/is-assignable-from "isAssignableFrom" :feature.status/supported]
                   [:java.reflection.class/is-array "isArray" :feature.status/supported]
                   [:java.reflection.class/is-primitive "isPrimitive" :feature.status/supported]
                   [:java.reflection.class/get-generic-superclass "getGenericSuperclass" :feature.status/supported]
                   [:java.reflection.class/get-type-parameters "getTypeParameters" :feature.status/supported]
                   [:java.reflection.class/get-component-type "getComponentType" :feature.status/supported]
                   [:java.reflection.class/is-enum "isEnum" :feature.status/supported]
                   [:java.reflection.class/get-class-loader "getClassLoader" :feature.status/supported]
                   [:java.reflection.class/cast "cast" :feature.status/supported]
                   [:java.reflection.class/get-resource-as-stream "getResourceAsStream" :feature.status/supported]
                   [:java.reflection.class/get-declared-methods "getDeclaredMethods" :feature.status/supported]
                   [:java.reflection.class/get-declared-constructors "getDeclaredConstructors" :feature.status/supported]
                   [:java.reflection.type/get-type-name "getTypeName" :feature.status/supported]
                   [:java.reflection.parameterized-type/get-actual-type-arguments "getActualTypeArguments" :feature.status/supported]
                   [:java.reflection.parameterized-type/get-raw-type "getRawType" :feature.status/supported]
                   [:java.reflection.parameterized-type/get-owner-type "getOwnerType" :feature.status/supported]
                   [:java.reflection.wildcard-type/get-lower-bounds "getLowerBounds" :feature.status/supported]
                   [:java.reflection.wildcard-type/get-upper-bounds "getUpperBounds" :feature.status/supported]
                   [:java.reflection.constructor/get-parameter-count "getParameterCount" :feature.status/supported]
                   [:java.reflection.executable/get-parameters "getParameters" :feature.status/supported]
                   [:java.reflection.parameter/is-name-present "isNamePresent" :feature.status/supported]
                   [:java.reflection.parameter/get-name "getName" :feature.status/supported]
                   [:java.reflection.modifier/is-abstract "isAbstract" :feature.status/supported]
                   [:java.reflection.class/for-name "forName" :feature.status/supported]}
                   reflection-features)))))))

(deftest classifies-unsupported-reflection-api-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/reflect/ReflectionUnsupportedApi.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path reflection-unsupported-api-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              reflection-features
              (set (d/q '[:find ?kind ?node-name ?status
                          :where
                          [?feature :feature/kind ?kind]
                          [(contains? #{:java.reflection.class/get-declared-method
                                        :java.reflection.class/get-method
                                        :java.reflection.class/get-annotation
                                        :java.reflection.method/invoke
                                        :java.reflection.constructor/new-instance
                                        :java.reflection.constructor/get-annotation
                                        :java.reflection.parameter/get-annotation}
                                       ?kind)]
                          [?feature :feature/status ?status]
                          [?feature :feature/node ?node]
                          [?node :node/name ?node-name]]
                        db))]
          (is (= #{[:java.reflection.class/get-declared-method "getDeclaredMethod" :feature.status/unsupported]
                   [:java.reflection.class/get-method "getMethod" :feature.status/unsupported]
                   [:java.reflection.class/get-annotation "getAnnotation" :feature.status/unsupported]
                   [:java.reflection.method/invoke "invoke" :feature.status/unsupported]
                   [:java.reflection.constructor/new-instance "newInstance" :feature.status/unsupported]
                   [:java.reflection.constructor/get-annotation "getAnnotation" :feature.status/unsupported]
                   [:java.reflection.parameter/get-annotation "getAnnotation" :feature.status/unsupported]}
                 reflection-features)))))))

(deftest keeps-imported-parser-class-calls-out-of-reflection-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/parser/ParserVisitor.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path parser-syntax-class-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              parser-class-call-names #{"getHeaderSpan"
                                        "getTypeParameterList"
                                        "getAnnotations"
                                        "getModifiers"
                                        "getName"}
              call-refs
              (set (d/q '[:find ?name ?owner-name ?resolved ?reason
                          :in $ ?names
                          :where
                          [?node :node/kind :java.node/method-call]
                          [?node :node/name ?name]
                          [(contains? ?names ?name)]
                          [?ref :ref/from-node ?node]
                          [?ref :ref/owner-type ?owner]
                          [?owner :type/name ?owner-name]
                          [?ref :ref/resolved? ?resolved]
                          [?ref :ref/reason ?reason]]
                        db parser-class-call-names))
              call-features
              (set (d/q '[:find ?name ?kind ?status
                          :in $ ?names
                          :where
                          [?node :node/kind :java.node/method-call]
                          [?node :node/name ?name]
                          [(contains? ?names ?name)]
                          [?feature :feature/node ?node]
                          [?feature :feature/kind ?kind]
                          [?feature :feature/status ?status]]
                        db parser-class-call-names))]
          (is (= #{["getHeaderSpan" "org.pkl.parser.syntax.Class" false :resolve.reason/missing-classpath]
                   ["getTypeParameterList" "org.pkl.parser.syntax.Class" false :resolve.reason/missing-classpath]
                   ["getAnnotations" "org.pkl.parser.syntax.Class" false :resolve.reason/missing-classpath]
                   ["getModifiers" "org.pkl.parser.syntax.Class" false :resolve.reason/missing-classpath]
                   ["getName" "org.pkl.parser.syntax.Class" false :resolve.reason/missing-classpath]}
                 call-refs))
          (is (empty? call-features)))))))

(deftest classifies-supported-synchronized-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/java/com/acme/sync/SynchronizedCase.java"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path synchronized-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [db (d/db conn)
              block-shape (set (d/q '[:find ?block-name ?lock-kind ?lock-role ?body-kind ?body-role
                                       :where
                                       [?block :node/kind :java.node/synchronized-block]
                                       [?block :node/name ?block-name]
                                       [?lock :node/parent ?block]
                                       [?lock :node/role ?lock-role]
                                       [?lock :node/kind ?lock-kind]
                                       [?body :node/parent ?block]
                                       [?body :node/role ?body-role]
                                       [?body :node/kind ?body-kind]
                                       [(= :lock ?lock-role)]
                                       [(= :body ?body-role)]]
                                     db))
              features (set (d/q '[:find ?kind ?node-name ?status
                                    :where
                                    [?feature :feature/kind ?kind]
                                    [(contains? #{:java.feature/synchronized-method
                                                  :java.feature/synchronized-block}
                                                 ?kind)]
                                    [?feature :feature/status ?status]
                                    [?feature :feature/node ?node]
                                    [?node :node/name ?node-name]]
                                  db))]
          (is (= #{["synchronized" :java.node/this :lock :java.node/assignment :body]
                   ["synchronized" :java.node/this :lock :java.node/return-statement :body]}
                 block-shape))
          (is (= #{[:java.feature/synchronized-method "increment" :feature.status/supported]
                   [:java.feature/synchronized-method "zero" :feature.status/supported]
                   [:java.feature/synchronized-block "synchronized" :feature.status/supported]}
                 features)))))))
