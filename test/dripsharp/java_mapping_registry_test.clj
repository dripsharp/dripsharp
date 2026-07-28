(ns dripsharp.java-mapping-registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-mapping-registry :as registry]))

(defn- base-entry
  [overrides]
  (let [entry
        (merge
         {:id :test.mapping/base
          :key "executable:example.Values#map(java.lang.Object)"
          :strategy :rename
          :destination "Map"
          :caveats #{}
          :introduced-by :test-target
          :evidence #{"test/dripsharp/java_mapping_registry_test.clj"}}
         overrides)]
    (cond-> entry
      (contains? #{:template :custom-handler} (:strategy entry))
      (dissoc :destination))))

(defn- caught
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- rendered
  [fragment]
  (:text (csharp/render (:node fragment))))

(def ^:private entries
  [(base-entry
    {:id :test.type/list
     :key "type:java.util.List"
     :strategy :rename
     :destination "global::System.Collections.Generic.IList"
     :required-usings #{"System.Collections.Generic"}})
   (base-entry
    {:id :test.executable/add
     :key "executable:java.util.List#add(java.lang.Object)"
     :strategy :rename
     :destination "Add"})
   (base-entry
    {:id :test.constructor/list
     :key "executable:java.util.ArrayList#<init>(int)"
     :strategy :rename
     :destination "global::System.Collections.Generic.List"})
   (base-entry
    {:id :test.property/count
     :key "executable:java.util.List#size()"
     :strategy :property-access
     :destination "Count"})
   (base-entry
    {:id :test.compat/list-get
     :key "executable:java.util.List#get(int)"
     :strategy :compat-call
     :destination "global::DripSharp.Runtime.JavaCompat.ListGet"
     :required-helpers #{:java-compat}})
   (base-entry
    {:id :test.reshape/array-copy
     :key "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
     :strategy :argument-reshape
     :destination "global::System.Array.Copy"
     :call :static
     :arguments [[:argument 0] [:argument 1]
                 [:argument 2] [:argument 3] [:argument 4]]})
   (base-entry
    {:id :test.reshape/instance
     :key "executable:example.Builder#appendTo(java.lang.String,java.lang.Object)"
     :strategy :argument-reshape
     :destination "Append"
     :call :member
     :receiver [:argument 1]
     :arguments [[:argument 0]]})
   (base-entry
    {:id :test.template/coalesce
     :key "executable:java.util.Objects#requireNonNullElse(java.lang.Object,java.lang.Object)"
     :strategy :template
     :template ["(" [:argument 0] " ?? " [:argument 1] ")"]})
   (base-entry
    {:id :test.field/max-value
     :key "field:java.lang.Integer#MAX_VALUE"
     :strategy :property-access
     :destination "global::System.Int32.MaxValue"})
   (base-entry
    {:id :test.custom/identity
     :key "type:example.Identity"
     :strategy :custom-handler
     :handler :test.handler/identity
     :caveats #{:preserves-test-shape}
     :evidence #{:differential/test}})])

(def ^:private handlers
  {:test.handler/identity
   (fn [{:keys [mapping-entry]}]
     {:node (csharp/raw "global::Example.Identity")
      :required-usings #{"Example.Custom"}
      :diagnostics []
      :handler-observation (:id mapping-entry)})})

(deftest exact-resolved-key-grammar-is-fail-closed
  (is (= :type (registry/resolved-key-kind "type:java.lang.String")))
  (is (= :type
         (registry/resolved-key-kind
          "type-parameter:example.Box#map(java.lang.Object)#T")))
  (is (= :executable
         (registry/resolved-key-kind
          "executable:java.util.List#get(int)")))
  (is (= :constructor
         (registry/resolved-key-kind
          "executable:java.util.ArrayList#<init>(int)")))
  (is (= :field
         (registry/resolved-key-kind "field:<array>#length")))
  (doseq [malformed
          [nil
           ""
           "method:java.util.List#get(int)"
           "type:"
           "type: java.lang.String"
           "type-parameter:T"
           "executable:java.util.List#get"
           "executable:java.util.List#get(,)"
           "field:java.util.List"
           "field:#size"]]
    (is (nil? (registry/resolved-key-kind malformed)) (pr-str malformed))))

(deftest common-strategies-interpret-to-structured-csharp
  (let [compiled (registry/compile-registry
                  entries {:custom-handlers handlers})
        target (csharp/raw "items")
        argument (csharp/raw "value")]
    (is (registry/compiled-registry? compiled))
    (is (= (mapv :key (sort-by :key entries))
           (vec (keys (:entries compiled)))))

    (testing "type rename retains generic arguments and requirements"
      (let [fragment
            (registry/interpret
             compiled "type:java.util.List"
             {:type-arguments [(csharp/raw "string")]})]
        (is (= "global::System.Collections.Generic.IList<string>"
               (rendered fragment)))
        (is (= #{"System.Collections.Generic"} (:required-usings fragment)))
        (is (= :rename (get-in fragment [:mapping :strategy])))))

    (testing "member rename invokes the renamed destination"
      (is (= "items.Add(value)"
             (rendered
              (registry/interpret
               compiled "executable:java.util.List#add(java.lang.Object)"
               {:target target :arguments [argument]})))))

    (testing "constructor rename emits an explicit destination construction"
      (is (= "new global::System.Collections.Generic.List<string>(capacity)"
             (rendered
              (registry/interpret
               compiled "executable:java.util.ArrayList#<init>(int)"
               {:arguments [(csharp/raw "capacity")]
                :type-arguments [(csharp/raw "string")]})))))

    (testing "property access omits invocation parentheses"
      (is (= "items.Count"
             (rendered
              (registry/interpret
               compiled "executable:java.util.List#size()"
               {:target target})))))

    (testing "compat calls receive the instance target before source arguments"
      (let [fragment
            (registry/interpret
             compiled "executable:java.util.List#get(int)"
             {:target target :arguments [(csharp/raw "index")]})]
        (is (= "global::DripSharp.Runtime.JavaCompat.ListGet(items, index)"
               (rendered fragment)))
        (is (= #{:java-compat} (:required-helpers fragment)))))

    (testing "argument reshape supports static and selected member calls"
      (is
       (= "global::System.Array.Copy(source, sourceIndex, destination, destinationIndex, length)"
          (rendered
           (registry/interpret
            compiled
            "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
            {:arguments
             (mapv csharp/raw
                   ["source" "sourceIndex" "destination"
                    "destinationIndex" "length"])}))))
      (is (= "builder.Append(text)"
             (rendered
              (registry/interpret
               compiled
               "executable:example.Builder#appendTo(java.lang.String,java.lang.Object)"
               {:arguments [(csharp/raw "text") (csharp/raw "builder")]})))))

    (testing "templates compose nodes without losing their structure"
      (is (= "(value ?? fallback)"
             (rendered
              (registry/interpret
               compiled
               "executable:java.util.Objects#requireNonNullElse(java.lang.Object,java.lang.Object)"
               {:arguments [(csharp/raw "value")
                            (csharp/raw "fallback")]})))))

    (testing "static field properties and registered handlers are explicit"
      (is (= "global::System.Int32.MaxValue"
             (rendered
              (registry/interpret
               compiled "field:java.lang.Integer#MAX_VALUE" {}))))
      (let [fragment
            (registry/interpret compiled "type:example.Identity" {})]
        (is (= "global::Example.Identity" (rendered fragment)))
        (is (= #{"Example.Custom"} (:required-usings fragment)))
        (is (= :test.custom/identity (:handler-observation fragment)))))

    (testing "every interpretation reports stable review metadata"
      (is (= {:identity :test.custom/identity
              :resolved-key "type:example.Identity"
              :kind :type
              :strategy :custom-handler
              :caveats #{:preserves-test-shape}
              :introduced-by :test-target
              :evidence #{:differential/test}}
             (:mapping
              (registry/interpret compiled "type:example.Identity" {})))))))

(deftest registry-validation-rejects-malformed-and-contradictory-data
  (let [error-kind
        (fn [entries & [options]]
          (some-> (caught #(registry/compile-registry entries (or options {})))
                  ex-data
                  :kind))]
    (is (= :malformed-resolved-symbol-key
           (error-kind [(base-entry {:key "method:example.Values#map()"})])))
    (is (= :unsupported-mapping-strategy
           (error-kind [(base-entry {:strategy :guess})])))
    (is (= :contradictory-mapping-entry
           (error-kind [(base-entry {:kind :field})])))
    (is (= :contradictory-mapping-entry
           (error-kind
            [(base-entry {:strategy :property-access
                          :destination "Value"})])))
    (is (= :contradictory-mapping-entry
           (error-kind [(base-entry {:handler :test.handler/identity})])))
    (is (= :unevidenced-mapping-caveat
           (error-kind [(base-entry {:caveats #{:lossy} :evidence #{}})])))
    (is (= :unregistered-custom-mapping-handler
           (error-kind
            [(base-entry {:strategy :custom-handler
                          :handler :test.handler/missing})])))
    (is (= :invalid-mapping-selectors
           (error-kind
            [(base-entry {:strategy :argument-reshape
                          :call :static
                          :arguments [[:argument -1]]})])))
    (is (= :contradictory-mapping-entry
           (error-kind
            [(base-entry {:strategy :argument-reshape
                          :call :static
                          :receiver :target
                          :arguments [:arguments]})])))

    (testing "sequential input exposes duplicate key ownership"
      (is (= :duplicate-mapping-ownership
             (error-kind
              [(base-entry {:id :test.mapping/first})
               (base-entry {:id :test.mapping/second
                            :introduced-by :another-target})]))))

    (testing "one identity cannot contradict itself across resolved keys"
      (is (= :contradictory-mapping-identities
             (error-kind
              [(base-entry {})
               (base-entry
                {:key "executable:example.Values#filter(java.lang.Object)"
                 :destination "Filter"})]))))))

(deftest resolved-occurrence-reports-are-complete-ranked-and-evidenced
  (let [type-registry
        (registry/compile-registry
         [(base-entry
           {:id :test.type/string
            :key "type:java.lang.String"
            :destination "string"
            :caveats #{:string-contract}
            :evidence #{:differential/string}})])
        member-registry
        (registry/compile-registry
         [(base-entry
           {:id :test.member/list-size
            :key "executable:java.util.List#size()"
            :strategy :property-access
            :destination "Count"})])
        occurrences
        (concat
         (repeat 3 {:kind :type
                    :key "type:java.lang.String"
                    :origin :jdk})
         (repeat 2 {:kind :executable
                    :key "executable:java.util.List#size()"
                    :origin :jdk})
         (repeat 4 {:kind :executable
                    :key "executable:example.Missing#run()"
                    :origin :jdk})
         [{:kind :field
           :key "field:example.Missing#VALUE"
           :origin :jdk}
          {:kind :type
           :key "type:example.ProjectType"
           :origin :project}])
        report
        (registry/resolved-occurrence-report
         occurrences
         {:types type-registry :members member-registry}
         #(= :jdk (:origin %)))
        failure
        (caught #(registry/require-complete-occurrence-report! report))]
    (is (= {:total-occurrences 11
            :mapping-required-occurrences 10
            :mapped-occurrences 5
            :unmapped-occurrences 5
            :used-mappings 2
            :unmapped-symbols 2}
           (:summary report)))
    (is (= [4 1] (mapv :occurrences (:unmapped-symbols report))))
    (is (= ["executable:example.Missing#run()"
            "field:example.Missing#VALUE"]
           (mapv :resolved-key (:unmapped-symbols report))))
    (is (= [3 2] (mapv :occurrences (:used-mappings report))))
    (is (= {:registry :types
            :identity ":test.type/string"
            :resolved-key "type:java.lang.String"
            :kind :type
            :strategy :rename
            :caveats [:string-contract]
            :introduced-by :test-target
            :evidence [:differential/string]
            :occurrences 3}
           (first (:used-mappings report))))
    (is (= :java-translation-coverage-failed (:kind (ex-data failure))))
    (is (= :unmapped-resolved-symbols (:reason (ex-data failure))))
    (is (= (:unmapped-symbols report)
           (:unmapped-symbols (ex-data failure))))))

(deftest resolved-occurrence-report-registry-selection-fails-closed
  (let [first-registry
        (registry/compile-registry
         [(base-entry {:id :test.mapping/first})])
        duplicate-key-registry
        (registry/compile-registry
         [(base-entry {:id :test.mapping/second})])
        duplicate-id-registry
        (registry/compile-registry
         [(base-entry
           {:id :test.mapping/first
            :key "executable:example.Values#filter(java.lang.Object)"
            :destination "Filter"})])
        report-error
        (fn [registries]
          (caught
           #(registry/resolved-occurrence-report
             []
             registries
             (constantly false))))]
    (is (= :contradictory-mapping-registry-ownership
           (:kind
            (ex-data
             (report-error {:first first-registry
                            :second duplicate-key-registry})))))
    (is (= :contradictory-mapping-registry-identities
           (:kind
            (ex-data
             (report-error {:first first-registry
                            :second duplicate-id-registry})))))
    (is (= :invalid-mapping-report-predicate-result
           (:kind
            (ex-data
             (caught
              #(registry/resolved-occurrence-report
                [{:kind :type :key "type:example.Value" :origin :jdk}]
                {}
                (constantly :yes)))))))))

(deftest interpretation-failures-do-not-guess-output
  (let [compiled (registry/compile-registry
                  entries {:custom-handlers handlers})
        failure-kind
        (fn [key input]
          (some-> (caught #(registry/interpret compiled key input))
                  ex-data
                  :kind))]
    (is (= :unmapped-resolved-symbol
           (failure-kind "executable:example.Missing#run()" {})))
    (is (= :unexpected-mapping-arguments
           (failure-kind "executable:java.util.List#size()"
                         {:target (csharp/raw "items")
                          :arguments [(csharp/raw "unexpected")]})))
    (is (= :mapping-argument-out-of-range
           (failure-kind
            "executable:java.lang.System#arraycopy(java.lang.Object,int,java.lang.Object,int,int)"
            {:arguments [(csharp/raw "tooFew")]})))
    (is (= :invalid-mapping-input-node
           (failure-kind "executable:java.util.List#get(int)"
                         {:target :not-a-node
                          :arguments [(csharp/raw "index")]})))))
