(ns dripsharp.harness
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.baseline :as baseline]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.java-project :as java-project]
            [dripsharp.maven :as maven]
            [dripsharp.paths :as paths]
            [dripsharp.project :as project]
            [dripsharp.project-input :as project-input]
            [dripsharp.public-surface :as public-surface]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file FileVisitOption Files Path]))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-gradle-profile? [profile]
  (and (contains? #{nil :gradle} (:build-tool profile))
       (re-matches
        #"^:(?:[A-Za-z0-9][A-Za-z0-9_-]*(?::[A-Za-z0-9][A-Za-z0-9_-]*)*)?$"
        (:gradle-project profile))
       (or (nil? (:gradle-wrapper profile))
           (non-blank-string? (:gradle-wrapper profile)))
       (or (nil? (:gradle-java-major profile))
           (and (integer? (:gradle-java-major profile))
                (pos? (:gradle-java-major profile))))
       (not-any? #(contains? profile %)
                 [:maven-project-id :maven-selected-projects :maven-pom-file])))

(defn- valid-maven-profile? [profile]
  (and (= :maven (:build-tool profile))
       (non-blank-string? (:maven-project-id profile))
       (boolean
        (re-matches #"[^:\\s]+:[^:\\s]+:[^:\\s]+"
                    (:maven-project-id profile)))
       (vector? (:maven-selected-projects profile))
       (seq (:maven-selected-projects profile))
       (= (count (:maven-selected-projects profile))
          (count (distinct (:maven-selected-projects profile))))
       (every? #(and (non-blank-string? %)
                     (not (str/starts-with? % "-"))
                     (not (str/includes? % ",")))
               (:maven-selected-projects profile))
       (or (nil? (:maven-pom-file profile))
           (non-blank-string? (:maven-pom-file profile)))
       (not-any? #(contains? profile %)
                 [:gradle-project :gradle-wrapper :gradle-java-major])))

(defn- target-owned-profile
  [profile-name profile]
  (if-let [[_ target-root]
           (re-matches #"^(targets/[^/]+)/profiles/[^/]+\.edn$"
                       profile-name)]
    (update profile :destination-config
            #(if (str/starts-with? % "destinations/")
               (str target-root "/" %)
               %))
    profile))

(defn read-profile
  "Reads and validates an explicit generation profile. Profile configuration
  selects the real Gradle project, destination policy, and optional resolved
  closure seeds; it is not a source-file allowlist."
  [workspace-root profile-name]
  (let [root (paths/absolute workspace-root)
        configured-file (paths/resolve-path root profile-name)
        entry (when (paths/regular-file? configured-file)
                {:configuration-file profile-name})]
    (when-not entry
      (throw (ex-info (str "Unknown DripSharp generation profile " profile-name)
                      {:kind :unknown-generation-profile
                       :profile profile-name
                       :available []})))
    (let [profile
          (target-owned-profile
           profile-name
           (baseline/hydrate-profile
            root
            (if-let [file (:configuration-file entry)]
              (let [path (paths/resolve-path root file)]
                (when-not (paths/regular-file? path)
                  (throw
                   (ex-info "Generation profile configuration is missing"
                            {:kind :missing-generation-profile
                             :profile profile-name :path (str path)})))
                (edn/read-string (slurp (str path))))
              entry)))]
      (when-not (and (= 1 (:schema-version profile))
                     (string? (:profile profile))
                     (not (str/blank? (:profile profile)))
                     (keyword? (:product-family profile))
                     (string? (:project-root profile))
                     (not (str/blank? (:project-root profile)))
                     (let [selector (:destination-bundle profile)]
                       (and (symbol? selector) (namespace selector)))
                     (or (valid-gradle-profile? profile)
                         (valid-maven-profile? profile))
                     (or (nil? (:revision profile))
                         (and (string? (:revision profile))
                              (not (str/blank? (:revision profile)))))
                     (or (nil? (:require-clean-source profile))
                         (boolean? (:require-clean-source profile)))
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
        (throw (ex-info "Invalid DripSharp generation profile"
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

(def ^:private portable-path util/portable-or-absolute-path)

(defn configuration
  "Builds the deterministic, serializable configuration used by later stages."
  ([workspace-root revision input]
   (configuration workspace-root revision input nil))
  ([workspace-root revision input destination]
   (let [input (project-input/validate! input)
         root (paths/absolute workspace-root)
         source-project (if (map? revision)
                          revision
                          {:path (:project-root input)
                           :revision revision})
         _ (when-not (:path source-project)
             (throw (ex-info "Generation configuration has no source project path"
                             {:kind :missing-source-project-path
                              :project-input
                              (select-keys input [:project-id :project-root])})))
         source-path (let [path (paths/path (:path source-project))]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))
         rendered-source {:path (portable-path root source-path)
                          :revision (:revision source-project)}
         render-many #(->> % (map (partial portable-path root)) sort vec)
         render-artifact
         (fn [artifact]
           (update artifact :path (partial portable-path root)))
         toolchain (:java-toolchain input)]
     (cond-> {:schema-version 2
              :project-input
              {:schema-version 1
               :project-id (:project-id input)
               :source-roots (render-many (:source-roots input))
               :resource-roots (render-many (:resource-roots input))
               :production-sources (render-many (:production-sources input))
               :generated-production-sources
               (render-many (:generated-production-sources input))
               :production-resources (render-many (:production-resources input))
               :java-toolchain
               {:home (portable-path root (:home toolchain))
                :release (:release toolchain)
                :preview-features? (:preview-features? toolchain)}
               :project-dependencies (:project-dependencies input)
               :external-dependencies (:external-dependencies input)
               :classpath-artifacts
               (mapv render-artifact (:classpath-artifacts input))}
              :source-project rendered-source}
       (:submodule source-project)
       (assoc :submodule (:submodule source-project))
       destination (assoc :destination destination)))))

(defn- project-options
  [profile]
  (case (or (:build-tool profile) :gradle)
    :gradle
    (assoc (select-keys profile [:project-root :gradle-wrapper :gradle-project
                                 :gradle-java-major])
           :build-tool :gradle)

    :maven
    (cond-> {:build-tool :maven
             :project-root (:project-root profile)
             :maven-project-id (:maven-project-id profile)
             :selected-projects (:maven-selected-projects profile)}
      (:maven-pom-file profile)
      (assoc :pom-file (:maven-pom-file profile)))))

(defn discover-project!
  "Dispatches a validated generation profile to its build-tool backend and
  returns exactly one neutral Java project input."
  [{:keys [build-tool maven-project-id] :as options}]
  (case (or build-tool :gradle)
    :gradle
    (project/discover-main! (dissoc options :build-tool))

    :maven
    (-> (maven/discover-reactor!
         (dissoc options :build-tool :maven-project-id))
        (maven/project-by-id! maven-project-id))

    (throw (ex-info "Unsupported generation-profile build tool"
                    {:kind :unsupported-project-discovery-backend
                     :build-tool build-tool}))))

(defn- manifest-name
  [prefix profile-name]
  (str prefix (str/replace profile-name #"[^A-Za-z0-9_.-]" "_") ".tsv"))

(defn- maven-discovery-group-key
  [profile source-project]
  [(str (:path source-project))
   (or (:maven-pom-file profile) "pom.xml")
   (:revision source-project)])

(defn- discover-project-inputs!
  [{:keys [root target graph source-projects discover-reactor-fn]}]
  (let [entries
        (mapv
         (fn [profile-name]
           {:profile-name profile-name
            :profile (get-in graph [:prepared profile-name :profile])
            :source-project (get source-projects profile-name)})
         (:topological-order graph))
        maven-groups
        (->> entries
             (filter #(= :maven (get-in % [:profile :build-tool])))
             (group-by #(maven-discovery-group-key
                         (:profile %) (:source-project %)))
             (sort-by (comp pr-str key))
             vec)
        maven-results
        (mapv
         (fn [index [_ group]]
           (let [{:keys [profile source-project]} (first group)
                 profile-names (mapv :profile-name group)
                 project-ids (mapv #(get-in % [:profile :maven-project-id])
                                   group)
                 selected-projects
                 (->> group
                      (mapcat #(get-in % [:profile :maven-selected-projects]))
                      distinct
                      vec)
                 manifest
                 (paths/resolve-path
                  target (str "maven-reactor-inputs-" index ".tsv"))
                 reactor
                 (discover-reactor-fn
                  (cond-> {:workspace-root root
                           :project-root (:path source-project)
                           :selected-projects selected-projects
                           :manifest manifest}
                    (:maven-pom-file profile)
                    (assoc :pom-file (:maven-pom-file profile))))
                 inputs
                 (into {}
                       (map (fn [profile-name project-id]
                              [profile-name
                               (maven/project-by-id! reactor project-id)])
                            profile-names project-ids))]
             {:inputs inputs
              :invocation
              {:build-tool :maven
               :profiles profile-names
               :project-ids project-ids
               :selected-projects selected-projects
               :manifest (portable-path root manifest)}}))
         (range)
         maven-groups)
        results maven-results]
    {:inputs (apply merge (map :inputs results))
     :evidence
     {:schema-version 1
      :invocations (mapv :invocation results)}}))

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
  [root profile verify-checkout-fn]
  (let [configured-root (:project-root profile)
        project-root (let [path (paths/path configured-root)]
                       (paths/absolute
                        (if (.isAbsolute path) path (paths/resolve-path root path))))]
    (verify-checkout-fn {:workspace-root root
                         :project-root project-root
                         :revision (:revision profile)
                         :require-clean? (:require-clean-source profile)})))

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

(defn- dependency-cycle!
  [stack profile-name]
  (let [start (.indexOf ^java.util.List stack profile-name)
        cycle (conj (subvec stack start) profile-name)]
    (throw
     (ex-info
      (str "Generation profile dependency cycle: " (str/join " -> " cycle))
      {:kind :generation-profile-dependency-cycle
       :profile profile-name
       :cycle cycle}))))

(defn- resolve-profile-dag!
  [root profile-name include-dependencies? read-profile-fn read-destination-fn]
  (let [prepared-by-selector (atom {})
        prepared-by-profile (atom {})
        selector-by-profile (atom {})
        dependencies (atom {})
        states (atom {})
        preparation-order (atom [])
        topological-order (atom [])]
    (letfn [(load! [selector]
              (if-let [prepared (get @prepared-by-selector selector)]
                prepared
                (let [prepared
                      (prepare-profile! root selector read-profile-fn
                                        read-destination-fn)
                      canonical (get-in prepared [:profile :profile])]
                  (when-let [existing (get @prepared-by-profile canonical)]
                    (when-not (= existing prepared)
                      (throw
                       (ex-info
                        "Multiple generation-profile selectors resolve to conflicting projects"
                        {:kind :conflicting-generation-profile
                         :profile canonical
                         :first-selector (get @selector-by-profile canonical)
                         :second-selector selector}))))
                  (swap! prepared-by-selector assoc selector prepared)
                  (when-not (contains? @prepared-by-profile canonical)
                    (swap! prepared-by-profile assoc canonical prepared)
                    (swap! selector-by-profile assoc canonical selector)
                    (swap! preparation-order conj canonical))
                  (get @prepared-by-profile canonical))))
            (visit! [selector stack]
              (let [prepared (load! selector)
                    canonical (get-in prepared [:profile :profile])]
                (case (get @states canonical)
                  :done canonical
                  :visiting (dependency-cycle! stack canonical)
                  (do
                    (swap! states assoc canonical :visiting)
                    (let [stack (conj stack canonical)
                          dependency-names
                          (if include-dependencies?
                            (mapv
                             (fn [dependency-selector]
                               (let [dependency (load! dependency-selector)
                                     dependency-name
                                     (get-in dependency [:profile :profile])]
                                 (when (= :visiting (get @states dependency-name))
                                   (dependency-cycle! stack dependency-name))
                                 (visit! dependency-selector stack)))
                             (get-in prepared [:profile :dependency-profiles]))
                            [])]
                      (swap! dependencies assoc canonical dependency-names)
                      (swap! states assoc canonical :done)
                      (swap! topological-order conj canonical)
                      canonical)))))]
      (let [primary-profile (visit! profile-name [])
            order @topological-order
            level-by-profile
            (reduce
             (fn [levels profile]
               (assoc levels profile
                      (if-let [dependency-levels
                               (seq (map levels (get @dependencies profile)))]
                        (inc (apply max dependency-levels))
                        0)))
             {}
             order)
            levels
            (->> order
                 (group-by level-by-profile)
                 (sort-by key)
                 (mapv (comp vec val)))]
        {:primary-profile primary-profile
         :preparation-order @preparation-order
         :topological-order order
         :levels levels
         :dependencies @dependencies
         :prepared @prepared-by-profile}))))

(defn- destination-project-path
  [destination]
  (let [directory (get-in destination [:output :project-directory])
        file (get-in destination [:output :project-file])]
    (when (and (non-blank-string? directory)
               (non-blank-string? file))
      (paths/resolve-path directory file))))

(defn- resolved-project-reference
  [destination dependency-destination]
  (let [directory (get-in destination [:output :project-directory])
        dependency-project
        (destination-project-path dependency-destination)]
    (when (and (non-blank-string? directory) dependency-project)
      (-> (str (.relativize (paths/path directory) dependency-project))
          (str/replace "\\" "/")))))

(defn- validate-unique-destinations!
  [graph]
  (doseq [[field value-fn]
          [[:project-file #(some-> (destination-project-path (:destination %))
                                   str)]
           [:package-id #(when (destination-project-path (:destination %))
                           (get-in % [:destination :package :id]))]]
          [value projects]
          (sort-by
           (comp str key)
           (group-by value-fn
                     (filter (comp non-blank-string? value-fn)
                             (vals (:prepared graph)))))
          :when (< 1 (count projects))]
    (throw
     (ex-info "Generation profiles resolve to the same destination identity"
              {:kind :duplicate-generation-destination
               :field field
               :value value
               :profiles
               (->> projects
                    (map #(get-in % [:profile :profile]))
                    sort
                    vec)})))
  graph)

(defn- resolve-destination-graph!
  [graph]
  (let [graph (validate-unique-destinations! graph)
        prepared
        (reduce
         (fn [resolved profile-name]
           (let [entry (get resolved profile-name)
                 destination (:destination entry)
                 dependency-entries
                 (mapv resolved (get-in graph [:dependencies profile-name]))
                 project-references-resolved?
                 (and (destination-project-path destination)
                      (every? #(destination-project-path (:destination %))
                              dependency-entries))
                 package-dependencies-resolved?
                 (every? #(non-blank-string?
                           (get-in % [:destination :package :id]))
                         (cons entry dependency-entries))
                 expected-project-references
                 (when project-references-resolved?
                   (mapv #(resolved-project-reference destination (:destination %))
                         dependency-entries))
                 expected-package-dependencies
                 (when package-dependencies-resolved?
                   (mapv #(get-in % [:destination :package :id])
                         dependency-entries))]
             (doseq [[field resolved? expected]
                     [[:project-references project-references-resolved?
                       expected-project-references]
                      [:package-dependencies package-dependencies-resolved?
                       expected-package-dependencies]]
                     :when resolved?
                     :when (contains? destination field)
                     :let [actual (get destination field)]
                     :when (not= expected actual)]
               (throw
                (ex-info
                 "Destination references differ from the resolved generation-profile graph"
                 {:kind :destination-dependency-graph-mismatch
                  :profile profile-name
                  :field field
                  :expected expected
                  :actual actual
                  :dependency-profiles
                  (get-in graph [:dependencies profile-name])})))
             (assoc resolved profile-name
                    (assoc entry :destination
                           (cond-> destination
                             project-references-resolved?
                             (assoc :project-references
                                    expected-project-references)
                             package-dependencies-resolved?
                             (assoc :package-dependencies
                                    expected-package-dependencies))))))
         (:prepared graph)
         (:topological-order graph))]
    (assoc graph :prepared prepared)))

(defn- transitive-dependency-profiles
  [graph profile-name]
  (letfn [(closure [profile]
            (reduce
             (fn [result dependency]
               (into (conj result dependency) (closure dependency)))
             #{}
             (get-in graph [:dependencies profile])))]
    (let [dependencies (closure profile-name)]
      (filterv dependencies (:topological-order graph)))))

(defn- project-graph-data
  [graph]
  {:schema-version 1
   :primary-profile (:primary-profile graph)
   :topological-order (:topological-order graph)
   :projects
   (mapv
    (fn [profile-name]
      (let [destination
            (get-in graph [:prepared profile-name :destination])]
        {:profile profile-name
         :dependency-profiles (get-in graph [:dependencies profile-name])
         :project-reference-paths (:project-references destination)
         :package-dependencies (:package-dependencies destination)
         :project-file (some-> (destination-project-path destination) str)
         :package-id (get-in destination [:package :id])}))
    (:topological-order graph))})

(defn- finish-emission!
  [selection surface emission destination]
  (if-not surface
    emission
    (let [metadata (public-surface/validate-generated! selection surface emission)
          file (paths/resolve-path (:project-root emission)
                                   (get-in destination [:output :public-metadata-file]))]
      (spit (str file) (str (pr-str metadata) "\n"))
      (assoc emission :public-metadata-file file :public-metadata metadata))))

(defn- generate-prepared-profile!
  [{:keys [root target graph profile-name source-project project-input
           dependency-emissions discover-main-fn validate-project-input-fn
           build-resolved-model-fn build-resolved-closure-fn emit-project-fn
           primary?]}]
  (let [{generation-profile :profile
         destination :destination
         rule-bundle :rule-bundle
         selection :public-surface-strategy}
        (get-in graph [:prepared profile-name])
        input-model
        (validate-project-input-fn
         (or project-input
             (let [manifest
                   (paths/resolve-path
                    target
                    (if primary?
                      "gradle-main-inputs.tsv"
                      (manifest-name "gradle-main-inputs-" profile-name)))]
               (discover-main-fn
                (merge {:workspace-root root :manifest manifest}
                       (project-options generation-profile))))))
        _ (when-let [validate!
                     (get-in rule-bundle
                             [:orchestration :validate-project-input!])]
            (validate! {:workspace-root root
                        :profile generation-profile
                        :configuration destination
                        :project-input input-model}))
        config
        (assoc (configuration root source-project input-model destination)
               :generation-profile generation-profile)
        surface (public-surface/read! selection root)
        seeds (merge-seeds (:seeds generation-profile) (:seeds surface))
        java-model
        (if (seq seeds)
          (build-resolved-closure-fn root input-model seeds)
          (build-resolved-model-fn root input-model))
        surface
        (public-surface/validate-selected! selection root surface java-model)
        emission-public-api-boundary
        (public-surface/emission-boundary selection surface dependency-emissions)
        emission
        (finish-emission!
         selection surface
         (emit-project-fn {:workspace-root root
                           :target target
                           :project-input input-model
                           :resolved-model java-model
                           :public-api-boundary emission-public-api-boundary
                           :configuration destination
                           :rule-bundle rule-bundle})
         destination)]
    {:profile profile-name
     :dependency-profiles (get-in graph [:dependencies profile-name])
     :transitive-dependency-profiles
     (transitive-dependency-profiles graph profile-name)
     :source-project source-project
     :project-input input-model
     :configuration config
     :java-model java-model
     :public-api-boundary surface
     :public-surface-strategy selection
     :destination destination
     :emission emission}))

(defn- emission-record
  [{:keys [profile dependency-profiles transitive-dependency-profiles
           source-project project-input java-model public-api-boundary
           public-surface-strategy destination emission]}]
  (assoc emission
         :profile profile
         :dependency-profiles dependency-profiles
         :transitive-dependency-profiles transitive-dependency-profiles
         :source-project source-project
         :project-input project-input
         :model-totals (:totals java-model)
         :public-api-boundary public-api-boundary
         :public-surface-strategy public-surface-strategy
         :destination destination))

(defn- generate-with-executor!
  "Preflights an explicit product/destination plan, then cleans disposable
  output, obtains neutral project inputs from the selected discovery backend,
  and emits them."
  [{:keys [workspace-root profile generate-dependencies? verify-checkout-fn
           discover-main-fn discover-reactor-fn validate-project-input-fn
           build-resolved-model-fn build-resolved-closure-fn
           read-profile-fn read-destination-fn emit-project-fn]
    :or {verify-checkout-fn project/verify-checkout!
         discover-main-fn discover-project!
         discover-reactor-fn maven/discover-reactor!
         validate-project-input-fn project-input/validate!
         build-resolved-model-fn spoon/build-resolved-model!
         build-resolved-closure-fn spoon/build-resolved-closure!
         read-profile-fn read-profile
         read-destination-fn java-project/read-configuration
         emit-project-fn java-project/emit-project!}}]
  (let [root (paths/absolute (or workspace-root (paths/workspace-root)))
        profile-name
        (or profile
            (throw
             (ex-info "Generation requires an explicit profile selection"
                      {:kind :missing-generation-profile-selection})))
        include-dependencies? (not= false generate-dependencies?)
        graph
        (cond-> (resolve-profile-dag!
                 root profile-name include-dependencies?
                 read-profile-fn read-destination-fn)
          include-dependencies? resolve-destination-graph!)
        source-projects
        (into
         {}
         (map
          (fn [profile-name]
            [profile-name
             (source-project!
              root (get-in graph [:prepared profile-name :profile])
              verify-checkout-fn)]))
         (:preparation-order graph))
        target (clean-directory! (paths/resolve-path root "target"))
        discovery
        (discover-project-inputs!
         {:root root
          :target target
          :graph graph
          :source-projects source-projects
          :discover-reactor-fn discover-reactor-fn})
        project-inputs (:inputs discovery)
        generated (atom {})
        _ (doseq [level (:levels graph)]
            (let [results
                  (concurrency/mapv-ordered
                   :project-dag-generation
                   (fn [profile-name]
                     (let [dependency-profiles
                           (transitive-dependency-profiles graph profile-name)
                           dependency-emissions
                           (mapv
                            (comp emission-record @generated)
                            dependency-profiles)]
                       (generate-prepared-profile!
                        {:root root
                         :target target
                         :graph graph
                         :profile-name profile-name
                         :source-project (get source-projects profile-name)
                         :project-input (get project-inputs profile-name)
                         :dependency-emissions dependency-emissions
                         :discover-main-fn discover-main-fn
                         :validate-project-input-fn validate-project-input-fn
                         :build-resolved-model-fn build-resolved-model-fn
                         :build-resolved-closure-fn build-resolved-closure-fn
                         :emit-project-fn emit-project-fn
                         :primary? (= profile-name
                                      (:primary-profile graph))})))
                   level)]
              (swap! generated into (map (juxt :profile identity) results))))
        main (get @generated (:primary-profile graph))
        dependency-results
        (mapv @generated
              (remove #{(:primary-profile graph)}
                      (:topological-order graph)))
        dependency-emissions (mapv emission-record dependency-results)
        input-model (:project-input main)
        config
        (assoc (:configuration main)
               :project-graph (project-graph-data graph)
               :project-discovery (:evidence discovery))
        java-model (:java-model main)
        surface (:public-api-boundary main)
        emission (:emission main)
        config-file (paths/resolve-path target "generation-config.edn")
        source-count
        (count (project-input/production-source-files input-model))
        resources (get-in config [:project-input :production-resources])]
    (spit (str config-file) (str (pr-str config) "\n"))
    (println (format "Prepared %s: %d production Java files, %d production resource%s, %d classpath entries."
                     (:project-id input-model)
                     source-count
                     (count resources)
                     (if (= 1 (count resources)) "" "s")
                     (count (get-in config [:project-input
                                            :classpath-artifacts]))))
    (println "Production resources:" (if (seq resources) (str/join ", " resources) "none"))
    (println "Resolved Spoon model:" (spoon/summary-line java-model))
    (println "Emitted declaration project:" (portable-path root (:project-file emission)))
    (println "Declaration emission:" (pr-str (:summary emission)))
    (println "Disposable configuration:" (portable-path root config-file))
    (assoc config
           :java-model java-model
           :resolved-project-input input-model
           :public-api-boundary surface
           :public-surface-strategy (:public-surface-strategy main)
           :project-graph (:project-graph config)
           :project-discovery (:project-discovery config)
           :dependency-profiles (:dependency-profiles main)
           :dependency-emissions dependency-emissions
           :emission emission)))

(defn generate!
  "Cleans disposable output, resolves a profile, and emits it through the shared
  bounded executor. Set :worker-count, DRIPSHARP_WORKERS, or
  -Ddripsharp.workers; one worker provides the sequential performance and
  debugging baseline."
  ([] (generate! {}))
  ([options]
   (concurrency/call-with-executor
    {:worker-count (:worker-count options)}
    #(generate-with-executor! options))))
