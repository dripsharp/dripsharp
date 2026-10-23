;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.compare-test
  "Self-check of the comparison harness: its metrics and classification on
  synthetic inputs, a PDFBox-against-PDFBox run over three corpus documents
  (which must come out equal throughout), and a hanging implementation
  (which must be recorded as a timeout). Run from the repository root with
  research/pdfbox checked out: clojure -M:pdfcli-compare-test"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]
            [pdfcli.compare :as compare])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- image [w h f]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)]
    (doseq [y (range h) x (range w)] (.setRGB img x y (unchecked-int (f x y))))
    img))

(deftest pixel-metrics-follow-the-group-tolerance
  (let [white (image 10 10 (constantly 0xffffff))]
    (testing "identical"
      (let [m (compare/image-metrics white white)]
        (is (:identical m))
        (is (compare/fairly-close? m))))
    (testing "every channel off by 2 is fairly close"
      (let [m (compare/image-metrics white (image 10 10 (constantly 0xfdfdfd)))]
        (is (== 2.0 (:mean-abs m)))
        (is (== 0.0 (:fraction-over-32 m)))
        (is (compare/fairly-close? m))))
    (testing "one black pixel in 100 passes the fraction but fails the mean"
      (let [m (compare/image-metrics white (image 10 10 #(if (= [0 0] [%1 %2]) 0 0xffffff)))]
        (is (== 0.01 (:fraction-over-32 m)))
        (is (== 2.55 (:mean-abs m)))
        (is (not (compare/fairly-close? m)))))
    (testing "sizes must match"
      (is (not (compare/fairly-close? (compare/image-metrics white (image 10 11 (constantly 0xffffff)))))))))

(deftest normalisation-drops-only-contract-fields
  (let [env (fn [impl bytes]
              {"ok" true "command" "save" "implementation" {"name" impl}
               "result" {"output" (str "/w/" impl "/out.pdf") "bytes" bytes "pageCount" 2 "compressed" false}
               "timing" nil})]
    (is (empty? (compare/diffs (compare/normalize (env "a" 10) "save" "/w/a/out")
                               (compare/normalize (env "b" 11) "save" "/w/b/out")))))
  (is (= [[["result" "pageCount"] 1 2]]
         (compare/diffs (compare/normalize {"result" {"pageCount" 1}} "info" nil)
                        (compare/normalize {"result" {"pageCount" 2}} "info" nil)))))

(deftest classification-of-failures
  (let [ok {:status :ok :env {"ok" true "command" "text" "result" {"text" "a  b"}}}
        ws {:status :ok :env {"ok" true "command" "text" "result" {"text" "a b"}}}
        err {:status :error :code "load-failed" :env {"ok" false "error" {"code" "load-failed"}}}]
    (is (= :fairly-close (:class (compare/classify-pair "text" ok ws nil nil))))
    (is (= :pdfcarton-fails (:class (compare/classify-pair "text" ok {:status :timeout} nil nil))))
    (is (= :pdfbox-fails (:class (compare/classify-pair "text" err ok nil nil))))
    (is (= :both-fail (:class (compare/classify-pair "text" err err nil nil))))))

(def ^:private out-root
  (delay (str (Files/createTempDirectory "pdfcli-compare-test" (make-array FileAttribute 0)))))

(deftest pdfbox-against-itself-is-equal-on-three-documents
  (let [out @out-root
        jvm (compare/jvm-impl! out)
        results (compare/run-comparison {:out out :jobs 4 :perf false}
                                        :impls {:jvm jvm :dotnet jvm}
                                        :docs compare/subset-docs)
        classes (frequencies (map :class (:cases results)))]
    (is (= 3 (count (:corpus results))))
    (is (< 30 (count (:cases results))))
    (is (= #{:equal} (set (keys classes))) (pr-str (remove #(= :equal (:class %)) (:cases results))))
    (is (some #(= "form-fill" (:command %)) (:cases results)) "write cases run and reopen")
    (compare/write-results! out results)
    (is (.isFile (io/file out "results.json")))
    (is (.isFile (io/file out "summary.md")))
    (testing "a performance case reports both timings"
      (let [c {:id "info/default/hello3" :id-slug "info" :command "info" :variant "default"
               :docs [(first compare/subset-docs)] :args ["info" (first compare/subset-docs)]}
            p (compare/run-perf-case {:jvm jvm :dotnet jvm} c
                                     (merge compare/default-options {:out out :warmup 1 :repeat 3 :cold-runs 1})
                                     (str out "/perf"))]
        (is (:both-succeed p))
        (is (every? pos? (:warm-ms p)))
        (is (every? pos? (:alloc-bytes p)))
        (is (number? (:cold-ratio p)))))))

(deftest hangs-are-recorded-as-timeouts
  (let [out @out-root
        hang {:name "hang" :cmd ["sh" "-c" "sleep 30" "hang"] :repro "hang"}
        quick {:name "quick" :cmd ["sh" "-c" "echo '{\"ok\":true,\"command\":\"info\",\"result\":{}}'" "quick"]
               :repro "quick"}
        c {:id "info/default/x" :id-slug "x" :command "info" :variant "default" :docs [] :args ["info"]}
        start (System/nanoTime)
        res (compare/run-case {:jvm quick :dotnet hang} c
                              (merge compare/default-options {:timeout-s 2}) (str out "/hang"))]
    (is (= :pdfcarton-fails (:class res)))
    (is (= :timeout (get-in res [:pdfcarton :status])))
    (is (< (/ (- (System/nanoTime) start) 1e9) 20.0) "not waited on")))

(defn -main [& _]
  (let [{:keys [fail error]} (try (run-tests 'pdfcli.compare-test)
                                  (finally (when (realized? out-root)
                                             (#'compare/delete-tree! (io/file @out-root)))))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
