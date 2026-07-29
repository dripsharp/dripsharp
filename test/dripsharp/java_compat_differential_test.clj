(ns dripsharp.java-compat-differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.differential :as differential]
            [dripsharp.java-compat-differential :as java-compat]
            [dripsharp.paths :as paths]))

(deftest direct-contract-covers-every-authored-compatibility-type
  (let [root (paths/workspace-root)
        contract (java-compat/read-contract root)
        provenance (java-compat/read-provenance contract root)]
    (is (= :java-compat-direct (:id contract)))
    (is (= differential/observation-header
           (get-in contract [:observation :header])))
    (is (= 13 (count (get-in contract [:runtime :sources]))))
    (is (= 161 (count provenance)))
    (is (= (mapv :compat-type provenance)
           (vec (sort (map :compat-type provenance)))))
    (doseq [{:keys [compat-type jdk-contract targets proof-rows]}
            provenance]
      (testing compat-type
        (is (not (str/blank? jdk-contract)))
        (is (= ["pdfcube" "pkl" "rawhttp"] targets))
        (is (some #{(str "type-contract/" compat-type)}
                  proof-rows))))))

(deftest direct-proof-compares-live-jdk-and-internal-dotnet-runtime
  (let [summary (java-compat/verify!)]
    (is (= :java-compat-direct
           (get-in summary [:contract :id])))
    (is (= 17 (get-in summary [:java :release])))
    (is (= {:namespace "DripSharp.Runtime"
            :visibility :internal
            :sources 13
            :types 161
            :targets ["pdfcube" "pkl" "rawhttp"]}
           (:runtime summary)))
    (is (= 182 (get-in summary [:trace :observations])))
    (is (= ["behavior" "type-contract"]
           (get-in summary [:trace :families])))
    (is (= {:matched 183} (:comparison summary)))
    (is (= 184 (:perturbation-line summary)))))
