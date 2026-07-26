(ns dripsharp.pdfcube.pdfbox-printing-differential
  "Pinned PDFBox 3.0.8 versus package-only PdfCube.PdfBox printing proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.packaging :as packaging]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption]
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
  #{"book" "failure" "host-surface" "lifecycle" "orientation"
    "page-format" "pageable" "paper" "rasterization" "rendering" "scaling"})

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-pdfbox-printing-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- write-text! [^Path file value]
  (Files/createDirectories (.getParent file)
                           (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn trace-summary
  "Validates a normalized printing trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "Printing trace contains a malformed observation"
                      {:trace (str trace)
                       :line (inc index)
                       :value line}))
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
      (fail! "Printing trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Printing trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Printing trace misses required behavior families"
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
       "Package-only PdfCube.PdfBox printing behavior differs from pinned PDFBox 3.0.8"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Printing trace coverage differs between runtimes"
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
  [run-command! ^Path root generation ^Path proof-root ^Path output]
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
        (paths/resolve-path root "validation" "pdfcube-pdfbox-printing"
                            "PdfBoxPrintingOracle.java")
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
    (when-not (paths/regular-file? oracle-source)
      (fail! "Printing Java oracle source is missing"
             {:source (str oracle-source)}))
    (run-command! {:command compile-command
                   :directory root
                   :timeout-ms 600000})
    (run-command! {:command [(str java)
                             "-Djava.awt.headless=true"
                             "-classpath" run-classpath
                             "PdfBoxPrintingOracle"
                             (str output)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project
        (paths/resolve-path consumer-root
                            (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-pdfbox-printing"
                            "Program.cs")]
    (when-not (paths/regular-file? probe)
      (fail! "Printing package probe source is missing"
             {:source (str probe)}))
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
                             (str output)]
                   :directory consumer-root
                   :timeout-ms 300000})))

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

(defn verify!
  "Runs clean deterministic packaging, independent package consumption, and
  normalized Java/.NET printable and pageable differential validation."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (package-fn {:workspace-root root
                      :profile "pdfcube-pdfbox"
                      :run-command! run-command!})
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "validation-output"
                              "pdfcube-pdfbox-printing-differential"))
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root "package-dotnet.tsv")]
     (compile-and-run-oracle!
      run-command! root generation proof-root java-trace)
     (run-package-probe!
      run-command! root package-proof dotnet-trace)
     (let [comparison (assert-match! java-trace dotnet-trace)
           trace (trace-summary java-trace)
           summary
           {:profile "pdfcube-pdfbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :package
            {:id (get-in package-proof [:identity :id])
             :version (get-in package-proof [:identity :version])
             :sha256 (get-in package-proof [:identity :sha256])}
            :consumer (:dependency-proof package-proof)
            :trace trace
            :comparison comparison
            :host (current-host)
            :supported-hosts supported-hosts
            :proof-root proof-root}]
       (write-text!
        (paths/resolve-path proof-root "summary.edn")
        (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/package PdfCube.PdfBox printing differential passed:"
        (pr-str (select-keys summary [:source :package :trace :host])))
       summary))))
