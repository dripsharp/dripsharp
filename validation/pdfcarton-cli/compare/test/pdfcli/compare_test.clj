;; SPDX-FileCopyrightText: 2026 Isak Sky
;; SPDX-License-Identifier: Apache-2.0
(ns pdfcli.compare-test
  "Self-check of the comparison harness: its metrics and classification on
  synthetic inputs, a PDFBox-against-PDFBox run over three corpus documents
  (which must come out equal throughout), and a hanging implementation
  (which must be recorded as a timeout). Run from the repository root with
  research/pdfbox checked out: clojure -M:pdfcli-compare-test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [pdfcli.compare :as compare])
  (:import (java.awt.image BufferedImage)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (javax.imageio ImageIO)))

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
        ;; A distinct name gives the second oracle its own output trees.
        other (assoc jvm :name "pdfbox-jvm-2")
        results (compare/run-comparison {:out out :jobs 4 :perf false}
                                        :impls {:jvm jvm :dotnet other}
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
            p (compare/run-perf-case {:jvm jvm :dotnet other} c
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

(defn- write-png! [dir name img]
  (.mkdirs (io/file dir))
  (ImageIO/write ^BufferedImage img "png" (io/file dir name)))

(deftest rendered-pixels-are-compared-per-output-tree
  (let [out @out-root
        env {"ok" true "command" "render" "result" {"pages" [{"page" 1 "file" "page-0001.png" "sha256" "x"}]}}
        white (image 10 10 (constantly 0xffffff))
        black (image 10 10 (constantly 0))]
    (write-png! (str out "/px/a") "page-0001.png" white)
    (write-png! (str out "/px/b") "page-0001.png" black)
    (write-png! (str out "/px/c") "page-0001.png" white)
    (.mkdirs (io/file out "px/d"))
    (spit (io/file out "px/d/page-0001.png") "PNG truncated")
    (is (= :equal (:class (compare/compare-ok "render" env env (str out "/px/a") (str out "/px/c")))))
    (let [r (compare/compare-ok "render" env env (str out "/px/a") (str out "/px/b"))]
      (is (= :different (:class r)))
      (is (== 255.0 (get-in r [:detail :render :max-mean-abs]))))
    (testing "an unreadable PNG is a difference, not a harness error"
      (is (= :different (:class (compare/compare-ok "render" env env (str out "/px/a") (str out "/px/d"))))))))

(deftest failed-reopen-checks-make-a-write-case-different
  (is (= :different (#'compare/worst-class [:equal :both-fail])))
  (is (= :different (#'compare/worst-class [:fairly-close :pdfbox-fails])))
  (is (= :pdfcarton-fails (#'compare/worst-class [:equal :pdfcarton-fails :both-fail])))
  (is (= :equal (#'compare/worst-class [:equal :equal]))))

(deftest reproductions-are-self-contained
  (let [impl {:repro "clojure -M:pdfcli"}
        cmd (#'compare/repro impl ["form-fill" "--values" :values "--out" :out "in.pdf"] "/tmp/r"
                             "{\"A\":\"it's\"}")]
    (is (str/includes? cmd "printf '%s' '{\"A\":\"it'\\''s\"}' > /tmp/r/values.json && "))
    (is (str/includes? cmd "clojure -M:pdfcli form-fill --values /tmp/r/values.json --out /tmp/r/out.pdf in.pdf")))
  (testing "large documents under a custom --out stay in the performance subset"
    (is (some #{"/x/corpus/large.pdf"}
              (compare/perf-docs false ["a.pdf" "/x/corpus/large.pdf"] ["/x/corpus/large.pdf"])))))

(deftest derived-preflight-documents-are-staged
  (let [staged (compare/stage-derived-corpus! (str @out-root "/staged"))]
    (is (= 2 (count staged)))
    (is (every? #(.isFile (io/file (:path %))) staged))))

(defn -main [& _]
  (let [{:keys [fail error]} (try (run-tests 'pdfcli.compare-test)
                                  (finally (when (realized? out-root)
                                             (#'compare/delete-tree! (io/file @out-root)))))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
