(ns dripsharp.pdfcube-pdfbox-interchange-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-interchange-differential
             :as interchange-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-interchange-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort interchange-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-interchange-contract
  (let [summary
        (interchange-differential/trace-summary
         (trace-file (complete-trace)))]
    (is (= (count interchange-differential/required-trace-families)
           (:observations summary)))
    (is (= interchange-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (interchange-differential/trace-summary
             (trace-file "structure-model\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-interchange-differential-failed
             (:kind (ex-data error))))
      (is (some #{"xfdf-roundtrip"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "fdf-failure\tcase-0\tvalue\n")
             "fdf-model\tmissing-value\n"]]
      (let [error
            (try
              (interchange-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-interchange-differential-failed
               (:kind (ex-data error))))))))
