(ns dripsharp.pkl.language-snippet-contract-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.pkl.language-snippet-contract :as contract]
            [dripsharp.paths :as paths])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(def ^:private manifest
  (delay (paths/resolve-path (paths/workspace-root) "validation"
                             "language-snippet-contract"
                             "LanguageSnippetContract.tsv")))

(def ^:private validated
  (delay (contract/validate-manifest! @manifest)))

(defn- case-by-id
  [case-id]
  (some #(when (= case-id (:case-id %)) %) (:cases @validated)))

(defn- thrown-kind
  [f]
  (try
    (f)
    nil
    (catch ExceptionInfo error
      (:kind (ex-data error)))))

(defn- temp-file
  [name]
  (let [directory (Files/createTempDirectory "language-snippet-contract-test"
                                             (make-array FileAttribute 0))]
    (.resolve directory name)))

(deftest authoritative-manifest-is-complete-source-backed-and-scope-safe
  (let [summary (:summary @validated)
        metadata (into {} (:metadata @validated))]
    (testing "the exact pinned upstream provenance and complete discovery are retained"
      (is (= contract/pinned-upstream-revision (metadata "source-revision")))
      (is (= 940 (:cases summary)))
      (is (= 934 (:expected-output-files summary)))
      (is (= {"success-output" 469 "error-output" 465 "success-empty" 6}
             (:outcomes summary)))
      (is (= {"fundamental-language" 268
              "standard-library-renderer" 170
              "diagnostics" 321
              "collections-generators" 77
              "module-project-package" 104}
             (:families summary))))

    (testing "only sole YAML cases leave the epic while mixed excluded surfaces remain"
      (is (= {"in-scope" 893
              "in-scope-mixed-excluded-surface" 16
              "outside-epic-approved-exclusion" 31}
             (:scopes summary)))
      (is (= "outside-epic-approved-exclusion"
             (:product-scope
              (case-by-id "language-snippet/api/yamlRenderer2.yml.pkl"))))
      (is (= "in-scope-mixed-excluded-surface"
             (:product-scope
              (case-by-id "language-snippet/pklbinary/classes.msgpack.yaml.pkl"))))
      (is (= "in-scope-mixed-excluded-surface"
             (:product-scope
              (case-by-id "language-snippet/api/renderDirective.pkl"))))
      (is (= "in-scope-mixed-excluded-surface"
             (:product-scope
              (case-by-id "language-snippet/basic/importGlob.pkl")))))

    (testing "input, helper, project, and execution dependencies are explicit"
      (let [read-case (case-by-id "language-snippet/basic/read.pkl")
            project-case (case-by-id "language-snippet/projects/project1/basic.pkl")
            local-project-case
            (case-by-id "language-snippet/projects/project1/localProject.pkl")]
        (is (str/includes? (:input-dependencies read-case) "input/basic/globtest/file1.txt"))
        (is (str/includes? (:helper-dependencies read-case)
                           "input-helper/basic/read/child/module2.pkl"))
        (is (str/includes? (:execution-requirements read-case) "environment-variables"))
        (is (str/includes? (:execution-requirements read-case) "external-properties"))
        (is (str/includes? (:project-dependencies project-case)
                           "projects/project1/PklProject.deps.json"))
        (is (str/includes? (:project-dependencies project-case)
                           "projects/project2/PklProject"))
        (is (str/includes? (:execution-requirements project-case) "package-service"))
        (is (str/includes? (:input-dependencies local-project-case)
                           "projects/project2/penguin.pkl")))))

  (is (= #{"language-snippet/errors/typeMismatchHelper.pkl"
           "language-snippet/modules/recursiveModule2.pkl"
           "language-snippet/parser/lambdaTrailingCommas.pkl"
           "language-snippet/pklbinaryTest.pkl"
           "language-snippet/snippetTest.pkl"
           "language-snippet/types/helpers/originalTypealias.pkl"}
         (->> (:cases @validated)
              (filter #(= "success-empty" (:expected-outcome %)))
              (map :case-id)
              set))))

(deftest manifest-row-validation-is-fail-closed
  (let [parsed (contract/read-manifest @manifest)
        cases (:cases parsed)
        first-case (first cases)
        outside-index (first (keep-indexed
                              (fn [index case-data]
                                (when (= "outside-epic-approved-exclusion"
                                         (:product-scope case-data))
                                  index))
                              cases))]
    (testing "missing and duplicate rows fail"
      (is (= :language-snippet-case-count
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases (pop cases))))))
      (is (= :duplicate-language-snippet-row
             (thrown-kind #(#'contract/validate-rows!
                            (assoc parsed :cases (assoc cases (dec (count cases))
                                                        first-case))))))

      (testing "unclassified and unexecutable rows fail"
        (is (= :unclassified-language-snippet-scope
               (thrown-kind #(#'contract/validate-rows!
                              (assoc parsed :cases
                                     (assoc cases 0 (assoc first-case
                                                           :product-scope "pending")))))))
        (is (= :unexecutable-language-snippet-row
               (thrown-kind #(#'contract/validate-rows!
                              (assoc parsed :cases
                                     (assoc cases 0 (assoc first-case
                                                           :execution-requirements "-"))))))))

      (testing "outside-epic rows require the approved exclusion evidence"
        (is (= :unapproved-language-snippet-exclusion
               (thrown-kind #(#'contract/validate-rows!
                              (assoc parsed :cases
                                     (update cases outside-index assoc
                                             :scope-basis "unimplemented"))))))))))

(deftest oracle-result-contract-rejects-coverage-execution-and-content-drift
  (let [expected (contract/write-expected-results! @validated (temp-file "expected.tsv"))
        identical (temp-file "identical.tsv")
        missing (temp-file "missing.tsv")
        duplicate (temp-file "duplicate.tsv")
        unexecutable (temp-file "unexecutable.tsv")
        perturbed (temp-file "perturbed.tsv")
        content (Files/readString expected StandardCharsets/UTF_8)
        lines (str/split-lines content)
        first-line (first lines)]
    (Files/copy expected identical
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
    (Files/writeString missing (str (str/join "\n" (pop (vec lines))) "\n")
                       StandardCharsets/UTF_8 (make-array OpenOption 0))
    (Files/writeString duplicate (str content first-line "\n")
                       StandardCharsets/UTF_8 (make-array OpenOption 0))
    (Files/writeString unexecutable
                       (str (str/replace-first first-line "\tSUCCESS\t"
                                               "\tUNEXECUTABLE\t")
                            "\n" (str/join "\n" (rest lines)) "\n")
                       StandardCharsets/UTF_8 (make-array OpenOption 0))
    (let [[case-id status _] (str/split first-line #"\t" -1)]
      (Files/writeString perturbed
                         (str case-id "\t" status "\tUFJPVkU=\n"
                              (str/join "\n" (rest lines)) "\n")
                         StandardCharsets/UTF_8 (make-array OpenOption 0)))

    (is (= {:matched 940}
           (contract/compare-results @validated expected identical)))
    (is (= :actual-result-coverage
           (get-in (contract/compare-results @validated expected missing)
                   [:mismatch :kind])))
    (is (= :duplicate-actual-results
           (get-in (contract/compare-results @validated expected duplicate)
                   [:mismatch :kind])))
    (is (= :unexecutable-in-scope-row
           (get-in (contract/compare-results @validated expected unexecutable)
                   [:mismatch :kind])))
    (is (= :content-mismatch
           (get-in (contract/compare-results @validated expected perturbed)
                   [:mismatch :kind])))))
