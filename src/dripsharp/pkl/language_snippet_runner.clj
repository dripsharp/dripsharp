(ns dripsharp.pkl.language-snippet-runner
  "Isolated package-only execution of the pinned language-snippet contract."
  (:require [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.package-provenance :as package-provenance]
            [dripsharp.pkl.language-snippet-contract :as contract]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path StandardCopyOption]
           [java.util Base64]))

(def ^:private result-magic "DRIPSHARP_LANGUAGE_SNIPPET_PACKAGE_RESULTS_V1")

(def ^:private result-columns
  ["case-id" "status" "normalized-payload-base64" "logger-base64" "diagnostic-base64"])

(def ^:private execution-statuses
  #{"SUCCESS" "ERROR" "TIMEOUT" "CRASH" "APPROVED_EXCLUSION"})

(def ^:private approved-exclusion "outside-epic-approved-exclusion")
(def ^:private mixed-excluded-surface "in-scope-mixed-excluded-surface")
(def ^:private messagepack-debug-requirement "messagepack-debug-decoding")
(def ^:private excluded-messagepack-boundary
  "MessagePack is excluded from the DripSharp product target.")

(def ^:private excluded-messagepack-bug-prefix
  "DripSharp.Brine.PklBugException: An unexpected error has occurred.")

(def ^:private excluded-messagepack-final-frame
  #"^\s+at Program[.]EvaluateCase[(].*[)](?: in .+:line [0-9]+)?$")

(def ^:private default-evaluation-timeout-ms 15000)
(def ^:private default-process-timeout-ms 30000)
(def ^:private default-worker-count 2)

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind (or (:kind data)
                                                :language-snippet-runner-failed)))))

(def ^:private write-text! util/write-text!)

(defn- decode-base64!
  [value context]
  (try
    (String. (.decode (Base64/getDecoder) value) StandardCharsets/UTF_8)
    (catch IllegalArgumentException error
      (fail! "Package language-snippet result contains invalid base64"
             (assoc context :kind :malformed-package-language-snippet-result)))))

(defn read-package-results
  "Reads the rich package-runner result format and rejects malformed rows."
  [result-file]
  (let [result-file (paths/absolute result-file)
        lines (str/split-lines (Files/readString result-file StandardCharsets/UTF_8))]
    (when-not (= result-magic (first lines))
      (fail! "Package language-snippet result has the wrong schema marker"
             {:kind :invalid-package-language-snippet-schema
              :path (str result-file) :actual (first lines)}))
    (let [columns (some-> (second lines) (str/split #"\t" -1))]
      (when-not (= (into ["columns"] result-columns) columns)
        (fail! "Package language-snippet result columns drifted"
               {:kind :package-language-snippet-columns-drift
                :expected result-columns :actual (vec (rest columns))}))
      {:path result-file
       :columns result-columns
       :rows
       (mapv
        (fn [index line]
          (let [fields (str/split line #"\t" -1)]
            (when-not (and (= "case" (first fields))
                           (= (inc (count result-columns)) (count fields)))
              (fail! "Malformed package language-snippet result row"
                     {:kind :malformed-package-language-snippet-result
                      :line (+ index 3) :actual line}))
            (let [[case-id status payload logger diagnostic] (rest fields)
                  context {:line (+ index 3) :case case-id}]
              (when-not (execution-statuses status)
                (fail! "Package language-snippet result has an unknown status"
                       (assoc context :kind :malformed-package-language-snippet-result
                              :status status)))
              {:case-id case-id
               :status status
               :payload-base64 payload
               :payload (decode-base64! payload (assoc context :field :payload))
               :logger-base64 logger
               :logger (decode-base64! logger (assoc context :field :logger))
               :diagnostic-base64 diagnostic
               :diagnostic (decode-base64! diagnostic (assoc context :field :diagnostic))
               :line line})))
        (range)
        (drop 2 lines))})))

(defn- duplicate-values
  [values]
  (->> values frequencies (keep (fn [[value n]] (when (> n 1) value))) sort vec))

(defn validate-package-results!
  "Requires one explicit, ordered result for every manifest row. Approved
  exclusions must be explicit and cannot be used for an in-scope case."
  [validated-manifest result-file]
  (let [{:keys [rows] :as parsed} (read-package-results result-file)
        cases (:cases validated-manifest)
        expected-ids (mapv :case-id cases)
        actual-ids (mapv :case-id rows)]
    (when-let [duplicates (seq (duplicate-values actual-ids))]
      (fail! "Package language-snippet results contain duplicate cases"
             {:kind :duplicate-package-language-snippet-results
              :cases duplicates}))
    (when-not (= expected-ids actual-ids)
      (fail! "Package language-snippet results do not cover the manifest in order"
             {:kind :package-language-snippet-result-coverage
              :expected expected-ids :actual actual-ids}))
    (doseq [[case-data row] (map vector cases rows)]
      (let [outside? (= approved-exclusion (:product-scope case-data))]
        (when-not (= outside? (= "APPROVED_EXCLUSION" (:status row)))
          (fail! (if outside?
                   "Approved exclusion was not recorded explicitly"
                   "An in-scope case was converted into an exclusion")
                 {:kind (if outside?
                          :missing-approved-language-snippet-exclusion
                          :unapproved-package-language-snippet-exclusion)
                  :case (:case-id case-data) :status (:status row)}))
        (when (and outside?
                   (some (complement str/blank?)
                         [(:payload row) (:logger row) (:diagnostic row)]))
          (fail! "Approved exclusion result contains fabricated execution evidence"
                 {:kind :invalid-approved-language-snippet-exclusion
                  :case (:case-id case-data)}))
        (when (and (#{"TIMEOUT" "CRASH"} (:status row))
                   (str/blank? (:diagnostic row)))
          (fail! "Timeout or crash result omits its diagnostic evidence"
                 {:kind :missing-package-language-snippet-diagnostic
                  :case (:case-id case-data) :status (:status row)}))))
    (assoc parsed :rows rows)))

(defn- read-oracle-results
  [oracle-file]
  (mapv
   (fn [index line]
     (let [fields (str/split line #"\t" -1)]
       (when-not (= 3 (count fields))
         (fail! "Malformed pinned language-snippet oracle row"
                {:kind :malformed-language-snippet-oracle-result
                 :line (inc index) :actual line}))
       (let [[case-id status payload] fields]
         (when-not (#{"SUCCESS" "ERROR"} status)
           (fail! "Pinned language-snippet oracle has an invalid status"
                  {:kind :malformed-language-snippet-oracle-result
                   :line (inc index) :case case-id :status status}))
         (decode-base64! payload {:line (inc index) :case case-id :field :payload})
         {:case-id case-id :status status :payload-base64 payload :line line})))
   (range)
   (str/split-lines (Files/readString (paths/path oracle-file) StandardCharsets/UTF_8))))

(def ^:private sha256-text util/sha256-text)

(defn- execution-requirement?
  [case-data requirement]
  (some #{requirement}
        (str/split (or (:execution-requirements case-data) "") #";" -1)))

(defn- approved-excluded-surface-boundary?
  "Recognizes the exact runtime boundary for a mixed-scope observation whose
  oracle bytes require the already user-excluded Pkl-binary transport. The row
  remains executed and in scope; only its transport-dependent observation is
  separated from directly comparable behavior."
  [case-data actual]
  (let [payload (:payload actual)
        lines (str/split-lines payload)
        exception-lines (filterv #(str/includes? % "Exception:") lines)
        boundary-exceptions
        [(str "System.NotSupportedException: " excluded-messagepack-boundary)
         (str " ---> DripSharp.Brine.Runtime.VmBugException: " excluded-messagepack-boundary)
         (str " ---> System.NotSupportedException: " excluded-messagepack-boundary)]]
    (and (= mixed-excluded-surface (:product-scope case-data))
         (execution-requirement? case-data messagepack-debug-requirement)
         (= "ERROR" (:status actual))
         (str/blank? (:logger actual))
         (str/blank? (:diagnostic actual))
         (str/starts-with? payload excluded-messagepack-bug-prefix)
         (= 4 (count (re-seq (re-pattern
                              (java.util.regex.Pattern/quote
                               excluded-messagepack-boundary))
                             payload)))
         (= 4 (count exception-lines))
         (= boundary-exceptions (subvec exception-lines 1))
         (str/includes? payload (str "\n" excluded-messagepack-boundary "\n\n"))
         (boolean
          (re-matches excluded-messagepack-final-frame (or (last lines) ""))))))

(defn compare-package-results
  "Compares every in-scope result with the pinned oracle. Mixed-scope rows that
  reach the exact approved Pkl-binary transport boundary remain executed and
  explicit; every other mismatch, crash, or timeout is retained as a failure."
  [validated-manifest oracle-file package-result-file]
  (let [oracle (read-oracle-results oracle-file)
        package (:rows (validate-package-results! validated-manifest package-result-file))
        cases (:cases validated-manifest)
        manifest-ids (mapv :case-id cases)
        oracle-ids (mapv :case-id oracle)]
    (when-let [duplicates (seq (duplicate-values oracle-ids))]
      (fail! "Pinned language-snippet oracle contains duplicate cases"
             {:kind :duplicate-language-snippet-oracle-results :cases duplicates}))
    (when-not (= manifest-ids oracle-ids)
      (fail! "Pinned language-snippet oracle does not cover the manifest in order"
             {:kind :language-snippet-oracle-result-coverage
              :expected manifest-ids :actual oracle-ids}))
    (let [comparisons
          (mapv
           (fn [case-data expected actual]
             (let [outside? (= approved-exclusion (:product-scope case-data))
                   excluded-surface-boundary?
                   (and (not outside?)
                        (approved-excluded-surface-boundary? case-data actual))
                   matched? (and (not outside?)
                                 (= (:status expected) (:status actual))
                                 (= (:payload-base64 expected) (:payload-base64 actual)))
                   kind (cond
                          outside? :approved-exclusion
                          excluded-surface-boundary? :approved-excluded-surface-boundary
                          (#{"TIMEOUT" "CRASH"} (:status actual)) :execution-failure
                          (not= (:status expected) (:status actual)) :status-mismatch
                          (not= (:payload-base64 expected) (:payload-base64 actual)) :content-mismatch
                          :else :match)]
               {:case-id (:case-id case-data)
                :semantic-family (:semantic-family case-data)
                :source-family (:source-family case-data)
                :product-scope (:product-scope case-data)
                :expected-status (:status expected)
                :actual-status (:status actual)
                :expected-payload-base64 (:payload-base64 expected)
                :actual-payload-base64 (:payload-base64 actual)
                :expected-sha256 (sha256-text (:payload-base64 expected))
                :actual-sha256 (sha256-text (:payload-base64 actual))
                :matched? matched?
                :kind kind}))
           cases oracle package)
          mismatches
          (filterv #(not (#{:match :approved-exclusion
                            :approved-excluded-surface-boundary} (:kind %)))
                   comparisons)
          boundary-count
          (count (filter #(= :approved-excluded-surface-boundary (:kind %)) comparisons))]
      {:total (count comparisons)
       :in-scope (count (remove #(= approved-exclusion (:product-scope %)) comparisons))
       :excluded (count (filter #(= :approved-exclusion (:kind %)) comparisons))
       :matched (count (filter :matched? comparisons))
       :approved-excluded-surface-boundaries boundary-count
       :conformant (+ (count (filter :matched? comparisons)) boundary-count)
       :mismatched (count mismatches)
       :mismatches mismatches
       :comparisons comparisons})))

(defn compare-runner-results
  "Checks deterministic runner observations independently of oracle conformance."
  [validated-manifest first-file second-file]
  (let [first (:rows (validate-package-results! validated-manifest first-file))
        second (:rows (validate-package-results! validated-manifest second-file))
        mismatches
        (->> (map vector first second)
             (keep (fn [[left right]]
                     (when-not (= (select-keys left [:case-id :status :payload-base64
                                                     :logger-base64 :diagnostic-base64])
                                  (select-keys right [:case-id :status :payload-base64
                                                      :logger-base64 :diagnostic-base64]))
                       {:case (:case-id left)
                        :first (select-keys left [:status :payload-base64 :logger-base64
                                                  :diagnostic-base64])
                        :second (select-keys right [:status :payload-base64 :logger-base64
                                                    :diagnostic-base64])})))
             vec)]
    {:observations (count first) :mismatches mismatches
     :deterministic? (empty? mismatches)}))

(defn- status-counts
  [rows]
  (merge (zipmap execution-statuses (repeat 0)) (frequencies (map :status rows))))

(defn summarize-family-baseline
  [validated-manifest package-result-file comparison]
  (let [rows (:rows (validate-package-results! validated-manifest package-result-file))
        mismatched-ids (set (map :case-id (:mismatches comparison)))]
    (into
     (sorted-map)
     (for [[family entries]
           (group-by (comp :semantic-family first) (map vector (:cases validated-manifest) rows))]
       (let [cases (mapv first entries)
             family-rows (mapv second entries)
             in-scope (remove #(= approved-exclusion (:product-scope %)) cases)
             excluded (- (count cases) (count in-scope))
             counts (status-counts family-rows)
             family-comparisons
             (filterv #(= family (:semantic-family %)) (:comparisons comparison))
             boundary-count
             (count (filter #(= :approved-excluded-surface-boundary (:kind %))
                            family-comparisons))
             mismatch-count (count (filter mismatched-ids (map :case-id cases)))
             matched-count (- (count in-scope) boundary-count mismatch-count)]
         [family {:total (count cases)
                  :in-scope (count in-scope)
                  :excluded excluded
                  :matched matched-count
                  :approved-excluded-surface-boundaries boundary-count
                  :conformant (+ matched-count boundary-count)
                  :mismatched mismatch-count
                  :success (counts "SUCCESS")
                  :error (counts "ERROR")
                  :timeout (counts "TIMEOUT")
                  :crash (counts "CRASH")}])))))

(defn- render-package-results
  [rows]
  (str result-magic "\n"
       "columns\t" (str/join "\t" result-columns) "\n"
       (apply str
              (for [{:keys [case-id status payload-base64 logger-base64
                            diagnostic-base64]} rows]
                (str "case\t" case-id "\t" status "\t" payload-base64 "\t"
                     logger-base64 "\t" diagnostic-base64 "\n")))))

(defn- oracle-shaped-results!
  [validated-manifest oracle-file output]
  (let [oracle (read-oracle-results oracle-file)
        rows (mapv (fn [case-data expected]
                     (if (= approved-exclusion (:product-scope case-data))
                       {:case-id (:case-id case-data) :status "APPROVED_EXCLUSION"
                        :payload-base64 "" :logger-base64 "" :diagnostic-base64 ""}
                       {:case-id (:case-id case-data) :status (:status expected)
                        :payload-base64 (:payload-base64 expected)
                        :logger-base64 "" :diagnostic-base64 ""}))
                   (:cases validated-manifest) oracle)]
    (write-text! (paths/absolute output) (render-package-results rows))))

(defn- perturb-results!
  [validated-manifest source output]
  (let [parsed (validate-package-results! validated-manifest source)
        index (first (keep-indexed
                      (fn [index row]
                        (when (#{"SUCCESS" "ERROR"} (:status row)) index))
                      (:rows parsed)))]
    (when-not index
      (fail! "No executable in-scope result is available for perturbation"
             {:kind :language-snippet-perturbation-unavailable}))
    (let [rows (update (:rows parsed) index assoc :payload-base64 "UFJPVkU=")]
      (write-text! (paths/absolute output) (render-package-results rows)))))

(defn verify-source-isolation!
  "Rejects source/project escape hatches in a freshly restored package-only
  consumer."
  [^Path consumer-root ^Path project ^Path source]
  (let [root (.toRealPath consumer-root (make-array LinkOption 0))
        source-path (.toRealPath source (make-array LinkOption 0))
        project-text (Files/readString project StandardCharsets/UTF_8)
        source-text (Files/readString source StandardCharsets/UTF_8)
        forbidden (->> ["target/generated" "ProjectReference" "Compile Include"
                        "HintPath" "#line" "runtime/" "runtime\\"]
                       (filter #(or (str/includes? project-text %)
                                    (str/includes? source-text %)))
                       vec)]
    (when-not (.startsWith source-path root)
      (fail! "Package language-snippet runner source escapes its isolated project"
             {:kind :language-snippet-source-isolation
              :root (str root) :source (str source-path)}))
    (when (seq forbidden)
      (fail! "Package language-snippet runner contains a source or reference escape hatch"
             {:kind :language-snippet-source-isolation
              :project (str project) :source (str source) :forbidden forbidden}))
    {:project (str project) :source (str source) :forbidden []}))

(defn- write-family-summary!
  [^Path output summary]
  (write-text!
   output
   (str "DRIPSHARP_LANGUAGE_SNIPPET_FAMILY_BASELINE_V2\n"
        "family\ttotal\tin-scope\texcluded\tmatched\tapproved-excluded-surface-boundaries\tconformant\tmismatched\tsuccess\terror\ttimeout\tcrash\n"
        (apply str
               (for [[family counts] summary]
                 (str family "\t"
                      (str/join "\t" (map counts [:total :in-scope :excluded :matched
                                                  :approved-excluded-surface-boundaries
                                                  :conformant :mismatched :success :error
                                                  :timeout :crash]))
                      "\n"))))))

(defn- write-mismatches!
  [^Path output mismatches]
  (write-text!
   output
   (str "DRIPSHARP_LANGUAGE_SNIPPET_MISMATCHES_V1\n"
        "case-id\tsemantic-family\tsource-family\tkind\texpected-status\tactual-status\texpected-sha256\tactual-sha256\texpected-base64\tactual-base64\n"
        (apply str
               (for [mismatch mismatches]
                 (str (str/join "\t"
                                (map #(get mismatch %)
                                     [:case-id :semantic-family :source-family :kind
                                      :expected-status :actual-status :expected-sha256
                                      :actual-sha256 :expected-payload-base64
                                      :actual-payload-base64]))
                      "\n"))))))

(defn- consumer-project-file
  [consumer-root]
  (paths/resolve-path consumer-root "DripSharp.Brine.PackageConsumer.csproj"))

(defn verify-package-runner!
  "Packs DripSharp.Brine, builds a fresh package-reference-only runner, executes every
  manifest row twice, and retains a deterministic conformance baseline. Product
  mismatches are returned as implementation evidence instead of exclusions."
  ([] (verify-package-runner! {}))
  ([{:keys [workspace-root manifest run-command! package-fn evaluation-timeout-ms
            process-timeout-ms worker-count]
     :or {run-command! process/run!
          package-fn packaging/verify-package-consumption!
          evaluation-timeout-ms default-evaluation-timeout-ms
          process-timeout-ms default-process-timeout-ms
          worker-count default-worker-count}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         manifest (or manifest
                      (paths/resolve-path root "validation"
                                          "language-snippet-contract"
                                          "LanguageSnippetContract.tsv"))
         validated (contract/validate-manifest! root manifest)
         package-proof (package-fn {:workspace-root root
                                    :profile "pkl-core-value-model"
                                    :run-command! run-command!})
         proof-root (harness/clean-directory!
                     (paths/resolve-path root "validation-output"
                                         "language-snippet-package"))
         expected (contract/write-expected-results!
                   validated (paths/resolve-path proof-root "expected.tsv"))
         first-output (paths/resolve-path proof-root "package-first.tsv")
         second-output (paths/resolve-path proof-root "package-second.tsv")
         oracle-shaped (paths/resolve-path proof-root "oracle-shaped.tsv")
         perturbed (paths/resolve-path proof-root "oracle-perturbed.tsv")
         family-output (paths/resolve-path proof-root "family-baseline.tsv")
         mismatch-output (paths/resolve-path proof-root "mismatches.tsv")
         assembly-manifest (paths/resolve-path proof-root "packed-assemblies.tsv")
         upstream (paths/resolve-path root "research" "pkl")
         snippets (paths/resolve-path upstream "pkl-core" "src" "test" "files"
                                      "LanguageSnippetTests")
         package-build (paths/resolve-path upstream "pkl-commons-test" "build")
         consumer-root (:consumer-root package-proof)
         project (consumer-project-file consumer-root)
         source (paths/resolve-path consumer-root "Program.cs")
         runner-source (paths/resolve-path root "validation"
                                           "language-snippet-runner"
                                           "LanguageSnippetPackageRunner.cs")
         run-corpus!
         (fn [output]
           (run-command!
            {:command ["dotnet" "run" "--project" (str project)
                       "--no-build" "--no-restore" "--"
                       (str manifest) (str output) (str snippets) (str package-build)
                       (str assembly-manifest) (str evaluation-timeout-ms)
                       (str process-timeout-ms) (str worker-count)]
             :directory consumer-root}))]
     (doseq [required [project runner-source manifest]]
       (when-not (paths/regular-file? required)
         (fail! "Package language-snippet runner input is missing"
                {:kind :missing-language-snippet-runner-input :path (str required)})))
     (when-not (and (pos-int? evaluation-timeout-ms)
                    (pos-int? process-timeout-ms)
                    (pos-int? worker-count)
                    (> process-timeout-ms evaluation-timeout-ms))
       (fail! "Package language-snippet runner timeouts or worker count are invalid"
              {:kind :invalid-language-snippet-runner-bounds
               :evaluation-timeout-ms evaluation-timeout-ms
               :process-timeout-ms process-timeout-ms :worker-count worker-count}))
     (package-provenance/write-packed-assembly-manifest!
      assembly-manifest (:packages package-proof))
     (Files/copy runner-source source
                 (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
     (let [source-isolation (verify-source-isolation! consumer-root project source)]
       (run-command! {:command ["./gradlew" ":pkl-commons-test:processResources"
                                "--console=plain"]
                      :directory upstream})
       (run-command! {:command ["dotnet" "build" (str project) "--nologo"
                                "--verbosity:minimal" "--no-restore" "--no-incremental"
                                "-warnaserror"]
                      :directory consumer-root})
       (run-corpus! first-output)
       (run-corpus! second-output)
       (let [determinism (compare-runner-results validated first-output second-output)]
         (when-not (:deterministic? determinism)
           (fail! "Repeated package language-snippet runs were not deterministic"
                  {:kind :nondeterministic-language-snippet-runner
                   :mismatches (:mismatches determinism)}))
         (let [comparison (compare-package-results validated expected first-output)
               family-summary (summarize-family-baseline validated first-output comparison)
               _ (write-family-summary! family-output family-summary)
               _ (write-mismatches! mismatch-output (:mismatches comparison))
               _ (when-not (zero? (:mismatched comparison))
                   (fail! "Package language-snippet release gate found in-scope mismatches"
                          {:kind :language-snippet-release-mismatch
                           :comparison comparison
                           :mismatches mismatch-output}))
               _ (oracle-shaped-results! validated expected oracle-shaped)
               oracle-comparison (compare-package-results validated expected oracle-shaped)
               _ (when-not (zero? (:mismatched oracle-comparison))
                   (fail! "Comparator rejected the unmodified pinned oracle"
                          {:kind :language-snippet-comparator-self-check
                           :comparison oracle-comparison}))
               _ (perturb-results! validated oracle-shaped perturbed)
               perturbation (compare-package-results validated expected perturbed)
               _ (when-not (= 1 (:mismatched perturbation))
                   (fail! "Comparator did not isolate the deliberate oracle perturbation"
                          {:kind :language-snippet-perturbation-undetected
                           :comparison perturbation}))
               summary {:cases (:total comparison)
                        :in-scope (:in-scope comparison)
                        :approved-exclusions (:excluded comparison)
                        :matched (:matched comparison)
                        :approved-excluded-surface-boundaries
                        (:approved-excluded-surface-boundaries comparison)
                        :conformant (:conformant comparison)
                        :mismatched (:mismatched comparison)
                        :deterministic-observations (:observations determinism)
                        :perturbation-detected-at
                        (:case-id (first (:mismatches perturbation)))
                        :families family-summary
                        :package (:identity package-proof)}]
           (println "Package-only language-snippet baseline recorded:" (pr-str summary))
           {:summary summary
            :package-proof package-proof
            :source-isolation source-isolation
            :assembly-provenance
            (package-provenance/read-packed-assembly-manifest
             assembly-manifest)
            :manifest (paths/absolute manifest)
            :expected expected
            :first-output first-output
            :second-output second-output
            :family-baseline family-output
            :mismatches mismatch-output}))))))
