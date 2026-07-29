(ns dripsharp.main-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.main :as main]
            [dripsharp.target-execution :as target-execution]))

(defn- failure-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest cli-dispatch-is-target-and-profile-driven
  (let [calls (atom [])]
    (with-redefs [target-execution/run!
                  (fn [command options]
                    (swap! calls conj [command options])
                    :ok)
                  target-execution/differential!
                  (fn [options]
                    (swap! calls conj [:differential options])
                    :ok)]
      (is (= :ok
             (main/dispatch! ["generate" "acme" "acme-core"])))
      (is (= :ok
             (main/dispatch!
              ["differential" "acme" "acme-contract"])))
      (is (= [[:generate {:target "acme" :profile "acme-core"}]
              [:differential
               {:target "acme" :validation "acme-contract"}]]
             @calls)))))

(deftest cli-rejects-implicit-target-or-profile-selection
  (doseq [args [["generate"]
                ["generate" "pkl"]
                ["differential"]]]
    (is (= :invalid-command-line
           (:kind (failure-data #(main/dispatch! args)))))))
