(ns dripsharp.pdfcube.fontbox-differential
  "Pinned PDFBox 3.0.8 versus package-only PdfCube.FontBox behavioral proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.io File]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

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
  #{"encoding" "cff" "afm" "cmap" "pfb" "type1" "truetype" "opentype"
    "tables" "gsub" "collection" "subsetting" "discovery" "lifecycle"
    "failure"})

(def font-fixtures
  [{:file "SourceSansProBold.otf"
    :url "https://issues.apache.org/jira/secure/attachment/12684264/SourceSansProBold.otf"
    :sha512
    "28a044a2685fbc8da7810d9ac7b6b93a95542d504d7d8e671f009b8ebb2f5b70c974be7ea78974b188d8e6ab17d65b08f276c054927857315d5aad26f6fe36fc"}
   {:file "OpenSans-Regular.pfb"
    :url "https://mirror.math.princeton.edu/pub/CTAN/fonts/opensans/type1/OpenSans-Regular.pfb"
    :sha512
    "2787fcecc0feb1c9e6ff0d8de6193658413863e44eaab572751ca7e6c3b369c0a9731f4952cb0821f307760f0422f77c5f0d3fe7df6b054643fb39423e8d70ee"}
   {:file "DejaVuSerifCondensed.pfb"
    :url "https://issues.apache.org/jira/secure/attachment/13064282/DejaVuSerifCondensed.pfb"
    :sha512
    "6ef13c3497862dc8e4c2a4261bc3a7ef3e2dd75e00ae2af4912b236b387225541db76c72854fbb2323d1064311ffdda9e64ed7065afc3a7d13f5b71b7df2f2ef"}])

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-fontbox-differential-failed))))

(defn- write-text! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- sha512 [^Path file]
  (let [digest (MessageDigest/getInstance "SHA-512")
        buffer (byte-array 65536)]
    (with-open [input (Files/newInputStream file (make-array OpenOption 0))]
      (loop []
        (let [count (.read input buffer)]
          (when (pos? count)
            (.update digest buffer 0 count)
            (recur)))))
    (apply str
           (map #(format "%02x" (bit-and (int %) 0xff))
                (.digest digest)))))

(defn- ensure-font-fixtures! [^Path root]
  (let [directory
        (doto (paths/resolve-path root "research" "pdfbox" "fontbox"
                                  "target" "fonts")
          (Files/createDirectories (make-array FileAttribute 0)))]
    (doseq [{:keys [file url] expected :sha512} font-fixtures]
      (let [destination (paths/resolve-path directory file)]
        (if (paths/regular-file? destination)
          (when-not (= expected (sha512 destination))
            (fail! "Existing authoritative FontBox fixture has the wrong checksum"
                   {:file (str destination)
                    :expected expected
                    :actual (sha512 destination)}))
          (let [temporary
                (Files/createTempFile directory (str file ".") ".download"
                                      (make-array FileAttribute 0))]
            (try
              (with-open [input (.openStream (.toURL (URI/create url)))]
                (Files/copy input temporary
                            (into-array StandardCopyOption
                                        [StandardCopyOption/REPLACE_EXISTING])))
              (let [actual (sha512 temporary)]
                (when-not (= expected actual)
                  (fail! "Downloaded authoritative FontBox fixture has the wrong checksum"
                         {:file file :url url :expected expected :actual actual})))
              (Files/move temporary destination
                          (into-array StandardCopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))
              (finally
                (Files/deleteIfExists temporary)))))))
    directory))

(defn trace-summary
  "Validates one normalized FontBox trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "FontBox trace contains a malformed observation"
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
      (fail! "FontBox trace contains no observations" {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "FontBox trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "FontBox trace does not cover every required behavior family"
             {:trace (str trace) :missing missing :families (sort families)}))
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
  (Files/writeString perturbed "failure\tperturbed-comparator\tvalue\n"
                     (into-array OpenOption [StandardOpenOption/APPEND]))
  (let [comparison (differential/compare-results oracle perturbed)]
    (when-not (:mismatch comparison)
      (fail! "FontBox differential comparator missed a deliberate perturbation"
             {:oracle (str oracle) :perturbed (str perturbed)}))
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
   ^Path resources ^Path fonts]
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
        resource-roots
        (mapv #(configured-path root %) (:resource-roots project-input))
        oracle-source
        (paths/resolve-path root "validation"
                            "pdfcube-fontbox" "FontBoxUpstreamOracle.java")
        classes (doto (paths/resolve-path proof-root "oracle-classes")
                  (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str (into [classes] (concat dependencies resource-roots))))
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
                             "FontBoxUpstreamOracle"
                             (str output) (str resources) (str fonts)]
                   :directory root
                   :timeout-ms 120000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path resources
   ^Path fonts ^Path canonical]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project
        (paths/resolve-path consumer-root (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation"
                            "pdfcube-fontbox"
                            "PdfCube.FontBox.PackageProbe.cs")]
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
                             (str output) (str resources) (str fonts)
                             (str canonical)]
                   :directory consumer-root
                   :timeout-ms 120000})))

(defn- current-host []
  (let [os-name (str/lower-case (System/getProperty "os.name" ""))
        architecture (str/lower-case (System/getProperty "os.arch" ""))
        os (cond
             (str/includes? os-name "win") "windows"
             (str/includes? os-name "mac") "macos"
             (str/includes? os-name "linux") "linux"
             :else os-name)
        architecture (case architecture
                       "amd64" "x64"
                       "x86_64" "x64"
                       "aarch64" "arm64"
                       "arm64" "arm64"
                       architecture)]
    {:os os :architecture architecture}))

(defn- validate-package-contract! [package-proof]
  (let [generation (get-in package-proof [:verification :generation])
        destination (:destination generation)
        identity (:identity package-proof)
        inspection (:inspection package-proof)
        primary (first (filter :primary? (:packages package-proof)))
        resource-proof (:resource-proof primary)
        compiled-surface (get-in package-proof [:verification :public-surface])
        compiled-fontbox
        (first (filter #(= "PdfCube.FontBox" (:assembly %))
                       (:assemblies compiled-surface)))
        public-metadata (get-in generation [:emission :public-metadata])
        expected
        {:project-id "org.apache.pdfbox:fontbox:3.0.8"
         :revision pinned-revision
         :package-id "PdfCube.FontBox"
         :version "3.0.8-dripsharp.0"
         :target-framework "net10.0"
         :assembly
         {:name "PdfCube.FontBox" :version "3.0.8.0"
          :dependency-assemblies ["PdfCube.IO"]}
         :dependencies
         [{:id "PdfCube.IO" :version "3.0.8-dripsharp.0"}
          {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
          {:id "SkiaSharp" :version "4.150.1"}
          {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}]
         :resource-count 93
         :package-files
         [{:kind :license :path "LICENSE.txt"
           :sha256 "1301d8415a4868d82aeeec594849cf7679f1ead4636a9603dc46875f5713157e"}
          {:kind :notice :path "NOTICE.txt"
           :sha256 "40741b4ab76d77ba4fbc5e8759277169fb0ce281859d273075de6fd3a3588458"}]
         :public-contract
         {:strategy :complete-accessible-java-library
          :required-rows 1440
          :compiled-contract-members 1440}}
        actual
        {:project-id (get-in generation [:project-input :project-id])
         :revision (get-in generation [:source-project :revision])
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
          :compiled-contract-members (:contract-members compiled-fontbox)}}]
    (when-not (= expected actual)
      (fail! "Packed PdfCube.FontBox identity or target contract is incorrect"
             {:expected expected :actual actual}))
    actual))

(defn verify!
  "Runs clean deterministic packing, isolated consumption, and the pinned
  Java/package FontBox differential."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-fontbox"
                      :run-command! run-command!})
         package-contract (validate-package-contract! package-proof)
         generation (get-in package-proof [:verification :generation])
         primary (first (filter :primary? (:packages package-proof)))
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-fontbox-differential"))
         canonical
         (paths/resolve-path root "validation"
                             "pdfcube-fontbox" "canonical-trace.tsv")
         resources
         (paths/resolve-path root "research" "pdfbox" "fontbox"
                             "src" "test" "resources")
         fonts (ensure-font-fixtures! root)
         oracle (paths/resolve-path proof-root "upstream-java.tsv")
         packaged (paths/resolve-path proof-root "package-dotnet.tsv")
         perturbed (paths/resolve-path proof-root "perturbed.tsv")]
     (when-not (paths/regular-file? canonical)
       (fail! "Pinned FontBox canonical trace is missing"
              {:canonical (str canonical)}))
     (compile-and-run-oracle!
      run-command! root generation proof-root oracle resources fonts)
     (let [canonical-comparison
           (assert-match! "Live upstream Java behavior" canonical oracle)
           _ (run-package-probe!
              run-command! root package-proof packaged resources fonts canonical)
           package-comparison
           (assert-match! "Package-only PdfCube.FontBox behavior"
                          oracle packaged)
           perturbation (prove-perturbation! oracle perturbed)
           trace (trace-summary oracle)
           summary
           {:profile "pdfcube-fontbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :package
            (merge package-contract
                   {:sha256 (get-in package-proof [:identity :sha256])
                    :assembly (get-in primary
                                      [:resource-proof :assembly-identity])
                    :public-surface (:public-surface primary)
                    :resources (:resources primary)
                    :external-packages (:external-packages package-proof)})
            :consumer (:dependency-proof package-proof)
            :trace trace
            :canonical-comparison canonical-comparison
            :package-comparison package-comparison
            :perturbation-line (get-in perturbation [:mismatch :line])
            :host (current-host)
            :supported-hosts supported-hosts
            :fixtures
            (mapv #(select-keys % [:file :sha512]) font-fixtures)}]
       (write-text! (paths/resolve-path proof-root "summary.edn")
                    (str (pr-str summary) "\n"))
       (println "Pinned Java/package PdfCube.FontBox differential passed:"
                (pr-str (select-keys summary [:source :trace :host])))
       (assoc summary :proof-root proof-root)))))
