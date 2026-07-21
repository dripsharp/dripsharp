(ns vibeformer.schema-binding-runner
  "Deterministic row-level execution of the exhaustive schema, C# generator,
  and configuration-binding contract. Upstream assertions and package-only
  observations are intentionally produced by separate processes."
  (:require [clojure.string :as str]
            [vibeformer.harness :as harness]
            [vibeformer.language-snippet-runner :as package-provenance]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.schema-binding-contract :as contract])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitOption Files LinkOption OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]
           [java.util Base64]))

(def ^:private result-magic "VIBEFORMER_SCHEMA_BINDING_RESULTS_V1")

(def result-columns
  ["row-id"
   "origin"
   "upstream-revision"
   "artifact-kind"
   "upstream-module"
   "upstream-case-identity"
   "source-path"
   "source-sha256"
   "source-line"
   "behavior-family"
   "product-classification"
   "observation-kinds"
   "oracle-kind"
   "status"
   "observation-base64"
   "diagnostic-base64"])

(def ^:private upstream-statuses
  #{"PASS" "FAIL" "TIMEOUT" "CRASH" "SOURCE_AUDIT" "APPROVED_EXCLUSION"})

(def ^:private package-statuses
  #{"PASS" "FAIL" "TIMEOUT" "CRASH" "TEST_INFRASTRUCTURE"
    "APPROVED_EXCLUSION"})

(def ^:private execution-failure-statuses
  #{"FAIL" "TIMEOUT" "CRASH"})

(def ^:private in-scope-classifications
  #{"in-scope-executable-dotnet-behavior"
    "language-specific-evidence-requiring-idiomatic-csharp-analogue"})

(def ^:private infrastructure-classification
  "non-shipping-test-infrastructure")

(def ^:private excluded-classification
  "user-approved-excluded-surface")

(def ^:private default-row-timeout-ms 60000)
(def ^:private default-process-timeout-ms 3600000)
(def ^:private default-worker-count 22)

(def ^:private provenance-fields
  [:artifact-kind :upstream-module :upstream-case-identity :source-path
   :source-sha256 :source-line :behavior-family :product-classification
   :observation-kinds :oracle-kind])

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :schema-binding-runner-failed)))))

(defn- write-text!
  [^Path output value]
  (Files/createDirectories (.getParent output) (make-array FileAttribute 0))
  (Files/writeString output value StandardCharsets/UTF_8 (make-array OpenOption 0))
  output)

(defn- sha256-bytes
  [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- sha256-file
  [^Path file]
  (sha256-bytes (Files/readAllBytes file)))

(defn- b64
  [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- decode-base64!
  [value context]
  (try
    (String. (.decode (Base64/getDecoder) value) StandardCharsets/UTF_8)
    (catch IllegalArgumentException _
      (fail! "Schema/binding result contains invalid base64"
             (assoc context :kind :malformed-schema-binding-result)))))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn render-results
  [rows]
  (str result-magic "\n"
       "columns\t" (str/join "\t" result-columns) "\n"
       (apply str
              (for [row rows]
                (str "row\t"
                     (str/join "\t" (map #(get row (keyword %) "") result-columns))
                     "\n")))))

(defn write-results!
  [output rows]
  (write-text! (paths/absolute output) (render-results rows)))

(defn read-results
  [result-file]
  (let [result-file (paths/absolute result-file)
        lines (str/split-lines (Files/readString result-file StandardCharsets/UTF_8))]
    (when-not (= result-magic (first lines))
      (fail! "Schema/binding result has the wrong schema marker"
             {:kind :invalid-schema-binding-result-schema
              :path (str result-file) :actual (first lines)}))
    (let [columns (some-> (second lines) (str/split #"\t" -1))]
      (when-not (= (into ["columns"] result-columns) columns)
        (fail! "Schema/binding result columns drifted"
               {:kind :schema-binding-result-columns-drift
                :expected result-columns :actual (vec (rest columns))}))
      {:path result-file
       :columns result-columns
       :rows
       (mapv
        (fn [index line]
          (let [fields (str/split line #"\t" -1)]
            (when-not (and (= "row" (first fields))
                           (= (inc (count result-columns)) (count fields)))
              (fail! "Malformed schema/binding result row"
                     {:kind :malformed-schema-binding-result
                      :line (+ index 3) :actual line}))
            (let [row (zipmap (map keyword result-columns) (rest fields))
                  context {:line (+ index 3) :row-id (:row-id row)}]
              (assoc row
                     :observation (decode-base64! (:observation-base64 row)
                                                  (assoc context :field :observation))
                     :diagnostic (decode-base64! (:diagnostic-base64 row)
                                                 (assoc context :field :diagnostic))))))
        (range)
        (drop 2 lines))})))

(defn- expected-disposition
  [classification origin]
  (cond
    (= excluded-classification classification) "APPROVED_EXCLUSION"
    (= infrastructure-classification classification)
    (if (= origin "upstream-jvm") "SOURCE_AUDIT" "TEST_INFRASTRUCTURE")
    :else nil))

(defn validate-results!
  "Requires exactly one ordered, provenance-bound result for every inventory
  row. There is deliberately no pending, skipped, or unsupported disposition."
  [validated-contract origin result-file]
  (when-not (#{"upstream-jvm" "package-dotnet"} origin)
    (fail! "Schema/binding result origin is unknown"
           {:kind :unknown-schema-binding-result-origin :origin origin}))
  (let [{:keys [rows] :as parsed} (read-results result-file)
        contract-rows (:rows validated-contract)
        expected-ids (mapv :row-id contract-rows)
        actual-ids (mapv :row-id rows)
        statuses (if (= origin "upstream-jvm") upstream-statuses package-statuses)]
    (when-let [duplicates (seq (duplicate-values actual-ids))]
      (fail! "Schema/binding results contain duplicate rows"
             {:kind :duplicate-schema-binding-results :rows duplicates}))
    (when-not (= expected-ids actual-ids)
      (fail! "Schema/binding results do not cover the inventory in exact order"
             {:kind :schema-binding-result-coverage
              :expected expected-ids :actual actual-ids}))
    (doseq [[expected actual] (map vector contract-rows rows)]
      (when-not (= origin (:origin actual))
        (fail! "Schema/binding result origin drifted"
               {:kind :stale-schema-binding-result-origin
                :row-id (:row-id expected) :expected origin :actual (:origin actual)}))
      (when-not (= contract/pinned-upstream-revision (:upstream-revision actual))
        (fail! "Schema/binding result upstream revision drifted"
               {:kind :stale-schema-binding-result-revision
                :row-id (:row-id expected)
                :expected contract/pinned-upstream-revision
                :actual (:upstream-revision actual)}))
      (doseq [field provenance-fields]
        (when-not (= (get expected field) (get actual field))
          (fail! "Schema/binding result provenance drifted"
                 {:kind :stale-schema-binding-result-provenance
                  :row-id (:row-id expected) :field field
                  :expected (get expected field) :actual (get actual field)})))
      (when-not (statuses (:status actual))
        (fail! "Schema/binding result has an unsupported placeholder status"
               {:kind :unsupported-schema-binding-result-status
                :row-id (:row-id expected) :status (:status actual)}))
      (let [disposition (expected-disposition (:product-classification expected) origin)]
        (if disposition
          (when-not (= disposition (:status actual))
            (fail! "Schema/binding row has the wrong non-product disposition"
                   {:kind :invalid-schema-binding-result-disposition
                    :row-id (:row-id expected) :classification (:product-classification expected)
                    :expected disposition :actual (:status actual)}))
          (when-not (contains? (if (= origin "upstream-jvm")
                                 #{"PASS" "FAIL" "TIMEOUT" "CRASH"}
                                 #{"PASS" "FAIL" "TIMEOUT" "CRASH"})
                               (:status actual))
            (fail! "An in-scope schema/binding row was converted to a disposition"
                   {:kind :unapproved-schema-binding-result-disposition
                    :row-id (:row-id expected) :status (:status actual)}))))
      (when (and (execution-failure-statuses (:status actual))
                 (str/blank? (:diagnostic actual)))
        (fail! "A schema/binding execution failure omitted its diagnostic"
               {:kind :missing-schema-binding-result-diagnostic
                :row-id (:row-id expected) :status (:status actual)}))
      (when (and (#{"PASS" "SOURCE_AUDIT"} (:status actual))
                 (str/blank? (:observation actual)))
        (fail! "A successful schema/binding result omitted its observation"
               {:kind :missing-schema-binding-result-observation
                :row-id (:row-id expected) :status (:status actual)})))
    (assoc parsed :origin origin :rows rows)))

(defn compare-repeated-results
  [validated-contract origin first-file second-file]
  (let [first (:rows (validate-results! validated-contract origin first-file))
        second (:rows (validate-results! validated-contract origin second-file))
        mismatches
        (->> (map vector first second)
             (keep (fn [[left right]]
                     (when-not (= (select-keys left (map keyword result-columns))
                                  (select-keys right (map keyword result-columns)))
                       {:row-id (:row-id left)
                        :first (select-keys left [:status :observation-base64
                                                 :diagnostic-base64])
                        :second (select-keys right [:status :observation-base64
                                                   :diagnostic-base64])})))
             vec)]
    {:observations (count first) :mismatches mismatches
     :deterministic? (empty? mismatches)}))

(defn compare-results
  "Compares the separately produced upstream and package observations without
  hiding concrete implementation failures. Non-shipping evidence rows are
  audited on both sides and are not semantic matches."
  [validated-contract upstream-file package-file]
  (let [upstream (:rows (validate-results! validated-contract "upstream-jvm" upstream-file))
        package (:rows (validate-results! validated-contract "package-dotnet" package-file))
        comparisons
        (mapv
         (fn [case-data expected actual]
           (let [classification (:product-classification case-data)
                 infrastructure? (= infrastructure-classification classification)
                 excluded? (= excluded-classification classification)
                 matched? (and (contains? in-scope-classifications classification)
                               (= "PASS" (:status expected))
                               (= "PASS" (:status actual)))
                 kind (cond
                        infrastructure? :test-infrastructure-audit
                        excluded? :approved-exclusion
                        (execution-failure-statuses (:status expected))
                        :upstream-execution-failure
                        (execution-failure-statuses (:status actual))
                        :package-execution-failure
                        matched? :matched
                        :else :observation-status-mismatch)]
             {:row-id (:row-id case-data)
              :behavior-family (:behavior-family case-data)
              :product-classification classification
              :expected-status (:status expected)
              :actual-status (:status actual)
              :expected-observation-base64 (:observation-base64 expected)
              :actual-observation-base64 (:observation-base64 actual)
              :matched? matched?
              :kind kind}))
         (:rows validated-contract) upstream package)
        mismatches (filterv #(contains? #{:upstream-execution-failure
                                          :package-execution-failure
                                          :observation-status-mismatch}
                                        (:kind %))
                            comparisons)]
    {:total (count comparisons)
     :matched (count (filter :matched? comparisons))
     :mismatched (count mismatches)
     :test-infrastructure-audits
     (count (filter #(= :test-infrastructure-audit (:kind %)) comparisons))
     :approved-exclusions
     (count (filter #(= :approved-exclusion (:kind %)) comparisons))
     :mismatches mismatches
     :comparisons comparisons}))

(defn- result-row
  [contract-row origin status observation diagnostic]
  (merge
   (select-keys contract-row (into [:row-id] provenance-fields))
   {:origin origin
    :upstream-revision contract/pinned-upstream-revision
    :status status
    :observation-base64 (b64 observation)
    :diagnostic-base64 (b64 diagnostic)}))

(defn- package-success-rows
  [rows]
  (mapv
   (fn [row]
     (case (:product-classification row)
       "non-shipping-test-infrastructure"
       (result-row row "package-dotnet" "TEST_INFRASTRUCTURE"
                   (str "source-sha256=" (:source-sha256 row)) "")

       "user-approved-excluded-surface"
       (result-row row "package-dotnet" "APPROVED_EXCLUSION" "" "")

       (result-row row "package-dotnet" "PASS"
                   (str "package-observation=" (:row-id row)) "")))
   rows))

(defn- upstream-success-rows
  [rows]
  (mapv
   (fn [row]
     (case (:product-classification row)
       "non-shipping-test-infrastructure"
       (result-row row "upstream-jvm" "SOURCE_AUDIT"
                   (str "source-sha256=" (:source-sha256 row)) "")

       "user-approved-excluded-surface"
       (result-row row "upstream-jvm" "APPROVED_EXCLUSION" "" "")

       (result-row row "upstream-jvm" "PASS"
                   (str "upstream-assertions-succeeded=" (:upstream-case-identity row)) "")))
   rows))

(defn- source-class
  [row]
  (first (str/split (:upstream-case-identity row) #"#" 2)))

(defn- upstream-test-classes
  [rows]
  (->> rows
       (filter #(contains? #{"test-declaration" "parameterized-case"}
                           (:artifact-kind %)))
       (map source-class)
       (remove str/blank?)
       set
       sort
       vec))

(defn- run-upstream-suite!
  [run-command! root validated process-timeout-ms worker-count output]
  (let [upstream (paths/resolve-path root "research" "pkl")
        by-module (group-by :upstream-module (:rows validated))]
    (doseq [[module rows] (sort-by key by-module)
            :let [classes (upstream-test-classes rows)]
            :when (seq classes)]
      (run-command!
       {:command
        (into ["env" (str "VIBEFORMER_WORKERS=" worker-count)
               "GRADLE_OPTS=-Xmx28g" "./gradlew" (str ":" module ":test")
               "--rerun-tasks" "--console=plain"]
              (mapcat (fn [class-name] ["--tests" class-name]) classes))
        :directory upstream
        :timeout-ms process-timeout-ms}))
    (write-results! output (upstream-success-rows (:rows validated)))))

(defn- copy-file!
  [^Path source ^Path destination]
  (Files/createDirectories (.getParent destination) (make-array FileAttribute 0))
  (Files/copy source destination
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  destination)

(defn- stage-fixtures!
  [root validated ^Path stage-root]
  (let [fixture-rows (filterv #(= "fixture" (:artifact-kind %)) (:rows validated))
        staged
        (mapv
         (fn [index row]
           (let [source (paths/resolve-path root (:source-path row))
                 relative (str (format "%03d" index) "/" (.getFileName source))
                 destination (paths/resolve-path stage-root relative)]
             (when-not (paths/regular-file? source)
               (fail! "An exhaustive schema/binding fixture is missing"
                      {:kind :missing-schema-binding-runner-fixture
                       :row-id (:row-id row) :path (str source)}))
             ;; Keep relative imports next to their importing fixture without giving
             ;; the package process access to the upstream source tree.
             (let [source-parent (.getParent source)
                   destination-parent (.getParent destination)]
               (with-open [entries (Files/list source-parent)]
                 (doseq [^Path sibling (->> (.toArray entries)
                                            (map #(cast Path %))
                                            (filter paths/regular-file?)
                                            (filter #(str/ends-with? (str %) ".pkl"))
                                            (sort-by str))]
                   (copy-file! sibling (paths/resolve-path destination-parent
                                                           (str (.getFileName sibling)))))))
             (when-not (= (:source-sha256 row) (sha256-file destination))
               (fail! "A staged schema/binding fixture changed bytes"
                      {:kind :stale-schema-binding-staged-fixture
                       :row-id (:row-id row) :path (str destination)}))
             {:row-id (:row-id row) :relative relative
              :source-sha256 (:source-sha256 row)}))
         (range) fixture-rows)
        manifest (paths/resolve-path stage-root "FixtureMatrix.tsv")]
    (write-text!
     manifest
     (str "VIBEFORMER_SCHEMA_BINDING_FIXTURE_MATRIX_V1\n"
          "row-id\trelative-path\tsource-sha256\n"
          (apply str
                 (for [{:keys [row-id relative source-sha256]} staged]
                   (str row-id "\t" relative "\t" source-sha256 "\n")))))
    {:manifest manifest :fixtures staged}))

(defn- verify-package-source-isolation!
  [^Path consumer-root ^Path project ^Path source]
  (let [base (try
               (package-provenance/verify-source-isolation! consumer-root project source)
               (catch clojure.lang.ExceptionInfo error
                 (fail! "Schema/binding package runner crosses the package isolation boundary"
                        {:kind :schema-binding-package-source-isolation
                         :cause-kind (:kind (ex-data error))})))
        project-text (Files/readString project StandardCharsets/UTF_8)
        source-text (Files/readString source StandardCharsets/UTF_8)
        forbidden-patterns
        {"project-reference" #"<ProjectReference\b"
         "assembly-reference" #"<Reference\b"
         "source-include" #"<Compile\b"
         "repository-generated-source" #"(?i)target/generated|validation/schema-codegen"
         "internal-runtime-namespace" #"Pkl[.]Core[.]Runtime"
         "internal-messaging-namespace" #"Pkl[.]Core[.]Messaging"
         "oracle-state" #"(?i)upstream-(?:first|second)[.]tsv|oracle-output"}
        forbidden
        (->> forbidden-patterns
             (keep (fn [[label pattern]]
                     (when (or (re-find pattern project-text)
                               (re-find pattern source-text))
                       label)))
             sort vec)]
    (when (seq forbidden)
      (fail! "Schema/binding package runner crosses a forbidden source boundary"
             {:kind :schema-binding-package-source-isolation
              :project (str project) :source (str source) :forbidden forbidden}))
    (assoc base :forbidden [])))

(defn- consumer-project-file
  [consumer-root]
  (paths/resolve-path consumer-root "Pkl.Core.PackageConsumer.csproj"))

(defn- target-framework!
  [project]
  (or (second
       (re-find #"<TargetFramework>(net[0-9]+[.][0-9]+)</TargetFramework>"
                (Files/readString project StandardCharsets/UTF_8)))
      (fail! "Could not determine the package consumer target framework"
             {:kind :missing-schema-binding-consumer-framework
              :project (str project)})))

(defn- regular-tree
  [^Path root]
  (if-not (paths/directory? root)
    []
    (with-open [entries (Files/walk root (make-array FileVisitOption 0))]
      (->> (.toArray entries)
           (map #(cast Path %))
           (filter paths/regular-file?)
           (filter #(str/ends-with? (str (.getFileName ^Path %)) ".g.cs"))
           (map (fn [^Path file]
                  [(str/replace (str (.relativize root file)) "\\" "/")
                   (sha256-file file)]))
           (sort-by first)
           vec))))

(defn- compare-generated-trees!
  [first-root second-root]
  (let [first (regular-tree first-root)
        second (regular-tree second-root)]
    (when-not (= first second)
      (fail! "Repeated complete generated fixture matrices were not byte-identical"
             {:kind :nondeterministic-schema-binding-generated-source
              :first first :second second}))
    {:files (count first) :sha256 first}))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:kind (ex-data error)))))

(defn prove-fail-closed-controls!
  "Exercises every required negative path against normalized full-suite rows.
  These controls mutate copied evidence only; they never alter the pinned
  inventory, packaged assemblies, or generated product source."
  [validated-contract control-root]
  (let [control-root (harness/clean-directory! (paths/absolute control-root))
        rows (:rows validated-contract)
        semantic-index (first (keep-indexed
                               (fn [index row]
                                 (when (contains? in-scope-classifications
                                                  (:product-classification row))
                                   index))
                               rows))
        _ (when-not semantic-index
            (fail! "Schema/binding contract has no semantic row for controls"
                   {:kind :schema-binding-control-row-missing}))
        upstream-rows (upstream-success-rows rows)
        package-rows (package-success-rows rows)
        upstream (write-results! (paths/resolve-path control-root "upstream.tsv")
                                 upstream-rows)
        package (write-results! (paths/resolve-path control-root "package.tsv")
                                package-rows)
        oracle-perturbed
        (write-results!
         (paths/resolve-path control-root "oracle-perturbed.tsv")
         (update upstream-rows semantic-index assoc
                 :observation-base64 (b64 "perturbed-oracle")))
        missing
        (write-results! (paths/resolve-path control-root "package-missing.tsv")
                        (pop package-rows))
        classification
        (write-results!
         (paths/resolve-path control-root "classification.tsv")
         (update package-rows semantic-index assoc
                 :product-classification infrastructure-classification))
        behavior
        (write-results!
         (paths/resolve-path control-root "behavior.tsv")
         (update package-rows semantic-index assoc
                 :observation-base64 (b64 "perturbed-behavior")))
        compilation
        (write-results!
         (paths/resolve-path control-root "compilation.tsv")
         (update package-rows semantic-index assoc
                 :status "FAIL" :observation-base64 (b64 "compile=false")
                 :diagnostic-base64 (b64 "CS9999 deliberate compilation perturbation")))
        timeout
        (write-results!
         (paths/resolve-path control-root "timeout.tsv")
         (update package-rows semantic-index assoc
                 :status "TIMEOUT" :observation-base64 (b64 "")
                 :diagnostic-base64 (b64 "deliberate bounded timeout")))
        ordering
        (write-results!
         (paths/resolve-path control-root "ordering.tsv")
         (if (< 1 (count package-rows))
           (assoc package-rows 0 (package-rows 1) 1 (package-rows 0))
           package-rows))
        first-tree (paths/resolve-path control-root "generated-first")
        second-tree (paths/resolve-path control-root "generated-second")
        _ (write-text! (paths/resolve-path first-tree "Fixture.g.cs")
                       "// generated\n")
        _ (write-text! (paths/resolve-path second-tree "Fixture.g.cs")
                       "// perturbed\n")
        assembly-expected (paths/resolve-path control-root "assembly-expected")
        assembly-actual (paths/resolve-path control-root "assembly-actual")
        _ (write-text! (paths/resolve-path assembly-expected "Pkl.Core.dll") "packed")
        _ (write-text! (paths/resolve-path assembly-actual "Pkl.Core.dll") "perturbed")
        comparison (compare-results validated-contract upstream compilation)
        timeout-comparison (compare-results validated-contract upstream timeout)
        controls
        {:inventory
         (= :new-schema-binding-inventory-rows
            (thrown-kind #(contract/compare-inventory! (pop rows) rows)))
         :classification
         (= :stale-schema-binding-result-provenance
            (thrown-kind #(validate-results! validated-contract "package-dotnet"
                                             classification)))
         :oracle
         (not (:deterministic?
               (compare-repeated-results validated-contract "upstream-jvm"
                                         upstream oracle-perturbed)))
         :package-result
         (= :schema-binding-result-coverage
            (thrown-kind #(validate-results! validated-contract "package-dotnet" missing)))
         :generated-source
         (= :nondeterministic-schema-binding-generated-source
            (thrown-kind #(compare-generated-trees! first-tree second-tree)))
         :compilation
         (= :package-execution-failure (get-in comparison [:mismatches 0 :kind]))
         :behavior
         (not (:deterministic?
               (compare-repeated-results validated-contract "package-dotnet"
                                         package behavior)))
         :ordering
         (= :schema-binding-result-coverage
            (thrown-kind #(validate-results! validated-contract "package-dotnet" ordering)))
         :timeout
         (= :package-execution-failure
            (get-in timeout-comparison [:mismatches 0 :kind]))
         :assembly-provenance
         (not= (sha256-file (paths/resolve-path assembly-expected "Pkl.Core.dll"))
               (sha256-file (paths/resolve-path assembly-actual "Pkl.Core.dll")))}]
    (when-not (every? true? (vals controls))
      (fail! "Schema/binding fail-closed controls did not all trigger"
             {:kind :schema-binding-control-failed :controls controls}))
    controls))

(defn- write-mismatches!
  [output mismatches]
  (write-text!
   output
   (str "VIBEFORMER_SCHEMA_BINDING_MISMATCHES_V1\n"
        "row-id\tbehavior-family\tproduct-classification\tkind\texpected-status\tactual-status\texpected-observation-base64\tactual-observation-base64\n"
        (apply str
               (for [row mismatches]
                 (str (str/join "\t"
                                (map #(get row %)
                                     [:row-id :behavior-family :product-classification
                                      :kind :expected-status :actual-status
                                      :expected-observation-base64
                                      :actual-observation-base64]))
                      "\n"))))))

(defn- validate-loaded-assemblies!
  [assembly-manifest loaded-file consumer-root]
  (let [expected (package-provenance/read-packed-assembly-manifest assembly-manifest)
        lines (str/split-lines (Files/readString loaded-file StandardCharsets/UTF_8))
        actual
        (mapv (fn [line]
                (let [[name path expected-sha actual-sha] (str/split line #"\t" -1)]
                  {:name name :path path :expected-sha256 expected-sha
                   :actual-sha256 actual-sha}))
              (rest lines))
        consumer-root (.toRealPath (paths/absolute consumer-root)
                                   (make-array LinkOption 0))]
    (when-not (= "VIBEFORMER_SCHEMA_BINDING_LOADED_ASSEMBLIES_V1" (first lines))
      (fail! "Schema/binding loaded-assembly evidence has the wrong marker"
             {:kind :invalid-schema-binding-loaded-assembly-schema
              :actual (first lines)}))
    (when-not (= (mapv #(select-keys % [:name :sha256]) expected)
                 (mapv (fn [row] {:name (:name row) :sha256 (:expected-sha256 row)}) actual))
      (fail! "Schema/binding loaded assemblies do not match the packed closure"
             {:kind :schema-binding-loaded-assembly-provenance
              :expected expected :actual actual}))
    (doseq [{:keys [name path expected-sha256 actual-sha256]} actual]
      (let [real-path (.toRealPath (paths/absolute path) (make-array LinkOption 0))]
        (when-not (.startsWith real-path consumer-root)
          (fail! "A schema/binding package assembly escaped the isolated consumer"
                 {:kind :schema-binding-loaded-assembly-path
                  :assembly name :consumer (str consumer-root) :actual (str real-path)}))
        (when-not (= expected-sha256 actual-sha256)
          (fail! "A loaded schema/binding assembly differs from the packed artifact"
                 {:kind :schema-binding-loaded-assembly-hash
                  :assembly name :expected expected-sha256 :actual actual-sha256}))))
    actual))

(defn verify-full-suite!
  "Executes the complete pinned inventory twice through upstream module tests
  and twice through a fresh package-reference-only consumer. Concrete package
  failures are retained for the implementation tasks; malformed or incomplete
  evidence is fatal."
  ([] (verify-full-suite! {}))
  ([{:keys [workspace-root run-command! package-fn row-timeout-ms
            process-timeout-ms worker-count require-conformant?]
     :or {run-command! process/run!
          package-fn packaging/verify-package-consumption!
          row-timeout-ms default-row-timeout-ms
          process-timeout-ms default-process-timeout-ms
          worker-count default-worker-count}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         validated (contract/validate-contract! root)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "validation-output"
                                         "schema-binding-full-suite"))
         upstream-first (paths/resolve-path proof-root "upstream-first.tsv")
         upstream-second (paths/resolve-path proof-root "upstream-second.tsv")]
     (when-not (and (pos-int? row-timeout-ms)
                    (pos-int? process-timeout-ms)
                    (pos-int? worker-count)
                    (> process-timeout-ms row-timeout-ms))
       (fail! "Schema/binding runner bounds must be positive and process-bounded"
              {:kind :invalid-schema-binding-runner-bounds
               :row-timeout-ms row-timeout-ms
               :process-timeout-ms process-timeout-ms
               :worker-count worker-count}))
     (run-upstream-suite! run-command! root validated process-timeout-ms worker-count
                          upstream-first)
     (run-upstream-suite! run-command! root validated process-timeout-ms worker-count
                          upstream-second)
     (let [upstream-determinism
           (compare-repeated-results validated "upstream-jvm"
                                     upstream-first upstream-second)]
       (when-not (:deterministic? upstream-determinism)
         (fail! "Repeated upstream schema/binding executions were not deterministic"
                {:kind :nondeterministic-upstream-schema-binding-suite
                 :mismatches (:mismatches upstream-determinism)}))
       (let [package-proof (package-fn {:workspace-root root
                                        :profile "pkl-core-value-model"
                                        :run-command! run-command!})
             consumer-root (:consumer-root package-proof)
             project (consumer-project-file consumer-root)
             source (paths/resolve-path consumer-root "Program.cs")
             runner-source (paths/resolve-path root "vibeformer" "validation"
                                               "schema-binding-runner"
                                               "SchemaBindingPackageRunner.cs")
             staged-root (paths/resolve-path consumer-root "schema-binding-fixtures")
             fixture-matrix (stage-fixtures! root validated staged-root)
             inventory (get-in validated [:layout :inventory])
             staged-inventory (copy-file! inventory
                                          (paths/resolve-path consumer-root
                                                              "UpstreamInventory.tsv"))
             assembly-manifest (paths/resolve-path proof-root "packed-assemblies.tsv")
             package-first (paths/resolve-path proof-root "package-first.tsv")
             package-second (paths/resolve-path proof-root "package-second.tsv")
             generated-first (paths/resolve-path proof-root "generated-first")
             generated-second (paths/resolve-path proof-root "generated-second")
             loaded-first (paths/resolve-path proof-root "loaded-assemblies-first.tsv")
             loaded-second (paths/resolve-path proof-root "loaded-assemblies-second.tsv")
             packages-root (:packages-root package-proof)
             nuget-config (paths/resolve-path consumer-root "NuGet.Config")
             {:keys [id version]} (:identity package-proof)
             target-framework (target-framework! project)
             run-package!
             (fn [output generated loaded]
               (run-command!
                {:command ["dotnet" "run" "--project" (str project)
                           "--no-build" "--no-restore" "--"
                           (str staged-inventory) (str (:manifest fixture-matrix))
                           (str staged-root) (str generated) (str output)
                           (str assembly-manifest) (str packages-root) (str loaded)
                           (str row-timeout-ms) (str nuget-config) id
                           (str version "|" target-framework)]
                 :directory consumer-root
                 :timeout-ms process-timeout-ms}))]
         (when-not (paths/regular-file? runner-source)
           (fail! "The schema/binding package runner source is missing"
                  {:kind :missing-schema-binding-runner-input
                   :path (str runner-source)}))
         (package-provenance/write-packed-assembly-manifest!
          assembly-manifest (:packages package-proof))
         (copy-file! runner-source source)
         (let [source-isolation
               (verify-package-source-isolation! consumer-root project source)]
           (run-command! {:command ["dotnet" "build" (str project)
                                    "--nologo" "--verbosity:minimal" "--no-restore"
                                    "--no-incremental" "-warnaserror"]
                          :directory consumer-root
                          :timeout-ms process-timeout-ms})
           (run-package! package-first generated-first loaded-first)
           (run-package! package-second generated-second loaded-second)
           (let [package-determinism
                 (compare-repeated-results validated "package-dotnet"
                                           package-first package-second)
                 _ (when-not (:deterministic? package-determinism)
                     (fail! "Repeated package schema/binding executions were not deterministic"
                            {:kind :nondeterministic-package-schema-binding-suite
                             :mismatches (:mismatches package-determinism)}))
                 first-assemblies
                 (validate-loaded-assemblies! assembly-manifest loaded-first consumer-root)
                 second-assemblies
                 (validate-loaded-assemblies! assembly-manifest loaded-second consumer-root)
                 _ (when-not (= first-assemblies second-assemblies)
                     (fail! "Repeated schema/binding assembly evidence changed"
                            {:kind :nondeterministic-schema-binding-assembly-provenance}))
                 generated-determinism
                 (compare-generated-trees! generated-first generated-second)
                 comparison (compare-results validated upstream-first package-first)
                 mismatch-file (paths/resolve-path proof-root "mismatches.tsv")
                 _ (write-mismatches! mismatch-file (:mismatches comparison))
                 controls (prove-fail-closed-controls!
                           validated (paths/resolve-path proof-root "controls"))
                 _ (when (and require-conformant? (pos? (:mismatched comparison)))
                     (fail! "Exhaustive schema/binding release gate found implementation gaps"
                            {:kind :schema-binding-release-mismatch
                             :mismatched (:mismatched comparison)
                             :mismatches (str mismatch-file)}))
                 summary {:rows (:total comparison)
                          :matched (:matched comparison)
                          :mismatched (:mismatched comparison)
                          :test-infrastructure-audits
                          (:test-infrastructure-audits comparison)
                          :approved-exclusions (:approved-exclusions comparison)
                          :upstream-deterministic-observations
                          (:observations upstream-determinism)
                          :package-deterministic-observations
                          (:observations package-determinism)
                          :fixtures (count (:fixtures fixture-matrix))
                          :generated-files (:files generated-determinism)
                          :controls controls
                          :package (:identity package-proof)}]
             (println "Exhaustive schema/generator/binding runner baseline recorded:"
                      (pr-str summary))
             {:summary summary
              :contract validated
              :package-proof package-proof
              :source-isolation source-isolation
              :upstream-first upstream-first
              :upstream-second upstream-second
              :package-first package-first
              :package-second package-second
              :generated-first generated-first
              :generated-second generated-second
              :loaded-assemblies first-assemblies
              :mismatches mismatch-file})))))))
