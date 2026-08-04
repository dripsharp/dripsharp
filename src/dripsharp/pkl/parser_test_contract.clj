(ns dripsharp.pkl.parser-test-contract
  "Pinned inventory and target-owned adaptation contract for pkl-parser tests."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.core-test-contract :as junit-discovery]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(def ^:private manifest-magic "DRIPSHARP_PKL_PARSER_TEST_CONTRACT_V1")

(def pinned-upstream-revision
  (baseline/upstream-revision :pkl))

(def source-columns
  ["source-path" "source-sha256" "source-role" "language"
   "behavior-disposition" "required-by-cases"])

(def case-columns
  ["case-id" "junit-unique-id" "junit-parent-id" "adapted-invocation-id"
   "case-kind" "display-name" "source-class" "source-method" "source-path"
   "source-sha256" "source-line" "fixture-path" "fixture-sha256"
   "product-classification" "scope-basis" "enabled-state" "disabled-reason"
   "platform-conditions" "expected-observation-sha256" "execution-owner"])

(def ^:private expected-parser-contract
  (:parser-tests (:contracts (baseline/read-baseline :pkl))))
(def ^:private expected-source-count (:sources expected-parser-contract))
(def ^:private expected-junit-case-count (:junit-cases expected-parser-contract))
(def ^:private expected-test-source-count (:test-sources expected-parser-contract))
(def ^:private expected-helper-source-count (:helper-sources expected-parser-contract))
(def ^:private expected-adapted-case-count (:adapted-cases expected-parser-contract))
(def ^:private expected-fixture-invocation-count
  (:fixture-invocations expected-parser-contract))

(def ^:private parser-source-paths
  [{:source-path "pkl-parser/src/test/kotlin/org/pkl/parser/LexerTest.kt"
    :source-role "test-source"
    :language "kotlin"
    :behavior-disposition "adapted-upstream-behavior"}
   {:source-path "pkl-parser/src/test/kotlin/org/pkl/parser/ParserComparisonTest.kt"
    :source-role "test-source"
    :language "kotlin"
    :behavior-disposition "adapted-upstream-behavior"}
   {:source-path "pkl-parser/src/test/kotlin/org/pkl/parser/SpanTest.kt"
    :source-role "test-source"
    :language "kotlin"
    :behavior-disposition "adapted-upstream-behavior"}
   {:source-path "pkl-parser/src/test/kotlin/org/pkl/parser/GenericSexpRenderer.kt"
    :source-role "test-helper"
    :language "kotlin"
    :behavior-disposition "adapted-by-structural-observation"}
   {:source-path "pkl-parser/src/test/kotlin/org/pkl/parser/SexpRenderer.kt"
    :source-role "test-helper"
    :language "kotlin"
    :behavior-disposition "adapted-by-structural-observation"}
   {:source-path "pkl-commons/src/main/kotlin/org/pkl/commons/Paths.kt"
    :source-role "test-helper"
    :language "kotlin"
    :behavior-disposition "adapted-by-dotnet-filesystem-enumeration"}])

(def ^:private comparison-exceptions
  #{"stringError1.pkl"
    "annotationIsNotExpression2.pkl"
    "amendsRequiresParens.pkl"
    "errors/binopDifferentLine.pkl"
    "errors/parser18.pkl"
    "errors/nested1.pkl"
    "errors/invalidCharacterEscape.pkl"
    "errors/invalidCharacterEscape2.pkl"
    "errors/invalidUnicodeEscape.pkl"
    "errors/letExpressionError3.pkl"
    "errors/unterminatedUnicodeEscape.pkl"
    "errors/keywordNotAllowedHere1.pkl"
    "errors/keywordNotAllowedHere2.pkl"
    "errors/keywordNotAllowedHere3.pkl"
    "errors/keywordNotAllowedHere4.pkl"
    "errors/moduleWithHighMinPklVersionAndParseErrors.pkl"
    "errors/underscore.pkl"
    "errors/shebang.pkl"
    "errors/emptyParenthesizedTypeAnnotation.pkl"
    "notAUnionDefault.pkl"
    "multipleDefaults.pkl"
    "modules/invalidModule1.pkl"
    "singleBacktick.pkl"})

(def ^:private ordinary-observation-cases
  {"org.pkl.parser.LexerTest/isRegularIdentifier"
   {:operation :identifier-regular}
   "org.pkl.parser.LexerTest/maybeQuoteIdentifier"
   {:operation :identifier-quote}
   "org.pkl.parser.LexerTest/lexSingleBacktick"
   {:operation :lexer :oracle-id "edge/lexer-single-backtick"}
   "org.pkl.parser.LexerTest/rejectsSentinelBetweenTokens"
   {:operation :lexer :oracle-id "edge/lexer-sentinel-between-tokens"}
   "org.pkl.parser.LexerTest/lineContinuationWithCRLF"
   {:operation :lexer :oracle-id "edge/lexer-line-continuation-crlf"}
   "org.pkl.parser.LexerTest/lineContinuationWithCR"
   {:operation :lexer :oracle-id "edge/lexer-line-continuation-cr"}
   "org.pkl.parser.LexerTest/lineContinuationWhitespaceErrorWithCRLF"
   {:operation :lexer :oracle-id "edge/lexer-line-continuation-whitespace-error"}
   "org.pkl.parser.LexerTest/acceptsAllUnicodeCodepointsInComments"
   {:operation :unicode-comments}
   "org.pkl.parser.SpanTest/endWith test"
   {:operation :span}})

(def ^:private unicode-oracle-ids
  (mapv #(format "edge/unicode-comment-u%04x" %)
        [0x0000 0x0001 0x007f 0x0080 0x7ffe 0x7fff 0x8000 0xfffe 0xffff]))

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :invalid-pkl-parser-test-contract)))))

(defn- portable
  [value]
  (str/replace (str value) "\\" "/"))

(defn- layout
  [workspace-root]
  (let [root (paths/absolute workspace-root)
        upstream (paths/resolve-path root "research" "pkl")]
    {:root root
     :upstream upstream
     :test-root (paths/resolve-path upstream "pkl-parser" "src" "test" "kotlin")
     :fixture-root (paths/resolve-path upstream "pkl-core" "src" "test" "files"
                                       "LanguageSnippetTests" "input")
     :manifest (paths/resolve-path root "validation" "pkl-parser-test-contract"
                                   "PklParserTestContract.tsv")
     :contract-dir (paths/resolve-path root "validation" "pkl-core-test-contract")
     :init-script (paths/resolve-path root "gradle" "pkl-parser-test-contract.gradle")}))

(defn- regular-files
  [root]
  (with-open [entries (Files/walk (paths/path root) (make-array FileVisitOption 0))]
    (->> (.toArray entries)
         (map #(cast Path %))
         (filter #(Files/isRegularFile ^Path % (make-array LinkOption 0)))
         (sort-by #(portable (.relativize (paths/path root) ^Path %)))
         vec)))

(defn- command-output
  [request]
  (str/trim (:output (process/run! request))))

(defn- verify-pinned-revision!
  [{:keys [root upstream]}]
  (let [gitlink (command-output {:command ["git" "rev-parse" "HEAD:research/pkl"]
                                 :directory root})
        checkout (command-output {:command ["git" "rev-parse" "HEAD"]
                                  :directory upstream})]
    (doseq [[subject actual] [[:gitlink gitlink] [:checkout checkout]]]
      (when-not (= pinned-upstream-revision actual)
        (fail! "The pkl-parser test contract upstream revision drifted"
               {:kind :pkl-parser-test-revision-drift
                :subject subject :expected pinned-upstream-revision :actual actual})))
    pinned-upstream-revision))

(defn- source-line
  [^Path source method]
  (let [lines (str/split-lines (Files/readString source StandardCharsets/UTF_8))
        matcher (if (str/includes? method " ")
                  (re-pattern (str "fun\\s+`" (java.util.regex.Pattern/quote method) "`\\s*\\("))
                  (re-pattern (str "fun\\s+" (java.util.regex.Pattern/quote method) "\\s*\\(")))
        index (first (keep-indexed (fn [index line]
                                     (when (re-find matcher line) index))
                                   lines))]
    (when-not index
      (fail! "A discovered pkl-parser case has no source method"
             {:source (str source) :method method}))
    (let [annotation (last (filter #(str/includes? (nth lines %) "@Test")
                                   (range (max 0 (- index 5)) index)))]
      (str (inc (or annotation index))))))

(defn- source-model
  [{:keys [upstream]} cases]
  (let [required-counts (frequencies (map :source-path cases))]
    (mapv
     (fn [entry]
       (let [source (paths/resolve-path upstream (:source-path entry))]
         (when-not (paths/regular-file? source)
           (fail! "A pkl-parser test source or helper is missing"
                  {:path (:source-path entry)}))
         (assoc entry
                :source-sha256 (util/sha256-file source)
                :required-by-cases (str (get required-counts (:source-path entry) 0)))))
     parser-source-paths)))

(defn- comparison-fixture?
  [relative]
  (and (str/ends-with? relative ".pkl")
       (not-any? #(str/ends-with? relative %) comparison-exceptions)
       (not (re-find #"(?:^|/)errors/delimiters/" relative))
       (not (re-find #"(?:^|/)errors/parser\d+[.]pkl$" relative))
       (not (re-find #"(?:^|/)parser/" relative))))

(defn- oracle-results
  [output]
  (let [decoder (Base64/getDecoder)]
    (reduce
     (fn [rows line]
       (let [[id kind encoded :as fields] (str/split line #"\t" -1)]
         (when-not (= 3 (count fields))
           (fail! "A parser oracle row is malformed" {:line line}))
         (let [key [id kind]
               value (String. (.decode decoder encoded) StandardCharsets/UTF_8)]
           (when (contains? rows key)
             (fail! "The parser oracle contains a duplicate observation" {:key key}))
           (assoc rows key value))))
     {}
     (str/split-lines (Files/readString (paths/path output) StandardCharsets/UTF_8)))))

(defn- identifier-observations
  [combined field-index]
  (->> (str/split combined #";" -1)
       (remove str/blank?)
       (map (fn [row]
              (let [fields (str/split row #"," -1)]
                (when-not (= 3 (count fields))
                  (fail! "The pinned identifier observation is malformed" {:row row}))
                (str (first fields) "," (nth fields field-index) ";"))))
       (apply str)))

(defn- expected-observation
  [oracle {:keys [operation oracle-id fixture-id]}]
  (case operation
    :span (get oracle ["@span" "SPAN"])
    :identifier-regular
    (identifier-observations (get oracle ["@identifier" "IDENTIFIER"]) 1)
    :identifier-quote
    (identifier-observations (get oracle ["@identifier" "IDENTIFIER"]) 2)
    :lexer (get oracle [oracle-id "LEXER"])
    :unicode-comments
    (str/join "\n" (map #(get oracle [% "LEXER"]) unicode-oracle-ids))
    :parser-comparison
    (str (get oracle [fixture-id "GENERIC"]) "\n"
         (get oracle [fixture-id "TYPED"]))))

(defn- ordinary-case
  [layout oracle raw]
  (let [key (str (:source-class raw) "/" (:source-method raw))
        adaptation (get ordinary-observation-cases key)
        source-path (str "pkl-parser/src/test/kotlin/"
                         (str/replace (:source-class raw) "." "/") ".kt")
        source (paths/resolve-path (:upstream layout) source-path)
        observation (expected-observation oracle adaptation)
        unique-id (:unique-id raw)]
    (when-not adaptation
      (fail! "A pkl-parser JUnit case has no target-owned adaptation"
             {:case unique-id :source-class (:source-class raw)
              :source-method (:source-method raw)}))
    (when-not observation
      (fail! "A pkl-parser adaptation has no pinned oracle observation"
             {:case unique-id :adaptation adaptation}))
    {:case-id (str "pkl-parser-junit/" (util/sha256-text unique-id))
     :junit-unique-id unique-id
     :junit-parent-id (:parent-id raw)
     :adapted-invocation-id unique-id
     :case-kind "test"
     :display-name (:display-name raw)
     :source-class (:source-class raw)
     :source-method (:source-method raw)
     :source-path source-path
     :source-sha256 (util/sha256-file source)
     :source-line (source-line source (:source-method raw))
     :fixture-path "-"
     :fixture-sha256 "-"
     :product-classification "jvm-shared-product-behavior"
     :scope-basis "targets/pkl/product-goal.md#product-target:complete-parser-behavior"
     :enabled-state "enabled"
     :disabled-reason "-"
     :platform-conditions "all-supported-hosts"
     :expected-observation-sha256 (util/sha256-text observation)
     :execution-owner "complete-pkl-parser-runner"}))

(defn- comparison-cases
  [layout oracle raw]
  (let [unique-id (:unique-id raw)
        source-path "pkl-parser/src/test/kotlin/org/pkl/parser/ParserComparisonTest.kt"
        source (paths/resolve-path (:upstream layout) source-path)]
    (->> (regular-files (:fixture-root layout))
         (map (fn [fixture]
                (let [relative (portable (.relativize (:fixture-root layout) fixture))]
                  [relative fixture])))
         (filter #(comparison-fixture? (first %)))
         (mapv
          (fn [[relative fixture]]
            (let [fixture-id (str "corpus/" relative)
                  observation (expected-observation
                               oracle {:operation :parser-comparison
                                       :fixture-id fixture-id})
                  invocation-id (str unique-id "/[adapted-fixture:" relative "]")]
              (when-not (and (string? observation)
                             (not (str/includes? observation "nil")))
                (fail! "A parser comparison fixture has no pinned observations"
                       {:fixture relative :oracle-id fixture-id}))
              {:case-id (str "pkl-parser-junit/" (util/sha256-text invocation-id))
               :junit-unique-id unique-id
               :junit-parent-id (:parent-id raw)
               :adapted-invocation-id invocation-id
               :case-kind "fixture-invocation"
               :display-name (str "compareSnippetTests() [" relative "]")
               :source-class (:source-class raw)
               :source-method (:source-method raw)
               :source-path source-path
               :source-sha256 (util/sha256-file source)
               :source-line (source-line source (:source-method raw))
               :fixture-path (str "pkl-core/src/test/files/LanguageSnippetTests/input/"
                                  relative)
               :fixture-sha256 (util/sha256-file fixture)
               :product-classification "jvm-shared-product-behavior"
               :scope-basis "targets/pkl/product-goal.md#product-target:complete-parser-behavior"
               :enabled-state "enabled"
               :disabled-reason "-"
               :platform-conditions "all-supported-hosts"
               :expected-observation-sha256 (util/sha256-text observation)
               :execution-owner "complete-pkl-parser-runner"})))
         (sort-by :fixture-path)
         vec)))

(defn contract-model
  ([raw oracle-output]
   (contract-model (paths/workspace-root) raw oracle-output))
  ([workspace-root raw oracle-output]
   (let [layout (layout workspace-root)
         _ (verify-pinned-revision! layout)
         raw-rows (junit-discovery/read-raw-discovery raw)
         oracle (oracle-results oracle-output)
         hosts (set (map (juxt :os-name :os-arch :java-version) raw-rows))
         comparison (filter #(= "org.pkl.parser.ParserComparisonTest"
                                (:source-class %)) raw-rows)
         ordinary (remove #(= "org.pkl.parser.ParserComparisonTest"
                              (:source-class %)) raw-rows)]
     (when-not (= expected-junit-case-count (count raw-rows))
       (fail! "The pkl-parser JUnit case count changed"
              {:expected expected-junit-case-count :actual (count raw-rows)}))
     (when-not (= 1 (count comparison))
       (fail! "The parser comparison JUnit case boundary changed"
              {:actual (mapv :unique-id comparison)}))
     (when-not (= 1 (count hosts))
       (fail! "The pkl-parser discovery stream contains inconsistent hosts"
              {:hosts hosts}))
     (doseq [raw-row raw-rows]
       (when-not (= "SUCCESSFUL" (:status raw-row))
         (fail! "A pinned pkl-parser test was not enabled and successful"
                {:case (:unique-id raw-row) :status (:status raw-row)
                 :reason (:reason raw-row)})))
     (let [cases (vec (concat (map #(ordinary-case layout oracle %) ordinary)
                              (comparison-cases layout oracle (first comparison))))
           sources (source-model layout cases)]
       {:layout layout
        :metadata
        [["source-repository" "https://github.com/apple/pkl.git"]
         ["source-gitlink" "research/pkl"]
         ["source-revision" pinned-upstream-revision]
         ["upstream-test-task" ":pkl-parser:test"]
         ["source-count" (str (count sources))]
         ["test-source-count" (str expected-test-source-count)]
         ["helper-source-count" (str expected-helper-source-count)]
         ["junit-case-count" (str (count raw-rows))]
         ["adapted-case-count" (str (count cases))]
         ["pinned-discovery-host" (str/join "/" (first hosts))]
         ["observation-contract" "exact-upstream-structural-sha256-v1"]
         ["pkl-parser-test-task-sha256"
          (util/sha256-file (paths/resolve-path (:upstream layout)
                                                "pkl-parser/pkl-parser.gradle.kts"))]
         ["gradle-init-sha256" (util/sha256-file (:init-script layout))]
         ["discovery-listener-sha256"
          (util/sha256-file (paths/resolve-path
                             (:root layout) "validation/pkl-core-test-contract"
                             "PklCoreTestDiscoveryListener.java"))]
         ["upstream-oracle-sha256"
          (util/sha256-file (paths/resolve-path
                             (:root layout) "targets/pkl/validation/oracle"
                             "UpstreamOracle.java"))]]
        :sources sources
        :cases (vec (sort-by :case-id cases))}))))

(defn- safe-field
  [row column]
  (let [value (get row (keyword column))]
    (when-not (and (string? value) (not (str/blank? value))
                   (not (re-find #"[\t\r\n]" value)))
      (fail! "A pkl-parser test contract field is unsafe"
             {:column column :value value :row row}))
    value))

(defn render-manifest
  [{:keys [metadata sources cases]}]
  (str manifest-magic "\n"
       (apply str (for [[key value] metadata]
                    (str "meta\t" key "\t" value "\n")))
       "source-columns\t" (str/join "\t" source-columns) "\n"
       (apply str (for [row sources]
                    (str "source\t"
                         (str/join "\t" (map #(safe-field row %) source-columns))
                         "\n")))
       "case-columns\t" (str/join "\t" case-columns) "\n"
       (apply str (for [row cases]
                    (str "case\t"
                         (str/join "\t" (map #(safe-field row %) case-columns))
                         "\n")))))

(defn generate-manifest!
  ([raw oracle-output manifest]
   (generate-manifest! (paths/workspace-root) raw oracle-output manifest))
  ([workspace-root raw oracle-output manifest]
   (let [manifest (paths/absolute manifest)
         model (contract-model workspace-root raw oracle-output)]
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
      (fail! "The pkl-parser test manifest has the wrong schema"
             {:actual (first lines)}))
    (loop [remaining (rest lines)
           parsed {:manifest manifest :content content :metadata []
                   :sources [] :cases []}
           columns {}]
      (if-let [line (first remaining)]
        (let [fields (str/split line #"\t" -1)
              kind (first fields)]
          (cond
            (= "meta" kind)
            (do
              (when-not (= 3 (count fields))
                (fail! "Malformed pkl-parser test metadata" {:line line}))
              (recur (rest remaining)
                     (update parsed :metadata conj [(second fields) (nth fields 2)])
                     columns))

            (str/ends-with? kind "-columns")
            (let [row-kind (subs kind 0 (- (count kind) (count "-columns")))]
              (recur (rest remaining) parsed
                     (assoc columns row-kind (vec (rest fields)))))

            (contains? #{"source" "case"} kind)
            (let [row-columns (get columns kind)]
              (when-not (= (count row-columns) (dec (count fields)))
                (fail! "A pkl-parser contract row has the wrong field count"
                       {:line line}))
              (recur (rest remaining)
                     (update parsed (keyword (str kind "s")) conj
                             (zipmap (map keyword row-columns) (rest fields)))
                     columns))

            :else
            (fail! "The pkl-parser test manifest has an unknown row kind"
                   {:line line})))
        (assoc parsed :columns columns)))))

(defn validate-manifest!
  ([manifest] (validate-manifest! (paths/workspace-root) manifest))
  ([workspace-root manifest]
   (let [layout (layout workspace-root)
         _ (verify-pinned-revision! layout)
         parsed (read-manifest manifest)
         metadata (into {} (:metadata parsed))
         sources (:sources parsed)
         cases (:cases parsed)
         fixture-root (:fixture-root layout)]
     (when-not (= {"source" source-columns "case" case-columns}
                  (:columns parsed))
       (fail! "The pkl-parser contract columns drifted"
              {:actual (:columns parsed)}))
     (doseq [[key expected]
             [["source-revision" pinned-upstream-revision]
              ["source-count" (str expected-source-count)]
              ["test-source-count" (str expected-test-source-count)]
              ["helper-source-count" (str expected-helper-source-count)]
              ["junit-case-count" (str expected-junit-case-count)]
              ["observation-contract" "exact-upstream-structural-sha256-v1"]]]
       (when-not (= expected (get metadata key))
         (fail! "The pkl-parser contract metadata drifted"
                {:key key :expected expected :actual (get metadata key)})))
     (when-not (= expected-source-count (count sources))
       (fail! "The pkl-parser source inventory count drifted"
              {:actual (count sources)}))
     (when-not (= (parse-long (get metadata "adapted-case-count")) (count cases))
       (fail! "The pkl-parser adapted case count drifted"
              {:actual (count cases)}))
     (when-not (= expected-adapted-case-count (count cases))
       (fail! "The pkl-parser adapted case baseline drifted"
              {:expected expected-adapted-case-count :actual (count cases)}))
     (doseq [[rows field label] [[sources :source-path "source paths"]
                                 [cases :case-id "case IDs"]
                                 [cases :adapted-invocation-id "adapted invocation IDs"]]]
       (when-not (= (count rows) (count (distinct (map field rows))))
         (fail! (str "The pkl-parser contract contains duplicate " label) {})))
     (doseq [source sources]
       (let [file (paths/resolve-path (:upstream layout) (:source-path source))]
         (when-not (= (:source-sha256 source) (util/sha256-file file))
           (fail! "A pkl-parser source/helper hash drifted"
                  {:path (:source-path source)}))))
     (doseq [case-data cases]
       (when-not (and (= "jvm-shared-product-behavior"
                         (:product-classification case-data))
                      (= "enabled" (:enabled-state case-data))
                      (= "-" (:disabled-reason case-data))
                      (= "all-supported-hosts" (:platform-conditions case-data))
                      (= "complete-pkl-parser-runner" (:execution-owner case-data))
                      (re-matches #"[0-9a-f]{64}"
                                  (:expected-observation-sha256 case-data)))
         (fail! "A pkl-parser case lost scope, enablement, platform, or execution accounting"
                {:case (:case-id case-data)}))
       (when-not (= (:source-sha256 case-data)
                    (util/sha256-file
                     (paths/resolve-path (:upstream layout) (:source-path case-data))))
         (fail! "A pkl-parser case source hash drifted" {:case (:case-id case-data)}))
       (when-not (= "-" (:fixture-path case-data))
         (let [prefix "pkl-core/src/test/files/LanguageSnippetTests/input/"
               relative (subs (:fixture-path case-data) (count prefix))
               fixture (paths/resolve-path fixture-root relative)]
           (when-not (str/starts-with? (:fixture-path case-data) prefix)
             (fail! "A parser fixture escaped its declared root"
                    {:case (:case-id case-data)}))
           (when-not (= (:fixture-sha256 case-data) (util/sha256-file fixture))
             (fail! "A pkl-parser fixture hash drifted"
                    {:case (:case-id case-data) :fixture (:fixture-path case-data)})))))
     (let [summary {:sources (count sources)
                    :test-sources (count (filter #(= "test-source" (:source-role %))
                                                 sources))
                    :helper-sources (count (filter #(= "test-helper" (:source-role %))
                                                   sources))
                    :junit-cases expected-junit-case-count
                    :adapted-cases (count cases)
                    :fixture-invocations
                    (count (filter #(= "fixture-invocation" (:case-kind %)) cases))
                    :upstream-revision pinned-upstream-revision}]
       (when-not (= expected-fixture-invocation-count (:fixture-invocations summary))
         (fail! "The pkl-parser fixture invocation baseline drifted"
                {:expected expected-fixture-invocation-count
                 :actual (:fixture-invocations summary)}))
       (assoc parsed :layout layout :summary summary)))))

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
                                         "pkl-parser-test-contract"))
         raw (paths/resolve-path proof-root "junit-discovery.tsv")]
     (run-command!
      {:command ["env" "DRIPSHARP_WORKERS=22" "GRADLE_OPTS=-Xmx28g"
                 "./gradlew" "-I" (str (:init-script layout))
                 ":pkl-parser:test" "--console=plain"
                 (str "-Pdripsharp.contractDir=" (:contract-dir layout))
                 (str "-Pdripsharp.discoveryOutput=" raw)]
       :directory (:upstream layout)
       :timeout-ms 3600000})
     (let [actual (junit-discovery/read-raw-discovery raw)
           expected-ids (set (map :junit-unique-id (:cases validated)))
           actual-ids (set (map :unique-id actual))]
       (when-not (= expected-ids actual-ids)
         (fail! "Live pkl-parser JUnit discovery differs from the pinned contract"
                {:missing (vec (sort (set/difference expected-ids actual-ids)))
                 :added (vec (sort (set/difference actual-ids expected-ids)))}))
       (when-not (every? #(= "SUCCESSFUL" (:status %)) actual)
         (fail! "Live pkl-parser JUnit discovery contains a non-successful case"
                {:outcomes (frequencies (map :status actual))}))
       {:manifest (paths/absolute manifest)
        :discovery raw
        :summary (assoc (:summary validated) :live-discovery (count actual))}))))
