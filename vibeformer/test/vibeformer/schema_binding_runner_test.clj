(ns vibeformer.schema-binding-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.schema-binding-contract :as contract]
            [vibeformer.schema-binding-runner :as runner])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(defn- temp-directory []
  (Files/createTempDirectory "schema-binding-runner-test"
                             (make-array FileAttribute 0)))

(defn- b64
  [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes value StandardCharsets/UTF_8)))

(def ^:private hash-a (apply str (repeat 64 "a")))
(def ^:private hash-b (apply str (repeat 64 "b")))
(def ^:private hash-c (apply str (repeat 64 "c")))

(def ^:private rows
  [{:row-id "artifact:a"
    :artifact-kind "test-source"
    :upstream-module "pkl-config-java"
    :upstream-case-identity "pkl-config-java:src/test/A.java"
    :source-path "research/pkl/pkl-config-java/src/test/A.java"
    :source-sha256 hash-a
    :source-line "1"
    :dependencies "upstream:pkl-core"
    :behavior-family "test-infrastructure"
    :product-classification "non-shipping-test-infrastructure"
    :scope-basis "product-goal.md:test-infrastructure"
    :observation-kinds "test-infrastructure-provenance"
    :oracle-kind "test-helper-provenance"
    :detail "source"}
   {:row-id "fixture:b"
    :artifact-kind "fixture"
    :upstream-module "pkl-config-java"
    :upstream-case-identity "pkl-config-java:src/test/B.pkl"
    :source-path "research/pkl/pkl-config-java/src/test/B.pkl"
    :source-sha256 hash-b
    :source-line "1"
    :dependencies "upstream:pkl-core"
    :behavior-family "binding.map-conversions"
    :product-classification "in-scope-executable-dotnet-behavior"
    :scope-basis "product-goal.md:binding"
    :observation-kinds "binding-and-conversion;diagnostics"
    :oracle-kind "upstream-source-fixture"
    :detail "fixture"}
   {:row-id "test:c"
    :artifact-kind "test-declaration"
    :upstream-module "pkl-codegen-kotlin"
    :upstream-case-identity "org.pkl.codegen.kotlin.C#equality"
    :source-path "research/pkl/pkl-codegen-kotlin/src/test/C.kt"
    :source-sha256 hash-c
    :source-line "9"
    :dependencies "upstream:pkl-core"
    :behavior-family "codegen.equality-hash-string-behavior"
    :product-classification "language-specific-evidence-requiring-idiomatic-csharp-analogue"
    :scope-basis "port-scope.md:C# analogue"
    :observation-kinds "equality-hash-string-behavior;generated-model-shape-and-behavior;diagnostics"
    :oracle-kind "upstream-junit-declaration"
    :detail "test"}])

(def ^:private validated {:rows rows})

(def ^:private provenance-fields
  [:artifact-kind :upstream-module :upstream-case-identity :source-path
   :source-sha256 :source-line :behavior-family :product-classification
   :observation-kinds :oracle-kind])

(defn- result-row
  [row origin status observation diagnostic]
  (merge (select-keys row (into [:row-id] provenance-fields))
         {:origin origin
          :upstream-revision contract/pinned-upstream-revision
          :status status
          :observation-base64 (b64 observation)
          :diagnostic-base64 (b64 diagnostic)}))

(defn- upstream-rows []
  [(result-row (rows 0) "upstream-jvm" "SOURCE_AUDIT" "source" "")
   (result-row (rows 1) "upstream-jvm" "PASS" "fixture passed" "")
   (result-row (rows 2) "upstream-jvm" "PASS" "test passed" "")])

(defn- package-rows []
  [(result-row (rows 0) "package-dotnet" "TEST_INFRASTRUCTURE" "source" "")
   (result-row (rows 1) "package-dotnet" "PASS" "fixture passed" "")
   (result-row (rows 2) "package-dotnet" "FAIL" "equality=false"
               "independently loaded values are unequal")])

(defn- result-file
  [^Path root name result-rows]
  (runner/write-results! (.resolve root name) result-rows))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error (:kind (ex-data error)))))

(deftest normalized-results-are-exact-ordered-and-provenance-bound
  (let [root (temp-directory)
        good (result-file root "good.tsv" (package-rows))]
    (is (= 3 (count (:rows (runner/validate-results!
                            validated "package-dotnet" good)))))
    (testing "missing, duplicate, reordered, and stale rows fail closed"
      (is (= :schema-binding-result-coverage
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "missing.tsv" (pop (package-rows)))))))
      (is (= :duplicate-schema-binding-results
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "duplicate.tsv"
                                         (assoc (package-rows) 1 (first (package-rows))))))))
      (is (= :schema-binding-result-coverage
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "reordered.tsv"
                                         (assoc (package-rows)
                                                0 ((package-rows) 1)
                                                1 ((package-rows) 0)))))))
      (is (= :stale-schema-binding-result-provenance
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "stale.tsv"
                                         (assoc-in (package-rows) [1 :source-line] "999")))))))))

(deftest classifications-cannot-be-converted-into-placeholder-dispositions
  (let [root (temp-directory)]
    (is (= :invalid-schema-binding-result-disposition
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "infra-pass.tsv"
                                       (assoc-in (package-rows) [0 :status] "PASS"))))))
    (is (= :unapproved-schema-binding-result-disposition
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "false-audit.tsv"
                                       (assoc-in (package-rows) [1 :status]
                                                 "TEST_INFRASTRUCTURE"))))))
    (is (= :unsupported-schema-binding-result-status
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "pending.tsv"
                                       (assoc-in (package-rows) [1 :status] "PENDING"))))))
    (is (= :missing-schema-binding-result-diagnostic
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "crash.tsv"
                                       (-> (package-rows)
                                           (assoc-in [1 :status] "CRASH")
                                           (assoc-in [1 :diagnostic-base64] (b64 ""))))))))))

(deftest comparison-retains-concrete-package-gaps-and-repetition-is-exact
  (let [root (temp-directory)
        upstream (result-file root "upstream.tsv" (upstream-rows))
        package (result-file root "package.tsv" (package-rows))
        repeated (result-file root "repeated.tsv" (package-rows))
        comparison (runner/compare-results validated upstream package)]
    (is (= {:total 3 :matched 1 :mismatched 1
            :test-infrastructure-audits 1 :approved-exclusions 0}
           (select-keys comparison
                        [:total :matched :mismatched
                         :test-infrastructure-audits :approved-exclusions])))
    (is (= :package-execution-failure (get-in comparison [:mismatches 0 :kind])))
    (is (:deterministic?
         (runner/compare-repeated-results validated "package-dotnet"
                                          package repeated)))
    (is (false?
         (:deterministic?
          (runner/compare-repeated-results
           validated "package-dotnet" package
           (result-file root "changed.tsv"
                        (assoc-in (package-rows) [1 :observation-base64]
                                  (b64 "changed")))))))))

(deftest all-ten-deliberate-controls-trigger
  (let [controls (runner/prove-fail-closed-controls!
                  validated (.resolve (temp-directory) "controls"))]
    (is (= #{:inventory :classification :oracle :package-result
             :generated-source :compilation :behavior :ordering :timeout
             :assembly-provenance}
           (set (keys controls))))
    (is (every? true? (vals controls)))))

(deftest shipped-package-runner-uses-dynamic-inventory-and-isolated-package-builds
  (let [root (paths/workspace-root)
        csharp (Files/readString
                (paths/resolve-path root "vibeformer" "validation"
                                    "schema-binding-runner"
                                    "SchemaBindingPackageRunner.cs"))
        clojure (Files/readString
                 (paths/resolve-path root "vibeformer" "src" "vibeformer"
                                     "schema_binding_runner.clj"))]
    (doseq [required ["ReadInventory" "ValidateFixtureCoverage" "ExecuteFixtureMatrix"
                      "CSharpGenerator" "<Nullable>enable</Nullable>"
                      "<TreatWarningsAsErrors>true</TreatWarningsAsErrors>"
                      "-warnaserror" "ConfigBinder" "ConfigEvaluator"
                      "ObjectDisposedException" "equal-values" "PklBindException"
                      "--no-cache" "--force-evaluate" "VerifyPackageCache"
                      "WriteLoadedAssemblies" "entireProcessTree: true"]]
      (is (.contains csharp required)))
    (doseq [forbidden ["ProjectReference" "Pkl.Core.Runtime" "Pkl.Core.Messaging"
                       "SchemaUpstreamOracle" "research/pkl"]]
      (is (not (.contains csharp forbidden))))
    (is (.contains clojure "upstream-test-classes"))
    (is (.contains clojure "GRADLE_OPTS=-Xmx28g"))
    (is (.contains clojure "VIBEFORMER_WORKERS="))
    (is (not (.contains clojure "EvaluateSchemaTest#")))))
