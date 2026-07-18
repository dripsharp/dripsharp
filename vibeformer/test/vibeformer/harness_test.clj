(ns vibeformer.harness-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
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

(defn- write-file!
  [^Path root relative content]
  (let [file (paths/resolve-path root relative)]
    (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
    (Files/writeString file content (make-array OpenOption 0))
    file))

(defn- fixture-discovery
  [root]
  (let [java-home (doto (paths/resolve-path root "toolchain")
                    (Files/createDirectories (make-array FileAttribute 0)))
        source-a (create-file! root "research/pkl/pkl-parser/src/main/java/A.java")
        source-b (create-file! root "research/pkl/pkl-parser/src/main/java/B.java")
        resource (create-file! root "research/pkl/pkl-parser/src/main/resources/errorMessages.properties")
        resource-root (.getParent ^Path resource)
        classpath (create-file! root "cache/jspecify.jar")]
    {:java-home java-home
     :java-release 17
     :preview-features false
     :java-sources [source-b source-a]
     :resource-root resource-root
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
                 (fn [{:keys [manifest gradle-project]}]
                   (reset! saw-clean-target?
                           (and (paths/directory? (.getParent ^Path manifest))
                                (not (paths/exists? stale))
                                (= ":pkl-parser" gradle-project)))
                   (assoc discovery :gradle-project gradle-project))
                 :read-destination-fn
                 (fn [_ config-file]
                   {:schema-version 1
                    :fixture true
                    :config-file config-file})
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
    (is (= {:schema-version 1 :fixture true
            :config-file "vibeformer/config/pkl-parser.edn"}
           (:destination config)))
    (is (= ["research/pkl/pkl-parser/src/main/java/A.java"
            "research/pkl/pkl-parser/src/main/java/B.java"]
           (get-in config [:production :java-sources])))))

(deftest explicit-core-profile-selects-live-closure-path
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        captured (atom nil)
        result (harness/generate!
                {:workspace-root root
                 :profile "pkl-core-value-model"
                 :generate-dependencies? false
                 :read-profile-fn (fn [_ profile-name]
                                    (harness/read-profile (paths/workspace-root)
                                                          profile-name))
                 :verify-submodule-fn (fn [_] {:revision "tracked-revision"})
                 :discover-main-fn (fn [options]
                                     (swap! captured assoc :discovery-options options)
                                     (assoc discovery :gradle-project (:gradle-project options)))
                 :read-destination-fn (fn [_ file]
                                        (swap! captured assoc :destination-file file)
                                        {:schema-version 1 :fixture true})
                 :build-resolved-closure-fn
                 (fn [_ _ seeds]
                   (swap! captured assoc :seeds seeds)
                   (spoon/map->ResolvedJavaClosure
                    {:totals {:declarations 1 :source-inputs 1
                              :type-references 0 :executable-references 0
                              :constructor-references 0 :field-references 0
                              :annotations 0 :symbols 0 :shadow-symbols 0
                              :unresolved-symbols 0 :ambiguous-symbols 0
                              :fallback-symbols 0}}))
                 :emit-project-fn
                 (fn [{:keys [resolved-model target]}]
                   (swap! captured assoc :resolved-model resolved-model)
                   {:project-file (paths/resolve-path target "core.csproj")
                    :summary {:compilation-units 1}})})]
    (is (= ":pkl-core" (get-in @captured [:discovery-options :gradle-project])))
    (is (= "vibeformer/config/pkl-core-value-model-destination.edn"
           (:destination-file @captured)))
    (is (= 83 (count (:seeds @captured))))
    (is (instance? vibeformer.spoon.ResolvedJavaClosure (:resolved-model @captured)))
    (is (= "pkl-core-value-model" (get-in result [:generation-profile :profile])))
    (let [written (edn/read-string
                   (slurp (str (paths/resolve-path root "vibeformer" "target"
                                                  "generation-config.edn"))))]
      (is (= (:generation-profile result) (:generation-profile written))))))

(deftest unknown-profile-fails-before-cleaning-output
  (let [root (temp-directory)
        stale (create-file! root "vibeformer/target/stale/output.cs")
        error (try
                (harness/generate! {:workspace-root root :profile "missing"})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= :unknown-generation-profile (:kind (ex-data error))))
    (is (paths/regular-file? stale))))

(deftest external-profile-configures-a-non-pkl-project
  (let [root (temp-directory)
        project-root (doto (paths/resolve-path root "examples/acme")
                       (Files/createDirectories (make-array FileAttribute 0)))
        _ (write-file!
           root "profiles/acme.edn"
           (str "{:schema-version 1\n"
                " :profile \"acme\"\n"
                " :project-root \"examples/acme\"\n"
                " :gradle-wrapper \"tools/gradlew\"\n"
                " :gradle-project \":library\"\n"
                " :destination-config \"config/acme.edn\"\n"
                " :dependency-profiles [\"profiles/dependency.edn\"]}\n"))
        discovery (fixture-discovery root)
        captured (atom nil)
        result (harness/generate!
                {:workspace-root root
                 :profile "profiles/acme.edn"
                 :generate-dependencies? false
                 :verify-submodule-fn
                 (fn [_] (throw (ex-info "Pkl verification must not run" {})))
                 :discover-main-fn
                 (fn [options]
                   (reset! captured options)
                   (assoc discovery
                          :project-root project-root
                          :gradle-project (:gradle-project options)))
                 :read-destination-fn (fn [_ file] {:schema-version 1 :file file})
                 :build-resolved-model-fn
                 (fn [_ _]
                   (spoon/map->ResolvedJavaModel
                    {:totals {:compilation-units 2 :project-types 0
                              :type-references 0 :executable-references 0
                              :constructor-references 0 :field-references 0
                              :annotations 0 :symbols 0 :shadow-symbols 0
                              :unresolved-symbols 0 :ambiguous-symbols 0
                              :fallback-symbols 0}}))
                 :emit-project-fn
                 (fn [{:keys [target]}]
                   {:project-file (paths/resolve-path target "acme.csproj")
                    :summary {:compilation-units 2}})})]
    (is (= project-root (paths/resolve-path root (:project-root @captured))))
    (is (= "tools/gradlew" (:gradle-wrapper @captured)))
    (is (= ":library" (:gradle-project @captured)))
    (is (= "acme" (get-in result [:generation-profile :profile])))
    (is (= {:path "examples/acme" :revision nil} (:source-project result)))
    (is (not (contains? result :submodule)))))

(deftest configuration-is-deterministic
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        reversed (-> discovery
                     (update :java-sources #(vec (reverse %)))
                     (update :resources #(vec (reverse %)))
                     (update :classpath #(vec (reverse %))))]
    (is (= (harness/configuration root "revision" discovery)
           (harness/configuration root "revision" reversed)))))

(deftest independent-dependency-profiles-run-concurrently-and-collate-in-order
  (let [root (temp-directory)
        discovery (fixture-discovery root)
        dependency-threads (atom #{})
        result
        (harness/generate!
         {:workspace-root root
          :worker-count 2
          :profile "main"
          :read-profile-fn
          (fn [_ profile-name]
            {:schema-version 1
             :profile profile-name
             :gradle-project (str ":" profile-name)
             :destination-config (str profile-name ".edn")
             :dependency-profiles (when (= "main" profile-name) ["dependency-b" "dependency-a"])})
          :verify-submodule-fn (fn [_] {:revision "tracked-revision"})
          :discover-main-fn
          (fn [{:keys [gradle-project]}]
            (when-not (= ":main" gradle-project)
              (swap! dependency-threads conj (.getName (Thread/currentThread)))
              (Thread/sleep 30))
            (assoc discovery :gradle-project gradle-project))
          :read-destination-fn (fn [_ file] {:schema-version 1 :file file})
          :build-resolved-model-fn
          (fn [_ _]
            (spoon/map->ResolvedJavaModel
             {:totals {:compilation-units 2 :project-types 0 :type-references 0
                       :executable-references 0 :constructor-references 0
                       :field-references 0 :annotations 0 :symbols 0
                       :shadow-symbols 0 :unresolved-symbols 0
                       :ambiguous-symbols 0 :fallback-symbols 0}}))
          :emit-project-fn
          (fn [{:keys [target configuration]}]
            {:project-file (paths/resolve-path target (str (:file configuration) ".csproj"))
             :summary {:compilation-units 2}})})]
    (is (= ["dependency-b" "dependency-a"]
           (mapv :profile (:dependency-emissions result))))
    (is (= ["dependency-b.edn" "dependency-a.edn"]
           (mapv #(get-in % [:destination :file]) (:dependency-emissions result))))
    (is (= 2 (count @dependency-threads)))))
