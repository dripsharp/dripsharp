(ns vibeformer.java-translate
  "Fail-closed recursive translation from live resolved Spoon elements.

  The kernel never inventories or reconstructs Java syntax.  Every result is
  produced by visiting a live element and its live direct children.  Semantic
  references dispatch through the stable resolved identities supplied by
  vibeformer.spoon."
  (:require [clojure.string :as str]
            [vibeformer.csharp :as csharp]
            [vibeformer.diagnostics :as diagnostics]
            [vibeformer.spoon :as spoon])
  (:import [java.util IdentityHashMap]
           [spoon.reflect.code CtBlock CtBreak CtContinue CtDo CtExpression CtFor
            CtForEach CtStatement CtTry CtWhile]
           [spoon.reflect.declaration CtAnnotation CtElement CtExecutable CtType]
           [spoon.reflect.reference CtExecutableReference CtFieldReference
            CtTypeReference]
           [spoon.reflect.visitor.filter TypeFilter]))

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

(defn- labeled-statement?
  [^CtStatement statement]
  (not (str/blank? (.getLabel statement))))

(defn- branch-statement?
  [^CtStatement statement]
  (and (or (instance? CtBreak statement)
           (instance? CtContinue statement))
       (not (str/blank? (.getTargetLabel statement)))))

(defn- labeled-branch-target
  "Resolves a labeled break or continue to its exact live declaration object.
  Java label scope follows the ancestor statement chain and cannot cross an
  executable boundary. Raw text performs only the scoped source lookup;
  destination identities belong to the resolved declaration objects."
  [^CtStatement branch]
  (when (branch-statement? branch)
    (let [target-label (.getTargetLabel branch)]
      (loop [current (when (.isParentInitialized branch) (.getParent branch))]
        (cond
          (nil? current) nil
          (instance? CtExecutable current) nil
          (and (instance? CtStatement current)
               (= target-label (.getLabel ^CtStatement current))) current
          :else (recur (when (.isParentInitialized ^CtElement current)
                         (.getParent ^CtElement current))))))))

(defn- continue-target?
  [target]
  (or (instance? CtDo target)
      (instance? CtFor target)
      (instance? CtForEach target)
      (instance? CtWhile target)))

(defn- finalizer-owner
  [element]
  (when (and (instance? CtBlock element)
             (.isParentInitialized ^CtElement element))
    (let [parent (.getParent ^CtElement element)]
      (when (and (instance? CtTry parent)
                 (identical? element (.getFinalizer ^CtTry parent)))
        parent))))

(defn- crossed-finalizers
  [^CtStatement branch ^CtStatement target]
  (loop [current (when (.isParentInitialized branch) (.getParent branch))
         result []]
    (cond
      (nil? current) result
      (identical? current target) result
      :else (recur (when (.isParentInitialized ^CtElement current)
                     (.getParent ^CtElement current))
                   (cond-> result
                     (finalizer-owner current) (conj (finalizer-owner current)))))))

(defn- root-statements
  [^CtElement root]
  (cond-> (vec (.getElements root (TypeFilter. CtStatement)))
    (instance? CtStatement root) (into [root])))

(defn- control-flow-index
  "Indexes labeled control flow for one translated executable tree. Both maps
  use object identity so disjoint declarations with the same Java spelling
  remain distinct and receive collision-free C# labels."
  [^CtElement root]
  (let [statements (root-statements root)
        declaration-ids (IdentityHashMap.)
        branch-targets (IdentityHashMap.)
        targeted-breaks (IdentityHashMap.)
        targeted-continues (IdentityHashMap.)
        branch-finalizers (IdentityHashMap.)
        branch-ids (IdentityHashMap.)
        finalizer-branches (IdentityHashMap.)
        finalizer-ids (IdentityHashMap.)
        next-branch-id (atom 0)]
    (doseq [[ordinal ^CtStatement statement]
            (map-indexed vector (filter labeled-statement? statements))]
      (.put declaration-ids statement ordinal))
    (doseq [^CtStatement branch (filter branch-statement? statements)]
      (when-let [target (labeled-branch-target branch)]
        (when (and (instance? CtContinue branch)
                   (not (continue-target? target)))
          (throw (ex-info "Java labeled continue does not target a loop declaration"
                          {:kind :invalid-labeled-continue-target
                           :branch (spoon/frontend-diagnostic branch)
                           :target (spoon/frontend-diagnostic target)})))
        (.put branch-targets branch target)
        (.put (if (instance? CtBreak branch)
                targeted-breaks
                targeted-continues)
              target true)
        (let [finalizers (crossed-finalizers branch target)]
          (when (seq finalizers)
            (.put branch-finalizers branch finalizers)
            (.put branch-ids branch (swap! next-branch-id inc))
            (doseq [finalizer finalizers]
              (.put finalizer-branches finalizer
                    (conj (vec (.get finalizer-branches finalizer)) branch)))))))
    (doseq [[ordinal ^CtTry finalizer]
            (map-indexed vector
                         (filter #(.containsKey finalizer-branches %)
                                 (filter #(instance? CtTry %) statements)))]
      (.put finalizer-ids finalizer ordinal))
    {:declaration-ids declaration-ids
     :branch-targets branch-targets
     :targeted-breaks targeted-breaks
     :targeted-continues targeted-continues
     :branch-finalizers branch-finalizers
     :branch-ids branch-ids
     :finalizer-branches finalizer-branches
     :finalizer-ids finalizer-ids}))

(defn labeled-target
  "Returns the exact labeled statement declaration targeted by a live branch."
  [translation-context ^CtStatement branch]
  (.get ^IdentityHashMap (get-in translation-context
                                 [:control-flow :branch-targets])
        branch))

(defn labeled-targeted?
  "True when a labeled declaration is targeted by the requested branch kind."
  [translation-context ^CtStatement target kind]
  (let [index-key (case kind
                    :break :targeted-breaks
                    :continue :targeted-continues
                    (throw (ex-info "Unknown labeled control-flow kind"
                                    {:kind :invalid-labeled-control-flow-kind
                                     :control-flow-kind kind})))]
    (boolean
     (.get ^IdentityHashMap (get-in translation-context
                                    [:control-flow index-key])
           target))))

(defn labeled-target-name
  "Returns a collision-free destination label for an exact declaration."
  [translation-context ^CtStatement target kind]
  (let [ordinal (.get ^IdentityHashMap
                      (get-in translation-context
                              [:control-flow :declaration-ids])
                      target)]
    (when (nil? ordinal)
      (throw (ex-info "Labeled branch target is outside the translated tree"
                      {:kind :unindexed-labeled-branch-target
                       :target (spoon/frontend-diagnostic target)})))
    (str "__java_" (name kind) "_" ordinal)))

(defn labeled-branch-target-name
  "Resolves a live labeled branch and returns its identity-based destination."
  [translation-context ^CtStatement branch kind]
  (let [target (labeled-target translation-context branch)]
    (when-not target
      (throw (ex-info "Java labeled branch has no declaration in scope"
                      {:kind :unresolved-labeled-branch-target
                       :branch (spoon/frontend-diagnostic branch)})))
    (labeled-target-name translation-context target kind)))

(defn labeled-finally-flow?
  "True when this translation tree contains a labeled branch that originates
  inside, and exits, a Java finally clause."
  [translation-context]
  (pos? (.size ^IdentityHashMap
               (get-in translation-context [:control-flow :branch-finalizers]))))

(defn labeled-branch-node
  "Emits a direct identity-based goto, or an internal control-flow signal when
  C# forbids control from leaving a finally clause. The nearest translated try
  boundary catches the signal before ordinary Java exception handlers can
  observe it."
  [translation-context ^CtStatement branch kind]
  (let [target (labeled-target translation-context branch)]
    (when-not target
      (throw (ex-info "Java labeled branch has no declaration in scope"
                      {:kind :unresolved-labeled-branch-target
                       :branch (spoon/frontend-diagnostic branch)})))
    (if-let [finalizers (.get ^IdentityHashMap
                             (get-in translation-context
                                     [:control-flow :branch-finalizers])
                             branch)]
      (let [branch-id (.get ^IdentityHashMap
                            (get-in translation-context [:control-flow :branch-ids])
                            branch)]
        (csharp/raw
         (str "throw new global::Vibeformer.Runtime.JavaLabeledControlFlowException("
              branch-id ");")))
      (csharp/raw
       (str "goto " (labeled-target-name translation-context target kind) ";")))))

(defn- finalizer-branch-id
  [translation-context branch]
  (.get ^IdentityHashMap
        (get-in translation-context [:control-flow :branch-ids])
        branch))

(defn- branch-finalizers
  [translation-context branch]
  (.get ^IdentityHashMap
        (get-in translation-context [:control-flow :branch-finalizers])
        branch))

(defn- outermost-finalizer?
  [translation-context branch finalizer]
  (identical? finalizer (last (branch-finalizers translation-context branch))))

(defn- finalizer-identity
  [translation-context finalizer]
  (.get ^IdentityHashMap
        (get-in translation-context [:control-flow :finalizer-ids])
        finalizer))

(defn- finally-crossing-node
  [translation-context ^CtTry finalizer node]
  (let [branches (.get ^IdentityHashMap
                       (get-in translation-context
                               [:control-flow :finalizer-branches])
                       finalizer)]
    (if-not (seq branches)
      node
      (let [exception-name (str "__java_finally_flow_"
                                (finalizer-identity translation-context finalizer))
            dispatches
            (for [branch branches
                  :when (outermost-finalizer? translation-context branch finalizer)
                  :let [kind (if (instance? CtBreak branch) :break :continue)
                        target (labeled-target translation-context branch)]]
              (csharp/raw
               (str "if (" exception-name ".BranchId == "
                    (finalizer-branch-id translation-context branch) ") goto "
                    (labeled-target-name translation-context target kind) ";")))]
        (csharp/sequence-node
         (remove nil?
                 [(csharp/raw "try {\n")
                  node
                  (csharp/raw
                   (str "\n} catch (global::Vibeformer.Runtime."
                        "JavaLabeledControlFlowException"
                        (when (seq dispatches) (str " " exception-name))
                        ") {\n"))
                  (csharp/sequence-node dispatches "\n")
                  (when (seq dispatches) (csharp/raw "\n"))
                  (csharp/raw "throw;\n}")]))))))

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
   (blocking-diagnostic kind element message resolved nil))
  ([kind ^CtElement element message resolved error]
   (cond-> {:severity :error
            :blocking? true
            :kind kind
            :message message
            :location (spoon/source-location element)
            :frontend (spoon/frontend-diagnostic element)}
     resolved (assoc :resolved resolved)
     error (merge (diagnostics/throwable-summary error)))))

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
        (let [fragment
              (or (emit {:context translation-context
                         :element element
                         :children children
                         :occurrence (:occurrence plan)
                         :mapping (:mapping plan)})
                  {})
              fragment
              (if (and (:node fragment) (instance? CtTry element))
                (update fragment :node
                        #(finally-crossing-node translation-context element %))
                fragment)]
          (if (and (:node fragment)
                   (instance? CtStatement element)
                   (labeled-statement? element)
                   (labeled-targeted? translation-context element :break))
            (assoc fragment :node
                   (csharp/sequence-node
                    [(:node fragment)
                     (csharp/raw
                      (str "\n"
                           (labeled-target-name translation-context element :break)
                           ":;"))]))
            fragment))
        (catch Throwable error
          {:diagnostics
           [(blocking-diagnostic
             :translation-rule-failed element
             (str "Translation rule " (:rule plan) " failed: " (.getMessage error))
             (when-let [occurrence (:occurrence plan)]
               (select-keys occurrence [:kind :key :origin :resolution]))
             error)]})))

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
  (let [translation-context (assoc translation-context
                                   :control-flow (control-flow-index element))
        translation (translate-element* translation-context element)
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
