(ns dripsharp.pdfcube-test-suite-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.test-suite :as test-suite]))

(deftest complete-pinned-pdfbox-test-tree-is-losslessly-inventoried
  (let [inventory (test-suite/inventory!)
        contract (test-suite/read-contract! (paths/absolute "targets/pdfcube"))
        accounting (:accounting inventory)
        disabled (filter #(= :disabled (:state %))
                         (:enablement accounting))
        conditions (:platform-conditions accounting)]
    (is (= inventory (test-suite/verify-inventory! contract inventory)))
    (is (= #{:io :fontbox :xmpbox :pdfbox :preflight}
           (set (map :module (:sources accounting)))))
    (is (= 233 (count (:sources accounting))))
    (is (= 371 (count (:fixtures accounting))))
    (is (= 371 (count (distinct (map :destination
                                     (:fixtures accounting))))))
    (is (every? #(and (= "Apache-2.0" (:license %))
                      (= :mechanically-upstream-derived (:authorship %))
                      (not (str/blank? (:attribution %))))
                (:fixtures accounting)))
    (is (= 1281 (count (:cases accounting))))
    (is (= 1281 (count (:enablement accounting))))
    (is (= 5 (count disabled)))
    (is (every? #(and (= :fontbox (:module %))
                      (not (str/blank? (:reason %))))
                disabled))
    (is (some #(str/starts-with?
                (:key %)
                "executable:org.junit.jupiter.api.Assumptions#")
              conditions))
    (is (some #(str/starts-with?
                (:key %)
                "executable:java.lang.System#getProperty(")
              conditions))
    (testing "the Maven module graph remains explicit in the accounting"
      (let [by-module (into {} (map (juxt :module identity))
                            (:dependencies accounting))]
        (is (= #{}
               (set (map :project-id
                         (get-in by-module [:io :production-projects])))))
        (is (= #{"org.apache.pdfbox:pdfbox-io:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:fontbox :production-projects])))))
        (is (= #{"org.apache.pdfbox:fontbox:3.0.8"
                 "org.apache.pdfbox:pdfbox-io:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:pdfbox :production-projects])))))
        (is (= #{"org.apache.pdfbox:fontbox:3.0.8"
                 "org.apache.pdfbox:pdfbox-io:3.0.8"
                 "org.apache.pdfbox:pdfbox:3.0.8"
                 "org.apache.pdfbox:xmpbox:3.0.8"}
               (set (map :project-id
                         (get-in by-module
                                 [:preflight :production-projects])))))))))
