(ns dripsharp.main-test
  (:require [clojure.test :refer [deftest is]]
            [dripsharp.authorship-portfolio :as authorship-portfolio]
            [dripsharp.java-compat-differential :as java-compat-differential]
            [dripsharp.main :as main]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.host-matrix :as pdfcube-host-matrix]
            [dripsharp.rebaseline :as rebaseline]
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
                  target-execution/synchronize!
                  (fn [options]
                    (swap! calls conj [:product-sync options])
                    :ok)
                  target-execution/prepare-publication!
                  (fn [options]
                    (swap! calls conj [:product-prepare options])
                    :ok)
                  authorship-portfolio/write!
                  (fn [options]
                    (swap! calls conj [:authorship-report options])
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
                    :ok)
                  rebaseline/run!
                  (fn [root args]
                    (swap! calls conj [:rebaseline root (vec args)])
                    :ok)]
      (is (= :ok
             (main/dispatch! ["generate" "acme" "acme-core"])))
      (is (= :ok
             (main/dispatch!
              ["differential" "acme" "acme-contract"])))
      (is (= :ok
             (main/dispatch! ["proof" "acme"])))
      (is (= :ok
             (main/dispatch! ["product-sync" "acme"])))
      (is (= :ok
             (main/dispatch!
              ["product-prepare" "acme" "generated/acme"
               "Publish Acme"])))
      (is (= :ok
             (main/dispatch! ["authorship-report" "all"])))
      (is (= :ok
             (main/dispatch! ["java-compat-differential"])))
      (is (= :ok
             (main/dispatch!
              ["pdfcube-family-host-matrix" "evidence" "output"])))
      (is (= :ok
             (main/dispatch! ["rebaseline" "rawhttp"])))
      (is (= [[:generate {:target "acme" :profile "acme-core"}]
              [:differential
               {:target "acme" :validation "acme-contract"}]
              [:proof {:target "acme"}]
              [:product-sync {:target "acme"}]
              [:product-prepare
               {:target "acme"
                :branch "generated/acme"
                :commit-message "Publish Acme"}]
              [:authorship-report {:selection "all"}]
              [:java-compat-differential]
              [:pdfcube-family-host-matrix "evidence" "output"]
              [:rebaseline
               (paths/workspace-root)
               ["rawhttp"]]]
             @calls)))))

(deftest cli-rejects-implicit-target-or-profile-selection
  (doseq [args [["generate"]
                ["generate" "pkl"]
                ["differential"]
                ["proof"]
                ["proof" "pkl" "extra"]
                ["product-sync"]
                ["product-sync" "pkl" "extra"]
                ["product-prepare" "pkl"]
                ["product-prepare" "pkl" "generated/pkl"]
                ["product-prepare" "pkl" "generated/pkl" "message" "extra"]
                ["authorship-report"]
                ["authorship-report" "all" "extra"]
                ["java-compat-differential" "pkl"]
                ["pdfcube-family-host-matrix"]
                ["pdfcube-family-host-matrix" "evidence"]
                ["pdfcube-family-host-matrix"
                 "evidence"
                 "output"
                 "extra"]]]
    (is (= :invalid-command-line
           (:kind (failure-data #(main/dispatch! args)))))))

(deftest cli-usage-advertises-every-rebaseline-target
  (let [error (try
                (main/dispatch! [])
                nil
                (catch clojure.lang.ExceptionInfo error
                  error))]
    (is (= :invalid-command-line (:kind (ex-data error))))
    (is (re-find
         #"\|rebaseline <pkl\|pdfcube\|rawhttp> \[--approve <token>\]"
         (ex-message error)))))
