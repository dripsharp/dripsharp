(ns dripsharp.pdfcube-pdfbox-font-text-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-font-text-differential
             :as font-text-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-font-text-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort font-text-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-font-text-contract
  (let [summary
        (font-text-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count font-text-differential/required-trace-families)
           (:observations summary)))
    (is (= font-text-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (font-text-differential/trace-summary
             (trace-file "type-0\tdefault\tpresent\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-font-text-differential-failed
             (:kind (ex-data error))))
      (is (some #{"text-position"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "article-handling\tcase-0\tvalue\n")
             "type-0\tmissing-value\n"]]
      (let [error
            (try
              (font-text-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-font-text-differential-failed
               (:kind (ex-data error))))))))
