(ns vibeformer.harness
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.pkl.java-project :as java-project]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project]
            [vibeformer.public-api-contract :as public-api]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file FileVisitOption Files Path]))

(def ^:private profiles
  {"pkl-parser"
   {:schema-version 1
    :profile "pkl-parser"
    :project-root "research/pkl"
    :gradle-project ":pkl-parser"
    :public-api-contract {:source-module "pkl-parser"}
    :destination-config "vibeformer/config/pkl-parser.edn"}
   "pkl-core-value-model"
   {:configuration-file "vibeformer/config/pkl-core-value-model.edn"}})

(defn read-profile
  "Reads and validates an explicit generation profile. Profile configuration
  selects the real Gradle project, destination policy, and optional resolved
  closure seeds; it is not a source-file allowlist."
  [workspace-root profile-name]
  (let [root (paths/absolute workspace-root)
        configured-file (paths/resolve-path root profile-name)
        entry (or (get profiles profile-name)
                  (when (paths/regular-file? configured-file)
                    {:configuration-file profile-name}))]
    (when-not entry
      (throw (ex-info (str "Unknown Vibeformer generation profile " profile-name)
                      {:kind :unknown-generation-profile
                       :profile profile-name
                       :available (vec (sort (keys profiles)))})))
    (let [profile (if-let [file (:configuration-file entry)]
                    (let [path (paths/resolve-path root file)]
                      (when-not (paths/regular-file? path)
                        (throw (ex-info "Generation profile configuration is missing"
                                        {:kind :missing-generation-profile
                                         :profile profile-name :path (str path)})))
                      (edn/read-string (slurp (str path))))
                    entry)]
      (when-not (and (= 1 (:schema-version profile))
                     (or (not (contains? profiles profile-name))
                         (= profile-name (:profile profile)))
                     (string? (:profile profile))
                     (not (str/blank? (:profile profile)))
                     (re-matches #"^:(?:[A-Za-z0-9][A-Za-z0-9_-]*(?::[A-Za-z0-9][A-Za-z0-9_-]*)*)?$"
                                 (or (:gradle-project profile) ""))
                     (or (nil? (:project-root profile))
                         (and (string? (:project-root profile))
                              (not (str/blank? (:project-root profile)))))
                     (or (nil? (:gradle-wrapper profile))
                         (and (string? (:gradle-wrapper profile))
                              (not (str/blank? (:gradle-wrapper profile)))))
                     (or (nil? (:revision profile))
                         (and (string? (:revision profile))
                              (not (str/blank? (:revision profile)))))
                     (string? (:destination-config profile))
                     (or (nil? (:dependency-profiles profile))
                         (and (vector? (:dependency-profiles profile))
                              (every? string? (:dependency-profiles profile))))
                     (or (nil? (:public-api-contract profile))
                         (and (map? (:public-api-contract profile))
                              (= #{:source-module}
                                 (set (keys (:public-api-contract profile))))
                              (contains? #{"pkl-parser" "pkl-core"}
                                         (get-in profile [:public-api-contract
                                                          :source-module]))))
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
         source-project (if (map? revision)
                          revision
                          {:path (or (:project-root discovery) "research/pkl")
                           :revision revision})
         source-path (let [path (paths/path (:path source-project))]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))
         rendered-source {:path (portable-path root source-path)
                          :revision (:revision source-project)}
         render-many #(->> % (map (partial portable-path root)) sort vec)]
     (cond-> {:schema-version 1
              :project (keyword (or (not-empty (subs (or (:gradle-project discovery)
                                                         ":pkl-parser") 1))
                                    "root"))
              :source-project rendered-source
              :toolchain {:java-home (portable-path root (:java-home discovery))
                          :java-release (:java-release discovery)
                          :preview-features (:preview-features discovery)}
              :production {:java-sources (render-many (:java-sources discovery))
                           :resource-root (portable-path root (:resource-root discovery))
                           :resources (render-many (:resources discovery))
                           :classpath (render-many (:classpath discovery))}}
       (= "research/pkl" (:path rendered-source))
       (assoc :submodule {:path "research/pkl" :revision (:revision source-project)})
       destination (assoc :destination destination)))))

(defn- project-options
  [profile]
  (select-keys profile [:project-root :gradle-wrapper :gradle-project]))

(defn- manifest-name
  [prefix profile-name]
  (str prefix (str/replace profile-name #"[^A-Za-z0-9_.-]" "_") ".tsv"))

(def ^:private expansion-rank {:shell 0 :body 1 :public-api 2})

(defn merge-seeds
  "Merges explicit behavior seeds with contract-derived surface seeds. The
  strongest requested expansion wins for an exact live declaration identity."
  [& seed-groups]
  (->> seed-groups
       (apply concat)
       (reduce (fn [result {:keys [key expand] :as seed}]
                 (let [expand (or expand :body)
                       seed (cond-> {:key key :expand expand}
                              (contains? seed :members) (assoc :members (:members seed)))
                       current (get result key)
                       same-public? (and current (= :public-api expand)
                                         (= :public-api (:expand current)))
                       merged-members
                       (when same-public?
                         (when (and (contains? current :members)
                                    (contains? seed :members))
                           (into (:members current) (:members seed))))]
                   (when-not (and (string? key) (contains? expansion-rank expand)
                                  (or (not (contains? seed :members))
                                      (set? (:members seed))))
                     (throw (ex-info "Invalid generation seed"
                                     {:kind :invalid-generation-seed :seed seed})))
                   (cond
                     same-public?
                     (assoc result key
                            (cond-> {:key key :expand :public-api}
                              merged-members (assoc :members merged-members)))

                     (and current
                          (>= (expansion-rank (:expand current))
                              (expansion-rank expand)))
                     result

                     :else (assoc result key seed))))
               (sorted-map))
       vals
       vec))

(defn- source-project!
  [root profile verify-submodule-fn]
  (let [configured-root (or (:project-root profile) "research/pkl")
        project-root (let [path (paths/path configured-root)]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))]
    (if (= (paths/resolve-path root "research" "pkl") project-root)
      (verify-submodule-fn {:workspace-root root})
      {:path project-root :revision (:revision profile)})))

(defn- finish-emission!
  [surface emission destination validate-generated-surface-fn]
  (if-not surface
    emission
    (let [metadata (validate-generated-surface-fn surface emission)
          file (paths/resolve-path (:project-root emission)
                                   (get-in destination [:output :public-metadata-file]))]
      (spit (str file) (str (pr-str metadata) "\n"))
      (assoc emission :public-metadata-file file :public-metadata metadata))))

(defn- generate-with-executor!
  "Cleans disposable output, resolves pkl-parser, and emits disposable project inputs."
  [{:keys [workspace-root profile generate-dependencies? verify-submodule-fn discover-main-fn
           build-resolved-model-fn build-resolved-closure-fn
           read-profile-fn read-destination-fn emit-project-fn
           read-public-surface-fn validate-selected-surface-fn
           validate-generated-surface-fn]
    :or {verify-submodule-fn project/verify-submodule!
         discover-main-fn project/discover-main!
         build-resolved-model-fn spoon/build-resolved-model!
         build-resolved-closure-fn spoon/build-resolved-closure!
         read-profile-fn read-profile
         read-destination-fn java-project/read-configuration
         emit-project-fn java-project/emit-project!
         read-public-surface-fn public-api/generation-surface!
         validate-selected-surface-fn public-api/validate-selected-surface!
         validate-generated-surface-fn public-api/validate-generated-surface!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        profile-name (or profile "pkl-parser")
        generation-profile (read-profile-fn root profile-name)
        target (clean-directory! (paths/resolve-path root "vibeformer" "target"))
        source-project (source-project! root generation-profile verify-submodule-fn)
        manifest (paths/resolve-path target "gradle-main-inputs.tsv")
        discovery (discover-main-fn (merge {:workspace-root root :manifest manifest}
                                           (project-options generation-profile)))
        destination (read-destination-fn root (:destination-config generation-profile))
        config (assoc (configuration root source-project discovery destination)
                      :generation-profile generation-profile)
        surface (some->> (:public-api-contract generation-profile)
                         (read-public-surface-fn root))
        seeds (merge-seeds (:seeds generation-profile) (:seeds surface))
        java-model (if (seq seeds)
                     (build-resolved-closure-fn root discovery seeds)
                     (build-resolved-model-fn root discovery))
        surface (when surface
                  (validate-selected-surface-fn root surface java-model))
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
                   dependency-source-project
                   (source-project! root dependency-profile verify-submodule-fn)
                   dependency-manifest (paths/resolve-path
                                        target (manifest-name "gradle-main-inputs-"
                                                              dependency-name))
                   dependency-discovery
                   (discover-main-fn
                    (merge {:workspace-root root :manifest dependency-manifest}
                           (project-options dependency-profile)))
                   dependency-destination
                   (read-destination-fn root (:destination-config dependency-profile))
                   dependency-surface
                   (some->> (:public-api-contract dependency-profile)
                            (read-public-surface-fn root))
                   dependency-seeds
                   (merge-seeds (:seeds dependency-profile)
                                (:seeds dependency-surface))
                   dependency-model
                   (if (seq dependency-seeds)
                     (build-resolved-closure-fn root dependency-discovery dependency-seeds)
                     (build-resolved-model-fn root dependency-discovery))
                   dependency-surface
                   (when dependency-surface
                     (validate-selected-surface-fn root dependency-surface
                                                   dependency-model))
                   dependency-emission
                   (finish-emission!
                    dependency-surface
                    (emit-project-fn {:workspace-root root
                                      :target target
                                      :discovery dependency-discovery
                                      :resolved-model dependency-model
                                      :public-api-boundary dependency-surface
                                      :configuration dependency-destination})
                    dependency-destination validate-generated-surface-fn)]
               (assoc dependency-emission
                      :profile dependency-name
                      :source-project dependency-source-project
                      :public-api-boundary dependency-surface
                      :destination dependency-destination)))
           (:dependency-profiles generation-profile)))
        emission-public-api-boundary
        (when surface
          (update surface :selection-evidence into
                  (mapcat #(get-in % [:public-api-boundary :selection-evidence])
                          dependency-emissions)))
        emission (finish-emission!
                  surface
                  (emit-project-fn {:workspace-root root
                                    :target target
                                    :discovery discovery
                                    :resolved-model java-model
                                    :public-api-boundary emission-public-api-boundary
                                    :configuration destination})
                  destination validate-generated-surface-fn)
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
