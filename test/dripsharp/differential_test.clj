(ns dripsharp.differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- result-file [contents]
  (let [file (Files/createTempFile "dripsharp-differential" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(deftest normalized-result-comparison-is-fail-closed
  (let [expected (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\n")]
    (testing "identical independently produced observations pass"
      (is (= {:matched 2}
             (differential/compare-results
              expected
              (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\n")))))
    (testing "content perturbations fail at their exact observation"
      (is (= {:matched 1
              :mismatch {:line 2
                         :expected "case-a\tTYPED\tQg=="
                         :actual "case-a\tTYPED\tQw=="}}
             (differential/compare-results
              expected
              (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQw==\n")))))
    (testing "missing or added observations cannot compare equal"
      (is (= 3
             (get-in
              (differential/compare-results
               expected
               (result-file
                "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\nextra\tPROOF\tWA==\n"))
              [:mismatch :line]))))))

(deftest versioned-target-contracts-own-the-common-runner-data
  (doseq [[file expected-id expected-count]
          [["validation/pdfcube-io/differential.edn" :pdfcube-io 25]
           ["validation/pdfcube-fontbox/differential.edn"
            :pdfcube-fontbox 55]
           ["validation/pdfcube-xmpbox/differential.edn"
            :pdfcube-xmpbox-metadata 57]]
          :let [contract (differential/read-contract file)]]
    (is (= 1 (:schema-version contract)) file)
    (is (= expected-id (:id contract)) file)
    (is (= differential/observation-header
           (get-in contract [:observation :header]))
        file)
    (is (= expected-count
           (get-in contract [:observation :expected-count]))
        file)
    (is (seq (get-in contract [:observation :required-families])) file)
    (is (seq (get-in contract [:runner :supported-hosts])) file)))

(deftest versioned-contract-schema-is-exact-and-protects-runner-evidence
  (let [contract
        (differential/read-contract
         "validation/pdfcube-io/differential.edn")]
    (doseq [invalid
            [(assoc contract :unknown true)
             (assoc contract :schema-version 2)
             (assoc-in contract [:observation :header] "UNVERSIONED")
             (assoc contract :summary {:package {:forged true}})]]
      (let [error
            (try
              (differential/validate-contract! invalid)
              nil
              (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :pdfcube-io-differential-failed
               (:kind (ex-data error))))))))

(deftest versioned-observations-and-perturbation-fail-closed
  (let [contract
        (differential/read-contract
         "validation/pdfcube-io/differential.edn")
        canonical
        (paths/resolve-path (paths/workspace-root)
                            "validation" "pdfcube-io"
                            "CanonicalTrace.tsv")
        perturbed
        (Files/createTempFile "dripsharp-perturbed-" ".tsv"
                              (make-array FileAttribute 0))
        summary (differential/trace-summary contract canonical)]
    (is (= 1 (:schema-version summary)))
    (is (= differential/observation-header (:header summary)))
    (is (= 25 (:observations summary)))
    (is (= 27
           (get-in
            (differential/prove-perturbation!
             contract canonical perturbed)
            [:mismatch :line])))
    (let [error
          (try
            (differential/trace-summary
             contract
             (result-file "WRONG_HEADER\nbuffer\tread\tvalue\n"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-io-differential-failed
             (:kind (ex-data error))))
      (is (= differential/observation-header
             (:expected (ex-data error)))))))

(deftest package-contract-is-derived-from-the-authoritative-baseline
  (let [root (paths/workspace-root)
        contract
        (differential/read-contract
         "validation/pdfcube-io/differential.edn")
        profile (baseline/profile root :pdfcube :io)
        revision (baseline/upstream-revision :pdfcube)
        version (baseline/package-version :pdfcube "PdfCube.IO")
        legal (baseline/package-legal-files :pdfcube [:upstream])
        rows (:public-contract-rows profile)
        counts (:source-counts profile)
        package-proof
        {:verification
         {:generation
          {:project-input
           {:project-id (:source-project-id profile)
            :production-sources
            (vec (repeat (:ordinary counts) :source))
            :generated-production-sources
            (vec (repeat (:generated counts) :generated))}
           :source-project {:revision revision}
           :destination {:project {:target-framework "net10.0"}}
           :emission
           {:public-metadata {:required-rows rows :rows []}}}
          :public-surface
          {:strategy :complete-accessible-java-library
           :assemblies
           [{:assembly "PdfCube.IO" :contract-members rows}]}}
         :identity {:id "PdfCube.IO" :version version}
         :inspection
         {:dependencies
          [{:id "Microsoft.Extensions.Logging.Abstractions"
            :version "10.0.0"}]
          :package-files legal}
         :packages
         [{:primary? true
           :resource-proof
           {:assembly-identity
            {:name "PdfCube.IO"
             :version (baseline/assembly-version :pdfcube "PdfCube.IO")
             :dependency-assemblies []}
            :resources 0}}]
         :packing-summary {:clean-builds 2}}
        actual
        (differential/validate-package-contract!
         contract root package-proof)]
    (is (= (:source-counts profile)
           {:ordinary (:production-sources actual)
            :generated (:generated-production-sources actual)}))
    (is (= rows (get-in actual
                        [:public-contract :compiled-contract-members])))
    (let [error
          (try
            (differential/validate-package-contract!
             contract root
             (assoc-in package-proof [:identity :version] "drift"))
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :pdfcube-io-differential-failed
             (:kind (ex-data error))))
      (is (= version (get-in (ex-data error)
                             [:expected :version])))
      (is (= "drift" (get-in (ex-data error)
                              [:actual :version]))))))
