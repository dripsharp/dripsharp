(ns dripsharp.pdfcube-pdfbox-document-lifecycle-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-document-lifecycle-differential
             :as lifecycle-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-lifecycle-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort lifecycle-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-document-lifecycle-contract
  (let [summary
        (lifecycle-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count lifecycle-differential/required-trace-families)
           (:observations summary)))
    (is (= lifecycle-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (lifecycle-differential/trace-summary
             (trace-file "catalog\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-document-lifecycle-differential-failed
             (:kind (ex-data error))))
      (is (some #{"content-stream"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "catalog\tcase-0\tvalue\n")
             "catalog\tmissing-value\n"]]
      (let [error
            (try
              (lifecycle-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-document-lifecycle-differential-failed
               (:kind (ex-data error))))))))
