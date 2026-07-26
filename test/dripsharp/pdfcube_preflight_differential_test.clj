(ns dripsharp.pdfcube-preflight-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.preflight-differential
             :as preflight-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-preflight-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort preflight-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-preflight-contract
  (let [summary
        (preflight-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count preflight-differential/required-trace-families)
           (:observations summary)))
    (is (= preflight-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing execution-model family is rejected"
    (let [error
          (try
            (preflight-differential/trace-summary
             (trace-file "configuration\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-preflight-differential-failed
             (:kind (ex-data error))))
      (is (some #{"parser-valid"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "configuration\tcase-0\tvalue\n")
             "configuration\tmissing-value\n"]]
      (let [error
            (try
              (preflight-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-preflight-differential-failed
               (:kind (ex-data error))))))))
