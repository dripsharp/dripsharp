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
               :kotlin/classpath-types #{"Locale"}
               :unrelated true})]
    (is (= ["-m" "vibeformer.sample-runner" "checked"]
           (take 3 args)))
    (is (= {:coverage/allow-unsupported? true
            :allow-stubs? true
            :kotlin/classpath-types #{"Locale"}}
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
