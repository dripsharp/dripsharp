(ns dripsharp.pdfcube.pdfbox-bidi-differential
  "Pinned java.text.Bidi and PDFBox extraction versus DripSharp.PdfCarton."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
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
  (baseline/upstream-revision :pdfcube))

(def required-trace-families
  #{"analysis" "direction" "extraction" "input" "mirrored" "reorder"})

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-pdfbox-bidi-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates a normalized bidi trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?)
                                    (take 2 fields)))
               (fail! "Bidi trace contains a malformed observation"
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
        missing (sort (set/difference required-trace-families families))
        failed-fixtures
        (->> rows
             (filter #(and (= "extraction" (:family %))
                           (not (str/starts-with? (:value %) "true|"))))
             (mapv :id))]
    (when-not (seq rows)
      (fail! "Bidi trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Bidi trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Bidi trace misses required behavior families"
             {:trace (str trace)
              :missing missing
              :families (sort families)}))
    (when (seq failed-fixtures)
      (fail! "Bidi extraction differs from the adapted upstream fixtures"
             {:trace (str trace) :fixtures failed-fixtures}))
    {:observations (count rows)
     :families (vec (sort families))
     :identities identities}))

(defn- assert-match! [expected actual]
  (let [expected-summary (trace-summary expected)
        actual-summary (trace-summary actual)
        comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail!
       "PdfCarton bidi behavior differs from the pinned Java/PDFBox oracle"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Bidi trace coverage differs between runtimes"
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

(defn- compile-and-run-direct-oracle!
  [run-command! ^Path root generation ^Path proof-root ^Path output
   ^Path mirroring]
  (let [{:keys [java javac]} (java-tools root generation)
        source
        (paths/resolve-path root "validation" "pdfcube-pdfbox-bidi"
                            "BidiOracle.java")
        classes
        (doto (paths/resolve-path proof-root "bidi-oracle-classes")
          (Files/createDirectories (make-array FileAttribute 0)))]
    (run-command! {:command [(str javac) "--release" "17"
                             "-encoding" "UTF-8"
                             "-d" (str classes) (str source)]
                   :directory root
                   :timeout-ms 300000})
    (run-command! {:command [(str java) "-classpath" (str classes)
                             "BidiOracle" (str output) (str mirroring)]
                   :directory root
                   :timeout-ms 300000})))

(defn- compile-and-run-extraction-oracle!
  [run-command! ^Path root generation ^Path proof-root ^Path output
   ^Path pdfbox-root]
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
        oracle
        (paths/resolve-path root "validation" "pdfcube-pdfbox-bidi"
                            "PdfBoxBidiExtractionOracle.java")
        classes
        (doto (paths/resolve-path proof-root "extraction-oracle-classes")
          (Files/createDirectories (make-array FileAttribute 0)))
        compile-classpath (str/join File/pathSeparator (map str dependencies))
        run-classpath
        (str/join File/pathSeparator
                  (map str
                       (into [classes]
                             (concat dependencies resource-roots))))
        command
        (cond-> [(str javac) "--release" (str release) "-encoding" "UTF-8"]
          (seq dependencies) (into ["-classpath" compile-classpath])
          true (into ["-d" (str classes)])
          true (into (map str sources))
          true (conj (str oracle)))]
    (run-command! {:command command
                   :directory root
                   :timeout-ms 600000})
    (run-command! {:command [(str java) "-classpath" run-classpath
                             "PdfBoxBidiExtractionOracle"
                             (str output) (str pdfbox-root)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-direct-dotnet-probe!
  [run-command! ^Path root ^Path output ^Path mirroring]
  (let [project
        (paths/resolve-path root "validation" "pdfcube-pdfbox-bidi"
                            "DripSharp.PdfCarton.BidiProbe.csproj")]
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--configuration" "Release"
                             "--verbosity:minimal" "--no-incremental"
                             "-warnaserror"]
                   :directory root
                   :timeout-ms 300000})
    (run-command! {:command ["dotnet" "run" "--project" (str project)
                             "--configuration" "Release"
                             "--no-build" "--no-restore" "--"
                             (str output) (str mirroring)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path pdfbox-root]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root
                                    (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-pdfbox-bidi"
                            "PackageProgram.cs")]
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
                             (str output) (str pdfbox-root)]
                   :directory consumer-root
                   :timeout-ms 300000})))

(defn- combine-traces! [^Path output traces]
  (Files/write
   output
   (mapcat #(Files/readAllLines ^Path % StandardCharsets/UTF_8) traces)
   StandardCharsets/UTF_8
   (make-array OpenOption 0)))

(defn verify!
  "Runs clean package consumption plus direct and extraction bidi differentials."
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
                              "pdfcube-pdfbox-bidi-differential"))
         pdfbox-root (paths/resolve-path root "research" "pdfbox")
         mirroring
         (paths/resolve-path
          pdfbox-root "pdfbox" "src" "main" "resources" "org" "apache"
          "pdfbox" "resources" "text" "BidiMirroring.txt")
         java-direct (paths/resolve-path proof-root "java-direct.tsv")
         java-extraction (paths/resolve-path proof-root "java-extraction.tsv")
         dotnet-direct (paths/resolve-path proof-root "dotnet-direct.tsv")
         dotnet-extraction
         (paths/resolve-path proof-root "dotnet-extraction.tsv")
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root "package-dotnet.tsv")]
     (when-not (paths/regular-file? mirroring)
       (fail! "Pinned PDFBox bidi mirroring resource is missing"
              {:resource (str mirroring)}))
     (compile-and-run-direct-oracle!
      run-command! root generation proof-root java-direct mirroring)
     (run-direct-dotnet-probe!
      run-command! root dotnet-direct mirroring)
     (compile-and-run-extraction-oracle!
      run-command! root generation proof-root java-extraction pdfbox-root)
     (run-package-probe!
      run-command! root package-proof dotnet-extraction pdfbox-root)
     (combine-traces! java-trace [java-direct java-extraction])
     (combine-traces! dotnet-trace [dotnet-direct dotnet-extraction])
     (let [comparison (assert-match! java-trace dotnet-trace)
           trace (trace-summary java-trace)
           summary
           {:profile "pdfcube-pdfbox"
            :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
            :package
            {:id (get-in package-proof [:identity :id])
             :version (get-in package-proof [:identity :version])
             :sha256 (get-in package-proof [:identity :sha256])}
            :consumer (:dependency-proof package-proof)
            :trace trace
            :comparison comparison
            :proof-root proof-root}]
       (spit (str (paths/resolve-path proof-root "summary.edn"))
             (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/package DripSharp.PdfCarton bidi differential passed:"
        (pr-str (select-keys summary [:source :package :trace])))
       summary))))
