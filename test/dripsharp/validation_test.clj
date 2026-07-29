(ns dripsharp.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.validation :as validation]))

(def ^:private context
  {:kind :invalid-example-configuration
   :subject "Example configuration"
   :data {:profile "example"}})

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest field-diagnostics-retain-value-predicate-and-context
  (let [error
        (caught
         #(validation/check!
           context [:project :target-framework] 10
           "a non-blank string"
           (fn [value] (and (string? value) (seq value)))))]
    (is (= :invalid-example-configuration (:kind (ex-data error))))
    (is (= "Example configuration" (:subject (ex-data error))))
    (is (= "example" (:profile (ex-data error))))
    (is (= [:project :target-framework] (:path (ex-data error))))
    (is (= 10 (:value (ex-data error))))
    (is (= "a non-blank string" (:expected (ex-data error))))))

(deftest map-shape-diagnostics-separate-missing-and-unknown-keys
  (let [error
        (caught
         #(validation/exact-keys!
           context [:profile] {:id "example" :opaque true}
           #{:id :destination}
           #{:id :destination :description}))]
    (is (= [:destination] (:missing-keys (ex-data error))))
    (is (= [:opaque] (:unknown-keys (ex-data error))))
    (is (= {:required-keys [:destination :id]
            :allowed-keys [:description :destination :id]}
           (:expected (ex-data error))))))

(deftest cross-contract-diagnostics-name-both-paths
  (let [error
        (caught
         #(validation/agree!
           context
           [:destination :package :id] "Example.Core"
           [:baseline :package-id] "Example.Other"))]
    (testing "actual and expected contracts remain independently actionable"
      (is (= [:baseline :package-id] (:path (ex-data error))))
      (is (= "Example.Other" (:value (ex-data error))))
      (is (= [:destination :package :id]
             (:expected-path (ex-data error))))
      (is (= "Example.Core" (:expected-value (ex-data error)))))))
