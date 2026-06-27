(ns vibeformer.ingest.kotlin-psi
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [vibeformer.ingest.kotlin-analysis-api :as kotlin-analysis-api])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)
           (org.jetbrains.kotlin.com.intellij.openapi.util Disposer)
           (org.jetbrains.kotlin.cli.jvm.compiler EnvironmentConfigFiles KotlinCoreEnvironment)
           (org.jetbrains.kotlin.config CompilerConfiguration)
           (org.jetbrains.kotlin.psi KtBinaryExpression KtCallExpression KtClass KtClassOrObject
                                     KtConstantExpression KtExpression KtFile KtLambdaExpression
                                     KtNamedFunction KtNameReferenceExpression KtNullableType
                                     KtObjectDeclaration KtParameter KtProperty KtPsiFactory
                                     KtQualifiedExpression KtReturnExpression KtSafeQualifiedExpression
                                     KtStringTemplateExpression KtThrowExpression KtTypeReference)))

(def ^:private lang :lang/kotlin)

(def ^:private max-node-value-length 1024)

(defn- hex-bytes [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [value]
  (let [bytes (.getBytes (str value) StandardCharsets/UTF_8)]
    (str "sha256:" (hex-bytes (.digest (doto (MessageDigest/getInstance "SHA-256")
                                         (.update bytes)))))))

(defn- bounded-node-value [value]
  (when (some? value)
    (let [value (str value)]
      (if (<= (count value) max-node-value-length)
        value
        (let [suffix (str "\n... [truncated " (sha256 value) "]")
              prefix-length (max 0 (- max-node-value-length (count suffix)))]
          (str (subs value 0 prefix-length) suffix))))))

(defn- path [value]
  (if (instance? java.nio.file.Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- file-records [db project-id]
  (mapv (fn [[file]]
          (d/pull db [:file/id :file/path :file/hash :file/package
                      {:file/project [:project/id :project/root]}]
                  file))
        (d/q '[:find ?file
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?file :file/lang :lang/kotlin]]
             db project-id)))

(defn- parse-source [filename source]
  (let [disposable (Disposer/newDisposable)
        environment (KotlinCoreEnvironment/createForProduction
                     disposable
                     (CompilerConfiguration.)
                     EnvironmentConfigFiles/JVM_CONFIG_FILES)
        psi-factory (KtPsiFactory. (.getProject environment) false)]
    {:disposable disposable
     :file (.createFile psi-factory filename source)}))

(defn- walk-elements [root]
  (letfn [(walk [element]
            (lazy-seq
             (cons element (mapcat walk (.getChildren element)))))]
    (walk root)))

(defn- collect-elements [root clazz]
  (filter #(instance? clazz %) (walk-elements root)))

(defn- offset->line-column [source offset]
  (let [offset (max 0 (min offset (count source)))
        lines-before-offset (str/split (subs source 0 offset) #"\n" -1)]
    {:line (count lines-before-offset)
     :column (inc (count (last lines-before-offset)))}))

(defn- source-span [source element]
  (let [range (.getTextRange element)
        start (offset->line-column source (.getStartOffset range))
        end (offset->line-column source (.getEndOffset range))]
    {:node/start-line (:line start)
     :node/start-column (:column start)
     :node/end-line (:line end)
     :node/end-column (:column end)}))

(defn- node-fact [source id kind name file-id ordinal element & {:keys [parent role value]}]
  (cond-> {:db/id id
           :node/id id
           :node/lang lang
           :node/kind kind
           :node/name name
           :node/file [:file/id file-id]
           :node/ordinal ordinal
           :node/source-hash (sha256 (.getText element))}
    parent (assoc :node/parent parent)
    role (assoc :node/role role)
    (some? value) (assoc :node/value (bounded-node-value value))
    true (merge (source-span source element))))

(defn- feature-fact [id kind node-id status severity]
  {:db/id id
   :feature/id id
   :feature/lang lang
   :feature/kind kind
   :feature/node node-id
   :feature/status status
   :feature/severity severity})

(defn- supported-feature [id kind node-id]
  (feature-fact id kind node-id :feature.status/supported :feature.severity/info))

(defn- unsupported-feature [id kind node-id severity]
  (feature-fact id kind node-id :feature.status/unsupported severity))

(defn- package-name [^KtFile file]
  (let [package (.asString (.getPackageFqName file))]
    (when-not (str/blank? package)
      package)))

(defn- declaration-owner [package owner-qname]
  (or owner-qname package))

(defn- qualify-name [package owner-qname name]
  (let [owner (declaration-owner package owner-qname)]
    (if (str/blank? owner)
      name
      (str owner "." name))))

(defn- declaration-name [declaration]
  (or (.getName declaration) "<anonymous>"))

(defn- type-syntax [^KtTypeReference type-reference]
  (some-> type-reference .getText str/trim))

(defn- nullable-type? [^KtTypeReference type-reference]
  (boolean
   (when type-reference
     (or (instance? KtNullableType (.getTypeElement type-reference))
         (str/ends-with? (type-syntax type-reference) "?")))))

(defn- type-name [syntax]
  (some-> syntax
          (str/replace #"\s+" "")
          (str/replace #"\?$" "")
          (str/replace #"<.*$" "")))

(defn- strip-null-suffix [syntax]
  (let [syntax (some-> syntax (str/replace #"\s+" ""))]
    (if (str/ends-with? (or syntax "") "?")
      [(subs syntax 0 (dec (count syntax))) true]
      [syntax false])))

(defn- split-top-level [s separator]
  (loop [chars (seq s)
         depth 0
         token []
         out []]
    (if-let [ch (first chars)]
      (cond
        (= ch \<)
        (recur (next chars) (inc depth) (conj token ch) out)

        (= ch \>)
        (recur (next chars) (dec depth) (conj token ch) out)

        (and (= ch separator) (zero? depth))
        (recur (next chars) depth [] (conj out (apply str token)))

        :else
        (recur (next chars) depth (conj token ch) out))
      (cond-> out
        (seq token) (conj (apply str token))))))

(defn- parse-type-syntax [syntax]
  (when-let [syntax (some-> syntax str/trim not-empty)]
    (let [[syntax nullable?] (strip-null-suffix syntax)
          [_ base args] (re-matches #"([^<]+)<(.+)>" syntax)]
      {:type/name (if base base syntax)
       :type/nullable? nullable?
       :type/args (when args
                    (mapv parse-type-syntax (split-top-level args \,)))})))

(defn- simple-type-name [type-name]
  (some-> type-name
          (str/replace #"<.*$" "")
          (str/split #"\.")
          last))

(declare type-id)

(defn- type-id [parsed]
  (when-let [name (:type/name parsed)]
    (str "kotlin:"
         name
         (when-let [args (seq (:type/args parsed))]
           (str "<" (str/join "," (map type-id args)) ">"))
         (when (:type/nullable? parsed) "?"))))

(defn- type-fact* [parsed]
  (when-let [id (type-id parsed)]
    (cond-> {:db/id id
             :type/id id
             :type/lang lang
             :type/name (:type/name parsed)
             :type/nullable? (:type/nullable? parsed)}
      (seq (:type/args parsed))
      (assoc :type/args
             (->> (:type/args parsed)
                  (keep-indexed (fn [ordinal arg]
                                  (when-let [arg-id (type-id arg)]
                                    {:type.arg/ordinal ordinal
                                     :type.arg/type arg-id})))
                  vec)))))

(defn- type-facts* [parsed]
  (when parsed
    (cons (type-fact* parsed)
          (mapcat type-facts* (:type/args parsed)))))

(defn- type-fact [^KtTypeReference type-reference]
  (when-let [syntax (type-syntax type-reference)]
    (type-fact* (assoc (parse-type-syntax syntax)
                       :type/nullable? (nullable-type? type-reference)))))

(defn- type-facts [^KtTypeReference type-reference]
  (when-let [syntax (type-syntax type-reference)]
    (type-facts* (assoc (parse-type-syntax syntax)
                        :type/nullable? (nullable-type? type-reference)))))

(defn- type-ref-facts
  ([node-id role ^KtTypeReference type-reference]
   (type-ref-facts node-id role type-reference nil))
  ([node-id role ^KtTypeReference type-reference source-name]
   (when-let [type-fact (type-fact type-reference)]
     (concat
      (type-facts type-reference)
      [(cond-> {:db/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                :ref/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                :ref/kind :ref.kind/type-use
                :ref/from-node node-id
                :ref/to-type (:type/id type-fact)
                :ref/name (:type/name type-fact)
                :ref/role role
                :ref/resolved? false
                :ref/reason :resolve.reason/syntax-only}
         source-name (assoc :ref/source-name source-name))]))))

(defn- nullable-type-feature-facts [node-id type-reference role]
  (when (nullable-type? type-reference)
    [(supported-feature (str node-id ":feature:nullable-type:" (name role))
                        :kotlin.feature/nullable-type
                        node-id)]))

(defn- source-type-fact [type-id name]
  {:db/id type-id
   :type/id type-id
   :type/lang lang
   :type/name name
   :type/nullable? false})

(defn- modifier-keywords [declaration]
  (when-let [modifier-list (.getModifierList declaration)]
    (->> (str/split (.getText modifier-list) #"\s+")
         (remove str/blank?)
         (map #(-> % (str/replace "_" "-") keyword))
         set)))

(defn- object-kind [^KtObjectDeclaration object]
  (if (.isCompanion object)
    :kotlin.node/companion-object
    :kotlin.node/object))

(defn- object-feature-kind [^KtObjectDeclaration object]
  (if (.isCompanion object)
    :kotlin.feature/companion-object
    :kotlin.feature/object))

(defn- declaration-node-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (object-kind declaration)
    (instance? KtClass declaration) :kotlin.node/class
    (instance? KtNamedFunction declaration) :kotlin.node/function
    (instance? KtProperty declaration) :kotlin.node/property
    :else :kotlin.node/declaration))

(defn- declaration-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (if (.isCompanion ^KtObjectDeclaration declaration)
                                                  :decl.kind/companion-object
                                                  :decl.kind/object)
    (instance? KtClass declaration) (if (.isInterface ^KtClass declaration)
                                      :decl.kind/interface
                                      :decl.kind/class)
    (instance? KtNamedFunction declaration) :decl.kind/function
    (instance? KtProperty declaration) :decl.kind/property
    :else :decl.kind/declaration))

(defn- declaration-feature-kind [declaration]
  (cond
    (instance? KtObjectDeclaration declaration) (object-feature-kind declaration)
    (instance? KtClass declaration) :kotlin.feature/class
    (instance? KtNamedFunction declaration) :kotlin.feature/function
    (instance? KtProperty declaration) :kotlin.feature/property
    :else :kotlin.feature/declaration))

(defn- declaration-node-id [file-id kind qualified-name]
  (str file-id ":" (name kind) ":" qualified-name))

(defn- declaration-id [kind qualified-name]
  (str "kotlin:" (name kind) ":" qualified-name))

(defn- value-parameter-type-refs [node-id value-parameters]
  (mapcat (fn [ordinal parameter]
            (type-ref-facts node-id
                            (keyword (str "param-" ordinal))
                            (.getTypeReference parameter)
                            (.getName parameter)))
          (range)
          value-parameters))

(defn- value-parameter-nullable-features [node-id value-parameters]
  (mapcat (fn [ordinal parameter]
            (nullable-type-feature-facts node-id
                                         (.getTypeReference parameter)
                                         (keyword (str "param-" ordinal))))
          (range)
          value-parameters))

(defn- declaration-type-facts [node-id declaration]
  (cond
    (instance? KtProperty declaration)
    (let [type-reference (.getTypeReference ^KtProperty declaration)]
      (concat (type-ref-facts node-id :property-type type-reference)
              (nullable-type-feature-facts node-id type-reference :property-type)))

    (instance? KtNamedFunction declaration)
    (let [function ^KtNamedFunction declaration
          return-type (.getTypeReference function)
          receiver-type (.getReceiverTypeReference function)
          value-parameters (.getValueParameters function)]
      (concat (type-ref-facts node-id :return-type return-type)
              (type-facts receiver-type)
              (value-parameter-type-refs node-id value-parameters)
              (nullable-type-feature-facts node-id return-type :return-type)
              (nullable-type-feature-facts node-id receiver-type :receiver-type)
              (value-parameter-nullable-features node-id value-parameters)))))

(defn- supertype-ref-facts [node-id declaration]
  (when (instance? KtClassOrObject declaration)
    (mapcat
     (fn [ordinal entry]
       (when-let [type-reference (.getTypeReference entry)]
         (when-let [type-fact (type-fact type-reference)]
           (concat
            (type-facts type-reference)
            [{:db/id (str node-id ":supertype:" ordinal ":" (:type/id type-fact))
              :ref/id (str node-id ":supertype:" ordinal ":" (:type/id type-fact))
              :ref/kind :ref.kind/implements
              :ref/from-node node-id
              :ref/to-type (:type/id type-fact)
              :ref/name (:type/name type-fact)
              :ref/role :supertype
              :ref/resolved? false
              :ref/reason :resolve.reason/syntax-only}]))))
     (range)
     (.getSuperTypeListEntries ^KtClassOrObject declaration))))

(defn- declaration-node-value [declaration]
  (cond
    (instance? KtProperty declaration)
    (some-> ^KtProperty declaration .getInitializer .getText str/trim)

    (instance? KtNamedFunction declaration)
    (let [function ^KtNamedFunction declaration]
      (when-not (.hasBlockBody function)
        (some-> function .getBodyExpression .getText str/trim)))))

(defn- declaration-fact [decl-id decl-kind name qualified-name node-id declaration]
  (let [function-return-type (when (instance? KtNamedFunction declaration)
                               (some-> ^KtNamedFunction declaration .getTypeReference type-fact :type/id))
        function-receiver-type (when (instance? KtNamedFunction declaration)
                                 (some-> ^KtNamedFunction declaration .getReceiverTypeReference type-fact :type/id))
        property-type (when (instance? KtProperty declaration)
                        (some-> ^KtProperty declaration .getTypeReference type-fact :type/id))]
    (cond-> {:db/id decl-id
             :decl/id decl-id
             :decl/lang lang
             :decl/kind decl-kind
             :decl/name name
             :decl/qualified-name qualified-name
             :decl/source-node node-id}
      (seq (modifier-keywords declaration))
      (assoc :decl/modifiers (modifier-keywords declaration))

      function-return-type
      (assoc :decl/return-type function-return-type)

      function-receiver-type
      (assoc :decl/receiver-type function-receiver-type)

      property-type
      (assoc :decl/type property-type))))

(defn- top-level-feature [node-id]
  (supported-feature (str node-id ":feature:top-level-declaration")
                     :kotlin.feature/top-level-declaration
                     node-id))

(declare declaration-facts elvis-expression? kotlin-expression-facts)

(defn- call-name [^KtCallExpression call]
  (some-> call .getCalleeExpression .getText))

(defn- call-node-id [file-id parent-node-id ordinal name]
  (str file-id ":call:" parent-node-id ":" ordinal ":" name))

(defn- kotlin-expression-node-kind [expression]
  (cond
    (instance? KtSafeQualifiedExpression expression) :kotlin.node/safe-call
    (and (instance? KtBinaryExpression expression)
         (elvis-expression? expression)) :kotlin.node/elvis-expression
    (instance? KtQualifiedExpression expression) :kotlin.node/qualified-expression
    (instance? KtCallExpression expression) :kotlin.node/call-expression
    (instance? KtLambdaExpression expression) :kotlin.node/lambda-expression
    (instance? KtBinaryExpression expression) :kotlin.node/binary-expression
    (instance? KtStringTemplateExpression expression) :kotlin.node/string-literal
    (instance? KtConstantExpression expression) :kotlin.node/literal
    (instance? KtNameReferenceExpression expression) :kotlin.node/name-reference
    :else :kotlin.node/expression))

(defn- kotlin-expression-name [expression]
  (cond
    (instance? KtCallExpression expression) (or (call-name expression) "<call>")
    (instance? KtSafeQualifiedExpression expression) "?."
    (and (instance? KtBinaryExpression expression)
         (elvis-expression? expression)) "?:"
    (instance? KtQualifiedExpression expression) "."
    (instance? KtLambdaExpression expression) "lambda"
    (instance? KtBinaryExpression expression) (some-> ^KtBinaryExpression expression .getOperationReference .getText)
    (instance? KtStringTemplateExpression expression) "string"
    (instance? KtConstantExpression expression) "literal"
    (instance? KtNameReferenceExpression expression) (str/trim (.getText expression))
    :else "<expression>"))

(defn- kotlin-expression-value [expression]
  (cond
    (instance? KtBinaryExpression expression) (some-> ^KtBinaryExpression expression .getOperationReference .getText)
    :else (str/trim (.getText expression))))

(defn- kotlin-expression-node-id [parent-node-id role ordinal expression]
  (str "kotlin:expr:"
       (sha256 (str parent-node-id
                    "|" (name role)
                    "|" ordinal
                    "|" (name (kotlin-expression-node-kind expression))
                    "|" (.getText expression)))))

(defn- kotlin-call-ref-fact [node-id name]
  {:db/id (str node-id ":ref")
   :ref/id (str node-id ":ref")
   :ref/kind :ref.kind/function-call
   :ref/from-node node-id
   :ref/name name
   :ref/resolved? false
   :ref/reason :resolve.reason/syntax-only})

(defn- kotlin-expression-children [expression]
  (cond
    (and (instance? KtBinaryExpression expression)
         (elvis-expression? expression))
    [[:left 0 (.getLeft ^KtBinaryExpression expression)]
     [:right 1 (.getRight ^KtBinaryExpression expression)]]

    (instance? KtBinaryExpression expression)
    [[:left 0 (.getLeft ^KtBinaryExpression expression)]
     [:right 1 (.getRight ^KtBinaryExpression expression)]]

    (instance? KtQualifiedExpression expression)
    [[:receiver 0 (.getReceiverExpression ^KtQualifiedExpression expression)]
     [:selector 1 (.getSelectorExpression ^KtQualifiedExpression expression)]]

    (instance? KtCallExpression expression)
    (concat
     (map-indexed (fn [ordinal argument]
                    [:argument ordinal (.getArgumentExpression argument)])
                  (.getValueArguments ^KtCallExpression expression))
     (map-indexed (fn [ordinal lambda-argument]
                    [:lambda (+ 100 ordinal) (.getLambdaExpression lambda-argument)])
                  (.getLambdaArguments ^KtCallExpression expression)))

    (instance? KtLambdaExpression expression)
    (when-let [body (.getBodyExpression ^KtLambdaExpression expression)]
      (map-indexed (fn [ordinal child]
                     [:body-expression ordinal child])
                   (.getStatements body)))))

(defn- kotlin-expression-facts [source file-id parent-node-id role ordinal ^KtExpression expression]
  (when expression
    (let [kind (kotlin-expression-node-kind expression)
          name (or (kotlin-expression-name expression) "<expression>")
          node-id (kotlin-expression-node-id parent-node-id role ordinal expression)]
      (concat
       [(node-fact source node-id kind name file-id ordinal expression
                   :parent parent-node-id
                   :role role
                   :value (kotlin-expression-value expression))]
       (mapcat (fn [[child-role child-ordinal child-expression]]
                 (kotlin-expression-facts source file-id node-id child-role child-ordinal child-expression))
               (kotlin-expression-children expression))))))

(defn- call-facts [source file-id parent-node-id ordinal ^KtCallExpression call]
  (let [name (or (call-name call) "<call>")
        node-id (call-node-id file-id parent-node-id ordinal name)]
    (concat
     [(node-fact source node-id :kotlin.node/call-expression name file-id ordinal call
                 :parent parent-node-id)
      (kotlin-call-ref-fact node-id name)
      (supported-feature (str node-id ":feature:call-expression")
                         :kotlin.feature/call-expression
                         node-id)]
     (when-let [parent (.getParent call)]
       (when (and (instance? KtQualifiedExpression parent)
                  (= call (.getSelectorExpression ^KtQualifiedExpression parent)))
         (let [receiver (.getReceiverExpression ^KtQualifiedExpression parent)]
           [(node-fact source
                       (str node-id ":receiver")
                       :kotlin.node/call-receiver
                       "receiver"
                       file-id
                       0
                       receiver
                       :parent node-id
                       :role :receiver
                       :value (str/trim (.getText receiver)))])))
     (mapcat (fn [arg-ordinal argument]
               (when-let [expression (.getArgumentExpression argument)]
                 [(node-fact source
                             (str node-id ":argument:" arg-ordinal)
                             :kotlin.node/call-argument
                             (str arg-ordinal)
                             file-id
                             arg-ordinal
                             expression
                             :parent node-id
                             :role :argument
                             :value (str/trim (.getText expression)))]))
             (range)
             (.getValueArguments call)))))

(defn- parameter-facts [source file-id parent-node-id ordinal ^KtParameter parameter]
  (let [name (or (.getName parameter) (str "<parameter-" ordinal ">"))
        node-id (str file-id ":parameter:" parent-node-id ":" ordinal ":" name)]
    (concat
     [(node-fact source
                 node-id
                 :kotlin.node/value-parameter
                 name
                 file-id
                 ordinal
                 parameter
                 :parent parent-node-id
                 :role :parameter
                 :value (type-syntax (.getTypeReference parameter)))]
     (type-ref-facts node-id :parameter (.getTypeReference parameter) name))))

(defn- safe-call-node-id [file-id parent-node-id ordinal]
  (str file-id ":safe-call:" parent-node-id ":" ordinal))

(defn- safe-call-facts [source file-id parent-node-id ordinal ^KtSafeQualifiedExpression expression]
  (let [node-id (safe-call-node-id file-id parent-node-id ordinal)]
    [(node-fact source node-id :kotlin.node/safe-call "?." file-id ordinal expression
                :parent parent-node-id)
     (supported-feature (str node-id ":feature:safe-call")
                        :kotlin.feature/safe-call
                        node-id)]))

(defn- elvis-expression? [^KtBinaryExpression expression]
  (= "?:" (some-> expression .getOperationReference .getText)))

(defn- elvis-node-id [file-id parent-node-id ordinal]
  (str file-id ":elvis:" parent-node-id ":" ordinal))

(defn- elvis-facts [source file-id parent-node-id ordinal ^KtBinaryExpression expression]
  (let [node-id (elvis-node-id file-id parent-node-id ordinal)]
    [(node-fact source node-id :kotlin.node/elvis-expression "?:" file-id ordinal expression
                :parent parent-node-id)
     (supported-feature (str node-id ":feature:elvis-expression")
                        :kotlin.feature/elvis-expression
                        node-id)]))

(defn- local-property-facts [source file-id parent-node-id ordinal ^KtProperty property]
  (let [name (or (.getName property) "<local>")
        node-id (str file-id ":local-property:" parent-node-id ":" ordinal ":" name)
        initializer (.getInitializer property)]
    (concat
     [(node-fact source
                 node-id
                 :kotlin.node/local-property
                 name
                 file-id
                 ordinal
                 property
                 :parent parent-node-id
                 :role :local-binding
                 :value (some-> initializer .getText str/trim))]
     (type-ref-facts node-id :local-binding (.getTypeReference property) name)
     (kotlin-expression-facts source file-id node-id :initializer 0 initializer))))

(defn- return-facts [source file-id parent-node-id ordinal ^KtReturnExpression expression]
  (let [node-id (str file-id ":return:" parent-node-id ":" ordinal)
        returned-expression (.getReturnedExpression expression)]
    (concat
     [(node-fact source
                 node-id
                 :kotlin.node/return
                 "return"
                 file-id
                 ordinal
                 expression
                 :parent parent-node-id
                 :role :return
                 :value (some-> returned-expression .getText str/trim))]
     (kotlin-expression-facts source file-id node-id :return-expression 0 returned-expression))))

(defn- throw-facts [source file-id parent-node-id ordinal ^KtThrowExpression expression]
  (let [node-id (str file-id ":throw:" parent-node-id ":" ordinal)
        thrown-expression (.getThrownExpression expression)]
    (concat
     [(node-fact source
                 node-id
                 :kotlin.node/throw
                 "throw"
                 file-id
                 ordinal
                 expression
                 :parent parent-node-id
                 :role :throw
                 :value (some-> thrown-expression .getText str/trim))]
     (kotlin-expression-facts source file-id node-id :thrown-expression 0 thrown-expression))))

(defn- expression-body-facts [source file-id parent-node-id ^KtNamedFunction function]
  (when (and (not (.hasBlockBody function))
             (.getBodyExpression function))
    (kotlin-expression-facts source
                             file-id
                             parent-node-id
                             :expression-body
                             0
                             (.getBodyExpression function))))

(defn- declaration-expression-facts [source file-id parent-node-id declaration]
  (let [calls (collect-elements declaration KtCallExpression)
        safe-calls (collect-elements declaration KtSafeQualifiedExpression)
        elvises (filter elvis-expression? (collect-elements declaration KtBinaryExpression))
        local-properties (collect-elements declaration KtProperty)
        returns (collect-elements declaration KtReturnExpression)
        throws (collect-elements declaration KtThrowExpression)
        body-shape? (and (instance? KtNamedFunction declaration)
                         (or (seq calls)
                             (seq safe-calls)
                             (seq elvises)
                             (seq local-properties)
                             (seq returns)
                             (seq throws)))]
    (concat
     (mapcat (fn [ordinal call]
               (call-facts source file-id parent-node-id ordinal call))
             (range)
             calls)
     (mapcat (fn [ordinal safe-call]
               (safe-call-facts source file-id parent-node-id ordinal safe-call))
             (range)
             safe-calls)
     (mapcat (fn [ordinal elvis]
               (elvis-facts source file-id parent-node-id ordinal elvis))
             (range)
             elvises)
     (when body-shape?
       (concat
        (mapcat (fn [ordinal property]
                  (local-property-facts source file-id parent-node-id ordinal property))
                (range)
                local-properties)
        (mapcat (fn [ordinal expression]
                  (return-facts source file-id parent-node-id ordinal expression))
                (range)
                returns)
        (mapcat (fn [ordinal expression]
                  (throw-facts source file-id parent-node-id ordinal expression))
                (range)
                throws)))
     (when (instance? KtNamedFunction declaration)
       (expression-body-facts source file-id parent-node-id declaration)))))

(defn- child-declarations [declaration]
  (cond
    (or (instance? KtClass declaration)
        (instance? KtObjectDeclaration declaration))
    (.getDeclarations declaration)
    :else []))

(defn- function-signature [^KtNamedFunction function]
  (str (when-let [receiver-type (some-> function .getReceiverTypeReference type-syntax)]
         (str receiver-type "."))
       (.getName function) "("
       (str/join "," (map #(or (type-syntax (.getTypeReference %)) "_")
                          (.getValueParameters function)))
       ")"))

(defn- declaration-qualified-name [package owner-qname declaration]
  (let [name (declaration-name declaration)]
    (cond
      (instance? KtNamedFunction declaration)
      (qualify-name package owner-qname (function-signature declaration))

      (instance? KtObjectDeclaration declaration)
      (if (.isCompanion ^KtObjectDeclaration declaration)
        (qualify-name package owner-qname (str (or name "Companion")))
        (qualify-name package owner-qname name))

      :else
      (qualify-name package owner-qname name))))

(defn- declaration-facts [source file-id package owner-qname parent-node-id ordinal declaration]
  (let [node-kind (declaration-node-kind declaration)
        decl-kind (declaration-kind declaration)
        name (declaration-name declaration)
        qualified-name (declaration-qualified-name package owner-qname declaration)
        node-id (declaration-node-id file-id node-kind qualified-name)
        decl-id (declaration-id decl-kind qualified-name)
        nested-owner-qname (when (or (instance? KtClass declaration)
                                     (instance? KtObjectDeclaration declaration))
                             qualified-name)]
    (concat
     (when (or (instance? KtClass declaration)
               (instance? KtObjectDeclaration declaration))
       [(source-type-fact (str "kotlin:" qualified-name) qualified-name)])
     [(node-fact source node-id node-kind name file-id ordinal declaration
                 :parent parent-node-id
                 :value (declaration-node-value declaration))
      (declaration-fact decl-id decl-kind name qualified-name node-id declaration)
      (supported-feature (str node-id ":feature:" (clojure.core/name (declaration-feature-kind declaration)))
                         (declaration-feature-kind declaration)
                         node-id)]
     (when-not parent-node-id
       [(top-level-feature node-id)])
     (when (instance? KtNamedFunction declaration)
       (mapcat (fn [parameter-ordinal parameter]
                 (parameter-facts source file-id node-id parameter-ordinal parameter))
               (range)
               (.getValueParameters ^KtNamedFunction declaration)))
     (declaration-type-facts node-id declaration)
     (supertype-ref-facts node-id declaration)
     (when (or (instance? KtNamedFunction declaration)
               (instance? KtProperty declaration))
       (declaration-expression-facts source file-id node-id declaration))
     (mapcat (fn [child-ordinal child]
               (declaration-facts source file-id package nested-owner-qname node-id child-ordinal child))
             (range)
             (child-declarations declaration)))))

(defn- package-facts [source file-id package ^KtFile file]
  (when (and package (.getPackageDirective file))
    (let [node-id (str file-id ":package:" package)]
      [(node-fact source node-id :kotlin.node/package package file-id 0 (.getPackageDirective file))
       (supported-feature (str node-id ":feature:package")
                          :kotlin.feature/package
                          node-id)])))

(defn- file-facts [file-record]
  (let [file-id (:file/id file-record)
        project-root (get-in file-record [:file/project :project/root])
        source-path (.resolve (path project-root) (:file/path file-record))
        source (Files/readString source-path StandardCharsets/UTF_8)
        {:keys [disposable file]} (parse-source (str (.getFileName source-path)) source)]
    (try
      (let [package (package-name file)]
        (doall
         (concat
          (package-facts source file-id package file)
          (mapcat (fn [ordinal declaration]
                    (declaration-facts source file-id package nil nil ordinal declaration))
                  (range)
                  (.getDeclarations file)))))
      (finally
        (Disposer/dispose disposable)))))

(defn- unique-key [fact]
  (some (fn [attr] (when-let [value (get fact attr)] [attr value]))
        [:node/id :decl/id :type/id :ref/id :feature/id]))

(defn- dedupe-facts [facts]
  (->> facts
       (filter map?)
       (reduce (fn [acc fact]
                 (if-let [key (unique-key fact)]
                   (if (contains? (:seen acc) key)
                     acc
                     (-> acc
                         (update :seen conj key)
                         (update :facts conj fact)))
                   (update acc :facts conj fact)))
               {:seen #{} :facts []})
       :facts))

(defn extract-project-facts
  "Read Kotlin file records for project-id from db and return normalized Kotlin PSI facts.

  Kotlin file records must already exist, usually from vibeformer.ingest.source."
  [db project-id]
  (dedupe-facts (mapcat file-facts (file-records db project-id))))

(def ^:private kotlin-stdlib-types
  {"Any" "kotlin:Any"
   "Boolean" "kotlin:Boolean"
   "Byte" "kotlin:Byte"
   "Char" "kotlin:Char"
   "Double" "kotlin:Double"
   "Float" "kotlin:Float"
   "Int" "kotlin:Int"
   "Long" "kotlin:Long"
   "List" "kotlin.collections.List"
   "Nothing" "kotlin:Nothing"
   "Short" "kotlin:Short"
   "String" "kotlin:String"
   "Map" "kotlin.collections.Map"
   "MutableList" "kotlin.collections.MutableList"
   "MutableMap" "kotlin.collections.MutableMap"
   "MutableSet" "kotlin.collections.MutableSet"
   "Regex" "kotlin.text.Regex"
   "Set" "kotlin.collections.Set"
   "Unit" "kotlin:Unit"})

(def ^:private known-function-calls
  {"addConfiguredDependencyTo" {:ref/to-type "org.gradle.api.artifacts.ExternalModuleDependency"
                                :ref/owner-type "org.gradle.kotlin.dsl.DependencyHandlerScope"}
   "addDependencyTo" {:ref/to-type "org.gradle.api.artifacts.Dependency"
                      :ref/owner-type "org.gradle.kotlin.dsl.DependencyHandlerScope"}
   "addExternalModuleDependencyTo" {:ref/to-type "org.gradle.api.artifacts.ExternalModuleDependency"
                                    :ref/owner-type "org.gradle.kotlin.dsl.DependencyHandlerScope"}
   "append" {:ref/to-type "java.lang.Appendable"
             :ref/owner-type "java.lang.Appendable"}
   "arrayOf" {:ref/to-type "kotlin:Array"}
   "assertThat" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                 :ref/owner-type "org.assertj.core.api.Assertions"}
   "assertThatCode" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                     :ref/owner-type "org.assertj.core.api.Assertions"}
   "assertThrows" {:ref/to-type "org.junit.jupiter.api.function.Executable"
                   :ref/owner-type "org.junit.jupiter.api.Assertions"}
   "configure" {:ref/to-type "kotlin:Unit"
                :ref/owner-type "org.gradle.api.plugins.ExtensionContainer"}
   "getByName" {:ref/to-type "kotlin:Any"
                :ref/owner-type "org.gradle.api.NamedDomainObjectCollection"}
   "hashCode" {:ref/to-type "kotlin:Int"
               :ref/owner-type "kotlin:Any"}
   "id" {:ref/to-type "org.gradle.plugin.use.PluginDependencySpec"
         :ref/owner-type "org.gradle.plugin.use.PluginDependenciesSpec"}
   "doesNotThrowAnyException" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                               :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "hasMessage" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                 :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "hasMessageContaining" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                           :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "hasMessageStartingWith" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                             :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "isEqualTo" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "isFalse" {:ref/to-type "org.assertj.core.api.AbstractAssert"
              :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "isInstanceOf" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                   :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "isNotEqualTo" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                   :ref/owner-type "org.assertj.core.api.AbstractAssert"}
   "lazy" {:ref/to-type "kotlin:Lazy"
           :ref/owner-type "kotlin:LazyKt"}
   "let" {:ref/to-type "kotlin:Any"
          :ref/owner-type "kotlin:Any"}
   "listOf" {:ref/to-type "kotlin.collections.List"
             :ref/owner-type "kotlin.collections.CollectionsKt"}
   "mapOf" {:ref/to-type "kotlin.collections.Map"
            :ref/owner-type "kotlin.collections.MapsKt"}
   "named" {:ref/to-type "org.gradle.api.NamedDomainObjectProvider"
            :ref/owner-type "org.gradle.api.NamedDomainObjectCollection"}
   "resolve" {:ref/to-type "java.nio.file.Path"
              :ref/owner-type "java.nio.file.Path"}
   "setOf" {:ref/to-type "kotlin.collections.Set"
            :ref/owner-type "kotlin.collections.SetsKt"}
   "toString" {:ref/to-type "kotlin:String"
               :ref/owner-type "kotlin:Any"}
   "toUri" {:ref/to-type "java.net.URI"
            :ref/owner-type "java.nio.file.Path"}
   "trimIndent" {:ref/to-type "kotlin:String"
                 :ref/owner-type "kotlin.text.StringsKt"}
   "trimMargin" {:ref/to-type "kotlin:String"
                 :ref/owner-type "kotlin.text.StringsKt"}
   "URI" {:ref/to-type "java.net.URI"
          :ref/owner-type "java.net.URI"}})

(def ^:private known-classpath-types
  {"AbstractAssert" "org.assertj.core.api.AbstractAssert"
   "Assertions" "org.assertj.core.api.Assertions"
   "ClassName" "com.squareup.javapoet.ClassName"
   "Executable" "org.junit.jupiter.api.function.Executable"
   "HttpRequest" "java.net.http.HttpRequest"
   "Identifier" "org.pkl.core.runtime.Identifier"
   "ParameterizedTypeName" "com.squareup.javapoet.ParameterizedTypeName"
   "PClassInfo" "org.pkl.core.PClassInfo"
   "Path" "java.nio.file.Path"
   "StringWriter" "java.io.StringWriter"
   "TypeName" "com.squareup.javapoet.TypeName"
   "URI" "java.net.URI"})

(def ^:private known-static-get-types
  #{"com.squareup.javapoet.ClassName"
    "com.squareup.javapoet.ParameterizedTypeName"
    "com.squareup.javapoet.TypeName"
    "org.pkl.core.PClassInfo"
    "org.pkl.core.runtime.Identifier"})

(def ^:private known-product-builder-types
  {"java.net.http.HttpRequest" "java.net.http.HttpRequest.Builder"
   "org.pkl.core.http.HttpClient" "org.pkl.core.http.HttpClientBuilder"})

(def ^:private product-builder-factory-methods
  #{"builder"
    "newBuilder"
    "annotationBuilder"
    "anonymousClassBuilder"
    "classBuilder"
    "companionObjectBuilder"
    "constructorBuilder"
    "enumBuilder"
    "funBuilder"
    "interfaceBuilder"
    "methodBuilder"
    "objectBuilder"
    "propertyBuilder"
    "typeAliasBuilder"})

(def ^:private builder-self-factory-methods
  #{"preconfigured" "unconfigured"})

(def ^:private absent :vibeformer.query/absent)

(defn- nil-if-absent [value]
  (when-not (= absent value)
    value))

(defn- type-identity [db value]
  (when-let [value (nil-if-absent value)]
    (if (string? value)
      value
      (:type/id (d/pull db [:type/id] value)))))

(defn- project-declarations [db project-id]
  (mapv (fn [[decl-id kind name qualified-name decl-type return-type receiver-type file-id node-id]]
          {:decl/id decl-id
           :decl/kind kind
           :decl/name name
           :decl/qualified-name qualified-name
           :decl/type (type-identity db decl-type)
           :decl/return-type (type-identity db return-type)
           :decl/receiver-type (type-identity db receiver-type)
           :decl/file-id file-id
           :node/id node-id})
        (d/q '[:find ?decl-id ?kind ?name ?qualified-name ?decl-type ?return-type ?receiver-type ?file-id ?node-id
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?file :file/id ?file-id]
               [?node :node/file ?file]
               [?node :node/id ?node-id]
               [?decl :decl/source-node ?node]
               [?decl :decl/id ?decl-id]
               [?decl :decl/kind ?kind]
               [?decl :decl/name ?name]
               [?decl :decl/qualified-name ?qualified-name]
               [(get-else $ ?decl :decl/type :vibeformer.query/absent) ?decl-type]
               [(get-else $ ?decl :decl/return-type :vibeformer.query/absent) ?return-type]
               [(get-else $ ?decl :decl/receiver-type :vibeformer.query/absent) ?receiver-type]]
             db project-id)))

(defn- project-explicit-binding-type-index [db project-id]
  (reduce (fn [acc [function-node-id name type-id]]
            (assoc-in acc [function-node-id name] type-id))
          {}
          (d/q '[:find ?function-node-id ?name ?type-id
              :in $ ?project-id
              :where
              [?project :project/id ?project-id]
              [?file :file/project ?project]
              [?function :node/file ?file]
              [?function :node/id ?function-node-id]
              [?binding :node/parent ?function]
              [?binding :node/name ?name]
              [?binding :node/kind ?kind]
              [(contains? #{:kotlin.node/value-parameter
                            :kotlin.node/local-property}
                           ?kind)]
              [?ref :ref/from-node ?binding]
              [?ref :ref/kind :ref.kind/type-use]
              [?ref :ref/to-type ?type]
              [?type :type/id ?type-id]]
            db
            project-id)))

(defn- literal-type-id [value]
  (let [value (some-> value str/trim)]
    (cond
      (nil? value) nil
      (re-matches #"\"(?:\\.|[^\"])*\"" value) "kotlin:String"
      (re-matches #"-?\d+" value) "kotlin:Int"
      (contains? #{"true" "false"} value) "kotlin:Boolean"
      (= "null" value) "kotlin:Nothing?"
      :else nil)))

(defn- simple-identifier [value]
  (when-let [value (some-> value str/trim)]
    (when (re-matches #"[A-Za-z_][A-Za-z0-9_]*" value)
      value)))

(declare expression-type-id)

(defn- receiver-known-call-type-id [value]
  (when-let [value (some-> value str/trim)]
    (when-let [[_ call-name] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" value)]
      (:ref/to-type (get known-function-calls call-name)))))

(defn- qualified-known-call-type-id [value]
  (when-let [value (some-> value str/trim)]
    (when-let [[_ call-name] (re-find #"\.([A-Za-z_][A-Za-z0-9_]*)\s*\(" value)]
      (:ref/to-type (get known-function-calls call-name)))))

(defn- receiver-known-static-type-id [value]
  (some-> value str/trim known-classpath-types))

(defn- strip-builder-suffix [type-id]
  (cond
    (str/ends-with? type-id ".Builder")
    (subs type-id 0 (- (count type-id) (count ".Builder")))

    (str/ends-with? type-id "Builder")
    (subs type-id 0 (- (count type-id) (count "Builder")))))

(defn- builder-product-type-id [builder-type-id]
  (or (some (fn [[product-type-id known-builder-type-id]]
              (when (= known-builder-type-id builder-type-id)
                product-type-id))
            known-product-builder-types)
      (strip-builder-suffix builder-type-id)))

(defn- builder-type-from-product [product-type-id]
  (or (get known-product-builder-types product-type-id)
      (str product-type-id ".Builder")))

(defn- constructor-expression-type-id [type-index value]
  (when-let [value (some-> value str/trim)]
    (or (when-let [[_ name] (re-matches #"([A-Z][A-Za-z0-9_]*)\s*\(.*" value)]
          (get type-index name))
        (when-let [[_ owner nested] (re-matches #"([A-Z][A-Za-z0-9_]*(?:\.[A-Z][A-Za-z0-9_]*)*)\.([A-Z][A-Za-z0-9_]*)\s*\(.*" value)]
          (when-let [owner-type-id (get type-index (simple-type-name owner))]
            (str owner-type-id "." nested))))))

(defn- builder-factory-expression-type-id [type-index value]
  (when-let [value (some-> value str/trim)]
    (when-let [[_ owner method] (re-matches #"([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\.([A-Za-z_][A-Za-z0-9_]*)\s*\(.*" value)]
      (when-let [owner-type-id (or (get type-index owner)
                                   (get type-index (simple-type-name owner)))]
        (cond
          (contains? product-builder-factory-methods method)
          (builder-type-from-product owner-type-id)

          (contains? builder-self-factory-methods method)
          owner-type-id)))))

(defn- expression-type-id [type-index value]
  (or (literal-type-id value)
      (constructor-expression-type-id type-index value)
      (builder-factory-expression-type-id type-index value)
      (receiver-known-call-type-id value)
      (qualified-known-call-type-id value)
      (receiver-known-static-type-id value)))

(defn- project-inferred-binding-type-index [db project-id type-index]
  (reduce (fn [acc [function-node-id name value]]
            (if-let [type-id (expression-type-id type-index value)]
              (assoc-in acc [function-node-id name] type-id)
              acc))
          {}
          (d/q '[:find ?function-node-id ?name ?value
                 :in $ ?project-id
                 :where
                 [?project :project/id ?project-id]
                 [?file :file/project ?project]
                 [?function :node/file ?file]
                 [?function :node/id ?function-node-id]
                 [?binding :node/parent ?function]
                 [?binding :node/name ?name]
                 [?binding :node/kind :kotlin.node/local-property]
                 [?binding :node/value ?value]]
               db
               project-id)))

(defn- merge-binding-type-indexes [& indexes]
  (apply merge-with merge indexes))

(defn- project-binding-type-index [db project-id type-index member-binding-types]
  (merge-binding-type-indexes
   member-binding-types
   (project-explicit-binding-type-index db project-id)
   (project-inferred-binding-type-index db project-id type-index)))

(defn- project-call-argument-values [db project-id]
  (->> (d/q '[:find ?call-node-id ?ordinal ?value
              :in $ ?project-id
              :where
              [?project :project/id ?project-id]
              [?file :file/project ?project]
              [?call :node/file ?file]
              [?call :node/id ?call-node-id]
              [?arg :node/parent ?call]
              [?arg :node/kind :kotlin.node/call-argument]
              [?arg :node/ordinal ?ordinal]
              [?arg :node/value ?value]]
            db
            project-id)
       (group-by first)
       (map (fn [[call-node-id rows]]
              [call-node-id (mapv #(nth % 2) (sort-by second rows))]))
       (into {})))

(defn- project-call-receiver-values [db project-id]
  (into {}
        (d/q '[:find ?call-node-id ?value
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?call :node/file ?file]
               [?call :node/id ?call-node-id]
               [?receiver :node/parent ?call]
               [?receiver :node/kind :kotlin.node/call-receiver]
               [?receiver :node/value ?value]]
             db
             project-id)))

(defn- project-call-parent-index [db project-id]
  (into {}
        (d/q '[:find ?call-node-id ?parent-node-id
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?call :node/file ?file]
               [?call :node/kind :kotlin.node/call-expression]
               [?call :node/id ?call-node-id]
               [?call :node/parent ?parent]
               [?parent :node/id ?parent-node-id]]
             db
             project-id)))

(defn- call-argument-type-ids [binding-types-by-parent arg-values-by-call parent-by-call node-id]
  (let [parent-node-id (get parent-by-call node-id)
        binding-types (get binding-types-by-parent parent-node-id)]
    (mapv (fn [value]
            (or (some->> value simple-identifier (get binding-types))
                (literal-type-id value)))
          (get arg-values-by-call node-id []))))

(defn- call-receiver-type-id [type-index binding-types-by-parent receiver-values-by-call parent-by-call node-id]
  (let [parent-node-id (get parent-by-call node-id)
        binding-types (get binding-types-by-parent parent-node-id)
        value (get receiver-values-by-call node-id)]
    (or (some->> value simple-identifier (get binding-types))
        (expression-type-id type-index value))))

(defn- project-refs [db project-id type-index member-binding-types]
  (let [binding-types-by-parent (project-binding-type-index db project-id type-index member-binding-types)
        arg-values-by-call (project-call-argument-values db project-id)
        receiver-values-by-call (project-call-receiver-values db project-id)
        parent-by-call (project-call-parent-index db project-id)]
    (mapv (fn [[ref-id kind name to-type node-id file-id]]
            (let [arg-types (when (= :ref.kind/function-call kind)
                              (call-argument-type-ids binding-types-by-parent
                                                      arg-values-by-call
                                                      parent-by-call
                                                      node-id))
                  receiver-type (when (= :ref.kind/function-call kind)
                                  (call-receiver-type-id type-index
                                                         binding-types-by-parent
                                                         receiver-values-by-call
                                                         parent-by-call
                                                         node-id))]
              {:ref/id ref-id
               :ref/kind kind
               :ref/name name
               :ref/to-type (type-identity db to-type)
               :node/id node-id
               :ref/file-id file-id
               :call/arg-count (count arg-types)
               :call/arg-types arg-types
               :call/receiver-type receiver-type}))
          (d/q '[:find ?ref-id ?kind ?name ?to-type ?node-id ?file-id
                 :in $ ?project-id
                 :where
                 [?project :project/id ?project-id]
                 [?file :file/project ?project]
                 [?file :file/id ?file-id]
                 [?file :file/lang :lang/kotlin]
                 [?node :node/file ?file]
                 [?node :node/id ?node-id]
                 [?ref :ref/from-node ?node]
                 [?ref :ref/id ?ref-id]
                 [?ref :ref/kind ?kind]
                 [?ref :ref/name ?name]
                 [(get-else $ ?ref :ref/to-type :vibeformer.query/absent) ?to-type]]
               db project-id))))

(defn- normalize-classpath-types [classpath-types]
  (cond
    (map? classpath-types)
    (into {}
          (map (fn [[name type-id]]
                 [(str name) (str type-id)]))
          classpath-types)

    (sequential? classpath-types)
    (into {}
          (map (fn [name]
                 [(str name) (str "kotlin:" name)]))
          classpath-types)

    (set? classpath-types)
    (normalize-classpath-types (seq classpath-types))

    :else {}))

(defn- source-declaration-type-id [{:decl/keys [kind qualified-name type]}]
  (when (contains? #{:decl.kind/class
                    :decl.kind/interface
                    :decl.kind/object
                    :decl.kind/companion-object}
                  kind)
    (or type (str "kotlin:" qualified-name))))

(defn- source-type-index [decls]
  (let [decl-type-ids (keep :decl/type decls)
        source-type-ids (keep source-declaration-type-id decls)]
    (->> (concat decl-type-ids source-type-ids)
         (map (fn [type-id]
                (let [type-name (str/replace type-id #"^kotlin:" "")]
                  [(simple-type-name type-name) type-id])))
         (into {}))))

(defn- function-param-type-ids [{:decl/keys [qualified-name]}]
  (when-let [[_ params] (re-find #"\((.*)\)$" qualified-name)]
    (if (str/blank? params)
      []
      (mapv (comp type-id parse-type-syntax str/trim)
            (split-top-level params \,)))))

(defn- with-function-param-types [decl]
  (assoc decl :decl/param-types (function-param-type-ids decl)))

(defn- function-index [decls]
  (->> decls
       (filter #(= :decl.kind/function (:decl/kind %)))
       (map with-function-param-types)
       (group-by :decl/name)))

(defn- method-index [decls]
  (->> decls
       (filter #(= :decl.kind/method (:decl/kind %)))
       (map with-function-param-types)
       (group-by :decl/name)))

(defn- type-lang [type-id]
  (if (str/starts-with? type-id "kotlin:")
    :lang/kotlin
    :lang/java))

(defn- nullable-type-id? [type-id]
  (str/ends-with? type-id "?"))

(defn- nullable-type-id [type-id]
  (if (nullable-type-id? type-id)
    type-id
    (str type-id "?")))

(defn- type-name-from-id [type-id]
  (-> type-id
      (str/replace #"^kotlin:" "")
      (str/replace #"\?$" "")))

(defn- type-stub [type-id]
  {:db/id type-id
   :type/id type-id
   :type/lang (type-lang type-id)
   :type/name (type-name-from-id type-id)
   :type/nullable? (nullable-type-id? type-id)})

(defn- referenced-type-id [value]
  (cond
    (nil? value) nil
    (and (vector? value) (= 2 (count value))) (second value)
    (string? value) value))

(defn- referenced-type-ids [facts]
  (->> facts
       (filter map?)
       (mapcat (fn [fact]
                 (keep (fn [attr]
                         (referenced-type-id (get fact attr)))
                       [:decl/receiver-type :ref/to-type :ref/owner-type])))
       set))

(defn- existing-type-ids [db type-ids]
  (set
   (map first
        (d/q '[:find ?type-id
               :in $ [?type-id ...]
               :where
               [_ :type/id ?type-id]]
             db
             (vec type-ids)))))

(defn- missing-type-stubs [db facts]
  (let [referenced (referenced-type-ids facts)
        existing (existing-type-ids db referenced)]
    (->> referenced
         (remove existing)
         sort
         (mapv type-stub))))

(defn- owner-type-id [source-types {:decl/keys [qualified-name]}]
  (some (fn [[_ type-id]]
          (let [qname (str/replace type-id #"^kotlin:" "")]
            (when (str/starts-with? qualified-name (str qname "."))
              type-id)))
        (sort-by (comp count val) > source-types)))

(defn- current-ref-reason [db ref-id]
  (ffirst (d/q '[:find ?reason
                 :in $ ?ref-id
                 :where
                 [?ref :ref/id ?ref-id]
                 [?ref :ref/reason ?reason]]
               db ref-id)))

(defn- resolved-ref-tx [db ref-id attrs]
  (let [reason (current-ref-reason db ref-id)]
    (cond-> [(assoc attrs
                    :db/id [:ref/id ref-id]
                    :ref/resolved? true)]
      reason (conj [:db/retract [:ref/id ref-id] :ref/reason reason]))))

(defn- unresolved-ref-tx [ref-id reason]
  [{:db/id [:ref/id ref-id]
    :ref/resolved? false
    :ref/reason reason}])

(defn- type-resolution-tx [db type-index {:ref/keys [id name to-type]}]
  (let [matched-type-id (or (get type-index name)
                            (get type-index (simple-type-name name)))
        resolved-type-id (cond-> (or matched-type-id to-type)
                           (and matched-type-id
                                (nullable-type-id? to-type))
                           nullable-type-id)]
    (if (or (get type-index name)
            (get type-index (simple-type-name name)))
      (resolved-ref-tx db id {:ref/to-type [:type/id resolved-type-id]})
      (unresolved-ref-tx id :resolve.reason/missing-classpath))))

(defn- unqualified-type-id [type-id]
  (some-> type-id
          (str/replace #"^kotlin:" "")
          (str/replace #"\?$" "")
          simple-type-name))

(defn- nullable-type-compatible? [arg-type param-type]
  (let [arg-base (unqualified-type-id arg-type)
        param-base (unqualified-type-id param-type)]
    (and (= arg-base param-base)
         (not (and (nullable-type-id? arg-type)
                   (not (nullable-type-id? param-type)))))))

(defn- type-match-score [arg-type param-type]
  (cond
    (or (nil? arg-type) (nil? param-type)) 0
    (= arg-type param-type) 3
    (and (not (nullable-type-id? arg-type))
         (nullable-type-id? param-type)
         (= (unqualified-type-id arg-type) (unqualified-type-id param-type))) 1
    (nullable-type-compatible? arg-type param-type) 2
    :else nil))

(defn- overload-score [arg-types {:decl/keys [param-types]}]
  (when (= (count arg-types) (count param-types))
    (let [scores (mapv type-match-score arg-types param-types)]
      (when (every? some? scores)
        (reduce + scores)))))

(defn- best-overload [arg-types candidates]
  (let [arity-matches (filter #(= (count arg-types) (count (:decl/param-types %)))
                              candidates)
        scored (->> arity-matches
                    (keep (fn [candidate]
                            (when-let [score (overload-score arg-types candidate)]
                              (assoc candidate :resolution/score score))))
                    (sort-by :resolution/score >)
                    vec)]
    (cond
      (= 1 (count arity-matches)) (first arity-matches)
      (and (seq scored)
           (or (= 1 (count scored))
               (> (:resolution/score (first scored))
                  (:resolution/score (second scored)))))
      (first scored))))

(defn- resolve-decl-tx [db source-types ref-id decl]
  (let [owner-type (or (owner-type-id source-types decl)
                       (:decl/receiver-type decl))]
    (resolved-ref-tx db ref-id
                     (cond-> {:ref/to-decl [:decl/id (:decl/id decl)]}
                       (:decl/return-type decl)
                       (assoc :ref/to-type [:type/id (:decl/return-type decl)])

                       owner-type
                       (assoc :ref/owner-type [:type/id owner-type])))))

(defn- kotlin-contains-call [{:call/keys [receiver-type]}]
  (when receiver-type
    (let [owner-root (unqualified-type-id receiver-type)]
      (cond
        (contains? #{"String" "Collection" "List" "MutableList" "Set" "MutableSet"} owner-root)
        {:ref/to-type "kotlin:Boolean"
         :ref/owner-type receiver-type}

        (= "AbstractAssert" owner-root)
        {:ref/to-type "org.assertj.core.api.AbstractAssert"
         :ref/owner-type "org.assertj.core.api.AbstractAssert"}))))

(defn- kotlin-matches-call [{:call/keys [receiver-type]}]
  (when receiver-type
    (let [owner-root (unqualified-type-id receiver-type)]
      (when (contains? #{"String" "Regex"} owner-root)
        {:ref/to-type "kotlin:Boolean"
         :ref/owner-type receiver-type}))))

(defn- kotlin-static-get-call [{:call/keys [receiver-type]}]
  (when (contains? known-static-get-types receiver-type)
    {:ref/to-type receiver-type
     :ref/owner-type receiver-type}))

(defn- kotlin-build-call [{:call/keys [receiver-type]}]
  (when-let [product-type-id (some-> receiver-type builder-product-type-id)]
    {:ref/to-type product-type-id
     :ref/owner-type receiver-type}))

(defn- constructor-call-name? [name]
  (boolean (re-matches #"[A-Z][A-Za-z0-9_]*" (or name ""))))

(defn- kotlin-constructor-call [type-index {:ref/keys [name]}]
  (when (constructor-call-name? name)
    (when-let [type-id (get type-index name)]
      {:ref/to-type type-id
       :ref/owner-type type-id})))

(defn- known-call-resolution [{:ref/keys [name] :as ref}]
  (or (when (= "contains" name)
        (kotlin-contains-call ref))
      (when (= "matches" name)
        (kotlin-matches-call ref))
      (when (= "build" name)
        (kotlin-build-call ref))
      (when (= "get" name)
        (kotlin-static-get-call ref))
      (get known-function-calls name)))

(defn- same-file-candidates [candidates file-id]
  (let [matches (filter #(= file-id (:decl/file-id %)) candidates)]
    (if (seq matches)
      matches
      candidates)))

(defn- same-type-id? [left right]
  (= (simple-type-name (type-name-from-id left))
     (simple-type-name (type-name-from-id right))))

(defn- source-node-type-index [decls]
  (into {}
        (keep (fn [{:node/keys [id] :as decl}]
                (when-let [type-id (source-declaration-type-id decl)]
                  [id type-id])))
        decls))

(defn- project-parent-node-index [db project-id]
  (into {}
        (d/q '[:find ?node-id ?parent-node-id
               :in $ ?project-id
               :where
               [?project :project/id ?project-id]
               [?file :file/project ?project]
               [?node :node/file ?file]
               [?node :node/id ?node-id]
               [?node :node/parent ?parent]
               [?parent :node/id ?parent-node-id]]
             db
             project-id)))

(defn- normalize-source-type-id [source-types type-index name type-id]
  (or (get type-index name)
      (some->> name simple-type-name (get type-index))
      (when type-id
        (or (get source-types (simple-type-name (type-name-from-id type-id)))
            type-id))))

(defn- project-direct-supertype-index [db project-id source-types type-index]
  (reduce (fn [acc [node-id name to-type]]
            (if-let [type-id (normalize-source-type-id source-types
                                                       type-index
                                                       name
                                                       (type-identity db to-type))]
              (update acc node-id (fnil conj #{}) type-id)
              acc))
          {}
          (d/q '[:find ?node-id ?name ?to-type
                 :in $ ?project-id
                 :where
                 [?project :project/id ?project-id]
                 [?file :file/project ?project]
                 [?node :node/file ?file]
                 [?node :node/id ?node-id]
                 [?ref :ref/from-node ?node]
                 [?ref :ref/kind :ref.kind/implements]
                 [?ref :ref/name ?name]
                 [?ref :ref/to-type ?to-type]]
               db
               project-id)))

(defn- enclosing-type-id [parent-by-node source-node-types node-id]
  (loop [current node-id]
    (when current
      (or (get source-node-types current)
          (recur (get parent-by-node current))))))

(defn- source-type-node-index [source-node-types]
  (into {}
        (map (fn [[node-id type-id]]
               [type-id node-id]))
        source-node-types))

(defn- type-lineage [type-node-by-id direct-supertypes type-id]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY type-id)
         seen #{}
         lineage []]
    (if-let [current (peek queue)]
      (let [queue (pop queue)]
        (if (contains? seen current)
          (recur queue seen lineage)
          (let [super-types (->> current
                                 (get type-node-by-id)
                                 (get direct-supertypes)
                                 sort)]
            (recur (into queue super-types)
                   (conj seen current)
                   (conj lineage current)))))
      lineage)))

(defn- property-bindings-by-owner-type [source-types decls]
  (reduce (fn [acc {:decl/keys [kind name type] :as decl}]
            (if (and (= :decl.kind/property kind) type)
              (if-let [owner-type (owner-type-id source-types decl)]
                (assoc-in acc [owner-type name] type)
                acc)
              acc))
          {}
          decls))

(defn- member-property-binding-type-index [source-types parent-by-node source-node-types direct-supertypes decls]
  (let [property-bindings (property-bindings-by-owner-type source-types decls)
        type-node-by-id (source-type-node-index source-node-types)]
    (reduce (fn [acc {:decl/keys [kind] :node/keys [id]}]
              (if (= :decl.kind/function kind)
                (if-let [current-type (enclosing-type-id parent-by-node source-node-types id)]
                  (let [bindings (->> current-type
                                      (type-lineage type-node-by-id direct-supertypes)
                                      reverse
                                      (map property-bindings)
                                      (apply merge))]
                    (cond-> acc
                      (seq bindings) (assoc id bindings)))
                  acc)
                acc))
            {}
            decls)))

(defn- receiver-member-candidates [source-types {:call/keys [receiver-type]} candidates]
  (when receiver-type
    (seq (filter (fn [candidate]
                   (when-let [owner-type (owner-type-id source-types candidate)]
                     (same-type-id? receiver-type owner-type)))
                 candidates))))

(defn- receiver-extension-candidates [{call-receiver-type :call/receiver-type} candidates]
  (when call-receiver-type
    (seq (filter (fn [{decl-receiver-type :decl/receiver-type}]
                   (and decl-receiver-type
                        (same-type-id? call-receiver-type decl-receiver-type)))
                 candidates))))

(defn- inherited-member-candidates [source-types parent-by-node source-node-types direct-supertypes {:call/keys [receiver-type] :node/keys [id]} candidates]
  (when-not receiver-type
    (when-let [current-type (enclosing-type-id parent-by-node source-node-types id)]
      (let [type-node-by-id (source-type-node-index source-node-types)
            lineage (set (type-lineage type-node-by-id direct-supertypes current-type))]
        (seq (filter (fn [candidate]
                       (when-let [owner-type (owner-type-id source-types candidate)]
                         (contains? lineage owner-type)))
                     candidates))))))

(defn- extension-receiver-type-index [decls]
  (into {}
        (keep (fn [{:node/keys [id] :decl/keys [receiver-type]}]
                (when receiver-type
                  [id receiver-type])))
        decls))

(defn- enclosing-extension-receiver-type [parent-by-node extension-receiver-types node-id]
  (loop [current node-id]
    (when current
      (or (get extension-receiver-types current)
          (recur (get parent-by-node current))))))

(defn- resolve-function-candidates-tx [db source-types id arg-types candidates]
  (case (count candidates)
    0 nil
    1 (resolve-decl-tx db source-types id (first candidates))
    (when-let [decl (best-overload arg-types candidates)]
      (resolve-decl-tx db source-types id decl))))

(defn- known-call-resolution-tx [db id known-call]
  (resolved-ref-tx db id
                   (cond-> {}
                     (:ref/to-type known-call)
                     (assoc :ref/to-type [:type/id (:ref/to-type known-call)])

                     (:ref/owner-type known-call)
                     (assoc :ref/owner-type [:type/id (:ref/owner-type known-call)]))))

(defn- function-resolution-tx [db functions methods source-types type-index parent-by-node source-node-types direct-supertypes extension-receiver-types {:ref/keys [id name] :call/keys [arg-types receiver-type] :as ref}]
  (let [candidates (same-file-candidates (get functions name) (:ref/file-id ref))
        member-candidates (concat (get functions name) (get methods name))
        receiver-candidates (or (receiver-member-candidates source-types ref candidates)
                                (receiver-member-candidates source-types ref member-candidates))
        extension-ref (cond-> ref
                        (nil? receiver-type)
                        (assoc :call/receiver-type
                               (enclosing-extension-receiver-type parent-by-node
                                                                  extension-receiver-types
                                                                  (:node/id ref))))
        extension-candidates (receiver-extension-candidates extension-ref candidates)
        inherited-candidates (inherited-member-candidates source-types
                                                          parent-by-node
                                                          source-node-types
                                                          direct-supertypes
                                                          ref
                                                          member-candidates)]
    (or (when (and (= "hashCode" name) receiver-candidates)
          (resolve-function-candidates-tx db source-types id arg-types receiver-candidates))
        (when-let [known-call (or (known-call-resolution ref)
                                  (kotlin-constructor-call type-index ref))]
          (known-call-resolution-tx db id known-call))
        (when (and (= "matches" name) receiver-candidates)
          (resolve-function-candidates-tx db source-types id arg-types receiver-candidates))
        (resolve-function-candidates-tx db source-types id arg-types extension-candidates)
        (when-not (and (= "matches" name)
                       (:call/receiver-type ref))
          (resolve-function-candidates-tx db source-types id arg-types candidates))
        (resolve-function-candidates-tx db source-types id arg-types inherited-candidates)
        (unresolved-ref-tx id (if (seq candidates)
                                :resolve.reason/analysis-api-limitation
                                :resolve.reason/missing-classpath)))))

(defn semantic-resolution-facts
  "Return idempotent tx-data that enriches Kotlin refs with semantic links.

  This is the conservative fallback used until a full Kotlin Analysis API
  session can be created for the source module. It resolves project-local
  functions and known classpath/source types, and records deliberate reasons
  for everything it cannot prove."
  [db project-id opts]
  (let [decls (project-declarations db project-id)
        source-types (source-type-index decls)
        type-index (merge kotlin-stdlib-types
                          known-classpath-types
                          (normalize-classpath-types (:kotlin/classpath-types opts))
                          source-types)
        functions (function-index decls)
        methods (method-index decls)
        parent-by-node (project-parent-node-index db project-id)
        source-node-types (source-node-type-index decls)
        direct-supertypes (project-direct-supertype-index db project-id source-types type-index)
        member-binding-types (member-property-binding-type-index source-types
                                                                 parent-by-node
                                                                 source-node-types
                                                                 direct-supertypes
                                                                 decls)
        extension-receiver-types (extension-receiver-type-index decls)
        analysis-api-tx (when (:kotlin/analysis-api? opts)
                          (:tx-data (kotlin-analysis-api/setup-facts db project-id opts)))]
    (vec
     (concat
      analysis-api-tx
      (mapcat (fn [ref]
                (case (:ref/kind ref)
                  :ref.kind/type-use
                  (type-resolution-tx db type-index ref)

                  :ref.kind/implements
                  (type-resolution-tx db type-index ref)

                  :ref.kind/function-call
                  (function-resolution-tx db
                                          functions
                                          methods
                                          source-types
                                          type-index
                                          parent-by-node
                                          source-node-types
                                          direct-supertypes
                                          extension-receiver-types
                                          ref)

                  []))
              (project-refs db project-id type-index member-binding-types))))))

(defn enrich!
  "Enrich existing Kotlin PSI facts with conservative semantic resolution.

  Options:
  - :project/id project identity to enrich
  - :kotlin/classpath-types collection or map of known type names available on
    the analysis classpath. Collection entries resolve to existing syntax type
    facts such as kotlin:Locale; map values may provide explicit type ids.
  - :kotlin/analysis-api? when true, records the Analysis API module/session
    setup attempt as stable pass/diagnostic facts and uses conservative
    fallback resolution when Analysis API classes are unavailable."
  [conn {:project/keys [id] :as opts}]
  (let [db (d/db conn)
        analysis-api-result (when (:kotlin/analysis-api? opts)
                              (kotlin-analysis-api/setup-facts db id opts))
        tx-data (semantic-resolution-facts db id opts)
        type-stubs (missing-type-stubs db tx-data)]
    (when (seq type-stubs)
      (d/transact conn {:tx-data type-stubs}))
    (when (seq tx-data)
      (d/transact conn {:tx-data tx-data}))
    {:project/id id
     :semantic-refs (count (filter #(contains? % :ref/resolved?) tx-data))
     :semantic-tx (count tx-data)
     :type-stubs (count type-stubs)
     :analysis-api/setup (:setup analysis-api-result)
     :analysis-api/status (:status analysis-api-result)
     :analysis-api/reason (:reason analysis-api-result)}))

(defn ingest!
  "Extract normalized Kotlin facts from ingested Kotlin files and transact them."
  [conn {:project/keys [id]}]
  (let [db (d/db conn)
        files (file-records db id)
        facts (extract-project-facts db id)]
    (when (seq facts)
      (d/transact conn {:tx-data facts}))
    {:project/id id
     :kotlin-files (count files)
     :transacted-facts (count facts)}))
