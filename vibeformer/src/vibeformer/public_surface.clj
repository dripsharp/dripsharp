(ns vibeformer.public-surface
  "Product-neutral orchestration for explicit source/public-surface contracts.

  A destination selects a qualified strategy factory. The strategy owns its
  source contract and compiled-surface semantics; the harness only enforces
  the common fail-closed lifecycle.")

(def ^:private required-hooks
  #{:read! :validate-selected! :validate-generated! :verify-compiled!})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :invalid-public-surface-strategy))))

(defn resolve-strategy!
  "Resolves and validates the destination's public-surface strategy before
  source discovery or generated-output cleanup."
  [product-family specification]
  (let [selector (:strategy specification)]
    (when-not (and (map? specification) (symbol? selector) (namespace selector))
      (fail! "Public surface strategy must be a namespace-qualified symbol"
             {:specification specification :strategy selector}))
    (let [factory
          (try
            (requiring-resolve selector)
            (catch Throwable error
              (throw (ex-info "Public surface strategy selection failed"
                              {:kind :unsupported-public-surface-strategy
                               :strategy selector}
                              error))))]
      (when-not (ifn? factory)
        (fail! "Public surface strategy selector is not callable"
               {:strategy selector}))
      (let [strategy (factory)]
        (when-not (and (map? strategy)
                       (= 1 (:schema-version strategy))
                       (keyword? (:id strategy))
                       (keyword? (:product-family strategy)))
          (fail! "Public surface strategy contract is invalid"
                 {:strategy selector
                  :contract (select-keys strategy
                                         [:schema-version :id :product-family])}))
        (when-not (= product-family (:product-family strategy))
          (throw (ex-info "Public surface strategy is product-incompatible"
                          {:kind :product-incompatible-public-surface-strategy
                           :strategy selector
                           :profile-product-family product-family
                           :strategy-product-family (:product-family strategy)})))
        (doseq [hook required-hooks]
          (when-not (fn? (get strategy hook))
            (fail! "Public surface strategy capability is missing"
                   {:strategy selector :capability hook})))
        {:contract strategy
         :specification (dissoc specification :strategy)}))))

(defn read!
  [selection workspace]
  ((get-in selection [:contract :read!])
   workspace (:specification selection)))

(defn validate-selected!
  [selection workspace surface resolved-model]
  ((get-in selection [:contract :validate-selected!])
   workspace surface resolved-model))

(defn validate-generated!
  [selection surface emission]
  ((get-in selection [:contract :validate-generated!]) surface emission))

(defn emission-boundary
  "Allows a selected surface strategy to compose dependency evidence when its
  product contract requires it. Ordinary strategies retain the main surface."
  [selection surface dependency-emissions]
  (if-let [compose! (get-in selection [:contract :emission-boundary])]
    (compose! surface dependency-emissions)
    surface))

(defn verify-compiled!
  "Runs the selected strategy's clean compiled-surface audit."
  [workspace generation build-configuration]
  (let [selection (:public-surface-strategy generation)]
    (when-not selection
      (fail! "Generation did not retain its selected public-surface strategy"
             {:generation-profile
              (get-in generation [:generation-profile :profile])}))
    ((get-in selection [:contract :verify-compiled!])
     workspace generation build-configuration)))
