(ns dripsharp.translation-kernel-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.translation-kernel :as kernel]))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest pure-planning-selects-one-fail-closed-path
  (let [emit (fn [_] :emitted)
        occurrence {:kind :executable
                    :key "executable:example.Values#size()"
                    :origin :project
                    :resolution :source-declaration}
        mappings
        {:executables
         {(:key occurrence)
          {:id :example.mapping/size
           :emit emit}}}
        structural-rule {:id :java.structure/object
                         :class Object
                         :emit emit}]
    (testing "resolved references select semantic mappings"
      (is (= {:kind :semantic
              :rule :example.mapping/size
              :mapping
              {:registry :executables
               :identity :example.mapping/size
               :resolved-key (:key occurrence)
               :origin :project
               :resolution :source-declaration}
              :occurrence occurrence
              :emit emit}
             (kernel/translation-plan
              {:reference? true
               :occurrence occurrence
               :mappings mappings
               :structural-rule structural-rule}))))
    (testing "references never fall through to structural output"
      (is (= {:kind :missing-mapping
              :category :executables
              :occurrence occurrence}
             (kernel/translation-plan
              {:reference? true
               :occurrence occurrence
               :mappings {}
               :structural-rule structural-rule})))
      (is (= {:kind :missing-occurrence}
             (kernel/translation-plan
              {:reference? true
               :mappings mappings
               :structural-rule structural-rule}))))
    (testing "non-references use their matched rule or fail closed"
      (is (= {:kind :structural
              :rule :java.structure/object
              :emit emit}
             (kernel/translation-plan
              {:reference? false
               :structural-rule structural-rule})))
      (is (= {:kind :unsupported}
             (kernel/translation-plan {:reference? false}))))))

(deftest registry-shapes-are-validated-before-planning
  (let [emit identity
        rule {:id :java.structure/object :class Object :emit emit}
        mappings
        {:types
         {"type:example.Value"
          {:id :example.mapping/value :emit emit}}}]
    (is (= [rule] (kernel/structural-rules [rule])))
    (is (= :example.mapping/value
           (get-in (kernel/mapping-registries mappings)
                   [:types "type:example.Value" :id])))
    (is (= :duplicate-structural-rule
           (-> (caught #(kernel/structural-rules
                         [rule (assoc rule :class String)]))
               ex-data
               :kind)))
    (is (= :duplicate-structural-class
           (-> (caught #(kernel/structural-rules
                         [rule (assoc rule :id :java.structure/string)]))
               ex-data
               :kind)))
    (is (= :invalid-symbol-mapping
           (-> (caught #(kernel/mapping-registries
                         {:types
                          {"method:example.Value#value()"
                           {:id :example.mapping/value :emit emit}}}))
               ex-data
               :kind)))))

(deftest runtime-identities-remain-destination-supplied
  (let [capabilities
        {:labeled-control-flow
         {:exception-type "global::Example.Runtime.LabeledFlow"}}]
    (is (= capabilities (kernel/runtime-capabilities capabilities)))
    (is (= "global::Example.Runtime.LabeledFlow"
           (kernel/runtime-type-identity
            {:runtime-capabilities capabilities}
            :labeled-control-flow)))
    (is (= :invalid-translation-runtime-capability
           (-> (caught
                #(kernel/runtime-capabilities
                  {:labeled-control-flow
                   {:exception-type "Example.Runtime.LabeledFlow"}}))
               ex-data
               :kind)))
    (is (= :missing-translation-runtime-capability
           (-> (caught
                #(kernel/runtime-type-identity
                  {:runtime-capabilities {}}
                  :labeled-control-flow))
               ex-data
               :kind)))))
