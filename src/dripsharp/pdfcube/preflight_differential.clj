(ns dripsharp.pdfcube.preflight-differential
  "Pinned PDFBox 3.0.8 versus generated PdfCube.Preflight execution-model proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.compiler :as compiler]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

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
       "Generated PdfCube.Preflight execution behavior differs from pinned PDFBox 3.0.8"
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

(defn- run-dotnet-probe!
  [run-command! ^Path root ^Path output ^Path fixtures]
  (let [project
        (paths/resolve-path
         root "validation" "pdfcube-preflight"
         "PdfCube.Preflight.ExecutionProbe.csproj")]
    (run-command! {:command ["dotnet" "build" (str project)
                             "--nologo" "--configuration" "Release"
                             "--verbosity:minimal" "--no-incremental"
                             "-p:RestoreIgnoreFailedSources=true"
                             "-warnaserror"]
                   :directory root
                   :timeout-ms 300000})
    (run-command! {:command ["dotnet" "run" "--project" (str project)
                             "--configuration" "Release"
                             "--no-build" "--no-restore" "--"
                             (str output) (str fixtures) (str root)]
                   :directory root
                   :timeout-ms 300000})))

(defn verify!
  "Runs clean Preflight generation/build/surface verification and normalized
  pinned Java versus generated .NET execution-model comparison."
  ([] (verify! {}))
  ([{:keys [workspace-root verify-fn run-command!]
     :or {verify-fn compiler/verify-clean-build!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         verification
         (verify-fn {:workspace-root root
                     :profile "pdfcube-preflight"
                     :run-command! run-command!})
         generation (:generation verification)
         proof-root
         (harness/clean-directory!
          (paths/resolve-path
           root "validation-output"
           "pdfcube-preflight-execution-differential"))
         fixtures
         (doto (paths/resolve-path proof-root "fixtures")
           (Files/createDirectories (make-array FileAttribute 0)))
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root "generated-dotnet.tsv")]
     (compile-and-run-oracle!
      run-command! root generation proof-root java-trace fixtures)
     (run-dotnet-probe! run-command! root dotnet-trace fixtures)
     (let [comparison (assert-match! java-trace dotnet-trace)
           trace (trace-summary java-trace)
           summary
           {:profile "pdfcube-preflight"
            :source {:version "3.0.8" :revision pinned-revision}
            :generation
            (select-keys
             (:summary (:emission generation))
             [:compilation-units :declarations :generated-files
              :missing-source-mappings :hard-failures :collisions])
            :public-surface (:public-surface verification)
            :trace trace
            :comparison comparison
            :proof-root proof-root}]
       (spit (str (paths/resolve-path proof-root "summary.edn"))
             (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/generated PdfCube.Preflight execution differential passed:"
        (pr-str (select-keys summary [:source :generation :trace])))
       summary))))
