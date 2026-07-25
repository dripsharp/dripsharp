(ns dripsharp.pkl-core-test-contract
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util Base64]))

(def ^:private manifest-magic "DRIPSHARP_PKL_CORE_TEST_CONTRACT_V1")

(def pinned-upstream-revision
  "f7cac257ade5775c1dfc255f4fda2eacc296e9d0")

(def ^:private expected-source-count 86)
(def ^:private expected-naive-test-source-count 85)
(def ^:private expected-audit-token-count 585)
(def ^:private expected-active-source-count 84)
(def ^:private expected-declaration-count 558)
(def ^:private expected-case-count 605)

(def source-columns
  ["source-path"
   "source-sha256"
   "source-disposition"
   "audit-token-count"
   "audit-only-token-count"
   "active-declaration-count"
   "discovered-case-count"])

(def declaration-columns
  ["declaration-id"
   "annotation"
   "source-path"
   "source-sha256"
   "annotation-line"
   "method-line"
   "source-method"
   "discovered-case-count"])

(def case-columns
  ["case-id"
   "junit-unique-id"
   "junit-parent-id"
   "case-kind"
   "display-name"
   "source-class"
   "source-method"
   "source-path"
   "source-sha256"
   "source-line"
   "declaration-id"
   "behavior-family"
   "product-classification"
   "scope-basis"
   "fixtures"
   "environment-requirements"
   "platform-conditions"
   "expected-outcome"
   "pinned-discovery-status"
   "pinned-discovery-reason"
   "existing-evidence"
   "execution-owner"])

(def ^:private raw-columns
  ["event" "unique-id" "parent-id" "descriptor-type" "display-name" "legacy-name"
   "source-kind" "source-class" "source-method" "source-parameters" "status" "reason"
   "os-name" "os-arch" "java-version"])

(def ^:private annotations #{"Test" "ParameterizedTest" "RepeatedTest"})

(def ^:private case-kinds
  #{"test" "parameterized-invocation" "repeated-invocation"})

(def ^:private behavior-families
  #{"evaluation-runtime"
    "excluded-cli-command"
    "excluded-cli-repl"
    "excluded-cli-test-reporting"
    "excluded-format-transport"
    "loading-security-project-package"
    "parser-analysis"
    "public-api-platform"
    "schema-binding"
    "test-infrastructure"
    "value-model-rendering"})

(def ^:private product-classifications
  #{"idiomatic-dotnet-adaptation"
    "in-scope-mixed-excluded-surface"
    "jvm-shared-product-behavior"
    "test-infrastructure-only-mechanics"
    "user-approved-excluded-surface"})

(def ^:private expected-outcomes
  #{"assertions-succeed"
    "enabled-on-windows"
    "external-reader-path-conditional"
    "upstream-explicitly-disabled"})

(def ^:private evidence-ids
  #{"binding" "core" "language" "loading" "parser" "public-api" "schema-codegen"})

(def ^:private execution-owners
  #{"approved-exclusion-audit"
    "complete-pkl-core-runner"
    "test-infrastructure-audit"})

(def ^:private loose-token-pattern #"@(ParameterizedTest|RepeatedTest|Test)")
(def ^:private active-annotation-pattern
  #"^\s*@(ParameterizedTest|RepeatedTest|Test)(?:\s|\(|$)")
(def ^:private method-pattern
  #"\bfun\s+(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))")

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-pkl-core-test-contract)))))

(defn- portable-path
  [^Path root ^Path path]
  (-> (str (.relativize root (.normalize path)))
      (str/replace "\\" "/")))

(defn- walk-paths
  [^Path root predicate]
  (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter predicate)
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

(defn- layout
  [workspace-root]
  (let [root (paths/absolute workspace-root)
        upstream (paths/resolve-path root "research" "pkl")]
    {:root root
     :upstream upstream
     :test-root (paths/resolve-path upstream "pkl-core" "src" "test" "kotlin")
     :manifest (paths/resolve-path root "validation"
                                   "pkl-core-test-contract" "PklCoreTestContract.tsv")
     :contract-dir (paths/resolve-path root "validation"
                                       "pkl-core-test-contract")
     :init-script (paths/resolve-path root "gradle"
                                      "pkl-core-test-contract.gradle")}))

(defn- verify-pinned-revision!
  [{:keys [root upstream]}]
  (let [gitlink (command-output {:command ["git" "rev-parse" "HEAD:research/pkl"]
                                 :directory root})
        checkout (command-output {:command ["git" "rev-parse" "HEAD"]
                                  :directory upstream})]
    (doseq [[subject actual] [[:gitlink gitlink] [:checkout checkout]]]
      (when-not (= pinned-upstream-revision actual)
        (fail! "The Pkl.Core test contract upstream revision drifted"
               {:kind :pkl-core-test-revision-drift
                :subject subject
                :expected pinned-upstream-revision
                :actual actual})))
    pinned-upstream-revision))

(defn- source-relative-path
  [source-class]
  (let [outer-class (first (str/split source-class #"\$"))]
    (str "pkl-core/src/test/kotlin/" (str/replace outer-class "." "/") ".kt")))

(defn- source-files
  [{:keys [test-root]}]
  (->> (walk-paths test-root
                   #(and (Files/isRegularFile ^Path % (make-array LinkOption 0))
                         (str/ends-with? (str (.getFileName ^Path %)) ".kt")
                         (not (str/starts-with? (str (.getFileName ^Path %))
                                                "LanguageSnippetTests"))))
       (keep (fn [file]
               (let [text (Files/readString file StandardCharsets/UTF_8)]
                 (when (re-find loose-token-pattern text)
                   {:path file :text text :lines (vec (str/split-lines text))}))))
       vec))

(defn- active-annotation
  [line]
  (second (re-find active-annotation-pattern line)))

(defn- audit-token-disposition
  [line]
  (cond
    (active-annotation line) "active-declaration"
    (str/includes? line "@TestInstance") "lifecycle-annotation-not-a-test"
    :else "commented-code-not-a-test"))

(defn- audit-tokens
  [{:keys [lines]}]
  (vec
   (mapcat
    (fn [index line]
      (for [[_ annotation] (re-seq loose-token-pattern line)]
        {:line (inc index)
         :annotation annotation
         :disposition (audit-token-disposition line)}))
    (range)
    lines)))

(defn- next-method
  [lines annotation-index]
  (some
   (fn [index]
     (when-let [[_ backtick-name ordinary-name] (re-find method-pattern (nth lines index))]
       {:method (or backtick-name ordinary-name)
        :method-line (inc index)}))
   (range (inc annotation-index) (min (count lines) (+ annotation-index 40)))))

(defn- source-declarations
  [upstream {:keys [path text lines]}]
  (let [relative (portable-path upstream path)
        source-hash (sha256-file path)
        annotation-indexes (keep-indexed
                            (fn [index line]
                              (when (active-annotation line) index))
                            lines)]
    (mapv
     (fn [position annotation-index]
       (let [annotation-line (inc annotation-index)
             annotation (active-annotation (nth lines annotation-index))
             method (next-method lines annotation-index)
             next-index (or (nth annotation-indexes (inc position) nil) (count lines))]
         (when-not method
           (fail! "An active JUnit annotation has no following Kotlin test method"
                  {:kind :unresolved-pkl-core-test-declaration
                   :source relative :line annotation-line}))
         (merge
          {:declaration-id (str relative ":" annotation-line)
           :annotation annotation
           :source-path relative
           :source-sha256 source-hash
           :annotation-line (str annotation-line)
           :declaration-text (str/join "\n" (subvec lines annotation-index next-index))
           :source-text text}
          (update method :method-line str)
          {:source-method (:method method)})))
     (range)
     annotation-indexes)))

(defn- static-inventory
  [layout]
  (let [sources (source-files layout)
        declarations (vec (mapcat #(source-declarations (:upstream layout) %) sources))
        by-source (group-by :source-path declarations)
        source-model
        (mapv
         (fn [{:keys [path] :as source}]
           (let [relative (portable-path (:upstream layout) path)
                 tokens (audit-tokens source)
                 active (get by-source relative [])]
             {:source-path relative
              :source-sha256 (sha256-file path)
              :source-disposition (if (seq active)
                                    "active-junit-source"
                                    "commented-legacy-non-junit-source")
              :audit-token-count (str (count tokens))
              :audit-only-token-count
              (str (count (remove #(= "active-declaration" (:disposition %)) tokens)))
              :active-declaration-count (str (count active))}))
         sources)]
    {:sources source-model
     :declarations declarations
     :source-data (into {} (map (juxt #(portable-path (:upstream layout) (:path %)) identity)
                                sources))}))

(defn- decode-field
  [value]
  (String. (.decode (Base64/getUrlDecoder) value) StandardCharsets/UTF_8))

(defn read-raw-discovery
  [raw]
  (let [raw (paths/absolute raw)
        lines (str/split-lines (Files/readString raw StandardCharsets/UTF_8))
        header (vec (str/split (or (first lines) "") #"\t" -1))]
    (when-not (= raw-columns header)
      (fail! "The Pkl.Core JUnit discovery stream has an unknown schema"
             {:kind :invalid-pkl-core-raw-schema
              :expected raw-columns :actual header}))
    (mapv
     (fn [index line]
       (let [fields (str/split line #"\t" -1)]
         (when-not (= (count raw-columns) (count fields))
           (fail! "A Pkl.Core JUnit discovery row has the wrong field count"
                  {:kind :malformed-pkl-core-raw-row
                   :line (+ index 2) :actual (count fields)}))
         (zipmap (map keyword raw-columns) (map decode-field fields))))
     (range)
     (rest lines))))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn- declaration-class-score
  [declaration nested-class]
  (let [needle (str "class " nested-class)
        prefix (subs (:source-text declaration) 0
                     (.indexOf ^String (:source-text declaration)
                               (:declaration-text declaration)))
        position (.lastIndexOf ^String prefix needle)]
    (when-not (neg? position)
      (- (count prefix) position))))

(defn- select-declaration
  [candidates source-class source-method]
  (cond
    (= 1 (count candidates)) (first candidates)
    (empty? candidates)
    (fail! "A discovered JUnit case has no active Kotlin declaration"
           {:kind :unreconciled-pkl-core-junit-case
            :source-class source-class :source-method source-method})
    :else
    (let [nested-class (last (str/split source-class #"\$"))
          ranked (->> candidates
                      (keep (fn [candidate]
                              (when-let [score (declaration-class-score candidate nested-class)]
                                [score candidate])))
                      (sort-by first))]
      (if (and (seq ranked)
               (or (= 1 (count ranked))
                   (< (ffirst ranked) (first (second ranked)))))
        (second (first ranked))
        (fail! "A discovered JUnit case maps ambiguously to Kotlin declarations"
               {:kind :ambiguous-pkl-core-test-declaration
                :source-class source-class :source-method source-method
                :candidates (mapv :declaration-id candidates)})))))

(defn- joined-list
  [values]
  (if (seq values) (str/join ";" (sort (set values))) "-"))

(defn- sanitized
  [value]
  (-> value
      (str/replace #"[\t\r\n]+" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- excluded-kind
  [source-class source-method]
  (cond
    (= source-class "org.pkl.core.YamlRendererTest") :yaml
    (and (= source-class "org.pkl.core.EvaluateOutputTextTest")
         (= source-method "render YAML")) :yaml
    (= source-class "org.pkl.core.PklBinaryDecoderTest") :binary
    (contains? #{"org.pkl.core.messaging.BaseMessagePackCodecTest"
                 "org.pkl.core.externalreader.MessagePackCodecTest"}
               source-class) :messagepack-server
    (= source-class "org.pkl.core.ReplServerTest") :cli-repl
    (= source-class "org.pkl.core.runtime.CommandSpecParserTest") :cli-command
    (contains? #{"org.pkl.core.stdlib.MinimalReportTest"
                 "org.pkl.core.stdlib.SimpleReportTest"}
               source-class) :cli-test-reporting
    :else nil))

(defn- mixed-pkl-binary-evaluator?
  [source-class source-method]
  (and (= source-class "org.pkl.core.EvaluatorTest")
       (= source-method "nested pkl-binary rendering produces correct results")))

(defn- behavior-family
  [source-class source-method]
  (cond
    (= source-class "org.pkl.core.RepositoryHygiene") "test-infrastructure"
    (= :cli-repl (excluded-kind source-class source-method)) "excluded-cli-repl"
    (= :cli-command (excluded-kind source-class source-method)) "excluded-cli-command"
    (= :cli-test-reporting (excluded-kind source-class source-method))
    "excluded-cli-test-reporting"
    (excluded-kind source-class source-method) "excluded-format-transport"
    (or (str/includes? source-class ".parser.")
        (str/includes? source-class ".ast.builder.")
        (= source-class "org.pkl.core.AnalyzerTest")
        (= source-class "org.pkl.core.runtime.CommandSpecParserTest")) "parser-analysis"
    (or (re-find #"[.](?:module|resource|project|packages|http|settings)[.]" source-class)
        (= source-class "org.pkl.core.SecurityManagersTest")
        (and (= source-class "org.pkl.core.EvaluatorTest")
             (re-find #"(?i)import|project|dependency|file|uri|root dir" source-method)))
    "loading-security-project-package"
    (contains? #{"org.pkl.core.EvaluateSchemaTest"
                 "org.pkl.core.PClassInfoTest"
                 "org.pkl.core.PModuleTest"
                 "org.pkl.core.PObjectTest"
                 "org.pkl.core.PairTest"} source-class) "schema-binding"
    (or (contains? #{"org.pkl.core.EvaluatorBuilderTest"
                     "org.pkl.core.PklInfoTest"
                     "org.pkl.core.PlatformTest"
                     "org.pkl.core.ReleaseTest"
                     "org.pkl.core.VersionTest"} source-class)
        (str/starts-with? source-class "org.pkl.core.EvaluateOutput")
        (= source-class "org.pkl.core.EvaluateMultipleFileOutputTest")) "public-api-platform"
    (or (re-find #"(?:DataSize|Duration|Renderer|PNull|PList)Test$" source-class)
        (= source-class "org.pkl.core.runtime.VmValueRendererTest")) "value-model-rendering"
    :else "evaluation-runtime"))

(defn- idiomatic-adaptation?
  [source-class]
  (or (re-find #"[.](?:module|resource|project|packages|http|settings)[.]" source-class)
      (re-find #"[.](?:FileSystemManager|PathConverterSupport|PathResolver|IoUtils)Test(?:\$|$)"
               source-class)
      (contains? #{"org.pkl.core.EvaluatorBuilderTest"
                   "org.pkl.core.PClassInfoTest"
                   "org.pkl.core.PklInfoTest"
                   "org.pkl.core.PModuleTest"
                   "org.pkl.core.PObjectTest"
                   "org.pkl.core.PairTest"
                   "org.pkl.core.PlatformTest"
                   "org.pkl.core.ReleaseTest"
                   "org.pkl.core.VersionTest"}
                 source-class)))

(defn- classification
  [source-class source-method display-name]
  (let [excluded (excluded-kind source-class source-method)]
    (cond
      (= source-class "org.pkl.core.RepositoryHygiene")
      {:product-classification "test-infrastructure-only-mechanics"
       :scope-basis
       "targets/pkl/product-goal.md#user-approved-product-exclusions:build-benchmark-and-test-infrastructure-as-shipped-product-surface;test-evidence-retained"
       :execution-owner "test-infrastructure-audit"}

      (mixed-pkl-binary-evaluator? source-class source-method)
      {:product-classification "in-scope-mixed-excluded-surface"
       :scope-basis
       (str "targets/pkl/product-goal.md#product-target:core-Pkl-evaluation+value-model+runtime+custom-resource-loading;"
            "targets/pkl/product-goal.md#user-approved-product-exclusions:MessagePack-and-Pkl-binary-transport-support-only;"
            "targets/pkl/port-scope.md#explicit-scope-decisions:MessagePack-support-is-out-of-scope;"
            "in-scope-evaluator+value-model+custom-resource-observation;"
            "excluded-pkl-binary-transport-is-not-a-case-exclusion")
       :execution-owner "complete-pkl-core-runner"}

      (= excluded :yaml)
      {:product-classification "user-approved-excluded-surface"
       :scope-basis
       "targets/pkl/product-goal.md#user-approved-product-exclusions:YAML-support;targets/pkl/port-scope.md#explicit-scope-decisions:YAML-support-is-out-of-scope"
       :execution-owner "approved-exclusion-audit"}

      (contains? #{:binary :messagepack-server} excluded)
      {:product-classification "user-approved-excluded-surface"
       :scope-basis
       (str "targets/pkl/product-goal.md#user-approved-product-exclusions:MessagePack-and-Pkl-binary-transport-support"
            (when (= excluded :messagepack-server) "+Pkl-server-support")
            ";targets/pkl/port-scope.md#explicit-scope-decisions:MessagePack-support-is-out-of-scope")
       :execution-owner "approved-exclusion-audit"}

      (contains? #{:cli-repl :cli-command :cli-test-reporting} excluded)
      {:product-classification "user-approved-excluded-surface"
       :scope-basis
       "targets/pkl/product-goal.md#user-approved-product-exclusions:CLI-product-support;targets/pkl/port-scope.md#explicit-scope-decisions:CLI-support-is-out-of-scope"
       :execution-owner "approved-exclusion-audit"}

      (and (= source-class "org.pkl.core.stdlib.ReflectModuleTest")
           (str/includes? display-name "pkl:yaml"))
      {:product-classification "jvm-shared-product-behavior"
       :scope-basis
       "targets/pkl/product-goal.md#product-target:core-runtime+schema-reflection;mixed-case-retains-in-scope-reflection-observation;YAML-output-exclusion-not-applied"
       :execution-owner "complete-pkl-core-runner"}

      (idiomatic-adaptation? source-class)
      {:product-classification "idiomatic-dotnet-adaptation"
       :scope-basis
       "targets/pkl/product-goal.md#product-target:idiomatic-public-.NET-APIs+normal-.NET-loading;targets/pkl/port-scope.md#product-target:JVM-specific-APIs-use-.NET-equivalents"
       :execution-owner "complete-pkl-core-runner"}

      :else
      {:product-classification "jvm-shared-product-behavior"
       :scope-basis
       "targets/pkl/product-goal.md#product-target:core-Pkl-parsing+evaluation+value-model+module-loading+runtime-behavior"
       :execution-owner "complete-pkl-core-runner"})))

(defn- family-evidence
  [family]
  (joined-list
   (case family
     "parser-analysis" ["parser" "language"]
     "loading-security-project-package" ["loading" "public-api" "language"]
     "schema-binding" ["schema-codegen" "binding" "public-api"]
     "public-api-platform" ["public-api" "core"]
     "value-model-rendering" ["core" "public-api" "language"]
     "evaluation-runtime" ["language" "core"]
     "excluded-format-transport" ["core" "language"]
     "excluded-cli-command" ["core" "public-api"]
     "excluded-cli-repl" ["core" "public-api"]
     "excluded-cli-test-reporting" ["core" "public-api"]
     "test-infrastructure" ["language"])))

(defn- fixtures
  [declaration]
  (let [source (:source-text declaration)
        method (:declaration-text declaration)]
    (joined-list
     (cond-> []
       (re-find #"getResource|modulePath|src/test/resources" (str source "\n" method))
       (conj "pkl-core/src/test/resources")
       (str/includes? source "PackageServer")
       (conj "pkl-commons-test/src/main/files/packages")
       (re-find #"externalReader|ExternalReader" (str source "\n" method))
       (conj "pkl-core/build/fixtures/externalreader")
       (str/includes? source "LanguageSnippetTests")
       (conj "pkl-core/src/test/files/LanguageSnippetTests")))))

(defn- environment-requirements
  [declaration]
  (let [source (:source-text declaration)
        method (:declaration-text declaration)
        combined (str source "\n" method)]
    (joined-list
     (cond-> ["pinned-pkl-core-test-task"]
       (str/includes? method "@TempDir") (conj "temporary-filesystem")
       (str/includes? combined "System.getenv") (conj "environment-variables")
       (str/includes? combined "System.getProperty") (conj "system-properties")
       (str/includes? combined "PackageServer")
       (conj "loopback-package-server" "test-certificate")
       (re-find #"HttpServer|WireMock|HttpClientTest" combined)
       (conj "loopback-http-server" "test-certificate")
       (re-find #"externalReader|ExternalReader" combined)
       (conj "external-reader-process")
       (str/includes? method "simple name off PATH")
       (conj "external-reader-fixture-on-PATH")
       (re-find #"createSymbolicLink|isSymbolicLink" method) (conj "symbolic-link-support")
       (re-find #"ProcessBuilder|external process" method) (conj "process-execution")
       (re-find #"modulePath|getResource" combined) (conj "test-resource-classpath")
       (re-find #"Truffle|truffle" combined) (conj "graal-truffle-runtime")))))

(defn- outcome-contract
  [declaration raw]
  (let [method-text (:declaration-text declaration)
        status (:status raw)
        reason (:reason raw)]
    (cond
      (str/includes? method-text "@EnabledOnOs(OS.WINDOWS)")
      (do
        (when-not (contains? #{"SKIPPED" "SUCCESSFUL"} status)
          (fail! "The Windows-conditional Pkl.Core case had an unexpected outcome"
                 {:kind :unexpected-pkl-core-test-outcome
                  :case (:unique-id raw) :actual status}))
        {:platform-conditions "os=windows"
         :expected-outcome "enabled-on-windows"})

      (re-find #"(?m)^\s*@Disabled(?:\s|\(|$)" method-text)
      (do
        (when-not (= "SKIPPED" status)
          (fail! "An explicitly disabled Pkl.Core case was not skipped"
                 {:kind :unexpected-pkl-core-test-outcome
                  :case (:unique-id raw) :actual status}))
        {:platform-conditions "upstream-@Disabled"
         :expected-outcome "upstream-explicitly-disabled"})

      (and (= (:source-class raw) "org.pkl.core.module.ModuleKeyFactoriesTest")
           (= (:source-method raw)
              "external process -- spawning an executable using a simple name off PATH"))
      (do
        (when-not (contains? #{"ABORTED" "SUCCESSFUL"} status)
          (fail! "The PATH-conditional external-reader case had an unexpected outcome"
                 {:kind :unexpected-pkl-core-test-outcome
                  :case (:unique-id raw) :actual status}))
        {:platform-conditions "all-supported-hosts;requires-fixture-on-PATH"
         :expected-outcome "external-reader-path-conditional"})

      (= "SUCCESSFUL" status)
      {:platform-conditions "all-supported-hosts"
       :expected-outcome "assertions-succeed"}

      (contains? #{"SKIPPED" "ABORTED"} status)
      (fail! "A Pkl.Core JUnit case was silently skipped or aborted"
             {:kind :unexpected-pkl-core-skip
              :case (:unique-id raw) :status status :reason reason})

      :else
      (fail! "A Pkl.Core JUnit case failed during authoritative discovery"
             {:kind :failed-pkl-core-junit-case
              :case (:unique-id raw) :status status :reason reason}))))

(defn- case-kind
  [declaration unique-id]
  (cond
    (= "RepeatedTest" (:annotation declaration)) "repeated-invocation"
    (or (= "ParameterizedTest" (:annotation declaration))
        (str/includes? unique-id "test-template-invocation")) "parameterized-invocation"
    :else "test"))

(defn- build-cases
  [inventory raw-rows]
  (when-let [duplicates (seq (duplicate-values (map :unique-id raw-rows)))]
    (fail! "The Pkl.Core JUnit discovery emitted duplicate identifiers"
           {:kind :duplicate-pkl-core-discovery-id :duplicates duplicates}))
  (let [by-method (group-by (juxt :source-path :source-method)
                            (:declarations inventory))]
    (->> raw-rows
         (mapv
          (fn [raw]
            (when-not (and (= "MethodSource" (:source-kind raw))
                           (= "TEST" (:descriptor-type raw))
                           (contains? #{"finished" "skipped"} (:event raw)))
              (fail! "The non-language Pkl.Core discovery emitted a non-method test row"
                     {:kind :unsupported-pkl-core-junit-descriptor :row raw}))
            (when (str/includes? (:unique-id raw) "LanguageSnippet")
              (fail! "LanguageSnippetTests leaked into the non-language Pkl.Core contract"
                     {:kind :language-snippet-case-leak :case (:unique-id raw)}))
            (let [source-path (source-relative-path (:source-class raw))
                  candidates (get by-method [source-path (:source-method raw)])
                  declaration (select-declaration candidates (:source-class raw)
                                                  (:source-method raw))
                  family (behavior-family (:source-class raw) (:source-method raw))
                  class-data (classification (:source-class raw) (:source-method raw)
                                             (:display-name raw))
                  outcome (outcome-contract declaration raw)
                  unique-id (:unique-id raw)]
              (merge
               {:case-id (str "pkl-core-junit/" (sha256-bytes
                                                 (.getBytes unique-id
                                                            StandardCharsets/UTF_8)))
                :junit-unique-id unique-id
                :junit-parent-id (:parent-id raw)
                :case-kind (case-kind declaration unique-id)
                :display-name (sanitized (:display-name raw))
                :source-class (:source-class raw)
                :source-method (:source-method raw)
                :source-path source-path
                :source-sha256 (:source-sha256 declaration)
                :source-line (:annotation-line declaration)
                :declaration-id (:declaration-id declaration)
                :behavior-family family
                :fixtures (fixtures declaration)
                :environment-requirements (environment-requirements declaration)
                :pinned-discovery-status (:status raw)
                :pinned-discovery-reason (let [reason (sanitized (:reason raw))]
                                           (if (str/blank? reason) "-" reason))
                :existing-evidence (family-evidence family)}
               class-data
               outcome))))
         (sort-by (juxt :source-path #(parse-long (:source-line %)) :junit-unique-id))
         vec)))

(defn- source-and-declaration-counts
  [inventory cases]
  (let [source-counts (frequencies (map :source-path cases))
        declaration-counts (frequencies (map :declaration-id cases))]
    {:sources (mapv #(assoc % :discovered-case-count
                            (str (get source-counts (:source-path %) 0)))
                    (:sources inventory))
     :declarations (mapv #(-> %
                              (select-keys (map keyword declaration-columns))
                              (assoc :discovered-case-count
                                     (str (get declaration-counts (:declaration-id %) 0))))
                         (:declarations inventory))}))

(defn- evidence-metadata
  []
  [["evidence-parser" "validation/differential/UpstreamOracle.java"]
   ["evidence-language"
    "validation/language-snippet-contract/LanguageSnippetContract.tsv"]
   ["evidence-core" "validation/differential/CoreUpstreamOracle.java"]
   ["evidence-loading" "validation/loading-contract/ContractEvidence.tsv"]
   ["evidence-public-api" "validation/public-api-contract/BehaviorContract.tsv"]
   ["evidence-schema-codegen"
    "validation/schema-codegen/ContractEvidence.tsv"]
   ["evidence-binding" "validation/schema-codegen/ContractEvidence.tsv"]])

(defn- static-metadata
  [{:keys [root upstream]}]
  (let [source-hash #(sha256-file (paths/resolve-path root %))
        upstream-hash #(sha256-file (paths/resolve-path upstream %))]
    (vec
     (concat
      [["source-repository" "https://github.com/apple/pkl.git"]
       ["source-gitlink" "research/pkl"]
       ["source-revision" pinned-upstream-revision]
       ["upstream-test-task" ":pkl-core:test"]
       ["excluded-engine" "LanguageSnippetTestsEngine"]
       ["source-count" (str expected-source-count)]
       ["naive-@Test-source-count" (str expected-naive-test-source-count)]
       ["audit-token-count" (str expected-audit-token-count)]
       ["active-source-count" (str expected-active-source-count)]
       ["active-declaration-count" (str expected-declaration-count)]
       ["junit-case-count" (str expected-case-count)]
       ["pkl-core-test-task-sha256" (upstream-hash "pkl-core/pkl-core.gradle.kts")]
       ["junit-test-convention-sha256"
        (upstream-hash "build-logic/src/main/kotlin/pklKotlinTest.gradle.kts")]
       ["listener-source-sha256"
        (source-hash
         "validation/pkl-core-test-contract/PklCoreTestDiscoveryListener.java")]
       ["listener-service-sha256"
        (source-hash
         "validation/pkl-core-test-contract/META-INF/services/org.junit.platform.launcher.TestExecutionListener")]
       ["gradle-init-sha256"
        (source-hash "gradle/pkl-core-test-contract.gradle")]]
      (evidence-metadata)))))

(defn contract-model
  ([raw] (contract-model (paths/workspace-root) raw))
  ([workspace-root raw]
   (let [layout (layout workspace-root)
         _ (verify-pinned-revision! layout)
         inventory (static-inventory layout)
         raw-rows (read-raw-discovery raw)
         cases (build-cases inventory raw-rows)
         counted (source-and-declaration-counts inventory cases)
         hosts (set (map (juxt :os-name :os-arch :java-version) raw-rows))]
     (when-not (= 1 (count hosts))
       (fail! "The Pkl.Core discovery stream contains inconsistent launcher hosts"
              {:kind :inconsistent-pkl-core-discovery-hosts :hosts hosts}))
     {:metadata (conj (static-metadata layout)
                      ["pinned-discovery-host" (str/join "/" (first hosts))])
      :sources (:sources counted)
      :declarations (:declarations counted)
      :cases cases
      :layout layout})))

(defn- safe-field
  [row column]
  (let [value (get row (keyword column))]
    (when-not (and (string? value)
                   (not (str/blank? value))
                   (not (re-find #"[\t\r\n]" value)))
      (fail! "A Pkl.Core test contract field is not safely encodable"
             {:kind :invalid-pkl-core-contract-field
              :column column :value value :row row}))
    value))

(defn render-manifest
  [{:keys [metadata sources declarations cases]}]
  (str manifest-magic "\n"
       (apply str (for [[key value] metadata] (str "meta\t" key "\t" value "\n")))
       "source-columns\t" (str/join "\t" source-columns) "\n"
       (apply str (for [row sources]
                    (str "source\t"
                         (str/join "\t" (map #(safe-field row %) source-columns)) "\n")))
       "declaration-columns\t" (str/join "\t" declaration-columns) "\n"
       (apply str (for [row declarations]
                    (str "declaration\t"
                         (str/join "\t" (map #(safe-field row %) declaration-columns)) "\n")))
       "case-columns\t" (str/join "\t" case-columns) "\n"
       (apply str (for [row cases]
                    (str "case\t"
                         (str/join "\t" (map #(safe-field row %) case-columns)) "\n")))))

(defn generate-manifest!
  ([raw manifest] (generate-manifest! (paths/workspace-root) raw manifest))
  ([workspace-root raw manifest]
   (let [manifest (paths/absolute manifest)
         model (contract-model workspace-root raw)]
     (Files/createDirectories (.getParent manifest) (make-array FileAttribute 0))
     (Files/writeString manifest (render-manifest model) StandardCharsets/UTF_8
                        (make-array OpenOption 0))
     manifest)))

(defn read-manifest
  [manifest]
  (let [manifest (paths/absolute manifest)
        content (Files/readString manifest StandardCharsets/UTF_8)
        lines (str/split-lines content)]
    (when-not (= manifest-magic (first lines))
      (fail! "The Pkl.Core test manifest has the wrong schema marker"
             {:kind :invalid-pkl-core-manifest-schema :actual (first lines)}))
    (loop [remaining (rest lines)
           parsed {:manifest manifest :content content :metadata []
                   :sources [] :declarations [] :cases []}
           columns {}]
      (if-let [line (first remaining)]
        (let [fields (str/split line #"\t" -1)
              kind (first fields)]
          (cond
            (= "meta" kind)
            (do
              (when-not (= 3 (count fields))
                (fail! "Malformed Pkl.Core test metadata" {:line line}))
              (recur (rest remaining)
                     (update parsed :metadata conj [(second fields) (nth fields 2)])
                     columns))

            (str/ends-with? kind "-columns")
            (let [row-kind (subs kind 0 (- (count kind) (count "-columns")))]
              (when (contains? columns row-kind)
                (fail! "The Pkl.Core test manifest repeats a columns row"
                       {:kind :duplicate-pkl-core-columns :row-kind row-kind}))
              (recur (rest remaining) parsed (assoc columns row-kind (vec (rest fields)))))

            (contains? #{"source" "declaration" "case"} kind)
            (let [row-columns (get columns kind)]
              (when-not row-columns
                (fail! "A Pkl.Core contract row appears before its columns"
                       {:kind :malformed-pkl-core-manifest :line line}))
              (when-not (= (count row-columns) (dec (count fields)))
                (fail! "A Pkl.Core contract row has the wrong field count"
                       {:kind :malformed-pkl-core-manifest-row :line line}))
              (recur (rest remaining)
                     (update parsed (keyword (str kind "s")) conj
                             (zipmap (map keyword row-columns) (rest fields)))
                     columns))

            :else
            (fail! "The Pkl.Core test manifest has an unknown row kind"
                   {:kind :malformed-pkl-core-manifest :line line})))
        (assoc parsed :columns columns)))))

(defn- split-list
  [value]
  (if (= "-" value) [] (str/split value #";" -1)))

(defn- validate-count!
  [label expected actual]
  (when-not (= expected actual)
    (fail! (str "The Pkl.Core test contract has the wrong " label " count")
           {:kind :pkl-core-contract-count
            :subject label :expected expected :actual actual})))

(defn- validate-rows!
  [{:keys [sources declarations cases columns] :as parsed}]
  (when-not (= {"source" source-columns
                "declaration" declaration-columns
                "case" case-columns}
               columns)
    (fail! "The Pkl.Core test manifest columns drifted"
           {:kind :pkl-core-contract-columns-drift :actual columns}))
  (validate-count! "source" expected-source-count (count sources))
  (validate-count! "declaration" expected-declaration-count (count declarations))
  (validate-count! "case" expected-case-count (count cases))
  (doseq [[rows field label]
          [[sources :source-path "source path"]
           [declarations :declaration-id "declaration ID"]
           [cases :case-id "case ID"]
           [cases :junit-unique-id "JUnit unique ID"]]]
    (when-let [duplicates (seq (duplicate-values (map field rows)))]
      (fail! (str "The Pkl.Core test contract has duplicate " label " values")
             {:kind :duplicate-pkl-core-contract-row
              :field field :duplicates duplicates})))
  (let [source-by-path (into {} (map (juxt :source-path identity) sources))
        declaration-by-id (into {} (map (juxt :declaration-id identity) declarations))
        cases-by-source (frequencies (map :source-path cases))
        cases-by-declaration (frequencies (map :declaration-id cases))]
    (doseq [source sources]
      (when-not (contains? #{"active-junit-source" "commented-legacy-non-junit-source"}
                           (:source-disposition source))
        (fail! "A Pkl.Core source has no recognized disposition"
               {:kind :unclassified-pkl-core-source :source (:source-path source)}))
      (when-not (= (parse-long (:discovered-case-count source))
                   (get cases-by-source (:source-path source) 0))
        (fail! "A Pkl.Core source case count is stale"
               {:kind :stale-pkl-core-source-count :source (:source-path source)})))
    (doseq [declaration declarations]
      (when-not (annotations (:annotation declaration))
        (fail! "A Pkl.Core declaration has an unknown annotation"
               {:kind :unclassified-pkl-core-declaration
                :declaration (:declaration-id declaration)}))
      (when-not (source-by-path (:source-path declaration))
        (fail! "A Pkl.Core declaration names an unknown source"
               {:kind :missing-pkl-core-source :declaration (:declaration-id declaration)}))
      (let [actual (get cases-by-declaration (:declaration-id declaration) 0)]
        (when (zero? actual)
          (fail! "An active Pkl.Core declaration has no discovered JUnit case"
                 {:kind :silently-undiscovered-pkl-core-declaration
                  :declaration (:declaration-id declaration)}))
        (when-not (= (parse-long (:discovered-case-count declaration)) actual)
          (fail! "A Pkl.Core declaration case count is stale"
                 {:kind :stale-pkl-core-declaration-count
                  :declaration (:declaration-id declaration)}))))
    (doseq [case-data cases]
      (let [declaration (declaration-by-id (:declaration-id case-data))
            source (source-by-path (:source-path case-data))]
        (when-not declaration
          (fail! "A Pkl.Core case names an unknown declaration"
                 {:kind :missing-pkl-core-declaration :case (:case-id case-data)}))
        (when-not source
          (fail! "A Pkl.Core case names an unknown source"
                 {:kind :missing-pkl-core-source :case (:case-id case-data)}))
        (when-not (= (:case-id case-data)
                     (str "pkl-core-junit/"
                          (sha256-bytes (.getBytes (:junit-unique-id case-data)
                                                   StandardCharsets/UTF_8))))
          (fail! "A Pkl.Core case ID is not derived from its JUnit identifier"
                 {:kind :unstable-pkl-core-case-id :case (:case-id case-data)}))
        (when-not (case-kinds (:case-kind case-data))
          (fail! "A Pkl.Core case has no recognized case kind"
                 {:kind :unclassified-pkl-core-case-kind :case (:case-id case-data)}))
        (when-not (behavior-families (:behavior-family case-data))
          (fail! "A Pkl.Core case has no recognized behavior family"
                 {:kind :unclassified-pkl-core-behavior-family :case (:case-id case-data)}))
        (when-not (product-classifications (:product-classification case-data))
          (fail! "A Pkl.Core case has no recognized product classification"
                 {:kind :unclassified-pkl-core-product-scope :case (:case-id case-data)}))
        (when (mixed-pkl-binary-evaluator? (:source-class case-data)
                                           (:source-method case-data))
          (when-not (= "in-scope-mixed-excluded-surface"
                       (:product-classification case-data))
            (fail! "The mixed Pkl-binary evaluator case lost its in-scope observations"
                   {:kind :pkl-core-mixed-evaluator-whole-case-exclusion
                    :case (:case-id case-data)
                    :classification (:product-classification case-data)})))
        (when (= "in-scope-mixed-excluded-surface"
                 (:product-classification case-data))
          (when-not (and (str/includes? (:scope-basis case-data)
                                        "in-scope-evaluator+value-model+custom-resource-observation")
                         (str/includes? (:scope-basis case-data)
                                        "excluded-pkl-binary-transport-is-not-a-case-exclusion"))
            (fail! "A mixed Pkl.Core case does not retain its in-scope observation basis"
                   {:kind :invalid-pkl-core-mixed-scope-basis
                    :case (:case-id case-data)})))
        (when-not (expected-outcomes (:expected-outcome case-data))
          (fail! "A Pkl.Core case has no recognized expected outcome"
                 {:kind :unclassified-pkl-core-expected-outcome :case (:case-id case-data)}))
        (when-not (execution-owners (:execution-owner case-data))
          (fail! "A Pkl.Core case has no execution owner"
                 {:kind :unowned-pkl-core-case :case (:case-id case-data)}))
        (when-not (every? evidence-ids (split-list (:existing-evidence case-data)))
          (fail! "A Pkl.Core case names unknown existing evidence"
                 {:kind :unknown-pkl-core-evidence :case (:case-id case-data)}))
        (when (= "-" (:environment-requirements case-data))
          (fail! "A Pkl.Core case omits its execution environment"
                 {:kind :unexecutable-pkl-core-case :case (:case-id case-data)}))
        (when (= "user-approved-excluded-surface" (:product-classification case-data))
          (when-not (and (str/includes? (:scope-basis case-data)
                                        "targets/pkl/product-goal.md#user-approved-product-exclusions")
                         (str/includes? (:scope-basis case-data)
                                        "targets/pkl/port-scope.md#explicit-scope-decisions"))
            (fail! "A Pkl.Core case cites no user-approved exclusion"
                   {:kind :unapproved-pkl-core-exclusion :case (:case-id case-data)})))
        (when (contains? #{"SKIPPED" "ABORTED"} (:pinned-discovery-status case-data))
          (when (= "-" (:pinned-discovery-reason case-data))
            (fail! "A skipped Pkl.Core case has no explicit reason"
                   {:kind :silent-pkl-core-skip :case (:case-id case-data)})))))
    parsed))

(defn- comparable-source
  [source]
  (dissoc source :discovered-case-count))

(defn- comparable-declaration
  [declaration]
  (dissoc declaration :discovered-case-count))

(defn- verify-static-inventory!
  [layout parsed]
  (let [inventory (static-inventory layout)
        expected-sources (mapv comparable-source (:sources inventory))
        actual-sources (mapv comparable-source (:sources parsed))
        expected-declarations (mapv #(select-keys % (map keyword (butlast declaration-columns)))
                                    (:declarations inventory))
        actual-declarations (mapv comparable-declaration (:declarations parsed))]
    (when-not (= expected-sources actual-sources)
      (fail! "The Pkl.Core source inventory or source hashes drifted"
             {:kind :stale-pkl-core-source-inventory}))
    (when-not (= expected-declarations actual-declarations)
      (fail! "The Pkl.Core active declaration inventory drifted"
             {:kind :stale-pkl-core-declaration-inventory}))
    parsed))

(defn validate-manifest!
  ([manifest] (validate-manifest! (paths/workspace-root) manifest))
  ([workspace-root manifest]
   (let [layout (layout workspace-root)
         _ (verify-pinned-revision! layout)
         parsed (-> (read-manifest manifest) validate-rows!)
         metadata (into {} (:metadata parsed))
         expected-metadata (into {} (static-metadata layout))]
     (doseq [[key value] expected-metadata]
       (when-not (= value (get metadata key))
         (fail! "The Pkl.Core test manifest provenance drifted"
                {:kind :pkl-core-contract-provenance-drift
                 :key key :expected value :actual (get metadata key)})))
     (when (str/blank? (get metadata "pinned-discovery-host"))
       (fail! "The Pkl.Core test manifest omits its discovery host"
              {:kind :missing-pkl-core-discovery-host}))
     (verify-static-inventory! layout parsed)
     (assoc parsed
            :layout layout
            :summary
            {:sources (count (:sources parsed))
             :active-sources (count (filter #(= "active-junit-source"
                                                (:source-disposition %))
                                            (:sources parsed)))
             :audit-tokens (reduce + (map #(parse-long (:audit-token-count %))
                                          (:sources parsed)))
             :audit-only-tokens (reduce + (map #(parse-long (:audit-only-token-count %))
                                               (:sources parsed)))
             :declarations (count (:declarations parsed))
             :cases (count (:cases parsed))
             :case-kinds (frequencies (map :case-kind (:cases parsed)))
             :statuses (frequencies (map :pinned-discovery-status (:cases parsed)))
             :families (frequencies (map :behavior-family (:cases parsed)))
             :classifications (frequencies (map :product-classification (:cases parsed)))
             :upstream-revision pinned-upstream-revision}))))

(def ^:private discovery-comparison-fields
  (vec (remove #{:pinned-discovery-status :pinned-discovery-reason}
               (map keyword case-columns))))

(defn compare-discovery-cases!
  [expected-cases actual-cases]
  (let [expected-by-id (into {} (map (juxt :junit-unique-id identity) expected-cases))
        actual-by-id (into {} (map (juxt :junit-unique-id identity) actual-cases))
        expected-ids (set (keys expected-by-id))
        actual-ids (set (keys actual-by-id))
        missing (sort (set/difference expected-ids actual-ids))
        added (sort (set/difference actual-ids expected-ids))]
    (when (seq missing)
      (fail! "Previously pinned Pkl.Core JUnit cases were not discovered"
             {:kind :missing-pkl-core-discovered-cases :cases (vec missing)}))
    (when (seq added)
      (fail! "New Pkl.Core JUnit cases are absent from the pinned manifest"
             {:kind :new-pkl-core-discovered-cases :cases (vec added)}))
    (doseq [unique-id (sort expected-ids)]
      (let [expected (select-keys (get expected-by-id unique-id)
                                  discovery-comparison-fields)
            actual (select-keys (get actual-by-id unique-id)
                                discovery-comparison-fields)]
        (when-not (= expected actual)
          (fail! "A pinned Pkl.Core JUnit case changed"
                 {:kind :stale-pkl-core-discovered-case
                  :case unique-id :expected expected :actual actual}))))
    {:matched (count expected-ids)}))

(defn verify-discovery!
  [validated raw]
  (let [inventory (static-inventory (:layout validated))
        actual (build-cases inventory (read-raw-discovery raw))]
    (compare-discovery-cases! (:cases validated) actual)))

(defn verify-contract!
  ([] (verify-contract! {}))
  ([{:keys [workspace-root manifest run-command!]
     :or {run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         layout (layout root)
         manifest (or manifest (:manifest layout))
         validated (validate-manifest! root manifest)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "validation-output"
                                         "pkl-core-test-contract"))
         raw (paths/resolve-path proof-root "junit-discovery.tsv")]
     (run-command!
      {:command ["env" "DRIPSHARP_WORKERS=22" "GRADLE_OPTS=-Xmx28g"
                 "./gradlew" "-I" (str (:init-script layout))
                 ":pkl-core:test" "--console=plain"
                 (str "-Pdripsharp.contractDir=" (:contract-dir layout))
                 (str "-Pdripsharp.discoveryOutput=" raw)]
       :directory (:upstream layout)
       :timeout-ms 3600000})
     (let [comparison (verify-discovery! validated raw)
           summary (assoc (:summary validated) :live-discovery (:matched comparison))]
       (println "Pinned non-language Pkl.Core JUnit contract passed:" (pr-str summary))
       {:summary summary :manifest (paths/absolute manifest) :discovery raw}))))
