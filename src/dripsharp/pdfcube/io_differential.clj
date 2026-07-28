(ns dripsharp.pdfcube.io-differential
  "Pinned reviewed PDFBox baseline versus package-only PdfCube.IO differential proof."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def ^:private io-contract
  (baseline/profile :pdfcube :io))

(def supported-hosts
  [{:os "windows" :architecture "x64" :runner "windows-2025"}
   {:os "windows" :architecture "arm64" :runner "windows-11-arm"}
   {:os "linux" :architecture "x64" :runner "ubuntu-24.04"}
   {:os "linux" :architecture "arm64" :runner "ubuntu-24.04-arm"}
   {:os "macos" :architecture "x64" :runner "macos-15-intel"}
   {:os "macos" :architecture "arm64" :runner "macos-15"}])

(def required-trace-families
  #{"buffer" "seek-rewind" "views" "eof" "file-backed" "memory-mapped"
    "scratch-storage" "memory-limits" "lifecycle" "failure"})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :pdfcube-io-differential-failed))))

(def ^:private write-text! util/write-text!)

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates one normalized PdfCube.IO trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "PdfCube.IO trace contains a malformed observation"
                      {:trace (str trace) :line (inc index) :value line}))
             (zipmap [:family :id :value] fields)))
         (range)
         lines)
        identities (mapv (juxt :family :id) rows)
        duplicates (->> identities frequencies
                        (keep (fn [[identity count]]
                                (when (< 1 count) identity)))
                        sort
                        vec)
        families (set (map :family rows))
        missing (sort (set/difference required-trace-families families))]
    (when-not (seq rows)
      (fail! "PdfCube.IO trace contains no observations" {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "PdfCube.IO trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "PdfCube.IO trace does not cover every required behavior family"
             {:trace (str trace) :missing missing :families (sort families)}))
    {:observations (count rows)
     :families (vec (sort families))
     :identities identities}))

(defn- assert-match! [subject expected actual]
  (let [expected-summary (trace-summary expected)
        actual-summary (trace-summary actual)
        comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str subject " differs from the pinned reviewed PDFBox baseline oracle")
             {:expected (str expected) :actual (str actual)
              :comparison comparison :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! (str subject " trace coverage differs from the oracle")
             {:expected expected-summary :actual actual-summary}))
    comparison))

(defn- prove-perturbation! [^Path oracle ^Path perturbed]
  (Files/copy oracle perturbed
              (into-array StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  (Files/writeString perturbed "failure\tperturbed-comparator\tvalue\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (differential/compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! "PdfCube.IO differential comparator missed a deliberate perturbation"
             {:oracle (str oracle) :perturbed (str perturbed)}))
    comparison))

(defn- java-tools [^Path root generation]
  (let [toolchain (get-in generation [:project-input :java-toolchain])
        home (configured-path root (:home toolchain))
        suffix (if (str/starts-with?
                    (str/lower-case (System/getProperty "os.name" ""))
                    "windows")
                 ".exe"
                 "")]
    {:release (:release toolchain)
     :java (paths/resolve-path home "bin" (str "java" suffix))
     :javac (paths/resolve-path home "bin" (str "javac" suffix))}))

(defn- compile-and-run-oracle!
  [run-command! ^Path root generation ^Path proof-root ^Path output]
  (let [{:keys [release java javac]} (java-tools root generation)
        project-input (:project-input generation)
        sources (mapv #(configured-path root %)
                      (concat (:production-sources project-input)
                              (:generated-production-sources project-input)))
        dependencies
        (->> (:classpath-artifacts project-input)
             (map #(configured-path root (:path %)))
             distinct
             vec)
        oracle-source (paths/resolve-path root "validation"
                                          "pdfcube-io"
                                          "PdfCubeIoUpstreamOracle.java")
        classes (doto (paths/resolve-path proof-root "oracle-classes")
                  (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator (map str (into [classes] dependencies)))
        compile-command
        (cond-> [(str javac) "--release" (str release)
                 "-encoding" "UTF-8"]
          (seq dependencies) (into ["-classpath" compile-classpath])
          true (into ["-d" (str classes)])
          true (into (map str sources))
          true (conj (str oracle-source)))]
    (doseq [tool [java javac]]
      (when-not (paths/regular-file? tool)
        (fail! "Pinned Java oracle toolchain is missing"
               {:tool (str tool) :release release})))
    (run-command! {:command compile-command
                   :directory root
                   :timeout-ms 300000})
    (run-command! {:command [(str java) "-classpath" run-classpath
                             "PdfCubeIoUpstreamOracle" (str output)]
                   :directory root
                   :timeout-ms 120000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path canonical]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe (paths/resolve-path root "validation" "pdfcube-io"
                                  "PdfCube.IO.PackageProbe.cs")]
    (Files/copy probe source
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--verbosity:minimal"
                             "--no-restore" "--no-incremental" "-warnaserror"]
                   :directory consumer-root
                   :timeout-ms 300000})
    (run-command! {:command ["dotnet" "run" "--project" (str project)
                             "--no-build" "--no-restore" "--"
                             (str output) (str canonical)]
                   :directory consumer-root
                   :timeout-ms 120000})))

(def ^:private current-host util/current-host)

(defn- validate-package-contract! [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        destination (:destination generation)
        identity (:identity package-proof)
        inspection (:inspection package-proof)
        resource-proof (get-in package-proof [:packages 0 :resource-proof])
        expected
        {:project-id (:source-project-id io-contract)
         :revision pinned-revision
         :package-id "PdfCube.IO"
         :version (baseline/package-version :pdfcube "PdfCube.IO")
         :target-framework "net10.0"
         :assembly-name "PdfCube.IO"
         :dependencies [{:id "Microsoft.Extensions.Logging.Abstractions"
                         :version "10.0.0"}]
         :package-files (baseline/package-legal-files :pdfcube [:upstream])}
        actual
        {:project-id (get-in generation [:project-input :project-id])
         :revision (get-in generation [:source-project :revision])
         :package-id (:id identity)
         :version (:version identity)
         :target-framework (get-in destination [:project :target-framework])
         :assembly-name (get-in resource-proof [:assembly-identity :name])
         :dependencies (:dependencies inspection)
         :package-files (:package-files inspection)}]
    (when-not (= expected actual)
      (fail! "Packed PdfCube.IO identity or target contract is incorrect"
             {:expected expected :actual actual}))
    actual))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, and the complete
  pinned Java/package differential for PdfCube.IO."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root :profile "pdfcube-io"
                      :run-command! run-command!})
         package-contract (validate-package-contract! package-proof)
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-io-differential"))
         canonical (paths/resolve-path root "validation"
                                       "pdfcube-io" "CanonicalTrace.tsv")
         oracle (paths/resolve-path proof-root "upstream-java.tsv")
         packaged (paths/resolve-path proof-root "package-dotnet.tsv")
         perturbed (paths/resolve-path proof-root "perturbed.tsv")]
     (when-not (paths/regular-file? canonical)
       (fail! "Pinned PdfCube.IO canonical trace is missing"
              {:canonical (str canonical)}))
     (compile-and-run-oracle! run-command! root generation proof-root oracle)
     (let [canonical-comparison
           (assert-match! "Live upstream Java behavior" canonical oracle)
           _ (run-package-probe!
              run-command! root package-proof packaged canonical)
           package-comparison
           (assert-match! "Package-only PdfCube.IO behavior" oracle packaged)
           perturbation (prove-perturbation! oracle perturbed)
           trace (trace-summary oracle)
           summary
           {:profile "pdfcube-io"
            :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
            :package (merge package-contract
                            {:sha256 (get-in package-proof [:identity :sha256])
                             :assembly
                             (get-in package-proof
                                     [:packages 0 :resource-proof
                                      :assembly-identity])
                             :public-surface
                             (get-in package-proof
                                     [:packages 0 :public-surface])
                             :resources
                             (get-in package-proof [:packages 0 :resources])
                             :external-packages
                             (:external-packages package-proof)})
            :consumer (:dependency-proof package-proof)
            :trace trace
            :canonical-comparison canonical-comparison
            :package-comparison package-comparison
            :perturbation-line (get-in perturbation [:mismatch :line])
            :host (current-host)
            :supported-hosts supported-hosts}]
       (write-text! (paths/resolve-path proof-root "summary.edn")
                    (str (pr-str summary) "\n"))
       (println "Pinned Java/package PdfCube.IO differential passed:"
                (pr-str (select-keys summary [:source :trace :host])))
       (assoc summary :proof-root proof-root)))))
