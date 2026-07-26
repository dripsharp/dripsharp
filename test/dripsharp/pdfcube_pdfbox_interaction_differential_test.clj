(ns dripsharp.pdfcube-pdfbox-interaction-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-interaction-differential
             :as interaction-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-pdfbox-interaction-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort interaction-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-interaction-contract
  (let [summary
        (interaction-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count interaction-differential/required-trace-families)
           (:observations summary)))
    (is (= interaction-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (interaction-differential/trace-summary
             (trace-file "form-model\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-interaction-differential-failed
             (:kind (ex-data error))))
      (is (some #{"attachment"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "action\tcase-0\tvalue\n")
             "action\tmissing-value\n"]]
      (let [error
            (try
              (interaction-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-interaction-differential-failed
               (:kind (ex-data error))))))))
