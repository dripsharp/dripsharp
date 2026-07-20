(ns vibeformer.public-api-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process]
            [vibeformer.public-api-contract :as contract])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private workspace (delay (paths/workspace-root)))
(def ^:private fixtures (delay (contract/contract-paths @workspace)))
(def ^:private upstream
  (delay (:rows (contract/read-tsv (:upstream @fixtures) contract/upstream-columns))))
(def ^:private package
  (delay (:rows (contract/read-tsv (:package @fixtures) contract/package-columns))))
(def ^:private policies (delay (contract/read-policy (:policy @fixtures))))
(def ^:private behavior
  (delay (:rows (contract/read-tsv (:behavior @fixtures) contract/behavior-columns))))
(def ^:private controls
  (delay (:rows (contract/read-tsv (:controls @fixtures)
                                   contract/failing-control-columns))))
(def ^:private body-candidates
  (delay (:rows (contract/read-tsv (:body-candidates @fixtures)
                                   contract/body-audit-columns))))
(def ^:private validated (delay (contract/validate-contract! @workspace)))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (:kind (ex-data error)))))

(defn- temp-file
  [name]
  (.resolve (Files/createTempDirectory "public-api-contract-test"
                                       (make-array FileAttribute 0))
            name))

(deftest authoritative-contract-is-complete-source-backed-and-scope-safe
  (let [summary @validated
        rows (contract/contract-rows @upstream @policies)
        kinds (set (map :kind rows))]
    (testing "all independently extracted declarations and package rows are classified"
      (is (= 6353 (:upstream-rows summary)))
      (is (= 2749 (:package-rows summary)))
      (is (= 20 (:behavior-rows summary)))
      (is (zero? (:failing-controls summary)))
      (is (= 6353 (reduce + (vals (:classifications summary)))))
      (is (= 2749 (reduce + (vals (:package-classifications summary)))))
      (is (zero? (get (:package-classifications summary)
                      "public-implementation-internal" 0))))

    (testing "member metadata covers every required declaration dimension"
      (is (every? kinds ["type" "constructor" "property" "field" "method"
                         "enum-value"]))
      (is (some #(not= "-" (:generic-constraints %)) rows))
      (is (some #(str/includes? (:nullability %) "nullable") rows))
      (is (some #(not= "-" (:exceptions %)) rows))
      (is (some #(not= "-" (:delegate %)) rows))
      (is (some #(not= "-" (:lifecycle %)) rows))
      (is (some #(not= "-" (:invocation-evidence %)) rows))
      (is (some (fn [[_ declarations]] (< 1 (count declarations)))
                (group-by (juxt :owner :kind :name :parameter-count) rows))))

    (testing "implementation internals do not become product exclusions"
      (let [internals (filter #(= "public-implementation-internal"
                                  (:classification %)) rows)
            exclusions (filter #(= "approved-exclusion" (:classification %)) rows)]
        (is (seq internals))
        (is (every? #(= "-" (:exclusion-evidence %)) internals))
        (is (every? #(str/includes? (:dotnet-adaptation %)
                                    "not a product exclusion")
                    internals))
        (is (seq exclusions))
        (is (every? #(str/starts-with? (:exclusion-evidence %)
                                       "vibeformer/doc/product-goal.md#User-Approved")
                    exclusions))))

    (testing "all four product areas have surface or behavior rows"
      (is (every? #(pos? (get (:areas summary) % 0))
                  ["parser" "core" "config-binding"]))
      (is (pos? (get (:native-areas summary) "csharp-generation" 0)))
      (is (= #{"parser" "core" "config-binding" "csharp-generation"}
             (set (map :area @behavior)))))))

(deftest independent-upstream-extraction-matches-the-pinned-snapshot
  (is (= {:matched 6353}
         (contract/verify-upstream-snapshot! @workspace))))

(deftest surface-comparators-fail-closed-on-missing-duplicate-and-perturbed-rows
  (testing "upstream declaration extraction"
    (is (= {:matched 6353}
           (contract/compare-upstream-surface @upstream @upstream)))
    (is (= :upstream-public-surface-drift
           (get-in (contract/compare-upstream-surface @upstream (pop @upstream))
                   [:mismatch :kind])))
    (is (= :duplicate-upstream-extraction-rows
           (get-in (contract/compare-upstream-surface @upstream
                                                      (conj @upstream (first @upstream)))
                   [:mismatch :kind])))
    (is (= :upstream-public-surface-drift
           (get-in (contract/compare-upstream-surface
                    @upstream (assoc-in @upstream [0 :signature] "perturbed"))
                   [:mismatch :kind]))))

  (testing "package reflection"
    (is (= {:matched 2749}
           (contract/compare-package-surface @package @package)))
    (is (= :package-public-surface-drift
           (get-in (contract/compare-package-surface @package (pop @package))
                   [:mismatch :kind])))
    (is (= :duplicate-package-reflection-rows
           (get-in (contract/compare-package-surface @package
                                                     (conj @package (first @package)))
                   [:mismatch :kind])))
    (is (= :package-public-surface-drift
           (get-in (contract/compare-package-surface
                    @package (assoc-in @package [0 :nullability] "perturbed"))
                   [:mismatch :kind])))))

(deftest policy-rejects-silently-skipped-and-ambiguously-classified-declarations
  (let [parser-row (some #(when (= "pkl-parser" (:source-module %)) %) @upstream)
        parser-policy (contract/classify-upstream-row @policies parser-row)]
    (is (= :unclassified-upstream-public-api
           (thrown-kind #(contract/classify-upstream-row
                          @policies (assoc parser-row :source-module "unknown")))))
    (is (= :ambiguous-upstream-public-api-policy
           (thrown-kind #(contract/classify-upstream-row
                          (conj @policies (assoc parser-policy :rule-id "duplicate-first"))
                          parser-row))))))

(deftest generation-surfaces-derive-every-product-member-from-the-contract
  (let [parser (contract/generation-surface!
                @workspace {:source-module "pkl-parser"})
        core (contract/generation-surface!
              @workspace {:source-module "pkl-core"})]
    (is (= [958 1200] (mapv #(count (:required-rows %)) [parser core])))
    (is (= [102 134] (mapv #(count (:seeds %)) [parser core])))
    (is (every? #(and (= :public-api (:expand %)) (set? (:members %)))
                (concat (:seeds parser) (:seeds core))))
    (is (some #(and (= "org.pkl.core.ImportGraph" (:owner %))
                    (= "parseFromJson" (:name %)))
              (:required-rows core)))
    (is (some #(and (= "org.pkl.core.StackFrameTransformers" (:owner %))
                    (= "createDefault" (:name %)))
              (:required-rows core)))
    (is (some #(and (= "org.pkl.core.TestResults" (:owner %))
                    (= "product-api" (:classification %)))
              (:required-rows core)))
    (is (not-any? #(and (= "org.pkl.core.Evaluator" (:owner %))
                        (= "evaluateTest" (:name %)))
                  (:required-rows core)))
    (is (not-any? #(and (= "org.pkl.core.ValueRenderers" (:owner %))
                        (= "yaml" (:name %)))
                  (:required-rows core)))
    (is (empty? @controls))))

(deftest generated-to-compiled-comparator-rejects-absent-collapsed-and-unmapped-members
  (let [destination {:assembly "Pkl.Core" :owner "Pkl.Core.Example"
                     :kind "method" :name "Parse" :parameter-count "1"}
        evidence (fn [key signature]
                   {:declaration-key key
                    :row {:signature signature}
                    :generated {:destination destination}})
        metadata {:schema-version 2
                  :rows [(evidence "executable:Example#parse(string)" "Parse(String)")
                         (evidence "executable:Example#parse(uri)" "Parse(URI)")]}
        actual-row {:assembly "Pkl.Core" :owner "Pkl.Core.Example"
                    :kind "method" :name "Parse" :parameter-count "1"}]
    (is (= {:matched 2 :distinct-shapes 1}
           (contract/compare-generated-package-surface
            metadata [actual-row (assoc actual-row :signature "second")])))
    (is (= :compiled-public-api-contract-mismatch
           (get-in (contract/compare-generated-package-surface metadata [actual-row])
                   [:mismatch :kind])))
    (is (= :source-unmapped-generated-public-metadata
           (get-in (contract/compare-generated-package-surface
                    (assoc-in metadata [:rows 0 :generated :destination :owner] "")
                    [actual-row actual-row])
                   [:mismatch :kind])))))

(deftest package-boundary-rejects-implementation-and-java-shaped-metadata
  (let [product-row {:assembly "Pkl.Core" :owner "Pkl.Core.Evaluator"
                     :signature "System.String Evaluate()"}
        implementation-row {:assembly "Pkl.Core" :owner "Pkl.Core.Runtime.VmContext"
                            :signature "Pkl.Core.Runtime.VmContext Get()"}
        mutable-row {:assembly "Pkl.Core" :owner "Pkl.Core.Evaluator"
                     :signature "System.Collections.Generic.IList<System.String> Get()"}]
    (is (= {:approved 1}
           (contract/validate-package-boundary!
            [product-row] [(assoc product-row :classification "product-api-current")])))
    (is (= :public-implementation-metadata-leak
           (thrown-kind
            #(contract/validate-package-boundary!
              [implementation-row]
              [(assoc implementation-row
                      :classification "public-implementation-internal")]))))
    (is (= :forbidden-public-package-signature
           (thrown-kind
            #(contract/validate-package-boundary!
              [mutable-row]
              [(assoc mutable-row :classification "product-api-current")]))))))

(deftest behavior-comparator-detects-coverage-execution-and-observation-drift
  (let [results (mapv (fn [row]
                        {:case-id (:case-id row)
                         :status "EXECUTED"
                         :observation (:normalized-expectation row)})
                      @behavior)]
    (is (= {:matched 20}
           (contract/compare-behavior-results @behavior results)))
    (is (= :public-api-behavior-coverage
           (get-in (contract/compare-behavior-results @behavior (pop results))
                   [:mismatch :kind])))
    (is (= :duplicate-public-api-behavior-results
           (get-in (contract/compare-behavior-results @behavior
                                                      (conj results (first results)))
                   [:mismatch :kind])))
    (is (= :unexecuted-public-api-behavior
           (get-in (contract/compare-behavior-results
                    @behavior (assoc-in results [0 :status] "SKIPPED"))
                   [:mismatch :kind])))
    (is (= :public-api-behavior-drift
           (get-in (contract/compare-behavior-results
                    @behavior (assoc-in results [0 :observation] "perturbed"))
                   [:mismatch :kind])))))

(deftest behavior-evidence-extraction-is-independent-and-deterministic
  (let [actual (temp-file "behavior.tsv")
        perturbed (temp-file "perturbed-behavior.tsv")
        expected-columns ["case-id" "upstream-provenance" "line-sha256"
                          "dotnet-invocation"]]
    (contract/extract-behavior-evidence! @workspace (:behavior @fixtures) actual)
    (is (= (mapv #(dissoc % :fixture-line)
                 (:rows (contract/read-tsv (:behavior-evidence @fixtures)
                                           expected-columns)))
           (mapv #(dissoc % :fixture-line)
                 (:rows (contract/read-tsv actual expected-columns)))))
    (Files/writeString
     perturbed
     (str/replace-first (Files/readString (:behavior @fixtures))
                        "new Parser().ParseModule(" "ABSENT_DOTNET_CALL(")
     (make-array OpenOption 0))
    (is (= :missing-dotnet-behavior-call
           (thrown-kind #(contract/extract-behavior-evidence!
                          @workspace perturbed (temp-file "unused.tsv")))))))

(deftest package-probe-reflects-generic-nullable-delegate-and-lifecycle-metadata
  (let [project (paths/resolve-path (:root @fixtures) "PackageProbeFixture.csproj")
        _ (process/run! {:command ["dotnet" "build" project
                                   "--configuration" "Release" "--nologo"]
                         :directory @workspace})
        assembly (paths/resolve-path (:root @fixtures) "bin" "Release" "net10.0"
                                     "PackageProbeFixture.dll")
        output (temp-file "package.tsv")]
    (contract/reflect-packages! @workspace [assembly] output)
    (let [rows (:rows (contract/read-tsv output contract/package-columns))
          by-owner (group-by :owner rows)
          resource (by-owner "Vibeformer.PublicApiProbeFixture.Resource`1")
          formatter (by-owner "Vibeformer.PublicApiProbeFixture.Formatter`1")]
      (is (seq resource))
      (is (some #(and (= "property" (:kind %)) (= "Label" (:name %))
                      (str/includes? (:nullability %) "nullable"))
                resource))
      (is (some #(and (= "method" (:kind %)) (= "Map" (:name %))
                      (not= "-" (:generic-constraints %)))
                resource))
      (is (some #(= "dispose" (:lifecycle %)) resource))
      (is (some #(= "delegate" (:delegate %)) formatter)))))

(deftest whole-public-body-audit-is-reviewed-and-perturbation-sensitive
  (let [rows @body-candidates]
    (is (= 11 (count rows)))
    (is (= {"constant-zero" 2
            "empty-no-op" 5
            "unconditional-unsupported" 4}
           (frequencies (map :finding rows))))
    (is (= {:matched 11} (contract/compare-body-audit rows rows)))
    (is (= :public-body-audit-drift
           (get-in (contract/compare-body-audit rows (pop rows))
                   [:mismatch :kind])))
    (is (= :duplicate-public-body-audit-rows
           (get-in (contract/compare-body-audit rows (conj rows (first rows)))
                   [:mismatch :kind])))
    (is (= :public-body-audit-drift
           (get-in (contract/compare-body-audit
                    rows (assoc-in rows [0 :finding] "constant-null"))
                   [:mismatch :kind])))))

(deftest strongly-typed-contract-key-generation-covers-product-and-native-apis
  (let [output (temp-file "strong-keys.tsv")
        summary (contract/write-strong-contract-keys! @workspace output)
        lines (->> (str/split-lines (Files/readString output))
                   (remove #(str/starts-with? % "#")) vec)]
    (is (= {:rows 2707 :keys 2648} summary))
    (is (= 2648 (count lines)))
    (is (= (sort lines) lines))
    (is (some #(str/includes? % "Pkl.Core\tPkl.Core.ConfigBinder\tmethod\tBind")
              lines))
    (is (some #(str/includes? % "Pkl.Core\tPkl.Core.CSharpGenerator\tmethod\tGenerate")
              lines))
    (is (some #(str/includes? % "Pkl.Parser\tPkl.Parser.Parser\tmethod\tParseModule")
              lines))))
