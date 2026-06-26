(ns vibeformer.ingest.java-spoon
  (:require [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.nio.file Paths)
           (java.security MessageDigest)
           (spoon Launcher)
           (spoon.reflect.code CtArrayRead CtAssignment CtBinaryOperator CtBlock CtCase CtConditional CtConstructorCall CtExecutableReferenceExpression CtExpression CtFieldRead CtFieldWrite CtIf CtInvocation CtLambda CtLiteral CtLocalVariable CtReturn CtStatement CtSwitchExpression CtSynchronized CtTargetedExpression CtThisAccess CtThrow CtTypeAccess CtTypePattern CtUnaryOperator CtVariableRead CtVariableWrite CtYieldStatement)
           (spoon.reflect.declaration CtAnnotationType CtClass CtConstructor CtEnum CtExecutable CtField CtInterface CtMethod CtParameter CtRecord CtRecordComponent CtType)
           (spoon.reflect.reference CtExecutableReference CtFieldReference CtTypeReference)
           (spoon.reflect.visitor.filter TypeFilter)))

(def ^:private lang :lang/java)

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

(defn- qname [value]
  (cond
    (nil? value) nil
    (instance? CtType value) (normalize-qname (.getQualifiedName ^CtType value))
    (instance? CtTypeReference value) (normalize-qname (.getQualifiedName ^CtTypeReference value))
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
  (nullable-annotated? type-ref))

(defn- actual-type-arguments [^CtTypeReference type-ref]
  (vec (.getActualTypeArguments type-ref)))

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

(defn- type-declaration [^CtTypeReference type-ref]
  (when type-ref
    (try
      (.getTypeDeclaration type-ref)
      (catch Throwable _
        nil))))

(defn- type-reference-resolved? [^CtTypeReference type-ref]
  (let [id (type-id type-ref false)]
    (boolean (or (contains? built-in-types id)
                 (type-declaration type-ref)))))

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
   ["java.lang.reflect.Type" "getTypeName"] :java.reflection.type/get-type-name
   ["java.lang.reflect.ParameterizedType" "getActualTypeArguments"] :java.reflection.parameterized-type/get-actual-type-arguments
   ["java.lang.reflect.ParameterizedType" "getRawType"] :java.reflection.parameterized-type/get-raw-type
   ["java.lang.reflect.ParameterizedType" "getOwnerType"] :java.reflection.parameterized-type/get-owner-type
   ["java.lang.reflect.Constructor" "getParameters"] :java.reflection.executable/get-parameters
   ["java.lang.reflect.Executable" "getParameters"] :java.reflection.executable/get-parameters
   ["java.lang.reflect.Parameter" "isNamePresent"] :java.reflection.parameter/is-name-present
   ["java.lang.reflect.Parameter" "getName"] :java.reflection.parameter/get-name
   ["java.lang.reflect.Modifier" "isAbstract"] :java.reflection.modifier/is-abstract})

(def unsupported-reflection-features
  {["java.lang.Class" "forName"] :java.reflection.class/for-name
   ["java.lang.Class" "getMethod"] :java.reflection.class/get-method
   ["java.lang.Class" "getDeclaredMethod"] :java.reflection.class/get-declared-method
   ["java.lang.Class" "getDeclaredMethods"] :java.reflection.class/get-declared-methods
   ["java.lang.Class" "getDeclaredConstructors"] :java.reflection.class/get-declared-constructors
   ["java.lang.Class" "getAnnotation"] :java.reflection.class/get-annotation
   ["java.lang.reflect.Method" "invoke"] :java.reflection.method/invoke
   ["java.lang.reflect.Constructor" "newInstance"] :java.reflection.constructor/new-instance
   ["java.lang.reflect.Constructor" "getAnnotation"] :java.reflection.constructor/get-annotation
   ["java.lang.reflect.Parameter" "getAnnotation"] :java.reflection.parameter/get-annotation
   ["java.lang.reflect.WildcardType" "getLowerBounds"] :java.reflection.wildcard-type/get-lower-bounds
   ["java.lang.reflect.WildcardType" "getUpperBounds"] :java.reflection.wildcard-type/get-upper-bounds})

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
   "max" :java.stream/max
   "anyMatch" :java.stream/any-match
   "allMatch" :java.stream/all-match
   "noneMatch" :java.stream/none-match
   "findFirst" :java.stream/find-first
   "distinct" :java.stream/distinct
   "sorted" :java.stream/sorted})

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

(defn- stream-owner? [owner]
  (some-> owner (str/starts-with? "java.util.stream")))

(defn- collector-owner? [owner]
  (= "java.util.stream.Collectors" owner))

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

(defn- record-components [type]
  (if (instance? CtRecord type)
    (vec (.getRecordComponents ^CtRecord type))
    []))

(defn- record-accessor-method? [component-names ^CtMethod method]
  (and (contains? component-names (.getSimpleName method))
       (empty? (.getParameters method))))

(defn- file-facts [file-record]
  (let [file-id (:file/id file-record)
        project-root (get-in file-record [:file/project :project/root])
        source-path (.resolve (path project-root) (:file/path file-record))
        types (vec (parse-file source-path))]
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
     types)))

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

(defn- method-ref-return-type [method-index type-names argument-counts decl-return-types ref]
  (or (:ref/to-type ref)
      (get decl-return-types (:ref/to-decl ref))
      (when-let [owner (type-owner type-names (:ref/owner-type ref))]
        (let [arity (get argument-counts (:ref/from-node ref) 0)]
          (:decl/return-type
           (unambiguous (get method-index [owner (:ref/name ref) arity])))))))

(defn- chained-method-target-owner [method-index type-names argument-counts child-index ref-index decl-return-types ref]
  (when-let [target (child-node child-index (:ref/from-node ref) :target)]
    (when (= :java.node/method-call (:node/kind target))
      (some->> (:node/id target)
               (get ref-index)
               (method-ref-return-type method-index type-names argument-counts decl-return-types)
               (type-owner type-names)))))

(defn- local-method-target [method-index type-names argument-counts child-index field-index ref-index decl-return-types ref]
  (let [owner-type-id (:ref/owner-type ref)
        owner (type-owner type-names owner-type-id)
        enum-target-owner (some->> (enum-constant-target-type child-index field-index ref)
                                   (type-owner type-names))
        chained-target-owner (chained-method-target-owner method-index type-names argument-counts child-index ref-index decl-return-types ref)
        arity (get argument-counts (:ref/from-node ref) 0)]
    (when (:ref/name ref)
      (or (when owner
            (unambiguous (get method-index [owner (:ref/name ref) arity])))
          (when enum-target-owner
            (unambiguous (get method-index [enum-target-owner (:ref/name ref) arity])))
          (when chained-target-owner
            (unambiguous (get method-index [chained-target-owner (:ref/name ref) arity])))))))

(defn- local-constructor-target [constructor-index type-names argument-counts ref]
  (when-let [owner (type-owner type-names (:ref/to-type ref))]
    (let [arity (get argument-counts (:ref/from-node ref) 0)]
      (unambiguous (get constructor-index [owner arity])))))

(defn- local-field-target [field-index type-names ref]
  (let [owner (type-owner type-names (:ref/owner-type ref))
        field-name (:ref/name ref)]
    (or (when (and owner field-name)
          (->> (get field-index field-name)
               (filter #(= owner (strip-type-args (owner-from-field-decl-id (:decl/id %)))))
               unambiguous))
        (when (and (nil? owner) field-name)
          (->> (get field-index field-name)
               (filter #(= (:decl/type %) (strip-type-args (owner-from-field-decl-id (:decl/id %)))))
               unambiguous)))))

(defn- resolve-local-refs [facts]
  (let [deduped (dedupe-facts facts)
        method-index (method-decl-index deduped)
        constructor-index (constructor-decl-index deduped)
        type-names (type-name-index deduped)
        argument-counts (argument-counts deduped)
        child-index (child-node-index deduped)
        ref-index (method-ref-index deduped)
        decl-return-types (decl-return-type-index deduped)
        field-index (field-decl-index deduped)]
    (mapv (fn [fact]
            (cond
              (and (= :ref.kind/method-call (:ref/kind fact))
                   (not (some-> fact :ref/owner-type (str/starts-with? "java."))))
              (if-let [target (local-method-target method-index
                                                   type-names
                                                   argument-counts
                                                   child-index
                                                   field-index
                                                   ref-index
                                                   decl-return-types
                                                   fact)]
                (let [owner (owner-from-method-decl-id (:decl/id target))]
                  (-> fact
                      (assoc :ref/to-decl (:decl/id target)
                             :ref/resolved? true)
                      (cond-> owner (assoc :ref/owner-type owner))
                      (cond-> (:decl/return-type target) (assoc :ref/to-type (:decl/return-type target)))
                      (dissoc :ref/reason)))
                fact)

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
              (if-let [target (local-field-target field-index type-names fact)]
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

(defn extract-project-facts
  "Read Java file records for project-id from db and return normalized Java facts.

  Java file records must already exist, usually from vibeformer.ingest.source."
  [db project-id]
  (resolve-local-refs (mapcat file-facts (file-records db project-id))))

(defn ingest!
  "Extract normalized Java facts from ingested Java files and transact them."
  [conn {:project/keys [id]}]
  (let [db (d/db conn)
        files (file-records db id)
        facts (resolve-local-refs (mapcat file-facts files))]
    (when (seq facts)
      (d/transact conn {:tx-data facts}))
    {:project/id id
     :java-files (count files)
     :transacted-facts (count facts)}))
