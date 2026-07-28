(ns dripsharp.java-project
  "Product-neutral Java-to-C# project emission.

  The emitter owns deterministic scheduling, declaration and source accounting,
  collision detection, project/resource output, and destination asset copying.
  Destination rule bundles supply all declaration shapes, resolved mapping
  policy, namespaces, bridges, and optional product runtime assets. This
  namespace deliberately does not load a product rule bundle unless an explicit
  qualified selector is present in destination configuration."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.concurrency :as concurrency]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.project-input :as project-input]
            [dripsharp.project-xml :as project-xml]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.charset Charset MalformedInputException]
           [java.nio.file Files Path StandardCopyOption]
           [java.util IdentityHashMap]
           [spoon.reflect.declaration CtElement CtEnum CtType]
           [spoon.reflect.visitor.filter TypeFilter]))

(def ^:private required-rule-components
  {:structural-declarations
   #{:create-template :create-context :emit-root-node :translate-member
     :merge-context! :context-results}
   :resolved-mappings #{:type-node :create-body-context :annotation-decisions}
   :namespace-policy #{:destination-namespace :destination-file-name}
   :project-policy #{:validate-configuration! :project-text}
   :resource-policy #{:resource-mapping}
   :destination-bridges #{:assets}})

(def translator-version
  "The source-controlled version recorded in every mechanical C# header."
  "0.1.0")

(defn rule-contract
  "Returns the serializable composition contract implemented by every
  destination bundle. Product runtime assets are optional; all other
  components and hooks are required and validated before any file is emitted."
  []
  {:schema-version 1
   :required-components required-rule-components
   :optional-components {:product-runtime-assets #{:assets}
                         :orchestration #{:validate-profile!
                                          :validate-project-input!}}})

(defn- destination-error [message data]
  (throw (ex-info message (assoc data :kind :invalid-destination-configuration))))

(defn- relative-path! [value label]
  (let [value (str value)
        path (paths/path value)]
    (when (or (str/blank? value) (.isAbsolute path)
              (some #(= ".." (str %)) (iterator-seq (.iterator path))))
      (destination-error (str label " must be a safe relative path")
                         {:field label :value value}))
    value))

(defn- project-reference! [value]
  (let [value (str value)
        path (paths/path value)
        segments (mapv str (iterator-seq (.iterator path)))]
    (when (or (str/blank? value) (.isAbsolute path)
              (some #(= ".." %) (rest segments))
              (not (str/ends-with? value ".csproj")))
      (destination-error "Project reference must name a sibling or child csproj"
                         {:field :project-references :value value}))
    value))

(defn validate-configuration!
  "Validates the destination-neutral project, package, namespace, resource,
  output, bridge, and optional runtime-asset configuration contract."
  [configuration]
  (when-not (= 1 (:schema-version configuration))
    (destination-error "Unsupported destination configuration schema"
                       {:schema-version (:schema-version configuration)}))
  (let [selector (:destination-bundle configuration)]
    (when-not (and (symbol? selector) (namespace selector))
      (destination-error
       "Destination bundle must be an explicit namespace-qualified symbol"
       {:destination-bundle selector})))
  (when-not (keyword? (:product-family configuration))
    (destination-error "Destination product family must be an explicit keyword"
                       {:product-family (:product-family configuration)}))
  (doseq [[section keys] [[:project [:assembly-name :root-namespace
                                     :target-framework :nullable :implicit-usings]]
                          [:package [:id :version :title :description :authors :tags
                                     :project-url :repository-url :repository-type]]
                          [:output [:project-directory :source-directory
                                    :resource-directory :project-file
                                    :source-map-file :diagnostics-file
                                    :manifest-file :public-metadata-file
                                    :annotation-decisions-file]]]]
    (when-not (map? (get configuration section))
      (destination-error (str "Missing destination " (name section) " section")
                         {:section section}))
    (doseq [key keys]
      (when-not (contains? (get configuration section) key)
        (destination-error (str "Missing destination setting " section "/" key)
                           {:section section :setting key}))))
  (doseq [key [:id :version :title :description :authors :tags
               :project-url :repository-url :repository-type]]
    (let [value (get-in configuration [:package key])]
      (when-not (and (string? value) (not (str/blank? value)))
        (destination-error "Destination package metadata must be a non-blank string"
                           {:section :package :setting key :value value}))))
  (when-let [commit (get-in configuration [:package :repository-commit])]
    (when-not (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" commit))
      (destination-error "Destination repository commit must be an exact Git identity"
                         {:repository-commit commit})))
  (when-let [license (get-in configuration [:package :license-expression])]
    (when-not (and (string? license) (not (str/blank? license)))
      (destination-error "Destination license expression must be non-blank"
                         {:license-expression license})))
  (when-let [copyright (get-in configuration [:package :copyright])]
    (when-not (and (string? copyright) (not (str/blank? copyright)))
      (destination-error "Destination package copyright must be non-blank"
                         {:copyright copyright})))
  (let [legal-files (:legal-files configuration)]
    (when-not
     (or
      (nil? legal-files)
      (and
       (vector? legal-files)
       (every?
        (fn [{:keys [kind destination package-path]}]
          (and (contains? #{:license :notice} kind)
               (string? destination)
               (relative-path! destination "legal file destination")
               (string? package-path)
               (relative-path! package-path "legal file package path")))
        legal-files)
       (= (count legal-files)
          (count (distinct (map :package-path legal-files))))
       (<= (count (filter #(= :license (:kind %)) legal-files)) 1)))
      (destination-error "Invalid destination legal-file packaging contract"
                         {:legal-files legal-files}))
    (when (and (seq legal-files)
               (get-in configuration [:package :license-expression]))
      (destination-error
       "Destination license expression and packed license file are mutually exclusive"
       {:license-expression
        (get-in configuration [:package :license-expression])
        :legal-files legal-files})))
  (when-let [symbols (get-in configuration [:package :symbols])]
    (when-not (= :snupkg symbols)
      (destination-error "Destination package symbol format is unsupported"
                         {:symbols symbols})))
  (doseq [key [:project-directory :source-directory :resource-directory
               :project-file :source-map-file :diagnostics-file :manifest-file
               :public-metadata-file :annotation-decisions-file]]
    (relative-path! (get-in configuration [:output key]) (name key)))
  (when-not (contains? #{"enable" "disable"}
                       (get-in configuration [:project :nullable]))
    (destination-error "Destination nullable setting must be enable or disable"
                       {:nullable (get-in configuration [:project :nullable])}))
  (when-not (boolean? (get-in configuration [:project :warnings-as-errors]))
    (destination-error "Destination warnings-as-errors policy must be explicit"
                       {:warnings-as-errors
                        (get-in configuration [:project :warnings-as-errors])}))
  (when-not (or (nil? (get-in configuration [:project :define-constants]))
                (and (vector? (get-in configuration [:project :define-constants]))
                     (every? #(and (string? %)
                                   (re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))
                             (get-in configuration [:project :define-constants]))))
    (destination-error "Destination define constants must be C# identifiers"
                       {:define-constants (get-in configuration [:project :define-constants])}))
  (when-not (and (map? (:namespaces configuration))
                 (every? #(and (string? %) (not (str/blank? %)))
                         (mapcat identity (:namespaces configuration))))
    (destination-error "Destination namespace mappings must be non-blank strings"
                       {:namespaces (:namespaces configuration)}))
  (when-not (or (nil? (:namespace-prefixes configuration))
                (and (map? (:namespace-prefixes configuration))
                     (every? #(and (string? %) (not (str/blank? %)))
                             (mapcat identity (:namespace-prefixes configuration)))))
    (destination-error "Destination namespace-prefix mappings must be non-blank strings"
                       {:namespace-prefixes (:namespace-prefixes configuration)}))
  (when-not (and (map? (:resources configuration))
                 (every? (fn [[source {:keys [strategy destination logical-name]}]]
                           (and (= :embedded-resource strategy)
                                (string? source) (string? logical-name)
                                (relative-path! destination "resource destination")))
                         (:resources configuration)))
    (destination-error "Invalid destination resource mapping"
                       {:resources (:resources configuration)}))
  (when-not (or (nil? (:resource-policy configuration))
                (= {:strategy :embedded-resource-preserve-path}
                   (:resource-policy configuration)))
    (destination-error "Invalid destination resource policy"
                       {:resource-policy (:resource-policy configuration)}))
  (when-not (or (nil? (:project-references configuration))
                (and (vector? (:project-references configuration))
                     (every? project-reference! (:project-references configuration))))
    (destination-error "Invalid destination project references"
                       {:project-references (:project-references configuration)}))
  (when-not (or (nil? (:project-dependencies configuration))
                (and (vector? (:project-dependencies configuration))
                     (every? #(and (string? %) (not (str/blank? %)))
                             (:project-dependencies configuration))))
    (destination-error "Invalid source project-dependency contract"
                       {:project-dependencies (:project-dependencies configuration)}))
  (when-not
   (or (nil? (:external-dependencies configuration))
       (and
        (map? (:external-dependencies configuration))
        (every?
         (fn [[coordinate {:keys [source-scope artifact-sha256 runtime-package]}]]
           (and (string? coordinate) (not (str/blank? coordinate))
                (contains? #{:compile-only :compile-runtime} source-scope)
                (or (nil? artifact-sha256)
                    (boolean (re-matches #"[0-9a-f]{64}" artifact-sha256)))
                (boolean? runtime-package)))
         (:external-dependencies configuration))))
    (destination-error "Invalid source external-dependency contract"
                       {:external-dependencies (:external-dependencies configuration)}))
  (when-not (or (nil? (:runtime-sources configuration))
                (and (vector? (:runtime-sources configuration))
                     (every? #(relative-path! % "runtime source")
                             (:runtime-sources configuration))))
    (destination-error "Invalid destination runtime sources"
                       {:runtime-sources (:runtime-sources configuration)}))
  (when-not (or (nil? (:destination-capabilities configuration))
                (and (set? (:destination-capabilities configuration))
                     (every? keyword? (:destination-capabilities configuration))))
    (destination-error "Destination capabilities must be an explicit keyword set"
                       {:destination-capabilities
                        (:destination-capabilities configuration)}))
  (let [mechanical-source (:mechanical-source configuration)
        expected-keys #{:repository :revision :notice-reference}
        single-line? #(and (string? %)
                           (not (str/blank? %))
                           (not (re-find #"[\r\n]" %)))]
    (when-not (and (map? mechanical-source)
                   (= expected-keys (set (keys mechanical-source)))
                   (single-line? (:repository mechanical-source))
                   (boolean
                    (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}"
                                (:revision mechanical-source)))
                   (or (nil? (:notice-reference mechanical-source))
                       (single-line? (:notice-reference mechanical-source))))
      (destination-error
       "Destination mechanical-source provenance must pin its repository, revision, and NOTICE reference"
       {:mechanical-source mechanical-source
        :required-keys expected-keys})))
  (let [surface (:public-surface configuration)]
    (when-not (and (map? surface)
                   (let [selector (:strategy surface)]
                     (and (symbol? selector) (namespace selector))))
      (destination-error
       "Destination public surface must select a namespace-qualified strategy"
       {:public-surface surface})))
  (when-let [consumer (:package-consumer configuration)]
    (when-not (and (map? consumer)
                   (contains? #{:source-file :compile-only} (:strategy consumer))
                   (string? (:project-file consumer))
                   (str/ends-with? (:project-file consumer) ".csproj")
                   (relative-path! (:project-file consumer)
                                   "package consumer project file")
                   (string? (:success-message consumer))
                   (not (str/blank? (:success-message consumer)))
                   (case (:strategy consumer)
                     :compile-only
                     (and (vector? (:compile-types consumer))
                          (seq (:compile-types consumer))
                          (every? #(and (string? %)
                                        (re-matches
                                         #"[A-Za-z_][A-Za-z0-9_]*(?:[.][A-Za-z_][A-Za-z0-9_]*)*"
                                         %))
                                  (:compile-types consumer)))
                     :source-file
                     (let [fixture-file (:fixture-file consumer)
                           source-path (:source-path consumer)]
                       (or
                        (and (string? fixture-file)
                             (not (str/blank? fixture-file))
                             (nil? source-path))
                        (and (nil? fixture-file)
                             (string? source-path)
                             (str/ends-with? source-path ".cs")
                             (relative-path! source-path
                                             "package consumer source path"))))
                     false))
      (destination-error "Invalid independent package-consumer contract"
                         {:package-consumer consumer})))
  configuration)

(defn read-configuration
  "Reads an explicit destination configuration without assuming a product."
  [workspace-root config-file]
  (let [file (paths/resolve-path (paths/absolute workspace-root) config-file)]
    (when-not (paths/regular-file? file)
      (destination-error "Destination configuration is missing" {:path (str file)}))
    (validate-configuration! (edn/read-string (slurp (str file))))))

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
  (when-not (and (map? rule-bundle)
                 (= 1 (:schema-version rule-bundle))
                 (keyword? (:id rule-bundle))
                 (keyword? (:product-family rule-bundle))
                 (map? (:rules rule-bundle)))
    (capability-error! resolved-model "Invalid destination rule bundle"
                       {:rule-bundle (select-keys rule-bundle
                                                  [:schema-version :id])}))
  (doseq [[component required-hooks] required-rule-components]
    (let [rules (get-in rule-bundle [:rules component])]
      (when-not (map? rules)
        (capability-error! resolved-model "Destination rule component is missing"
                           {:bundle (:id rule-bundle) :component component}))
      (doseq [hook required-hooks]
        (when-not (fn? (get rules hook))
          (capability-error! resolved-model "Destination rule capability is missing"
                             {:bundle (:id rule-bundle)
                              :component component :capability hook})))))
  (when-let [runtime-rules (get-in rule-bundle [:rules :product-runtime-assets])]
    (when-not (and (map? runtime-rules) (fn? (:assets runtime-rules)))
      (capability-error! resolved-model "Product runtime asset capability is invalid"
                         {:bundle (:id rule-bundle)
                          :component :product-runtime-assets
                          :capability :assets})))
  (when-let [orchestration (:orchestration rule-bundle)]
    (when-not (and (map? orchestration)
                   (every? (fn [[_ hook]] (fn? hook)) orchestration)
                   (every? #{:validate-profile! :validate-project-input!}
                           (keys orchestration)))
      (capability-error! resolved-model "Destination orchestration capability is invalid"
                         {:bundle (:id rule-bundle)
                          :component :orchestration})))
  rule-bundle)

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
  (let [root (paths/absolute workspace-root)
        diagnostics (:diagnostics ctx)
        by-file (group-by #(get-in % [:source :location :file]) diagnostics)
        outputs-by-file
        (group-by #(get-in % [:source :location :file])
                  (filter #(and (= :type (:kind %)) (nil? (:owner %)))
                          (:declarations ctx)))]
    (mapv
     (fn [source]
       (let [canonical (.getCanonicalPath (.toFile ^Path source))
             types (get outputs-by-file canonical)
             package-info? (= "package-info.java" (str (.getFileName ^Path source)))]
         (when-not (or (seq types) package-info?)
           (throw (ex-info "Production source has no emitted declaration or package mapping"
                           {:kind :unaccounted-production-source :path canonical})))
         {:source (portable root source)
          :strategy (if package-info? :package-nullability-metadata :generated-csharp)
          :top-level-declarations (mapv :name types)
          :hard-failures (count (get by-file canonical))}))
     (sort-by str files))))

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
                  text-replacements]}]
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
          :strategy strategy}))
     (mapcat
      #(expand-asset-tree root %)
      (concat bridge-assets product-assets)))))

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
                             (get-in rule-bundle [:rules :resolved-mappings]))))
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
                              :sources accounts
                              :resources resource-artifacts
                              :artifacts (mapv #(dissoc % :mappings) artifacts)
                              :summary summary}))
      {:project-root project-root
       :project-file project-file
       :manifest-file manifest-file
       :configuration configuration
       :rule-bundle (:id rule-bundle)
       :summary summary
       :emission-profile @emission-profile
       :diagnostics (:diagnostics ctx)
       :declarations (:declarations ctx)
       :artifacts artifacts
       :mechanical-source-header-proof mechanical-source-header-proof
       :source-accounts accounts
       :resource-artifacts resource-artifacts})))
