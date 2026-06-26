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

(def native-method-fixture
  "package com.example;

public final class NativeCase {
  public native void run();
}
")

(def unsupported-expression-fixture
  "package com.example;

public final class UnsupportedExpressionCase {
  public static String call(String text) {
    return text.substring(1);
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

(defn- coverage-failure-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/native-method/source/src/main/java/com/example/NativeCase.java"
                 native-method-fixture)
    root))

(defn- coverage-allow-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/native-method/source/src/main/java/com/example/NativeCase.java"
                 native-method-fixture)
    root))

(defn- csharp-diagnostic-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/unsupported-expression/source/src/main/java/com/example/UnsupportedExpressionCase.java"
                 unsupported-expression-fixture)
    root))

(deftest discovers-samples-with-source-roots
  (let [root (sample-checkout)]
    (is (= ["hello"]
           (mapv :sample/name (sample-runner/discover-samples root))))))

(deftest runs-supported-sample-stages-and-writes-artifacts
  (let [root (sample-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "hello"
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/hello/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        source-files (read-edn (.resolve target "facts/source-files.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-file (.resolve target "csharp/com/example/Hello.cs")
        project-file (.resolve target "csharp/hello.csproj")
        project-content (delay (slurp (str project-file)))]
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
      (is (= :skipped (:status (last stages))))
      (is (= :dotnet/build-disabled (:reason (last stages)))))
    (testing "diagnostic artifacts identify transform coverage"
      (is (true? (:ok? coverage)))
      (is (empty? (:failures coverage))))
    (testing "facts and provenance point at disposable output"
      (is (= ["src/main/java/com/example/Hello.java"]
             (mapv :file/path source-files)))
      (is (= :generated (:status provenance)))
      (is (= 1 (:csharp/files-written provenance)))
      (is (= ["com/example/Hello.cs"] (:csharp/project-files provenance)))
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
      (is (Files/isRegularFile project-file (make-array java.nio.file.LinkOption 0)))
      (is (str/includes? (slurp (str csharp-file))
                         "public static void Main(string[] args)"))
      (is (str/includes? @project-content "<TargetFramework>net8.0</TargetFramework>"))
      (is (str/includes? @project-content "<ImplicitUsings>disable</ImplicitUsings>"))
      (is (str/includes? @project-content "<Nullable>enable</Nullable>"))
      (is (str/includes? @project-content "<EnableDefaultCompileItems>false</EnableDefaultCompileItems>"))
      (is (str/includes? @project-content "<Compile Include=\"com/example/Hello.cs\" />")))))

(deftest coverage-failure-stops-before-emission
  (let [root (coverage-failure-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "native-method"
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/native-method/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        coverage-stage (some #(when (= :coverage/check (:stage %)) %) stages)]
    (is (false? (:ok? result)))
    (is (= :failed (:status coverage-stage)))
    (is (false? (:ok? coverage)))
    (is (seq (:failures coverage)))
    (is (not-any? #(= :csharp/emit (:stage %)) stages))
    (is (= :skipped (:status provenance)))))

(deftest explicit-coverage-allow-mode-is-recorded
  (let [root (coverage-allow-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "native-method"
                                          :dotnet/enabled? false
                                          :coverage/allow-unsupported? true})
        target (.resolve root "sample-projects/native-method/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        coverage-stage (some #(when (= :coverage/check (:stage %)) %) stages)]
    (is (:ok? result))
    (is (= :ok (:status coverage-stage)))
    (is (= {:allow-unsupported? true} (:coverage/allow-mode coverage-stage)))
    (is (= {:allow-unsupported? true} (:coverage/allow-mode coverage)))
    (is (= {:allow-unsupported? true} (:coverage/allow-mode provenance)))
    (is (some #(= :csharp/emit (:stage %)) stages))))

(deftest csharp-error-diagnostics-stop-before-dotnet-build
  (let [root (csharp-diagnostic-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "unsupported-expression"})
        target (.resolve root "sample-projects/unsupported-expression/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-stage (some #(when (= :csharp/emit (:stage %)) %) stages)
        generated (.resolve target "csharp/com/example/UnsupportedExpressionCase.cs")
        content (slurp (str generated))]
    (is (false? (:ok? result)))
    (is (= :failed (:status csharp-stage)))
    (is (= :csharp/emit-diagnostics (:reason csharp-stage)))
    (is (= 1 (:csharp/error-diagnostics-count csharp-stage)))
    (is (not-any? #(= :dotnet/build (:stage %)) stages))
    (is (= :failed (:status provenance)))
    (is (= :csharp/emit-diagnostics (:reason provenance)))
    (is (= 1 (:csharp/error-diagnostics-count provenance)))
    (is (str/includes? content "default! /* Unsupported Java node method-call */"))
    (is (not (str/includes? content "Unsupported Java node method-call\");;")))))

(deftest explicit-csharp-diagnostic-allow-mode-is-recorded
  (let [root (csharp-diagnostic-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "unsupported-expression"
                                          :dotnet/enabled? false
                                          :csharp/allow-diagnostics? true})
        target (.resolve root "sample-projects/unsupported-expression/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-stage (some #(when (= :csharp/emit (:stage %)) %) stages)
        dotnet-stage (some #(when (= :dotnet/build (:stage %)) %) stages)]
    (is (:ok? result))
    (is (= :ok (:status csharp-stage)))
    (is (= {:allow-diagnostics? true} (:csharp/allow-mode csharp-stage)))
    (is (= 1 (:csharp/error-diagnostics-count csharp-stage)))
    (is (= :skipped (:status dotnet-stage)))
    (is (= {:allow-diagnostics? true} (:csharp/allow-mode provenance)))))

(deftest sample-runner-cli-parses-edn-options
  (is (= {:coverage/allow-unsupported? true}
         (#'sample-runner/parse-cli-opts "{:coverage/allow-unsupported? true}")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"EDN map"
                        (#'sample-runner/parse-cli-opts "[:not :a :map]"))))

(deftest captures-dotnet-build-diagnostics
  (let [root (sample-checkout)
        fake-dotnet (.resolve root "fake-dotnet")
        diagnostic-file (.resolve root "sample-projects/hello/target/diagnostics/dotnet-build.edn")
        diagnostic-facts-file (.resolve root "sample-projects/hello/target/diagnostics/dotnet-diagnostic-facts.edn")
        stdout-file (.resolve root "sample-projects/hello/target/diagnostics/dotnet-build.stdout.log")
        stderr-file (.resolve root "sample-projects/hello/target/diagnostics/dotnet-build.stderr.log")
        script (str "#!/bin/sh\n"
                    "echo \"Determining projects to restore...\"\n"
                    "echo \"$PWD/com/example/Hello.cs(7,13): error CS1002: ; expected [$PWD/hello.csproj]\"\n"
                    "echo \"$PWD/com/example/Missing.cs(1,1): warning CS0168: unused variable [$PWD/hello.csproj]\"\n"
                    "echo \"fake stderr\" >&2\n"
                    "exit 1\n")]
    (write-file! root "fake-dotnet" script)
    (.setExecutable (.toFile fake-dotnet) true)
    (let [result (sample-runner/run-sample {:project-root root
                                            :name "hello"
                                            :dotnet/command (str fake-dotnet)})
          build-stage (some #(when (= :dotnet/build (:stage %)) %) (:stages result))
          ingest-stage (some #(when (= :diagnostics/ingest (:stage %)) %) (:stages result))
          diagnostic-report (read-edn diagnostic-file)
          diagnostic-facts-report (read-edn diagnostic-facts-file)
          diagnostic (first (:diagnostics diagnostic-report))
          mapped-fact (some #(when (= :diagnostic.mapping/mapped
                                      (:diagnostic/mapping-status %))
                               %)
                            (:diagnostics diagnostic-facts-report))
          unmapped-fact (some #(when (= :diagnostic.mapping/unmapped
                                        (:diagnostic/mapping-status %))
                                 %)
                              (:diagnostics diagnostic-facts-report))]
      (is (false? (:ok? result)))
      (is (= :failed (:status build-stage)))
      (is (= 1 (:dotnet/exit build-stage)))
      (is (= :ok (:status ingest-stage)))
      (is (= 2 (:diagnostic/facts-count ingest-stage)))
      (is (= 1 (:diagnostic/mapped-count ingest-stage)))
      (is (= 1 (:diagnostic/unmapped-count ingest-stage)))
      (is (Files/isRegularFile stdout-file (make-array java.nio.file.LinkOption 0)))
      (is (Files/isRegularFile stderr-file (make-array java.nio.file.LinkOption 0)))
      (is (Files/isRegularFile diagnostic-facts-file (make-array java.nio.file.LinkOption 0)))
      (is (= "fake stderr\n" (slurp (str stderr-file))))
      (is (str/ends-with? (:file diagnostic) "/sample-projects/hello/target/csharp/com/example/Hello.cs"))
      (is (= {:line 7
              :column 13
              :severity :diagnostic.severity/error
              :code "CS1002"
              :message "; expected"}
             (dissoc diagnostic :file)))
      (is (= :diagnostic.mapping/provenance-span (:diagnostic/mapping-reason mapped-fact)))
      (is (some? (:diagnostic/source-node mapped-fact)))
      (is (some? (:diagnostic/rule mapped-fact)))
      (is (= :diagnostic.mapping/no-provenance-span (:diagnostic/mapping-reason unmapped-fact)))
      (is (= #{{:diagnostic/code "CS1002"
                :diagnostic/mapping-status :diagnostic.mapping/mapped
                :diagnostic/rule :java.class-node/to-csharp-class
                :diagnostic/source-node (second (:diagnostic/source-node mapped-fact))}
               {:diagnostic/code "CS0168"
                :diagnostic/mapping-status :diagnostic.mapping/unmapped
                :diagnostic/rule nil
                :diagnostic/source-node nil}}
             (set (:query-summary diagnostic-facts-report))))
      (is (= (:command diagnostic-report) (:command build-stage)))
      (is (= (:target/project diagnostic-report) (:target/project build-stage))))))
