(ns vibeformer.pdfcube-fontbox-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.pdfcube.fontbox-differential :as fontbox-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-fontbox-trace-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(deftest canonical-trace-covers-the-selected-behavior-contract
  (let [canonical
        (paths/resolve-path (paths/workspace-root)
                            "vibeformer" "validation" "pdfcube-fontbox"
                            "canonical-trace.tsv")
        summary (fontbox-differential/trace-summary canonical)]
    (is (= 45 (:observations summary)))
    (is (= fontbox-differential/required-trace-families
           (set (:families summary))))
    (is (= 45 (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (fontbox-differential/trace-summary
             (trace-file "encoding\tstandard\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-fontbox-differential-failed
             (:kind (ex-data error))))
      (is (some #{"cff"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str "encoding\tstandard\tvalue\n"
                  "encoding\tstandard\tvalue\n")
             "encoding\tmissing-value\n"]]
      (let [error
            (try
              (fontbox-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-fontbox-differential-failed
               (:kind (ex-data error))))))))
