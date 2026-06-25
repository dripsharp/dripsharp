(ns vibeformer.emit.csharp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)
           (java.util UUID)))

(def java-fixture
  "package com.example.tools;

public final class Counter {
  private static final String EMPTY = \"\";
  private int ignored;

  private Counter() {
  }

  public static void main(String[] args) {
  }

  static int countWords(String text) {
    return 0;
  }
}
")

(defn- with-empty-db [f]
  (let [system (str "vibeformer-csharp-emit-test-" (UUID/randomUUID))
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
  (Files/createTempDirectory "vibeformer-csharp-emit-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(deftest emits-java-declarations-as-csharp-skeletons
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/tools/Counter.java"
            opts {:source/root source-root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! source-root file-path java-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "fixture"})
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/tools/Counter.cs")
              content (slurp (str generated))]
          (is (= 1 (:csharp/files-written result)))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (testing "namespace, class, static final field, constructor, and methods are emitted"
            (doseq [snippet ["namespace com.example.tools"
                             "public sealed class Counter"
                             "private static readonly string EMPTY;"
                             "private Counter()"
                             "public static void Main(string[] args)"
                             "internal static int countWords(string text)"
                             "throw new System.NotImplementedException();"]]
              (is (str/includes? content snippet))))
          (testing "non-static instance fields are outside the initial declaration subset"
            (is (not (str/includes? content "ignored")))))))))
