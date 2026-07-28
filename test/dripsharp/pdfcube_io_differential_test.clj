(ns dripsharp.pdfcube-io-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.io-differential :as io-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-io-trace-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString
     file
     (str "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1\n" contents)
     (make-array OpenOption 0))
    file))

(deftest canonical-trace-covers-the-complete-selected-behavior-contract
  (let [canonical (paths/resolve-path (paths/workspace-root)
                                      "validation" "pdfcube-io"
                                      "CanonicalTrace.tsv")
        summary (io-differential/trace-summary canonical)]
    (is (= 25 (:observations summary)))
    (is (= io-differential/required-trace-families
           (set (:families summary))))
    (is (= 25 (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error (try
                  (io-differential/trace-summary
                   (trace-file "buffer\tread\tvalue\n"))
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-io-differential-failed (:kind (ex-data error))))
      (is (some #{"memory-mapped"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str "buffer\tread\tvalue\n"
                  "buffer\tread\tvalue\n")
             "buffer\tmissing-value\n"]]
      (let [error (try
                    (io-differential/trace-summary (trace-file contents))
                    nil
                    (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-io-differential-failed (:kind (ex-data error))))))))

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set (map (juxt :os :architecture)
                   io-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner io-differential/supported-hosts)))))
