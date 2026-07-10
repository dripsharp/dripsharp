(ns vibeformer.project-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project])
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

(deftest source-resource-and-classpath-discovery
  (let [root (temp-directory)
        java-home (doto (paths/resolve-path root "jdk")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-b (create-file! root "src/B.java")
        source-a (create-file! root "src/A.java")
        resource (create-file! root "resources/errorMessages.properties")
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
    (is (= [source-a source-b] (:java-sources discovery)))
    (is (= [resource] (:resources discovery)))
    (is (= (paths/resolve-path root "resources-output") (:resource-root discovery)))
    (is (= [classpath] (:classpath discovery)))
    (is (= ":pkl-parser" (:gradle-project discovery)))
    (is (= java-home (:java-home discovery)))
    (is (= 17 (:java-release discovery)))
    (is (false? (:preview-features discovery)))))

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

(deftest mismatched-submodule-is-explicit
  (let [root (temp-directory)
        submodule (paths/resolve-path root "research" "pkl")
        _ (create-file! submodule ".git")
        _ (create-file! submodule "settings.gradle.kts")
        expected (apply str (repeat 40 "a"))
        actual (apply str (repeat 40 "b"))
        runner (fn [{:keys [command]}]
                 (if (= "ls-tree" (second command))
                   {:exit 0 :output (str "160000 commit " expected "\tresearch/pkl\n")}
                   {:exit 0 :output (str actual "\n")}))
        error (try
                (project/verify-submodule! {:workspace-root root :run-command! runner})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :submodule-revision-mismatch (:kind (ex-data error))))
    (is (= expected (:expected (ex-data error))))
    (is (= actual (:actual (ex-data error))))))

(deftest missing-submodule-is-explicit
  (let [root (temp-directory)
        error (try
                (project/verify-submodule! {:workspace-root root})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :submodule-missing (:kind (ex-data error))))
    (is (re-find #"git submodule update --init" (.getMessage error)))))

(deftest gradle-toolchain-failure-is-explicit
  (let [root (temp-directory)
        pkl (paths/resolve-path root "research" "pkl")
        _ (create-file! pkl "gradlew")
        _ (create-file! root "vibeformer/gradle/discover-main.gradle")
        error (try
                (project/discover-main!
                 {:workspace-root root
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
    (is (= ":pkl-core" (:gradle-project discovery)))
    (is (some #{":pkl-core:vibeformerDescribeMain"} @seen-command))
    (is (some #{"-Pvibeformer.project=:pkl-core"} @seen-command))))

(deftest invalid-gradle-project-is-explicit
  (let [error (try
                (project/discover-main!
                 {:workspace-root (temp-directory)
                  :manifest "unused"
                  :gradle-project "pkl-core;other"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :invalid-gradle-project (:kind (ex-data error))))))

(deftest complete-pkl-core-main-discovery
  (let [workspace (paths/workspace-root)
        discovery (project/discover-main!
                   {:workspace-root workspace
                    :manifest (paths/resolve-path (temp-directory) "pkl-core-main.tsv")
                    :gradle-project ":pkl-core"})
        sources (mapv str (:java-sources discovery))
        resources (mapv str (:resources discovery))]
    (is (= ":pkl-core" (:gradle-project discovery)))
    (is (= 723 (count sources)))
    (is (= 140 (count (filter #(str/includes? % "/generated/truffle/") sources))))
    (is (some #(str/ends-with? % "/BaseModuleMembers.java") sources))
    (is (= 28 (count resources)))
    (is (some #(str/ends-with? % "/Release.properties") resources))
    (is (some #(str/ends-with? % "/stdlib/base.pkl") resources))
    (is (= 13 (count (:classpath discovery))))
    (is (every? paths/exists? (:classpath discovery)))))
