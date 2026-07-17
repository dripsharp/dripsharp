(ns vibeformer.differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.differential :as differential]
            [vibeformer.paths :as paths])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- result-file [contents]
  (let [file (Files/createTempFile "vibeformer-differential" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

(defn- public-surface-root [body]
  (let [root (Files/createTempDirectory "vibeformer-loading-surface"
                                        (make-array FileAttribute 0))
        source-root (.resolve root "src/Pkl/Core")]
    (doseq [relative (var-get #'differential/loading-public-surface-files)]
      (let [file (.resolve source-root relative)]
        (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
        (Files/writeString file body (make-array OpenOption 0))))
    root))

(deftest normalized-result-comparison-is-fail-closed
  (let [expected (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\n")]
    (testing "identical independently produced observations pass"
      (is (= {:matched 2} (differential/compare-results expected
                                                        (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\n")))))
    (testing "content perturbations fail at their exact observation"
      (is (= {:matched 1
              :mismatch {:line 2
                         :expected "case-a\tTYPED\tQg=="
                         :actual "case-a\tTYPED\tQw=="}}
             (differential/compare-results expected
                                           (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQw==\n")))))
    (testing "missing or added observations cannot compare equal"
      (is (= 3 (get-in (differential/compare-results
                        expected
                        (result-file "case-a\tLEXER\tQQ==\ncase-a\tTYPED\tQg==\nextra\tPROOF\tWA==\n"))
                       [:mismatch :line]))))))

(deftest independent-probes-overlap-and-retain-command-context
  (let [threads (atom #{})
        results
        (concurrency/call-with-executor
         {:worker-count 2 :thread-prefix "differential-test"}
         #(#'differential/run-independent-probes!
           (fn [{:keys [command]}]
             (swap! threads conj (.getName (Thread/currentThread)))
             (Thread/sleep 30)
             {:command command :exit 0 :output (first command)})
           [{:name :java :command ["java" "oracle"] :directory "."}
            {:name :dotnet :command ["dotnet" "probe"] :directory "."}]))]
    (is (= [:java :dotnet] (mapv :probe results)))
    (is (= [["java" "oracle"] ["dotnet" "probe"]] (mapv :command results)))
    (is (= 2 (count @threads)))))

(deftest schema-contract-evidence-is-source-backed-and-retains-product-scope
  (let [root (paths/workspace-root)
        evidence (paths/resolve-path root "vibeformer" "validation" "schema-codegen"
                                     "ContractEvidence.tsv")
        summary (#'differential/verify-contract-evidence! root evidence)]
    (is (= 20 (:selected summary)))
    (is (zero? (:pending-in-scope summary)))
    (is (some #{"schema.collections-aliases-generics-functions"} (:families summary)))
    (is (some #{"codegen.polymorphism-overrides"} (:families summary)))
    (is (some #{"binding.complete-conversion-matrix"} (:families summary)))
    (is (some #{"schema.methods-generic-classes"} (:families summary)))
    (is (some #{"schema.amends-recursive-aliases"} (:families summary)))))

(deftest loading-contract-is-source-backed-executable-and-retains-pending-scope
  (let [root (paths/workspace-root)
        fixtures (paths/resolve-path root "vibeformer" "validation" "loading-contract")
        contract (#'differential/verify-loading-contract-evidence!
                  root
                  (paths/resolve-path fixtures "ContractEvidence.tsv")
                  (paths/resolve-path fixtures "ContractExpectations.tsv"))
        summary (:summary contract)]
    (is (= 62 (:families summary)))
    (is (= 62 (:existing-evidence summary)))
    (is (zero? (:pending-in-scope summary)))
    (is (= 54 (:jvm-shared-families summary)))
    (is (= 8 (:dotnet-adaptation-families summary)))
    (is (= 20 (:jvm-shared-observations summary)))
    (is (= 7 (:dotnet-adaptation-observations summary)))
    (is (some #(= "package.cache-offline" (:family %)) (:evidence contract)))
    (is (some #(= "adaptation.assembly-modules" (:family %)) (:evidence contract)))
    (is (some #(= "evaluator.timeout-cancellation" (:family %)) (:evidence contract)))
    (is (some #(= "collections.map-entry-set" (:family %)) (:evidence contract)))
    (is (every? #(#{"existing-evidence" "pending-in-scope"}
                   (:implementation %))
                (:evidence contract)))))

(deftest loading-public-surface-audit-is-fail-closed
  (let [clean (public-surface-root "public int Value() { return 1; }\n")
        summary (#'differential/audit-loading-public-surface! clean)]
    (is (= 15 (:files summary)))
    (is (= [:translation-error :not-implemented :todo
            :null-or-default-body :null-or-default-expression]
           (:patterns summary))))
  (let [stubbed (public-surface-root
                 "public object Missing() {\nreturn default!;\n}\n")
        error (try
                (#'differential/audit-loading-public-surface! stubbed)
                nil
                (catch ExceptionInfo exception exception))]
    (is (some? error))
    (is (= :null-or-default-body
           (get-in (ex-data error) [:findings 0 :kind])))))

(deftest packed-assembly-manifest-pins-exact-runtime-hashes
  (let [output (Files/createTempFile "vibeformer-packed-assemblies" ".tsv"
                                     (make-array FileAttribute 0))
        hash-a (apply str (repeat 64 "a"))
        hash-b (apply str (repeat 64 "b"))
        packages [{:resource-proof
                   {:assembly-identity {:name "Pkl.Parser"}
                    :assembly-artifact {:sha256 hash-a}}}
                  {:resource-proof
                   {:assembly-identity {:name "Pkl.Core"}
                    :assembly-artifact {:sha256 hash-b}}}]
        proof (#'differential/write-packed-assembly-manifest! output packages)]
    (is (= [{:name "Pkl.Core" :sha256 hash-b}
            {:name "Pkl.Parser" :sha256 hash-a}]
           (:assemblies proof)))
    (is (= (str "Pkl.Core\t" hash-b "\nPkl.Parser\t" hash-a "\n")
           (Files/readString output)))))
