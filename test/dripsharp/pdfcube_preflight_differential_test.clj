(ns dripsharp.pdfcube-preflight-differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.preflight-differential
             :as preflight-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file (Files/createTempFile "pdfcube-preflight-" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- complete-trace []
  (apply str
         (map-indexed
          (fn [index family]
            (str family "\tcase-" index "\tvalue\n"))
          (sort preflight-differential/required-trace-families))))

(deftest trace-validation-accepts-the-selected-preflight-contract
  (let [summary
        (preflight-differential/trace-summary (trace-file (complete-trace)))]
    (is (= (count preflight-differential/required-trace-families)
           (:observations summary)))
    (is (= preflight-differential/required-trace-families
           (set (:families summary))))
    (is (= (:observations summary)
           (count (set (:identities summary)))))))

(deftest trace-validation-fails-closed
  (testing "a missing execution-model family is rejected"
    (let [error
          (try
            (preflight-differential/trace-summary
             (trace-file "configuration\tdefault\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-preflight-differential-failed
             (:kind (ex-data error))))
      (is (some #{"parser-valid"} (:missing (ex-data error))))))
  (testing "duplicate and malformed observations are rejected"
    (doseq [contents
            [(str (complete-trace)
                  (first (sort preflight-differential/required-trace-families))
                  "\tcase-0\tvalue\n")
             "configuration\tmissing-value\n"]]
      (let [error
            (try
              (preflight-differential/trace-summary (trace-file contents))
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-preflight-differential-failed
               (:kind (ex-data error))))))))

(deftest package-comparator-detects-a-deliberate-mismatch
  (let [oracle
        (trace-file "failure\tinvalid-input\tIOException\n")
        perturbed (trace-file "")
        comparison
        (preflight-differential/prove-mismatch-detection!
         oracle perturbed)]
    (is (:mismatch comparison))
    (is (= 2 (get-in comparison [:mismatch :line])))))

(deftest package-contract-pins-preflight-and-its-restored-closure
  (is (= "DripSharp.PdfCarton.Preflight"
         (:package-id preflight-differential/expected-package-contract)))
  (is (= ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Fonts"
          "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Xmp"]
         (get-in preflight-differential/expected-package-contract
                 [:assembly :dependency-assemblies])))
  (is (= [{:id "DripSharp.PdfCarton.Xmp" :version "3.0.8-dripsharp.0"}
          {:id "DripSharp.PdfCarton" :version "3.0.8-dripsharp.0"}
          {:id "Microsoft.Extensions.Logging.Abstractions"
           :version "10.0.0"}
          {:id "SkiaSharp" :version "4.150.1"}]
         (:dependencies
          preflight-differential/expected-package-contract)))
  (is (= {:strategy :complete-accessible-java-library
          :required-rows 946
          :compiled-contract-members 946
          :public-stubs 0}
         (:public-contract
          preflight-differential/expected-package-contract)))
  (is (= 12
         (count preflight-differential/expected-restored-closure))))

(deftest package-consumer-calls-the-erased-position-api
  (let [source (slurp "validation/pdfcube-preflight/Program.cs")]
    (is (str/includes?
         source
         "path.GetClosestTypePosition(typeof(string))"))
    (is (str/includes?
         source
         "path.GetClosestTypePosition(typeof(int))"))
    (is (not (str/includes? source "GetClosestTypePosition<")))))

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set
          (map (juxt :os :architecture)
               preflight-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner preflight-differential/supported-hosts)))))
