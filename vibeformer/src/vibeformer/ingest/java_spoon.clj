(ns vibeformer.ingest.java-spoon
  (:require [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.nio.file Paths)
           (java.security MessageDigest)
           (spoon Launcher)
           (spoon.reflect.code CtArrayRead CtAssignment CtBinaryOperator CtBlock CtCase CtCatch CtConditional CtConstructorCall CtExecutableReferenceExpression CtExpression CtFieldRead CtFieldWrite CtForEach CtIf CtInvocation CtLambda CtLiteral CtLocalVariable CtReturn CtStatement CtSwitchExpression CtSynchronized CtTargetedExpression CtThisAccess CtThrow CtTry CtTypeAccess CtTypePattern CtUnaryOperator CtVariableRead CtVariableWrite CtYieldStatement)
           (spoon.reflect.declaration CtAnnotationType CtClass CtConstructor CtEnum CtExecutable CtField CtInterface CtMethod CtParameter CtRecord CtRecordComponent CtType)
           (spoon.reflect.reference CtExecutableReference CtFieldReference CtTypeReference)
           (spoon.reflect.visitor.filter TypeFilter)))

(def ^:private lang :lang/java)
(def ^:dynamic *classpath-types* #{})
(def ^:dynamic *classpath-package-roots* #{})

(def ^:private known-java-api-calls
  {"assertThat" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                 :ref/owner-type "org.assertj.core.api.Assertions"}
   "isEqualTo" {:ref/to-type "org.assertj.core.api.AbstractAssert"
                :ref/owner-type "org.assertj.core.api.AbstractAssert"}})

(def ^:private known-java-api-types
  #{"java.lang.StringBuilder"
    "java.lang.StringBuffer"
    "java.lang.Object"
    "boolean"
    "int"})

(defn- hex-bytes [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- sha256 [value]
  (let [bytes (.getBytes (str value) java.nio.charset.StandardCharsets/UTF_8)]
    (str "sha256:" (hex-bytes (.digest (doto (MessageDigest/getInstance "SHA-256")
                                         (.update bytes)))))))

(defn- path [value]
  (if (instance? java.nio.file.Path value)
    value
    (Paths/get (str value) (make-array String 0))))

(defn- modifier-keyword [modifier]
  (-> modifier .name str/lower-case (str/replace "_" "-") keyword))

(defn- modifiers [element]
  (set (map modifier-keyword (.getModifiers element))))

(defn- has-modifier? [element kw]
  (contains? (modifiers element) kw))

(defn- visibility-modifier? [kw]
  (contains? #{:public :private :protected} kw))

(defn- package-private? [element]
  (not-any? visibility-modifier? (modifiers element)))

(defn- valid-position [element]
  (let [position (.getPosition element)]
    (when (and position (.isValidPosition position))
      position)))

(defn- source-span [element]
  (when-let [position (valid-position element)]
    {:node/start-line (.getLine position)
     :node/start-column (.getColumn position)
     :node/end-line (.getEndLine position)
     :node/end-column (.getEndColumn position)}))

(defn- source-order-key [element fallback-name]
  (let [span (source-span element)]
    [(or (:node/start-line span) Long/MAX_VALUE)
     (or (:node/start-column span) Long/MAX_VALUE)
     fallback-name]))

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
               [?file :file/lang :lang/java]]
             db project-id)))

(defn- parse-file [source-path]
  (let [launcher (Launcher.)]
    (doto (.getEnvironment launcher)
      (.setNoClasspath true)
      (.setComplianceLevel 17)
      (.setAutoImports true))
    (.addInputResource launcher (str source-path))
    (.buildModel launcher)
    (.getAllTypes (.getModel launcher))))

(defn- normalize-qname [value]
  (if-let [[_ owner nested] (re-matches #"(.+)\$([^.$]+)$" (or value ""))]
    (let [owner-simple (last (str/split owner #"\."))]
      (if (= owner-simple nested)
        owner
        value))
    value))

(def ^:dynamic *import-aliases* {})
(def ^:dynamic *import-wildcard-packages* [])
(def ^:dynamic *source-type-qnames* #{})
(def ^:dynamic *current-package* nil)

(defn- simple-source-type-ref? [^CtTypeReference type-ref]
  (let [simple-name (.getSimpleName type-ref)
        source-name (str type-ref)]
    (boolean
     (and simple-name
          (or (= source-name simple-name)
              (str/starts-with? source-name (str simple-name "<"))
              (str/starts-with? source-name (str simple-name "[")))))))

(defn- import-shadowed-qname [^CtTypeReference type-ref qname]
  (let [simple-name (.getSimpleName type-ref)
        alias (get *import-aliases* simple-name)
        same-package-alias (when (seq *current-package*)
                             (let [candidate (str *current-package* "." simple-name)]
                               (when (contains? *source-type-qnames* candidate)
                                 candidate)))
        wildcard-alias (->> *import-wildcard-packages*
                            (map #(str % "." simple-name))
                            (filter *source-type-qnames*)
                            distinct
                            (#(when (= 1 (count %)) (first %))))
        simple-ref? (simple-source-type-ref? type-ref)
        replaceable? (and qname
                          simple-ref?
                          (not (contains? *source-type-qnames* qname))
                          (or (= qname simple-name)
                              (= qname (str "java.lang." simple-name))
                              (str/ends-with? qname (str "." simple-name))))]
    (cond
      (and alias replaceable? (not= alias qname))
      alias

      (and same-package-alias replaceable? (not= same-package-alias qname))
      same-package-alias

      (and wildcard-alias replaceable? (not= wildcard-alias qname))
      wildcard-alias

      :else
      qname)))

(defn- import-shadowed-type-ref? [^CtTypeReference type-ref]
  (let [raw-qname (normalize-qname (.getQualifiedName type-ref))]
    (not= raw-qname (import-shadowed-qname type-ref raw-qname))))

(defn- qname [value]
  (cond
    (nil? value) nil
    (instance? CtType value) (normalize-qname (.getQualifiedName ^CtType value))
    (instance? CtTypeReference value)
    (import-shadowed-qname value (normalize-qname (.getQualifiedName ^CtTypeReference value)))
    :else (str value)))

(defn- type-base-name [^CtTypeReference type-ref]
  (or (qname type-ref) (some-> type-ref str)))

(declare type-id)

(def nullable-annotations
  #{"Nullable"
    "javax.annotation.Nullable"
    "org.jetbrains.annotations.Nullable"
    "org.jspecify.annotations.Nullable"})

(defn- annotation-qname [annotation]
  (or (some-> annotation .getAnnotationType qname)
      (some-> annotation .getAnnotationType .getSimpleName)
      (some-> annotation str (str/replace #"^@" ""))))

(defn- nullable-annotated? [element]
  (boolean
   (try
     (some nullable-annotations (map annotation-qname (.getAnnotations element)))
     (catch Throwable _
       false))))

(defn- nullable-type-ref? [^CtTypeReference type-ref]
  (boolean (and type-ref (nullable-annotated? type-ref))))

(defn- actual-type-arguments [^CtTypeReference type-ref]
  (if type-ref
    (vec (.getActualTypeArguments type-ref))
    []))

(defn- type-display-name [^CtTypeReference type-ref nullable?]
  (let [base-name (type-base-name type-ref)
        args (actual-type-arguments type-ref)]
    (when base-name
      (cond-> (if (empty? args)
                base-name
                (str base-name "<" (str/join "," (map type-id args)) ">"))
        nullable? (str "?")))))

(defn- type-id
  ([^CtTypeReference type-ref]
   (type-id type-ref (nullable-type-ref? type-ref)))
  ([^CtTypeReference type-ref nullable?]
   (type-display-name type-ref nullable?)))

(def ^:private built-in-types
  #{"boolean" "byte" "char" "double" "float" "int" "long" "short" "void"})

(def ^:private pseudo-types
  #{"var"})

(defn- type-declaration [^CtTypeReference type-ref]
  (when type-ref
    (try
      (.getTypeDeclaration type-ref)
      (catch Throwable _
        nil))))

(defn- strip-generic-suffix [type-name]
  (some-> type-name
          (str/replace #"<.*$" "")
          (str/replace #"\[\]$" "")))

(defn- package-root-match? [type-name package-root]
  (or (= type-name package-root)
      (str/starts-with? type-name (str package-root "."))))

(defn- classpath-type? [type-name]
  (let [type-name (strip-generic-suffix type-name)]
    (boolean
     (and (not (str/blank? (or type-name "")))
          (or (contains? *classpath-types* type-name)
              (some #(package-root-match? type-name %) *classpath-package-roots*))))))

(defn- type-reference-resolved? [^CtTypeReference type-ref]
  (let [id (type-id type-ref false)]
    (boolean (and type-ref
                  (or (contains? built-in-types id)
                      (contains? pseudo-types id)
                      (and (not (import-shadowed-type-ref? type-ref))
                           (or (type-declaration type-ref)
                               (classpath-type? id))))))))

(defn- type-fact
  ([^CtTypeReference type-ref]
   (type-fact type-ref (nullable-type-ref? type-ref)))
  ([^CtTypeReference type-ref nullable?]
   (when-let [id (type-id type-ref nullable?)]
    (let [args (actual-type-arguments type-ref)]
      (cond-> {:db/id id
               :type/id id
               :type/lang lang
               :type/name (or (type-base-name type-ref) id)
               :type/nullable? (boolean nullable?)}
        (seq args)
        (assoc :type/args
               (->> args
                    (keep-indexed (fn [ordinal arg]
                                    (when-let [arg-id (type-id arg)]
                                      {:type.arg/ordinal ordinal
                                       :type.arg/type arg-id})))
                    vec)))))))

(defn- erased-type-fact [^CtTypeReference type-ref]
  (let [id (type-id type-ref)
        base-name (type-base-name type-ref)
        args (actual-type-arguments type-ref)]
    (when (and (seq args) base-name (not= id base-name))
      {:db/id base-name
       :type/id base-name
       :type/lang lang
       :type/name base-name
       :type/nullable? false})))

(defn- type-reference-facts
  ([type-ref]
   (type-reference-facts type-ref (nullable-type-ref? type-ref)))
  ([type-ref nullable?]
   (when type-ref
    (let [args (actual-type-arguments type-ref)]
      (concat
       [(type-fact type-ref nullable?)
        (erased-type-fact type-ref)]
       (keep type-fact args)
       (mapcat type-reference-facts args))))))

(defn- source-type-fact [^CtType type]
  (when-let [id (qname type)]
    {:db/id id
     :type/id id
     :type/lang lang
     :type/name id
     :type/nullable? false}))

(defn- synthetic-type-fact [type-id]
  {:db/id type-id
   :type/id type-id
   :type/lang lang
   :type/name type-id
   :type/nullable? false})

(defn- known-java-api-type-facts []
  (->> (concat known-java-api-types
               (mapcat (juxt :ref/to-type :ref/owner-type)
                       (vals known-java-api-calls)))
       distinct
       (map synthetic-type-fact)))

(defn- node-kind [type]
  (cond
    (instance? CtAnnotationType type) :java.node/annotation
    (instance? CtRecord type) :java.node/record
    (instance? CtEnum type) :java.node/enum
    (instance? CtInterface type) :java.node/interface
    (instance? CtClass type) :java.node/class
    :else :java.node/type))

(defn- decl-kind [type]
  (cond
    (instance? CtAnnotationType type) :decl.kind/annotation
    (instance? CtRecord type) :decl.kind/record
    (instance? CtEnum type) :decl.kind/enum
    (instance? CtInterface type) :decl.kind/interface
    (instance? CtClass type) :decl.kind/class
    :else :decl.kind/type))

(defn- type-feature-kind [type]
  (cond
    (instance? CtAnnotationType type) :java.feature/annotation
    (instance? CtRecord type) :java.feature/record
    (instance? CtEnum type) :java.feature/enum
    (instance? CtInterface type) :java.feature/interface
    :else :java.feature/class))

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

(defn- type-node-id [file-id ^CtType type]
  (str file-id ":type:" (qname type)))

(defn- field-node-id [file-id ^CtField field]
  (str file-id ":field:" (qname (.getDeclaringType field)) "#" (.getSimpleName field)))

(defn- record-component-node-id [file-id record-type ^CtRecordComponent component]
  (str file-id ":record-component:" (qname record-type) "#" (.getSimpleName component)))

(defn- executable-signature [^CtExecutable executable]
  (cond
    (instance? CtConstructor executable) (str (qname (.getDeclaringType executable))
                                             "("
                                             (str/join "," (map #(qname (.getType %)) (.getParameters executable)))
                                             ")")
    (instance? CtMethod executable) (str (qname (.getDeclaringType executable))
                                        "#"
                                        (.getSimpleName executable)
                                        "("
                                        (str/join "," (map #(qname (.getType %)) (.getParameters executable)))
                                        ")")
    :else (.getSignature executable)))

(defn- executable-node-id [file-id executable]
  (str file-id
       (if (instance? CtConstructor executable) ":constructor:" ":method:")
       (executable-signature executable)))

(defn- executable-decl-id [executable]
  (str "java:" (executable-signature executable)))

(defn- executable-ref-decl-id [^CtExecutableReference executable-ref]
  (when-let [owner (some-> executable-ref .getDeclaringType qname)]
    (str "java:" owner "#" (.getSimpleName executable-ref) "("
         (str/join "," (map qname (.getParameters executable-ref)))
         ")")))

(defn- executable-ref-qname [^CtExecutableReference executable-ref]
  (when-let [owner (some-> executable-ref .getDeclaringType qname)]
    (str owner "." (.getSimpleName executable-ref))))

(defn- constructor-ref-decl-id [^CtExecutableReference executable-ref]
  (when-let [owner (some-> executable-ref .getDeclaringType qname)]
    (str "java:" owner "("
         (str/join "," (map qname (.getParameters executable-ref)))
         ")")))

(defn- field-ref-decl-id [^CtFieldReference field-ref]
  (when-let [owner (some-> field-ref .getDeclaringType qname)]
    (str "java:" owner "#field:" (.getSimpleName field-ref))))

(defn- node-fact [id kind name file-id ordinal element & {:keys [parent role value]}]
  (cond-> {:db/id id
           :node/id id
           :node/lang lang
           :node/kind kind
           :node/name name
           :node/file [:file/id file-id]
           :node/ordinal ordinal
           :node/source-hash (sha256 element)}
    parent (assoc :node/parent parent)
    role (assoc :node/role role)
    value (assoc :node/value value)
    true (merge (source-span element))))

(defn- formal-type-parameters [owner]
  (try
    (vec (.getFormalCtTypeParameters owner))
    (catch Throwable _
      [])))

(defn- type-param-name [type-param]
  (or (some-> type-param .getSimpleName)
      (qname type-param)
      (str type-param)))

(defn- type-param-facts [owner-id owner]
  (mapv (fn [ordinal type-param]
          (let [name (type-param-name type-param)
                id (str owner-id ":type-param:" ordinal ":" name)]
            {:db/id id
             :type-param/id id
             :type-param/ordinal ordinal
             :type-param/name name}))
        (range)
        (formal-type-parameters owner)))

(defn- type-ref-facts
  ([node-id role type-ref]
   (type-ref-facts node-id role type-ref nil))
  ([node-id role type-ref source-name]
   (type-ref-facts node-id role type-ref source-name (nullable-type-ref? type-ref)))
  ([node-id role type-ref source-name nullable?]
   (when-let [type-fact (type-fact type-ref nullable?)]
     (let [resolved? (type-reference-resolved? type-ref)]
       (concat
        (type-reference-facts type-ref nullable?)
        [(cond-> {:ref/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                  :db/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                  :ref/kind :ref.kind/type-use
                  :ref/from-node node-id
                  :ref/to-type (:type/id type-fact)
                  :ref/name (:type/name type-fact)
                  :ref/role role
                  :ref/resolved? resolved?}
           source-name (assoc :ref/source-name source-name)
           (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))])))))

(defn- inheritance-ref-facts [node-id kind type-ref]
  (when-let [type-fact (type-fact type-ref)]
    (let [resolved? (type-reference-resolved? type-ref)]
      (concat
       (type-reference-facts type-ref)
       [(cond-> {:ref/id (str node-id ":" (name kind) ":" (:type/id type-fact))
                 :db/id (str node-id ":" (name kind) ":" (:type/id type-fact))
                 :ref/kind kind
                 :ref/from-node node-id
                 :ref/to-type (:type/id type-fact)
                 :ref/name (:type/name type-fact)
                 :ref/resolved? resolved?}
          (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))]))))

(declare expression-facts statement-facts)

(defn- child-node-id [parent-node-id role ordinal element]
  (str parent-node-id ":" (name role) ":" ordinal ":" (sha256 element)))

(defn- literal-value [^CtLiteral literal]
  (let [value (.getValue literal)]
    (if (nil? value)
      "nil"
      (pr-str value))))

(defn- literal-name [^CtLiteral literal]
  (let [value (.getValue literal)]
    (if (nil? value)
      "null"
      (-> value class .getSimpleName))))

(defn- type-access-name [^CtTypeAccess type-access]
  (or (some-> type-access .getAccessedType qname)
      (some-> type-access .getAccessedType str)))

(defn- binary-operator-name [^CtBinaryOperator expression]
  (-> (.getKind expression) .name str/lower-case (str/replace "_" "-")))

(defn- unary-operator-name [^CtUnaryOperator expression]
  (-> (.getKind expression) .name str/lower-case (str/replace "_" "-")))

(defn- expression-kind [expression]
  (cond
    (instance? CtSwitchExpression expression) :java.node/switch-expression
    (instance? CtConditional expression) :java.node/conditional-expression
    (instance? CtLambda expression) :java.node/lambda
    (instance? CtExecutableReferenceExpression expression) :java.node/method-reference
    (instance? CtConstructorCall expression) :java.node/object-creation
    (instance? CtInvocation expression) :java.node/method-call
    (instance? CtAssignment expression) :java.node/assignment
    (instance? CtUnaryOperator expression) :java.node/unary-operator
    (instance? CtBinaryOperator expression) :java.node/binary-operator
    (instance? CtArrayRead expression) :java.node/array-read
    (instance? CtFieldWrite expression) :java.node/field-write
    (instance? CtFieldRead expression) :java.node/field-read
    (instance? CtVariableWrite expression) :java.node/variable-write
    (instance? CtVariableRead expression) :java.node/variable-read
    (instance? CtThisAccess expression) :java.node/this
    (instance? CtTypePattern expression) :java.node/type-pattern
    (instance? CtTypeAccess expression) :java.node/type-access
    (instance? CtLiteral expression) :java.node/literal
    :else :java.node/expression))

(defn- expression-name [expression]
  (cond
    (instance? CtSwitchExpression expression)
    "switch"

    (instance? CtConditional expression)
    "conditional"

    (instance? CtLambda expression)
    "lambda"

    (instance? CtExecutableReferenceExpression expression)
    (let [executable (.getExecutable ^CtExecutableReferenceExpression expression)]
      (if (.isConstructor executable)
        "new"
        (.getSimpleName executable)))

    (instance? CtInvocation expression)
    (.getSimpleName (.getExecutable ^CtInvocation expression))

    (instance? CtConstructorCall expression)
    (some-> expression .getType qname)

    (instance? CtAssignment expression)
    "assignment"

    (instance? CtBinaryOperator expression)
    (binary-operator-name expression)

    (instance? CtUnaryOperator expression)
    (unary-operator-name expression)

    (instance? CtFieldWrite expression)
    (.getSimpleName (.getVariable ^CtFieldWrite expression))

    (instance? CtFieldRead expression)
    (.getSimpleName (.getVariable ^CtFieldRead expression))

    (instance? CtVariableWrite expression)
    (.getSimpleName (.getVariable ^CtVariableWrite expression))

    (instance? CtVariableRead expression)
    (.getSimpleName (.getVariable ^CtVariableRead expression))

    (instance? CtThisAccess expression)
    "this"

    (instance? CtTypePattern expression)
    (some-> ^CtTypePattern expression .getVariable .getSimpleName)

    (instance? CtTypeAccess expression)
    (type-access-name expression)

    (instance? CtLiteral expression)
    (literal-name expression)

    :else
    (some-> expression class .getSimpleName)))

(defn- expression-value [expression]
  (cond
    (instance? CtLiteral expression) (literal-value expression)
    (instance? CtConditional expression) "?:"
    (instance? CtLambda expression) (->> (.getParameters ^CtLambda expression)
                                         (map #(.getSimpleName ^CtParameter %))
                                         (str/join ","))
    (instance? CtExecutableReferenceExpression expression)
    (some-> ^CtExecutableReferenceExpression expression .getExecutable .getDeclaringType type-id)
    (instance? CtConstructorCall expression) (some-> expression .getType type-id)
    (instance? CtAssignment expression) "="
    (instance? CtUnaryOperator expression) (unary-operator-name expression)
    (instance? CtBinaryOperator expression) (binary-operator-name expression)
    (instance? CtTypePattern expression) (some-> ^CtTypePattern expression .getVariable .getType type-id)
    (instance? CtTypeAccess expression) (type-access-name expression)
    :else nil))

(defn- case-kind-name [^CtCase case]
  (some-> case .getCaseKind .name str/lower-case))

(defn- yield-expression [statement]
  (cond
    (instance? CtYieldStatement statement)
    (.getExpression ^CtYieldStatement statement)

    (instance? CtExpression statement)
    statement

    :else nil))

(defn- switch-case-result [statements]
  (some (fn [statement]
          (if-let [expression (yield-expression statement)]
            [:expression expression]
            [:statement statement]))
        statements))

(defn- type-facts [file-id ordinal ^CtType type]
  (let [node-id (type-node-id file-id type)
        decl-id (str "java:" (qname type))
        type-id (qname type)
        superclass (.getSuperclass type)
        interfaces (.getSuperInterfaces type)
        type-params (type-param-facts decl-id type)]
    (concat
     [(source-type-fact type)
      (node-fact node-id (node-kind type) (.getSimpleName type) file-id ordinal type)
      (cond-> {:db/id decl-id
               :decl/id decl-id
               :decl/lang lang
               :decl/kind (decl-kind type)
               :decl/name (.getSimpleName type)
               :decl/qualified-name (qname type)
               :decl/source-node node-id
               :decl/type type-id}
        (seq (modifiers type)) (assoc :decl/modifiers (modifiers type))
        (seq type-params) (assoc :decl/type-params (mapv :db/id type-params)))
      (supported-feature (str node-id ":feature:" (name (type-feature-kind type)))
                         (type-feature-kind type)
                         node-id)]
     type-params
     (when superclass
       (inheritance-ref-facts node-id :ref.kind/extends superclass))
     (mapcat #(inheritance-ref-facts node-id :ref.kind/implements %) interfaces)
     (when (.getDeclaringType type)
       [(supported-feature (str node-id ":feature:inner-class")
                           :java.feature/inner-class
                           node-id)]))))

(defn- field-facts [file-id parent-node-id ordinal ^CtField field]
  (let [node-id (field-node-id file-id field)
        decl-id (str "java:" (qname (.getDeclaringType field)) "#field:" (.getSimpleName field))
        field-type (.getType field)
        nullable? (or (nullable-type-ref? field-type)
                      (nullable-annotated? field))]
    (concat
     [(node-fact node-id :java.node/field (.getSimpleName field) file-id ordinal field
                 :parent parent-node-id)
      (cond-> {:db/id decl-id
               :decl/id decl-id
               :decl/lang lang
               :decl/kind :decl.kind/field
               :decl/name (.getSimpleName field)
               :decl/qualified-name (str (qname (.getDeclaringType field)) "." (.getSimpleName field))
               :decl/source-node node-id}
        field-type (assoc :decl/type (type-id field-type nullable?))
        (seq (modifiers field)) (assoc :decl/modifiers (modifiers field)))
      (supported-feature (str node-id ":feature:field") :java.feature/field node-id)]
     (type-ref-facts node-id :field-type field-type nil nullable?)
     (expression-facts file-id node-id :initializer 0 (.getDefaultExpression field))
     (when (package-private? field)
       [(supported-feature (str node-id ":feature:package-private-member")
                           :java.feature/package-private-member
                           node-id)]))))

(defn- record-component-facts [file-id parent-node-id ordinal record-type ^CtRecordComponent component]
  (let [node-id (record-component-node-id file-id record-type component)
        decl-id (str "java:" (qname record-type) "#component:" (.getSimpleName component))
        component-type (.getType component)]
    (concat
     [(node-fact node-id :java.node/record-component (.getSimpleName component) file-id ordinal component
                 :parent parent-node-id)
      (cond-> {:db/id decl-id
               :decl/id decl-id
               :decl/lang lang
               :decl/kind :decl.kind/record-component
               :decl/name (.getSimpleName component)
               :decl/qualified-name (str (qname record-type) "." (.getSimpleName component))
               :decl/source-node node-id}
        component-type (assoc :decl/type (type-id component-type)))
      (supported-feature (str node-id ":feature:record-component")
                         :java.feature/record-component
                         node-id)]
     (type-ref-facts node-id :component-type component-type (.getSimpleName component)))))

(defn- executable-feature-facts [node-id executable]
  (concat
   (when (package-private? executable)
     [(supported-feature (str node-id ":feature:package-private-member")
                         :java.feature/package-private-member
                         node-id)])
   (when (has-modifier? executable :synchronized)
     [(supported-feature (str node-id ":feature:synchronized-method")
                         :java.feature/synchronized-method
                         node-id)])
   (when (has-modifier? executable :native)
     [(unsupported-feature (str node-id ":feature:native-method")
                           :java.feature/native-method
                           node-id
                           :feature.severity/hard)])
   (when (seq (.getThrownTypes executable))
     [(supported-feature (str node-id ":feature:checked-exception")
                         :java.feature/checked-exception
                         node-id)])
   (when (and (instance? CtMethod executable) (seq (.getFormalCtTypeParameters executable)))
     [(supported-feature (str node-id ":feature:generic-method")
                         :java.feature/generic-method
                         node-id)])))

(defn- executable-facts [file-id parent-node-id ordinal executable]
  (let [node-id (executable-node-id file-id executable)
        decl-id (executable-decl-id executable)
        return-type (when (instance? CtMethod executable) (.getType ^CtMethod executable))
        return-nullable? (or (nullable-type-ref? return-type)
                             (nullable-annotated? executable))
        params (.getParameters executable)
        type-params (type-param-facts decl-id executable)]
    (concat
     [(node-fact node-id
                 (if (instance? CtConstructor executable) :java.node/constructor :java.node/method)
                 (.getSimpleName executable)
                 file-id
                 ordinal
                 executable
                 :parent parent-node-id)
      (cond-> {:db/id decl-id
               :decl/id decl-id
               :decl/lang lang
               :decl/kind (if (instance? CtConstructor executable)
                            :decl.kind/constructor
                            :decl.kind/method)
               :decl/name (.getSimpleName executable)
               :decl/qualified-name (if (instance? CtConstructor executable)
                                      (qname (.getDeclaringType executable))
                                      (str (qname (.getDeclaringType executable)) "." (.getSimpleName executable)))
               :decl/source-node node-id}
        return-type (assoc :decl/return-type (type-id return-type return-nullable?))
        (instance? CtConstructor executable) (assoc :decl/type (qname (.getDeclaringType executable)))
        (seq (modifiers executable)) (assoc :decl/modifiers (modifiers executable))
        (seq type-params) (assoc :decl/type-params (mapv :db/id type-params)))]
     type-params
     (when return-type
       (type-ref-facts node-id :return-type return-type nil return-nullable?))
     (mapcat (fn [index param]
               (let [param-type (.getType param)
                     nullable? (or (nullable-type-ref? param-type)
                                   (nullable-annotated? param))]
                 (type-ref-facts node-id
                                 (keyword (str "param-" index))
                                 param-type
                                 (.getSimpleName param)
                                 nullable?)))
             (range)
             params)
     (mapcat #(type-ref-facts node-id :throws %) (.getThrownTypes executable))
     (executable-feature-facts node-id executable))))

(defn- constructor-invocation? [^CtInvocation invocation]
  (= "<init>" (.getSimpleName (.getExecutable invocation))))

(def supported-reflection-features
  {["java.lang.Class" "getTypeName"] :java.reflection.class/get-type-name
   ["java.lang.Class" "getName"] :java.reflection.class/get-name
   ["java.lang.Class" "getSimpleName"] :java.reflection.class/get-simple-name
   ["java.lang.Class" "getModifiers"] :java.reflection.class/get-modifiers
   ["java.lang.Class" "isAssignableFrom"] :java.reflection.class/is-assignable-from
   ["java.lang.Class" "isArray"] :java.reflection.class/is-array
   ["java.lang.Class" "isPrimitive"] :java.reflection.class/is-primitive
   ["java.lang.Class" "getGenericSuperclass"] :java.reflection.class/get-generic-superclass
   ["java.lang.Class" "getTypeParameters"] :java.reflection.class/get-type-parameters
   ["java.lang.Class" "getComponentType"] :java.reflection.class/get-component-type
   ["java.lang.Class" "isEnum"] :java.reflection.class/is-enum
   ["java.lang.Class" "getClassLoader"] :java.reflection.class/get-class-loader
   ["java.lang.Class" "cast"] :java.reflection.class/cast
   ["java.lang.Class" "getResourceAsStream"] :java.reflection.class/get-resource-as-stream
   ["java.lang.Class" "getDeclaredMethods"] :java.reflection.class/get-declared-methods
   ["java.lang.Class" "getDeclaredConstructors"] :java.reflection.class/get-declared-constructors
   ["java.lang.Class" "forName"] :java.reflection.class/for-name
   ["java.lang.Class" "getAnnotation"] :java.reflection.class/get-annotation
   ["java.lang.reflect.Type" "getTypeName"] :java.reflection.type/get-type-name
   ["java.lang.reflect.ParameterizedType" "getActualTypeArguments"] :java.reflection.parameterized-type/get-actual-type-arguments
   ["java.lang.reflect.ParameterizedType" "getRawType"] :java.reflection.parameterized-type/get-raw-type
   ["java.lang.reflect.ParameterizedType" "getOwnerType"] :java.reflection.parameterized-type/get-owner-type
   ["java.lang.reflect.WildcardType" "getLowerBounds"] :java.reflection.wildcard-type/get-lower-bounds
   ["java.lang.reflect.WildcardType" "getUpperBounds"] :java.reflection.wildcard-type/get-upper-bounds
   ["java.lang.reflect.Constructor" "getParameterCount"] :java.reflection.constructor/get-parameter-count
   ["java.lang.reflect.Constructor" "getParameters"] :java.reflection.executable/get-parameters
   ["java.lang.reflect.Executable" "getParameters"] :java.reflection.executable/get-parameters
   ["java.lang.reflect.Parameter" "isNamePresent"] :java.reflection.parameter/is-name-present
   ["java.lang.reflect.Parameter" "getName"] :java.reflection.parameter/get-name
   ["java.lang.reflect.Modifier" "isAbstract"] :java.reflection.modifier/is-abstract
   ["java.lang.reflect.Constructor" "getAnnotation"] :java.reflection.constructor/get-annotation
   ["java.lang.reflect.Parameter" "getAnnotation"] :java.reflection.parameter/get-annotation
   ["java.lang.reflect.Method" "invoke"] :java.reflection.method/invoke})

(def unsupported-reflection-features
  {["java.lang.Class" "getMethod"] :java.reflection.class/get-method
   ["java.lang.Class" "getDeclaredMethod"] :java.reflection.class/get-declared-method
   ["java.lang.reflect.Constructor" "newInstance"] :java.reflection.constructor/new-instance})

(defn- reflection-owner? [owner]
  (or (= "java.lang.Class" owner)
      (some-> owner (str/starts-with? "java.lang.reflect"))))

(def supported-stream-features
  {"stream" :java.stream/source-to-enumerable
   "map" :java.stream/map
   "filter" :java.stream/filter
   "flatMap" :java.stream/flat-map
   "mapToInt" :java.stream/map-to-int
   "mapToLong" :java.stream/map-to-long
   "toList" :java.stream/to-list
   "toArray" :java.stream/to-array
   "count" :java.stream/count
   "sum" :java.stream/sum
   "min" :java.stream/min
   "max" :java.stream/max
   "skip" :java.stream/skip
   "anyMatch" :java.stream/any-match
   "allMatch" :java.stream/all-match
   "noneMatch" :java.stream/none-match
   "findFirst" :java.stream/find-first
   "distinct" :java.stream/distinct
   "sorted" :java.stream/sorted
   "iterator" :java.stream/iterator})

(def unsupported-stream-features
  {"collect" :java.stream/collect})

(def supported-collector-features
  {"toList" :java.stream.collector/to-list
   "toSet" :java.stream.collector/to-set
   "joining" :java.stream.collector/joining
   "toMap" :java.stream.collector/to-map
   "toCollection" :java.stream.collector/to-collection})

(def unsupported-collector-features
  {})

(def supported-optional-features
  {["java.util.Optional" "orElse"] :java.optional/or-else
   ["java.util.OptionalInt" "orElse"] :java.optional/or-else
   ["java.util.OptionalLong" "orElse"] :java.optional/or-else
   ["java.util.OptionalDouble" "orElse"] :java.optional/or-else})

(def java-list-owners
  #{"java.util.List"
    "java.util.ArrayList"
    "java.util.LinkedList"})

(def java-set-owners
  #{"java.util.Set"
    "java.util.HashSet"
    "java.util.LinkedHashSet"})

(def java-map-owners
  #{"java.util.Map"
    "java.util.HashMap"
    "java.util.LinkedHashMap"
    "java.util.TreeMap"})

(def java-map-entry-owners
  #{"java.util.Map$Entry"
    "java.util.Map.Entry"})

(defn- java-list-owner? [owner]
  (contains? java-list-owners owner))

(defn- java-set-owner? [owner]
  (contains? java-set-owners owner))

(defn- java-map-owner? [owner]
  (contains? java-map-owners owner))

(defn- java-collection-owner? [owner]
  (or (java-list-owner? owner)
      (java-set-owner? owner)
      (java-map-owner? owner)
      (= "java.util.Collection" owner)))

(defn- java-map-entry-owner? [owner]
  (contains? java-map-entry-owners owner))

(defn- collection-feature-kind [owner name]
  (cond
    (and (java-collection-owner? owner) (= "size" name)) :java.collection/size
    (and (java-collection-owner? owner) (= "isEmpty" name)) :java.collection/is-empty
    (and (or (java-list-owner? owner) (java-set-owner? owner) (= "java.util.Collection" owner))
         (= "contains" name)) :java.collection/contains
    (and (or (java-list-owner? owner) (java-set-owner? owner) (= "java.util.Collection" owner))
         (= "add" name)) :java.collection/add
    (and (java-list-owner? owner) (= "get" name)) :java.list/get
    (and (java-map-owner? owner) (= "get" name)) :java.map/get
    (and (java-map-owner? owner) (= "put" name)) :java.map/put
    (and (java-map-owner? owner) (= "getOrDefault" name)) :java.map/get-or-default
    (and (java-map-owner? owner) (= "containsKey" name)) :java.map/contains-key
    (and (java-map-owner? owner) (= "containsValue" name)) :java.map/contains-value
    (and (java-map-owner? owner) (= "entrySet" name)) :java.map/entry-set
    (and (java-map-owner? owner) (= "keySet" name)) :java.map/key-set
    (and (java-map-owner? owner) (= "values" name)) :java.map/values
    (and (java-map-entry-owner? owner) (= "getKey" name)) :java.map-entry/get-key
    (and (java-map-entry-owner? owner) (= "getValue" name)) :java.map-entry/get-value))

(defn- stream-owner? [owner]
  (some-> owner (str/starts-with? "java.util.stream")))

(defn- collector-owner? [owner]
  (= "java.util.stream.Collectors" owner))

(defn- java-iterator-owner? [owner]
  (or (= "java.util.Iterator" owner)
      (some-> owner (str/starts-with? "java.util.PrimitiveIterator"))))

(defn- java-primitive-int-iterator-owner? [owner]
  (contains? #{"java.util.PrimitiveIterator$OfInt"
               "java.util.PrimitiveIterator.OfInt"}
             owner))

(defn- collector-invocation-name [expression]
  (when (instance? CtInvocation expression)
    (let [executable-ref (.getExecutable ^CtInvocation expression)
          owner (some-> executable-ref .getDeclaringType qname)]
      (when (collector-owner? owner)
        (.getSimpleName executable-ref)))))

(defn- collector-feature-kind [expression]
  (some-> expression collector-invocation-name supported-collector-features))

(defn- unsupported-collector-feature-kind [expression]
  (some-> expression collector-invocation-name unsupported-collector-features))

(def collect-feature-by-collector
  {:java.stream.collector/to-list :java.stream/collect-to-list
   :java.stream.collector/to-set :java.stream/collect-to-set
   :java.stream.collector/joining :java.stream/collect-joining
   :java.stream.collector/to-map :java.stream/collect-to-map
   :java.stream.collector/to-collection :java.stream/collect-to-collection})

(defn- invocation-feature-facts [node-id ^CtInvocation invocation]
  (let [executable-ref (.getExecutable invocation)
        owner (some-> executable-ref .getDeclaringType qname)
        name (.getSimpleName executable-ref)
        collect-collector-feature (when (and (stream-owner? owner)
                                             (= "collect" name)
                                             (= 1 (count (.getArguments invocation))))
                                    (or (collector-feature-kind (first (.getArguments invocation)))
                                        (unsupported-collector-feature-kind (first (.getArguments invocation)))))
        collect-feature (get collect-feature-by-collector collect-collector-feature)
        collect-supported? (contains? (set (vals supported-collector-features)) collect-collector-feature)]
    (concat
     (when (or (= "java.lang.Class" owner)
               (some-> owner (str/starts-with? "java.lang.reflect"))
               (= "forName" name))
       (cond
         (contains? supported-reflection-features [owner name])
         [(supported-feature (str node-id ":feature:" (clojure.core/name (get supported-reflection-features [owner name])))
                             (get supported-reflection-features [owner name])
                             node-id)]

         (contains? unsupported-reflection-features [owner name])
         [(unsupported-feature (str node-id ":feature:" (clojure.core/name (get unsupported-reflection-features [owner name])))
                               (get unsupported-reflection-features [owner name])
                               node-id
                               :feature.severity/hard)]

         (or (reflection-owner? owner) (= "forName" name))
         [(unsupported-feature (str node-id ":feature:reflection")
                               :java.feature/reflection
                               node-id
                               :feature.severity/hard)]))
     (when-let [feature-kind (and (or (stream-owner? owner)
                                      (= "stream" name))
                                  (get supported-stream-features name))]
       [(supported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                           feature-kind
                           node-id)])
     (when-let [feature-kind (get supported-optional-features [owner name])]
       [(supported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                           feature-kind
                           node-id)])
     (when-let [feature-kind (collection-feature-kind owner name)]
       [(supported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                           feature-kind
                           node-id)])
     (when (and collect-feature collect-supported?)
       [(supported-feature (str node-id ":feature:" (clojure.core/name collect-feature))
                           collect-feature
                           node-id)])
     (when (and collect-feature (not collect-supported?))
       [(unsupported-feature (str node-id ":feature:" (clojure.core/name collect-feature))
                             collect-feature
                             node-id
                             :feature.severity/medium)])
     (when-let [feature-kind (and (stream-owner? owner)
                                  (not collect-feature)
                                  (get unsupported-stream-features name))]
       [(unsupported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                             feature-kind
                             node-id
                             :feature.severity/medium)])
     (when-let [feature-kind (and (collector-owner? owner)
                                  (get supported-collector-features name))]
       [(supported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                           feature-kind
                           node-id)])
     (when-let [feature-kind (and (collector-owner? owner)
                                  (get unsupported-collector-features name))]
       [(unsupported-feature (str node-id ":feature:" (clojure.core/name feature-kind))
                             feature-kind
                             node-id
                             :feature.severity/medium)])
     (when (and (or (stream-owner? owner)
                    (collector-owner? owner)
                    (= "stream" name))
                (not (contains? supported-stream-features name))
                (not (contains? unsupported-stream-features name))
                (not (contains? supported-collector-features name))
                (not (contains? unsupported-collector-features name)))
       [(unsupported-feature (str node-id ":feature:stream-api")
                             :java.feature/stream-api
                             node-id
                             :feature.severity/medium)])
     (when (and (= "java.util.Objects" owner)
                (= "requireNonNull" name))
       [(supported-feature (str node-id ":feature:objects-require-non-null")
                           :java.api/objects-require-non-null
                           node-id)])
     (when (and (= "java.util.Objects" owner)
                (= "equals" name))
       [(supported-feature (str node-id ":feature:objects-equals")
                           :java.api/objects-equals
                           node-id)])
     (when (and (= "java.util.Objects" owner)
                (= "hash" name))
       [(supported-feature (str node-id ":feature:objects-hash")
                           :java.api/objects-hash
                           node-id)])
     (when (and (= "java.lang.Math" owner)
                (= "round" name))
       [(supported-feature (str node-id ":feature:math-round")
                           :java.api/math-round
                           node-id)])
     (when (and (= "java.lang.Math" owner)
                (= "min" name))
       [(supported-feature (str node-id ":feature:math-min")
                           :java.api/math-min
                           node-id)])
     (when (and (= "java.lang.Math" owner)
                (= "max" name))
       [(supported-feature (str node-id ":feature:math-max")
                           :java.api/math-max
                           node-id)])
     (when (and (= "java.lang.String" owner)
                (= "length" name))
       [(supported-feature (str node-id ":feature:string-length")
                           :java.api/string-length
                           node-id)])
     (when (and (= "java.lang.String" owner)
                (= "codePoints" name))
       [(supported-feature (str node-id ":feature:string-code-points")
                           :java.api/string-code-points
                           node-id)])
     (when (and (java-iterator-owner? owner)
                (= "hasNext" name))
       [(supported-feature (str node-id ":feature:iterator-has-next")
                           :java.iterator/has-next
                           node-id)])
     (when (and (java-primitive-int-iterator-owner? owner)
                (= "nextInt" name))
       [(supported-feature (str node-id ":feature:primitive-iterator-next-int")
                           :java.primitive-iterator/next-int
                           node-id)])
     (when (and (= "java.lang.Double" owner)
                (= "hashCode" name))
       [(supported-feature (str node-id ":feature:double-hash-code")
                           :java.api/double-hash-code
                           node-id)]))))

(defn- invocation-reference-facts [node-id ^CtInvocation invocation]
  (let [executable-ref (.getExecutable invocation)
        target-decl-id (executable-ref-decl-id executable-ref)
        return-type (.getType invocation)
        owner-type (some-> executable-ref .getDeclaringType)
        resolved? (boolean (and target-decl-id
                                (or (nil? owner-type)
                                     (type-reference-resolved? owner-type))))]
    (concat
     (when return-type (type-reference-facts return-type))
     (when owner-type (type-reference-facts owner-type))
     (when resolved?
       [(cond-> {:db/id target-decl-id
                 :decl/id target-decl-id
                 :decl/lang lang
                 :decl/kind :decl.kind/method
                 :decl/name (.getSimpleName executable-ref)
                 :decl/qualified-name (executable-ref-qname executable-ref)}
          return-type (assoc :decl/return-type (type-id return-type)))])
     [(cond-> {:db/id (str node-id ":ref")
               :ref/id (str node-id ":ref")
               :ref/kind :ref.kind/method-call
               :ref/from-node node-id
               :ref/name (.getSimpleName executable-ref)
               :ref/resolved? resolved?}
        resolved? (assoc :ref/to-decl target-decl-id)
        return-type (assoc :ref/to-type (type-id return-type))
        owner-type (assoc :ref/owner-type (type-id owner-type))
        (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))]
     (invocation-feature-facts node-id invocation))))

(defn- constructor-call-reference-facts [node-id ^CtConstructorCall constructor-call]
  (let [executable-ref (.getExecutable constructor-call)
        target-decl-id (constructor-ref-decl-id executable-ref)
        object-type (.getType constructor-call)
        resolved? (boolean (and target-decl-id object-type))]
    (concat
     (when object-type (type-reference-facts object-type))
     (when resolved?
       [(cond-> {:db/id target-decl-id
                 :decl/id target-decl-id
                 :decl/lang lang
                 :decl/kind :decl.kind/constructor
                 :decl/name (some-> object-type type-base-name)
                 :decl/qualified-name (some-> object-type type-base-name)}
          object-type (assoc :decl/type (type-id object-type)))])
     [(cond-> {:db/id (str node-id ":ref")
               :ref/id (str node-id ":ref")
               :ref/kind :ref.kind/constructor-call
               :ref/from-node node-id
               :ref/name (or (some-> object-type type-base-name) "<init>")
               :ref/resolved? resolved?}
        resolved? (assoc :ref/to-decl target-decl-id)
        object-type (assoc :ref/to-type (type-id object-type)
                           :ref/owner-type (type-id object-type))
        (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))])))

(defn- method-reference-facts [node-id ^CtExecutableReferenceExpression expression]
  (let [executable-ref (.getExecutable expression)
        owner-type (.getDeclaringType executable-ref)
        return-type (.getType executable-ref)]
    (concat
     (when owner-type (type-ref-facts node-id :method-reference-target-type owner-type))
     (when return-type (type-ref-facts node-id :method-reference-return-type return-type)))))

(defn- field-reference-facts [node-id field-access]
  (let [field-ref (.getVariable field-access)
        target-decl-id (field-ref-decl-id field-ref)
        field-type (.getType field-ref)
        owner-type (.getDeclaringType field-ref)
        resolved? (boolean (and target-decl-id
                                owner-type
                                (type-reference-resolved? owner-type)))
        field-name (.getSimpleName field-ref)
        class-literal? (and (= "class" field-name)
                            field-type
                            (= "java.lang.Class" (type-base-name field-type)))]
    (concat
     (when field-type (type-reference-facts field-type))
     (when owner-type (type-reference-facts owner-type))
     (when resolved?
       [(cond-> {:db/id target-decl-id
                 :decl/id target-decl-id
                 :decl/lang lang
                 :decl/kind :decl.kind/field
                 :decl/name (.getSimpleName field-ref)
                 :decl/qualified-name (str (qname owner-type) "." (.getSimpleName field-ref))}
          field-type (assoc :decl/type (type-id field-type)))])
     [(cond-> {:db/id (str node-id ":ref")
               :ref/id (str node-id ":ref")
               :ref/kind :ref.kind/field-access
               :ref/from-node node-id
               :ref/name field-name
               :ref/resolved? resolved?}
        resolved? (assoc :ref/to-decl target-decl-id)
        field-type (assoc :ref/to-type (type-id field-type))
        owner-type (assoc :ref/owner-type (type-id owner-type))
        (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))]
     (when class-literal?
       [(supported-feature (str node-id ":feature:class-type-literal")
                           :java.reflection.class/type-literal
                           node-id)]))))

(defn- targeted-expression-target [expression]
  (when (instance? CtTargetedExpression expression)
    (.getTarget ^CtTargetedExpression expression)))

(defn- expression-children [expression]
  (cond
    (instance? CtSwitchExpression expression)
    [[:selector 0 (.getSelector ^CtSwitchExpression expression)]]

    (instance? CtLambda expression)
    (when-let [body-expression (.getExpression ^CtLambda expression)]
      [[:body-expression 0 body-expression]])

    (instance? CtConstructorCall expression)
    (map-indexed (fn [index arg] [:argument index arg])
                 (.getArguments ^CtConstructorCall expression))

    (instance? CtConditional expression)
    [[:condition 0 (.getCondition ^CtConditional expression)]
     [:then-expression 1 (.getThenExpression ^CtConditional expression)]
     [:else-expression 2 (.getElseExpression ^CtConditional expression)]]

    (instance? CtInvocation expression)
    (let [target (targeted-expression-target expression)
          args (.getArguments ^CtInvocation expression)]
      (concat
       (when target [[:target 0 target]])
       (map-indexed (fn [index arg] [:argument index arg]) args)))

    (instance? CtBinaryOperator expression)
    [[:left 0 (.getLeftHandOperand ^CtBinaryOperator expression)]
     [:right 1 (.getRightHandOperand ^CtBinaryOperator expression)]]

    (instance? CtUnaryOperator expression)
    [[:operand 0 (.getOperand ^CtUnaryOperator expression)]]

    (instance? CtAssignment expression)
    [[:left 0 (.getAssigned ^CtAssignment expression)]
     [:right 1 (.getAssignment ^CtAssignment expression)]]

    (instance? CtArrayRead expression)
    [[:target 0 (.getTarget ^CtArrayRead expression)]
     [:index 1 (.getIndexExpression ^CtArrayRead expression)]]

    (instance? CtFieldRead expression)
    (when-let [target (targeted-expression-target expression)]
      [[:target 0 target]])

    (instance? CtFieldWrite expression)
    (when-let [target (targeted-expression-target expression)]
      [[:target 0 target]])

    :else
    []))

(defn- switch-case-facts [file-id parent-node-id ordinal ^CtCase case]
  (let [node-id (child-node-id parent-node-id :case ordinal case)
        [result-kind result] (switch-case-result (.getStatements case))]
    (concat
     [(node-fact node-id
                 :java.node/switch-case
                 (if (or (.getIncludesDefault case)
                         (empty? (.getCaseExpressions case)))
                   "default"
                   "case")
                 file-id
                 ordinal
                 case
                 :parent parent-node-id
                 :role :case
                 :value (case-kind-name case))]
     (mapcat (fn [index label]
               (expression-facts file-id node-id :case-label index label))
             (range)
             (.getCaseExpressions case))
     (cond
       (= :expression result-kind) (expression-facts file-id node-id :case-result 0 result)
       (= :statement result-kind) (statement-facts file-id node-id :case-result 0 result)))))

(defn- expression-type-casts [^CtExpression expression]
  (seq (.getTypeCasts expression)))

(defn- expression-facts
  ([file-id parent-node-id role ordinal expression]
   (expression-facts file-id parent-node-id role ordinal expression true))
  ([file-id parent-node-id role ordinal ^CtExpression expression wrap-casts?]
   (when expression
     (let [node-id (child-node-id parent-node-id role ordinal expression)
           type-casts (expression-type-casts expression)]
       (if (and wrap-casts? type-casts)
         (let [cast-type (first type-casts)]
           (concat
            [(node-fact node-id
                        :java.node/type-cast
                        "cast"
                        file-id
                        ordinal
                        expression
                        :parent parent-node-id
                        :role role
                        :value (type-id cast-type))]
            (type-ref-facts node-id :cast-type cast-type)
            (expression-facts file-id node-id :operand 0 expression false)))
         (concat
          [(node-fact node-id
                      (expression-kind expression)
                      (expression-name expression)
                      file-id
                      ordinal
                      expression
                      :parent parent-node-id
                      :role role
                      :value (expression-value expression))]
          (when (instance? CtInvocation expression)
            (invocation-reference-facts node-id expression))
          (when (instance? CtConstructorCall expression)
            (constructor-call-reference-facts node-id expression))
          (when (instance? CtExecutableReferenceExpression expression)
            (method-reference-facts node-id expression))
          (when (or (instance? CtFieldRead expression)
                    (instance? CtFieldWrite expression))
            (field-reference-facts node-id expression))
          (when (instance? CtTypePattern expression)
            (let [variable (.getVariable ^CtTypePattern expression)]
              (type-ref-facts node-id :pattern-type (.getType variable) (.getSimpleName variable))))
          (mapcat (fn [[child-role child-ordinal child-expression]]
                    (expression-facts file-id node-id child-role child-ordinal child-expression))
                  (expression-children expression))
          (when (instance? CtSwitchExpression expression)
            (mapcat (fn [case-ordinal case]
                      (switch-case-facts file-id node-id case-ordinal case))
                    (range)
                    (.getCases ^CtSwitchExpression expression)))))))))

(defn- branch-statements [statement]
  (cond
    (nil? statement) []
    (instance? CtBlock statement) (.getStatements ^CtBlock statement)
    :else [statement]))

(defn- catch-clause-facts [file-id parent-node-id ordinal ^CtCatch catch-clause]
  (let [parameter (.getParameter catch-clause)
        node-id (child-node-id parent-node-id :catch ordinal catch-clause)
        body (.getBody catch-clause)]
    (concat
     [(node-fact node-id
                 :java.node/catch-clause
                 (or (some-> parameter .getSimpleName) "catch")
                 file-id
                 ordinal
                 catch-clause
                 :parent parent-node-id
                 :role :catch)]
     (type-ref-facts node-id :catch-type (some-> parameter .getType) (some-> parameter .getSimpleName))
     (mapcat (fn [index body-statement]
               (statement-facts file-id node-id :body index body-statement))
             (range)
             (if body (.getStatements body) [])))))

(defn- statement-facts [file-id parent-node-id role ordinal ^CtStatement statement]
  (when statement
    (let [node-id (child-node-id parent-node-id role ordinal statement)]
      (cond
        (instance? CtAssignment statement)
        (concat
         [(node-fact node-id
                     :java.node/assignment
                     "assignment"
                     file-id
                     ordinal
                     statement
                     :parent parent-node-id
                     :role role
                     :value "=")]
         (expression-facts file-id node-id :left 0 (.getAssigned ^CtAssignment statement))
         (expression-facts file-id node-id :right 1 (.getAssignment ^CtAssignment statement)))

        (instance? CtLocalVariable statement)
        (let [local ^CtLocalVariable statement
              default-expression (.getDefaultExpression local)
              local-type (.getType local)]
          (concat
           [(node-fact node-id
                       :java.node/local-variable
                       (.getSimpleName local)
                       file-id
                       ordinal
                       statement
                       :parent parent-node-id
                       :role role)]
           (type-ref-facts node-id :local-type local-type (.getSimpleName local))
           (expression-facts file-id node-id :initializer 0 default-expression)))

        (instance? CtSynchronized statement)
        (let [synchronized-statement ^CtSynchronized statement
              block (.getBlock synchronized-statement)]
          (concat
           [(node-fact node-id
                       :java.node/synchronized-block
                       "synchronized"
                       file-id
                       ordinal
                       statement
                       :parent parent-node-id
                       :role role)
            (supported-feature (str node-id ":feature:synchronized-block")
                               :java.feature/synchronized-block
                               node-id)]
           (expression-facts file-id node-id :lock 0 (.getExpression synchronized-statement))
           (mapcat (fn [index body-statement]
                     (statement-facts file-id node-id :body index body-statement))
                   (range)
                   (if block (.getStatements block) []))))

        (instance? CtForEach statement)
        (let [foreach-statement ^CtForEach statement
              variable (.getVariable foreach-statement)]
          (concat
           [(node-fact node-id
                       :java.node/foreach-statement
                       (or (some-> variable .getSimpleName) "foreach")
                       file-id
                       ordinal
                       statement
                       :parent parent-node-id
                       :role role
                       :value "foreach")]
           (type-ref-facts node-id :element-type (some-> variable .getType) (some-> variable .getSimpleName))
           (expression-facts file-id node-id :iterable 0 (.getExpression foreach-statement))
           (mapcat (fn [index body-statement]
                     (statement-facts file-id node-id :body index body-statement))
                   (range)
                   (branch-statements (.getBody foreach-statement)))))

        (instance? CtTry statement)
        (let [try-statement ^CtTry statement
              body (.getBody try-statement)
              finalizer (.getFinalizer try-statement)]
          (concat
           [(node-fact node-id
                       :java.node/try-statement
                       "try"
                       file-id
                       ordinal
                       statement
                       :parent parent-node-id
                       :role role)]
           (mapcat (fn [index body-statement]
                     (statement-facts file-id node-id :body index body-statement))
                   (range)
                   (if body (.getStatements body) []))
           (mapcat (fn [index catch-clause]
                     (catch-clause-facts file-id node-id index catch-clause))
                   (range)
                   (.getCatchers try-statement))
           (mapcat (fn [index finalizer-statement]
                     (statement-facts file-id node-id :finally index finalizer-statement))
                   (range)
                   (if finalizer (.getStatements finalizer) []))))

        (instance? CtReturn statement)
        (concat
         [(node-fact node-id
                     :java.node/return-statement
                     "return"
                     file-id
                     ordinal
                     statement
                     :parent parent-node-id
                     :role role)]
         (expression-facts file-id node-id :return-expression 0 (.getReturnedExpression ^CtReturn statement)))

        (instance? CtThrow statement)
        (concat
         [(node-fact node-id
                     :java.node/throw-statement
                     "throw"
                     file-id
                     ordinal
                     statement
                     :parent parent-node-id
                     :role role)]
         (expression-facts file-id node-id :thrown-expression 0 (.getThrownExpression ^CtThrow statement)))

        (instance? CtIf statement)
        (let [if-statement ^CtIf statement]
          (concat
           [(node-fact node-id
                       :java.node/if-statement
                       "if"
                       file-id
                       ordinal
                       statement
                       :parent parent-node-id
                       :role role)]
           (expression-facts file-id node-id :condition 0 (.getCondition if-statement))
           (mapcat (fn [index then-statement]
                     (statement-facts file-id node-id :then index then-statement))
                   (range)
                   (branch-statements (.getThenStatement if-statement)))
           (mapcat (fn [index else-statement]
                     (statement-facts file-id node-id :else index else-statement))
                   (range)
                   (branch-statements (.getElseStatement if-statement)))))

        (and (instance? CtInvocation statement)
             (constructor-invocation? statement))
        []

        (instance? CtInvocation statement)
        (expression-facts file-id parent-node-id role ordinal statement)

        :else
        [(node-fact node-id
                    :java.node/statement
                    (some-> statement class .getSimpleName)
                    file-id
                    ordinal
                    statement
                    :parent parent-node-id
                    :role role)]))))

(defn- executable-body-facts [file-id executable]
  (let [parent-node-id (executable-node-id file-id executable)
        body (when (instance? CtExecutable executable) (.getBody ^CtExecutable executable))]
    (mapcat (fn [ordinal statement]
              (statement-facts file-id parent-node-id :body ordinal statement))
            (range)
            (if body (.getStatements body) []))))

(defn- expression-feature-facts [file-id executable]
  (let [parent-node-id (executable-node-id file-id executable)]
    (map-indexed (fn [ordinal lambda]
                   (supported-feature (str parent-node-id ":lambda:" ordinal)
                                      :java.feature/lambda
                                      parent-node-id))
                 (.getElements executable (TypeFilter. CtLambda)))))

(defn- constructors [type]
  (if (instance? CtClass type)
    (.getConstructors ^CtClass type)
    []))

(defn- import-qname [import]
  (when-let [[_ qname] (re-matches #"\s*import\s+(?!static\s+)([\w.$]+)\s*;\s*" (str import))]
    (when-not (str/ends-with? qname ".*")
      qname)))

(defn- import-wildcard-package [import]
  (when-let [[_ package-name] (re-matches #"\s*import\s+(?!static\s+)([\w.$]+)\.\*;\s*" (str import))]
    package-name))

(defn- compilation-unit [^CtType type]
  (try
    (some-> type .getPosition .getCompilationUnit)
    (catch Throwable _
      nil)))

(defn- compilation-unit-imports [types]
  (->> types
       (keep compilation-unit)
       distinct
       (mapcat (fn [compilation-unit]
                 (try
                   (.getImports compilation-unit)
                   (catch Throwable _
                     []))))))

(defn- compilation-unit-import-aliases [types]
  (->> (compilation-unit-imports types)
       (keep import-qname)
       (reduce (fn [aliases qname]
                 (assoc aliases (last (str/split qname #"\.")) qname))
               {})))

(defn- compilation-unit-wildcard-imports [types]
  (->> (compilation-unit-imports types)
       (keep import-wildcard-package)
       distinct
       sort
       vec))

(defn- record-components [type]
  (if (instance? CtRecord type)
    (vec (.getRecordComponents ^CtRecord type))
    []))

(defn- record-accessor-method? [component-names ^CtMethod method]
  (and (contains? component-names (.getSimpleName method))
       (empty? (.getParameters method))))

(defn- nested-types [^CtType type]
  (try
    (vec (.getNestedTypes type))
    (catch Throwable _
      [])))

(defn- source-types [top-level-types]
  (letfn [(walk [type]
            (cons type
                  (mapcat walk
                          (sort-by #(source-order-key % (.getSimpleName %))
                                   (nested-types type)))))]
    (->> top-level-types
         (mapcat walk)
         (reduce (fn [acc type]
                   (let [id (qname type)]
                     (if (contains? (:seen acc) id)
                       acc
                       (-> acc
                           (update :seen conj id)
                           (update :types conj type)))))
                 {:seen #{} :types []})
         :types)))

(defn- source-file-type-qname [file-record]
  (let [file-path (:file/path file-record)
        file-name (last (str/split file-path #"/"))
        type-name (str/replace file-name #"\.java$" "")
        package-name (:file/package file-record)]
    (when (and (seq type-name) (not= file-name type-name))
      (if (str/blank? (or package-name ""))
        type-name
        (str package-name "." type-name)))))

(defn- source-file-type-qnames [file-records]
  (set (keep source-file-type-qname file-records)))

(defn- file-facts [file-record]
  (let [file-id (:file/id file-record)
        project-root (get-in file-record [:file/project :project/root])
        source-path (.resolve (path project-root) (:file/path file-record))
        top-level-types (vec (parse-file source-path))
        types (source-types top-level-types)
        import-aliases (compilation-unit-import-aliases top-level-types)
        wildcard-imports (compilation-unit-wildcard-imports top-level-types)]
    (binding [*import-aliases* import-aliases
              *import-wildcard-packages* wildcard-imports
              *current-package* (:file/package file-record)]
      (doall
       (mapcat
        (fn [type-ordinal type]
          (let [type-node-id (type-node-id file-id type)
                record? (instance? CtRecord type)
                components (sort-by #(source-order-key % (.getSimpleName %)) (record-components type))
                component-names (set (map #(.getSimpleName %) components))
                fields (cond->> (.getFields type)
                         record? (remove #(contains? component-names (.getSimpleName %)))
                         true (sort-by #(source-order-key % (.getSimpleName %))))
                constructors (sort-by #(.getSignature %) (constructors type))
                methods (cond->> (.getMethods type)
                          record? (remove #(record-accessor-method? component-names %))
                          true (sort-by #(.getSignature %)))
                executables (concat constructors methods)]
            (concat
             (type-facts file-id type-ordinal type)
             (mapcat (fn [ordinal component]
                       (record-component-facts file-id type-node-id ordinal type component))
                     (range)
                     components)
             (mapcat (fn [ordinal field] (field-facts file-id type-node-id ordinal field))
                     (range)
                     fields)
             (mapcat (fn [ordinal executable]
                       (executable-facts file-id type-node-id ordinal executable))
                     (range)
                     executables)
             (mapcat #(executable-body-facts file-id %) executables)
             (mapcat #(expression-feature-facts file-id %) executables))))
        (range)
        types)))))

(defn- unique-key [fact]
  (some (fn [attr] (when-let [value (get fact attr)] [attr value]))
        [:node/id :decl/id :type/id :type-param/id :ref/id :feature/id]))

(defn- merge-duplicate-fact [existing incoming]
  (cond-> (merge existing incoming)
    (contains? existing :decl/type)
    (assoc :decl/type (:decl/type existing))

    (contains? existing :decl/return-type)
    (assoc :decl/return-type (:decl/return-type existing))))

(defn- dedupe-facts [facts]
  (:facts
   (reduce (fn [acc fact]
             (if-not (map? fact)
               acc
               (if-let [key (unique-key fact)]
                 (if-let [index (get-in acc [:index key])]
                   (update-in acc [:facts index] merge-duplicate-fact fact)
                   (-> acc
                       (assoc-in [:index key] (count (:facts acc)))
                       (update :facts conj fact)))
                 (update acc :facts conj fact))))
           {:index {} :facts []}
           facts)))

(defn- strip-type-args [type-name]
  (some-> type-name
          (str/replace #"<.*$" "")))

(defn- param-count-from-decl-id [decl-id]
  (when-let [[_ params] (re-matches #".*\((.*)\)$" (or decl-id ""))]
    (if (str/blank? params)
      0
      (count (str/split params #",")))))

(defn- param-types-from-decl-id [decl-id]
  (when-let [[_ params] (re-matches #".*\((.*)\)$" (or decl-id ""))]
    (if (str/blank? params)
      []
      (str/split params #","))))

(defn- varargs-method-decl? [decl]
  (some-> (:decl/id decl)
          param-types-from-decl-id
          last
          (str/ends-with? "[]")))

(defn- owner-from-method-decl-id [decl-id]
  (when-let [[_ owner] (re-matches #"java:(.+)#.+\(.*\)$" (or decl-id ""))]
    owner))

(defn- owner-from-constructor-decl-id [decl-id]
  (when-let [[_ owner] (re-matches #"java:(.+)\(.*\)$" (or decl-id ""))]
    owner))

(defn- owner-from-field-decl-id [decl-id]
  (when-let [[_ owner] (re-matches #"java:(.+)#field:.+$" (or decl-id ""))]
    owner))

(defn- method-decl-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/method (:decl/kind %))
                     (:decl/source-node %)))
       (reduce (fn [index decl]
                 (if-let [owner (owner-from-method-decl-id (:decl/id decl))]
                   (update index
                           [(strip-type-args owner)
                            (:decl/name decl)
                            (param-count-from-decl-id (:decl/id decl))]
                           (fnil conj [])
                           decl)
                   index))
               {})))

(defn- varargs-method-decl-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/method (:decl/kind %))
                     (:decl/source-node %)
                     (varargs-method-decl? %)))
       (reduce (fn [index decl]
                 (if-let [owner (owner-from-method-decl-id (:decl/id decl))]
                   (update index
                           [(strip-type-args owner) (:decl/name decl)]
                           (fnil conj [])
                           decl)
                   index))
               {})
       (map (fn [[k decls]]
              [k (vec (sort-by :decl/id decls))]))
       (into {})))

(defn- constructor-decl-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/constructor (:decl/kind %))
                     (:decl/source-node %)))
       (reduce (fn [index decl]
                 (if-let [owner (owner-from-constructor-decl-id (:decl/id decl))]
                   (update index
                           [(strip-type-args owner)
                            (param-count-from-decl-id (:decl/id decl))]
                           (fnil conj [])
                           decl)
                   index))
               {})))

(defn- type-name-index [facts]
  (->> facts
       (filter :type/id)
       (map (juxt :type/id :type/name))
       (into {})))

(defn- nested-type-alias-index [facts]
  (->> facts
       (keep :type/id)
       (filter #(str/includes? % "$"))
       (reduce (fn [aliases type-id]
                 (assoc aliases (str/replace type-id #"\$" ".") type-id))
               {})))

(defn- canonical-type-id [type-aliases type-id]
  (get type-aliases type-id type-id))

(defn- type-arg-index [facts]
  (reduce (fn [index fact]
            (if (and (:type/id fact) (seq (:type/args fact)))
              (reduce (fn [index arg]
                        (assoc-in index
                                  [(:type/id fact) (:type.arg/ordinal arg)]
                                  (:type.arg/type arg)))
                      index
                      (:type/args fact))
              index))
          {}
          facts))

(defn- argument-counts [facts]
  (->> facts
       (filter #(= :argument (:node/role %)))
       (reduce (fn [counts node]
                 (update counts (:node/parent node) (fnil inc 0)))
               {})))

(defn- child-node-index [facts]
  (->> facts
       (filter #(and (:node/id %) (:node/parent %) (:node/role %)))
       (reduce (fn [index node]
                 (update index [(:node/parent node) (:node/role node)] (fnil conj []) node))
               {})
       (map (fn [[k nodes]]
              [k (vec (sort-by (juxt #(or (:node/ordinal %) 0) :node/id) nodes))]))
       (into {})))

(defn- method-ref-index [facts]
  (->> facts
       (filter #(= :ref.kind/method-call (:ref/kind %)))
       (map (juxt :ref/from-node identity))
       (into {})))

(defn- field-ref-index [facts]
  (->> facts
       (filter #(= :ref.kind/field-access (:ref/kind %)))
       (map (juxt :ref/from-node identity))
       (into {})))

(defn- constructor-ref-index [facts]
  (->> facts
       (filter #(= :ref.kind/constructor-call (:ref/kind %)))
       (map (juxt :ref/from-node identity))
       (into {})))

(defn- decl-return-type-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/method (:decl/kind %))
                     (:decl/id %)
                     (:decl/return-type %)))
       (map (juxt :decl/id :decl/return-type))
       (into {})))

(defn- field-decl-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/field (:decl/kind %))
                     (:decl/type %)
                     (:decl/name %)
                     (:decl/qualified-name %)))
       (reduce (fn [index decl]
                 (update index (:decl/name decl) (fnil conj []) decl))
               {})
       (map (fn [[field-name decls]]
              [field-name (vec (sort-by :decl/qualified-name decls))]))
       (into {})))

(defn- field-decl-owner-index [facts]
  (->> facts
       (filter #(and (= :decl.kind/field (:decl/kind %))
                     (:decl/type %)
                     (:decl/name %)
                     (:decl/id %)))
       (reduce (fn [index decl]
                 (if-let [owner (owner-from-field-decl-id (:decl/id decl))]
                   (update index [(strip-type-args owner) (:decl/name decl)] (fnil conj []) decl)
                   index))
               {})
       (map (fn [[k decls]]
              [k (vec (sort-by :decl/qualified-name decls))]))
       (into {})))

(defn- type-decl-index [facts type-aliases]
  (->> facts
       (filter #(and (contains? #{:decl.kind/annotation
                                  :decl.kind/class
                                  :decl.kind/enum
                                  :decl.kind/interface
                                  :decl.kind/record
                                  :decl.kind/type}
                                (:decl/kind %))
                     (:decl/type %)
                     (:decl/source-node %)))
       (reduce (fn [index decl]
                 (let [type-id (:decl/type decl)
                       aliases (for [[alias canonical] type-aliases
                                     :when (= canonical type-id)]
                                 alias)]
                   (reduce #(assoc %1 %2 decl)
                           (assoc index type-id decl)
                           aliases)))
               {})))

(defn- type-decl-source-node-index [facts]
  (->> facts
       (filter #(and (contains? #{:decl.kind/annotation
                                  :decl.kind/class
                                  :decl.kind/enum
                                  :decl.kind/interface
                                  :decl.kind/record
                                  :decl.kind/type}
                                (:decl/kind %))
                     (:decl/type %)
                     (:decl/source-node %)))
       (map (juxt :decl/source-node :decl/type))
       (into {})))

(defn- parent-node-index [facts]
  (->> facts
       (filter #(and (:node/id %) (:node/parent %)))
       (map (juxt :node/id :node/parent))
       (into {})))

(defn- node-index [facts]
  (->> facts
       (filter :node/id)
       (map (juxt :node/id identity))
       (into {})))

(defn- direct-supertype-index [facts source-node-types type-aliases]
  (->> facts
       (filter #(and (contains? #{:ref.kind/extends :ref.kind/implements} (:ref/kind %))
                     (:ref/from-node %)
                     (:ref/to-type %)))
       (reduce (fn [index ref]
                 (if-let [type-id (get source-node-types (:ref/from-node ref))]
                   (update index
                           (strip-type-args (canonical-type-id type-aliases type-id))
                           (fnil conj [])
                           (strip-type-args (canonical-type-id type-aliases (:ref/to-type ref))))
                   index))
               {})))

(defn- unambiguous [xs]
  (when (= 1 (count xs))
    (first xs)))

(defn- child-node [child-index parent-id role]
  (first (get child-index [parent-id role])))

(defn- enum-constant-field-decl [field-index target-type-name field-name]
  (let [suffix (when (and target-type-name field-name)
                 (str target-type-name "." field-name))]
    (->> (get field-index field-name)
         (filter (fn [decl]
                   (let [decl-type (:decl/type decl)
                         qualified-name (:decl/qualified-name decl)
                         expected-qualified-name (str decl-type "." field-name)]
                     (and (= qualified-name expected-qualified-name)
                          (or (= qualified-name suffix)
                              (some-> qualified-name (str/ends-with? (str "." suffix)))
                              (= decl-type target-type-name)
                              (some-> decl-type (str/ends-with? (str "." target-type-name))))))))
         unambiguous)))

(defn- enum-constant-target-type [child-index field-index ref]
  (when-let [target (child-node child-index (:ref/from-node ref) :target)]
    (when (= :java.node/field-read (:node/kind target))
      (let [type-access (child-node child-index (:node/id target) :target)
            target-type-name (:node/value type-access)
            field-name (:node/name target)]
        (some-> (enum-constant-field-decl field-index target-type-name field-name)
                :decl/type)))))

(defn- type-owner [type-names type-id]
  (strip-type-args (or (get type-names type-id)
                       type-id)))

(defn- executable-node-kind? [kind]
  (contains? #{:java.node/method :java.node/constructor} kind))

(defn- enclosing-executable [nodes-by-id parent-by-node node-id]
  (loop [current node-id
         seen #{}]
    (when (and current (not (contains? seen current)))
      (let [node (get nodes-by-id current)]
        (if (executable-node-kind? (:node/kind node))
          current
          (recur (get parent-by-node current) (conj seen current)))))))

(defn- binding-type-index [facts nodes-by-id parent-by-node type-aliases]
  (->> facts
       (filter #(and (= :ref.kind/type-use (:ref/kind %))
                     (:ref/source-name %)
                     (:ref/to-type %)
                     (or (some-> % :ref/role name (str/starts-with? "param-"))
                         (contains? #{:local-type :catch-type :element-type :pattern-type}
                                    (:ref/role %)))))
       (reduce (fn [index ref]
                 (let [binding-node (:ref/from-node ref)
                       scope-node (if (some-> ref :ref/role name (str/starts-with? "param-"))
                                    binding-node
                                    (enclosing-executable nodes-by-id parent-by-node binding-node))]
                   (if scope-node
                     (assoc-in index
                               [scope-node (:ref/source-name ref)]
                               (canonical-type-id type-aliases (:ref/to-type ref)))
                     index)))
               {})))

(defn- weak-binding-type? [type-id]
  (or (nil? type-id)
      (= "var" type-id)
      (= "java.lang.Object" type-id)))

(declare expression-node-type)

(defn- method-ref-return-type [method-index type-names argument-counts decl-return-types ref]
  (or (:ref/to-type ref)
      (get decl-return-types (:ref/to-decl ref))
      (when-let [owner (type-owner type-names (:ref/owner-type ref))]
        (let [arity (get argument-counts (:ref/from-node ref) 0)]
          (:decl/return-type
           (unambiguous (get method-index [owner (:ref/name ref) arity])))))))

(defn- collection-method-call-return-type
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types
   node
   ref]
  (when-let [target (child-node child-index (:node/id node) :target)]
    (let [target-type (expression-node-type facts
                                            method-index
                                            type-names
                                            type-args
                                            argument-counts
                                            child-index
                                            method-refs
                                            field-refs
                                            constructor-refs
                                            decl-return-types
                                            nodes-by-id
                                            parent-by-node
                                            binding-types
                                            target)
          owner (type-owner type-names target-type)]
      (cond
        (and (= "get" (:ref/name ref))
             (java-list-owner? owner))
        (get-in type-args [target-type 0])

        (and (= "subList" (:ref/name ref))
             (java-list-owner? owner))
        target-type

        (and (= "get" (:ref/name ref))
             (java-map-owner? owner))
        (get-in type-args [target-type 1])))))

(defn- type-ref-for-role [facts node-id role]
  (some (fn [fact]
          (when (and (= :ref.kind/type-use (:ref/kind fact))
                     (= node-id (:ref/from-node fact))
                     (= role (:ref/role fact)))
            (:ref/to-type fact)))
        facts))

(defn- expression-node-type
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types
   node]
  (case (:node/kind node)
    :java.node/method-call
    (when-let [ref (get method-refs (:node/id node))]
      (or (collection-method-call-return-type facts
                                              method-index
                                              type-names
                                              type-args
                                              argument-counts
                                              child-index
                                              method-refs
                                              field-refs
                                              constructor-refs
                                              decl-return-types
                                              nodes-by-id
                                              parent-by-node
                                              binding-types
                                              node
                                              ref)
          (method-ref-return-type method-index type-names argument-counts decl-return-types ref)))

    :java.node/constructor-call
    (some->> (:node/id node)
             (get constructor-refs)
             :ref/to-type)

    :java.node/field-read
    (some->> (:node/id node)
             (get field-refs)
             :ref/to-type)

    :java.node/field-write
    (some->> (:node/id node)
             (get field-refs)
             :ref/to-type)

    :java.node/variable-read
    (some->> (:node/id node)
             (enclosing-executable nodes-by-id parent-by-node)
             (get binding-types)
             (#(get % (:node/name node))))

    :java.node/variable-write
    (some->> (:node/id node)
             (enclosing-executable nodes-by-id parent-by-node)
             (get binding-types)
             (#(get % (:node/name node))))

    :java.node/type-cast
    (type-ref-for-role facts (:node/id node) :cast-type)

    nil))

(defn- argument-type-ids
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types
   call-node-id]
  (mapv #(expression-node-type facts
                               method-index
                               type-names
                               type-args
                               argument-counts
                               child-index
                               method-refs
                               field-refs
                               constructor-refs
                               decl-return-types
                               nodes-by-id
                               parent-by-node
                               binding-types
                               %)
        (get child-index [call-node-id :argument])))

(defn- initializer-binding-type-index
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types]
  (reduce (fn [index node]
            (if (and (= :java.node/local-variable (:node/kind node))
                     (:node/name node))
              (let [scope-node (enclosing-executable nodes-by-id parent-by-node (:node/id node))
                    current-type (get-in index [scope-node (:node/name node)])
                    initializer (child-node child-index (:node/id node) :initializer)
                    inferred-type (when initializer
                                    (expression-node-type facts
                                                          method-index
                                                          type-names
                                                          type-args
                                                          argument-counts
                                                          child-index
                                                          method-refs
                                                          field-refs
                                                          constructor-refs
                                                          decl-return-types
                                                          nodes-by-id
                                                          parent-by-node
                                                          index
                                                          initializer))]
                (if (and scope-node
                         inferred-type
                         (weak-binding-type? current-type))
                  (assoc-in index [scope-node (:node/name node)] inferred-type)
                  index))
              index))
          binding-types
          (filter :node/id facts)))

(defn- chained-method-target-owner [method-index type-names argument-counts child-index ref-index decl-return-types ref]
  (when-let [target (child-node child-index (:ref/from-node ref) :target)]
    (when (= :java.node/method-call (:node/kind target))
      (some->> (:node/id target)
               (get ref-index)
               (method-ref-return-type method-index type-names argument-counts decl-return-types)
               (type-owner type-names)))))

(defn- constructor-call-target-owner [type-names child-index constructor-refs ref]
  (when-let [target (child-node child-index (:ref/from-node ref) :target)]
    (when (= :java.node/constructor-call (:node/kind target))
      (some->> (:node/id target)
               (get constructor-refs)
               :ref/to-type
               (type-owner type-names)))))

(defn- expression-target-owner
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types
   ref]
  (when-let [target (child-node child-index (:ref/from-node ref) :target)]
    (some->> target
             (expression-node-type facts
                                   method-index
                                   type-names
                                   type-args
                                   argument-counts
                                   child-index
                                   method-refs
                                   field-refs
                                   constructor-refs
                                   decl-return-types
                                   nodes-by-id
                                   parent-by-node
                                   binding-types)
             (type-owner type-names))))

(declare enclosing-type type-lineage)

(defn- comparable-type-id [type-id]
  (some-> type-id
          (str/replace #"\?$" "")
          strip-type-args))

(defn- type-compatible? [type-aliases direct-supertypes param-type arg-type]
  (let [param-type (comparable-type-id (canonical-type-id type-aliases param-type))
        arg-type (comparable-type-id (canonical-type-id type-aliases arg-type))]
    (boolean
     (and param-type
          arg-type
          (or (= param-type arg-type)
              (= "java.lang.Object" param-type)
              (contains? (set (type-lineage direct-supertypes arg-type)) param-type))))))

(defn- fixed-arity-arg-types-match? [type-aliases direct-supertypes param-types arg-types]
  (and (= (count param-types) (count arg-types))
       (every? true? (map #(type-compatible? type-aliases direct-supertypes %1 %2)
                          param-types
                          arg-types))))

(defn- varargs-param-types [param-types arity]
  (let [fixed-params (butlast param-types)
        vararg-type (last param-types)
        element-type (some-> vararg-type (str/replace #"\[\]$" ""))]
    (when (and element-type (>= arity (count fixed-params)))
      (vec (concat fixed-params
                   (repeat (- arity (count fixed-params)) element-type))))))

(defn- arg-types-match? [type-aliases direct-supertypes decl arg-types]
  (let [param-types (param-types-from-decl-id (:decl/id decl))
        arity (count arg-types)]
    (or (fixed-arity-arg-types-match? type-aliases direct-supertypes param-types arg-types)
        (when-let [expanded (varargs-param-types param-types arity)]
          (fixed-arity-arg-types-match? type-aliases direct-supertypes expanded arg-types)))))

(defn- method-target-by-arg-types [type-aliases direct-supertypes candidates arg-types]
  (when (every? some? arg-types)
    (->> candidates
         (filter #(arg-types-match? type-aliases direct-supertypes % arg-types))
         unambiguous)))

(defn- varargs-method-candidates [varargs-method-index owner method-name arity]
  (->> (get varargs-method-index [owner method-name])
       (filter (fn [decl]
                 (let [param-count (param-count-from-decl-id (:decl/id decl))]
                   (and param-count (>= arity (dec param-count))))))))

(defn- method-target-for-owner
  ([method-index varargs-method-index owner method-name arity]
   (method-target-for-owner method-index varargs-method-index nil owner method-name arity []))
  ([method-index varargs-method-index direct-supertypes owner method-name arity arg-types]
   (method-target-for-owner method-index varargs-method-index {} direct-supertypes owner method-name arity arg-types))
  ([method-index varargs-method-index type-aliases direct-supertypes owner method-name arity arg-types]
   (let [exact-candidates (get method-index [owner method-name arity])
         varargs-candidates (varargs-method-candidates varargs-method-index owner method-name arity)]
     (or (unambiguous exact-candidates)
         (method-target-by-arg-types type-aliases direct-supertypes exact-candidates arg-types)
         (unambiguous varargs-candidates)
         (method-target-by-arg-types type-aliases direct-supertypes varargs-candidates arg-types)))))

(defn- inherited-local-method-target
  [method-index varargs-method-index type-aliases argument-counts parent-by-node source-node-types direct-supertypes arg-types ref]
  (when (and (nil? (:ref/owner-type ref)) (:ref/name ref))
    (let [arity (get argument-counts (:ref/from-node ref) 0)]
      (some->> (:ref/from-node ref)
               (enclosing-type parent-by-node source-node-types)
               (type-lineage direct-supertypes)
               (keep #(method-target-for-owner method-index
                                               varargs-method-index
                                               type-aliases
                                               direct-supertypes
                                               %
                                               (:ref/name ref)
                                               arity
                                               arg-types))
               first))))

(defn- local-method-target
  [facts method-index varargs-method-index type-names type-args type-aliases argument-counts child-index field-index ref-index field-refs constructor-refs decl-return-types nodes-by-id parent-by-node source-node-types direct-supertypes binding-types ref]
  (let [owner-type-id (:ref/owner-type ref)
        owner (type-owner type-names owner-type-id)
        enum-target-owner (some->> (enum-constant-target-type child-index field-index ref)
                                   (type-owner type-names))
        chained-target-owner (chained-method-target-owner method-index type-names argument-counts child-index ref-index decl-return-types ref)
        constructor-target-owner (constructor-call-target-owner type-names child-index constructor-refs ref)
        arity (get argument-counts (:ref/from-node ref) 0)
        arg-types (argument-type-ids facts
                                     method-index
                                     type-names
                                     type-args
                                     argument-counts
                                     child-index
                                     ref-index
                                     field-refs
                                     constructor-refs
                                     decl-return-types
                                     nodes-by-id
                                     parent-by-node
                                     binding-types
                                     (:ref/from-node ref))]
    (when (:ref/name ref)
      (or (when owner
            (method-target-for-owner method-index varargs-method-index type-aliases direct-supertypes owner (:ref/name ref) arity arg-types))
          (when enum-target-owner
            (method-target-for-owner method-index varargs-method-index type-aliases direct-supertypes enum-target-owner (:ref/name ref) arity arg-types))
          (when constructor-target-owner
            (method-target-for-owner method-index varargs-method-index type-aliases direct-supertypes constructor-target-owner (:ref/name ref) arity arg-types))
          (when chained-target-owner
            (method-target-for-owner method-index varargs-method-index type-aliases direct-supertypes chained-target-owner (:ref/name ref) arity arg-types))
          (inherited-local-method-target method-index
                                         varargs-method-index
                                         type-aliases
                                         argument-counts
                                         parent-by-node
                                         source-node-types
                                         direct-supertypes
                                         arg-types
                                         ref)))))

(defn- local-constructor-target [constructor-index type-names argument-counts ref]
  (when-let [owner (type-owner type-names (:ref/to-type ref))]
    (let [arity (get argument-counts (:ref/from-node ref) 0)]
      (unambiguous (get constructor-index [owner arity])))))

(defn- local-field-target
  [facts
   method-index
   type-names
   type-args
   argument-counts
   child-index
   method-refs
   field-refs
   constructor-refs
   decl-return-types
   nodes-by-id
   parent-by-node
   binding-types
   field-index
   ref]
  (let [owner (type-owner type-names (:ref/owner-type ref))
        target-owner (expression-target-owner facts
                                              method-index
                                              type-names
                                              type-args
                                              argument-counts
                                              child-index
                                              method-refs
                                              field-refs
                                              constructor-refs
                                              decl-return-types
                                              nodes-by-id
                                              parent-by-node
                                              binding-types
                                              ref)
        field-name (:ref/name ref)]
    (or (when (and (or owner target-owner) field-name)
          (->> (get field-index field-name)
               (filter #(= (or owner target-owner)
                           (strip-type-args (owner-from-field-decl-id (:decl/id %)))))
               unambiguous))
        (when (and (nil? owner) field-name)
          (->> (get field-index field-name)
               (filter #(= (:decl/type %) (strip-type-args (owner-from-field-decl-id (:decl/id %)))))
               unambiguous)))))

(defn- enclosing-type [parent-by-node source-node-types node-id]
  (loop [current node-id
         seen #{}]
    (when (and current (not (contains? seen current)))
      (or (get source-node-types current)
          (recur (get parent-by-node current) (conj seen current))))))

(defn- type-lineage [direct-supertypes type-id]
  (loop [lineage []
         pending [(some-> type-id strip-type-args)]
         seen #{}]
    (if-let [current (first pending)]
      (if (or (nil? current) (contains? seen current))
        (recur lineage (subvec (vec pending) 1) seen)
        (let [remaining (subvec (vec pending) 1)
              parents (seq (keep identity (get direct-supertypes current)))]
          (recur (conj lineage current)
                 (into remaining parents)
                 (conj seen current))))
      lineage)))

(defn- inherited-local-field-target [field-owner-index parent-by-node source-node-types direct-supertypes ref]
  (when (and (nil? (:ref/owner-type ref)) (:ref/name ref))
    (some->> (:ref/from-node ref)
             (enclosing-type parent-by-node source-node-types)
             (type-lineage direct-supertypes)
             (keep #(unambiguous (get field-owner-index [% (:ref/name ref)])))
             first)))

(defn- target-variable-field-type
  [field-owner-index parent-by-node source-node-types direct-supertypes target-node]
  (when (and (contains? #{:java.node/variable-read
                          :java.node/variable-write}
                        (:node/kind target-node))
             (:node/name target-node))
    (some-> (inherited-local-field-target field-owner-index
                                          parent-by-node
                                          source-node-types
                                          direct-supertypes
                                          {:ref/from-node (:node/id target-node)
                                           :ref/name (:node/name target-node)})
            :decl/type)))

(def ^:private string-builder-aliases
  #{"b" "builder" "out" "sb" "stringBuilder"})

(defn- string-builder-alias-owner? [owner]
  (let [simple-name (some-> owner (str/split #"\.") last)]
    (contains? string-builder-aliases simple-name)))

(defn- fluent-append-owner [owner]
  (let [owner (some-> owner strip-type-args)]
    (cond
      (contains? #{"java.lang.StringBuilder" "java.lang.StringBuffer"} owner)
      owner

      (= "org.pkl.core.util.AnsiStringBuilder" owner)
      owner

      (some-> owner (str/ends-with? "StringBuilder"))
      owner

      (string-builder-alias-owner? owner)
      "java.lang.StringBuilder")))

(defn- collection-call-return-type [type-args target-type owner name]
  (cond
    (and (java-collection-owner? owner)
         (#{"size"} name))
    "int"

    (and (java-collection-owner? owner)
         (#{"isEmpty"} name))
    "boolean"

    (and (or (java-list-owner? owner)
             (java-set-owner? owner)
             (= "java.util.Collection" owner))
         (#{"contains" "add"} name))
    "boolean"

    (and (java-list-owner? owner)
         (= "get" name))
    (get-in type-args [target-type 0] "java.lang.Object")

    (and (java-list-owner? owner)
         (= "subList" name))
    target-type

    (and (java-map-owner? owner)
         (#{"get" "getOrDefault" "put"} name))
    (get-in type-args [target-type 1] "java.lang.Object")

    (and (java-map-owner? owner)
         (#{"containsKey" "containsValue"} name))
    "boolean"))

(defn- resolve-known-collection-call [type-args fact target-type target-owner]
  (let [owner (or target-owner
                  (some-> (:ref/owner-type fact) strip-type-args))
        return-type (collection-call-return-type type-args
                                                 target-type
                                                 owner
                                                 (:ref/name fact))]
    (when (and owner
               (or (collection-feature-kind owner (:ref/name fact))
                   return-type))
      (cond-> (assoc fact
                     :ref/resolved? true
                     :ref/owner-type (or target-type owner))
        true (dissoc :ref/reason)
        return-type (assoc :ref/to-type return-type)))))

(defn- resolve-known-java-api-call
  ([fact]
   (resolve-known-java-api-call fact nil nil nil))
  ([fact target-owner]
   (resolve-known-java-api-call fact nil target-owner nil))
  ([fact type-args target-owner target-type]
  (when (and (= :ref.kind/method-call (:ref/kind fact))
             (not (:ref/resolved? fact)))
    (or (resolve-known-collection-call type-args fact target-type target-owner)
        (if (= "append" (:ref/name fact))
          (when-let [owner (fluent-append-owner (or (:ref/owner-type fact) target-owner))]
            (-> fact
                (assoc :ref/resolved? true
                       :ref/to-type owner
                       :ref/owner-type owner)
                (dissoc :ref/reason)))
          (when-let [known (get known-java-api-calls (:ref/name fact))]
            (-> fact
                (assoc :ref/resolved? true
                       :ref/to-type (:ref/to-type known)
                       :ref/owner-type (:ref/owner-type known))
                (dissoc :ref/reason))))))))

(defn- resolve-local-type-ref [type-decls type-aliases fact]
  (when (and (contains? #{:ref.kind/type-use
                          :ref.kind/extends
                          :ref.kind/implements}
                        (:ref/kind fact))
             (not (:ref/resolved? fact)))
    (let [type-id (canonical-type-id type-aliases (:ref/to-type fact))
          decl-type-id (str/replace type-id #"\?$" "")]
      (when-let [decl (or (get type-decls type-id)
                          (get type-decls decl-type-id))]
        (-> fact
            (assoc :ref/to-decl (:decl/id decl)
                   :ref/to-type (if (str/ends-with? type-id "?")
                                  type-id
                                  (:decl/type decl))
                   :ref/resolved? true)
            (dissoc :ref/reason))))))

(defn- resolve-local-refs-once [facts]
  (let [deduped (dedupe-facts (concat facts (known-java-api-type-facts)))
        method-index (method-decl-index deduped)
        varargs-method-index (varargs-method-decl-index deduped)
        constructor-index (constructor-decl-index deduped)
        type-names (type-name-index deduped)
        type-aliases (nested-type-alias-index deduped)
        type-args (type-arg-index deduped)
        argument-counts (argument-counts deduped)
        child-index (child-node-index deduped)
        ref-index (method-ref-index deduped)
        field-refs (field-ref-index deduped)
        constructor-refs (constructor-ref-index deduped)
        decl-return-types (decl-return-type-index deduped)
        field-index (field-decl-index deduped)
        field-owner-index (field-decl-owner-index deduped)
        type-decls (type-decl-index deduped type-aliases)
        source-node-types (type-decl-source-node-index deduped)
        parent-by-node (parent-node-index deduped)
        nodes-by-id (node-index deduped)
        basic-binding-types (binding-type-index deduped nodes-by-id parent-by-node type-aliases)
        binding-types (initializer-binding-type-index deduped
                                                       method-index
                                                       type-names
                                                       type-args
                                                       argument-counts
                                                       child-index
                                                       ref-index
                                                       field-refs
                                                       constructor-refs
                                                       decl-return-types
                                                       nodes-by-id
                                                       parent-by-node
                                                       basic-binding-types)
        direct-supertypes (direct-supertype-index deduped source-node-types type-aliases)]
    (mapv (fn [fact]
            (cond
              (contains? #{:ref.kind/type-use
                           :ref.kind/extends
                           :ref.kind/implements}
                         (:ref/kind fact))
              (or (resolve-local-type-ref type-decls type-aliases fact)
                  fact)

              (= :ref.kind/method-call (:ref/kind fact))
              (let [target-node (child-node child-index (:ref/from-node fact) :target)
                    target-type (when target-node
                                  (or (expression-node-type deduped
                                                            method-index
                                                            type-names
                                                            type-args
                                                            argument-counts
                                                            child-index
                                                            ref-index
                                                            field-refs
                                                            constructor-refs
                                                            decl-return-types
                                                            nodes-by-id
                                                            parent-by-node
                                                            binding-types
                                                            target-node)
                                      (target-variable-field-type field-owner-index
                                                                  parent-by-node
                                                                  source-node-types
                                                                  direct-supertypes
                                                                  target-node)))
                    target-owner (some->> target-type (type-owner type-names))]
                (if (not (some-> fact :ref/owner-type (str/starts-with? "java.")))
                  (if-let [target (local-method-target deduped
                                                       method-index
                                                       varargs-method-index
                                                       type-names
                                                       type-args
                                                       type-aliases
                                                       argument-counts
                                                       child-index
                                                       field-index
                                                       ref-index
                                                       field-refs
                                                       constructor-refs
                                                       decl-return-types
                                                       nodes-by-id
                                                       parent-by-node
                                                       source-node-types
                                                       direct-supertypes
                                                       binding-types
                                                       fact)]
                    (let [owner (owner-from-method-decl-id (:decl/id target))]
                      (-> fact
                          (assoc :ref/to-decl (:decl/id target)
                                 :ref/resolved? true)
                          (cond-> owner (assoc :ref/owner-type owner))
                          (cond-> (:decl/return-type target) (assoc :ref/to-type (:decl/return-type target)))
                          (dissoc :ref/reason)))
                    (or (resolve-known-java-api-call fact type-args target-owner target-type)
                        fact))
                  (or (resolve-known-java-api-call fact type-args target-owner target-type)
                      fact)))

              (and (= :ref.kind/constructor-call (:ref/kind fact))
                   (not (some-> fact :ref/to-type (str/starts-with? "java."))))
              (if-let [target (local-constructor-target constructor-index
                                                        type-names
                                                        argument-counts
                                                        fact)]
                (-> fact
                    (assoc :ref/to-decl (:decl/id target)
                           :ref/resolved? true)
                    (dissoc :ref/reason))
                fact)

              (and (= :ref.kind/field-access (:ref/kind fact))
                   (not (some-> fact :ref/owner-type (str/starts-with? "java."))))
              (if-let [target (or (local-field-target deduped
                                                       method-index
                                                       type-names
                                                       type-args
                                                       argument-counts
                                                       child-index
                                                       ref-index
                                                       field-refs
                                                       constructor-refs
                                                       decl-return-types
                                                       nodes-by-id
                                                       parent-by-node
                                                       binding-types
                                                       field-index
                                                       fact)
                                  (inherited-local-field-target field-owner-index
                                                                parent-by-node
                                                                source-node-types
                                                                direct-supertypes
                                                                fact))]
                (let [owner (owner-from-field-decl-id (:decl/id target))]
                  (-> fact
                      (assoc :ref/to-decl (:decl/id target)
                             :ref/resolved? true)
                      (cond-> owner (assoc :ref/owner-type owner))
                      (cond-> (:decl/type target) (assoc :ref/to-type (:decl/type target)))
                      (dissoc :ref/reason)))
                fact)

              :else fact))
          deduped)))

(defn- resolve-local-refs [facts]
  (loop [current facts
         remaining 8]
    (let [next (resolve-local-refs-once current)]
      (if (or (zero? remaining) (= next current))
        next
        (recur next (dec remaining))))))

(defn- normalize-classpath-strings [values]
  (cond
    (nil? values) #{}
    (string? values) #{values}
    (set? values) (set (remove str/blank? (map str values)))
    (sequential? values) (set (remove str/blank? (map str values)))
    :else #{(str values)}))

(defn extract-project-facts
  "Read Java file records for project-id from db and return normalized Java facts.

  Java file records must already exist, usually from vibeformer.ingest.source."
  ([db project-id]
   (extract-project-facts db project-id {}))
  ([db project-id opts]
   (let [files (file-records db project-id)]
     (binding [*classpath-types* (normalize-classpath-strings (:java/classpath-types opts))
               *classpath-package-roots* (normalize-classpath-strings (:java/classpath-package-roots opts))
               *source-type-qnames* (source-file-type-qnames files)]
       (resolve-local-refs (mapcat file-facts files))))))

(defn ingest!
  "Extract normalized Java facts from ingested Java files and transact them."
  [conn {:project/keys [id] :as opts}]
  (let [db (d/db conn)
        files (file-records db id)
        facts (extract-project-facts db id opts)]
    (when (seq facts)
      (d/transact conn {:tx-data facts}))
    {:project/id id
     :java-files (count files)
     :classpath/types (count (normalize-classpath-strings (:java/classpath-types opts)))
     :classpath/package-roots (count (normalize-classpath-strings (:java/classpath-package-roots opts)))
     :transacted-facts (count facts)}))
