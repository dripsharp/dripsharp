(ns vibeformer.vibeformer-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.util UUID)
           (org.jetbrains.kotlin.com.intellij.openapi.util Disposer)
           (org.jetbrains.kotlin.cli.jvm.compiler EnvironmentConfigFiles KotlinCoreEnvironment)
           (org.jetbrains.kotlin.config CompilerConfiguration)
           (org.jetbrains.kotlin.psi KtCallExpression KtClass KtNamedFunction KtNullableType KtObjectDeclaration KtProperty KtPsiFactory KtSafeQualifiedExpression)
           (spoon Launcher)
           (spoon.reflect.code CtInvocation)
           (spoon.reflect.declaration ModifierKind)
           (spoon.reflect.visitor.filter TypeFilter)))

(def java-sample
  "package com.acme.parser;

import java.util.Locale;

public final class Greeter {
  private final String name;

  public Greeter(String name) {
    this.name = name;
  }

  public String greeting(Locale locale) {
    String normalized = name.toUpperCase(locale);
    return \"Hello, \" + normalized.trim();
  }
}
")

(def kotlin-sample
  "package com.acme.parser

import java.util.Locale

object Fixture {
  val defaultName: String? = \"world\"
}

class KotlinGreeter(private val initialName: String?) {
  val name: String? = initialName
  val salutation: String = \"Hello\"

  fun greeting(locale: Locale): String {
    val normalized: String? = name?.uppercase(locale)
    return \"$salutation, ${normalized?.trim() ?: Fixture.defaultName}\"
  }
}

fun topLevelMessage(value: String?): String = value?.trim() ?: \"empty\"
")

(defn- write-java-sample! []
  (let [root (Files/createTempDirectory "vibeformer-spoon-" (make-array java.nio.file.attribute.FileAttribute 0))
        package-dir (.resolve root "com/acme/parser")
        source-file (.resolve package-dir "Greeter.java")]
    (Files/createDirectories package-dir (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString source-file java-sample StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    source-file))

(defn- parse-java-source [source-file]
  (let [launcher (Launcher.)]
    (doto (.getEnvironment launcher)
      (.setNoClasspath true)
      (.setComplianceLevel 17)
      (.setAutoImports true))
    (.addInputResource launcher (str source-file))
    (.buildModel launcher)
    (.get (.Type (.getFactory launcher)) "com.acme.parser.Greeter")))

(defn- valid-position? [element]
  (let [position (.getPosition element)]
    (and (.isValidPosition position)
         (= "Greeter.java" (.getName (io/file (.getFile position))))
         (pos? (.getLine position))
         (pos? (.getColumn position)))))

(defn- parse-kotlin-source [source]
  (let [disposable (Disposer/newDisposable)
        environment (KotlinCoreEnvironment/createForProduction
                     disposable
                     (CompilerConfiguration.)
                     EnvironmentConfigFiles/JVM_CONFIG_FILES)
        psi-factory (KtPsiFactory. (.getProject environment) false)]
    {:disposable disposable
     :file (.createFile psi-factory "Sample.kt" source)}))

(defn- find-declaration [declarations clazz name]
  (some #(when (and (instance? clazz %)
                    (= name (.getName %)))
           %)
        declarations))

(defn- collect-psi-elements [root clazz]
  (letfn [(walk [element]
            (concat (when (instance? clazz element) [element])
                    (mapcat walk (.getChildren element))))]
    (doall (walk root))))

(defn- offset->line-column [source offset]
  (let [lines-before-offset (str/split (subs source 0 offset) #"\n" -1)]
    {:line (count lines-before-offset)
     :column (inc (count (last lines-before-offset)))}))

(defn- element-start-offset [element]
  (.getStartOffset (.getTextRange element)))

(deftest spoon-parses-java-source-into-useful-model
  (let [greeter (parse-java-source (write-java-sample!))
        name-field (.getField greeter "name")
        greeting-method (first (.getMethodsByName greeter "greeting"))
        greeting-params (.getParameters greeting-method)
        invocations (.getElements greeting-method (TypeFilter. CtInvocation))
        invocation-names (set (map #(.getSimpleName (.getExecutable %)) invocations))]
    (testing "package, class name, and class modifiers"
      (is (= "com.acme.parser" (.getQualifiedName (.getPackage greeter))))
      (is (= "Greeter" (.getSimpleName greeter)))
      (is (= "com.acme.parser.Greeter" (.getQualifiedName greeter)))
      (is (contains? (.getModifiers greeter) ModifierKind/PUBLIC))
      (is (contains? (.getModifiers greeter) ModifierKind/FINAL)))

    (testing "field facts include modifiers, type references, and positions"
      (is (= "name" (.getSimpleName name-field)))
      (is (= "java.lang.String" (.getQualifiedName (.getType name-field))))
      (is (contains? (.getModifiers name-field) ModifierKind/PRIVATE))
      (is (contains? (.getModifiers name-field) ModifierKind/FINAL))
      (is (valid-position? name-field)))

    (testing "method facts include signature, type references, calls, and positions"
      (is (= "greeting" (.getSimpleName greeting-method)))
      (is (= "java.lang.String" (.getQualifiedName (.getType greeting-method))))
      (is (= ["java.util.Locale"] (mapv #(.getQualifiedName (.getType %)) greeting-params)))
      (is (contains? invocation-names "toUpperCase"))
      (is (contains? invocation-names "trim"))
      (is (valid-position? greeting-method)))))

(deftest kotlin-psi-parses-source-into-useful-model
  (let [{:keys [disposable file]} (parse-kotlin-source kotlin-sample)]
    (try
      (let [top-level-declarations (.getDeclarations file)
            fixture (find-declaration top-level-declarations KtObjectDeclaration "Fixture")
            greeter (find-declaration top-level-declarations KtClass "KotlinGreeter")
            top-level-message (find-declaration top-level-declarations KtNamedFunction "topLevelMessage")
            greeter-declarations (.getDeclarations greeter)
            name-property (find-declaration greeter-declarations KtProperty "name")
            greeting-function (find-declaration greeter-declarations KtNamedFunction "greeting")
            greeting-params (.getValueParameters greeting-function)
            call-names (set (keep #(some-> (.getCalleeExpression %) .getText)
                                  (collect-psi-elements greeting-function KtCallExpression)))]
        (testing "package, object, class, and top-level function facts"
          (is (= "com.acme.parser" (.asString (.getPackageFqName file))))
          (is (= "Fixture" (.getName fixture)))
          (is (= "KotlinGreeter" (.getName greeter)))
          (is (= "topLevelMessage" (.getName top-level-message))))

        (testing "property and function facts include type references and nullability"
          (is (= "name" (.getName name-property)))
          (is (= "String?" (.getText (.getTypeReference name-property))))
          (is (instance? KtNullableType (.getTypeElement (.getTypeReference name-property))))
          (is (= "greeting" (.getName greeting-function)))
          (is (= "String" (.getText (.getTypeReference greeting-function))))
          (is (= ["Locale"] (mapv #(.getText (.getTypeReference %)) greeting-params))))

        (testing "expression facts include calls and safe-call syntax"
          (is (contains? call-names "uppercase"))
          (is (contains? call-names "trim"))
          (is (seq (collect-psi-elements greeting-function KtSafeQualifiedExpression))))

        (testing "source positions can be recovered from PSI text offsets"
          (is (= {:line 9 :column 1} (offset->line-column kotlin-sample (element-start-offset greeter))))
          (is (= {:line 13 :column 3} (offset->line-column kotlin-sample (element-start-offset greeting-function))))
          (is (= "Sample.kt" (.getName (.getContainingFile greeter))))))
      (finally
        (Disposer/dispose disposable)))))

(deftest datomic-local-stores-and-queries-vibeformer-shaped-facts
  (let [system (str "vibeformer-test-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (is (true? (d/create-database client {:db-name db-name})))
      (reset! created? true)
      (let [conn (d/connect client {:db-name db-name})
            file-path "src/main/java/com/acme/parser/Greeter.java"
            class-node-id (str "test:" file-path ":class:com.acme.parser.Greeter")
            method-node-id (str "test:" file-path ":method:com.acme.parser.Greeter.greeting")
            invocation-node-id (str "test:" file-path ":call:com.acme.parser.Greeter.greeting:toUpperCase")]
        (schema/install! conn)
        (d/transact conn
                    {:tx-data
                     [{:db/id "project"
                       :project/id "test-project"
                       :project/name "Test Project"
                       :project/root "/workspace/test-project"}
                      {:db/id "file"
                       :file/id (str "test-project:" file-path)
                       :file/path file-path
                       :file/lang :lang/java
                       :file/hash "sha256:file"
                       :file/project "project"
                       :file/package "com.acme.parser"}
                      {:db/id "string-type"
                       :type/id "java.lang.String"
                       :type/lang :lang/java
                       :type/name "java.lang.String"
                       :type/nullable? false}
                      {:db/id "greeter-type"
                       :type/id "java:com.acme.parser.Greeter"
                       :type/lang :lang/java
                       :type/name "com.acme.parser.Greeter"
                       :type/nullable? false}
                      {:db/id "locale-type"
                       :type/id "java.util.Locale"
                       :type/lang :lang/java
                       :type/name "java.util.Locale"
                       :type/nullable? false}
                      {:db/id "list-type"
                       :type/id "java.util.List<java.lang.String>"
                       :type/lang :lang/java
                       :type/name "java.util.List"
                       :type/nullable? false
                       :type/args [{:type.arg/ordinal 0
                                    :type.arg/type "string-type"}]}
                      {:db/id "class-node"
                       :node/id class-node-id
                       :node/lang :lang/java
                       :node/kind :java.node/class
                       :node/name "Greeter"
                       :node/file "file"
                       :node/ordinal 0
                       :node/start-line 5
                       :node/start-column 1
                       :node/end-line 16
                       :node/end-column 2
                       :node/source-hash "sha256:class"}
                      {:db/id "method-node"
                       :node/id method-node-id
                       :node/lang :lang/java
                       :node/kind :java.node/method
                       :node/name "greeting"
                       :node/file "file"
                       :node/parent "class-node"
                       :node/ordinal 1
                       :node/start-line 12
                       :node/start-column 3
                       :node/end-line 15
                       :node/end-column 4
                       :node/source-hash "sha256:method"}
                      {:db/id "invocation-node"
                       :node/id invocation-node-id
                       :node/lang :lang/java
                       :node/kind :java.node/method-call
                       :node/name "toUpperCase"
                       :node/file "file"
                       :node/parent "method-node"
                       :node/ordinal 0
                       :node/start-line 13
                       :node/start-column 25
                       :node/end-line 13
                       :node/end-column 48
                       :node/source-hash "sha256:call"}
                      {:db/id "class-decl"
                       :decl/id "java:com.acme.parser.Greeter"
                       :decl/lang :lang/java
                       :decl/kind :decl.kind/class
                       :decl/name "Greeter"
                       :decl/qualified-name "com.acme.parser.Greeter"
                       :decl/source-node "class-node"
                       :decl/type "greeter-type"
                       :decl/modifiers #{:public :final}}
                      {:db/id "method-decl"
                       :decl/id "java:com.acme.parser.Greeter#greeting(java.util.Locale)"
                       :decl/lang :lang/java
                       :decl/kind :decl.kind/method
                       :decl/name "greeting"
                       :decl/qualified-name "com.acme.parser.Greeter.greeting"
                       :decl/source-node "method-node"
                       :decl/return-type "string-type"
                       :decl/modifiers #{:public}}
                      {:ref/id "java:Greeter.greeting:call:toUpperCase"
                       :ref/kind :ref.kind/method-call
                       :ref/from-node "invocation-node"
                       :ref/to-decl "method-decl"
                       :ref/to-type "string-type"
                       :ref/name "toUpperCase"
                       :ref/owner-type "string-type"
                       :ref/resolved? true}
                      {:ref/id "java:Greeter.greeting:call:missing"
                       :ref/kind :ref.kind/method-call
                       :ref/from-node "method-node"
                       :ref/name "missing"
                       :ref/resolved? false
                       :ref/reason :resolve.reason/missing-classpath}
                      {:feature/id "java:Greeter:feature:class"
                       :feature/lang :lang/java
                       :feature/kind :java.feature/class
                       :feature/node "class-node"
                       :feature/status :feature.status/supported
                       :feature/severity :feature.severity/info}
                      {:feature/id "java:Greeter:feature:reflection"
                       :feature/lang :lang/java
                       :feature/kind :java.feature/reflection
                       :feature/node "method-node"
                       :feature/status :feature.status/unsupported
                       :feature/severity :feature.severity/hard}]})
        (let [db (d/db conn)]
          (testing "file -> node -> declaration"
            (is (= #{[file-path :java.node/class "Greeter" "com.acme.parser.Greeter"]
                     [file-path :java.node/method "greeting" "com.acme.parser.Greeter.greeting"]}
                   (set (d/q '[:find ?path ?node-kind ?decl-name ?decl-qname
                               :where
                               [?file :file/path ?path]
                               [?node :node/file ?file]
                               [?node :node/kind ?node-kind]
                               [?decl :decl/source-node ?node]
                               [?decl :decl/name ?decl-name]
                               [?decl :decl/qualified-name ?decl-qname]]
                             db)))))
          (testing "declaration -> return type"
            (is (= #{["greeting" "java.lang.String" false]}
                   (set (d/q '[:find ?decl-name ?type-id ?nullable?
                               :where
                               [?decl :decl/name ?decl-name]
                               [?decl :decl/return-type ?type]
                               [?type :type/id ?type-id]
                               [?type :type/nullable? ?nullable?]]
                             db)))))
          (testing "reference -> source node -> target declaration and type"
            (is (= #{["toUpperCase" "toUpperCase" "greeting" "java.lang.String"]}
                   (set (d/q '[:find ?ref-name ?node-name ?decl-name ?type-id
                               :where
                               [?ref :ref/resolved? true]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/from-node ?node]
                               [?node :node/name ?node-name]
                               [?ref :ref/to-decl ?decl]
                               [?decl :decl/name ?decl-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]]
                             db))))
            (is (= #{["missing" "greeting" :resolve.reason/missing-classpath]}
                   (set (d/q '[:find ?ref-name ?node-name ?reason
                               :where
                               [?ref :ref/resolved? false]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/from-node ?node]
                               [?node :node/name ?node-name]
                               [?ref :ref/reason ?reason]]
                             db)))))
          (testing "feature inventory counts by kind and status"
            (is (= #{[:java.feature/class :feature.status/supported 1]
                     [:java.feature/reflection :feature.status/unsupported 1]}
                   (set (d/q '[:find ?kind ?status (count ?feature)
                               :where
                               [?feature :feature/kind ?kind]
                               [?feature :feature/status ?status]]
                             db)))))
          (testing "unsupported features by file"
            (is (= #{[file-path :java.feature/reflection 1]}
                   (set (d/q '[:find ?path ?kind (count ?feature)
                               :where
                               [?feature :feature/status :feature.status/unsupported]
                               [?feature :feature/kind ?kind]
                               [?feature :feature/node ?node]
                               [?node :node/file ?file]
                               [?file :file/path ?path]]
                             db)))))))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))
