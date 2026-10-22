(ns dripsharp.pdfcube.pdfbox-interaction-differential
  "Pinned reviewed PDFBox baseline versus package-only DripSharp.PdfCarton forms and
  document-interaction proof."
  (:require [dripsharp.baseline :as baseline]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.generated-project :as generated-project]
            [dripsharp.process :as process]
            [dripsharp.target-execution :as target-execution])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

(def required-trace-families
  #{"action" "annotation-appearance" "annotation-fixture"
    "annotation-model" "attachment" "destination" "form-flatten"
    "form-import-export" "form-malformed" "form-model" "javascript"
    "named-destination" "outline" "page-label" "representative-reopen"
    "thread"})

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data
           :kind :pdfcube-pdfbox-interaction-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn trace-summary
  "Validates a normalized interaction trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "Interaction trace contains a malformed observation"
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
      (fail! "Interaction trace contains no observations"
             {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Interaction trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Interaction trace misses required behavior families"
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
      (fail! "Package-only DripSharp.PdfCarton interactions differ from pinned reviewed PDFBox baseline"
             {:expected (str expected)
              :actual (str actual)
              :comparison comparison
              :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Interaction trace coverage differs between runtimes"
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
   ^Path exchange ^Path fixtures]
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
         root "validation" "pdfcube-pdfbox-interaction"
         "PdfBoxInteractionOracle.java")
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
                             "PdfBoxInteractionOracle"
                             (str output) (str exchange) (str fixtures)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-consumer-probe!
  [run-command! ^Path root package-proof ^Path output ^Path exchange
   ^Path fixtures mode]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root
                                    (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation"
                            "pdfcube-pdfbox-interaction" "Program.cs")
        arguments
        (cond-> [(str output) (str exchange) (str fixtures)]
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

(defn- run-probe!
  "Runs the .NET probe in the packed consumer, or the checked-in
  project-reference probe in generated-project mode."
  [run-command! ^Path root proof & arguments]
  (if-let [project (:probe proof)]
    (generated-project/run-probe! run-command! root project
                                  (remove nil? arguments))
    (apply run-consumer-probe! run-command! root proof arguments)))

(defn verify!
  "Runs clean deterministic packaging, independent package consumption, and
  normalized Java/.NET forms and document-interaction validation.

  With :generated-project? true it instead compares the existing generated
  project through the checked-in project-reference probe (see
  validation/pdfcube-pdfbox/README.md); :maven-manifest overrides
  target/maven-reactor-inputs-0.tsv."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command! generated-project?
            maven-manifest]
     :or {package-fn #(target-execution/package! (assoc % :target :pdfcube))
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         package-proof
         (if generated-project?
           (assoc (generated-project/proof
                   {:run-command! run-command!
                    :workspace-root root
                    :maven-manifest maven-manifest
                    :fail! fail!})
                  :probe (generated-project/probe-project
                          root "pdfcube-pdfbox-interaction"
                          "DripSharp.PdfCarton.InteractionProbe.csproj"))
           (package-fn {:workspace-root root
                        :profile "pdfcube-pdfbox"
                        :run-command! run-command!}))
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path
           root "validation-output"
           "pdfcube-pdfbox-interaction-differential"))
         exchange
         (doto (paths/resolve-path proof-root "exchange")
           (Files/createDirectories (make-array FileAttribute 0)))
         fixtures
         (paths/resolve-path root "research" "pdfbox" "pdfbox" "src" "test"
                             "resources")
         java-trace (paths/resolve-path proof-root "upstream-java.tsv")
         dotnet-trace (paths/resolve-path proof-root
                                          (if generated-project?
                                            "project-dotnet.tsv"
                                            "package-dotnet.tsv"))]
     (when-not (paths/directory? fixtures)
       (fail! "Pinned upstream PDFBox fixtures are missing"
              {:fixtures (str fixtures)}))
     (when generated-project?
       (generated-project/build-probe!
        run-command! root (:probe package-proof)))
     (run-probe!
      run-command! root package-proof dotnet-trace exchange fixtures
      "--write-only")
     (compile-and-run-oracle!
      run-command! root generation proof-root java-trace exchange fixtures)
     (run-probe!
      run-command! root package-proof dotnet-trace exchange fixtures nil)
     (let [comparison (assert-match! java-trace dotnet-trace)
           trace (trace-summary java-trace)
           summary
           {:profile "pdfcube-pdfbox"
            :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
            :package (generated-project/package-summary package-proof)
            :consumer (generated-project/consumer-summary package-proof)
            :fixtures
            ["org/apache/pdfbox/pdmodel/interactive/form/MultilineFields.pdf"
             "org/apache/pdfbox/pdmodel/interactive/annotation/AnnotationTypes.pdf"
             "org/apache/pdfbox/pdmodel/with_outline.pdf"
             "org/apache/pdfbox/pdmodel/test_pagelabels.pdf"
             "org/apache/pdfbox/pdmodel/common/testPDF_multiFormatEmbFiles.pdf"]
            :trace trace
            :comparison comparison
            :proof-root proof-root}]
       (spit (str (paths/resolve-path proof-root "summary.edn"))
             (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        (str "Pinned Java/"
             (if generated-project? "generated-project" "package")
             " DripSharp.PdfCarton interaction differential passed:")
        (pr-str (select-keys summary [:source :package :trace])))
       summary))))
