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
          [["targets/pdfcube/validation/io.edn" :pdfcube-io 25]
           ["targets/pdfcube/validation/fontbox-common.edn"
            :pdfcube-fontbox 55]
           ["targets/pdfcube/validation/xmpbox.edn"
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
         "targets/pdfcube/validation/io.edn")]
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

(deftest differential-contract-reader-rejects-trailing-edn
  (let [root (paths/workspace-root)
        source (paths/resolve-path
                root "targets" "pdfcube" "validation" "io.edn")
        file (Files/createTempFile "dripsharp-differential-contract-" ".edn"
                                   (make-array FileAttribute 0))]
    (Files/writeString file
                       (str (Files/readString source) "\n{:hidden true}\n")
                       (make-array OpenOption 0))
    (let [error
          (try
            (differential/read-contract file)
            nil
            (catch clojure.lang.ExceptionInfo caught caught))]
      (is (= :trailing-data (:reason (ex-data error)))))))

(deftest versioned-observations-and-perturbation-fail-closed
  (let [contract
        (differential/read-contract
         "targets/pdfcube/validation/io.edn")
        canonical
        (paths/resolve-path (paths/workspace-root)
                            "targets" "pdfcube" "validation"
                            "io-canonical.tsv")
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
         "targets/pdfcube/validation/io.edn")
        profile (baseline/profile root :pdfcube :io)
        revision (baseline/upstream-revision :pdfcube)
        version (baseline/package-version :pdfcube "DripSharp.PdfCarton.IO")
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
           [{:assembly "DripSharp.PdfCarton.IO" :contract-members rows}]}}
         :identity {:id "DripSharp.PdfCarton.IO" :version version}
         :inspection
         {:dependencies
         [{:id "Microsoft.Extensions.Logging.Abstractions"
            :version "10.0.0"}]
          :package-files
          (conj legal
                {:kind :readme
                 :path "README.md"
                 :sha256 (apply str (repeat 64 "a"))})}
         :packages
         [{:primary? true
           :resource-proof
           {:assembly-identity
            {:name "DripSharp.PdfCarton.IO"
             :version (baseline/assembly-version :pdfcube "DripSharp.PdfCarton.IO")
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
    (is (= legal (:package-files actual)))
    (let [assembly-version "99.0.0.0"
          required-rows (inc rows)
          compiled-members (+ 2 rows)
          pinned-contract
          (-> contract
              (assoc-in [:package-contract :assembly-version]
                        assembly-version)
              (assoc-in [:package-contract :public-contract-rows]
                        required-rows)
              (assoc-in [:package-contract :compiled-contract-members]
                        compiled-members))
          pinned-proof
          (-> package-proof
              (assoc-in [:packages 0 :resource-proof :assembly-identity
                         :version]
                        assembly-version)
              (assoc-in [:verification :generation :emission
                         :public-metadata :required-rows]
                        required-rows)
              (assoc-in [:verification :public-surface :assemblies 0
                         :contract-members]
                        compiled-members))]
      (is (= assembly-version
             (get-in
              (differential/validate-package-contract!
               pinned-contract root pinned-proof)
              [:assembly :version])))
      (is (= required-rows
             (get-in
              (differential/validate-package-contract!
               pinned-contract root pinned-proof)
              [:public-contract :required-rows])))
      (is (= compiled-members
             (get-in
              (differential/validate-package-contract!
               pinned-contract root pinned-proof)
              [:public-contract :compiled-contract-members]))))
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

(deftest sqltrellis-netstandard-package-contract-rejects-drift
  (let [root (paths/workspace-root)
        contract
        (differential/read-contract
         "targets/sqltrellis/validation/behavior.edn")
        profile (baseline/profile root :sqltrellis :core)
        revision (baseline/upstream-revision :sqltrellis)
        version
        (baseline/package-version :sqltrellis "DripSharp.SqlTrellis")
        legal (baseline/package-legal-files :sqltrellis [:upstream])
        counts (:source-counts profile)
        dependencies
        [{:id "Microsoft.CSharp" :version "4.7.0"}
         {:id "System.Memory" :version "4.6.3"}
         {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
        rows 8852
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
           :destination {:project {:target-framework "netstandard2.0"}}
           :emission
           {:public-metadata {:required-rows rows :rows []}}}
          :public-surface
          {:strategy :complete-accessible-java-library
           :assemblies
           [{:assembly "DripSharp.SqlTrellis"
             :contract-members rows}]}}
         :identity {:id "DripSharp.SqlTrellis" :version version}
         :inspection
         {:dependencies dependencies
          :package-files
          (conj legal
                {:kind :readme
                 :path "README.md"
                 :sha256 (apply str (repeat 64 "a"))})}
         :packages
         [{:primary? true
           :resource-proof
           {:assembly-identity
            {:name "DripSharp.SqlTrellis"
             :version "5.3.0.0"
             :dependency-assemblies []}
            :resources 1}}]
         :packing-summary {:clean-builds 2}}
        failure
        (fn [proof]
          (try
            (differential/validate-package-contract! contract root proof)
            nil
            (catch clojure.lang.ExceptionInfo caught caught)))]
    (is (= {:target-framework "netstandard2.0"
            :dependencies dependencies
            :public-contract-rows rows
            :compiled-contract-members rows}
           (select-keys (:package-contract contract)
                        [:target-framework :dependencies
                         :public-contract-rows
                         :compiled-contract-members])))
    (testing "the corrected dependency and public-count contract is accepted"
      (is (= {:dependencies dependencies
              :required-rows rows
              :compiled-contract-members rows}
             (let [actual
                   (differential/validate-package-contract!
                    contract root package-proof)]
               {:dependencies (:dependencies actual)
                :required-rows
                (get-in actual [:public-contract :required-rows])
                :compiled-contract-members
                (get-in actual
                        [:public-contract :compiled-contract-members])}))))
    (testing "dependency inventory drift is rejected"
      (let [error (failure (update-in package-proof
                                      [:inspection :dependencies]
                                      pop))]
        (is (= :sqltrellis-behavior-differential-failed
               (:kind (ex-data error))))
        (is (= dependencies
               (get-in (ex-data error) [:expected :dependencies])))
        (is (= (pop dependencies)
               (get-in (ex-data error) [:actual :dependencies])))))
    (testing "generated public-count drift is rejected"
      (let [error
            (failure
             (update-in package-proof
                        [:verification :generation :emission
                         :public-metadata :required-rows]
                        dec))]
        (is (= :sqltrellis-behavior-differential-failed
               (:kind (ex-data error))))
        (is (= rows
               (get-in (ex-data error)
                       [:expected :public-contract :required-rows])))
        (is (= (dec rows)
               (get-in (ex-data error)
                       [:actual :public-contract :required-rows])))))
    (testing "compiled public-count drift is rejected"
      (let [error
            (failure
             (update-in package-proof
                        [:verification :public-surface :assemblies 0
                         :contract-members]
                        dec))]
        (is (= :sqltrellis-behavior-differential-failed
               (:kind (ex-data error))))
        (is (= rows
               (get-in (ex-data error)
                       [:expected :public-contract
                        :compiled-contract-members])))
        (is (= (dec rows)
               (get-in (ex-data error)
                       [:actual :public-contract
                        :compiled-contract-members])))))))

(deftest differential-package-file-projection-excludes-packed-readme
  (is (= [{:kind :license :path "LICENSE.txt" :sha256 "license"}
          {:kind :notice :path "NOTICE.txt" :sha256 "notice"}]
         (differential/legal-package-files
          [{:kind :license :path "LICENSE.txt" :sha256 "license"}
           {:kind :notice :path "NOTICE.txt" :sha256 "notice"}
           {:kind :readme :path "README.md" :sha256 "readme"}]))))
