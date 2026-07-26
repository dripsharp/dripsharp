(ns dripsharp.pdfcube-pdfbox-security-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-security-differential
             :as security-differential])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [path (Files/createTempFile
              "pdfcube-pdfbox-security-"
              ".tsv"
              (make-array FileAttribute 0))]
    (Files/write
     path
     (.getBytes contents StandardCharsets/UTF_8)
     (make-array OpenOption 0))
    path))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue-" index "\n"))
          (sort security-differential/required-trace-families))))

(deftest trace-summary-requires-complete-unique-nonsensitive-observations
  (testing "a complete trace reports deterministic family coverage"
    (let [summary
          (security-differential/trace-summary
           (trace-file (complete-trace)))]
      (is (= (count security-differential/required-trace-families)
             (:observations summary)))
      (is (= (vec (sort
                   security-differential/required-trace-families))
             (:families summary)))))
  (testing "missing required security behavior is rejected"
    (let [error
          (try
            (security-differential/trace-summary
             (trace-file "byte-range\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-security-differential-failed
             (:kind (ex-data error))))
      (is (some #{"corrupt-cms"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "byte-range\tcase-0\tvalue\n")
             "byte-range\tmissing-value\n"]]
      (let [error
            (try
              (security-differential/trace-summary
               (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-security-differential-failed
               (:kind (ex-data error)))))))
  (testing "sensitive material is rejected from trace values"
    (let [error
          (try
            (security-differential/trace-summary
             (trace-file
              (str (complete-trace)
                   "byte-range\tdisclosure\t123456\n")))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-security-differential-failed
             (:kind (ex-data error)))))))
