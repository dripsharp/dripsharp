(ns dripsharp.pkl.parser-test-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.pkl.parser-test-contract :as contract])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption]))

(def ^:private manifest
  (delay (paths/resolve-path (paths/workspace-root) "validation"
                             "pkl-parser-test-contract"
                             "PklParserTestContract.tsv")))

(def ^:private validated
  (delay (contract/validate-manifest! @manifest)))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (:kind (ex-data error)))))

(deftest complete-parser-suite-has-exact-source-case-and-fixture-accounting
  (let [summary (:summary @validated)
        sources (:sources @validated)
        cases (:cases @validated)]
    (is (= contract/pinned-upstream-revision (:upstream-revision summary)))
    (is (= {:sources 6
            :test-sources 3
            :helper-sources 3
            :junit-cases 10
            :adapted-cases 849
            :fixture-invocations 840
            :upstream-revision contract/pinned-upstream-revision}
           summary))
    (is (= {"test-source" 3 "test-helper" 3}
           (frequencies (map :source-role sources))))
    (is (= 10 (count (distinct (map :junit-unique-id cases)))))
    (is (= {"test" 9 "fixture-invocation" 840}
           (frequencies (map :case-kind cases))))
    (is (= {"enabled" 849}
           (frequencies (map :enabled-state cases))))
    (is (= {"all-supported-hosts" 849}
           (frequencies (map :platform-conditions cases))))
    (is (every? #(re-matches #"[0-9a-f]{64}"
                             (:expected-observation-sha256 %))
                cases))))

(deftest parser-contract-fails-closed-on-source-and-fixture-drift
  (let [source (:source-path (first (:sources @validated)))
        fixture (some #(when (not= "-" (:fixture-path %)) %) (:cases @validated))
        original (Files/readString @manifest StandardCharsets/UTF_8)
        source-sha (:source-sha256 (first (:sources @validated)))
        perturbed (Files/createTempFile "pkl-parser-contract-" ".tsv"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (testing "the durable manifest validates every pinned source/helper hash"
      (try
        (Files/writeString perturbed
                           (str/replace-first original source-sha (str (repeat 64 "0")))
                           StandardCharsets/UTF_8 (make-array OpenOption 0))
        (is (= :invalid-pkl-parser-test-contract
               (thrown-kind #(contract/validate-manifest! perturbed))))
        (finally
          (Files/deleteIfExists perturbed))))
    (testing "fixture rows carry exact source and fixture hashes"
      (is source)
      (is fixture)
      (is (re-matches #"[0-9a-f]{64}" (:source-sha256 fixture)))
      (is (re-matches #"[0-9a-f]{64}" (:fixture-sha256 fixture))))))
