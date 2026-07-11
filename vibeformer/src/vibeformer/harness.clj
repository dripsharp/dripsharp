(ns vibeformer.harness
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.java-project :as java-project]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file FileVisitOption Files Path]))

(def ^:private profiles
  {"pkl-parser"
   {:schema-version 1
    :profile "pkl-parser"
    :gradle-project ":pkl-parser"
    :destination-config "vibeformer/config/pkl-parser.edn"}
   "pkl-core-value-model"
   {:configuration-file "vibeformer/config/pkl-core-value-model.edn"}})

(defn read-profile
  "Reads and validates an explicit generation profile. Profile configuration
  selects the real Gradle project, destination policy, and optional resolved
  closure seeds; it is not a source-file allowlist."
  [workspace-root profile-name]
  (let [entry (get profiles profile-name)]
    (when-not entry
      (throw (ex-info (str "Unknown Vibeformer generation profile " profile-name)
                      {:kind :unknown-generation-profile
                       :profile profile-name
                       :available (vec (sort (keys profiles)))})))
    (let [profile (if-let [file (:configuration-file entry)]
                    (let [path (paths/resolve-path (paths/absolute workspace-root) file)]
                      (when-not (paths/regular-file? path)
                        (throw (ex-info "Generation profile configuration is missing"
                                        {:kind :missing-generation-profile
                                         :profile profile-name :path (str path)})))
                      (edn/read-string (slurp (str path))))
                    entry)]
      (when-not (and (= 1 (:schema-version profile))
                     (= profile-name (:profile profile))
                     (re-matches #":[A-Za-z0-9_.-]+" (or (:gradle-project profile) ""))
                     (string? (:destination-config profile))
                     (or (nil? (:dependency-profiles profile))
                         (and (vector? (:dependency-profiles profile))
                              (every? string? (:dependency-profiles profile))))
                     (or (nil? (:seeds profile)) (vector? (:seeds profile))))
        (throw (ex-info "Invalid Vibeformer generation profile"
                        {:kind :invalid-generation-profile
                         :profile profile-name :configuration profile})))
      profile)))

(defn clean-directory!
  "Deletes a directory tree, then recreates the empty directory."
  [directory]
  (let [directory (paths/absolute directory)]
    (when (paths/exists? directory)
      (with-open [entries (Files/walk directory (make-array FileVisitOption 0))]
        (doseq [^Path entry (->> (.toArray entries)
                                 (map #(cast Path %))
                                 (sort-by #(.getNameCount ^Path %) >))]
          (Files/delete entry))))
    (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
    directory))

(defn- portable-path
  [^Path root ^Path input]
  (let [input (.normalize input)]
    (-> (if (.startsWith input root)
          (str (.relativize root input))
          (str input))
        (str/replace "\\" "/"))))

(defn configuration
  "Builds the deterministic, serializable configuration used by later stages."
  ([workspace-root revision discovery]
   (configuration workspace-root revision discovery nil))
  ([workspace-root revision discovery destination]
   (let [root (paths/absolute workspace-root)
         render-many #(->> % (map (partial portable-path root)) sort vec)]
     (cond-> {:schema-version 1
              :project (keyword (subs (or (:gradle-project discovery) ":pkl-parser") 1))
              :submodule {:path "research/pkl" :revision revision}
              :toolchain {:java-home (portable-path root (:java-home discovery))
                          :java-release (:java-release discovery)
                          :preview-features (:preview-features discovery)}
              :production {:java-sources (render-many (:java-sources discovery))
                           :resource-root (portable-path root (:resource-root discovery))
                           :resources (render-many (:resources discovery))
                           :classpath (render-many (:classpath discovery))}}
       destination (assoc :destination destination)))))

(defn- generate-with-executor!
  "Cleans disposable output, resolves pkl-parser, and emits disposable project inputs."
  [{:keys [workspace-root profile generate-dependencies? verify-submodule-fn discover-main-fn
            build-resolved-model-fn build-resolved-closure-fn
            read-profile-fn read-destination-fn emit-project-fn]
     :or {verify-submodule-fn project/verify-submodule!
          discover-main-fn project/discover-main!
          build-resolved-model-fn spoon/build-resolved-model!
          build-resolved-closure-fn spoon/build-resolved-closure!
          read-profile-fn read-profile
          read-destination-fn java-project/read-configuration
          emit-project-fn java-project/emit-project!}}]
   (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
         profile-name (or profile "pkl-parser")
         generation-profile (read-profile-fn root profile-name)
         target (clean-directory! (paths/resolve-path root "vibeformer" "target"))
         submodule (verify-submodule-fn {:workspace-root root})
         manifest (paths/resolve-path target "gradle-main-inputs.tsv")
         discovery (discover-main-fn {:workspace-root root
                                      :manifest manifest
                                      :gradle-project (:gradle-project generation-profile)})
         destination (read-destination-fn root (:destination-config generation-profile))
         config (assoc (configuration root (:revision submodule) discovery destination)
                       :generation-profile generation-profile)
         java-model (if-let [seeds (:seeds generation-profile)]
                      (build-resolved-closure-fn root discovery seeds)
                      (build-resolved-model-fn root discovery))
         dependency-emissions
         (when (not= false generate-dependencies?)
           (when-not (= (count (:dependency-profiles generation-profile))
                        (count (distinct (:dependency-profiles generation-profile))))
             (throw (ex-info "Generation dependency profiles must be unique"
                             {:kind :duplicate-dependency-profile
                              :profiles (:dependency-profiles generation-profile)})))
           (concurrency/mapv-ordered
            :dependency-profile-generation
            (fn [dependency-name]
              (let [dependency-profile (read-profile-fn root dependency-name)
                    dependency-manifest (paths/resolve-path
                                         target (str "gradle-main-inputs-" dependency-name ".tsv"))
                    dependency-discovery
                    (discover-main-fn {:workspace-root root
                                       :manifest dependency-manifest
                                       :gradle-project (:gradle-project dependency-profile)})
                    dependency-destination
                    (read-destination-fn root (:destination-config dependency-profile))
                    dependency-model
                    (if-let [dependency-seeds (:seeds dependency-profile)]
                      (build-resolved-closure-fn root dependency-discovery dependency-seeds)
                      (build-resolved-model-fn root dependency-discovery))]
                (assoc (emit-project-fn {:workspace-root root
                                         :target target
                                         :discovery dependency-discovery
                                         :resolved-model dependency-model
                                         :configuration dependency-destination})
                       :profile dependency-name)))
            (:dependency-profiles generation-profile)))
         emission (emit-project-fn {:workspace-root root
                                    :target target
                                    :discovery discovery
                                    :resolved-model java-model
                                    :configuration destination})
         config-file (paths/resolve-path target "generation-config.edn")
         source-count (count (get-in config [:production :java-sources]))
         resources (get-in config [:production :resources])]
     (spit (str config-file) (str (pr-str config) "\n"))
     (println (format "Prepared %s: %d production Java files, %d production resource%s, %d classpath entries."
                      (or (:gradle-project discovery) ":pkl-parser")
                      source-count
                      (count resources)
                      (if (= 1 (count resources)) "" "s")
                      (count (get-in config [:production :classpath]))))
     (println "Production resources:" (if (seq resources) (str/join ", " resources) "none"))
     (println "Resolved Spoon model:" (spoon/summary-line java-model))
     (println "Emitted declaration project:" (portable-path root (:project-file emission)))
     (println "Declaration emission:" (pr-str (:summary emission)))
     (println "Disposable configuration:" (portable-path root config-file))
     (assoc config
            :java-model java-model
            :dependency-emissions dependency-emissions
            :emission emission)))

(defn generate!
  "Cleans disposable output, resolves a profile, and emits it through the shared
  bounded executor. Set :worker-count, VIBEFORMER_WORKERS, or
  -Dvibeformer.workers; one worker provides the deterministic debug mode."
  ([] (generate! {}))
  ([options]
   (concurrency/call-with-executor
    {:worker-count (:worker-count options)}
    #(generate-with-executor! options))))
