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
            [dripsharp.paths :as paths]
            [dripsharp.util :as util]
            [dripsharp.validation :as validation])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file Files LinkOption]))

(def schema-version 4)
(def legal-policy-schema-version 4)
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
  #{:compatibility :destination :third-party})

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
    :legal-sets :profile-legal-sets :resource-notice-legal-sets
    :notice-appendix-sha256 :package-metadata})

(def ^:private package-metadata-policy-keys
  #{:required-description-fragments :forbidden-identity-marks})

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

(defn- validation-context
  ([subject]
   (validation-context subject {}))
  ([subject data]
   {:kind :invalid-target-directory
    :subject subject
    :data data}))

(defn- non-blank-string?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (re-find #"[\u0000\u000B\u000C\r\n\u0085\u2028\u2029]" value))))

(defn- non-blank-text?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (str/includes? value "\u0000"))))

(defn- exact-keys!
  ([subject expected value]
   (exact-keys! subject [] expected value))
  ([subject path expected value]
   (validation/exact-keys! (validation-context subject)
                           path value expected expected)))

(defn- target-id
  [target]
  (let [context (validation-context "Target identity")
        target (cond
                 (keyword? target) target
                 (string? target) (keyword target)
                 :else target)]
    (validation/check! context [:target] target
                       "a simple keyword" simple-keyword?)
    (validation/check! context [:target] target
                       "a stable lowercase keyword"
                       #(boolean
                         (re-matches #"[a-z][a-z0-9-]*" (name %))))
    target))

(defn- keyword-set!
  ([subject value]
   (keyword-set! subject [] value))
  ([subject path value]
   (let [context (validation-context subject)]
     (validation/check! context path value "a set" set?)
     (doseq [item value]
       (validation/check! context (conj path item) item "a keyword" keyword?)))
   value))

(defn- distinct-vector!
  ([subject value value-fn]
   (distinct-vector! subject [] value value-fn))
  ([subject path value value-fn]
   (let [context (validation-context subject)]
     (validation/check! context path value "a vector" vector?)
     (let [identities (mapv value-fn value)]
       (validation/check! context path value
                          "a vector without duplicate identities"
                          (fn [_]
                            (= (count identities)
                               (count (distinct identities)))))))
   value))

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
    (when-not (paths/real-contained? target-root file)
      (fail! (str subject " resolves outside its target-owned area")
             {:subject subject :path path :reason :outside-target-root}))
    file))

(defn- workspace-file!
  [workspace-root subject path]
  (relative-path! subject path)
  (let [file (paths/resolve-path workspace-root path)]
    (when-not (regular-file? file)
      (fail! (str subject " is missing")
             {:subject subject :path (str file)}))
    (when-not (paths/real-contained? workspace-root file)
      (fail! (str subject " resolves outside the workspace")
             {:subject subject :path path :reason :outside-workspace}))
    file))

(defn- read-edn!
  [subject file]
  (let [eof (Object.)
        [value trailing]
        (try
          (with-open [reader
                      (PushbackReader.
                       (StringReader. (slurp (str file))))]
            [(edn/read {:eof eof} reader)
             (edn/read {:eof eof} reader)])
          (catch RuntimeException error
            (throw
             (ex-info (str subject " is not valid EDN")
                      {:kind :invalid-target-directory
                       :subject subject
                       :path (str file)
                       :reason :invalid-edn}
                      error))))]
    (when (identical? eof value)
      (fail! (str subject " is empty")
             {:subject subject
              :path (str file)
              :reason :empty-edn}))
    (when-not (identical? eof trailing)
      (fail! (str subject " contains trailing EDN data")
             {:subject subject
              :path (str file)
              :reason :trailing-data}))
    value))

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
  (exact-keys! "Target authorship paths" [:authorship]
               authorship-path-keys paths-contract)
  (let [{:keys [compatibility destination third-party]} paths-contract
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
                      [] destination)
        third-party-file
        (target-file! target-root "Target third-party source contract"
                      [] third-party)]
    (when-not (= "authorship.edn" destination)
      (fail! "Target authored source contract must use its canonical path"
             {:path destination :expected "authorship.edn"}))
    (when-not (= "third-party.edn" third-party)
      (fail! "Target third-party source contract must use its canonical path"
             {:path third-party :expected "third-party.edn"}))
    {:compatibility
     (load-authored-source-contract!
      workspace-root :shared-compatibility :authored-compat
      "Shared authored compatibility contract" compatibility-file)
     :destination
     (load-authored-source-contract!
      workspace-root target :authored-destination-runtime
      "Target authored source contract" destination-file)
     :third-party
     (load-authored-source-contract!
      workspace-root target :vendored-third-party
      "Target third-party source contract" third-party-file)}))

(defn- validate-documents!
  [workspace-root documents]
  (exact-keys! "Target document contract" [:contracts] document-keys documents)
  (let [{:keys [product-goal port-scope dependencies]} documents]
    (doseq [[subject path required-name]
            [["Product goal" product-goal "product-goal.md"]
             ["Port scope" port-scope "port-scope.md"]]]
      (workspace-file! workspace-root subject path)
      (when-not (and (starts-with-components? path ["doc" "targets"])
                     (= required-name (last (relative-components path))))
        (fail! (str subject " must remain under doc/targets")
               {:subject subject :path path :required-name required-name})))
    (distinct-vector! "Target dependency documents"
                      [:contracts :dependencies] dependencies identity)
    (doseq [path dependencies]
      (workspace-file! workspace-root "Target dependency contract" path)
      (when-not (starts-with-components? path ["doc" "targets"])
        (fail! "Target dependency contract must remain under doc/targets"
               {:path path})))
    documents))

(defn- validate-java!
  [java]
  (exact-keys! "Target Java contract" [:java] java-keys java)
  (let [{:keys [source-language-version runtime-major preview-features?]} java]
    (validation/check!
     (validation-context "Target Java contract")
     [:java :source-language-version] source-language-version
     "a positive integer" pos-int?)
    (validation/check!
     (validation-context "Target Java contract")
     [:java :runtime-major] runtime-major
     "a positive integer" pos-int?)
    (validation/check!
     (validation-context "Target Java contract")
     [:java :runtime-major] runtime-major
     (str "greater than or equal to :source-language-version "
          source-language-version)
     #(<= source-language-version %))
    (validation/check!
     (validation-context "Target Java contract")
     [:java :preview-features?] preview-features?
     "a boolean" boolean?))
  java)

(defn- validate-path-descriptors!
  [subject path expected-keys descriptors]
  (distinct-vector! subject path descriptors :id)
  (distinct-vector! subject path descriptors :path)
  (doseq [[index descriptor] (map-indexed vector descriptors)]
    (exact-keys! (str subject " entry") (conj path index)
                 expected-keys descriptor)
    (when-not (keyword? (:id descriptor))
      (fail! (str subject " identity must be a keyword")
             {:path (conj path index :id)
              :value (:id descriptor)
              :expected "a keyword"
              :descriptor descriptor}))
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
        (exact-keys! "Mapping overlay" [:mapping-overlays id]
                     mapping-overlay-keys overlay)
        (when-not (= mapping-overlay-schema-version (:schema-version overlay))
          (fail! "Mapping overlay has an unsupported schema version"
                 {:overlay id :actual (:schema-version overlay)}))
        (let [context
              (validation-context "Mapping overlay"
                                  {:overlay id})]
          (validation/agree! context
                             [:target :target] target
                             [:mapping-overlays id :target]
                             (:target overlay))
          (validation/agree! context
                             [:target :product-family] family
                             [:mapping-overlays id :product-family]
                             (:product-family overlay))
          (validation/agree! context
                             [:target :mapping-overlays id :id] id
                             [:mapping-overlays id :id]
                             (:id overlay)))
        (let [capabilities
              (keyword-set! "Mapping overlay capabilities"
                            [:mapping-overlays id :capabilities]
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
    (exact-keys! "Legal policy" [:legal-policy] legal-policy-keys policy)
    (when-not (= legal-policy-schema-version (:schema-version policy))
      (fail! "Legal policy has an unsupported schema version"
             {:actual (:schema-version policy)}))
    (when-not (= target (:target policy))
      (fail! "Legal policy identifies the wrong target"
             {:expected target :actual (:target policy)}))
    (let [allowed (:allowed-upstream-licenses policy)
          selected (:upstream-license policy)
          legal-sets (keyword-set! "Legal policy sets"
                                   [:legal-policy :legal-sets]
                                   (:legal-sets policy))
          profile-legal-sets (:profile-legal-sets policy)
          resource-notice-legal-sets
          (:resource-notice-legal-sets policy)
          notice-appendix (:notice-appendix baseline-record)
          notice-appendix-sha256 (:notice-appendix-sha256 policy)
          package-metadata (:package-metadata policy)
          baseline-sets (set (keys (:legal-sets baseline-record)))]
      (let [context (validation-context "Target legal policy")]
        (validation/check! context [:legal-policy :allowed-upstream-licenses]
                           allowed "a nonempty set of non-blank strings"
                           #(and (set? %) (seq %)))
        (doseq [license allowed]
          (validation/check! context
                             [:legal-policy :allowed-upstream-licenses license]
                             license "a non-blank string" non-blank-string?))
        (validation/check! context [:legal-policy :upstream-license]
                           selected
                           "a non-blank member of :allowed-upstream-licenses"
                           #(and (non-blank-string? %)
                                 (contains? allowed %)))
        (validation/agree!
         context
         [:baseline :upstream :license]
         (get-in baseline-record [:upstream :license])
         [:legal-policy :upstream-license]
         selected)
        (validation/agree! context
                           [:baseline :legal-sets] baseline-sets
                           [:legal-policy :legal-sets] legal-sets)
        (when (some? notice-appendix)
          (validation/check! context [:baseline :notice-appendix]
                             notice-appendix
                             "non-blank text without NUL characters"
                             non-blank-text?))
        (validation/check!
         context [:legal-policy :notice-appendix-sha256]
         notice-appendix-sha256
         "nil or a lowercase SHA-256 string"
         #(or (nil? %)
              (and (string? %)
                   (boolean (re-matches #"[0-9a-f]{64}" %)))))
        (validation/agree!
         context
         [:baseline :notice-appendix-sha256]
         (some-> notice-appendix util/sha256-text)
         [:legal-policy :notice-appendix-sha256]
         notice-appendix-sha256)
        (when notice-appendix-sha256
          (let [notice-entries
                (for [[legal-set entries] (:legal-sets baseline-record)
                      entry entries
                      :when (= :notice (:kind entry))]
                  [legal-set entry])]
            (validation/check!
             context [:baseline :legal-sets]
             notice-entries
             "at least one pinned NOTICE input for the translation appendix"
             seq)))
        (validation/exact-keys!
         context [:legal-policy :profile-legal-sets]
         profile-legal-sets profile-names profile-names)
        (validation/exact-keys!
         context [:legal-policy :resource-notice-legal-sets]
         resource-notice-legal-sets profile-names profile-names))
      (validation/exact-keys!
       (validation-context "Target package-metadata policy")
       [:legal-policy :package-metadata]
       package-metadata profile-names profile-names)
      (doseq [[profile sets] profile-legal-sets]
        (let [path [:legal-policy :profile-legal-sets profile]
              context
              (validation-context "Profile legal-set selection"
                                  {:profile profile
                                   :available (vec (sort legal-sets))})]
          (validation/check! context path sets
                             "a vector without duplicate identities"
                             #(and (vector? %)
                                   (= (count %) (count (distinct %)))))
          (doseq [[index legal-set] (map-indexed vector sets)]
            (validation/check! context (conj path index) legal-set
                               "a keyword" keyword?))
          (validation/check! context path sets
                             (str "a selection from "
                                  (vec (sort legal-sets)))
                             #(set/subset? (set %) legal-sets))))
      (doseq [[profile notice-sets] resource-notice-legal-sets]
        (let [path [:legal-policy :resource-notice-legal-sets profile]
              selected (set (get profile-legal-sets profile))
              context
              (validation-context
               "Production-resource NOTICE attribution"
               {:profile profile
                :selected-legal-sets (vec (sort selected))})]
          (validation/check! context path notice-sets
                             "a vector without duplicate legal-set identities"
                             #(and (vector? %)
                                   (= (count %) (count (distinct %)))))
          (doseq [[index legal-set] (map-indexed vector notice-sets)]
            (validation/check! context (conj path index) legal-set
                               "a keyword" keyword?))
          (validation/check! context path notice-sets
                             (str "a selection from the profile legal sets "
                                  (vec (sort selected)))
                             #(set/subset? (set %) selected))
          (doseq [legal-set notice-sets]
            (when-not
             (some #(= :notice (:kind %))
                   (get-in baseline-record [:legal-sets legal-set]))
              (fail!
               "Resource-attribution legal set has no pinned NOTICE input"
               {:profile profile
                :path path
                :legal-set legal-set})))))
      (doseq [[profile metadata] package-metadata]
        (let [path [:legal-policy :package-metadata profile]
              context
              (validation-context "Target package-metadata policy"
                                  {:profile profile})]
          (exact-keys! "Target package-metadata policy" path
                       package-metadata-policy-keys metadata)
          (doseq [field [:required-description-fragments
                         :forbidden-identity-marks]
                  :let [values (get metadata field)
                        field-path (conj path field)]]
            (validation/check! context field-path values
                               "a vector of distinct non-blank strings"
                               #(and (vector? %)
                                     (= (count %) (count (distinct %)))))
            (doseq [[index value] (map-indexed vector values)]
              (validation/check! context (conj field-path index)
                                 value "a non-blank string"
                                 non-blank-string?)))))
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
      (let [source
            (workspace-file!
             workspace-root
             (str "Baseline " (name legal-set) " legal source")
             (:source entry))
            expected (or (:source-sha256 entry) (:sha256 entry))
            actual (util/sha256-file source)]
        (when-not (= expected actual)
          (fail! "Baseline legal source differs from its pinned digest"
                 {:target target
                  :legal-set legal-set
                  :legal-kind (:kind entry)
                  :path (str source)
                  :expected expected
                  :actual actual})))
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
       (keyword-set! (str "Destination " (name field)) [field] value)
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
            (keyword-set! "Runtime asset capabilities"
                          [:runtime-assets id :capabilities] capabilities)
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
        (let [context
              (validation-context "Destination configuration"
                                  {:destination id})]
          (validation/check! context
                             [:destinations id :schema-version]
                             (:schema-version destination)
                             "the integer 1" #{1})
          (validation/agree! context
                             [:target :product-family] family
                             [:destinations id :product-family]
                             (:product-family destination))
          (validation/agree! context
                             [:target :target] target
                             [:destinations id :baseline-target]
                             (:baseline-target destination))
          (validation/check! context
                             [:destinations id :baseline-profile]
                             baseline-profile
                             "a keyword naming a baseline profile"
                             keyword?)
          (validation/check! context
                             [:destinations id :baseline-profile]
                             baseline-profile
                             (str "one of " (vec (sort (keys (:profiles
                                                              baseline-record)))))
                             #(contains? (:profiles baseline-record) %)))
        (let [package-id (get-in destination [:package :id])
              assembly-name (get-in destination [:project :assembly-name])
              target-framework (get-in destination [:project :target-framework])
              context
              (validation-context "Destination configuration"
                                  {:destination id})]
          (validation/agree!
           context
           [:baseline :profiles baseline-profile :package-id]
           (:package-id baseline-contract)
           [:destinations id :package :id]
           package-id)
          (validation/check! context
                             [:destinations id :project :assembly-name]
                             assembly-name "a non-blank string"
                             non-blank-string?)
          (validation/check! context
                             [:destinations id :project :target-framework]
                             target-framework "a non-blank string"
                             non-blank-string?))
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

(defn- validate-package-metadata-policy!
  [profile destination-id destination policy]
  (let [package (:package destination)
        description (:description package)
        authors (:authors package)
        required-fragments (:required-description-fragments policy)
        forbidden-marks (:forbidden-identity-marks policy)
        identity-fields
        (concat
         [[[:package :id] (:id package)]
          [[:package :title] (:title package)]
          [[:package :authors] (:authors package)]
          [[:project :assembly-name]
           (get-in destination [:project :assembly-name])]
          [[:project :root-namespace]
           (get-in destination [:project :root-namespace])]]
         (map (fn [[source value]]
                [[:namespaces source] value])
              (:namespaces destination))
         (map (fn [[source value]]
                [[:namespace-prefixes source] value])
              (:namespace-prefixes destination)))]
    (let [context
          (validation-context "Package legal metadata"
                              {:profile profile
                               :destination destination-id})]
      (validation/check!
       context
       [:destinations destination-id :package :authors]
       authors
       "a non-blank single-line publisher identity"
       non-blank-string?)
      (validation/check!
       context
       [:destinations destination-id :package :description]
       description
       "non-blank text without NUL characters"
       non-blank-text?))
    (doseq [fragment required-fragments]
      (when-not (and (non-blank-string? description)
                     (str/includes? description fragment))
        (fail! "Package description omits required legal attribution text"
               {:profile profile
                :destination destination-id
                :path [:destinations destination-id :package :description]
                :fragment fragment
                :actual description})))
    (doseq [mark forbidden-marks
            [path value] identity-fields
            :when (and (non-blank-string? value)
                       (str/includes? (str/lower-case value)
                                      (str/lower-case mark)))]
      (fail! "Package product or publisher identity contains a forbidden upstream-owner mark"
             {:profile profile
              :destination destination-id
              :path (into [:destinations destination-id] path)
              :mark mark
              :actual value}))))

(defn- profile-authorship!
  [workspace-root target family profile-id descriptor destination
   runtime-records authorship-contracts]
  (let [selection (:authorship descriptor)]
    (exact-keys! "Profile authorship contract"
                 [:profiles profile-id :authorship]
                 profile-authorship-keys selection)
    (exact-keys! "Profile authorship budget"
                 [:profiles profile-id :authorship :budget]
                 authorship-budget-keys (:budget selection))
    (let [{:keys [sources evidence review budget]} selection
          available-destination-sources
          (get-in authorship-contracts [:destination :sources])
          available-third-party-sources
          (get-in authorship-contracts [:third-party :sources])
          source-ids
          (set (concat (keys available-destination-sources)
                       (keys available-third-party-sources)))
          context
          (validation-context "Profile authorship selections"
                              {:profile profile-id
                               :available-sources (vec (sort source-ids))})
          sources-path [:profiles profile-id :authorship :sources]
          evidence-path [:profiles profile-id :authorship :evidence]
          _sources-shape
          (validation/check! context sources-path sources
                             "a vector without duplicate identities"
                             #(and (vector? %)
                                   (= (count %) (count (distinct %)))))
          _source-items
          (doseq [[index source] (map-indexed vector sources)]
            (validation/check! context (conj sources-path index) source
                               "a qualified keyword" qualified-keyword?))
          _source-selection
          (validation/check! context sources-path sources
                             (str "a selection from "
                                  (vec (sort source-ids)))
                             #(set/subset? (set %) source-ids))
          _evidence-shape
          (validation/check! context evidence-path evidence
                             "a nonempty vector without duplicate identities"
                             #(and (vector? %) (seq %)
                                   (= (count %) (count (distinct %)))))
          _evidence-items
          (doseq [[index evidence-id] (map-indexed vector evidence)]
            (validation/check! context (conj evidence-path index) evidence-id
                               "a keyword" keyword?))
          _review
          (validation/check! context
                             [:profiles profile-id :authorship :review]
                             review "a non-blank string" non-blank-string?)
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
          third-party-sources
          (select-keys available-third-party-sources sources)
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
           :destination-sources destination-sources
           :third-party-sources third-party-sources}]
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
                  [[:mapping-overlays mapping-ids (set (keys overlay-records))]
                   [:runtime-assets runtime-ids (set (keys asset-records))]
                   [:validation-contracts validation-ids
                    validation-descriptor-ids]]]
              (when-not (non-blank-string? id)
                (validation/fail!
                 (validation-context "Target profile descriptor")
                 [:profiles :id] id "a non-blank string"))
              (doseq [[field values available] selections]
                (let [path [:profiles id field]
                      context
                      (validation-context "Target profile descriptor"
                                          {:profile id
                                           :available (vec (sort available))})]
                  (validation/check! context path values
                                     "a vector of distinct keywords" vector?)
                  (when-not (= (count values) (count (distinct values)))
                    (validation/fail! context path values
                                      "a vector without duplicate identities"))
                  (doseq [[index value] (map-indexed vector values)]
                    (validation/check! context (conj path index) value
                                       "a keyword" keyword?))
                  (let [unknown (set/difference (set values) available)]
                    (when (seq unknown)
                      (validation/fail!
                       context path values
                       (str "a selection from " (vec (sort available))))))))
              (keyword-set! "Profile required capabilities"
                            [:profiles id :required-capabilities]
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
                (let [context
                      (validation-context "Generation profile"
                                          {:profile id})]
                  (validation/check! context
                                     [:profiles id :schema-version]
                                     (:schema-version profile)
                                     "the integer 1" #{1})
                  (validation/agree! context
                                     [:target :profiles id :id] id
                                     [:profiles id :profile]
                                     (:profile profile))
                  (validation/agree! context
                                     [:target :product-family] family
                                     [:profiles id :product-family]
                                     (:product-family profile))
                  (validation/agree! context
                                     [:target :target] target
                                     [:profiles id :baseline-target]
                                     (:baseline-target profile))
                  (validation/check! context
                                     [:profiles id :baseline-profile]
                                     baseline-profile
                                     "a keyword naming a baseline profile"
                                     keyword?)
                  (validation/check! context
                                     [:profiles id :baseline-profile]
                                     baseline-profile
                                     (str "one of "
                                          (vec
                                           (sort
                                            (keys (:profiles baseline-record)))))
                                     #(contains? (:profiles baseline-record) %))
                  (validation/agree!
                   context
                   [:target :profiles id :id] id
                   [:baseline :profiles baseline-profile :profile]
                   (:profile baseline-contract))
                  (validation/agree!
                   context
                   [:target :profiles id :destination]
                   (:path (:descriptor destination-record))
                   [:profiles id :destination-config]
                   (:destination-config profile))
                  (validation/agree!
                   context
                   [:destinations destination :destination-bundle]
                   (:destination-bundle destination-config)
                   [:profiles id :destination-bundle]
                   (:destination-bundle profile))
                  (validation/check! context
                                     [:profiles id :destination-bundle]
                                     (:destination-bundle profile)
                                     "a namespace-qualified symbol"
                                     qualified-symbol?))
                (let [dependencies (or (:dependency-profiles profile) [])
                      context
                      (validation-context "Generation profile"
                                          {:profile id})
                      path [:profiles id :dependency-profiles]]
                  (validation/check! context path dependencies
                                     "a vector of distinct profile names"
                                     vector?)
                  (when-not (= (count dependencies)
                               (count (distinct dependencies)))
                    (validation/fail! context path dependencies
                                      "a vector without duplicate names"))
                  (doseq [[index dependency]
                          (map-indexed vector dependencies)]
                    (validation/check! context (conj path index) dependency
                                       "a non-blank string"
                                       non-blank-string?))
                  (when (contains? (set dependencies) id)
                    (validation/fail! context path dependencies
                                      "a vector that excludes its own profile")))
                (when (and (contains? profile :gradle-java-major)
                           (not= (:runtime-major java)
                                 (:gradle-java-major profile)))
                  (validation/agree!
                   (validation-context "Generation profile"
                                       {:profile id})
                   [:target :java :runtime-major] (:runtime-major java)
                   [:profiles id :gradle-java-major]
                   (:gradle-java-major profile)))
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
                      resource-notice-legal-sets
                      (get-in legal-policy
                              [:contract :resource-notice-legal-sets id])
                      destination-legal-sets
                      (vec (or (:baseline-legal-sets destination-config) []))]
                  (when (seq missing)
                    (fail! "Target profile requires unavailable capabilities"
                           {:profile id
                            :missing (vec (sort missing))
                            :provided (vec (sort provided))}))
                  (validation/agree!
                   (validation-context
                    "Destination runtime-source contract"
                    {:profile id :destination destination})
                   [:target :profiles id :runtime-assets]
                   selected-runtime-paths
                   [:destinations destination :runtime-sources]
                   (vec (sort configured-runtime)))
                  (validation/agree!
                   (validation-context
                    "Destination legal-set contract"
                    {:profile id :destination destination})
                   [:legal-policy :profile-legal-sets id]
                   policy-legal-sets
                   [:destinations destination :baseline-legal-sets]
                   destination-legal-sets)
                  (validate-package-metadata-policy!
                   id destination destination-config
                   (get-in legal-policy
                           [:contract :package-metadata id]))
                  [id {:descriptor descriptor
                       :path file
                       :configuration profile
                       :destination destination-record
                       :mapping-overlays selected-mappings
                       :runtime-assets selected-runtime
                       :resource-notice-legal-sets
                       resource-notice-legal-sets
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
  (let [path [:validation area :sources]
        context (validation-context (str subject " sources"))]
    (validation/check! context path sources
                       "a nonempty vector without duplicate paths"
                       #(and (vector? %) (seq %)
                             (= (count %) (count (distinct %)))))
    (doseq [[index source] (map-indexed vector sources)]
      (validation/check! context (conj path index) source
                         "a non-blank string" non-blank-string?)))
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
  [subject path values available ladder-id]
  (let [context
        (validation-context (str "Proof ladder " subject)
                            {:ladder ladder-id
                             :available (vec (sort available))})]
    (validation/check! context path values
                       "a nonempty vector without duplicate identities"
                       #(and (vector? %) (seq %)
                             (= (count %) (count (distinct %)))))
    (validation/check! context path values
                       (str "a selection from " (vec (sort available)))
                       #(set/subset? (set %) available)))
  values)

(defn- load-proof!
  [target family proof profiles validations]
  (exact-keys! "Target proof contract" [:proof] proof-keys proof)
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
    (distinct-vector! "Target proof ladders" [:proof :ladders] ladders :id)
    (when (empty? ladders)
      (fail! "Target proof contract must declare at least one required ladder"
             {:target target}))
    (let [loaded
          (mapv
           (fn [index
                {:keys [id kind profiles validation-contracts resource-class
                        runner]
                 :as ladder}]
             (exact-keys!
              "Target proof ladder"
              [:proof :ladders index]
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
             (proof-selection! "profiles"
                               [:proof :ladders index :profiles]
                               profiles profile-ids id)
             (proof-selection! "validation contracts"
                               [:proof :ladders index :validation-contracts]
                               validation-contracts validation-ids id)
             (cond-> ladder
               (= :custom kind)
               (assoc :runner (resolve-custom-runner! runner id))))
           (range)
           ladders)
          selected-profiles (mapcat :profiles loaded)
          selected-validations (mapcat :validation-contracts loaded)]
      (let [context (validation-context "Required proof ladder coverage"
                                        {:target target})]
        (validation/check!
         context [:proof :ladders :profiles] (vec selected-profiles)
         "each target profile exactly once"
         #(and (= (count %) (count (distinct %)))
               (= profile-ids (set %))))
        (validation/check!
         context [:proof :ladders :validation-contracts]
         (vec selected-validations)
         "each target validation exactly once"
         #(and (= (count %) (count (distinct %)))
               (= validation-ids (set %)))))
      {:role role :ladders loaded})))

(defn- validate-authorship-evidence!
  [target profiles proof]
  (let [ladders (into {} (map (juxt :id identity)) (:ladders proof))]
    (doseq [[profile-id {:keys [authorship]}] profiles
            [evidence-index evidence-id]
            (map-indexed vector (:evidence authorship))]
      (let [ladder (get ladders evidence-id)]
        (validation/check!
         (validation-context
          "Profile authorship evidence"
          {:target target
           :profile profile-id
           :evidence evidence-id
           :available
           (vec
            (sort
             (for [[id candidate] ladders
                   :when (contains? (set (:profiles candidate)) profile-id)]
               id)))})
         [:profiles profile-id :authorship :evidence evidence-index]
         evidence-id
         "a required proof ladder that covers this profile"
         (fn [_]
           (and ladder
                (contains? (set (:profiles ladder)) profile-id))))))
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
    (let [context
          (validation-context "Differential validation contract"
                              {:validation id :profile profile-name})]
      (validation/agree! context
                         [:target :validation-contracts id :id] id
                         [:validation-contracts id :id] (:id contract))
      (validation/agree! context
                         [:target :target] target
                         [:validation-contracts id :target] (:target contract))
      (validation/check! context
                         [:validation-contracts id :runner :profile]
                         profile-name
                         (str "one of " (vec (sort (keys profiles))))
                         #(contains? profiles %))
      (validation/agree!
       context
       [:profiles profile-name :baseline-profile]
       (get-in profile-record [:configuration :baseline-profile])
       [:validation-contracts id :baseline-profile]
       (:baseline-profile contract))
      (validation/check!
       context
       [:target :profiles profile-name :validation-contracts]
       (get-in profile-record [:descriptor :validation-contracts])
       (str "a selection containing " id)
       #(contains? (set %) id)))
    (validation-source!
     target-root "Validation oracle" "oracle"
     (get-in contract [:runner :oracle :source]) ".java")
    (validation-source!
     target-root "Validation probe" "probe"
     (get-in contract [:runner :probe :source]) ".cs")
    (let [context
          (validation-context "Differential validation package contract"
                              {:validation id :profile profile-name})]
      (validation/agree!
       context
       [:legal-policy :profile-legal-sets profile-name]
       profile-legal-sets
       [:validation-contracts id :package-contract :legal-sets]
       (get-in contract [:package-contract :legal-sets]))
      (validation/agree!
       context
       [:destinations
        (get-in profile-record [:descriptor :destination])
        :project :assembly-name]
       (get-in destination [:project :assembly-name])
       [:validation-contracts id :package-contract :assembly-name]
       (get-in contract [:package-contract :assembly-name]))
      (validation/agree!
       context
       [:destinations
        (get-in profile-record [:descriptor :destination])
        :project :target-framework]
       (get-in destination [:project :target-framework])
       [:validation-contracts id :package-contract :target-framework]
       (get-in contract [:package-contract :target-framework])))
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
    (exact-keys! "Custom validation contract"
                 [:validation-contracts id]
                 custom-validation-keys contract)
    (when-not (= 1 (:schema-version contract))
      (fail! "Custom validation contract has an unsupported schema version"
             {:validation id :actual (:schema-version contract)}))
    (let [context
          (validation-context "Custom validation contract"
                              {:validation id :profile profile-name})]
      (validation/agree! context
                         [:target :validation-contracts id :id] id
                         [:validation-contracts id :id] (:id contract))
      (validation/agree! context
                         [:target :target] target
                         [:validation-contracts id :target] (:target contract))
      (validation/check! context
                         [:validation-contracts id :profile]
                         profile-name
                         (str "one of " (vec (sort (keys profiles))))
                         #(contains? profiles %))
      (validation/agree!
       context
       [:profiles profile-name :baseline-profile]
       (get-in profile-record [:configuration :baseline-profile])
       [:validation-contracts id :baseline-profile]
       (:baseline-profile contract))
      (validation/check!
       context
       [:target :profiles profile-name :validation-contracts]
       (get-in profile-record [:descriptor :validation-contracts])
       (str "a selection containing " id)
       #(contains? (set %) id)))
    (validate-custom-sources! target-root "Validation oracle" "oracle" ".java"
                              (:oracle-sources contract))
    (validate-custom-sources! target-root "Validation probe" "probe" ".cs"
                              (:probe-sources contract))
    (validation/agree!
     (validation-context "Custom validation legal-set contract"
                         {:validation id :profile profile-name})
     [:legal-policy :profile-legal-sets profile-name]
     (get-in legal-policy [:contract :profile-legal-sets profile-name])
     [:validation-contracts id :legal-sets]
     legal-sets)
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
     (when-not (and (paths/real-contained? workspace-root target-root)
                    (paths/real-contained? workspace-root manifest-file))
       (fail! "Target manifest resolves outside the workspace"
              {:target requested-target
               :path (str manifest-file)
               :reason :outside-workspace}))
     (let [manifest (read-edn! "Target manifest" manifest-file)]
       (exact-keys! "Target manifest" [] manifest-keys manifest)
       (validation/check!
        (validation-context "Target manifest")
        [:schema-version] (:schema-version manifest)
        (str "the supported schema version " schema-version)
        #{schema-version})
       (let [target (target-id (:target manifest))
             family (:product-family manifest)
             java (validate-java! (:java manifest))
             declared-capabilities
             (keyword-set! "Target capabilities" [:capabilities]
                           (:capabilities manifest))]
         (validation/agree!
          (validation-context "Target manifest identity")
          [:target-directory] requested-target
          [:target] target)
         (validation/check!
          (validation-context "Target manifest")
          [:product-family] family "a keyword" keyword?)
         (validate-documents! workspace-root (:contracts manifest))
         (validate-path-descriptors! "Target destinations" [:destinations]
                                     path-descriptor-keys
                                     (:destinations manifest))
         (validate-path-descriptors! "Target mapping overlays"
                                     [:mapping-overlays]
                                     path-descriptor-keys
                                     (:mapping-overlays manifest))
         (validate-path-descriptors! "Target runtime assets" [:runtime-assets]
                                     runtime-descriptor-keys
                                     (:runtime-assets manifest))
         (validate-path-descriptors! "Target validation contracts"
                                     [:validation-contracts]
                                     validation-descriptor-keys
                                     (:validation-contracts manifest))
         (distinct-vector! "Target profiles" [:profiles]
                           (:profiles manifest) :id)
         (distinct-vector! "Target profiles" [:profiles]
                           (:profiles manifest) :path)
         (when (empty? (:profiles manifest))
           (fail! "Target directory must declare at least one profile"
                  {:target target}))
         (doseq [[index descriptor]
                 (map-indexed vector (:profiles manifest))]
           (exact-keys! "Target profile descriptor"
                        [:profiles index]
                        profile-descriptor-keys descriptor)
           (relative-path! "Target profile path" (:path descriptor)))
         (let [baseline (load-baseline! workspace-root target-root target
                                        (:baseline manifest))
               baseline-record (:record baseline)]
           (let [manifest-profiles (set (map :id (:profiles manifest)))
                 baseline-profiles
                 (set (map :profile (vals (:profiles baseline-record))))]
             (validation/agree!
              (validation-context "Target profile contract"
                                  {:target target})
              [:baseline :profiles] baseline-profiles
              [:profiles] manifest-profiles))
           (validation/agree!
            (validation-context "Target Java language contract"
                                {:target target})
            [:baseline :upstream :java-language-version]
            (get-in baseline-record [:upstream :java-language-version])
            [:java :source-language-version]
            (:source-language-version java))
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
                 selected-contracted-sources
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
              "target contracted sources"
              (set
               (concat
                (keys
                 (get-in authorship-contracts
                         [:destination :sources]))
                (keys
                 (get-in authorship-contracts
                         [:third-party :sources]))))
              selected-contracted-sources)
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
