(ns vibeformer.research-classpath-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.research-classpath :as research-classpath])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path)))

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-research-classpath-test-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- read-edn [^Path file]
  (edn/read-string (slurp (str file))))

(defn- gradle-fixture []
  (let [root (temp-root)]
    (write-file! root "settings.gradle.kts"
                 "rootProject.name = \"fixture\"
includeBuild(\"build-logic\")
include(\"app\", \":lib:core\")
")
    (write-file! root "gradle/libs.versions.toml"
                 "[versions]
kotlin = \"2.2.21\"
msgpack = \"0.9.12\"

[libraries]
kotlinReflect = { group = \"org.jetbrains.kotlin\", name = \"kotlin-reflect\", version.ref = \"kotlin\" }
msgpack = { group = \"org.msgpack\", name = \"msgpack-core\", version.ref = \"msgpack\" }
truffleApi = { group = \"org.graalvm.truffle\", name = \"truffle-api\", version = \"24.2.0\" }

[plugins]
kotlinxSerialization = { id = \"org.jetbrains.kotlin.plugin.serialization\", version.ref = \"kotlin\" }
")
    (write-file! root "build.gradle.kts"
                 "plugins { alias(libs.plugins.kotlinxSerialization) apply false }\n")
    (write-file! root "app/app.gradle.kts"
                 "plugins { kotlin(\"jvm\") }

dependencies {
  implementation(projects.lib.core)
  implementation(libs.kotlinReflect)
  implementation(libs.truffleApi)
  testImplementation(\"org.junit.jupiter:junit-jupiter-api:6.1.0\")
  add(\"generatorImplementation\", libs.msgpack)
}
")
    (write-file! root "app/src/main/kotlin/com/example/App.kt" "package com.example\n")
    (write-file! root "app/src/test/resources/data.txt" "fixture\n")
    (write-file! root "lib/core/core.gradle.kts"
                 "dependencies {
  api(libs.msgpack)
}
")
    (write-file! root "lib/core/src/main/java/com/example/Core.java" "package com.example;\n")
    root))

(deftest discovers-gradle-projects-source-roots-and-dependencies
  (let [root (gradle-fixture)
        out (.resolve root "target/classpath.edn")
        report (research-classpath/run-classpath-inventory {:project-root root
                                                            :research/root root
                                                            :out out})
        written (read-edn out)
        app (first (filter #(= ":app" (:project/path %)) (:projects report)))
        core (first (filter #(= ":lib:core" (:project/path %)) (:projects report)))]
    (is (= :vibeformer.report/research-classpath (:report/type report)))
    (is (= report written))
    (is (= ["app" ":lib:core"] (get-in report [:settings :settings/includes])))
    (is (= ["build-logic"] (get-in report [:settings :settings/include-builds])))
    (is (= 3 (:projects/count report)))
    (is (= 3 (:source-roots/count report)))
    (is (= 6 (:dependencies/count report)))
    (is (= ["com.oracle.truffle.api"
            "org.graalvm.truffle"
            "org.jetbrains.kotlin"
            "org.junit.jupiter"
            "org.msgpack"]
           (:java/classpath-package-roots report)))
    (is (= 5 (:java/classpath-package-roots/count report)))
    (is (= {"Action" "org.gradle.api.Action"
            "DependencyConstraint" "org.gradle.api.artifacts.DependencyConstraint"
            "ExternalModuleDependency" "org.gradle.api.artifacts.ExternalModuleDependency"
            "ProviderConvertible" "org.gradle.api.provider.ProviderConvertible"
            "PublishArtifact" "org.gradle.api.artifacts.PublishArtifact"}
           (:kotlin/classpath-types report)))
    (is (= 5 (:kotlin/classpath-types/count report)))
    (testing "conventional source roots are grouped by source-set and language"
      (is (= #{{:source/relative-path "src/main/kotlin"
                :source/source-set "main"
                :source/kind :source.kind/kotlin}
               {:source/relative-path "src/test/resources"
                :source/source-set "test"
                :source/kind :source.kind/resources}}
             (set (map #(select-keys % [:source/relative-path :source/source-set :source/kind])
                       (:source/roots app)))))
      (is (= [{:source/relative-path "src/main/java"
               :source/source-set "main"
               :source/kind :source.kind/java}]
             (mapv #(select-keys % [:source/relative-path :source/source-set :source/kind])
                   (:source/roots core)))))
    (testing "project accessors, catalog aliases, coordinates, and add(...) dependencies are captured"
      (is (= #{{:dependency/configuration "implementation"
                :dependency/kind :dependency.kind/project-accessor
                :dependency/project ":lib:core"}
               {:dependency/configuration "implementation"
                :dependency/kind :dependency.kind/version-catalog
                :dependency/catalog-alias "kotlinReflect"}
               {:dependency/configuration "implementation"
                :dependency/kind :dependency.kind/version-catalog
                :dependency/catalog-alias "truffleApi"}
               {:dependency/configuration "testImplementation"
                :dependency/kind :dependency.kind/coordinate
                :dependency/coordinate "org.junit.jupiter:junit-jupiter-api:6.1.0"}
               {:dependency/configuration "generatorImplementation"
                :dependency/kind :dependency.kind/version-catalog
                :dependency/catalog-alias "msgpack"}}
             (set (map #(select-keys % [:dependency/configuration
                                         :dependency/kind
                                         :dependency/project
                                         :dependency/catalog-alias
                                         :dependency/coordinate])
                       (:dependencies app))))))
    (testing "version catalog libraries are resolved with version refs"
      (is (contains?
           (set (:catalog/libraries (:version-catalog report)))
           {:catalog/alias "kotlinReflect"
            :catalog/group "org.jetbrains.kotlin"
            :catalog/name "kotlin-reflect"
            :catalog/version-ref "kotlin"
            :catalog/version "2.2.21"})))))
