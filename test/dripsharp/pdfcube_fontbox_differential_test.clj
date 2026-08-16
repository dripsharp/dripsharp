(ns dripsharp.pdfcube-fontbox-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.fontbox-differential :as fontbox-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-fontbox-trace-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString
     file
     (str "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1\n" contents)
     (make-array OpenOption 0))
    file))

(deftest canonical-trace-covers-the-selected-behavior-contract
  (let [canonical
        (paths/resolve-path (paths/workspace-root)
                            "targets" "pdfcube" "validation"
                            "fontbox-canonical.tsv")
        summary (fontbox-differential/trace-summary canonical)]
    (is (= 56 (:observations summary)))
    (is (= fontbox-differential/required-trace-families
           (set (:families summary))))
    (is (= 56 (count (set (:identities summary)))))))

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

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set (map (juxt :os :architecture)
                   fontbox-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner fontbox-differential/supported-hosts)))))
