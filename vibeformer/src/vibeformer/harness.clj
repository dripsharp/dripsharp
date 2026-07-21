(ns vibeformer.harness
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [vibeformer.concurrency :as concurrency]
            [vibeformer.java-project :as java-project]
            [vibeformer.paths :as paths]
            [vibeformer.project :as project]
            [vibeformer.public-surface :as public-surface]
            [vibeformer.spoon :as spoon])
  (:import [java.nio.file FileVisitOption Files Path]))

(def ^:private profiles
  {"pkl-parser"
   {:schema-version 1
    :profile "pkl-parser"
    :product-family :pkl
    :project-root "research/pkl"
    :source-verifier 'vibeformer.project/verify-submodule!
    :gradle-project ":pkl-parser"
    :destination-bundle 'vibeformer.pkl.java-project/rule-bundle
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
                     (keyword? (:product-family profile))
                     (string? (:project-root profile))
                     (not (str/blank? (:project-root profile)))
                     (let [selector (:destination-bundle profile)]
                       (and (symbol? selector) (namespace selector)))
                     (re-matches #"^:(?:[A-Za-z0-9][A-Za-z0-9_-]*(?::[A-Za-z0-9][A-Za-z0-9_-]*)*)?$"
                                 (:gradle-project profile))
                     (or (nil? (:gradle-wrapper profile))
                         (and (string? (:gradle-wrapper profile))
                              (not (str/blank? (:gradle-wrapper profile)))))
                     (or (nil? (:gradle-java-major profile))
                         (and (integer? (:gradle-java-major profile))
                              (pos? (:gradle-java-major profile))))
                     (or (nil? (:revision profile))
                         (and (string? (:revision profile))
                              (not (str/blank? (:revision profile)))))
                     (or (nil? (:require-clean-source profile))
                         (boolean? (:require-clean-source profile)))
                     (or (nil? (:source-verifier profile))
                         (let [selector (:source-verifier profile)]
                           (and (symbol? selector) (namespace selector))))
                     (string? (:destination-config profile))
                     (let [dependencies (:dependency-profiles profile)]
                       (or (nil? dependencies)
                           (and (vector? dependencies)
                                (every? #(and (string? %)
                                              (not (str/blank? %)))
                                        dependencies)
                                (= (count dependencies)
                                   (count (distinct dependencies)))
                                (not (some #{(:profile profile)}
                                           dependencies)))))
                     (or (nil? (:identity-guard profile))
                         (and (map? (:identity-guard profile))
                              (= #{:forbidden-fragments}
                                 (set (keys (:identity-guard profile))))
                              (vector? (get-in profile [:identity-guard
                                                        :forbidden-fragments]))
                              (every? #(and (string? %) (not (str/blank? %)))
                                      (get-in profile [:identity-guard
                                                       :forbidden-fragments]))))
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
                          {:path (:project-root discovery)
                           :revision revision})
         _ (when-not (:path source-project)
             (throw (ex-info "Generation configuration has no source project path"
                             {:kind :missing-source-project-path
                              :discovery (select-keys discovery
                                                      [:gradle-project
                                                       :project-root])})))
         source-path (let [path (paths/path (:path source-project))]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))
         rendered-source {:path (portable-path root source-path)
                          :revision (:revision source-project)}
         render-many #(->> % (map (partial portable-path root)) sort vec)]
     (cond-> {:schema-version 1
              :project (keyword (or (not-empty (subs (:gradle-project discovery) 1))
                                    "root"))
              :source-project rendered-source
              :toolchain {:java-home (portable-path root (:java-home discovery))
                          :java-release (:java-release discovery)
                          :preview-features (:preview-features discovery)}
              :production {:java-sources (render-many (:java-sources discovery))
                           :resource-root (portable-path root (:resource-root discovery))
                           :resources (render-many (:resources discovery))
                           :classpath (render-many (:classpath discovery))}}
       (:submodule source-project)
       (assoc :submodule (:submodule source-project))
       destination (assoc :destination destination)))))

(defn- project-options
  [profile]
  (select-keys profile [:project-root :gradle-wrapper :gradle-project
                        :gradle-java-major]))

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
  (let [configured-root (:project-root profile)
        project-root (let [path (paths/path configured-root)]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))]
    (if-let [selector (:source-verifier profile)]
      (let [verifier (if (= selector 'vibeformer.project/verify-submodule!)
                       verify-submodule-fn
                       (try
                         (requiring-resolve selector)
                         (catch Throwable error
                           (throw (ex-info "Source verifier selection failed"
                                           {:kind :unsupported-source-verifier
                                            :source-verifier selector}
                                           error)))))]
        (when-not (ifn? verifier)
          (throw (ex-info "Source verifier selector is not callable"
                          {:kind :invalid-source-verifier
                           :source-verifier selector})))
        (verifier {:workspace-root root :project-root project-root
                   :profile profile}))
      (project/verify-checkout! {:workspace-root root
                                 :project-root project-root
                                 :revision (:revision profile)
                                 :require-clean? (:require-clean-source profile)}))))

(defn- identity-values [profile destination]
  {:source [(:project-root profile)]
   :output (vals (:output destination))
   :package [(get-in destination [:package :id])]
   :namespace (concat [(get-in destination [:project :assembly-name])
                       (get-in destination [:project :root-namespace])]
                      (vals (:namespaces destination))
                      (vals (:namespace-prefixes destination)))})

(defn- validate-identity-guard! [profile destination]
  (doseq [fragment (get-in profile [:identity-guard :forbidden-fragments])
          [area values] (identity-values profile destination)
          value values
          :when (str/includes? (str/lower-case (str value))
                               (str/lower-case fragment))]
    (throw (ex-info "Profile or destination leaks a forbidden product identity"
                    {:kind :forbidden-product-identity
                     :profile (:profile profile) :area area
                     :fragment fragment :value value}))))

(defn- prepare-profile!
  [root profile-name read-profile-fn read-destination-fn]
  (let [profile (read-profile-fn root profile-name)
        destination (read-destination-fn root (:destination-config profile))
        profile-selector (:destination-bundle profile)
        destination-selector (:destination-bundle destination)]
    (when-not (= profile-selector destination-selector)
      (throw (ex-info "Profile and destination select different rule bundles"
                      {:kind :ambiguous-destination-rule-bundle
                       :profile (:profile profile)
                       :profile-selection profile-selector
                       :destination-selection destination-selector})))
    (when-not (= (:product-family profile) (:product-family destination))
      (throw (ex-info "Profile and destination product families differ"
                      {:kind :product-incompatible-destination
                       :profile (:profile profile)
                       :profile-product-family (:product-family profile)
                       :destination-product-family (:product-family destination)})))
    (let [bundle (java-project/resolve-rule-bundle!
                  {:configuration destination :resolved-model nil})]
      (when-not (= (:product-family profile) (:product-family bundle))
        (throw (ex-info "Selected destination rule bundle is product-incompatible"
                        {:kind :product-incompatible-destination-rule-bundle
                         :profile (:profile profile)
                         :profile-product-family (:product-family profile)
                         :bundle (:id bundle)
                         :bundle-product-family (:product-family bundle)})))
      (when (and (seq (:runtime-sources destination))
                 (nil? (get-in bundle [:rules :product-runtime-assets :assets])))
        (throw (ex-info "Destination requests product runtime assets without that capability"
                        {:kind :unsupported-product-runtime-assets
                         :profile (:profile profile) :bundle (:id bundle)
                         :runtime-sources (:runtime-sources destination)})))
      (validate-identity-guard! profile destination)
      (when-let [validate! (get-in bundle [:orchestration :validate-profile!])]
        (validate! {:workspace-root root :profile profile
                    :configuration destination}))
      {:profile profile
       :destination destination
       :rule-bundle bundle
       :public-surface-strategy
       (public-surface/resolve-strategy! (:product-family profile)
                                         (:public-surface destination))})))

(defn- finish-emission!
  [selection surface emission destination]
  (if-not surface
    emission
    (let [metadata (public-surface/validate-generated! selection surface emission)
          file (paths/resolve-path (:project-root emission)
                                   (get-in destination [:output :public-metadata-file]))]
      (spit (str file) (str (pr-str metadata) "\n"))
      (assoc emission :public-metadata-file file :public-metadata metadata))))

(defn- generate-with-executor!
  "Preflights an explicit product/destination plan, then cleans disposable
  output, discovers the selected Gradle projects, and emits them."
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
        prepared (prepare-profile! root profile-name read-profile-fn read-destination-fn)
        generation-profile (:profile prepared)
        dependency-prepared
        (if (= false generate-dependencies?)
          []
          (mapv #(prepare-profile! root % read-profile-fn read-destination-fn)
                (:dependency-profiles generation-profile)))
        target (clean-directory! (paths/resolve-path root "vibeformer" "target"))
        source-project (source-project! root generation-profile verify-submodule-fn)
        manifest (paths/resolve-path target "gradle-main-inputs.tsv")
        discovery (discover-main-fn (merge {:workspace-root root :manifest manifest}
                                           (project-options generation-profile)))
        destination (:destination prepared)
        _ (when-let [validate! (get-in prepared
                                       [:rule-bundle :orchestration
                                        :validate-discovery!])]
            (validate! {:workspace-root root :profile generation-profile
                        :configuration destination :discovery discovery}))
        config (assoc (configuration root source-project discovery destination)
                      :generation-profile generation-profile)
        surface (public-surface/read! (:public-surface-strategy prepared) root)
        seeds (merge-seeds (:seeds generation-profile) (:seeds surface))
        java-model (if (seq seeds)
                     (build-resolved-closure-fn root discovery seeds)
                     (build-resolved-model-fn root discovery))
        surface (public-surface/validate-selected!
                 (:public-surface-strategy prepared) root surface java-model)
        dependency-emissions
        (concurrency/mapv-ordered
         :dependency-profile-generation
         (fn [{dependency-profile :profile
               dependency-destination :destination
               dependency-bundle :rule-bundle
               dependency-selection :public-surface-strategy}]
           (let [dependency-name (:profile dependency-profile)
                 dependency-source-project
                 (source-project! root dependency-profile verify-submodule-fn)
                 dependency-manifest
                 (paths/resolve-path target
                                     (manifest-name "gradle-main-inputs-"
                                                    dependency-name))
                 dependency-discovery
                 (discover-main-fn
                  (merge {:workspace-root root :manifest dependency-manifest}
                         (project-options dependency-profile)))
                 _ (when-let [validate! (get-in dependency-bundle
                                                [:orchestration
                                                 :validate-discovery!])]
                     (validate! {:workspace-root root :profile dependency-profile
                                 :configuration dependency-destination
                                 :discovery dependency-discovery}))
                 dependency-surface (public-surface/read! dependency-selection root)
                 dependency-seeds
                 (merge-seeds (:seeds dependency-profile)
                              (:seeds dependency-surface))
                 dependency-model
                 (if (seq dependency-seeds)
                   (build-resolved-closure-fn root dependency-discovery dependency-seeds)
                   (build-resolved-model-fn root dependency-discovery))
                 dependency-surface
                 (public-surface/validate-selected!
                  dependency-selection root dependency-surface dependency-model)
                 dependency-emission
                 (finish-emission!
                  dependency-selection dependency-surface
                  (emit-project-fn {:workspace-root root
                                    :target target
                                    :discovery dependency-discovery
                                    :resolved-model dependency-model
                                    :public-api-boundary dependency-surface
                                    :configuration dependency-destination
                                    :rule-bundle dependency-bundle})
                  dependency-destination)]
             (assoc dependency-emission
                    :profile dependency-name
                    :source-project dependency-source-project
                    :public-api-boundary dependency-surface
                    :public-surface-strategy dependency-selection
                    :destination dependency-destination)))
         dependency-prepared)
        emission-public-api-boundary
        (public-surface/emission-boundary
         (:public-surface-strategy prepared) surface dependency-emissions)
        emission (finish-emission!
                  (:public-surface-strategy prepared) surface
                  (emit-project-fn {:workspace-root root
                                    :target target
                                    :discovery discovery
                                    :resolved-model java-model
                                    :public-api-boundary emission-public-api-boundary
                                    :configuration destination
                                    :rule-bundle (:rule-bundle prepared)})
                  destination)
        config-file (paths/resolve-path target "generation-config.edn")
        source-count (count (get-in config [:production :java-sources]))
        resources (get-in config [:production :resources])]
    (spit (str config-file) (str (pr-str config) "\n"))
    (println (format "Prepared %s: %d production Java files, %d production resource%s, %d classpath entries."
                     (:gradle-project discovery)
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
           :public-api-boundary surface
           :public-surface-strategy (:public-surface-strategy prepared)
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
