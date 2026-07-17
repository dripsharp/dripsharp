(ns vibeformer.differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.differential :as differential]
            [vibeformer.paths :as paths])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(defn- result-file [contents]
  (let [file (Files/createTempFile "vibeformer-differential" ".tsv"
                                   (make-array FileAttribute 0))]
    (Files/writeString file contents (make-array OpenOption 0))
    file))

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
    (is (= 53 (:families summary)))
    (is (= 41 (:existing-evidence summary)))
    (is (= 12 (:pending-in-scope summary)))
    (is (= 49 (:jvm-shared-families summary)))
    (is (= 4 (:dotnet-adaptation-families summary)))
    (is (= 17 (:jvm-shared-observations summary)))
    (is (= 4 (:dotnet-adaptation-observations summary)))
    (is (some #(= "package.cache-offline" (:family %)) (:evidence contract)))
    (is (some #(= "adaptation.assembly-modules" (:family %)) (:evidence contract)))
    (is (every? #(#{"existing-evidence" "pending-in-scope"}
                   (:implementation %))
                (:evidence contract)))))
