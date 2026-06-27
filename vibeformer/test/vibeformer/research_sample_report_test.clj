(ns vibeformer.research-sample-report-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.research-sample-report :as report])
  (:import (java.nio.file Files Path)))

(defn temp-root []
  (Files/createTempDirectory "vibeformer-research-sample-report-test-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))

(defn ensure-parent! [^Path file]
  (Files/createDirectories (.getParent file)
                           (make-array java.nio.file.attribute.FileAttribute 0)))

(defn write-edn! [^Path file value]
  (ensure-parent! file)
  (spit (str file) (str (pr-str value) "\n")))

(defn read-edn [^Path file]
  (edn/read-string (slurp (str file))))

(def fake-inventory
  {:project/id "research-pkl"
   :source/files 4
   :inventory {:unresolved-ref-detail-rankings
               [{:lang :lang/kotlin
                 :kind :ref.kind/type-use
                 :reason :resolve.reason/missing-classpath
                 :owner ""
                 :name "Action"
                 :count 30
                 :file-count 3}
                {:lang :lang/java
                 :kind :ref.kind/type-use
                 :reason :resolve.reason/missing-classpath
                 :owner ""
                 :name "Missing"
                 :count 10
                 :file-count 2}]
               :unresolved-api-call-rankings
               [{:lang :lang/kotlin
                 :kind :ref.kind/function-call
                 :reason :resolve.reason/missing-classpath
                 :owner "GradleHelpers"
                 :name "named"
                 :count 8
                 :file-count 8}]
               :unsupported-rankings
               [{:lang :lang/java
                 :kind :java.reflection.method/invoke
                 :count 5
                 :file-count 4}]}
   :coverage {:failure-rankings
              [{:lang :lang/java
                :feature/kind :java.feature/native-method
                :feature/status :feature.status/stubbed
                :count 2
                :file-count 2}]}})

(def fake-dry-run
  {:project/id "research-pkl"
   :dry-run/mode :facts-only
   :stages [{:stage :unresolved-reference-gate
             :status :warning
             :unresolved/total 40}]})

(deftest builds-report-from-inventory-diagnostics-and-provenance
  (let [root (temp-root)
        inventory-file (.resolve root "target/research-pkl/inventory.edn")
        dry-run-file (.resolve root "target/research-pkl/dry-run.edn")
        out-file (.resolve root "target/research-pkl/sample-selection.edn")
        diagnostic-file (.resolve root "sample-projects/java-broken/target/diagnostics/dotnet-diagnostic-facts.edn")
        provenance-file (.resolve root "sample-projects/java-mapping/target/provenance.edn")]
    (write-edn! inventory-file fake-inventory)
    (write-edn! dry-run-file fake-dry-run)
    (write-edn! diagnostic-file
                {:mapping-quality
                 {:unmapped-rankings
                  [{:diagnostic/code "CS1002"
                    :diagnostic/severity :diagnostic.severity/error
                    :diagnostic/message "; expected"
                    :diagnostic/mapping-reason :diagnostic.mapping/no-provenance-span
                    :count 3
                    :file-count 1}]}})
    (write-edn! provenance-file
                {:csharp/diagnostics
                 [{:rule/id :type-mapping/unknown
                   :diagnostic/message "No C# type mapping for javax.persistence.EntityManager"
                   :rule/context {:type/name "javax.persistence.EntityManager"
                                  :mapping/reason :mapping.reason/unknown-type}}]})
    (let [result (report/run-report {:project-root root
                                     :top 1})
          written (read-edn out-file)
          categories (set (map :candidate/category (:candidates result)))]
      (is (= :vibeformer.report/research-sample-selection (:report/type result)))
      (is (= (:report/file result) (get-in result [:artifacts :sample-selection])))
      (is (= result (assoc written :report/file (:report/file result))))
      (is (= 40 (:unresolved/total result)))
      (is (= #{:sample.category/unsupported-construct
               :sample.category/coverage-gap
               :sample.category/unresolved-ref
               :sample.category/unresolved-api-call
               :sample.category/missing-mapping
               :sample.category/compiler-diagnostic}
             categories))
      (is (= "Action" (get-in result [:sections :unresolved-refs 0 :name])))
      (is (= "javax.persistence.EntityManager"
             (get-in result [:sections :missing-mappings 0 :type/name])))
      (is (= "CS1002"
             (get-in result [:sections :compiler-diagnostics 0 :diagnostic/code])))
      (is (= (:candidate/count result)
             (count (distinct (map :candidate/id (:candidates result))))))
      (is (every? :suggested-bead (:candidates result)))
      (is (= (range 1 (inc (:candidate/count result)))
             (map :candidate/priority (:candidates result)))))))

(deftest report-tolerates-missing-dry-run-and-sample-artifacts
  (let [root (temp-root)
        inventory-file (.resolve root "target/research-pkl/inventory.edn")]
    (write-edn! inventory-file fake-inventory)
    (let [result (report/run-report {:project-root root
                                     :top 1})]
      (is (= :vibeformer.report/research-sample-selection (:report/type result)))
      (is (nil? (:dry-run/mode result)))
      (is (empty? (get-in result [:sections :missing-mappings])))
      (is (empty? (get-in result [:sections :compiler-diagnostics])))
      (is (pos? (:candidate/count result))))))

(deftest cli-options-must-be-edn-map
  (is (= {:top 3}
         (#'report/parse-cli-opts "{:top 3}")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"EDN map"
                        (#'report/parse-cli-opts "[:not :a :map]"))))
