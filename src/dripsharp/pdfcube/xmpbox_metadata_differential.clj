(ns dripsharp.pdfcube.xmpbox-metadata-differential
  "Pinned PDFBox 3.0.8 versus package-only PdfCube.XmpBox differential proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.util :as util])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def supported-hosts
  [{:os "windows" :architecture "x64" :runner "windows-2025"}
   {:os "windows" :architecture "arm64" :runner "windows-11-arm"}
   {:os "linux" :architecture "x64" :runner "ubuntu-24.04"}
   {:os "linux" :architecture "arm64" :runner "ubuntu-24.04-arm"}
   {:os "macos" :architecture "x64" :runner "macos-15-intel"}
   {:os "macos" :architecture "arm64" :runner "macos-15"}])

(def required-trace-families
  #{"namespace" "registry" "simple" "structured" "array" "lang-alt"
    "date" "invalid" "fixture" "parser" "parser-failure"
    "strict-lenient" "extension" "serialization" "round-trip"
    "security" "lifetime"})

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-xmpbox-metadata-differential-failed))))

(def ^:private write-text! util/write-text!)

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates one normalized XmpBox metadata trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "XmpBox metadata trace contains a malformed observation"
                      {:trace (str trace) :line (inc index) :value line}))
             (zipmap [:family :id :value] fields)))
         (range)
         lines)
        identities (mapv (juxt :family :id) rows)
        duplicates (->> identities
                        frequencies
                        (keep (fn [[identity count]]
                                (when (< 1 count) identity)))
                        sort
                        vec)
        families (set (map :family rows))
        missing (sort (set/difference required-trace-families families))]
    (when-not (seq rows)
      (fail! "XmpBox metadata trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "XmpBox metadata trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "XmpBox metadata trace misses required behavior families"
             {:trace (str trace)
              :missing missing
              :families (sort families)}))
    {:observations (count rows)
     :families (vec (sort families))
     :identities identities}))

(defn- assert-match! [subject expected actual]
  (let [expected-summary (trace-summary expected)
        actual-summary (trace-summary actual)
        comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! (str subject " differs from the pinned PDFBox 3.0.8 oracle")
             {:expected (str expected)
              :actual (str actual)
              :comparison comparison
              :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! (str subject " trace coverage differs from the oracle")
             {:expected expected-summary :actual actual-summary}))
    comparison))

(defn- prove-perturbation! [^Path oracle ^Path perturbed]
  (Files/copy oracle perturbed
              (into-array StandardCopyOption
                          [StandardCopyOption/REPLACE_EXISTING]))
  (Files/writeString perturbed
                     "failure\tperturbed-comparator\tvalue\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (differential/compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! "XmpBox differential comparator missed a deliberate perturbation"
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
  [run-command! ^Path root generation ^Path proof-root ^Path output
   ^Path resources]
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
        oracle-source
        (paths/resolve-path root "validation"
                            "pdfcube-xmpbox"
                            "XmpBoxMetadataUpstreamOracle.java")
        classes (doto (paths/resolve-path proof-root "oracle-classes")
                  (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str (into [classes] dependencies)))
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
                   :timeout-ms 300000})
    (run-command! {:command [(str java) "-classpath" run-classpath
                             "XmpBoxMetadataUpstreamOracle"
                             (str output) (str resources)]
                   :directory root
                   :timeout-ms 120000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path resources]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project
        (paths/resolve-path consumer-root (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation"
                            "pdfcube-xmpbox"
                            "PdfCube.XmpBox.MetadataProbe.cs")]
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
                             (str output) (str resources)]
                   :directory consumer-root
                   :timeout-ms 120000})))

(def ^:private current-host util/current-host)

(defn- validate-package-contract! [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        project-input (:project-input generation)
        destination (:destination generation)
        identity (:identity package-proof)
        inspection (:inspection package-proof)
        primary (first (filter :primary? (:packages package-proof)))
        resource-proof (:resource-proof primary)
        compiled-surface
        (get-in package-proof [:verification :public-surface])
        public-metadata (get-in generation [:emission :public-metadata])
        xmpbox-surface
        (first
         (filter #(= "PdfCube.XmpBox" (:assembly %))
                 (:assemblies compiled-surface)))
        public-stubs
        (count
         (filter #(= :public-stub
                     (get-in % [:generated :implementation]))
                 (:rows public-metadata)))
        expected
        {:project-id "org.apache.pdfbox:xmpbox:3.0.8"
         :revision pinned-revision
         :production-sources 74
         :generated-production-sources 0
         :clean-builds 2
         :package-id "PdfCube.XmpBox"
         :version "3.0.8-dripsharp.0"
         :target-framework "net10.0"
         :assembly
         {:name "PdfCube.XmpBox" :version "3.0.8.0"
          :dependency-assemblies []}
         :dependencies
         [{:id "Microsoft.Extensions.Logging.Abstractions"
           :version "10.0.0"}]
         :resource-count 0
         :package-files
         [{:kind :license :path "LICENSE.txt"
           :sha256
           "1301d8415a4868d82aeeec594849cf7679f1ead4636a9603dc46875f5713157e"}
          {:kind :notice :path "NOTICE.txt"
           :sha256
           "40741b4ab76d77ba4fbc5e8759277169fb0ce281859d273075de6fd3a3588458"}]
         :public-contract
         {:strategy :complete-accessible-java-library
          :required-rows 1199
          :compiled-contract-members 1199
          :public-stubs 0}}
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
         :assembly (:assembly-identity resource-proof)
         :dependencies (:dependencies inspection)
         :resource-count (:resources resource-proof)
         :package-files (:package-files inspection)
         :public-contract
         {:strategy (:strategy compiled-surface)
          :required-rows (:required-rows public-metadata)
          :compiled-contract-members (:contract-members xmpbox-surface)
          :public-stubs public-stubs}}]
    (when-not (= expected actual)
      (fail! "Packed PdfCube.XmpBox identity or target contract is incorrect"
             {:expected expected :actual actual}))
    actual))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, complete public
  surface and zero-public-stub gates, and the pinned Java/package XmpBox
  differential."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-xmpbox"
                      :run-command! run-command!})
         package-contract (validate-package-contract! package-proof)
         generation (get-in package-proof [:verification :generation])
         primary (first (filter :primary? (:packages package-proof)))
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-xmpbox-metadata-differential"))
         resources
         (paths/resolve-path root "research" "pdfbox" "xmpbox"
                             "src" "test" "resources")
         oracle (paths/resolve-path proof-root "upstream-java.tsv")
         packaged (paths/resolve-path proof-root "package-dotnet.tsv")
         perturbed (paths/resolve-path proof-root "perturbed.tsv")]
     (compile-and-run-oracle!
      run-command! root generation proof-root oracle resources)
     (run-package-probe!
      run-command! root package-proof packaged resources)
     (let [package-comparison
           (assert-match! "Package-only PdfCube.XmpBox behavior"
                          oracle packaged)
           perturbation (prove-perturbation! oracle perturbed)
           trace (trace-summary oracle)
           summary
           {:profile "pdfcube-xmpbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :package
            (merge package-contract
                   {:sha256 (get-in package-proof [:identity :sha256])
                    :assembly
                    (get-in primary [:resource-proof :assembly-identity])
                    :public-surface (:public-surface primary)
                    :resources (:resources primary)
                    :external-packages (:external-packages package-proof)})
            :consumer (:dependency-proof package-proof)
            :trace trace
            :package-comparison package-comparison
            :perturbation-line (get-in perturbation [:mismatch :line])
            :host (current-host)
            :supported-hosts supported-hosts
            :fixtures
            ["org/apache/xmpbox/parser/AltBagSeqTest.xml"
             "org/apache/xmpbox/parser/ThumbisartorStyle.xml"
             "org/apache/xmpbox/parser/structured_recursive.xml"
             "org/apache/xmpbox/parser/empty_list.xml"
             "org/apache/xmpbox/xml/PDFBOX-3882-dematbox.xml"
             "validxmp/attr_as_props.xml"
             "validxmp/only_space_fields.xmp"
             "validxmp/override_ns.rdf"
             "validxmp/PDFBOX-6099.xmp"
             "undefinedxmp/prism.xmp"
             "invalidxmp/*.xml"]}]
       (write-text! (paths/resolve-path proof-root "summary.edn")
                    (str (pr-str summary) "\n"))
       (println
        "Pinned Java/package PdfCube.XmpBox differential passed:"
        (pr-str (select-keys summary [:source :trace :host])))
       (assoc summary :proof-root proof-root)))))
