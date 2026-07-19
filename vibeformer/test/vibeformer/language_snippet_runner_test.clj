(ns vibeformer.language-snippet-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.language-snippet-runner :as runner])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(defn- temp-directory []
  (Files/createTempDirectory "language-snippet-runner-test"
                             (make-array FileAttribute 0)))

(defn- write! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value StandardCharsets/UTF_8 (make-array OpenOption 0))
  file)

(defn- b64 [value]
  (.encodeToString (Base64/getEncoder) (.getBytes value StandardCharsets/UTF_8)))

(def ^:private cases
  [{:case-id "case/a" :semantic-family "fundamental-language"
    :source-family "basic" :product-scope "in-scope"}
   {:case-id "case/b" :semantic-family "fundamental-language"
    :source-family "types" :product-scope "in-scope-mixed-excluded-surface"
    :execution-requirements
    "engine-baseline;messagepack-debug-decoding;mixed-scope-observation"}
   {:case-id "case/c" :semantic-family "standard-library-renderer"
    :source-family "api" :product-scope "outside-epic-approved-exclusion"}
   {:case-id "case/d" :semantic-family "collections-generators"
    :source-family "mappings" :product-scope "in-scope"}])

(def ^:private validated {:cases cases})

(defn- package-file [^Path root name rows]
  (write! (.resolve root name) (#'runner/render-package-results rows)))

(defn- oracle-file [^Path root]
  (write! (.resolve root "expected.tsv")
          (str "case/a\tSUCCESS\t" (b64 "alpha") "\n"
               "case/b\tERROR\t" (b64 "beta") "\n"
               "case/c\tSUCCESS\t" (b64 "excluded oracle") "\n"
               "case/d\tSUCCESS\t" (b64 "delta") "\n")))

(defn- good-rows []
  [{:case-id "case/a" :status "SUCCESS" :payload-base64 (b64 "alpha")
    :logger-base64 "" :diagnostic-base64 ""}
   {:case-id "case/b" :status "ERROR" :payload-base64 (b64 "beta")
    :logger-base64 "" :diagnostic-base64 ""}
   {:case-id "case/c" :status "APPROVED_EXCLUSION" :payload-base64 ""
    :logger-base64 "" :diagnostic-base64 ""}
   {:case-id "case/d" :status "SUCCESS" :payload-base64 (b64 "delta")
    :logger-base64 "" :diagnostic-base64 ""}])

(defn- thrown-kind [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error (:kind (ex-data error)))))

(deftest comparator-retains-every-mismatch-and-family-baseline
  (let [root (temp-directory)
        oracle (oracle-file root)
        rows (-> (good-rows)
                 (assoc-in [1 :status] "TIMEOUT")
                 (assoc-in [1 :payload-base64] "")
                 (assoc-in [1 :diagnostic-base64] (b64 "bounded timeout"))
                 (assoc-in [3 :status] "ERROR")
                 (assoc-in [3 :payload-base64] (b64 "wrong")))
        package (package-file root "package.tsv" rows)
        comparison (runner/compare-package-results validated oracle package)
        baseline (runner/summarize-family-baseline validated package comparison)]
    (is (= {:total 4 :in-scope 3 :excluded 1 :matched 1 :mismatched 2}
           (select-keys comparison [:total :in-scope :excluded :matched :mismatched])))
    (is (= [[:execution-failure "case/b"] [:status-mismatch "case/d"]]
           (mapv (juxt :kind :case-id) (:mismatches comparison))))
    (is (= {:total 2 :in-scope 2 :excluded 0 :matched 1
            :approved-excluded-surface-boundaries 0 :conformant 1 :mismatched 1
            :success 1 :error 0 :timeout 1 :crash 0}
           (get baseline "fundamental-language")))
    (is (= 1 (get-in baseline ["standard-library-renderer" :excluded])))
    (is (= 1 (get-in baseline ["collections-generators" :mismatched])))))

(deftest result-validation-is-fail-closed-for-coverage-and-exclusions
  (let [root (temp-directory)
        good (good-rows)]
    (testing "coverage is exact and ordered"
      (is (= :package-language-snippet-result-coverage
             (thrown-kind #(runner/validate-package-results!
                            validated (package-file root "missing.tsv" (pop good))))))
      (is (= :duplicate-package-language-snippet-results
             (thrown-kind #(runner/validate-package-results!
                            validated
                            (package-file root "duplicate.tsv"
                                          (assoc good 3 (first good))))))))
    (testing "in-scope work cannot become an exclusion"
      (is (= :unapproved-package-language-snippet-exclusion
             (thrown-kind #(runner/validate-package-results!
                            validated
                            (package-file root "false-exclusion.tsv"
                                          (assoc-in good [0 :status]
                                                    "APPROVED_EXCLUSION")))))))
    (testing "approved exclusions and failure diagnostics remain explicit"
      (is (= :missing-approved-language-snippet-exclusion
             (thrown-kind #(runner/validate-package-results!
                            validated
                            (package-file root "silent-exclusion.tsv"
                                          (assoc (good-rows) 2
                                                 {:case-id "case/c" :status "SUCCESS"
                                                  :payload-base64 "" :logger-base64 ""
                                                  :diagnostic-base64 ""}))))))
      (is (= :missing-package-language-snippet-diagnostic
             (thrown-kind #(runner/validate-package-results!
                            validated
                            (package-file root "silent-timeout.tsv"
                                          (-> (good-rows)
                                              (assoc-in [0 :status] "TIMEOUT")
                                              (assoc-in [0 :payload-base64] ""))))))))))

(deftest repeated-results-and-deliberate-perturbation-are-detected
  (let [root (temp-directory)
        first (package-file root "first.tsv" (good-rows))
        identical (package-file root "identical.tsv" (good-rows))
        perturbed (package-file root "perturbed.tsv"
                                (assoc-in (good-rows) [0 :payload-base64] (b64 "PROVE")))]
    (is (= {:observations 4 :mismatches [] :deterministic? true}
           (runner/compare-runner-results validated first identical)))
    (is (false? (:deterministic?
                 (runner/compare-runner-results validated first perturbed))))
    (is (= ["case/a"]
           (mapv :case (:mismatches
                        (runner/compare-runner-results validated first perturbed)))))))

(deftest approved-excluded-transport-boundary-is-explicit-and-fail-closed
  (let [root (temp-directory)
        oracle (oracle-file root)
        boundary-message "MessagePack is excluded from the Vibeformer product target."
        boundary-rows (assoc-in (good-rows) [1 :payload-base64]
                                (b64 (str "Pkl.Core.PklBugException\n"
                                          boundary-message)))
        boundary-package (package-file root "boundary.tsv" boundary-rows)
        comparison (runner/compare-package-results validated oracle boundary-package)
        baseline (runner/summarize-family-baseline
                  validated boundary-package comparison)]
    (is (= {:total 4 :in-scope 3 :excluded 1 :matched 2
            :approved-excluded-surface-boundaries 1 :conformant 3 :mismatched 0}
           (select-keys comparison
                        [:total :in-scope :excluded :matched
                         :approved-excluded-surface-boundaries :conformant :mismatched])))
    (is (= :approved-excluded-surface-boundary
           (get-in comparison [:comparisons 1 :kind])))
    (is (= {:total 2 :in-scope 2 :excluded 0 :matched 1
            :approved-excluded-surface-boundaries 1 :conformant 2 :mismatched 0
            :success 1 :error 1 :timeout 0 :crash 0}
           (get baseline "fundamental-language")))
    (testing "a generic mixed-scope failure is not accepted as the transport boundary"
      (let [wrong (package-file root "wrong-boundary.tsv"
                                (assoc-in (good-rows) [1 :payload-base64]
                                          (b64 "MessagePack failed")))
            rejected (runner/compare-package-results validated oracle wrong)]
        (is (= 1 (:mismatched rejected)))
        (is (= :content-mismatch (get-in rejected [:mismatches 0 :kind])))))
    (testing "the boundary cannot be applied to an ordinary in-scope row"
      (let [ordinary (assoc-in validated [:cases 1 :product-scope] "in-scope")
            rejected (runner/compare-package-results ordinary oracle boundary-package)]
        (is (= 1 (:mismatched rejected)))
        (is (= :content-mismatch (get-in rejected [:mismatches 0 :kind])))))))

(deftest package-runner-source-is-confined-to-the-fresh-consumer
  (let [root (temp-directory)
        project (write! (.resolve root "Runner.csproj")
                        "<Project><ItemGroup><PackageReference Include=\"Pkl.Core\" Version=\"1.0.0\" /></ItemGroup></Project>")
        source (write! (.resolve root "Program.cs") "static class Program { }")
        outside-root (temp-directory)
        outside (write! (.resolve outside-root "Program.cs") "static class Program { }")]
    (is (= [] (:forbidden (#'runner/verify-source-isolation! root project source))))
    (is (= :language-snippet-source-isolation
           (thrown-kind #(#'runner/verify-source-isolation! root project outside))))))
