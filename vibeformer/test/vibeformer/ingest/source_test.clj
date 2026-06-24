(ns vibeformer.ingest.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.source :as ingest])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)
           (java.util UUID)))

(defn- with-empty-db [f]
  (let [system (str "vibeformer-source-ingest-test-" (UUID/randomUUID))
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

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-source-ingest-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- fixture-root []
  (let [root (temp-root)]
    (write-file! root "src/main/java/com/acme/JavaThing.java"
                 "package com.acme.java;\n\npublic class JavaThing {}\n")
    (write-file! root "src/main/kotlin/com/acme/KotlinThing.kt"
                 "package com.acme.kotlin\n\nclass KotlinThing\n")
    (write-file! root "src/main/resources/ignored.txt" "not source\n")
    (write-file! root "src/test/java/com/acme/AlsoJava.java"
                 "package com.acme.test;\n\nclass AlsoJava {}\n")
    root))

(deftest discovers-java-and-kotlin-source-files
  (let [root (fixture-root)
        facts (ingest/source-file-facts {:source/root root
                                         :project/id "fixture"
                                         :project/name "Fixture"})]
    (is (= ["src/main/java/com/acme/JavaThing.java"
            "src/main/kotlin/com/acme/KotlinThing.kt"
            "src/test/java/com/acme/AlsoJava.java"]
           (mapv :file/path facts)))
    (is (= [:lang/java :lang/kotlin :lang/java]
           (mapv :file/lang facts)))
    (is (= ["com.acme.java" "com.acme.kotlin" "com.acme.test"]
           (mapv :file/package facts)))
    (is (every? #(re-matches #"sha256:[0-9a-f]{64}" (:file/hash %)) facts))
    (is (not-any? #(= "src/main/resources/ignored.txt" (:file/path %)) facts))))

(deftest discovers-research-pkl-sources-when-present
  (let [root (some #(when (Files/isDirectory % (make-array java.nio.file.LinkOption 0)) %)
                   [(Paths/get "../research/pkl" (make-array String 0))
                    (Paths/get "research/pkl" (make-array String 0))])]
    (when root
      (let [facts (ingest/source-file-facts {:source/root root
                                             :project/id "pkl"
                                             :project/name "pkl"})
            langs (set (map :file/lang facts))]
        (is (pos? (count facts)))
        (is (contains? langs :lang/java))
        (is (contains? langs :lang/kotlin))))))

(deftest ingests-file-facts-idempotently-and-updates-changed-files
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (fixture-root)
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}
            first-run (ingest/ingest! conn opts)]
        (is (= {:discovered-files 3
                :transacted-files 3
                :skipped-files 0}
               (select-keys first-run [:discovered-files :transacted-files :skipped-files])))
        (testing "project and file facts are queryable"
          (let [db (d/db conn)]
            (is (= "Fixture" (:project/name (d/pull db [:project/name] [:project/id "fixture"]))))
            (is (= #{["fixture:src/main/java/com/acme/JavaThing.java" :lang/java "com.acme.java"]
                     ["fixture:src/main/kotlin/com/acme/KotlinThing.kt" :lang/kotlin "com.acme.kotlin"]
                     ["fixture:src/test/java/com/acme/AlsoJava.java" :lang/java "com.acme.test"]}
                   (set (d/q '[:find ?file-id ?lang ?package
                               :where
                               [?file :file/id ?file-id]
                               [?file :file/lang ?lang]
                               [?file :file/package ?package]]
                             db))))))
        (testing "unchanged reruns skip all file transactions"
          (is (= {:discovered-files 3
                  :transacted-files 0
                  :skipped-files 3}
                 (select-keys (ingest/ingest! conn opts)
                              [:discovered-files :transacted-files :skipped-files])))
          (is (= 1 (ffirst (d/q '[:find (count ?file)
                                  :where
                                  [?file :file/id "fixture:src/main/kotlin/com/acme/KotlinThing.kt"]]
                                (d/db conn))))))
        (testing "changed files update hash and package deterministically"
          (let [kotlin-id "fixture:src/main/kotlin/com/acme/KotlinThing.kt"
                java-id "fixture:src/main/java/com/acme/JavaThing.java"
                old-kotlin-hash (:file/hash (d/pull (d/db conn) [:file/hash] [:file/id kotlin-id]))]
            (write-file! root "src/main/kotlin/com/acme/KotlinThing.kt"
                         "package com.acme.changed\n\nclass KotlinThing\n")
            (write-file! root "src/main/java/com/acme/JavaThing.java"
                         "public class JavaThing {}\n")
            (is (= {:discovered-files 3
                    :transacted-files 2
                    :skipped-files 1}
                   (select-keys (ingest/ingest! conn opts)
                                [:discovered-files :transacted-files :skipped-files])))
            (let [db (d/db conn)
                  kotlin-file (d/pull db [:file/hash :file/package] [:file/id kotlin-id])
                  java-file (d/pull db [:file/package] [:file/id java-id])]
              (is (not= old-kotlin-hash (:file/hash kotlin-file)))
              (is (= "com.acme.changed" (:file/package kotlin-file)))
              (is (nil? (:file/package java-file))))))))))
