(ns dripsharp.sqltrellis.java-project
  "SqlTrellis registration on the product-neutral Java-library pipeline.

  This namespace owns only target identity and discovery-accounting policy.
  JSqlParser SQL semantics do not belong here or in the shared translator."
  (:require [clojure.set :as set]
            [dripsharp.baseline :as baseline]
            [dripsharp.csharp :as csharp]
            [dripsharp.java-library :as java-library]
            [dripsharp.paths :as paths])
  (:import [spoon.reflect.code CtBinaryOperator CtInvocation CtThisAccess
            CtTypeAccess CtVariableAccess]
           [spoon.reflect.declaration CtElement CtMethod CtParameter CtType
            CtTypeParameter]
           [spoon.reflect.reference CtTypeParameterReference CtTypeReference
            CtWildcardReference]))

(def ^:private benchmark-sources
  #{"src/test/java/net/sf/jsqlparser/benchmark/DynamicParserRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/JSQLParserBenchmark.java"
    "src/test/java/net/sf/jsqlparser/benchmark/LatestClasspathRunner.java"
    "src/test/java/net/sf/jsqlparser/benchmark/SqlParserRunner.java"})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :kind :sqltrellis-registration-failed))))

(defn- relative-to-project [project-root path]
  (-> (paths/absolute project-root)
      (.relativize (paths/absolute path))
      str
      (.replace \\ \/)))

(defn production-source-graph
  "Returns the deterministic ordinary, generated, and resource inventory used
  by full-model SqlTrellis generation. Generated sources are the Maven/JJTree/
  JavaCC results retained by the neutral project input, not maintained target
  copies."
  [project-root project-input]
  (into (sorted-map)
        (map (fn [[kind files]]
               [kind (->> files
                          (map #(relative-to-project project-root %))
                          sort
                          vec)]))
        {:ordinary (:production-sources project-input)
         :generated (:generated-production-sources project-input)
         :resources (:production-resources project-input)}))

(defn- dependency-hashes [project-input]
  (->> (concat (:classpath-artifacts project-input)
               (:test-classpath-artifacts project-input))
       (keep (fn [{:keys [coordinate sha256]}]
               (when coordinate [coordinate sha256])))
       (into {})))

(defn validate-project-input!
  "Requires the complete pinned production/test graph retained by ingestion."
  [{:keys [workspace-root profile project-input]}]
  (let [record (baseline/read-baseline workspace-root :sqltrellis)
        discovery (get-in record [:contracts :discovery])
        project-root (paths/resolve-path workspace-root (:project-root profile))
        actual
        {:production-resources (count (:production-resources project-input))
         :test-sources (count (:test-sources project-input))
         :test-resources (count (:test-resources project-input))}
        expected (select-keys discovery (keys actual))
        test-paths (set (map #(relative-to-project project-root %)
                             (:test-sources project-input)))
        configured-benchmarks
        (set (get-in record [:contracts :benchmark-exclusions]))
        dependency-identities
        (set (map :coordinate
                  (concat (:external-dependencies project-input)
                          (:test-external-dependencies project-input))))
        hashes (dependency-hashes project-input)
        production-graph (production-source-graph project-root project-input)
        production-paths (concat (:ordinary production-graph)
                                 (:generated production-graph))]
    (baseline/validate-project-input!
     workspace-root :sqltrellis (:profile profile) project-input)
    (when-not (= expected actual)
      (fail! "SqlTrellis production/test discovery counts changed"
             {:expected expected :actual actual}))
    (when-not (= benchmark-sources configured-benchmarks)
      (fail! "SqlTrellis benchmark classification changed"
             {:expected (sort benchmark-sources)
              :actual (sort configured-benchmarks)}))
    (when-not (set/subset? benchmark-sources test-paths)
      (fail! "A classified SqlTrellis benchmark source is absent from discovery"
             {:missing (sort (set/difference benchmark-sources test-paths))}))
    (when-not (= (set (keys (:artifacts record))) dependency-identities)
      (fail! "SqlTrellis production/test dependency identities changed"
             {:expected (sort (keys (:artifacts record)))
              :actual (sort dependency-identities)}))
    (when-not (= (:artifacts record) hashes)
      (fail! "SqlTrellis production/test dependency bytes changed"
             {:expected (:artifacts record) :actual hashes}))
    (when (contains? profile :seeds)
      (fail! "SqlTrellis complete production generation must not use closure seeds"
             {:seeds (count (:seeds profile))}))
    (when-not (= (count production-paths) (count (distinct production-paths)))
      (fail! "SqlTrellis ordinary and generated production sources overlap"
             {:ordinary (count (:ordinary production-graph))
              :generated (count (:generated production-graph))}))
    project-input))

(def ^:private covariant-visitor-types
  #{"net.sf.jsqlparser.expression.ExpressionVisitor"
    "net.sf.jsqlparser.statement.StatementVisitor"
    "net.sf.jsqlparser.statement.select.FromItemVisitor"
    "net.sf.jsqlparser.statement.select.SelectItemVisitor"
    "net.sf.jsqlparser.statement.select.SelectVisitor"})

(def ^:private existential-expression-list-contracts
  {"net.sf.jsqlparser.expression.operators.relational.ExpressionList"
   "global::DripSharp.SqlTrellis.Expression.Operators.Relational.ExpressionList"
   "net.sf.jsqlparser.expression.operators.relational.NamedExpressionList"
   "global::DripSharp.SqlTrellis.Expression.Operators.Relational.NamedExpressionList"
   "net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList"
   "global::DripSharp.SqlTrellis.Expression.Operators.Relational.ParenthesedExpressionList"})

(defn- raw [text]
  (csharp/raw text))

(defn- existential-expression-list-shape
  [{:keys [context ^CtTypeReference reference]}]
  (let [qualified (.getQualifiedName reference)
        arguments (vec (.getActualTypeArguments reference))]
    (when (and (contains? existential-expression-list-contracts qualified)
               (or (empty? arguments)
                   (some #(or (instance? CtWildcardReference %)
                              (= "java.lang.Object"
                                 (.getQualifiedName ^CtTypeReference %)))
                         arguments)))
      [(csharp/generic-name
        (raw (get existential-expression-list-contracts qualified))
        [(raw "global::DripSharp.SqlTrellis.Expression.Expression")])
       :sqltrellis.type/covariant-expression-list-contract])))

(defn- direct-validator-generic-erasure?
  [{:keys [^CtTypeReference reference default-eligible?]}]
  (let [qualified (.getQualifiedName reference)
        arguments (vec (.getActualTypeArguments reference))]
    (if (contains?
         #{"net.sf.jsqlparser.util.validation.Validator"
           "net.sf.jsqlparser.util.validation.validator.AbstractValidator"}
         qualified)
      (and default-eligible?
           (or (empty? arguments)
               (some #(instance? CtWildcardReference %) arguments)))
      default-eligible?)))

(defn- generic-contract-base-nodes
  [_context ^CtType type]
  (case (.getQualifiedName type)
    "net.sf.jsqlparser.util.validation.Validator"
    [(raw "global::DripSharp.SqlTrellis.Util.Validation.IValidator")]

    "net.sf.jsqlparser.util.validation.validator.AbstractValidator"
    [(raw "global::DripSharp.SqlTrellis.Util.Validation.IAbstractValidator")]

    []))

(defn- enclosing-method [^CtElement element]
  (loop [current (when (.isParentInitialized element) (.getParent element))]
    (cond
      (nil? current) nil
      (instance? CtMethod current) current
      (.isParentInitialized ^CtElement current)
      (recur (.getParent ^CtElement current))
      :else nil)))

(defn- invocation-method [source target]
  (or (when (instance? CtMethod target) target)
      (when (and source
                 (.isParentInitialized ^CtElement source)
                 (instance? CtInvocation (.getParent ^CtElement source)))
        (some-> ^CtElement source
                .getParent
                ^CtInvocation
                .getExecutable
                .getDeclaration))))

(defn- forwarded-wildcard-parameter?
  [source target expected-argument source-argument]
  (let [source-declaration
        (when (instance? CtVariableAccess source)
          (some-> ^CtVariableAccess source .getVariable .getDeclaration))
        current-method (enclosing-method source)
        target-method (invocation-method source target)]
    (and (instance? CtParameter source-declaration)
         (instance? CtMethod current-method)
         (instance? CtMethod target-method)
         (= (.getSimpleName ^CtMethod current-method)
            (.getSimpleName ^CtMethod target-method))
         (instance? CtWildcardReference source-argument))))

(defn- in-scope-type-parameter?
  [source ^CtTypeParameterReference reference]
  (let [declaration
        (try (.getDeclaration reference) (catch Throwable _ nil))]
    (loop [current source]
      (cond
        (nil? current) false
        (and (or (instance? CtMethod current) (instance? CtType current))
             (some #(identical? declaration %)
                   (.getFormalCtTypeParameters current)))
        true

        (.isParentInitialized ^CtElement current)
        (recur (.getParent ^CtElement current))

        :else false))))

(defn- concrete-expression-list-argument
  [destination-context source destination-argument source-argument]
  (cond
    (instance? CtWildcardReference destination-argument)
    (let [bound (.getBoundingType ^CtWildcardReference destination-argument)]
      (if (and bound
               (not= "java.lang.Object" (.getQualifiedName bound)))
        (java-library/type-node destination-context bound)
        (raw "global::DripSharp.SqlTrellis.Expression.Expression")))

    (and (instance? CtTypeParameterReference destination-argument)
         (not (in-scope-type-parameter? source destination-argument)))
    (if (and source-argument
             (not (instance? CtWildcardReference source-argument))
             (not (instance? CtTypeParameterReference source-argument)))
      (java-library/type-node destination-context source-argument)
      (raw "global::DripSharp.SqlTrellis.Expression.Expression"))

    destination-argument
    (java-library/type-node destination-context destination-argument)

    :else
    (raw "global::DripSharp.SqlTrellis.Expression.Expression")))

(defn- implemented-interface-arguments
  [source expected-name]
  (let [source-reference (some-> source .getType)
        declaration (some-> ^CtTypeReference source-reference .getTypeDeclaration)]
    (when (instance? CtType declaration)
      (let [formals (vec (.getFormalCtTypeParameters ^CtType declaration))
            actuals (vec (.getActualTypeArguments ^CtTypeReference source-reference))
            substitutions
            (cond
              (= (count formals) (count actuals))
              (zipmap (map #(.getSimpleName ^CtTypeParameter %) formals)
                      actuals)

              (and (empty? actuals) (seq formals))
              (zipmap (map #(.getSimpleName ^CtTypeParameter %) formals)
                      (repeat ::raw-object))

              :else {})]
        (some
         (fn [^CtTypeReference interface-reference]
           (when (= expected-name (.getQualifiedName interface-reference))
             (mapv
              (fn [argument]
                (if (instance? CtTypeParameterReference argument)
                  (or (get substitutions (.getSimpleName argument))
                      (when-let [formal
                                 (some #(when (= (.getSimpleName
                                                 ^CtTypeParameter %)
                                                (.getSimpleName
                                                 ^CtTypeParameterReference
                                                 argument))
                                          %)
                                       formals)]
                        (first
                         (remove
                          #(= "java.lang.Object"
                              (.getQualifiedName ^CtTypeReference %))
                          (concat [(.getSuperclass ^CtTypeParameter formal)]
                                  (.getSuperInterfaces
                                   ^CtTypeParameter formal)))))
                      argument)
                  argument))
              (.getActualTypeArguments interface-reference))))
         (.getSuperInterfaces ^CtType declaration))))))

(defn- expression-list-value-adapter
  [{:keys [destination-context source target-reference target node]}]
  (let [expected-name (some-> ^CtTypeReference target-reference .getQualifiedName)
        source-reference (some-> source .getType)
        source-name (some-> ^CtTypeReference source-reference .getQualifiedName)
        expected-arguments
        (when target-reference
          (vec (.getActualTypeArguments ^CtTypeReference target-reference)))
        source-arguments
        (when source-reference
          (vec (.getActualTypeArguments ^CtTypeReference source-reference)))
        fluent-target-reference
        (when (instance? CtInvocation source)
          (some-> ^CtInvocation source .getTarget .getType))
        fluent-target-arguments
        (when (and fluent-target-reference
                   (= source-name
                      (.getQualifiedName ^CtTypeReference fluent-target-reference)))
          (vec (.getActualTypeArguments
                ^CtTypeReference fluent-target-reference)))
        expected-argument (first expected-arguments)
        source-argument (first source-arguments)
        destination-argument
        (if (and (instance? CtMethod target)
                 (instance? CtWildcardReference expected-argument)
                 (= 1 (count fluent-target-arguments)))
          (first fluent-target-arguments)
          expected-argument)]
    (when (and (not (instance? CtThisAccess source))
               (not (forwarded-wildcard-parameter?
                     source target expected-argument source-argument))
               (= "net.sf.jsqlparser.expression.operators.relational.ExpressionList"
                  expected-name)
               (= 1 (count expected-arguments))
               (contains?
                #{"net.sf.jsqlparser.expression.operators.relational.ExpressionList"
                  "net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList"
                  "net.sf.jsqlparser.expression.RowConstructor"}
                source-name)
               (or (not= expected-name source-name)
                   (instance? CtWildcardReference expected-argument)
                   (not= (mapv str expected-arguments)
                         (mapv str source-arguments))))
      (csharp/sequence-node
       [(raw "global::DripSharp.SqlTrellis.SqlTrellisGenericCompatibility.CastExpressionList<")
        (concrete-expression-list-argument
         destination-context source destination-argument source-argument)
        (raw ">(") node (raw ")")]))))

(defn- visitor-argument-adapter
  [{:keys [destination-context kind source target-reference target node]}]
  (let [expected-name (some-> ^CtTypeReference target-reference .getQualifiedName)
        target-method (invocation-method source target)
        invocation
        (when (and source
                   (.isParentInitialized ^CtElement source)
                   (instance? CtInvocation (.getParent ^CtElement source)))
          (.getParent ^CtElement source))
        expected-arguments
        (some-> ^CtTypeReference target-reference
                .getActualTypeArguments
                vec)
        expected-argument (first expected-arguments)
        source-argument
        (some-> source .getType .getActualTypeArguments vec first)
        target-formals
        (when (instance? CtMethod target-method)
          (vec (.getFormalCtTypeParameters ^CtMethod target-method)))
        invocation-actuals
        (when (instance? CtInvocation invocation)
          (vec (.getActualTypeArguments
                (.getExecutable ^CtInvocation invocation))))
        resolved-arguments
        (or
         (implemented-interface-arguments source expected-name)
         (mapv
          (fn [expected]
            (if (instance? CtTypeParameterReference expected)
              (if-let [formal-index
                       (first
                        (keep-indexed
                         (fn [index ^CtTypeParameter formal]
                           (when (= (.getSimpleName formal)
                                    (.getSimpleName
                                     ^CtTypeParameterReference expected))
                             index))
                         target-formals))]
                (nth invocation-actuals formal-index expected)
                expected)
              expected))
          expected-arguments))
        visitor-node
        (if (and (seq resolved-arguments)
                 (= (count resolved-arguments) (count expected-arguments)))
          (let [rendered
                (:text
                 (csharp/render
                  (java-library/type-node destination-context target-reference)))
                separator (.lastIndexOf ^String rendered "<")]
            (if (pos? separator)
              (csharp/generic-name
               (raw (subs rendered 0 separator))
               (mapv
                #(if (= ::raw-object %)
                   (raw "object")
                   (java-library/type-node destination-context %))
                resolved-arguments))
              (java-library/type-node destination-context target-reference)))
          (java-library/type-node destination-context target-reference))]
    (when (and (= :argument kind)
               (instance? CtMethod target-method)
               (= "accept" (.getSimpleName ^CtMethod target-method))
               expected-name
               (.endsWith ^String expected-name "Visitor")
               (some-> ^CtTypeReference target-reference
                       .getTypeDeclaration
                       .isInterface)
               (not (forwarded-wildcard-parameter?
                     source target expected-argument source-argument)))
      (csharp/sequence-node
       [(raw "(")
        visitor-node
        (raw ")(") node (raw ")")]))))

(defn- sqltrellis-value-adapter [context]
  (or (expression-list-value-adapter context)
      (visitor-argument-adapter context)))

(defn- expression-list-instanceof-adapter
  [{:keys [destination-context kind left left-expression right-expression]}]
  (let [reference
        (when (and (= "INSTANCEOF" (str kind))
                   (instance? CtTypeAccess right-expression))
          (.getAccessedType ^CtTypeAccess right-expression))
        qualified (some-> ^CtTypeReference reference .getQualifiedName)
        left-argument
        (some-> ^CtElement left-expression
                .getType
                .getActualTypeArguments
                vec
                first)
        destination-argument
        (if (and left-argument
                 (not (instance? CtWildcardReference left-argument))
                 (not (and (instance? CtTypeParameterReference left-argument)
                           (nil? (try
                                   (.getDeclaration
                                    ^CtTypeParameterReference left-argument)
                                   (catch Throwable _ nil))))))
          (java-library/type-node destination-context left-argument)
          (raw "global::DripSharp.SqlTrellis.Expression.Expression"))]
    (when (contains?
           #{"net.sf.jsqlparser.expression.operators.relational.NamedExpressionList"
             "net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList"}
           qualified)
      (csharp/sequence-node
       [(raw "(") left (raw " is ")
        (csharp/generic-name
         (raw (get existential-expression-list-contracts qualified))
         [destination-argument])
        (raw ")")]))))

(defn- sqltrellis-runtime-assets [_context]
  [{:source "targets/sqltrellis/runtime/DripSharp.SqlTrellis.Compatibility.cs"
    :destination "DripSharp/SqlTrellis/Compatibility.cs"
    :strategy :sqltrellis/target-owned-generic-compatibility
    :authorship-class :authored-destination-runtime
    :missing-kind :missing-sqltrellis-compatibility-source
    :missing-message "SqlTrellis target-owned compatibility source is missing"}])

(defn rule-bundle
  "Extends the shared Java-library rules with SqlTrellis identity/accounting."
  []
  (let [base (java-library/rule-bundle)
        base-validator (get-in base [:orchestration :validate-project-input!])
        base-assets (get-in base [:rules :destination-bridges :assets])
        base-create-context
        (get-in base [:rules :structural-declarations :create-context])]
    (-> base
        (assoc :id :sqltrellis :product-family :sqltrellis)
        (assoc-in
         [:orchestration :validate-project-input!]
         (fn [context]
           (base-validator context)
           (validate-project-input! context)))
        (assoc-in
         [:rules :structural-declarations :create-context]
         (fn [options]
           (base-create-context
            (assoc options
                   :covariant-interface-types covariant-visitor-types
                   :destination-base-type-nodes generic-contract-base-nodes
                   :destination-value-adapter sqltrellis-value-adapter
                   :destination-binary-adapter
                   expression-list-instanceof-adapter
                   :resolved-type-policy
                   {:adapt-shape existential-expression-list-shape
                    :generic-erasure? direct-validator-generic-erasure?}))))
        (assoc-in
         [:rules :product-runtime-assets :assets]
         sqltrellis-runtime-assets)
        (assoc-in
         [:rules :destination-bridges :assets]
         (fn [context]
           ;; Legal assets are still emitted by the shared Java-library policy;
           ;; only the externally visible product family differs.
           (base-assets
            (assoc-in context [:configuration :product-family] :java-library)))))))

(defn public-surface-strategy
  "Uses the shared complete-accessible surface policy under SqlTrellis identity."
  []
  (assoc (java-library/public-surface-strategy)
         :id :sqltrellis-complete-accessible-java-library
         :product-family :sqltrellis))
