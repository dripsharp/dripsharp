(ns dripsharp.baseline
  "Authoritative, reviewed upstream baselines for product targets.

  Baselines own pins and derived expectations. Product goals, exclusions, and
  completion rules are deliberately outside this namespace and outside the
  files that the re-baseline workflow may write."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.paths :as paths]
            [dripsharp.project-xml :as project-xml])
  (:import [java.io PushbackReader StringReader]))

(def baseline-files
  {:pkl "targets/pkl/baseline.edn"
   :pdfcube "targets/pdfcube/baseline.edn"
   :rawhttp "targets/rawhttp/baseline.edn"})

(def ^:dynamic *target-records*
  "Validated target-directory baseline records available to the current
  execution. Generic target workflows bind this map before loading a
  target-owned rule bundle or running a later stage, so existing baseline
  consumers use the preflighted record instead of reopening configuration."
  {})

(def ^:private baseline-required-keys
  #{:schema-version :target :upstream :artifacts :legal-sets :packages :profiles})
(def ^:private baseline-allowed-keys
  (into baseline-required-keys [:notice-appendix :contracts]))
(def ^:private upstream-required-keys
  #{:name :version :repository :revision :license :java-language-version})
(def ^:private upstream-allowed-keys
  (conj upstream-required-keys :notice-reference))
(def ^:private legal-entry-required-keys
  #{:kind :source :destination :package-path :sha256})
(def ^:private legal-entry-allowed-keys
  (conj legal-entry-required-keys :source-sha256))
(def ^:private package-required-keys #{:version})
(def ^:private package-allowed-keys
  (conj package-required-keys :assembly-version))
(def ^:private profile-required-keys
  #{:profile :source-module :package-id :source-counts :public-contract-rows})
(def ^:private profile-allowed-keys
  (into profile-required-keys
        [:maven-selector :source-project-id :source-project-dependencies
         :public-contract-status]))
(def ^:private source-count-required-keys #{:ordinary :generated})

(defn target-key
  [target]
  (let [target (if (keyword? target) target (keyword (str target)))]
    (when-not (and (simple-keyword? target)
                   (re-matches #"[a-z][a-z0-9-]*" (name target)))
      (throw (ex-info "Invalid product baseline target"
                      {:kind :invalid-baseline-target
                       :target target})))
    target))

(defn baseline-path
  [workspace-root target]
  (let [target (target-key target)]
    (paths/resolve-path
     (paths/absolute workspace-root)
     (or (get baseline-files target)
         (str "targets/" (name target) "/baseline.edn")))))

(defn- non-blank-string?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- non-blank-single-line?
  [value]
  (and (non-blank-string? value)
       (not
        (re-find
         #"[\u0000\u000B\u000C\r\n\u0085\u2028\u2029]"
         value))))

(defn- non-blank-single-line-xml-path?
  [value]
  (and (non-blank-single-line? value)
       (not (str/includes? value "\t"))
       (project-xml/valid-text? value)))

(defn- windows-reserved-path-component?
  [component]
  (let [basename (first (str/split component #"\." 2))]
    (boolean
     (re-matches
      #"(?i:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])"
      basename))))

(defn- portable-path-component?
  [component]
  (and (non-blank-string? component)
       (not (contains? #{"." ".."} component))
       (not (re-find #"[<>:\"|?*\u0000-\u001F]" component))
       (not (re-find #"[. ]$" component))
       (not (windows-reserved-path-component? component))))

(defn- normalized-portable-relative-path?
  [value]
  (when (non-blank-single-line-xml-path? value)
    (let [components (str/split value #"/" -1)]
      (and (not (str/includes? value "\\"))
           (not (str/starts-with? value "/"))
           (every? portable-path-component? components)))))

(defn- exact-keys?
  [value required allowed]
  (and (map? value)
       (every? #(contains? value %) required)
       (every? allowed (keys value))))

(defn- sha256?
  [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- invalid!
  [message data]
  (throw (ex-info message (assoc data :kind :invalid-target-baseline))))

(defn- read-single-edn!
  [target file]
  (let [eof (Object.)
        [record trailing]
        (try
          (with-open [reader
                      (PushbackReader.
                       (StringReader. (slurp (str file))))]
            [(edn/read {:eof eof} reader)
             (edn/read {:eof eof} reader)])
          (catch RuntimeException error
            (throw
             (ex-info "Target baseline file is not valid EDN"
                      {:kind :invalid-target-baseline
                       :target target
                       :path (str file)
                       :reason :invalid-edn}
                      error))))]
    (when (identical? eof record)
      (invalid! "Target baseline file is empty"
                {:target target
                 :path (str file)
                 :reason :empty-edn}))
    (when-not (identical? eof trailing)
      (invalid! "Target baseline file contains trailing EDN data"
                {:target target
                 :path (str file)
                 :reason :trailing-data}))
    record))

(defn validate-record!
  "Validates a baseline record for an already validated target identity.

  Unlike `validate!`, this entry point does not consult the legacy baseline
  file registry. Target-directory discovery uses it so a new target can
  validate its owned baseline without changing generic source."
  [expected-target record]
  (when-not (keyword? expected-target)
    (invalid! "Target baseline requires a keyword target identity"
              {:target expected-target}))
  (when-not
   (exact-keys? record baseline-required-keys baseline-allowed-keys)
    (invalid! "Target baseline has invalid top-level fields"
              {:target expected-target
               :actual (when (map? record) (set (keys record)))}))
  (let [expected-target expected-target
        upstream (:upstream record)
        packages (:packages record)
        profiles (:profiles record)
        legal-sets (:legal-sets record)
        artifacts (:artifacts record)]
    (when-not (= 1 (:schema-version record))
      (invalid! "Target baseline has an unsupported schema version"
                {:target expected-target :actual (:schema-version record)}))
    (when-not (= expected-target (:target record))
      (invalid! "Target baseline identifies the wrong target"
                {:expected expected-target :actual (:target record)}))
    (when-not (and (exact-keys? upstream upstream-required-keys upstream-allowed-keys)
                   (every? #(non-blank-single-line? (get upstream %))
                           [:name :version :repository :revision :license])
                   (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}"
                               (:revision upstream))
                   (pos-int? (:java-language-version upstream))
                   (or (nil? (:notice-reference upstream))
                       (non-blank-single-line?
                        (:notice-reference upstream))))
      (invalid! "Target baseline has invalid upstream identity metadata"
                {:target expected-target :upstream upstream}))
    (when-not (and (map? artifacts)
                   (every? non-blank-string? (keys artifacts))
                   (every? sha256? (vals artifacts)))
      (invalid! "Target baseline has invalid artifact hashes"
                {:target expected-target :artifacts artifacts}))
    (when-not (and (map? legal-sets)
                   (every? keyword? (keys legal-sets))
                   (every?
                    (fn [entries]
                      (and (vector? entries)
                           (every?
                            (fn [entry]
                              (and (exact-keys? entry
                                                legal-entry-required-keys
                                                legal-entry-allowed-keys)
                                   (contains? #{:license :notice} (:kind entry))
                                   (every? #(normalized-portable-relative-path?
                                             (get entry %))
                                           [:source :destination :package-path])
                                   (sha256? (:sha256 entry))
                                   (or
                                    (not (contains? entry :source-sha256))
                                    (sha256? (:source-sha256 entry)))))
                            entries)))
                    (vals legal-sets)))
      (invalid! "Target baseline has invalid legal-file contracts"
                {:target expected-target :legal-sets legal-sets}))
    (when-not (and (map? packages)
                   (seq packages)
                   (every? non-blank-string? (keys packages))
                   (every?
                    (fn [[_ package]]
                      (and (exact-keys? package package-required-keys package-allowed-keys)
                           (non-blank-string? (:version package))
                           (or (nil? (:assembly-version package))
                               (non-blank-string? (:assembly-version package)))))
                    packages))
      (invalid! "Target baseline has invalid package versions"
                {:target expected-target :packages packages}))
    (when-not
     (and
      (map? profiles)
      (seq profiles)
      (every? keyword? (keys profiles))
      (every?
       (fn [[_ profile]]
         (let [counts (:source-counts profile)]
           (and (exact-keys? profile profile-required-keys profile-allowed-keys)
                (every? #(non-blank-string? (get profile %))
                        [:profile :source-module :package-id])
                (contains? packages (:package-id profile))
                (exact-keys? counts source-count-required-keys source-count-required-keys)
                (= #{:ordinary :generated} (set (keys counts)))
                (every? #(and (integer? %) (not (neg? %))) (vals counts))
                (or
                 (and (nil? (:public-contract-status profile))
                      (pos-int? (:public-contract-rows profile)))
                 (and (= :pending (:public-contract-status profile))
                      (nil? (:public-contract-rows profile))))
                (or (nil? (:source-project-id profile))
                    (non-blank-string? (:source-project-id profile)))
                (or (nil? (:source-project-dependencies profile))
                    (and (vector? (:source-project-dependencies profile))
                         (every? non-blank-string?
                                 (:source-project-dependencies profile)))))))
       profiles))
      (invalid! "Target baseline has invalid profile expectations"
                {:target expected-target :profiles profiles}))
    record))

(defn validate!
  [expected-target record]
  (validate-record! (target-key expected-target) record))

(defn read-baseline
  ([target]
   (read-baseline (paths/workspace-root) target))
  ([workspace-root target]
   (let [target (target-key target)]
     (if-let [record (get *target-records* target)]
       (validate-record! target record)
       (let [file (baseline-path workspace-root target)]
         (when-not (paths/regular-file? file)
           (throw (ex-info "Target baseline file is missing"
                           {:kind :missing-target-baseline
                            :target target :path (str file)})))
         (validate! target (read-single-edn! target file)))))))

(defn upstream
  ([target] (:upstream (read-baseline target)))
  ([workspace-root target] (:upstream (read-baseline workspace-root target))))

(defn upstream-version
  [target]
  (:version (upstream target)))

(defn upstream-revision
  [target]
  (:revision (upstream target)))

(defn java-language-version
  [target]
  (:java-language-version (upstream target)))

(defn mechanical-source
  ([target] (mechanical-source (paths/workspace-root) target))
  ([workspace-root target]
   (let [{:keys [repository revision notice-reference]}
         (:upstream (read-baseline workspace-root target))]
     {:repository repository
      :revision revision
      :notice-reference notice-reference})))

(defn profile
  ([target profile-key] (profile (paths/workspace-root) target profile-key))
  ([workspace-root target profile-key]
   (let [record (read-baseline workspace-root target)
         value (get-in record [:profiles profile-key])]
     (when-not value
       (throw (ex-info "Target baseline has no such profile"
                       {:kind :unknown-baseline-profile
                        :target (target-key target)
                        :profile profile-key
                        :available (vec (keys (:profiles record)))})))
     value)))

(defn profile-by-name
  ([target profile-name]
   (profile-by-name (paths/workspace-root) target profile-name))
  ([workspace-root target profile-name]
   (let [record (read-baseline workspace-root target)
         matches (filter #(= profile-name (:profile (val %)))
                         (:profiles record))]
     (when-not (= 1 (count matches))
       (throw (ex-info "Target baseline profile name is missing or ambiguous"
                       {:kind :unknown-baseline-profile-name
                        :target (target-key target)
                        :profile profile-name})))
     (val (first matches)))))

(defn profile-by-source-module
  ([target source-module]
   (profile-by-source-module (paths/workspace-root) target source-module))
  ([workspace-root target source-module]
   (let [record (read-baseline workspace-root target)
         matches (filter #(= source-module (:source-module (val %)))
                         (:profiles record))]
     (when-not (= 1 (count matches))
       (throw (ex-info "Target baseline source module is missing or ambiguous"
                       {:kind :unknown-baseline-source-module
                        :target (target-key target)
                        :source-module source-module})))
     (val (first matches)))))

(defn package
  ([target package-id] (package (paths/workspace-root) target package-id))
  ([workspace-root target package-id]
   (let [record (read-baseline workspace-root target)
         value (get-in record [:packages package-id])]
     (when-not value
       (throw (ex-info "Target baseline has no such package"
                       {:kind :unknown-baseline-package
                        :target (target-key target)
                        :package-id package-id
                        :available (vec (sort (keys (:packages record))))})))
     value)))

(defn package-version
  [target package-id]
  (:version (package target package-id)))

(defn assembly-version
  [target package-id]
  (:assembly-version (package target package-id)))

(defn legal-files
  ([target legal-set-keys]
   (legal-files (paths/workspace-root) target legal-set-keys))
  ([workspace-root target legal-set-keys]
   (let [record (read-baseline workspace-root target)]
     (mapv
      identity
      (mapcat
       (fn [legal-set-key]
         (or (get-in record [:legal-sets legal-set-key])
             (throw (ex-info "Target baseline has no such legal-file set"
                             {:kind :unknown-baseline-legal-set
                              :target (target-key target)
                              :legal-set legal-set-key}))))
       legal-set-keys)))))

(defn package-legal-files
  [target legal-set-keys]
  (mapv (fn [{:keys [kind package-path sha256]}]
          {:kind kind :path package-path :sha256 sha256})
        (legal-files target legal-set-keys)))

(defn artifact-sha256
  ([target coordinate]
   (artifact-sha256 (paths/workspace-root) target coordinate))
  ([workspace-root target coordinate]
   (or (get-in (read-baseline workspace-root target)
               [:artifacts coordinate])
       (throw (ex-info "Target baseline has no hash for an external artifact"
                       {:kind :missing-baseline-artifact
                        :target (target-key target)
                        :coordinate coordinate})))))

(defn hydrate-profile
  [workspace-root configuration]
  (if-let [target (:baseline-target configuration)]
    (let [profile-key (:baseline-profile configuration)
          contract (profile workspace-root target profile-key)
          source-project-id (:source-project-id contract)]
      (cond->
       (-> configuration
           (dissoc :baseline-target :baseline-profile)
           (assoc :revision
                  (get-in (read-baseline workspace-root target)
                          [:upstream :revision])))
        source-project-id
        (assoc :maven-project-id source-project-id)))
    configuration))

(defn- hydrate-artifacts
  [workspace-root target dependencies]
  (into
   (empty dependencies)
   (map (fn [[coordinate dependency]]
          [coordinate
           (assoc dependency :artifact-sha256
                  (artifact-sha256 workspace-root target coordinate))]))
   dependencies))

(defn hydrate-destination
  [workspace-root configuration]
  (if-let [target (:baseline-target configuration)]
    (let [profile-key (:baseline-profile configuration)
          legal-set-keys (:baseline-legal-sets configuration)
          record (read-baseline workspace-root target)
          profile-contract (profile workspace-root target profile-key)
          package-id (get-in configuration [:package :id])
          package-contract (package workspace-root target package-id)
          external (:external-dependencies configuration)]
      (cond->
       (-> configuration
           (dissoc :baseline-target :baseline-profile :baseline-legal-sets)
           (assoc :mechanical-source (mechanical-source workspace-root target))
           (assoc-in [:package :version] (:version package-contract)))

        (:source-project-id profile-contract)
        (assoc :source-project-id (:source-project-id profile-contract))

        (contains? profile-contract :source-project-dependencies)
        (assoc :project-dependencies
               (:source-project-dependencies profile-contract))

        external
        (assoc :external-dependencies
               (hydrate-artifacts workspace-root target external))

        (seq legal-set-keys)
        (assoc :legal-files
               (legal-files workspace-root target legal-set-keys))

        (and (seq legal-set-keys) (:notice-appendix record))
        (assoc :notice-appendix (:notice-appendix record))))
    configuration))

(defn validate-project-input!
  "Checks live discovery against the target's reviewed source counts and Java
  language level. This is intentionally independent of generated declaration
  and behavior completeness gates."
  [workspace-root target profile-name project-input]
  (let [record (read-baseline workspace-root target)
        contract (profile-by-name workspace-root target profile-name)
        expected (:source-counts contract)
        actual {:ordinary (count (:production-sources project-input))
                :generated
                (count (:generated-production-sources project-input))}
        expected-java (get-in record [:upstream :java-language-version])
        actual-java (get-in project-input [:java-toolchain :release])]
    (when-not (= expected actual)
      (throw (ex-info "Discovered production source counts differ from the reviewed target baseline"
                      {:kind :baseline-source-count-drift
                       :target (target-key target)
                       :profile profile-name
                       :expected expected :actual actual})))
    (when-not (= expected-java actual-java)
      (throw (ex-info "Discovered Java language version differs from the reviewed target baseline"
                      {:kind :baseline-java-language-version-drift
                       :target (target-key target)
                       :profile profile-name
                       :expected expected-java :actual actual-java})))
    project-input))
