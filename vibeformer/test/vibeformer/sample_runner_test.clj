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

(def optional-helper-fixture
  "package com.example.helpers;

import java.util.Optional;

public final class OptionalHelper {
  public Optional<String> maybe(String value) {
    return null;
  }
}
")

(def kotlin-fixture
  "package com.example.kotlin

import java.net.URI
import java.nio.file.Path

object KotlinApiCalls {
  fun message(name: String?): String {
    val raw = \"\"\"
      hello
    \"\"\".trimIndent()
    return name?.let { raw + it } ?: raw
  }

  fun values(root: Path): List<URI> {
    return listOf(root.resolve(\"child\").toUri(), URI(\"https://example.com\"))
  }
}
")

(def kotlin-basic-fixture
  "package com.example.kotlin

object BasicDeclarations {
  val count: Int = 1

  fun describe(name: String?): String {
    return \"\"
  }
}
")

(def kotlin-object-overrides-fixture
  "package com.example.kotlin

import java.net.URI

interface ModuleReader {
  val isLocal: Boolean
  val scheme: String

  fun read(uri: URI): String

  fun listElements(uri: URI): List<String>
}

object FixtureModuleReader : ModuleReader {
  override val isLocal: Boolean = true

  override val scheme: String = \"foo\"

  override fun read(uri: URI): String = \"hello\"

  override fun listElements(uri: URI): List<String> {
    throw NotImplementedError()
  }
}
")

(def kotlin-top-level-fixture
  "package com.example.kotlin

val answer: Int = 42
val greeting: String = \"hello\"

fun render(name: String): String {
  return greeting
}

fun constant(): Int = answer
")

(def negated-pattern-version-fixture
  "package org.pkl.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class Version {
  private final int major;
  private final int minor;
  private final int patch;
  private final @Nullable String preRelease;

  public Version(int major, int minor, int patch, @Nullable String preRelease) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.preRelease = preRelease;
  }

  public int hashCode() {
    return Objects.hash(major, minor, patch, preRelease);
  }

  public int smallerMajor(Version other) {
    return Math.min(major, other.major);
  }

  public int largerMajor(Version other) {
    return Math.max(major, other.major);
  }
}
")

(def negated-pattern-demo-fixture
  "package org.pkl.core;

public final class DataSizeDemo {
  private DataSizeDemo() {
  }

  public static void main(String[] args) {
    Version version = new Version(1, 2, 3, \"beta\");
    version.hashCode();
    version.smallerMajor(new Version(2, 0, 0, null));
    version.largerMajor(new Version(2, 0, 0, null));
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

(defn- optional-helper-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/java-optional-helper/source/src/main/java/com/example/helpers/OptionalHelper.java"
                 optional-helper-fixture)
    root))

(defn- kotlin-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/kotlin-api-calls/source/src/main/kotlin/com/example/kotlin/ApiCalls.kt"
                 kotlin-fixture)
    root))

(defn- kotlin-basic-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/kotlin-basic-declarations/source/src/main/kotlin/com/example/kotlin/BasicDeclarations.kt"
                 kotlin-basic-fixture)
    root))

(defn- kotlin-object-overrides-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/kotlin-object-overrides/source/src/main/kotlin/com/example/kotlin/ObjectOverrides.kt"
                 kotlin-object-overrides-fixture)
    root))

(defn- kotlin-top-level-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/kotlin-top-level/source/src/main/kotlin/com/example/kotlin/Utilities.kt"
                 kotlin-top-level-fixture)
    root))

(defn- negated-pattern-checkout []
  (let [root (temp-root)]
    (write-file! root "sample-projects/java-negated-pattern/source/src/main/java/org/pkl/core/Version.java"
                 negated-pattern-version-fixture)
    (write-file! root "sample-projects/java-negated-pattern/source/src/main/java/org/pkl/core/DataSizeDemo.java"
                 negated-pattern-demo-fixture)
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
	        destination (read-edn (.resolve target "facts/destination.edn"))
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
	      (is (= "hello:csharp" (:destination/project provenance)))
	      (is (= {:report/type :vibeformer.report/destination-mapping
	              :projects/count 1
	              :project-references/count 0
	              :packages/count 0
	              :resources/count 0
	              :helpers/count 0}
	             (select-keys destination [:report/type
	                                       :projects/count
	                                       :project-references/count
	                                       :packages/count
	                                       :resources/count
	                                       :helpers/count])))
	      (is (= [{:dest.project/id "hello:csharp"
	               :dest.project/target-framework "net8.0"
	               :dest.project/items [{:dest.item/kind :dest.item.kind/compile
	                                     :dest.item/path "com/example/Hello.cs"}]}]
	             (mapv #(select-keys % [:dest.project/id
	                                     :dest.project/target-framework
	                                     :dest.project/items])
	                   (:projects destination))))
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
      (is (str/includes? @project-content "<OutputType>Library</OutputType>"))
      (is (str/includes? @project-content "<ImplicitUsings>disable</ImplicitUsings>"))
      (is (str/includes? @project-content "<Nullable>enable</Nullable>"))
      (is (str/includes? @project-content "<EnableDefaultCompileItems>false</EnableDefaultCompileItems>"))
      (is (str/includes? @project-content "<Compile Include=\"com/example/Hello.cs\" />")))))

(deftest writes-runtime-helper-sources-and-project-items
  (let [root (optional-helper-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "java-optional-helper"
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/java-optional-helper/target")
        destination (read-edn (.resolve target "facts/destination.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        project-file (.resolve target "csharp/java-optional-helper.csproj")
        source-file (.resolve target "csharp/com/example/helpers/OptionalHelper.cs")
        helper-file (.resolve target "csharp/Vibeformer/Runtime/JavaOptional.cs")
        project-content (slurp (str project-file))
        helper-content (slurp (str helper-file))]
    (is (:ok? result))
    (is (= :generated (:status provenance)))
    (is (= [:helper/java-optional] (:csharp/helpers provenance)))
    (is (= 1 (:helpers/count destination)))
    (is (= ["Vibeformer/Runtime/JavaOptional.cs"
            "com/example/helpers/OptionalHelper.cs"]
           (sort (:csharp/project-files provenance))))
    (is (= [{:helper/id :helper/java-optional
             :helper/name "JavaOptional"
             :helper/path "Vibeformer/Runtime/JavaOptional.cs"
             :helper/source :helper.source/type-mapping
             :helper/status :helper.status/generated
             :helper/project-path "Vibeformer/Runtime/JavaOptional.cs"}]
           (mapv #(dissoc % :helper/file) (:csharp/helper-files provenance))))
    (is (= #{{:dest.item/kind :dest.item.kind/compile
              :dest.item/path "com/example/helpers/OptionalHelper.cs"}
             {:dest.item/kind :dest.item.kind/helper
              :dest.item/path "Vibeformer/Runtime/JavaOptional.cs"}}
           (->> destination
                :projects
                first
                :dest.project/items
                (map #(select-keys % [:dest.item/kind :dest.item/path]))
                set)))
    (is (Files/isRegularFile source-file (make-array java.nio.file.LinkOption 0)))
    (is (Files/isRegularFile helper-file (make-array java.nio.file.LinkOption 0)))
    (is (str/includes? project-content "<Compile Include=\"com/example/helpers/OptionalHelper.cs\" />"))
    (is (str/includes? project-content "<Compile Include=\"Vibeformer/Runtime/JavaOptional.cs\" />"))
    (is (str/includes? helper-content "public static object? OfNullable(object? value)"))))

(deftest runs-negated-pattern-sample-with-nullable-version
  (let [root (negated-pattern-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "java-negated-pattern"
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/java-negated-pattern/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        version-file (.resolve target "csharp/org/pkl/core/Version.cs")
        demo-file (.resolve target "csharp/org/pkl/core/DataSizeDemo.cs")
        version-content (slurp (str version-file))
        demo-content (slurp (str demo-file))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (= :ok (get-in stage-by-name [:csharp/emit :status])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (true? (:ok? coverage)))
    (is (= :generated (:status provenance)))
    (is (empty? (:csharp/diagnostics provenance)))
    (is (str/includes? version-content "private readonly string? preRelease;"))
    (is (str/includes? version-content "public Version(int major, int minor, int patch, string? preRelease)"))
    (is (str/includes? demo-content "new Version(2, 0, 0, null)"))))

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

(deftest runs-kotlin-sample-through-facts-only-pipeline
  (let [root (kotlin-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "kotlin-api-calls"
                                          :kotlin/analysis-api? true})
        target (.resolve root "sample-projects/kotlin-api-calls/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        inventory (read-edn (.resolve target "diagnostics/inventory.edn"))
        source-files (read-edn (.resolve target "facts/source-files.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= [:schema/install
            :source/discover
            :source/ingest
            :java/ingest
            :kotlin/ingest
            :kotlin/enrich
            :transform/rules
            :diagnostics/inventory
            :coverage/check
            :csharp/emit
            :dotnet/build]
           (mapv :stage stages)))
    (is (= :skipped (get-in stage-by-name [:java/ingest :status])))
    (is (= :java/no-source-files (get-in stage-by-name [:java/ingest :reason])))
    (is (= :ok (get-in stage-by-name [:kotlin/ingest :status])))
    (is (= 1 (get-in stage-by-name [:kotlin/ingest :kotlin-files])))
    (is (pos? (get-in stage-by-name [:kotlin/enrich :semantic-refs])))
    (is (= :analysis-api.prototype/unavailable
           (get-in stage-by-name [:kotlin/enrich :analysis-api/status])))
    (is (= :analysis-api.reason/classes-not-on-classpath
           (get-in stage-by-name [:kotlin/enrich :analysis-api/reason])))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (= {:allow-stubs? true
            :strategy :coverage.strategy/kotlin-facts-only}
           (get-in stage-by-name [:coverage/check :coverage/allow-mode])))
    (is (= :skipped (get-in stage-by-name [:csharp/emit :status])))
    (is (= :pipeline.kotlin/csharp-emission-not-implemented
           (get-in stage-by-name [:csharp/emit :reason])))
    (is (= :csharp.strategy/kotlin-facts-only
           (get-in stage-by-name [:csharp/emit :csharp/strategy])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (= :dotnet/no-csharp-output
           (get-in stage-by-name [:dotnet/build :reason])))
    (is (true? (:ok? coverage)))
    (is (= {:allow-stubs? true
            :strategy :coverage.strategy/kotlin-facts-only}
           (:coverage/allow-mode coverage)))
    (is (= ["src/main/kotlin/com/example/kotlin/ApiCalls.kt"]
           (mapv :file/path source-files)))
    (is (= [:lang/kotlin] (mapv :file/lang source-files)))
    (is (some #(= :lang/kotlin (:lang %))
              (:feature-counts inventory)))
    (is (= :skipped (:status provenance)))
    (is (= :pipeline.kotlin/csharp-emission-not-implemented (:reason provenance)))
    (is (= :csharp.strategy/kotlin-facts-only (:csharp/strategy provenance)))
    (is (= {:allow-stubs? true
            :strategy :coverage.strategy/kotlin-facts-only}
           (:coverage/allow-mode provenance)))))

(deftest runs-kotlin-sample-through-emission-pipeline
  (let [root (kotlin-basic-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "kotlin-basic-declarations"
                                          :kotlin/emit? true
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/kotlin-basic-declarations/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-file (.resolve target "csharp/com/example/kotlin/BasicDeclarations.cs")
        content (slurp (str csharp-file))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= :ok (get-in stage-by-name [:kotlin/ingest :status])))
    (is (= :ok (get-in stage-by-name [:kotlin/enrich :status])))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (nil? (get-in stage-by-name [:coverage/check :coverage/allow-mode])))
    (is (= :ok (get-in stage-by-name [:csharp/emit :status])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (= :dotnet/build-disabled (get-in stage-by-name [:dotnet/build :reason])))
    (is (true? (:ok? coverage)))
    (is (nil? (:coverage/allow-mode coverage)))
    (is (= :generated (:status provenance)))
    (is (= 1 (:csharp/files-written provenance)))
    (is (seq (:csharp/provenance provenance)))
    (is (some #(= :kotlin.object-node/to-csharp-stub
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (str/includes? content "#nullable enable"))
    (is (str/includes? content "public static class BasicDeclarations"))
    (is (str/includes? content "public static int count { get; } = default!;"))
    (is (str/includes? content "public static string describe(string? name)"))
    (is (str/includes? content "return \"\";"))))

(deftest runs-kotlin-api-call-sample-through-emission-pipeline
  (let [root (kotlin-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "kotlin-api-calls"
                                          :kotlin/emit? true
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/kotlin-api-calls/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-file (.resolve target "csharp/com/example/kotlin/KotlinApiCalls.cs")
        content (slurp (str csharp-file))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (nil? (get-in stage-by-name [:coverage/check :coverage/allow-mode])))
    (is (= :ok (get-in stage-by-name [:csharp/emit :status])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (true? (:ok? coverage)))
    (is (nil? (:coverage/allow-mode coverage)))
    (is (= :generated (:status provenance)))
    (is (= 1 (:csharp/files-written provenance)))
    (is (some #(= :kotlin.local-property-node/to-csharp-local
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (some #(= :kotlin.return-node/to-csharp-return
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (str/includes? content "public static string message(string? name)"))
    (is (str/includes? content "var raw = \"hello\";"))
    (is (str/includes? content "return name is not null ? raw + name : raw;"))
    (is (str/includes? content "public static List<Uri> values(string root)"))
    (is (str/includes? content "new Uri(System.IO.Path.Combine(root, \"child\"), UriKind.RelativeOrAbsolute)"))))

(deftest runs-kotlin-top-level-sample-through-emission-pipeline
  (let [root (kotlin-top-level-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "kotlin-top-level"
                                          :kotlin/emit? true
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/kotlin-top-level/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        csharp-file (.resolve target "csharp/com/example/kotlin/UtilitiesKt.cs")
        content (slurp (str csharp-file))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (nil? (get-in stage-by-name [:coverage/check :coverage/allow-mode])))
    (is (= :ok (get-in stage-by-name [:csharp/emit :status])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (true? (:ok? coverage)))
    (is (nil? (:coverage/allow-mode coverage)))
    (is (= :generated (:status provenance)))
    (is (= 1 (:csharp/files-written provenance)))
    (is (some #(= :kotlin.file-facade/to-csharp-static-class
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (str/includes? content "public static class UtilitiesKt"))
    (is (str/includes? content "public static int answer { get; } = 42;"))
    (is (str/includes? content "public static string greeting { get; } = \"hello\";"))
    (is (str/includes? content "public static string render(string name)"))
    (is (str/includes? content "return greeting;"))
    (is (str/includes? content "public static int constant()"))
    (is (str/includes? content "return answer;"))))

(deftest runs-kotlin-object-overrides-sample-through-emission-pipeline
  (let [root (kotlin-object-overrides-checkout)
        result (sample-runner/run-sample {:project-root root
                                          :name "kotlin-object-overrides"
                                          :kotlin/emit? true
                                          :dotnet/enabled? false})
        target (.resolve root "sample-projects/kotlin-object-overrides/target")
        stages (read-edn (.resolve target "diagnostics/stages.edn"))
        coverage (read-edn (.resolve target "diagnostics/coverage.edn"))
        provenance (read-edn (.resolve target "provenance.edn"))
        interface-file (.resolve target "csharp/com/example/kotlin/ModuleReader.cs")
        object-file (.resolve target "csharp/com/example/kotlin/FixtureModuleReader.cs")
        interface-content (slurp (str interface-file))
        object-content (slurp (str object-file))
        stage-by-name (into {} (map (juxt :stage identity) stages))]
    (is (:ok? result))
    (is (= :ok (get-in stage-by-name [:coverage/check :status])))
    (is (nil? (get-in stage-by-name [:coverage/check :coverage/allow-mode])))
    (is (= :ok (get-in stage-by-name [:csharp/emit :status])))
    (is (= :skipped (get-in stage-by-name [:dotnet/build :status])))
    (is (true? (:ok? coverage)))
    (is (nil? (:coverage/allow-mode coverage)))
    (is (= :generated (:status provenance)))
    (is (= 2 (:csharp/files-written provenance)))
    (is (some #(= :kotlin.object-node/to-csharp-stub
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (some #(= :kotlin.throw-node/to-csharp-throw
                  (get-in % [:rule :rule/id]))
              (:csharp/provenance provenance)))
    (is (str/includes? interface-content "public interface ModuleReader"))
    (is (str/includes? interface-content "List<string> listElements(Uri uri);"))
    (is (str/includes? object-content "public sealed class FixtureModuleReader : ModuleReader"))
    (is (str/includes? object-content "public static readonly FixtureModuleReader Instance = new FixtureModuleReader();"))
    (is (str/includes? object-content "public bool isLocal { get; } = true;"))
    (is (str/includes? object-content "public string scheme { get; } = \"foo\";"))
    (is (str/includes? object-content "return \"hello\";"))
    (is (str/includes? object-content "throw new NotImplementedException();"))))

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
      (is (= {:mapped-count 1
              :mapped-with-source-node-count 1
              :mapped-with-rule-count 1
              :mapped-with-feature-count 1
              :unmapped-rankings [{:diagnostic/code "CS0168"
                                   :diagnostic/severity :diagnostic.severity/warning
                                   :diagnostic/message "unused variable"
                                   :diagnostic/mapping-reason :diagnostic.mapping/no-provenance-span
                                   :count 1
                                   :file-count 1}]}
             (:diagnostic/mapping-quality ingest-stage)))
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
      (is (seq (:diagnostic/source-features mapped-fact)))
      (is (= :diagnostic.mapping/no-provenance-span (:diagnostic/mapping-reason unmapped-fact)))
      (is (= (:diagnostic/mapping-quality ingest-stage)
             (:mapping-quality diagnostic-facts-report)))
      (is (= (get-in diagnostic-facts-report [:mapping-quality :unmapped-rankings])
             (:unmapped-rankings diagnostic-facts-report)))
      (is (= #{{:diagnostic/code "CS1002"
                :diagnostic/mapping-status :diagnostic.mapping/mapped
                :diagnostic/rule :java.class-node/to-csharp-class
                :diagnostic/source-node (second (:diagnostic/source-node mapped-fact))
                :diagnostic/source-features [:java.feature/class]}
               {:diagnostic/code "CS0168"
                :diagnostic/mapping-status :diagnostic.mapping/unmapped
                :diagnostic/rule nil
                :diagnostic/source-node nil
                :diagnostic/source-features []}}
             (set (:query-summary diagnostic-facts-report))))
      (is (= (:command diagnostic-report) (:command build-stage)))
      (is (= (:target/project diagnostic-report) (:target/project build-stage))))))
