(ns dripsharp.java-project
  "Product-neutral Java-to-C# project emission.

  The emitter owns deterministic scheduling, declaration and source accounting,
  collision detection, project/resource output, and destination asset copying.
  Destination rule bundles supply all declaration shapes, resolved mapping
  policy, namespaces, bridges, and optional product runtime assets. This
  namespace deliberately does not load a product rule bundle unless an explicit
  qualified selector is present in destination configuration."
  (:require [clojure.string :as str]
            [dripsharp.authorship :as authorship]
            [dripsharp.baseline :as baseline]
            [dripsharp.bundle-contract :as bundle-contract]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-mapping-registry :as mapping-registry]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input]
            [dripsharp.project-xml :as project-xml]
            [dripsharp.source-accountability :as source-accountability]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util]
            [dripsharp.validation :as validation])
  (:import [java.nio.charset Charset MalformedInputException]
           [java.nio.file Files Path StandardCopyOption]
           [java.util IdentityHashMap]
           [spoon.reflect.declaration CtElement CtEnum CtType]
           [spoon.reflect.visitor.filter TypeFilter]))

(def translator-version
  "The source-controlled version recorded in every mechanical C# header."
  "0.1.0")

(defn rule-contract
  "Returns the serializable composition contract implemented by every
  destination bundle. Product runtime assets are optional; all other
  components and hooks are required and validated before any file is emitted."
  []
  (bundle-contract/contract))

(defn- destination-error [message data]
  (throw (ex-info message (assoc data :kind :invalid-destination-configuration))))

(defn- destination-context
  ([subject]
   (destination-context subject {}))
  ([subject data]
   {:kind :invalid-destination-configuration
    :subject subject
    :data data}))

(def ^:private project-required-keys
  #{:assembly-name :root-namespace :target-framework :nullable
    :implicit-usings :warnings-as-errors})

(def ^:private project-allowed-keys
  (into project-required-keys [:define-constants :no-warn]))

(def ^:private package-required-keys
  #{:id :version :title :description :authors :tags
    :project-url :repository-url :repository-type})

(def ^:private package-allowed-keys
  (into package-required-keys
        [:repository-commit :license-expression :copyright :symbols]))

(def ^:private output-keys
  #{:project-directory :source-directory :resource-directory :project-file
    :source-map-file :diagnostics-file :manifest-file :public-metadata-file
    :annotation-decisions-file})

(def ^:private mechanical-source-keys
  #{:repository :revision :notice-reference})

(def ^:private resource-notice-attribution-keys
  #{:legal-sets :package-paths})

(defn- relative-path!
  ([value label]
   (relative-path! value label [label]))
  ([value label field-path]
   (let [value (str value)
         path (paths/path value)]
     (when (or (str/blank? value) (.isAbsolute path)
               (some #(= ".." (str %)) (iterator-seq (.iterator path))))
       (validation/fail!
        (destination-context label {:field label})
        field-path value "a safe relative path"))
     value)))

(defn- non-blank-single-line-xml-path?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (re-find
             #"[\u0000\t\u000B\u000C\r\n\u0085\u2028\u2029]"
             value))
       (project-xml/valid-text? value)))

(defn- normalized-portable-relative-path?
  [value]
  (when (non-blank-single-line-xml-path? value)
    (let [components (str/split value #"/" -1)]
      (and (not (str/includes? value "\\"))
           (not (str/starts-with? value "/"))
           (not (re-find #"^[A-Za-z]:" value))
           (every? #(and (not (str/blank? %))
                         (not (contains? #{"." ".."} %)))
                   components)))))

(defn- sha256?
  [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- project-reference!
  ([value]
   (project-reference! value [:project-references]))
  ([value field-path]
   (let [value (str value)
         path (paths/path value)
         segments (mapv str (iterator-seq (.iterator path)))]
     (when (or (str/blank? value) (.isAbsolute path)
               (some #(= ".." %) (rest segments))
               (not (str/ends-with? value ".csproj")))
       (validation/fail!
        (destination-context "Destination project reference"
                             {:field :project-references})
        field-path value "a sibling or child .csproj path"))
     value)))

(defn validate-configuration!
  "Validates the destination-neutral project, package, namespace, resource,
  output, bridge, and optional runtime-asset configuration contract."
  [configuration]
  (let [context (destination-context "Destination configuration")]
    (validation/check! context [:schema-version] (:schema-version configuration)
                       "the integer 1" #{1})
    (validation/check! context [:destination-bundle]
                       (:destination-bundle configuration)
                       "a namespace-qualified symbol" qualified-symbol?)
    (validation/check! context [:product-family]
                       (:product-family configuration)
                       "a keyword" keyword?)
    (validation/exact-keys!
     (destination-context "Destination project settings"
                          {:section :project})
     [:project] (:project configuration)
     project-required-keys project-allowed-keys)
    (validation/exact-keys!
     (destination-context "Destination package metadata"
                          {:section :package})
     [:package] (:package configuration)
     package-required-keys package-allowed-keys)
    (validation/exact-keys!
     (destination-context "Destination output settings"
                          {:section :output})
     [:output] (:output configuration)
     output-keys output-keys))
  (doseq [key [:assembly-name :root-namespace :target-framework]]
    (let [value (get-in configuration [:project key])]
      (validation/check!
       (destination-context "Destination project setting"
                            {:section :project :setting key})
       [:project key] value "a non-blank string"
       #(and (string? %) (not (str/blank? %))))))
  (validation/check!
   (destination-context "Destination project setting"
                        {:section :project :setting :implicit-usings})
   [:project :implicit-usings]
   (get-in configuration [:project :implicit-usings])
   "a boolean" boolean?)
  (doseq [key [:id :version :title :description :authors :tags
               :project-url :repository-url :repository-type]]
    (let [value (get-in configuration [:package key])]
      (validation/check!
       (destination-context "Destination package metadata"
                            {:section :package :setting key})
       [:package key] value "a non-blank XML-compatible string"
       #(and (string? %)
             (not (str/blank? %))
             (project-xml/valid-text? %)))))
  (let [authors (get-in configuration [:package :authors])]
    (validation/check!
     (destination-context "Destination package metadata"
                          {:section :package :setting :authors})
     [:package :authors] authors
     "a non-blank single-line XML-compatible publisher identity"
     #(and (string? %)
           (not (str/blank? %))
           (not (re-find
                 #"[\u0000\u000B\u000C\r\n\u0085\u2028\u2029]"
                 %))
           (project-xml/valid-text? %))))
  (when-let [commit (get-in configuration [:package :repository-commit])]
    (validation/check!
     (destination-context "Destination package repository commit")
     [:package :repository-commit] commit
     "a 40- or 64-character lowercase Git identity"
     #(and (string? %)
           (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" %)))))
  (when-let [license (get-in configuration [:package :license-expression])]
    (validation/check!
     (destination-context "Destination package license expression"
                          {:section :package
                           :setting :license-expression})
     [:package :license-expression] license
     "a non-blank XML-compatible string"
     #(and (string? %)
           (not (str/blank? %))
           (project-xml/valid-text? %))))
  (when-let [copyright (get-in configuration [:package :copyright])]
    (validation/check!
     (destination-context "Destination package copyright"
                          {:section :package
                           :setting :copyright})
     [:package :copyright] copyright
     "a non-blank XML-compatible string"
     #(and (string? %)
           (not (str/blank? %))
           (project-xml/valid-text? %))))
  (let [legal-files (:legal-files configuration)
        license-expression (get-in configuration [:package :license-expression])]
    (when (contains? configuration :legal-files)
      (let [context
            (destination-context "Destination legal-file packaging contract")]
        (validation/check! context [:legal-files] legal-files
                           "a nonempty vector"
                           #(and (vector? %) (seq %)))
        (doseq [[index legal-file] (map-indexed vector legal-files)]
          (validation/exact-keys!
           context [:legal-files index] legal-file
           #{:kind :source :destination :package-path :sha256}
           #{:kind :source :destination :package-path :sha256 :source-sha256})
          (validation/check! context [:legal-files index :kind]
                             (:kind legal-file)
                             ":license or :notice"
                             #{:license :notice})
          (doseq [[field label]
                  [[:source "legal file source"]
                   [:destination "legal file destination"]
                   [:package-path "legal file package path"]]]
            (validation/check! context [:legal-files index field]
                               (get legal-file field)
                               "a non-blank single-line XML-safe path"
                               non-blank-single-line-xml-path?)
            (relative-path! (get legal-file field)
                            label
                            [:legal-files index field])
            (validation/check! context [:legal-files index field]
                               (get legal-file field)
                               "a normalized portable relative path"
                               normalized-portable-relative-path?))
          (validation/check! context [:legal-files index :sha256]
                             (:sha256 legal-file)
                             "a lowercase SHA-256 string"
                             sha256?)
          (when-some [source-sha256 (:source-sha256 legal-file)]
            (validation/check! context [:legal-files index :source-sha256]
                               source-sha256
                               "a lowercase SHA-256 string"
                               sha256?)))
        (doseq [[field exact-expectation case-insensitive-expectation]
                [[:destination
                  "entries with distinct :destination values"
                  "entries with case-insensitively distinct :destination values"]
                 [:package-path
                  "entries with distinct :package-path values"
                  "entries with case-insensitively distinct :package-path values"]]]
          (when-not (= (count legal-files)
                       (count (distinct (map field legal-files))))
            (validation/fail! context [:legal-files] legal-files
                              exact-expectation))
          (when-not (= (count legal-files)
                       (count
                        (distinct
                         (map (comp str/lower-case field) legal-files))))
            (validation/fail! context [:legal-files] legal-files
                              case-insensitive-expectation)))
        (when (< 1 (count (filter #(= :license (:kind %)) legal-files)))
          (validation/fail! context [:legal-files] legal-files
                            "at most one :license entry"))))
    (let [license-files
          (filterv #(= :license (:kind %)) legal-files)]
      (when (and (seq license-files) license-expression)
        (destination-error
         "Destination license expression and packed license file are mutually exclusive"
         {:license-expression license-expression
          :legal-files legal-files}))
      (when-not (or license-expression (seq license-files))
        (validation/fail!
         (destination-context "Destination package license metadata")
         [:package]
         {:license-expression license-expression
          :legal-files legal-files}
         "a license expression or exactly one pinned :license legal file"))))
  (when-let [attribution (:resource-notice-attribution configuration)]
    (let [context
          (destination-context
           "Production-resource NOTICE attribution contract")
          legal-sets (:legal-sets attribution)
          package-paths (:package-paths attribution)
          configured-notice-paths
          (set
           (keep #(when (= :notice (:kind %)) (:package-path %))
                 (:legal-files configuration)))]
      (validation/exact-keys!
       context [:resource-notice-attribution] attribution
       resource-notice-attribution-keys resource-notice-attribution-keys)
      (doseq [[field values expected]
              [[:legal-sets legal-sets
                "a vector of distinct legal-set keywords"]
               [:package-paths package-paths
                "a vector of distinct relative package paths"]]]
        (validation/check!
         context [:resource-notice-attribution field] values expected
         #(and (vector? %)
               (= (count %) (count (distinct %))))))
      (doseq [[index legal-set] (map-indexed vector legal-sets)]
        (validation/check!
         context [:resource-notice-attribution :legal-sets index]
         legal-set "a keyword" keyword?))
      (doseq [[index package-path] (map-indexed vector package-paths)]
        (validation/check!
         context [:resource-notice-attribution :package-paths index]
         package-path "a string" string?)
        (relative-path!
         package-path "resource NOTICE package path"
         [:resource-notice-attribution :package-paths index]))
      (validation/check!
       context [:resource-notice-attribution :package-paths]
       package-paths
       "one or more paths exactly when resource NOTICE legal sets are selected"
       #(= (boolean (seq legal-sets)) (boolean (seq %))))
      (let [missing
            (vec (remove configured-notice-paths package-paths))]
        (when (seq missing)
          (validation/fail!
           context [:resource-notice-attribution :package-paths]
           package-paths
           (str "paths backed by configured :notice legal files; missing "
                missing))))))
  (when-let [symbols (get-in configuration [:package :symbols])]
    (validation/check!
     (destination-context "Destination package symbol format")
     [:package :symbols] symbols ":snupkg" #{:snupkg}))
  (doseq [key [:project-directory :source-directory :resource-directory
               :project-file :source-map-file :diagnostics-file :manifest-file
               :public-metadata-file :annotation-decisions-file]]
    (relative-path! (get-in configuration [:output key])
                    (name key) [:output key]))
  (validation/check!
   (destination-context "Destination nullable setting")
   [:project :nullable] (get-in configuration [:project :nullable])
   "\"enable\" or \"disable\"" #{"enable" "disable"})
  (validation/check!
   (destination-context "Destination warnings-as-errors policy")
   [:project :warnings-as-errors]
   (get-in configuration [:project :warnings-as-errors])
   "a boolean" boolean?)
  (when-let [constants (get-in configuration [:project :define-constants])]
    (let [context (destination-context "Destination define constants")]
      (validation/check! context [:project :define-constants] constants
                         "a vector of C# identifiers" vector?)
      (doseq [[index constant] (map-indexed vector constants)]
        (validation/check!
         context [:project :define-constants index] constant
         "a C# identifier"
         #(and (string? %)
               (boolean
                (re-matches #"[A-Za-z_][A-Za-z0-9_]*" %)))))))
  (let [context (destination-context "Destination namespace mappings")
        mappings (:namespaces configuration)]
    (validation/check! context [:namespaces] mappings "a map" map?)
    (doseq [[source destination] mappings]
      (validation/check! context [:namespaces source :source] source
                         "a non-blank string"
                         #(and (string? %) (not (str/blank? %))))
      (validation/check! context [:namespaces source] destination
                         "a non-blank string"
                         #(and (string? %) (not (str/blank? %))))))
  (when-let [mappings (:namespace-prefixes configuration)]
    (let [context (destination-context "Destination namespace-prefix mappings")]
      (validation/check! context [:namespace-prefixes] mappings "a map" map?)
      (doseq [[source destination] mappings]
        (validation/check! context [:namespace-prefixes source :source] source
                           "a non-blank string"
                           #(and (string? %) (not (str/blank? %))))
        (validation/check! context [:namespace-prefixes source] destination
                           "a non-blank string"
                           #(and (string? %) (not (str/blank? %)))))))
  (when-let [mappings (:generic-erasure-mappings configuration)]
    (let [context (destination-context "Generic erasure mappings")]
      (validation/check! context [:generic-erasure-mappings] mappings
                         "a map" map?)
      (doseq [[source destination] mappings]
        (validation/check!
         context [:generic-erasure-mappings source :source] source
         "a resolved Java type identity"
         #(and
           (string? %)
           (boolean
            (re-matches
             #"[A-Za-z_$][A-Za-z0-9_$]*(?:[.][A-Za-z_$][A-Za-z0-9_$]*)*"
             %))))
        (validation/check!
         context [:generic-erasure-mappings source] destination
         "a global C# contract type"
         #(and
           (string? %)
           (boolean
            (re-matches
             #"global::@?[A-Za-z_][A-Za-z0-9_]*(?:[.]@?[A-Za-z_][A-Za-z0-9_]*)*"
             %)))))))
  (let [resources (:resources configuration)
        context (destination-context "Destination resource mappings")]
    (validation/check! context [:resources] resources "a map" map?)
    (doseq [[source resource] resources]
      (validation/check! context [:resources source :source] source
                         "a string" string?)
      (validation/exact-keys! context [:resources source] resource
                              #{:strategy :destination :logical-name}
                              #{:strategy :destination :logical-name})
      (validation/check! context [:resources source :strategy]
                         (:strategy resource)
                         ":embedded-resource" #{:embedded-resource})
      (validation/check! context [:resources source :logical-name]
                         (:logical-name resource)
                         "a string" string?)
      (relative-path! (:destination resource) "resource destination"
                      [:resources source :destination])))
  (when-not (or (nil? (:resource-policy configuration))
                (= {:strategy :embedded-resource-preserve-path}
                   (:resource-policy configuration)))
    (destination-error "Invalid destination resource policy"
                       {:resource-policy (:resource-policy configuration)}))
  (when-let [references (:project-references configuration)]
    (let [context (destination-context "Destination project references")]
      (validation/check! context [:project-references] references
                         "a vector" vector?)
      (doseq [[index reference] (map-indexed vector references)]
        (project-reference! reference [:project-references index]))))
  (when-let [dependencies (:project-dependencies configuration)]
    (let [context (destination-context "Source project-dependency contract")]
      (validation/check! context [:project-dependencies] dependencies
                         "a vector" vector?)
      (doseq [[index dependency] (map-indexed vector dependencies)]
        (validation/check! context [:project-dependencies index] dependency
                           "a non-blank string"
                           #(and (string? %) (not (str/blank? %)))))))
  (when-let [dependencies (:external-dependencies configuration)]
    (let [context (destination-context "Source external-dependency contract")]
      (validation/check! context [:external-dependencies] dependencies
                         "a map" map?)
      (doseq [[coordinate dependency] dependencies]
        (validation/check! context [:external-dependencies coordinate :coordinate]
                           coordinate "a non-blank string"
                           #(and (string? %) (not (str/blank? %))))
        (validation/check! context [:external-dependencies coordinate]
                           dependency "a map" map?)
        (validation/check! context
                           [:external-dependencies coordinate :source-scope]
                           (:source-scope dependency)
                           ":compile-only or :compile-runtime"
                           #{:compile-only :compile-runtime})
        (when-let [artifact-sha256 (:artifact-sha256 dependency)]
          (validation/check!
           context [:external-dependencies coordinate :artifact-sha256]
           artifact-sha256 "a lowercase SHA-256 hash"
           #(and (string? %)
                 (boolean (re-matches #"[0-9a-f]{64}" %)))))
        (validation/check! context
                           [:external-dependencies coordinate :runtime-package]
                           (:runtime-package dependency)
                           "a boolean" boolean?))))
  (when-let [sources (:runtime-sources configuration)]
    (let [context (destination-context "Destination runtime sources")]
      (validation/check! context [:runtime-sources] sources "a vector" vector?)
      (doseq [[index source] (map-indexed vector sources)]
        (relative-path! source "runtime source" [:runtime-sources index]))))
  (when-let [capabilities (:destination-capabilities configuration)]
    (let [context (destination-context "Destination capabilities")]
      (validation/check! context [:destination-capabilities] capabilities
                         "a set of keywords" set?)
      (doseq [capability capabilities]
        (validation/check! context [:destination-capabilities capability]
                           capability "a keyword" keyword?))))
  (let [mechanical-source (:mechanical-source configuration)
        context (destination-context "Destination mechanical-source provenance")
        single-line? #(and (string? %)
                           (not (str/blank? %))
                           (not
                            (re-find
                             #"[\u0000\u000B\u000C\r\n\u0085\u2028\u2029]"
                             %)))]
    (validation/exact-keys! context [:mechanical-source] mechanical-source
                            mechanical-source-keys mechanical-source-keys)
    (validation/check! context [:mechanical-source :repository]
                       (:repository mechanical-source)
                       "a non-blank single-line repository identity"
                       single-line?)
    (validation/check!
     context [:mechanical-source :revision] (:revision mechanical-source)
     "a 40- or 64-character lowercase revision"
     #(and (string? %)
           (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" %))))
    (when-let [notice (:notice-reference mechanical-source)]
      (validation/check! context [:mechanical-source :notice-reference]
                         notice "a non-blank single-line string"
                         single-line?)))
  (let [surface (:public-surface configuration)]
    (validation/check!
     (destination-context "Destination public surface")
     [:public-surface] surface "a map" map?)
    (validation/check!
     (destination-context "Destination public surface")
     [:public-surface :strategy] (:strategy surface)
     "a namespace-qualified symbol" qualified-symbol?))
  (when-let [consumer (:package-consumer configuration)]
    (let [context
          (destination-context "Independent package-consumer contract")
          strategy (:strategy consumer)
          common #{:strategy :project-file :success-message}]
      (validation/check! context [:package-consumer] consumer "a map" map?)
      (validation/check! context [:package-consumer :strategy] strategy
                         ":source-file or :compile-only"
                         #{:source-file :compile-only})
      (validation/exact-keys!
       context [:package-consumer] consumer
       (case strategy
         :compile-only (conj common :compile-types)
         :source-file common)
       (case strategy
         :compile-only (conj common :compile-types)
         :source-file (into common [:fixture-file :source-path])))
      (validation/check!
       context [:package-consumer :project-file] (:project-file consumer)
       "a relative .csproj path"
       #(and (string? %) (str/ends-with? % ".csproj")))
      (relative-path! (:project-file consumer)
                      "package consumer project file"
                      [:package-consumer :project-file])
      (validation/check!
       context [:package-consumer :success-message] (:success-message consumer)
       "a non-blank string"
       #(and (string? %) (not (str/blank? %))))
      (case strategy
        :compile-only
        (let [types (:compile-types consumer)]
          (validation/check! context [:package-consumer :compile-types] types
                             "a nonempty vector of C# type names"
                             #(and (vector? %) (seq %)))
          (doseq [[index type-name] (map-indexed vector types)]
            (validation/check!
             context [:package-consumer :compile-types index] type-name
             "a qualified C# type name"
             #(and
               (string? %)
               (boolean
                (re-matches
                 #"[A-Za-z_][A-Za-z0-9_]*(?:[.][A-Za-z_][A-Za-z0-9_]*)*"
                 %))))))

        :source-file
        (let [fixture-file (:fixture-file consumer)
              source-path (:source-path consumer)]
          (validation/check!
           context [:package-consumer] consumer
           "exactly one of :fixture-file or :source-path"
           (fn [_] (not= (nil? fixture-file) (nil? source-path))))
          (if fixture-file
            (validation/check!
             context [:package-consumer :fixture-file] fixture-file
             "a non-blank string"
             #(and (string? %) (not (str/blank? %))))
            (do
              (validation/check!
               context [:package-consumer :source-path] source-path
               "a relative .cs path"
               #(and (string? %) (str/ends-with? % ".cs")))
              (relative-path! source-path "package consumer source path"
                              [:package-consumer :source-path])))))))
  (when-let [contract (:authorship configuration)]
    (try
      (authorship/validate-policy-contract! contract)
      (catch clojure.lang.ExceptionInfo error
        (throw
         (ex-info "Invalid destination authorship contract"
                  (assoc (ex-data error)
                         :kind :invalid-destination-configuration)
                  error)))))
  configuration)

(defn read-configuration
  "Reads an explicit destination configuration without assuming a product."
  [workspace-root config-file]
  (let [file (paths/resolve-path (paths/absolute workspace-root) config-file)]
    (when-not (paths/regular-file? file)
      (destination-error "Destination configuration is missing" {:path (str file)}))
    (let [raw-configuration
          (try
            (util/read-single-edn-string! (slurp (str file)))
            (catch RuntimeException error
              (destination-error
               "Destination configuration is not exactly one EDN value"
               (merge {:path (str file)}
                      (select-keys (ex-data error) [:reason])))))
          configuration
          (baseline/hydrate-destination
           workspace-root
           raw-configuration)
          configuration
          (if-let [[_ target-root]
                   (re-matches
                    #"^(targets/[^/]+)/destinations/[^/]+\.edn$"
                    config-file)]
            (update configuration :runtime-sources
                    (fn [sources]
                      (when sources
                        (mapv #(if (str/starts-with? % "runtime/")
                                 (str target-root "/" %)
                                 %)
                              sources))))
            configuration)]
      (validate-configuration! configuration))))

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonicalize item)]) value))
    (set? value) (mapv canonicalize (sort-by pr-str value))
    (sequential? value) (mapv canonicalize value)
    :else value))

(defn- edn-text [value]
  (str (pr-str (canonicalize value)) "\n"))

(def ^:private write-text! util/write-text!)

(defn- project-property
  [name value]
  (project-xml/element name [(project-xml/text value)]))

(defn- project-node*
  [configuration resource-artifacts
   {:keys [additional-properties additional-items]
    :or {additional-properties [] additional-items []}}]
  (let [project (:project configuration)
        package (:package configuration)
        output (:output configuration)
        legal-files (:legal-files configuration)
        license-file (some #(when (= :license (:kind %)) %) legal-files)
        properties
        (vec
         (remove
          nil?
          [(project-property "TargetFramework" (:target-framework project))
           (project-property "Nullable" (:nullable project))
           (project-property "ImplicitUsings"
                             (if (:implicit-usings project) "enable" "disable"))
           (project-property "TreatWarningsAsErrors"
                             (if (:warnings-as-errors project) "true" "false"))
           (when (seq (:no-warn project))
             (project-property "NoWarn"
                               (str/join ";" (sort (:no-warn project)))))
           (when (seq (:define-constants project))
             (project-property
              "DefineConstants"
              (str "$(DefineConstants);"
                   (str/join ";" (sort (:define-constants project))))))
           (project-property "EnableDefaultCompileItems" "false")
           (project-property "AssemblyName" (:assembly-name project))
           (project-property "RootNamespace" (:root-namespace project))
           (project-property "Deterministic" "true")
           (project-property "ContinuousIntegrationBuild" "true")
           (project-property "PackageId" (:id package))
           (project-property "Version" (:version package))
           (project-property "Title" (:title package))
           (project-property "Description" (:description package))
           (project-property "Authors" (:authors package))
           (when-let [copyright (:copyright package)]
             (project-property "Copyright" copyright))
           (project-property "PackageTags" (:tags package))
           (project-property "PackageProjectUrl" (:project-url package))
           (project-property "RepositoryUrl" (:repository-url package))
           (project-property "RepositoryType" (:repository-type package))
           (when-let [commit (:repository-commit package)]
             (project-property "RepositoryCommit" commit))
           (when-let [license (:license-expression package)]
             (project-property "PackageLicenseExpression" license))
           (when license-file
             (project-property "PackageLicenseFile"
                               (:package-path license-file)))]))
        properties
        (into properties
              (concat additional-properties
                      [(project-property
                        "PackageRequireLicenseAcceptance" "false")
                       (project-property "IsPackable" "true")]))
        items
        (vec
         (concat
          [(project-xml/element
            "Compile"
            [["Include" (str (:source-directory output) "/**/*.cs")]]
            [])]
          (for [reference (sort (:project-references configuration))]
            (project-xml/element
             "ProjectReference" [["Include" reference]] []))
          (for [assembly (sort (:friend-assemblies configuration))]
            (project-xml/element
             "AssemblyAttribute"
             [["Include"
               "System.Runtime.CompilerServices.InternalsVisibleToAttribute"]]
             [(project-property "_Parameter1" assembly)]))
          (for [{:keys [destination logical-name]}
                (sort-by :destination resource-artifacts)]
            (project-xml/element
             "EmbeddedResource"
             [["Include" destination] ["LogicalName" logical-name]]
             []))
          (for [{:keys [destination package-path]}
                (sort-by :package-path legal-files)]
            (project-xml/element
             "None"
             [["Include" (str (:source-directory output) "/" destination)]
              ["Pack" "true"]
              ["PackagePath" package-path]]
             []))
          additional-items))]
    (project-xml/element
     "Project"
     [["Sdk" "Microsoft.NET.Sdk"]]
     [(project-xml/element "PropertyGroup" properties)
      (project-xml/element "ItemGroup" items)])))

(defn project-node
  "Constructs the common deterministic SDK project contract as structured XML.
  Destination policies may supply additional property and item nodes; both are
  placed at stable extension points before the common group closings."
  ([configuration resource-artifacts]
   (project-node* configuration resource-artifacts {}))
  ([configuration resource-artifacts additions]
   (project-node* configuration resource-artifacts additions)))

(defn project-text
  "Renders the common deterministic SDK project contract."
  [configuration resource-artifacts]
  (project-xml/render (project-node configuration resource-artifacts)))

(defn resource-mapping
  "Applies the common explicit-or-preserve-path resource policy."
  [configuration relative]
  (or (get-in configuration [:resources relative])
      (when (= :embedded-resource-preserve-path
               (get-in configuration [:resource-policy :strategy]))
        {:strategy :embedded-resource
         :destination relative
         :logical-name (str/replace relative "/" ".")})))

(def common-project-policy
  {:validate-configuration! validate-configuration!
   :project-text project-text})

(def common-resource-policy
  {:resource-mapping resource-mapping})

(defn- live-source [resolved-model]
  (when-let [^CtType type (first (java/project-roots resolved-model))]
    {:source-element type
     :source-identity (spoon/declaration-key type)
     :source-location (spoon/source-location type)
     :frontend-class (.getName (class type))}))

(defn- capability-error! [resolved-model message data]
  (throw (ex-info message
                  (merge {:kind :missing-destination-capability}
                         (live-source resolved-model)
                         data))))

(defn validate-rule-bundle!
  [rule-bundle resolved-model]
  (bundle-contract/validate!
   rule-bundle
   (partial capability-error! resolved-model)))

(defn resolve-rule-bundle!
  [{:keys [rule-bundle configuration resolved-model]}]
  (if rule-bundle
    (validate-rule-bundle! rule-bundle resolved-model)
    (let [selector (:destination-bundle configuration)]
      (when-not (and (symbol? selector) (namespace selector))
        (capability-error!
         resolved-model
         "Destination configuration does not select a supported rule bundle"
         {:destination-bundle selector}))
      (let [factory
            (try
              (requiring-resolve selector)
              (catch Throwable error
                (throw (ex-info "Destination rule bundle selection failed"
                                (merge {:kind :unsupported-destination-rule-bundle
                                        :destination-bundle selector}
                                       (live-source resolved-model))
                                error))))]
        (when-not (ifn? factory)
          (capability-error! resolved-model "Destination rule selector is not callable"
                             {:destination-bundle selector}))
        (validate-rule-bundle! (factory) resolved-model)))))

(defn- rule [rule-bundle component capability]
  (get-in rule-bundle [:rules component capability]))

(defn- collision-errors [declarations]
  (let [nested-types (filter #(and (= :type (:kind %)) (:owner %)) declarations)
        values (filter #(contains? #{:field :enum-value :record-component} (:kind %)) declarations)
        methods (filter #(= :method (:kind %)) declarations)
        constructors (filter #(= :constructor (:kind %)) declarations)
        parameters (filter #(= :parameter (:kind %)) declarations)
        type-parameters (filter #(= :type-parameter (:kind %)) declarations)
        non-callable (concat nested-types values)
        non-callable-names (set (map (juxt :owner :name) non-callable))
        duplicate-groups
        (concat
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) non-callable)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name :signature) methods)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :signature) constructors)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) parameters)))
         (filter #(< 1 (count %)) (vals (group-by (juxt :owner :name) type-parameters)))
         (map (fn [method]
                [method {:kind :conflicting-non-callable
                         :owner (:owner method) :name (:name method)}])
              (filter #(contains? non-callable-names [(:owner %) (:name %)]) methods)))]
    (mapv #(mapv (fn [entry] (select-keys entry [:id :kind :owner :name :signature])) %)
          duplicate-groups)))

(defn- resource-relative [resource-roots ^Path resource]
  (let [resource (.normalize resource)
        roots (->> resource-roots
                   (map #(.normalize ^Path %))
                   (filter #(.startsWith resource ^Path %))
                   (sort-by #(.getNameCount ^Path %) >)
                   vec)]
    (when-not (seq roots)
      (throw (ex-info "Production resource is outside every project resource root"
                      {:kind :unmapped-production-resource
                       :roots (mapv str resource-roots)
                       :path (str resource)})))
    (when (< 1 (count roots))
      (throw (ex-info "Production resource matches multiple project resource roots"
                      {:kind :ambiguous-production-resource-root
                       :roots (mapv str roots)
                       :path (str resource)})))
    (str/replace (str (.relativize ^Path (first roots) resource)) "\\" "/")))

(defn- portable [^Path root value]
  (util/portable-or-absolute-path root (paths/absolute value)))

(defn- source-relative [source-roots source]
  (let [canonical #(-> (paths/absolute %) (.toRealPath paths/no-links))
        source (canonical source)
        roots (->> source-roots
                   (map canonical)
                   (filter #(.startsWith ^Path source ^Path %))
                   (sort-by #(.getNameCount ^Path %) >)
                   vec)]
    (when-not (= 1 (count roots))
      (throw
       (ex-info
        (if (seq roots)
          "Mechanical source matches multiple Java source roots"
          "Mechanical source is outside every Java source root")
        {:kind (if (seq roots)
                 :ambiguous-mechanical-source-root
                 :unmapped-mechanical-source)
         :source (str source)
         :source-roots (mapv str roots)})))
    (str/replace (str (.relativize ^Path (first roots) source)) "\\" "/")))

(defn mechanical-source-header
  "Renders the exact deterministic header for one mechanically translated file."
  [{:keys [repository revision notice-reference]} upstream-relative-path]
  (str "// <auto-generated />\n"
       "// Mechanically translated from: " upstream-relative-path "\n"
       "// Upstream repository: " repository "\n"
       "// Upstream revision: " revision "\n"
       "// Translator: DripSharp " translator-version "\n"
       "// IMPORTANT: This mechanically translated derivative has been changed "
       "from the upstream source.\n"
       "// Applicable upstream notices: "
       (if notice-reference
         (str "see " notice-reference ".")
         "upstream supplies no NOTICE file.")
       "\n"))

(defn verify-mechanical-source-headers!
  "Recomputes and verifies every exact mechanical header in an emission.
  Copied authored/runtime/legal assets have :strategy and are deliberately
  excluded."
  [{:keys [^Path project-root artifacts configuration destination
           mechanical-source-header-proof]}]
  (let [mechanical (filterv :mechanical-source-header artifacts)
        provenance (or (:mechanical-source configuration)
                       (:mechanical-source destination))
        unclassified (filterv #(and (nil? (:strategy %))
                                    (nil? (:mechanical-source-header %)))
                              artifacts)
        proof {:schema-version 1
               :translator "DripSharp"
               :translator-version translator-version
               :verified-files (count mechanical)}]
    (when (seq unclassified)
      (throw
       (ex-info "Mechanically emitted artifacts are missing provenance headers"
                {:kind :missing-mechanical-source-header
                 :files (mapv :file unclassified)})))
    (doseq [{:keys [file upstream-source] :as artifact} mechanical]
      (let [recorded-header (:mechanical-source-header artifact)
            expected (when provenance
                       (mechanical-source-header provenance upstream-source))
            source (paths/resolve-path project-root file)
            actual (when (paths/regular-file? source) (Files/readString source))]
        (when-not (and expected
                       (= expected recorded-header)
                       actual
                       (str/starts-with? actual expected))
          (throw
           (ex-info "Mechanically emitted C# header differs from its exact generation evidence"
                    {:kind :mechanical-source-header-mismatch
                     :file file
                     :upstream-source upstream-source
                     :expected expected
                     :recorded recorded-header
                     :actual (some-> actual str/split-lines first)})))))
    (when (and mechanical-source-header-proof
               (not= proof mechanical-source-header-proof))
      (throw
       (ex-info "Mechanical source header proof differs from the emitted artifacts"
                {:kind :mechanical-source-header-proof-mismatch
                 :expected proof
                 :actual mechanical-source-header-proof})))
    proof))

(defn- source-accounting [ctx workspace-root files]
  (source-accountability/summarize
   workspace-root (:diagnostics ctx) (:declarations ctx) files))

(defn- selected-source-files [resolved-model input]
  (if-let [source-inputs (:source-inputs resolved-model)]
    (mapv (comp paths/path key) source-inputs)
    (project-input/production-source-files input)))

(defn- selected-declaration-index [resolved-model]
  (when-let [declarations (:declarations resolved-model)]
    (let [index (IdentityHashMap.)]
      (doseq [[_ {:keys [declaration]}] declarations]
        (.put index declaration true))
      index)))

(defn- element-weight [^CtElement element]
  (count (.getElements element (TypeFilter. CtElement))))

(defn- balanced-work-order
  "Places largest jobs at the front of separate executor chunks while keeping
  the returned order deterministic. Results are reassembled by canonical job
  indexes, so this ordering affects scheduling only."
  [jobs]
  (let [jobs (vec (sort-by (juxt (comp - :weight) :kind :index) jobs))
        chunk-size (max 1 (long (Math/ceil
                                 (/ (double (count jobs))
                                    (* 16.0 (concurrency/current-worker-count))))))
        chunk-count (max 1 (long (Math/ceil (/ (double (count jobs)) chunk-size))))
        chunks (reduce (fn [result [index job]]
                         (update result (mod index chunk-count) conj job))
                       (vec (repeat chunk-count []))
                       (map-indexed vector jobs))]
    (vec (mapcat identity chunks))))

(defn- context-results [rule-bundle ctx]
  ((rule rule-bundle :structural-declarations :context-results) ctx))

(defn- absolute-asset-source
  [root source]
  (paths/absolute
   (let [path (paths/path source)]
     (if (.isAbsolute path) path (paths/resolve-path root path)))))

(defn- expand-asset-tree
  [root {:keys [source-tree destination-tree include-pattern
                missing-kind missing-message]
         :as asset}]
  (if-not source-tree
    [asset]
    (let [source-root (absolute-asset-source root source-tree)
          pattern (re-pattern (or include-pattern ".*"))]
      (when-not (paths/directory? source-root)
        (throw
         (ex-info (or missing-message "Configured destination asset tree is missing")
                  {:kind (or missing-kind :missing-destination-asset-tree)
                   :source (portable root source-root)
                   :destination destination-tree})))
      (with-open [files (Files/walk
                         source-root
                         (make-array java.nio.file.FileVisitOption 0))]
        (->> (.toArray files)
             (map #(cast Path %))
             (filter paths/regular-file?)
             (filter #(re-find pattern (str (.getFileName ^Path %))))
             (sort-by str)
             (mapv
              (fn [source]
                (let [relative (str (.relativize source-root source))]
                  (-> asset
                      (dissoc :source-tree :destination-tree :include-pattern)
                      (assoc :source source
                             :destination
                             (str (str/replace destination-tree #"[\\/]+$" "")
                                  "/"
                                  (str/replace relative "\\" "/"))))))))))))

(defn- read-asset-text
  [source fallback-charset]
  (try
    (Files/readString source)
    (catch MalformedInputException error
      (if fallback-charset
        (Files/readString source (Charset/forName fallback-charset))
        (throw error)))))

(defn- copy-assets!
  [rule-bundle root project-root source-root configuration]
  (let [asset-context {:workspace-root root
                       :project-root project-root
                       :source-root source-root
                       :configuration configuration}
        bridge-assets ((rule rule-bundle :destination-bridges :assets) asset-context)
        product-assets (when-let [assets (get-in rule-bundle
                                                 [:rules :product-runtime-assets :assets])]
                         (assets asset-context))]
    (mapv
     (fn [{:keys [source destination strategy missing-kind missing-message
                  text-charset-fallback text-prefix text-suffix
                  text-replacements authorship-class]}]
       (let [source (absolute-asset-source root source)
             relative (relative-path! destination "destination asset")
             destination (paths/resolve-path source-root relative)]
         (when-not (paths/regular-file? source)
           (throw (ex-info (or missing-message "Configured destination asset is missing")
                           {:kind (or missing-kind :missing-destination-asset)
                            :source (portable root source)
                            :destination relative
                            :bundle (:id rule-bundle)})))
         (Files/createDirectories (.getParent destination)
                                  (make-array java.nio.file.attribute.FileAttribute 0))
         (if (or (seq text-replacements) text-prefix text-suffix)
           (let [text (read-asset-text source text-charset-fallback)
                 transformed
                 (reduce-kv
                  (fn [value from to]
                    (when-not (and (string? from) (not (str/blank? from))
                                   (string? to))
                      (throw
                       (ex-info "Destination asset text replacement is invalid"
                                {:kind :invalid-destination-asset-replacement
                                 :source (portable root source)
                                 :from from :to to})))
                    (str/replace value from to))
                  text
                  (or text-replacements {}))]
             (when-not (and (or (nil? text-prefix) (string? text-prefix))
                            (or (nil? text-suffix) (string? text-suffix)))
               (throw
                (ex-info "Destination asset text wrapper is invalid"
                         {:kind :invalid-destination-asset-wrapper
                          :source (portable root source)})))
             (write-text! destination
                          (str (or text-prefix "")
                               transformed
                               (or text-suffix ""))))
           (Files/copy source destination
                       (into-array java.nio.file.CopyOption
                                   [StandardCopyOption/REPLACE_EXISTING])))
         {:file (portable project-root destination)
          :source {:file (portable root source) :line 1 :column 1}
          :mappings []
          :strategy strategy
          :authorship-class authorship-class}))
     (mapcat
      #(expand-asset-tree root %)
      (concat
       (map #(update % :authorship-class
                     (fnil identity :authored-compat))
            bridge-assets)
       (map #(update % :authorship-class
                     (fnil identity :authored-destination-runtime))
            product-assets))))))

(defn emit-project!
  "Emits one deterministic project through an explicit destination rule bundle.

  Callers may pass :rule-bundle directly, or configuration must select a
  namespace-qualified zero-argument bundle factory with :destination-bundle."
  [{:keys [workspace-root target project-input resolved-model configuration
           public-api-boundary]
    :as options}]
  (let [project-input (project-input/validate! project-input)
        rule-bundle (resolve-rule-bundle! options)
        validate! (rule rule-bundle :project-policy :validate-configuration!)
        configuration (validate! configuration)
        initial-mapping-context
        {:resolved-model resolved-model
         :configuration configuration
         :rule-bundle (:id rule-bundle)}
        mapping-registries
        ((rule rule-bundle :resolved-mappings
               :declarative-mapping-registries)
         initial-mapping-context)
        mapping-context
        (assoc initial-mapping-context :registries mapping-registries)
        mapping-report
        (assoc
         (mapping-registry/resolved-occurrence-report
          (:occurrences resolved-model)
          mapping-registries
          (fn [occurrence]
            ((rule rule-bundle :resolved-mappings
                   :declarative-mapping-required?)
             mapping-context occurrence)))
         :target (:id rule-bundle))
        _ (mapping-registry/require-complete-occurrence-report! mapping-report)
        root (paths/absolute workspace-root)
        project-root (paths/resolve-path target (get-in configuration [:output :project-directory]))
        source-root (paths/resolve-path project-root (get-in configuration [:output :source-directory]))
        occurrence-index (java/resolved-occurrence-index resolved-model)
        selected-declarations (selected-declaration-index resolved-model)
        public-api-type-keys
        (when public-api-boundary
          (->> (:selection-evidence public-api-boundary)
               (filter #(= "type" (get-in % [:row :kind])))
               (map :declaration-key)
               set))
        public-api-declaration-keys
        (when public-api-boundary
          (set (map :declaration-key (:selection-evidence public-api-boundary))))
        roots (java/project-roots resolved-model)
        scheduled-roots
        (->> roots
             (map-indexed
              (fn [index ^CtType type]
                {:index index
                 :type type
                 :weight (element-weight type)
                 :member-count (+ (count (.getTypeMembers type))
                                  (if (instance? CtEnum type)
                                    (count (.getEnumValues ^CtEnum type))
                                    0))}))
             vec)
        average-root-weight (if (seq scheduled-roots)
                              (/ (double (reduce + (map :weight scheduled-roots)))
                                 (count scheduled-roots))
                              0.0)
        dominant-root
        (let [candidate (first (sort-by (juxt (comp - :weight) :index) scheduled-roots))]
          (when (and candidate
                     (<= 8 (:member-count candidate))
                     (<= (* 4.0 average-root-weight) (:weight candidate)))
            candidate))
        create-template (rule rule-bundle :structural-declarations :create-template)
        create-context (rule rule-bundle :structural-declarations :create-context)
        emit-root-node (rule rule-bundle :structural-declarations :emit-root-node)
        translate-member (rule rule-bundle :structural-declarations :translate-member)
        merge-context! (rule rule-bundle :structural-declarations :merge-context!)
        destination-namespace (rule rule-bundle :namespace-policy :destination-namespace)
        destination-file-name (rule rule-bundle :namespace-policy :destination-file-name)
        worker-template
        (proxy [ThreadLocal] []
          (initialValue []
            (create-template resolved-model
                             (assoc
                              (get-in rule-bundle [:rules :resolved-mappings])
                              :runtime-capabilities
                              (:runtime-capabilities rule-bundle)))))
        emission-profile (atom {:root-count (count scheduled-roots)
                                :average-root-weight average-root-weight
                                :largest-root
                                (when-let [{:keys [^CtType type weight member-count]}
                                           (first (sort-by (juxt (comp - :weight) :index)
                                                           scheduled-roots))]
                                  {:name (.getQualifiedName type)
                                   :weight weight
                                   :member-count member-count})
                                :dominant-root nil})
        context-parameters
        (fn [template blocker-start emit-members]
          {:template template
           :configuration configuration
           :resolved-model resolved-model
           :occurrence-index occurrence-index
           :selected-declarations selected-declarations
           :public-api-type-keys public-api-type-keys
           :public-api-declaration-keys public-api-declaration-keys
           :runtime-capabilities (:runtime-capabilities rule-bundle)
           :blocker-start blocker-start
           :emit-members emit-members})
        context-additions
        (fn [ctx]
          (apply dissoc ctx
                 [:template :configuration :resolved-model :occurrence-index
                  :selected-declarations :public-api-type-keys
                  :public-api-declaration-keys :blocker-start :emit-members
                  :emitted :declarations :diagnostics :body-translations
                  :body-context]))
        declaration-results
        (let [ordinary-results (atom [])]
          (letfn [(emit-root!
                    [{:keys [index type]} emit-members]
                    (let [^CtType type type
                          template (.get ^ThreadLocal worker-template)
                          ctx (create-context
                               (context-parameters template (* index 1000000000)
                                                   emit-members))
                          namespace (destination-namespace ctx type)
                          relative (str (str/replace namespace "." "/") "/"
                                        (destination-file-name ctx type))
                          file (paths/resolve-path source-root relative)
                          upstream-source
                          (source-relative
                           (:source-roots project-input)
                           (get-in (spoon/source-location type) [:file]))
                          mechanical-header
                          (mechanical-source-header
                           (:mechanical-source configuration)
                           upstream-source)
                          node (csharp/sequence-node
                                [(csharp/raw (str mechanical-header "#nullable "
                                                  (get-in configuration [:project :nullable])
                                                  "\n"))
                                 (csharp/file-scoped-namespace namespace)
                                 (csharp/raw "\n\n")
                                 (emit-root-node ctx type) (csharp/raw "\n")])
                          node
                          (if-let [transform
                                   (get-in rule-bundle
                                           [:rules :project-policy
                                            :transform-node])]
                            (transform configuration node)
                            node)
                          rendered (csharp/render node)
                          result (context-results rule-bundle ctx)]
                      (write-text! file (:text rendered))
                      {:index index
                       :artifact {:file (portable project-root file)
                                  :source (spoon/source-location type)
                                  :upstream-source upstream-source
                                  :mechanical-source-header mechanical-header
                                  :mappings
                                  (mapv #(assoc % :file (portable project-root file))
                                        (:mappings rendered))}
                       :declarations (:declarations result)
                       :diagnostics (:diagnostics result)
                       :body-translations (:body-translations result)}))
                  (translate-member!
                    [parent-ctx root-index ^CtType owner index member]
                    (let [template (.get ^ThreadLocal worker-template)
                          ctx (create-context
                               (merge
                                (context-additions parent-ctx)
                                (context-parameters
                                 template
                                 (+ (* root-index 1000000000) (* (inc index) 1000000))
                                 nil)))]
                      {:kind :member
                       :index index
                       :node (translate-member ctx owner member)
                       :ctx ctx
                       :thread (.getName (Thread/currentThread))}))
                  (emit-dominant-members
                    [dominant-ctx ^CtType owner members]
                    (let [started (System/nanoTime)
                          root-index (:index dominant-root)
                          member-jobs
                          (mapv (fn [index member]
                                  {:kind :member :index index :member member
                                   :weight (element-weight member)})
                                (range) members)
                          root-jobs
                          (->> scheduled-roots
                               (remove #(= root-index (:index %)))
                               (mapv #(assoc % :kind :root)))
                          jobs (balanced-work-order (into root-jobs member-jobs))
                          results
                          (concurrency/mapv-ordered
                           :root-and-member-translation
                           (fn [{:keys [kind index member] :as job}]
                             (case kind
                               :root {:kind :root :index index
                                      :result (emit-root! job nil)}
                               :member (translate-member! dominant-ctx
                                                          root-index owner index member)))
                           jobs)
                          member-results (sort-by :index (filter #(= :member (:kind %)) results))
                          roots (mapv :result (sort-by :index (filter #(= :root (:kind %)) results)))
                          threads (->> member-results (map :thread) set sort vec)
                          elapsed (- (System/nanoTime) started)]
                      (reset! ordinary-results roots)
                      (doseq [{member-ctx :ctx} member-results]
                        (merge-context! dominant-ctx member-ctx))
                      (swap! emission-profile assoc
                             :dominant-root
                             {:name (.getQualifiedName owner)
                              :weight (:weight dominant-root)
                              :member-count (count members)
                              :member-weight (reduce + (map :weight member-jobs))
                              :largest-member-weight (reduce max 0 (map :weight member-jobs))
                              :worker-threads threads
                              :worker-participation (count threads)
                              :elapsed-millis (/ elapsed 1000000.0)})
                      (mapv :node member-results)))]
            (if dominant-root
              (let [dominant-result (emit-root! dominant-root emit-dominant-members)]
                (conj @ordinary-results dominant-result))
              (concurrency/mapv-ordered
               :declaration-translation-and-emission
               #(emit-root! % nil)
               (balanced-work-order
                (mapv #(assoc % :kind :root) scheduled-roots))))))
        declaration-results (vec (sort-by :index declaration-results))
        declaration-artifacts (mapv :artifact declaration-results)
        ctx {:configuration configuration
             :resolved-model resolved-model
             :occurrence-index occurrence-index
             :selected-declarations selected-declarations
             :public-api-type-keys public-api-type-keys
             :public-api-declaration-keys public-api-declaration-keys
             :declarations (vec (mapcat :declarations declaration-results))
             :diagnostics (vec (mapcat :diagnostics declaration-results))
             :body-translations (vec (mapcat :body-translations declaration-results))}
        asset-artifacts (copy-assets! rule-bundle root project-root source-root configuration)
        artifacts (into declaration-artifacts asset-artifacts)
        mechanical-source-header-proof
        (verify-mechanical-source-headers!
         {:project-root project-root
          :configuration configuration
          :artifacts artifacts})
        artifact-collisions (->> artifacts (group-by :file) vals (filter #(< 1 (count %))) vec)
        declaration-collisions (collision-errors (:declarations ctx))]
    (when (or (seq artifact-collisions) (seq declaration-collisions))
      (throw (ex-info "Generated declaration names or files collide"
                      {:kind :generated-declaration-collision
                       :file-collisions (mapv #(mapv :file %) artifact-collisions)
                       :declaration-collisions declaration-collisions})))
    (let [map-resource (rule rule-bundle :resource-policy :resource-mapping)
          authorship-ledger
          (authorship/create-ledger!
           {:workspace-root root
            :project-root project-root
            :source-root source-root
            :artifacts artifacts
            :mechanical-source (:mechanical-source configuration)
            :mechanical-header mechanical-source-header
            :configuration configuration
            :contract (:authorship configuration)})
          resource-artifacts
          (mapv
           (fn [^Path source]
             (let [relative (resource-relative (:resource-roots project-input) source)
                   mapping (map-resource configuration relative)]
               (when-not mapping
                 (throw (ex-info "Production resource has no explicit destination mapping"
                                 {:kind :unmapped-production-resource :resource relative})))
               (let [destination (paths/resolve-path project-root
                                                     (get-in configuration [:output :resource-directory])
                                                     (:destination mapping))]
                 (Files/createDirectories (.getParent destination)
                                          (make-array java.nio.file.attribute.FileAttribute 0))
                 (Files/copy source destination
                             (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
                 {:source (portable root source)
                  :destination (portable project-root destination)
                  :strategy (:strategy mapping)
                  :logical-name (:logical-name mapping)})))
           (sort-by str (:production-resources project-input)))
          resource-collisions
          (->> resource-artifacts
               (group-by :destination)
               vals
               (filter #(< 1 (count %)))
               vec)
          _ (when (seq resource-collisions)
              (throw (ex-info "Production resources map to the same destination"
                              {:kind :generated-resource-collision
                               :collisions resource-collisions})))
          project-file (paths/resolve-path project-root (get-in configuration [:output :project-file]))
          source-map-file (paths/resolve-path project-root (get-in configuration [:output :source-map-file]))
          diagnostics-file (paths/resolve-path project-root (get-in configuration [:output :diagnostics-file]))
          manifest-file (paths/resolve-path project-root (get-in configuration [:output :manifest-file]))
          annotations-file (paths/resolve-path project-root (get-in configuration [:output :annotation-decisions-file]))
          mappings (vec (mapcat :mappings artifacts))
          declaration-ids (set (map :id (:declarations ctx)))
          mapped-declaration-ids (set (keep #(get-in % [:source :declaration-id]) mappings))
          missing-mappings (sort (remove mapped-declaration-ids declaration-ids))
          accounts (source-accounting ctx root
                                      (selected-source-files resolved-model project-input))
          counts (frequencies (map :kind (:declarations ctx)))
          body-results (:body-translations ctx)
          body-coverage (reduce (fn [totals result]
                                  (merge-with + totals (java/coverage-totals result)))
                                {:visited 0 :covered 0 :blocked 0 :structural 0
                                 :semantic 0 :unsupported-elements 0
                                 :missing-mappings 0 :missing-occurrences 0 :fallback 0}
                                body-results)
          summary {:compilation-units (count accounts)
                   :generated-files (count artifacts)
                   :resources (count resource-artifacts)
                   :declarations (count (:declarations ctx))
                   :declaration-kinds (into (sorted-map) counts)
                   :source-mappings (count mappings)
                   :missing-source-mappings (count missing-mappings)
                   :hard-failures (count (:diagnostics ctx))
                   :executable-roots (count body-results)
                   :executable-coverage body-coverage
                   :collisions 0
                   :skipped-source-units 0}]
      (when (seq missing-mappings)
        (throw (ex-info "Generated declarations are missing Spoon source mappings"
                        {:kind :missing-declaration-source-mapping
                         :declaration-ids missing-mappings})))
      (write-text! project-file
                   ((rule rule-bundle :project-policy :project-text)
                    configuration resource-artifacts))
      (write-text! source-map-file (edn-text {:schema-version 1 :mappings mappings}))
      (write-text! diagnostics-file (edn-text {:schema-version 1 :diagnostics (:diagnostics ctx)}))
      (write-text! annotations-file
                   (edn-text {:schema-version 1
                              :decisions ((rule rule-bundle :resolved-mappings
                                                :annotation-decisions)
                                          ctx)}))
      (write-text! manifest-file
                   (edn-text {:schema-version 1
                              :configuration configuration
                              :rule-bundle (:id rule-bundle)
                              :mechanical-source-headers
                              mechanical-source-header-proof
                              :authorship authorship-ledger
                              :mapping-report mapping-report
                              :sources accounts
                              :resources resource-artifacts
                              :artifacts (mapv #(dissoc % :mappings) artifacts)
                              :summary summary}))
      {:workspace-root root
       :project-root project-root
       :source-root source-root
       :project-file project-file
       :manifest-file manifest-file
       :configuration configuration
       :rule-bundle (:id rule-bundle)
       :mapping-report mapping-report
       :summary summary
       :emission-profile @emission-profile
       :diagnostics (:diagnostics ctx)
       :declarations (:declarations ctx)
       :artifacts artifacts
       :authorship authorship-ledger
       :mechanical-source-header-proof mechanical-source-header-proof
       :source-accounts accounts
       :resource-artifacts resource-artifacts})))
