(ns vibeformer.sample-runner-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.sample-runner :as sample-runner])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)))

(def java-fixture
  "package com.example;

public final class Hello {
  public static void main(String[] args) {
    System.out.println(\"hello\");
  }
}
")

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-sample-runner-test-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- read-edn [^Path file]
  (edn/read-string (slurp (str file))))

(defn- sample-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/hello/source/src/main/java/com/example/Hello.java" java-fixture)
    (write-file! root "sample-projects/ignored/README.md" "no source root\n")
    root))

(deftest discovers-samples-with-source-roots
  (let [root (sample-checkout)]
    (is (= ["hello"]
           (mapv :sample/name (sample-runner/discover-samples root))))))

(deftest runs-supported-sample-stages-and-writes-artifacts
  (let [root (sample-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "hello"})
        target (.resolve root "sample-projects/hello/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        source-files (read-edn (.resolve target "facts/source-files.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-file (.resolve target "csharp/com/example/Hello.cs")]
    (is (:ok? result))
    (testing "target layout is created"
      (doseq [dir ["csharp" "diagnostics" "facts"]]
        (is (Files/isDirectory (.resolve target dir)
                               (make-array java.nio.file.LinkOption 0)))))
    (testing "the supported stages run and future stages are explicit"
      (is (= [:schema/install
              :source/discover
              :source/ingest
              :java/ingest
              :transform/rules
              :diagnostics/inventory
              :coverage/check
              :csharp/emit
              :dotnet/build]
             (mapv :stage stages)))
      (is (= :ok (:status (some #(when (= :csharp/emit (:stage %)) %) stages))))
      (is (= :skipped (:status (last stages)))))
    (testing "diagnostic artifacts identify transform coverage"
      (is (true? (:ok? coverage)))
      (is (empty? (:failures coverage))))
    (testing "facts and provenance point at disposable output"
      (is (= ["src/main/java/com/example/Hello.java"]
             (mapv :file/path source-files)))
      (is (= :generated (:status provenance)))
      (is (= 1 (:csharp/files-written provenance)))
      (is (seq (:csharp/rule-applications provenance)))
      (is (seq (:csharp/provenance provenance)))
      (is (some #(and (= :java.class-node/to-csharp-class
                         (get-in % [:rule :rule/id]))
                      (= "src/main/java/com/example/Hello.java"
                         (:source/file %))
                      (seq (:source/declarations %))
                      (:emit/dest-span %))
                (:csharp/provenance provenance)))
      (is (empty? (:csharp/diagnostics provenance)))
      (is (Files/isRegularFile csharp-file (make-array java.nio.file.LinkOption 0)))
      (is (str/includes? (slurp (str csharp-file))
                         "public static void Main(string[] args)")))))
