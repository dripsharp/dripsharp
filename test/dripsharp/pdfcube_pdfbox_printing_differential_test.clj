(ns dripsharp.pdfcube-pdfbox-printing-differential-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pdfcube.pdfbox-printing-differential
             :as printing-differential]
            [dripsharp.tree-cleanup :as tree-cleanup])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private printing-boundary-paths
  [["validation" "pdfcube-pdfbox-printing" "Program.cs"]
   ["validation" "pdfcube-pdfbox-printing"
    "DripSharp.PdfCarton.PrintingHostSmoke.csproj"]
   ["targets" "pdfcube" "runtime"
    "DripSharp.PdfCarton.Fonts.Compat.cs"]
   ["src" "dripsharp" "pdfcube" "java_project.clj"]
   ["targets" "pdfcube" "destinations" "pdfbox.edn"]])

(defn- copy-printing-boundary! [source-root destination-root]
  (doseq [relative printing-boundary-paths]
    (let [source (apply paths/resolve-path source-root relative)
          destination (apply paths/resolve-path destination-root relative)]
      (Files/createDirectories (.getParent destination)
                               (make-array FileAttribute 0))
      (Files/writeString destination (Files/readString source)
                         (make-array OpenOption 0)))))

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

(deftest package-comparator-detects-a-deliberate-printing-mismatch
  (let [oracle (trace-file "failure\tinvalid-input\tIOException\n")
        perturbed (trace-file "")
        comparison
        (printing-differential/prove-mismatch-detection! oracle perturbed)]
    (is (:mismatch comparison))
    (is (= 2 (get-in comparison [:mismatch :line])))))

(deftest printing-probe-pins-the-extracted-constant-boundary
  (let [root (paths/workspace-root)]
    (is (= {:probe-interface "JavaPrintable"
            :constant-owner "JavaPrintConstants"
            :constants {"PAGE_EXISTS" 0
                        "NO_SUCH_PAGE" 1
                        "UNKNOWN_NUMBER_OF_PAGES" -1}
            :production-framework "netstandard2.0"
            :consumer-framework "net10.0"
            :consumer-package "DripSharp.PdfCarton"}
           (printing-differential/validate-printing-boundary! root)))
    (let [stale-root
          (Files/createTempDirectory
           "pdfcube-stale-printing-boundary-"
           (make-array FileAttribute 0))]
      (try
        (copy-printing-boundary! root stale-root)
        (let [probe
              (paths/resolve-path stale-root "validation"
                                  "pdfcube-pdfbox-printing" "Program.cs")
              current (Files/readString probe)]
          (Files/writeString
           probe
           (str/replace current
                        "JavaPrintConstants.PAGE_EXISTS;"
                        "JavaPrintable.PAGE_EXISTS;")
           (make-array OpenOption 0))
          (let [error
                (try
                  (printing-differential/validate-printing-boundary! stale-root)
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
            (is (= :pdfcube-pdfbox-printing-differential-failed
                   (:kind (ex-data error))))
            (is (= :printing-constant-boundary
                   (:contract (ex-data error))))
            (is (= :probe (:source (ex-data error))))))
        (finally
          (tree-cleanup/delete-tree! stale-root))))))

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
