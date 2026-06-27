(ns build-test
  (:require [build]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(deftest sample-task-forwards-explicit-coverage-options
  (let [args (build/sample-runner-main-args
              "checked"
              {:name "ignored-by-helper"
               :coverage/allow-unsupported? true
               :allow-stubs? true
               :java/classpath-types #{"org.example.Dependency"}
               :java/classpath-package-roots #{"org.example"}
               :kotlin/classpath-types #{"Locale"}
               :kotlin/classpath-roots ["lib/kotlin"]
               :kotlin/analysis-api? true
               :unrelated true})]
    (is (= ["-m" "vibeformer.sample-runner" "checked"]
           (take 3 args)))
    (is (= {:coverage/allow-unsupported? true
            :allow-stubs? true
            :java/classpath-types #{"org.example.Dependency"}
            :java/classpath-package-roots #{"org.example"}
            :kotlin/classpath-types #{"Locale"}
            :kotlin/classpath-roots ["lib/kotlin"]
            :kotlin/analysis-api? true}
           (edn/read-string (last args))))))

(deftest sample-task-keeps-non-default-samples-strict-by-default
  (testing "no runner EDN map is passed unless the sample has a default or the caller opts in"
    (is (= ["-m" "vibeformer.sample-runner" "checked"]
           (build/sample-runner-main-args "checked" {})))))

(deftest kotlin-emission-sample-enables-runner-emission-by-default
  (testing "string sample names"
    (doseq [sample-name ["kotlin-basic-declarations"
                         "kotlin-api-calls"
                         "kotlin-object-overrides"]]
      (let [args (build/sample-runner-main-args sample-name {})]
        (is (= ["-m" "vibeformer.sample-runner" sample-name]
               (take 3 args)))
        (is (= {:kotlin/emit? true}
               (edn/read-string (last args)))))))
  (testing "tool invocation symbol sample names"
    (let [args (build/sample-runner-main-args 'kotlin-basic-declarations {})]
      (is (= ["-m" "vibeformer.sample-runner" "kotlin-basic-declarations"]
             (take 3 args)))
      (is (= {:kotlin/emit? true}
             (edn/read-string (last args)))))))

(deftest research-dry-run-task-forwards-boundary-options
  (let [args (build/research-dry-run-main-args
              {:dry-run/mode :compile-capable
               :research/root "../research/pkl"
               :out-dir "target/custom-research"
               :unrelated true})]
    (is (= ["-m" "vibeformer.research-dry-run"]
           (take 2 args)))
    (is (= {:dry-run/mode :compile-capable
            :research/root "../research/pkl"
            :out-dir "target/custom-research"}
           (edn/read-string (last args))))))

(deftest research-classpath-task-forwards-source-and-output-options
  (let [args (build/research-classpath-main-args
              {:research/root "../research/pkl"
               :classpath/out "target/research-pkl/classpath.edn"
               :unrelated true})]
    (is (= ["-m" "vibeformer.research-classpath"]
           (take 2 args)))
    (is (= {:research/root "../research/pkl"
            :classpath/out "target/research-pkl/classpath.edn"}
           (edn/read-string (last args))))))

(deftest research-sample-report-task-forwards-report-input-options
  (let [args (build/research-sample-report-main-args
              {:project/id "research-pkl"
               :inventory "target/research-pkl/inventory.edn"
               :dry-run "target/research-pkl/dry-run.edn"
               :samples/root "sample-projects"
               :sample-report/out "target/research-pkl/sample-selection.edn"
               :top 5
               :unrelated true})]
    (is (= ["-m" "vibeformer.research-sample-report"]
           (take 2 args)))
    (is (= {:project/id "research-pkl"
            :inventory "target/research-pkl/inventory.edn"
            :dry-run "target/research-pkl/dry-run.edn"
            :samples/root "sample-projects"
            :sample-report/out "target/research-pkl/sample-selection.edn"
            :top 5}
           (edn/read-string (last args))))))
