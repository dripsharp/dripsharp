(ns dripsharp.linked-hash-map-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-project :as java-project]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private expected-observation
  (str "b=B,c=3,a=A"
       "|a=1,c=C,b=B"
       "|a=A,b=B,c=3,d=D/c=3,b=B,d=D,a=A"
       "|genericFactories=3,genericMissing=false,genericN=N,genericP=P,"
       "linkedFactories=3,linked=a=A,b=B,n=N,x=X"
       "|checks=3,entries=b=2,c=C"
       "|same=true,order=C,A,D,disposed=true,evicted=true"))

(defn- fixture-path []
  (paths/resolve-path (paths/workspace-root)
                      "test" "fixtures" "linked_hash_map"
                      "LinkedHashMapFixture.java"))

(defn- discovery [source]
  {:schema-version 1
   :project-id "linked-hash-map-fixture"
   :source-roots [(.getParent ^Path source)]
   :resource-roots []
   :production-sources [source]
   :generated-production-sources []
   :production-resources []
   :java-toolchain
   {:home (paths/absolute (System/getProperty "java.home"))
    :release 17
    :preview-features? false}
   :project-dependencies []
   :external-dependencies []
   :classpath-artifacts []})

(def ^:private configuration
  {:schema-version 1
   :product-family :java-library
   :destination-bundle 'dripsharp.java-library/rule-bundle
   :mechanical-source
   {:repository "https://example.invalid/upstream/linked-hash-map.git"
    :revision "3333333333333333333333333333333333333333"
    :notice-reference nil}
   :project {:assembly-name "DripSharp.LinkedHashMap.Fixture"
             :root-namespace "Fixture.LinkedHashMap"
             :target-framework "net8.0"
             :nullable "enable"
             :implicit-usings false
             :warnings-as-errors true}
   :package {:id "DripSharp.LinkedHashMap.Fixture"
             :version "1.0.0-task"
             :title "DripSharp LinkedHashMap Fixture"
             :description "Resolved Java compatibility fixture for LinkedHashMap."
             :authors "DripSharp"
             :tags "dripsharp java linkedhashmap fixture"
             :project-url "https://example.invalid/dripsharp"
             :repository-url "https://example.invalid/dripsharp.git"
             :repository-type "git"}
   :output {:project-directory "linked-hash-map-fixture"
            :source-directory "src"
            :resource-directory "resources"
            :project-file "Fixture.csproj"
            :source-map-file "source-map.edn"
            :diagnostics-file "diagnostics.edn"
            :manifest-file "generation-manifest.edn"
            :public-metadata-file "public-metadata.edn"
            :annotation-decisions-file "annotation-decisions.edn"}
   :namespaces {"fixture.linkedhashmap" "Fixture.LinkedHashMap"}
   :namespace-prefixes {"fixture.linkedhashmap" "Fixture.LinkedHashMap"}
   :destination-capabilities #{:java-compat :java-regex-unicode}
   :public-surface {:strategy 'dripsharp.java-library/public-surface-strategy}
   :resources {}})

(defn- write-string! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(defn- run-command [directory & command]
  (:output (process/run! {:directory directory :command command})))

(defn- run-java! [^Path root source]
  (let [classes (paths/resolve-path root "java-classes")]
    (Files/createDirectories classes (make-array FileAttribute 0))
    (run-command root "javac" "--release" "17" "-Xlint:all" "-Werror"
                 "-d" classes source)
    (str/trim
     (run-command root "java" "-cp" classes
                  "fixture.linkedhashmap.LinkedHashMapFixture"))))

(defn- consumer-project [target-framework]
  (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
       "  <PropertyGroup>\n"
       "    <OutputType>Exe</OutputType>\n"
       "    <TargetFramework>" target-framework "</TargetFramework>\n"
       "    <Nullable>enable</Nullable>\n"
       "    <ImplicitUsings>disable</ImplicitUsings>\n"
       "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
       "  </PropertyGroup>\n"
       "  <ItemGroup>\n"
       "    <PackageReference Include=\"DripSharp.LinkedHashMap.Fixture\" "
       "Version=\"1.0.0-task\" />\n"
       "  </ItemGroup>\n"
       "</Project>\n"))

(defn- run-package-consumer! [^Path root emission]
  (let [runtime-output (run-command root "dotnet" "--list-runtimes")
        runtime-majors (keep (fn [line]
                               (when-let [[_ major]
                                          (re-find #"^Microsoft\.NETCore\.App (\d+)\."
                                                   line)]
                                 (parse-long major)))
                             (str/split-lines runtime-output))
        target-framework (str "net" (apply max runtime-majors) ".0")
        feed (paths/resolve-path root "feed")
        packages (paths/resolve-path root "packages")
        consumer (paths/resolve-path root "consumer")
        project (write-string! (paths/resolve-path consumer "Consumer.csproj")
                               (consumer-project target-framework))
        _ (write-string!
           (paths/resolve-path consumer "Program.cs")
           (str "public static class Program\n{\n"
                "  public static void Main() => global::System.Console.Write(\n"
                "    global::Fixture.LinkedHashMap.LinkedHashMapFixture.observeAll());\n"
                "}\n"))]
    (Files/createDirectories feed (make-array FileAttribute 0))
    (Files/createDirectories packages (make-array FileAttribute 0))
    (run-command root "dotnet" "pack" (:project-file emission) "--nologo"
                 "--configuration" "Release" "--output" feed
                 "--verbosity:quiet" "-warnaserror")
    (run-command root "dotnet" "restore" project "--nologo"
                 "--packages" packages "--source" feed
                 "--ignore-failed-sources" "--verbosity:quiet")
    (run-command root "dotnet" "build" project "--nologo"
                 "--configuration" "Release" "--no-restore"
                 "--verbosity:quiet" "-warnaserror")
    (str/trim
     (run-command root "dotnet"
                  (paths/resolve-path consumer "bin" "Release" target-framework
                                      "Consumer.dll")))))

(defn- equivalent! [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "LinkedHashMap Java/.NET differential mismatch"
                    {:kind :linked-hash-map-differential-mismatch
                     :expected expected
                     :actual actual})))
  actual)

(deftest resolved-linked-hash-map-semantics-match-java-through-a-package-only-consumer
  (let [workspace (paths/workspace-root)
        source (fixture-path)
        root (Files/createTempDirectory "dripsharp-linked-hash-map"
                                        (make-array FileAttribute 0))
        discovery (discovery source)
        resolved (spoon/build-resolved-model! workspace discovery)
        emission (concurrency/call-with-executor
                  {:worker-count 2}
                  #(java-project/emit-project!
                    {:workspace-root workspace
                     :target root
                     :project-input discovery
                     :resolved-model resolved
                     :configuration configuration
                     :rule-bundle (java-library/rule-bundle)}))
        java-output (run-java! root source)
        package-output (run-package-consumer! root emission)
        summary (:summary emission)
        coverage (:executable-coverage summary)
        manifest (edn/read-string (slurp (str (:manifest-file emission))))
        generated (slurp (str (paths/resolve-path
                               (:project-root emission) "src" "Fixture"
                               "LinkedHashMap" "LinkedHashMapFixture.cs")))
        executor-source (slurp (str (paths/resolve-path
                                     workspace "research" "pkl" "pkl-core"
                                     "src" "main" "java" "org" "pkl" "core"
                                     "service" "ExecutorSpiImpl.java")))]
    (testing "the live frontend resolves every fixture symbol"
      (is (= 0 (get-in resolved [:totals :shadow-symbols])))
      (is (= 0 (get-in resolved [:totals :unresolved-symbols])))
      (is (= 0 (get-in resolved [:totals :ambiguous-symbols]))))
    (testing "emission is complete and the package-only differential is exact"
      (is (= 0 (:hard-failures summary)))
      (is (= 0 (:missing-source-mappings summary)))
      (is (= 0 (:skipped-source-units summary)))
      (is (= 0 (:collisions summary)))
      (is (= (:visited coverage) (:covered coverage)))
      (is (= {:blocked 0
              :unsupported-elements 0
              :missing-mappings 0
              :missing-occurrences 0
              :fallback 0}
             (select-keys coverage [:blocked :unsupported-elements
                                    :missing-mappings :missing-occurrences
                                    :fallback])))
      (is (= summary (:summary manifest)))
      (is (= expected-observation java-output))
      (is (= java-output (equivalent! java-output package-output)))
      (is (= :linked-hash-map-differential-mismatch
             (:kind
              (ex-data
               (try
                 (equivalent! java-output (str package-output "-perturbed"))
                 nil
                 (catch clojure.lang.ExceptionInfo error error)))))))
    (testing "the resolved fixture exercises helper dispatch and subclass eviction"
      (doseq [helper ["JavaCompat.MapGet("
                      "JavaCompat.MapGetOrDefault("
                      "JavaCompat.MapPut("
                      "JavaCompat.MapPutAll("
                      "JavaCompat.MapPutIfAbsent("
                      "JavaCompat.ComputeIfAbsent("
                      "JavaCompat.MapRemove("
                      "JavaCompat.MapEntrySet("]]
        (is (str/includes? generated helper) (str "missing resolved helper " helper)))
      (is (str/includes? generated "RemoveEldestEntry"))
      (is (str/includes? generated
                         "new global::DripSharp.Runtime.JavaLinkedHashMap<string, string>(8, 0.75F, true)")))
    (testing "the ExecutorSpi observation stays tied to the exact Pkl cache design"
      (is (str/includes? executor-source "private static final int MAX_HTTP_CLIENTS = 3;"))
      (is (str/includes? executor-source
                         "new LinkedHashMap<HttpClientKey, HttpClient>(8, 0.75f, true)"))
      (is (str/includes? executor-source "httpClients.computeIfAbsent("))
      (is (str/ends-with? package-output
                          "same=true,order=C,A,D,disposed=true,evicted=true")))))
