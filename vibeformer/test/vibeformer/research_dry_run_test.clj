(ns vibeformer.research-dry-run-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.research-dry-run :as research-dry-run]
            [vibeformer.research-classpath :as research-classpath]
            [vibeformer.research-inventory :as research-inventory])
  (:import (java.nio.file Files Path)))

(def fake-inventory-report
  {:report/type :vibeformer.report/research-inventory
   :project/id "research-pkl"
   :project/root "/workspace/vibeformer"
   :research/root "/workspace/research/pkl"
   :report/file "/workspace/vibeformer/target/research-pkl/inventory.edn"
   :source/files 3
   :source/counts [{:lang :lang/java :count 2}
                   {:lang :lang/kotlin :count 1}]
   :ingest/source {:project/id "research-pkl"
                   :files 3}
   :ingest/java {:project/id "research-pkl"
                 :java-files 2
                 :transacted-facts 20}
   :ingest/kotlin {:project/id "research-pkl"
                   :kotlin-files 1
                   :transacted-facts 10}
   :ingest/kotlin-enrich {:project/id "research-pkl"
                          :semantic-tx 4
                          :semantic-refs 4}
   :rules/registered 22
   :rules/langs [:lang/java :lang/kotlin]
   :coverage {:ok? true
              :failure-count 0
              :failure-rankings []}
   :inventory {:unsupported-rankings [{:kind :java.feature/reflection
                                       :count 2}]
               :unresolved-ref-detail-rankings
               [{:lang :lang/kotlin
                 :kind :ref.kind/function-call
                 :reason :resolve.reason/analysis-api-limitation
                 :owner ""
                 :name "apply"
                 :count 3
                 :file-count 2}]}})

(def fake-classpath-report
  {:report/type :vibeformer.report/research-classpath
   :project/id "research-pkl"
   :project/root "/workspace/vibeformer"
   :research/root "/workspace/research/pkl"
	   :report/file "/workspace/vibeformer/target/research-pkl/classpath.edn"
	   :projects/count 2
	   :source-roots/count 3
	   :dependencies/count 4
	   :java/classpath-package-roots ["org.msgpack"]
	   :java/classpath-package-roots/count 1
   :kotlin/classpath-types {"Action" "org.gradle.api.Action"}
   :kotlin/classpath-types/count 1
	   :version-catalog {:catalog/libraries [{:catalog/alias "msgpack"
	                                          :catalog/group "org.msgpack"
	                                          :catalog/name "msgpack-core"
	                                          :catalog/version "0.9.12"}]}
	   :projects [{:project/path ":"
	               :project/name "root"
	               :project/dir "/workspace/research/pkl"
	               :source/roots []
	               :dependencies []}
	              {:project/path ":app"
	               :project/name "app"
	               :project/dir "/workspace/research/pkl/app"
	               :source/roots [{:source/kind :source.kind/resources
	                               :source/relative-path "src/main/resources"}]
	               :dependencies [{:dependency/configuration "implementation"
	                               :dependency/kind :dependency.kind/version-catalog
	                               :dependency/catalog-alias "msgpack"
	                               :dependency/expression "libs.msgpack"}]}]})

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-research-dry-run-test-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- read-edn [^Path file]
  (edn/read-string (slurp (str file))))

(deftest facts-only-dry-run-writes-staged-report-with-explicit-non-goals
  (let [root (temp-root)
        research-root (.resolve root "../research/pkl")
        captured-opts (atom nil)]
    (with-redefs [research-inventory/run-inventory
                  (fn [opts]
                    (reset! captured-opts opts)
                    (assoc fake-inventory-report
                           :project/root (str root)
                           :research/root (str research-root)
                           :report/file (str (.resolve root "target/research-pkl/inventory.edn"))))
                  research-classpath/run-classpath-inventory
                  (fn [opts]
                    (assoc fake-classpath-report
                           :project/root (str root)
                           :research/root (str research-root)
                           :report/file (str (:out opts))))]
      (let [result (research-dry-run/run-dry-run {:project-root root
                                                  :research/root research-root})
            target (.resolve root "target/research-pkl")
            dry-run (read-edn (.resolve target "dry-run.edn"))
            provenance (read-edn (.resolve target "provenance.edn"))
            stage-by-name (into {} (map (juxt :stage identity) (:stages result)))]
        (testing "inventory receives the normalized target inventory path"
          (is (= (.normalize (.toAbsolutePath (.resolve target "inventory.edn")))
                 (:out @captured-opts)))
          (is (= ["org.msgpack"]
                 (:java/classpath-package-roots @captured-opts)))
          (is (= {"Action" "org.gradle.api.Action"}
                 (:kotlin/classpath-types @captured-opts))))
        (testing "the staged report is facts-only by default"
          (is (:ok? result))
          (is (= :facts-only (:dry-run/mode result)))
          (is (= :vibeformer.report/research-dry-run (:report/type dry-run)))
	          (is (= [:source/discover
	                  :classpath/discover
	                  :destination/mapping
	                  :source/ingest
                  :java/ingest
                  :kotlin/ingest
                  :kotlin/enrich
                  :transform/rules
                  :diagnostics/inventory
                  :coverage/check
                  :semantic/unresolved-refs
                  :csharp/emit
                  :dotnet/build
                  :diagnostics/ingest
                  :provenance/write]
                 (mapv :stage (:stages result)))))
        (testing "later stages are explicit non-goals instead of silent omissions"
          (is (= :skipped (get-in stage-by-name [:csharp/emit :status])))
          (is (= :dry-run/facts-only-mode
                 (get-in stage-by-name [:csharp/emit :reason])))
          (is (= :dry-run/facts-only-mode
                 (get-in stage-by-name [:dotnet/build :reason])))
          (is (= :warn
                 (get-in stage-by-name [:semantic/unresolved-refs :status])))
	          (is (= {:projects/count 2
	                  :source-roots/count 3
	                  :dependencies/count 4}
	                 (select-keys (get stage-by-name :classpath/discover)
	                              [:projects/count :source-roots/count :dependencies/count])))
	          (is (= {:projects/count 2
	                  :packages/count 1
	                  :resources/count 1}
	                 (select-keys (get stage-by-name :destination/mapping)
	                              [:projects/count :packages/count :resources/count])))
	          (is (= 3
                 (get-in stage-by-name [:semantic/unresolved-refs :unresolved/total])))
          (is (some #{"does not modify the research Pkl checkout"}
                    (:dry-run/non-goals result))))
        (testing "provenance and artifact paths are recorded"
          (is (= :facts-only (:dry-run/mode provenance)))
          (is (= 3 (:source/files provenance)))
          (is (= :warn
                 (:status (read-edn (.resolve target "diagnostics/unresolved-refs.edn")))))
	          (is (= (str (.resolve target "classpath.edn"))
	                 (get-in result [:artifacts :classpath])))
	          (is (= (str (.resolve target "destination.edn"))
	                 (get-in result [:artifacts :destination])))
	          (is (= 2 (:destination/projects provenance)))
	          (is (= 2 (:projects/count (read-edn (.resolve target "destination.edn")))))
          (is (Files/isDirectory (.resolve target "diagnostics")
                                 (make-array java.nio.file.LinkOption 0)))
          (is (Files/isDirectory (.resolve target "csharp")
                                 (make-array java.nio.file.LinkOption 0))))))))

(deftest compile-capable-mode-records-current-blockers
  (with-redefs [research-inventory/run-inventory (constantly fake-inventory-report)
                research-classpath/run-classpath-inventory (constantly fake-classpath-report)]
    (let [root (temp-root)
          result (research-dry-run/run-dry-run {:project-root root
                                                :dry-run/mode :compile-capable})
          stage-by-name (into {} (map (juxt :stage identity) (:stages result)))]
      (is (false? (:ok? result)))
      (is (= :failed
             (get-in stage-by-name [:semantic/unresolved-refs :status])))
      (is (= :semantic.unresolved-refs/failed-threshold
             (get-in stage-by-name [:semantic/unresolved-refs :reason])))
      (is (= :pipeline/full-project-emission-not-implemented
             (get-in stage-by-name [:csharp/emit :reason])))
	      (is (= [:unresolved-reference-gate :csharp/full-project-emission]
	             (get-in stage-by-name [:csharp/emit :blocked-by])))
	      (is (= :dotnet/no-csharp-project
	             (get-in stage-by-name [:dotnet/build :reason])))
	      (is (= [:csharp/full-project-emission]
	             (get-in stage-by-name [:dotnet/build :blocked-by]))))))

(deftest rejects-unknown-mode
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unsupported research dry-run mode"
       (research-dry-run/run-dry-run {:project-root (temp-root)
                                      :dry-run/mode :surprise}))))
