(ns dripsharp.pdfcube-pdfbox-graphics-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-graphics-differential
             :as graphics-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-graphics-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort graphics-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-graphics-contract
  (let [summary
        (graphics-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count graphics-differential/required-trace-families)
           (:observations summary)))
    (is (= graphics-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (graphics-differential/trace-summary
             (trace-file "backend\tdefault\tcpu\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-graphics-differential-failed
             (:kind (ex-data error))))
      (is (some #{"operator-dispatch"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "backend\tcase-0\tcpu\n")
             "backend\tmissing-value\n"]]
      (let [error
            (try
              (graphics-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-graphics-differential-failed
               (:kind (ex-data error))))))))
