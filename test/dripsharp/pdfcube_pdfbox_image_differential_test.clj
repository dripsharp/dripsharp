(ns dripsharp.pdfcube-pdfbox-image-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-image-differential
             :as image-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-image-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort image-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-image-contract
  (let [summary
        (image-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count image-differential/required-trace-families)
           (:observations summary)))
    (is (= image-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (image-differential/trace-summary
             (trace-file "codec-metadata\tjpx\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-image-differential-failed
             (:kind (ex-data error))))
      (is (some #{"full-pixels"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "codec-metadata\tcase-0\tvalue\n")
             "codec-metadata\tmissing-value\n"]]
      (let [error
            (try
              (image-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-image-differential-failed
               (:kind (ex-data error))))))))

(deftest required-fixtures-cover-both-managed-codecs
  (is (= #{"JBIG2Image.pdf"
           "JPXTestCMYK.pdf"
           "JPXTestGrey.pdf"
           "JPXTestRGB.pdf"}
         (set image-differential/required-fixtures))))
