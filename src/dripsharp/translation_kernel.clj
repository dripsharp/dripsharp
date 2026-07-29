(ns dripsharp.translation-kernel
  "Pure validation and plan selection for the recursive translation kernel.

  This namespace deliberately has no Spoon dependency. The live frontend
  adapter supplies resolved occurrences and matching structural rules; this
  kernel validates those inputs and selects exactly one fail-closed plan."
  (:require [clojure.string :as str]))

(def ^:private mapping-categories
  #{:types :executables :constructors :fields :annotations})

(def ^:private runtime-capability-keys
  #{:labeled-control-flow})

(def ^:private global-csharp-type-pattern
  #"global::@?[A-Za-z_][A-Za-z0-9_]*(?:[.]@?[A-Za-z_][A-Za-z0-9_]*)*")

(defn runtime-capabilities
  "Validates destination runtime identities used by translated constructs."
  [capabilities]
  (let [capabilities (or capabilities {})]
    (when-not (map? capabilities)
      (throw (ex-info "Java translation runtime capabilities must be a map"
                      {:kind :invalid-translation-runtime-capabilities
                       :runtime-capabilities capabilities})))
    (when-let [unexpected (seq (remove runtime-capability-keys
                                       (keys capabilities)))]
      (throw (ex-info "Unknown Java translation runtime capability"
                      {:kind :unknown-translation-runtime-capability
                       :capabilities (vec (sort unexpected))})))
    (when-let [labeled-control-flow (:labeled-control-flow capabilities)]
      (when-not (and (map? labeled-control-flow)
                     (= #{:exception-type} (set (keys labeled-control-flow)))
                     (string? (:exception-type labeled-control-flow))
                     (re-matches global-csharp-type-pattern
                                 (:exception-type labeled-control-flow)))
        (throw (ex-info "Invalid labeled-control-flow runtime capability"
                        {:kind :invalid-translation-runtime-capability
                         :capability :labeled-control-flow
                         :contract labeled-control-flow}))))
    capabilities))

(defn runtime-type-identity
  "Returns one validated destination runtime type identity."
  [translation-context capability]
  (let [path (case capability
               :labeled-control-flow
               [:runtime-capabilities :labeled-control-flow :exception-type]

               (throw (ex-info "Unknown Java translation runtime type"
                               {:kind :unknown-translation-runtime-type
                                :capability capability})))]
    (or (get-in translation-context path)
        (throw (ex-info "Destination did not supply a required runtime type"
                        {:kind :missing-translation-runtime-capability
                         :capability capability})))))

(defn structural-rules
  "Validates the ordered structural-rule registry."
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
  "Validates semantic registries keyed by exact resolved-symbol identity."
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

(defn- occurrence-category
  [occurrence]
  (case (:kind occurrence)
    :type :types
    :executable :executables
    :constructor :constructors
    :field :fields
    :annotation :annotations
    nil))

(defn translation-plan
  "Selects one semantic, structural, or fail-closed translation plan.

  References always use resolved occurrences and never fall through to a
  structural rule. Non-reference elements use the first frontend-matched
  structural rule supplied by the adapter."
  [{:keys [reference? occurrence mappings structural-rule]}]
  (if reference?
    (if occurrence
      (let [category (occurrence-category occurrence)
            mapping (get-in mappings [category (:key occurrence)])]
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
           :category category
           :occurrence occurrence}))
      {:kind :missing-occurrence})
    (if structural-rule
      {:kind :structural
       :rule (:id structural-rule)
       :emit (:emit structural-rule)}
      {:kind :unsupported})))
