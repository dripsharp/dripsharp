(ns vibeformer.inventory-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.inventory :as inventory])
  (:import (java.util UUID)))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-inventory-test-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (is (true? (d/create-database client {:db-name db-name})))
      (reset! created? true)
      (f (d/connect client {:db-name db-name}))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))

(def inventory-fixture
  [{:db/id "project"
    :project/id "fixture"
    :project/name "Fixture"
    :project/root "/workspace/fixture"}
   {:db/id "java-a"
    :file/id "fixture:src/A.java"
    :file/path "src/A.java"
    :file/lang :lang/java
    :file/hash "sha256:a"
    :file/project "project"
    :file/package "a"}
   {:db/id "java-b"
    :file/id "fixture:src/B.java"
    :file/path "src/B.java"
    :file/lang :lang/java
    :file/hash "sha256:b"
    :file/project "project"
    :file/package "b"}
   {:db/id "kotlin-c"
    :file/id "fixture:src/C.kt"
    :file/path "src/C.kt"
    :file/lang :lang/kotlin
    :file/hash "sha256:c"
    :file/project "project"
    :file/package "c"}
   {:db/id "kotlin-d"
    :file/id "fixture:src/D.kt"
    :file/path "src/D.kt"
    :file/lang :lang/kotlin
    :file/hash "sha256:d"
    :file/project "project"
    :file/package "d"}
   {:db/id "java-a-class"
    :node/id "fixture:src/A.java:class:A"
    :node/lang :lang/java
    :node/kind :java.node/class
    :node/name "A"
    :node/file "java-a"
    :node/ordinal 0}
   {:db/id "java-a-reflection-1"
    :node/id "fixture:src/A.java:call:reflection-1"
    :node/lang :lang/java
    :node/kind :java.node/method-call
    :node/name "forName"
    :node/file "java-a"
    :node/parent "java-a-class"
    :node/ordinal 1}
   {:db/id "java-a-reflection-2"
    :node/id "fixture:src/A.java:call:reflection-2"
    :node/lang :lang/java
    :node/kind :java.node/method-call
    :node/name "newInstance"
    :node/file "java-a"
    :node/parent "java-a-class"
    :node/ordinal 2}
   {:db/id "java-b-class"
    :node/id "fixture:src/B.java:class:B"
    :node/lang :lang/java
    :node/kind :java.node/class
    :node/name "B"
    :node/file "java-b"
    :node/ordinal 0}
   {:db/id "java-b-reflection"
    :node/id "fixture:src/B.java:call:reflection"
    :node/lang :lang/java
    :node/kind :java.node/method-call
    :node/name "invoke"
    :node/file "java-b"
    :node/parent "java-b-class"
    :node/ordinal 1}
   {:db/id "java-b-stream"
    :node/id "fixture:src/B.java:call:stream"
    :node/lang :lang/java
    :node/kind :java.node/method-call
    :node/name "stream"
    :node/file "java-b"
    :node/parent "java-b-class"
    :node/ordinal 2}
   {:db/id "kotlin-c-class"
    :node/id "fixture:src/C.kt:class:C"
    :node/lang :lang/kotlin
    :node/kind :kotlin.node/class
    :node/name "C"
    :node/file "kotlin-c"
    :node/ordinal 0}
   {:db/id "feature-java-class"
    :feature/id "fixture:src/A.java:feature:class"
    :feature/lang :lang/java
    :feature/kind :java.feature/class
    :feature/node "java-a-class"
    :feature/status :feature.status/supported
    :feature/severity :feature.severity/info}
   {:db/id "feature-java-reflection-1"
    :feature/id "fixture:src/A.java:feature:reflection-1"
    :feature/lang :lang/java
    :feature/kind :java.feature/reflection
    :feature/node "java-a-reflection-1"
    :feature/status :feature.status/unsupported
    :feature/severity :feature.severity/hard}
   {:db/id "feature-java-reflection-2"
    :feature/id "fixture:src/A.java:feature:reflection-2"
    :feature/lang :lang/java
    :feature/kind :java.feature/reflection
    :feature/node "java-a-reflection-2"
    :feature/status :feature.status/unsupported
    :feature/severity :feature.severity/hard}
   {:db/id "feature-java-reflection-3"
    :feature/id "fixture:src/B.java:feature:reflection"
    :feature/lang :lang/java
    :feature/kind :java.feature/reflection
    :feature/node "java-b-reflection"
    :feature/status :feature.status/unsupported
    :feature/severity :feature.severity/hard}
   {:db/id "feature-java-stream"
    :feature/id "fixture:src/B.java:feature:stream"
    :feature/lang :lang/java
    :feature/kind :java.feature/stream-api
    :feature/node "java-b-stream"
    :feature/status :feature.status/unsupported
    :feature/severity :feature.severity/medium}
   {:db/id "feature-kotlin-class"
    :feature/id "fixture:src/C.kt:feature:class"
    :feature/lang :lang/kotlin
    :feature/kind :kotlin.feature/class
    :feature/node "kotlin-c-class"
    :feature/status :feature.status/supported
    :feature/severity :feature.severity/info}
   {:db/id "java-list-type"
    :type/id "java.util.List"
    :type/lang :lang/java
    :type/name "java.util.List"
    :type/nullable? false}
   {:ref/id "fixture:src/A.java:call:stream:ref"
    :ref/kind :ref.kind/method-call
    :ref/from-node "java-a-reflection-1"
    :ref/name "stream"
    :ref/owner-type "java-list-type"
    :ref/resolved? false
    :ref/reason :resolve.reason/missing-classpath}
   {:ref/id "fixture:src/B.java:call:invoke:ref"
    :ref/kind :ref.kind/method-call
    :ref/from-node "java-b-reflection"
    :ref/name "invoke"
    :ref/resolved? false
    :ref/reason :resolve.reason/missing-classpath}
   {:ref/id "fixture:src/C.kt:call:apply:ref"
    :ref/kind :ref.kind/function-call
    :ref/from-node "kotlin-c-class"
    :ref/name "apply"
    :ref/resolved? false
    :ref/reason :resolve.reason/analysis-api-limitation}])

(deftest summarizes-feature-inventory
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (d/transact conn {:tx-data inventory-fixture})
      (let [db (d/db conn)]
        (testing "features are counted by language, kind, and status"
          (is (= [{:lang :lang/java
                   :kind :java.feature/class
                   :status :feature.status/supported
                   :count 1}
                  {:lang :lang/java
                   :kind :java.feature/reflection
                   :status :feature.status/unsupported
                   :count 3}
                  {:lang :lang/java
                   :kind :java.feature/stream-api
                   :status :feature.status/unsupported
                   :count 1}
                  {:lang :lang/kotlin
                   :kind :kotlin.feature/class
                   :status :feature.status/supported
                   :count 1}]
                 (inventory/feature-counts db))))

        (testing "unsupported Java and Kotlin features are ranked by count and file spread"
          (is (= [{:lang :lang/java
                   :kind :java.feature/reflection
                   :count 3
                   :file-count 2}
                  {:lang :lang/java
                   :kind :java.feature/stream-api
                   :count 1
                   :file-count 1}]
                 (inventory/unsupported-rankings db {:langs #{:lang/java :lang/kotlin}}))))

        (testing "unsupported features are reported by source file"
          (is (= [{:file/id "fixture:src/A.java"
                   :file/path "src/A.java"
                   :file/lang :lang/java
                   :unsupported-count 2
                   :features [{:kind :java.feature/reflection
                               :count 2}]}
                  {:file/id "fixture:src/B.java"
                   :file/path "src/B.java"
                   :file/lang :lang/java
                   :unsupported-count 2
                   :features [{:kind :java.feature/reflection
                               :count 1}
                              {:kind :java.feature/stream-api
                               :count 1}]}]
                 (inventory/unsupported-by-file db))))

        (testing "files with no unsupported features include supported-only and empty files"
          (is (= [{:file/id "fixture:src/C.kt"
                   :file/path "src/C.kt"
                   :file/lang :lang/kotlin}
                  {:file/id "fixture:src/D.kt"
                   :file/path "src/D.kt"
                   :file/lang :lang/kotlin}]
                 (inventory/files-without-unsupported db))))

        (testing "unresolved refs and API gaps are ranked by count and file spread"
          (is (= [{:lang :lang/java
                   :kind :ref.kind/method-call
                   :reason :resolve.reason/missing-classpath
                   :count 2
                   :file-count 2}
                  {:lang :lang/kotlin
                   :kind :ref.kind/function-call
                   :reason :resolve.reason/analysis-api-limitation
                   :count 1
                   :file-count 1}]
                 (inventory/unresolved-ref-rankings db)))
          (is (= [{:lang :lang/java
                   :kind :ref.kind/method-call
                   :reason :resolve.reason/missing-classpath
                   :owner ""
                   :name "invoke"
                   :count 1
                   :file-count 1}
                  {:lang :lang/java
                   :kind :ref.kind/method-call
                   :reason :resolve.reason/missing-classpath
                   :owner "java.util.List"
                   :name "stream"
                   :count 1
                   :file-count 1}
                  {:lang :lang/kotlin
                   :kind :ref.kind/function-call
                   :reason :resolve.reason/analysis-api-limitation
                   :owner ""
                   :name "apply"
                   :count 1
                   :file-count 1}]
                 (inventory/unresolved-ref-detail-rankings db)))
          (is (= [{:lang :lang/java
                   :kind :ref.kind/method-call
                   :name "invoke"
                   :owner ""
                   :reason :resolve.reason/missing-classpath
                   :count 1
                   :file-count 1}
                  {:lang :lang/java
                   :kind :ref.kind/method-call
                   :name "stream"
                   :owner "java.util.List"
                   :reason :resolve.reason/missing-classpath
                   :count 1
                   :file-count 1}
                  {:lang :lang/kotlin
                   :kind :ref.kind/function-call
                   :name "apply"
                   :owner ""
                   :reason :resolve.reason/analysis-api-limitation
                   :count 1
                   :file-count 1}]
                 (inventory/unresolved-api-call-rankings db))))

        (testing "summary returns the task-friendly inventory report"
          (is (= (inventory/feature-counts db)
                 (:feature-counts (inventory/summary db))))
          (is (= (inventory/unresolved-ref-rankings db)
                 (:unresolved-ref-rankings (inventory/summary db))))
          (is (= (inventory/unresolved-ref-detail-rankings db)
                 (:unresolved-ref-detail-rankings (inventory/summary db))))
          (is (= (inventory/files-without-unsupported db {:langs #{:lang/kotlin}})
                 (:files-without-unsupported
                  (inventory/summary db {:langs #{:lang/kotlin}})))))))))
