(ns dripsharp.floating-microprogram-test
  "Process-free admission; live numeric checks require explicit local selection."
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.floating-microprogram :as floating]
            [dripsharp.java-compat-differential :as compat]
            [dripsharp.microprogram :as micro]
            [dripsharp.differential-registry :as registry]))

(deftest recorded-inputs-are-exact-bounded-and-admitted-by-both-runners
  (let [record (floating/snapshot 73 24)
        rows (micro/validate-snapshot! record)]
    (is (= record (floating/snapshot 73 24)))
    (is (= rows (compat/select-cases (:input record) {:family "floating"})))
    (is (= 1000 (count rows)))
    (is (= #{"none" "illegal-argument"} (set (map :expected-exception rows))))
    (is (= :explicit-local-only
           (get-in (registry/registry) [:translated-microprograms :families "floating" :execution])))
    (doseq [samples [-1 25] seed [73]]
      (is (thrown? clojure.lang.ExceptionInfo (floating/snapshot seed samples))))))
