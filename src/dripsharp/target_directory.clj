(ns dripsharp.target-directory
  "Fail-closed loading of target-owned translation contracts.

  A target directory is selected by its stable target id. It owns operational
  inputs while referencing, but never redefining, the authoritative product
  documents under doc/targets."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.baseline :as baseline]
            [dripsharp.differential :as differential]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.paths :as paths])
  (:import [java.nio.file Files LinkOption]))

(def schema-version 3)
(def legal-policy-schema-version 1)
(def mapping-overlay-schema-version 1)

(def ^:private manifest-keys
  #{:schema-version :target :product-family :contracts :baseline :legal-policy
    :java :capabilities :profiles :destinations :mapping-overlays
    :runtime-assets :validation-contracts :authorship :proof})

(def ^:private document-keys
  #{:product-goal :port-scope :dependencies})

(def ^:private java-keys
  #{:source-language-version :runtime-major :preview-features?})

(def ^:private profile-descriptor-keys
  #{:id :path :destination :mapping-overlays :runtime-assets
    :validation-contracts :required-capabilities :authorship})

(def ^:private authorship-path-keys
  #{:compatibility :destination})

(def ^:private profile-authorship-keys
  #{:sources :evidence :review :budget})

(def ^:private authorship-budget-keys
  #{:authored-lines :total-lines})

(def ^:private path-descriptor-keys
  #{:id :path})

(def ^:private validation-descriptor-keys
  #{:id :kind :path})

(def ^:private runtime-descriptor-keys
  #{:id :path :capabilities})

(def ^:private mapping-overlay-keys
  #{:schema-version :target :product-family :id :capabilities
    :custom-handlers :entries})

(def ^:private legal-policy-keys
  #{:schema-version :target :upstream-license :allowed-upstream-licenses
    :legal-sets :profile-legal-sets})

(def ^:private custom-validation-keys
  #{:schema-version :id :target :profile :baseline-profile :runner
    :oracle-sources :probe-sources :legal-sets})

(def ^:private proof-keys
  #{:role :ladders})

(def ^:private target-validation-ladder-keys
  #{:id :kind :profiles :validation-contracts :resource-class})

(def ^:private custom-ladder-keys
  (conj target-validation-ladder-keys :runner))

(def ^:private target-roles
  #{:product :reusable-translator-conformance})

(def ^:private proof-resource-classes
  #{:conformance :high-memory})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-target-directory))))

(defn- non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (re-find #"[\r\n\u0000]" value))))

(defn- exact-keys!
  [subject expected value]
  (when-not (map? value)
    (fail! (str subject " must be a map")
           {:subject subject :value value}))
  (let [actual (set (keys value))]
    (when-not (= expected actual)
      (fail! (str subject " has missing or unknown keys")
             {:subject subject
              :expected (vec (sort expected))
              :actual (vec (sort actual))
              :missing (vec (sort (set/difference expected actual)))
              :unknown (vec (sort (set/difference actual expected)))})))
  value)

(defn- target-id
  [target]
  (let [target (cond
                 (keyword? target) target
                 (string? target) (keyword target)
                 :else nil)]
    (when-not (and (simple-keyword? target)
                   (re-matches #"[a-z][a-z0-9-]*" (name target)))
      (fail! "Target identity must be a stable lowercase keyword"
             {:target target}))
    target))

(defn- keyword-set!
  [subject value]
  (when-not (and (set? value) (every? keyword? value))
    (fail! (str subject " must be a set of keywords")
           {:subject subject :value value}))
  value)

(defn- distinct-vector!
  [subject value value-fn]
  (when-not (vector? value)
    (fail! (str subject " must be a vector")
           {:subject subject :value value}))
  (let [identities (mapv value-fn value)]
    (when-not (= (count identities) (count (distinct identities)))
      (fail! (str subject " contains duplicate identities")
             {:subject subject :identities identities})))
  value)

(defn- relative-components
  [value]
  (when (non-blank-string? value)
    (let [components (str/split value #"/" -1)]
      (when (and (not (str/includes? value "\\"))
                 (not (str/starts-with? value "/"))
                 (not (re-find #"^[A-Za-z]:" value))
                 (every? #(and (non-blank-string? %)
                               (not (contains? #{"." ".."} %)))
                         components))
        components))))

(defn- relative-path!
  [subject value]
  (or (relative-components value)
      (fail! (str subject " must be a normalized portable relative path")
             {:subject subject :path value}))
  value)

(defn- starts-with-components?
  [path components]
  (= components (subvec (vec (relative-components path))
                        0 (min (count components)
                               (count (relative-components path))))))

(defn- regular-file?
  [path]
  (Files/isRegularFile (paths/path path) (make-array LinkOption 0)))

(defn- directory?
  [path]
  (Files/isDirectory (paths/path path) (make-array LinkOption 0)))

(defn- target-file!
  [target-root subject required-prefix path]
  (relative-path! subject path)
  (when-not (starts-with-components? path required-prefix)
    (fail! (str subject " is outside its target-owned area")
           {:subject subject :path path :required-prefix required-prefix}))
  (let [file (paths/resolve-path target-root path)]
    (when-not (regular-file? file)
      (fail! (str subject " is missing")
             {:subject subject :path (str file)}))
    file))

(defn- workspace-file!
  [workspace-root subject path]
  (relative-path! subject path)
  (let [file (paths/resolve-path workspace-root path)]
    (when-not (regular-file? file)
      (fail! (str subject " is missing")
             {:subject subject :path (str file)}))
    file))

(defn- read-edn!
  [subject file]
  (try
    (edn/read-string (slurp (str file)))
    (catch RuntimeException error
      (if (ex-data error)
        (throw error)
        (throw (ex-info (str subject " is not valid EDN")
                        {:kind :invalid-target-directory
                         :subject subject
                         :path (str file)}
                        error))))))

(defn- load-authored-source-contract!
  [workspace-root expected-scope expected-class subject file]
  (let [contract (read-edn! subject file)]
    (try
      (assoc
       (authorship/validate-source-contract!
        workspace-root expected-scope expected-class contract)
       :path file)
      (catch clojure.lang.ExceptionInfo error
        (throw
         (ex-info (str subject " is invalid")
                  (assoc (ex-data error)
                         :kind :invalid-target-directory
                         :subject subject
                         :path (str file))
                  error))))))

(defn- load-authorship!
  [workspace-root target-root target paths-contract]
  (exact-keys! "Target authorship paths"
               authorship-path-keys paths-contract)
  (let [{:keys [compatibility destination]} paths-contract
        compatibility-file
        (workspace-file! workspace-root
                         "Shared authored compatibility contract"
                         compatibility)
        _ (when-not (= "config/authored-compat.edn" compatibility)
            (fail! "Shared authored compatibility contract must use its canonical path"
                   {:path compatibility
                    :expected "config/authored-compat.edn"}))
        destination-file
        (target-file! target-root "Target authored source contract"
                      [] destination)]
    (when-not (= "authorship.edn" destination)
      (fail! "Target authored source contract must use its canonical path"
             {:path destination :expected "authorship.edn"}))
    {:compatibility
     (load-authored-source-contract!
      workspace-root :shared-compatibility :authored-compat
      "Shared authored compatibility contract" compatibility-file)
     :destination
     (load-authored-source-contract!
      workspace-root target :authored-destination-runtime
      "Target authored source contract" destination-file)}))

(defn- validate-documents!
  [workspace-root documents]
  (exact-keys! "Target document contract" document-keys documents)
  (let [{:keys [product-goal port-scope dependencies]} documents]
    (doseq [[subject path required-name]
            [["Product goal" product-goal "product-goal.md"]
             ["Port scope" port-scope "port-scope.md"]]]
      (workspace-file! workspace-root subject path)
      (when-not (and (starts-with-components? path ["doc" "targets"])
                     (= required-name (last (relative-components path))))
        (fail! (str subject " must remain under doc/targets")
               {:subject subject :path path :required-name required-name})))
    (distinct-vector! "Target dependency documents" dependencies identity)
    (doseq [path dependencies]
      (workspace-file! workspace-root "Target dependency contract" path)
      (when-not (starts-with-components? path ["doc" "targets"])
        (fail! "Target dependency contract must remain under doc/targets"
               {:path path})))
    documents))

(defn- validate-java!
  [java]
  (exact-keys! "Target Java contract" java-keys java)
  (let [{:keys [source-language-version runtime-major preview-features?]} java]
    (when-not (and (pos-int? source-language-version)
                   (pos-int? runtime-major)
                   (<= source-language-version runtime-major)
                   (boolean? preview-features?))
      (fail! "Target Java-version declarations are invalid"
             {:java java})))
  java)

(defn- validate-path-descriptors!
  [subject expected-keys descriptors]
  (distinct-vector! subject descriptors :id)
  (distinct-vector! subject descriptors :path)
  (doseq [descriptor descriptors]
    (exact-keys! (str subject " entry") expected-keys descriptor)
    (when-not (keyword? (:id descriptor))
      (fail! (str subject " identity must be a keyword")
             {:descriptor descriptor}))
    (relative-path! (str subject " path") (:path descriptor)))
  descriptors)

(defn- resolve-handler-registry!
  [handlers overlay]
  (when-not (map? handlers)
    (fail! "Mapping overlay custom handlers must be a map"
           {:overlay (:id overlay) :custom-handlers handlers}))
  (into
   {}
   (map
    (fn [[id selector]]
      (when-not (and (qualified-keyword? id)
                     (qualified-symbol? selector))
        (fail! "Mapping overlay custom-handler declaration is invalid"
               {:overlay (:id overlay)
                :handler-id id
                :selector selector}))
      (let [resolved
            (try
              (requiring-resolve selector)
              (catch RuntimeException error
                (throw
                 (ex-info "Mapping overlay custom handler cannot be resolved"
                          {:kind :invalid-target-directory
                           :overlay (:id overlay)
                           :handler-id id
                           :selector selector}
                          error))))]
        (when-not (ifn? resolved)
          (fail! "Mapping overlay custom handler is not callable"
                 {:overlay (:id overlay)
                  :handler-id id
                  :selector selector}))
        [id resolved]))
    handlers)))

(defn- load-mapping-overlays!
  [target-root target family profile-names declared-capabilities descriptors]
  (into
   {}
   (map
    (fn [{:keys [id path]}]
      (let [file (target-file! target-root "Mapping overlay"
                               ["mappings"] path)
            overlay (read-edn! "Mapping overlay" file)]
        (exact-keys! "Mapping overlay" mapping-overlay-keys overlay)
        (when-not (= mapping-overlay-schema-version (:schema-version overlay))
          (fail! "Mapping overlay has an unsupported schema version"
                 {:overlay id :actual (:schema-version overlay)}))
        (when-not (and (= target (:target overlay))
                       (= family (:product-family overlay))
                       (= id (:id overlay)))
          (fail! "Mapping overlay identities disagree with its target descriptor"
                 {:descriptor {:target target :product-family family :id id}
                  :overlay
                  (select-keys overlay [:target :product-family :id])}))
        (let [capabilities
              (keyword-set! "Mapping overlay capabilities"
                            (:capabilities overlay))
              undeclared (set/difference capabilities declared-capabilities)]
          (when (seq undeclared)
            (fail! "Mapping overlay provides undeclared target capabilities"
                   {:overlay id :undeclared (vec (sort undeclared))}))
          (let [handlers
                (resolve-handler-registry! (:custom-handlers overlay) overlay)
                registry
                (try
                  (mapping-registry/compile-registry
                   (:entries overlay)
                   {:custom-handlers handlers})
                  (catch RuntimeException error
                    (throw
                     (ex-info "Target mapping overlay is invalid"
                              {:kind :invalid-target-directory
                               :overlay id
                               :path (str file)}
                              error))))]
            (doseq [entry (mapping-registry/registry-entries registry)]
              (when-not
               (contains? (conj (set (map keyword profile-names)) target)
                          (:introduced-by entry))
                (fail! "Mapping overlay entry has an unrelated introducing target"
                       {:overlay id
                        :mapping (:id entry)
                        :introduced-by (:introduced-by entry)
                        :allowed
                        (vec
                         (sort
                          (conj (set (map keyword profile-names)) target)))})))
            [id {:descriptor {:id id :path path}
                 :path file
                 :contract overlay
                 :registry registry
                 :capabilities capabilities}]))))
    descriptors)))

(defn- validate-legal-policy!
  [target-root target policy-path baseline-record profile-names]
  (let [file (target-file! target-root "Legal policy" ["legal"] policy-path)
        policy (read-edn! "Legal policy" file)]
    (exact-keys! "Legal policy" legal-policy-keys policy)
    (when-not (= legal-policy-schema-version (:schema-version policy))
      (fail! "Legal policy has an unsupported schema version"
             {:actual (:schema-version policy)}))
    (when-not (= target (:target policy))
      (fail! "Legal policy identifies the wrong target"
             {:expected target :actual (:target policy)}))
    (let [allowed (:allowed-upstream-licenses policy)
          selected (:upstream-license policy)
          legal-sets (keyword-set! "Legal policy sets" (:legal-sets policy))
          profile-legal-sets (:profile-legal-sets policy)
          baseline-sets (set (keys (:legal-sets baseline-record)))]
      (when-not (and (set? allowed)
                     (seq allowed)
                     (every? non-blank-string? allowed)
                     (non-blank-string? selected)
                     (contains? allowed selected))
        (fail! "Legal policy license selection is invalid"
               {:selected selected :allowed allowed}))
      (when-not (= selected (get-in baseline-record [:upstream :license]))
        (fail! "Legal policy and baseline select different upstream licenses"
               {:policy selected
                :baseline (get-in baseline-record [:upstream :license])}))
      (when-not (= legal-sets baseline-sets)
        (fail! "Legal policy and baseline define different legal sets"
               {:policy legal-sets :baseline baseline-sets}))
      (when-not (and (map? profile-legal-sets)
                     (= profile-names (set (keys profile-legal-sets))))
        (fail! "Legal policy must declare legal sets for every target profile"
               {:expected (vec (sort profile-names))
                :actual (vec (sort (keys profile-legal-sets)))}))
      (doseq [[profile sets] profile-legal-sets]
        (when-not (and (vector? sets)
                       (= (count sets) (count (distinct sets)))
                       (every? keyword? sets)
                       (set/subset? (set sets) legal-sets))
          (fail! "Profile legal-set selection is invalid"
                 {:profile profile :legal-sets sets
                  :available legal-sets})))
      {:path file :contract policy})))

(defn- load-baseline!
  [workspace-root target-root target baseline-path]
  (let [file (target-file! target-root "Target baseline" [] baseline-path)
        record (read-edn! "Target baseline" file)]
    (when-not (= "baseline.edn" baseline-path)
      (fail! "Target baseline must use the canonical target-relative path"
             {:path baseline-path :expected "baseline.edn"}))
    (try
      (baseline/validate-record! target record)
      (catch RuntimeException error
        (throw
         (ex-info "Target-owned baseline is invalid"
                  {:kind :invalid-target-directory
                   :target target
                   :path (str file)}
                  error))))
    (doseq [[legal-set entries] (:legal-sets record)
            entry entries]
      (workspace-file! workspace-root
                       (str "Baseline " (name legal-set) " legal source")
                       (:source entry))
      (relative-path! "Baseline legal destination" (:destination entry))
      (relative-path! "Baseline package legal path" (:package-path entry)))
    {:path file :record record}))

(defn- profile-dag!
  [profiles]
  (let [states (atom {})]
    (letfn [(visit! [profile stack]
              (case (get @states profile)
                :done nil
                :visiting
                (let [start (.indexOf ^java.util.List stack profile)]
                  (fail! "Target profile dependencies contain a cycle"
                         {:cycle (conj (subvec stack start) profile)}))
                (do
                  (swap! states assoc profile :visiting)
                  (doseq [dependency
                          (get-in profiles
                                  [profile :configuration
                                   :dependency-profiles])]
                    (when-not (contains? profiles dependency)
                      (fail! "Target profile names an unknown dependency"
                             {:profile profile :dependency dependency
                              :available (vec (sort (keys profiles)))}))
                    (visit! dependency (conj stack profile)))
                  (swap! states assoc profile :done))))]
      (doseq [profile (sort (keys profiles))]
        (visit! profile [])))
    profiles))

(defn- source-root!
  [workspace-root profile]
  (let [configured (:project-root profile)]
    (when-not (non-blank-string? configured)
      (fail! "Target profile has no source project path"
             {:profile (:profile profile)
              :project-root configured}))
    (let [candidate (paths/path configured)
          resolved (if (.isAbsolute candidate)
                     candidate
                     (do
                       (relative-path! "Source project path" configured)
                       (paths/resolve-path workspace-root configured)))]
      (when-not (directory? resolved)
        (fail! "Target profile source project directory is missing"
               {:profile (:profile profile)
                :path (str resolved)}))
      resolved)))

(defn- destination-capabilities!
  [destination]
  (reduce
   (fn [result field]
     (let [value (or (get destination field) #{})]
       (keyword-set! (str "Destination " (name field)) value)
       (into result value)))
   #{}
   [:destination-capabilities :internal-capabilities :bridge-capabilities]))

(defn- load-runtime-assets!
  [target-root declared-capabilities descriptors]
  (into
   {}
   (map
    (fn [{:keys [id path capabilities] :as descriptor}]
      (let [capabilities
            (keyword-set! "Runtime asset capabilities" capabilities)
            undeclared (set/difference capabilities declared-capabilities)]
        (when (empty? capabilities)
          (fail! "Runtime asset must provide at least one capability"
                 {:asset id :path path}))
        (when (seq undeclared)
          (fail! "Runtime asset provides undeclared target capabilities"
                 {:asset id :undeclared (vec (sort undeclared))}))
        (when-not (str/ends-with? path ".cs")
          (fail! "Runtime asset must be a C# source file"
                 {:asset id :path path}))
        [id {:descriptor descriptor
             :path (target-file! target-root "Runtime asset" ["runtime"] path)
             :capabilities capabilities}]))
    descriptors)))

(defn- load-destinations!
  [target-root target family baseline-record declared-capabilities descriptors]
  (into
   {}
   (map
    (fn [{:keys [id path] :as descriptor}]
      (let [file (target-file! target-root "Destination configuration"
                               ["destinations"] path)
            destination (read-edn! "Destination configuration" file)
            baseline-profile (:baseline-profile destination)
            baseline-contract (get-in baseline-record
                                      [:profiles baseline-profile])
            capabilities (destination-capabilities! destination)]
        (when-not (= 1 (:schema-version destination))
          (fail! "Destination configuration has an unsupported schema version"
                 {:destination id :actual (:schema-version destination)}))
        (when-not (and (= family (:product-family destination))
                       (= target (:baseline-target destination))
                       (keyword? baseline-profile)
                       baseline-contract)
          (fail! "Destination identity disagrees with its target or baseline"
                 {:destination id
                  :target target
                  :product-family family
                  :actual
                  (select-keys destination
                               [:product-family :baseline-target
                                :baseline-profile])}))
        (let [package-id (get-in destination [:package :id])
              assembly-name (get-in destination [:project :assembly-name])
              target-framework (get-in destination [:project :target-framework])]
          (when-not (and (= package-id (:package-id baseline-contract))
                         (non-blank-string? assembly-name)
                         (non-blank-string? target-framework))
            (fail! "Destination package identity disagrees with its baseline"
                   {:destination id
                    :package-id package-id
                    :baseline-package-id (:package-id baseline-contract)
                    :assembly-name assembly-name
                    :target-framework target-framework})))
        (let [undeclared (set/difference capabilities declared-capabilities)]
          (when (seq undeclared)
            (fail! "Destination requests undeclared target capabilities"
                   {:destination id
                    :undeclared (vec (sort undeclared))})))
        [id {:descriptor descriptor
             :path file
             :configuration destination
             :capabilities capabilities}]))
    descriptors)))

(defn- product-identity-fragments
  [target family destination]
  (let [leading
        (fn [value]
          (when (non-blank-string? value)
            (first (str/split value #"[.]"))))]
    (->> (concat
          [(name target)]
          (when-not (= :java-library family) [(name family)])
          (map leading
               (concat
                [(get-in destination [:package :id])
                 (get-in destination [:project :assembly-name])
                 (get-in destination [:project :root-namespace])]
                (vals (:namespaces destination))
                (vals (:namespace-prefixes destination)))))
         (filter non-blank-string?)
         (map str/lower-case)
         (filter #(<= 3 (count %)))
         distinct
         sort
         vec)))

(defn- profile-authorship!
  [workspace-root target family profile-id descriptor destination
   runtime-records authorship-contracts]
  (let [selection (:authorship descriptor)]
    (exact-keys! "Profile authorship contract"
                 profile-authorship-keys selection)
    (exact-keys! "Profile authorship budget"
                 authorship-budget-keys (:budget selection))
    (let [{:keys [sources evidence review budget]} selection
          available-destination-sources
          (get-in authorship-contracts [:destination :sources])
          source-ids (set (keys available-destination-sources))
          selected-source-ids (set sources)
          compatibility-capabilities
          (set
           (if (contains? destination :bridge-capabilities)
             (:bridge-capabilities destination)
             (:destination-capabilities destination)))
          compatibility-sources
          (into
           (sorted-map)
           (filter
            (fn [[_ group]]
              (contains? compatibility-capabilities
                         (:capability group))))
           (get-in authorship-contracts [:compatibility :sources]))
          destination-sources
          (select-keys available-destination-sources sources)
          selected-runtime-paths
          (set
           (map
            #(str "targets/" (name target) "/"
                  (get-in % [:descriptor :path]))
            (vals runtime-records)))
          contracted-runtime-paths
          (set (mapcat :paths (vals destination-sources)))
          policy
          {:schema-version authorship/policy-schema-version
           :target target
           :profile profile-id
           :package-id (get-in destination [:package :id])
           :review review
           :evidence evidence
           :budget budget
           :forbidden-identities
           (product-identity-fragments target family destination)
           :compatibility-sources compatibility-sources
           :destination-sources destination-sources}]
      (when-not
       (and (vector? sources)
            (= (count sources) (count selected-source-ids))
            (every? qualified-keyword? sources)
            (set/subset? selected-source-ids source-ids)
            (vector? evidence)
            (seq evidence)
            (= (count evidence) (count (distinct evidence)))
            (every? keyword? evidence)
            (non-blank-string? review))
        (fail! "Profile authorship selections are invalid"
               {:profile profile-id
                :sources sources
                :available-sources (vec (sort source-ids))
                :evidence evidence
                :review review}))
      (when-not (set/subset? selected-runtime-paths
                             contracted-runtime-paths)
        (fail! "Selected target runtime assets lack authored source contracts"
               {:profile profile-id
                :missing
                (vec
                 (sort
                  (set/difference selected-runtime-paths
                                  contracted-runtime-paths)))}))
      (try
        (authorship/verify-compatibility-neutrality!
         workspace-root
         (vals (get-in authorship-contracts
                       [:compatibility :sources]))
         (:forbidden-identities policy)
         {:target target :profile profile-id})
        (catch clojure.lang.ExceptionInfo error
          (throw
           (ex-info "Shared authored compatibility contract is product-specific"
                    (assoc (ex-data error)
                           :kind :invalid-target-directory
                           :profile profile-id)
                    error))))
      (try
        (authorship/validate-policy-contract! policy)
        (catch clojure.lang.ExceptionInfo error
          (throw
           (ex-info "Profile authorship policy is invalid"
                    (assoc (ex-data error)
                           :kind :invalid-target-directory
                           :profile profile-id)
                    error))))
      policy)))

(defn- load-profiles!
  [workspace-root target-root target family java baseline-record descriptors
   destinations overlay-records asset-records validation-descriptor-ids
   legal-policy authorship-contracts]
  (let [profiles
        (into
         {}
         (map
          (fn [{:keys [id path destination required-capabilities]
                :as descriptor}]
            (let [mapping-ids (:mapping-overlays descriptor)
                  runtime-ids (:runtime-assets descriptor)
                  validation-ids (:validation-contracts descriptor)
                  selections
                  [["mapping overlay" mapping-ids
                    (set (keys overlay-records))]
                   ["runtime asset" runtime-ids
                    (set (keys asset-records))]
                   ["validation contract" validation-ids
                    validation-descriptor-ids]]]
              (when-not (non-blank-string? id)
                (fail! "Target profile descriptor id must be a nonblank string"
                       {:descriptor descriptor}))
              (doseq [[subject values available] selections]
                (when-not (and (vector? values)
                               (= (count values) (count (distinct values)))
                               (every? keyword? values)
                               (set/subset? (set values) available))
                  (fail! (str "Target profile selects an invalid " subject)
                         {:profile id :selection values
                          :available (vec (sort available))})))
              (keyword-set! "Profile required capabilities"
                            required-capabilities)
              (let [file (target-file! target-root "Generation profile"
                                       ["profiles"] path)
                    profile (read-edn! "Generation profile" file)
                    destination-record (get destinations destination)
                    destination-config (:configuration destination-record)
                    baseline-profile (:baseline-profile profile)
                    baseline-contract (get-in baseline-record
                                              [:profiles baseline-profile])]
                (when-not destination-record
                  (fail! "Target profile selects an unknown destination"
                         {:profile id :destination destination
                          :available (vec (sort (keys destinations)))}))
                (when-not (and (= 1 (:schema-version profile))
                               (= id (:profile profile))
                               (= family (:product-family profile))
                               (= target (:baseline-target profile))
                               (keyword? baseline-profile)
                               baseline-contract
                               (= id (:profile baseline-contract)))
                  (fail! "Generation profile identities disagree with its target or baseline"
                         {:descriptor-id id
                          :profile
                          (select-keys profile
                                       [:schema-version :profile
                                        :product-family :baseline-target
                                        :baseline-profile])
                          :baseline-profile baseline-contract}))
                (when-not (= (:path (:descriptor destination-record))
                             (:destination-config profile))
                  (fail! "Generation profile selects a destination outside its descriptor"
                         {:profile id
                          :expected (:path (:descriptor destination-record))
                          :actual (:destination-config profile)}))
                (when-not (= (:destination-bundle profile)
                             (:destination-bundle destination-config))
                  (fail! "Generation profile and destination select different rule bundles"
                         {:profile id
                          :profile-bundle (:destination-bundle profile)
                          :destination-bundle
                          (:destination-bundle destination-config)}))
                (when-not (qualified-symbol? (:destination-bundle profile))
                  (fail! "Generation profile must select a qualified rule bundle"
                         {:profile id
                          :destination-bundle (:destination-bundle profile)}))
                (let [dependencies (or (:dependency-profiles profile) [])]
                  (when-not (and (vector? dependencies)
                                 (= (count dependencies)
                                    (count (distinct dependencies)))
                                 (every? non-blank-string? dependencies)
                                 (not (contains? (set dependencies) id)))
                    (fail! "Generation profile dependencies are invalid"
                           {:profile id :dependencies dependencies})))
                (when (and (contains? profile :gradle-java-major)
                           (not= (:runtime-major java)
                                 (:gradle-java-major profile)))
                  (fail! "Generation profile and target declare different Java runtimes"
                         {:profile id
                          :target-runtime (:runtime-major java)
                          :profile-runtime (:gradle-java-major profile)}))
                (source-root! workspace-root profile)
                (let [selected-mappings
                      (select-keys overlay-records mapping-ids)
                      selected-runtime
                      (select-keys asset-records runtime-ids)
                      provided
                      (into (:capabilities destination-record)
                            (concat (mapcat :capabilities
                                            (vals selected-mappings))
                                    (mapcat :capabilities
                                            (vals selected-runtime))))
                      missing
                      (set/difference required-capabilities provided)
                      configured-runtime
                      (vec (or (:runtime-sources destination-config) []))
                      selected-runtime-paths
                      (->> selected-runtime
                           vals
                           (map #(get-in % [:descriptor :path]))
                           sort
                           vec)
                      policy-legal-sets
                      (get-in legal-policy
                              [:contract :profile-legal-sets id])
                      destination-legal-sets
                      (vec (or (:baseline-legal-sets destination-config) []))]
                  (when (seq missing)
                    (fail! "Target profile requires unavailable capabilities"
                           {:profile id
                            :missing (vec (sort missing))
                            :provided (vec (sort provided))}))
                  (when-not (= (vec (sort configured-runtime))
                               selected-runtime-paths)
                    (fail! "Destination runtime sources disagree with target assets"
                           {:profile id
                            :destination destination
                            :configured (vec (sort configured-runtime))
                            :selected selected-runtime-paths}))
                  (when-not (= policy-legal-sets destination-legal-sets)
                    (fail! "Destination and legal policy select different legal sets"
                           {:profile id
                            :policy policy-legal-sets
                            :destination destination-legal-sets}))
                  [id {:descriptor descriptor
                       :path file
                       :configuration profile
                       :destination destination-record
                       :mapping-overlays selected-mappings
                       :runtime-assets selected-runtime
                       :provided-capabilities provided
                       :authorship
                       (profile-authorship!
                        workspace-root target family id descriptor
                        destination-config selected-runtime
                        authorship-contracts)}])))))
         descriptors)]
    (profile-dag! profiles)))

(defn- validate-used!
  [subject available selected]
  (let [unused (set/difference available selected)]
    (when (seq unused)
      (fail! (str "Target directory declares unused " subject)
             {:subject subject :unused (vec (sort unused))}))))

(defn- validation-source!
  [target-root subject expected-area source expected-extension]
  (when-not (str/ends-with? source expected-extension)
    (fail! (str subject " has the wrong source extension")
           {:source source :expected expected-extension}))
  (target-file! target-root subject ["validation" expected-area] source))

(defn- validate-custom-sources!
  [target-root subject area extension sources]
  (when-not (and (vector? sources)
                 (seq sources)
                 (= (count sources) (count (distinct sources)))
                 (every? non-blank-string? sources))
    (fail! (str subject " sources must be a nonempty distinct vector")
           {:sources sources}))
  (mapv #(validation-source! target-root subject area % extension) sources))

(defn- resolve-custom-runner!
  [selector validation-id]
  (when-not (qualified-symbol? selector)
    (fail! "Custom validation runner must be a qualified symbol"
           {:validation validation-id :runner selector}))
  (let [resolved
        (try
          (requiring-resolve selector)
          (catch RuntimeException error
            (throw
             (ex-info "Custom validation runner cannot be resolved"
                      {:kind :invalid-target-directory
                       :validation validation-id
                       :runner selector}
                      error))))]
    (when-not (ifn? resolved)
      (fail! "Custom validation runner is not callable"
             {:validation validation-id :runner selector}))
    resolved))

(defn- proof-selection!
  [subject values available ladder-id]
  (when-not (and (vector? values)
                 (seq values)
                 (= (count values) (count (distinct values)))
                 (set/subset? (set values) available))
    (fail! (str "Proof ladder selects invalid " subject)
           {:ladder ladder-id
            :selection values
            :available (vec (sort available))}))
  values)

(defn- load-proof!
  [target family proof profiles validations]
  (exact-keys! "Target proof contract" proof-keys proof)
  (let [role (:role proof)
        profile-ids (set (keys profiles))
        validation-ids (set (keys validations))
        ladders (:ladders proof)]
    (when-not (contains? target-roles role)
      (fail! "Target proof contract has an unsupported role"
             {:target target :role role :allowed (vec (sort target-roles))}))
    (when (and (= :reusable-translator-conformance role)
               (not= :java-library family))
      (fail! "Reusable-translator conformance targets must use the Java-library product family"
             {:target target :product-family family}))
    (distinct-vector! "Target proof ladders" ladders :id)
    (when (empty? ladders)
      (fail! "Target proof contract must declare at least one required ladder"
             {:target target}))
    (let [loaded
          (mapv
           (fn [{:keys [id kind profiles validation-contracts resource-class
                        runner]
                 :as ladder}]
             (exact-keys!
              "Target proof ladder"
              (if (= :custom kind)
                custom-ladder-keys
                target-validation-ladder-keys)
              ladder)
             (when-not (keyword? id)
               (fail! "Target proof ladder id must be a keyword"
                      {:target target :ladder id}))
             (when-not (contains? #{:target-validations :custom} kind)
               (fail! "Target proof ladder has an unsupported kind"
                      {:target target :ladder id :kind kind}))
             (when-not (contains? proof-resource-classes resource-class)
               (fail! "Target proof ladder has an unsupported resource class"
                      {:target target :ladder id
                       :resource-class resource-class
                       :allowed (vec (sort proof-resource-classes))}))
             (when (and (= :reusable-translator-conformance role)
                        (not= :conformance resource-class))
               (fail! "Reusable-translator conformance ladders must use the conformance resource class"
                      {:target target :ladder id
                       :resource-class resource-class}))
             (proof-selection! "profiles" profiles profile-ids id)
             (proof-selection! "validation contracts"
                               validation-contracts validation-ids id)
             (cond-> ladder
               (= :custom kind)
               (assoc :runner (resolve-custom-runner! runner id))))
           ladders)
          selected-profiles (mapcat :profiles loaded)
          selected-validations (mapcat :validation-contracts loaded)]
      (when-not (and (= (count selected-profiles)
                        (count (distinct selected-profiles)))
                     (= profile-ids (set selected-profiles)))
        (fail! "Required proof ladders must cover every target profile exactly once"
               {:target target
                :expected (vec (sort profile-ids))
                :actual (vec (sort selected-profiles))}))
      (when-not (and (= (count selected-validations)
                        (count (distinct selected-validations)))
                     (= validation-ids (set selected-validations)))
        (fail! "Required proof ladders must cover every target validation exactly once"
               {:target target
                :expected (vec (sort validation-ids))
                :actual (vec (sort selected-validations))}))
      {:role role :ladders loaded})))

(defn- validate-authorship-evidence!
  [target profiles proof]
  (let [ladders (into {} (map (juxt :id identity)) (:ladders proof))]
    (doseq [[profile-id {:keys [authorship]}] profiles
            evidence-id (:evidence authorship)]
      (let [ladder (get ladders evidence-id)]
        (when-not (and ladder
                       (contains? (set (:profiles ladder)) profile-id))
          (fail! "Profile authored sources lack a covering required proof ladder"
                 {:target target
                  :profile profile-id
                  :evidence evidence-id
                  :available
                  (vec
                   (sort
                    (for [[id candidate] ladders
                          :when (contains? (set (:profiles candidate))
                                           profile-id)]
                      id)))}))))
    profiles))

(defn- load-differential-validation!
  [workspace-root target-root target baseline-record legal-policy
   descriptor file contract profiles]
  (let [{:keys [id]} descriptor
        profile-name (get-in contract [:runner :profile])
        profile-record (get profiles profile-name)
        destination (get-in profile-record [:destination :configuration])
        profile-legal-sets
        (get-in legal-policy [:contract :profile-legal-sets profile-name])]
    (when-not (and (= id (:id contract))
                   (= target (:target contract))
                   profile-record
                   (= (:baseline-profile contract)
                      (get-in profile-record
                              [:configuration :baseline-profile]))
                   (contains?
                    (set (get-in profile-record
                                 [:descriptor :validation-contracts]))
                    id))
      (fail! "Validation contract identities disagree with its target profile"
             {:descriptor-id id
              :contract
              (select-keys contract [:id :target :baseline-profile])
              :profile profile-name}))
    (validation-source!
     target-root "Validation oracle" "oracle"
     (get-in contract [:runner :oracle :source]) ".java")
    (validation-source!
     target-root "Validation probe" "probe"
     (get-in contract [:runner :probe :source]) ".cs")
    (when-not (= profile-legal-sets
                 (get-in contract [:package-contract :legal-sets]))
      (fail! "Validation and legal-policy package contracts disagree"
             {:validation id
              :profile profile-name
              :policy profile-legal-sets
              :validation-legal-sets
              (get-in contract [:package-contract :legal-sets])}))
    (when-not (= (get-in destination [:project :assembly-name])
                 (get-in contract [:package-contract :assembly-name]))
      (fail! "Validation and destination assembly identities disagree"
             {:validation id
              :destination
              (get-in destination [:project :assembly-name])
              :validation-assembly
              (get-in contract [:package-contract :assembly-name])}))
    (when-not (= (get-in destination [:project :target-framework])
                 (get-in contract [:package-contract :target-framework]))
      (fail! "Validation and destination target frameworks disagree"
             {:validation id
              :destination
              (get-in destination [:project :target-framework])
              :validation-target-framework
              (get-in contract [:package-contract :target-framework])}))
    (doseq [[context-id context-path] (get-in contract [:runner :context])]
      (let [required-file?
            (contains? (set (get-in contract [:runner :required-files]))
                       context-id)
            required-directory?
            (contains?
             (set (get-in contract [:runner :required-directories]))
             context-id)
            candidate
            (if (starts-with-components? context-path ["validation"])
              (do
                (relative-path! "Validation context path" context-path)
                (paths/resolve-path target-root context-path))
              (do
                (relative-path! "Validation context path" context-path)
                (paths/resolve-path workspace-root context-path)))]
        (when (and required-file? (not (regular-file? candidate)))
          (fail! "Required validation context file is missing"
                 {:validation id :context context-id
                  :path (str candidate)}))
        (when (and required-directory? (not (directory? candidate)))
          (fail! "Required validation context directory is missing"
                 {:validation id :context context-id
                  :path (str candidate)}))))
    (doseq [legal-set (get-in contract [:package-contract :legal-sets])]
      (when-not (contains? (set (keys (:legal-sets baseline-record)))
                           legal-set)
        (fail! "Validation contract selects an unknown baseline legal set"
               {:validation id :legal-set legal-set})))
    {:descriptor descriptor :path file :contract contract}))

(defn- load-custom-validation!
  [target-root target baseline-record legal-policy descriptor file contract
   profiles]
  (let [{:keys [id]} descriptor
        profile-name (:profile contract)
        profile-record (get profiles profile-name)
        legal-sets (:legal-sets contract)]
    (exact-keys! "Custom validation contract" custom-validation-keys contract)
    (when-not (= 1 (:schema-version contract))
      (fail! "Custom validation contract has an unsupported schema version"
             {:validation id :actual (:schema-version contract)}))
    (when-not (and (= id (:id contract))
                   (= target (:target contract))
                   profile-record
                   (= (:baseline-profile contract)
                      (get-in profile-record
                              [:configuration :baseline-profile]))
                   (contains?
                    (set (get-in profile-record
                                 [:descriptor :validation-contracts]))
                    id))
      (fail! "Custom validation identities disagree with its target profile"
             {:descriptor-id id
              :contract
              (select-keys contract
                           [:id :target :profile :baseline-profile])}))
    (validate-custom-sources! target-root "Validation oracle" "oracle" ".java"
                              (:oracle-sources contract))
    (validate-custom-sources! target-root "Validation probe" "probe" ".cs"
                              (:probe-sources contract))
    (when-not (= legal-sets
                 (get-in legal-policy
                         [:contract :profile-legal-sets profile-name]))
      (fail! "Custom validation and legal-policy contracts disagree"
             {:validation id :profile profile-name
              :validation-legal-sets legal-sets
              :policy
              (get-in legal-policy
                      [:contract :profile-legal-sets profile-name])}))
    (doseq [legal-set legal-sets]
      (when-not (contains? (set (keys (:legal-sets baseline-record)))
                           legal-set)
        (fail! "Custom validation selects an unknown baseline legal set"
               {:validation id :legal-set legal-set})))
    {:descriptor descriptor
     :path file
     :contract contract
     :runner (resolve-custom-runner! (:runner contract) id)}))

(defn- load-validation-contracts!
  [workspace-root target-root target baseline-record legal-policy
   descriptors profiles]
  (into
   {}
   (map
    (fn [{:keys [id kind path] :as descriptor}]
      (let [file (target-file! target-root "Validation contract"
                               ["validation"] path)
            raw (read-edn! "Validation contract" file)
            validation
            (case kind
              :differential
              (let [contract
                    (try
                      (differential/validate-contract! raw)
                      (catch RuntimeException error
                        (throw
                         (ex-info "Target differential contract is invalid"
                                  {:kind :invalid-target-directory
                                   :validation id
                                   :path (str file)}
                                  error))))]
                (load-differential-validation!
                 workspace-root target-root target baseline-record legal-policy
                 descriptor file contract profiles))

              :custom
              (load-custom-validation!
               target-root target baseline-record legal-policy descriptor file
               raw profiles)

              (fail! "Target validation descriptor has an unsupported kind"
                     {:validation id :kind kind}))]
        [id validation]))
    descriptors)))

(defn read-target
  "Reads and validates one `targets/<id>` contract without a target registry.

  All referenced identities, paths, capabilities, profiles, destinations,
  mapping overlays, runtime sources, validation sources, baseline data, legal
  selections, and Java versions are checked before the normalized contract is
  returned. Callers must complete this preflight before discovery or output
  cleanup."
  ([target]
   (read-target (paths/workspace-root) target))
  ([workspace-root target]
   (let [workspace-root (paths/absolute workspace-root)
         requested-target (target-id target)
         target-root (paths/resolve-path
                      workspace-root (str "targets/" (name requested-target)))
         manifest-file (paths/resolve-path target-root "target.edn")]
     (when-not (regular-file? manifest-file)
       (fail! "Target manifest is missing"
              {:target requested-target :path (str manifest-file)}))
     (let [manifest (read-edn! "Target manifest" manifest-file)]
       (exact-keys! "Target manifest" manifest-keys manifest)
       (when-not (= schema-version (:schema-version manifest))
         (fail! "Target manifest has an unsupported schema version"
                {:expected schema-version
                 :actual (:schema-version manifest)}))
       (let [target (target-id (:target manifest))
             family (:product-family manifest)
             java (validate-java! (:java manifest))
             declared-capabilities
             (keyword-set! "Target capabilities" (:capabilities manifest))]
         (when-not (= requested-target target)
           (fail! "Target manifest identity disagrees with its directory"
                  {:requested requested-target :manifest target}))
         (when-not (keyword? family)
           (fail! "Target product-family identity must be a keyword"
                  {:product-family family}))
         (validate-documents! workspace-root (:contracts manifest))
         (validate-path-descriptors! "Target destinations"
                                     path-descriptor-keys
                                     (:destinations manifest))
         (validate-path-descriptors! "Target mapping overlays"
                                     path-descriptor-keys
                                     (:mapping-overlays manifest))
         (validate-path-descriptors! "Target runtime assets"
                                     runtime-descriptor-keys
                                     (:runtime-assets manifest))
         (validate-path-descriptors! "Target validation contracts"
                                     validation-descriptor-keys
                                     (:validation-contracts manifest))
         (distinct-vector! "Target profiles" (:profiles manifest) :id)
         (distinct-vector! "Target profiles" (:profiles manifest) :path)
         (when (empty? (:profiles manifest))
           (fail! "Target directory must declare at least one profile"
                  {:target target}))
         (doseq [descriptor (:profiles manifest)]
           (exact-keys! "Target profile descriptor"
                        profile-descriptor-keys descriptor)
           (relative-path! "Target profile path" (:path descriptor)))
         (let [baseline (load-baseline! workspace-root target-root target
                                        (:baseline manifest))
               baseline-record (:record baseline)]
           (let [manifest-profiles (set (map :id (:profiles manifest)))
                 baseline-profiles
                 (set (map :profile (vals (:profiles baseline-record))))]
             (when-not (= manifest-profiles baseline-profiles)
               (fail! "Target manifest and baseline define different profiles"
                      {:manifest (vec (sort manifest-profiles))
                       :baseline (vec (sort baseline-profiles))})))
           (when-not (= (:source-language-version java)
                        (get-in baseline-record
                                [:upstream :java-language-version]))
             (fail! "Target manifest and baseline declare different Java language versions"
                    {:target target
                     :manifest (:source-language-version java)
                     :baseline
                     (get-in baseline-record
                             [:upstream :java-language-version])}))
           (let [profile-names (set (map :id (:profiles manifest)))
                 legal-policy
                 (validate-legal-policy!
                  target-root target (:legal-policy manifest)
                  baseline-record profile-names)
                 authorship-contracts
                 (load-authorship!
                  workspace-root target-root target (:authorship manifest))
                 destinations
                 (load-destinations!
                  target-root target family baseline-record
                  declared-capabilities (:destinations manifest))
                 mapping-overlays
                 (load-mapping-overlays!
                  target-root target family profile-names
                  declared-capabilities
                  (:mapping-overlays manifest))
                 runtime-assets
                 (load-runtime-assets!
                  target-root declared-capabilities
                  (:runtime-assets manifest))
                 validation-descriptor-ids
                 (set (map :id (:validation-contracts manifest)))
                 profiles
                 (load-profiles!
                  workspace-root target-root target family java baseline-record
                  (:profiles manifest) destinations mapping-overlays
                  runtime-assets validation-descriptor-ids legal-policy
                  authorship-contracts)
                 validations
                 (load-validation-contracts!
                  workspace-root target-root target baseline-record legal-policy
                  (:validation-contracts manifest) profiles)
                 proof
                 (load-proof! target family (:proof manifest)
                              profiles validations)
                 _ (validate-authorship-evidence! target profiles proof)
                 selected-destinations
                 (set (map #(get-in % [:descriptor :destination])
                           (vals profiles)))
                 selected-mappings
                 (set (mapcat #(get-in % [:descriptor :mapping-overlays])
                              (vals profiles)))
                 selected-runtime
                 (set (mapcat #(get-in % [:descriptor :runtime-assets])
                              (vals profiles)))
                 selected-validations
                 (set (mapcat #(get-in % [:descriptor :validation-contracts])
                              (vals profiles)))
                 selected-authored-sources
                 (set (mapcat #(get-in % [:descriptor :authorship :sources])
                              (vals profiles)))
                 provided-capabilities
                 (into #{}
                       (concat
                        (mapcat :capabilities (vals destinations))
                        (mapcat :capabilities (vals mapping-overlays))
                        (mapcat :capabilities (vals runtime-assets))))]
             (validate-used! "destinations" (set (keys destinations))
                             selected-destinations)
             (validate-used! "mapping overlays" (set (keys mapping-overlays))
                             selected-mappings)
             (validate-used! "runtime assets" (set (keys runtime-assets))
                             selected-runtime)
             (validate-used! "validation contracts"
                             (set (keys validations))
                             selected-validations)
             (validate-used!
              "target authored sources"
              (set
               (keys
                (get-in authorship-contracts
                        [:destination :sources])))
              selected-authored-sources)
             (when-not (= declared-capabilities provided-capabilities)
               (fail! "Target capability declaration and providers disagree"
                      {:declared (vec (sort declared-capabilities))
                       :provided (vec (sort provided-capabilities))
                       :missing
                       (vec (sort
                             (set/difference declared-capabilities
                                             provided-capabilities)))
                       :undeclared
                       (vec (sort
                             (set/difference provided-capabilities
                                             declared-capabilities)))}))
             {:schema-version schema-version
              :target target
              :product-family family
              :workspace-root workspace-root
              :target-directory target-root
              :manifest-file manifest-file
              :manifest manifest
              :documents (:contracts manifest)
              :java java
              :capabilities declared-capabilities
              :baseline baseline
              :legal-policy legal-policy
              :profiles profiles
              :destinations destinations
              :mapping-overlays mapping-overlays
              :runtime-assets runtime-assets
              :authorship authorship-contracts
              :validation-contracts validations
              :proof proof})))))))
