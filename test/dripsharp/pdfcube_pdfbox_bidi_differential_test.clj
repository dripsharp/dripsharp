(ns dripsharp.pdfcube-pdfbox-bidi-differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-bidi-differential :as bidi])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-bidi-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (for [family (sort bidi/required-trace-families)]
           (str family "\tcase-0\t"
                (if (= "extraction" family) "true|value" "value")
                "\n"))))

(deftest trace-validation-accepts-the-bidi-contract
  (let [trace (str/replace (complete-trace)
                           "direction\tcase-0\tvalue"
                           "direction\tcase-0\t")
        summary (bidi/trace-summary (trace-file trace))]
    (is (= (count bidi/required-trace-families)
           (:observations summary)))
    (is (= bidi/required-trace-families
           (set (:families summary))))))

(deftest trace-validation-rejects-missing-and-failed-observations
  (testing "a missing family is rejected"
    (let [error
          (try
            (bidi/trace-summary (trace-file "analysis\tcase-0\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-bidi-differential-failed
             (:kind (ex-data error))))
      (is (some #{"extraction"} (:missing (ex-data error))))))
  (testing "an upstream fixture mismatch is rejected"
    (let [trace (str/replace (complete-trace)
                             "extraction\tcase-0\ttrue|"
                             "extraction\tcase-0\tfalse|")
          error
          (try
            (bidi/trace-summary (trace-file trace))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-bidi-differential-failed
             (:kind (ex-data error))))
      (is (= ["case-0"] (:fixtures (ex-data error)))))))
