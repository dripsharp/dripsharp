(ns dripsharp.pdfcube-pdfbox-differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.pdfcube.pdfbox-differential
             :as pdfbox-differential])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- trace-file [contents]
  (let [file
        (Files/createTempFile
         "pdfcube-pdfbox-aggregate-" ".tsv"
         (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(deftest aggregate-workflow-contract-is-exact
  (is (= pdfbox-differential/required-workflows
         (pdfbox-differential/workflow-coverage
          pdfbox-differential/verification-slices)))
  (is (= [:low-level :document-lifecycle :manipulation :font-text
          :rendering :interaction :security :printing]
         (mapv :id pdfbox-differential/verification-slices))))

(deftest aggregate-workflow-contract-fails-closed
  (doseq [slices
          [(pop pdfbox-differential/verification-slices)
           (conj pdfbox-differential/verification-slices
                 {:id :unexpected :workflows #{:unexpected}})]]
    (let [error
          (try
            (pdfbox-differential/workflow-coverage slices)
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-pdfbox-differential-failed
             (:kind (ex-data error)))))))

(deftest aggregate-comparator-detects-a-deliberate-mismatch
  (let [oracle (trace-file "failure\tinvalid-input\tIOException\n")
        perturbed (trace-file "")
        comparison
        (pdfbox-differential/prove-mismatch-detection! oracle perturbed)]
    (is (:mismatch comparison))
    (is (= 2 (get-in comparison [:mismatch :line])))))

(deftest package-contract-pins-the-translated-closure
  (is (= #{"DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Fonts" "DripSharp.PdfCarton"}
         (set (keys pdfbox-differential/expected-package-contract))))
  (is (= {"DripSharp.PdfCarton.IO" 177
          "DripSharp.PdfCarton.Fonts" 1440
          "DripSharp.PdfCarton" 7424}
         (into {}
               (map (fn [[id contract]]
                      [id (:contract-members contract)]))
               pdfbox-differential/expected-package-contract)))
  (is (= {"DripSharp.PdfCarton.IO"
          [{:id "Microsoft.CSharp" :version "4.7.0"}
           {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
           {:id "System.Memory" :version "4.6.3"}
           {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
          "DripSharp.PdfCarton.Fonts"
          [{:id "DripSharp.PdfCarton.IO" :version "3.0.8-alpha.1"}
           {:id "Microsoft.CSharp" :version "4.7.0"}
           {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
           {:id "SkiaSharp" :version "4.150.1"}
           {:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
           {:id "System.Formats.Asn1" :version "10.0.0"}
           {:id "System.Memory" :version "4.6.3"}
           {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
          "DripSharp.PdfCarton"
          [{:id "DripSharp.PdfCarton.Fonts" :version "3.0.8-alpha.1"}
           {:id "DripSharp.PdfCarton.IO" :version "3.0.8-alpha.1"}
           {:id "Microsoft.CSharp" :version "4.7.0"}
           {:id "Microsoft.Extensions.Logging.Abstractions" :version "10.0.0"}
           {:id "SkiaSharp" :version "4.150.1"}
           {:id "System.Memory" :version "4.6.3"}
           {:id "System.Security.Cryptography.Pkcs" :version "10.0.0"}
           {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]}
         (into {}
               (map (fn [[id contract]] [id (:dependencies contract)]))
               pdfbox-differential/expected-package-contract)))
  (testing "runtime closure carries native assets for all supported OS families"
    (is (every?
         pdfbox-differential/expected-restored-closure
         [{:id "SkiaSharp.NativeAssets.Linux" :version "4.150.1"}
          {:id "SkiaSharp.NativeAssets.macOS" :version "4.150.1"}
          {:id "SkiaSharp.NativeAssets.Win32" :version "4.150.1"}]))))

(deftest supported-host-matrix-is-exact
  (is (= #{["windows" "x64"] ["windows" "arm64"]
           ["linux" "x64"] ["linux" "arm64"]
           ["macos" "x64"] ["macos" "arm64"]}
         (set (map (juxt :os :architecture)
                   pdfbox-differential/supported-hosts))))
  (is (= #{"windows-2025" "windows-11-arm"
           "ubuntu-24.04" "ubuntu-24.04-arm"
           "macos-15-intel" "macos-15"}
         (set (map :runner pdfbox-differential/supported-hosts)))))
