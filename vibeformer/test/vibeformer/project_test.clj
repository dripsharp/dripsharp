(ns vibeformer.project-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project]
            [vibeformer.project-input :as project-input])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "vibeformer-project-test" (make-array FileAttribute 0)))

(defn- create-file!
  [^Path root relative]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file "test" (make-array OpenOption 0))
    file))

(defn- write-file!
  [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- write-manifest!
  [^Path root records]
  (let [manifest (paths/resolve-path root "manifest.tsv")
        resource-root (doto (paths/resolve-path root "resources-output")
                        (Files/createDirectories (make-array FileAttribute 0)))
        records (if (some #(= "project-path" (first %)) records)
                  records
                  (cons ["project-path" ":pkl-parser"] records))
        records (if (some #(= "resource-root" (first %)) records)
                  records
                  (cons ["resource-root" resource-root] records))]
    (spit (str manifest)
          (str "VIBEFORMER_GRADLE_INPUTS_V3\n"
               (apply str (for [[kind value] records]
                            (str kind "\t" value "\n")))))
    manifest))

(defn- write-v5-manifest!
  [^Path root records]
  (let [manifest (paths/resolve-path root "manifest-v5.tsv")]
    (spit (str manifest)
          (str "VIBEFORMER_GRADLE_INPUTS_V5\n"
               (apply str
                      (map #(str (str/join "\t" %) "\n") records))))
    manifest))

(deftest source-resource-and-classpath-discovery
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-b (create-file! root "src/B.java")
        source-a (create-file! root "src/A.java")
        resource (create-file! root "resources-output/errorMessages.properties")
        classpath (doto (paths/resolve-path root "classes/main")
                    (Files/createDirectories (make-array FileAttribute 0)))
        manifest (write-manifest!
                  root
                  [["source" source-b]
                   ["classpath" classpath]
                   ["java-home" java-home]
                   ["java-release" "17"]
                   ["preview-features" "false"]
                   ["resource" resource]
                   ["source" source-a]])
        discovery (project/read-discovery-manifest manifest)]
    (is (= [source-a source-b] (:production-sources discovery)))
    (is (= [] (:generated-production-sources discovery)))
    (is (= [resource] (:production-resources discovery)))
    (is (= [(paths/resolve-path root "resources-output")]
           (:resource-roots discovery)))
    (is (= [classpath] (project-input/compile-classpath discovery)))
    (is (= ":pkl-parser" (:project-id discovery)))
    (is (= {:home java-home :release 17 :preview-features? false}
           (:java-toolchain discovery)))))

(deftest missing-classpath-input-is-explicit
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source (create-file! root "src/A.java")
        missing (paths/resolve-path root "cache/missing.jar")
        manifest (write-manifest!
                  root
                  [["java-home" java-home]
                   ["java-release" "17"]
                   ["preview-features" "false"]
                   ["source" source]
                   ["classpath" missing]])
        error (try
                (project/read-discovery-manifest manifest)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :input-missing (:kind (ex-data error))))
    (is (= :classpath (:input-kind (ex-data error))))))

(deftest standalone-project-may-have-an-empty-classpath
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source (create-file! root "src/Main.java")
        discovery (project/read-discovery-manifest
                   (write-manifest!
                    root
                    [["project-path" ":"]
                     ["java-home" java-home]
                     ["java-release" "17"]
                     ["preview-features" "false"]
                     ["source" source]]))]
    (is (= ":" (:project-id discovery)))
    (is (= [] (:classpath-artifacts discovery)))
    (is (= [source] (project-input/production-source-files discovery)))))

(deftest v5-manifest-adapts-multiple-roots-and-generated-sources
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-root-a (doto (paths/resolve-path root "src/main/java")
                        (Files/createDirectories (make-array FileAttribute 0)))
        source-root-b (doto (paths/resolve-path root "build/generated/java")
                        (Files/createDirectories (make-array FileAttribute 0)))
        resource-root-a (doto (paths/resolve-path root "src/main/resources")
                          (Files/createDirectories (make-array FileAttribute 0)))
        resource-root-b (doto (paths/resolve-path root "build/generated/resources")
                          (Files/createDirectories (make-array FileAttribute 0)))
        source (create-file! source-root-a "example/Main.java")
        generated (create-file! source-root-b "example/Generated.java")
        resource-a (create-file! resource-root-a "example/main.txt")
        resource-b (create-file! resource-root-b "example/generated.txt")
        input
        (project/read-discovery-manifest
         (write-v5-manifest!
          root
          [["project-path" ":library"]
           ["java-home" java-home]
           ["java-release" "17"]
           ["preview-features" "false"]
           ["source-root" source-root-b]
           ["source-root" source-root-a]
           ["resource-root" resource-root-b]
           ["resource-root" resource-root-a]
           ["source" source]
           ["generated-source" generated]
           ["resource" resource-b]
           ["resource" resource-a]]))]
    (is (= [source-root-b source-root-a]
           (vec (sort-by str (:source-roots input)))))
    (is (= [resource-root-b resource-root-a]
           (vec (sort-by str (:resource-roots input)))))
    (is (= [source] (:production-sources input)))
    (is (= [generated] (:generated-production-sources input)))
    (is (= [resource-b resource-a]
           (vec (sort-by str (:production-resources input)))))))

(deftest v5-manifest-rejects-unknown-and-malformed-records-deterministically
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        base [["project-path" ":library"]
              ["java-home" java-home]
              ["java-release" "17"]
              ["preview-features" "false"]]
        errors
        (mapv
         (fn [records]
           (try
             (project/read-discovery-manifest
              (write-v5-manifest! root records))
             nil
             (catch clojure.lang.ExceptionInfo caught caught)))
         [(conj base ["product-name" "PdfCube"])
          (conj base ["project-dependency" "test" ":dependency"])
          (conj base ["external-artifact" "compile" "group:name:1"
                      "/missing/path.jar" "not-a-hash"])
          (conj base ["java-release" "17"])])]
    (is (every? #(= :invalid-discovery-manifest (:kind (ex-data %))) errors))
    (is (= [:product-name]
           (:unknown-record-kinds (ex-data (first errors)))))
    (is (= "test" (:scope (ex-data (second errors)))))
    (is (= "not-a-hash" (:sha256 (ex-data (nth errors 2)))))
    (is (= [[:java-release "17"]]
           (:duplicate-records (ex-data (nth errors 3)))))))

(deftest configured-checkout-verifier-accepts-an-arbitrary-path-and-revision
  (let [root (temp-directory)
        checkout (doto (paths/resolve-path root "vendor" "example")
                   (Files/createDirectories (make-array FileAttribute 0)))
        _ (create-file! checkout ".git")
        expected (apply str (repeat 40 "a"))
        seen (atom nil)
        result (project/verify-checkout!
                {:workspace-root root
                 :project-root "vendor/example"
                 :revision expected
                 :run-command! (fn [options]
                                 (reset! seen options)
                                 {:exit 0 :output (str expected "\n")})})]
    (is (= {:path checkout :revision expected} result))
    (is (= ["git" "rev-parse" "HEAD"] (:command @seen)))
    (is (= checkout (:directory @seen)))))

(deftest missing-configured-checkout-is-explicit
  (let [root (temp-directory)
        expected (apply str (repeat 40 "a"))
        error (try
                (project/verify-checkout!
                 {:workspace-root root
                  :project-root "research/pkl"
                  :revision expected})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :source-checkout-missing (:kind (ex-data error))))
    (is (str/ends-with? (:path (ex-data error)) "/research/pkl"))
    (is (re-find #"git submodule update --init research/pkl" (.getMessage error)))))

(deftest uninitialized-configured-checkout-is-explicit
  (let [root (temp-directory)
        checkout (paths/resolve-path root "research" "pdfbox")
        _ (Files/createDirectories checkout (make-array FileAttribute 0))
        expected (apply str (repeat 40 "a"))
        error (try
                (project/verify-checkout!
                 {:workspace-root root
                  :project-root "research/pdfbox"
                  :revision expected})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :source-checkout-uninitialized (:kind (ex-data error))))
    (is (= expected (:expected (ex-data error))))
    (is (re-find #"git submodule update --init research/pdfbox" (.getMessage error)))))

(deftest mismatched-pkl-checkout-is-explicit
  (let [root (temp-directory)
        checkout (paths/resolve-path root "research" "pkl")
        _ (create-file! checkout ".git")
        expected (apply str (repeat 40 "a"))
        actual (apply str (repeat 40 "b"))
        error (try
                (project/verify-checkout!
                 {:workspace-root root
                  :project-root "research/pkl"
                  :revision expected
                  :run-command! (fn [_] {:exit 0 :output (str actual "\n")})})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :source-revision-mismatch (:kind (ex-data error))))
    (is (= expected (:expected (ex-data error))))
    (is (= actual (:actual (ex-data error))))
    (is (re-find #"check out the configured revision" (.getMessage error)))))

(deftest dirty-rawhttp-checkout-is-explicit
  (let [root (temp-directory)
        checkout (paths/resolve-path root "research" "rawhttp")
        _ (create-file! checkout ".git")
        expected (apply str (repeat 40 "a"))
        error (try
                (project/verify-checkout!
                 {:workspace-root root
                  :project-root "research/rawhttp"
                  :revision expected
                  :require-clean? true
                  :run-command!
                  (fn [{:keys [command]}]
                    (case (second command)
                      "rev-parse" {:exit 0 :output (str expected "\n")}
                      "status" {:exit 0 :output " M rawhttp-core/build.gradle\n"}))})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :source-checkout-dirty (:kind (ex-data error))))
    (is (= "M rawhttp-core/build.gradle" (:status (ex-data error))))
    (is (re-find #"commit, stash, or discard" (.getMessage error)))))

(deftest gradle-toolchain-failure-is-explicit
  (let [root (temp-directory)
        pkl (paths/resolve-path root "research" "pkl")
        _ (create-file! pkl "gradlew")
        _ (create-file! root "vibeformer/gradle/discover-main.gradle")
        error (try
                (project/discover-main!
                 {:workspace-root root
                  :project-root "research/pkl"
                  :gradle-project ":pkl-parser"
                  :manifest (paths/resolve-path root "target/manifest.tsv")
                  :run-command! (fn [_]
                                  (throw (ex-info "toolchain unavailable"
                                                  {:kind :command-failed
                                                   :exit 1
                                                   :output "No matching Java installation"})))})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :gradle-discovery-failed (:kind (ex-data error))))
    (is (= 1 (:exit (ex-data error))))
    (is (= "No matching Java installation" (:output (ex-data error))))))

(deftest requested-gradle-project-controls-task-and-manifest-identity
  (let [root (temp-directory)
        pkl (paths/resolve-path root "research" "pkl")
        _ (create-file! pkl "gradlew")
        _ (create-file! root "vibeformer/gradle/discover-main.gradle")
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source (create-file! root "pkl-core/src/main/java/Value.java")
        classpath (create-file! root "cache/pkl-parser.jar")
        seen-command (atom nil)
        discovery (project/discover-main!
                   {:workspace-root root
                    :project-root "research/pkl"
                    :manifest (paths/resolve-path root "target/manifest.tsv")
                    :gradle-project ":pkl-core"
                    :run-command!
                    (fn [{:keys [command]}]
                      (reset! seen-command command)
                      (write-manifest!
                       root
                       [["project-path" ":pkl-core"]
                        ["java-home" java-home]
                        ["java-release" "17"]
                        ["preview-features" "false"]
                        ["source" source]
                        ["classpath" classpath]])
                      (Files/createDirectories (paths/resolve-path root "target")
                                               (make-array FileAttribute 0))
                      (Files/move (paths/resolve-path root "manifest.tsv")
                                  (paths/resolve-path root "target/manifest.tsv")
                                  (make-array java.nio.file.CopyOption 0))
                      {:exit 0 :output ""})})]
    (is (= ":pkl-core" (:project-id discovery)))
    (is (some #{":pkl-core:vibeformerDescribeMain"} @seen-command))
    (is (some #{"-Pvibeformer.project=:pkl-core"} @seen-command))))

(deftest gradle-wrapper-launcher-matches-host-platform
  (let [root (temp-directory)
        pkl (paths/resolve-path root "research" "pkl")
        _ (create-file! pkl "gradlew")
        _ (create-file! pkl "gradlew.bat")
        _ (create-file! root "vibeformer/gradle/discover-main.gradle")
        windows? (str/starts-with?
                  (str/lower-case (System/getProperty "os.name" "")) "windows")
        seen-command (atom nil)
        _ (project/discover-main!
           {:workspace-root root
            :project-root "research/pkl"
            :manifest (paths/resolve-path root "target/manifest.tsv")
            :gradle-project ":pkl-core"
            :run-command!
            (fn [{:keys [command]}]
              (reset! seen-command command)
              (write-manifest!
               root
               [["project-path" ":pkl-core"]
                ["java-home" (doto (paths/resolve-path root "jdk")
                               (Files/createDirectories (make-array FileAttribute 0)))]
                ["java-release" "17"]
                ["preview-features" "false"]
                ["source" (create-file! root "pkl-core/src/main/java/Value.java")]
                ["classpath" (create-file! root "cache/pkl-parser.jar")]])
              (Files/createDirectories (paths/resolve-path root "target")
                                       (make-array FileAttribute 0))
              (Files/move (paths/resolve-path root "manifest.tsv")
                          (paths/resolve-path root "target/manifest.tsv")
                          (make-array java.nio.file.CopyOption 0))
              {:exit 0 :output ""})})
        launcher (first @seen-command)]
    (if windows?
      (is (str/ends-with? launcher "gradlew.bat")
          "Windows must launch the batch wrapper, not the Unix script")
      (is (and (str/ends-with? launcher "gradlew")
               (not (str/ends-with? launcher ".bat")))
          "Non-Windows must launch the Unix wrapper script"))))

(deftest invalid-gradle-project-is-explicit
  (let [error (try
                (project/discover-main!
                 {:workspace-root (temp-directory)
                  :manifest "unused"
                  :gradle-project "pkl-core;other"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-gradle-project (:kind (ex-data error))))))

(deftest gradle-discovery-requires-an-explicit-source-root
  (let [error (try
                (project/discover-main!
                 {:workspace-root (temp-directory)
                  :manifest "unused"
                  :gradle-project ":library"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :missing-gradle-project-root (:kind (ex-data error))))))

(deftest configurable-non-pkl-gradle-project-discovers-complete-inputs
  (let [workspace (paths/workspace-root)
        root (temp-directory)
        wrapper (paths/resolve-path workspace "research" "pkl" "gradlew")
        _ (write-file! root "settings.gradle"
                       "rootProject.name = 'generic-fixture'\ninclude 'library', 'application'\n")
        _ (write-file! root "library/build.gradle"
                       (str "plugins { id 'java-library' }\n"
                            "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"))
        _ (write-file! root "library/src/main/java/example/Dependency.java"
                       (str "package example;\n"
                            "public final class Dependency { public static String value() { return \"ok\"; } }\n"))
        _ (write-file! root "application/build.gradle"
                       (str "plugins { id 'java' }\n"
                            "java { sourceCompatibility = JavaVersion.VERSION_17 }\n"
                            "def generated = layout.buildDirectory.dir('generated/sources/fixture/main')\n"
                            "tasks.register('generateFixtureJava') {\n"
                            "  outputs.dir(generated)\n"
                            "  doLast {\n"
                            "    def file = generated.get().file('example/Generated.java').asFile\n"
                            "    file.parentFile.mkdirs()\n"
                            "    file.text = 'package example; public final class Generated {}\\n'\n"
                            "  }\n"
                            "}\n"
                            "sourceSets.main.java.srcDir(generated)\n"
                            "tasks.named('compileJava') { dependsOn('generateFixtureJava') }\n"
                            "dependencies { implementation project(':library') }\n"))
        _ (write-file! root "application/src/main/java/example/Application.java"
                       (str "package example;\n"
                            "public final class Application { String value() { return Dependency.value(); } }\n"))
        _ (write-file! root "application/src/main/resources/example/message.txt" "resource\n")
        discovery (project/discover-main!
                   {:workspace-root workspace
                    :project-root root
                    :gradle-wrapper wrapper
                    :gradle-project ":application"
                    :manifest (paths/resolve-path root "build/vibeformer-inputs.tsv")})
        sources (mapv str (project-input/production-source-files discovery))
        resources (mapv str (:production-resources discovery))
        classpath (mapv str (project-input/compile-classpath discovery))]
    (is (= root (:project-root discovery)))
    (is (= ":application" (:project-id discovery)))
    (is (some #(str/ends-with? % "/Application.java") sources))
    (is (some #(str/ends-with? % "/Generated.java") sources))
    (is (some #(str/ends-with? (str %) "/Generated.java")
              (:generated-production-sources discovery)))
    (is (some #(str/ends-with? % "/example/message.txt") resources))
    (is (some #(str/ends-with? % "/library/build/classes/java/main") classpath))
    (is (= #{{:scope :compile :project-id ":library"}
             {:scope :runtime :project-id ":library"}}
           (set (:project-dependencies discovery))))
    (is (empty? (:external-dependencies discovery)))
    (is (empty? (filter :coordinate (:classpath-artifacts discovery))))))

(deftest complete-independent-profile-discovers-resources-and-pinned-package-dependency
  (let [workspace (paths/workspace-root)
        discovery
        (project/discover-main!
         {:workspace-root workspace
          :project-root "research/rawhttp"
          :gradle-wrapper "gradlew"
          :gradle-java-major 17
          :gradle-project ":rawhttp-core"
          :manifest (paths/resolve-path (temp-directory) "rawhttp-main.tsv")})]
    (is (= ":rawhttp-core" (:project-id discovery)))
    (is (= 62 (count (project-input/production-source-files discovery))))
    (is (= 1 (count (:production-resources discovery))))
    (is (str/ends-with? (str (first (:production-resources discovery)))
                        "/META-INF/services/rawhttp.core.body.encoding.HttpMessageDecoder"))
    (is (empty? (:project-dependencies discovery)))
    (is (= [{:scope :compile
             :coordinate "com.google.code.findbugs:jsr305:3.0.2"}]
           (:external-dependencies discovery)))
    (is (= #{{:scope :compile
              :coordinate "com.google.code.findbugs:jsr305:3.0.2"
              :sha256 "766ad2a0783f2687962c8ad74ceecc38a28b9f72a2d085ee438b7813e928d0c7"}}
           (set (map #(select-keys % [:scope :coordinate :sha256])
                     (filter :coordinate (:classpath-artifacts discovery))))))))

(deftest complete-pkl-core-main-discovery
  (let [workspace (paths/workspace-root)
        discovery (project/discover-main!
                   {:workspace-root workspace
                    :project-root "research/pkl"
                    :manifest (paths/resolve-path (temp-directory) "pkl-core-main.tsv")
                    :gradle-project ":pkl-core"})
        sources (mapv str (project-input/production-source-files discovery))
        resources (mapv str (:production-resources discovery))]
    (is (= ":pkl-core" (:project-id discovery)))
    (is (= 723 (count sources)))
    (is (= 140 (count (filter #(str/includes? % "/generated/truffle/") sources))))
    (is (some #(str/ends-with? % "/BaseModuleMembers.java") sources))
    (is (= 28 (count resources)))
    (is (some #(str/ends-with? % "/Release.properties") resources))
    (is (some #(str/ends-with? % "/stdlib/base.pkl") resources))
    (is (= 13 (count (project-input/compile-classpath discovery))))
    (is (every? paths/exists? (project-input/compile-classpath discovery)))))
