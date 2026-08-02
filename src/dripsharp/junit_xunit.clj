(ns dripsharp.junit-xunit
  "Resolved-symbol JUnit 4/Jupiter discovery and xUnit lowering.

  This adapter inventories framework annotations separately from assertion and
  mocking APIs, builds an explicit case/lifecycle plan from live Spoon
  declarations, and rejects every unknown JUnit annotation. Ordinary Java test
  bodies continue through `dripsharp.java-library/translate-body`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dripsharp.java-library :as java-library]
            [dripsharp.java-test-adapters :as test-adapters]
            [dripsharp.java-translate :as java]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process]
            [dripsharp.spoon :as spoon]
            [dripsharp.util :as util])
  (:import [java.nio.file Files Path]
           [java.util IdentityHashMap]
           [spoon.reflect.code CtBinaryOperator CtFieldRead CtLiteral CtNewArray
            CtTypeAccess]
           [spoon.reflect.declaration CtAnnotation CtClass CtConstructor
            CtElement CtField CtMethod CtModifiable CtParameter CtType
            ModifierKind]))

(def schema-version 1)

(def inventory-schema-version 2)

(def ^:private api-classification-contract
  [{:api :junit4
    :kind :framework-api
    :source-languages #{:java}
    :adaptation :shared-resolved-xunit-adapter}
   {:api :junit-jupiter
    :kind :framework-api
    :source-languages #{:java :kotlin}
    :adaptation :shared-resolved-xunit-adapter
    :kotlin-policy :evidence-only-no-kotlin-frontend}
   {:api :junit-platform
    :kind :framework-api
    :source-languages #{:kotlin}
    :adaptation :target-test-utility-evidence
    :kotlin-policy :evidence-only-no-kotlin-frontend}
   {:api :assertj
    :kind :framework-api
    :source-languages #{:java :kotlin}
    :adaptation :shared-resolved-xunit-adapter
    :kotlin-policy :evidence-only-no-kotlin-frontend}
   {:api :hamcrest
    :kind :framework-api
    :source-languages #{:java}
    :adaptation :shared-resolved-xunit-adapter}
   {:api :mockito
    :kind :framework-api
    :source-languages #{:java}
    :adaptation :shared-resolved-xunit-adapter}
   {:api :kotest
    :kind :framework-api
    :source-languages #{:kotlin}
    :adaptation :evidence-only-no-kotlin-frontend
    :kotlin-policy :evidence-only-no-kotlin-frontend}
   {:api :h2
    :kind :target-owned-utility
    :source-languages #{:java}
    :adaptation :target-strategy}
   {:api :wiremock
    :kind :target-owned-utility
    :source-languages #{:kotlin}
    :adaptation :target-strategy
    :kotlin-policy :evidence-only-no-kotlin-frontend}
   {:api :jimfs
    :kind :target-owned-utility
    :source-languages #{:kotlin}
    :adaptation :target-strategy
    :kotlin-policy :evidence-only-no-kotlin-frontend}])

(def ^:private governed-framework-apis
  (set (map :api api-classification-contract)))

(def ^:private canonical-disabled-reason
  "Upstream @Disabled/@Ignore has no reason.")

(def annotation-contracts
  "Exact resolved annotation identities owned by the JUnit/xUnit adapter.
  Assertion calls and third-party extensions intentionally live in other
  adapters."
  {"annotation:org.junit.Test"
   {:framework :junit4 :role :test :lowering :xunit-fact}
   "annotation:org.junit.Before"
   {:framework :junit4 :role :before-each :lowering :case-wrapper}
   "annotation:org.junit.After"
   {:framework :junit4 :role :after-each :lowering :case-wrapper-finally}
   "annotation:org.junit.BeforeClass"
   {:framework :junit4 :role :before-all :lowering :xunit-class-fixture}
   "annotation:org.junit.AfterClass"
   {:framework :junit4 :role :after-all :lowering :xunit-class-fixture-dispose}
   "annotation:org.junit.Ignore"
   {:framework :junit4 :role :disabled :lowering :xunit-skip}
   "annotation:org.junit.Rule"
   {:framework :junit4 :role :instance-rule :lowering :resolved-rule-adapter}
   "annotation:org.junit.ClassRule"
   {:framework :junit4 :role :class-rule :lowering :resolved-rule-adapter}
   "annotation:org.junit.runner.RunWith"
   {:framework :junit4 :role :runner :lowering :resolved-runner-adapter}
   "annotation:org.junit.runners.Parameterized$Parameters"
   {:framework :junit4 :role :junit4-parameters :lowering :xunit-member-data}
   ;; Spoon releases have represented nested annotation types with both binary
   ;; and source spelling. Both remain exact, explicit resolved identities.
   "annotation:org.junit.runners.Parameterized.Parameters"
   {:framework :junit4 :role :junit4-parameters :lowering :xunit-member-data}
   "annotation:org.junit.runners.Parameterized$Parameter"
   {:framework :junit4 :role :junit4-parameter :lowering :theory-parameter}
   "annotation:org.junit.runners.Parameterized.Parameter"
   {:framework :junit4 :role :junit4-parameter :lowering :theory-parameter}

   "annotation:org.junit.jupiter.api.Test"
   {:framework :jupiter :role :test :lowering :xunit-fact}
   "annotation:org.junit.jupiter.api.BeforeEach"
   {:framework :jupiter :role :before-each :lowering :case-wrapper}
   "annotation:org.junit.jupiter.api.AfterEach"
   {:framework :jupiter :role :after-each :lowering :case-wrapper-finally}
   "annotation:org.junit.jupiter.api.BeforeAll"
   {:framework :jupiter :role :before-all :lowering :xunit-class-fixture}
   "annotation:org.junit.jupiter.api.AfterAll"
   {:framework :jupiter :role :after-all :lowering :xunit-class-fixture-dispose}
   "annotation:org.junit.jupiter.api.Disabled"
   {:framework :jupiter :role :disabled :lowering :xunit-skip}
   "annotation:org.junit.jupiter.api.DisplayName"
   {:framework :jupiter :role :display-name :lowering :xunit-display-name}
   "annotation:org.junit.jupiter.api.Nested"
   {:framework :jupiter :role :nested :lowering :nested-instance-chain}
   "annotation:org.junit.jupiter.api.RepeatedTest"
   {:framework :jupiter :role :repeated-test :lowering :xunit-theory-rows}
   "annotation:org.junit.jupiter.api.TestFactory"
   {:framework :jupiter :role :dynamic-factory :lowering :dynamic-case-wrapper}
   "annotation:org.junit.jupiter.api.TestInstance"
   {:framework :jupiter :role :test-instance :lowering :instance-lifecycle}
   "annotation:org.junit.jupiter.api.Timeout"
   {:framework :jupiter :role :timeout :lowering :jupiter-timeout-guard}
   "annotation:org.junit.jupiter.api.Order"
   {:framework :jupiter :role :order :lowering :explicit-case-order}
   "annotation:org.junit.jupiter.api.TestMethodOrder"
   {:framework :jupiter :role :method-order :lowering :explicit-case-order}
   "annotation:org.junit.jupiter.api.extension.ExtendWith"
   {:framework :jupiter :role :extension :lowering :resolved-extension-adapter}
   "annotation:org.junit.jupiter.api.extension.RegisterExtension"
   {:framework :jupiter :role :extension :lowering :resolved-extension-adapter}
   "annotation:org.junit.jupiter.api.io.TempDir"
   {:framework :jupiter :role :temporary-directory :lowering :temporary-directory-fixture}
   "annotation:org.junit.jupiter.api.parallel.Execution"
   {:framework :jupiter :role :execution :lowering :xunit-parallel-plan}
   "annotation:org.junit.jupiter.api.parallel.Isolated"
   {:framework :jupiter :role :isolated :lowering :xunit-serial-collection}
   "annotation:org.junit.jupiter.api.parallel.ResourceLock"
   {:framework :jupiter :role :resource-lock :lowering :xunit-resource-collection}

   "annotation:org.junit.jupiter.params.ParameterizedTest"
   {:framework :jupiter :role :parameterized-test :lowering :xunit-theory}
   "annotation:org.junit.jupiter.params.provider.ValueSource"
   {:framework :jupiter :role :value-source :lowering :xunit-inline-data}
   "annotation:org.junit.jupiter.params.provider.CsvSource"
   {:framework :jupiter :role :csv-source :lowering :xunit-inline-data}
   "annotation:org.junit.jupiter.params.provider.CsvFileSource"
   {:framework :jupiter :role :csv-file-source :lowering :xunit-member-data}
   "annotation:org.junit.jupiter.params.provider.EnumSource"
   {:framework :jupiter :role :enum-source :lowering :xunit-member-data}
   "annotation:org.junit.jupiter.params.provider.MethodSource"
   {:framework :jupiter :role :method-source :lowering :xunit-member-data}
   "annotation:org.junit.jupiter.params.provider.ArgumentsSource"
   {:framework :jupiter :role :arguments-source :lowering :xunit-member-data}

   "annotation:org.junit.jupiter.api.condition.DisabledIf"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.DisabledIfSystemProperty"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.DisabledOnJre"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.DisabledOnOs"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.EnabledForJreRange"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.EnabledIfSystemProperty"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}
   "annotation:org.junit.jupiter.api.condition.EnabledOnOs"
   {:framework :jupiter :role :condition :lowering :runtime-skip-condition}

   "annotation:org.junit.platform.suite.api.Suite"
   {:framework :junit-platform :role :suite :lowering :expanded-suite-selection}
   "annotation:org.junit.platform.suite.api.SelectClasses"
   {:framework :junit-platform :role :suite-selector :lowering :expanded-suite-selection}
   "annotation:org.junit.platform.suite.api.SelectPackages"
   {:framework :junit-platform :role :suite-selector :lowering :expanded-suite-selection}
   "annotation:org.junit.platform.suite.api.IncludeTags"
   {:framework :junit-platform :role :suite-filter :lowering :xunit-trait-filter}
   "annotation:org.junit.platform.suite.api.ExcludeTags"
   {:framework :junit-platform :role :suite-filter :lowering :xunit-trait-filter}})

(def ^:private test-roles
  #{:test :parameterized-test :repeated-test :dynamic-factory})

(def ^:private parameter-source-roles
  #{:value-source :csv-source :csv-file-source :enum-source :method-source
    :arguments-source})

(def ^:private unsupported-java-roles
  ;; These identities remain in the pinned inventory registry so Kotlin-only
  ;; evidence can be accounted for. A Java source that introduces one must not
  ;; silently pass until its runtime lowering is implemented.
  #{:condition :method-order :order :suite :suite-filter :suite-selector
    :test-instance})

(defn- fail!
  [message data]
  (throw (ex-info message (assoc data :kind :junit-xunit-adaptation-failed))))

(defn- junit-symbol?
  [symbol]
  (str/starts-with? symbol "annotation:org.junit"))

(defn- source-data
  [^CtElement element]
  {:source (spoon/source-location element)
   :frontend (spoon/frontend-diagnostic element)})

(defn- occurrence!
  [^IdentityHashMap index ^CtElement element expected-kind]
  (let [occurrence (.get index element)]
    (when-not (= expected-kind (:kind occurrence))
      (fail! "JUnit construct has no matching resolved occurrence"
             (merge {:reason :missing-resolved-junit-occurrence
                     :expected-kind expected-kind
                     :resolved (select-keys occurrence
                                            [:kind :key :origin :resolution])}
                    (source-data element))))
    occurrence))

(declare expression-value)

(defn- field-value
  [index ^CtFieldRead expression]
  (let [occurrence (occurrence! index (.getVariable expression) :field)
        declaring (some-> expression .getVariable .getDeclaringType
                          .getQualifiedName)
        name (some-> expression .getVariable .getSimpleName)]
    (if (= "class" name)
      {:class declaring}
      {:field (:key occurrence)})))

(defn- expression-value
  [index expression]
  (cond
    (instance? CtLiteral expression)
    (.getValue ^CtLiteral expression)

    (instance? CtNewArray expression)
    (mapv #(expression-value index %) (.getElements ^CtNewArray expression))

    (instance? CtFieldRead expression)
    (field-value index expression)

    (instance? CtTypeAccess expression)
    {:class (some-> ^CtTypeAccess expression .getAccessedType .getQualifiedName)}

    (instance? CtBinaryOperator expression)
    (let [operator ^CtBinaryOperator expression
          kind (str (.getKind operator))
          left (expression-value index (.getLeftHandOperand operator))
          right (expression-value index (.getRightHandOperand operator))]
      (case kind
        "PLUS"
        (cond
          (or (string? left) (string? right)) (str left right)
          (and (number? left) (number? right)) (+ left right)
          :else
          (fail! "JUnit annotation addition has non-constant operands"
                 (merge {:reason :unsupported-junit-annotation-value
                         :operator kind
                         :left left
                         :right right}
                        (source-data expression))))

        (fail! "JUnit annotation binary expression has no explicit semantic lowering"
               (merge {:reason :unsupported-junit-annotation-value
                       :operator kind
                       :left left
                       :right right}
                      (source-data expression)))))

    :else
    (fail! "JUnit annotation value has no explicit semantic lowering"
           (merge {:reason :unsupported-junit-annotation-value
                   :value-class (.getName (class expression))}
                  (source-data expression)))))

(defn- annotation-values
  [index ^CtAnnotation annotation]
  (into (sorted-map)
        (map (fn [[name expression]]
               [(keyword (str name)) (expression-value index expression)]))
        (.getValues annotation)))

(defn- resolved-annotation
  [index ^CtAnnotation annotation]
  (let [occurrence (occurrence! index annotation :annotation)
        symbol (:key occurrence)
        contract (get annotation-contracts symbol)]
    (when (and (junit-symbol? symbol) (nil? contract))
      (fail! "JUnit annotation has no xUnit adapter"
             (merge {:reason :unmapped-junit-construct
                     :symbol symbol
                     :resolved (select-keys occurrence
                                            [:kind :key :origin :resolution])}
                    (source-data annotation))))
    (when contract
      (merge contract
             {:symbol symbol
              :values (annotation-values index annotation)}
             (source-data annotation)))))

(defn- annotations
  [index ^CtElement element]
  (->> (.getAnnotations element)
       (keep #(resolved-annotation index %))
       (sort-by (juxt (comp :line :source) :symbol))
       vec))

(defn- source-order
  [elements]
  (sort-by (juxt #(or (get-in % [:source :line]) Long/MAX_VALUE)
                 :symbol)
           elements))

(defn- class-name
  [^CtType type]
  (.getQualifiedName type))

(defn- method-id
  [^CtMethod method]
  (str (class-name (.getDeclaringType method)) "#" (.getSignature method)))

(defn- method-record
  [index ^CtMethod method]
  {:id (method-id method)
   :name (.getSimpleName method)
   :signature (.getSignature method)
   :declaring-class (class-name (.getDeclaringType method))
   :static? (.hasModifier ^CtModifiable method ModifierKind/STATIC)
   :parameters
   (mapv (fn [^CtParameter parameter]
           {:name (.getSimpleName parameter)
            :type (some-> parameter .getType .getQualifiedName)
            :annotations (annotations index parameter)})
         (.getParameters method))
   :annotations (annotations index method)
   :source (spoon/source-location method)
   :element method})

(defn- constructor-record
  [index ^CtConstructor constructor]
  {:id (str (class-name (.getDeclaringType constructor)) "#"
            (.getSignature constructor))
   :signature (.getSignature constructor)
   :parameters
   (mapv (fn [^CtParameter parameter]
           {:name (.getSimpleName parameter)
            :type (some-> parameter .getType .getQualifiedName)
            :annotations (annotations index parameter)})
         (.getParameters constructor))
   :source (spoon/source-location constructor)})

(defn- field-record
  [index ^CtField field]
  {:id (str (class-name (.getDeclaringType field)) "#" (.getSimpleName field))
   :name (.getSimpleName field)
   :type (some-> field .getType .getQualifiedName)
   :static? (.hasModifier ^CtModifiable field ModifierKind/STATIC)
   :annotations (annotations index field)
   :source (spoon/source-location field)})

(defn- direct-methods
  [index ^CtType type]
  (->> (.getMethods type)
       (filter #(identical? type (.getDeclaringType ^CtMethod %)))
       (map #(method-record index %))
       (sort-by (juxt (comp :line :source) :signature))
       vec))

(defn- direct-fields
  [index ^CtType type]
  (->> (.getFields type)
       (filter #(identical? type (.getDeclaringType ^CtField %)))
       (map #(field-record index %))
       (sort-by (juxt (comp :line :source) :name))
       vec))

(defn- direct-constructors
  [index ^CtType type]
  (if (instance? CtClass type)
    (->> (.getConstructors ^CtClass type)
         (remove #(.isImplicit ^CtConstructor %))
         (map #(constructor-record index %))
         (sort-by (juxt (comp :line :source) :signature))
         vec)
    []))

(defn- project-superclass
  [^CtType type]
  (when (instance? CtClass type)
    (when-let [reference (.getSuperclass ^CtClass type)]
      (let [declaration (.getTypeDeclaration reference)]
        (when (and (instance? CtClass declaration)
                   (not (.isShadow ^CtClass declaration)))
          declaration)))))

(defn- ancestry
  [^CtType type]
  (loop [current type result []]
    (if current
      (recur (project-superclass current) (conj result current))
      (vec (reverse result)))))

(defn- enclosing-types
  [^CtType type]
  (loop [current type result []]
    (if current
      (recur (.getDeclaringType current) (conj result current))
      (vec (reverse result)))))

(defn- lifecycle-families
  [^CtType type]
  (->> (enclosing-types type)
       (mapv ancestry)))

(defn- role-annotations
  [records role]
  (->> records
       (mapcat (fn [record]
                 (for [annotation (:annotations record)
                       :when (= role (:role annotation))]
                   (assoc record :framework-annotation annotation))))
       source-order
       vec))

(defn- lifecycle-plan
  [index ^CtType type]
  (let [families (lifecycle-families type)
        effective-family
        (fn [family]
          (->> family
               (mapcat #(direct-methods index %))
               (reduce (fn [by-signature method]
                         ;; A Java override without a lifecycle annotation
                         ;; suppresses the annotated superclass method too.
                         (assoc by-signature (:signature method) method))
                       (sorted-map))
               vals))
        ordered-family
        (fn [family role descending?]
          (let [rank (into {} (map-indexed (fn [position candidate]
                                             [(class-name candidate) position])
                                           family))]
            (->> (role-annotations (effective-family family) role)
                 (sort-by (fn [method]
                            [((if descending? - identity)
                              (get rank (:declaring-class method)))
                             (:signature method)])))))
        ordered
        (fn [role after?]
          (let [ordered-families (if after? (reverse families) families)]
            (vec (mapcat #(ordered-family % role after?)
                         ordered-families))))
        before-each (ordered :before-each false)
        before-all (ordered :before-all false)
        after-each (ordered :after-each true)
        after-all (ordered :after-all true)]
    {:class-chain (mapv class-name (mapcat identity families))
     :enclosing-class-chain (mapv class-name (enclosing-types type))
     :inheritance-class-chains
     (mapv #(mapv class-name %) families)
     :before-all (mapv :id before-all)
     :before-each (mapv :id before-each)
     :after-each (mapv :id after-each)
     :after-all (mapv :id after-all)}))

(defn- effective-class-annotations
  [records-by-name ^CtType type]
  (->> (enclosing-types type)
       (mapcat #(get-in records-by-name [(class-name %) :annotations]))
       vec))

(defn- annotation-by-role
  [annotations role]
  (first (filter #(= role (:role %)) annotations)))

(defn- annotations-by-role
  [annotations roles]
  (filterv #(contains? roles (:role %)) annotations))

(defn- disabled-plan
  [class-annotations method-annotations]
  (when-let [annotation (or (annotation-by-role method-annotations :disabled)
                            (annotation-by-role class-annotations :disabled))]
    {:symbol (:symbol annotation)
     :reason (or (get-in annotation [:values :value])
                 canonical-disabled-reason)
     :reason-supplied? (contains? (:values annotation) :value)}))

(defn- display-name
  [class-annotations method-annotations]
  (some-> (or (annotation-by-role method-annotations :display-name)
              (annotation-by-role class-annotations :display-name))
          (get-in [:values :value])))

(defn- csv-row
  [row]
  ;; Current governed Java sources use JUnit's default comma delimiter. Quoted
  ;; commas and doubled quote escapes are handled; non-default dialect options
  ;; remain explicit annotation values in the plan and are rejected below.
  (loop [characters (seq (str row)) quoted? false token "" values []]
    (if-let [character (first characters)]
      (cond
        (= character \')
        (if (and quoted? (= \' (second characters)))
          (recur (nnext characters) quoted? (str token \') values)
          (recur (next characters) (not quoted?) token values))

        (and (= character \,) (not quoted?))
        (recur (next characters) false "" (conj values (str/trim token)))

        :else
        (recur (next characters) quoted? (str token character) values))
      (do
        (when quoted?
          (fail! "JUnit CSV row has an unterminated quoted value"
                 {:reason :invalid-junit-csv-row :row row}))
        (conj values (str/trim token))))))

(defn- value-source-rows
  [annotation]
  (let [values (:values annotation)
        populated (filter (comp sequential? val) values)]
    (when-not (= 1 (count populated))
      (fail! "@ValueSource must select exactly one value family"
             {:reason :invalid-junit-value-source
              :symbol (:symbol annotation)
              :source (:source annotation)
              :values values}))
    (mapv (fn [[index value]]
            {:id (str "value:" index) :arguments [value]
             :source (:source annotation)})
          (map-indexed vector (second (first populated))))))

(defn- csv-source-rows
  [annotation]
  (let [values (:values annotation)
        unsupported (seq (remove #{:value} (keys values)))]
    (when unsupported
      (fail! "@CsvSource uses an unmapped non-default dialect option"
             {:reason :unsupported-junit-csv-option
              :symbol (:symbol annotation)
              :source (:source annotation)
              :options (vec unsupported)}))
    (mapv (fn [[index row]]
            {:id (str "csv:" index) :arguments (csv-row row)
             :raw row :source (:source annotation)})
          (map-indexed vector (:value values)))))

(defn- method-source-plan
  [method annotation]
  (let [configured (get-in annotation [:values :value])
        providers (cond
                    (nil? configured) [(:name method)]
                    (string? configured) [configured]
                    (sequential? configured) configured
                    :else
                    (fail! "@MethodSource provider names are invalid"
                           {:reason :invalid-junit-method-source
                            :value configured :source (:source annotation)}))]
    {:kind :member-data
     :providers (mapv str providers)
     :row-accounting :runtime-member-data
     :source (:source annotation)}))

(defn- parameter-plan
  [method primary]
  (let [sources (annotations-by-role (:annotations method)
                                     parameter-source-roles)]
    (case (:role primary)
      :test nil
      :dynamic-factory
      {:kind :dynamic-cases :factory (:id method)
       :row-accounting :runtime-dynamic-cases}
      :repeated-test
      (let [count (get-in primary [:values :value])]
        (when-not (pos-int? count)
          (fail! "@RepeatedTest requires a positive repetition count"
                 {:reason :invalid-junit-repetition
                  :symbol (:symbol primary) :source (:source primary)}))
        {:kind :inline-rows
         :rows (mapv (fn [index]
                       {:id (str "repetition:" index)
                        :arguments [index]})
                     (range 1 (inc count)))})
      :parameterized-test
      (do
        (when-not (seq sources)
          (fail! "@ParameterizedTest has no resolved argument source"
                 {:reason :missing-junit-parameter-source
                  :method (:id method) :source (:source method)}))
        (let [lowered
              (mapv
               (fn [source]
                 (case (:role source)
                   :value-source
                   {:kind :inline-rows :rows (value-source-rows source)}
                   :csv-source
                   {:kind :inline-rows :rows (csv-source-rows source)}
                   :method-source (method-source-plan method source)
                   :csv-file-source
                   {:kind :member-data :adapter :csv-file
                    :values (:values source)
                    :row-accounting :runtime-member-data}
                   :enum-source
                   {:kind :member-data :adapter :enum
                    :values (:values source)
                    :row-accounting :runtime-member-data}
                   :arguments-source
                   {:kind :member-data :adapter :arguments-provider
                    :values (:values source)
                    :row-accounting :runtime-member-data}))
               sources)
              plan (if (= 1 (count lowered))
                     (first lowered)
                     {:kind :composite-sources :sources lowered})]
          (cond-> plan
            (get-in primary [:values :name])
            (assoc :display-template (get-in primary [:values :name])))))
      nil)))

(defn- parameterized-runner?
  [class-annotations]
  (boolean
   (some
    (fn [annotation]
      (and (= :runner (:role annotation))
           (= {:class "org.junit.runners.Parameterized"}
              (get-in annotation [:values :value]))))
    class-annotations)))

(defn- junit4-parameter-plan
  [type-record class-annotations primary]
  (when (and (= :junit4 (:framework primary))
             (parameterized-runner? class-annotations))
    (let [providers (role-annotations (:methods type-record) :junit4-parameters)
          constructors (:constructors type-record)
          parameter-fields
          (->> (:fields type-record)
               (keep (fn [field]
                       (when-let [annotation
                                  (annotation-by-role (:annotations field)
                                                      :junit4-parameter)]
                         {:index (or (get-in annotation [:values :value]) 0)
                          :name (:name field) :type (:type field)})))
               (sort-by :index)
               vec)]
      (when-not (= 1 (count providers))
        (fail! "Parameterized JUnit 4 class must have one @Parameters provider"
               {:reason :invalid-junit4-parameters-provider
                :class (:name type-record)
                :providers (mapv :id providers)}))
      (when (< 1 (count constructors))
        (fail! "Parameterized JUnit 4 class has ambiguous constructors"
               {:reason :ambiguous-junit4-parameter-constructor
                :class (:name type-record)
                :constructors (mapv :signature constructors)}))
      (let [provider (first providers)
            constructor (first constructors)
            parameters (or (:parameters constructor) parameter-fields)]
        (when-not (seq parameters)
          (fail! "Parameterized JUnit 4 class has no constructor or field bindings"
                 {:reason :missing-junit4-parameter-bindings
                  :class (:name type-record)}))
        {:kind :member-data
         :api :junit4-parameterized-runner
         :providers [(:name provider)]
         :display-template (get-in provider [:framework-annotation :values :name])
         :constructor-parameters (vec parameters)
         :row-accounting :runtime-member-data
         :source (:source provider)}))))

(defn- junit4-expected
  [primary]
  (when-let [expected (get-in primary [:values :expected])]
    {:kind :throws-subtype :exception expected}))

(defn- timeout-plan
  [class-annotations method-annotations primary]
  (or
   (when-let [timeout (or (annotation-by-role method-annotations :timeout)
                          (annotation-by-role class-annotations :timeout))]
     {:kind :jupiter-same-thread-deadline
      :value (get-in timeout [:values :value])
      :unit (or (get-in timeout [:values :unit]) :seconds)
      :thread-mode (or (get-in timeout [:values :threadMode]) :inferred)
      :source (:source timeout)})
   (when-let [milliseconds (get-in primary [:values :timeout])]
     {:kind :junit4-preemptive-deadline
      :milliseconds milliseconds
      :source (:source primary)})))

(defn- temporary-resources
  [class-fields method]
  (let [field-resources
        (for [field class-fields
              annotation (:annotations field)
              :when (contains? #{:temporary-directory :instance-rule :class-rule}
                               (:role annotation))]
          (cond
            (= :temporary-directory (:role annotation))
            {:kind :temporary-directory :api :jupiter
             :target :field :name (:name field)
             :scope (if (:static? field) :class :case)
             :source (:source annotation)}

            (= "org.junit.rules.TemporaryFolder" (:type field))
            {:kind :temporary-directory :api :junit4-temporary-folder
             :target :field :name (:name field)
             :scope (if (= :class-rule (:role annotation)) :class :case)
             :source (:source annotation)}

            :else
            (fail! "JUnit rule has no resolved destination resource adapter"
                   {:reason :unmapped-junit-rule
                    :symbol (:symbol annotation)
                    :field (:id field) :field-type (:type field)
                    :source (:source annotation)})))
        parameter-resources
        (for [parameter (:parameters method)
              annotation (:annotations parameter)
              :when (= :temporary-directory (:role annotation))]
          {:kind :temporary-directory :api :jupiter
           :target :parameter
           :name (:name parameter) :scope :case
           :source (:source annotation)})]
    (vec (concat field-resources parameter-resources))))

(defn- parallel-plan
  [class-annotations method-annotations]
  (let [all (concat method-annotations class-annotations)
        execution (annotation-by-role all :execution)
        isolated (annotation-by-role all :isolated)
        locks (filter #(= :resource-lock (:role %)) all)]
    (cond
      isolated
      {:kind :serial-collection :reason :jupiter-isolated}

      (seq locks)
      {:kind :resource-collection
       :resources (mapv #(get-in % [:values :value]) locks)}

      execution
      {:kind :execution-mode :mode (get-in execution [:values :value])}

      :else {:kind :framework-default})))

(defn- case-plan
  [type-record class-annotations method primary]
  (let [method-annotations (:annotations method)
        parameters (or (parameter-plan method primary)
                       (junit4-parameter-plan
                        type-record class-annotations primary))]
    {:id (str (:name type-record) "#" (:signature method))
     :declaring-method (:id method)
     :class (:name type-record)
     :framework (:framework primary)
     :kind (case (:role primary)
             :test (if parameters :theory :fact)
             :parameterized-test :theory
             :repeated-test :theory
             :dynamic-factory :dynamic-factory)
     :primary-symbol (:symbol primary)
     :display-name (display-name class-annotations method-annotations)
     :disabled (disabled-plan class-annotations method-annotations)
     :parameters parameters
     :expected-exception (junit4-expected primary)
     :timeout (timeout-plan class-annotations method-annotations primary)
     :temporary-resources (temporary-resources (:fields type-record) method)
     :parallel (parallel-plan class-annotations method-annotations)
     :lifecycle (:lifecycle type-record)
     :source (:source method)
     :method-element (:element method)}))

(defn- concrete-class?
  [^CtType type]
  (and (instance? CtClass type)
       (not (.hasModifier ^CtModifiable type ModifierKind/ABSTRACT))))

(defn- effective-test-methods
  [records-by-name type-record]
  (let [chain (ancestry (:type-element type-record))]
    (->> chain
         (map #(get records-by-name (class-name %)))
         (mapcat :methods)
         (reduce (fn [by-signature method]
                   (let [primaries
                         (filter #(contains? test-roles (:role %))
                                 (:annotations method))]
                     (when (< 1 (count primaries))
                       (fail! "JUnit method has multiple discovery annotations"
                              {:reason :ambiguous-junit-test-method
                               :method (:id method)
                               :symbols (mapv :symbol primaries)
                               :source (:source method)}))
                     (if-let [primary (first primaries)]
                       (assoc by-signature (:signature method)
                              [method primary])
                       (dissoc by-signature (:signature method)))))
                 (sorted-map))
         vals
         vec)))

(defn- fail-on-unsupported-java-annotations!
  [records]
  (doseq [annotation
          (mapcat (fn [record]
                    (concat (:annotations record)
                            (mapcat :annotations (:methods record))
                            (mapcat :annotations (:fields record))
                            (mapcat (comp #(mapcat :annotations %) :parameters)
                                    (:methods record))))
                  records)
          :when (contains? unsupported-java-roles (:role annotation))]
    (fail! "Resolved JUnit annotation has no Java-to-xUnit runtime lowering"
           {:reason :unmapped-junit-construct
            :symbol (:symbol annotation)
            :role (:role annotation)
            :source (:source annotation)})))

(defn- discoverable-type?
  [records-by-name type-record]
  (loop [^CtType type (:type-element type-record)]
    (if (.isTopLevel type)
      true
      (let [record (get records-by-name (class-name type))
            nested? (some #(= :nested (:role %)) (:annotations record))
            static? (.hasModifier ^CtModifiable type ModifierKind/STATIC)]
        (and nested?
             (not static?)
             (recur (.getDeclaringType type)))))))

(defn plan-suite
  "Builds a deterministic, fail-closed JUnit-to-xUnit plan from a resolved Java
  model. The plan retains live method elements only so callers can feed their
  bodies to `translate-test-body!`; serializable-plan removes those handles."
  ([resolved-model] (plan-suite resolved-model {}))
  ([resolved-model {:keys [extension-adapters runner-adapters]
                    :or {extension-adapters #{} runner-adapters #{}}}]
   (let [index (java/resolved-occurrence-index resolved-model)
         types (java/project-roots resolved-model)
         all-types
         (->> (:project-types resolved-model)
              vals
              (filter #(instance? CtType %))
              (reduce #(assoc %1 (class-name %2) %2) (sorted-map))
              vals
              vec)
         records
         (mapv
          (fn [^CtType type]
            (let [type-annotations (annotations index type)
                  methods (direct-methods index type)
                  fields (direct-fields index type)
                  extensions (filter #(= :extension (:role %))
                                     (concat type-annotations
                                             (mapcat :annotations methods)
                                             (mapcat :annotations fields)))
                  runners (filter #(= :runner (:role %)) type-annotations)]
              (doseq [extension extensions]
                (let [configured (set (vals (:values extension)))]
                  (when-not (some extension-adapters configured)
                    (fail! "JUnit extension has no resolved destination adapter"
                           {:reason :unmapped-junit-extension
                            :symbol (:symbol extension)
                            :values (:values extension)
                            :source (:source extension)}))))
              (doseq [runner runners]
                (let [configured (set (vals (:values runner)))]
                  (when-not (or (some runner-adapters configured)
                                (some #(str/includes? (pr-str %)
                                                      "org.junit.runners.Parameterized")
                                      configured))
                    (fail! "JUnit runner has no resolved destination adapter"
                           {:reason :unmapped-junit-runner
                            :symbol (:symbol runner)
                            :values (:values runner)
                            :source (:source runner)}))))
              {:name (class-name type)
               :annotations type-annotations
               :methods methods
               :fields fields
               :constructors (direct-constructors index type)
               :lifecycle (lifecycle-plan index type)
               :type-element type}))
          all-types)
         records-by-name (into {} (map (juxt :name identity)) records)
         _ (fail-on-unsupported-java-annotations! records)
         cases
         (->> records
              (filter #(and (concrete-class? (:type-element %))
                            (discoverable-type? records-by-name %)))
              (mapcat
               (fn [record]
                 (map (fn [[method primary]]
                        (case-plan
                         record
                         (effective-class-annotations
                          records-by-name (:type-element record))
                         method primary))
                      (effective-test-methods records-by-name record))))
              (sort-by :id)
              vec)
         annotation-inventory
         (->> records
              (mapcat (fn [record]
                        (concat (:annotations record)
                                (mapcat :annotations (:methods record))
                                (mapcat :annotations (:fields record))
                                (mapcat (comp #(mapcat :annotations %) :parameters)
                                        (:methods record)))))
              (map :symbol)
              frequencies
              (into (sorted-map)))]
     (test-adapters/augment-plan
      resolved-model
      {:schema-version schema-version
       :source-model-totals (:totals resolved-model)
       :root-classes (mapv class-name types)
       :annotation-inventory annotation-inventory
       :classes records-by-name
       :cases cases}))))

(defn serializable-plan
  "Removes live Spoon handles while retaining complete case and row accounting."
  [plan]
  (letfn [(strip-live [value]
            (cond
              (instance? CtElement value) nil
              (map? value) (into (empty value)
                                 (keep (fn [[key child]]
                                         (when-not (contains? #{:element :field-element
                                                                :method-element :type-element}
                                                              key)
                                           [key (strip-live child)])))
                                 value)
              (vector? value) (mapv strip-live value)
              (sequential? value) (mapv strip-live value)
              :else value))]
    (strip-live plan)))

(defn- row-identities
  [case]
  (let [parameters (:parameters case)]
    (cond
      (= :inline-rows (:kind parameters))
      (mapv #(str (:id case) "/" (:id %)) (:rows parameters))

      (= :composite-sources (:kind parameters))
      (vec
       (mapcat
        (fn [source-index source]
          (if (= :inline-rows (:kind source))
            (map #(str (:id case) "/source:" source-index "/" (:id %))
                 (:rows source))
            [(str (:id case) "/source:" source-index "/runtime")]))
        (range) (:sources parameters)))

      parameters [(str (:id case) "/runtime")]
      :else [(:id case)])))

(defn verify-plan!
  "Proves that a candidate lowering retained every discovered case, parameter
  row, lifecycle edge, timeout, temporary resource, and disabled reason."
  [expected candidate]
  (doseq [[label projection]
          [[:cases #(mapv :id (:cases %))]
           [:discovery
            #(into (sorted-map)
                   (map (fn [test-case]
                          [(:id test-case)
                           (select-keys test-case
                                        [:framework :kind :primary-symbol
                                         :display-name :parallel])]))
                   (:cases %))]
           [:parameters
            #(into (sorted-map)
                   (keep (fn [test-case]
                           (when-let [parameters (:parameters test-case)]
                             [(:id test-case) parameters])))
                   (:cases %))]
           [:rows #(vec (mapcat row-identities (:cases %)))]
           [:lifecycle #(into (sorted-map)
                              (map (fn [[name class]] [name (:lifecycle class)]))
                              (:classes %))]
           [:mockito-fixtures
            #(into (sorted-map)
                   (keep (fn [[name class]]
                           (when-let [fixture (:mockito-fixture class)]
                             [name fixture])))
                   (:classes %))]
           [:disabled #(into (sorted-map)
                             (keep (fn [case]
                                     (when-let [disabled (:disabled case)]
                                       [(:id case) disabled])))
                             (:cases %))]
           [:timeouts #(into (sorted-map)
                             (keep (fn [case]
                                     (when-let [timeout (:timeout case)]
                                       [(:id case) timeout])))
                             (:cases %))]
           [:expected-exceptions
            #(into (sorted-map)
                   (keep (fn [test-case]
                           (when-let [expected-exception
                                      (:expected-exception test-case)]
                             [(:id test-case) expected-exception])))
                   (:cases %))]
           [:temporary-resources
            #(into (sorted-map)
                   (keep (fn [case]
                           (when (seq (:temporary-resources case))
                             [(:id case) (:temporary-resources case)])))
                   (:cases %))]]]
    (let [wanted (projection expected)
          actual (projection candidate)]
      (when-not (= wanted actual)
        (fail! "JUnit-to-xUnit plan lost or changed discovered semantics"
               {:reason :junit-plan-perturbation
                :section label :expected wanted :actual actual}))))
  candidate)

(defn translate-test-body!
  "Translates one planned test body through the ordinary Java library body
  translator and its accepted coverage gate."
  [resolved-model destination-context case]
  (let [method (:method-element case)]
    (when-not (instance? CtMethod method)
      (fail! "JUnit case has no live Java method body"
             {:reason :missing-junit-test-body :case (:id case)
              :source (:source case)}))
    (when-not (.getBody ^CtMethod method)
      (fail! "JUnit test method has no Java body"
             {:reason :missing-junit-test-body :case (:id case)
              :source (:source case)}))
    (test-adapters/validate-test-body!
     resolved-model (.getBody ^CtMethod method) destination-context)
    (let [destination-context
          (test-adapters/compose-destination-context destination-context)
          holder (atom destination-context)
          context (java-library/create-body-context resolved-model holder)]
      (java-library/translate-body context (.getBody ^CtMethod method)))))

(defn- csharp-string
  [value]
  (let [escaped (-> (str value)
                    (str/replace "\\" "\\\\")
                    (str/replace "\"" "\\\"")
                    (str/replace "\r" "\\r")
                    (str/replace "\n" "\\n"))]
    (str "\"" escaped "\"")))

(defn- csharp-literal
  [value]
  (cond
    (nil? value) "null"
    (string? value) (csharp-string value)
    (char? value) (str "'" value "'")
    (boolean? value) (str value)
    (number? value) (str value)
    (and (map? value) (:field value))
    (last (str/split (:field value) #"#|[.]"))
    :else
    (fail! "JUnit inline parameter has no C# literal representation"
           {:reason :unsupported-xunit-inline-literal :value value})))

(defn- generated-provider-name
  [case index]
  (str "__JunitData_"
       (str/replace (:id case) #"[^A-Za-z0-9_]" "_")
       "_" index))

(defn- parameter-source-attributes
  [test-case source index]
  (case (:kind source)
    :inline-rows
    (mapv (fn [row]
            (str "[Xunit.InlineData("
                 (str/join ", " (map csharp-literal (:arguments row)))
                 ")]"))
          (:rows source))

    :member-data
    (let [providers (or (seq (:providers source))
                        [(generated-provider-name test-case index)])]
      (mapv #(str "[Xunit.MemberData(" (csharp-string %) ")]")
            providers))

    :dynamic-cases []

    (fail! "JUnit parameter source has no xUnit discovery attribute adapter"
           {:reason :unsupported-xunit-parameter-source
            :case (:id test-case) :source source})))

(defn xunit-attributes
  "Renders the xUnit discovery attributes represented by one case plan. Runtime
  member data, timeout, lifecycle, and temporary-resource wrappers remain
  explicit lowerings in the returned plan rather than being hidden in text."
  [case]
  (let [theory? (= :theory (:kind case))
        options (cond-> []
                  (:display-name case)
                  (conj (str "DisplayName = "
                             (csharp-string (:display-name case))))
                  (:disabled case)
                  (conj (str "Skip = "
                             (csharp-string (get-in case [:disabled :reason])))))
        primary (str "[Xunit." (if theory? "Theory" "Fact")
                     (when (seq options)
                       (str "(" (str/join ", " options) ")")) "]")
        parameters (:parameters case)
        rows
        (cond
          (= :composite-sources (:kind parameters))
          (vec
           (mapcat (fn [[index source]]
                     (parameter-source-attributes case source index))
                   (map-indexed vector (:sources parameters))))

          parameters
          (parameter-source-attributes case parameters 0)

          :else [])]
    (into [primary] rows)))

(defn lower-case
  "Converts one discovered case into the complete target-neutral xUnit lowering
  contract consumed by suite emitters. Wrapper order is outer-to-inner and is
  significant: resources outlive timeout/exception guards, JUnit 4 timeout is
  outside expected-exception handling, and teardown is always an xUnit dispose
  path rather than appended to the test body."
  [case]
  (let [parameters (:parameters case)
        wrappers
        (vec
         (concat
          (when (seq (:temporary-resources case))
            [{:kind :temporary-resources
              :resources (:temporary-resources case)
              :cleanup :finally-dispose}])
          (when-let [timeout (:timeout case)]
            [{:kind :timeout :semantics (:kind timeout)
              :configuration (dissoc timeout :kind :source)}])
          (when-let [expected (:expected-exception case)]
            [{:kind :expected-exception
              :semantics (:kind expected)
              :exception (:exception expected)}])
          (when (= :dynamic-factory (:kind case))
            [{:kind :dynamic-case-enumeration
              :accounting :one-result-per-runtime-dynamic-case}])
          [{:kind :ordinary-java-body
            :translator 'dripsharp.junit-xunit/translate-test-body!}]))]
    {:schema-version schema-version
     :case-id (:id case)
     :source (:source case)
     :xunit
     {:attributes (xunit-attributes case)
      :discovery-kind (:kind case)
      :parameter-data parameters
      :disabled (:disabled case)}
     :instance-lifecycle
     {:policy :new-instance-per-test-case-row
      :enclosing-instance-chain
      (get-in case [:lifecycle :enclosing-class-chain])
      :field-initializers (get-in case [:mockito-fixture :fields])
      :constructor-calls (get-in case [:lifecycle :before-each])
      :dispose-finally-calls (get-in case [:lifecycle :after-each])}
     :class-fixture
     {:constructor-calls (get-in case [:lifecycle :before-all])
      :dispose-finally-calls (get-in case [:lifecycle :after-all])}
     :body {:wrappers wrappers}
     :parallelization (:parallel case)
     :row-accounting
     (cond
       (= :inline-rows (:kind parameters))
       {:kind :static :rows (mapv :id (:rows parameters))}

       (= :composite-sources (:kind parameters))
       {:kind :composite
        :sources
        (mapv (fn [source]
                (if (= :inline-rows (:kind source))
                  {:kind :static :rows (mapv :id (:rows source))}
                  {:kind :runtime :policy (:row-accounting source)}))
              (:sources parameters))}

       parameters
       {:kind :runtime :policy (:row-accounting parameters)}

       :else {:kind :single-case})}))

(defn lower-suite
  "Lowers and reconciles every case in a resolved suite plan."
  [plan]
  (let [lowered (mapv lower-case (:cases plan))
        case-ids (mapv :case-id lowered)]
    (when-not (= (mapv :id (:cases plan)) case-ids)
      (fail! "xUnit lowering changed the discovered case set"
             {:reason :junit-lowering-case-drift
              :expected (mapv :id (:cases plan))
              :actual case-ids}))
    {:schema-version schema-version
     :source-annotation-inventory (:annotation-inventory plan)
     :cases lowered}))

(defn- java-files
  [root]
  (let [root (paths/path root)]
    (if (Files/exists root (make-array java.nio.file.LinkOption 0))
      (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
        (->> (.toArray stream)
             (map #(cast Path %))
             (filter #(and (Files/isRegularFile
                            % (make-array java.nio.file.LinkOption 0))
                           (str/ends-with? (str %) ".java")))
             (sort-by str)
             vec))
      [])))

(defn- kotlin-files
  [root]
  (let [root (paths/path root)]
    (if (Files/exists root (make-array java.nio.file.LinkOption 0))
      (with-open [stream (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
        (->> (.toArray stream)
             (map #(cast Path %))
             (filter #(and (Files/isRegularFile
                            % (make-array java.nio.file.LinkOption 0))
                           (str/ends-with? (str %) ".kt")))
             (sort-by str)
             vec))
      [])))

(defn- import-index
  [text]
  (let [imports (map second (re-seq #"(?m)^\s*import\s+([^;]+);\s*$" text))
        explicit
        (into {}
              (keep (fn [qualified]
                      (when-not (str/ends-with? qualified ".*")
                        [(last (str/split qualified #"[.]")) qualified])))
              imports)
        wildcards (->> imports
                       (filter #(str/ends-with? % ".*"))
                       (map #(subs % 0 (- (count %) 2)))
                       set)]
    {:explicit explicit :wildcards wildcards}))

(defn- kotlin-import-index
  [text]
  (let [imports (map second (re-seq #"(?m)^\s*import\s+([^\s]+)\s*$" text))
        explicit
        (into {}
              (keep (fn [qualified]
                      (when-not (str/ends-with? qualified ".*")
                        [(last (str/split qualified #"[.]")) qualified])))
              imports)
        wildcards (->> imports
                       (filter #(str/ends-with? % ".*"))
                       (map #(subs % 0 (- (count %) 2)))
                       set)]
    {:explicit explicit :wildcards wildcards}))

(defn- source-annotation-symbol
  [{:keys [explicit wildcards]} token]
  (let [parts (str/split token #"[.]")
        first-part (first parts)
        explicit-base (get explicit first-part)
        qualified
        (cond
          explicit-base
          (str explicit-base
               (when (< 1 (count parts))
                 (str "$" (str/join "$" (rest parts)))))

          (str/starts-with? token "org.junit.") token

          :else
          (let [candidates
                (filter #(contains? annotation-contracts
                                    (str "annotation:" % "." token))
                        wildcards)]
            (when (= 1 (count candidates))
              (str (first candidates) "." token))))]
    (when qualified
      (let [direct (str "annotation:" qualified)
            binary (str "annotation:"
                        (str/replace
                         qualified
                         #"(org[.]junit[.]runners[.]Parameterized)[.]"
                         (fn [[_ prefix]] (str prefix "$"))))]
        (cond
          (contains? annotation-contracts direct) direct
          (contains? annotation-contracts binary) binary
          :else nil)))))

(defn scan-java-sources
  "Creates a deterministic lexical cross-check for pinned Java source trees.
  The executable adapter itself never dispatches lexically; it uses the live
  resolved model. This scanner exists to pin the upstream inventory before a
  full target model is available."
  [roots]
  (let [files (vec (mapcat java-files roots))
        observations
        (mapcat
         (fn [^Path file]
           (let [text (Files/readString file)
                 imports (import-index text)]
             (keep
              (fn [[_ token]]
                (when-let [symbol (source-annotation-symbol imports token)]
                  {:symbol symbol :file (str file)}))
              (re-seq #"(?m)^\s*@([A-Za-z_$][A-Za-z0-9_$.]*)\b" text))))
         files)]
    {:java-files (count files)
     :annotations (into (sorted-map)
                        (frequencies (map :symbol observations)))}))

(defn scan-kotlin-sources
  "Inventories Kotlin JUnit evidence without authorizing Kotlin translation."
  [roots]
  (let [files (vec (mapcat kotlin-files roots))
        observations
        (mapcat
         (fn [^Path file]
           (let [text (Files/readString file)
                 imports (kotlin-import-index text)]
             (keep
              (fn [[_ token]]
                (when-let [symbol (source-annotation-symbol imports token)]
                  {:symbol symbol :file (str file)}))
              (re-seq
               #"(?m)^\s*@(?!file:)(?:field:|get:|set:|param:|property:)?([A-Za-z_$][A-Za-z0-9_$.]*)\b"
               text))))
         files)]
    {:kotlin-files (count files)
     :annotations (into (sorted-map)
                        (frequencies (map :symbol observations)))}))

(defn- framework-api
  [imported]
  (cond
    (str/starts-with? imported "org.junit.jupiter.") :junit-jupiter
    (str/starts-with? imported "org.junit.platform.") :junit-platform
    (str/starts-with? imported "org.junit.") :junit4
    (str/starts-with? imported "org.assertj.") :assertj
    (str/starts-with? imported "org.hamcrest.") :hamcrest
    (str/starts-with? imported "org.mockito.") :mockito
    (str/starts-with? imported "io.kotest.") :kotest
    (or (str/starts-with? imported "com.github.tomakehurst.wiremock.")
        (str/starts-with? imported "org.wiremock.")) :wiremock
    (or (str/starts-with? imported "com.google.common.jimfs.")
        (str/starts-with? imported "com.google.jimfs.")) :jimfs
    (str/starts-with? imported "org.h2.") :h2
    :else nil))

(defn scan-framework-sources
  "Pins framework and target-utility imports separately by source language.
  This lexical inventory is evidence only; executable adaptation continues to
  dispatch exclusively through resolved Spoon symbols."
  [language roots]
  (let [files (vec (mapcat (case language
                             :java java-files
                             :kotlin kotlin-files
                             (fail! "Framework inventory has an unsupported source language"
                                    {:reason :unsupported-framework-source-language
                                     :language language}))
                           roots))
        import-pattern
        (case language
          :java #"(?m)^\s*import\s+(?:static\s+)?([^;\s]+)\s*;"
          :kotlin #"(?m)^\s*import\s+([^\s]+)")
        imports
        (mapcat
         (fn [^Path file]
           (->> (re-seq import-pattern (Files/readString file))
                (map second)
                (keep framework-api)))
         files)]
    {:files (count files)
     :imports (into (sorted-map) (frequencies imports))}))

(defn- exact-keys?
  [value expected]
  (and (map? value) (= expected (set (keys value)))))

(defn- validate-framework-api-evidence!
  [inventory workspace-root]
  (when-not (= api-classification-contract (:api-classification inventory))
    (fail! "Pinned Java test API classification drifted"
           {:reason :pinned-framework-api-classification-drift
            :expected api-classification-contract
            :actual (:api-classification inventory)}))
  (let [target-evidence (:framework-api-evidence inventory)]
    (when-not (and (vector? target-evidence)
                   (= #{:pkl :pdfcarton :rawhttp :sqltrellis}
                      (set (map :target target-evidence)))
                   (= (count target-evidence)
                      (count (distinct (map :target target-evidence)))))
      (fail! "Pinned Java test API evidence has an invalid target inventory"
             {:reason :invalid-pinned-framework-api-evidence
              :evidence target-evidence}))
    (doseq [{:keys [target revision checkout-root checkout-required?
                    source-groups dependency-declarations] :as evidence}
            target-evidence]
      (when-not
       (and (exact-keys?
             evidence
             #{:target :revision :checkout-root :checkout-required?
               :source-groups :dependency-declarations})
            (keyword? target)
            (string? revision)
            (re-matches #"[0-9a-f]{40}" revision)
            (string? checkout-root)
            (boolean? checkout-required?)
            (vector? source-groups)
            (seq source-groups)
            (vector? dependency-declarations)
            (seq dependency-declarations))
        (fail! "Pinned Java test API target evidence is invalid"
               {:reason :invalid-pinned-framework-api-target
                :evidence evidence}))
      (doseq [{:keys [language role roots files imports] :as group}
              source-groups]
        (when-not
         (and (exact-keys? group #{:language :role :roots :files :imports})
              (contains? #{:java :kotlin} language)
              (contains? #{:test-source :target-test-utility} role)
              (vector? roots)
              (seq roots)
              (pos-int? files)
              (map? imports)
              (seq imports)
              (every? governed-framework-apis (keys imports))
              (every? pos-int? (vals imports)))
          (fail! "Pinned Java test API source group is invalid"
                 {:reason :invalid-pinned-framework-source-group
                  :target target :group group})))
      (doseq [{:keys [path sha256 apis] :as declaration}
              dependency-declarations]
        (when-not
         (and (exact-keys? declaration #{:path :sha256 :apis})
              (string? path)
              (re-matches #"[0-9a-f]{64}" sha256)
              (set? apis)
              (seq apis)
              (every? governed-framework-apis apis))
          (fail! "Pinned Java test dependency declaration is invalid"
                 {:reason :invalid-pinned-framework-dependency
                  :target target :declaration declaration})))
      (let [checkout (paths/resolve-path workspace-root checkout-root)
            present? (Files/isDirectory
                      checkout (make-array java.nio.file.LinkOption 0))]
        (when (and checkout-required? (not present?))
          (fail! "Required Java test API evidence checkout is missing"
                 {:reason :missing-pinned-framework-api-checkout
                  :target target :checkout-root checkout-root}))
        (when present?
          (let [actual-revision
                (str/trim
                 (:output
                  (process/run!
                   {:command ["git" "-C" (str checkout) "rev-parse" "HEAD"]
                    :directory workspace-root})))]
            (when-not (= revision actual-revision)
              (fail! "Pinned Java test API evidence revision drifted"
                     {:reason :pinned-framework-api-revision-drift
                      :target target :expected revision
                      :actual actual-revision})))
          (doseq [{:keys [language role roots files imports]} source-groups]
            (let [resolved-roots
                  (mapv #(paths/resolve-path workspace-root %) roots)
                  actual (scan-framework-sources language resolved-roots)
                  expected {:files files :imports imports}]
              (when-not (= expected actual)
                (fail! "Pinned Java test API source evidence drifted"
                       {:reason :pinned-framework-source-evidence-drift
                        :target target :language language :role role
                        :expected expected :actual actual}))))
          (doseq [{:keys [path sha256]} dependency-declarations]
            (let [file (paths/resolve-path workspace-root path)]
              (when-not (.startsWith file checkout)
                (fail! "Java test dependency declaration escapes its checkout"
                       {:reason :pinned-framework-dependency-path-escape
                        :target target :path path
                        :checkout-root checkout-root}))
              (let [actual (when (paths/regular-file? file)
                             (util/sha256-file file))]
                (when-not (= sha256 actual)
                  (fail! "Pinned Java test dependency declaration drifted"
                         {:reason :pinned-framework-dependency-drift
                          :target target :path path
                          :expected sha256 :actual actual}))))))))
    (let [observed-apis
          (set
           (concat
            (mapcat (comp keys :imports)
                    (mapcat :source-groups target-evidence))
            (mapcat :apis
                    (mapcat :dependency-declarations target-evidence))))]
      (when-not (= governed-framework-apis observed-apis)
        (fail! "Pinned Java test API evidence is incomplete"
               {:reason :incomplete-pinned-framework-api-evidence
                :expected governed-framework-apis
                :actual observed-apis})))))

(defn read-pinned-inventory
  ([] (read-pinned-inventory "validation/java-test-frameworks/junit-inventory.edn"))
  ([path]
   (edn/read-string (slurp path))))

(defn validate-pinned-inventory!
  "Validates the committed target/revision inventory and, for entries whose
  checkout is present, re-scans the pinned Java sources."
  ([inventory] (validate-pinned-inventory! inventory "."))
  ([inventory workspace-root]
   (when-not (and (= inventory-schema-version (:schema-version inventory))
                  (= #{:schema-version :targets :kotlin-evidence
                       :api-classification :framework-api-evidence}
                     (set (keys inventory)))
                  (vector? (:targets inventory))
                  (seq (:targets inventory)))
     (fail! "Pinned JUnit inventory has an invalid schema"
            {:reason :invalid-pinned-junit-inventory
             :inventory inventory}))
   (doseq [{:keys [target revision checkout-root java-source-roots java-files
                   annotations checkout-required? framework-dependencies
                   semantic-forms] :as entry}
           (:targets inventory)]
     (when-not (and (keyword? target)
                    (string? revision)
                    (re-matches #"[0-9a-f]{40}" revision)
                    (string? checkout-root)
                    (vector? java-source-roots)
                    (pos-int? java-files)
                    (vector? framework-dependencies)
                    (seq framework-dependencies)
                    (every? #(and (keyword? (:api %))
                                  (string? (:version %)))
                            framework-dependencies)
                    (map? semantic-forms)
                    (seq semantic-forms)
                    (map? annotations)
                    (every? #(contains? annotation-contracts %) (keys annotations)))
       (fail! "Pinned JUnit target inventory entry is invalid"
              {:reason :invalid-pinned-junit-target :entry entry}))
     (let [resolved-roots (mapv #(-> (paths/resolve-path workspace-root %) .toFile)
                                java-source-roots)
           present? (every? #(.isDirectory ^java.io.File %) resolved-roots)
           checkout (paths/resolve-path workspace-root checkout-root)]
       (when (and checkout-required? (not present?))
         (fail! "Required pinned JUnit source checkout is missing"
                {:reason :missing-pinned-junit-checkout
                 :target target :roots java-source-roots}))
       (when present?
         (let [actual-revision
               (str/trim
                (:output
                 (process/run!
                  {:command ["git" "-C" (str checkout) "rev-parse" "HEAD"]
                   :directory workspace-root})))
               _ (when-not (= revision actual-revision)
                   (fail! "Pinned JUnit source checkout revision drifted"
                          {:reason :pinned-junit-revision-drift
                           :target target :expected revision
                           :actual actual-revision}))
               actual (scan-java-sources resolved-roots)
               expected {:java-files java-files :annotations annotations}]
           (when-not (= expected actual)
             (fail! "Pinned JUnit source inventory drifted"
                    {:reason :pinned-junit-inventory-drift
                     :target target :revision revision
                     :expected expected :actual actual}))))))
   (doseq [{:keys [target revision kotlin-source-roots kotlin-files annotations
                   checkout-required? translation-policy] :as entry}
           (:kotlin-evidence inventory)]
     (when-not (and (keyword? target)
                    (string? revision)
                    (re-matches #"[0-9a-f]{40}" revision)
                    (vector? kotlin-source-roots)
                    (pos-int? kotlin-files)
                    (= :evidence-only-no-kotlin-frontend translation-policy)
                    (map? annotations)
                    (every? #(contains? annotation-contracts %) (keys annotations)))
       (fail! "Pinned Kotlin JUnit evidence entry is invalid"
              {:reason :invalid-pinned-kotlin-junit-evidence :entry entry}))
     (let [resolved-roots (mapv #(-> (paths/resolve-path workspace-root %) .toFile)
                                kotlin-source-roots)
           present? (every? #(.isDirectory ^java.io.File %) resolved-roots)]
       (when (and checkout-required? (not present?))
         (fail! "Required pinned Kotlin JUnit evidence checkout is missing"
                {:reason :missing-pinned-kotlin-junit-checkout
                 :target target :roots kotlin-source-roots}))
       (when present?
         (let [actual (scan-kotlin-sources resolved-roots)
               expected {:kotlin-files kotlin-files :annotations annotations}]
           (when-not (= expected actual)
             (fail! "Pinned Kotlin JUnit evidence inventory drifted"
                    {:reason :pinned-kotlin-junit-inventory-drift
                     :target target :revision revision
                     :expected expected :actual actual}))))))
   (validate-framework-api-evidence! inventory workspace-root)
   inventory))
