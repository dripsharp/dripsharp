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

(defn- package-contract-proof
  [production-target-framework consumer-target-framework]
  (let [contract preflight-differential/expected-package-contract
        public-contract (:public-contract contract)]
    {:verification
     {:generation
      {:project-input
       {:project-id (:project-id contract)
        :production-sources
        (vec (repeat (:production-sources contract) :source))
        :generated-production-sources
        (vec (repeat (:generated-production-sources contract) :source))}
       :source-project {:revision (:revision contract)}
       :destination
       {:project {:target-framework production-target-framework}}
       :emission
       {:public-metadata
        {:required-rows (:required-rows public-contract)
         :rows []}}}
      :public-surface
      {:strategy (:strategy public-contract)
       :assemblies
       [{:assembly (get-in contract [:assembly :name])
         :contract-members (:compiled-contract-members public-contract)}]}}
     :identity
     {:id (:package-id contract) :version (:version contract)}
     :inspection
     {:dependencies (:dependencies contract)
      :package-files (:package-files contract)}
     :packages
     [{:primary? true
       :resource-proof {:assembly-identity (:assembly contract)}
       :resources (vec (repeat (:resource-count contract) :resource))}]
     :packing-summary {:clean-builds (:clean-builds contract)}
     :dependency-proof
     {:target-framework consumer-target-framework
      :packages
      (vec preflight-differential/expected-restored-closure)}}))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

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
  (is (= "netstandard2.0"
         (:target-framework
          preflight-differential/expected-package-contract)))
  (is (= ["DripSharp.PdfCarton" "DripSharp.PdfCarton.Fonts"
          "DripSharp.PdfCarton.IO" "DripSharp.PdfCarton.Xmp"]
         (get-in preflight-differential/expected-package-contract
                 [:assembly :dependency-assemblies])))
  (is (= [{:id "DripSharp.PdfCarton.Xmp" :version "3.0.8-alpha.2"}
          {:id "DripSharp.PdfCarton" :version "3.0.8-alpha.2"}
          {:id "Microsoft.CSharp" :version "4.7.0"}
          {:id "Microsoft.Extensions.Logging.Abstractions"
           :version "10.0.0"}
          {:id "SkiaSharp" :version "4.150.1"}
          {:id "System.Memory" :version "4.6.3"}
          {:id "System.Text.Encoding.CodePages" :version "10.0.0"}]
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

(deftest package-contract-accepts-netstandard-and-rejects-production-framework-drift
  (let [valid-proof
        (package-contract-proof
         "netstandard2.0"
         preflight-differential/expected-consumer-target-framework)
        actual
        (preflight-differential/validate-package-contract! valid-proof)]
    (is (= (assoc preflight-differential/expected-package-contract
                  :restored-closure
                  preflight-differential/expected-restored-closure
                  :consumer-target-framework
                  preflight-differential/expected-consumer-target-framework)
           actual)))
  (testing "the former Preflight net10.0 production mismatch fails in isolation"
    (let [error
          (caught
           #(preflight-differential/validate-package-contract!
             (package-contract-proof
              "net10.0"
              preflight-differential/expected-consumer-target-framework)))
          expected (:expected (ex-data error))
          actual (:actual (ex-data error))]
      (is (= :pdfcube-preflight-differential-failed
             (:kind (ex-data error))))
      (is (= "netstandard2.0" (:target-framework expected)))
      (is (= "net10.0" (:target-framework actual)))
      (is (= (dissoc expected :target-framework)
             (dissoc actual :target-framework)))))
  (testing "missing, extra, and other production framework evidence fail closed"
    (doseq [target-framework
            [nil ["netstandard2.0" "net10.0"] "net9.0"]]
      (let [error
            (caught
             #(preflight-differential/validate-package-contract!
               (package-contract-proof
                target-framework
                preflight-differential/expected-consumer-target-framework)))]
        (is (= :pdfcube-preflight-differential-failed
               (:kind (ex-data error)))
            (pr-str {:target-framework target-framework}))))))

(deftest package-execution-framework-remains-net10
  (is (= "net10.0"
         preflight-differential/expected-consumer-target-framework))
  (doseq [project
          ["validation/pdfcube-preflight/DripSharp.PdfCarton.Preflight.HostSmoke.csproj"
           "validation/pdfcube-preflight/DripSharp.PdfCarton.Preflight.ExecutionProbe.csproj"]]
    (is (str/includes? (slurp project)
                       "<TargetFramework>net10.0</TargetFramework>")))
  (testing "missing or incorrect executable consumer evidence fails closed"
    (doseq [target-framework [nil "netstandard2.0" "net9.0"]]
      (let [error
            (caught
             #(preflight-differential/validate-package-contract!
               (package-contract-proof "netstandard2.0" target-framework)))]
        (is (= :pdfcube-preflight-differential-failed
               (:kind (ex-data error)))
            (pr-str {:consumer-target-framework target-framework}))))))

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
