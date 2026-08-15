(ns dripsharp.pdfcube.pdfbox-printing-differential
  "Pinned reviewed PDFBox baseline versus package-only DripSharp.PdfCarton printing proof."
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
           [java.nio.file Files OpenOption Path StandardCopyOption
            StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  (baseline/upstream-revision :pdfcube))

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

(def ^:private printing-boundary-sources
  [[:probe ["validation" "pdfcube-pdfbox-printing" "Program.cs"]]
   [:package-consumer
    ["validation" "pdfcube-pdfbox-printing"
     "DripSharp.PdfCarton.PrintingHostSmoke.csproj"]]
   [:runtime
    ["targets" "pdfcube" "runtime"
     "DripSharp.PdfCarton.Fonts.Compat.cs"]]
   [:mappings ["src" "dripsharp" "pdfcube" "java_project.clj"]]
   [:destination ["targets" "pdfcube" "destinations" "pdfbox.edn"]]])

(def ^:private printing-boundary-requirements
  {:probe
   [["private sealed class NamedPrintable : JavaPrintable" 1]
    ["JavaPrintConstants.PAGE_EXISTS;" 1]
    ["JavaPrintable.PAGE_EXISTS" 0]
    ["JavaPrintable.NO_SUCH_PAGE" 0]]
   :package-consumer
   [["<TargetFramework>net10.0</TargetFramework>" 1]
    ["<TreatWarningsAsErrors>true</TreatWarningsAsErrors>" 1]
    ["<PackageReference Include=\"DripSharp.PdfCarton\"" 1]
    ["<ProjectReference" 0]]
   :runtime
   [["public interface JavaPrintable" 1]
    ["int Print(PdfCartonGraphics2D graphics, JavaPageFormat pageFormat, int pageIndex);" 1]
    ["public static class JavaPrintConstants" 1]
    ["public const int PAGE_EXISTS = 0;" 1]
    ["public const int NO_SUCH_PAGE = 1;" 1]
    ["public const int UNKNOWN_NUMBER_OF_PAGES = -1;" 1]]
   :mappings
   [["field:java.awt.print.Printable#PAGE_EXISTS" 1]
    ["JavaPrintConstants.PAGE_EXISTS" 1]
    ["field:java.awt.print.Printable#NO_SUCH_PAGE" 1]
    ["JavaPrintConstants.NO_SUCH_PAGE" 1]
    ["field:java.awt.print.Pageable#UNKNOWN_NUMBER_OF_PAGES" 1]
    ["JavaPrintConstants.UNKNOWN_NUMBER_OF_PAGES" 1]
    ["JavaPrintable.PAGE_EXISTS" 0]
    ["JavaPrintable.NO_SUCH_PAGE" 0]
    ["JavaPageable.UNKNOWN_NUMBER_OF_PAGES" 0]]
   :destination
   [[":target-framework \"netstandard2.0\"" 1]
    [":package-consumer" 1]
    [":project-file \"DripSharp.PdfCarton.PackageConsumer.csproj\"" 1]]})

(defn- fragment-count [source fragment]
  (count
   (re-seq
    (re-pattern (java.util.regex.Pattern/quote fragment))
    source)))

(defn validate-printing-boundary!
  "Fails closed unless the target-owned probe, package host, runtime, mapping,
  and destination sources preserve the netstandard printing-constant boundary."
  [root]
  (let [root (paths/absolute root)
        sources
        (into
         {}
         (map
          (fn [[id relative]]
            (let [path (apply paths/resolve-path root relative)]
              (when-not (paths/regular-file? path)
                (fail! "Printing boundary source is missing"
                       {:contract :printing-constant-boundary
                        :source id
                        :path (str path)}))
              [id {:path path :text (Files/readString path)}])))
         printing-boundary-sources)]
    (doseq [[source-id requirements] printing-boundary-requirements
            [fragment expected] requirements]
      (let [{:keys [path text]} (get sources source-id)
            actual (fragment-count text fragment)]
        (when-not (= expected actual)
          (fail! "PdfCarton printing constant boundary drifted"
                 {:contract :printing-constant-boundary
                  :source source-id
                  :path (str path)
                  :fragment fragment
                  :expected-occurrences expected
                  :actual-occurrences actual}))))
    {:probe-interface "JavaPrintable"
     :constant-owner "JavaPrintConstants"
     :constants {"PAGE_EXISTS" 0
                 "NO_SUCH_PAGE" 1
                 "UNKNOWN_NUMBER_OF_PAGES" -1}
     :production-framework "netstandard2.0"
     :consumer-framework "net10.0"
     :consumer-package "DripSharp.PdfCarton"}))

(def ^:private write-text! util/write-text!)

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
       "Package-only DripSharp.PdfCarton printing behavior differs from pinned reviewed PDFBox baseline"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Printing trace coverage differs between runtimes"
             {:expected expected-summary :actual actual-summary}))
    comparison))

(defn prove-mismatch-detection!
  "Copies a printing oracle trace, deliberately changes it, and requires the
  shared comparator to report the mismatch."
  [oracle perturbed]
  (let [oracle (paths/path oracle)
        perturbed (paths/path perturbed)]
    (when-not (paths/regular-file? oracle)
      (fail! "Printing mismatch control is missing its oracle trace"
             {:oracle (str oracle)}))
    (Files/createDirectories (.getParent perturbed)
                             (make-array FileAttribute 0))
    (Files/copy oracle perturbed
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING]))
    (Files/writeString
     perturbed
     "failure\tdeliberate-printing-mismatch\tchanged\n"
     (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [comparison (differential/compare-results oracle perturbed)]
      (when-not (:mismatch comparison)
        (fail! "Printing comparator missed a deliberate package mismatch"
               {:oracle (str oracle) :perturbed (str perturbed)}))
      comparison)))

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

(def ^:private current-host util/current-host)

(defn verify!
  "Runs clean deterministic packaging, independent package consumption, and
  normalized Java/.NET printable and pageable differential validation."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         _ (validate-printing-boundary! root)
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
           perturbation
           (prove-mismatch-detection!
            java-trace
            (paths/resolve-path proof-root "deliberate-mismatch.tsv"))
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
            :deliberate-mismatch
            {:line (get-in perturbation [:mismatch :line])
             :expected (get-in perturbation [:mismatch :expected])
             :actual (get-in perturbation [:mismatch :actual])}
            :host (current-host)
            :supported-hosts supported-hosts
            :proof-root proof-root}]
       (write-text!
        (paths/resolve-path proof-root "summary.edn")
        (str (pr-str (dissoc summary :proof-root)) "\n"))
       (println
        "Pinned Java/package DripSharp.PdfCarton printing differential passed:"
        (pr-str (select-keys summary [:source :package :trace :host])))
       summary))))
