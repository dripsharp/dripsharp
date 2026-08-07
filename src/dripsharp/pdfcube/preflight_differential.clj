(ns dripsharp.pdfcube.preflight-differential
  "Pinned reviewed PDFBox baseline versus package-only DripSharp.PdfCarton.Preflight proof."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.preflight-corpus :as preflight-corpus]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def ^:private preflight-contract
  (baseline/profile :pdfcube :preflight))

(def supported-hosts
  [{:os "windows" :architecture "x64" :runner "windows-2025"}
   {:os "windows" :architecture "arm64" :runner "windows-11-arm"}
   {:os "linux" :architecture "x64" :runner "ubuntu-24.04"}
   {:os "linux" :architecture "arm64" :runner "ubuntu-24.04-arm"}
   {:os "macos" :architecture "x64" :runner "macos-15-intel"}
   {:os "macos" :architecture "arm64" :runner "macos-15"}])

(def expected-restored-closure
  #{{:id "DripSharp.PdfCarton.IO"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")}
    {:id "DripSharp.PdfCarton.Fonts"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Fonts")}
    {:id "DripSharp.PdfCarton.Xmp"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Xmp")}
    {:id "DripSharp.PdfCarton"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton")}
    {:id "DripSharp.PdfCarton.Preflight"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Preflight")}
    {:id "Microsoft.Extensions.DependencyInjection.Abstractions"
     :version "10.0.0"}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.macOS" :version "4.150.1"}
    {:id "SkiaSharp.NativeAssets.Win32" :version "4.150.1"}
    {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}})

(def expected-package-contract
  {:project-id (:source-project-id preflight-contract)
   :revision pinned-revision
   :production-sources (get-in preflight-contract [:source-counts :ordinary])
   :generated-production-sources
   (get-in preflight-contract [:source-counts :generated])
   :clean-builds 2
   :package-id "DripSharp.PdfCarton.Preflight"
   :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Preflight")
   :target-framework "net10.0"
   :assembly
   {:name "DripSharp.PdfCarton.Preflight"
    :version (baseline/assembly-version :pdfcube "DripSharp.PdfCarton.Preflight")
    :dependency-assemblies
    ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Xmp"]}
   :dependencies
   [{:id "DripSharp.PdfCarton.Xmp"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton.Xmp")}
    {:id "DripSharp.PdfCarton"
     :version (baseline/package-version :pdfcube "DripSharp.PdfCarton")}
    {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
    {:id "SkiaSharp" :version "4.150.1"}]
   :resource-count 0
   :package-files (baseline/package-legal-files :pdfcube [:upstream])
   :public-contract
   {:strategy :complete-accessible-java-library
    :required-rows (:public-contract-rows preflight-contract)
    :compiled-contract-members (:public-contract-rows preflight-contract)
    :public-stubs 0}})

(def required-execution-families
  #{"configuration" "context" "document" "error" "error-code" "exception"
    "format" "lifecycle" "parser-encrypted" "parser-invalid"
    "parser-malformed" "parser-truncated" "parser-unsupported" "parser-valid"
    "path" "process" "result" "xml"})

(def required-validation-families
  #{"action" "annotation" "catalog" "color" "content-stream"
    "cross-reference" "embedded-file" "file-structure" "font" "form"
    "graphics-state" "icc" "image-xobject" "logical-structure" "metadata"
    "output-intent" "page" "rule-selection" "trailer" "transparency" "xmp"})

(def required-trace-families
  (set/union required-execution-families required-validation-families))

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-preflight-differential-failed))))

(defn- verify-package-consumption!
  [options]
  (packaging/verify-package-consumption!
   (assoc options
          :pack-fn preflight-corpus/pack-verified-profile!)))

(def ^:private current-host util/current-host)

(defn validate-package-contract!
  "Requires the exact primary package identity, translated/runtime dependency
  graph, target framework, legal payload, resources, complete compiled
  Preflight contract, zero public stubs, and isolated restore closure."
  [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        project-input (:project-input generation)
        destination (:destination generation)
        identity (:identity package-proof)
        inspection (:inspection package-proof)
        primary (first (filter :primary? (:packages package-proof)))
        compiled-surface
        (get-in package-proof [:verification :public-surface])
        public-metadata (get-in generation [:emission :public-metadata])
        preflight-surface
        (first
         (filter #(= "DripSharp.PdfCarton.Preflight" (:assembly %))
                 (:assemblies compiled-surface)))
        public-stubs
        (count
         (filter #(= :public-stub
                     (get-in % [:generated :implementation]))
                 (:rows public-metadata)))
        restored
        (->> (get-in package-proof [:dependency-proof :packages])
             (map #(select-keys % [:id :version]))
             set)
        expected
        (assoc expected-package-contract
               :restored-closure expected-restored-closure)
        actual
        {:project-id (:project-id project-input)
         :revision (get-in generation [:source-project :revision])
         :production-sources (count (:production-sources project-input))
         :generated-production-sources
         (count (:generated-production-sources project-input))
         :clean-builds (get-in package-proof [:packing-summary :clean-builds])
         :package-id (:id identity)
         :version (:version identity)
         :target-framework (get-in destination [:project :target-framework])
         :assembly (get-in primary [:resource-proof :assembly-identity])
         :dependencies (:dependencies inspection)
         :resource-count (count (:resources primary))
         :package-files
         (differential/legal-package-files (:package-files inspection))
         :public-contract
         {:strategy (:strategy compiled-surface)
          :required-rows (:required-rows public-metadata)
          :compiled-contract-members (:contract-members preflight-surface)
          :public-stubs public-stubs}
         :restored-closure restored}]
    (when-not (= expected actual)
      (fail! "Packed DripSharp.PdfCarton.Preflight violates its exact target contract"
             {:expected expected :actual actual}))
    actual))

(defn prove-mismatch-detection!
  "Copies an oracle trace, deliberately changes it, and requires the shared
  comparator to report the mismatch."
  [oracle perturbed]
  (let [oracle (paths/path oracle)
        perturbed (paths/path perturbed)]
    (when-not (paths/regular-file? oracle)
      (fail! "Preflight mismatch control is missing its oracle trace"
             {:oracle (str oracle)}))
    (Files/createDirectories (.getParent perturbed)
                             (make-array FileAttribute 0))
    (Files/copy oracle perturbed
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (Files/writeString
     perturbed
     "failure\tdeliberate-package-mismatch\tchanged\n"
     (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [comparison (differential/compare-results oracle perturbed)]
      (when-not (:mismatch comparison)
        (fail! "Preflight comparator missed a deliberate package mismatch"
               {:oracle (str oracle) :perturbed (str perturbed)}))
      comparison)))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates a normalized Preflight trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "Preflight trace contains a malformed observation"
                      {:trace (str trace) :line (inc index) :value line}))
             (zipmap [:family :id :value] fields)))
         (range)
         lines)
        identities (mapv (juxt :family :id) rows)
        duplicates
        (->> identities
             frequencies
             (keep (fn [[identity count]]
                     (when (< 1 count) identity)))
             sort
             vec)
        families (set (map :family rows))
        missing (sort (set/difference required-trace-families families))]
    (when-not (seq rows)
      (fail! "Preflight trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Preflight trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Preflight trace misses required execution-model families"
             {:trace (str trace)
              :missing missing
              :families (sort families)}))
    {:observations (count rows)
     :families (vec (sort families))
     :identities identities}))

(defn- assert-match! [expected actual]
  (let [expected-summary (trace-summary expected)
        actual-summary (trace-summary actual)
        comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail!
       "Packed DripSharp.PdfCarton.Preflight execution behavior differs from pinned reviewed PDFBox baseline"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Preflight trace coverage differs between runtimes"
             {:expected expected-summary :actual actual-summary}))
    comparison))

(defn- java-tools [^Path root generation]
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

(defn- compile-and-run-oracle!
  [run-command! ^Path root generation ^Path proof-root ^Path output
   ^Path fixtures]
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
         root "validation" "pdfcube-preflight" "PreflightExecutionOracle.java")
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
        (cond-> [(str javac) "--release" (str release) "-encoding" "UTF-8"]
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
                   :timeout-ms 600000})
    (run-command! {:command [(str java) "-classpath" run-classpath
                             "PreflightExecutionOracle"
                             (str output) (str fixtures) (str root)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path fixtures]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project
        (paths/resolve-path consumer-root (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-preflight" "Program.cs")]
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
                             (str output) (str fixtures) (str root)]
                   :directory consumer-root
                   :timeout-ms 300000})))

(defn verify!
  "Runs deterministic dependency-closed packing, an isolated focused consumer,
  complete surface and zero-stub gates, pinned Java/package execution-model
  comparison, and the representative PDF/A corpus proof."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn corpus-fn run-command!]
     :or {package-fn verify-package-consumption!
          corpus-fn preflight-corpus/verify!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-preflight"
                      :run-command! run-command!})
         package-contract (validate-package-contract! package-proof)
         verification (:verification package-proof)
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path
           root "validation-output"
           "pdfcube-preflight-execution-differential"))
         fixtures
         (doto (paths/resolve-path proof-root "fixtures")
           (Files/createDirectories (make-array FileAttribute 0)))
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root "package-dotnet.tsv")]
     (compile-and-run-oracle!
      run-command! root generation proof-root java-trace fixtures)
     (run-package-probe!
      run-command! root package-proof dotnet-trace fixtures)
     (let [comparison (assert-match! java-trace dotnet-trace)
           trace (trace-summary java-trace)
           perturbation
           (prove-mismatch-detection!
            java-trace
            (paths/resolve-path proof-root "deliberate-mismatch.tsv"))
           _ (harness/clean-directory! (:packages-root package-proof))
           corpus-proof
           (corpus-fn
            {:workspace-root root
             :run-command! run-command!
             :pack-fn (fn [_] package-proof)})
           summary
           {:profile "pdfcube-preflight"
            :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
            :package
            (merge
             (select-keys (:identity package-proof)
                          [:id :version :sha256])
             {:contract package-contract})
            :consumer (:dependency-proof package-proof)
            :generation
            (select-keys
             (:summary (:emission generation))
             [:compilation-units :declarations :generated-files
              :missing-source-mappings :hard-failures :collisions])
            :public-surface (:public-surface verification)
            :trace trace
            :comparison comparison
            :deliberate-mismatch
            {:line (get-in perturbation [:mismatch :line])
             :expected (get-in perturbation [:mismatch :expected])
             :actual (get-in perturbation [:mismatch :actual])}
            :corpus (:summary corpus-proof)
            :host (current-host)
            :supported-hosts supported-hosts
            :proof-root proof-root}]
       (spit (str (paths/resolve-path proof-root "summary.edn"))
             (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/package DripSharp.PdfCarton.Preflight differential passed:"
        (pr-str
         {:source (:source summary)
          :package (select-keys (:package summary) [:id :version :sha256])
          :trace (:trace summary)
          :corpus (select-keys (:corpus summary)
                               [:cases :matched :mismatched])
          :host (:host summary)}))
       summary))))
