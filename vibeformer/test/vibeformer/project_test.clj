(ns vibeformer.project-test
  (:require [clojure.test :refer [deftest is testing]]
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
  (let [manifest (paths/resolve-path root "manifest.tsv")]
    (spit (str manifest)
          (str "VIBEFORMER_GRADLE_INPUTS_V2\n"
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
        classpath (create-file! root "cache/jspecify.jar")
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
    (is (= [classpath] (:classpath discovery)))
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
