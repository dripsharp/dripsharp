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
