(ns vibeformer.java-translate
  "Fail-closed recursive translation from live resolved Spoon elements.

  The kernel never inventories or reconstructs Java syntax.  Every result is
  produced by visiting a live element and its live direct children.  Semantic
  references dispatch through the stable resolved identities supplied by
  vibeformer.spoon."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code CtExpression CtStatement]
           [spoon.reflect.declaration CtAnnotation CtElement CtExecutable CtType]
           [spoon.reflect.reference CtExecutableReference CtFieldReference
            CtTypeReference]))

(def ^:private mapping-categories
  #{:types :executables :constructors :fields :annotations})

(defn structural-rules
  "Validates an ordered structural rule vector.  A rule is
  {:id keyword :class Spoon-interface :emit fn}.  More-specific interfaces must
  precede broader interfaces; this explicit order is part of the registry."
  [rules]
  (let [rules (vec rules)]
    (doseq [{:keys [id class emit] :as rule} rules]
      (when-not (and (keyword? id) (instance? Class class) (ifn? emit))
        (throw (ex-info "Invalid Java structural translation rule"
                        {:kind :invalid-structural-rule :rule rule}))))
    (when-not (= (count rules) (count (distinct (map :id rules))))
      (throw (ex-info "Java structural rule identities must be unique"
                      {:kind :duplicate-structural-rule :rules rules})))
    (when-not (= (count rules) (count (distinct (map :class rules))))
      (throw (ex-info "Java structural rule classes must be unique"
                      {:kind :duplicate-structural-class :rules rules})))
    rules))

(defn- valid-mapping-key?
  [category key]
  (and (string? key)
       (case category
         :types (or (str/starts-with? key "type:")
                    (str/starts-with? key "type-parameter:"))
         :executables (str/starts-with? key "executable:")
         :constructors (str/starts-with? key "executable:")
         :fields (str/starts-with? key "field:")
         :annotations (str/starts-with? key "annotation:")
         false)))

(defn mapping-registries
  "Validates semantic mapping registries keyed by resolved symbol identity.
  Mapping values are {:id keyword :emit fn}; emit receives a map containing the
  live element, its translated children, and its resolved occurrence."
  [registries]
  (let [unexpected (seq (remove mapping-categories (keys registries)))
        normalized (into {}
                         (for [category mapping-categories]
                           [category (or (get registries category) {})]))]
    (when unexpected
      (throw (ex-info "Unknown Java semantic mapping registry"
                      {:kind :unknown-mapping-registry
                       :categories (vec (sort unexpected))})))
    (doseq [[category mappings] normalized]
      (when-not (map? mappings)
        (throw (ex-info "Java semantic mapping registry must be a map"
                        {:kind :invalid-mapping-registry
                         :category category
                         :registry mappings})))
      (doseq [[key {:keys [id emit] :as mapping}] mappings]
        (when-not (and (valid-mapping-key? category key)
                       (keyword? id)
                       (ifn? emit))
          (throw (ex-info "Invalid resolved-symbol mapping"
                          {:kind :invalid-symbol-mapping
                           :category category
                           :key key
                           :mapping mapping})))))
    normalized))

(defn resolved-occurrence-index
  "Builds an identity index over the resolver's live reference objects.  Java
  equality and rendered text are deliberately not used for lookup."
  [resolved-model]
  (let [index (IdentityHashMap.)]
    (doseq [occurrence (:occurrences resolved-model)]
      (.put index (:reference occurrence) occurrence))
    index))

(defn context
  "Creates a reusable translation context for a resolved Spoon model."
  [resolved-model {:keys [rules mappings mode diagnostic-fallback]
                   :or {rules [] mappings {} mode :accepted}}]
  (when-not (#{:accepted :diagnostic} mode)
    (throw (ex-info "Java translation mode must be :accepted or :diagnostic"
                    {:kind :invalid-translation-mode :mode mode})))
  (when-not (or (nil? diagnostic-fallback) (ifn? diagnostic-fallback))
    (throw (ex-info "Diagnostic fallback must be a function"
                    {:kind :invalid-diagnostic-fallback})))
  {:resolved-model resolved-model
   :occurrence-index (resolved-occurrence-index resolved-model)
   :rules (structural-rules rules)
   :mappings (mapping-registries mappings)
   :mode mode
   :diagnostic-fallback diagnostic-fallback})

(defn child-result
  "Returns the already translated result for an exact live direct child."
  [children ^CtElement child]
  (or (some (fn [result]
              (when (identical? child (:source-element result)) result))
            children)
      (throw (ex-info "Translation rule requested a non-child Spoon element"
                      {:kind :non-child-translation-access
                       :child (when child (spoon/frontend-diagnostic child))}))))

(defn- blocking-diagnostic
  ([kind ^CtElement element message]
   (blocking-diagnostic kind element message nil))
  ([kind ^CtElement element message resolved]
   (cond-> {:severity :error
            :blocking? true
            :kind kind
            :message message
            :location (spoon/source-location element)
            :frontend (spoon/frontend-diagnostic element)}
     resolved (assoc :resolved resolved))))

(defn- reference-element?
  [element]
  (or (instance? CtTypeReference element)
      (instance? CtExecutableReference element)
      (instance? CtFieldReference element)
      (instance? CtAnnotation element)))

(defn- occurrence-category
  [occurrence]
  (case (:kind occurrence)
    :type :types
    :executable :executables
    :constructor :constructors
    :field :fields
    :annotation :annotations
    nil))

(defn- semantic-plan
  [translation-context ^CtElement element]
  (when (reference-element? element)
    (if-let [occurrence (.get ^IdentityHashMap (:occurrence-index translation-context)
                              element)]
      (let [category (occurrence-category occurrence)
            mapping (get-in translation-context [:mappings category (:key occurrence)])]
        (if mapping
          {:kind :semantic
           :rule (:id mapping)
           :mapping {:registry category
                     :identity (:id mapping)
                     :resolved-key (:key occurrence)
                     :origin (:origin occurrence)
                     :resolution (:resolution occurrence)}
           :occurrence occurrence
           :emit (:emit mapping)}
          {:kind :missing-mapping
           :diagnostic
           (blocking-diagnostic
            :unsupported-resolved-symbol element
            (str "No " (name category) " mapping for " (:key occurrence))
            (select-keys occurrence [:kind :key :origin :resolution]))}))
      {:kind :missing-occurrence
       :diagnostic
       (blocking-diagnostic
        :unresolved-reference-occurrence element
        "Spoon reference was not present in the resolved occurrence index")})))

(defn- structural-plan
  [translation-context ^CtElement element]
  (when-let [rule (some #(when (instance? (:class %) element) %)
                        (:rules translation-context))]
    {:kind :structural
     :rule (:id rule)
     :emit (:emit rule)}))

(defn- unsupported-plan
  [^CtElement element]
  {:kind :unsupported
   :diagnostic
   (blocking-diagnostic
    :unsupported-java-element element
    (str "No structural translation rule for " (.getName (class element))))})

(defn- plan-for
  [translation-context element]
  (or (semantic-plan translation-context element)
      (structural-plan translation-context element)
      (unsupported-plan element)))

(defn- direct-children
  [^CtElement element]
  (vec (.getDirectChildren element)))

(defn- emit-plan
  [translation-context plan element children]
  (cond
    (and (:emit plan)
         (some #(some :blocking? (:diagnostics %)) children))
    ;; A child owns the actionable failure.  Do not manufacture cascading
    ;; parent rule failures merely because its destination node is absent.
    {}

    (:emit plan)
    (let [emit (:emit plan)]
      (try
        (or (emit {:context translation-context
                   :element element
                   :children children
                   :occurrence (:occurrence plan)
                   :mapping (:mapping plan)})
            {})
        (catch Throwable error
          {:diagnostics
           [(blocking-diagnostic
             :translation-rule-failed element
             (str "Translation rule " (:rule plan) " failed: " (.getMessage error))
             (when-let [occurrence (:occurrence plan)]
               (select-keys occurrence [:kind :key :origin :resolution])))]})))

    :else
    (let [diagnostic (:diagnostic plan)
          fallback (when (and (= :diagnostic (:mode translation-context))
                              (:diagnostic-fallback translation-context))
                     ((:diagnostic-fallback translation-context)
                      {:element element :diagnostic diagnostic}))]
      (cond-> {:diagnostics [diagnostic]}
        fallback (assoc :node fallback :fallback? true)))))

(defn- normalize-fragment
  [fragment]
  (let [diagnostics (vec (or (:diagnostics fragment) []))
        required-usings (set (or (:required-usings fragment) #{}))
        required-helpers (set (or (:required-helpers fragment) #{}))]
    (when-not (every? map? diagnostics)
      (throw (ex-info "Translation diagnostics must be maps"
                      {:kind :invalid-translation-diagnostics
                       :diagnostics diagnostics})))
    (assoc fragment
           :diagnostics diagnostics
           :required-usings required-usings
           :required-helpers required-helpers)))

(defn- combined-set
  [children key own]
  (reduce into own (map key children)))

(defn- combined-vector
  [children key own]
  (into (vec own) (mapcat key children)))

(declare translate-element*)

(defn- translate-element*
  [translation-context ^CtElement element]
  (when-not (instance? CtElement element)
    (throw (ex-info "Java translation requires a live Spoon CtElement"
                    {:kind :invalid-java-translation-root :root element})))
  (let [plan (plan-for translation-context element)
        children (mapv #(translate-element* translation-context %)
                       (direct-children element))
        fragment (normalize-fragment
                  (emit-plan translation-context plan element children))
        own-diagnostics (:diagnostics fragment)
        own-blocked? (boolean (some :blocking? own-diagnostics))
        source {:identity (spoon/frontend-identity element)
                :location (spoon/source-location element)
                :rule (:rule plan)
                :mapping (:mapping plan)}
        node (some-> (:node fragment) (csharp/with-source source))
        visit {:source-element element
               :source-identity (:identity source)
               :source-location (:location source)
               :frontend-category (cond
                                    (instance? CtTypeReference element) :type
                                    (instance? CtExecutableReference element) :executable
                                    (instance? CtFieldReference element) :field
                                    (instance? CtAnnotation element) :annotation
                                    (instance? CtType element) :declaration
                                    (instance? CtExecutable element) :declaration
                                    (instance? CtStatement element) :statement
                                    (instance? CtExpression element) :expression
                                    :else :element)
               :dispatch-kind (:kind plan)
               :rule (:rule plan)
               :mapping (:mapping plan)
               :fallback? (boolean (:fallback? fragment))
               :status (if own-blocked? :blocked :covered)}]
    {:node node
     :source-element element
     :source-identity (:identity source)
     :source-location (:location source)
     :rule (:rule plan)
     :mapping-identity (:mapping plan)
     :required-usings (combined-set children :required-usings
                                    (:required-usings fragment))
     :required-helpers (combined-set children :required-helpers
                                     (:required-helpers fragment))
     :diagnostics (combined-vector children :diagnostics own-diagnostics)
     :mapping-identities (combined-vector
                          children :mapping-identities
                          (if-let [mapping (:mapping plan)] [mapping] []))
     :visits (into [visit] (mapcat :visits children))
     :fallback? (or (boolean (:fallback? fragment))
                    (boolean (some :fallback? children)))
     :mode (:mode translation-context)}))

(defn translate-element
  "Recursively translates one live Spoon tree.  Children are translated before
  the selected rule emits its destination node, but visits are reported in
  deterministic parent-first order.  The completed destination tree is
  rendered exactly once so text and source ranges remain derived from the
  structured C# tree without repeatedly rendering descendants at each parent."
  [translation-context ^CtElement element]
  (let [translation (translate-element* translation-context element)
        rendered (when-let [node (:node translation)] (csharp/render node))]
    (assoc translation
           :text (:text rendered)
           :source-mappings (vec (or (:mappings rendered) [])))))

(defn coverage-totals
  [translation]
  (let [visits (:visits translation)
        statuses (frequencies (map :status visits))
        dispatches (frequencies (map :dispatch-kind visits))]
    {:visited (count visits)
     :covered (get statuses :covered 0)
     :blocked (get statuses :blocked 0)
     :structural (get dispatches :structural 0)
     :semantic (get dispatches :semantic 0)
     :unsupported-elements (get dispatches :unsupported 0)
     :missing-mappings (get dispatches :missing-mapping 0)
     :missing-occurrences (get dispatches :missing-occurrence 0)
     :fallback (count (filter :fallback? visits))}))

(defn coverage-gate!
  "Returns an accepted, completely covered translation or throws with the
  originating live Spoon diagnostic and visit totals.  Diagnostic mode is
  categorically ineligible for accepted generation."
  [translation]
  (let [totals (coverage-totals translation)
        diagnostic (first (filter :blocking? (:diagnostics translation)))]
    (when (or (not= :accepted (:mode translation))
              (:fallback? translation)
              diagnostic
              (pos? (:blocked totals)))
      (throw (ex-info
              (if (= :accepted (:mode translation))
                (str "Java translation coverage is blocked at "
                     (or (get-in diagnostic [:location :file]) "an unknown source")
                     (when-let [line (get-in diagnostic [:location :line])]
                       (str ":" line))
                     (when-let [message (:message diagnostic)]
                       (str ": " message)))
                "Diagnostic Java translation cannot satisfy accepted generation")
              {:kind :java-translation-coverage-failed
               :mode (:mode translation)
               :coverage totals
               :diagnostic diagnostic
               :diagnostics (:diagnostics translation)})))
    translation))

(defn project-roots
  "Returns each live top-level project declaration exactly once."
  [resolved-model]
  (->> (if-let [project-types (:project-types resolved-model)]
         (vals project-types)
         (keep (fn [[_ entry]]
                 (let [declaration (:declaration entry)]
                   (when (instance? CtType declaration) declaration)))
               (:declarations resolved-model)))
       (filter #(.isTopLevel ^CtType %))
       (reduce (fn [result ^CtType type]
                 (assoc result (.getQualifiedName type) type))
               (sorted-map))
       vals
       (sort-by #(.getQualifiedName ^CtType %))
       vec))

(defn translate-project
  "Translates every live top-level declaration from a resolved model.  This is
  a direct traversal; the resolver's occurrence index is used only for semantic
  identity dispatch and is never a second syntax inventory."
  [translation-context]
  (let [roots (project-roots (:resolved-model translation-context))
        results (mapv #(translate-element translation-context %) roots)]
    {:results results
     :diagnostics (vec (mapcat :diagnostics results))
     :required-usings (reduce into #{} (map :required-usings results))
     :required-helpers (reduce into #{} (map :required-helpers results))
     :mapping-identities (vec (mapcat :mapping-identities results))
     :visits (vec (mapcat :visits results))
     :fallback? (boolean (some :fallback? results))
     :mode (:mode translation-context)}))
