(ns vibeformer.emit.csharp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.emit.csharp :as csharp]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.source :as source]
            [vibeformer.transform.rules :as rules])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)
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

(defn- sample-word-counter-source []
  (slurp (str (Paths/get "sample-projects/java-word-count/source/src/main/java/com/example/wordcount/WordCounter.java"
                         (make-array String 0)))))

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
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/tools/Counter.cs")
              content (slurp (str generated))]
          (is (= 1 (:csharp/files-written result)))
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (testing "namespace, class, static final field, constructor, and methods are emitted"
            (doseq [snippet ["namespace com.example.tools"
                             "public sealed class Counter"
                             "private static readonly string EMPTY = \"\";"
                             "private Counter()"
                             "public static void Main(string[] args)"
                             "internal static int countWords(string text)"
                             "return 0;"
                             "throw new System.NotImplementedException();"]]
              (is (str/includes? content snippet))))
          (testing "emission returns provenance-friendly rule applications"
            (is (seq (:csharp/rule-applications result)))
            (is (seq (:csharp/provenance result)))
            (is (empty? (:csharp/diagnostics result))))
          (testing "non-static instance fields are outside the initial declaration subset"
            (is (not (str/includes? content "ignored")))))))))

(deftest emits-word-counter-statement-and-expression-subset
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            target (.resolve root "target/csharp")
            source-root (.resolve root "source")
            file-path "src/main/java/com/example/wordcount/WordCounter.java"
            opts {:source/root source-root
                  :project/id "word-count"
                  :project/name "Word Count"}]
        (write-file! source-root file-path (sample-word-counter-source))
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "word-count"})
        (rules/register! conn rules/initial-java-rules)
        (let [result (csharp/emit! (d/db conn) target)
              generated (.resolve target "com/example/wordcount/WordCounter.cs")
              content (slurp (str generated))
              rules (set (map (comp second :rule-app/rule)
                              (:csharp/rule-applications result)))
              emitter-source (slurp "src/vibeformer/emit/csharp.clj")]
          (is (Files/isRegularFile generated (make-array java.nio.file.LinkOption 0)))
          (doseq [snippet ["using System.Text.RegularExpressions;"
                           "private static readonly Regex WHITESPACE = new Regex(\"\\\\s+\");"
                           "if (args.Length != 1)"
                           "System.Console.Error.WriteLine(\"Usage: WordCounter <file>\");"
                           "System.Environment.Exit(1);"
                           "string input = args[0];"
                           "string text = System.IO.File.ReadAllText(input);"
                           "int words = WordCounter.countWords(text);"
                           "System.Console.WriteLine(words);"
                           "string trimmed = text.Trim();"
                           "if (string.IsNullOrEmpty(trimmed))"
                           "return 0;"
                           "return WordCounter.WHITESPACE.Split(trimmed).Length;"]]
            (is (str/includes? content snippet)))
          (is (empty? (:csharp/diagnostics result)))
          (is (every? rules
                      [:java.regex-pattern-compile/to-csharp-regex
                       :java.string-trim/to-csharp-trim
                       :java.string-is-empty/to-csharp-is-null-or-empty
                       :java.regex-split/to-csharp-regex-split
                       :java.printstream-println/to-csharp-console
                       :java.system-exit/to-csharp-environment-exit
                       :java.path-of/to-csharp-string-path
                       :java.files-read-string/to-csharp-file-read-all-text]))
          (testing "provenance is deterministic structural emitter output"
            (let [provenance (:csharp/provenance result)
                  second-result (csharp/emit! (d/db conn) target)
                  word-counter (some #(when (= :java.class-node/to-csharp-class
                                               (get-in % [:rule :rule/id]))
                                        %)
                                     provenance)
                  count-words (some #(when (and (= :java.method-node/to-csharp-method
                                                  (get-in % [:rule :rule/id]))
                                               (= "countWords" (:source/name %)))
                                       %)
                                    provenance)]
              (is (= provenance (:csharp/provenance second-result)))
              (is (= "src/main/java/com/example/wordcount/WordCounter.java"
                     (:source/file word-counter)))
              (is (= :java.node/class (:source/kind word-counter)))
              (is (= 1 (get-in word-counter [:rule :rule/version])))
              (is (some? (:emit/dest-span word-counter)))
              (is (some? count-words))
              (is (= :java.node/method (:source/kind count-words)))
              (is (some? (:emit/dest-span count-words)))
              (is (every? #(and (:emit/source-node %)
                                (:emit/rule %)
                                (:rule %))
                          provenance))
              (is (not-any? #(contains? % :source/text) provenance))))
          (is (not (str/includes? emitter-source "source-text"))))))))
