(ns dripsharp.pdfcube-pdfbox-manipulation-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-manipulation-differential
             :as manipulation-differential])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [path (Files/createTempFile
              "pdfcube-pdfbox-manipulation-"
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
          (sort manipulation-differential/required-trace-families))))

(deftest trace-summary-requires-complete-unique-observations
  (testing "a complete trace reports deterministic family coverage"
    (let [summary
          (manipulation-differential/trace-summary
           (trace-file (complete-trace)))]
      (is (= (count manipulation-differential/required-trace-families)
             (:observations summary)))
      (is (= (vec (sort
                   manipulation-differential/required-trace-families))
             (:families summary)))))
  (testing "missing required manipulation behavior is rejected"
    (let [error
          (try
            (manipulation-differential/trace-summary
             (trace-file "clone-identity\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-manipulation-differential-failed
             (:kind (ex-data error))))
      (is (some #{"cross-reopen"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "clone-identity\tcase-0\tvalue\n")
             "clone-identity\tmissing-value\n"]]
      (let [error
            (try
              (manipulation-differential/trace-summary
               (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-manipulation-differential-failed
               (:kind (ex-data error))))))))
