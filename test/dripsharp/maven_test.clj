(ns dripsharp.maven-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.maven :as maven]
            [dripsharp.harness :as harness]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.java-project :as pdfcube]
            [dripsharp.project-input :as project-input]
            [dripsharp.spoon :as spoon])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file CopyOption FileVisitOption Files OpenOption Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def fixture-root
  (paths/resolve-path (paths/workspace-root)
                      "test" "fixtures" "maven_reactor"))

(def app-id "org.example.dripsharp:fixture-app:1.0.0")
(def core-id "org.example.dripsharp:fixture-core:1.0.0")

(declare copy-tree!)

(defn- synthetic-discovery
  []
  (let [root
        (Files/createTempDirectory "dripsharp-maven-fixture-reactor-"
                                   (make-array FileAttribute 0))
        _ (copy-tree! fixture-root root)
        manifest
        (Files/createTempFile "dripsharp-maven-fixture-" ".tsv"
                              (make-array FileAttribute 0))]
    (try
      (let [reactor
            (maven/discover-reactor!
             {:project-root root
              :selected-projects [":fixture-app"]
              :manifest manifest})]
        {:reactor reactor
         :manifest (slurp (str manifest))})
      (finally
        (Files/deleteIfExists manifest)))))

(defonce ^:private synthetic-state (delay (synthetic-discovery)))

(defn- project
  [reactor project-id]
  (maven/project-by-id! reactor project-id))

(defn- copy-tree!
  [^Path source ^Path destination]
  (with-open [entries (Files/walk source (make-array FileVisitOption 0))]
    (doseq [^Path input (->> (.toArray entries)
                             (map #(cast Path %))
                             (remove #(str/includes? (str %) "/target/"))
                             (sort-by #(.getNameCount ^Path %)))]
      (let [relative (.relativize source input)
            output (.resolve destination relative)]
        (if (Files/isDirectory input (make-array java.nio.file.LinkOption 0))
          (Files/createDirectories output (make-array FileAttribute 0))
          (do
            (Files/createDirectories (.getParent output)
                                     (make-array FileAttribute 0))
            (Files/copy input output
                        (into-array CopyOption
                                    [StandardCopyOption/REPLACE_EXISTING])))))))
  destination)

(deftest pinned-runner-rejects-corrupt-distributions
  (let [cache
        (Files/createTempDirectory "dripsharp-corrupt-maven-"
                                   (make-array FileAttribute 0))
        archive
        (paths/resolve-path cache "downloads"
                            (str "apache-maven-" maven/maven-version "-bin.zip"))
        _ (Files/createDirectories (.getParent archive)
                                   (make-array FileAttribute 0))
        _ (Files/writeString archive "not a Maven distribution"
                             (make-array OpenOption 0))
        error (try
                (maven/ensure-pinned-runner! {:runner-cache cache})
                nil
                (catch ExceptionInfo caught caught))]
    (is (= :maven-runner-checksum-mismatch (:kind (ex-data error))))
    (is (= maven/maven-distribution-sha512
           (:expected (ex-data error))))
    (is (re-find #"remove the corrupt cache entry"
                 (.getMessage error)))))

(deftest effective-multi-module-reactor-adapts-to-neutral-inputs
  (let [{:keys [reactor manifest]} @synthetic-state
        app (project reactor app-id)
        source-paths (mapv str (project-input/production-source-files app))
        resources (mapv str (:production-resources app))
        project-dependencies (set (:project-dependencies app))
        external-dependencies (set (:external-dependencies app))
        artifacts (:classpath-artifacts app)]
    (testing "Maven inheritance and lifecycle-added production inputs are effective"
      (is (= 17 (get-in app [:java-toolchain :release])))
      (is (false? (get-in app [:java-toolchain :preview-features?])))
      (is (= 1 (count (:production-sources app))))
      (is (= 1 (count (:generated-production-sources app))))
      (is (some #(str/ends-with? % "/GeneratedValue.java") source-paths))
      (is (= #{"app.txt" "inherited.txt"}
             (set (map #(str (.getFileName ^Path %))
                       (:production-resources app)))))
      (is (= app (project-input/validate! app))))

    (testing "production and test inputs remain explicitly distinguished"
      (is (some #(str/starts-with? % "test-source\t") (str/split-lines manifest)))
      (is (some #(str/starts-with? % "test-resource\t")
                (str/split-lines manifest)))
      (is (not-any? #(str/includes? % "/src/test/") source-paths))
      (is (not-any? #(str/includes? % "/src/test/") resources)))

    (testing "reactor and external scopes retain Maven classpath semantics"
      (is (= #{{:scope :compile :project-id core-id}
               {:scope :runtime :project-id core-id}}
             project-dependencies))
      (is (contains?
           external-dependencies
           {:scope :compile
            :coordinate "org.apache.commons:commons-lang3:jar:3.17.0"}))
      (is (contains?
           external-dependencies
           {:scope :runtime
            :coordinate "org.apache.commons:commons-lang3:jar:3.17.0"}))
      (is (contains?
           external-dependencies
           {:scope :runtime
            :coordinate "commons-codec:commons-codec:jar:1.17.1"}))
      (is (not (contains?
                external-dependencies
                {:scope :compile
                 :coordinate "commons-codec:commons-codec:jar:1.17.1"})))
      (is (contains?
           external-dependencies
           {:scope :compile
            :coordinate "org.jspecify:jspecify:jar:1.0.0"}))
      (is (every? #(or (:project-id %)
                       (re-matches #"[0-9a-f]{64}" (:sha256 %)))
                  artifacts))
      (is (every? paths/regular-file?
                  (map :path (filter :coordinate artifacts))))
      (is (some #(and (= core-id (:project-id %))
                      (paths/directory? (:path %)))
                artifacts)))

    (testing "the neutral Maven output is accepted by Spoon"
      (let [resolved (spoon/build-resolved-model! (:project-root app) app)]
        (is (= 2 (get-in resolved [:totals :compilation-units])))
        (is (not-any?
             #(contains? (:totals resolved) %)
             [:shadow-symbols :unresolved-symbols :ambiguous-symbols
              :fallback-symbols]))))))

(deftest unresolved-maven-dependencies-have-actionable-diagnostics
  @synthetic-state
  (let [root
        (Files/createTempDirectory "dripsharp-maven-unresolved-"
                                   (make-array FileAttribute 0))
        _ (copy-tree! fixture-root root)
        pom (paths/resolve-path root "fixture-app" "pom.xml")
        original (slurp (str pom))
        dependency
        (str "    <dependency>\n"
             "      <groupId>org.example.missing</groupId>\n"
             "      <artifactId>missing-production-library</artifactId>\n"
             "      <version>987654321.0</version>\n"
             "    </dependency>\n")
        _ (Files/writeString
           pom
           (str/replace original "  </dependencies>"
                        (str dependency "  </dependencies>"))
           (make-array OpenOption 0))
        error
        (try
          (maven/discover-reactor!
           {:project-root root
            :selected-projects [":fixture-app"]
            :offline? true})
          nil
          (catch ExceptionInfo caught caught))]
    (is (= :maven-discovery-failed (:kind (ex-data error))))
    (is (= maven/maven-version (:maven-version (ex-data error))))
    (is (str/includes? (.getMessage error)
                       "org.example.missing:missing-production-library"))
    (is (str/includes? (:output (ex-data error))
                       "org.example.missing:missing-production-library"))
    (is (seq (:command (ex-data error))))))

(deftest pinned-pdfbox-reactor-discovers-selected-production-trees
  (let [workspace (paths/workspace-root)
        profiles
        (mapv harness/read-profile
              (repeat workspace)
              ["targets/pdfcube/profiles/io.edn"
               "targets/pdfcube/profiles/fontbox.edn"
               "targets/pdfcube/profiles/xmpbox.edn"
               "targets/pdfcube/profiles/pdfbox.edn"
               "targets/pdfcube/profiles/preflight.edn"])
        destinations
        (mapv #(java-project/read-configuration
                workspace (:destination-config %))
              profiles)
        reactor
        (maven/discover-reactor!
         {:project-root "research/pdfbox"
          :selected-projects
          (mapv (comp first :maven-selected-projects) profiles)})
        expected
        {"pdfbox-io" [18 0]
         "fontbox" [143 93]
         "xmpbox" [74 0]
         "pdfbox" [621 22]
         "preflight" [116 0]}
        selected
        (into {}
              (keep
               (fn [input]
                 (let [[_ artifact _] (str/split (:project-id input) #":")]
                   (when (contains? expected artifact)
                     [artifact input]))))
              reactor)]
    (is (= (set (keys expected)) (set (keys selected))))
    (is (= (set (map :maven-project-id profiles))
           (set (map :project-id (vals selected)))))
    (doseq [[profile destination] (map vector profiles destinations)
            :let [input (maven/project-by-id!
                         reactor (:maven-project-id profile))]]
      (testing (str (:profile profile) " effective dependency contract")
        ((get-in (pdfcube/rule-bundle)
                 [:orchestration :validate-profile!])
         {:workspace-root workspace
          :profile profile
          :configuration destination})
        ((get-in (pdfcube/rule-bundle)
                 [:orchestration :validate-project-input!])
         {:workspace-root workspace
          :profile profile
          :configuration destination
          :project-input input})
        (is (vector? (:generated-production-sources input)))
        (is (vector? (:production-resources input)))
        (is (every? #{:compile :runtime}
                    (map :scope (:project-dependencies input))))
        (is (every? #{:compile :runtime}
                    (map :scope (:external-dependencies input))))
        (is (every? paths/exists?
                    (project-input/compile-classpath input)))))
    (doseq [[artifact [source-count resource-count]] expected
            :let [input (get selected artifact)]]
      (testing artifact
        (is (= 8 (get-in input [:java-toolchain :release])))
        (is (= source-count
               (count (project-input/production-source-files input))))
        (is (= resource-count (count (:production-resources input))))
        (is (not-any? #(str/includes? (str %) "/src/test/")
                      (project-input/production-source-files input)))
        (is (= input (project-input/validate! input)))))
    (testing "the PdfCube.IO input resolves through the live Spoon frontend"
      (let [input (get selected "pdfbox-io")
            resolved (spoon/build-resolved-model! (:project-root input) input)]
        (is (= 18 (get-in resolved [:totals :compilation-units])))
        (is (not-any?
             #(contains? (:totals resolved) %)
             [:shadow-symbols :unresolved-symbols :ambiguous-symbols
              :fallback-symbols]))))))
