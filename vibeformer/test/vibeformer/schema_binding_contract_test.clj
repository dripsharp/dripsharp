(ns vibeformer.schema-binding-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.schema-binding-contract :as contract])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private validated
  (delay (contract/validate-contract! (paths/workspace-root))))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (:kind (ex-data error)))))

(defn- rows-of-kind
  [kind]
  (filterv #(= kind (:artifact-kind %)) (:rows @validated)))

(deftest exhaustive-pinned-inventory-covers-every-requested-upstream-input
  (let [{:keys [summary rows]} @validated]
    (testing "the pinned source oracle is complete and repeats byte-identically"
      (is (= contract/pinned-upstream-revision (:upstream-revision summary)))
      (is (= 680 (:rows summary)))
      (is (= 680 (:first-live-match summary)))
      (is (= 680 (:second-live-match summary)))
      (is (= "567aa0e7b6513c2cc022dcf34cc7fa10d37ef0309d3a1b845faad06645a8fe5e"
             (:oracle-sha256 summary))))

    (testing "all declarations, invocations, resources, helpers, and support models are explicit"
      (is (= {"test-source" 41
              "helper-source" 6
              "fixture" 22
              "expected-output-resource" 8
              "test-declaration" 292
              "parameterized-case" 4
              "helper-declaration" 123
              "support-type" 184}
             (:artifacts summary)))
      (is (= {"pkl-core" 13
              "pkl-config-java" 281
              "pkl-config-kotlin" 60
              "pkl-codegen-java" 164
              "pkl-codegen-kotlin" 162}
             (:modules summary)))
      (is (= 37 (:behavior-families summary))))

    (testing "the source declaration counts retain CLI wrappers and parameter expansion"
      (is (= {"pkl-core" 3
              "pkl-config-java" 152
              "pkl-config-kotlin" 22
              "pkl-codegen-java" 60
              "pkl-codegen-kotlin" 55}
             (frequencies (map :upstream-module (rows-of-kind "test-declaration")))))
      (is (= #{"org.pkl.codegen.java.JavaCodeGeneratorTest#deprecated module class with message[generateJavadoc=false]"
               "org.pkl.codegen.java.JavaCodeGeneratorTest#deprecated module class with message[generateJavadoc=true]"
               "org.pkl.codegen.java.JavaCodeGeneratorTest#deprecated property[generateJavadoc=false]"
               "org.pkl.codegen.java.JavaCodeGeneratorTest#deprecated property[generateJavadoc=true]"}
             (set (map :upstream-case-identity (rows-of-kind "parameterized-case")))))
      (is (= 6
             (count
              (filter #(str/includes? (:upstream-case-identity %)
                                      "Cli")
                      (rows-of-kind "test-declaration"))))))

    (testing "all rows carry raw source provenance and dependency identities"
      (is (every? #(re-matches #"[0-9a-f]{64}" (:source-sha256 %)) rows))
      (is (every? #(str/starts-with? (:source-path %) "research/pkl/") rows))
      (is (every? #(not= "-" (:dependencies %)) rows)))))

(deftest classifications-retain-product-scope-and-language-specific-evidence
  (let [{:keys [summary rows]} @validated
        declarations (rows-of-kind "test-declaration")
        cli (filter #(str/includes? (:upstream-case-identity %) "Cli") declarations)
        evaluate-schema
        (filter #(= "org.pkl.core.EvaluateSchemaTest"
                    (first (str/split (:upstream-case-identity %) #"#")))
                declarations)
        infrastructure (filter #(contains? #{"test-source" "helper-source"
                                             "helper-declaration"}
                                           (:artifact-kind %))
                               rows)]
    (is (= {"non-shipping-test-infrastructure" 176
            "language-specific-evidence-requiring-idiomatic-csharp-analogue" 368
            "in-scope-executable-dotnet-behavior" 136}
           (:classifications summary)))
    (is (zero? (count (filter #(= "user-approved-excluded-surface"
                                  (:product-classification %))
                              rows))))
    (is (every? #(= "language-specific-evidence-requiring-idiomatic-csharp-analogue"
                    (:product-classification %))
                cli))
    (is (every? #(str/includes? (:scope-basis %) "CLI-wrapper-does-not-exclude") cli))
    (is (every? #(= "in-scope-executable-dotnet-behavior"
                    (:product-classification %))
                evaluate-schema))
    (is (every? #(= "non-shipping-test-infrastructure"
                    (:product-classification %))
                infrastructure))))

(deftest relevant-helpers-fixtures-and-golden-resources-are-pinned
  (let [rows (:rows @validated)
        identities (set (map :upstream-case-identity rows))
        paths (set (map :source-path rows))]
    (testing "EvaluateSchema helper assertions and lifecycle are separate rows"
      (doseq [helper ["afterEach" "checkModuleMetadata" "checkModuleProperties"
                      "checkModuleMethods" "checkModuleClasses" "checkSupermodule"]]
        (is (some #(str/includes? % (str "EvaluateSchemaTest#" helper "@")) identities))))

    (testing "standalone compilers, module models, and mapping models are source rows"
      (doseq [path ["research/pkl/pkl-codegen-java/src/test/kotlin/org/pkl/codegen/java/InMemoryJavaCompiler.kt"
                    "research/pkl/pkl-codegen-java/src/test/kotlin/org/pkl/codegen/java/PklModule.kt"
                    "research/pkl/pkl-codegen-kotlin/src/test/kotlin/org/pkl/codegen/kotlin/InMemoryKotlinCompiler.kt"
                    "research/pkl/pkl-codegen-kotlin/src/test/kotlin/org/pkl/codegen/kotlin/PklModule.kt"
                    "research/pkl/pkl-config-java/src/test/java/org/pkl/config/java/mapper/Person.java"
                    "research/pkl/pkl-config-kotlin/src/test/java/org/pkl/config/kotlin/JavaPerson.java"]]
        (is (contains? paths path))))

    (testing "all eight language golden resources and both schema fixtures remain exact"
      (is (= 8 (count (rows-of-kind "expected-output-resource"))))
      (doseq [path ["research/pkl/pkl-core/src/test/resources/org/pkl/core/EvaluateSchemaTest.pkl"
                    "research/pkl/pkl-core/src/test/resources/org/pkl/core/EvaluateSchemaTestBaseModule.pkl"
                    "research/pkl/pkl-codegen-java/src/test/resources/org/pkl/codegen/java/Javadoc.jva"
                    "research/pkl/pkl-codegen-kotlin/src/test/resources/org/pkl/codegen/kotlin/Kdoc.kotlin"]]
        (is (contains? paths path))))))

(deftest deterministic-observations-cover-success-and-failure-contracts
  (let [observations (:observations @validated)
        by-kind (into {} (map (juxt :observation-kind identity) observations))
        required #{"schema-metadata"
                   "generated-model-shape-and-behavior"
                   "symbol-and-namespace-mapping"
                   "documentation-and-deprecation-metadata"
                   "equality-hash-string-behavior"
                   "evaluator-config-navigation"
                   "binding-and-conversion"
                   "generated-loaders"
                   "reflection-and-nullability"
                   "lifecycle"
                   "diagnostics"}]
    (is (= 12 (count observations)))
    (is (every? #(contains? by-kind %) required))
    (doseq [kind required
            :let [observation (get by-kind kind)]]
      (is (not (str/blank? (:success-observation observation))))
      (is (not (str/blank? (:failure-observation observation))))
      (is (not (str/blank? (:normalization observation))))
      (is (not (str/blank? (:comparison observation)))))))

(deftest inventory-and-observation-perturbations-fail-closed
  (let [{:keys [rows observations]} @validated
        first-row (first rows)]
    (testing "missing, new, duplicate, and changed inventory rows are detected"
      (is (= :new-schema-binding-inventory-rows
             (thrown-kind #(contract/compare-inventory! (subvec rows 1) rows))))
      (is (= :missing-schema-binding-inventory-rows
             (thrown-kind #(contract/compare-inventory! rows (subvec rows 1)))))
      (is (= :duplicate-schema-binding-inventory-row
             (thrown-kind #(#'contract/validate-rows!
                            (conj rows first-row) observations))))
      (is (= :stale-schema-binding-inventory-row
             (thrown-kind #(contract/compare-inventory!
                            rows (assoc rows 0 (assoc first-row :detail "perturbed")))))))

    (testing "unclassified rows and observation changes are detected"
      (is (= :unclassified-schema-binding-inventory-row
             (thrown-kind #(#'contract/validate-rows!
                            (assoc rows 0 (assoc first-row
                                                 :product-classification "pending"))
                            observations))))
      (is (= :schema-binding-observation-perturbation
             (thrown-kind #(contract/compare-observations!
                            observations
                            (assoc observations 0
                                   (assoc (first observations)
                                          :failure-observation "perturbed")))))))))
