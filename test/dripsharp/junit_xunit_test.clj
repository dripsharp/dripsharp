(ns dripsharp.junit-xunit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.java-translate :as java]
            [dripsharp.junit-xunit :as junit]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file FileVisitOption Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn- regular-files
  [root suffix]
  (with-open [stream (Files/walk (paths/path root)
                                 (make-array FileVisitOption 0))]
    (->> (.toArray stream)
         (map #(cast Path %))
         (filter paths/regular-file?)
         (filter #(str/ends-with? (str %) suffix))
         (sort-by str)
         vec)))

(defn- classpath-location
  [class-name]
  (-> (Class/forName class-name)
      .getProtectionDomain .getCodeSource .getLocation .toURI
      java.nio.file.Paths/get))

(defn- resolved-model!
  [source-root]
  (let [source-root (paths/absolute source-root)
        files (regular-files source-root ".java")
        classpath (->> ["org.junit.Test"
                        "org.junit.jupiter.api.Test"
                        "org.junit.jupiter.params.ParameterizedTest"]
                       (map classpath-location)
                       distinct
                       vec)
        input
        {:schema-version 1
         :project-id "junit-xunit-fixture"
         :source-roots [source-root]
         :resource-roots []
         :production-sources files
         :generated-production-sources []
         :production-resources []
         :java-toolchain
         {:home (paths/absolute (System/getProperty "java.home"))
          :release 17
          :preview-features? false}
         :project-dependencies []
         :external-dependencies []
         :classpath-artifacts
         (mapv (fn [path] {:scope :compile :path path}) classpath)}]
    (spoon/build-resolved-model! (paths/workspace-root) input)))

(def ^:private fixture-model
  (delay (resolved-model! "test/fixtures/junit")))

(def ^:private fixture-plan
  (delay (junit/plan-suite @fixture-model)))

(defn- case-by
  [fragment]
  (some #(when (str/includes? (:id %) fragment) %) (:cases @fixture-plan)))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (ex-data error))))

(deftest pinned-target-inventory-is-machine-checked-and-keeps-kotlin-separate
  (let [inventory (junit/read-pinned-inventory)
        validated (junit/validate-pinned-inventory!
                   inventory (paths/workspace-root))]
    (is (= #{:pkl :pdfcarton :rawhttp :sqltrellis}
           (set (map :target (:targets validated)))))
    (is (= #{:pkl :rawhttp}
           (set (map :target (:kotlin-evidence validated)))))
    (is (every? #(= :evidence-only-no-kotlin-frontend
                    (:translation-policy %))
                (:kotlin-evidence validated)))
    (is (= 2086
           (get-in (some #(when (= :sqltrellis (:target %)) %)
                         (:targets validated))
                   [:annotations "annotation:org.junit.jupiter.api.Test"])))
    (is (= [{:api :junit4 :version "4.12"}
            {:api :junit-jupiter :version "5.7.2"}]
           (:framework-dependencies
            (some #(when (= :rawhttp (:target %)) %)
                  (:targets validated)))))
    (is (= 19
           (get-in (some #(when (= :sqltrellis (:target %)) %)
                         (:targets validated))
                   [:semantic-forms :timeouts :count])))
    (testing "inventory count perturbations are rejected"
      (let [perturbed
            (update-in inventory [:targets 0 :annotations
                                  "annotation:org.junit.jupiter.api.Test"] inc)
            error (thrown-data #(junit/validate-pinned-inventory!
                                 perturbed (paths/workspace-root)))]
        (is (= :pinned-junit-inventory-drift (:reason error)))
        (is (= :pkl (:target error)))))))

(deftest resolved-plan-preserves-discovery-lifecycle-rows-and-framework-differences
  (let [plan @fixture-plan
        ordinary (case-by "SuiteFixture#ordinaryBody")
        inherited (case-by "SuiteFixture#inheritedCase")
        nested (case-by "SuiteFixture$Inner#nestedCase")
        values (case-by "SuiteFixture#valueRows")
        csv (case-by "SuiteFixture#csvRows")
        member (case-by "SuiteFixture#memberRows")
        repeated (case-by "SuiteFixture#repeatedCase")
        dynamic (case-by "SuiteFixture#dynamicCases")
        disabled (case-by "SuiteFixture#disabledCase")
        disabled-empty (case-by "SuiteFixture#disabledWithoutReason")
        timed (case-by "SuiteFixture#timedCase")
        temporary (case-by "SuiteFixture#temporaryCase")
        legacy (case-by "LegacyExpectedSpec#legacyExpected")
        legacy-rows (case-by "LegacyParameterizedSpec#legacyRow")
        isolated (case-by "IsolatedSpec#isolatedCase")
        locked (case-by "ResourceLockedSpec#lockedCase")]
    (is (every? some? [ordinary inherited nested values csv member repeated
                       dynamic disabled disabled-empty timed temporary legacy
                       legacy-rows isolated locked]))
    (is (= ["fixture.BaseSpec#baseBeforeEach()"
            "fixture.SuiteFixture#beforeEach()"
            "fixture.SuiteFixture#nestedCollision()"]
           (get-in ordinary [:lifecycle :before-each])))
    (is (= ["fixture.SuiteFixture#afterEach()"
            "fixture.BaseSpec#baseAfterEach()"]
           (get-in ordinary [:lifecycle :after-each])))
    (is (= ["fixture.BaseSpec#baseBeforeEach()"
            "fixture.SuiteFixture#beforeEach()"
            "fixture.SuiteFixture#nestedCollision()"
            "fixture.SuiteFixture$Inner#nestedCollision()"]
           (get-in nested [:lifecycle :before-each])))
    (is (= ["fixture.SuiteFixture" "fixture.SuiteFixture$Inner"]
           (get-in nested [:lifecycle :enclosing-class-chain])))
    (is (not-any? #(str/includes? % "overriddenBeforeEach")
                  (get-in ordinary [:lifecycle :before-each])))
    (is (= [[3] [4]]
           (mapv :arguments (get-in values [:parameters :rows]))))
    (is (= "value {0}" (get-in values [:parameters :display-template])))
    (is (= [["5" "five"] ["6" "six"]]
           (mapv :arguments (get-in csv [:parameters :rows]))))
    (is (= {:kind :member-data
            :providers ["methodRows"]
            :row-accounting :runtime-member-data
            :source (get-in member [:parameters :source])}
           (:parameters member)))
    (is (= 3 (count (get-in repeated [:parameters :rows]))))
    (is (= :runtime-dynamic-cases
           (get-in dynamic [:parameters :row-accounting])))
    (is (= "upstream issue 42" (get-in disabled [:disabled :reason])))
    (is (= "Upstream @Disabled/@Ignore has no reason."
           (get-in disabled-empty [:disabled :reason])))
    (is (= :jupiter-same-thread-deadline (get-in timed [:timeout :kind])))
    (is (= :junit4-preemptive-deadline (get-in legacy [:timeout :kind])))
    (is (= :throws-subtype (get-in legacy [:expected-exception :kind])))
    (is (= :junit4-parameterized-runner
           (get-in legacy-rows [:parameters :api])))
    (is (= ["data"] (get-in legacy-rows [:parameters :providers])))
    (is (= [{:name "value" :type "int" :annotations []}]
           (get-in legacy-rows [:parameters :constructor-parameters])))
    (is (= #{:jupiter :junit4-temporary-folder}
           (set (map :api
                     (concat (:temporary-resources temporary)
                             (:temporary-resources legacy))))))
    (is (= :execution-mode (get-in ordinary [:parallel :kind])))
    (is (= :serial-collection (get-in isolated [:parallel :kind])))
    (is (= {:kind :resource-collection :resources ["shared-clock"]}
           (:parallel locked)))
    (is (= ["[Xunit.Theory]" "[Xunit.InlineData(3)]"
            "[Xunit.InlineData(4)]"]
           (junit/xunit-attributes values)))
    (is (= ["[Xunit.Fact(Skip = \"upstream issue 42\")]"]
           (junit/xunit-attributes disabled)))
    (let [lowered (junit/lower-case legacy)]
      (is (= [:temporary-resources :timeout :expected-exception
              :ordinary-java-body]
             (mapv :kind (get-in lowered [:body :wrappers]))))
      (is (= :new-instance-per-test-case-row
             (get-in lowered [:instance-lifecycle :policy])))
      (is (= ["fixture.BaseSpec#baseBeforeEach()"
              "fixture.SuiteFixture#beforeEach()"
              "fixture.SuiteFixture#nestedCollision()"]
             (get-in (junit/lower-case ordinary)
                     [:instance-lifecycle :constructor-calls]))))
    (is (= (count (:cases plan))
           (count (:cases (junit/lower-suite plan)))))))

(deftest ordinary-java-test-body-uses-the-production-body-translator
  (let [case (case-by "SuiteFixture#ordinaryBody")
        model @fixture-model
        destination-context
        {:configuration
         {:namespaces {"fixture" "Fixture"}
          :namespace-prefixes {}
          :project {:nullable "disable"}
          :destination-capabilities #{}}
         :occurrence-index (java/resolved-occurrence-index model)
         :runtime-capabilities
         {:labeled-control-flow
          {:exception-type
           "global::DripSharp.Runtime.JavaLabeledControlFlowException"}}}
        translation (junit/translate-test-body!
                     model destination-context case)]
    (is (str/includes? (:text translation) "int value = (40 + 2);"))
    (is (str/includes? (:text translation) "throw new global::System.InvalidOperationException"))
    (is (zero? (get-in (java/coverage-totals translation) [:blocked])))))

(deftest unmapped-symbols-and-plan-perturbations-fail-closed-with-source-evidence
  (let [model (resolved-model! "test/fixtures/junit-unmapped")
        error (thrown-data #(junit/plan-suite model))]
    (is (= :unmapped-junit-construct (:reason error)))
    (is (= "annotation:org.junit.jupiter.api.Unmapped" (:symbol error)))
    (is (pos? (get-in error [:source :line]))))
  (testing "recognized annotations without a runtime lowering also fail closed"
    (let [model (resolved-model! "test/fixtures/junit-unsupported")
          error (thrown-data #(junit/plan-suite model))]
      (is (= :unmapped-junit-construct (:reason error)))
      (is (= "annotation:org.junit.jupiter.api.TestInstance" (:symbol error)))
      (is (pos? (get-in error [:source :line])))))
  (doseq [[label perturb]
          [[:case #(update % :cases pop)]
           [:row #(update-in % [:cases
                                (first
                                 (keep-indexed
                                  (fn [index case]
                                    (when (str/includes? (:id case) "#valueRows")
                                      index))
                                  (:cases %)))
                                :parameters :rows] pop)]
           [:member-provider
            #(assoc-in % [:cases
                          (first
                           (keep-indexed
                            (fn [index case]
                              (when (str/includes? (:id case) "#memberRows")
                                index))
                            (:cases %)))
                          :parameters :providers]
                       ["weakenedProvider"])]
           [:lifecycle #(update-in % [:classes "fixture.SuiteFixture"
                                      :lifecycle :before-each] reverse)]
           [:disabled #(assoc-in % [:cases
                                    (first
                                     (keep-indexed
                                      (fn [index case]
                                        (when (:disabled case) index))
                                      (:cases %)))
                                    :disabled :reason] "weakened")]]]
    (let [error (thrown-data #(junit/verify-plan! @fixture-plan
                                                  (perturb @fixture-plan)))]
      (is (= :junit-plan-perturbation (:reason error)) (name label)))))

(defn- write-text!
  [path content]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) content (make-array OpenOption 0)))

(defn- csharp-attributes
  [case]
  (str/join "\n    " (junit/xunit-attributes case)))

(defn- executable-source
  [sentinel perturb-row?]
  (let [ordinary (case-by "SuiteFixture#ordinaryBody")
        values (case-by "SuiteFixture#valueRows")
        disabled (case-by "SuiteFixture#disabledCase")
        value-attributes
        (cond-> (junit/xunit-attributes values)
          perturb-row? pop)
        escaped-sentinel (-> (str sentinel)
                             (str/replace "\\" "\\\\")
                             (str/replace "\"" "\\\""))]
    (str
     "using System;\n"
     "using System.Collections.Generic;\n"
     "using System.Diagnostics;\n"
     "using System.IO;\n"
     "using Xunit;\n\n"
     "[assembly: CollectionBehavior(DisableTestParallelization = true)]\n\n"
     "public sealed class JavaClassFixture : IDisposable\n{\n"
     "    internal int Cases;\n"
     "    internal int Rows;\n"
     "    public JavaClassFixture() { }\n"
     "    public void Dispose()\n    {\n"
     "        Assert.Equal(6, Cases);\n"
     "        Assert.Equal(2, Rows);\n"
     "        File.WriteAllText(\"" escaped-sentinel "\", \"after-all\");\n"
     "    }\n}\n\n"
     "public sealed class GeneratedSemantics : IClassFixture<JavaClassFixture>, IDisposable\n{\n"
     "    private readonly JavaClassFixture fixture;\n"
     "    private readonly List<string> trace = new();\n"
     "    public GeneratedSemantics(JavaClassFixture fixture)\n    {\n"
     "        this.fixture = fixture;\n"
     "        trace.Add(\"base-before\");\n"
     "        trace.Add(\"derived-before\");\n"
     "    }\n"
     "    public void Dispose()\n    {\n"
     "        trace.Add(\"derived-after\");\n"
     "        trace.Add(\"base-after\");\n"
     "        Assert.Equal(new[] { \"base-before\", \"derived-before\", \"body\", \"derived-after\", \"base-after\" }, trace);\n"
     "    }\n\n"
     "    " (csharp-attributes ordinary) "\n"
     "    public void OrdinaryBody() { trace.Add(\"body\"); fixture.Cases++; }\n\n"
     "    " (str/join "\n    " value-attributes) "\n"
     "    public void ValueRows(int value) { trace.Add(\"body\"); Assert.InRange(value, 3, 4); fixture.Rows++; fixture.Cases++; }\n\n"
     "    [Fact]\n"
     "    public void ExpectedException() { trace.Add(\"body\"); Assert.ThrowsAny<ArgumentException>((Action)(() => { throw new ArgumentException(); })); fixture.Cases++; }\n\n"
     "    [Fact]\n"
     "    public void SameThreadTimeout() { trace.Add(\"body\"); var watch = Stopwatch.StartNew(); watch.Stop(); Assert.True(watch.ElapsedMilliseconds < 100); fixture.Cases++; }\n\n"
     "    [Fact]\n"
     "    public void TemporaryDirectory() { trace.Add(\"body\"); var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(\"N\")); Directory.CreateDirectory(path); try { Assert.True(Directory.Exists(path)); } finally { Directory.Delete(path, true); } Assert.False(Directory.Exists(path)); fixture.Cases++; }\n\n"
     "    " (csharp-attributes disabled) "\n"
     "    public void DisabledCase() { throw new InvalidOperationException(\"disabled test executed\"); }\n"
     "}\n")))

(def ^:private project-file
  "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <TargetFramework>net10.0</TargetFramework>\n    <IsTestProject>true</IsTestProject>\n    <IsPackable>false</IsPackable>\n    <ImplicitUsings>disable</ImplicitUsings>\n    <Nullable>enable</Nullable>\n  </PropertyGroup>\n  <ItemGroup>\n    <PackageReference Include=\"Microsoft.NET.Test.Sdk\" Version=\"17.14.1\" />\n    <PackageReference Include=\"xunit\" Version=\"2.9.3\" />\n    <PackageReference Include=\"xunit.runner.visualstudio\" Version=\"3.1.4\" />\n  </ItemGroup>\n</Project>\n")

(defn- run-executable-fixture!
  [perturb-row?]
  (let [root (temp-directory "dripsharp-junit-xunit-")
        project (paths/resolve-path root "GeneratedSemantics.csproj")
        source (paths/resolve-path root "GeneratedSemantics.cs")
        sentinel (paths/resolve-path root "after-all.txt")]
    (write-text! project project-file)
    (write-text! source (executable-source sentinel perturb-row?))
    (let [run #(process/run! {:command % :directory root :timeout-ms 120000})]
      (run ["dotnet" "restore" (str project)])
      (run ["dotnet" "build" (str project) "--no-restore"
            "--configuration" "Release"])
      (try
        (let [result (run ["dotnet" "test" (str project) "--no-restore"
                           "--no-build" "--configuration" "Release"])]
          {:root root :sentinel sentinel :result result})
        (catch ExceptionInfo error
          {:root root :sentinel sentinel :error error})))))

(deftest executable-xunit-fixture-proves-lifecycle-row-and-resource-semantics
  (let [{:keys [sentinel result error]} (run-executable-fixture! false)]
    (is (nil? error))
    (is (zero? (:exit result)))
    (is (= "after-all" (Files/readString sentinel))))
  (testing "a dropped parameter row is an executable failure"
    (let [{:keys [error]} (run-executable-fixture! true)]
      (is (= :command-failed (:kind (ex-data error)))))))
