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

(deftest schema-contract-evidence-is-source-backed-and-keeps-broader-families-pending
  (let [root (paths/workspace-root)
        evidence (paths/resolve-path root "vibeformer" "validation" "schema-codegen"
                                     "ContractEvidence.tsv")
        summary (#'differential/verify-contract-evidence! root evidence)]
    (is (= 9 (:selected summary)))
    (is (= 4 (:pending-in-scope summary)))
    (is (some #{"schema.collections-aliases-generics-functions"} (:families summary)))
    (is (some #{"schema.methods-generic-classes"} (:families summary)))))
