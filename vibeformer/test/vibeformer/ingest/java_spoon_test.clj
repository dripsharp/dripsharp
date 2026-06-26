(ns vibeformer.ingest.java-spoon-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source])
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
                 #{[:java.feature/reflection "forName" :java.node/method-call]
                   [:java.feature/stream-api "stream" :java.node/method-call]}
                                   (set (d/q '[:find ?kind ?node-name ?node-kind
                             :where
                             [?feature :feature/kind ?kind]
                             [(contains? #{:java.feature/reflection
                                           :java.feature/stream-api}
                                          ?kind)]
                             [?feature :feature/node ?node]
                             [?node :node/name ?node-name]
                             [?node :node/kind ?node-kind]]
                           db))))))))))
