(ns dripsharp.main-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.java-compat-differential :as java-compat-differential]
            [dripsharp.main :as main]
            [dripsharp.pdfcube.host-matrix :as pdfcube-host-matrix]
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
                  target-execution/proof!
                  (fn [options]
                    (swap! calls conj [:proof options])
                    :ok)
                  java-compat-differential/verify!
                  (fn []
                    (swap! calls conj [:java-compat-differential])
                    :ok)
                  pdfcube-host-matrix/verify!
                  (fn [evidence-root output-root]
                    (swap! calls conj
                           [:pdfcube-family-host-matrix
                            evidence-root
                            output-root])
                    :ok)]
      (is (= :ok
             (main/dispatch! ["generate" "acme" "acme-core"])))
      (is (= :ok
             (main/dispatch!
              ["differential" "acme" "acme-contract"])))
      (is (= :ok
             (main/dispatch! ["proof" "acme"])))
      (is (= :ok
             (main/dispatch! ["java-compat-differential"])))
      (is (= :ok
             (main/dispatch!
              ["pdfcube-family-host-matrix" "evidence" "output"])))
      (is (= [[:generate {:target "acme" :profile "acme-core"}]
              [:differential
               {:target "acme" :validation "acme-contract"}]
              [:proof {:target "acme"}]
              [:java-compat-differential]
              [:pdfcube-family-host-matrix "evidence" "output"]]
             @calls)))))

(deftest cli-rejects-implicit-target-or-profile-selection
  (doseq [args [["generate"]
                ["generate" "pkl"]
                ["differential"]
                ["proof"]
                ["proof" "pkl" "extra"]
                ["java-compat-differential" "pkl"]
                ["pdfcube-family-host-matrix"]
                ["pdfcube-family-host-matrix" "evidence"]
                ["pdfcube-family-host-matrix"
                 "evidence"
                 "output"
                 "extra"]]]
    (is (= :invalid-command-line
           (:kind (failure-data #(main/dispatch! args)))))))
