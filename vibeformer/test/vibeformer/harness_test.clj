(ns vibeformer.harness-test
  (:require [clojure.test :refer [deftest is]]
            [vibeformer.harness :as harness]
            [vibeformer.paths :as paths]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file Files OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory
  []
  (Files/createTempDirectory "vibeformer-harness-test" (make-array FileAttribute 0)))

(defn- create-file!
  [^Path root relative]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file "test" (make-array OpenOption 0))
    file))

(defn- fixture-discovery
  [root]
  (let [java-home (doto (paths/resolve-path root "toolchain")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-a (create-file! root "research/pkl/pkl-parser/src/main/java/A.java")
        source-b (create-file! root "research/pkl/pkl-parser/src/main/java/B.java")
        resource (create-file! root "research/pkl/pkl-parser/src/main/resources/errorMessages.properties")
        classpath (create-file! root "cache/jspecify.jar")]
    {:java-home java-home
     :java-release 17
     :preview-features false
     :java-sources [source-b source-a]
     :resources [resource]
     :classpath [classpath]}))

(deftest generation-cleans-output-and-writes-configuration
  (let [root (temp-directory)
        stale (create-file! root "vibeformer/target/stale/output.cs")
        discovery (fixture-discovery root)
        saw-clean-target? (atom false)
        config (harness/generate!
                {:workspace-root root
                 :verify-submodule-fn (fn [_] {:revision "tracked-revision"})
                 :discover-main-fn
                 (fn [{:keys [manifest]}]
                   (reset! saw-clean-target?
                           (and (paths/directory? (.getParent ^Path manifest))
                                (not (paths/exists? stale))))
                   discovery)
                 :read-destination-fn
                 (fn [_]
                   {:schema-version 1
                    :fixture true})
                 :build-resolved-model-fn
                 (fn [_ _]
                   (spoon/map->ResolvedJavaModel
                    {:totals {:compilation-units 2
                              :project-types 0
                              :type-references 0
                              :executable-references 0
                              :constructor-references 0
                              :field-references 0
                              :annotations 0
                              :symbols 0
                              :shadow-symbols 0
                              :unresolved-symbols 0
                              :ambiguous-symbols 0
                              :fallback-symbols 0}}))
                 :emit-project-fn
                 (fn [{:keys [target]}]
                   {:project-file (paths/resolve-path target "fixture.csproj")
                    :summary {:compilation-units 2}})})]
    (is @saw-clean-target?)
    (is (paths/regular-file? (paths/resolve-path root "vibeformer/target/generation-config.edn")))
    (is (= 2 (count (get-in config [:production :java-sources]))))
    (is (= {:schema-version 1 :fixture true} (:destination config)))
    (is (= ["research/pkl/pkl-parser/src/main/java/A.java"
            "research/pkl/pkl-parser/src/main/java/B.java"]
           (get-in config [:production :java-sources])))))

(deftest configuration-is-deterministic
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        reversed (-> discovery
                     (update :java-sources #(vec (reverse %)))
                     (update :resources #(vec (reverse %)))
                     (update :classpath #(vec (reverse %))))]
    (is (= (harness/configuration root "revision" discovery)
           (harness/configuration root "revision" reversed)))))
