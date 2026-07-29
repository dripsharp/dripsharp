(ns dripsharp.main-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.java-compat-differential :as java-compat-differential]
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
                    :ok)
                  java-compat-differential/verify!
                  (fn []
                    (swap! calls conj [:java-compat-differential])
                    :ok)]
      (is (= :ok
             (main/dispatch! ["generate" "acme" "acme-core"])))
      (is (= :ok
             (main/dispatch!
              ["differential" "acme" "acme-contract"])))
      (is (= :ok
             (main/dispatch! ["java-compat-differential"])))
      (is (= [[:generate {:target "acme" :profile "acme-core"}]
              [:differential
               {:target "acme" :validation "acme-contract"}]
              [:java-compat-differential]]
             @calls)))))

(deftest cli-rejects-implicit-target-or-profile-selection
  (doseq [args [["generate"]
                ["generate" "pkl"]
                ["differential"]
                ["java-compat-differential" "pkl"]]]
    (is (= :invalid-command-line
           (:kind (failure-data #(main/dispatch! args)))))))
