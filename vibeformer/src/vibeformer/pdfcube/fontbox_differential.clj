(ns vibeformer.pdfcube.fontbox-differential
  "Pinned PDFBox 3.0.8 versus package-only PdfCube.FontBox behavioral proof."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vibeformer.differential :as differential]
            [vibeformer.harness :as harness]
            [vibeformer.packaging :as packaging]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.io File]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(def pinned-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def required-trace-families
  #{"encoding" "cff" "afm" "cmap" "pfb" "type1" "truetype" "opentype"
    "tables" "gsub" "collection" "lifecycle" "failure"})

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
        (paths/resolve-path root "vibeformer" "validation"
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
        (paths/resolve-path root "vibeformer" "validation"
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
         generation (get-in package-proof [:verification :generation])
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "vibeformer" "validation-output"
                              "pdfcube-fontbox-differential"))
         canonical
         (paths/resolve-path root "vibeformer" "validation"
                             "pdfcube-fontbox" "canonical-trace.tsv")
         resources
         (paths/resolve-path root "research" "pdfbox" "fontbox"
                             "src" "test" "resources")
         fonts (ensure-font-fixtures! root)
         oracle (paths/resolve-path proof-root "upstream-java.tsv")
         packaged (paths/resolve-path proof-root "package-dotnet.tsv")]
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
           trace (trace-summary oracle)
           summary
           {:profile "pdfcube-fontbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :package (select-keys (:identity package-proof)
                                  [:id :version :sha256 :file])
            :consumer (:dependency-proof package-proof)
            :trace trace
            :canonical-comparison canonical-comparison
            :package-comparison package-comparison
            :fixtures
            (mapv #(select-keys % [:file :sha512]) font-fixtures)}]
       (Files/writeString
        (paths/resolve-path proof-root "summary.edn")
        (str (pr-str summary) "\n")
        (make-array OpenOption 0))
       (println "Pinned Java/package PdfCube.FontBox differential passed:"
                (pr-str (select-keys summary [:source :package :trace])))
       (assoc summary :proof-root proof-root)))))
