(ns dripsharp.bundle-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.bundle-contract :as bundle]))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- conforming-bundle
  []
  (let [hooks
        (into
         {}
         (map
          (fn [[component capabilities]]
            [component
             (into {} (map (fn [capability]
                             [capability (fn [& _])])
                           capabilities))]))
         (:required-components (bundle/contract)))]
    {:schema-version 1
     :id :example
     :product-family :example
     :runtime-capabilities
     {:labeled-control-flow
      {:exception-type "global::Example.Runtime.LabeledFlow"}}
     :rules hooks}))

(deftest bundle-contract-is-serializable-and-complete
  (is (= {:schema-version 1
          :required-components
          {:structural-declarations
           #{:create-template :create-context :emit-root-node
             :translate-member :merge-context! :context-results}
           :resolved-mappings
           #{:type-node :create-body-context :annotation-decisions
             :declarative-mapping-registries
             :declarative-mapping-required?}
           :namespace-policy
           #{:destination-namespace :destination-file-name}
           :project-policy #{:validate-configuration! :project-text}
           :resource-policy #{:resource-mapping}
           :destination-bridges #{:assets}}
          :required-runtime-capabilities
          {:labeled-control-flow #{:exception-type}}
          :optional-components
          {:product-runtime-assets #{:assets}
           :orchestration
           #{:validate-profile! :validate-project-input!}}}
         (bundle/contract))))

(deftest bundle-validation-fails-at-the-exact-capability
  (let [valid (conforming-bundle)]
    (is (= valid (bundle/validate! valid)))
    (testing "required hooks fail closed"
      (let [error
            (caught
             #(bundle/validate!
               (update-in valid
                          [:rules :resolved-mappings]
                          dissoc
                          :annotation-decisions)))]
        (is (= :invalid-destination-bundle-contract
               (:kind (ex-data error))))
        (is (= :resolved-mappings (:component (ex-data error))))
        (is (= :annotation-decisions (:capability (ex-data error))))))
    (testing "runtime identities use the kernel contract"
      (let [error
            (caught
             #(bundle/validate!
               (assoc-in
                valid
                [:runtime-capabilities
                 :labeled-control-flow
                 :exception-type]
                "Example.Runtime.LabeledFlow")))]
        (is (= :runtime-capabilities (:component (ex-data error))))
        (is (= :invalid-translation-runtime-capability
               (get-in (ex-data error) [:validation :kind])))))
    (testing "optional hooks reject unknown behavior"
      (let [error
            (caught
             #(bundle/validate!
               (assoc valid :orchestration
                      {:unexpected (fn [])})))]
        (is (= :orchestration (:component (ex-data error))))))))
