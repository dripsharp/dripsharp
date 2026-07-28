(ns dripsharp.pkl.core-test-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.core-test-contract :as contract])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private manifest
  (delay (paths/resolve-path (paths/workspace-root) "validation"
                             "pkl-core-test-contract" "PklCoreTestContract.tsv")))

(def ^:private validated
  (delay (contract/validate-manifest! @manifest)))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (:kind (ex-data error)))))

(defn- case-by
  [predicate]
  (some #(when (predicate %) %) (:cases @validated)))

(deftest authoritative-junit-case-set-reconciles-static-and-dynamic-discovery
  (let [summary (:summary @validated)
        sources (:sources @validated)
        cases (:cases @validated)]
    (testing "the loose audit is reconciled with active declarations and launcher cases"
      (is (= contract/pinned-upstream-revision (:upstream-revision summary)))
      (is (= 86 (:sources summary)))
      (is (= 84 (:active-sources summary)))
      (is (= 585 (:audit-tokens summary)))
      (is (= 27 (:audit-only-tokens summary)))
      (is (= 558 (:declarations summary)))
      (is (= 605 (:cases summary)))
      (is (= {"test" 560
              "repeated-invocation" 6
              "parameterized-invocation" 39}
             (:case-kinds summary)))
      (is (= {"SUCCESSFUL" 602 "SKIPPED" 2 "ABORTED" 1}
             (:statuses summary))))

    (testing "commented legacy annotations remain visible but are not invented as JUnit cases"
      (is (= #{"pkl-core/src/test/kotlin/org/pkl/core/runtime/DefaultModuleResolverTest.kt"
               "pkl-core/src/test/kotlin/org/pkl/core/runtime/ModuleKeyTest.kt"}
             (->> sources
                  (filter #(= "commented-legacy-non-junit-source"
                              (:source-disposition %)))
                  (map :source-path)
                  set)))
      (is (= 0 (reduce +
                       (map #(parse-long (:discovered-case-count %))
                            (filter #(= "commented-legacy-non-junit-source"
                                        (:source-disposition %))
                                    sources))))))

    (testing "parameterized unique IDs, source lines, outcomes, and owners are explicit"
      (let [reflection (case-by #(str/includes? (:display-name %) "pkl:yaml"))]
        (is (str/includes? (:junit-unique-id reflection) "test-template-invocation"))
        (is (= "ParameterizedTest"
               (:annotation
                (some #(when (= (:declaration-id reflection) (:declaration-id %)) %)
                      (:declarations @validated)))))
        (is (pos? (parse-long (:source-line reflection))))
        (is (= "assertions-succeed" (:expected-outcome reflection)))
        (is (= "jvm-shared-product-behavior" (:product-classification reflection)))
        (is (str/includes? (:scope-basis reflection)
                           "mixed-case-retains-in-scope-reflection-observation")))
      (is (every? #(not= "-" (:environment-requirements %)) cases))
      (is (every? #(not (str/blank? (:execution-owner %))) cases)))

    (testing "all existing evidence domains are cross-referenced"
      (is (= #{"parser" "language" "core" "loading" "public-api"
               "schema-codegen" "binding"}
             (->> cases
                  (mapcat #(str/split (:existing-evidence %) #";"))
                  set))))))

(deftest product-scope-is-fail-closed-and-mixed-observations-stay-in-scope
  (let [yaml (case-by #(= "org.pkl.core.YamlRendererTest" (:source-class %)))
        binary (case-by #(= "org.pkl.core.PklBinaryDecoderTest" (:source-class %)))
        mixed-binary
        (case-by #(and (= "org.pkl.core.EvaluatorTest" (:source-class %))
                       (= "nested pkl-binary rendering produces correct results"
                          (:source-method %))))
        repl (case-by #(= "org.pkl.core.ReplServerTest" (:source-class %)))
        command (case-by #(= "org.pkl.core.runtime.CommandSpecParserTest"
                             (:source-class %)))
        report (case-by #(= "org.pkl.core.stdlib.MinimalReportTest"
                            (:source-class %)))
        hygiene (case-by #(= "org.pkl.core.RepositoryHygiene" (:source-class %)))
        disabled (case-by #(= "org.pkl.core.StackFrameTransformersTest" (:source-class %)))
        windows (case-by #(= "enabled-on-windows" (:expected-outcome %)))
        path-abort (case-by #(= "external-reader-path-conditional"
                                (:expected-outcome %)))]
    (doseq [excluded [yaml binary repl command report]]
      (is (= "user-approved-excluded-surface" (:product-classification excluded)))
      (is (str/includes? (:scope-basis excluded)
                         "targets/pkl/product-goal.md#user-approved-product-exclusions"))
      (is (str/includes? (:scope-basis excluded)
                         "targets/pkl/port-scope.md#explicit-scope-decisions")))
    (is (= "excluded-cli-command" (:behavior-family command)))
    (is (= "excluded-cli-test-reporting" (:behavior-family report)))
    (is (= "evaluation-runtime" (:behavior-family mixed-binary)))
    (is (= "in-scope-mixed-excluded-surface"
           (:product-classification mixed-binary)))
    (is (= "complete-pkl-core-runner" (:execution-owner mixed-binary)))
    (is (str/includes? (:scope-basis mixed-binary)
                       "in-scope-evaluator+value-model+custom-resource-observation"))
    (is (str/includes? (:scope-basis mixed-binary)
                       "excluded-pkl-binary-transport-is-not-a-case-exclusion"))
    (is (= "test-infrastructure-only-mechanics" (:product-classification hygiene)))
    (is (= "jvm-shared-product-behavior" (:product-classification disabled)))
    (is (= "upstream-explicitly-disabled" (:expected-outcome disabled)))
    (is (= "os=windows" (:platform-conditions windows)))
    (is (str/includes? (:environment-requirements path-abort)
                       "external-reader-fixture-on-PATH"))))

(deftest manifest-controls-detect-missing-duplicate-unclassified-and-silent-rows
  (let [parsed (contract/read-manifest @manifest)
        cases (:cases parsed)
        declarations (:declarations parsed)
        first-case (first cases)
        single-case-declaration
        (some (fn [declaration]
                (when (= "1" (:discovered-case-count declaration)) declaration))
              declarations)
        single-case-index
        (first (keep-indexed
                (fn [index case-data]
                  (when (= (:declaration-id single-case-declaration)
                           (:declaration-id case-data))
                    index))
                cases))
        replacement-declaration
        (some #(when (and (= (:source-path single-case-declaration) (:source-path %))
                          (not= (:declaration-id single-case-declaration)
                                (:declaration-id %)))
                 %)
              declarations)]
    (testing "missing and duplicate cases fail closed"
      (is (= :pkl-core-contract-count
             (thrown-kind #(#'contract/validate-rows! (assoc parsed :cases (pop cases))))))
      (is (= :duplicate-pkl-core-contract-row
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases
                                   (assoc cases (dec (count cases)) first-case)))))))

    (testing "unclassified and unowned cases fail closed"
      (is (= :unclassified-pkl-core-product-scope
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases
                                   (assoc cases 0 (assoc first-case
                                                         :product-classification "pending")))))))
      (is (= :unowned-pkl-core-case
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases
                                   (assoc cases 0 (assoc first-case
                                                         :execution-owner "-"))))))))

    (testing "the mixed evaluator row cannot revert to a whole-case exclusion"
      (let [mixed-index
            (first
             (keep-indexed
              (fn [index case-data]
                (when (= "nested pkl-binary rendering produces correct results"
                         (:source-method case-data))
                  index))
              cases))]
        (is (some? mixed-index))
        (is (= :pkl-core-mixed-evaluator-whole-case-exclusion
               (thrown-kind
                #(#'contract/validate-rows!
                  (assoc parsed :cases
                         (update cases mixed-index assoc
                                 :product-classification
                                 "user-approved-excluded-surface"))))))))

    (testing "silent skips and undiscovered active declarations fail closed"
      (is (= :silent-pkl-core-skip
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases
                                   (assoc cases 0 (assoc first-case
                                                         :pinned-discovery-status "SKIPPED"
                                                         :pinned-discovery-reason "-")))))))
      (is replacement-declaration)
      (is (= :silently-undiscovered-pkl-core-declaration
             (thrown-kind
              #(#'contract/validate-rows!
                (assoc parsed :cases
                       (update cases single-case-index assoc
                               :declaration-id (:declaration-id replacement-declaration))))))))))

(deftest provenance-and-live-discovery-controls-detect-stale-and-new-cases
  (let [parsed @validated
        cases (:cases parsed)
        first-case (first cases)]
    (testing "source hash drift is detected"
      (is (= :stale-pkl-core-source-inventory
             (thrown-kind
              #(#'contract/verify-static-inventory!
                (:layout parsed)
                (update-in parsed [:sources 0] assoc :source-sha256 (str (repeat 64 "0"))))))))

    (testing "live discovery detects missing, new, and changed identifiers"
      (is (= :missing-pkl-core-discovered-cases
             (thrown-kind #(contract/compare-discovery-cases! cases (subvec cases 1)))))
      (let [new-id "[engine:junit-jupiter]/[class:NewTest]/[method:newCase()]"
            new-case (assoc first-case :junit-unique-id new-id)]
        (is (= :new-pkl-core-discovered-cases
               (thrown-kind #(contract/compare-discovery-cases! cases
                                                                (conj cases new-case))))))
      (is (= :stale-pkl-core-discovered-case
             (thrown-kind #(contract/compare-discovery-cases!
                            cases (assoc cases 0 (assoc first-case
                                                        :display-name "changed")))))))))
