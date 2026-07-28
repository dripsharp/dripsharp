(ns dripsharp.pdfcube.pdfbox-font-text-differential
  "Pinned reviewed PDFBox baseline versus package-only PdfCube.PdfBox font/text proof."
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
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def required-trace-families
  #{"article-handling" "cff" "cid" "cross-reopen" "damaged-font"
    "complex-text-arabic" "complex-text-cluster" "complex-text-combining"
    "complex-text-direction" "complex-text-glyph-position"
    "complex-text-indic" "complex-text-ligature"
    "content-stream-integrity" "displacement" "duplicate-suppression"
    "embedded" "encoding" "extraction-api" "fallback" "font-dictionary"
    "glyph-mapping" "harfbuzz-boundary" "line-separation"
    "missing-glyph" "positioned-text" "representative-pdf" "sorting"
    "standard-font" "substituted" "text-matrix" "text-position"
    "text-position-comparator" "to-unicode" "true-type" "type-0"
    "type-1" "type-3" "unicode-text-shaping" "vertical-writing"
    "width-advance" "word-separation"})

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-pdfbox-font-text-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates a normalized font/text trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "Font/text trace contains a malformed observation"
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
      (fail! "Font/text trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Font/text trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Font/text trace misses required behavior families"
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
       "Package-only PdfCube.PdfBox font/text behavior differs from pinned reviewed PDFBox baseline"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Font/text trace coverage differs between runtimes"
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
   ^Path pdfbox-root ^Path exchange]
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
        (paths/resolve-path root "validation" "pdfcube-pdfbox-font-text"
                            "PdfBoxFontTextOracle.java")
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
                             "PdfBoxFontTextOracle"
                             (str output) (str pdfbox-root) (str exchange)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output
   ^Path pdfbox-root ^Path exchange mode]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root
                                    (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-pdfbox-font-text"
                            "Program.cs")
        arguments
        (cond-> [(str output) (str pdfbox-root) (str exchange)]
          mode (conj mode))]
    (Files/copy probe source
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--verbosity:minimal"
                             "--no-restore" "--no-incremental" "-warnaserror"]
                   :directory consumer-root
                   :timeout-ms 300000})
    (run-command! {:command
                   (into ["dotnet" "run" "--project" (str project)
                          "--no-build" "--no-restore" "--"]
                         arguments)
                   :directory consumer-root
                   :timeout-ms 300000})))

(defn verify!
  "Runs clean deterministic packaging, isolated consumption, and normalized
  Java/.NET font integration, positioned-text, and extraction validation."
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
                              "pdfcube-pdfbox-font-text-differential"))
         exchange
         (doto (paths/resolve-path proof-root "exchange")
           (Files/createDirectories (make-array FileAttribute 0)))
         pdfbox-root (paths/resolve-path root "research" "pdfbox")
         test-resources
         (paths/resolve-path pdfbox-root "pdfbox" "src" "test" "resources")
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root "package-dotnet.tsv")]
     (when-not (paths/directory? test-resources)
       (fail! "Authoritative PDFBox test resources are missing"
              {:resources (str test-resources)}))
     (run-package-probe!
      run-command! root package-proof dotnet-trace pdfbox-root exchange
      "--write-only")
     (compile-and-run-oracle!
      run-command! root generation proof-root java-trace pdfbox-root exchange)
     (run-package-probe!
      run-command! root package-proof dotnet-trace pdfbox-root exchange nil)
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
        "Pinned Java/package PdfCube.PdfBox font/text differential passed:"
        (pr-str (select-keys summary [:source :package :trace])))
       summary))))
