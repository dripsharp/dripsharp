(ns vibeformer.pkl-core-corpus-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.pkl-core-corpus-runner :as runner]
            [vibeformer.pkl-core-test-contract :as contract])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util Base64]))

(defn- temp-directory []
  (Files/createTempDirectory "pkl-core-corpus-runner-test"
                             (make-array FileAttribute 0)))

(defn- write!
  [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value StandardCharsets/UTF_8 (make-array OpenOption 0))
  file)

(defn- b64
  [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes value StandardCharsets/UTF_8)))

(def ^:private cases
  [{:case-id "case/a" :junit-unique-id "[engine:junit]/[class:A]/[method:a()]"
    :source-path "pkl-core/src/test/kotlin/A.kt"
    :source-sha256 (apply str (repeat 64 "a")) :source-line "10"
    :behavior-family "evaluation-runtime"
    :product-classification "jvm-shared-product-behavior"
    :execution-owner "complete-pkl-core-runner"
    :expected-outcome "assertions-succeed" :platform-conditions "all-supported-hosts"}
   {:case-id "case/b" :junit-unique-id "[engine:junit]/[class:B]/[method:b()]"
    :source-path "pkl-core/src/test/kotlin/B.kt"
    :source-sha256 (apply str (repeat 64 "b")) :source-line "20"
    :behavior-family "loading-security-project-package"
    :product-classification "idiomatic-dotnet-adaptation"
    :execution-owner "complete-pkl-core-runner"
    :expected-outcome "assertions-succeed" :platform-conditions "all-supported-hosts"}
   {:case-id "case/c" :junit-unique-id "[engine:junit]/[class:C]/[method:c()]"
    :source-path "pkl-core/src/test/kotlin/C.kt"
    :source-sha256 (apply str (repeat 64 "c")) :source-line "30"
    :behavior-family "excluded-format-transport"
    :product-classification "user-approved-excluded-surface"
    :execution-owner "approved-exclusion-audit"
    :expected-outcome "assertions-succeed" :platform-conditions "all-supported-hosts"}
   {:case-id "case/d" :junit-unique-id "[engine:junit]/[class:D]/[method:d()]"
    :source-path "pkl-core/src/test/kotlin/D.kt"
    :source-sha256 (apply str (repeat 64 "d")) :source-line "40"
    :behavior-family "test-infrastructure"
    :product-classification "test-infrastructure-only-mechanics"
    :execution-owner "test-infrastructure-audit"
    :expected-outcome "assertions-succeed" :platform-conditions "all-supported-hosts"}
   {:case-id "case/e" :junit-unique-id "[engine:junit]/[class:E]/[method:e()]"
    :source-path "pkl-core/src/test/kotlin/E.kt"
    :source-sha256 (apply str (repeat 64 "e")) :source-line "50"
    :behavior-family "public-api-platform"
    :product-classification "idiomatic-dotnet-adaptation"
    :execution-owner "complete-pkl-core-runner"
    :expected-outcome "enabled-on-windows" :platform-conditions "os=windows"}])

(def ^:private validated {:cases cases})

(def ^:private provenance-fields
  [:junit-unique-id :source-path :source-sha256 :source-line :behavior-family
   :product-classification :execution-owner])

(defn- result-row
  [case-data origin status observation diagnostic]
  (merge (select-keys case-data (into [:case-id] provenance-fields))
         {:origin origin
          :upstream-revision contract/pinned-upstream-revision
          :status status
          :observation-base64 (b64 observation)
          :diagnostic-base64 (b64 diagnostic)}))

(defn- upstream-rows []
  (mapv (fn [case-data]
          (result-row case-data "upstream-jvm"
                      (if (= "enabled-on-windows" (:expected-outcome case-data))
                        "CONDITION_AUDIT" "PASS")
                      (:expected-outcome case-data)
                      (if (= "enabled-on-windows" (:expected-outcome case-data))
                        "Focused condition oracle: os=windows" "")))
        cases))

(defn- package-rows []
  (mapv (fn [case-data]
          (case (:product-classification case-data)
            "user-approved-excluded-surface"
            (result-row case-data "package-dotnet" "APPROVED_EXCLUSION" "" "")

            "test-infrastructure-only-mechanics"
            (result-row case-data "package-dotnet" "TEST_INFRASTRUCTURE" "" "")

            (result-row case-data "package-dotnet" "PASS"
                        (:expected-outcome case-data) "")))
        cases))

(defn- result-file
  [^Path root name rows]
  (runner/write-results! (.resolve root name) rows))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error (:kind (ex-data error)))))

(deftest result-validation-is-exact-ordered-and-provenance-bound
  (let [root (temp-directory)
        rows (package-rows)]
    (is (= 5 (count (:rows (runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "good.tsv" rows))))))
    (testing "missing and duplicate rows fail before comparison"
      (is (= :pkl-core-corpus-result-coverage
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "missing.tsv" (pop rows))))))
      (is (= :duplicate-pkl-core-corpus-results
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "duplicate.tsv"
                                         (assoc rows 1 (first rows))))))))
    (testing "stale and skipped rows fail closed"
      (is (= :stale-pkl-core-corpus-provenance
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "stale.tsv"
                                         (assoc-in rows [0 :source-line] "999"))))))
      (is (= :malformed-pkl-core-corpus-result
             (thrown-kind #(runner/validate-results!
                            validated "package-dotnet"
                            (result-file root "skipped.tsv"
                                         (assoc-in rows [0 :status] "SKIPPED")))))))))

(deftest dispositions-cannot-hide-product-work
  (let [root (temp-directory)
        rows (package-rows)]
    (is (= :unapproved-pkl-core-corpus-disposition
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "false-exclusion.tsv"
                                       (assoc-in rows [0 :status]
                                                 "APPROVED_EXCLUSION"))))))
    (is (= :missing-pkl-core-corpus-exclusion
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "missing-exclusion.tsv"
                                       (assoc-in rows [2 :status] "PASS"))))))
    (is (= :missing-pkl-core-corpus-test-infrastructure-audit
           (thrown-kind #(runner/validate-results!
                          validated "package-dotnet"
                          (result-file root "missing-audit.tsv"
                                       (assoc-in rows [3 :status] "PASS"))))))))

(deftest comparison-retains-failures-and-repetition-is-byte-exact
  (let [root (temp-directory)
        upstream (result-file root "upstream.tsv" (upstream-rows))
        package (result-file root "package.tsv" (package-rows))
        repeated (result-file root "package-repeated.tsv" (package-rows))
        failed (result-file root "package-failed.tsv"
                            (-> (package-rows)
                                (assoc-in [1 :status] "TIMEOUT")
                                (assoc-in [1 :observation-base64] "")
                                (assoc-in [1 :diagnostic-base64]
                                          (b64 "bounded timeout"))))
        comparison (runner/compare-results validated upstream failed)]
    (is (= {:total 5 :matched 2 :approved-exclusions 1
            :test-infrastructure-audits 1 :mismatched 1}
           (select-keys comparison
                        [:total :matched :approved-exclusions
                         :test-infrastructure-audits :mismatched])))
    (is (= :package-execution-failure (get-in comparison [:mismatches 0 :kind])))
    (is (:deterministic?
         (runner/compare-repeated-results validated "package-dotnet"
                                          package repeated)))
    (is (= :pkl-core-corpus-mismatch
           (thrown-kind #(runner/require-conformant! comparison))))))

(deftest deliberate-control-suite-proves-every-fail-closed-path
  (let [controls (runner/prove-fail-closed-controls!
                  validated (.resolve (temp-directory) "controls"))]
    (is (= #{:jvm-perturbation :package-perturbation :crash :timeout
             :missing :duplicate :stale}
           (set (keys controls))))
    (is (every? true? (vals controls)))))

(deftest package-runner-source-is-confined-to-public-package-consumption
  (let [root (temp-directory)
        project (write! (.resolve root "Pkl.Core.PackageConsumer.csproj")
                        (str "<Project><ItemGroup>"
                             "<PackageReference Include=\"Pkl.Core\" Version=\"1.0.0\" />"
                             "</ItemGroup></Project>"))
        source (write! (.resolve root "Program.cs")
                       "using Pkl.Core; using Pkl.Parser; static class Program { }")]
    (is (= [] (:forbidden (#'runner/verify-package-source-isolation!
                           root project source))))
    (doseq [[name forbidden]
            [["project" "<ProjectReference Include=\"outside.csproj\" />"]
             ["runtime" "using Pkl.Core.Runtime;"]
             ["generated" "const string path = \"target/generated\";"]]]
      (let [bad (write! (.resolve root (str name ".cs")) forbidden)]
        (is (= :pkl-core-corpus-source-isolation
               (thrown-kind #(#'runner/verify-package-source-isolation!
                              root project bad))))))))

(deftest shipped-runner-declares-controlled-fixture-and-process-facilities
  (let [root (paths/workspace-root)
        csharp (Files/readString
                (paths/resolve-path root "vibeformer" "validation" "pkl-core-corpus"
                                    "PklCorePackageCorpusRunner.cs"))
        java (Files/readString
              (paths/resolve-path root "vibeformer" "validation"
                                  "pkl-core-test-contract"
                                  "PklCoreUpstreamCorpusRunner.java"))]
    (doseq [required ["createTempDirectory" "ProcessBuilder" "selectUniqueId"
                      "destroyProcessTree" "TIMEOUT" "CRASH"]]
      (is (.contains java required)))
    (doseq [required ["ZipFile.Open" "TcpListener" "CertificateRequest" "WebProxy"
                      "environment-value" "property-value" "module-key-factory"
                      "WaitForExitAsync" "entireProcessTree" "TIMEOUT" "CRASH"]]
      (is (.contains csharp required)))))
