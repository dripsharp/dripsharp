(ns dripsharp.pdfcube.preflight-corpus
  "Checksum-pinned PDF/A corpus validation through synchronized PDFBox Java
  and a fresh package-reference-only DripSharp.PdfCarton.Preflight consumer."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.harness :as harness]
            [dripsharp.package-provenance :as package-provenance]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Arrays Base64]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def ^:private manifest-magic
  "DRIPSHARP_PDFCARTON_PREFLIGHT_CORPUS_MANIFEST_V1")

(def ^:private result-magic
  "DRIPSHARP_PDFCARTON_PREFLIGHT_CORPUS_RESULTS_V1")

(def ^:private assembly-magic
  "DRIPSHARP_PDFCARTON_PREFLIGHT_LOADED_ASSEMBLIES_V1")

(def result-columns
  ["case-id" "origin" "format" "expected-outcome" "input-sha256"
   "status" "valid" "error-count" "error-codes-base64"
   "warnings-base64" "pages-base64" "details-base64" "source-closed"
   "document-closed" "diagnostic-base64"])

(def ^:private result-statuses
  #{"PASS" "TIMEOUT" "CRASH" "LEAK"})

(def ^:private default-case-timeout-ms 30000)
(def ^:private default-process-timeout-ms 600000)

(defn- fail!
  [message data]
  (throw
   (ex-info
    message
    (assoc data :kind (or (:kind data)
                          :pdfcube-preflight-corpus-failed)))))

(def ^:private write-text! util/write-text!)

(defn- duplicate-values
  [values]
  (->> values
       frequencies
       (keep (fn [[value count]] (when (< 1 count) value)))
       sort
       vec))

(def ^:private sha256-bytes util/sha256-bytes)
(def ^:private sha256-file util/sha256-file)

(defn- b64
  [value]
  (.encodeToString
   (Base64/getEncoder)
   (.getBytes (str value) StandardCharsets/UTF_8)))

(defn- decode-base64!
  [value context]
  (try
    (String. (.decode (Base64/getDecoder) value) StandardCharsets/UTF_8)
    (catch IllegalArgumentException _
      (fail! "Preflight corpus result contains invalid base64"
             (assoc context :kind :malformed-preflight-corpus-result)))))

(defn- relative-contained-path!
  [^Path root value label]
  (let [candidate (paths/path value)
        resolved (paths/absolute (paths/resolve-path root candidate))]
    (when (or (.isAbsolute candidate)
              (not (.startsWith resolved root)))
      (fail! "Preflight corpus path escapes the repository"
             {:kind :invalid-preflight-corpus-path
              :label label :path value :root (str root)}))
    resolved))

(defn- verify-pinned-file!
  [^Path path expected context]
  (when-not (paths/regular-file? path)
    (fail! "Pinned Preflight corpus input is missing"
           (assoc context :kind :missing-preflight-corpus-input
                  :path (str path))))
  (let [actual (sha256-file path)]
    (when-not (= expected actual)
      (fail! "Pinned Preflight corpus checksum changed"
             (assoc context :kind :preflight-corpus-checksum-mismatch
                    :path (str path) :expected expected :actual actual))))
  path)

(defn- valid-sha256?
  [value]
  (boolean (re-matches #"[0-9a-f]{64}" (or value ""))))

(defn validate-manifest!
  "Loads the durable corpus configuration, validates checksums, provenance,
  redistribution obligations, and required PDF/A/process/outcome coverage."
  [workspace-root manifest]
  (let [root (paths/absolute workspace-root)
        manifest (paths/absolute manifest)
        data
        (try
          (util/read-single-edn-string!
           (Files/readString manifest StandardCharsets/UTF_8))
          (catch RuntimeException error
            (throw
             (ex-info
              "Preflight corpus manifest is not exactly one EDN value"
              {:kind :invalid-preflight-corpus-manifest
               :path (str manifest)
               :reason (:reason (ex-data error))}
              error))))
        target (:baseline-target data)
        baseline-record (baseline/read-baseline root target)
        upstream (:upstream baseline-record)
        [license-file notice-file]
        (baseline/legal-files root target [:upstream])
        oracle
        (merge (:oracle data)
               {:version (:version upstream)
                :revision (:revision upstream)
                :repository (:repository upstream)})
        redistribution
        (merge
         (:redistribution data)
         {:license (:license upstream)
          :license-path (:source license-file)
          :license-sha256 (:sha256 license-file)
          :notice-path (:source notice-file)
          :notice-sha256 (:sha256 notice-file)})
        sources (:sources data)
        cases (:cases data)]
    (when-not (= 1 (:schema-version data))
      (fail! "Preflight corpus manifest schema is unsupported"
             {:kind :invalid-preflight-corpus-manifest-schema
              :actual (:schema-version data)}))
    (when-not (= {:product "Apache PDFBox Preflight"
                  :version (:version upstream)
                  :revision (:revision upstream)
                  :repository (:repository upstream)}
                 oracle)
      (fail! "Preflight corpus Java oracle provenance drifted"
             {:kind :stale-preflight-corpus-oracle
              :expected-revision pinned-revision :actual oracle}))
    (when-not (and (= (:license upstream) (:license redistribution))
                   (not (str/blank? (:constraint redistribution))))
      (fail! "Preflight corpus redistribution constraints are incomplete"
             {:kind :missing-preflight-corpus-redistribution
              :redistribution redistribution}))
    (doseq [[kind path-key hash-key]
            [[:license :license-path :license-sha256]
             [:notice :notice-path :notice-sha256]]]
      (let [expected (get redistribution hash-key)
            path (relative-contained-path!
                  root (get redistribution path-key) kind)]
        (when-not (valid-sha256? expected)
          (fail! "Preflight corpus legal-file checksum is malformed"
                 {:kind :invalid-preflight-corpus-provenance
                  :field hash-key :actual expected}))
        (verify-pinned-file! path expected {:asset kind})))
    (when-not (and (map? sources) (seq sources) (vector? cases) (seq cases))
      (fail! "Preflight corpus manifest has no sources or cases"
             {:kind :empty-preflight-corpus-manifest}))
    (let [validated-sources
          (into
           {}
           (for [[source-id source] sources]
             (let [source-path
                   (relative-contained-path! root (:path source) source-id)
                   evidence-path
                   (relative-contained-path!
                    root (:upstream-test source) (str source-id "-test"))
                   expected (:sha256 source)]
               (when-not (and (string? source-id)
                              (not (str/blank? source-id))
                              (valid-sha256? expected)
                              (not (str/blank? (:origin source))))
                 (fail! "Preflight corpus source provenance is incomplete"
                        {:kind :invalid-preflight-corpus-provenance
                         :source source-id :value source}))
               (verify-pinned-file!
                source-path expected {:source source-id})
               (when-not (paths/regular-file? evidence-path)
                 (fail! "Preflight corpus upstream test evidence is missing"
                        {:kind :missing-preflight-corpus-evidence
                         :source source-id :path (str evidence-path)}))
               [source-id
                (assoc source
                       :source-path source-path
                       :evidence-path evidence-path)])))
          ids (mapv :id cases)
          _ (when-let [duplicates (seq (duplicate-values ids))]
              (fail! "Preflight corpus case identifiers are duplicated"
                     {:kind :duplicate-preflight-corpus-cases
                      :cases duplicates}))
          validated-cases
          (mapv
           (fn [case-data]
             (let [{:keys [id source staged-file transform payload-sha256
                           format expected-outcome processes]} case-data
                   source-data (get validated-sources source)
                   transform-kind (:kind transform)
                   staged-path (paths/path (or staged-file ""))]
               (when-not source-data
                 (fail! "Preflight corpus case references an unknown source"
                        {:kind :unknown-preflight-corpus-source
                         :case id :source source}))
               (when-not (and (string? id) (not (str/blank? id))
                              (string? staged-file)
                              (= staged-file
                                 (str (.getFileName staged-path)))
                              (valid-sha256? payload-sha256)
                              (contains? #{:pdf-a1a :pdf-a1b} format)
                              (contains? #{:valid :invalid :malformed}
                                         expected-outcome)
                              (vector? processes) (seq processes)
                              (every? #(and (string? %)
                                            (not (str/blank? %)))
                                      processes))
                 (fail! "Preflight corpus case is malformed"
                        {:kind :malformed-preflight-corpus-case
                         :case case-data}))
               (when-not (or (= :copy transform-kind)
                             (= :truncate-half transform-kind)
                             (and (= :prefix transform-kind)
                                  (pos-int? (:bytes transform))))
                 (fail! "Preflight corpus transform is unsupported"
                        {:kind :unsupported-preflight-corpus-transform
                         :case id :transform transform}))
               (assoc case-data :source-data source-data)))
           cases)
          supported-formats (set (:supported-formats data))
          required-outcomes (set (:required-outcomes data))
          required-processes (set (:required-processes data))
          actual-formats (set (map :format validated-cases))
          actual-outcomes (set (map :expected-outcome validated-cases))
          actual-processes (set (mapcat :processes validated-cases))
          missing-processes (set/difference required-processes
                                            actual-processes)]
      (when-not (= #{:pdf-a1a :pdf-a1b} supported-formats actual-formats)
        (fail! "Preflight corpus does not cover every supported PDF/A family"
               {:kind :incomplete-preflight-corpus-format-coverage
                :supported supported-formats :actual actual-formats}))
      (when-not (set/subset? required-outcomes actual-outcomes)
        (fail! "Preflight corpus does not cover valid, invalid, and malformed inputs"
               {:kind :incomplete-preflight-corpus-outcome-coverage
                :required required-outcomes :actual actual-outcomes}))
      (when (seq missing-processes)
        (fail! "Preflight corpus misses configured validation processes"
               {:kind :incomplete-preflight-corpus-process-coverage
                :missing (vec (sort missing-processes))}))
      {:manifest manifest
       :oracle oracle
       :redistribution redistribution
       :sources validated-sources
       :cases validated-cases
       :coverage
       {:formats (vec (sort actual-formats))
        :outcomes (vec (sort actual-outcomes))
        :processes (vec (sort actual-processes))
        :source-fixtures (count validated-sources)
        :cases (count validated-cases)}})))

(defn- transformed-bytes
  [case-data]
  (let [source (Files/readAllBytes
                (get-in case-data [:source-data :source-path]))
        {:keys [kind bytes]} (:transform case-data)]
    (case kind
      :copy source
      :truncate-half (Arrays/copyOf source (quot (alength source) 2))
      :prefix (Arrays/copyOf source (min bytes (alength source))))))

(defn stage-corpus!
  "Materializes checksum-verified corpus payloads into a clean proof directory."
  [validated output]
  (let [output (harness/clean-directory! (paths/absolute output))]
    (doseq [[staged-file cases]
            (sort-by key (group-by :staged-file (:cases validated)))]
      (let [payloads (mapv transformed-bytes cases)
            hashes (mapv sha256-bytes payloads)
            expected (mapv :payload-sha256 cases)]
        (when-not (and (apply = hashes)
                       (= expected hashes))
          (fail! "Preflight corpus derived payload checksum changed"
                 {:kind :preflight-corpus-payload-checksum-mismatch
                  :staged-file staged-file
                  :expected expected :actual hashes}))
        (Files/write (paths/resolve-path output staged-file)
                     (first payloads)
                     (make-array OpenOption 0))))
    output))

(defn- copy-staged-corpus!
  [validated source output]
  (let [output (harness/clean-directory! output)]
    (doseq [staged-file (->> (:cases validated)
                             (map :staged-file)
                             distinct
                             sort)]
      (Files/copy
       (paths/resolve-path source staged-file)
       (paths/resolve-path output staged-file)
       (into-array StandardCopyOption
                   [StandardCopyOption/REPLACE_EXISTING])))
    output))

(defn write-execution-manifest!
  [validated output]
  (write-text!
   (paths/absolute output)
   (str manifest-magic "\n"
        "columns\tcase-id\tstaged-file\tinput-sha256\tformat\texpected-outcome\n"
        (apply str
               (for [{:keys [id staged-file payload-sha256 format
                             expected-outcome]} (:cases validated)]
                 (str "case\t" id "\t" staged-file "\t" payload-sha256
                      "\t" (name format) "\t" (name expected-outcome) "\n"))))))

(defn render-results
  "Renders canonical normalized corpus rows."
  [rows]
  (str result-magic "\n"
       "columns\t" (str/join "\t" result-columns) "\n"
       (apply str
              (for [row rows]
                (str
                 "case\t"
                 (str/join
                  "\t"
                  [(:case-id row)
                   (:origin row)
                   (:format row)
                   (:expected-outcome row)
                   (:input-sha256 row)
                   (:status row)
                   (:valid row)
                   (:error-count row)
                   (b64 (:error-codes row))
                   (b64 (:warnings row))
                   (b64 (:pages row))
                   (b64 (:details row))
                   (:source-closed row)
                   (:document-closed row)
                   (b64 (:diagnostic row))])
                 "\n")))))

(defn- write-results!
  [output rows]
  (write-text! (paths/absolute output) (render-results rows)))

(defn read-results
  "Reads normalized Preflight corpus results and rejects malformed rows."
  [result-file]
  (let [result-file (paths/absolute result-file)
        content (Files/readString result-file StandardCharsets/UTF_8)
        lines (str/split-lines content)]
    (when-not (= result-magic (first lines))
      (fail! "Preflight corpus result has the wrong schema marker"
             {:kind :invalid-preflight-corpus-result-schema
              :path (str result-file) :actual (first lines)}))
    (let [columns (some-> (second lines) (str/split #"\t" -1))]
      (when-not (= (into ["columns"] result-columns) columns)
        (fail! "Preflight corpus result columns drifted"
               {:kind :preflight-corpus-result-columns-drift
                :expected result-columns :actual (vec (rest columns))}))
      {:path result-file
       :content content
       :rows
       (mapv
        (fn [index line]
          (let [fields (str/split line #"\t" -1)]
            (when-not (and (= "case" (first fields))
                           (= (inc (count result-columns)) (count fields)))
              (fail! "Malformed Preflight corpus result row"
                     {:kind :malformed-preflight-corpus-result
                      :line (+ index 3) :actual line}))
            (let [row (zipmap (map keyword result-columns) (rest fields))
                  context {:line (+ index 3) :case (:case-id row)}]
              (when-not (result-statuses (:status row))
                (fail! "Preflight corpus result has an unknown status"
                       (assoc context
                              :kind :malformed-preflight-corpus-result
                              :status (:status row))))
              (assoc row
                     :error-codes
                     (decode-base64! (:error-codes-base64 row)
                                     (assoc context :field :error-codes))
                     :warnings
                     (decode-base64! (:warnings-base64 row)
                                     (assoc context :field :warnings))
                     :pages
                     (decode-base64! (:pages-base64 row)
                                     (assoc context :field :pages))
                     :details
                     (decode-base64! (:details-base64 row)
                                     (assoc context :field :details))
                     :diagnostic
                     (decode-base64! (:diagnostic-base64 row)
                                     (assoc context :field :diagnostic))))))
        (range)
        (drop 2 lines))})))

(defn validate-results!
  "Requires exact ordered corpus provenance and fail-closed execution status."
  [validated origin result-file]
  (when-not (#{"upstream-java" "package-dotnet"} origin)
    (fail! "Unknown Preflight corpus execution origin"
           {:kind :invalid-preflight-corpus-origin :origin origin}))
  (let [{:keys [rows] :as parsed} (read-results result-file)
        cases (:cases validated)
        expected-ids (mapv :id cases)
        actual-ids (mapv :case-id rows)]
    (when-let [duplicates (seq (duplicate-values actual-ids))]
      (fail! "Preflight corpus results contain duplicate rows"
             {:kind :duplicate-preflight-corpus-results
              :cases duplicates}))
    (when-not (= expected-ids actual-ids)
      (fail! "Preflight corpus results do not cover the manifest in order"
             {:kind :preflight-corpus-result-coverage
              :expected expected-ids :actual actual-ids}))
    (doseq [[case-data row] (map vector cases rows)]
      (let [expected-provenance
            {:origin origin
             :format (name (:format case-data))
             :expected-outcome (name (:expected-outcome case-data))
             :input-sha256 (:payload-sha256 case-data)}
            actual-provenance
            (select-keys row (keys expected-provenance))]
        (when-not (= expected-provenance actual-provenance)
          (fail! "Preflight corpus result provenance drifted"
                 {:kind :stale-preflight-corpus-result-provenance
                  :case (:id case-data)
                  :expected expected-provenance
                  :actual actual-provenance})))
      (if (= "PASS" (:status row))
        (do
          (when-not (and (#{"true" "false"} (:valid row))
                         (re-matches #"\d+" (:error-count row))
                         (= "true" (:source-closed row))
                         (#{"true" "na"} (:document-closed row))
                         (str/blank? (:diagnostic row)))
            (fail! "Successful Preflight corpus result is malformed or leaked resources"
                   {:kind :malformed-preflight-corpus-result
                    :case (:id case-data) :row row}))
          (let [expected-valid (= :valid (:expected-outcome case-data))
                actual-valid (= "true" (:valid row))]
            (when-not (= expected-valid actual-valid)
              (fail! "Pinned Java corpus outcome contradicts its durable category"
                     {:kind :preflight-corpus-expected-outcome-mismatch
                      :case (:id case-data)
                      :expected (:expected-outcome case-data)
                      :actual-valid actual-valid}))))
        (when (str/blank? (:diagnostic row))
          (fail! "Preflight corpus execution failure omits diagnostics"
                 {:kind :missing-preflight-corpus-failure-diagnostic
                  :case (:id case-data) :status (:status row)}))))
    (assoc parsed :origin origin)))

(def ^:private observation-fields
  [:format :expected-outcome :input-sha256 :status :valid :error-count
   :error-codes :warnings :pages :details :source-closed :document-closed])

(defn compare-results
  "Compares package observations to normalized synchronized Java outcomes."
  [validated upstream-file package-file]
  (let [upstream (:rows (validate-results!
                         validated "upstream-java" upstream-file))
        package (:rows (validate-results!
                        validated "package-dotnet" package-file))
        comparisons
        (mapv
         (fn [expected actual]
           (let [expected-observation (select-keys expected observation-fields)
                 actual-observation (select-keys actual observation-fields)
                 kind
                 (cond
                   (not= "PASS" (:status expected)) :oracle-execution-failure
                   (not= "PASS" (:status actual)) :package-execution-failure
                   (not= expected-observation actual-observation)
                   :observation-mismatch
                   :else :matched)]
             {:case-id (:case-id expected)
              :kind kind
              :expected expected-observation
              :actual actual-observation}))
         upstream package)
        mismatches (filterv #(not= :matched (:kind %)) comparisons)]
    {:total (count comparisons)
     :matched (count (filter #(= :matched (:kind %)) comparisons))
     :mismatched (count mismatches)
     :comparisons comparisons
     :mismatches mismatches}))

(defn require-conformant!
  [comparison]
  (when-not (zero? (:mismatched comparison))
    (fail! "Packed DripSharp.PdfCarton.Preflight corpus differs from synchronized PDFBox"
           {:kind :pdfcube-preflight-corpus-mismatch
            :mismatched (:mismatched comparison)
            :mismatches (:mismatches comparison)}))
  comparison)

(defn compare-repeated-results
  [validated origin first-result second-result]
  (let [first (validate-results! validated origin first-result)
        second (validate-results! validated origin second-result)]
    {:observations (count (:rows first))
     :deterministic? (= (:content first) (:content second))
     :first (:path first)
     :second (:path second)}))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:kind (ex-data error)))))

(defn prove-fail-closed-controls!
  "Requires a deliberate comparator perturbation plus timeout, crash, leak,
  missing-row, and nondeterminism controls to fail the corpus gate."
  [validated upstream-file output-root]
  (let [output-root (paths/absolute output-root)
        upstream-rows (:rows (validate-results!
                              validated "upstream-java" upstream-file))
        package-rows (mapv #(assoc % :origin "package-dotnet")
                           upstream-rows)
        package-file (write-results!
                      (paths/resolve-path output-root "control-package.tsv")
                      package-rows)
        baseline (compare-results validated upstream-file package-file)
        _ (require-conformant! baseline)
        index 0
        compare-control
        (fn [name rows]
          (let [file (write-results!
                      (paths/resolve-path output-root name) rows)]
            (compare-results validated upstream-file file)))
        perturbed-rows (update package-rows index assoc
                               :details "deliberate-comparator-perturbation")
        perturbation (compare-control
                      "control-package-perturbed.tsv" perturbed-rows)
        failure-control
        (fn [status]
          (compare-control
           (str "control-package-" (str/lower-case status) ".tsv")
           (update package-rows index assoc
                   :status status
                   :valid ""
                   :error-count ""
                   :error-codes ""
                   :warnings ""
                   :pages ""
                   :details ""
                   :diagnostic (str "deliberate " (str/lower-case status)))))
        timeout (failure-control "TIMEOUT")
        crash (failure-control "CRASH")
        leak (failure-control "LEAK")
        missing-file
        (write-results!
         (paths/resolve-path output-root "control-package-missing.tsv")
         (subvec package-rows 1))
        nondeterministic-upstream
        (write-results!
         (paths/resolve-path output-root "control-upstream-nondeterministic.tsv")
         (update upstream-rows index assoc
                 :details "deliberate-nondeterminism"))
        controls
        {:perturbation
         (and (= 1 (:mismatched perturbation))
              (= :observation-mismatch
                 (:kind (first (:mismatches perturbation)))))
         :timeout
         (= :pdfcube-preflight-corpus-mismatch
            (thrown-kind #(require-conformant! timeout)))
         :crash
         (= :pdfcube-preflight-corpus-mismatch
            (thrown-kind #(require-conformant! crash)))
         :leak
         (= :pdfcube-preflight-corpus-mismatch
            (thrown-kind #(require-conformant! leak)))
         :missing
         (= :preflight-corpus-result-coverage
            (thrown-kind #(validate-results!
                           validated "package-dotnet" missing-file)))
         :nondeterminism
         (false?
          (:deterministic?
           (compare-repeated-results
            validated "upstream-java"
            upstream-file nondeterministic-upstream)))}]
    (when-not (every? true? (vals controls))
      (fail! "Preflight corpus fail-closed controls did not all trigger"
             {:kind :pdfcube-preflight-corpus-control-failed
              :controls controls}))
    controls))

(defn- configured-path
  [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- java-tools
  [^Path root generation]
  (let [toolchain (get-in generation [:project-input :java-toolchain])
        home (configured-path root (:home toolchain))
        suffix
        (if (str/starts-with?
             (str/lower-case (System/getProperty "os.name" ""))
             "windows")
          ".exe"
          "")]
    {:release (:release toolchain)
     :java (paths/resolve-path home "bin" (str "java" suffix))
     :javac (paths/resolve-path home "bin" (str "javac" suffix))}))

(defn- compile-oracle!
  [run-command! ^Path root generation ^Path proof-root]
  (let [{:keys [release java javac]} (java-tools root generation)
        project-input (:project-input generation)
        sources
        (mapv #(configured-path root %)
              (concat (:production-sources project-input)
                      (:generated-production-sources project-input)))
        dependencies
        (->> (:classpath-artifacts project-input)
             (map #(configured-path root (:path %)))
             distinct
             vec)
        resource-roots
        (mapv #(configured-path root %) (:resource-roots project-input))
        oracle-source
        (paths/resolve-path
         root "validation" "pdfcube-preflight-corpus"
         "PreflightCorpusOracle.java")
        classes
        (doto (paths/resolve-path proof-root "oracle-classes")
          (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str
                       (into [classes]
                             (concat dependencies resource-roots))))
        compile-command
        (cond-> [(str javac) "-J-Xmx28g" "--release" (str release)
                 "-encoding" "UTF-8"]
          (seq dependencies) (into ["-classpath" compile-classpath])
          true (into ["-d" (str classes)])
          true (into (map str sources))
          true (conj (str oracle-source)))]
    (doseq [tool [java javac]]
      (when-not (paths/regular-file? tool)
        (fail! "Pinned Java oracle toolchain is missing"
               {:kind :missing-preflight-corpus-java-tool
                :tool (str tool) :release release})))
    (when-not (paths/regular-file? oracle-source)
      (fail! "Pinned Preflight corpus oracle source is missing"
             {:kind :missing-preflight-corpus-input
              :path (str oracle-source)}))
    (run-command! {:command compile-command
                   :directory root
                   :timeout-ms default-process-timeout-ms
                   :environment {"DRIPSHARP_WORKERS" "22"}})
    {:java java :classpath run-classpath :classes classes}))

(defn- run-oracle!
  [run-command! ^Path root oracle execution-manifest corpus output]
  (run-command!
   {:command [(str (:java oracle)) "-Xmx28g"
              "-classpath" (:classpath oracle)
              "PreflightCorpusOracle"
              (str execution-manifest) (str corpus) (str output)]
    :directory root
    :timeout-ms default-process-timeout-ms
    :environment {"DRIPSHARP_WORKERS" "22"}}))

(def ^:private xml-escape util/xml-escape)

(defn- consumer-project
  [package-id version]
  (str
   "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
   "  <PropertyGroup>\n"
   "    <OutputType>Exe</OutputType>\n"
   "    <TargetFramework>net10.0</TargetFramework>\n"
   "    <Nullable>enable</Nullable>\n"
   "    <ImplicitUsings>disable</ImplicitUsings>\n"
   "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
   "  </PropertyGroup>\n"
   "  <ItemGroup>\n"
   "    <PackageReference Include=\"" (xml-escape package-id)
   "\" Version=\"" (xml-escape version) "\" />\n"
   "  </ItemGroup>\n"
   "</Project>\n"))

(defn- nuget-config
  [feed package-ids]
  (str
   "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
   "<configuration>\n"
   "  <packageSources>\n"
   "    <clear />\n"
   "    <add key=\"pdfcube-local\" value=\"" (xml-escape feed) "\" />\n"
   "  </packageSources>\n"
   "  <packageSourceMapping>\n"
   "    <packageSource key=\"pdfcube-local\">\n"
   (apply str
          (for [package-id (sort package-ids)]
            (str "      <package pattern=\"" (xml-escape package-id)
                 "\" />\n")))
   "    </packageSource>\n"
   "  </packageSourceMapping>\n"
   "</configuration>\n"))

(defn- verify-source-isolation!
  [^Path consumer-root ^Path project ^Path source]
  (let [root (.toRealPath consumer-root (make-array LinkOption 0))
        source-path (.toRealPath source (make-array LinkOption 0))
        project-text (Files/readString project StandardCharsets/UTF_8)
        source-text (Files/readString source StandardCharsets/UTF_8)
        forbidden
        (->> [#"<ProjectReference\b" #"<Reference\b" #"<Compile\b"
              #"(?i)target/generated" #"(?i)research/pdfbox"
              #"(?i)\.\./.*\.csproj" #"(?i)#line"]
             (filter #(or (re-find % project-text)
                          (re-find % source-text)))
             (mapv str))]
    (when-not (.startsWith source-path root)
      (fail! "Package Preflight corpus runner source escapes its consumer"
             {:kind :pdfcube-preflight-corpus-source-isolation
              :root (str root) :source (str source-path)}))
    (when (seq forbidden)
      (fail! "Package Preflight corpus runner crosses a source/project boundary"
             {:kind :pdfcube-preflight-corpus-source-isolation
              :project (str project) :source (str source)
              :forbidden forbidden}))
    {:project (str project) :source (str source) :forbidden []}))

(defn- validate-loaded-assemblies!
  [assembly-manifest loaded-file consumer-root]
  (let [expected
        (package-provenance/read-packed-assembly-manifest assembly-manifest)
        lines (str/split-lines
               (Files/readString loaded-file StandardCharsets/UTF_8))
        rows (mapv #(str/split % #"\t" -1) (rest lines))
        actual
        (mapv
         (fn [[name path expected-hash actual-hash]]
           {:name name :path path
            :expected-sha256 expected-hash :actual-sha256 actual-hash})
         rows)
        consumer-root (.toRealPath
                       (paths/absolute consumer-root)
                       (make-array LinkOption 0))]
    (when-not (= assembly-magic (first lines))
      (fail! "Loaded Preflight assembly evidence has the wrong marker"
             {:kind :invalid-preflight-loaded-assembly-schema
              :actual (first lines)}))
    (when-not (= (mapv #(select-keys % [:name :sha256]) expected)
                 (mapv (fn [row]
                         {:name (:name row)
                          :sha256 (:expected-sha256 row)})
                       actual))
      (fail! "Loaded Preflight assemblies differ from the packed closure"
             {:kind :preflight-loaded-assembly-provenance
              :expected expected :actual actual}))
    (doseq [{:keys [name path expected-sha256 actual-sha256]} actual]
      (let [real-path (.toRealPath (paths/absolute path)
                                   (make-array LinkOption 0))]
        (when-not (.startsWith real-path consumer-root)
          (fail! "Loaded Preflight assembly escaped the isolated consumer"
                 {:kind :preflight-loaded-assembly-path
                  :assembly name :actual (str real-path)}))
        (when-not (= expected-sha256 actual-sha256)
          (fail! "Loaded Preflight assembly hash differs from its package"
                 {:kind :preflight-loaded-assembly-hash
                  :assembly name
                  :expected expected-sha256 :actual actual-sha256}))))
    actual))

(defn- package-identities
  [package-proof]
  (packaging/published-dependency-closure!
   package-proof [(:identity package-proof)]))

(defn- inspect-corpus-consumer-dependencies!
  [project assets-file packages package-proof]
  (let [identity (:identity package-proof)
        identities (package-identities package-proof)
        framework-omitted-packages
        (get-in package-proof
                [:verification :generation :destination :package-consumer
                 :framework-omitted-packages])]
    (packaging/inspect-consumer-dependencies!
     project assets-file packages identity identities
     framework-omitted-packages)))

(def ^:private preflight-assembly-dependency-contract
  (mapv
   (fn [assembly-name]
     {:assembly-name assembly-name
      :package-id assembly-name
      :version (baseline/package-version :pdfcube assembly-name)
      :target-framework "netstandard2.0"})
   ["DripSharp.PdfCarton"
    "DripSharp.PdfCarton.Fonts"
    "DripSharp.PdfCarton.IO"
    "DripSharp.PdfCarton.Xmp"]))

(defn- preflight-assembly-dependencies
  [assembly-name expected]
  (if-not (= "DripSharp.PdfCarton.Preflight" assembly-name)
    expected
    (let [actual (vec expected)]
      (when-not (= preflight-assembly-dependency-contract actual)
        (fail!
         "Preflight packed assembly dependencies differ from the exact netstandard2.0 contract"
         {:kind :invalid-preflight-assembly-dependencies
          :expected preflight-assembly-dependency-contract
          :actual actual}))
      actual)))

(defn- inspect-preflight-package-assembly!
  [run-command! root artifact assembly-entry assembly-name verified-assembly
   package-assembly-names expected-dependency-assemblies expected-resources]
  (let [inspector
        (or (some-> (ns-resolve
                     'dripsharp.packaging
                     'inspect-package-assembly!)
                    var-get)
            (fail! "Shared packed-assembly inspector is unavailable"
                   {:kind :missing-preflight-package-inspector}))]
    (inspector
     run-command! root artifact assembly-entry assembly-name verified-assembly
     package-assembly-names
     (preflight-assembly-dependencies
      assembly-name expected-dependency-assemblies)
     expected-resources)))

(defn pack-verified-profile!
  "Runs the shared deterministic package gate with Preflight's complete
  translated assembly-reference closure."
  [options]
  (packaging/pack-verified-profile!
   (assoc options
          :inspect-resources-fn
          inspect-preflight-package-assembly!)))

(defn- directory-empty?
  [^Path directory]
  (with-open [entries (Files/list directory)]
    (zero? (.count entries))))

(defn- run-package-corpus!
  [run-command! ^Path root validated package-proof proof-root execution-manifest
   oracle-corpus case-timeout-ms process-timeout-ms]
  (let [identity (:identity package-proof)
        _ (when-not (= "DripSharp.PdfCarton.Preflight" (:id identity))
            (fail! "Corpus pack proof did not select DripSharp.PdfCarton.Preflight"
                   {:kind :wrong-preflight-corpus-package
                    :actual identity}))
        consumer-root
        (doto (paths/resolve-path proof-root "consumer")
          (Files/createDirectories (make-array FileAttribute 0)))
        project
        (paths/resolve-path
         consumer-root "DripSharp.PdfCarton.Preflight.CorpusRunner.csproj")
        source (paths/resolve-path consumer-root "Program.cs")
        runner-source
        (paths/resolve-path
         root
         "validation" "pdfcube-preflight-corpus"
         "PdfCartonPreflightCorpusRunner.cs")
        config (paths/resolve-path consumer-root "NuGet.Config")
        packages (doto (paths/resolve-path proof-root "nuget-packages")
                   (Files/createDirectories (make-array FileAttribute 0)))
        dotnet-home (doto (paths/resolve-path proof-root "dotnet-home")
                      (Files/createDirectories (make-array FileAttribute 0)))
        http-cache (doto (paths/resolve-path proof-root "nuget-http-cache")
                     (Files/createDirectories (make-array FileAttribute 0)))
        temp-root (doto (paths/resolve-path proof-root "dotnet-temp")
                    (Files/createDirectories (make-array FileAttribute 0)))
        package-corpus
        (copy-staged-corpus!
         validated oracle-corpus
         (paths/resolve-path consumer-root "corpus"))
        package-manifest
        (paths/resolve-path consumer-root "corpus-manifest.tsv")
        assembly-manifest
        (paths/resolve-path consumer-root "packed-assemblies.tsv")
        first-output (paths/resolve-path consumer-root "package-first.tsv")
        second-output (paths/resolve-path consumer-root "package-second.tsv")
        first-loaded
        (paths/resolve-path consumer-root "loaded-assemblies-first.tsv")
        second-loaded
        (paths/resolve-path consumer-root "loaded-assemblies-second.tsv")
        identities (package-identities package-proof)
        environment
        {"NUGET_PACKAGES" (str packages)
         "NUGET_HTTP_CACHE_PATH" (str http-cache)
         "DOTNET_CLI_HOME" (str dotnet-home)
         "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" "1"
         "DOTNET_CLI_TELEMETRY_OPTOUT" "1"
         "TMPDIR" (str temp-root)}
        run-package!
        (fn [output loaded]
          (run-command!
           {:command ["dotnet" "run" "--project" (str project)
                      "--configuration" "Release"
                      "--no-build" "--no-restore" "--"
                      (str package-manifest)
                      (str package-corpus)
                      (str output)
                      (str assembly-manifest)
                      (str packages)
                      (str loaded)
                      (str case-timeout-ms)]
            :directory consumer-root
            :timeout-ms process-timeout-ms
            :environment environment}))]
    (when-not (paths/regular-file? runner-source)
      (fail! "Package Preflight corpus runner source is missing"
             {:kind :missing-preflight-corpus-input
              :path (str runner-source)}))
    (when-not (every? directory-empty?
                      [packages dotnet-home http-cache])
      (fail! "Preflight corpus package caches were not fresh"
             {:kind :non-fresh-preflight-corpus-cache}))
    (write-text! project
                 (consumer-project (:id identity) (:version identity)))
    (write-text! config
                 (nuget-config (:feed package-proof)
                               (map :id identities)))
    (Files/copy runner-source source
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (Files/copy execution-manifest package-manifest
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (package-provenance/write-packed-assembly-manifest!
     assembly-manifest (:packages package-proof))
    (let [source-isolation
          (verify-source-isolation! consumer-root project source)]
      (run-command!
       {:command ["dotnet" "restore" (str project)
                  "--configfile" (str config)
                  "--packages" (str packages)
                  "--no-cache" "--force" "--force-evaluate"]
        :directory consumer-root
        :timeout-ms process-timeout-ms
        :environment environment})
      (let [dependency-proof
            (inspect-corpus-consumer-dependencies!
             project
             (paths/resolve-path consumer-root "obj" "project.assets.json")
             packages package-proof)]
        (run-command!
         {:command ["dotnet" "build" (str project)
                    "--configuration" "Release"
                    "--nologo" "--verbosity:minimal"
                    "--no-restore" "--no-incremental" "-warnaserror"]
          :directory consumer-root
          :timeout-ms process-timeout-ms
          :environment environment})
        (run-package! first-output first-loaded)
        (run-package! second-output second-loaded)
        {:consumer-root consumer-root
         :project project
         :source-isolation source-isolation
         :dependency-proof dependency-proof
         :packages-root packages
         :cache-roots [packages dotnet-home http-cache temp-root]
         :first-output first-output
         :second-output second-output
         :first-assemblies
         (validate-loaded-assemblies!
          assembly-manifest first-loaded consumer-root)
         :second-assemblies
         (validate-loaded-assemblies!
          assembly-manifest second-loaded consumer-root)}))))

(defn verify!
  "Packs DripSharp.PdfCarton.Preflight, executes the synchronized Java and isolated .NET
  corpus sides twice, and requires deterministic exact conformance."
  ([] (verify! {}))
  ([{:keys [workspace-root manifest run-command! pack-fn
            case-timeout-ms process-timeout-ms]
     :or {run-command! process/run!
          pack-fn pack-verified-profile!
          case-timeout-ms default-case-timeout-ms
          process-timeout-ms default-process-timeout-ms}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         manifest (or manifest
                      (paths/resolve-path
                       root "validation" "pdfcube-preflight-corpus"
                       "corpus.edn"))
         _ (when-not (and (pos-int? case-timeout-ms)
                          (pos-int? process-timeout-ms)
                          (> process-timeout-ms case-timeout-ms))
             (fail! "Preflight corpus timeout bounds are invalid"
                    {:kind :invalid-preflight-corpus-timeouts
                     :case-timeout-ms case-timeout-ms
                     :process-timeout-ms process-timeout-ms}))
         validated (validate-manifest! root manifest)
         package-proof
         (pack-fn {:workspace-root root
                   :profile "pdfcube-preflight"
                   :run-command! run-command!})
         proof-root
         (harness/clean-directory!
          (paths/resolve-path
           root "validation-output" "pdfcube-preflight-corpus"))
         oracle-corpus
         (stage-corpus!
          validated (paths/resolve-path proof-root "oracle-corpus"))
         execution-manifest
         (write-execution-manifest!
          validated (paths/resolve-path proof-root "corpus-manifest.tsv"))
         oracle
         (compile-oracle!
          run-command! root
          (get-in package-proof [:verification :generation])
          proof-root)
         upstream-first
         (paths/resolve-path proof-root "upstream-first.tsv")
         upstream-second
         (paths/resolve-path proof-root "upstream-second.tsv")]
     (run-oracle!
      run-command! root oracle execution-manifest oracle-corpus upstream-first)
     (run-oracle!
      run-command! root oracle execution-manifest oracle-corpus upstream-second)
     (let [upstream-determinism
           (compare-repeated-results
            validated "upstream-java" upstream-first upstream-second)
           _ (when-not (:deterministic? upstream-determinism)
               (fail! "Repeated synchronized Java corpus runs were not deterministic"
                      {:kind :nondeterministic-upstream-preflight-corpus
                       :first (str upstream-first)
                       :second (str upstream-second)}))
           package-run
           (run-package-corpus!
            run-command! root validated package-proof proof-root execution-manifest
            oracle-corpus case-timeout-ms process-timeout-ms)
           package-determinism
           (compare-repeated-results
            validated "package-dotnet"
            (:first-output package-run)
            (:second-output package-run))
           _ (when-not (:deterministic? package-determinism)
               (fail! "Repeated isolated-package corpus runs were not deterministic"
                      {:kind :nondeterministic-package-preflight-corpus
                       :first (str (:first-output package-run))
                       :second (str (:second-output package-run))}))
           _ (when-not (= (:first-assemblies package-run)
                          (:second-assemblies package-run))
               (fail! "Repeated loaded Preflight assembly evidence changed"
                      {:kind :nondeterministic-preflight-loaded-assemblies
                       :first (:first-assemblies package-run)
                       :second (:second-assemblies package-run)}))
           comparison
           (compare-results validated upstream-first
                            (:first-output package-run))
           _ (require-conformant! comparison)
           controls
           (prove-fail-closed-controls!
            validated upstream-first
            (paths/resolve-path proof-root "controls"))
           _ (doseq [cache (conj (:cache-roots package-run)
                                 (:classes oracle))]
               (harness/clean-directory! cache))
           summary
           {:source (:oracle validated)
            :coverage (:coverage validated)
            :cases (:total comparison)
            :matched (:matched comparison)
            :mismatched (:mismatched comparison)
            :upstream-deterministic-observations
            (:observations upstream-determinism)
            :package-deterministic-observations
            (:observations package-determinism)
            :package (:identity package-proof)
            :restored-packages
            (get-in package-run [:dependency-proof :packages])
            :temporary-caches-cleaned true
            :controls controls
            :redistribution (:redistribution validated)}]
       (write-text!
        (paths/resolve-path proof-root "summary.edn")
        (str (pr-str summary) "\n"))
       (println
        "Pinned PDF/A corpus/package differential passed:"
        (pr-str (select-keys summary
                             [:source :coverage :cases :matched
                              :package :controls])))
       {:summary summary
        :validated-manifest validated
        :package-proof package-proof
        :package-run package-run
        :upstream-first upstream-first
        :upstream-second upstream-second
        :package-first (:first-output package-run)
        :package-second (:second-output package-run)
        :proof-root proof-root}))))
