(ns dripsharp.pdfcube-pdfbox-printing-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-printing-differential
             :as printing-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile
              "pdfcube-pdfbox-printing-" ".tsv"
              (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort printing-differential/required-trace-families))))

(deftest trace-validation-accepts-the-printing-contract
  (let [summary
        (printing-differential/trace-summary
         (trace-file (complete-trace)))]
    (is (= (count printing-differential/required-trace-families)
           (:observations summary)))
    (is (= printing-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (printing-differential/trace-summary
             (trace-file "paper\tdefault\tletter\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-printing-differential-failed
             (:kind (ex-data error))))
      (is (some #{"rasterization"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace) "paper\tcase-7\tvalue\n")
             "paper\tmissing-value\n"]]
      (let [error
            (try
              (printing-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-pdfbox-printing-differential-failed
               (:kind (ex-data error))))))))

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set (map (juxt :os :architecture)
                   printing-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner printing-differential/supported-hosts)))))
