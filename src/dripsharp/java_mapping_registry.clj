(ns dripsharp.java-mapping-registry
  "Validated declarative mappings from resolved Java identities to C# shapes.

  This namespace is product-neutral.  Targets contribute data entries and
  explicitly registered custom handlers; the interpreter owns the small set of
  common mapping strategies."
  (:require [clojure.string :as str]
            [dripsharp.csharp :as csharp]))

(def schema-version 1)

(def supported-strategies
  #{:rename
    :property-access
    :compat-call
    :argument-reshape
    :template
    :custom-handler})

(def ^:private mapping-kinds
  #{:type :executable :constructor :field})

(def ^:private required-entry-keys
  #{:id :key :strategy :caveats :introduced-by :evidence})

(def ^:private common-entry-keys
  (into required-entry-keys
        #{:kind :required-usings :required-helpers}))

(def ^:private strategy-contracts
  {:rename
   {:required #{:destination}
    :allowed #{:destination}
    :kinds mapping-kinds}

   :property-access
   {:required #{:destination}
    :allowed #{:destination}
    :kinds #{:executable :field}}

   :compat-call
   {:required #{:destination}
    :allowed #{:destination}
    :kinds #{:executable :constructor :field}}

   :argument-reshape
   {:required #{:destination :call :arguments}
    :allowed #{:destination :call :receiver :arguments}
    :kinds #{:executable :constructor}}

   :template
   {:required #{:template}
    :allowed #{:template}
    :kinds mapping-kinds}

   :custom-handler
   {:required #{:handler}
    :allowed #{:handler}
    :kinds mapping-kinds}})

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- present-string?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (not (re-find #"[\r\n\u0000]" value))))

(defn- component?
  [value]
  (and (present-string? value)
       (= value (str/trim value))))

(defn- parameter-list?
  [parameters]
  (or (empty? parameters)
      (every? #(and (component? %)
                    (not (re-find #"[#()]" %)))
              (str/split parameters #"," -1))))

(defn- executable-key-kind
  [key]
  (when-let [[_ owner member parameters]
             (and (string? key)
                  (re-matches
                   #"^executable:([^#()\r\n]+)#([^#()\r\n]+)\(([^()\r\n]*)\)$"
                   key))]
    (when (and (component? owner)
               (component? member)
               (not (str/includes? member " "))
               (parameter-list? parameters))
      (if (= "<init>" member) :constructor :executable))))

(defn- executable-parameter-count
  [key]
  (when-let [[_ _ _ parameters]
             (and (string? key)
                  (re-matches
                   #"^executable:([^#()\r\n]+)#([^#()\r\n]+)\(([^()\r\n]*)\)$"
                   key))]
    (if (empty? parameters)
      0
      (count (str/split parameters #"," -1)))))

(defn- type-key?
  [key]
  (when (and (string? key) (str/starts-with? key "type:"))
    (let [identity (subs key (count "type:"))]
      (and (component? identity)
           (not (re-find #"[#(),]" identity))))))

(defn- type-parameter-key?
  [key]
  (when (and (string? key)
             (str/starts-with? key "type-parameter:"))
    (let [identity (subs key (count "type-parameter:"))
          separator (.lastIndexOf ^String identity "#")]
      (and (pos? separator)
           (< separator (dec (count identity)))
           (component? (subs identity 0 separator))
           (re-matches #"[A-Za-z_$][A-Za-z0-9_$]*"
                       (subs identity (inc separator)))))))

(defn- field-key?
  [key]
  (when-let [[_ owner member]
             (and (string? key)
                  (re-matches #"^field:([^#\r\n]+)#([^#\r\n]+)$" key))]
    (and (component? owner)
         (component? member)
         (not (str/includes? owner " "))
         (not (str/includes? member " ")))))

(defn resolved-key-kind
  "Returns the registry kind for one exact Spoon resolved-symbol key, or nil
  when the key does not match the supported fail-closed grammar.  Constructors
  retain Spoon's executable key spelling but have their own mapping kind."
  [key]
  (cond
    (type-key? key) :type
    (type-parameter-key? key) :type
    (field-key? key) :field
    :else (executable-key-kind key)))

(defn- reference?
  [value]
  (or (qualified-keyword? value)
      (present-string? value)))

(defn- handler?
  [value]
  (and (ifn? value)
       (not (coll? value))
       (not (keyword? value))
       (not (symbol? value))))

(defn- selector?
  [selector]
  (or (#{:target :arguments :type-arguments} selector)
      (and (vector? selector)
           (= 2 (count selector))
           (case (first selector)
             :argument (nat-int? (second selector))
             :literal (string? (second selector))
             false))))

(defn- template-token?
  [token]
  (or (string? token) (selector? token)))

(defn- validate-handler-registry!
  [handlers]
  (when-not (map? handlers)
    (fail! "Custom mapping handlers must be a map"
           {:kind :invalid-custom-handler-registry
            :handlers handlers}))
  (doseq [[id handler] handlers]
    (when-not (and (qualified-keyword? id) (handler? handler))
      (fail! "Invalid registered custom mapping handler"
             {:kind :invalid-custom-handler
              :handler-id id
              :handler handler})))
  handlers)

(defn- validate-common-metadata!
  [{:keys [id key kind strategy caveats introduced-by evidence
           required-usings required-helpers]
    :as entry}]
  (let [actual-kind (resolved-key-kind key)]
    (when-not (= required-entry-keys
                 (set (filter #(contains? entry %) required-entry-keys)))
      (fail! "Declarative mapping entry is missing required metadata"
             {:kind :incomplete-mapping-entry
              :missing (vec (sort (remove #(contains? entry %)
                                          required-entry-keys)))
              :entry entry}))
    (when-not (qualified-keyword? id)
      (fail! "Declarative mapping identity must be a qualified keyword"
             {:kind :invalid-mapping-identity :entry entry}))
    (when-not actual-kind
      (fail! "Declarative mapping has a malformed resolved-symbol key"
             {:kind :malformed-resolved-symbol-key :key key :entry entry}))
    (when (and (contains? entry :kind) (not= kind actual-kind))
      (fail! "Declarative mapping kind contradicts its resolved-symbol key"
             {:kind :contradictory-mapping-entry
              :declared-kind kind
              :resolved-kind actual-kind
              :entry entry}))
    (when-not (contains? supported-strategies strategy)
      (fail! "Declarative mapping uses an unsupported strategy"
             {:kind :unsupported-mapping-strategy
              :strategy strategy
              :entry entry}))
    (when-not (and (set? caveats) (every? keyword? caveats))
      (fail! "Declarative mapping caveats must be a set of keywords"
             {:kind :invalid-mapping-caveats :entry entry}))
    (when-not (keyword? introduced-by)
      (fail! "Declarative mapping must identify its introducing target"
             {:kind :invalid-mapping-introducing-target :entry entry}))
    (when-not (and (set? evidence) (every? reference? evidence))
      (fail! "Declarative mapping evidence must be a set of references"
             {:kind :invalid-mapping-evidence :entry entry}))
    (when (and (seq caveats) (empty? evidence))
      (fail! "A caveated declarative mapping requires evidence"
             {:kind :unevidenced-mapping-caveat :entry entry}))
    (when-not (and (set? (or required-usings #{}))
                   (every? present-string? (or required-usings #{})))
      (fail! "Declarative mapping required usings must be strings"
             {:kind :invalid-mapping-usings :entry entry}))
    (when-not (and (set? (or required-helpers #{}))
                   (every? keyword? (or required-helpers #{})))
      (fail! "Declarative mapping required helpers must be keywords"
             {:kind :invalid-mapping-helpers :entry entry}))
    actual-kind))

(defn- validate-strategy!
  [entry actual-kind custom-handlers]
  (let [{:keys [strategy] :as mapping} entry
        {:keys [required allowed kinds]} (get strategy-contracts strategy)
        entry-keys (set (keys mapping))
        unexpected (remove (into common-entry-keys allowed) entry-keys)
        missing (remove #(contains? mapping %) required)]
    (when (seq unexpected)
      (fail! "Declarative mapping combines contradictory strategy fields"
             {:kind :contradictory-mapping-entry
              :strategy strategy
              :unexpected (vec (sort unexpected))
              :entry entry}))
    (when (seq missing)
      (fail! "Declarative mapping strategy is missing required fields"
             {:kind :incomplete-mapping-strategy
              :strategy strategy
              :missing (vec (sort missing))
              :entry entry}))
    (when-not (contains? kinds actual-kind)
      (fail! "Declarative mapping strategy does not support this symbol kind"
             {:kind :contradictory-mapping-entry
              :strategy strategy
              :mapping-kind actual-kind
              :entry entry}))
    (when (and (= :property-access strategy)
               (= :executable actual-kind)
               (not (zero? (executable-parameter-count (:key entry)))))
      (fail! "Executable property access requires a zero-argument source member"
             {:kind :contradictory-mapping-entry
              :strategy strategy
              :entry entry}))
    (when (contains? #{:rename :property-access :compat-call
                       :argument-reshape}
                     strategy)
      (when-not (present-string? (:destination entry))
        (fail! "Declarative mapping destination must be a nonblank string"
               {:kind :invalid-mapping-destination :entry entry})))
    (case strategy
      :argument-reshape
      (do
        (when-not (contains? #{:static :member :constructor} (:call entry))
          (fail! "Argument reshape has an unsupported call shape"
                 {:kind :unsupported-mapping-call-shape :entry entry}))
        (when-not (and (vector? (:arguments entry))
                       (every? selector? (:arguments entry)))
          (fail! "Argument reshape selectors are malformed"
                 {:kind :invalid-mapping-selectors :entry entry}))
        (if (= :member (:call entry))
          (when-not (and (contains? entry :receiver)
                         (selector? (:receiver entry))
                         (not (#{:arguments :type-arguments}
                               (:receiver entry))))
            (fail! "Member argument reshape requires one receiver selector"
                   {:kind :invalid-mapping-receiver :entry entry}))
          (when (contains? entry :receiver)
            (fail! "Only a member argument reshape may declare a receiver"
                   {:kind :contradictory-mapping-entry :entry entry}))))

      :template
      (when-not (and (vector? (:template entry))
                     (seq (:template entry))
                     (every? template-token? (:template entry)))
        (fail! "Declarative mapping template is malformed"
               {:kind :invalid-mapping-template :entry entry}))

      :custom-handler
      (let [handler-id (:handler entry)]
        (when-not (and (qualified-keyword? handler-id)
                       (contains? custom-handlers handler-id))
          (fail! "Declarative mapping references an unregistered custom handler"
                 {:kind :unregistered-custom-mapping-handler
                  :handler-id handler-id
                  :entry entry})))

      nil))
  entry)

(defn- validate-entry!
  [entry custom-handlers]
  (when-not (map? entry)
    (fail! "Declarative mapping entry must be a map"
           {:kind :invalid-mapping-entry :entry entry}))
  (let [actual-kind (validate-common-metadata! entry)]
    (validate-strategy! entry actual-kind custom-handlers)
    (assoc entry
           :kind actual-kind
           :required-usings (or (:required-usings entry) #{})
           :required-helpers (or (:required-helpers entry) #{}))))

(defn- duplicate-groups
  [entries key-fn]
  (->> entries
       (group-by key-fn)
       (filter (fn [[_ owned]] (> (count owned) 1)))
       (sort-by (comp pr-str first))
       vec))

(defn compile-registry
  "Validates and indexes a sequence of declarative mapping entries.

  Entries remain sequential at this boundary so duplicate resolved-key
  ownership cannot be silently overwritten by Clojure map construction.
  Options may contain only `:custom-handlers`, a map from qualified handler ids
  to functions."
  ([entries]
   (compile-registry entries {}))
  ([entries options]
   (when-not (map? options)
     (fail! "Declarative mapping registry options must be a map"
            {:kind :invalid-mapping-registry-options :options options}))
   (let [custom-handlers (get options :custom-handlers {})
         unexpected-options (remove #{:custom-handlers} (keys options))]
     (when (seq unexpected-options)
       (fail! "Unknown declarative mapping registry option"
              {:kind :unknown-mapping-registry-option
               :options (vec (sort unexpected-options))}))
     (when-not (and (sequential? entries) (not (string? entries)))
       (fail! "Declarative mapping registry entries must be sequential"
              {:kind :invalid-mapping-registry-entries :entries entries}))
     (let [handlers (validate-handler-registry! custom-handlers)
           normalized (mapv #(validate-entry! % handlers) entries)
           duplicate-keys (duplicate-groups normalized :key)
           duplicate-ids (duplicate-groups normalized :id)]
       (when (seq duplicate-keys)
         (fail! "Resolved-symbol mapping ownership must be unique"
                {:kind :duplicate-mapping-ownership
                 :duplicates
                 (mapv (fn [[key owned]]
                         {:key key
                          :owners (mapv :introduced-by owned)
                          :identities (mapv :id owned)})
                       duplicate-keys)}))
       (when (seq duplicate-ids)
         (fail! "Mapping identity describes contradictory entries"
                {:kind :contradictory-mapping-identities
                 :duplicates
                 (mapv (fn [[id owned]]
                         {:id id :keys (mapv :key owned)})
                       duplicate-ids)}))
       {::compiled? true
        :schema-version schema-version
        :entries (into (sorted-map) (map (juxt :key identity)) normalized)
        :by-kind
        (into (sorted-map)
              (for [kind (sort mapping-kinds)]
                [kind
                 (into (sorted-map)
                       (map (juxt :key identity))
                       (filter #(= kind (:kind %)) normalized))]))
        :custom-handlers handlers}))))

(defn compiled-registry?
  [registry]
  (and (map? registry)
       (true? (::compiled? registry))
       (= schema-version (:schema-version registry))
       (map? (:entries registry))
       (map? (:custom-handlers registry))))

(defn registry-entry
  "Returns one validated declarative entry, or nil when the exact resolved key
  is not owned by the registry."
  [registry resolved-key]
  (when-not (compiled-registry? registry)
    (fail! "Expected a compiled declarative mapping registry"
           {:kind :invalid-compiled-mapping-registry}))
  (get (:entries registry) resolved-key))

(defn registry-entries
  "Returns the validated entries in stable resolved-key order."
  [registry]
  (when-not (compiled-registry? registry)
    (fail! "Expected a compiled declarative mapping registry"
           {:kind :invalid-compiled-mapping-registry}))
  (vec (vals (:entries registry))))

(defn- report-entry
  [registry entry occurrence-count]
  {:registry registry
   :identity (str (:id entry))
   :resolved-key (:key entry)
   :kind (:kind entry)
   :strategy (:strategy entry)
   :caveats (vec (sort-by pr-str (:caveats entry)))
   :introduced-by (:introduced-by entry)
   :evidence (vec (sort-by pr-str (:evidence entry)))
   :occurrences occurrence-count})

(defn- validate-registry-set!
  [registries]
  (when-not (map? registries)
    (fail! "Declarative mapping report registries must be a map"
           {:kind :invalid-mapping-report-registries
            :registries registries}))
  (doseq [[registry compiled] registries]
    (when-not (and (keyword? registry) (compiled-registry? compiled))
      (fail! "Declarative mapping report registry is invalid"
             {:kind :invalid-mapping-report-registry
              :registry registry})))
  (let [owned
        (mapcat
         (fn [[registry compiled]]
           (map #(assoc % ::registry registry)
                (registry-entries compiled)))
         (sort-by (comp pr-str key) registries))
        duplicate-keys (duplicate-groups owned :key)
        duplicate-identities (duplicate-groups owned :id)]
    (when (seq duplicate-keys)
      (fail! "Selected declarative registries contradict resolved-key ownership"
             {:kind :contradictory-mapping-registry-ownership
              :duplicates
              (mapv
               (fn [[key entries]]
                 {:resolved-key key
                  :registries (mapv ::registry entries)
                  :identities (mapv :id entries)})
               duplicate-keys)}))
    (when (seq duplicate-identities)
      (fail! "Selected declarative registries contradict mapping identities"
             {:kind :contradictory-mapping-registry-identities
              :duplicates
              (mapv
               (fn [[identity entries]]
                 {:identity identity
                  :registries (mapv ::registry entries)
                  :resolved-keys (mapv :key entries)})
               duplicate-identities)}))
    (into {}
          (map (fn [entry]
                 [(:key entry)
                  {:registry (::registry entry)
                   :entry (dissoc entry ::registry)}]))
          owned)))

(defn resolved-occurrence-report
  "Joins a complete resolved-occurrence sequence against the selected
  declarative registries.

  `mapping-required?` identifies occurrences whose selected target contract
  requires declarative ownership. The result is deterministic: used mappings
  and unmapped symbols are ranked by descending occurrence count with exact
  resolved identity as the stable tie-breaker."
  [occurrences registries mapping-required?]
  (when-not (and (sequential? occurrences) (not (string? occurrences)))
    (fail! "Resolved mapping report occurrences must be sequential"
           {:kind :invalid-mapping-report-occurrences}))
  (when-not (ifn? mapping-required?)
    (fail! "Resolved mapping report requires a mapping ownership predicate"
           {:kind :invalid-mapping-report-predicate}))
  (let [owners (validate-registry-set! registries)
        required
        (mapv
         (fn [occurrence]
           (when-not (map? occurrence)
             (fail! "Resolved mapping report occurrence must be a map"
                    {:kind :invalid-mapping-report-occurrence
                     :occurrence occurrence}))
           (let [required? (mapping-required? occurrence)]
             (when-not (instance? Boolean required?)
               (fail! "Mapping ownership predicate must return a boolean"
                      {:kind :invalid-mapping-report-predicate-result
                       :resolved-key (:key occurrence)
                       :result required?}))
             (assoc occurrence ::mapping-required? required?)))
         occurrences)
        required (filterv ::mapping-required? required)
        mapped (filterv #(contains? owners (:key %)) required)
        unmapped (remove #(contains? owners (:key %)) required)
        used-mappings
        (->> mapped
             (group-by :key)
             (map
              (fn [[resolved-key owned-occurrences]]
                (let [{:keys [registry entry]} (get owners resolved-key)]
                  (report-entry registry entry (count owned-occurrences)))))
             (sort-by (juxt (comp - :occurrences) :resolved-key))
             vec)
        unmapped-symbols
        (->> unmapped
             (group-by :key)
             (map
              (fn [[resolved-key missing-occurrences]]
                {:resolved-key resolved-key
                 :kinds (vec (sort-by pr-str
                                      (set (map :kind missing-occurrences))))
                 :origins (vec (sort-by pr-str
                                        (set (map :origin missing-occurrences))))
                 :occurrences (count missing-occurrences)}))
             (sort-by (juxt (comp - :occurrences) :resolved-key))
             vec)]
    {:schema-version 1
     :summary
     {:total-occurrences (count occurrences)
      :mapping-required-occurrences (count required)
      :mapped-occurrences (count mapped)
      :unmapped-occurrences (count unmapped)
      :used-mappings (count used-mappings)
      :unmapped-symbols (count unmapped-symbols)}
     :used-mappings used-mappings
     :unmapped-symbols unmapped-symbols}))

(defn require-complete-occurrence-report!
  "Fails closed with the complete frequency-ranked backlog when any required
  resolved identity lacks declarative ownership."
  [report]
  (when (seq (:unmapped-symbols report))
    (let [{:keys [resolved-key kinds origins]}
          (first (:unmapped-symbols report))]
      (fail! "Selected target has unmapped resolved symbols"
             {:kind :java-translation-coverage-failed
              :reason :unmapped-resolved-symbols
              :diagnostic
              {:resolved
               {:key resolved-key
                :kind (first kinds)
                :origin (first origins)}}
              :mapping-report report
              :unmapped-symbols (:unmapped-symbols report)})))
  report)

(defn- node!
  [node role]
  (try
    (csharp/render node)
    node
    (catch Throwable error
      (throw (ex-info "Mapping interpreter received an invalid C# node"
                      {:kind :invalid-mapping-input-node
                       :role role
                       :node node}
                      error)))))

(defn- normalize-input
  [input]
  (when-not (map? input)
    (fail! "Declarative mapping interpreter input must be a map"
           {:kind :invalid-mapping-input :input input}))
  (let [arguments (or (:arguments input) [])
        type-arguments (or (:type-arguments input) [])]
    (when-not (sequential? arguments)
      (fail! "Declarative mapping arguments must be sequential"
             {:kind :invalid-mapping-arguments :arguments arguments}))
    (when-not (sequential? type-arguments)
      (fail! "Declarative mapping type arguments must be sequential"
             {:kind :invalid-mapping-type-arguments
              :type-arguments type-arguments}))
    (assoc input
           :target (when-let [target (:target input)]
                     (node! target :target))
           :arguments (mapv #(node! % :argument) arguments)
           :type-arguments (mapv #(node! % :type-argument) type-arguments))))

(defn- require-target
  [{:keys [target]} entry]
  (or target
      (fail! "Declarative mapping requires an invocation target"
             {:kind :missing-mapping-target
              :key (:key entry)
              :strategy (:strategy entry)})))

(defn- destination-node
  [destination type-arguments]
  (cond-> (csharp/raw destination)
    (seq type-arguments) (csharp/generic-name type-arguments)))

(defn- invocation-node
  [target destination arguments type-arguments]
  (let [call-target (if target
                      (csharp/member target destination)
                      (destination-node destination type-arguments))]
    (csharp/invocation call-target arguments)))

(defn- constructor-node
  [destination arguments type-arguments]
  (csharp/sequence-node
   [(csharp/raw "new ")
    (csharp/invocation
     (destination-node destination type-arguments)
     arguments)]))

(defn- no-arguments!
  [entry arguments]
  (when (seq arguments)
    (fail! "Declarative field or property mapping received arguments"
           {:kind :unexpected-mapping-arguments
            :key (:key entry)
            :arguments (count arguments)})))

(defn- rename-node
  [{:keys [kind destination] :as entry}
   {:keys [target arguments type-arguments]}]
  (case kind
    :type
    (do
      (no-arguments! entry arguments)
      (destination-node destination type-arguments))

    :constructor
    (constructor-node destination arguments type-arguments)

    :executable
    (invocation-node target destination arguments type-arguments)

    :field
    (do
      (no-arguments! entry arguments)
      (if target
        (csharp/member target destination)
        (csharp/raw destination)))))

(defn- property-node
  [{:keys [destination] :as entry} {:keys [target arguments]}]
  (no-arguments! entry arguments)
  (if target
    (csharp/member target destination)
    (csharp/raw destination)))

(defn- compat-call-node
  [{:keys [destination]} {:keys [target arguments type-arguments]}]
  (csharp/invocation
   (destination-node destination type-arguments)
   (if target (into [target] arguments) arguments)))

(defn- selector-nodes
  [{:keys [target arguments type-arguments]} entry selector]
  (cond
    (= :target selector)
    [(require-target {:target target} entry)]

    (= :arguments selector)
    arguments

    (= :type-arguments selector)
    type-arguments

    (= :argument (first selector))
    (let [index (second selector)]
      (if (< index (count arguments))
        [(nth arguments index)]
        (fail! "Declarative mapping argument selector is out of range"
               {:kind :mapping-argument-out-of-range
                :key (:key entry)
                :index index
                :argument-count (count arguments)})))

    (= :literal (first selector))
    [(csharp/raw (second selector))]

    :else
    (fail! "Declarative mapping selector is unsupported"
           {:kind :unsupported-mapping-selector
            :key (:key entry)
            :selector selector})))

(defn- single-selector-node
  [input entry selector]
  (let [nodes (selector-nodes input entry selector)]
    (if (= 1 (count nodes))
      (first nodes)
      (fail! "Declarative mapping receiver selector must yield one node"
             {:kind :invalid-mapping-receiver-result
              :key (:key entry)
              :selector selector
              :node-count (count nodes)}))))

(defn- reshaped-node
  [{:keys [destination call receiver arguments] :as entry}
   {:keys [type-arguments] :as input}]
  (let [reshaped (vec (mapcat #(selector-nodes input entry %) arguments))]
    (case call
      :static
      (csharp/invocation
       (destination-node destination type-arguments)
       reshaped)

      :member
      (csharp/invocation
       (csharp/member (single-selector-node input entry receiver) destination)
       reshaped)

      :constructor
      (constructor-node destination reshaped type-arguments))))

(defn- template-token-node
  [{:keys [arguments type-arguments] :as input} entry token]
  (cond
    (string? token) (csharp/raw token)
    (= :arguments token) (csharp/sequence-node arguments ", ")
    (= :type-arguments token) (csharp/sequence-node type-arguments ", ")
    :else (single-selector-node input entry token)))

(defn- template-node
  [{:keys [template] :as entry} input]
  (csharp/sequence-node
   (mapv #(template-token-node input entry %) template)))

(defn- custom-fragment
  [registry {:keys [handler] :as entry} input]
  (let [handler-fn (get (:custom-handlers registry) handler)]
    (try
      (let [fragment (handler-fn (assoc input :mapping-entry entry))]
        (when-not (and (map? fragment) (contains? fragment :node))
          (fail! "Custom mapping handler must return a fragment with a node"
                 {:kind :invalid-custom-mapping-result
                  :handler handler
                  :key (:key entry)
                  :result fragment}))
        (node! (:node fragment) :custom-handler-result)
        fragment)
      (catch Throwable error
        (if (= :invalid-custom-mapping-result (:kind (ex-data error)))
          (throw error)
          (throw (ex-info "Registered custom mapping handler failed"
                          {:kind :custom-mapping-handler-failed
                           :handler handler
                           :key (:key entry)}
                          error)))))))

(defn- mapping-evidence
  [entry]
  {:identity (:id entry)
   :resolved-key (:key entry)
   :kind (:kind entry)
   :strategy (:strategy entry)
   :caveats (:caveats entry)
   :introduced-by (:introduced-by entry)
   :evidence (:evidence entry)})

(defn interpret
  "Interprets one exact resolved-symbol mapping.

  Input is a map with optional structured C# `:target`, `:arguments`, and
  `:type-arguments`; custom handlers also receive all additional input keys.
  The result is an emission fragment containing `:node`, accumulated helper and
  using requirements, and stable mapping evidence."
  [registry resolved-key input]
  (let [entry
        (or (registry-entry registry resolved-key)
            (fail! "Resolved symbol has no declarative mapping"
                   {:kind :unmapped-resolved-symbol
                    :resolved-key resolved-key}))
        normalized-input (normalize-input input)
        fragment
        (case (:strategy entry)
          :rename {:node (rename-node entry normalized-input)}
          :property-access {:node (property-node entry normalized-input)}
          :compat-call {:node (compat-call-node entry normalized-input)}
          :argument-reshape {:node (reshaped-node entry normalized-input)}
          :template {:node (template-node entry normalized-input)}
          :custom-handler (custom-fragment registry entry normalized-input)
          (fail! "Compiled mapping contains an unsupported strategy"
                 {:kind :unsupported-mapping-strategy
                  :entry entry}))]
    (-> fragment
        (update :required-usings
                #(into (set (or % #{})) (:required-usings entry)))
        (update :required-helpers
                #(into (set (or % #{})) (:required-helpers entry)))
        (assoc :mapping (mapping-evidence entry)))))
