(ns vibeformer.emit.csharp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
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

(def enum-call-demo-fixture
  "package com.example.token;

public final class TokenDemo {
  private TokenDemo() {
  }

  public static void main(String[] args) {
    Token.ABSTRACT.isModifier();
  }
}
")

(def enum-unsupported-method-fixture
  "package com.example.token;

public enum Token {
  IDENTIFIER;

  public String text() {
    return name();
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
                                   (:csharp/provenance result))]
          (is (= {:ok? true :failures []} coverage))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (is (str/includes? content "public enum Token"))
          (is (str/includes? content "ABSTRACT,"))
          (is (str/includes? content "public static class TokenExtensions"))
          (is (str/includes? content "public static bool isModifier(this Token value)"))
          (is (str/includes? content "return value switch"))
          (is (str/includes? content "Token.ABSTRACT or Token.OPEN or Token.LOCAL => true,"))
          (is (str/includes? content "_ => false,"))
          (is (str/includes? demo-content "Token.ABSTRACT.isModifier();"))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rule-ids
                      [:java.method-node/to-csharp-method
                       :java.switch-expression-node/to-csharp-switch
                       :java.switch-case-node/to-csharp-switch-arm]))
          (is (some? switch-entry))
          (is (= 2 (count case-entries))))))))

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
