(ns vibeformer.java-translate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [vibeformer.complete-parser-fixture :as fixture]
            [vibeformer.csharp :as csharp]
            [vibeformer.java-translate :as java])
  (:import [spoon.reflect.code CtBinaryOperator CtBlock CtFieldRead CtReturn
            CtThisAccess CtTypeAccess]
           [spoon.reflect.declaration CtMethod]
           [spoon.reflect.reference CtPackageReference]
           [spoon.reflect.visitor.filter TypeFilter]))

(defn- complete-model
  []
  (:first (fixture/models)))

(defn- stop-index-exclusive-method
  []
  (let [methods (.getElements (:model (complete-model))
                              (TypeFilter. CtMethod))]
    (or (some (fn [^CtMethod method]
                (when (and (= "stopIndexExclusive" (.getSimpleName method))
                           (= "org.pkl.parser.Span"
                              (some-> method .getDeclaringType .getQualifiedName)))
                  method))
              methods)
        (throw (ex-info "Resolved pkl-parser model is missing Span.stopIndexExclusive"
                        {:kind :test-model-mismatch})))))

(defn- child-node
  [children element]
  (:node (java/child-result children element)))

(def ^:private parser-rules
  (java/structural-rules
   [{:id :java.declaration/method
     :class CtMethod
     :emit (fn [{:keys [^CtMethod element children]}]
             {:node (csharp/sequence-node
                     [(csharp/raw "public ")
                      (child-node children (.getType element))
                      (csharp/raw " StopIndexExclusive() ")
                      (child-node children (.getBody element))])})}
    {:id :java.statement/block
     :class CtBlock
     :emit (fn [{:keys [^CtBlock element children]}]
             (let [statements (mapv #(child-node children %)
                                    (.getStatements element))]
               {:node (csharp/sequence-node
                       [(csharp/raw "{\n  ")
                        (csharp/sequence-node statements "\n  ")
                        (csharp/raw "\n}")])}))}
    {:id :java.statement/return
     :class CtReturn
     :emit (fn [{:keys [^CtReturn element children]}]
             {:node (csharp/sequence-node
                     [(csharp/raw "return ")
                      (child-node children (.getReturnedExpression element))
                      (csharp/raw ";")])})}
    {:id :java.expression/binary
     :class CtBinaryOperator
     :emit (fn [{:keys [^CtBinaryOperator element children]}]
             (let [[operator precedence]
                   (case (str (.getKind element))
                     "PLUS" ["+" 60]
                     (throw (ex-info "Test rule received an unexpected operator"
                                     {:operator (.getKind element)})))]
               {:node (csharp/binary
                       operator precedence
                       (child-node children (.getLeftHandOperand element))
                       (child-node children (.getRightHandOperand element)))}))}
    {:id :java.expression/field-read
     :class CtFieldRead
     :emit (fn [{:keys [^CtFieldRead element children]}]
             {:node (child-node children (.getVariable element))})}
    {:id :java.expression/this
     :class CtThisAccess
     :emit (fn [_] {:node (csharp/raw "this")})}
    {:id :java.expression/type-access
     :class CtTypeAccess
     :emit (fn [{:keys [^CtTypeAccess element children]}]
             {:node (child-node children (.getAccessedType element))})}
    {:id :java.reference/package
     :class CtPackageReference
     :emit (fn [_] {:node (csharp/raw "Pkl.Parser")})}]))

(def ^:private parser-mappings
  (java/mapping-registries
   {:types
    {"type:int"
     {:id :dotnet.type/int32
      :emit (fn [_]
              {:node (csharp/raw "int")})}
     "type:org.pkl.parser.Span"
     {:id :dotnet.type/span
      :emit (fn [_]
              {:node (csharp/raw "Span")
               :required-usings #{"Pkl.Parser"}})}}
    :fields
    {"field:org.pkl.parser.Span#charIndex"
     {:id :dotnet.field/span-char-index
      :emit (fn [_]
              {:node (csharp/raw "CharIndex")})}
     "field:org.pkl.parser.Span#length"
     {:id :dotnet.field/span-length
      :emit (fn [_]
              {:node (csharp/raw "Length")
               :required-helpers #{:record-component-property}})}}}))

(defn- translation-context
  ([] (translation-context parser-rules parser-mappings :accepted))
  ([rules mappings mode]
   (java/context (complete-model)
                 {:rules rules :mappings mappings :mode mode})))

(defn- translate-method
  [translation-context]
  (java/translate-element translation-context (stop-index-exclusive-method)))

(defn- gate-error
  [translation]
  (try
    (java/coverage-gate! translation)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(deftest live-spoon-tree-translates-directly-and-deterministically
  (let [first-result (translate-method (translation-context))
        second-result (translate-method (translation-context))
        accepted (java/coverage-gate! first-result)
        coverage (java/coverage-totals accepted)
        categories (set (map :frontend-category (:visits accepted)))
        mappings (set (map :identity (:mapping-identities accepted)))
        mapped-sources (set (keep #(get-in % [:source :mapping :identity])
                                  (:source-mappings accepted)))]
    (is (= "public int StopIndexExclusive() {\n  return CharIndex + Length;\n}"
           (:text accepted)))
    (is (= (:text first-result) (:text second-result)))
    (is (= (mapv #(select-keys % [:source-identity :source-location
                                  :frontend-category :dispatch-kind :rule
                                  :mapping :status])
                 (:visits first-result))
           (mapv #(select-keys % [:source-identity :source-location
                                  :frontend-category :dispatch-kind :rule
                                  :mapping :status])
                 (:visits second-result))))
    (is (= {:visited 28
            :covered 28
            :blocked 0
            :structural 16
            :semantic 12
            :unsupported-elements 0
            :missing-mappings 0
            :missing-occurrences 0
            :fallback 0}
           coverage))
    (is (every? categories [:declaration :type :statement :expression :field]))
    (is (every? mappings [:dotnet.type/int32 :dotnet.type/span
                          :dotnet.field/span-char-index
                          :dotnet.field/span-length]))
    (is (every? mapped-sources [:dotnet.field/span-char-index
                                :dotnet.field/span-length]))
    (is (= #{"Pkl.Parser"} (:required-usings accepted)))
    (is (= #{:record-component-property} (:required-helpers accepted)))
    (is (some #(= (get-in % [:source :location :line])
                  (get-in accepted [:source-location :line]))
              (:source-mappings accepted)))
    (is (every? #(identical? (stop-index-exclusive-method) %)
                [(:source-element first-result)
                 (:source-element second-result)]))))

(deftest recursive-translation-renders-only-the-completed-root
  (let [render csharp/render
        render-count (atom 0)
        translation (with-redefs [csharp/render (fn [node]
                                                  (swap! render-count inc)
                                                  (render node))]
                      (translate-method (translation-context)))]
    (is (= 1 @render-count))
    (is (= 28 (:visited (java/coverage-totals translation))))
    (is (= "public int StopIndexExclusive() {\n  return CharIndex + Length;\n}"
           (:text translation)))
    (is (seq (:source-mappings translation)))))

(deftest removing-structural-rule-fails-at-originating-live-element
  (let [rules (vec (remove #(= :java.expression/binary (:id %)) parser-rules))
        translation (translate-method
                     (translation-context rules parser-mappings :accepted))
        error (gate-error translation)
        diagnostic (:diagnostic (ex-data error))]
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))
    (is (= 1 (get-in (ex-data error) [:coverage :blocked])))
    (is (= 1 (get-in (ex-data error) [:coverage :unsupported-elements])))
    (is (= :unsupported-java-element (:kind diagnostic)))
    (is (= "spoon.support.reflect.code.CtBinaryOperatorImpl"
           (get-in diagnostic [:frontend :frontend-class])))
    (is (pos? (get-in diagnostic [:location :line])))
    (is (some #(= :java.expression/field-read (:rule %))
              (:visits translation)))))

(deftest removing-resolved-mapping-fails-with-symbol-identity-and-location
  (let [mappings (update parser-mappings :fields
                         dissoc "field:org.pkl.parser.Span#length")
        translation (translate-method
                     (translation-context parser-rules mappings :accepted))
        error (gate-error translation)
        diagnostic (:diagnostic (ex-data error))]
    (is (= 1 (get-in (ex-data error) [:coverage :blocked])))
    (is (= 1 (get-in (ex-data error) [:coverage :missing-mappings])))
    (is (= :unsupported-resolved-symbol (:kind diagnostic)))
    (is (= "field:org.pkl.parser.Span#length"
           (get-in diagnostic [:resolved :key])))
    (is (= :record-component
           (get-in diagnostic [:resolved :resolution])))
    (is (pos? (get-in diagnostic [:location :line])))
    (is (str/includes? (:message diagnostic)
                       "field:org.pkl.parser.Span#length"))))

(deftest independent-child-failures-aggregate-without-parent-cascades
  (let [rules (vec (remove #(= :java.expression/binary (:id %)) parser-rules))
        mappings (update parser-mappings :fields
                         dissoc
                         "field:org.pkl.parser.Span#charIndex"
                         "field:org.pkl.parser.Span#length")
        translation (translate-method
                     (translation-context rules mappings :accepted))
        diagnostics (:diagnostics translation)]
    (is (= 3 (count diagnostics)))
    (is (= {:unsupported-java-element 1
            :unsupported-resolved-symbol 2}
           (frequencies (map :kind diagnostics))))
    (is (= 3 (:blocked (java/coverage-totals translation))))
    (is (not-any? #(= :translation-rule-failed (:kind %)) diagnostics))))

(deftest diagnostic-mode-and-fallback-cannot-pass-accepted-coverage
  (let [rules (vec (remove #(= :java.expression/binary (:id %)) parser-rules))
        diagnostic-context
        (assoc (translation-context rules parser-mappings :diagnostic)
               :diagnostic-fallback (fn [_] (csharp/raw "default")))
        translation (translate-method diagnostic-context)
        error (gate-error translation)
        coverage (java/coverage-totals translation)]
    (is (= 1 (:blocked coverage)))
    (is (= 1 (:fallback coverage)))
    (is (= :diagnostic (:mode (ex-data error))))
    (is (= :java-translation-coverage-failed (:kind (ex-data error))))))
