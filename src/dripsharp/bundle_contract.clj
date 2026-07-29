(ns dripsharp.bundle-contract
  "Pure schema and validation for destination rule bundles."
  (:require [dripsharp.translation-kernel :as kernel]))

(def ^:private required-rule-components
  {:structural-declarations
   #{:create-template :create-context :emit-root-node :translate-member
     :merge-context! :context-results}
   :resolved-mappings
   #{:type-node :create-body-context :annotation-decisions
     :declarative-mapping-registries :declarative-mapping-required?}
   :namespace-policy #{:destination-namespace :destination-file-name}
   :project-policy #{:validate-configuration! :project-text}
   :resource-policy #{:resource-mapping}
   :destination-bridges #{:assets}})

(def ^:private required-runtime-capabilities
  {:labeled-control-flow #{:exception-type}})

(defn contract
  "Returns the serializable contract implemented by every destination bundle."
  []
  {:schema-version 1
   :required-components required-rule-components
   :required-runtime-capabilities required-runtime-capabilities
   :optional-components {:product-runtime-assets #{:assets}
                         :orchestration #{:validate-profile!
                                          :validate-project-input!}}})

(defn- fail!
  [message data]
  (throw (ex-info message
                  (assoc data :kind :invalid-destination-bundle-contract))))

(defn validate!
  "Validates a destination bundle without loading a product or frontend.

  The optional failure callback receives message and data and must throw. The
  project emitter supplies a callback that augments failures with live Spoon
  evidence while pure callers receive the standalone contract diagnostic."
  ([rule-bundle]
   (validate! rule-bundle fail!))
  ([rule-bundle failure!]
   (when-not (and (map? rule-bundle)
                  (= 1 (:schema-version rule-bundle))
                  (keyword? (:id rule-bundle))
                  (keyword? (:product-family rule-bundle))
                  (map? (:rules rule-bundle)))
     (failure! "Invalid destination rule bundle"
               {:rule-bundle (select-keys rule-bundle
                                          [:schema-version :id])}))
   (doseq [[component required-hooks] required-rule-components]
     (let [rules (get-in rule-bundle [:rules component])]
       (when-not (map? rules)
         (failure! "Destination rule component is missing"
                   {:bundle (:id rule-bundle) :component component}))
       (doseq [hook required-hooks]
         (when-not (fn? (get rules hook))
           (failure! "Destination rule capability is missing"
                     {:bundle (:id rule-bundle)
                      :component component :capability hook})))))
   (let [runtime-capabilities
         (try
           (kernel/runtime-capabilities (:runtime-capabilities rule-bundle))
           (catch clojure.lang.ExceptionInfo error
             (failure!
              "Destination runtime capability contract is invalid"
              {:bundle (:id rule-bundle)
               :component :runtime-capabilities
               :validation (ex-data error)})))]
     (doseq [[capability _settings] required-runtime-capabilities]
       (when-not (contains? runtime-capabilities capability)
         (failure!
          "Destination runtime capability is missing"
          {:bundle (:id rule-bundle)
           :component :runtime-capabilities
           :capability capability}))))
   (when-let [runtime-rules
              (get-in rule-bundle [:rules :product-runtime-assets])]
     (when-not (and (map? runtime-rules) (fn? (:assets runtime-rules)))
       (failure! "Product runtime asset capability is invalid"
                 {:bundle (:id rule-bundle)
                  :component :product-runtime-assets
                  :capability :assets})))
   (when-let [orchestration (:orchestration rule-bundle)]
     (when-not (and (map? orchestration)
                    (every? (fn [[_ hook]] (fn? hook)) orchestration)
                    (every? #{:validate-profile! :validate-project-input!}
                            (keys orchestration)))
       (failure! "Destination orchestration capability is invalid"
                 {:bundle (:id rule-bundle)
                  :component :orchestration})))
   rule-bundle))
