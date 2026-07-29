(ns dripsharp.test-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.test-runner :as runner]))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest unit-tier-is-explicit-process-free-and-complementary
  (is (= runner/unit-test-namespaces
         (#'runner/selected-namespaces ["--tier" "unit"])))
  (is (= #{:translation-planning
           :mapping-registry
           :configuration-diagnostics
           :csharp-rendering
           :project-emission
           :source-accountability
           :bundle-contract}
         (get-in runner/test-tiers [:unit :capabilities])))
  (doseq [namespace runner/unit-test-namespaces
          :let [source
                (str "test/"
                     (.replace (str namespace) "." "/")
                     ".clj")
                source (.replace source "-" "_")]]
    (is (.isFile (java.io.File. source)) source))
  (when (= :unit runner/*test-tier*)
    (testing "the loaded tier has no frontend or process orchestration"
      (doseq [namespace
              '[dripsharp.spoon
                dripsharp.java-project
                dripsharp.process
                dripsharp.harness
                dripsharp.maven]]
        (is (nil? (find-ns namespace)) (str namespace))))))

(deftest unit-tier-cannot-be-silently-combined-or-substituted
  (testing "namespace selection remains available for focused full-suite tests"
    (is (= '[dripsharp.harness-test dripsharp.csharp-test]
           (#'runner/selected-namespaces
            ["--namespace" "dripsharp.harness-test"
             "--namespace" "dripsharp.csharp-test"]))))
  (doseq [arguments
          [["--tier" "integration"]
           ["--tier" "unit" "--namespace" "dripsharp.harness-test"]
           ["--namespace"]]]
    (let [error
          (caught #(#'runner/selected-namespaces arguments))]
      (is (= :invalid-test-arguments (:kind (ex-data error)))
          (pr-str arguments)))))
