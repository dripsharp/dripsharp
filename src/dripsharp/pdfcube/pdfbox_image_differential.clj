(ns dripsharp.pdfcube.pdfbox-image-differential
  "Pinned reviewed PDFBox baseline versus package-only DripSharp.PdfCarton image proof."
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
  #{"codec-metadata" "failure" "full-pixels" "region-subsampling"})

(def required-fixtures
  ["JPXTestCMYK.pdf"
   "JPXTestGrey.pdf"
   "JPXTestRGB.pdf"
   "JBIG2Image.pdf"])

(defn- fail! [message data]
  (throw
   (ex-info
    message
    (assoc data :kind :pdfcube-pdfbox-image-differential-failed))))

(defn- configured-path [^Path root value]
  (let [path (paths/path value)]
    (paths/absolute
     (if (.isAbsolute path)
       path
       (paths/resolve-path root path)))))

(defn- oracle-imageio-dependencies []
  (let [configured (System/getenv "XDG_CACHE_HOME")
        base (if (str/blank? configured)
               (paths/resolve-path (System/getProperty "user.home") ".cache")
               (paths/path configured))
        repository (paths/resolve-path base "maven" "repository")
        artifacts
        [(paths/resolve-path
          repository "org" "apache" "pdfbox" "jbig2-imageio" "3.0.5"
          "jbig2-imageio-3.0.5.jar")
         (paths/resolve-path
          repository "com" "github" "jai-imageio" "jai-imageio-core" "1.4.0"
          "jai-imageio-core-1.4.0.jar")
         (paths/resolve-path
          repository "com" "github" "jai-imageio"
          "jai-imageio-jpeg2000" "1.4.0"
          "jai-imageio-jpeg2000-1.4.0.jar")]]
    (doseq [artifact artifacts]
      (when-not (paths/regular-file? artifact)
        (fail! "Pinned Java image oracle dependency is missing"
               {:artifact (str artifact)})))
    artifacts))

(defn trace-summary
  "Validates a normalized codec trace and returns its coverage."
  [trace]
  (let [trace (paths/path trace)
        lines (vec (Files/readAllLines trace StandardCharsets/UTF_8))
        rows
        (mapv
         (fn [index line]
           (let [fields (str/split line #"\t" -1)]
             (when-not (and (= 3 (count fields))
                            (every? (complement str/blank?) fields))
               (fail! "Image trace contains a malformed observation"
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
      (fail! "Image trace contains no observations" {:trace (str trace)}))
    (when (seq duplicates)
      (fail! "Image trace contains duplicate observation identities"
             {:trace (str trace) :duplicates duplicates}))
    (when (seq missing)
      (fail! "Image trace misses required behavior families"
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
       "Package-only DripSharp.PdfCarton image behavior differs from pinned reviewed PDFBox baseline"
       {:expected (str expected)
        :actual (str actual)
        :comparison comparison
        :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Image trace coverage differs between runtimes"
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
   ^Path resources]
  (let [{:keys [release java javac]} (java-tools root generation)
        project-input (:project-input generation)
        sources
        (mapv #(configured-path root %)
              (concat (:production-sources project-input)
                      (:generated-production-sources project-input)))
        dependencies
        (->> (:classpath-artifacts project-input)
             (map #(configured-path root (:path %)))
             (concat (oracle-imageio-dependencies))
             distinct
             vec)
        resource-roots
        (mapv #(configured-path root %) (:resource-roots project-input))
        oracle-source
        (paths/resolve-path root "validation" "pdfcube-pdfbox-image"
                            "PdfBoxImageOracle.java")
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
                             "PdfBoxImageOracle"
                             (str output) (str resources)]
                   :directory root
                   :timeout-ms 300000})))

(defn- run-package-probe!
  [run-command! ^Path root package-proof ^Path output ^Path resources]
  (let [generation (get-in package-proof [:verification :generation])
        consumer-profile (get-in generation [:destination :package-consumer])
        consumer-root (:consumer-root package-proof)
        project (paths/resolve-path consumer-root
                                    (:project-file consumer-profile))
        source (paths/resolve-path consumer-root "Program.cs")
        probe
        (paths/resolve-path root "validation" "pdfcube-pdfbox-image"
                            "Program.cs")]
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
                   :timeout-ms 300000})))

(defn verify!
  "Runs clean packaging, package-only consumption, and normalized Java/.NET
  JBIG2 and JPX image validation."
  ([] (verify! {}))
  ([{:keys [workspace-root package-fn run-command!]
     :or {package-fn packaging/verify-package-consumption!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         resources
         (paths/resolve-path root "research" "pdfbox" "tools" "src" "test"
                             "resources" "input" "ImageIOUtil")]
     (doseq [fixture required-fixtures]
       (let [path (paths/resolve-path resources fixture)]
         (when-not (paths/regular-file? path)
           (fail! "Authoritative PDFBox image fixture is missing"
                  {:fixture fixture :path (str path)}))))
     (let [package-proof
           (package-fn {:workspace-root root
                        :profile "pdfcube-pdfbox"
                        :run-command! run-command!})
           generation (get-in package-proof [:verification :generation])
           proof-root
           (harness/clean-directory!
            (paths/resolve-path root "validation-output"
                                "pdfcube-pdfbox-image-differential"))
           java-trace (paths/resolve-path proof-root "upstream-java.tsv")
           dotnet-trace (paths/resolve-path proof-root "package-dotnet.tsv")]
       (compile-and-run-oracle!
        run-command! root generation proof-root java-trace resources)
       (run-package-probe!
        run-command! root package-proof dotnet-trace resources)
       (let [comparison (assert-match! java-trace dotnet-trace)
             trace (trace-summary java-trace)
             summary
             {:profile "pdfcube-pdfbox"
              :source {:version (baseline/upstream-version :pdfcube) :revision pinned-revision}
              :fixtures required-fixtures
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
          "Pinned Java/package DripSharp.PdfCarton image differential passed:"
          (pr-str (select-keys summary [:source :fixtures :package :trace])))
         summary)))))
