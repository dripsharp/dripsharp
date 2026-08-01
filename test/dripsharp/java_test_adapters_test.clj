(ns dripsharp.java-test-adapters-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-test-adapters :as adapters]
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
        classpath
        (->> ["org.junit.Test"
              "org.junit.jupiter.api.Test"
              "org.assertj.core.api.Assertions"
              "org.hamcrest.MatcherAssert"
              "org.h2.jdbcx.JdbcDataSource"
              "org.mockito.Mockito"
              "org.mockito.junit.jupiter.MockitoExtension"]
             (map classpath-location)
             distinct
             vec)
        input
        {:schema-version 1
         :project-id "java-test-adapter-fixture"
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
  (delay (resolved-model! "test/fixtures/java-test-adapters")))

(def ^:private fixture-plan
  (delay (junit/plan-suite @fixture-model (adapters/junit-plan-options))))

(defn- case-by
  [plan fragment]
  (some #(when (str/includes? (:id %) fragment) %) (:cases plan)))

(defn- destination-context
  ([] (destination-context nil))
  ([target-strategy]
   (cond->
    {:configuration
     {:namespaces {"fixture" "Fixture"
                   "org.h2.jdbcx" "Fixture.H2"}
      :namespace-prefixes {}
      :project {:nullable "disable"}
      :destination-capabilities #{}}
     :occurrence-index (java/resolved-occurrence-index @fixture-model)
     :runtime-capabilities
     {:labeled-control-flow
      {:exception-type
       "global::DripSharp.Runtime.JavaLabeledControlFlowException"}}}
     target-strategy
     (assoc :target-test-facility-strategy target-strategy
            :destination-type-mappings
            {"org.h2.jdbcx.JdbcDataSource"
             ["global::Fixture.TargetH2" :test.target/h2]}))))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (ex-data error))))

(deftest pinned-inventory-classifies-source-language-and-reuse-boundary
  (let [inventory (adapters/read-pinned-inventory)
        validated (adapters/validate-pinned-inventory! inventory)
        frameworks (into {} (map (juxt :framework identity))
                         (:frameworks validated))
        facilities (into {} (map (juxt :facility identity))
                         (:target-facilities validated))]
    (is (contains? (get-in frameworks [:assertj :used-operations])
                   "allSatisfy"))
    (is (= #{"given" "mock" "should" "spy" "then" "will" "willReturn"}
           (get-in frameworks [:mockito :used-operations])))
    (is (= :target-strategy (get-in facilities [:h2 :reuse-boundary])))
    (is (= :target-strategy (get-in facilities [:wiremock :reuse-boundary])))
    (is (= :target-strategy (get-in facilities [:jimfs :reuse-boundary])))
    (is (= #{:kotlin} (get-in facilities [:wiremock :source-languages])))
    (is (= #{:kotlin} (get-in facilities [:jimfs :source-languages])))
    (is (= #{:kotlin} (get-in facilities [:kotest :source-languages])))
    (is (= :evidence-only-no-kotlin-frontend
           (get-in facilities [:wiremock :kotlin-policy])))
    (is (= :evidence-only-no-kotlin-frontend
           (get-in facilities [:jimfs :kotlin-policy])))
    (is (= :evidence-only-no-kotlin-frontend
           (get-in facilities [:kotest :reuse-boundary])))
    (is (= #{{:class "org.mockito.junit.jupiter.MockitoExtension"}}
           (:extension-adapters (adapters/junit-plan-options))))
    (testing "classification drift is rejected"
      (let [perturbed (assoc-in inventory
                                [:target-facilities 0 :reuse-boundary]
                                :shared-resolved-xunit-adapter)
            error (thrown-data #(adapters/validate-pinned-inventory!
                                 perturbed))]
        (is (= :java-test-facility-classification-drift (:reason error)))
        (is (= :h2 (:facility error)))))
    (testing "framework target drift is rejected"
      (let [perturbed (assoc-in inventory [:frameworks 3 :targets] #{:pkl})
            error (thrown-data #(adapters/validate-pinned-inventory!
                                 perturbed))]
        (is (= :java-test-framework-classification-drift (:reason error)))
        (is (= :hamcrest (:framework error)))))))

(deftest mockito-field-fixtures-are-explicit-and-drop-checked
  (let [test-case (case-by @fixture-plan "#frameworkSemantics")
        fields (get-in test-case [:mockito-fixture :fields])
        lowered (junit/lower-case test-case)]
    (is (= [{:field-id "fixture.JavaTestAdapterFixture#annotatedClock"
             :field "annotatedClock"
             :type "fixture.Clock"
             :initializer :java-mockito/mock}]
           (mapv #(select-keys % [:field-id :field :type :initializer])
                 fields)))
    (is (= fields (get-in lowered [:instance-lifecycle :field-initializers])))
    (let [perturbed (update-in @fixture-plan
                               [:classes "fixture.JavaTestAdapterFixture"]
                               dissoc :mockito-fixture)
          error (thrown-data #(junit/verify-plan! @fixture-plan perturbed))]
      (is (= :junit-plan-perturbation (:reason error)))
      (is (= :mockito-fixtures (:section error))))))

(deftest unsupported-mock-field-annotations-fail-with-resolved-identity
  (let [model (resolved-model!
               "test/fixtures/java-test-adapters-mock-unsupported")
        error (thrown-data #(junit/plan-suite model))]
    (is (= :unsupported-java-test-mock-annotation (:reason error)))
    (is (= "annotation:org.mockito.InjectMocks" (:resolved-key error)))
    (is (= "unsupported" (:field error)))
    (is (pos? (get-in error [:source :line])))))

(deftest mock-fields-without-lifecycle-adapter-fail-closed
  (let [model (resolved-model!
               "test/fixtures/java-test-adapters-mock-no-extension")
        error (thrown-data #(junit/plan-suite model))]
    (is (= :mockito-mock-without-extension (:reason error)))
    (is (= "annotation:org.mockito.Mock" (:resolved-key error)))
    (is (= "org.mockito.junit.jupiter.MockitoExtension"
           (:required-extension error)))
    (is (pos? (get-in error [:source :line])))))

(deftest resolved-framework-calls-lower-with-messages-and-verification-intact
  (let [test-case (case-by @fixture-plan "#frameworkSemantics")
        translation (junit/translate-test-body!
                     @fixture-model (destination-context) test-case)
        text (:text translation)]
    (is (some? test-case))
    (is (str/includes?
         text
         "JavaAssertions.Equal((long)(2), (long)((1 + 1)), \"legacy message\")"))
    (is (str/includes?
         text
         "JavaAssertions.Equal(3, (1 + 2), \"jupiter message\")"))
    (is (str/includes? text "JavaAssertions.Throws<global::System.ArgumentException>"))
    (is (str/includes?
         text
         "JavaAssertions.DoesNotThrow(() => 4, \"does not throw message\")"))
    (is (str/includes? text "JavaAssertJ.That(new int[]"))
    (is (str/includes? text ".@As(\"ordered values\").ContainsExactly(1, 2)"))
    (is (str/includes? text
                       ".Extracting(((global::System.Func<string, object>)"))
    (is (str/includes? text
                       ".AllSatisfy(((global::System.Action<string>)"))
    (is (str/includes? text "JavaHamcrest.AssertThat(\"hamcrest reason\""))
    (is (str/includes? text "JavaHamcrest.AllOf("))
    (is (str/includes? text "JavaMockito.Mock<global::Fixture.Clock>()"))
    (is (str/includes? text "JavaMockito.Given(clock.tick()).WillReturn(7)"))
    (is (str/includes?
         text
         "JavaMockito.Verify(clock, global::DripSharp.Testing.JavaMockito.Times(1)).tick()"))
    (is (str/includes? text "JavaMockito.Then(clock).Should().tick()"))
    (is (str/includes? text "JavaMockito.Will(answer).Given(clock).tick()"))
    (is (str/includes?
         text
         "JavaMockito.Spy<global::Fixture.RealClock>(new global::Fixture.RealClock())"))
    (is (zero? (get-in (java/coverage-totals translation) [:blocked])))))

(deftest target-facilities-require-and-receive-an-explicit-strategy
  (let [test-case (case-by @fixture-plan "#targetFacility")
        missing (thrown-data #(junit/translate-test-body!
                               @fixture-model (destination-context) test-case))
        declined (thrown-data #(-> (junit/translate-test-body!
                                    @fixture-model
                                    (destination-context (constantly nil))
                                    test-case)
                                   :text))
        calls (atom [])
        strategy
        (fn [{:keys [facility occurrence target-node arguments]}]
          (swap! calls conj [facility (:key occurrence)])
          (if (str/includes? (:key occurrence) "#<init>")
            (csharp/raw "new global::Fixture.TargetH2()")
            (csharp/invocation (csharp/member target-node "SetURL") arguments)))
        translation
        (junit/translate-test-body!
         @fixture-model (destination-context strategy) test-case)]
    (is (= :unmapped-target-test-facility (:reason missing)))
    (is (= :h2 (:facility missing)))
    (is (str/includes? (:resolved-key missing) "org.h2.jdbcx.JdbcDataSource"))
    (is (= :java-translation-coverage-failed (:kind declined)))
    (is (some #(str/includes? (:message %)
                              "Target Java test-facility strategy declined")
              (:diagnostics declined)))
    (is (= 2 (count @calls)))
    (is (every? #(= :h2 (first %)) @calls))
    (is (str/includes? (:text translation)
                       "new global::Fixture.TargetH2()"))
    (is (str/includes? (:text translation)
                       "global::Fixture.TargetH2 source"))
    (is (str/includes? (:text translation)
                       "source.SetURL(\"jdbc:h2:mem:test\")"))))

(deftest unsupported-resolved-assertion-fails-generation-with-identity
  (let [model (resolved-model! "test/fixtures/java-test-adapters-unsupported")
        plan (junit/plan-suite model)
        test-case (case-by plan "#unsupportedAssertion")
        context (assoc (destination-context)
                       :occurrence-index (java/resolved-occurrence-index model))
        error (thrown-data #(junit/translate-test-body!
                             model context test-case))]
    (is (= :unsupported-java-test-call (:reason error)))
    (is (= :assertj (:framework error)))
    (is (= "hasSameHashCodeAs" (:operation error)))
    (is (str/starts-with? (:resolved-key error)
                          "executable:org.assertj.core.api."))
    (is (pos? (get-in error [:source :line])))))

(defn- write-text!
  [path content]
  (Files/createDirectories (.getParent (paths/path path))
                           (make-array FileAttribute 0))
  (Files/writeString (paths/path path) content (make-array OpenOption 0)))

(def ^:private project-file
  "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n    <TargetFramework>net10.0</TargetFramework>\n    <IsTestProject>true</IsTestProject>\n    <IsPackable>false</IsPackable>\n    <ImplicitUsings>disable</ImplicitUsings>\n    <Nullable>enable</Nullable>\n  </PropertyGroup>\n  <ItemGroup>\n    <PackageReference Include=\"Microsoft.NET.Test.Sdk\" Version=\"17.14.1\" />\n    <PackageReference Include=\"xunit\" Version=\"2.9.3\" />\n    <PackageReference Include=\"xunit.runner.visualstudio\" Version=\"3.1.4\" />\n    <PackageReference Include=\"Castle.Core\" Version=\"5.1.1\" />\n  </ItemGroup>\n</Project>\n")

(def ^:private executable-source
  "using System;\nusing DripSharp.Testing;\nusing Xunit;\nusing Xunit.Sdk;\n\npublic interface IClock\n{\n    int Tick();\n}\n\npublic class RealClock : IClock\n{\n    public virtual int Tick() => 5;\n}\n\npublic class VirtualClock\n{\n    public VirtualClock(int ignored) { throw new InvalidOperationException(\"constructor must not run\"); }\n    public virtual int Tick() => -1;\n}\n\npublic sealed class Person\n{\n    public Person(string name) { Name = name; }\n    public string Name { get; }\n}\n\npublic sealed class ChildArgumentException : ArgumentException { }\n\npublic sealed class JavaTestSupportEquivalence\n{\n    [Fact]\n    public void AssertionsPreserveComparisonExceptionAndMessages()\n    {\n        JavaAssertions.Equal(new[] { 1, 2 }, new[] { 1, 2 }, null);\n        JavaAssertions.Equal(2.0, 2.05, null, 0.1);\n        var thrown = JavaAssertions.Throws<ArgumentException>(() => throw new ArgumentException(\"boom\"), null);\n        Assert.Equal(\"boom\", thrown.Message);\n        var exactFailure = Assert.IsAssignableFrom<XunitException>(Record.Exception(() =>\n            JavaAssertions.ThrowsExactly<ArgumentException>(() => throw new ChildArgumentException(), \"exact type\")));\n        Assert.Contains(\"exact type\", exactFailure.Message);\n        var failure = Assert.IsAssignableFrom<XunitException>(Record.Exception(() => JavaAssertions.Equal(1, 2, \"preserved message\")));\n        Assert.Contains(\"preserved message\", failure.Message);\n    }\n\n    [Fact]\n    public void AssertJAndHamcrestPreserveFluentAndMatcherFailures()\n    {\n        JavaAssertJ.That(new[] { 1, 2 }).As(\"ordered values\").ContainsExactly(1, 2);\n        JavaAssertJ.That(new[] { 1, 2 }).ContainsExactlyInAnyOrder(2, 1);\n        JavaAssertJ.That(new[] { new Person(\"a\"), new Person(\"b\") })\n            .Extracting(new Func<object?, object?>(value => ((Person)value!).Name))\n            .ContainsExactly(\"a\", \"b\");\n        JavaAssertJ.That(new Person(\"name\")).HasFieldOrPropertyWithValue(\"Name\", \"name\");\n        JavaAssertJ.That(42).AsString().IsEqualTo(\"42\");\n        JavaAssertJ.ThrownBy(() => throw new InvalidOperationException(\"problem\", new ArgumentException(\"root\")))\n            .HasRootCauseInstanceOf(typeof(ArgumentException)).RootCause().HasMessageContaining(\"root\");\n        JavaHamcrest.AssertThat(\"reason\", \"abc\",\n            JavaHamcrest.AllOf(JavaHamcrest.StartsWith(\"a\"), JavaHamcrest.EndsWith(\"c\")));\n        var failure = Assert.IsAssignableFrom<XunitException>(Record.Exception(() =>\n            JavaHamcrest.AssertThat(\"matcher message\", \"abc\", JavaHamcrest.StartsWith(\"z\"))));\n        Assert.Contains(\"matcher message\", failure.Message);\n    }\n\n    [Fact]\n    public void MockitoStubbingAndVerificationWorkForInterfacesAndClasses()\n    {\n        var clock = JavaMockito.Mock<IClock>();\n        JavaMockito.Given(clock.Tick()).WillReturn(7);\n        Assert.Equal(7, clock.Tick());\n        Assert.Equal(7, clock.Tick());\n        JavaMockito.Then(clock).Should(JavaMockito.Times(2)).Tick();\n        JavaMockito.VerifyNoMoreInteractions(clock);\n\n        JavaAnswer<int> answer = invocation => 9;\n        JavaMockito.Will(answer).Given(clock).Tick();\n        Assert.Equal(9, clock.Tick());\n        JavaMockito.Then(clock).Should(JavaMockito.Times(3)).Tick();\n\n        var spy = JavaMockito.Spy(new RealClock());\n        Assert.Equal(5, spy.Tick());\n        JavaMockito.Then(spy).Should().Tick();\n\n        var concrete = JavaMockito.Mock<VirtualClock>();\n        JavaMockito.Given(concrete.Tick()).ThenReturn(11);\n        Assert.Equal(11, concrete.Tick());\n        JavaMockito.Verify(concrete).Tick();\n\n        var failure = Assert.IsAssignableFrom<XunitException>(Record.Exception(() =>\n            JavaMockito.Verify(clock, JavaMockito.Never()).Tick()));\n        Assert.Contains(\"observed 3\", failure.Message);\n    }\n}\n")

(def ^:private extended-executable-source
  "using System;\nusing DripSharp.Testing;\nusing Xunit;\n\npublic sealed class JavaTestSupportAdditionalEquivalence\n{\n    [Fact]\n    public void AssertJCollectionAndExtractionOperationsPreserveSemantics()\n    {\n        JavaAssertJ.That(new[] { 1, 1 }).ContainsOnly(1);\n        JavaAssertJ.That(new[] { 1, 2 }).AllSatisfy(\n            new Action<object?>(value => JavaAssertions.True((int)value! > 0, null)));\n        JavaAssertJ.That(new Person(\"name\"))\n            .Extracting(new Func<object?, object?>(value => ((Person)value!).Name))\n            .IsEqualTo(\"name\");\n    }\n}\n")

(def ^:private delta-executable-source
  "using DripSharp.Testing;\nusing Xunit;\n\npublic sealed class JavaTestSupportDeltaEquivalence\n{\n    [Fact]\n    public void FloatingPointArraysUseElementwiseDelta()\n    {\n        JavaAssertions.Equal(new[] { 1.0, 2.0 }, new[] { 1.05, 1.95 }, null, 0.1);\n    }\n}\n")

(def ^:private collection-executable-source
  "using System.Collections.Generic;\nusing DripSharp.Testing;\nusing Xunit;\n\npublic sealed class JavaTestSupportCollectionEquivalence\n{\n    [Fact]\n    public void JavaSetEqualityDoesNotDependOnIterationOrder()\n    {\n        JavaAssertions.Equal(new HashSet<int> { 1, 2 }, new HashSet<int> { 2, 1 }, null);\n    }\n}\n")

(def ^:private mock-object-executable-source
  "using DripSharp.Testing;\nusing Xunit;\n\npublic sealed class JavaTestSupportMockObjectEquivalence\n{\n    [Fact]\n    public void MockObjectIdentityOperationsRemainStable()\n    {\n        var mock = JavaMockito.Mock<IClock>();\n        var other = JavaMockito.Mock<IClock>();\n        Assert.True(mock.Equals(mock));\n        Assert.False(mock.Equals(other));\n        Assert.Equal(mock.GetHashCode(), mock.GetHashCode());\n        Assert.NotNull(mock.ToString());\n    }\n}\n")

(deftest executable-xunit-support-proves-equivalence-and-negative-behavior
  (let [root (temp-directory "dripsharp-java-test-support-")
        project (paths/resolve-path root "JavaTestSupportEquivalence.csproj")]
    (write-text! project project-file)
    (write-text! (paths/resolve-path root "JavaTestSupport.cs")
                 (adapters/support-source))
    (write-text! (paths/resolve-path root "JavaTestSupportEquivalence.cs")
                 executable-source)
    (write-text! (paths/resolve-path root
                                     "JavaTestSupportAdditionalEquivalence.cs")
                 extended-executable-source)
    (write-text! (paths/resolve-path root "JavaTestSupportDeltaEquivalence.cs")
                 delta-executable-source)
    (write-text! (paths/resolve-path root
                                     "JavaTestSupportCollectionEquivalence.cs")
                 collection-executable-source)
    (write-text! (paths/resolve-path root
                                     "JavaTestSupportMockObjectEquivalence.cs")
                 mock-object-executable-source)
    (let [run #(process/run! {:command % :directory root :timeout-ms 120000})]
      (run ["dotnet" "restore" (str project)])
      (run ["dotnet" "build" (str project) "--no-restore"
            "--configuration" "Release"])
      (let [result (run ["dotnet" "test" (str project) "--no-restore"
                         "--no-build" "--configuration" "Release"])]
        (is (zero? (:exit result)))))))
