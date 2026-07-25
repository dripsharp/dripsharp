(ns dripsharp.project-input-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "dripsharp-project-input-test"
                             (make-array FileAttribute 0)))

(defn- directory! [^Path root relative]
  (doto (paths/resolve-path root relative)
    (Files/createDirectories (make-array FileAttribute 0))))

(defn- file! [^Path root relative]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file)
                             (make-array FileAttribute 0))
    (Files/writeString file "fixture" (make-array OpenOption 0))
    file))

(defn- input
  [root]
  (let [java-home (directory! root "jdk")
        source-a-root (directory! root "src/main/java")
        source-b-root (directory! root "build/generated/java")
        resource-a-root (directory! root "src/main/resources")
        resource-b-root (directory! root "build/generated/resources")
        source-a (file! source-a-root "example/A.java")
        source-b (file! source-b-root "example/Generated.java")
        resource-a (file! resource-a-root "example/a.properties")
        resource-b (file! resource-b-root "example/b.properties")
        classes (directory! root "dependency/classes")]
    {:schema-version 1
     :project-id "example:library"
     :project-root root
     :source-roots [source-b-root source-a-root]
     :resource-roots [resource-b-root resource-a-root]
     :production-sources [source-a]
     :generated-production-sources [source-b]
     :production-resources [resource-b resource-a]
     :java-toolchain {:home java-home :release 17 :preview-features? false}
     :project-dependencies [{:scope :runtime :project-id "example:dependency"}
                            {:scope :compile :project-id "example:dependency"}]
     :external-dependencies []
     :classpath-artifacts [{:scope :compile :path classes}]}))

(deftest neutral-model-supports-multiple-roots-and-generated-sources
  (let [root (temp-directory)
        validated (project-input/validate! (input root))]
    (is (= 2 (count (:source-roots validated))))
    (is (= 2 (count (:resource-roots validated))))
    (is (= 1 (count (:production-sources validated))))
    (is (= 1 (count (:generated-production-sources validated))))
    (is (= 2 (count (project-input/production-source-files validated))))
    (is (= (vec (sort-by str (:source-roots validated)))
           (:source-roots validated)))
    (is (= [{:scope :compile :project-id "example:dependency"}
            {:scope :runtime :project-id "example:dependency"}]
           (:project-dependencies validated)))
    (is (empty? (filter #(re-find #"(?i)gradle|maven|pkl|pdf"
                                  (name %))
                        (keys validated))))))

(deftest neutral-model-supports-empty-roots-and-inputs
  (let [root (temp-directory)
        java-home (directory! root "jdk")
        validated
        (project-input/validate!
         {:schema-version 1
          :project-id "empty"
          :source-roots []
          :resource-roots []
          :production-sources []
          :generated-production-sources []
          :production-resources []
          :java-toolchain {:home java-home :release 8
                           :preview-features? false}
          :project-dependencies []
          :external-dependencies []
          :classpath-artifacts []})]
    (is (= [] (project-input/production-source-files validated)))
    (is (= [] (project-input/compile-classpath validated)))))

(deftest neutral-model-is-fail-closed
  (let [root (temp-directory)
        valid (input root)]
    (testing "unknown model fields"
      (let [error (try
                    (project-input/validate! (assoc valid :gradle-project ":library"))
                    nil
                    (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-project-input (:kind (ex-data error))))
        (is (= [:gradle-project] (:unknown-fields (ex-data error))))))
    (testing "overlapping ordinary and generated sources"
      (let [source (first (:production-sources valid))
            error (try
                    (project-input/validate!
                     (assoc valid :generated-production-sources [source]))
                    nil
                    (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-project-input (:kind (ex-data error))))
        (is (= :generated-production-sources (:field (ex-data error))))))
    (testing "unsupported scopes"
      (let [error (try
                    (project-input/validate!
                     (assoc valid :external-dependencies
                            [{:scope :test :coordinate "example:test:1"}]))
                    nil
                    (catch clojure.lang.ExceptionInfo caught caught))]
        (is (= :invalid-project-input (:kind (ex-data error))))
        (is (= :test (:scope (ex-data error))))))))
