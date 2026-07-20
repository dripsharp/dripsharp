(ns vibeformer.pkl-core-corpus-runner
  "Deterministic row-level execution of the complete non-language Pkl.Core
  contract through the pinned JVM and an isolated NuGet consumer."
  (:require [clojure.string :as str]
            [vibeformer.harness :as harness]
            [vibeformer.language-snippet-runner :as package-provenance]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.pkl-core-test-contract :as contract]
            [vibeformer.process :as process])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(def ^:private result-magic "VIBEFORMER_PKL_CORE_CORPUS_RESULTS_V1")

(def result-columns
  ["case-id"
   "origin"
   "upstream-revision"
   "junit-unique-id"
   "source-path"
   "source-sha256"
   "source-line"
   "behavior-family"
   "product-classification"
   "execution-owner"
   "status"
   "observation-base64"
   "diagnostic-base64"])

(def ^:private result-statuses
  #{"PASS" "FAIL" "TIMEOUT" "CRASH" "CONDITION_AUDIT"
    "APPROVED_EXCLUSION" "TEST_INFRASTRUCTURE" "PENDING"})

(def ^:private execution-failure-statuses
  #{"FAIL" "TIMEOUT" "CRASH" "PENDING"})

(def ^:private default-case-timeout-ms 60000)
(def ^:private default-process-timeout-ms 3600000)
(def ^:private default-worker-count 22)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :pkl-core-corpus-runner-failed)))))

(defn- write-text!
  [^Path output value]
  (Files/createDirectories (.getParent output) (make-array FileAttribute 0))
  (Files/writeString output value StandardCharsets/UTF_8 (make-array OpenOption 0))
  output)

(defn- b64
  [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- decode-base64!
  [value context]
  (try
    (String. (.decode (Base64/getDecoder) value) StandardCharsets/UTF_8)
    (catch IllegalArgumentException error
      (fail! "Pkl.Core corpus result contains invalid base64"
             (assoc context :kind :malformed-pkl-core-corpus-result)))))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn render-results
  "Renders canonical, ordered corpus rows. Volatile platform values must be
  normalized by the child runner before they reach this format."
  [rows]
  (str result-magic "\n"
       "columns\t" (str/join "\t" result-columns) "\n"
       (apply str
              (for [row rows]
                (str "case\t"
                     (str/join "\t" (map #(get row (keyword %)) result-columns))
                     "\n")))))

(defn write-results!
  [output rows]
  (write-text! (paths/absolute output) (render-results rows)))

(defn read-results
  "Reads the lossless corpus result format and rejects malformed rows."
  [result-file]
  (let [result-file (paths/absolute result-file)
        lines (str/split-lines (Files/readString result-file StandardCharsets/UTF_8))]
    (when-not (= result-magic (first lines))
      (fail! "Pkl.Core corpus result has the wrong schema marker"
             {:kind :invalid-pkl-core-corpus-result-schema
              :path (str result-file) :actual (first lines)}))
    (let [columns (some-> (second lines) (str/split #"\t" -1))]
      (when-not (= (into ["columns"] result-columns) columns)
        (fail! "Pkl.Core corpus result columns drifted"
               {:kind :pkl-core-corpus-result-columns-drift
                :expected result-columns :actual (vec (rest columns))}))
      {:path result-file
       :content (Files/readString result-file StandardCharsets/UTF_8)
       :rows
       (mapv
        (fn [index line]
          (let [fields (str/split line #"\t" -1)]
            (when-not (and (= "case" (first fields))
                           (= (inc (count result-columns)) (count fields)))
              (fail! "Malformed Pkl.Core corpus result row"
                     {:kind :malformed-pkl-core-corpus-result
                      :line (+ index 3) :actual line}))
            (let [row (zipmap (map keyword result-columns) (rest fields))
                  context {:line (+ index 3) :case (:case-id row)}]
              (when-not (result-statuses (:status row))
                (fail! "Pkl.Core corpus result has an unknown status"
                       (assoc context :kind :malformed-pkl-core-corpus-result
                              :status (:status row))))
              (assoc row
                     :observation (decode-base64!
                                   (:observation-base64 row)
                                   (assoc context :field :observation))
                     :diagnostic (decode-base64!
                                  (:diagnostic-base64 row)
                                  (assoc context :field :diagnostic))
                     :line line))))
        (range)
        (drop 2 lines))})))

(def ^:private provenance-fields
  [:junit-unique-id :source-path :source-sha256 :source-line :behavior-family
   :product-classification :execution-owner])

(defn validate-results!
  "Requires one ordered, provenance-exact, non-skipped result for every
  contract row. Exclusions and test-only mechanics remain explicit and cannot
  be assigned to product rows."
  [validated-manifest origin result-file]
  (when-not (#{"upstream-jvm" "package-dotnet"} origin)
    (fail! "Unknown Pkl.Core corpus origin"
           {:kind :invalid-pkl-core-corpus-origin :origin origin}))
  (let [{:keys [rows] :as parsed} (read-results result-file)
        cases (:cases validated-manifest)
        expected-ids (mapv :case-id cases)
        actual-ids (mapv :case-id rows)]
    (when-let [duplicates (seq (duplicate-values actual-ids))]
      (fail! "Pkl.Core corpus results contain duplicate rows"
             {:kind :duplicate-pkl-core-corpus-results :cases duplicates}))
    (when-not (= expected-ids actual-ids)
      (fail! "Pkl.Core corpus results do not cover the contract in order"
             {:kind :pkl-core-corpus-result-coverage
              :expected expected-ids :actual actual-ids}))
    (doseq [[case-data row] (map vector cases rows)]
      (when-not (= origin (:origin row))
        (fail! "Pkl.Core corpus result has the wrong execution origin"
               {:kind :invalid-pkl-core-corpus-origin :case (:case-id case-data)
                :expected origin :actual (:origin row)}))
      (when-not (= contract/pinned-upstream-revision (:upstream-revision row))
        (fail! "Pkl.Core corpus result has stale upstream provenance"
               {:kind :stale-pkl-core-corpus-provenance :case (:case-id case-data)
                :field :upstream-revision :expected contract/pinned-upstream-revision
                :actual (:upstream-revision row)}))
      (doseq [field provenance-fields]
        (when-not (= (get case-data field) (get row field))
          (fail! "Pkl.Core corpus row provenance does not match the contract"
                 {:kind :stale-pkl-core-corpus-provenance
                  :case (:case-id case-data) :field field
                  :expected (get case-data field) :actual (get row field)})))
      (when (and (execution-failure-statuses (:status row))
                 (str/blank? (:diagnostic row)))
        (fail! "Pkl.Core corpus failure omits deterministic diagnostics"
               {:kind :missing-pkl-core-corpus-diagnostic
                :case (:case-id case-data) :status (:status row)}))
      (if (= "upstream-jvm" origin)
        (when (#{"APPROVED_EXCLUSION" "TEST_INFRASTRUCTURE" "PENDING"}
               (:status row))
          (fail! "Pinned JVM execution was replaced with a package disposition"
                 {:kind :invalid-upstream-pkl-core-corpus-status
                  :case (:case-id case-data) :status (:status row)}))
        (case (:product-classification case-data)
          "user-approved-excluded-surface"
          (when-not (= "APPROVED_EXCLUSION" (:status row))
            (fail! "Approved product exclusion is not explicit in package results"
                   {:kind :missing-pkl-core-corpus-exclusion
                    :case (:case-id case-data) :status (:status row)}))

          "test-infrastructure-only-mechanics"
          (when-not (= "TEST_INFRASTRUCTURE" (:status row))
            (fail! "Test-infrastructure-only row has no explicit package audit"
                   {:kind :missing-pkl-core-corpus-test-infrastructure-audit
                    :case (:case-id case-data) :status (:status row)}))

          (when (#{"APPROVED_EXCLUSION" "TEST_INFRASTRUCTURE" "CONDITION_AUDIT"}
                 (:status row))
            (fail! "An in-scope product row was converted into a non-product disposition"
                   {:kind :unapproved-pkl-core-corpus-disposition
                    :case (:case-id case-data) :status (:status row)})))))
    (assoc parsed :origin origin :rows rows)))

(defn compare-repeated-results
  "Requires two complete executions to be byte-for-byte identical after each
  execution has independently passed coverage and provenance validation."
  [validated-manifest origin first-result second-result]
  (let [first (validate-results! validated-manifest origin first-result)
        second (validate-results! validated-manifest origin second-result)]
    {:observations (count (:rows first))
     :deterministic? (= (:content first) (:content second))
     :first (:path first)
     :second (:path second)}))

(defn compare-results
  "Compares the isolated package result to the independently executed JVM
  result. Product failures remain mismatches; only user-approved exclusions and
  test-infrastructure mechanics receive their pinned dispositions."
  [validated-manifest upstream-file package-file]
  (let [upstream (:rows (validate-results! validated-manifest "upstream-jvm" upstream-file))
        package (:rows (validate-results! validated-manifest "package-dotnet" package-file))
        comparisons
        (mapv
         (fn [case-data expected actual]
           (let [classification (:product-classification case-data)
                 kind
                 (cond
                   (= "user-approved-excluded-surface" classification)
                   :approved-exclusion

                   (= "test-infrastructure-only-mechanics" classification)
                   :test-infrastructure-audit

                   (execution-failure-statuses (:status expected))
                   :upstream-execution-failure

                   (execution-failure-statuses (:status actual))
                   :package-execution-failure

                   (not= "PASS" (:status actual))
                   :status-mismatch

                   (not= (:observation-base64 expected)
                         (:observation-base64 actual))
                   :observation-mismatch

                   :else :matched)]
             {:case-id (:case-id case-data)
              :behavior-family (:behavior-family case-data)
              :product-classification classification
              :kind kind
              :expected-status (:status expected)
              :actual-status (:status actual)
              :expected-observation-base64 (:observation-base64 expected)
              :actual-observation-base64 (:observation-base64 actual)}))
         (:cases validated-manifest) upstream package)
        mismatches (filterv #(contains? #{:upstream-execution-failure
                                          :package-execution-failure
                                          :status-mismatch
                                          :observation-mismatch}
                                        (:kind %))
                            comparisons)]
    {:total (count comparisons)
     :matched (count (filter #(= :matched (:kind %)) comparisons))
     :approved-exclusions (count (filter #(= :approved-exclusion (:kind %)) comparisons))
     :test-infrastructure-audits
     (count (filter #(= :test-infrastructure-audit (:kind %)) comparisons))
     :mismatched (count mismatches)
     :comparisons comparisons
     :mismatches mismatches}))

(defn require-conformant!
  "Release-style aggregate gate. The infrastructure baseline intentionally
  calls `compare-results` directly so pending product work is retained instead
  of narrowing the contract."
  [comparison]
  (when-not (zero? (:mismatched comparison))
    (fail! "Pkl.Core package corpus is not conformant with the pinned JVM corpus"
           {:kind :pkl-core-corpus-mismatch
            :mismatched (:mismatched comparison)
            :mismatches (:mismatches comparison)}))
  comparison)

(defn- result-row
  [case-data origin status observation diagnostic]
  (merge
   (select-keys case-data (into [:case-id] provenance-fields))
   {:origin origin
    :upstream-revision contract/pinned-upstream-revision
    :status status
    :observation-base64 (b64 observation)
    :diagnostic-base64 (b64 diagnostic)}))

(defn- synthetic-upstream-rows
  [validated]
  (mapv
   (fn [case-data]
     (if (#{"upstream-explicitly-disabled" "enabled-on-windows"
            "external-reader-path-conditional"}
          (:expected-outcome case-data))
       (result-row case-data "upstream-jvm" "CONDITION_AUDIT"
                   (:expected-outcome case-data)
                   (str "Focused condition oracle: " (:platform-conditions case-data)))
       (result-row case-data "upstream-jvm" "PASS"
                   (:expected-outcome case-data) "")))
   (:cases validated)))

(defn- conformant-package-rows
  [validated upstream-rows]
  (mapv
   (fn [case-data upstream]
     (case (:product-classification case-data)
       "user-approved-excluded-surface"
       (result-row case-data "package-dotnet" "APPROVED_EXCLUSION" "" "")

       "test-infrastructure-only-mechanics"
       (result-row case-data "package-dotnet" "TEST_INFRASTRUCTURE" "" "")

       (result-row case-data "package-dotnet" "PASS"
                   (:expected-outcome case-data) "")))
   (:cases validated) upstream-rows))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:kind (ex-data error)))))

(defn prove-fail-closed-controls!
  "Writes and executes deliberate JVM/package perturbation, omission,
  duplication, stale-provenance, crash, and timeout controls."
  [validated output-root]
  (let [output-root (paths/absolute output-root)
        upstream-rows (synthetic-upstream-rows validated)
        package-rows (conformant-package-rows validated upstream-rows)
        executable-index (first (keep-indexed
                                 (fn [index case-data]
                                   (when (contains?
                                          #{"jvm-shared-product-behavior"
                                            "idiomatic-dotnet-adaptation"}
                                          (:product-classification case-data))
                                     index))
                                 (:cases validated)))
        upstream (write-results! (paths/resolve-path output-root "control-upstream.tsv")
                                 upstream-rows)
        package (write-results! (paths/resolve-path output-root "control-package.tsv")
                                package-rows)
        compare-control
        (fn [name rows]
          (let [file (write-results! (paths/resolve-path output-root name) rows)]
            (compare-results validated upstream file)))
        upstream-perturbed
        (write-results!
         (paths/resolve-path output-root "control-upstream-perturbed.tsv")
         (update upstream-rows executable-index assoc
                 :observation-base64 (b64 "deliberate-jvm-perturbation")))
        upstream-perturbation
        (compare-results validated upstream-perturbed package)
        package-perturbation
        (compare-control
         "control-package-perturbed.tsv"
         (update package-rows executable-index assoc
                 :observation-base64 (b64 "deliberate-package-perturbation")))
        crash (compare-control
               "control-package-crash.tsv"
               (update package-rows executable-index assoc
                       :status "CRASH" :observation-base64 ""
                       :diagnostic-base64 (b64 "deliberate crash")))
        timeout (compare-control
                 "control-package-timeout.tsv"
                 (update package-rows executable-index assoc
                         :status "TIMEOUT" :observation-base64 ""
                         :diagnostic-base64 (b64 "deliberate timeout")))
        missing-file
        (write-results! (paths/resolve-path output-root "control-package-missing.tsv")
                        (vec (concat (subvec package-rows 0 executable-index)
                                     (subvec package-rows (inc executable-index)))))
        duplicate-file
        (write-results! (paths/resolve-path output-root "control-package-duplicate.tsv")
                        (assoc package-rows executable-index
                               (nth package-rows (mod (inc executable-index)
                                                      (count package-rows)))))
        stale-file
        (write-results! (paths/resolve-path output-root "control-package-stale.tsv")
                        (update package-rows executable-index assoc
                                :source-sha256 (apply str (repeat 64 "0"))))
        controls
        {:jvm-perturbation (= 1 (:mismatched upstream-perturbation))
         :package-perturbation (= 1 (:mismatched package-perturbation))
         :crash (= :package-execution-failure
                   (get-in crash [:mismatches 0 :kind]))
         :timeout (= :package-execution-failure
                     (get-in timeout [:mismatches 0 :kind]))
         :missing (= :pkl-core-corpus-result-coverage
                     (thrown-kind #(validate-results! validated "package-dotnet"
                                                      missing-file)))
         :duplicate (= :duplicate-pkl-core-corpus-results
                       (thrown-kind #(validate-results! validated "package-dotnet"
                                                        duplicate-file)))
         :stale (= :stale-pkl-core-corpus-provenance
                   (thrown-kind #(validate-results! validated "package-dotnet"
                                                    stale-file)))}]
    (when-not (every? true? (vals controls))
      (fail! "Pkl.Core corpus fail-closed controls did not all trigger"
             {:kind :pkl-core-corpus-control-failed :controls controls}))
    controls))

(defn- verify-package-source-isolation!
  [^Path consumer-root ^Path project ^Path source]
  (let [base (try
               (package-provenance/verify-source-isolation!
                consumer-root project source)
               (catch clojure.lang.ExceptionInfo error
                 (fail! "Package Pkl.Core corpus consumer crosses the shared package isolation boundary"
                        {:kind :pkl-core-corpus-source-isolation
                         :cause-kind (:kind (ex-data error))})))
        project-text (Files/readString project StandardCharsets/UTF_8)
        source-text (Files/readString source StandardCharsets/UTF_8)
        forbidden-patterns
        {"project-reference" #"<ProjectReference\b"
         "assembly-reference" #"<Reference\b"
         "source-include" #"<Compile\b"
         "generated-source" #"(?i)target/generated|(?i)generated-source"
         "internal-runtime-namespace" #"Pkl[.]Core[.]Runtime"
         "internal-messaging-namespace" #"Pkl[.]Core[.]Messaging"
         "excluded-yaml-surface" #"(?i)Yaml"
         "excluded-binary-surface" #"(?i)PklBinary|MessagePack"}
        forbidden
        (->> forbidden-patterns
             (keep (fn [[label pattern]]
                     (when (or (re-find pattern project-text)
                               (re-find pattern source-text))
                       label)))
             sort vec)]
    (when (seq forbidden)
      (fail! "Package Pkl.Core corpus consumer crosses a forbidden source boundary"
             {:kind :pkl-core-corpus-source-isolation
              :project (str project) :source (str source) :forbidden forbidden}))
    (assoc base :forbidden [])))

(def ^:private assembly-result-magic
  "VIBEFORMER_PKL_CORE_LOADED_ASSEMBLIES_V1")

(defn- validate-loaded-assemblies!
  [assembly-manifest loaded-file consumer-root]
  (let [expected (package-provenance/read-packed-assembly-manifest assembly-manifest)
        lines (str/split-lines (Files/readString loaded-file StandardCharsets/UTF_8))
        rows (mapv #(str/split % #"\t" -1) (rest lines))
        actual
        (mapv (fn [[name path expected-hash actual-hash]]
                {:name name :path path :expected-sha256 expected-hash
                 :actual-sha256 actual-hash})
              rows)
        consumer-root (.toRealPath (paths/absolute consumer-root)
                                   (make-array LinkOption 0))]
    (when-not (= assembly-result-magic (first lines))
      (fail! "Loaded assembly evidence has the wrong schema marker"
             {:kind :invalid-pkl-core-loaded-assembly-schema
              :actual (first lines)}))
    (when-not (= (mapv #(select-keys % [:name :sha256]) expected)
                 (mapv (fn [row]
                         {:name (:name row) :sha256 (:expected-sha256 row)})
                       actual))
      (fail! "Loaded assembly evidence does not name the exact packed closure"
             {:kind :pkl-core-loaded-assembly-provenance
              :expected expected :actual actual}))
    (doseq [{:keys [name path expected-sha256 actual-sha256]} actual]
      (let [real-path (.toRealPath (paths/absolute path) (make-array LinkOption 0))]
        (when-not (.startsWith real-path consumer-root)
          (fail! "Loaded package assembly escaped the isolated consumer output"
                 {:kind :pkl-core-loaded-assembly-path
                  :assembly name :consumer (str consumer-root) :actual (str real-path)}))
        (when-not (= expected-sha256 actual-sha256)
          (fail! "Loaded package assembly hash differs from the packed artifact"
                 {:kind :pkl-core-loaded-assembly-hash
                  :assembly name :expected expected-sha256 :actual actual-sha256}))))
    actual))

(defn- write-family-summary!
  [output validated comparison package-file]
  (let [statuses (frequencies (map :status
                                   (:rows (validate-results!
                                           validated "package-dotnet" package-file))))
        comparisons-by-family (group-by :behavior-family (:comparisons comparison))
        families
        (into (sorted-map)
              (for [[family cases] (group-by :behavior-family (:cases validated))]
                [family
                 {:total (count cases)
                  :matched (count (filter #(= :matched (:kind %))
                                          (get comparisons-by-family family)))
                  :mismatched (count (filter #(contains?
                                               #{:upstream-execution-failure
                                                 :package-execution-failure
                                                 :status-mismatch
                                                 :observation-mismatch}
                                               (:kind %))
                                             (get comparisons-by-family family)))}]))]
    (write-text!
     output
     (str "VIBEFORMER_PKL_CORE_CORPUS_FAMILY_BASELINE_V1\n"
          "family\ttotal\tmatched\tmismatched\n"
          (apply str
                 (for [[family counts] families]
                   (str family "\t" (:total counts) "\t" (:matched counts) "\t"
                        (:mismatched counts) "\n")))))
    {:families families :statuses statuses}))

(defn- write-mismatches!
  [output mismatches]
  (write-text!
   output
   (str "VIBEFORMER_PKL_CORE_CORPUS_MISMATCHES_V1\n"
        "case-id\tbehavior-family\tproduct-classification\tkind\texpected-status\tactual-status\texpected-observation-base64\tactual-observation-base64\n"
        (apply str
               (for [row mismatches]
                 (str (str/join "\t"
                                (map #(get row %)
                                     [:case-id :behavior-family :product-classification
                                      :kind :expected-status :actual-status
                                      :expected-observation-base64
                                      :actual-observation-base64]))
                      "\n"))))))

(defn- consumer-project-file
  [consumer-root]
  (paths/resolve-path consumer-root "Pkl.Core.PackageConsumer.csproj"))

(defn verify-corpus-runner!
  "Executes the pinned upstream contract and isolated package consumer twice.
  Infrastructure failures throw; semantic mismatches are retained as the
  implementation baseline consumed by the dependent behavior tasks."
  ([] (verify-corpus-runner! {}))
  ([{:keys [workspace-root manifest run-command! package-fn case-timeout-ms
            process-timeout-ms worker-count]
     :or {run-command! process/run!
          package-fn packaging/verify-package-consumption!
          case-timeout-ms default-case-timeout-ms
          process-timeout-ms default-process-timeout-ms
          worker-count default-worker-count}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         manifest (or manifest
                      (paths/resolve-path root "vibeformer" "validation"
                                          "pkl-core-test-contract"
                                          "PklCoreTestContract.tsv"))
         validated (contract/validate-manifest! root manifest)
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "vibeformer" "validation-output"
                                         "pkl-core-corpus"))
         upstream-first (paths/resolve-path proof-root "upstream-first.tsv")
         upstream-second (paths/resolve-path proof-root "upstream-second.tsv")
         package-first (paths/resolve-path proof-root "package-first.tsv")
         package-second (paths/resolve-path proof-root "package-second.tsv")
         upstream (paths/resolve-path root "research" "pkl")
         init-script (paths/resolve-path root "vibeformer" "gradle"
                                         "pkl-core-corpus.gradle")
         contract-dir (paths/resolve-path root "vibeformer" "validation"
                                          "pkl-core-test-contract")
         run-upstream!
         (fn [output]
           (run-command!
            {:command ["env" (str "VIBEFORMER_WORKERS=" worker-count)
                       "GRADLE_OPTS=-Xmx28g" "./gradlew" "-I" (str init-script)
                       ":pkl-core:vibeformerPklCoreCorpus" "--console=plain"
                       (str "-Pvibeformer.contractDir=" contract-dir)
                       (str "-Pvibeformer.contractManifest=" manifest)
                       (str "-Pvibeformer.corpusOutput=" output)
                       (str "-Pvibeformer.caseTimeoutMs=" case-timeout-ms)
                       (str "-Pvibeformer.corpusWorkers=" worker-count)]
             :directory upstream
             :timeout-ms process-timeout-ms}))]
     (when-not (and (pos-int? case-timeout-ms)
                    (pos-int? process-timeout-ms)
                    (pos-int? worker-count)
                    (> process-timeout-ms case-timeout-ms))
       (fail! "Pkl.Core corpus bounds must be positive and process-bounded"
              {:kind :invalid-pkl-core-corpus-bounds
               :case-timeout-ms case-timeout-ms
               :process-timeout-ms process-timeout-ms
               :worker-count worker-count}))
     (doseq [required [manifest init-script]]
       (when-not (paths/regular-file? required)
         (fail! "Pkl.Core corpus input is missing"
                {:kind :missing-pkl-core-corpus-input :path (str required)})))
     (run-upstream! upstream-first)
     (run-upstream! upstream-second)
     (let [upstream-determinism
           (compare-repeated-results validated "upstream-jvm"
                                     upstream-first upstream-second)]
       (when-not (:deterministic? upstream-determinism)
         (fail! "Repeated pinned-JVM corpus executions were not byte-identical"
                {:kind :nondeterministic-upstream-pkl-core-corpus
                 :first (str upstream-first) :second (str upstream-second)}))
       (let [package-proof (package-fn {:workspace-root root
                                        :profile "pkl-core-value-model"
                                        :run-command! run-command!})
             consumer-root (:consumer-root package-proof)
             packages-root (paths/resolve-path (:proof-root package-proof) "packages")
             project (consumer-project-file consumer-root)
             source (paths/resolve-path consumer-root "Program.cs")
             runner-source (paths/resolve-path root "vibeformer" "validation"
                                               "pkl-core-corpus"
                                               "PklCorePackageCorpusRunner.cs")
             assembly-manifest (paths/resolve-path proof-root "packed-assemblies.tsv")
             first-loaded (paths/resolve-path proof-root "loaded-assemblies-first.tsv")
             second-loaded (paths/resolve-path proof-root "loaded-assemblies-second.tsv")
             run-package!
             (fn [output loaded]
               (run-command!
                {:command ["dotnet" "run" "--project" (str project)
                           "--no-build" "--no-restore" "--"
                           (str manifest) (str output) (str assembly-manifest)
                           (str packages-root) (str loaded)
                           (str case-timeout-ms) (str worker-count)]
                 :directory consumer-root
                 :timeout-ms process-timeout-ms}))]
         (when-not (paths/regular-file? runner-source)
           (fail! "Package Pkl.Core corpus runner source is missing"
                  {:kind :missing-pkl-core-corpus-input :path (str runner-source)}))
         (package-provenance/write-packed-assembly-manifest!
          assembly-manifest (:packages package-proof))
         (Files/copy runner-source source
                     (into-array StandardCopyOption
                                 [StandardCopyOption/REPLACE_EXISTING]))
         (let [source-isolation
               (verify-package-source-isolation! consumer-root project source)]
           (run-command! {:command ["dotnet" "build" (str project)
                                    "--nologo" "--verbosity:minimal" "--no-restore"
                                    "--no-incremental" "-warnaserror"]
                          :directory consumer-root})
           (run-package! package-first first-loaded)
           (run-package! package-second second-loaded)
           (let [package-determinism
                 (compare-repeated-results validated "package-dotnet"
                                           package-first package-second)
                 _ (when-not (:deterministic? package-determinism)
                     (fail! "Repeated isolated-package corpus executions were not byte-identical"
                            {:kind :nondeterministic-package-pkl-core-corpus
                             :first (str package-first) :second (str package-second)}))
                 first-assemblies
                 (validate-loaded-assemblies! assembly-manifest first-loaded consumer-root)
                 second-assemblies
                 (validate-loaded-assemblies! assembly-manifest second-loaded consumer-root)
                 _ (when-not (= first-assemblies second-assemblies)
                     (fail! "Repeated loaded-assembly evidence was not deterministic"
                            {:kind :nondeterministic-pkl-core-loaded-assemblies
                             :first first-assemblies :second second-assemblies}))
                 comparison (compare-results validated upstream-first package-first)
                 family-file (paths/resolve-path proof-root "family-baseline.tsv")
                 mismatch-file (paths/resolve-path proof-root "mismatches.tsv")
                 baseline (write-family-summary! family-file validated comparison
                                                 package-first)
                 _ (write-mismatches! mismatch-file (:mismatches comparison))
                 controls (prove-fail-closed-controls!
                           validated (paths/resolve-path proof-root "controls"))
                 summary {:cases (:total comparison)
                          :matched (:matched comparison)
                          :approved-exclusions (:approved-exclusions comparison)
                          :test-infrastructure-audits
                          (:test-infrastructure-audits comparison)
                          :mismatched (:mismatched comparison)
                          :upstream-deterministic-observations
                          (:observations upstream-determinism)
                          :package-deterministic-observations
                          (:observations package-determinism)
                          :package-statuses (:statuses baseline)
                          :package (:identity package-proof)
                          :controls controls}]
             (println "Complete Pkl.Core corpus runner baseline recorded:"
                      (pr-str summary))
             {:summary summary
              :manifest (paths/absolute manifest)
              :upstream-first upstream-first
              :upstream-second upstream-second
              :package-first package-first
              :package-second package-second
              :family-baseline family-file
              :mismatches mismatch-file
              :package-proof package-proof
              :source-isolation source-isolation
              :loaded-assemblies first-assemblies})))))))
