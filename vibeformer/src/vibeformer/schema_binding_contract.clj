(ns vibeformer.schema-binding-contract
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption Path]
           [java.security MessageDigest]
           [spoon Launcher]
           [spoon.reflect.declaration CtConstructor CtMethod]
           [spoon.reflect.visitor.filter TypeFilter]))

(def ^:private inventory-magic "VIBEFORMER_SCHEMA_BINDING_CONTRACT_V1")

(def pinned-upstream-revision
  "f7cac257ade5775c1dfc255f4fda2eacc296e9d0")

(def inventory-columns
  ["row-id"
   "artifact-kind"
   "upstream-module"
   "upstream-case-identity"
   "source-path"
   "source-sha256"
   "source-line"
   "dependencies"
   "behavior-family"
   "product-classification"
   "scope-basis"
   "observation-kinds"
   "oracle-kind"
   "detail"])

(def observation-columns
  ["observation-kind"
   "success-observation"
   "failure-observation"
   "normalization"
   "comparison"])

(def ^:private product-classifications
  #{"in-scope-executable-dotnet-behavior"
    "language-specific-evidence-requiring-idiomatic-csharp-analogue"
    "user-approved-excluded-surface"
    "non-shipping-test-infrastructure"})

(def ^:private required-observation-kinds
  #{"schema-metadata"
    "generated-model-shape-and-behavior"
    "symbol-and-namespace-mapping"
    "documentation-and-deprecation-metadata"
    "equality-hash-string-behavior"
    "evaluator-config-navigation"
    "binding-and-conversion"
    "generated-loaders"
    "reflection-and-nullability"
    "lifecycle"
    "diagnostics"})

(def ^:private artifact-kinds
  #{"test-source"
    "helper-source"
    "fixture"
    "expected-output-resource"
    "test-declaration"
    "parameterized-case"
    "helper-declaration"
    "support-type"})

(def ^:private module-dependencies
  {"pkl-core"
   ["upstream:pkl-core"]

   "pkl-config-java"
   ["upstream:pkl-core"
    "upstream:pkl-codegen-java:test-fixture-generation"
    "upstream:geantyref"
    "upstream:javax-inject:test"]

   "pkl-config-kotlin"
   ["upstream:pkl-config-java"
    "upstream:pkl-codegen-kotlin:test-fixture-generation"
    "upstream:kotlin-reflect"
    "upstream:geantyref:test"]

   "pkl-codegen-java"
   ["upstream:pkl-core"
    "upstream:pkl-config-java:test"
    "upstream:pkl-commons"
    "upstream:pkl-commons-cli:wrapper-evidence"
    "upstream:pkl-commons-test"
    "upstream:javapoet:language-specific-output"]

   "pkl-codegen-kotlin"
   ["upstream:pkl-core"
    "upstream:pkl-config-kotlin:test"
    "upstream:pkl-commons"
    "upstream:pkl-commons-cli:wrapper-evidence"
    "upstream:pkl-commons-test"
    "upstream:kotlin-reflect"
    "upstream:kotlinpoet:language-specific-output"]})

(def ^:private java-specific-config-classes
  #{"JavaTypeTest"
    "TypesTest"
    "ReflectionTest"
    "PObjectToDataObjectJavaxInjectTest"
    "PObjectToInnerClassTest"})

(def ^:private java-method-pattern
  #"^\s*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp)\s+)*(?:<[^>]+>\s+)?(?:[A-Za-z_$@?.][A-Za-z0-9_$@?.<>\[\],]*\s+)+([A-Za-z_$][A-Za-z0-9_$]*)\s*\(")

(def ^:private java-constructor-pattern
  #"^\s*(?:(?:public|protected|private)\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*\(")

(def ^:private kotlin-method-pattern
  #"^\s*(?:(?:public|private|protected|internal|override|open|inline|operator|infix|tailrec|suspend|external)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_?.<>]+\.)?(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*\(")

(def ^:private type-pattern
  #"^\s*(?:(?:public|private|protected|internal|static|final|abstract|sealed|open|data|inner|value)\s+)*(?:class|interface|record|object|enum(?:\s+class)?)\s+([A-Za-z_$][A-Za-z0-9_$]*)")

(def ^:private annotation-pattern
  #"^\s*@(Test|ParameterizedTest|RepeatedTest)(?:\s|\(|$)")

(def ^:private reserved-call-names
  #{"if" "for" "while" "switch" "catch" "try" "do" "return" "throw"
    "assertThat" "assertThrows" "check" "require" "when"})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-schema-binding-contract)))))

(defn- portable-path
  [^Path root ^Path path]
  (-> (str (.relativize root (.normalize path)))
      (str/replace "\\" "/")))

(defn- regular-file?
  [^Path path]
  (Files/isRegularFile path (make-array LinkOption 0)))

(defn- walk-files
  [^Path root]
  (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter regular-file?)
         (sort-by #(portable-path root %))
         vec)))

(defn- sha256-bytes
  [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- sha256-file
  [^Path file]
  (sha256-bytes (Files/readAllBytes file)))

(defn- command-output
  [request]
  (str/trim (:output (process/run! request))))

(defn- joined
  [values]
  (let [values (->> values (remove str/blank?) distinct sort vec)]
    (if (seq values) (str/join ";" values) "-")))

(defn- sanitized
  [value]
  (-> (str value)
      (str/replace #"[\t\r\n]+" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- layout
  [workspace-root]
  (let [root (paths/absolute workspace-root)
        upstream (paths/resolve-path root "research" "pkl")
        contract-dir (paths/resolve-path root "vibeformer" "validation"
                                         "schema-binding-contract")]
    {:root root
     :upstream upstream
     :contract-dir contract-dir
     :inventory (paths/resolve-path contract-dir "UpstreamInventory.tsv")
     :observations (paths/resolve-path contract-dir "ObservationKinds.tsv")
     :scopes
     [{:module "pkl-core"
       :files [(paths/resolve-path upstream "pkl-core" "src" "test" "kotlin"
                                   "org" "pkl" "core" "EvaluateSchemaTest.kt")
               (paths/resolve-path upstream "pkl-core" "src" "test" "resources"
                                   "org" "pkl" "core" "EvaluateSchemaTest.pkl")
               (paths/resolve-path upstream "pkl-core" "src" "test" "resources"
                                   "org" "pkl" "core" "EvaluateSchemaTestBaseModule.pkl")]}
      {:module "pkl-config-java"
       :root (paths/resolve-path upstream "pkl-config-java" "src" "test")}
      {:module "pkl-config-kotlin"
       :root (paths/resolve-path upstream "pkl-config-kotlin" "src" "test")}
      {:module "pkl-codegen-java"
       :root (paths/resolve-path upstream "pkl-codegen-java" "src" "test")}
      {:module "pkl-codegen-kotlin"
       :root (paths/resolve-path upstream "pkl-codegen-kotlin" "src" "test")}]}))

(defn- verify-pinned-revision!
  [{:keys [root upstream]}]
  (let [gitlink (command-output {:command ["git" "rev-parse" "HEAD:research/pkl"]
                                 :directory root})
        checkout (command-output {:command ["git" "rev-parse" "HEAD"]
                                  :directory upstream})]
    (doseq [[subject actual] [[:gitlink gitlink] [:checkout checkout]]]
      (when-not (= pinned-upstream-revision actual)
        (fail! "The schema/binding contract upstream revision drifted"
               {:kind :schema-binding-revision-drift
                :subject subject
                :expected pinned-upstream-revision
                :actual actual})))
    pinned-upstream-revision))

(defn- scoped-files
  [{:keys [scopes]}]
  (vec
   (mapcat
    (fn [{:keys [module root files]}]
      (for [file (or files (walk-files root))]
        (do
          (when-not (regular-file? file)
            (fail! "A required upstream contract input is missing"
                   {:kind :missing-schema-binding-input :path (str file)}))
          {:module module :path file})))
    scopes)))

(defn- source-extension
  [^Path path]
  (let [name (str (.getFileName path))
        index (.lastIndexOf name ".")]
    (if (neg? index) "" (subs name index))))

(defn- active-annotation?
  [line]
  (boolean (re-find annotation-pattern line)))

(defn- source-file?
  [^Path path]
  (contains? #{".java" ".kt"} (source-extension path)))

(defn- source-data
  [{:keys [module path]} root]
  (let [text (when (source-file? path)
               (Files/readString path StandardCharsets/UTF_8))]
    {:module module
     :path path
     :source-path (portable-path root path)
     :source-sha256 (sha256-file path)
     :text text
     :lines (when text (vec (str/split-lines text)))}))

(defn- source-class
  [{:keys [path text]}]
  (let [package-name (second (re-find #"(?m)^\s*package\s+([A-Za-z0-9_.]+)" text))
        file-name (str (.getFileName ^Path path))
        simple-name (first (str/split file-name #"\."))]
    (if package-name (str package-name "." simple-name) simple-name)))

(defn- test-annotation
  [line]
  (second (re-find annotation-pattern line)))

(defn- method-at-line
  [extension line]
  (case extension
    ".kt" (let [[_ quoted ordinary] (re-find kotlin-method-pattern line)]
            (or quoted ordinary))
    ".java" (or (second (re-find java-method-pattern line))
                (let [candidate (second (re-find java-constructor-pattern line))]
                  (when-not (reserved-call-names candidate) candidate)))
    nil))

(defn- next-method
  [lines extension annotation-index]
  (some
   (fn [index]
     (when-let [method (method-at-line extension (nth lines index))]
       {:method method :method-index index :method-line (inc index)}))
   (range (inc annotation-index) (min (count lines) (+ annotation-index 40)))))

(defn- declaration-block
  [lines annotation-index next-annotation-index]
  (str/join "\n" (subvec lines annotation-index next-annotation-index)))

(defn- discover-declarations
  [{:keys [module path source-path source-sha256 text lines] :as source}]
  (if-not (source-file? path)
    []
    (let [extension (source-extension path)
          class-name (source-class source)
          annotation-indexes (vec (keep-indexed (fn [index line]
                                                  (when (active-annotation? line) index))
                                                lines))]
      (mapv
       (fn [position annotation-index]
         (let [method (next-method lines extension annotation-index)
               next-index (or (nth annotation-indexes (inc position) nil) (count lines))
               annotation (test-annotation (nth lines annotation-index))]
           (when-not method
             (fail! "An upstream test annotation has no following method"
                    {:kind :unresolved-schema-binding-test
                     :source source-path :line (inc annotation-index)}))
           {:module module
            :path path
            :source-path source-path
            :source-sha256 source-sha256
            :source-text text
            :source-lines lines
            :source-class class-name
            :source-method (:method method)
            :annotation annotation
            :annotation-index annotation-index
            :annotation-line (inc annotation-index)
            :method-index (:method-index method)
            :method-line (:method-line method)
            :declaration-text (declaration-block lines annotation-index next-index)}))
       (range)
       annotation-indexes))))

(defn- parameter-values
  [{:keys [annotation declaration-text source-path annotation-line]}]
  (if (= "ParameterizedTest" annotation)
    (if-let [[_ body] (re-find #"@ValueSource\s*\(\s*booleans\s*=\s*\[([^]]+)\]"
                               declaration-text)]
      (let [values (->> (str/split body #",") (map str/trim) (remove str/blank?) vec)]
        (when-not (every? #{"false" "true"} values)
          (fail! "A parameterized generator test has an unsupported boolean value source"
                 {:kind :unsupported-schema-binding-parameter-source
                  :source source-path :line annotation-line :values values}))
        values)
      (fail! "A parameterized upstream test has no pinned invocation discovery rule"
             {:kind :unsupported-schema-binding-parameter-source
              :source source-path :line annotation-line}))
    []))

(defn- artifact-kind
  [{:keys [path text]}]
  (let [extension (source-extension path)]
    (cond
      (source-file? path)
      (if (some active-annotation? (str/split-lines text)) "test-source" "helper-source")

      (contains? #{".jva" ".kotlin"} extension) "expected-output-resource"
      :else "fixture")))

(defn- source-row-id
  [source-path]
  (str "artifact:" source-path))

(defn- declaration-row-id
  [{:keys [source-path annotation-line source-method]}]
  (str "test:" source-path ":" annotation-line ":" (sanitized source-method)))

(defn- helper-row-id
  [source-path line name]
  (str "helper:" source-path ":" line ":" (sanitized name)))

(defn- type-row-id
  [source-path line name]
  (str "type:" source-path ":" line ":" name))

(defn- simple-class-name
  [class-name]
  (last (str/split class-name #"\.")))

(defn- behavior-family
  [module class-name identity text]
  (let [class-simple (simple-class-name class-name)
        name-text (str/lower-case (str class-simple " " identity))
        haystack (str/lower-case (str class-simple " " identity " " text))]
    (cond
      (= module "pkl-core")
      (cond
        (str/includes? name-text "metadata") "schema.metadata-source-locations"
        (str/includes? name-text "propert") "schema.properties-and-types"
        (str/includes? name-text "method") "schema.methods-parameters-constraints"
        (str/includes? name-text "supermodule") "schema.inheritance-supermodules"
        (str/includes? name-text "local class") "schema.classes-local-visibility"
        (str/includes? name-text "pkl_base") "schema.base-module-superclass"
        (str/includes? name-text "class") "schema.classes-local-visibility"
        :else "schema.complete-module-metadata")

      (contains? #{"pkl-codegen-java" "pkl-codegen-kotlin"} module)
      (cond
        (re-find #"deprecated|deprecation" name-text) "codegen.deprecation-metadata"
        (re-find #"javadoc|kdoc|doc comment|documentation" name-text)
        "codegen.documentation-metadata"
        (re-find #"generated.?annotation" name-text) "codegen.generated-annotation-metadata"
        (re-find #"getter" name-text) "codegen.generated-accessors"
        (re-find #"equals|hashcode|to.?string|data class|value semantics" name-text)
        "codegen.equality-hash-string-behavior"
        (re-find #"package name|namespace|module name|custom package" name-text)
        "codegen.namespace-module-mapping"
        (re-find #"class name|clash|collision|reserved|identifier|keyword|mangle" name-text)
        "codegen.symbol-collision-keyword-mapping"
        (re-find #"loader|loadfrom|load\(" name-text) "codegen.generated-loaders"
        (re-find #"polymorph" name-text) "codegen.polymorphism"
        (re-find #"override|overridden|amend" name-text) "codegen.overrides-amends"
        (re-find #"inherit|extends|superclass|abstract|open class" name-text)
        "codegen.inheritance-overrides"
        (re-find #"null" name-text) "codegen.nullability"
        (re-find #"collection|listing|mapping|map|list|set|pair" name-text)
        "codegen.collection-types"
        (re-find #"type alias|alias|union" name-text) "codegen.alias-union-types"
        (re-find #"generic|function|method" name-text) "codegen.generics-functions-methods"
        (re-find #"constraint" name-text) "codegen.constrained-types"
        (re-find #"primitive|property type|type mapping" name-text) "codegen.property-type-mapping"
        (re-find #"order|determin" name-text) "codegen.deterministic-ordering"
        (re-find #"error|invalid|reject|unsupported|fail|compile" name-text)
        "codegen.compilation-diagnostics"
        (re-find #"deprecated|javadoc|kdoc|doc comment|documentation" haystack)
        "codegen.documentation-deprecation-metadata"
        (re-find #"package name|namespace|class name|clash|collision|reserved|identifier|module name"
                 haystack)
        "codegen.symbol-namespace-mapping"
        (re-find #"null|type|property|collection|listing|mapping|map|list|set|pair|alias|generic|function"
                 haystack)
        "codegen.types-nullability-model-shape"
        :else "codegen.generated-model-shape-behavior")

      (contains? #{"pkl-config-java" "pkl-config-kotlin"} module)
      (cond
        (and (re-find #"evaluatorbuilder|configevaluatorbuilder" name-text)
             (re-find #"environment" name-text)) "config.builder-environment"
        (and (re-find #"evaluatorbuilder|configevaluatorbuilder" name-text)
             (re-find #"external|system propert" name-text)) "config.builder-external-properties"
        (and (re-find #"evaluatorbuilder|configevaluatorbuilder" name-text)
             (re-find #"security" name-text)) "config.builder-security-manager"
        (re-find #"evaluatorbuilder|configevaluatorbuilder" name-text)
        "config.evaluator-builder-lifecycle"
        (re-find #"configevaluatortest|configevaluatorextensions" name-text)
        "config.evaluator-navigation-lifecycle"
        (and (re-find #"abstractconfig|navigate|child|qualified name" name-text)
             (re-find #"non.?existing|missing" name-text)) "config.navigation-diagnostics"
        (re-find #"abstractconfig|navigate|child|qualified name" name-text)
        "config.navigation-conversion"
        (re-find #"deprecated" name-text) "config.deprecated-api-metadata"
        (re-find #"reflection" name-text) "binding.reflection-type-resolution"
        (and (re-find #"javatype|types|type argument|raw type|supertype|subtype" name-text)
             (re-find #"null" name-text)) "binding.generic-nullability-metadata"
        (re-find #"javatype|types|type argument|raw type|supertype|subtype" name-text)
        "binding.generic-reflection-metadata"
        (re-find #"polymorph" name-text) "binding.polymorphism"
        (re-find #"override" name-text) "binding.overridden-properties"
        (re-find #"optional" name-text) "binding.optional-null-conversions"
        (re-find #"array" name-text) "binding.array-conversions"
        (re-find #"map" name-text) "binding.map-conversions"
        (re-find #"pair" name-text) "binding.pair-conversions"
        (re-find #"collection|list|set|iterable" name-text)
        "binding.collection-conversions"
        (re-find #"module" name-text) "binding.module-object-construction"
        (re-find #"inner class" name-text) "binding.inner-class-construction"
        (re-find #"object|constructor|data object|pojo" name-text)
        "binding.object-construction-members"
        (re-find #"enum" name-text) "binding.enum-conversions"
        (re-find #"version" name-text) "binding.version-conversions"
        (re-find #"bytes" name-text) "binding.bytes-conversions"
        (re-find #"convert|conversion|duration|uri|regex|char" name-text)
        "binding.scalar-value-conversions"
        (re-find #"decoder" name-text) "config.decoder-options"
        (re-find #"null" name-text) "binding.nullability"
        (re-find #"reflection|javatype|types|type argument|raw type|supertype|subtype|null"
                 haystack)
        "binding.reflection-generics-nullability"
        :else "binding.complete-value-mapping")

      :else "test-infrastructure")))

(defn- observation-kinds
  [module class-name identity text artifact-kind]
  (let [family (behavior-family module class-name identity text)
        haystack (str/lower-case (str identity " " text))
        result
        (cond
          (contains? #{"test-source" "helper-source" "helper-declaration"} artifact-kind)
          ["test-infrastructure-provenance"]

          (str/starts-with? family "schema.")
          ["schema-metadata" "diagnostics"]

          (str/starts-with? family "config.evaluator")
          ["evaluator-config-navigation" "lifecycle" "diagnostics"]

          (str/starts-with? family "config.navigation")
          ["evaluator-config-navigation" "binding-and-conversion" "diagnostics"]

          (or (str/includes? family "deprecated")
              (str/includes? family "deprecation")
              (str/includes? family "documentation")
              (str/includes? family "annotation-metadata"))
          ["documentation-and-deprecation-metadata" "diagnostics"]

          (or (str/includes? family "reflection")
              (str/includes? family "nullability"))
          ["reflection-and-nullability" "binding-and-conversion" "diagnostics"]

          (str/starts-with? family "binding.")
          (cond-> ["binding-and-conversion" "diagnostics"]
            (re-find #"equal|hash|tostring" haystack)
            (conj "equality-hash-string-behavior")
            (re-find #"null|generic|type|reflection" haystack)
            (conj "reflection-and-nullability"))

          (or (str/includes? family "documentation-deprecation")
              (str/includes? family "documentation-metadata")
              (str/includes? family "deprecation-metadata")
              (str/includes? family "annotation-metadata"))
          ["documentation-and-deprecation-metadata"
           "generated-model-shape-and-behavior"
           "diagnostics"]

          (str/includes? family "equality-hash-string")
          ["equality-hash-string-behavior"
           "generated-model-shape-and-behavior"
           "diagnostics"]

          (or (str/includes? family "symbol-namespace")
              (str/includes? family "namespace-module")
              (str/includes? family "symbol-collision"))
          ["symbol-and-namespace-mapping"
           "generated-model-shape-and-behavior"
           "diagnostics"]

          (str/includes? family "generated-loaders")
          ["generated-loaders" "lifecycle"
           "generated-model-shape-and-behavior" "diagnostics"]

          (str/starts-with? family "codegen.")
          (cond-> ["generated-model-shape-and-behavior" "diagnostics"]
            (re-find #"null|type|generic" haystack) (conj "reflection-and-nullability")
            (re-find #"inherit|override" haystack) (conj "equality-hash-string-behavior"))

          :else ["test-infrastructure-provenance"])]
    (joined result)))

(defn- language-specific-config?
  [module class-name]
  (or (= module "pkl-config-kotlin")
      (and (= module "pkl-config-java")
           (contains? java-specific-config-classes (simple-class-name class-name)))))

(defn- classification
  [module class-name artifact-kind source-path]
  (cond
    (contains? #{"test-source" "helper-source" "helper-declaration"} artifact-kind)
    {:product-classification "non-shipping-test-infrastructure"
     :scope-basis
     (str "vibeformer/doc/product-goal.md#User-Approved-Product-Exclusions:"
          "build-benchmark-and-test-infrastructure-as-shipped-product-surface;"
          "upstream-tests-and-fixtures-remain-authoritative-behavior-evidence")}

    (and (= artifact-kind "support-type")
         (not (contains? #{"pkl-config-java" "pkl-config-kotlin"} module))
         (not (str/includes? (last (str/split source-path #"/")) "Test")))
    {:product-classification "non-shipping-test-infrastructure"
     :scope-basis
     (str "vibeformer/doc/product-goal.md#User-Approved-Product-Exclusions:"
          "build-benchmark-and-test-infrastructure-as-shipped-product-surface;"
          "support-type-retained-for-provenance")}

    (or (contains? #{"pkl-codegen-java" "pkl-codegen-kotlin"} module)
        (language-specific-config? module class-name)
        (and (= module "pkl-config-java")
             (str/includes? source-path "/codegenPkl/")))
    {:product-classification
     "language-specific-evidence-requiring-idiomatic-csharp-analogue"
     :scope-basis
     (str "vibeformer/doc/product-goal.md#Product-Target:idiomatic-public-.NET-APIs+C#-code-generation;"
          "vibeformer/doc/product-goal.md#User-Approved-Product-Exclusions:"
          "Java-Kotlin-output-products-and-Kotlin-translation-only;"
          "vibeformer/doc/port-scope.md#C#-Code-Generation:"
          "mine-Java-Kotlin-tests-for-concepts-and-implement-idiomatic-C#-analogues;"
          "CLI-wrapper-does-not-exclude-reusable-generator-behavior")}

    :else
    {:product-classification "in-scope-executable-dotnet-behavior"
     :scope-basis
     (str "vibeformer/doc/product-goal.md#Product-Target:"
          "core-Pkl-evaluation+value-model+idiomatic-.NET-APIs+C#-code-generation;"
          "vibeformer/doc/product-goal.md#In-Scope-Pending-Areas:"
          "complete-object-config-binding+schema+generated-loader-APIs")}))

(defn- oracle-kind
  [artifact-kind]
  (case artifact-kind
    "test-declaration" "upstream-junit-declaration"
    "parameterized-case" "upstream-junit-parameter-invocation"
    "fixture" "upstream-source-fixture"
    "expected-output-resource" "upstream-golden-output"
    "support-type" "upstream-test-model"
    "test-helper-provenance"))

(defn- base-row
  [{:keys [row-id artifact-kind module case-identity source-path source-sha256
           source-line dependencies family classification observation-kinds
           oracle-kind detail]}]
  (merge
   {:row-id row-id
    :artifact-kind artifact-kind
    :upstream-module module
    :upstream-case-identity (sanitized case-identity)
    :source-path source-path
    :source-sha256 source-sha256
    :source-line (str source-line)
    :dependencies (joined dependencies)
    :behavior-family family
    :observation-kinds observation-kinds
    :oracle-kind oracle-kind
    :detail (sanitized detail)}
   classification))

(defn- artifact-row
  [{:keys [module path source-path source-sha256 text] :as source}]
  (let [kind (artifact-kind source)
        class-name (if (source-file? path) (source-class source) (str (.getFileName ^Path path)))
        identity (str module ":" (subs source-path (inc (.indexOf source-path "/src/test/"))))
        family (if (contains? #{"test-source" "helper-source"} kind)
                 "test-infrastructure"
                 (behavior-family module class-name identity (or text source-path)))
        class (classification module class-name kind source-path)]
    (base-row
     {:row-id (source-row-id source-path)
      :artifact-kind kind
      :module module
      :case-identity identity
      :source-path source-path
      :source-sha256 source-sha256
      :source-line 1
      :dependencies (module-dependencies module)
      :family family
      :classification class
      :observation-kinds (observation-kinds module class-name identity (or text source-path) kind)
      :oracle-kind (oracle-kind kind)
      :detail (case kind
                "test-source" "Complete upstream test source; declarations are inventoried separately."
                "helper-source" "Separate upstream helper source used by the scoped tests."
                "fixture" "Upstream Pkl fixture consumed by configuration, schema, or binding coverage."
                "expected-output-resource" "Target-language golden output retained as C# analogue evidence.")})))

(defn- inferred-artifact-dependencies
  [declaration artifact-rows]
  (let [{:keys [module source-text declaration-text source-class]} declaration
        class-simple (simple-class-name source-class)
        haystack (str source-text "\n" declaration-text)]
    (for [row artifact-rows
          :when (= module (:upstream-module row))
          :let [path (:source-path row)
                file-name (last (str/split path #"/"))
                stem (first (str/split file-name #"\."))]
          :when (and (contains? #{"fixture" "expected-output-resource" "helper-source"}
                                (:artifact-kind row))
                     (or (str/includes? haystack file-name)
                         (str/includes? haystack stem)
                         (= class-simple stem)
                         (and (str/includes? path "/codegenPkl/")
                              (or (str/includes? haystack "codegenPkl")
                                  (str/includes? haystack stem)))))]
      (:row-id row))))

(defn- declaration-row
  [declaration artifact-rows]
  (let [{:keys [module source-path source-sha256 source-class source-method annotation
                annotation-line declaration-text]} declaration
        identity (str source-class "#" source-method)
        kind "test-declaration"
        family (behavior-family module source-class identity declaration-text)
        class (classification module source-class kind source-path)]
    (base-row
     {:row-id (declaration-row-id declaration)
      :artifact-kind kind
      :module module
      :case-identity identity
      :source-path source-path
      :source-sha256 source-sha256
      :source-line annotation-line
      :dependencies (concat (module-dependencies module)
                            [(source-row-id source-path)]
                            (inferred-artifact-dependencies declaration artifact-rows))
      :family family
      :classification class
      :observation-kinds
      (observation-kinds module source-class identity declaration-text kind)
      :oracle-kind (oracle-kind kind)
      :detail (str annotation " declaration; "
                   (if (= annotation "ParameterizedTest")
                     (str (count (parameter-values declaration)) " pinned invocations.")
                     "one pinned invocation."))})))

(defn- parameterized-rows
  [declaration declaration-row]
  (mapv
   (fn [value]
     (let [{:keys [module source-path source-sha256 source-class source-method
                   annotation-line declaration-text]} declaration
           identity (str source-class "#" source-method "[generateJavadoc=" value "]")
           kind "parameterized-case"
           family (behavior-family module source-class identity declaration-text)
           class (classification module source-class kind source-path)]
       (base-row
        {:row-id (str "parameter:" (:row-id declaration-row) ":generateJavadoc=" value)
         :artifact-kind kind
         :module module
         :case-identity identity
         :source-path source-path
         :source-sha256 source-sha256
         :source-line annotation-line
         :dependencies (concat (module-dependencies module) [(:row-id declaration-row)])
         :family family
         :classification class
         :observation-kinds
         (observation-kinds module source-class identity declaration-text kind)
         :oracle-kind (oracle-kind kind)
         :detail (str "@ValueSource boolean invocation generateJavadoc=" value ".")})))
   (parameter-values declaration)))

(defn- callable-name
  [extension line]
  (method-at-line extension line))

(defn- discover-helper-declarations
  [{:keys [module path source-path source-sha256 lines] :as source} declarations]
  (if-not (= ".kt" (source-extension path))
    []
    (let [extension (source-extension path)
          class-name (source-class source)
          test-lines (set (map :method-line declarations))]
      (->> lines
           (keep-indexed
            (fn [index line]
              (let [line-number (inc index)
                    name (callable-name extension line)]
                (when (and name
                           (not (contains? test-lines line-number))
                           (not (reserved-call-names name)))
                  (let [kind "helper-declaration"
                        identity (str class-name "#" name "@" line-number)
                        family "test-infrastructure"
                        class (classification module class-name kind source-path)]
                    (base-row
                     {:row-id (helper-row-id source-path line-number name)
                      :artifact-kind kind
                      :module module
                      :case-identity identity
                      :source-path source-path
                      :source-sha256 source-sha256
                      :source-line line-number
                      :dependencies (concat (module-dependencies module)
                                            [(source-row-id source-path)])
                      :family family
                      :classification class
                      :observation-kinds "test-infrastructure-provenance"
                      :oracle-kind (oracle-kind kind)
                      :detail "Unannotated callable used by scoped upstream test evidence."}))))))
           vec))))

(defn- java-helper-row
  [{:keys [module source-path source-sha256] :as source} line-number name]
  (let [class-name (source-class source)
        kind "helper-declaration"
        identity (str class-name "#" name "@" line-number)
        class (classification module class-name kind source-path)]
    (base-row
     {:row-id (helper-row-id source-path line-number name)
      :artifact-kind kind
      :module module
      :case-identity identity
      :source-path source-path
      :source-sha256 source-sha256
      :source-line line-number
      :dependencies (concat (module-dependencies module) [(source-row-id source-path)])
      :family "test-infrastructure"
      :classification class
      :observation-kinds "test-infrastructure-provenance"
      :oracle-kind (oracle-kind kind)
      :detail "Java callable discovered from the no-classpath Spoon source model."})))

(defn- discover-java-helper-declarations
  [sources declarations]
  (let [java-sources (filterv #(= ".java" (source-extension (:path %))) sources)]
    (if (empty? java-sources)
      []
      (let [launcher (Launcher.)
            _ (doseq [{:keys [path]} java-sources] (.addInputResource launcher (str path)))
            _ (.setNoClasspath (.getEnvironment launcher) true)
            _ (.buildModel launcher)
            source-index (into {} (map (juxt #(str (.toAbsolutePath ^Path (:path %))) identity)
                                       java-sources))
            test-lines (set (for [{:keys [path method-line]} declarations
                                  :when (= ".java" (source-extension path))]
                              [(str (.toAbsolutePath ^Path path)) method-line]))
            root-package (.getRootPackage (.getModel launcher))
            members (concat (.getElements root-package (TypeFilter. CtMethod))
                            (.getElements root-package (TypeFilter. CtConstructor)))]
        (->> members
             (keep
              (fn [member]
                (let [position (.getPosition member)]
                  (when (.isValidPosition position)
                    (let [file-path (str (.toAbsolutePath (.toPath (.getFile position))))
                          line-number (.getLine position)
                          source (get source-index file-path)
                          name (if (instance? CtConstructor member)
                                 (.getSimpleName (.getDeclaringType member))
                                 (.getSimpleName member))]
                      (when (and source (not (contains? test-lines [file-path line-number])))
                        (java-helper-row source line-number name)))))))
             (sort-by :row-id)
             vec)))))

(defn- discover-support-types
  [{:keys [module path source-path source-sha256 lines] :as source}]
  (if-not (source-file? path)
    []
    (let [class-name (source-class source)
          outer-name (simple-class-name class-name)]
      (->> lines
           (keep-indexed
            (fn [index line]
              (when-let [name (second (re-find type-pattern line))]
                (when-not (= name outer-name)
                  (let [line-number (inc index)
                        kind "support-type"
                        identity (str class-name "$" name)
                        family (behavior-family module class-name identity line)
                        class (classification module class-name kind source-path)]
                    (base-row
                     {:row-id (type-row-id source-path line-number name)
                      :artifact-kind kind
                      :module module
                      :case-identity identity
                      :source-path source-path
                      :source-sha256 source-sha256
                      :source-line line-number
                      :dependencies (concat (module-dependencies module)
                                            [(source-row-id source-path)])
                      :family family
                      :classification class
                      :observation-kinds
                      (observation-kinds module class-name identity line kind)
                      :oracle-kind (oracle-kind kind)
                      :detail "Nested model or support type used by scoped upstream test evidence."}))))))
           vec))))

(defn discover-inventory
  [workspace-root]
  (let [layout (layout workspace-root)
        sources (mapv #(source-data % (:root layout)) (scoped-files layout))
        artifact-rows (mapv artifact-row sources)
        declarations-by-source
        (into {} (map (fn [source] [(:source-path source) (discover-declarations source)]) sources))
        declarations (vec (mapcat #(get declarations-by-source (:source-path %)) sources))
        declaration-rows (mapv #(declaration-row % artifact-rows) declarations)
        parameter-rows (vec (mapcat parameterized-rows declarations declaration-rows))
        helper-rows (vec (concat
                          (mapcat #(discover-helper-declarations
                                    % (get declarations-by-source (:source-path %)))
                                  sources)
                          (discover-java-helper-declarations sources declarations)))
        type-rows (vec (mapcat discover-support-types sources))]
    (->> (concat artifact-rows declaration-rows parameter-rows helper-rows type-rows)
         (sort-by :row-id)
         vec)))

(defn- tsv-line
  [columns row]
  (str/join "\t" (map #(get row (keyword %) "") columns)))

(defn inventory-text
  [rows]
  (str inventory-magic "\t" pinned-upstream-revision "\n"
       (str/join "\t" inventory-columns) "\n"
       (apply str (map #(str (tsv-line inventory-columns %) "\n") rows))))

(defn write-inventory!
  [workspace-root]
  (let [{:keys [inventory contract-dir] :as layout} (layout workspace-root)
        rows (discover-inventory workspace-root)]
    (Files/createDirectories contract-dir (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString inventory (inventory-text rows) StandardCharsets/UTF_8
                       (make-array java.nio.file.OpenOption 0))
    {:layout layout :rows rows :inventory inventory}))

(defn- read-tsv
  [^Path file columns]
  (when-not (regular-file? file)
    (fail! "A schema/binding contract file is missing"
           {:kind :missing-schema-binding-contract :path (str file)}))
  (let [lines (str/split-lines (Files/readString file StandardCharsets/UTF_8))
        header (vec (str/split (or (first lines) "") #"\t" -1))]
    (when-not (= columns header)
      (fail! "A schema/binding contract file has an unknown column schema"
             {:kind :schema-binding-contract-columns
              :path (str file) :expected columns :actual header}))
    (mapv
     (fn [index line]
       (let [fields (str/split line #"\t" -1)]
         (when-not (= (count columns) (count fields))
           (fail! "A schema/binding contract row has the wrong field count"
                  {:kind :schema-binding-contract-field-count
                   :path (str file) :line (+ index 2)
                   :expected (count columns) :actual (count fields)}))
         (zipmap (map keyword columns) fields)))
     (range)
     (rest lines))))

(defn read-inventory
  [inventory]
  (let [inventory (paths/absolute inventory)
        lines (str/split-lines (Files/readString inventory StandardCharsets/UTF_8))
        [magic revision & _] (str/split (or (first lines) "") #"\t" -1)]
    (when-not (and (= inventory-magic magic) (= pinned-upstream-revision revision))
      (fail! "The schema/binding inventory has stale magic or revision"
             {:kind :schema-binding-inventory-revision
              :path (str inventory)
              :expected [inventory-magic pinned-upstream-revision]
              :actual [magic revision]}))
    (let [payload (str (str/join "\n" (rest lines)) "\n")
          temporary (Files/createTempFile "schema-binding-inventory" ".tsv"
                                          (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/writeString temporary payload StandardCharsets/UTF_8
                           (make-array java.nio.file.OpenOption 0))
        (read-tsv temporary inventory-columns)
        (finally (Files/deleteIfExists temporary))))))

(defn read-observations
  [observations]
  (read-tsv (paths/absolute observations) observation-columns))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn compare-inventory!
  [expected actual]
  (let [expected-index (into {} (map (juxt :row-id identity) expected))
        actual-index (into {} (map (juxt :row-id identity) actual))
        missing (sort (set/difference (set (keys expected-index)) (set (keys actual-index))))
        new (sort (set/difference (set (keys actual-index)) (set (keys expected-index))))]
    (when (seq missing)
      (fail! "Live discovery is missing pinned schema/binding inventory rows"
             {:kind :missing-schema-binding-inventory-rows :missing missing}))
    (when (seq new)
      (fail! "Live discovery found new unpinned schema/binding inventory rows"
             {:kind :new-schema-binding-inventory-rows :new new}))
    (doseq [row-id (sort (keys expected-index))]
      (when-not (= (get expected-index row-id) (get actual-index row-id))
        (fail! "A live schema/binding inventory row differs from its pinned contract"
               {:kind :stale-schema-binding-inventory-row
                :row-id row-id
                :expected (get expected-index row-id)
                :actual (get actual-index row-id)})))
    {:matched (count expected)}))

(defn compare-observations!
  [expected actual]
  (let [expected-index (into {} (map (juxt :observation-kind identity) expected))
        actual-index (into {} (map (juxt :observation-kind identity) actual))]
    (when-not (= (set (keys expected-index)) (set (keys actual-index)))
      (fail! "Observation kind discovery differs from the pinned contract"
             {:kind :schema-binding-observation-set
              :expected (sort (keys expected-index))
              :actual (sort (keys actual-index))}))
    (doseq [kind (sort (keys expected-index))]
      (when-not (= (get expected-index kind) (get actual-index kind))
        (fail! "A deterministic observation definition was perturbed"
               {:kind :schema-binding-observation-perturbation
                :observation-kind kind
                :expected (get expected-index kind)
                :actual (get actual-index kind)})))
    {:matched (count expected)}))

(defn- validate-rows!
  [rows observations]
  (let [duplicate-ids (duplicate-values (map :row-id rows))
        row-ids (set (map :row-id rows))
        observation-ids (set (map :observation-kind observations))
        duplicate-observations (duplicate-values (map :observation-kind observations))]
    (when (seq duplicate-ids)
      (fail! "The schema/binding inventory contains duplicate row identities"
             {:kind :duplicate-schema-binding-inventory-row :duplicates duplicate-ids}))
    (when (seq duplicate-observations)
      (fail! "The schema/binding contract contains duplicate observation kinds"
             {:kind :duplicate-schema-binding-observation
              :duplicates duplicate-observations}))
    (when-not (set/subset? required-observation-kinds observation-ids)
      (fail! "The schema/binding contract omits required deterministic observation kinds"
             {:kind :missing-schema-binding-observation-kinds
              :missing (sort (set/difference required-observation-kinds observation-ids))}))
    (doseq [row rows]
      (when-not (artifact-kinds (:artifact-kind row))
        (fail! "A schema/binding inventory row has an unknown artifact kind"
               {:kind :unknown-schema-binding-artifact-kind :row row}))
      (when-not (product-classifications (:product-classification row))
        (fail! "A schema/binding inventory row is unclassified"
               {:kind :unclassified-schema-binding-inventory-row :row row}))
      (when (some str/blank? (map row (map keyword inventory-columns)))
        (fail! "A schema/binding inventory row contains a blank required field"
               {:kind :blank-schema-binding-inventory-field :row row}))
      (when-not (re-matches #"[0-9a-f]{64}" (:source-sha256 row))
        (fail! "A schema/binding inventory row has an invalid source hash"
               {:kind :invalid-schema-binding-source-hash :row row}))
      (let [unknown-observations
            (set/difference (set (str/split (:observation-kinds row) #";")) observation-ids)]
        (when (seq unknown-observations)
          (fail! "A schema/binding inventory row references an unknown observation kind"
                 {:kind :unknown-schema-binding-observation-kind
                  :row-id (:row-id row) :unknown (sort unknown-observations)})))
      (doseq [dependency (remove #(str/starts-with? % "upstream:")
                                 (str/split (:dependencies row) #";"))]
        (when-not (or (= dependency "-") (contains? row-ids dependency))
          (fail! "A schema/binding inventory dependency is unresolved"
                 {:kind :unresolved-schema-binding-inventory-dependency
                  :row-id (:row-id row) :dependency dependency})))
      (when (= "user-approved-excluded-surface" (:product-classification row))
        (when-not (and (str/includes? (:scope-basis row) "product-goal.md")
                       (str/includes? (:scope-basis row) "port-scope.md"))
          (fail! "An excluded inventory row lacks exact user-approved scope basis"
                 {:kind :schema-binding-exclusion-without-scope-basis :row row}))))
    (let [referenced (set (mapcat #(str/split (:observation-kinds %) #";") rows))
          unused (set/difference required-observation-kinds referenced)]
      (when (seq unused)
        (fail! "Required observation kinds have no inventory evidence"
               {:kind :unused-schema-binding-observation-kinds :unused (sort unused)})))
    rows))

(defn validate-contract!
  ([] (validate-contract! (paths/workspace-root)))
  ([workspace-root]
   (let [{:keys [inventory observations] :as layout} (layout workspace-root)
         _ (verify-pinned-revision! layout)
         pinned (read-inventory inventory)
         live-first (discover-inventory workspace-root)
         live-second (discover-inventory workspace-root)
         observation-rows (read-observations observations)
         _ (validate-rows! pinned observation-rows)
         first-comparison (compare-inventory! pinned live-first)
         second-comparison (compare-inventory! pinned live-second)
         first-oracle (inventory-text live-first)
         second-oracle (inventory-text live-second)]
     (when-not (= first-oracle second-oracle)
       (fail! "Repeated upstream source discovery was nondeterministic"
              {:kind :nondeterministic-schema-binding-source-oracle}))
     {:layout layout
      :rows pinned
      :observations observation-rows
      :summary
      {:upstream-revision pinned-upstream-revision
       :rows (count pinned)
       :artifacts (frequencies (map :artifact-kind pinned))
       :modules (frequencies (map :upstream-module pinned))
       :classifications (frequencies (map :product-classification pinned))
       :behavior-families (count (set (map :behavior-family pinned)))
       :observation-kinds (count observation-rows)
       :first-live-match (:matched first-comparison)
       :second-live-match (:matched second-comparison)
       :oracle-sha256 (sha256-bytes (.getBytes first-oracle StandardCharsets/UTF_8))}})))
