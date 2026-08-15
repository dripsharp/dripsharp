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

(defn- package-contract-proof
  [target-framework]
  (let [contracts pdfbox-differential/expected-package-contract
        destinations
        (into
         {}
         (map
          (fn [[id contract]]
            [id
             {:package {:id id}
              :project
              {:assembly-name (get-in contract [:assembly :name])
               :target-framework target-framework}
              :source-project-id (:project-id contract)
              :mechanical-source {:revision (:revision contract)}}]))
         contracts)
        primary-id
        (->> contracts
             (keep (fn [[id contract]] (when (:primary? contract) id)))
             first)
        dependency-ids (vec (sort (disj (set (keys contracts)) primary-id)))
        package
        (fn [[id contract]]
          {:profile (:profile contract)
           :primary? (:primary? contract)
           :identity {:id id :version (:version contract)}
           :inspection
           {:dependencies (:dependencies contract)
            :package-files (:package-files contract)}
           :resource-proof {:assembly-identity (:assembly contract)}
           :resources (vec (repeat (:resources contract) :resource))})
        audit
        (fn [[_ contract]]
          {:assembly (get-in contract [:assembly :name])
           :contract-members (:contract-members contract)})
        dependency-emission
        (fn [id]
          {:profile (get-in contracts [id :profile])
           :destination (get destinations id)
           :public-metadata {:rows []}})]
    {:verification
     {:generation
      {:generation-profile {:profile (get-in contracts [primary-id :profile])}
       :destination (get destinations primary-id)
       :emission {:public-metadata {:rows []}}
       :dependency-emissions (mapv dependency-emission dependency-ids)}
      :public-surface
      {:strategy :complete-accessible-java-library
       :assemblies (mapv audit contracts)}}
     :packages (mapv package contracts)
     :packing-summary {:clean-builds 2}
     :dependency-proof
     {:packages (vec pdfbox-differential/expected-restored-closure)}}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

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
  (is (= #{"netstandard2.0"}
         (set (map :target-framework
                   (vals pdfbox-differential/expected-package-contract)))))
  (is (= {"DripSharp.PdfCarton.IO" 214
          "DripSharp.PdfCarton.Fonts" 1448
          "DripSharp.PdfCarton" 7438}
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

(deftest package-contract-accepts-netstandard-and-rejects-production-framework-drift
  (let [valid-proof (package-contract-proof "netstandard2.0")
        actual (pdfbox-differential/validate-package-contract! valid-proof)]
    (is (= pdfbox-differential/expected-package-contract
           (:packages actual)))
    (is (= pdfbox-differential/expected-restored-closure
           (:restored-closure actual)))
    (is (= 0 (:public-stubs actual))))
  (testing "the former three-package net10.0 mismatch fails in isolation"
    (let [error
          (caught
           #(pdfbox-differential/validate-package-contract!
             (package-contract-proof "net10.0")))
          expected (:expected (ex-data error))
          actual (:actual (ex-data error))
          without-framework
          (fn [contract]
            (update contract :packages
                    (fn [packages]
                      (into {}
                            (map (fn [[id package]]
                                   [id (dissoc package :target-framework)]))
                            packages))))]
      (is (= :pdfcube-pdfbox-differential-failed
             (:kind (ex-data error))))
      (is (= #{"netstandard2.0"}
             (set (map :target-framework (vals (:packages expected))))))
      (is (= #{"net10.0"}
             (set (map :target-framework (vals (:packages actual))))))
      (is (= (without-framework expected)
             (without-framework actual)))))
  (testing "missing, extra, and other production framework evidence fail closed"
    (doseq [target-framework
            [nil ["netstandard2.0" "net10.0"] "net9.0"]]
      (let [error
            (caught
             #(pdfbox-differential/validate-package-contract!
               (package-contract-proof target-framework)))]
        (is (= :pdfcube-pdfbox-differential-failed
               (:kind (ex-data error)))
            (pr-str {:target-framework target-framework}))))))

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
