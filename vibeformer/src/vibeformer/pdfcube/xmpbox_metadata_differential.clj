(ns vibeformer.pdfcube.xmpbox-metadata-differential
  "Pinned PDFBox 3.0.8 versus generated PdfCube.XmpBox metadata differential."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [vibeformer.compiler :as compiler]
            [vibeformer.differential :as differential]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(def pinned-revision
  "9286e47d89d6877005c9d2d0f2fd38793a62519a")

(def required-trace-families
  #{"namespace" "registry" "simple" "structured" "array" "lang-alt"
    "date" "invalid" "fixture"})

(defn- fail! [message data]
  (throw
   (ex-info message
            (assoc data :kind :pdfcube-xmpbox-metadata-differential-failed))))

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

(defn- assert-match! [expected actual]
  (let [expected-summary (trace-summary expected)
        actual-summary (trace-summary actual)
        comparison (differential/compare-results expected actual)]
    (when-let [mismatch (:mismatch comparison)]
      (fail! "Generated PdfCube.XmpBox metadata behavior differs from PDFBox 3.0.8"
             {:expected (str expected)
              :actual (str actual)
              :comparison comparison
              :mismatch mismatch}))
    (when-not (= expected-summary actual-summary)
      (fail! "Generated XmpBox metadata trace coverage differs from the oracle"
             {:expected expected-summary :actual actual-summary}))
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
        (paths/resolve-path root "vibeformer" "validation"
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

(defn- xml-escape [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- run-generated-probe!
  [run-command! ^Path root build-proof ^Path proof-root ^Path output
   ^Path resources]
  (let [generation (:generation build-proof)
        generated-project
        (get-in generation [:emission :project-file])
        probe-source
        (paths/resolve-path root "vibeformer" "validation"
                            "pdfcube-xmpbox"
                            "PdfCube.XmpBox.MetadataProbe.cs")
        probe-root
        (doto (paths/resolve-path proof-root "dotnet-probe")
          (Files/createDirectories (make-array FileAttribute 0)))
        probe-project
        (paths/resolve-path probe-root "PdfCube.XmpBox.MetadataProbe.csproj")]
    (write-text!
     probe-project
     (str
      "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
      "  <PropertyGroup>\n"
      "    <OutputType>Exe</OutputType>\n"
      "    <TargetFramework>net10.0</TargetFramework>\n"
      "    <Nullable>enable</Nullable>\n"
      "    <ImplicitUsings>disable</ImplicitUsings>\n"
      "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
      "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
      "  </PropertyGroup>\n"
      "  <ItemGroup>\n"
      "    <Compile Include=\"" (xml-escape probe-source)
      "\" Link=\"Program.cs\" />\n"
      "    <ProjectReference Include=\"" (xml-escape generated-project)
      "\" />\n"
      "  </ItemGroup>\n"
      "</Project>\n"))
    (run-command! {:command ["dotnet" "build" (str probe-project)
                             "--nologo" "--configuration" "Release"
                             "--verbosity:minimal" "--no-incremental"
                             "-p:RestoreIgnoreFailedSources=true"
                             "-warnaserror"]
                   :directory probe-root
                   :timeout-ms 300000})
    (run-command! {:command ["dotnet" "run" "--project" (str probe-project)
                             "--configuration" "Release"
                             "--no-build" "--no-restore" "--"
                             (str output) (str resources)]
                   :directory probe-root
                   :timeout-ms 120000})))

(defn- validate-build-contract! [build-proof]
  (let [generation (:generation build-proof)
        project-input (:project-input generation)
        public-metadata (get-in generation [:emission :public-metadata])
        xmpbox-surface
        (first
         (filter #(= "PdfCube.XmpBox" (:assembly %))
                 (get-in build-proof [:public-surface :assemblies])))
        expected
        {:project-id "org.apache.pdfbox:xmpbox:3.0.8"
         :revision pinned-revision
         :production-sources 74
         :generated-production-sources 0
         :public-rows 1199
         :compiled-contract-members 1199}
        actual
        {:project-id (:project-id project-input)
         :revision (get-in generation [:source-project :revision])
         :production-sources (count (:production-sources project-input))
         :generated-production-sources
         (count (:generated-production-sources project-input))
         :public-rows (:required-rows public-metadata)
         :compiled-contract-members (:contract-members xmpbox-surface)}]
    (when-not (= expected actual)
      (fail! "Clean XmpBox generation or public-surface contract drifted"
             {:expected expected :actual actual}))
    actual))

(defn verify!
  "Runs clean XmpBox generation, compilation, public-surface validation, and
  the pinned Java/generated-.NET metadata object-model differential."
  ([] (verify! {}))
  ([{:keys [workspace-root build-fn run-command!]
     :or {build-fn compiler/verify-clean-build!
          run-command! process/run!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         build-proof
         (build-fn {:workspace-root root
                    :profile "pdfcube-xmpbox"
                    :run-command! run-command!})
         build-contract (validate-build-contract! build-proof)
         generation (:generation build-proof)
         proof-root
         (harness/clean-directory!
          (paths/resolve-path root "vibeformer" "validation-output"
                              "pdfcube-xmpbox-metadata-differential"))
         resources
         (paths/resolve-path root "research" "pdfbox" "xmpbox"
                             "src" "test" "resources")
         oracle (paths/resolve-path proof-root "upstream-java.tsv")
         generated (paths/resolve-path proof-root "generated-dotnet.tsv")]
     (compile-and-run-oracle!
      run-command! root generation proof-root oracle resources)
     (run-generated-probe!
      run-command! root build-proof proof-root generated resources)
     (let [comparison (assert-match! oracle generated)
           trace (trace-summary oracle)
           summary
           {:profile "pdfcube-xmpbox"
            :source {:version "3.0.8" :revision pinned-revision}
            :build build-contract
            :public-surface (:public-surface build-proof)
            :trace trace
            :comparison comparison
            :fixtures
            ["org/apache/xmpbox/parser/AltBagSeqTest.xml"
             "org/apache/xmpbox/parser/ThumbisartorStyle.xml"]}]
       (write-text! (paths/resolve-path proof-root "summary.edn")
                    (str (pr-str summary) "\n"))
       (println
        "Pinned Java/generated-.NET PdfCube.XmpBox metadata differential passed:"
        (pr-str (select-keys summary [:source :build :trace :fixtures])))
       (assoc summary :proof-root proof-root)))))
