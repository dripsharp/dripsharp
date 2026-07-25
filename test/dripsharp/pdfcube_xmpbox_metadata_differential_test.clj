(ns dripsharp.pdfcube-xmpbox-metadata-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.xmpbox-metadata-differential
             :as xmpbox-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-xmpbox-trace-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(deftest trace-validation-accepts-the-selected-metadata-contract
  (let [contents
        (str
         "namespace\tmetadata\tvalue\n"
         "registry\tmapping\tvalue\n"
         "simple\ttext\tvalue\n"
         "structured\tjob\tvalue\n"
         "array\tsequence\tvalue\n"
         "lang-alt\tlocale\tvalue\n"
         "date\toffset\tvalue\n"
         "invalid\tvalues\tvalue\n"
         "fixture\tupstream\tvalue\n"
         "parser\tvalid\tvalue\n"
         "parser-failure\tinvalid\tvalue\n"
         "strict-lenient\tmode\tvalue\n"
         "extension\tpdfa\tvalue\n"
         "serialization\tbytes\tvalue\n"
         "round-trip\tmetadata\tvalue\n"
         "security\tdoctype\tvalue\n"
         "lifetime\tstreams\tvalue\n")
        summary (xmpbox-differential/trace-summary (trace-file contents))]
    (is (= 17 (:observations summary)))
    (is (= xmpbox-differential/required-trace-families
           (set (:families summary))))
    (is (= 17 (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing behavior family is rejected"
    (let [error
          (try
            (xmpbox-differential/trace-summary
             (trace-file "namespace\tmetadata\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-xmpbox-metadata-differential-failed
             (:kind (ex-data error))))
      (is (some #{"fixture"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str "namespace\tmetadata\tvalue\n"
                  "namespace\tmetadata\tvalue\n")
             "namespace\tmissing-value\n"]]
      (let [error
            (try
              (xmpbox-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-xmpbox-metadata-differential-failed
               (:kind (ex-data error))))))))

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set (map (juxt :os :architecture)
                   xmpbox-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner xmpbox-differential/supported-hosts)))))
