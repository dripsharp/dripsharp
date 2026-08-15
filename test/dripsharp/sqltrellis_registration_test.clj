(ns dripsharp.sqltrellis-registration-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dripsharp.consumer-tests :as consumer-tests]
            [dripsharp.differential :as differential]
            [dripsharp.harness :as harness]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.sqltrellis.java-project :as sqltrellis]
            [dripsharp.sqltrellis.test-suite :as test-suite]
            [dripsharp.target-directory :as target-directory]
            [dripsharp.util :as util])
  (:import [java.nio.file CopyOption FileVisitOption Files Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private workspace (paths/workspace-root))
(def ^:private profile-file "targets/sqltrellis/profiles/core.edn")
(def ^:private build-input-file "targets/sqltrellis/maven-build-inputs.edn")
(def ^:private behavior-contract-file
  "targets/sqltrellis/validation/behavior.edn")
(def ^:private revision "8a9479a05c75fcb73d0ed167a822b9b18ab7abaa")

(defn- input-options [project-root local-repository]
  (let [profile (harness/read-profile workspace profile-file)]
    (cond->
     {:workspace-root workspace
      :project-root project-root
      :selected-projects (:maven-selected-projects profile)
      :properties (:maven-properties profile)
      :build-input-contract build-input-file
      :lifecycle-phase (name (:maven-lifecycle-phase profile))}
      local-repository (assoc :local-repository local-repository))))

(defn- discover-input
  ([project-root] (discover-input project-root nil))
  ([project-root local-repository]
   (maven/project-by-id!
    (maven/discover-reactor! (input-options project-root local-repository))
    "com.github.jsqlparser:jsqlparser:5.3")))

(def ^:private live-input (delay (discover-input "research/jsqlparser")))

(defn- failure [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- relative-path [root path]
  (-> (paths/absolute root)
      (.relativize (paths/absolute path))
      str
      (.replace \\ \/)))

(defn- generated-hashes [root input]
  (into (sorted-map)
        (map (fn [file]
               [(relative-path root file) (util/sha256-file file)]))
        (:generated-production-sources input)))

(defn- representative-plan [checkout]
  (let [source (fn [relative line]
                 {:file (str (paths/resolve-path checkout relative))
                  :line line
                  :column 3})]
    {:schema-version 5
     :source-model-totals {:types 2 :methods 2}
     :root-classes ["example.EnabledTest" "example.DisabledTest"]
     :annotation-inventory
     (sorted-map "annotation:org.junit.jupiter.api.Disabled" 1
                 "annotation:org.junit.jupiter.params.ParameterizedTest" 1
                 "annotation:org.junit.jupiter.api.Test" 1)
     :classes
     (sorted-map
      "example.DisabledTest"
      {:name "example.DisabledTest"
       :annotations []
       :methods
       [{:id "example.DisabledTest#disabled()"
         :source (source "src/test/java/example/DisabledTest.java" 17)}]}
      "example.EnabledTest"
      {:name "example.EnabledTest"
       :annotations []
       :methods
       [{:id "example.EnabledTest#rows(java.lang.String)"
         :source (source "src/test/java/example/EnabledTest.java" 11)}]})
     :cases
     [{:id "example.EnabledTest#rows(java.lang.String)"
       :disabled nil
       :parameters
       {:kind :inline-rows
        :rows [{:id "value:0" :arguments ["alpha"]
                :source (source "src/test/java/example/EnabledTest.java" 10)}
               {:id "value:1" :arguments ["beta"]
                :source (source "src/test/java/example/EnabledTest.java" 10)}]}
       :source (source "src/test/java/example/EnabledTest.java" 11)}
      {:id "example.DisabledTest#disabled()"
       :disabled {:symbol "annotation:org.junit.jupiter.api.Disabled"
                  :reason "upstream reason"
                  :reason-supplied? true}
       :parameters nil
       :source (source "src/test/java/example/DisabledTest.java" 17)}]}))

(defn- copy-source-checkout! [destination]
  (let [source (paths/resolve-path workspace "research/jsqlparser")]
    (with-open [entries (Files/walk source (make-array FileVisitOption 0))]
      (doseq [^Path entry (sort-by #(.getNameCount ^Path %)
                                   (map #(cast Path %) (.toArray entries)))]
        (let [relative (.relativize source entry)
              components (mapv str (iterator-seq (.iterator relative)))]
          (when-not (or (= ".git" (first components))
                        (= "target" (first components)))
            (let [target (paths/resolve-path destination relative)]
              (if (Files/isDirectory entry (make-array java.nio.file.LinkOption 0))
                (Files/createDirectories target (make-array FileAttribute 0))
                (do
                  (Files/createDirectories (.getParent target)
                                           (make-array FileAttribute 0))
                  (Files/copy entry target (make-array CopyOption 0))))))))))
  destination)

(deftest sqltrellis-target-registration-is-exact
  (let [product-repository
        (paths/resolve-path workspace "products/sqltrellis")
        product-repository-existed? (paths/exists? product-repository)
        target (target-directory/read-target workspace :sqltrellis)
        profile (harness/read-profile workspace profile-file)
        checkout (process/run!
                  {:command ["git" "rev-parse" "HEAD"]
                   :directory (paths/resolve-path workspace "research/jsqlparser")})]
    (is (= :sqltrellis (:target target)))
    (is (= revision (str/trim (:output checkout))))
    (is (= "Apache-2.0" (get-in target [:baseline :record :upstream :license])))
    (is (nil?
         (get-in target
                 [:baseline :record :profiles :core
                  :public-contract-status])))
    (is (= 5421
           (get-in target
                   [:baseline :record :profiles :core
                    :public-contract-rows])))
    (is (= "dripsharp/sqltrellis"
           (get-in target [:publication :repository-slug])))
    (is (= "products/sqltrellis"
           (get-in target [:publication :submodule-path])))
    (is (= {"sqltrellis" "src/DripSharp.SqlTrellis"}
           (get-in target [:publication :profile-projects])))
    (is (= ["src/DripSharp.SqlTrellis/source-map.edn"]
           (get-in target [:publication :excluded-paths])))
    (is (= "disable"
           (get-in target
                   [:profiles "sqltrellis" :destination
                    :configuration :project :nullable])))
    (is (= product-repository-existed?
           (paths/exists? product-repository)))
    (is (= :maven (:build-tool profile)))
    (is (= :generate-sources (:maven-lifecycle-phase profile)))
    (is (= [":jsqlparser"] (:maven-selected-projects profile)))
    (is (= "targets/sqltrellis/maven-build-inputs.edn"
           (:maven-build-input-contract profile)))))

(deftest neutral-model-retains-the-complete-pinned-production-and-test-graph
  (let [input @live-input
        project-root (paths/resolve-path workspace "research/jsqlparser")
        profile (harness/read-profile workspace profile-file)
        production-graph
        (sqltrellis/production-source-graph project-root input)
        benchmarks
        (->> (:test-sources input)
             (map #(relative-path project-root %))
             (filter #(str/starts-with?
                       % "src/test/java/net/sf/jsqlparser/benchmark/"))
             sort vec)]
    (sqltrellis/validate-project-input!
     {:workspace-root workspace
      :profile profile
      :project-input input})
    (is (= 444 (count (:production-sources input))))
    (is (= 15 (count (:generated-production-sources input))))
    (is (= 1 (count (:production-resources input))))
    (is (= 218 (count (:test-sources input))))
    (is (= 295 (count (:test-resources input))))
    (is (= 7 (count (:external-dependencies input))))
    (is (= 48 (count (:test-external-dependencies input))))
    (is (= 7 (count (:classpath-artifacts input))))
    (is (= 48 (count (:test-classpath-artifacts input))))
    (is (not (contains? profile :seeds)))
    (is (= {:generated 15 :ordinary 444 :resources 1}
           (update-vals production-graph count)))
    (is (= 460
           (count (distinct (mapcat val production-graph)))))
    (is (= "target/generated-sources/javacc/net/sf/jsqlparser/parser/CCJSqlParser.java"
           (first (:generated production-graph))))
    (is (= "target/generated-sources/jjtree/net/sf/jsqlparser/parser/SimpleNode.java"
           (last (:generated production-graph))))
    (is (empty?
         (set/intersection
          (set (concat (:production-sources input)
                       (:generated-production-sources input)))
          (set (:test-sources input)))))
    (is (empty?
         (set/intersection (set (:production-resources input))
                           (set (:test-resources input)))))
    (is (= ["src/test/java/net/sf/jsqlparser/benchmark/DynamicParserRunner.java"
            "src/test/java/net/sf/jsqlparser/benchmark/JSQLParserBenchmark.java"
            "src/test/java/net/sf/jsqlparser/benchmark/LatestClasspathRunner.java"
            "src/test/java/net/sf/jsqlparser/benchmark/SqlParserRunner.java"]
           benchmarks))
    (is (some #(str/includes? (relative-path project-root %)
                              "PerformanceTest.java")
              (:test-sources input)))))

(deftest maven-generator-pins-fail-closed
  (let [input @live-input
        contract
        (maven/read-build-input-contract!
         workspace
         (paths/resolve-path workspace "research/jsqlparser")
         build-input-file
         "generate-sources"
         {"license.skipUpdateLicense" "true"
          "pmd.skip" "true"
          "skipCheckSources" "true"})
        changed (assoc-in input [:build-input-artifacts 0 :sha256]
                          (apply str (repeat 64 "0")))
        error (failure #(maven/verify-build-input-contract! [changed] contract))]
    (is (= [{:owner "org.codehaus.mojo:build-helper-maven-plugin:3.6.0"
             :goal "add-source"}
            {:owner "org.javacc.plugin:javacc-maven-plugin:3.0.3"
             :goal "jjtree-javacc"}]
           (:generation-executions input)))
    (is (= 88 (count (:build-input-artifacts input))))
    (is (= "b0a69fec97977508db743a247a783fde08d0882edc9702436674fada69f189a2"
           (maven/generation-artifacts-sha256
            (:build-input-artifacts input))))
    (is (= :maven-build-input-artifact-drift (:kind (ex-data error))))))

(deftest shipped-suite-declaration-has-one-contained-nonpackable-test-project
  (let [target (target-directory/read-target workspace :sqltrellis)
        suites (get-in target [:publication :test-suites])
        project (first (:projects suites))
        strategy (first (:strategies suites))
        rendered (#'consumer-tests/render-project target project)
        fixture-targets (#'test-suite/fixture-targets)
        solution (#'consumer-tests/render-solution target)
        references (#'consumer-tests/project-reference-paths target project)]
    (is (= "DripSharp.SqlTrellis.Tests" (:id project)))
    (is (= "tests/DripSharp.SqlTrellis.Tests" (:directory project)))
    (is (true? (:solution-inclusion project)))
    (is (= "DripSharp.SqlTrellis.slnx"
           (#'consumer-tests/solution-relative-path! target)))
    (is (str/includes?
         solution
         "Path=\"src/DripSharp.SqlTrellis/DripSharp.SqlTrellis.csproj\""))
    (is (str/includes?
         solution
         "Path=\"tests/DripSharp.SqlTrellis.Tests/DripSharp.SqlTrellis.Tests.csproj\""))
    (is (not (str/includes? solution (str workspace))))
    (is (= ["sqltrellis"] (:profile-references project)))
    (is (= :adapted-upstream (:kind strategy)))
    (is (= :shipped (:policy strategy)))
    (is (= ["../../src/DripSharp.SqlTrellis/DripSharp.SqlTrellis.csproj"]
           references))
    (is (str/includes? rendered "<IsTestProject>true</IsTestProject>"))
    (is (str/includes? rendered "<IsPackable>false</IsPackable>"))
    (is (str/includes?
         fixture-targets
         (str "<None Update=\"Fixtures/**/*\">\n"
              "      <TargetPath>%(Identity)</TargetPath>\n"
              "      <CopyToOutputDirectory>PreserveNewest"
              "</CopyToOutputDirectory>")))
    (is (str/includes?
         fixture-targets
         (str "<None Update=\"src/main/jjtree/**/*\">\n"
              "      <TargetPath>%(Identity)</TargetPath>\n"
              "      <CopyToOutputDirectory>PreserveNewest"
              "</CopyToOutputDirectory>")))
    (is (= 2 (count (re-seq #"<TargetPath>%\(Identity\)</TargetPath>"
                            fixture-targets))))
    (is (= [{:id "Microsoft.NET.Test.Sdk" :version "17.14.1"}
            {:id "xunit" :version "2.9.3"}
            {:id "xunit.runner.visualstudio" :version "3.1.4"}
            {:id "Castle.Core" :version "5.1.1"}]
           (:packages project)))
    (is (= 'dripsharp.sqltrellis.test-suite/strategy! (:handler strategy)))
    (let [error (failure #(test-suite/strategy! {:phase :unknown}))
          ladder (get-in target [:proof :ladders 0])]
      (is (= :sqltrellis-test-suite-generation-failed
             (:kind (ex-data error))))
      (is (= :unsupported-sqltrellis-test-suite-phase
             (:reason (ex-data error))))
      (is (= :sqltrellis-complete-product-proof (:id ladder)))
      (is (= :target-validations (:kind ladder)))
      (is (= [:sqltrellis-registration :sqltrellis-behavior]
             (:validation-contracts ladder)))
      (is (nil? (:runner ladder))))))

(deftest adapted-suite-plan-accounting-is-checkout-path-independent
  (let [left-root (paths/resolve-path
                   workspace "target/plan-checkouts/left/research/jsqlparser")
        right-root (paths/resolve-path
                    workspace "target/plan-checkouts/right/research/jsqlparser")
        left (representative-plan left-root)
        right (representative-plan right-root)
        portable-left (#'test-suite/portable-plan left-root left)
        portable-right (#'test-suite/portable-plan right-root right)
        ids #(mapv :id (:cases %))
        enablement #(mapv (fn [test-case]
                            [(:id test-case) (:disabled test-case)])
                          (:cases %))
        parameters #(mapv (fn [test-case]
                            [(:id test-case) (:parameters test-case)])
                          (:cases %))
        rows #(mapv (fn [test-case]
                      [(:id test-case)
                       (junit/row-identities test-case)])
                    (:cases %))
        digest #(util/sha256-text
                 (#'test-suite/stable-text %))]
    (is (not= (digest left) (digest right))
        "absolute checkout paths reproduce the fresh-checkout digest drift")
    (is (= portable-left portable-right))
    (is (= (ids portable-left) (ids portable-right)))
    (is (= (enablement portable-left) (enablement portable-right)))
    (is (= (parameters portable-left) (parameters portable-right)))
    (is (= (rows portable-left) (rows portable-right)))
    (is (= (digest portable-left) (digest portable-right)))
    (is (= ["src/test/java/example/EnabledTest.java"
            "src/test/java/example/DisabledTest.java"]
           (mapv #(get-in % [:source :file]) (:cases portable-left))))))

(deftest behavior-differential-is-pinned-and-covers-the-required-families
  (let [contract (differential/read-contract workspace behavior-contract-file)
        canonical
        (paths/resolve-path
         workspace "targets/sqltrellis/validation/behavior-canonical.tsv")
        trace (differential/trace-summary contract canonical)]
    (is (= :sqltrellis-behavior (:id contract)))
    (is (= :sqltrellis (:target contract)))
    (is (= :core (:baseline-profile contract)))
    (is (= "sqltrellis" (get-in contract [:runner :profile])))
    (is (= 35 (get-in contract [:observation :expected-count])))
    (is (= ["ast" "deparse" "dialects" "errors" "features" "mutation"
            "parsing" "resources" "roundtrip" "validation" "visitors"]
           (:families trace)))
    (is (= 35 (:observations trace)))
    (is (= {:version "5.3" :revision revision}
           (select-keys
            (get-in (target-directory/read-target workspace :sqltrellis)
                    [:baseline :record :upstream])
            [:version :revision])))
    (doseq [relative
            ["targets/sqltrellis/validation/behavior-canonical.tsv"
             "targets/sqltrellis/validation/oracle/SqlTrellisBehaviorOracle.java"
             "targets/sqltrellis/validation/probe/SqlTrellisBehaviorPackageProbe.cs"]]
      (is (paths/regular-file? (paths/resolve-path workspace relative)) relative))))

(deftest clean-isolated-maven-generation-is-cache-independent
  (let [temporary (Files/createTempDirectory
                   "sqltrellis-isolated-generation-"
                   (make-array FileAttribute 0))
        source (copy-source-checkout! (paths/resolve-path temporary "source"))
        repository (paths/resolve-path temporary "repository")
        ordinary-before
        (util/sha256-file
         (paths/resolve-path source
                             "src/main/jjtree/net/sf/jsqlparser/parser/JSqlParserCC.jjt"))
        cold (discover-input source repository)
        cold-hashes (generated-hashes source cold)
        _ (harness/clean-directory! (paths/resolve-path source "target"))
        warm (discover-input source repository)
        warm-hashes (generated-hashes source warm)]
    (is (= 15 (count cold-hashes)))
    (is (= cold-hashes warm-hashes))
    (is (= ordinary-before
           (util/sha256-file
            (paths/resolve-path
             source "src/main/jjtree/net/sf/jsqlparser/parser/JSqlParserCC.jjt"))))
    (is (not-any? #(str/includes? (slurp (str %)) "Copyright")
                  (:generated-production-sources cold)))
    (is (not-any? #(str/includes? (:owner %) "license-maven-plugin")
                  (:generation-executions cold)))))
