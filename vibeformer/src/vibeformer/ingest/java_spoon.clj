(ns vibeformer.ingest.java-spoon
  (:require [clojure.string :as str]
            [datomic.client.api :as d])
  (:import (java.nio.file Paths)
           (java.security MessageDigest)
           (spoon Launcher)
           (spoon.reflect.code CtArrayRead CtBinaryOperator CtBlock CtExpression CtFieldRead CtIf CtInvocation CtLambda CtLiteral CtLocalVariable CtReturn CtStatement CtSynchronized CtTargetedExpression CtTypeAccess CtVariableRead)
           (spoon.reflect.declaration CtAnnotationType CtClass CtConstructor CtEnum CtExecutable CtField CtInterface CtMethod CtType)
           (spoon.reflect.reference CtExecutableReference CtTypeReference)
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

(defn- qname [value]
  (cond
    (nil? value) nil
    (instance? CtType value) (.getQualifiedName ^CtType value)
    (instance? CtTypeReference value) (.getQualifiedName ^CtTypeReference value)
    :else (str value)))

(defn- type-display-name [^CtTypeReference type-ref]
  (or (qname type-ref) (some-> type-ref str)))

(defn- type-id [^CtTypeReference type-ref]
  (type-display-name type-ref))

(def ^:private built-in-types
  #{"boolean" "byte" "char" "double" "float" "int" "long" "short" "void"})

(defn- type-declaration [^CtTypeReference type-ref]
  (when type-ref
    (try
      (.getTypeDeclaration type-ref)
      (catch Throwable _
        nil))))

(defn- type-reference-resolved? [^CtTypeReference type-ref]
  (let [id (type-id type-ref)]
    (boolean (or (contains? built-in-types id)
                 (type-declaration type-ref)))))

(defn- type-fact [type-ref]
  (when-let [id (type-id type-ref)]
    {:db/id id
     :type/id id
     :type/lang lang
     :type/name id
     :type/nullable? false}))

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
    (instance? CtEnum type) :java.node/enum
    (instance? CtInterface type) :java.node/interface
    (instance? CtClass type) :java.node/class
    :else :java.node/type))

(defn- decl-kind [type]
  (cond
    (instance? CtAnnotationType type) :decl.kind/annotation
    (instance? CtEnum type) :decl.kind/enum
    (instance? CtInterface type) :decl.kind/interface
    (instance? CtClass type) :decl.kind/class
    :else :decl.kind/type))

(defn- type-feature-kind [type]
  (cond
    (instance? CtAnnotationType type) :java.feature/annotation
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

(defn- executable-signature [^CtExecutable executable]
  (cond
    (instance? CtConstructor executable) (.getSignature executable)
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

(defn- type-ref-facts
  ([node-id role type-ref]
   (type-ref-facts node-id role type-ref nil))
  ([node-id role type-ref source-name]
   (when-let [type-fact (type-fact type-ref)]
     (let [resolved? (type-reference-resolved? type-ref)]
       [type-fact
        (cond-> {:ref/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                 :db/id (str node-id ":type-ref:" (name role) ":" (:type/id type-fact))
                 :ref/kind :ref.kind/type-use
                 :ref/from-node node-id
                 :ref/to-type (:type/id type-fact)
                 :ref/name (:type/name type-fact)
                 :ref/role role
                 :ref/resolved? resolved?}
          source-name (assoc :ref/source-name source-name)
          (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))]))))

(defn- inheritance-ref-facts [node-id kind type-ref]
  (when-let [type-fact (type-fact type-ref)]
    (let [resolved? (type-reference-resolved? type-ref)]
      [type-fact
       (cond-> {:ref/id (str node-id ":" (name kind) ":" (:type/id type-fact))
                :db/id (str node-id ":" (name kind) ":" (:type/id type-fact))
                :ref/kind kind
                :ref/from-node node-id
                :ref/to-type (:type/id type-fact)
                :ref/name (:type/name type-fact)
                :ref/resolved? resolved?}
         (not resolved?) (assoc :ref/reason :resolve.reason/missing-classpath))])))

(declare expression-facts statement-facts)

(defn- child-node-id [parent-node-id role ordinal element]
  (str parent-node-id ":" (name role) ":" ordinal ":" (sha256 element)))

(defn- literal-value [^CtLiteral literal]
  (let [value (.getValue literal)]
    (if (nil? value)
      "nil"
      (pr-str value))))

(defn- type-access-name [^CtTypeAccess type-access]
  (or (some-> type-access .getAccessedType qname)
      (some-> type-access .getAccessedType str)))

(defn- binary-operator-name [^CtBinaryOperator expression]
  (-> (.getKind expression) .name str/lower-case (str/replace "_" "-")))

(defn- expression-kind [expression]
  (cond
    (instance? CtInvocation expression) :java.node/method-call
    (instance? CtBinaryOperator expression) :java.node/binary-operator
    (instance? CtArrayRead expression) :java.node/array-read
    (instance? CtFieldRead expression) :java.node/field-read
    (instance? CtVariableRead expression) :java.node/variable-read
    (instance? CtTypeAccess expression) :java.node/type-access
    (instance? CtLiteral expression) :java.node/literal
    :else :java.node/expression))

(defn- expression-name [expression]
  (cond
    (instance? CtInvocation expression)
    (.getSimpleName (.getExecutable ^CtInvocation expression))

    (instance? CtBinaryOperator expression)
    (binary-operator-name expression)

    (instance? CtFieldRead expression)
    (.getSimpleName (.getVariable ^CtFieldRead expression))

    (instance? CtVariableRead expression)
    (.getSimpleName (.getVariable ^CtVariableRead expression))

    (instance? CtTypeAccess expression)
    (type-access-name expression)

    (instance? CtLiteral expression)
    (some-> (.getValue ^CtLiteral expression) class .getSimpleName)

    :else
    (some-> expression class .getSimpleName)))

(defn- expression-value [expression]
  (cond
    (instance? CtLiteral expression) (literal-value expression)
    (instance? CtBinaryOperator expression) (binary-operator-name expression)
    (instance? CtTypeAccess expression) (type-access-name expression)
    :else nil))

(defn- type-facts [file-id ordinal ^CtType type]
  (let [node-id (type-node-id file-id type)
        decl-id (str "java:" (qname type))
        type-id (qname type)
        superclass (.getSuperclass type)
        interfaces (.getSuperInterfaces type)]
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
        (seq (modifiers type)) (assoc :decl/modifiers (modifiers type)))
      (supported-feature (str node-id ":feature:" (name (type-feature-kind type)))
                         (type-feature-kind type)
                         node-id)]
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
        field-type (.getType field)]
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
        field-type (assoc :decl/type (type-id field-type))
        (seq (modifiers field)) (assoc :decl/modifiers (modifiers field)))
      (supported-feature (str node-id ":feature:field") :java.feature/field node-id)]
     (type-ref-facts node-id :field-type field-type)
     (expression-facts file-id node-id :initializer 0 (.getDefaultExpression field))
     (when (package-private? field)
       [(supported-feature (str node-id ":feature:package-private-member")
                           :java.feature/package-private-member
                           node-id)]))))

(defn- executable-feature-facts [node-id executable]
  (concat
   (when (package-private? executable)
     [(supported-feature (str node-id ":feature:package-private-member")
                         :java.feature/package-private-member
                         node-id)])
   (when (has-modifier? executable :synchronized)
     [(unsupported-feature (str node-id ":feature:synchronized-method")
                           :java.feature/synchronized-method
                           node-id
                           :feature.severity/medium)])
   (when (has-modifier? executable :native)
     [(unsupported-feature (str node-id ":feature:native-method")
                           :java.feature/native-method
                           node-id
                           :feature.severity/hard)])
   (when (seq (.getThrownTypes executable))
     [(unsupported-feature (str node-id ":feature:checked-exception")
                           :java.feature/checked-exception
                           node-id
                           :feature.severity/medium)])
   (when (and (instance? CtMethod executable) (seq (.getFormalCtTypeParameters executable)))
     [(supported-feature (str node-id ":feature:generic-method")
                         :java.feature/generic-method
                         node-id)])))

(defn- executable-facts [file-id parent-node-id ordinal executable]
  (let [node-id (executable-node-id file-id executable)
        return-type (when (instance? CtMethod executable) (.getType ^CtMethod executable))
        params (.getParameters executable)]
    (concat
     [(node-fact node-id
                 (if (instance? CtConstructor executable) :java.node/constructor :java.node/method)
                 (.getSimpleName executable)
                 file-id
                 ordinal
                 executable
                 :parent parent-node-id)
      (cond-> {:db/id (executable-decl-id executable)
               :decl/id (executable-decl-id executable)
               :decl/lang lang
               :decl/kind (if (instance? CtConstructor executable)
                            :decl.kind/constructor
                            :decl.kind/method)
               :decl/name (.getSimpleName executable)
               :decl/qualified-name (if (instance? CtConstructor executable)
                                      (qname (.getDeclaringType executable))
                                      (str (qname (.getDeclaringType executable)) "." (.getSimpleName executable)))
               :decl/source-node node-id}
        return-type (assoc :decl/return-type (type-id return-type))
        (instance? CtConstructor executable) (assoc :decl/type (qname (.getDeclaringType executable)))
        (seq (modifiers executable)) (assoc :decl/modifiers (modifiers executable)))]
     (when return-type
       (type-ref-facts node-id :return-type return-type))
     (mapcat (fn [index param]
               (type-ref-facts node-id
                               (keyword (str "param-" index))
                               (.getType param)
                               (.getSimpleName param)))
             (range)
             params)
     (mapcat #(type-ref-facts node-id :throws %) (.getThrownTypes executable))
     (executable-feature-facts node-id executable))))

(defn- constructor-invocation? [^CtInvocation invocation]
  (= "<init>" (.getSimpleName (.getExecutable invocation))))

(defn- invocation-feature-facts [node-id ^CtInvocation invocation]
  (let [executable-ref (.getExecutable invocation)
        owner (some-> executable-ref .getDeclaringType qname)
        name (.getSimpleName executable-ref)]
    (concat
     (when (or (= "java.lang.Class" owner)
               (some-> owner (str/starts-with? "java.lang.reflect"))
               (= "forName" name))
       [(unsupported-feature (str node-id ":feature:reflection")
                             :java.feature/reflection
                             node-id
                             :feature.severity/hard)])
     (when (or (some-> owner (str/starts-with? "java.util.stream"))
               (= "stream" name))
       [(unsupported-feature (str node-id ":feature:stream-api")
                             :java.feature/stream-api
                             node-id
                             :feature.severity/medium)]))))

(defn- invocation-reference-facts [node-id ^CtInvocation invocation]
  (let [executable-ref (.getExecutable invocation)
        target-decl-id (executable-ref-decl-id executable-ref)
        return-type (.getType invocation)
        owner-type (some-> executable-ref .getDeclaringType)
        resolved? (boolean (and target-decl-id
                                (or (nil? owner-type)
                                    (type-reference-resolved? owner-type))))]
    (concat
     (when return-type [(type-fact return-type)])
     (when owner-type [(type-fact owner-type)])
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

(defn- targeted-expression-target [expression]
  (when (instance? CtTargetedExpression expression)
    (.getTarget ^CtTargetedExpression expression)))

(defn- expression-children [expression]
  (cond
    (instance? CtInvocation expression)
    (let [target (targeted-expression-target expression)
          args (.getArguments ^CtInvocation expression)]
      (concat
       (when target [[:target 0 target]])
       (map-indexed (fn [index arg] [:argument index arg]) args)))

    (instance? CtBinaryOperator expression)
    [[:left 0 (.getLeftHandOperand ^CtBinaryOperator expression)]
     [:right 1 (.getRightHandOperand ^CtBinaryOperator expression)]]

    (instance? CtArrayRead expression)
    [[:target 0 (.getTarget ^CtArrayRead expression)]
     [:index 1 (.getIndexExpression ^CtArrayRead expression)]]

    (instance? CtFieldRead expression)
    (when-let [target (targeted-expression-target expression)]
      [[:target 0 target]])

    :else
    []))

(defn- expression-facts [file-id parent-node-id role ordinal ^CtExpression expression]
  (when expression
    (let [node-id (child-node-id parent-node-id role ordinal expression)]
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
       (mapcat (fn [[child-role child-ordinal child-expression]]
                 (expression-facts file-id node-id child-role child-ordinal child-expression))
               (expression-children expression))))))

(defn- branch-statements [statement]
  (cond
    (nil? statement) []
    (instance? CtBlock statement) (.getStatements ^CtBlock statement)
    :else [statement]))

(defn- statement-facts [file-id parent-node-id role ordinal ^CtStatement statement]
  (when statement
    (let [node-id (child-node-id parent-node-id role ordinal statement)]
      (cond
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
    (concat
     (map-indexed (fn [ordinal block]
                    (unsupported-feature (str parent-node-id ":synchronized-block:" ordinal)
                                         :java.feature/synchronized-block
                                         parent-node-id
                                         :feature.severity/medium))
                  (.getElements executable (TypeFilter. CtSynchronized)))
     (map-indexed (fn [ordinal lambda]
                    (supported-feature (str parent-node-id ":lambda:" ordinal)
                                       :java.feature/lambda
                                       parent-node-id))
                  (.getElements executable (TypeFilter. CtLambda))))))

(defn- constructors [type]
  (if (instance? CtClass type)
    (.getConstructors ^CtClass type)
    []))

(defn- file-facts [file-record]
  (let [file-id (:file/id file-record)
        project-root (get-in file-record [:file/project :project/root])
        source-path (.resolve (path project-root) (:file/path file-record))
        types (vec (parse-file source-path))]
    (mapcat
     (fn [type-ordinal type]
       (let [type-node-id (type-node-id file-id type)
             fields (sort-by #(.getSimpleName %) (.getFields type))
             constructors (sort-by #(.getSignature %) (constructors type))
             methods (sort-by #(.getSignature %) (.getMethods type))
             executables (concat constructors methods)]
         (concat
          (type-facts file-id type-ordinal type)
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
  "Read Java file records for project-id from db and return normalized Java facts.

  Java file records must already exist, usually from vibeformer.ingest.source."
  [db project-id]
  (dedupe-facts (mapcat file-facts (file-records db project-id))))

(defn ingest!
  "Extract normalized Java facts from ingested Java files and transact them."
  [conn {:project/keys [id]}]
  (let [db (d/db conn)
        files (file-records db id)
        facts (dedupe-facts (mapcat file-facts files))]
    (when (seq facts)
      (d/transact conn {:tx-data facts}))
    {:project/id id
     :java-files (count files)
     :transacted-facts (count facts)}))
